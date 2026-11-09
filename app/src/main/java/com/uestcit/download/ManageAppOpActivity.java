package com.uestcit.download;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.uestcit.download.perm.SpecialAppOps;

import java.util.ArrayList;
import java.util.List;

/**
 * 特殊权限授权页备选实现（无 Settings.apk 时申请会没反应）。
 * <p>
 * 注册对应 Intent，但不设高 priority、不禁用系统组件；有多个处理器时由系统选择器让用户选。
 * 覆盖：悬浮窗、改系统设置、使用情况、通知监听、勿扰策略。
 * 带 {@code package:} data → 单包允许/拒绝；无 data → 候选包列表。
 */
public final class ManageAppOpActivity extends AppCompatActivity {

    private SpecialAppOps ops;
    private SpecialAppOps.Kind kind;
    private TextView textTitle;
    private TextView textHint;
    private TextView textLog;
    private TextView textTargetLabel;
    private TextView textTargetPkg;
    private TextView textTargetStatus;
    private View panelSingle;
    private View panelList;
    private ListView listView;
    private EntryAdapter adapter;

    @Nullable
    private String targetPackage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_app_op);
        ops = new SpecialAppOps(this);

        textTitle = findViewById(R.id.text_title);
        textHint = findViewById(R.id.text_hint);
        textLog = findViewById(R.id.text_log);
        textTargetLabel = findViewById(R.id.text_target_label);
        textTargetPkg = findViewById(R.id.text_target_pkg);
        textTargetStatus = findViewById(R.id.text_target_status);
        panelSingle = findViewById(R.id.panel_single);
        panelList = findViewById(R.id.panel_list);
        listView = findViewById(R.id.list_entries);
        adapter = new EntryAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.btn_allow).setOnClickListener(v -> applySingle(true, false));
        findViewById(R.id.btn_deny).setOnClickListener(v -> applySingle(false, false));
        findViewById(R.id.btn_allow_finish).setOnClickListener(v -> applySingle(true, true));
        findViewById(R.id.btn_refresh_list).setOnClickListener(v -> refreshList());

        bindFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindFromIntent(intent);
    }

    private void bindFromIntent(Intent intent) {
        kind = resolveKind(intent);
        textTitle.setText(kind.label);
        textHint.setText(getString(R.string.hint_manage_app_op, kind.label));

        targetPackage = extractTargetPackage(intent);
        // 无 data 时用 callingPackage（应用 startActivityForResult 申请本权限）
        if (TextUtils.isEmpty(targetPackage)) {
            String caller = getCallingPackage();
            if (!TextUtils.isEmpty(caller) && !getPackageName().equals(caller)) {
                targetPackage = caller;
            }
        }

        StringBuilder head = new StringBuilder();
        head.append("action=").append(intent != null ? intent.getAction() : null)
                .append('\n');
        String caller = getCallingPackage();
        if (!TextUtils.isEmpty(caller)) {
            head.append("caller=").append(caller).append('\n');
        }
        head.append("systemUid=").append(ops.isSystemUid()).append('\n');
        textLog.setText(head.toString());

        if (!TextUtils.isEmpty(targetPackage)) {
            panelSingle.setVisibility(View.VISIBLE);
            panelList.setVisibility(View.GONE);
            bindSingle(targetPackage);
        } else {
            panelSingle.setVisibility(View.GONE);
            panelList.setVisibility(View.VISIBLE);
            refreshList();
        }
    }

    private static SpecialAppOps.Kind resolveKind(@Nullable Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Settings.ACTION_MANAGE_WRITE_SETTINGS.equals(action)) {
            return SpecialAppOps.WRITE_SETTINGS;
        }
        if (Settings.ACTION_USAGE_ACCESS_SETTINGS.equals(action)) {
            return SpecialAppOps.USAGE_STATS;
        }
        // 通知监听设置（无 Settings 时备选）
        if ("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS".equals(action)
                || Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.equals(action)) {
            return SpecialAppOps.NOTIFICATION_LISTENER;
        }
        if (Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS.equals(action)
                || "android.settings.NOTIFICATION_POLICY_ACCESS_GRANTED_SETTINGS"
                .equals(action)) {
            return SpecialAppOps.NOTIFICATION_POLICY;
        }
        // 默认悬浮窗（含 ACTION_MANAGE_OVERLAY_PERMISSION）
        return SpecialAppOps.OVERLAY;
    }

    @Nullable
    static String extractTargetPackage(@Nullable Intent intent) {
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
        String extra = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (TextUtils.isEmpty(extra)) {
            extra = intent.getStringExtra("android.intent.extra.PACKAGE_NAME");
        }
        return TextUtils.isEmpty(extra) ? null : extra;
    }

    private void bindSingle(String pkg) {
        textTargetPkg.setText(pkg);
        String label = pkg;
        try {
            CharSequence l = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
            if (l != null) {
                label = l.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg + "（未安装）";
        }
        textTargetLabel.setText(label);
        textTargetStatus.setText(ops.describe(pkg, kind));
    }

    private void applySingle(boolean allow, boolean finishAfter) {
        if (TextUtils.isEmpty(targetPackage) || kind == null) {
            return;
        }
        String result = ops.setAllowed(targetPackage, kind, allow);
        appendLog(result + "\n");
        bindSingle(targetPackage);
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
        if (finishAfter) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void refreshList() {
        List<SpecialAppOps.Entry> entries = ops.listDeclaring(kind);
        adapter.setItems(entries);
        appendLog("列表刷新：" + entries.size() + " 包\n");
    }

    private void appendLog(String line) {
        CharSequence cur = textLog.getText();
        textLog.setText(cur == null ? line : cur.toString() + line);
    }

    private final class EntryAdapter extends BaseAdapter {
        private final List<SpecialAppOps.Entry> items = new ArrayList<>();

        void setItems(List<SpecialAppOps.Entry> list) {
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
        public SpecialAppOps.Entry getItem(int position) {
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
                        .inflate(R.layout.item_unknown_source, parent, false);
            }
            final SpecialAppOps.Entry e = getItem(position);
            TextView label = v.findViewById(R.id.item_unknown_label);
            TextView pkg = v.findViewById(R.id.item_unknown_pkg);
            Switch sw = v.findViewById(R.id.item_unknown_switch);
            label.setText(e.label);
            pkg.setText(e.packageName);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(e.allowed);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                String msg = ops.setAllowed(e.packageName, kind, isChecked);
                Toast.makeText(ManageAppOpActivity.this, msg, Toast.LENGTH_SHORT).show();
                appendLog(msg + "\n");
                refreshList();
            });
            return v;
        }
    }
}
