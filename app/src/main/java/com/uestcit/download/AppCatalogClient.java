package com.uestcit.download;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拉取服务端应用清单 {@code apps.json}，并对照本机已装版本给出安装状态。
 * 约定见仓库 docs/apps.json.example。
 */
public final class AppCatalogClient {

    /** 官网静态站 apps 目录（HTTPS）。 */
    public static final String DEFAULT_BASE = "https://jimu.uestcit.com/apps/";

    private final Context context;

    public AppCatalogClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 清单中的一条应用。 */
    public static final class AppEntry {
        /** 清单内稳定 id（可选）。 */
        public final String id;
        public final String name;
        public final String packageName;
        public final String versionName;
        public final int versionCode;
        /** 已解析的 APK 绝对 URL。 */
        public final String apkUrl;
        public final String desc;
        /** 相对本机：未安装 / 可更新 / 已是最新。 */
        public final InstallState installState;
        public final int localVersionCode;
        public final String localVersionName;

        AppEntry(String id, String name, String packageName, String versionName,
                 int versionCode, String apkUrl, String desc,
                 InstallState installState, int localVersionCode,
                 String localVersionName) {
            this.id = id;
            this.name = name;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.desc = desc;
            this.installState = installState;
            this.localVersionCode = localVersionCode;
            this.localVersionName = localVersionName;
        }
    }

    /** 相对本机安装状态。 */
    public enum InstallState {
        NOT_INSTALLED,
        UPDATE_AVAILABLE,
        UP_TO_DATE
    }

    /**
     * GET {@code {base}/apps.json} 并解析。须在后台线程调用。
     *
     * @param baseUrl 清单目录，如 {@code http://host/apps/}
     */
    public List<AppEntry> fetch(String baseUrl) throws Exception {
        String base = normalizeBase(baseUrl);
        String url = base + "apps.json";
        String json = httpGetText(url);
        JSONObject root = new JSONObject(json);
        JSONArray apps = root.optJSONArray("apps");
        if (apps == null) {
            throw new IllegalStateException("apps.json 缺少 apps 数组");
        }
        List<AppEntry> out = new ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String packageName = o.optString("packageName", "").trim();
            String apkField = o.optString("apk", "").trim();
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(apkField)) {
                continue;
            }
            String id = o.optString("id", packageName).trim();
            String name = o.optString("name", packageName).trim();
            String versionName = o.optString("versionName", "").trim();
            int versionCode = o.optInt("versionCode", -1);
            String desc = o.optString("desc", "").trim();
            String apkUrl = resolveApkUrl(base, apkField);

            int localCode = -1;
            String localName = null;
            try {
                PackageInfo pi = context.getPackageManager()
                        .getPackageInfo(packageName, 0);
                localCode = pi.versionCode;
                localName = pi.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 未安装
            }

            InstallState state;
            if (localCode < 0) {
                state = InstallState.NOT_INSTALLED;
            } else if (versionCode >= 0 && versionCode > localCode) {
                state = InstallState.UPDATE_AVAILABLE;
            } else if (versionCode < 0 && !TextUtils.isEmpty(versionName)
                    && !TextUtils.isEmpty(localName)
                    && UpdateChecker.compareVersionName(versionName, localName) > 0) {
                state = InstallState.UPDATE_AVAILABLE;
            } else {
                state = InstallState.UP_TO_DATE;
            }

            out.add(new AppEntry(id, name, packageName, versionName, versionCode,
                    apkUrl, desc, state, localCode, localName));
        }
        return out;
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

    static String resolveApkUrl(String base, String apkField) {
        if (apkField.startsWith("http://") || apkField.startsWith("https://")) {
            return apkField;
        }
        return base + apkField.replaceFirst("^/+", "");
    }

    /** 安装按钮文案。 */
    public static String actionLabel(InstallState state) {
        switch (state) {
            case NOT_INSTALLED:
                return "安装";
            case UPDATE_AVAILABLE:
                return "更新";
            case UP_TO_DATE:
            default:
                return "重装";
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
                    String.format(Locale.US, "HTTP %d 拉取 apps.json 失败: %s",
                            code, summarizeHttpBody(body)));
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

    /** 错误体压成单行短摘要，避免把 HTML 404 页刷进车机日志。 */
    static String summarizeHttpBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String one = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (one.length() > 80) {
            return one.substring(0, 80) + "…";
        }
        return one;
    }
}
