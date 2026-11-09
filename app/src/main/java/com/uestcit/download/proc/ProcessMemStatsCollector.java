package com.uestcit.download.proc;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Process;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 采集系统可用内存与各运行中应用的 PSS / CPU 占用。
 * <p>
 * 内存：{@link ActivityManager#getMemoryInfo} + {@code /proc/meminfo}（优先 MemAvailable）+
 * {@link ActivityManager#getProcessMemoryInfo} 的 totalPss。<br>
 * CPU：对 {@code /proc/[pid]/stat} 与 {@code /proc/stat} 做两次采样求差（需 system UID
 * 才能读其它进程；间隔约 {@link #CPU_SAMPLE_MS}）。
 */
public final class ProcessMemStatsCollector {

    /** CPU 两次采样间隔；过短抖动大，过长刷新慢。 */
    private static final int CPU_SAMPLE_MS = 450;

    private final Context context;
    private final ActivityManager am;
    private final PackageManager pm;

    public ProcessMemStatsCollector(Context context) {
        this.context = context.getApplicationContext();
        this.am = (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        this.pm = this.context.getPackageManager();
    }

    /**
     * 同步采集一帧；含 CPU 采样休眠，须在后台线程调用。
     *
     * @return 系统汇总 + 按包聚合的进程行；失败时 entries 可能为空但 system 仍尽量有值
     */
    public Snapshot collect() {
        SystemMem system = readSystemMem();
        List<RunningAppProcessInfo> procs = listRunningProcesses();
        if (procs.isEmpty()) {
            return new Snapshot(system, Collections.<AppMemEntry>emptyList(),
                    Process.myUid() == Process.SYSTEM_UID, "无运行中进程");
        }

        Map<Integer, Long> cpu1 = readPidCpuJiffies(procs);
        long total1 = readTotalCpuJiffies();
        try {
            Thread.sleep(CPU_SAMPLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<Integer, Long> cpu2 = readPidCpuJiffies(procs);
        long total2 = readTotalCpuJiffies();
        long totalDelta = Math.max(1L, total2 - total1);

        int[] pids = new int[procs.size()];
        for (int i = 0; i < procs.size(); i++) {
            pids[i] = procs.get(i).pid;
        }
        Debug.MemoryInfo[] memInfos = null;
        try {
            memInfos = am.getProcessMemoryInfo(pids);
        } catch (Throwable ignored) {
            // 非 system 或 stub 失败时仍展示进程名，PSS=0
        }

        // key=包名（无包名则用 processName）→ 聚合
        Map<String, AppMemEntry> byKey = new LinkedHashMap<>();
        for (int i = 0; i < procs.size(); i++) {
            RunningAppProcessInfo rap = procs.get(i);
            String pkg = primaryPackage(rap);
            String key = !TextUtils.isEmpty(pkg) ? pkg : rap.processName;
            AppMemEntry e = byKey.get(key);
            if (e == null) {
                e = new AppMemEntry(key, resolveLabel(pkg, rap.processName), pkg);
                byKey.put(key, e);
            }
            e.processCount++;
            e.pids.add(rap.pid);
            if (memInfos != null && i < memInfos.length && memInfos[i] != null) {
                e.totalPssKb += memInfos[i].getTotalPss();
            }
            Long a = cpu1.get(rap.pid);
            Long b = cpu2.get(rap.pid);
            if (a != null && b != null && b >= a) {
                e.cpuJiffies += (b - a);
            }
        }

        List<AppMemEntry> entries = new ArrayList<>(byKey.values());
        for (AppMemEntry e : entries) {
            e.cpuPercent = 100f * e.cpuJiffies / (float) totalDelta;
        }
        // 默认按 PSS 降序，便于找吃内存的包
        Collections.sort(entries, new Comparator<AppMemEntry>() {
            @Override
            public int compare(AppMemEntry a, AppMemEntry b) {
                return Long.compare(b.totalPssKb, a.totalPssKb);
            }
        });
        return new Snapshot(system, entries,
                Process.myUid() == Process.SYSTEM_UID, null);
    }

    private SystemMem readSystemMem() {
        SystemMem out = new SystemMem();
        if (am != null) {
            MemoryInfo mi = new MemoryInfo();
            try {
                am.getMemoryInfo(mi);
                out.availBytes = mi.availMem;
                out.totalBytes = mi.totalMem;
                out.thresholdBytes = mi.threshold;
                out.lowMemory = mi.lowMemory;
            } catch (Throwable ignored) {
                // 继续用 /proc/meminfo
            }
        }
        // MemAvailable 比 availMem 更接近「用户可感知剩余」
        ProcMeminfo proc = readProcMeminfo();
        if (proc.totalKb > 0) {
            out.totalBytes = proc.totalKb * 1024L;
        }
        if (proc.availableKb > 0) {
            out.availBytes = proc.availableKb * 1024L;
        } else if (proc.freeKb > 0 && out.availBytes <= 0) {
            out.availBytes = (proc.freeKb + proc.cachedKb) * 1024L;
        }
        if (out.totalBytes > 0 && out.availBytes >= 0) {
            out.usedBytes = Math.max(0L, out.totalBytes - out.availBytes);
        }
        return out;
    }

    private List<RunningAppProcessInfo> listRunningProcesses() {
        if (am == null) {
            return Collections.emptyList();
        }
        try {
            List<RunningAppProcessInfo> list = am.getRunningAppProcesses();
            return list != null ? list : Collections.<RunningAppProcessInfo>emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static String primaryPackage(RunningAppProcessInfo rap) {
        if (rap.pkgList != null && rap.pkgList.length > 0 && !TextUtils.isEmpty(rap.pkgList[0])) {
            return rap.pkgList[0];
        }
        // processName 常为包名或 package:service
        String n = rap.processName;
        if (TextUtils.isEmpty(n)) {
            return null;
        }
        int colon = n.indexOf(':');
        return colon > 0 ? n.substring(0, colon) : n;
    }

    private String resolveLabel(String packageName, String processName) {
        if (!TextUtils.isEmpty(packageName)) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // 用包名
            }
            return packageName;
        }
        return processName != null ? processName : "?";
    }

    /** pid → utime+stime（jiffies）。 */
    private static Map<Integer, Long> readPidCpuJiffies(List<RunningAppProcessInfo> procs) {
        Map<Integer, Long> map = new HashMap<>();
        for (RunningAppProcessInfo rap : procs) {
            long j = readPidJiffies(rap.pid);
            if (j >= 0) {
                map.put(rap.pid, j);
            }
        }
        return map;
    }

    /**
     * {@code /proc/[pid]/stat} 第 14、15 字段为 utime/stime（从 1 起算）。
     *
     * @return jiffies 合计；读失败返回 -1
     */
    private static long readPidJiffies(int pid) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/" + pid + "/stat"), 256);
            String line = br.readLine();
            if (line == null) {
                return -1;
            }
            // comm 可能含空格/括号，取最后一个 ')' 之后再 split
            int rp = line.lastIndexOf(')');
            if (rp < 0 || rp + 2 >= line.length()) {
                return -1;
            }
            String[] parts = line.substring(rp + 2).trim().split("\\s+");
            // 字段：state(0) ... utime(11) stime(12) —— 相对 ')' 后从 0 起
            if (parts.length < 13) {
                return -1;
            }
            return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** {@code /proc/stat} 首行 {@code cpu} 用户+nice+system+… 合计 jiffies。 */
    private static long readTotalCpuJiffies() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/stat"), 256);
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu")) {
                return 1L;
            }
            String[] parts = line.trim().split("\\s+");
            long sum = 0L;
            // parts[0]=cpu，其后均为数值
            for (int i = 1; i < parts.length; i++) {
                sum += Long.parseLong(parts[i]);
            }
            return sum > 0 ? sum : 1L;
        } catch (Throwable t) {
            return 1L;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static ProcMeminfo readProcMeminfo() {
        ProcMeminfo out = new ProcMeminfo();
        File f = new File("/proc/meminfo");
        if (!f.exists()) {
            return out;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f), 512);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    out.totalKb = parseKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    out.availableKb = parseKb(line);
                } else if (line.startsWith("MemFree:")) {
                    out.freeKb = parseKb(line);
                } else if (line.startsWith("Cached:")) {
                    out.cachedKb = parseKb(line);
                }
            }
        } catch (Throwable ignored) {
            // 保留已解析字段
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return out;
    }

    private static long parseKb(String line) {
        try {
            String[] p = line.split("\\s+");
            if (p.length >= 2) {
                return Long.parseLong(p[1]);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    /** 一帧采集结果。 */
    public static final class Snapshot {
        public final SystemMem system;
        public final List<AppMemEntry> entries;
        /** 当前进程是否 system UID（影响能否读其它进程 /proc）。 */
        public final boolean systemUid;
        /** 非致命提示（如无进程）。 */
        public final String note;

        Snapshot(SystemMem system, List<AppMemEntry> entries, boolean systemUid, String note) {
            this.system = system;
            this.entries = entries;
            this.systemUid = systemUid;
            this.note = note;
        }
    }

    /** 整机内存。 */
    public static final class SystemMem {
        /** 物理总量（字节）。 */
        public long totalBytes;
        /** 可用（优先 MemAvailable）。 */
        public long availBytes;
        /** 已用 ≈ total - avail。 */
        public long usedBytes;
        /** ActivityManager low-memory 阈值。 */
        public long thresholdBytes;
        public boolean lowMemory;
    }

    /** 按应用（包）聚合的一行。 */
    public static final class AppMemEntry {
        /** 列表主键（包名或进程名）。 */
        public final String key;
        public final String label;
        /** 可能为空（纯原生进程名）。 */
        public final String packageName;
        public long totalPssKb;
        public float cpuPercent;
        public int processCount;
        public long cpuJiffies;
        public final List<Integer> pids = new ArrayList<>();

        AppMemEntry(String key, String label, String packageName) {
            this.key = key;
            this.label = label;
            this.packageName = packageName;
        }

        /** 展示用：PSS 人类可读。 */
        public String formatPss() {
            return formatBytes(totalPssKb * 1024L);
        }

        public String formatCpu() {
            return String.format(Locale.US, "%.1f%%", cpuPercent);
        }
    }

    /** /proc/meminfo 解析缓冲。 */
    private static final class ProcMeminfo {
        long totalKb;
        long availableKb;
        long freeKb;
        long cachedKb;
    }

    /** KB/MB/GB 展示。 */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        final double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        final double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
