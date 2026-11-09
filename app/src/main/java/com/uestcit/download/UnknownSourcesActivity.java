package com.uestcit.download;

import android.app.AppOpsManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 为本机声明了 REQUEST_INSTALL_PACKAGES 的包开关「安装未知应用」。
 * 逻辑与旧三 Tab 页一致，UI 独立出来方便从设置进入。
 */
public class UnknownSourcesActivity extends AppCompatActivity {

    private DownloadInstaller installer;
    private TextView textUnknownStatus;
    private UnknownAdapter unknownAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unknown_sources);

        installer = new DownloadInstaller(this);
        textUnknownStatus = findViewById(R.id.text_unknown_status);

        RecyclerView listUnknown = findViewById(R.id.list_unknown);
        listUnknown.setLayoutManager(new LinearLayoutManager(this));
        unknownAdapter = new UnknownAdapter();
        listUnknown.setAdapter(unknownAdapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_grant_self).setOnClickListener(v -> {
            String r = installer.unknownSources().grantSelf();
            Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
            refreshUnknown();
        });
        findViewById(R.id.btn_refresh_unknown).setOnClickListener(v -> refreshUnknown());

        refreshUnknown();
    }

    private void refreshUnknown() {
        UnknownSourcesController us = installer.unknownSources();
        textUnknownStatus.setText(
                "systemUid=" + us.isSystemUid()
                        + "  canRequest=" + us.canSelfInstallPackages()
                        + "  selfAppOps=" + us.peekSelfAppOpsLabel());
        List<UnknownSourcesController.Entry> entries = us.listRequestInstallPackages();
        unknownAdapter.setItems(entries);
    }

    private final class UnknownAdapter extends RecyclerView.Adapter<UnknownAdapter.VH> {
        private final List<UnknownSourcesController.Entry> items = new ArrayList<>();

        void setItems(List<UnknownSourcesController.Entry> list) {
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
                    .inflate(R.layout.item_unknown, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            UnknownSourcesController.Entry e = items.get(position);
            h.label.setText(e.label);
            h.pkg.setText(e.packageName + "  uid=" + e.uid
                    + "  " + UnknownSourcesController.modeToLabel(e.appOpsMode));
            // 先清监听，避免回收复用时误触发 revoke/grant
            h.sw.setOnCheckedChangeListener(null);
            boolean allowed = e.appOpsMode == AppOpsManager.MODE_ALLOWED
                    || e.canRequestInstalls;
            h.sw.setChecked(allowed);
            h.sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                String result = isChecked
                        ? installer.unknownSources().grant(e.packageName)
                        : installer.unknownSources().revoke(e.packageName);
                Toast.makeText(UnknownSourcesActivity.this, result, Toast.LENGTH_SHORT).show();
                refreshUnknown();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView pkg;
            final Switch sw;

            VH(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.item_unknown_label);
                pkg = itemView.findViewById(R.id.item_unknown_pkg);
                sw = itemView.findViewById(R.id.item_unknown_switch);
            }
        }
    }
}
