package com.uestcit.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.wifi.WifiConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.wifiap.WifiAp;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设置 → 二维码传 APK：只读展示原车 SoftAp 配置，仅开/关热点，本机 HTTP 出码。
 * 不写 {@code setWifiApConfiguration}；退出本页即停 HTTP，且仅关闭由本页打开的热点。
 */
public class QrApkTransferActivity extends AppCompatActivity {

    private static final String TAG = "QrApkTransfer";
    /** SoftAp 状态广播（隐藏 action，与 WifiApImpl 一致）。 */
    private static final String ACTION_WIFI_AP_STATE =
            "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final int WIFI_AP_STATE_ENABLED = 13;
    private static final int QR_SIZE_PX = 512;
    /** SoftAp IP 未就绪时轮询间隔。 */
    private static final long IP_POLL_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean restored = new AtomicBoolean(false);

    private SoftApController softAp;
    private DownloadInstaller installer;
    private LanApkReceiveServer server;

    /** 进页快照：只读展示，绝不写回。 */
    private WifiConfiguration displayApConfig;
    private boolean apWasEnabled;
    /** 本页调用 startSoftAp 打开的热点，退出时才 stopSoftAp。 */
    private volatile boolean weEnabledAp;

    private String lastQrUrl;
    private String lastCandidatesLog;
    /** 同一 URL 连续确认次数；够数后停止 IP 轮询。 */
    private int stableUrlHits;

    private ImageView imageQr;
    private TextView textSsid;
    private TextView textUrl;
    private TextView textStatus;
    private TextView textLog;

    private final BroadcastReceiver apStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            int state = intent.getIntExtra("wifi_state", 0);
            if (state == WIFI_AP_STATE_ENABLED) {
                appendLog("热点已开启");
                startReceiveIfNeeded();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_apk_transfer);

        softAp = new SoftApController(this);
        installer = new DownloadInstaller(this);

        imageQr = findViewById(R.id.image_qr);
        textSsid = findViewById(R.id.text_ssid);
        textUrl = findViewById(R.id.text_url);
        textStatus = findViewById(R.id.text_status);
        textLog = findViewById(R.id.text_log);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 只读快照：展示原车 SSID/密码，不改写配置
        displayApConfig = softAp.getApConfiguration();
        apWasEnabled = softAp.isApEnabled();
        weEnabledAp = false;
        refreshSsidUi();

        setStatus("准备热点…");
        appendLog("进页前热点=" + (apWasEnabled ? "已开" : "未开"));

        try {
            registerReceiver(apStateReceiver, new IntentFilter(ACTION_WIFI_AP_STATE));
        } catch (Exception e) {
            Log.w(TAG, "register AP receiver: " + e.getMessage());
        }

