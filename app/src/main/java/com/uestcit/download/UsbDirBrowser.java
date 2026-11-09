package com.uestcit.download;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * U 盘单层目录浏览：只列出文件夹与 APK 候选（含 {@code .apk.1} 等魔术名），不递归。
 */
final class UsbDirBrowser {

    /** 列表项：文件夹或可装 APK。 */
    static final class Entry {
        final File file;
        final boolean directory;
        final String displayName;

        Entry(File file, boolean directory) {
            this.file = file;
            this.directory = directory;
            this.displayName = file.getName();
        }
    }

    private UsbDirBrowser() {
    }

    /**
     * 列出 {@code dir} 下一层可见项：文件夹在前，再按名排序。
     * 跳过 {@code ._*}；非 APK 普通文件不展示。
     */
    static List<Entry> listVisible(File dir) {
        List<Entry> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            return out;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return out;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            String name = child.getName();
            if (name.startsWith("._")) {
                continue;
            }
            if (child.isDirectory()) {
                out.add(new Entry(child, true));
                continue;
            }
            if (child.isFile() && isApkCandidate(name)) {
                out.add(new Entry(child, false));
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        return out;
    }

    /** 与 {@link ApkFileNames} 一致的可装文件名；另认常见大小写变体。 */
    static boolean isApkCandidate(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (ApkFileNames.looksLikeApkName(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.US);
        // 偶发无扩展名但含 apk 的另存（极少）；保守：仅扩展名族
        return lower.endsWith(".apk.1")
                || lower.endsWith(".apk.2")
                || lower.endsWith(".apk.3");
    }

    /**
     * 是否可回到上一级：当前不在任一 U 盘根之上（父目录仍在根路径内或等于根）。
     */
    static boolean canGoUp(File current, File root) {
        if (current == null || root == null) {
            return false;
        }
        try {
            String cur = current.getCanonicalPath();
            String r = root.getCanonicalPath();
            if (cur.equals(r)) {
                return false;
            }
            return cur.startsWith(r + File.separator);
        } catch (Exception e) {
            File parent = current.getParentFile();
            return parent != null && !current.equals(root);
        }
    }

    static File parentWithinRoot(File current, File root) {
        if (!canGoUp(current, root)) {
            return root;
        }
        File parent = current.getParentFile();
        return parent != null ? parent : root;
    }
}
