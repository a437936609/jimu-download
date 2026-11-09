package com.uestcit.download;

import android.content.pm.PackageInstaller;
import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link PackageInstaller} 终态整理成可展示原因（优先 {@code INSTALL_*}）。
 * 对齐 ota-apks/ecarx.upgrade 同名工具，供结果对话框使用。
 */
public final class InstallStatusFormatter {

    /** 从系统 STATUS_MESSAGE 抽出 INSTALL_FAILED_* / INSTALL_PARSE_* 等。 */
    private static final Pattern INSTALL_TOKEN =
            Pattern.compile("INSTALL_[A-Z0-9_]+");

    private InstallStatusFormatter() {
    }

    /**
     * @param status   {@link PackageInstaller} EXTRA_STATUS
     * @param message  EXTRA_STATUS_MESSAGE（常含 INSTALL_FAILED_*）
     * @param timedOut 等待回调超时
     * @return 不含 URL/路径的原因片段
     */
    public static String formatReason(int status, String message, boolean timedOut) {
        if (timedOut) {
            return "超时（未收到安装回调）";
        }
        String msg = message != null ? message.trim() : "";
        Matcher m = INSTALL_TOKEN.matcher(msg);
        if (m.find()) {
            return m.group();
        }
        String statusName = statusName(status);
        if (!TextUtils.isEmpty(msg)) {
            String cleaned = stripNoise(msg);
            if (!cleaned.isEmpty()) {
                if (cleaned.length() > 96) {
                    cleaned = cleaned.substring(0, 93) + "…";
                }
                return statusName + " — " + cleaned;
            }
        }
        return statusName;
    }

    /** 成功/失败面向用户的整句摘要。 */
    public static String formatSummary(boolean ok, int status, String message,
                                       boolean timedOut) {
        if (ok) {
            return "安装成功";
        }
        return "安装失败：" + formatReason(status, message, timedOut);
    }

    /** PackageInstaller status 常量名 + 数值。 */
    public static String statusName(int status) {
        String name;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                name = "STATUS_SUCCESS";
                break;
            case PackageInstaller.STATUS_FAILURE:
                name = "STATUS_FAILURE";
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                name = "STATUS_FAILURE_ABORTED";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                name = "STATUS_FAILURE_BLOCKED";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                name = "STATUS_FAILURE_CONFLICT";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                name = "STATUS_FAILURE_INCOMPATIBLE";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                name = "STATUS_FAILURE_INVALID";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                name = "STATUS_FAILURE_STORAGE";
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                name = "STATUS_PENDING_USER_ACTION";
                break;
            default:
                name = "STATUS_UNKNOWN";
                break;
        }
        return String.format(Locale.US, "%s (%d)", name, status);
    }

    private static String stripNoise(String s) {
        return s.replaceAll("(?i)https?://\\S+", "")
                .replaceAll("(?i)/data/\\S+", "")
                .replaceAll("(?i)/storage/\\S+", "")
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
