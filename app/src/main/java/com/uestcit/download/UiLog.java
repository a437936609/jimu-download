package com.uestcit.download;

import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 主屏日志：带时间戳、最新在上；写入后滚到顶部。
 * 行数上限避免 TextView 过大卡顿。
 */
public final class UiLog {

    private static final int MAX_LINES = 200;

    private final TextView textView;
    private final ScrollView scrollView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    public UiLog(TextView textView, ScrollView scrollView) {
        this.textView = textView;
        this.scrollView = scrollView;
    }

    /** 追加一条（可含多行）；每行都会打时间戳并插到顶部。 */
    public void log(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendNow(text);
        } else {
            main.post(() -> appendNow(text));
        }
    }

    private void appendNow(String text) {
        String stamp = timeFmt.format(new Date());
        // 多行压成少量行；过长截断，避免 HTML/堆栈刷屏
        String normalized = text.replace("\r", "").trim();
        if (normalized.isEmpty()) {
            return;
        }
        String[] parts = normalized.split("\n");
        StringBuilder block = new StringBuilder();
        int limit = Math.min(parts.length, 4);
        for (int i = 0; i < limit; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() > 160) {
                part = part.substring(0, 160) + "…";
            }
            if (block.length() > 0) {
                block.append('\n');
            }
            block.append('[').append(stamp).append("] ").append(part);
        }
        if (parts.length > 4) {
            block.append('\n').append('[').append(stamp).append("] …共 ")
                    .append(parts.length).append(" 行已截断");
        }
        if (block.length() == 0) {
            return;
        }
        lines.addFirst(block.toString());
        while (lines.size() > MAX_LINES) {
            lines.removeLast();
        }
        StringBuilder all = new StringBuilder();
        for (String line : lines) {
            if (all.length() > 0) {
                all.append('\n');
            }
            all.append(line);
        }
        textView.setText(all.toString());
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_UP));
    }
}
