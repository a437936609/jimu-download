package com.uestcit.download;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.uestcit.download.proc.ProcessMemStatsCollector;
import com.uestcit.download.proc.ProcessMemStatsCollector.AppMemEntry;
import com.uestcit.download.proc.ProcessMemStatsCollector.Snapshot;
import com.uestcit.download.proc.ProcessMemStatsCollector.SystemMem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设置 → 内存分析：整机可用/已用 + 各运行中应用 PSS 与 CPU%。
 * <p>
 * 前台时约每 {@link #AUTO_REFRESH_MS} 自动刷新；可按内存或 CPU 排序。
 */
public final class ProcessMemStatsActivity extends AppCompatActivity {

    /** 自动刷新间隔（含采集休眠，体感约 2s+）。 */
    private static final long AUTO_REFRESH_MS = 2500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    private ProcessMemStatsCollector collector;
    private TextView textSummary;
    private TextView textHint;
    private Button btnSortMem;
    private Button btnSortCpu;
    private EntryAdapter adapter;

    /** true=按 PSS，false=按 CPU。 */
    private boolean sortByMem = true;
    private Snapshot lastSnapshot;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAsync();
            main.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process_mem_stats);
        collector = new ProcessMemStatsCollector(this);

        textSummary = findViewById(R.id.text_mem_summary);
        textHint = findViewById(R.id.text_mem_hint);
        btnSortMem = findViewById(R.id.btn_sort_mem);
        btnSortCpu = findViewById(R.id.btn_sort_cpu);
        ListView list = findViewById(R.id.list_procs);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshAsync());
        btnSortMem.setOnClickListener(v -> {
            sortByMem = true;
            updateSortButtons();
            applySortAndBind();
        });
        btnSortCpu.setOnClickListener(v -> {
            sortByMem = false;
            updateSortButtons();
            applySortAndBind();
        });
        updateSortButtons();
        textSummary.setText(R.string.mem_stats_loading);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAsync();
        main.removeCallbacks(autoRefresh);
        main.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(autoRefresh);
        io.shutdownNow();
        super.onDestroy();
    }

    /** 后台采集；并发刷新合并为一次。 */
    private void refreshAsync() {
        if (!collecting.compareAndSet(false, true)) {
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                Snapshot snap = null;
                try {
                    snap = collector.collect();
                } catch (Throwable t) {
                    final String err = t.getMessage() != null
                            ? t.getMessage() : t.getClass().getSimpleName();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            textHint.setText("采集失败: " + err);
                        }
                    });
                } finally {
                    collecting.set(false);
                }
                if (snap == null) {
                    return;
                }
                final Snapshot ready = snap;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        lastSnapshot = ready;
                        bindSystem(ready.system, ready.systemUid, ready.note, ready.entries.size());
                        applySortAndBind();
                    }
                });
            }
        });
    }

    private void bindSystem(SystemMem s, boolean systemUid, String note, int rows) {
        String summary = getString(R.string.mem_stats_summary_fmt,
                ProcessMemStatsCollector.formatBytes(s.totalBytes),
                ProcessMemStatsCollector.formatBytes(s.usedBytes),
                ProcessMemStatsCollector.formatBytes(s.availBytes));
        if (s.lowMemory) {
            summary = summary + "  ·  " + getString(R.string.mem_stats_low_memory);
        }
        textSummary.setText(summary);

        StringBuilder hint = new StringBuilder();
        hint.append(getString(R.string.mem_stats_hint));
        hint.append("  ·  ").append(rows).append(" 项");
        if (!systemUid) {
            hint.append("  ·  ").append(getString(R.string.mem_stats_need_system));
        }
        if (note != null) {
            hint.append("  ·  ").append(note);
        }
        textHint.setText(hint.toString());
    }

    private void applySortAndBind() {
        if (lastSnapshot == null) {
            return;
        }
        List<AppMemEntry> copy = new ArrayList<>(lastSnapshot.entries);
        if (sortByMem) {
            Collections.sort(copy, new Comparator<AppMemEntry>() {
                @Override
                public int compare(AppMemEntry a, AppMemEntry b) {
                    return Long.compare(b.totalPssKb, a.totalPssKb);
                }
            });
        } else {
            Collections.sort(copy, new Comparator<AppMemEntry>() {
                @Override
                public int compare(AppMemEntry a, AppMemEntry b) {
                    return Float.compare(b.cpuPercent, a.cpuPercent);
                }
            });
        }
        adapter.setItems(copy);
    }

    /** 切换选中态背景后须重设文字色（AppCompat Button 会掉回默认黑字）。 */
    private void updateSortButtons() {
        styleSort(btnSortMem, sortByMem);
        styleSort(btnSortCpu, !sortByMem);
    }

    private void styleSort(Button btn, boolean selected) {
        btn.setBackgroundResource(selected
                ? R.drawable.bg_btn_accent
                : R.drawable.bg_btn_ghost);
        btn.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.bg : R.color.text_primary));
    }

    private final class EntryAdapter extends BaseAdapter {
        private final List<AppMemEntry> items = new ArrayList<>();

        void setItems(List<AppMemEntry> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppMemEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_process_mem, parent, false);
            }
            AppMemEntry e = getItem(position);
            TextView label = v.findViewById(R.id.text_proc_label);
            TextView pkg = v.findViewById(R.id.text_proc_pkg);
            TextView pss = v.findViewById(R.id.text_proc_pss);
            TextView cpu = v.findViewById(R.id.text_proc_cpu);
            TextView count = v.findViewById(R.id.text_proc_count);
            label.setText(e.label);
            pkg.setText(e.packageName != null ? e.packageName : e.key);
            pss.setText(e.formatPss());
            cpu.setText(e.formatCpu());
            count.setText(String.format(Locale.US, "%d", e.processCount));
            return v;
        }
    }
}
