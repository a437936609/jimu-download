package com.uestcit.download;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本机实现「安装未知应用」授权，替代 Settings
 * {@code ACTION_MANAGE_UNKNOWN_APP_SOURCES}（753 未注入 Settings.apk 时无人接）。
 * <p>
 * 原理与系统设置页一致：写 {@code AppOps OP_REQUEST_INSTALL_PACKAGES}；
 * API&lt;26 路径额外写 {@code install_non_market_apps}。须平台签 +
 * {@code sharedUserId=system}（UID 1000）才有权改任意包的 AppOps。
 */
public final class UnknownSourcesController {

    /** AppOps 字符串码（API 28+ 公开为 OPSTR_REQUEST_INSTALL_PACKAGES）。 */
    static final String OPSTR_REQUEST_INSTALL_PACKAGES =
            "android:request_install_packages";

    private final Context context;

    public UnknownSourcesController(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 单条候选：声明了 REQUEST_INSTALL_PACKAGES 的已装包。 */
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final int uid;
        /** AppOps 模式，见 {@link AppOpsManager#MODE_ALLOWED} 等。 */
        public final int appOpsMode;
        public final boolean canRequestInstalls;

        Entry(String packageName, String label, int uid, int appOpsMode,
              boolean canRequestInstalls) {
            this.packageName = packageName;
            this.label = label;
            this.uid = uid;
            this.appOpsMode = appOpsMode;
            this.canRequestInstalls = canRequestInstalls;
        }
    }

    /** 当前进程是否系统 UID（改 AppOps 的硬前提）。 */
    public boolean isSystemUid() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /**
     * 本包是否已获「请求安装包」资格（API 26+ 走
     * {@link PackageManager#canRequestPackageInstalls()}）。
     */
    public boolean canSelfInstallPackages() {
        if (Build.VERSION.SDK_INT >= 26) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return readInstallNonMarketApps() == 1;
    }

    /**
     * 开启指定包的未知来源（AppOps=ALLOWED；并尽量写 install_non_market_apps）。
     *
     * @param packageName 目标包名；空则本包
     * @return 人类可读过程
     */
    public String grant(String packageName) {
        return setAllowed(resolvePkg(packageName), true);
    }

    /**
     * 关闭指定包的未知来源（AppOps=ERRORED，与 Settings「不允许」一致）。
     *
     * @param packageName 目标包名；空则本包
     */
    public String revoke(String packageName) {
        return setAllowed(resolvePkg(packageName), false);
    }

    /** 一键开启本包（装包页「开启未知来源」主路径，不跳 Settings）。 */
    public String grantSelf() {
        return setAllowed(context.getPackageName(), true);
    }

