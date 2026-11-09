package com.uestcit.download.perm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 权限列表中的一行：运行时危险权限，或特殊 AppOps（悬浮窗等）。
 * 管理页按「单条权限」展示，方便逐项开启（不再只露 modern 组）。
 */
public final class RuntimePermissionGroup implements Comparable<RuntimePermissionGroup> {

    /** 行类型：运行时权限。 */
    public static final int KIND_RUNTIME = 0;
    /** 行类型：特殊 AppOps（非 grantRuntimePermission）。 */
    public static final int KIND_APP_OP = 1;

    /** 稳定 key（权限名或 AppOps 名）。 */
    public final String name;
    /** 主标题（中文标签）。 */
    public final CharSequence label;
    /** 副标题（原始权限名 / op 说明）。 */
    public final CharSequence detail;
    /** {@link #KIND_RUNTIME} / {@link #KIND_APP_OP}。 */
    public final int kind;
    /** 组内权限（运行时通常 1 条；AppOps 可空）。 */
    private final List<PermEntry> permissions = new ArrayList<>();
    /** AppOps 当前是否允许（仅 {@link #KIND_APP_OP}）。 */
    public boolean appOpAllowed;
    /** 是否不可改（systemFixed）。 */
    public boolean locked;

    RuntimePermissionGroup(String name, CharSequence label, CharSequence detail, int kind) {
        this.name = name;
        this.label = label != null ? label : name;
        this.detail = detail != null ? detail : "";
        this.kind = kind;
    }

    void add(PermEntry entry) {
        permissions.add(entry);
        if (entry.systemFixed) {
            locked = true;
        }
    }

    /** 组内权限只读列表。 */
    public List<PermEntry> getPermissions() {
        return Collections.unmodifiableList(permissions);
    }

    /** Switch 是否应打开。 */
    public boolean isGranted() {
        if (kind == KIND_APP_OP) {
            return appOpAllowed;
        }
        for (PermEntry p : permissions) {
            if (p.granted) {
                return true;
            }
        }
        return false;
    }

    /** 是否系统锁定不可改。 */
    public boolean isSystemFixed() {
        return locked;
    }

    public boolean isEmpty() {
        return kind == KIND_RUNTIME && permissions.isEmpty();
    }

    @Override
    public int compareTo(RuntimePermissionGroup other) {
        // 特殊权限排前（允许通知等），运行时在后；同类型按标签
        if (kind != other.kind) {
            return Integer.compare(other.kind, kind);
        }
        // 「允许通知」固定置顶
        boolean aNotify = "android.permission.POST_NOTIFICATIONS".equals(name);
        boolean bNotify = "android.permission.POST_NOTIFICATIONS".equals(other.name);
        if (aNotify != bNotify) {
            return aNotify ? -1 : 1;
        }
        return String.valueOf(label).compareToIgnoreCase(String.valueOf(other.label));
    }

    /** 单条运行时权限状态。 */
    public static final class PermEntry {
        /** 权限名。 */
        public final String name;
        /** 是否已授予。 */
        public boolean granted;
        /** {@code FLAG_PERMISSION_SYSTEM_FIXED}。 */
        public final boolean systemFixed;

        PermEntry(String name, boolean granted, boolean systemFixed) {
            this.name = name;
            this.granted = granted;
            this.systemFixed = systemFixed;
        }
    }
}
