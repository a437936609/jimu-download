package com.uestcit.download;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * 下载与安装核心：HTTP(S) 下 APK → 优先静默 {@link PackageInstaller} 会话，
 * 失败再退回 ACTION_VIEW（绑系统 PackageInstaller，避开沙发管家）。
 * <p>
 * 未知来源由 {@link UnknownSourcesController} 本机写 AppOps（本包及其它包均可）。
 * 静默依赖平台签 + {@code sharedUserId=system} + {@code INSTALL_PACKAGES}；
 * 753 上若仍弹确认，Receiver 会转发 {@code PENDING_USER_ACTION}。
 */
public final class DownloadInstaller {

    private static final String TAG = "DownloadInstaller";

    /** 公共 Download 副本前缀；安装成功后按同名规则删除。 */
    public static final String PUBLIC_APK_PREFIX = "uestcit-download-";

    /** 系统安装器优先；沙发管家等三方对平台签包常直接「解析失败」。 */
    private static final String[] PREFERRED_INSTALLERS = {
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
    };
    private static final String[] BLOCKED_INSTALLERS = {
            "com.sofa.launcherhd",
    };

    /** 等待 PackageInstaller 终态（对齐 ecarx.upgrade ~120s）。 */
    private static final long INSTALL_WAIT_MS = 120_000L;

    public interface Progress {
        void onProgress(String line);
    }

    private final Context context;
    private final UnknownSourcesController unknownSources;

