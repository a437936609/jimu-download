package com.uestcit.download;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * SoftAp 开关与只读配置（隐藏 API 反射）。
 * <p>
 * 对齐 ecarx.settings {@code APSupport.enableSoftAP(null, on/off)}：753 / API 28 上
 * 开热点用 {@code WifiManager.startSoftAp(null)}，关热点用 {@code stopSoftAp}。
 * 不写 {@code setWifiApConfiguration}，避免改动原车 SSID/密码。
 * AdaptAPI {@code WifiAp} 无开关方法，不能用来启停 SoftAp。
 */
final class SoftApController {

    private static final String TAG = "SoftApController";

    /** WIFI_AP_STATE_ENABLED（隐藏常量，一般为 13）。 */
    private static final int WIFI_AP_STATE_ENABLED = 13;

    private final WifiManager wifiManager;

    SoftApController(Context context) {
        wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /** 当前 SoftAp 是否已启用。 */
    boolean isApEnabled() {
        if (wifiManager == null) {
            return false;
        }
        try {
            Method m = wifiManager.getClass().getMethod("isWifiApEnabled");
            Object r = m.invoke(wifiManager);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            try {
                Method m = wifiManager.getClass().getMethod("getWifiApState");
                Object r = m.invoke(wifiManager);
                if (r instanceof Integer) {
                    return ((Integer) r) == WIFI_AP_STATE_ENABLED;
                }
            } catch (Exception e2) {
                Log.w(TAG, "isApEnabled fail: " + e2.getMessage());
            }
            return false;
        }
    }

    /** 读取当前热点配置（只读展示用）；失败返回 null。 */
    WifiConfiguration getApConfiguration() {
        if (wifiManager == null) {
            return null;
        }
        try {
            Method m = wifiManager.getClass().getMethod("getWifiApConfiguration");
            Object r = m.invoke(wifiManager);
            return r instanceof WifiConfiguration ? (WifiConfiguration) r : null;
        } catch (Exception e) {
            Log.w(TAG, "getWifiApConfiguration: " + e.getMessage());
            return null;
        }
    }

    /**
     * 开关 SoftAp，始终沿用系统已存配置（对齐原厂传 {@code null}）。
     * <p>
     * 753 走 {@code startSoftAp}/{@code stopSoftAp}；反射未抛错且布尔返回非 false 视为调用成功
     * （不保证瞬时已 ENABLED，需听广播）。
     *
     * @return 反射调用是否成功
     */
    boolean setApEnabled(boolean enabled) {
        if (wifiManager == null) {
            return false;
        }
        // 部分 SoftAp 栈与 STA 互斥；失败不阻断后续 startSoftAp
        if (enabled) {
            try {
                //noinspection deprecation
                wifiManager.setWifiEnabled(false);
            } catch (Exception e) {
                Log.w(TAG, "setWifiEnabled(false): " + e.getMessage());
            }
        }
        try {
            if (enabled) {
                // 原厂 WifiAPManager.setAPEnable → enableSoftAP(null, true)
                Method m = wifiManager.getClass().getMethod(
                        "startSoftAp", WifiConfiguration.class);
                Object r = m.invoke(wifiManager, (WifiConfiguration) null);
                boolean ok = !(r instanceof Boolean) || (Boolean) r;
                Log.i(TAG, "startSoftAp(null)=" + r);
                return ok;
            }
            // 原厂 APSupport 对 stopSoftAp 使用 getDeclaredMethod
            Method m = wifiManager.getClass().getDeclaredMethod("stopSoftAp");
            m.setAccessible(true);
            Object r = m.invoke(wifiManager);
            boolean ok = !(r instanceof Boolean) || (Boolean) r;
            Log.i(TAG, "stopSoftAp=" + r);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, (enabled ? "startSoftAp" : "stopSoftAp") + ": " + e.getMessage());
            return false;
        }
    }

    /** 配置里可读的 SSID（去掉引号）；无则返回「未知」。 */
    static String displaySsid(WifiConfiguration conf) {
        if (conf == null || conf.SSID == null || conf.SSID.isEmpty()) {
            return "未知";
        }
        return stripQuotes(conf.SSID);
    }

    /**
     * 配置里可读的热点密码（去掉引号）；读不到返回空串，由 UI 提示去系统设置查看。
     */
    static String displayPassword(WifiConfiguration conf) {
        if (conf == null || conf.preSharedKey == null || conf.preSharedKey.isEmpty()) {
            return "";
        }
        return stripQuotes(conf.preSharedKey);
    }

    /** 去掉 WifiConfiguration 字段两侧的双引号。 */
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
