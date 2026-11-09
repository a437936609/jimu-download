package com.uestcit.download;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 探测车机 U 盘挂载根：优先原车 {@code /storage/udisk*}，再补可移动 {@link StorageVolume}。
 */
final class UsbVolumeHelper {

    private static final String TAG = "UsbVolume";

    /** 原车 XCDebugTools 硬编码挂载点。 */
    private static final String[] OEM_UDISK_ROOTS = {
            "/storage/udisk",
            "/storage/udisk1",
            "/storage/udisk2",
    };

    private UsbVolumeHelper() {
    }

    /**
     * 返回当前可用的 U 盘根路径列表（已去重、可读目录）。
     * 空列表表示未插盘或不可读。
     */
    static List<File> listUsbRoots(Context context) {
        Set<String> paths = new LinkedHashSet<>();
        for (String p : OEM_UDISK_ROOTS) {
            File f = new File(p);
            if (isUsableDir(f)) {
                paths.add(f.getAbsolutePath());
            }
        }
        addRemovableVolumes(context, paths);
        List<File> out = new ArrayList<>();
        for (String p : paths) {
            out.add(new File(p));
        }
        return out;
    }

    /** 首选一根 U 盘（有则取列表第一项）。 */
    static File primaryUsbRoot(Context context) {
        List<File> roots = listUsbRoots(context);
        return roots.isEmpty() ? null : roots.get(0);
    }

    private static void addRemovableVolumes(Context context, Set<String> paths) {
        if (context == null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            StorageManager sm =
                    (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) {
                return;
            }
            List<StorageVolume> volumes = sm.getStorageVolumes();
            if (volumes == null) {
                return;
            }
            for (StorageVolume vol : volumes) {
                if (vol == null || !vol.isRemovable()) {
                    continue;
                }
                String state = vol.getState();
                if (state != null
                        && !Environment.MEDIA_MOUNTED.equals(state)
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                    continue;
                }
                File dir = volumeDirectory(vol);
                if (isUsableDir(dir)) {
                    // 排除主存储 emulated
                    String p = dir.getAbsolutePath().toLowerCase(Locale.US);
                    if (p.contains("emulated") || p.endsWith("/0")) {
                        continue;
                    }
                    paths.add(dir.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "list StorageVolume: " + e.getMessage());
        }
    }

    /** API 30+ getDirectory；更低版本反射 getPath。 */
    private static File volumeDirectory(StorageVolume vol) {
        if (Build.VERSION.SDK_INT >= 30) {
            File dir = vol.getDirectory();
            if (dir != null) {
                return dir;
            }
        }
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object path = m.invoke(vol);
            if (path instanceof String) {
                return new File((String) path);
            }
        } catch (Exception ignored) {
            // 反射失败则放弃该卷
        }
        return null;
    }

    private static boolean isUsableDir(File f) {
        return f != null && f.exists() && f.isDirectory() && f.canRead();
    }
}
