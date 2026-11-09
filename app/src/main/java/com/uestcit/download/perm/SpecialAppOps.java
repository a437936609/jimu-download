package com.uestcit.download.perm;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 特殊权限（AppOps / 通知监听 / 勿扰）统一读写。
 * <p>
 * 权限管理页与 {@code ManageAppOpActivity} 共用；Intent 备选页不高优抢系统默认。
 * API 28「允许通知」走 {@code OP_POST_NOTIFICATION}（无运行时 POST_NOTIFICATIONS）。
 */
public final class SpecialAppOps {

    /** 后端：纯 AppOps。 */
    public static final int BACKEND_APP_OP = 0;
    /** 后端：Secure enabled_notification_listeners。 */
    public static final int BACKEND_NOTIFICATION_LISTENER = 1;
    /** 后端：NotificationManager 勿扰策略访问。 */
    public static final int BACKEND_NOTIFICATION_POLICY = 2;

    /**
     * 允许通知：几乎每包都显示（不依赖 Manifest）。
     * permission 字段为列表稳定 key（API28 无此运行时权限）。
     */
    public static final Kind POST_NOTIFICATION = new Kind(
            "android.permission.POST_NOTIFICATIONS",
            "android:post_notification",
            "OP_POST_NOTIFICATION",
            11,
            "允许通知",
            true,
            true,
            BACKEND_APP_OP,
            AppOpsManager.MODE_IGNORED);

    /** 悬浮窗。 */
    public static final Kind OVERLAY = new Kind(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android:system_alert_window",
            "OP_SYSTEM_ALERT_WINDOW",
            24,
            "悬浮窗",
            false,
            false,
            BACKEND_APP_OP,
            AppOpsManager.MODE_ERRORED);

    /** 修改系统设置。 */
    public static final Kind WRITE_SETTINGS = new Kind(
            "android.permission.WRITE_SETTINGS",
            "android:write_settings",
            "OP_WRITE_SETTINGS",
            23,
            "修改系统设置",
            false,
            false,
            BACKEND_APP_OP,
            AppOpsManager.MODE_ERRORED);

    /** 使用情况访问。 */
    public static final Kind USAGE_STATS = new Kind(
            "android.permission.PACKAGE_USAGE_STATS",
            "android:get_usage_stats",
            "OP_GET_USAGE_STATS",
            43,
            "使用情况访问",
            false,
            false,
            BACKEND_APP_OP,
            AppOpsManager.MODE_ERRORED);

    /** 安装未知应用。 */
    public static final Kind REQUEST_INSTALL = new Kind(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android:request_install_packages",
            "OP_REQUEST_INSTALL_PACKAGES",
            66,
            "安装未知应用",
            false,
            false,
            BACKEND_APP_OP,
            AppOpsManager.MODE_ERRORED);

    /** 通知使用权（监听其它应用通知）。 */
    public static final Kind NOTIFICATION_LISTENER = new Kind(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android:access_notifications",
            "OP_ACCESS_NOTIFICATIONS",
            25,
            "通知使用权",
            false,
            false,
            BACKEND_NOTIFICATION_LISTENER,
            AppOpsManager.MODE_ERRORED);

    /** 勿扰 / 通知策略访问。 */
    public static final Kind NOTIFICATION_POLICY = new Kind(
            "android.permission.ACCESS_NOTIFICATION_POLICY",
            "android:access_notification_policy",
            "OP_ACCESS_NOTIFICATIONS",
            25,
            "勿扰权限",
            false,
            false,
            BACKEND_NOTIFICATION_POLICY,
            AppOpsManager.MODE_ERRORED);

    /** 权限管理页展示顺序（允许通知置顶）。 */
    public static final Kind[] MANAGED_KINDS = {
            POST_NOTIFICATION,
            OVERLAY,
            WRITE_SETTINGS,
            USAGE_STATS,
            REQUEST_INSTALL,
            NOTIFICATION_LISTENER,
            NOTIFICATION_POLICY,
    };

    private final Context context;
    private final NotificationAccessHelper notificationAccess;

    public SpecialAppOps(Context context) {
        this.context = context.getApplicationContext();
        this.notificationAccess = new NotificationAccessHelper(this.context);
    }

    public boolean isSystemUid() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /** 按 permission / 稳定 key 查找 Kind。 */
    @Nullable
    public static Kind findByPermission(String permission) {
        if (TextUtils.isEmpty(permission)) {
            return null;
        }
        for (Kind k : MANAGED_KINDS) {
            if (k.permission.equals(permission)) {
                return k;
            }
        }
        return null;
    }

