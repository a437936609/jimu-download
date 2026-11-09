package com.uestcit.download;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 单包应用详情：强停 / 停用·启用 / 清缓存 / 清数据 / 权限管理 / 卸载·卸载更新。
 * 亦承接 {@link Settings#ACTION_APPLICATION_DETAILS_SETTINGS}（{@code package:} data）。
 * 车机无 Settings.apk，权限与卸载均自建。
 */
public class AppDetailActivity extends AppCompatActivity {

    /** 列表页传入的目标包名。 */
    public static final String EXTRA_PACKAGE = "package_name";

    private PackageAdminController admin;
    private String packageName;

    private ImageView imgIcon;
    private TextView textLabel;
    private TextView textPkg;
    private TextView textMeta;
    private TextView textStorage;
    private TextView textLog;
    private Button btnForceStop;
    private Button btnToggleEnable;
    private Button btnClearCache;
    private Button btnClearData;
    private Button btnPermissions;
    private Button btnUninstall;

    /** 当前卸载按钮是否为「卸载更新」（否则为第三方「卸载」）。 */
    private boolean uninstallIsUpdates;

    private volatile boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_detail);

        admin = new PackageAdminController(this);
        imgIcon = findViewById(R.id.img_app_icon);
        textLabel = findViewById(R.id.text_app_label);
        textPkg = findViewById(R.id.text_app_pkg);
        textMeta = findViewById(R.id.text_app_meta);
        textStorage = findViewById(R.id.text_app_storage);
        textLog = findViewById(R.id.text_detail_log);
        btnForceStop = findViewById(R.id.btn_force_stop);
        btnToggleEnable = findViewById(R.id.btn_toggle_enable);
        btnClearCache = findViewById(R.id.btn_clear_cache);
        btnClearData = findViewById(R.id.btn_clear_data);
        btnPermissions = findViewById(R.id.btn_permissions);
        btnUninstall = findViewById(R.id.btn_uninstall);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnForceStop.setOnClickListener(v -> confirmForceStop());
        btnToggleEnable.setOnClickListener(v -> onToggleEnable());
        btnClearCache.setOnClickListener(v -> confirmClearCache());
        btnClearData.setOnClickListener(v -> confirmClearData());
        btnPermissions.setOnClickListener(v -> openPermissions());
        btnUninstall.setOnClickListener(v -> confirmUninstall());

        bindFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindFromIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从权限页返回后刷新底栏；包若已被卸掉则 refreshUi 会禁用按钮
        if (!TextUtils.isEmpty(packageName)) {
            refreshUi();
        }
    }

    /**
     * 解析目标包：优先 Intent data {@code package:xxx}，
     * 其次 {@link #EXTRA_PACKAGE} / {@link Intent#EXTRA_PACKAGE_NAME}。
     */
    private void bindFromIntent(@Nullable Intent intent) {
        packageName = extractPackage(intent);
        if (TextUtils.isEmpty(packageName)) {
            setLog("未指定包名");
            disableAllActions();
            return;
        }
        refreshUi();
    }

    @Nullable
    static String extractPackage(@Nullable Intent intent) {
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
        return intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
    }

    private void refreshUi() {
        PackageAdminController.AppEntry entry = admin.getApp(packageName);
        if (entry == null) {
            textLabel.setText(R.string.app_detail_missing);
            textPkg.setText(packageName);
            textMeta.setText("");
            textStorage.setVisibility(View.GONE);
            setLog("包未安装或不存在");
            disableAllActions();
            return;
        }
        if (entry.icon != null) {
            imgIcon.setImageDrawable(entry.icon);
        }
        textLabel.setText(entry.label);
        textPkg.setText(entry.packageName);
        String kind = entry.systemApp
                ? getString(R.string.app_badge_system)
                : getString(R.string.app_badge_user);
        String state = entry.enabled
                ? getString(R.string.app_state_enabled)
                : getString(R.string.app_state_disabled);
        textMeta.setText(getString(R.string.app_detail_meta_fmt,
                entry.versionName, entry.versionCode, kind, state));

        boolean self = admin.isSelf(packageName);
        btnForceStop.setEnabled(!self && entry.enabled);
        btnClearData.setEnabled(!self);
        btnClearCache.setEnabled(true);
        btnPermissions.setEnabled(true);
        if (entry.enabled) {
            btnToggleEnable.setText(R.string.app_action_disable);
        } else {
            btnToggleEnable.setText(R.string.app_action_enable);
        }
        btnToggleEnable.setEnabled(!self);

        // 卸载按钮：第三方→卸载；已更新系统→卸载更新；纯系统→隐藏（用停用）
        uninstallIsUpdates = false;
        if (self) {
            btnUninstall.setVisibility(View.GONE);
        } else if (entry.updatedSystemApp) {
            uninstallIsUpdates = true;
            btnUninstall.setVisibility(View.VISIBLE);
            btnUninstall.setEnabled(true);
            btnUninstall.setText(R.string.app_action_uninstall_updates);
        } else if (!entry.systemApp) {
            btnUninstall.setVisibility(View.VISIBLE);
            btnUninstall.setEnabled(true);
            btnUninstall.setText(R.string.app_action_uninstall);
        } else {
            btnUninstall.setVisibility(View.GONE);
        }

        textStorage.setVisibility(View.VISIBLE);
        textStorage.setText(R.string.app_storage_loading);
        final String pkg = packageName;
        PackageAdminController.runAsync(
                () -> {
                    final PackageAdminController.StorageInfo info = admin.queryStorage(pkg);
                    final String summary = admin.formatStorageSummary(info);
                    runOnUiThread(() -> {
                        if (!pkg.equals(packageName)) {
                            return;
                        }
                        if (summary == null) {
                            textStorage.setText(R.string.app_storage_unavailable);
                        } else {
                            textStorage.setText(summary);
                        }
                    });
                },
                null);
        setLog("systemUid=" + admin.isSystemUid());
    }

    private void disableAllActions() {
        btnForceStop.setEnabled(false);
        btnToggleEnable.setEnabled(false);
        btnClearCache.setEnabled(false);
        btnClearData.setEnabled(false);
        btnPermissions.setEnabled(false);
        btnUninstall.setEnabled(false);
        btnUninstall.setVisibility(View.GONE);
    }

    /** 打开自建权限管理页。 */
    private void openPermissions() {
        if (TextUtils.isEmpty(packageName)) {
            setLog("未指定包名");
            return;
        }
        Intent intent = new Intent(this, AppPermissionsActivity.class);
        intent.putExtra(AppPermissionsActivity.EXTRA_PACKAGE, packageName);
        startActivity(intent);
    }

    private void confirmForceStop() {
        if (!tryBeginBusy()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_force_stop_title)
                .setMessage(R.string.app_force_stop_msg)
                .setPositiveButton(R.string.app_dlg_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runOp(() -> admin.forceStop(packageName));
                    }
                })
                .setNegativeButton(R.string.app_dlg_cancel, (d, w) -> endBusy())
                .setOnCancelListener(d -> endBusy())
                .show();
    }

    private void onToggleEnable() {
        PackageAdminController.AppEntry entry = admin.getApp(packageName);
        if (entry == null) {
            setLog("包未安装");
            return;
        }
        if (entry.enabled) {
            if (!tryBeginBusy()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_disable_title)
                    .setMessage(R.string.app_disable_msg)
                    .setPositiveButton(R.string.app_action_disable,
                            (d, w) -> runOp(() -> admin.disable(packageName)))
                    .setNegativeButton(R.string.app_dlg_cancel, (d, w) -> endBusy())
                    .setOnCancelListener(d -> endBusy())
                    .show();
        } else {
            if (!tryBeginBusy()) {
                return;
            }
            runOp(() -> admin.enable(packageName));
        }
    }

    private void confirmClearCache() {
        if (!tryBeginBusy()) {
            return;
        }
        runOp(() -> admin.clearCache(packageName));
    }

    private void confirmClearData() {
        if (!tryBeginBusy()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_clear_data_title)
                .setMessage(R.string.app_clear_data_msg)
                .setPositiveButton(R.string.app_action_clear_data,
                        (d, w) -> runOp(() -> admin.clearData(packageName)))
                .setNegativeButton(R.string.app_dlg_cancel, (d, w) -> endBusy())
                .setOnCancelListener(d -> endBusy())
                .show();
    }

    /**
     * 确认后卸载或卸载更新。第三方成功后 finish 回列表；
     * 卸更新后刷新（预装底包仍在）。
     */
    private void confirmUninstall() {
        if (!tryBeginBusy()) {
            return;
        }
        final boolean updates = uninstallIsUpdates;
        int title = updates
                ? R.string.app_uninstall_updates_title
                : R.string.app_uninstall_title;
        int msg = updates
                ? R.string.app_uninstall_updates_msg
                : R.string.app_uninstall_msg;
        int positive = updates
                ? R.string.app_action_uninstall_updates
                : R.string.app_action_uninstall;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(positive, (d, w) -> runUninstallOp(updates))
                .setNegativeButton(R.string.app_dlg_cancel, (d, w) -> endBusy())
                .setOnCancelListener(d -> endBusy())
                .show();
    }

    private void runUninstallOp(final boolean updates) {
        final String[] result = new String[1];
        PackageAdminController.runAsync(
                () -> result[0] = updates
                        ? admin.uninstallUpdates(packageName)
                        : admin.uninstall(packageName),
                () -> {
                    String msg = result[0] != null ? result[0] : "完成";
                    setLog(msg);
                    Toast.makeText(AppDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                    endBusy();
                    if (!updates && admin.getApp(packageName) == null) {
                        // 第三方已卸掉：关闭详情回列表
                        finish();
                        return;
                    }
                    refreshUi();
                });
    }

    /**
     * 后台执行破坏性操作；结果 Toast + 底栏，并刷新 UI。
     *
     * @param op 阻塞调用（可能等 IPackageDataObserver）
     */
    private void runOp(final ResultOp op) {
        final String[] result = new String[1];
        PackageAdminController.runAsync(
                () -> result[0] = op.run(),
                () -> {
                    String msg = result[0] != null ? result[0] : "完成";
                    setLog(msg);
                    Toast.makeText(AppDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                    endBusy();
                    refreshUi();
                });
    }

    private boolean tryBeginBusy() {
        if (busy) {
            setLog("忙，请稍候…");
            return false;
        }
        if (TextUtils.isEmpty(packageName)) {
            setLog("未指定包名");
            return false;
        }
        busy = true;
        return true;
    }

    private void endBusy() {
        busy = false;
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

    /** 后台包操作返回可读文案。 */
    private interface ResultOp {
        String run();
    }
}