    /**
     * 写入授权状态。
     *
     * @param packageName 已解析的非空包名
     * @param allowed     true=ALLOWED，false=ERRORED
     */
    public String setAllowed(String packageName, boolean allowed) {
        StringBuilder sb = new StringBuilder();
        sb.append("target=").append(packageName)
                .append(" want=").append(allowed ? "ALLOWED" : "ERRORED")
                .append(" uidSelf=").append(Process.myUid())
                .append('\n');
        if (!isSystemUid()) {
            sb.append("→ FAIL 非系统 UID，无法改 AppOps（需平台签 + sharedUserId=system）\n");
            return sb.toString();
        }
        int uid;
        try {
            uid = packageUid(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("→ FAIL 包不存在: ").append(packageName).append('\n');
            return sb.toString();
        }
        boolean self = packageName.equals(context.getPackageName());
        boolean declares = declaresRequestInstallPermission(packageName);
        int mode = allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED;
        sb.append("uid=").append(uid)
                .append(" declaresREQUEST_INSTALL=").append(declares)
                .append(" before=").append(modeToLabel(checkMode(packageName, uid)))
                .append('\n');
        // 对任意已装包写 AppOps（与 Settings 按包授权一致）
        sb.append("AppOps: ").append(setAppOpsMode(packageName, uid, mode)).append('\n');
        // 全局 Secure 仅随本包开关；勿在授权/撤销他包时改全局，否则会误伤其它应用
        if (self) {
            sb.append("install_non_market_apps: ")
                    .append(writeInstallNonMarketApps(allowed ? 1 : 0))
                    .append('\n');
        }
        int after = checkMode(packageName, uid);
        sb.append("after AppOps=").append(modeToLabel(after));
        if (self) {
            sb.append(" canRequestPackageInstalls=")
                    .append(canSelfInstallPackages());
        }
        sb.append('\n');
        if (!declares && allowed) {
            sb.append("注意：该包未声明 REQUEST_INSTALL_PACKAGES，")
                    .append("AppOps 已写但对方 canRequest 可能仍为 false\n");
        }
        if (self && allowed && !canSelfInstallPackages()) {
            sb.append("→ 已写 AppOps 但仍 canRequest=false：固件可能另有门闸\n");
        } else if (allowed && after == AppOpsManager.MODE_ALLOWED) {
            sb.append("→ OK 已授权 ").append(packageName)
                    .append("（不依赖 Settings.apk）\n");
        } else if (!allowed && after == AppOpsManager.MODE_ERRORED) {
            sb.append("→ OK 已关闭 ").append(packageName).append('\n');
        } else if (allowed) {
            sb.append("→ WARN 写入后 AppOps 仍非 ALLOWED\n");
        } else {
            sb.append("→ WARN 写入后 AppOps 仍非 ERRORED\n");
        }
        return sb.toString();
    }

    /**
     * 单包状态摘要（装包页「查顶部包」用）。
     *
     * @param packageName 目标包；空则本包
     */
    public String describePackage(String packageName) {
        String pkg = resolvePkg(packageName);
        StringBuilder sb = new StringBuilder();
        sb.append("pkg=").append(pkg).append('\n');
        try {
            int uid = packageUid(pkg);
            sb.append("uid=").append(uid)
                    .append(" label=").append(appLabel(pkg))
                    .append('\n');
            sb.append("declaresREQUEST_INSTALL=")
                    .append(declaresRequestInstallPermission(pkg)).append('\n');
            sb.append("AppOps=").append(modeToLabel(checkMode(pkg, uid))).append('\n');
            if (pkg.equals(context.getPackageName())) {
                sb.append("canRequestPackageInstalls=")
                        .append(canSelfInstallPackages()).append('\n');
            }
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("→ FAIL 包未安装\n");
        }
        return sb.toString();
    }

    /**
     * 列出声明了 {@code REQUEST_INSTALL_PACKAGES} 的已装应用及当前 AppOps。
     * 与 Settings「安装未知应用」列表同源过滤条件。
     */
    public List<Entry> listRequestInstallPackages() {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (PackageInfo pi : pkgs) {
            if (pi == null || pi.packageName == null) {
                continue;
            }
            if (!declaresRequestInstall(pi)) {
                continue;
            }
            int uid;
            try {
                uid = packageUid(pi.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            CharSequence labelCs = null;
            try {
                ApplicationInfo ai = pi.applicationInfo != null
                        ? pi.applicationInfo
                        : pm.getApplicationInfo(pi.packageName, 0);
                labelCs = pm.getApplicationLabel(ai);
            } catch (Exception ignored) {
                // 无标签时用包名
            }
            String label = labelCs != null ? labelCs.toString() : pi.packageName;
            int mode = checkMode(pi.packageName, uid);
            boolean can = false;
            if (pi.packageName.equals(context.getPackageName())) {
                can = canSelfInstallPackages();
            } else {
                can = mode == AppOpsManager.MODE_ALLOWED;
            }
            out.add(new Entry(pi.packageName, label, uid, mode, can));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.packageName.compareTo(b.packageName);
            }
        });
        return out;
    }

    /**
     * 人类可读清单 + 本包状态；供装包页「未知来源清单」按钮。
     *
     * @param alsoGrantSelf true 时顺带对本包执行 {@link #grantSelf()}
     */
    public String formatListReport(boolean alsoGrantSelf) {
        StringBuilder sb = new StringBuilder();
        sb.append("systemUid=").append(isSystemUid())
                .append(" self=").append(context.getPackageName())
                .append(" canRequest=").append(canSelfInstallPackages())
                .append(" selfAppOps=")
                .append(modeToLabel(checkMode(context.getPackageName(), Process.myUid())))
                .append('\n');
        if (alsoGrantSelf) {
            sb.append("--- grantSelf ---\n");
            sb.append(grantSelf());
        }
        List<Entry> list = listRequestInstallPackages();
        sb.append("--- REQUEST_INSTALL_PACKAGES 包数=")
                .append(list.size()).append(" ---\n");
        int n = 0;
        for (Entry e : list) {
            sb.append(modeShort(e.appOpsMode))
                    .append(e.canRequestInstalls ? " ON " : " -- ")
                    .append(e.packageName);
            if (!e.label.equals(e.packageName)) {
                sb.append(" (").append(e.label).append(')');
            }
            sb.append(" uid=").append(e.uid).append('\n');
            if (++n >= 80) {
                sb.append("…已截断，共 ").append(list.size()).append(" 条\n");
                break;
            }
        }
        return sb.toString();
    }

    /** 窥探本包 AppOps 标签（装包页检查权限用）。 */
    public String peekSelfAppOpsLabel() {
        return modeToLabel(checkMode(context.getPackageName(), Process.myUid()));
    }

    // ------------------------------------------------------------------

    private String resolvePkg(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return context.getPackageName();
        }
        return packageName.trim();
    }

