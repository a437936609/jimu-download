package com.uestcit.download;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 本包自更新：GET {@code {base}/latest.json}，与本机 versionCode/versionName 比较。
 * 默认目录与 intent-probe 的 version/ 分离，避免互相覆盖。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** 本包自更新目录（与 apps/ 清单分离；官网 download/）。 */
    public static final String DEFAULT_BASE = "https://jimu.uestcit.com/download/";

    private final Context context;

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 一次检测结果（含本机与远端）。 */
    public static final class UpdateInfo {
        public final String localVersionName;
        public final int localVersionCode;
        public final String remoteVersionName;
        public final int remoteVersionCode;
        /** 已解析的 APK 绝对 URL。 */
        public final String apkUrl;
        public final boolean updateAvailable;
        public final String message;

        public UpdateInfo(String localVersionName, int localVersionCode,
                          String remoteVersionName, int remoteVersionCode,
                          String apkUrl, boolean updateAvailable, String message) {
            this.localVersionName = localVersionName;
            this.localVersionCode = localVersionCode;
            this.remoteVersionName = remoteVersionName;
            this.remoteVersionCode = remoteVersionCode;
            this.apkUrl = apkUrl;
            this.updateAvailable = updateAvailable;
            this.message = message;
        }
    }

    public PackageInfo localPackageInfo() throws PackageManager.NameNotFoundException {
        return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
    }

    /**
     * 拉取 latest.json 并比较。须在后台线程调用。
     *
     * @param baseUrl 更新目录，如 {@code http://host/download/}（可无尾斜杠）
     */
    public UpdateInfo check(String baseUrl) throws Exception {
        PackageInfo local = localPackageInfo();
        String localName = local.versionName != null ? local.versionName : "?";
        int localCode = local.versionCode;

        String base = normalizeBase(baseUrl);
        String latestUrl = base + "latest.json";
        String json = httpGetText(latestUrl);
        JSONObject o = new JSONObject(json);
        String remoteName = o.optString("versionName", "").trim();
        int remoteCode = o.optInt("versionCode", -1);
        String apkField = o.optString("apk", "").trim();
        if (TextUtils.isEmpty(remoteName) && remoteCode < 0) {
            throw new IllegalStateException("latest.json 缺少 versionName/versionCode");
        }
        if (TextUtils.isEmpty(remoteName)) {
            remoteName = String.valueOf(remoteCode);
        }
        String apkUrl = resolveApkUrl(base, apkField, remoteName);

        boolean newer;
        String how;
        if (remoteCode >= 0) {
            newer = remoteCode > localCode;
            how = "by versionCode";
        } else {
            newer = compareVersionName(remoteName, localName) > 0;
            how = "by versionName";
        }

        // 完整 URL 仅 logcat；UI message 不含服务器地址/文件名
        Log.i(TAG, "latestUrl=" + latestUrl
                + " apkUrl=" + apkUrl
                + " local=" + localName + "(" + localCode + ")"
                + " remote=" + remoteName + "(" + remoteCode + ")"
                + " compare=" + how
                + " newer=" + newer);

        StringBuilder msg = new StringBuilder();
        msg.append("本机 ").append(localName).append(" (").append(localCode).append(")\n");
        msg.append("远端 ").append(remoteName).append(" (")
                .append(remoteCode >= 0 ? remoteCode : "n/a").append(")\n");
        if (newer) {
            msg.append("有新版本可用");
        } else {
            msg.append("已是最新");
        }

        return new UpdateInfo(localName, localCode, remoteName, remoteCode,
                apkUrl, newer, msg.toString());
    }

    static String normalizeBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (TextUtils.isEmpty(b)) {
            b = DEFAULT_BASE;
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            throw new IllegalArgumentException("Base 须为 http/https");
        }
        if (!b.endsWith("/")) {
            b = b + "/";
        }
        return b;
    }

    static String resolveApkUrl(String base, String apkField, String versionName) {
        if (!TextUtils.isEmpty(apkField)) {
            if (apkField.startsWith("http://") || apkField.startsWith("https://")) {
                return apkField;
            }
            return base + apkField.replaceFirst("^/+", "");
        }
        return base + versionName + ".apk";
    }

    /** @return &gt;0 若 a&gt;b；0 相等；&lt;0 若 a&lt;b */
    static int compareVersionName(String a, String b) {
        String[] pa = splitVer(a);
        String[] pb = splitVer(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }

    private static String[] splitVer(String v) {
        if (v == null) {
            return new String[]{"0"};
        }
        return v.trim().split("[^0-9]+");
    }

    private static int parsePart(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String httpGetText(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.connect();
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream();
        String body = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException(
                    String.format(Locale.US, "HTTP %d 拉取 latest.json 失败: %s",
                            code, AppCatalogClient.summarizeHttpBody(body)));
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
