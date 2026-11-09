package com.uestcit.download;

import android.app.Activity;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

/**
 * 各装包入口共用的安装结果对话框（横屏车机可读）。
 */
final class InstallResultUi {

    private InstallResultUi() {
    }

    /**
     * 根据 {@link DownloadInstaller#installApk} 返回文案弹结果框。
     * 「已打开安装确认」用中性标题（系统 UI 终态未知）。
     */
    static void showFromInstallResult(Activity activity, String result) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String summary = result != null ? result.replace('\n', ' ').trim() : "";
        if (summary.contains("已打开安装确认")) {
            show(activity, activity.getString(R.string.install_result_confirm_title),
                    summary.isEmpty()
                            ? activity.getString(R.string.install_result_confirm_title)
                            : summary);
            return;
        }
        boolean ok = looksSuccessful(result);
        show(activity, ok, summary);
    }

    /**
     * 弹出成功/失败结果；{@code activity} 已销毁则忽略。
     *
     * @param summary 已面向用户的摘要（可含「安装成功」/「安装失败：…」）
     */
    static void show(Activity activity, boolean ok, String summary) {
        String title = ok ? activity.getString(R.string.install_result_ok_title)
                : activity.getString(R.string.install_result_fail_title);
        String msg = summary != null ? summary.trim() : "";
        if (msg.isEmpty()) {
            msg = title;
        }
        show(activity, title, msg);
    }

    private static void show(Activity activity, String title, String message) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.app_dlg_ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                .show();
    }

    /** 根据返回文案粗判是否成功（DownloadInstaller 约定前缀）。 */
    static boolean looksSuccessful(String result) {
        if (result == null) {
            return false;
        }
        return result.trim().startsWith("安装成功");
    }
}