    private int packageUid(String packageName)
            throws PackageManager.NameNotFoundException {
        if (packageName.equals(context.getPackageName())) {
            return Process.myUid();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            return context.getPackageManager().getPackageUid(packageName, 0);
        }
        ApplicationInfo ai = context.getPackageManager()
                .getApplicationInfo(packageName, 0);
        return ai.uid;
    }

    private static boolean declaresRequestInstall(PackageInfo pi) {
        if (pi.requestedPermissions == null) {
            return false;
        }
        for (String p : pi.requestedPermissions) {
            if ("android.permission.REQUEST_INSTALL_PACKAGES".equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 已装包是否声明了 REQUEST_INSTALL_PACKAGES。 */
    public boolean declaresRequestInstallPermission(String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS);
            return declaresRequestInstall(pi);
        } catch (Exception e) {
            return false;
        }
    }

    private String appLabel(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    private int checkMode(String packageName, int uid) {
        try {
            AppOpsManager aom = context.getSystemService(AppOpsManager.class);
            if (aom == null) {
                return -1;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                Method m = AppOpsManager.class.getMethod(
                        "unsafeCheckOpNoThrow",
                        String.class, int.class, String.class);
                return (Integer) m.invoke(
                        aom, OPSTR_REQUEST_INSTALL_PACKAGES, uid, packageName);
            }
            int op = resolveRequestInstallOpCode();
            Method m = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            return (Integer) m.invoke(aom, op, uid, packageName);
        } catch (Exception e) {
            return -1;
        }
    }

    private String setAppOpsMode(String packageName, int uid, int mode) {
        try {
            AppOpsManager aom = context.getSystemService(AppOpsManager.class);
            if (aom == null) {
                return "FAIL AppOpsManager=null";
            }
            if (Build.VERSION.SDK_INT >= 28) {
                Method m = AppOpsManager.class.getMethod(
                        "setMode", String.class, int.class, String.class, int.class);
                m.invoke(aom, OPSTR_REQUEST_INSTALL_PACKAGES, uid, packageName, mode);
                return "setMode(OPSTR)=" + modeToLabel(mode);
            }
            int op = resolveRequestInstallOpCode();
            Method m = AppOpsManager.class.getMethod(
                    "setMode", int.class, int.class, String.class, int.class);
            m.invoke(aom, op, uid, packageName, mode);
            return "setMode(op=" + op + ")=" + modeToLabel(mode);
        } catch (Exception e) {
            return "FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String writeInstallNonMarketApps(int value) {
        try {
            boolean ok = Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS,
                    value);
            return "Secure.putInt=" + value + " ok=" + ok
                    + " read=" + readInstallNonMarketApps();
        } catch (Exception e) {
            return "FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private int readInstallNonMarketApps() {
        try {
            return Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS,
                    -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int resolveRequestInstallOpCode() throws Exception {
        try {
            Field f = AppOpsManager.class.getField("OP_REQUEST_INSTALL_PACKAGES");
            return f.getInt(null);
        } catch (NoSuchFieldException ignored) {
            return 66;
        }
    }

    /** AppOps 模式转可读标签（列表 UI 共用）。 */
    public static String modeToLabel(int mode) {
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return "ALLOWED(0)";
            case AppOpsManager.MODE_IGNORED:
                return "IGNORED(1)";
            case AppOpsManager.MODE_ERRORED:
                return "ERRORED(2)";
            case AppOpsManager.MODE_DEFAULT:
                return "DEFAULT(3)";
            case -1:
                return "n/a";
            default:
                return "mode=" + mode;
        }
    }

    private static String modeShort(int mode) {
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return "ALLOW";
            case AppOpsManager.MODE_IGNORED:
                return "IGNOR";
            case AppOpsManager.MODE_ERRORED:
                return "ERROR";
            case AppOpsManager.MODE_DEFAULT:
                return "DEFLT";
            default:
                return "m=" + mode;
        }
    }
}