    /**
     * 该包是否应在权限列表展示此 Kind。
     * <ul>
     *   <li>{@link Kind#alwaysShow}：包存在即显示</li>
     *   <li>通知监听：有 NLS 组件</li>
     *   <li>其余：Manifest 声明了对应 permission</li>
     * </ul>
     */
    public boolean shouldShow(String packageName, PackageInfo pi, Kind kind) {
        if (kind == null || TextUtils.isEmpty(packageName)) {
            return false;
        }
        if (kind.alwaysShow) {
            return appInfo(packageName) != null;
        }
        if (kind.backend == BACKEND_NOTIFICATION_LISTENER) {
            return notificationAccess.hasNotificationListener(packageName);
        }
        if (pi == null || pi.requestedPermissions == null) {
            return false;
        }
        for (String p : pi.requestedPermissions) {
            if (kind.permission.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 当前是否允许。 */
    public boolean isAllowed(String packageName, Kind kind) {
        if (kind == null) {
            return false;
        }
        if (kind.backend == BACKEND_NOTIFICATION_LISTENER) {
            return notificationAccess.isListenerEnabled(packageName);
        }
        if (kind.backend == BACKEND_NOTIFICATION_POLICY) {
            return notificationAccess.isPolicyAccessGranted(packageName);
        }
        ApplicationInfo ai = appInfo(packageName);
        if (ai == null) {
            return false;
        }
        // 优先跟 SystemUI 通知开关对齐
        if (kind == POST_NOTIFICATION) {
            Boolean nm = notificationAccess.areNotificationsEnabledForPackage(
                    packageName, ai.uid);
            if (nm != null) {
                return nm;
            }
        }
        int mode = checkMode(packageName, ai.uid, kind);
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        // POST_NOTIFICATION 默认即允许（未写入 AppOps 时 dumpsys 显示 Default mode: allow）
        if (kind.defaultMeansAllowed && mode == AppOpsManager.MODE_DEFAULT) {
            return true;
        }
        return false;
    }

    /**
     * 允许或拒绝。
     *
     * @return 人类可读过程
     */
    public String setAllowed(String packageName, Kind kind, boolean allow) {
        if (TextUtils.isEmpty(packageName) || kind == null) {
            return "失败: 参数无效";
        }
        if (kind.backend == BACKEND_NOTIFICATION_LISTENER) {
            return notificationAccess.setListenerEnabled(packageName, allow);
        }
        if (kind.backend == BACKEND_NOTIFICATION_POLICY) {
            return notificationAccess.setPolicyAccessGranted(packageName, allow);
        }
        ApplicationInfo ai = appInfo(packageName);
        if (ai == null) {
            return "失败: 包未安装";
        }
        int mode = allow ? AppOpsManager.MODE_ALLOWED : kind.denyMode;
        try {
            AppOpsManager aom =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return "失败: AppOpsManager 不可用";
            }
            try {
                Method m = AppOpsManager.class.getMethod(
                        "setMode", String.class, int.class, String.class, int.class);
                m.invoke(aom, kind.opstr, ai.uid, packageName, mode);
            } catch (NoSuchMethodException e) {
                Method m = AppOpsManager.class.getMethod(
                        "setMode", int.class, int.class, String.class, int.class);
                m.invoke(aom, kind.resolveOpCode(), ai.uid, packageName, mode);
            } catch (Throwable first) {
                Method m = AppOpsManager.class.getMethod(
                        "setMode", int.class, int.class, String.class, int.class);
                m.invoke(aom, kind.resolveOpCode(), ai.uid, packageName, mode);
            }
            if (kind == POST_NOTIFICATION) {
                notificationAccess.setNotificationsEnabledForPackage(
                        packageName, ai.uid, allow);
            }
            boolean now = isAllowed(packageName, kind);
            if (allow && !now) {
                return "已调用 setMode，但回读仍未允许（mode="
                        + checkMode(packageName, ai.uid, kind) + "）";
            }
            return (allow ? "已允许「" : "已拒绝「") + kind.label + "」 " + packageName;
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            String msg = c.getMessage();
            return "AppOps 失败: " + c.getClass().getSimpleName()
                    + (TextUtils.isEmpty(msg) ? "" : (": " + msg));
        }
    }

    /**
     * 列表页候选包：alwaysShow → 全部已装；监听 → 有 NLS；其余 → Manifest 声明。
     */
    public List<Entry> listDeclaring(Kind kind) {
        if (kind == null) {
            return Collections.emptyList();
        }
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> pkgs;
        try {
            int flags = PackageManager.GET_PERMISSIONS;
            if (kind.backend == BACKEND_NOTIFICATION_LISTENER) {
                flags |= PackageManager.GET_SERVICES;
            }
            pkgs = pm.getInstalledPackages(flags);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (PackageInfo pi : pkgs) {
            if (pi.applicationInfo == null || TextUtils.isEmpty(pi.packageName)) {
                continue;
            }
            if (!shouldShow(pi.packageName, pi, kind)) {
                continue;
            }
            String label;
            try {
                CharSequence l = pm.getApplicationLabel(pi.applicationInfo);
                label = l != null ? l.toString() : pi.packageName;
            } catch (Exception e) {
                label = pi.packageName;
            }
            out.add(new Entry(pi.packageName, label, isAllowed(pi.packageName, kind)));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    public String describe(String packageName, Kind kind) {
        ApplicationInfo ai = appInfo(packageName);
        if (ai == null) {
            return "包未安装";
        }
        if (kind == null) {
            return "未知类型";
        }
        if (kind.backend == BACKEND_NOTIFICATION_LISTENER) {
            return "listener=" + notificationAccess.isListenerEnabled(packageName)
                    + " hasNls=" + notificationAccess.hasNotificationListener(packageName)
                    + " systemUid=" + isSystemUid();
        }
        if (kind.backend == BACKEND_NOTIFICATION_POLICY) {
            return "policy=" + notificationAccess.isPolicyAccessGranted(packageName)
                    + " systemUid=" + isSystemUid();
        }
        int mode = checkMode(packageName, ai.uid, kind);
        return "uid=" + ai.uid + " mode=" + modeToLabel(mode)
                + " allowed=" + isAllowed(packageName, kind)
                + " systemUid=" + isSystemUid();
    }

    private int checkMode(String packageName, int uid, Kind kind) {
        try {
            AppOpsManager aom =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return -1;
            }
            try {
                Method m = AppOpsManager.class.getMethod(
                        "checkOpNoThrow", String.class, int.class, String.class);
                Object v = m.invoke(aom, kind.opstr, uid, packageName);
                if (v instanceof Integer) {
                    return (Integer) v;
                }
            } catch (Throwable ignored) {
                // 回落 int
            }
            Method m = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            Object v = m.invoke(aom, kind.resolveOpCode(), uid, packageName);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return -1;
    }

    private ApplicationInfo appInfo(String packageName) {
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String modeToLabel(int mode) {
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return "ALLOWED(0)";
            case AppOpsManager.MODE_IGNORED:
                return "IGNORED(1)";
            case AppOpsManager.MODE_ERRORED:
                return "ERRORED(2)";
            case AppOpsManager.MODE_DEFAULT:
                return "DEFAULT(3)";
            default:
                return "mode=" + mode;
        }
    }

    /** 一种特殊权限的常量。 */
    public static final class Kind {
        /** 列表 key / Manifest 权限名（POST_NOTIFICATIONS 为合成 key）。 */
        public final String permission;
        public final String opstr;
        public final String opField;
        public final int fallbackOp;
        public final String label;
        /** true：不依赖 Manifest，包存在就显示。 */
        public final boolean alwaysShow;
        /** true：AppOps MODE_DEFAULT 视为已允许（通知）。 */
        public final boolean defaultMeansAllowed;
        /** {@link SpecialAppOps#BACKEND_APP_OP} 等。 */
        public final int backend;
        /** 拒绝时写入的 mode（通知用 IGNORED）。 */
        public final int denyMode;

        Kind(String permission, String opstr, String opField, int fallbackOp, String label,
             boolean alwaysShow, boolean defaultMeansAllowed, int backend, int denyMode) {
            this.permission = permission;
            this.opstr = opstr;
            this.opField = opField;
            this.fallbackOp = fallbackOp;
            this.label = label;
            this.alwaysShow = alwaysShow;
            this.defaultMeansAllowed = defaultMeansAllowed;
            this.backend = backend;
            this.denyMode = denyMode;
        }

        int resolveOpCode() {
            try {
                Field f = AppOpsManager.class.getField(opField);
                return f.getInt(null);
            } catch (Throwable t) {
                return fallbackOp;
            }
        }
    }

    /** 列表行。 */
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final boolean allowed;

        Entry(String packageName, String label, boolean allowed) {
            this.packageName = packageName;
            this.label = label;
            this.allowed = allowed;
        }
    }
}