        probeAdaptApi();
        enableHotspotIfNeeded();
    }

    /** AdaptAPI：能力探测（开热点仍走 SoftApController）。 */
    private void probeAdaptApi() {
        try {
            WifiAp wifiAp = WifiAp.create(this);
            if (wifiAp == null) {
                appendLog("AdaptAPI WifiAp=null");
                return;
            }
            FunctionStatus st = wifiAp.isWifiAPSupported();
            appendLog("WifiAp.isWifiAPSupported=" + st);
        } catch (Throwable t) {
            appendLog("AdaptAPI 不可用: " + t.getClass().getSimpleName());
        }
    }

    /** 未开则 startSoftAp(null)；已开则直接起接收服务。 */
    private void enableHotspotIfNeeded() {
        if (apWasEnabled) {
            setStatus("热点已开，准备出码…");
            startReceiveIfNeeded();
            return;
        }
        io.execute(() -> {
            boolean ok = softAp.setApEnabled(true);
            if (ok) {
                weEnabledAp = true;
            }
            mainHandler.post(() -> {
                if (ok) {
                    setStatus("正在开启热点…");
                    appendLog("startSoftAp(null)=true");
                    if (softAp.isApEnabled()) {
                        startReceiveIfNeeded();
                    } else {
                        // 等广播；短延迟兜底再试
                        mainHandler.postDelayed(this::startReceiveIfNeeded, 2500);
                    }
                } else {
                    setStatus("自动开热点失败，请手动打开车机 WiFi 热点后仍可出码");
                    appendLog("开热点失败");
                    startReceiveIfNeeded();
                }
            });
        });
    }

    private void startReceiveIfNeeded() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        synchronized (this) {
            if (server != null && server.port() > 0) {
                refreshQrUi();
                return;
            }
            // 占位，避免广播/延迟重复起多个 ServerSocket
            if (server != null) {
                return;
            }
            server = new LanApkReceiveServer(
                    resolveSaveDir(),
                    new LanApkReceiveServer.Listener() {
                        @Override
                        public void onStatus(String line) {
                            mainHandler.post(() -> {
                                setStatus(line);
                                appendLog(line);
                            });
                        }

                        @Override
                        public void onApkReceived(File apkFile) {
                            mainHandler.post(() -> installReceived(apkFile));
                        }
                    });
        }
        final LanApkReceiveServer s = server;
        io.execute(() -> {
            try {
                s.start();
                mainHandler.post(() -> {
                    setStatus("等待手机扫码上传…");
                    refreshQrUi();
                });
            } catch (Exception e) {
                synchronized (QrApkTransferActivity.this) {
                    if (server == s) {
                        server = null;
                    }
                }
                mainHandler.post(() -> {
                    setStatus("启动接收失败: " + e.getMessage());
                    appendLog("HTTP: " + e.getMessage());
                });
            }
        });
    }

    private File resolveSaveDir() {
        File dir = getExternalFilesDir("downloads");
        if (dir == null) {
            dir = new File(getFilesDir(), "downloads");
        }
        return dir;
    }

    /** 展示原车热点名/密码（只读）。 */
    private void refreshSsidUi() {
        String ssid = SoftApController.displaySsid(displayApConfig);
        String pass = SoftApController.displayPassword(displayApConfig);
        if (pass.isEmpty()) {
            pass = "（请看系统热点设置）";
        }
        textSsid.setText(getString(R.string.qr_apk_ssid_fmt, ssid, pass));
    }

    private void refreshQrUi() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (server == null || server.port() <= 0) {
            return;
        }
        List<LanApkReceiveServer.Ipv4Candidate> candidates =
                LanApkReceiveServer.listLanIpv4Candidates();
        StringBuilder sb = new StringBuilder("网卡候选");
        for (LanApkReceiveServer.Ipv4Candidate c : candidates) {
            sb.append(' ').append(c.iface).append('=').append(c.host)
                    .append('(').append(c.score).append(')');
        }
        String candLine = sb.toString();
        if (!candLine.equals(lastCandidatesLog)) {
            lastCandidatesLog = candLine;
            appendLog(candLine);
        }
        String ip = LanApkReceiveServer.pickLanIpv4(candidates);
        if (ip == null) {
            stableUrlHits = 0;
            setStatus("等待局域网 IP（请确认车机热点已开）…");
            textUrl.setText(getString(R.string.qr_apk_url_fmt, "（暂无 SoftAp IP）"));
            // SoftAp 未出 IP：不拿其它网卡凑合出码
            mainHandler.postDelayed(this::refreshQrUi, IP_POLL_MS);
            return;
        }
        String url = server.buildUrl(ip);
        textUrl.setText(getString(R.string.qr_apk_url_fmt, url));
        refreshSsidUi();
        // IP 变化或首次：重绘二维码
        if (!url.equals(lastQrUrl)) {
            stableUrlHits = 0;
            lastQrUrl = url;
            try {
                Bitmap bmp = QrBitmapEncoder.encode(url, QR_SIZE_PX);
                imageQr.setImageBitmap(bmp);
                String ssid = SoftApController.displaySsid(displayApConfig);
                setStatus("请连接「" + ssid + "」后扫码（或用浏览器打开地址）");
                appendLog("出码 " + url);
            } catch (Exception e) {
                appendLog("二维码生成失败: " + e.getMessage());
            }
        } else {
            stableUrlHits++;
        }
        // SoftAp 刚起来时 IP 可能晚到/切换，连续稳定几次后停轮询
        if (stableUrlHits < 3) {
            mainHandler.postDelayed(this::refreshQrUi, IP_POLL_MS * 2);
        }
    }

    private void installReceived(File apk) {
        // SoftAP 收包：状态不含服务器 URL；本地文件名可给用户确认收到哪包
        setStatus("正在安装…");
        appendLog("正在安装收到的安装包…\n");
        io.execute(() -> {
            try {
                installer.canInstallPackagesOrEnsure();
                String result = installer.installApk(apk, line ->
                        mainHandler.post(() -> appendLog(line)));
                final boolean ok = InstallResultUi.looksSuccessful(result);
                final String summary = result != null
                        ? result.replace('\n', ' ').trim() : "";
                mainHandler.post(() -> {
                    setStatus(ok ? "安装成功" : summary);
                    appendLog(result);
                    InstallResultUi.showFromInstallResult(
                            QrApkTransferActivity.this, result);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setStatus("安装失败");
                    appendLog("install: " + e.getMessage());
                    InstallResultUi.show(QrApkTransferActivity.this, false,
                            "安装失败：" + e.getMessage());
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(apStateReceiver);
        } catch (Exception ignored) {
            // 可能未注册成功
        }
        stopServer();
        restoreHotspot();
        io.shutdownNow();
        super.onDestroy();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            appendLog("已停止 HTTP 接收");
        }
    }

    /**
     * 退出页：绝不写 WifiConfiguration；仅当我们打开的热点才关闭。
     */
    private void restoreHotspot() {
        if (!restored.compareAndSet(false, true)) {
            return;
        }
        try {
            if (weEnabledAp) {
                softAp.setApEnabled(false);
                appendLog("已关闭由本页打开的热点");
            } else {
                appendLog("热点保持进页前状态（未改配置）");
            }
        } catch (Exception e) {
            Log.w(TAG, "restoreHotspot: " + e.getMessage());
        }
    }

    private void setStatus(String s) {
        if (s != null) {
            textStatus.setText(s);
        }
    }

    private void appendLog(String s) {
        if (s == null || textLog == null) {
            return;
        }
        String one = s.replace('\n', ' ').trim();
        if (one.length() > 200) {
            one = one.substring(0, 197) + "…";
        }
        CharSequence old = textLog.getText();
        if (old == null || old.length() == 0) {
            textLog.setText(one);
        } else {
            textLog.setText(old + " · " + one);
        }
    }
}
