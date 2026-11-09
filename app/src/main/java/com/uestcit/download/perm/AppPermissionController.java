package com.uestcit.download.perm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按包装载<strong>全部</strong>危险运行时权限（逐条）+ 常见特殊 AppOps，并特权开关。
 * <p>
 * 车机无 Settings.apk / 系统授权页不可靠：管理页列出所有可开项；
 * API 走 {@code grantRuntimePermission} / {@code revokeRuntimePermission} /
 * {@code AppOpsManager.setMode}（需 system UID）。
 */
public final class AppPermissionController {

    /** {@link PermissionInfo#PROTECTION_DANGEROUS}。 */
    private static final int PROTECTION_DANGEROUS = 1;
    /** {@code PermissionInfo.PROTECTION_MASK_BASE}。 */
    private static final int PROTECTION_MASK_BASE = 0xf;
    /** {@code PackageManager.FLAG_PERMISSION_SYSTEM_FIXED}。 */
    private static final int FLAG_PERMISSION_SYSTEM_FIXED = 1 << 4;
    /** {@code PackageInfo.REQUESTED_PERMISSION_GRANTED}。 */
    private static final int REQUESTED_PERMISSION_GRANTED = 1 << 1;

    private final Context context;
    private final SpecialAppOps specialAppOps;

    public AppPermissionController(Context context) {
        this.context = context.getApplicationContext();
        this.specialAppOps = new SpecialAppOps(this.context);
    }

