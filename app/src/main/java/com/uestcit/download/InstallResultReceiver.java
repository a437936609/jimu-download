package com.uestcit.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 接收 {@link PackageInstaller} 会话结果：可阻塞等待指定 session，
 * 并向 UI 广播摘要；SUCCESS 时按 Intent extra 删除落地包。
 * <p>
 * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} 只拉确认界面，不当终态
 * （对齐 ecarx.upgrade）。
 */
public final class InstallResultReceiver extends BroadcastReceiver {

    /**
     * PackageInstaller PendingIntent 回调 action（仅本 Receiver 清单注册）。
     * 与 {@link #ACTION_INSTALL_UI} 分离，避免再广播时把自己打进死循环。
     */
    public static final String ACTION_INSTALL_STATUS =
            "com.uestcit.download.INSTALL_STATUS";

    /** 给 Activity 动态注册的 UI 通知 action。 */
    public static final String ACTION_INSTALL_UI =
            "com.uestcit.download.INSTALL_UI";

    /** 人类可读状态摘要。 */
    public static final String EXTRA_STATUS_TEXT =
            "com.uestcit.download.EXTRA_STATUS_TEXT";

    /** 私有 downloads 内 APK 绝对路径（装成功后删除；U 盘源勿传）。 */
    public static final String EXTRA_APK_PATH =
            "com.uestcit.download.EXTRA_APK_PATH";

    /** 公共 Download 副本绝对路径（装成功后删除）。 */
    public static final String EXTRA_PUBLIC_APK_PATH =
            "com.uestcit.download.EXTRA_PUBLIC_APK_PATH";

    private static final String TAG = "InstallResult";

    /** sessionId → 等待者；由 {@link DownloadInstaller} 在 commit 前注册。 */
    private static final ConcurrentHashMap<Integer, Waiter> WAITERS =
            new ConcurrentHashMap<>();

    /** 单次安装会话等待槽。 */
    public static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int status = PackageInstaller.STATUS_FAILURE;
        volatile String message = "";
    }

    /** commit 前注册；返回的 Waiter 用于 {@link #await}。 */
    public static Waiter register(int sessionId) {
        Waiter w = new Waiter();
        WAITERS.put(sessionId, w);
        return w;
    }

    /** 超时或结束后务必注销，避免泄漏。 */
    public static void unregister(int sessionId) {
        WAITERS.remove(sessionId);
    }

    /**
     * 阻塞至会话终态或超时。
     *
     * @return true 表示 STATUS_SUCCESS
     */
    public static boolean await(Waiter waiter, long timeoutMs)
            throws InterruptedException {
        if (waiter == null) {
            return false;
        }
        boolean signaled = waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!signaled) {
            return false;
        }
        return waiter.status == PackageInstaller.STATUS_SUCCESS;
    }

    /** 是否已收到终态回调（区分超时与明确失败）。 */
    public static boolean wasSignaled(Waiter waiter) {
        return waiter != null && waiter.latch.getCount() == 0;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (!ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        Log.i(TAG, "session=" + sessionId
                + " status=" + InstallStatusFormatter.statusName(status)
                + " pkg=" + pkg
                + (msg != null ? " msg=" + msg : ""));

        // 静默失败时系统可能要求确认：拉起界面，继续等最终 SUCCESS/FAILURE
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    Log.i(TAG, "转发 PENDING_USER_ACTION 确认界面");
                } catch (Exception e) {
                    Log.w(TAG, "无法打开确认界面: " + e.getMessage());
                    signalFailure(sessionId, "无法拉起安装确认界面");
                }
            }
            return;
        }

        Waiter w = sessionId >= 0 ? WAITERS.get(sessionId) : null;
        if (w != null) {
            w.status = status;
            w.message = msg != null ? msg : "";
            w.latch.countDown();
        }

        // 仅成功后清包；失败保留便于重试；ACTION_VIEW 路径不经此
        if (status == PackageInstaller.STATUS_SUCCESS) {
            deleteExtraPath(intent.getStringExtra(EXTRA_APK_PATH));
            deleteExtraPath(intent.getStringExtra(EXTRA_PUBLIC_APK_PATH));
        }

        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        String uiSummary = InstallStatusFormatter.formatSummary(
                ok, status, msg, false);
        Intent broadcast = new Intent(ACTION_INSTALL_UI);
        broadcast.setPackage(context.getPackageName());
        broadcast.putExtra(EXTRA_STATUS_TEXT, uiSummary);
        broadcast.putExtra(PackageInstaller.EXTRA_STATUS, status);
        context.sendBroadcast(broadcast);
    }

    private static void signalFailure(int sessionId, String message) {
        Waiter w = sessionId >= 0 ? WAITERS.get(sessionId) : null;
        if (w != null) {
            w.status = PackageInstaller.STATUS_FAILURE;
            w.message = message;
            w.latch.countDown();
        }
    }

    /** 删除 extra 指向的文件；失败只打日志。 */
    private static void deleteExtraPath(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            return;
        }
        if (f.delete()) {
            Log.i(TAG, "deleted after install: " + f.getName());
        } else {
            Log.w(TAG, "delete failed: " + path);
        }
    }
}
