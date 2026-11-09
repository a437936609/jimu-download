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

import java.util.ArrayList;
import java.util.List;

/**
 * 承接系统 {@link Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES}，
 * 替代缺失的 Settings.apk：其它应用申请「安装未知应用」时跳到本页。
 * <ul>
 *   <li>带 {@code package:} data → 单包授权（允许/拒绝/允许并返回）</li>
 *   <li>无 data → 列出声明了 REQUEST_INSTALL_PACKAGES 的包，Switch 开关</li>
 * </ul>
 * Manifest 须 exported + 双 intent-filter（有/无 package scheme），否则系统解析不到。
 */
public final class ManageUnknownSourcesActivity extends AppCompatActivity {

    private UnknownSourcesController controller;
    private TextView textLog;
    private TextView textTargetLabel;
    private TextView textTargetPkg;
    private TextView textTargetStatus;
    private View panelSingle;
    private View panelList;
    private ListView listView;
    private EntryAdapter adapter;

    /** 单包模式下的目标包名；列表模式为 null。 */
    @Nullable
    private String targetPackage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_unknown);
        controller = new UnknownSourcesController(this);

        textLog = findViewById(R.id.text_log);
        textTargetLabel = findViewById(R.id.text_target_label);
        textTargetPkg = findViewById(R.id.text_target_pkg);
        textTargetStatus = findViewById(R.id.text_target_status);
        panelSingle = findViewById(R.id.panel_single);
        panelList = findViewById(R.id.panel_list);
        listView = findViewById(R.id.list_unknown);
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

    /**
     * 从 Intent 解析目标包：优先 data {@code package:xxx}，
     * 其次 {@link Intent#EXTRA_PACKAGE_NAME}（部分 OEM 会带）。
     */
    private void bindFromIntent(Intent intent) {
        targetPackage = extractTargetPackage(intent);
        String caller = getCallingPackage();
        StringBuilder head = new StringBuilder();
        head.append("action=").append(intent != null ? intent.getAction() : null)
                .append('\n');
        if (!TextUtils.isEmpty(caller)) {
            head.append("caller=").append(caller).append('\n');
        }
        head.append("systemUid=").append(controller.isSystemUid()).append('\n');
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
        String extra = intent.getStringExtra("android.intent.extra.PACKAGE_NAME");
        if (TextUtils.isEmpty(extra)) {
            extra = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
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
        textTargetStatus.setText(controller.describePackage(pkg));
    }

    private void applySingle(boolean allow, boolean finishAfter) {
        if (TextUtils.isEmpty(targetPackage)) {
            return;
        }
        String result = allow
                ? controller.grant(targetPackage)
                : controller.revoke(targetPackage);
        appendLog(result);
        bindSingle(targetPackage);
        Toast.makeText(this,
                allow ? R.string.toast_unknown_allowed : R.string.toast_unknown_denied,
                Toast.LENGTH_SHORT).show();
        if (finishAfter) {
            // 允许调用方（startActivityForResult）收到 OK 后继续安装流程
            setResult(RESULT_OK);
            finish();
        }
    }

    private void refreshList() {
        List<UnknownSourcesController.Entry> entries =
                controller.listRequestInstallPackages();
        adapter.setItems(entries);
        appendLog("列表刷新：" + entries.size() + " 包\n");
    }

    private void appendLog(String line) {
        CharSequence cur = textLog.getText();
        textLog.setText(cur == null ? line : cur.toString() + line);
    }

    private final class EntryAdapter extends BaseAdapter {
        private final List<UnknownSourcesController.Entry> items = new ArrayList<>();

        void setItems(List<UnknownSourcesController.Entry> list) {
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
        public UnknownSourcesController.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_unknown_source, parent, false);
            }
            UnknownSourcesController.Entry e = getItem(position);
            TextView label = row.findViewById(R.id.item_unknown_label);
            TextView pkg = row.findViewById(R.id.item_unknown_pkg);
            Switch sw = row.findViewById(R.id.item_unknown_switch);
            label.setText(e.label);
            pkg.setText(e.packageName + "  "
                    + UnknownSourcesController.modeToLabel(e.appOpsMode));
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(e.appOpsMode == android.app.AppOpsManager.MODE_ALLOWED);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                String r = isChecked
                        ? controller.grant(e.packageName)
                        : controller.revoke(e.packageName);
                appendLog(r);
                refreshList();
            });
            return row;
        }
    }
}
