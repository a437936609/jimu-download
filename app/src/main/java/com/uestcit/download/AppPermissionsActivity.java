package com.uestcit.download;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.uestcit.download.perm.AppPermissionController;
import com.uestcit.download.perm.RuntimePermissionGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 单包权限管理页（自建）：列出<strong>全部</strong>危险运行时权限 + 特殊 AppOps。
 * <p>
 * 入口：{@link AppDetailActivity}；亦承接
 * {@code android.intent.action.MANAGE_APP_PERMISSIONS}（替代缺失 Settings）。
 */
public class AppPermissionsActivity extends AppCompatActivity {

    /** 目标包名 extra，与详情页一致。 */
    public static final String EXTRA_PACKAGE = AppDetailActivity.EXTRA_PACKAGE;

    private AppPermissionController controller;
    private String packageName;
    private TextView textPkg;
    private TextView textEmpty;
    private TextView textLog;
    private PermAdapter adapter;
    private final List<RuntimePermissionGroup> currentGroups = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_permissions);

        controller = new AppPermissionController(this);
        textPkg = findViewById(R.id.text_perm_pkg);
        textEmpty = findViewById(R.id.text_perm_empty);
        textLog = findViewById(R.id.text_perm_log);

        RecyclerView list = findViewById(R.id.list_permissions);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PermAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_grant_all).setOnClickListener(v -> onGrantAll());

        bindFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindFromIntent(intent);
    }

    private void bindFromIntent(@Nullable Intent intent) {
        packageName = extractPackage(intent);
        if (TextUtils.isEmpty(packageName)) {
            textPkg.setText(R.string.app_detail_missing);
            textEmpty.setVisibility(View.VISIBLE);
            setLog("未指定包名");
            return;
        }
        textPkg.setText(packageName);
        refresh();
    }

    private void refresh() {
        List<RuntimePermissionGroup> groups = controller.listGroups(packageName);
        currentGroups.clear();
        currentGroups.addAll(groups);
        adapter.setItems(groups);
        textEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        setLog(groups.isEmpty()
                ? getString(R.string.app_permissions_empty)
                : ("共 " + groups.size() + " 项可管理"));
    }

    private void onGrantAll() {
        if (TextUtils.isEmpty(packageName) || currentGroups.isEmpty()) {
            setLog("无可开启权限");
            return;
        }
        String msg = controller.grantAll(packageName, currentGroups);
        setLog(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        refresh();
    }

    @Nullable
    private static String extractPackage(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        if (data != null && "package".equals(data.getScheme())) {
            String ssp = data.getSchemeSpecificPart();
            if (!TextUtils.isEmpty(ssp)) {
                return ssp;
            }
        }
        String extra = intent.getStringExtra(EXTRA_PACKAGE);
        if (!TextUtils.isEmpty(extra)) {
            return extra;
        }
        extra = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (!TextUtils.isEmpty(extra)) {
            return extra;
        }
        // Settings / PackageInstaller MANAGE_APP_PERMISSIONS 常用 key
        extra = intent.getStringExtra("android.intent.extra.PACKAGE_NAME");
        if (!TextUtils.isEmpty(extra)) {
            return extra;
        }
        return intent.getStringExtra("package");
    }

    private void setLog(String text) {
        if (text == null) {
            return;
        }
        String one = text.replace('\n', ' ').trim();
        if (one.length() > 160) {
            one = one.substring(0, 157) + "…";
        }
        textLog.setText(one);
    }

    private final class PermAdapter extends RecyclerView.Adapter<PermAdapter.VH> {
        private final List<RuntimePermissionGroup> items = new ArrayList<>();

        void setItems(List<RuntimePermissionGroup> list) {
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
                    .inflate(R.layout.item_app_permission, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final RuntimePermissionGroup group = items.get(position);
            holder.label.setText(group.label);
            holder.detail.setText(group.detail);
            holder.sw.setOnCheckedChangeListener(null);
            boolean fixed = group.isSystemFixed();
            holder.sw.setEnabled(!fixed);
            holder.sw.setChecked(group.isGranted());
            holder.sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (!buttonView.isPressed()) {
                        return;
                    }
                    String msg;
                    if (isChecked) {
                        msg = controller.grantGroup(packageName, group);
                    } else {
                        msg = controller.revokeGroup(packageName, group);
                    }
                    setLog(msg);
                    Toast.makeText(AppPermissionsActivity.this, msg, Toast.LENGTH_SHORT).show();
                    holder.sw.setOnCheckedChangeListener(null);
                    holder.sw.setChecked(group.isGranted());
                    holder.sw.setOnCheckedChangeListener(this);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView detail;
            final Switch sw;

            VH(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.item_perm_label);
                detail = itemView.findViewById(R.id.item_perm_detail);
                sw = itemView.findViewById(R.id.item_perm_switch);
            }
        }
    }
}
