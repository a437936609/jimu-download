package com.uestcit.download;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机已装应用列表（替代 Settings「应用」页）。
 * 筛选：全部 / 第三方 / 已停用；支持名称与包名搜索。
 */
public class AppListActivity extends AppCompatActivity {

    private PackageAdminController admin;
    private AppAdapter adapter;
    private TextView textStatus;
    private Button btnFilterAll;
    private Button btnFilterThird;
    private Button btnFilterDisabled;
    private EditText editSearch;

    private int filter = PackageAdminController.FILTER_ALL;
    private String query = "";
    /** 丢弃过期异步刷新结果。 */
    private final AtomicInteger loadGen = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        admin = new PackageAdminController(this);
        textStatus = findViewById(R.id.text_app_list_status);
        btnFilterAll = findViewById(R.id.btn_filter_all);
        btnFilterThird = findViewById(R.id.btn_filter_third);
        btnFilterDisabled = findViewById(R.id.btn_filter_disabled);
        editSearch = findViewById(R.id.edit_app_search);

        RecyclerView list = findViewById(R.id.list_apps);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh_apps).setOnClickListener(v -> reload());
        btnFilterAll.setOnClickListener(v -> setFilter(PackageAdminController.FILTER_ALL));
        btnFilterThird.setOnClickListener(v -> setFilter(PackageAdminController.FILTER_THIRD_PARTY));
        btnFilterDisabled.setOnClickListener(v -> setFilter(PackageAdminController.FILTER_DISABLED));

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s != null ? s.toString() : "";
                reload();
            }
        });

        updateFilterButtons();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 详情页停用/启用后回到列表需刷新状态角标
        reload();
    }

    private void setFilter(int newFilter) {
        filter = newFilter;
        updateFilterButtons();
        reload();
    }

    private void updateFilterButtons() {
        styleFilter(btnFilterAll, filter == PackageAdminController.FILTER_ALL);
        styleFilter(btnFilterThird, filter == PackageAdminController.FILTER_THIRD_PARTY);
        styleFilter(btnFilterDisabled, filter == PackageAdminController.FILTER_DISABLED);
    }

    private void styleFilter(Button btn, boolean selected) {
        btn.setBackgroundResource(selected
                ? R.drawable.bg_btn_accent
                : R.drawable.bg_btn_ghost);
        btn.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.bg : R.color.text_primary));
    }

    private void reload() {
        final int gen = loadGen.incrementAndGet();
        textStatus.setText(R.string.app_manage_loading);
        final int f = filter;
        final String q = query;
        PackageAdminController.runAsync(
                () -> {
                    final List<PackageAdminController.AppEntry> items = admin.listApps(f, q);
                    runOnUiThread(() -> {
                        if (gen != loadGen.get()) {
                            return;
                        }
                        adapter.setItems(items);
                        textStatus.setText(getString(R.string.app_manage_count_fmt, items.size()));
                    });
                },
                null);
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        private final List<PackageAdminController.AppEntry> items = new ArrayList<>();

        void setItems(List<PackageAdminController.AppEntry> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_manage, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final PackageAdminController.AppEntry e = items.get(position);
            h.label.setText(e.label);
            h.pkg.setText(e.packageName);
            if (e.icon != null) {
                h.icon.setImageDrawable(e.icon);
            } else {
                h.icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
            }
            if (!e.enabled) {
                h.badge.setText(R.string.app_badge_disabled);
                h.badge.setTextColor(ContextCompat.getColor(AppListActivity.this, R.color.status_warn));
            } else if (e.systemApp) {
                h.badge.setText(R.string.app_badge_system);
                h.badge.setTextColor(ContextCompat.getColor(AppListActivity.this, R.color.text_muted));
            } else {
                h.badge.setText(R.string.app_badge_user);
                h.badge.setTextColor(ContextCompat.getColor(AppListActivity.this, R.color.status_ok));
            }
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(AppListActivity.this, AppDetailActivity.class);
                intent.putExtra(AppDetailActivity.EXTRA_PACKAGE, e.packageName);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView pkg;
            final TextView badge;

            VH(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.item_app_icon);
                label = itemView.findViewById(R.id.item_app_label);
                pkg = itemView.findViewById(R.id.item_app_pkg);
                badge = itemView.findViewById(R.id.item_app_badge);
            }
        }
    }
}
