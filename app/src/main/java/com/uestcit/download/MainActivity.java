package com.uestcit.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 横屏主屏：仅应用清单（下拉刷新 + 上拉分页）与底部日志。
 * 自更新 / 未知来源在 {@link SettingsActivity}；清单 Base 固定官网 apps/。
 */
public class MainActivity extends AppCompatActivity {

    /** 客户端内存分页（一次拉全量 apps.json）。 */
    private static final int PAGE_SIZE = 15;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DownloadInstaller installer;
    private AppCatalogClient catalogClient;
    private UpdateChecker updateChecker;
    private UiLog uiLog;

    private SwipeRefreshLayout swipeApps;
    private RecyclerView listApps;
    private TextView textEmptyApps;
    private AppsAdapter appsAdapter;

    private final List<AppCatalogClient.AppEntry> allApps = new ArrayList<>();
    private int displayedCount;
    private boolean loadingMore;
    private volatile boolean busy;

    private final BroadcastReceiver installStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String text = intent.getStringExtra(InstallResultReceiver.EXTRA_STATUS_TEXT);
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            uiLog.log("安装结果: " + (text != null ? text : ("status=" + status)));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        installer = new DownloadInstaller(this);
        catalogClient = new AppCatalogClient(this);
        updateChecker = new UpdateChecker(this);

        TextView logView = findViewById(R.id.log_view);
        ScrollView logScroll = findViewById(R.id.log_scroll);
        uiLog = new UiLog(logView, logScroll);

        swipeApps = findViewById(R.id.swipe_apps);
        listApps = findViewById(R.id.list_apps);
        textEmptyApps = findViewById(R.id.text_empty_apps);

        listApps.setLayoutManager(new LinearLayoutManager(this));
        appsAdapter = new AppsAdapter();
        listApps.setAdapter(appsAdapter);
        listApps.addItemDecoration(new DividerDecoration());

        swipeApps.setColorSchemeColors(0xFF2BB673);
        swipeApps.setOnRefreshListener(this::refreshApps);

