package com.uestcit.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置 → U盘安装：单层目录浏览（文件夹 + APK 候选），点选后静默安装且不删 U 盘源文件。
 */
public class UsbApkInstallActivity extends AppCompatActivity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DownloadInstaller installer;
    private UiLog uiLog;

    private TextView textPath;
    private TextView textEmpty;
    private Button btnUp;
    private Button btnInstall;
    private UsbListAdapter adapter;

    /** 当前 U 盘根；拔盘后清空。 */
    private File usbRoot;
    /** 当前浏览目录（默认等于根）。 */
    private File currentDir;
    private File selectedApk;
    private volatile boolean busy;

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 插拔后重新探测并刷新列表
            mainHandler.post(() -> reconnectAndList());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_apk_install);

        installer = new DownloadInstaller(this);
        textPath = findViewById(R.id.text_usb_path);
        textEmpty = findViewById(R.id.text_usb_empty);
        btnUp = findViewById(R.id.btn_usb_up);
        btnInstall = findViewById(R.id.btn_usb_install);
        RecyclerView list = findViewById(R.id.list_usb);
        TextView logView = findViewById(R.id.log_view);
        ScrollView logScroll = findViewById(R.id.log_scroll);
        uiLog = new UiLog(logView, logScroll);

        adapter = new UsbListAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnUp.setOnClickListener(v -> goUp());
        findViewById(R.id.btn_usb_refresh).setOnClickListener(v -> reconnectAndList());
        btnInstall.setOnClickListener(v -> installSelected());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        registerReceiver(mediaReceiver, filter);

        reconnectAndList();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(mediaReceiver);
        } catch (Exception ignored) {
            // 可能未注册
        }
        io.shutdownNow();
        super.onDestroy();
    }

    /** 重新探测 U 盘根并列出第一层。 */
    private void reconnectAndList() {
        usbRoot = UsbVolumeHelper.primaryUsbRoot(this);
        selectedApk = null;
        if (usbRoot == null) {
            currentDir = null;
            textPath.setText(R.string.usb_apk_not_connected);
            adapter.setItems(new ArrayList<UsbDirBrowser.Entry>());
            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText(R.string.usb_apk_not_connected);
            btnUp.setEnabled(false);
            uiLog.log("未检测到 U 盘");
            return;
        }
        currentDir = usbRoot;
        listCurrent();
        uiLog.log("U 盘: " + usbRoot.getAbsolutePath());
    }

    private void listCurrent() {
        if (currentDir == null) {
            return;
        }
        List<UsbDirBrowser.Entry> entries = UsbDirBrowser.listVisible(currentDir);
        adapter.setItems(entries);
        textPath.setText(getString(R.string.usb_apk_path_fmt, currentDir.getAbsolutePath()));
        textEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        textEmpty.setText(R.string.usb_apk_empty);
        btnUp.setEnabled(UsbDirBrowser.canGoUp(currentDir, usbRoot));
        selectedApk = null;
        adapter.clearSelection();
    }

    private void goUp() {
        if (usbRoot == null || currentDir == null) {
            return;
        }
        currentDir = UsbDirBrowser.parentWithinRoot(currentDir, usbRoot);
        listCurrent();
    }

    private void enterDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        currentDir = dir;
        listCurrent();
    }

    private void selectApk(File apk) {
        selectedApk = apk;
        adapter.setSelected(apk);
        uiLog.log("已选: " + apk.getName());
    }

    private void installSelected() {
        if (busy) {
            uiLog.log("忙，请稍候…");
            return;
        }
        if (selectedApk == null || !selectedApk.isFile()) {
            uiLog.log(getString(R.string.usb_apk_select_hint));
            return;
        }
        busy = true;
        btnInstall.setEnabled(false);
        final File apk = selectedApk;
        uiLog.log("开始安装 " + apk.getName() + " …");
        io.execute(() -> {
            // U 盘源：deleteOnSuccess=false，装完不删
            String result = installer.installApk(apk,
                    line -> mainHandler.post(() -> uiLog.log(line)), false);
            mainHandler.post(() -> {
                uiLog.log(result);
                busy = false;
                btnInstall.setEnabled(true);
                InstallResultUi.showFromInstallResult(
                        UsbApkInstallActivity.this, result);
            });
        });
    }

    private final class UsbListAdapter extends RecyclerView.Adapter<UsbVH> {
        private final List<UsbDirBrowser.Entry> items = new ArrayList<>();
        private File selected;

        void setItems(List<UsbDirBrowser.Entry> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        void setSelected(File apk) {
            selected = apk;
            notifyDataSetChanged();
        }

        void clearSelection() {
            selected = null;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public UsbVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_usb_entry, parent, false);
            return new UsbVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull UsbVH h, int position) {
            UsbDirBrowser.Entry e = items.get(position);
            h.kind.setText(e.directory
                    ? getString(R.string.usb_apk_folder)
                    : "APK");
            h.name.setText(e.displayName);
            boolean isSel = !e.directory && selected != null
                    && selected.getAbsolutePath().equals(e.file.getAbsolutePath());
            h.itemView.setBackgroundColor(isSel ? 0x332BB673 : Color.TRANSPARENT);
            h.itemView.setOnClickListener(v -> {
                if (e.directory) {
                    enterDir(e.file);
                } else {
                    selectApk(e.file);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class UsbVH extends RecyclerView.ViewHolder {
        final TextView kind;
        final TextView name;

        UsbVH(View itemView) {
            super(itemView);
            kind = itemView.findViewById(R.id.item_usb_kind);
            name = itemView.findViewById(R.id.item_usb_name);
        }
    }
}
