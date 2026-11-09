package com.uestcit.download;

import android.app.ActivityManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.text.format.Formatter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 替代缺失 Settings.apk 的应用管理：枚举已装包，并执行强停 / 停用·启用 /
 * 清数据 / 清缓存 / 卸载·卸载更新。API 对齐原车 Settings-jadx
 * （{@code AppInfoDashboardFragment}、{@code AppStorageSettings}）与同仓
 * {@code PerfPackageDisabler} / {@code HicarDepsRestorer}。
 * <p>
 * 须平台签 + {@code sharedUserId=system}；对本包禁止破坏性操作。
 */
public final class PackageAdminController {

    /** 筛选：全部已装包。 */
    public static final int FILTER_ALL = 0;
    /** 筛选：非系统（无 FLAG_SYSTEM）。 */
    public static final int FILTER_THIRD_PARTY = 1;
    /** 筛选：用户停用或组件禁用。 */
    public static final int FILTER_DISABLED = 2;

    /** {@link PackageManager} 隐藏常量：卸载时保留用户数据（卸更新回落预装用）。 */
    private static final int DELETE_KEEP_DATA = 0x00000001;
    /** {@code PackageManager.DELETE_SUCCEEDED}。 */
    private static final int DELETE_SUCCEEDED = 1;

    private static final long OBSERVER_TIMEOUT_MS = 15_000L;

    private final Context context;

