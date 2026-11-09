package com.uestcit.download.perm;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知监听（NotificationListener）与勿扰策略访问的读写。
 * <p>
 * API 28 无 {@code POST_NOTIFICATIONS} 运行时权限；监听名单在
 * Secure {@code enabled_notification_listeners}（SDK 常量 @hide，用字面量），勿扰用
 * {@link NotificationManager} 隐藏 API {@code setNotificationPolicyAccessGranted}。
 */
public final class NotificationAccessHelper {

    /** {@link android.service.notification.NotificationListenerService} 的 action。 */
    private static final String ACTION_NLS =
            "android.service.notification.NotificationListenerService";

    /** Secure 键名（平台 @hide 常量，compileSdk 无公开字段）。 */
    private static final String KEY_ENABLED_NLS = "enabled_notification_listeners";

    private final Context context;

    public NotificationAccessHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 该包是否声明了 NotificationListenerService。 */
    public boolean hasNotificationListener(String packageName) {
        return !listListenerComponents(packageName).isEmpty();
    }

    /** 包内全部 NLS 组件是否都已写入 Secure 名单（有一个算开启）。 */
    public boolean isListenerEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        Set<ComponentName> enabled = readEnabledListeners();
        for (ComponentName cn : listListenerComponents(packageName)) {
            if (enabled.contains(cn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 开启：把该包全部 NLS 组件写入名单；关闭：移除该包相关项。
     *
     * @return 人类可读结果
     */
    public String setListenerEnabled(String packageName, boolean enable) {
        if (TextUtils.isEmpty(packageName)) {
            return "失败: 包名为空";
        }
        List<ComponentName> components = listListenerComponents(packageName);
        if (components.isEmpty()) {
            return "失败: 无 NotificationListenerService";
        }
        Set<ComponentName> enabled = readEnabledListeners();
        if (enable) {
            enabled.addAll(components);
        } else {
            for (ComponentName cn : components) {
                enabled.remove(cn);
            }
        }
        if (!writeEnabledListeners(enabled)) {
            return "失败: 无法写入 enabled_notification_listeners（需 WRITE_SECURE_SETTINGS）";
        }
        return (enable ? "已允许「通知使用权」 " : "已拒绝「通知使用权」 ") + packageName;
    }

    /** 是否已授予勿扰 / 通知策略访问。 */
    public boolean isPolicyAccessGranted(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            Method m = NotificationManager.class.getMethod(
                    "isNotificationPolicyAccessGrantedForPackage", String.class);
            Object v = m.invoke(nm, packageName);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            // 旧路径：仅能查「本包」
            try {
                NotificationManager nm =
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && packageName.equals(context.getPackageName())) {
                    return nm.isNotificationPolicyAccessGranted();
                }
            } catch (Throwable ignored) {
                // ignore
            }
            return false;
        }
    }

    /**
     * 授予/撤销勿扰策略访问（隐藏 API，需 system）。
     *
     * @return 人类可读结果
     */
    public String setPolicyAccessGranted(String packageName, boolean grant) {
        if (TextUtils.isEmpty(packageName)) {
            return "失败: 包名为空";
        }
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return "失败: NotificationManager 不可用";
            }
            Method m = NotificationManager.class.getMethod(
                    "setNotificationPolicyAccessGranted", String.class, boolean.class);
            m.invoke(nm, packageName, grant);
            boolean now = isPolicyAccessGranted(packageName);
            if (grant && !now) {
                return "已调用 setNotificationPolicyAccessGranted，但回读仍未允许";
            }
            return (grant ? "已允许「勿扰权限」 " : "已拒绝「勿扰权限」 ") + packageName;
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            String msg = c.getMessage();
            return "勿扰权限失败: " + c.getClass().getSimpleName()
                    + (TextUtils.isEmpty(msg) ? "" : (": " + msg));
        }
    }

    /** 同步 SystemUI 的「应用通知」开关（与 OP_POST_NOTIFICATION 配套）。 */
    public void setNotificationsEnabledForPackage(String packageName, int uid, boolean enabled) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Method m = NotificationManager.class.getMethod(
                    "setNotificationsEnabledForPackage", String.class, int.class, boolean.class);
            m.invoke(nm, packageName, uid, enabled);
        } catch (Throwable ignored) {
            // AppOps 仍是主路径
        }
    }

    /** 读 SystemUI 侧通知是否开启；失败返回 null。 */
    public Boolean areNotificationsEnabledForPackage(String packageName, int uid) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return null;
            }
            Method m = NotificationManager.class.getMethod(
                    "areNotificationsEnabledForPackage", String.class, int.class);
            Object v = m.invoke(nm, packageName, uid);
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private List<ComponentName> listListenerComponents(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return Collections.emptyList();
        }
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(ACTION_NLS);
        intent.setPackage(packageName);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentServices(intent, PackageManager.GET_SERVICES);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (resolved == null || resolved.isEmpty()) {
            return Collections.emptyList();
        }
        List<ComponentName> out = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || !packageName.equals(si.packageName)) {
                continue;
            }
            out.add(new ComponentName(si.packageName, si.name));
        }
        return out;
    }

    private Set<ComponentName> readEnabledListeners() {
        Set<ComponentName> out = new HashSet<>();
        String raw;
        try {
            raw = Settings.Secure.getString(
                    context.getContentResolver(), KEY_ENABLED_NLS);
        } catch (Throwable t) {
            return out;
        }
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String part : raw.split(":")) {
            if (TextUtils.isEmpty(part)) {
                continue;
            }
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null) {
                out.add(cn);
            }
        }
        return out;
    }

    private boolean writeEnabledListeners(Set<ComponentName> enabled) {
        StringBuilder sb = new StringBuilder();
        List<ComponentName> sorted = new ArrayList<>(enabled);
        Collections.sort(sorted, (a, b) -> a.flattenToString().compareTo(b.flattenToString()));
        for (ComponentName cn : sorted) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(cn.flattenToString());
        }
        try {
            return Settings.Secure.putString(
                    context.getContentResolver(), KEY_ENABLED_NLS, sb.toString());
        } catch (Throwable t) {
            return false;
        }
    }
}
