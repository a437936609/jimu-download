package com.uestcit.download;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
 * 接替系统运行时权限申请弹窗（{@code android.content.pm.action.REQUEST_PERMISSIONS}）。
 * <p>
 * 对齐 PackageInstaller {@code GrantPermissionsActivity}：读请求权限数组，
 * 用户允许/拒绝后通过 {@code EXTRA_REQUEST_PERMISSIONS_RESULTS} 回传，
 * 并特权 {@code grantRuntimePermission}。车机无可靠 PermissionController 时由本页承接。
 */
public final class GrantPermissionsActivity extends AppCompatActivity {

    /** PackageManager 隐藏常量，与 AOSP 一致。 */
    public static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";
    /** 请求的权限名数组。 */
    public static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    /** 对应授予结果：{@link PackageManager#PERMISSION_GRANTED/DENIED}。 */
    public static final String EXTRA_REQUEST_PERMISSIONS_RESULTS =
            "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

    private AppPermissionController controller;
    private String packageName;
    private String[] requestedNames = new String[0];
    private final List<RuntimePermissionGroup> rows = new ArrayList<>();
    private TextView textPkg;
    private NameAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grant_permissions);

        controller = new AppPermissionController(this);
        textPkg = findViewById(R.id.text_grant_pkg);
        RecyclerView list = findViewById(R.id.list_grant_permissions);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NameAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_grant_allow).setOnClickListener(v -> finishWithGrant(true));
        findViewById(R.id.btn_grant_deny).setOnClickListener(v -> finishWithGrant(false));

        bindFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindFromIntent(intent);
    }

    @Override
    public void onBackPressed() {
        // 返回键视为拒绝，避免调用方挂起
        finishWithGrant(false);
    }

    private void bindFromIntent(@Nullable Intent intent) {
        packageName = resolvePackage(intent);
        requestedNames = resolveRequested(intent);
        CharSequence label = packageName;
        if (!TextUtils.isEmpty(packageName)) {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                label = pm.getApplicationLabel(ai) + "\n" + packageName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 仅显示包名
            }
        }
        textPkg.setText(label);

        rows.clear();
        if (!TextUtils.isEmpty(packageName) && requestedNames.length > 0) {
            rows.addAll(controller.listRequested(packageName, requestedNames));
        }
        adapter.notifyDataSetChanged();

        if (TextUtils.isEmpty(packageName) || requestedNames.length == 0) {
            // 无有效请求则直接拒绝结束，避免空白卡死
            finishWithGrant(false);
        }
    }

    @Nullable
    private String resolvePackage(@Nullable Intent intent) {
        // 优先 Intent extra：PackageInstaller 转发时 callingPackage 会变成 installer
        if (intent != null) {
            String extra = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            if (!TextUtils.isEmpty(extra)) {
                return extra;
            }
            extra = intent.getStringExtra("android.intent.extra.PACKAGE_NAME");
            if (!TextUtils.isEmpty(extra)) {
                return extra;
            }
        }
        String caller = getCallingPackage();
        if (!TextUtils.isEmpty(caller)
                && !"com.android.packageinstaller".equals(caller)
                && !getPackageName().equals(caller)) {
            return caller;
        }
        return caller;
    }

    @NonNull
    private static String[] resolveRequested(@Nullable Intent intent) {
        if (intent == null) {
            return new String[0];
        }
        String[] names = intent.getStringArrayExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (names != null && names.length > 0) {
            return names;
        }
        // 个别路径用 ArrayList
        ArrayList<String> list = intent.getStringArrayListExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (list != null && !list.isEmpty()) {
            return list.toArray(new String[0]);
        }
        return new String[0];
    }

    /**
     * 授权或拒绝全部请求项，写回 result Intent。
     *
     * @param allow true=全部允许并 grant；false=全部拒绝
     */
    private void finishWithGrant(boolean allow) {
        int[] results = new int[requestedNames.length];
        for (int i = 0; i < requestedNames.length; i++) {
            String perm = requestedNames[i];
            if (allow && !TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(perm)) {
                boolean granted = controller.grantPermission(packageName, perm);
                results[i] = granted
                        ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED;
            } else {
                results[i] = PackageManager.PERMISSION_DENIED;
            }
        }

        Intent data = new Intent();
        data.putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, requestedNames);
        data.putExtra(EXTRA_REQUEST_PERMISSIONS_RESULTS, results);
        setResult(RESULT_OK, data);
        finish();
    }

    private final class NameAdapter extends RecyclerView.Adapter<NameAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_permission, parent, false);
            // 申请弹窗只展示，不提供行内 Switch
            View sw = v.findViewById(R.id.item_perm_switch);
            if (sw != null) {
                sw.setVisibility(View.GONE);
            }
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RuntimePermissionGroup g = rows.get(position);
            holder.label.setText(g.label);
            holder.detail.setText(g.detail);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView detail;

            VH(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.item_perm_label);
                detail = itemView.findViewById(R.id.item_perm_detail);
            }
        }
    }
}