    public PackageAdminController(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 列表 / 详情共用的包摘要。 */
    public static final class AppEntry {
        public final String packageName;
        public final String label;
        public final Drawable icon;
        /** {@link ApplicationInfo#FLAG_SYSTEM}。 */
        public final boolean systemApp;
        /**
         * {@link ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}：系统预装已装更新，
         * 可「卸载更新」回落预装版本（对齐 Settings {@code mUpdatedSysApp}）。
         */
        public final boolean updatedSystemApp;
        public final boolean enabled;
        public final int enabledSetting;
        public final String versionName;
        public final int versionCode;

        AppEntry(String packageName, String label, Drawable icon, boolean systemApp,
                 boolean updatedSystemApp, boolean enabled, int enabledSetting,
                 String versionName, int versionCode) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.systemApp = systemApp;
            this.updatedSystemApp = updatedSystemApp;
            this.enabled = enabled;
            this.enabledSetting = enabledSetting;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    /** 体积查询结果；任一字段未知则为 -1。 */
    public static final class StorageInfo {
        public final long codeBytes;
        public final long dataBytes;
        public final long cacheBytes;
        public final boolean available;

        StorageInfo(long codeBytes, long dataBytes, long cacheBytes, boolean available) {
            this.codeBytes = codeBytes;
            this.dataBytes = dataBytes;
            this.cacheBytes = cacheBytes;
            this.available = available;
        }

        public long totalBytes() {
            if (!available) {
                return -1L;
            }
            return Math.max(0L, codeBytes) + Math.max(0L, dataBytes);
        }
    }

    /** 当前进程是否系统 UID（改其它包启用态 / 强停的硬前提）。 */
    public boolean isSystemUid() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /**
     * 枚举已装应用，按标签排序。
     *
     * @param filter {@link #FILTER_ALL} / {@link #FILTER_THIRD_PARTY} / {@link #FILTER_DISABLED}
     * @param query  名称或包名子串，可空
     */
    public List<AppEntry> listApps(int filter, String query) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        String q = query == null ? "" : query.trim().toLowerCase();
        List<AppEntry> out = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai == null || TextUtils.isEmpty(ai.packageName)) {
                continue;
            }
            AppEntry entry = toEntry(pm, ai);
            if (entry == null) {
                continue;
            }
            if (!matchesFilter(entry, filter)) {
                continue;
            }
            if (!q.isEmpty()) {
                String labelLower = entry.label == null ? "" : entry.label.toLowerCase();
                String pkgLower = entry.packageName.toLowerCase();
                if (!labelLower.contains(q) && !pkgLower.contains(q)) {
                    continue;
                }
            }
            out.add(entry);
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int c = safeLabel(a).compareToIgnoreCase(safeLabel(b));
                if (c != 0) {
                    return c;
                }
                return a.packageName.compareTo(b.packageName);
            }
        });
        return out;
    }

    /** 按包名取详情；未安装返回 null。 */
    public AppEntry getApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return toEntry(pm, ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * 查询占用体积。主路径 {@link StorageStatsManager#queryStatsForPackage}；
     * 失败再反射 {@code PackageManager.getPackageSizeInfo}。
     */
    public StorageInfo queryStorage(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return new StorageInfo(-1, -1, -1, false);
        }
        StorageInfo viaStats = queryStorageViaStatsManager(packageName);
        if (viaStats.available) {
            return viaStats;
        }
        return queryStorageViaPackageSizeInfo(packageName);
    }

    /** 人类可读总体积摘要；不可用返回 null。 */
    public String formatStorageSummary(StorageInfo info) {
        if (info == null || !info.available) {
            return null;
        }
        long total = info.totalBytes();
        long cache = Math.max(0L, info.cacheBytes);
        long data = Math.max(0L, info.dataBytes - cache);
        long code = Math.max(0L, info.codeBytes);
        return "共 " + Formatter.formatFileSize(context, total)
                + "（应用 " + Formatter.formatFileSize(context, code)
                + " · 数据 " + Formatter.formatFileSize(context, data)
                + " · 缓存 " + Formatter.formatFileSize(context, cache) + "）";
    }

    /**
     * 强行停止目标包进程。对齐 Settings {@code forceStopPackage}。
     *
     * @return 人类可读结果
     */
    public String forceStop(String packageName) {
        String guard = guardDestructive(packageName, true);
        if (guard != null) {
            return guard;
        }
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return "失败: ActivityManager 不可用";
            }
            Method m = ActivityManager.class.getMethod("forceStopPackage", String.class);
            m.invoke(am, packageName);
            return "已强行停止 " + packageName;
        } catch (Throwable t) {
            return "强行停止失败: " + readableError(t);
        }
    }

    /**
     * 停用应用（{@link PackageManager#COMPONENT_ENABLED_STATE_DISABLED_USER}）。
     * 停用后尽量再强停一次，避免进程残留。
     */
    public String disable(String packageName) {
        String guard = guardDestructive(packageName, true);
        if (guard != null) {
            return guard;
        }
        try {
            PackageManager pm = context.getPackageManager();
            int state = pm.getApplicationEnabledSetting(packageName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                forceStop(packageName);
                return "已处于停用状态";
            }
            pm.setApplicationEnabledSetting(
                    packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    0);
            forceStop(packageName);
            return "已停用 " + packageName;
        } catch (Throwable t) {
            return "停用失败: " + readableError(t);
        }
    }

    /** 启用应用（恢复 {@link PackageManager#COMPONENT_ENABLED_STATE_DEFAULT}）。 */
    public String enable(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return "失败: 包名为空";
        }
        if (isSelf(packageName)) {
            return "本包无需启用操作";
        }
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(packageName, 0);
            pm.setApplicationEnabledSetting(
                    packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    0);
            return "已启用 " + packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return "失败: 包未安装";
        } catch (Throwable t) {
            return "启用失败: " + readableError(t);
        }
    }

    /**
     * 清除用户数据。对齐 Settings：走
     * {@code ActivityManager.clearApplicationUserData} + {@code IPackageDataObserver}。
     */
    public String clearData(String packageName) {
        String guard = guardDestructive(packageName, true);
        if (guard != null) {
            return guard;
        }
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return "失败: ActivityManager 不可用";
            }
            Method clear = ActivityManager.class.getMethod(
                    "clearApplicationUserData",
                    String.class,
                    Class.forName("android.content.pm.IPackageDataObserver"));
            ObserverWait wait = new ObserverWait();
            Object observer = newPackageDataObserver(wait);
            Boolean accepted = (Boolean) clear.invoke(am, packageName, observer);
            if (accepted != null && !accepted) {
                return "清除数据被拒绝（系统可能不允许该包）";
            }
            if (!wait.await()) {
                return "清除数据超时，请稍后刷新查看";
            }
            if (!wait.succeeded.get()) {
                return "清除数据失败（观察者回报失败）";
            }
            return "已清除数据 " + packageName;
        } catch (Throwable t) {
            return "清除数据失败: " + readableError(t);
        }
    }

    /** 清除缓存：{@code PackageManager.deleteApplicationCacheFiles}。 */
    public String clearCache(String packageName) {
        String guard = guardDestructive(packageName, false);
        if (guard != null) {
            return guard;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Method delete = PackageManager.class.getMethod(
                    "deleteApplicationCacheFiles",
                    String.class,
                    Class.forName("android.content.pm.IPackageDataObserver"));
            ObserverWait wait = new ObserverWait();
            Object observer = newPackageDataObserver(wait);
            delete.invoke(pm, packageName, observer);
            if (!wait.await()) {
                return "清除缓存超时，请稍后刷新查看";
            }
            if (!wait.succeeded.get()) {
                return "清除缓存失败（观察者回报失败）";
            }
            return "已清除缓存 " + packageName;
        } catch (Throwable t) {
            return "清除缓存失败: " + readableError(t);
        }
    }

    /**
     * 卸载第三方应用（完整删除）。对齐 Settings「卸载」；
     * 系统包请用 {@link #uninstallUpdates}。
     */
    public String uninstall(String packageName) {
        String guard = guardDestructive(packageName, true);
        if (guard != null) {
            return guard;
        }
        AppEntry entry = getApp(packageName);
        if (entry == null) {
            return "失败: 包未安装";
        }
        if (entry.systemApp) {
            return "系统应用请使用「卸载更新」或「停用」";
        }
        return deletePackageInternal(packageName, 0, "已卸载 ");
    }

    /**
     * 卸载系统应用更新（{@link #DELETE_KEEP_DATA}），回落预装版本。
     * 对齐 Settings 菜单「卸载更新」（{@code FLAG_UPDATED_SYSTEM_APP}）。
     */
    public String uninstallUpdates(String packageName) {
        String guard = guardDestructive(packageName, true);
        if (guard != null) {
            return guard;
        }
        AppEntry entry = getApp(packageName);
        if (entry == null) {
            return "失败: 包未安装";
        }
        if (!entry.updatedSystemApp) {
            return "失败: 非已更新的系统应用，无法卸载更新";
        }
        return deletePackageInternal(packageName, DELETE_KEEP_DATA, "已卸载更新 ");
    }

    /**
     * 反射隐藏 {@code PackageManager.deletePackage(pkg, observer, flags)}，
     * 阻塞至 {@code IPackageDeleteObserver} 或超时。
     */
    private String deletePackageInternal(String packageName, int flags, String okPrefix) {
        try {
            PackageManager pm = context.getPackageManager();
            Class<?> observerIface = Class.forName("android.content.pm.IPackageDeleteObserver");
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger returnCode = new AtomicInteger(-1);
            Object observer = Proxy.newProxyInstance(
                    observerIface.getClassLoader(),
                    new Class<?>[]{observerIface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("packageDeleted".equals(method.getName())
                                    && args != null && args.length >= 2
                                    && args[1] instanceof Integer) {
                                returnCode.set((Integer) args[1]);
                                latch.countDown();
                            }
                            return null;
                        }
                    });
            Method delete = pm.getClass().getMethod(
                    "deletePackage", String.class, observerIface, int.class);
            delete.invoke(pm, packageName, observer, flags);
            boolean signaled = latch.await(OBSERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!signaled) {
                return "卸载超时，请稍后刷新查看";
            }
            int code = returnCode.get();
            if (code != DELETE_SUCCEEDED) {
                return "卸载失败（returnCode=" + code + "）";
            }
            return okPrefix + packageName;
        } catch (Throwable t) {
            return "卸载失败: " + readableError(t);
        }
    }

    /** 是否允许对本包做强停/停用/清数据。 */
    public boolean isSelf(String packageName) {
        return context.getPackageName().equals(packageName);
    }

    private String guardDestructive(String packageName, boolean blockSelf) {
        if (TextUtils.isEmpty(packageName)) {
            return "失败: 包名为空";
        }
        if (blockSelf && isSelf(packageName)) {
            return "禁止对本应用执行该操作";
        }
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "失败: 包未安装";
        }
        return null;
    }

    private boolean matchesFilter(AppEntry entry, int filter) {
        switch (filter) {
            case FILTER_THIRD_PARTY:
                return !entry.systemApp;
            case FILTER_DISABLED:
                return !entry.enabled;
            case FILTER_ALL:
            default:
                return true;
        }
    }

    private AppEntry toEntry(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence labelCs = pm.getApplicationLabel(ai);
            String label = labelCs != null ? labelCs.toString() : ai.packageName;
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                icon = pm.getDefaultActivityIcon();
            }
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSystem =
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            int enabledSetting = pm.getApplicationEnabledSetting(ai.packageName);
            boolean enabled = ai.enabled
                    && enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            String versionName = "";
            int versionCode = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                versionName = pi.versionName != null ? pi.versionName : "";
                versionCode = pi.versionCode;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 枚举窗口内被卸载则跳过版本
            }
            return new AppEntry(ai.packageName, label, icon, system, updatedSystem, enabled,
                    enabledSetting, versionName, versionCode);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 走 StorageStatsManager（与 Settings StorageStatsSource 同路径）。
     * 反射调用以避免 compileSdk / 隐藏重载在不同 stub 上的 UUID 类型差异；
     * 体积字段：优先 getCodeBytes（API 28+），否则 getAppBytes。
     */
    private StorageInfo queryStorageViaStatsManager(String packageName) {
        try {
            StorageStatsManager ssm =
                    (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) {
                return new StorageInfo(-1, -1, -1, false);
            }
            UUID uuid = resolveStorageUuid(packageName);
            UserHandle user = UserHandle.getUserHandleForUid(Process.myUid());
            Method query = StorageStatsManager.class.getMethod(
                    "queryStatsForPackage", UUID.class, String.class, UserHandle.class);
            Object stats = query.invoke(ssm, uuid, packageName, user);
            if (stats == null) {
                return new StorageInfo(-1, -1, -1, false);
            }
            long code = invokeLongPrefer(stats, "getCodeBytes", "getAppBytes");
            long data = invokeLongPrefer(stats, "getDataBytes");
            long cache = invokeLongPrefer(stats, "getCacheBytes");
            return new StorageInfo(code, data, cache, true);
        } catch (Throwable t) {
            return new StorageInfo(-1, -1, -1, false);
        }
    }

    /** 应用所在存储卷 UUID；读不到则用内部存储默认卷。 */
    private UUID resolveStorageUuid(String packageName) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(packageName, 0);
            Field f = ApplicationInfo.class.getField("storageUuid");
            Object value = f.get(ai);
            if (value instanceof UUID) {
                return (UUID) value;
            }
        } catch (Throwable ignored) {
            // 回落默认卷
        }
        try {
            Object def = StorageManager.class.getField("UUID_DEFAULT").get(null);
            if (def instanceof UUID) {
                return (UUID) def;
            }
            if (def instanceof String) {
                return UUID.fromString((String) def);
            }
        } catch (Throwable ignored) {
            // 再回落 nil UUID 字符串（内部存储历史约定）
        }
        // AOSP StorageManager.UUID_DEFAULT 字面量
        return UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");
    }

    /** 反射取 long；候选方法按序尝试（兼容 API 26 getAppBytes / 28 getCodeBytes）。 */
    private static long invokeLongPrefer(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Object v = m.invoke(target);
                if (v instanceof Long) {
                    return (Long) v;
                }
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Throwable ignored) {
                // 试下一个候选
            }
        }
        return -1L;
    }

    /**
     * 旧路径：反射 getPackageSizeInfo + IPackageStatsObserver。
     * API 26+ 通常已不可用，仅作 StorageStats 失败时的兜底。
     */
    private StorageInfo queryStorageViaPackageSizeInfo(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Class<?> observerClz = Class.forName("android.content.pm.IPackageStatsObserver");
            Method getSize = PackageManager.class.getMethod(
                    "getPackageSizeInfo", String.class, observerClz);
            final CountDownLatch latch = new CountDownLatch(1);
            final long[] sizes = new long[]{-1L, -1L, -1L};
            final AtomicBoolean ok = new AtomicBoolean(false);
            Object observer = Proxy.newProxyInstance(
                    observerClz.getClassLoader(),
                    new Class<?>[]{observerClz},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onGetStatsCompleted".equals(method.getName())
                                    && args != null && args.length >= 2) {
                                try {
                                    Object packageStats = args[0];
                                    boolean succeeded = args[1] instanceof Boolean
                                            && (Boolean) args[1];
                                    if (succeeded && packageStats != null) {
                                        sizes[0] = packageStats.getClass()
                                                .getField("codeSize").getLong(packageStats);
                                        sizes[1] = packageStats.getClass()
                                                .getField("dataSize").getLong(packageStats);
                                        sizes[2] = packageStats.getClass()
                                                .getField("cacheSize").getLong(packageStats);
                                        ok.set(true);
                                    }
                                } catch (Throwable ignored) {
                                    ok.set(false);
                                } finally {
                                    latch.countDown();
                                }
                            }
                            return null;
                        }
                    });
            getSize.invoke(pm, packageName, observer);
            if (!latch.await(OBSERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new StorageInfo(-1, -1, -1, false);
            }
            return new StorageInfo(sizes[0], sizes[1], sizes[2], ok.get());
        } catch (Throwable t) {
            return new StorageInfo(-1, -1, -1, false);
        }
    }

    private Object newPackageDataObserver(final ObserverWait wait) throws ClassNotFoundException {
        Class<?> observerClz = Class.forName("android.content.pm.IPackageDataObserver");
        return Proxy.newProxyInstance(
                observerClz.getClassLoader(),
                new Class<?>[]{observerClz},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onRemoveCompleted".equals(name) && args != null && args.length >= 2) {
                            boolean succeeded = args[1] instanceof Boolean && (Boolean) args[1];
                            wait.succeeded.set(succeeded);
                            wait.latch.countDown();
                        } else if ("asBinder".equals(name)) {
                            return null;
                        }
                        return null;
                    }
                });
    }

    private static String safeLabel(AppEntry e) {
        return e.label != null ? e.label : e.packageName;
    }

    private static String readableError(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        if (TextUtils.isEmpty(msg)) {
            return c.getClass().getSimpleName();
        }
        return msg;
    }

    /** 同步等待 IPackageDataObserver 回调。 */
    private static final class ObserverWait {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean succeeded = new AtomicBoolean(false);

        boolean await() throws InterruptedException {
            return latch.await(OBSERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 在后台线程跑阻塞操作，结果回主线程。
     * 供详情页按钮避免 ANR（清数据/清缓存会等 Observer）。
     */
    public static void runAsync(final Runnable background, final Runnable onMainAfter) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    background.run();
                } finally {
                    if (onMainAfter != null) {
                        main.post(onMainAfter);
                    }
                }
            }
        }, "pkg-admin").start();
    }
}
