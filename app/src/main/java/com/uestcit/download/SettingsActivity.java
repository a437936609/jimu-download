package com.uestcit.download;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置：本包自更新、应用管理、内存分析、未知来源、二维码传 APK、U盘安装、厂内 XCDiagnostic，以及重启车机。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 厂内诊断入口，与 vendor XCDiagnostic 主 Activity 一致。 */
    private static final ComponentName DIAGNOSTIC_MAIN = new ComponentName(
            "com.ecarx.diagnostic",
            "com.ecarx.diagnostic.MainActivity");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DownloadInstaller installer;
    private UpdateChecker updateChecker;

    private TextView textLocalVersion;
    private TextView textUpdateStatus;
    private TextView textSettingsLog;
    private Button btnDownloadUpdate;

    private UpdateChecker.UpdateInfo lastUpdateInfo;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        installer = new DownloadInstaller(this);
        updateChecker = new UpdateChecker(this);

        textLocalVersion = findViewById(R.id.text_local_version);
        textUpdateStatus = findViewById(R.id.text_update_status);
        textSettingsLog = findViewById(R.id.text_settings_log);
        btnDownloadUpdate = findViewById(R.id.btn_download_update);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate());
        btnDownloadUpdate.setOnClickListener(v -> downloadAndInstallUpdate());
        findViewById(R.id.row_app_manage).setOnClickListener(v ->
                startActivity(new Intent(this, AppListActivity.class)));
        findViewById(R.id.row_mem_stats).setOnClickListener(v ->
                startActivity(new Intent(this, ProcessMemStatsActivity.class)));
        findViewById(R.id.row_unknown_sources).setOnClickListener(v ->
                startActivity(new Intent(this, UnknownSourcesActivity.class)));
        findViewById(R.id.row_qr_apk_transfer).setOnClickListener(v ->
                startActivity(new Intent(this, QrApkTransferActivity.class)));
        findViewById(R.id.row_usb_apk_install).setOnClickListener(v ->
                startActivity(new Intent(this, UsbApkInstallActivity.class)));
        findViewById(R.id.row_open_diagnostic).setOnClickListener(v -> openDiagnostic());
        findViewById(R.id.row_reboot_ihu).setOnClickListener(v -> confirmRebootIhu());

        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            textLocalVersion.setText(getString(R.string.local_version_fmt,
                    pi.versionName, pi.versionCode));
        } catch (Exception e) {
            textLocalVersion.setText("本机版本读取失败");
        }
    }

    /**
     * 显式拉起 XCDiagnostic；未安装或组件不可用时写入底栏 log。
     */
    private void openDiagnostic() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(DIAGNOSTIC_MAIN);
        try {
            startActivity(intent);
            setLog("已打开 Diagnostic");
        } catch (ActivityNotFoundException e) {
            setLog("Diagnostic 未安装或无法启动");
        } catch (Exception e) {
            String msg = e.getMessage();
            setLog("打开 Diagnostic 失败: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    /**
     * 二次确认后调用 {@link PowerManager#reboot(String)} 重启本机。
     * 需 Manifest REBOOT + system UID；失败时写底栏，不吞异常细节。
     */
    private void confirmRebootIhu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reboot_title)
                .setMessage(R.string.settings_reboot_msg)
                .setPositiveButton(R.string.app_dlg_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rebootIhu();
                    }
                })
                .setNegativeButton(R.string.app_dlg_cancel, null)
                .show();
    }

    /** 立即请求系统重启；正常路径进程会被干掉，走到 catch 才是失败。 */
    private void rebootIhu() {
        setLog("正在重启车机…");
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                setLog("重启失败: PowerManager 不可用");
                return;
            }
            pm.reboot(null);
        } catch (Exception e) {
            String msg = e.getMessage();
            setLog("重启失败: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void checkUpdate() {
        if (!tryBeginBusy()) {
            return;
        }
        setLog("检测自更新…");
        io.execute(() -> {
            try {
                UpdateChecker.UpdateInfo info =
                        updateChecker.check(UpdateChecker.DEFAULT_BASE);
                mainHandler.post(() -> {
                    lastUpdateInfo = info;
                    textUpdateStatus.setText(info.message);
                    btnDownloadUpdate.setEnabled(info.updateAvailable
                            || (info.apkUrl != null && !info.apkUrl.isEmpty()));
                    setLog(info.message);
                    endBusy();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    textUpdateStatus.setText("失败: " + e.getMessage());
                    setLog("检测失败: " + e.getMessage());
                    endBusy();
                });
            }
        });
    }

    private void downloadAndInstallUpdate() {
        if (lastUpdateInfo == null || lastUpdateInfo.apkUrl == null) {
            setLog("请先检测更新");
            return;
        }
        if (!tryBeginBusy()) {
            return;
        }
        final String apkUrl = lastUpdateInfo.apkUrl;
        setLog("开始下载…");
        io.execute(() -> {
            try {
                File apk = installer.download(apkUrl, "self-update.apk", line ->
                        mainHandler.post(() -> setLog(line)));
                String result = installer.installApk(apk, line ->
                        mainHandler.post(() -> setLog(line)));
                mainHandler.post(() -> {
                    setLog(result);
                    endBusy();
                    InstallResultUi.showFromInstallResult(SettingsActivity.this, result);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setLog("失败: " + e.getMessage());
                    endBusy();
                    InstallResultUi.show(SettingsActivity.this, false,
                            "安装失败：" + e.getMessage());
                });
            }
        });
    }

    private boolean tryBeginBusy() {
        if (busy) {
            setLog("忙，请稍候…");
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
        // 底栏单行摘要；过长截断尾部
        String one = text.replace('\n', ' ').trim();
        if (one.length() > 160) {
            one = one.substring(0, 157) + "…";
        }
        textSettingsLog.setText(one);
    }
}
