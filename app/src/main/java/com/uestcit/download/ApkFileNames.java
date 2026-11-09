package com.uestcit.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 识别微信等另存后的 APK 文件名变形，并以 ZIP 魔数校验内容。
 * 例：{@code foo.apk.1}、{@code foo(1).apk}。
 */
final class ApkFileNames {

    /** {@code .apk} 或 {@code .apk.数字}（忽略大小写）。 */
    private static final Pattern APK_NAME = Pattern.compile(
            "(?i)^.+\\.apk(\\.\\d+)?$");

    private ApkFileNames() {
    }

    /** 文件名是否像 APK（含微信 {@code .apk.1} 等）。 */
    static boolean looksLikeApkName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (APK_NAME.matcher(base).matches()) {
            return true;
        }
        // foo (1).apk / foo(1).apk 已由上面覆盖；再剥末尾 .数字 后看扩展名
        String stripped = base.replaceAll("(?i)(\\.\\d+)+$", "");
        return stripped.toLowerCase(Locale.US).endsWith(".apk");
    }

    /**
     * 安装前规范文件名：去掉 {@code .apk} 后多余序号，保证以 {@code .apk} 结尾。
     */
    static String normalizeInstallFileName(String original) {
        String base = original == null ? "upload.apk" : original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff() ]", "_");
        if (base.isEmpty()) {
            return "upload.apk";
        }
        // foo.apk.1 → foo.apk
        base = base.replaceAll("(?i)(\\.apk)(\\.\\d+)+$", "$1");
        if (!base.toLowerCase(Locale.US).endsWith(".apk")) {
            base = base + ".apk";
        }
        return base;
    }

    /** 文件头是否为 ZIP/APK（{@code PK\\x03\\x04}）。 */
    static boolean hasZipApkMagic(File file) {
        if (file == null || !file.isFile() || file.length() < 4) {
            return false;
        }
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(magic) != 4) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
    }
}