    public DownloadInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.unknownSources = new UnknownSourcesController(this.context);
    }

    /** 本机未知来源控制器（授权页 / 装包前 ensure 共用）。 */
    public UnknownSourcesController unknownSources() {
        return unknownSources;
    }

    /** 本应用是否已获「安装未知应用」授权（API 26+）。 */
    public boolean canInstallPackages() {
        return unknownSources.canSelfInstallPackages();
    }

    /**
     * 若尚未授权：系统 UID 下本机写 AppOps。
     *
     * @return 人类可读说明
     */
    public String ensureInstallPermission() {
        StringBuilder sb = new StringBuilder();
        boolean before = canInstallPackages();
        sb.append("before canRequestPackageInstalls=").append(before)
                .append(" uid=").append(Process.myUid())
                .append(" pkg=").append(context.getPackageName())
                .append(" appOps=").append(unknownSources.peekSelfAppOpsLabel())
                .append('\n');
        if (before) {
            sb.append("→ 已授权，无需修补\n");
            return sb.toString();
        }
        sb.append(unknownSources.grantSelf());
        return sb.toString();
    }

    /** 先 ensure 再读；装包前调用。 */
    public boolean canInstallPackagesOrEnsure() {
        if (canInstallPackages()) {
            return true;
        }
        ensureInstallPermission();
        return canInstallPackages();
    }

    public String describeInstallPermission() {
        boolean ok = canInstallPackages();
        return "canRequestPackageInstalls=" + ok
                + " package=" + context.getPackageName()
                + " uid=" + Process.myUid()
                + " systemUid=" + unknownSources.isSystemUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + "\nAppOps request_install_packages="
                + unknownSources.peekSelfAppOpsLabel()
                + (ok ? "\n→ 已授权\n"
                : "\n→ 未授权：请在「未知来源」页开启本包\n");
    }

    /**
     * 下载 URL 到应用私有 downloads/，并复制到公共 Download（装成功后由 Receiver 双删）。
     * 进度回调不含 URL / 绝对路径；细节进 logcat。
     *
     * @return 私有目录中的文件
     */
    public File download(String urlStr, String defaultName, Progress progress)
            throws Exception {
        if (urlStr == null) {
            throw new IllegalArgumentException("URL 为空");
        }
        String trimmed = urlStr.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("仅支持 http/https");
        }
        URL url = new URL(trimmed);
        String name = guessFileName(url.getPath(), defaultName);
        File dir = new File(context.getExternalFilesDir(null), "downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 downloads 目录: " + dir);
        }
        File outFile = new File(dir, name);
        Log.i(TAG, "download start url=" + trimmed + " → " + outFile.getAbsolutePath());
        progress.onProgress("正在下载…\n");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        long total = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            long read = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                read += n;
                if (read - lastReport > 512 * 1024 || read == total) {
                    if (total > 0) {
                        progress.onProgress(String.format(Locale.US,
                                "进度 %.0f%%\n",
                                100.0 * read / total));
                    } else {
                        progress.onProgress("正在下载…\n");
                    }
                    lastReport = read;
                }
            }
        } finally {
            conn.disconnect();
        }
        Log.i(TAG, "download done size=" + outFile.length());
        progress.onProgress("下载完成\n");
        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
            assertLooksLikeZipApk(outFile);
        }

        try {
            File pubFile = publicDownloadTwin(name);
            File pub = pubFile.getParentFile();
            if (pub != null && (pub.exists() || pub.mkdirs())) {
                copyFile(outFile, pubFile);
                Log.i(TAG, "copied public Download: " + pubFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "公共 Download 复制跳过: " + e.getMessage());
        }
        return outFile;
    }

    /** 公共 Download 中与私有 APK 对应的副本路径。 */
    public static File publicDownloadTwin(String fileName) {
        File pub = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        return new File(pub, PUBLIC_APK_PREFIX + fileName);
    }

    /**
     * 安装本地 APK（成功后删除源文件与公共副本）。
     * 须在后台线程调用：会阻塞等待 PackageInstaller 终态。
     */
    public String installApk(File apk, Progress progress) {
        return installApk(apk, progress, true);
    }

    /**
     * 安装本地 APK：优先静默 PackageInstaller 并等待终态，失败再 ACTION_VIEW。
     *
     * @param deleteOnSuccess true 时 SUCCESS 后删源文件+公共 Download 副本；
     *                        U 盘源文件须传 false
     */
    public String installApk(File apk, Progress progress, boolean deleteOnSuccess) {
        if (!canInstallPackagesOrEnsure()) {
            String fail = "未授权未知来源，请先在「未知来源」页开启本包\n";
            progress.onProgress(fail);
            return fail;
        }
        if (apk == null || !apk.exists()) {
            String fail = "安装文件不存在\n";
            progress.onProgress(fail);
            return fail;
        }
        try {
            assertLooksLikeZipApk(apk);
        } catch (IllegalStateException e) {
            Log.w(TAG, "assert apk failed", e);
            String fail = "安装包无效\n";
            progress.onProgress(fail);
            return fail;
        }

        progress.onProgress("正在安装…\n");
        try {
            String summary = installViaPackageInstaller(apk, progress, deleteOnSuccess);
            progress.onProgress(summary + "\n");
            return summary + "\n";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String fail = "安装失败：已中断\n";
            progress.onProgress(fail);
            return fail;
        } catch (Exception e) {
            Log.w(TAG, "PackageInstaller 失败，回退 ACTION_VIEW", e);
            progress.onProgress("改用确认安装…\n");
        }

        // ACTION_VIEW：FileProvider 仅覆盖私有/公共 Download；U 盘先拷再装
        File viewApk = apk;
        try {
            viewApk = ensureFileProviderReachable(apk);
        } catch (Exception e) {
            Log.w(TAG, "拷贝到私有 downloads 失败", e);
            String fail = "安装失败：无法准备安装包\n";
            progress.onProgress(fail);
            return fail;
        }
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                viewApk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        List<ResolveInfo> ris = context.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        ResolveInfo chosen = pickInstaller(ris);
        if (chosen != null) {
            intent.setClassName(
                    chosen.activityInfo.packageName, chosen.activityInfo.name);
        }
        try {
            context.startActivity(intent);
            String ok = "已打开安装确认\n";
            progress.onProgress(ok);
            return ok;
        } catch (ActivityNotFoundException e) {
            String fail = "无法打开安装界面\n";
            progress.onProgress(fail);
            return fail;
        } catch (SecurityException e) {
            String fail = "安装权限不足\n";
            progress.onProgress(fail);
            return fail;
        } catch (Exception e) {
            Log.e(TAG, "ACTION_VIEW fail", e);
            String fail = "安装失败\n";
            progress.onProgress(fail);
            return fail;
        }
    }

    /**
     * PackageInstaller Session：commit 后阻塞等待终态。
     *
     * @return 「安装成功」或「安装失败：…」
     */
    private String installViaPackageInstaller(File apk, Progress progress,
                                              boolean deleteOnSuccess)
            throws Exception {
        PackageInstaller installer =
                context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 26) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        params.setSize(apk.length());
        String userAction = trySetRequireUserActionFalse(params);
        Log.i(TAG, "setRequireUserAction(false): " + userAction);

        int sessionId = installer.createSession(params);
        Log.i(TAG, "sessionId=" + sessionId + " size=" + apk.length()
                + " uid=" + Process.myUid()
                + " deleteOnSuccess=" + deleteOnSuccess);
        InstallResultReceiver.Waiter waiter = InstallResultReceiver.register(sessionId);
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite(
                         "base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent status = new Intent(context, InstallResultReceiver.class);
            status.setAction(InstallResultReceiver.ACTION_INSTALL_STATUS);
            // 仅下载落盘包在 SUCCESS 后删除；U 盘源不塞路径
            if (deleteOnSuccess) {
                status.putExtra(InstallResultReceiver.EXTRA_APK_PATH,
                        apk.getAbsolutePath());
                File twin = publicDownloadTwin(apk.getName());
                status.putExtra(InstallResultReceiver.EXTRA_PUBLIC_APK_PATH,
                        twin.getAbsolutePath());
            }
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                piFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, sessionId, status, piFlags);
            session.commit(pending.getIntentSender());
            committed = true;
        } catch (Exception e) {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // 放弃失败不掩盖原错误
                }
            }
            InstallResultReceiver.unregister(sessionId);
            throw e;
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        Log.i(TAG, "PackageInstaller committed " + apk.getName());
        progress.onProgress("正在安装…\n");
        try {
            boolean ok = InstallResultReceiver.await(waiter, INSTALL_WAIT_MS);
            boolean timedOut = !InstallResultReceiver.wasSignaled(waiter);
            String summary = InstallStatusFormatter.formatSummary(
                    ok, waiter.status, waiter.message, timedOut);
            Log.i(TAG, "install done ok=" + ok + " " + summary);
            return summary;
        } finally {
            InstallResultReceiver.unregister(sessionId);
        }
    }

    /**
     * ACTION_VIEW 回退：若文件不在 FileProvider 覆盖范围内，拷到私有 downloads。
     */
    private File ensureFileProviderReachable(File apk) throws Exception {
        File privateDir = new File(context.getExternalFilesDir(null), "downloads");
        String path = apk.getAbsolutePath();
        if (privateDir != null && path.startsWith(privateDir.getAbsolutePath())) {
            return apk;
        }
        File pub = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (pub != null && path.startsWith(pub.getAbsolutePath())) {
            return apk;
        }
        if (privateDir != null && !privateDir.exists() && !privateDir.mkdirs()) {
            throw new IllegalStateException("无法创建 downloads");
        }
        String name = ApkFileNames.normalizeInstallFileName(apk.getName());
        File dest = new File(privateDir, name);
        copyFile(apk, dest);
        Log.i(TAG, "copied for FileProvider: " + dest.getName());
        return dest;
    }

    /**
     * 尝试关闭「需要用户操作」。成功返回 ok；方法不存在或失败返回原因（不抛）。
     */
    private static String trySetRequireUserActionFalse(
            PackageInstaller.SessionParams params) {
        try {
            // API 31: setRequireUserAction(int)
            // USER_ACTION_NOT_REQUIRED = 2
            Method m = PackageInstaller.SessionParams.class.getMethod(
                    "setRequireUserAction", int.class);
            m.invoke(params, 2);
            return "ok(int=2)";
        } catch (NoSuchMethodException e) {
            try {
                Method m = PackageInstaller.SessionParams.class.getMethod(
                        "setRequireUserAction", boolean.class);
                m.invoke(params, false);
                return "ok(boolean=false)";
            } catch (Exception e2) {
                return "skip: " + e2.getClass().getSimpleName();
            }
        } catch (Exception e) {
            return "fail: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static ResolveInfo pickInstaller(List<ResolveInfo> ris) {
        if (ris == null || ris.isEmpty()) {
            return null;
        }
        for (String pref : PREFERRED_INSTALLERS) {
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null
                        && pref.equals(ri.activityInfo.packageName)
                        && !isBlockedInstaller(ri.activityInfo.packageName)) {
                    return ri;
                }
            }
        }
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo != null
                    && !isBlockedInstaller(ri.activityInfo.packageName)) {
                return ri;
            }
        }
        return null;
    }

    private static boolean isBlockedInstaller(String pkg) {
        if (pkg == null) {
            return true;
        }
        for (String b : BLOCKED_INSTALLERS) {
            if (b.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String guessFileName(String path, String defaultName) {
        if (path != null) {
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            int q = base.indexOf('?');
            if (q >= 0) {
                base = base.substring(0, q);
            }
            if (!base.isEmpty() && base.contains(".")) {
                return sanitize(base);
            }
        }
        return sanitize(defaultName);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
    }

    /**
     * 粗检 ZIP/APK 头（PK\\x03\\x04）。网关软 404 回 HTML 时提前拦住。
     */
    static void assertLooksLikeZipApk(File file) throws IllegalStateException {
        if (file == null || !file.isFile() || file.length() < 4) {
            throw new IllegalStateException(
                    "不是有效 APK（文件过小或不存在）: " + file);
        }
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(magic) != 4) {
                throw new IllegalStateException("无法读取文件头: " + file.getName());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取文件头失败: " + e.getMessage());
        }
        if (magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
            throw new IllegalStateException(
                    "不是 APK/ZIP（文件头非 PK\\x03\\x04，可能是 HTML/404 页） size="
                            + file.length() + " name=" + file.getName());
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