        listApps.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loadingMore || busy) {
                    return;
                }
                LinearLayoutManager lm =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) {
                    return;
                }
                int last = lm.findLastVisibleItemPosition();
                if (last >= appsAdapter.getItemCount() - 2) {
                    loadMore();
                }
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        uiLog.log(installer.describeInstallPermission());
        io.execute(() -> {
            String grant = installer.ensureInstallPermission();
            mainHandler.post(() -> uiLog.log(grant));
        });
        // 静默自检：仅有更新时提示，失败不刷日志
        io.execute(this::checkUpdateSilent);
        refreshApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(installStatusReceiver,
                new IntentFilter(InstallResultReceiver.ACTION_INSTALL_UI));
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(installStatusReceiver);
        } catch (Exception ignored) {
            // 未注册时忽略
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** 下拉 / 首次：重新拉清单并重置分页。 */
    private void refreshApps() {
        if (busy) {
            swipeApps.setRefreshing(false);
            return;
        }
        busy = true;
        swipeApps.setRefreshing(true);
        uiLog.log("拉取应用清单…");
        io.execute(() -> {
            try {
                List<AppCatalogClient.AppEntry> list =
                        catalogClient.fetch(AppCatalogClient.DEFAULT_BASE);
                mainHandler.post(() -> {
                    allApps.clear();
                    allApps.addAll(list);
                    displayedCount = 0;
                    appsAdapter.resetVisible();
                    loadMore();
                    textEmptyApps.setVisibility(
                            allApps.isEmpty() ? View.VISIBLE : View.GONE);
                    uiLog.log("清单条目: " + allApps.size());
                    busy = false;
                    swipeApps.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    uiLog.log("清单失败: " + e.getMessage());
                    busy = false;
                    swipeApps.setRefreshing(false);
                    textEmptyApps.setVisibility(
                            allApps.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
        });
    }

    /** 从缓存再吐一页到列表。 */
    private void loadMore() {
        if (displayedCount >= allApps.size()) {
            appsAdapter.setFooter(
                    allApps.isEmpty() ? null : getString(R.string.no_more));
            return;
        }
        loadingMore = true;
        appsAdapter.setFooter(getString(R.string.loading_more));
        int next = Math.min(displayedCount + PAGE_SIZE, allApps.size());
        List<AppCatalogClient.AppEntry> chunk =
                new ArrayList<>(allApps.subList(displayedCount, next));
        appsAdapter.appendItems(chunk);
        displayedCount = next;
        loadingMore = false;
        if (displayedCount >= allApps.size()) {
            appsAdapter.setFooter(getString(R.string.no_more));
        } else {
            appsAdapter.setFooter(null);
        }
    }

    /** 有新版本只提示去设置；404 等失败静默。 */
    private void checkUpdateSilent() {
        try {
            UpdateChecker.UpdateInfo info =
                    updateChecker.check(UpdateChecker.DEFAULT_BASE);
            if (info.updateAvailable) {
                mainHandler.post(() -> uiLog.log("发现本包新版本: "
                        + info.remoteVersionName + " (" + info.remoteVersionCode
                        + ")，请到设置中更新"));
            }
        } catch (Exception ignored) {
            // 官网未部署 download/ 时不刷错误
        }
    }

    private void downloadAndInstall(String apkUrl, String defaultName) {
        if (busy) {
            uiLog.log("忙，请稍候…");
            return;
        }
        busy = true;
        // 不把服务器 URL 刷到界面；完整地址只走下载器内部 Log
        uiLog.log("开始下载…");
        io.execute(() -> {
            try {
                File apk = installer.download(apkUrl, defaultName,
                        line -> mainHandler.post(() -> uiLog.log(line)));
                String result = installer.installApk(apk,
                        line -> mainHandler.post(() -> uiLog.log(line)));
                final boolean ok = InstallResultUi.looksSuccessful(result);
                mainHandler.post(() -> {
                    uiLog.log(result);
                    busy = false;
                    InstallResultUi.showFromInstallResult(MainActivity.this, result);
                    if (ok) {
                        refreshApps();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    uiLog.log("失败: " + e.getMessage());
                    busy = false;
                    InstallResultUi.show(MainActivity.this, false,
                            "安装失败：" + e.getMessage());
                });
            }
        });
    }

    // ------------------------------------------------------------------ adapter

    private final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_APP = 0;
        private static final int TYPE_FOOTER = 1;

        private final List<AppCatalogClient.AppEntry> items = new ArrayList<>();
        private String footerText;

        void resetVisible() {
            items.clear();
            footerText = null;
            notifyDataSetChanged();
        }

        void appendItems(List<AppCatalogClient.AppEntry> chunk) {
            int start = items.size();
            items.addAll(chunk);
            notifyItemRangeInserted(start, chunk.size());
        }

        void setFooter(String text) {
            boolean had = footerText != null;
            footerText = text;
            if (text == null) {
                if (had) {
                    notifyItemRemoved(items.size());
                }
            } else if (had) {
                notifyItemChanged(items.size());
            } else {
                notifyItemInserted(items.size());
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (footerText != null && position == items.size()) {
                return TYPE_FOOTER;
            }
            return TYPE_APP;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_FOOTER) {
                return new FooterVH(inflater.inflate(
                        R.layout.item_app_footer, parent, false));
            }
            return new AppVH(inflater.inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof FooterVH) {
                ((FooterVH) holder).text.setText(footerText);
                return;
            }
            AppCatalogClient.AppEntry e = items.get(position);
            AppVH h = (AppVH) holder;
            h.name.setText(e.name);
            StringBuilder meta = new StringBuilder(e.packageName);
            if (e.versionName != null && !e.versionName.isEmpty()) {
                meta.append("  v").append(e.versionName);
                if (e.versionCode >= 0) {
                    meta.append(" (").append(e.versionCode).append(')');
                }
            }
            if (e.desc != null && !e.desc.isEmpty()) {
                meta.append(" · ").append(e.desc);
            }
            h.meta.setText(meta.toString());
            bindState(h.state, e.installState);
            h.action.setText(AppCatalogClient.actionLabel(e.installState));
            h.action.setOnClickListener(v ->
                    downloadAndInstall(e.apkUrl, e.packageName + ".apk"));
        }

        @Override
        public int getItemCount() {
            return items.size() + (footerText != null ? 1 : 0);
        }

        private void bindState(TextView tv, AppCatalogClient.InstallState state) {
            switch (state) {
                case NOT_INSTALLED:
                    tv.setText("未安装");
                    tv.setTextColor(getColor(R.color.status_info));
                    break;
                case UPDATE_AVAILABLE:
                    tv.setText("可更新");
                    tv.setTextColor(getColor(R.color.status_warn));
                    break;
                case UP_TO_DATE:
                default:
                    tv.setText("已最新");
                    tv.setTextColor(getColor(R.color.status_ok));
                    break;
            }
        }
    }

    private static final class AppVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView state;
        final Button action;

        AppVH(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.item_app_name);
            meta = itemView.findViewById(R.id.item_app_meta);
            state = itemView.findViewById(R.id.item_app_state);
            action = itemView.findViewById(R.id.item_app_action);
        }
    }

    private static final class FooterVH extends RecyclerView.ViewHolder {
        final TextView text;

        FooterVH(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.item_footer_text);
        }
    }

    /** 列表行底部分割线（非卡片）。 */
    private final class DividerDecoration extends RecyclerView.ItemDecoration {
        private final Paint paint = new Paint();

        DividerDecoration() {
            paint.setColor(getColor(R.color.divider));
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            outRect.bottom = 1;
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent,
                           @NonNull RecyclerView.State state) {
            int left = parent.getPaddingLeft() + dp(24);
            int right = parent.getWidth() - parent.getPaddingRight() - dp(24);
            int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = parent.getChildAt(i);
                float top = child.getBottom();
                c.drawRect(left, top, right, top + 1, paint);
            }
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }
}