    /**
     * 列出目标包全部可管理项：每条危险运行时权限一行 + 已声明的特殊 AppOps。
     *
     * @return 按类型/标签排序；包不存在返回空列表
     */
    public List<RuntimePermissionGroup> listGroups(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return Collections.emptyList();
        }
        PackageManager pm = context.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            return Collections.emptyList();
        }
        if (pi.requestedPermissions == null || pi.requestedPermissions.length == 0) {
            return appendSpecialAppOps(packageName, pi, new ArrayList<RuntimePermissionGroup>());
        }

        // 用 LinkedHashMap 保序，key=权限名 → 一行一条，不再合并 modern 组
        Map<String, RuntimePermissionGroup> byPerm = new LinkedHashMap<>();
        UserHandle user = Process.myUserHandle();
        int[] flagsArr = pi.requestedPermissionsFlags;

        for (int i = 0; i < pi.requestedPermissions.length; i++) {
            String permName = pi.requestedPermissions[i];
            PermissionInfo permInfo;
            try {
                permInfo = pm.getPermissionInfo(permName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            // 仅危险运行时权限（signature/normal 无法用本 API 开关）
            if ((permInfo.protectionLevel & PROTECTION_MASK_BASE) != PROTECTION_DANGEROUS) {
                continue;
            }
            // targetSdk≤22 且非平台声明：安装时已定，跳过
            if (pi.applicationInfo != null
                    && pi.applicationInfo.targetSdkVersion <= 22
                    && !"android".equals(permInfo.packageName)) {
                continue;
            }

            CharSequence label = permInfo.loadLabel(pm);
            if (label == null || label.length() == 0) {
                label = permName;
            }
            RuntimePermissionGroup row = new RuntimePermissionGroup(
                    permName, label, permName, RuntimePermissionGroup.KIND_RUNTIME);
            boolean granted = flagsArr != null
                    && i < flagsArr.length
                    && (flagsArr[i] & REQUESTED_PERMISSION_GRANTED) != 0;
            int pmFlags = getPermissionFlags(pm, permName, packageName, user);
            boolean systemFixed = (pmFlags & FLAG_PERMISSION_SYSTEM_FIXED) != 0;
            row.add(new RuntimePermissionGroup.PermEntry(permName, granted, systemFixed));
            byPerm.put(permName, row);
        }

        List<RuntimePermissionGroup> out = new ArrayList<>(byPerm.values());
        appendSpecialAppOps(packageName, pi, out);
        Collections.sort(out);
        return out;
    }

    /**
     * 将请求数组中的危险权限做成行（供系统权限申请弹窗复用）。
     *
     * @param packageName 申请方包名
     * @param requested   Intent 里的权限名数组
     */
    public List<RuntimePermissionGroup> listRequested(
            String packageName, String[] requested) {
        if (requested == null || requested.length == 0) {
            return Collections.emptyList();
        }
        PackageManager pm = context.getPackageManager();
        UserHandle user = Process.myUserHandle();
        List<RuntimePermissionGroup> out = new ArrayList<>();
        for (String permName : requested) {
            if (TextUtils.isEmpty(permName)) {
                continue;
            }
            PermissionInfo permInfo;
            try {
                permInfo = pm.getPermissionInfo(permName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // 未知权限仍展示，允许用户点开尝试授予
                RuntimePermissionGroup row = new RuntimePermissionGroup(
                        permName, permName, permName, RuntimePermissionGroup.KIND_RUNTIME);
                row.add(new RuntimePermissionGroup.PermEntry(permName, false, false));
                out.add(row);
                continue;
            }
            CharSequence label = permInfo.loadLabel(pm);
            if (label == null || label.length() == 0) {
                label = permName;
            }
            RuntimePermissionGroup row = new RuntimePermissionGroup(
                    permName, label, permName, RuntimePermissionGroup.KIND_RUNTIME);
            boolean granted = pm.checkPermission(permName, packageName)
                    == PackageManager.PERMISSION_GRANTED;
            int pmFlags = getPermissionFlags(pm, permName, packageName, user);
            boolean systemFixed = (pmFlags & FLAG_PERMISSION_SYSTEM_FIXED) != 0;
            row.add(new RuntimePermissionGroup.PermEntry(permName, granted, systemFixed));
            out.add(row);
        }
        return out;
    }

    /**
     * 一键授予列表中全部可改项。
     *
     * @return 人类可读摘要
     */
    public String grantAll(String packageName, List<RuntimePermissionGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "无可开启权限";
        }
        int ok = 0;
        int fail = 0;
        for (RuntimePermissionGroup g : groups) {
            if (g.isSystemFixed()) {
                continue;
            }
            String r = grantGroup(packageName, g);
            if (r != null && r.startsWith("失败")) {
                fail++;
            } else {
                ok++;
            }
        }
        return "已开启 " + ok + " 项" + (fail > 0 ? ("，失败 " + fail) : "");
    }

    /**
     * 授予单条运行时权限（供系统申请弹窗逐项/批量调用）。
     *
     * @return true 表示调用后 {@link PackageManager#checkPermission} 为已授予
     */
    public boolean grantPermission(String packageName, String permission) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(permission)) {
            return false;
        }
        if (!grantRuntime(packageName, permission)) {
            return false;
        }
        return context.getPackageManager().checkPermission(permission, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 开启一行（运行时或 AppOps）。
     *
     * @return 人类可读结果
     */
    public String grantGroup(String packageName, RuntimePermissionGroup group) {
        if (group == null || TextUtils.isEmpty(packageName)) {
            return "失败: 参数无效";
        }
        if (group.isSystemFixed()) {
            return "失败: 系统锁定，不可更改";
        }
        if (group.kind == RuntimePermissionGroup.KIND_APP_OP) {
            return setAppOp(packageName, group, true);
        }
        int ok = 0;
        int fail = 0;
        for (RuntimePermissionGroup.PermEntry p : group.getPermissions()) {
            if (p.systemFixed) {
                continue;
            }
            if (p.granted) {
                ok++;
                continue;
            }
            if (grantRuntime(packageName, p.name)) {
                p.granted = true;
                ok++;
            } else {
                fail++;
            }
        }
        if (fail > 0 && ok == 0) {
            return "授权失败: " + group.name;
        }
        if (fail > 0) {
            return "部分授权成功（失败 " + fail + "）";
        }
        return "已允许「" + group.label + "」";
    }

    /**
     * 关闭一行（运行时或 AppOps）。
     *
     * @return 人类可读结果
     */
    public String revokeGroup(String packageName, RuntimePermissionGroup group) {
        if (group == null || TextUtils.isEmpty(packageName)) {
            return "失败: 参数无效";
        }
        if (group.isSystemFixed()) {
            return "失败: 系统锁定，不可更改";
        }
        if (group.kind == RuntimePermissionGroup.KIND_APP_OP) {
            return setAppOp(packageName, group, false);
        }
        int ok = 0;
        int fail = 0;
        for (RuntimePermissionGroup.PermEntry p : group.getPermissions()) {
            if (p.systemFixed) {
                continue;
            }
            if (!p.granted) {
                ok++;
                continue;
            }
            if (revokeRuntime(packageName, p.name)) {
                p.granted = false;
                ok++;
            } else {
                fail++;
            }
        }
        if (fail > 0 && ok == 0) {
            return "撤销失败: " + group.name;
        }
        if (fail > 0) {
            return "部分撤销成功（失败 " + fail + "）";
        }
        return "已拒绝「" + group.label + "」";
    }

    /**
     * 追加特殊权限行：允许通知（alwaysShow）+ 已声明/有组件的特殊项。
     * 见 {@link SpecialAppOps#MANAGED_KINDS}。
     */
    private List<RuntimePermissionGroup> appendSpecialAppOps(
            String packageName, PackageInfo pi, List<RuntimePermissionGroup> out) {
        for (SpecialAppOps.Kind kind : SpecialAppOps.MANAGED_KINDS) {
            if (!specialAppOps.shouldShow(packageName, pi, kind)) {
                continue;
            }
            String detail = kind.opstr != null
                    ? (kind.permission + " / " + kind.opstr)
                    : kind.permission;
            RuntimePermissionGroup row = new RuntimePermissionGroup(
                    kind.permission,
                    kind.label,
                    detail,
                    RuntimePermissionGroup.KIND_APP_OP);
            row.appOpAllowed = specialAppOps.isAllowed(packageName, kind);
            out.add(row);
        }
        return out;
    }

    private String setAppOp(String packageName, RuntimePermissionGroup group, boolean allow) {
        SpecialAppOps.Kind kind = SpecialAppOps.findByPermission(group.name);
        if (kind == null) {
            return "失败: 未知 AppOps";
        }
        String msg = specialAppOps.setAllowed(packageName, kind, allow);
        group.appOpAllowed = specialAppOps.isAllowed(packageName, kind);
        return msg;
    }

    private boolean grantRuntime(String packageName, String permission) {
        try {
            PackageManager pm = context.getPackageManager();
            Method m = PackageManager.class.getMethod(
                    "grantRuntimePermission", String.class, String.class, UserHandle.class);
            m.invoke(pm, packageName, permission, Process.myUserHandle());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean revokeRuntime(String packageName, String permission) {
        try {
            PackageManager pm = context.getPackageManager();
            Method m = PackageManager.class.getMethod(
                    "revokeRuntimePermission", String.class, String.class, UserHandle.class);
            m.invoke(pm, packageName, permission, Process.myUserHandle());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private int getPermissionFlags(PackageManager pm, String permission,
                                   String packageName, UserHandle user) {
        try {
            Method m = PackageManager.class.getMethod(
                    "getPermissionFlags", String.class, String.class, UserHandle.class);
            Object v = m.invoke(pm, permission, packageName, user);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (Throwable ignored) {
            // 旧 stub 无此方法则视为无锁定标志
        }
        return 0;
    }
}
