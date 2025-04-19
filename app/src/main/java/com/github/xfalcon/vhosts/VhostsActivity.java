package com.github.xfalcon.vhosts;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.VhostsService;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public class VhostsActivity extends AppCompatActivity {

    private static final String SWITCH_FLYME        = "switch_flyme";
    private static final String SWITCH_MIUI_APP     = "switch_miui_app";
    private static final String SWITCH_MIUI_SEC     = "switch_miui_sec";
    private static final String SWITCH_MIUI_BROWSER = "switch_miui_browser";

    private static final String HOSTS_FLYME         = "hosts-flyme";
    private static final String HOSTS_MIUI_APP      = "hosts-miui-app";
    private static final String HOSTS_MIUI_SEC      = "hosts-miui-ad";
    private static final String HOSTS_MIUI_BROWSER  = "hosts-miui-browser";
    private static final String COMBINED_HOSTS      = "combined_hosts";

    private MaterialSwitch switchFlyme;
    private MaterialSwitch switchMiuiApp;
    private MaterialSwitch switchMiuiSec;
    private MaterialSwitch switchMiuiBrowser;
    private MaterialSwitch vpnButton;
    private ViewGroup selectHosts;
    private TextView tvSelectedHosts;
    private Button buttonClearRules;
    private View buttonAbout;

    private FirebaseAnalytics mFirebaseAnalytics;
    private boolean waitingForVPNStart;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VhostsService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                waitingForVPNStart = intent.getBooleanExtra("running", false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch();
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        setContentView(R.layout.activity_vhosts);
        LogUtils.context = getApplicationContext();

        initializeViews();
        tvSelectedHosts  = findViewById(R.id.tv_selected_hosts);
        buttonClearRules = findViewById(R.id.button_clear_rules);
        buttonAbout      = findViewById(R.id.button_about);

        initializeSwitchStates();
        updateSelectedHostsDisplay();

        setupSwitchListeners();
        setupVpnButton();
        setupFileSelector();

        buttonClearRules.setOnClickListener(v -> {
            switchFlyme.setChecked(false);
            switchMiuiApp.setChecked(false);
            switchMiuiSec.setChecked(false);
            switchMiuiBrowser.setChecked(false);
            updateSelectedHostsDisplay();
            new Thread(() -> {
                try {
                    generateCombinedHosts();
                    mainHandler.post(() -> Toast.makeText(this,
                            "已清除所有规则", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(this,
                            "清除失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        buttonAbout.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class))
        );

        LocalBroadcastManager.getInstance(this).registerReceiver(
                vpnStateReceiver,
                new IntentFilter(VhostsService.BROADCAST_VPN_STATE)
        );
    }

    private void initializeViews() {
        switchFlyme      = findViewById(R.id.switch_flyme);
        switchMiuiApp     = findViewById(R.id.switch_miui_app);
        switchMiuiSec     = findViewById(R.id.switch_miui_sec);
        switchMiuiBrowser = findViewById(R.id.switch_miui_browser);
        vpnButton         = findViewById(R.id.button_start_vpn);
        selectHosts       = findViewById(R.id.button_select_hosts);

        if (switchFlyme == null || switchMiuiApp == null ||
                switchMiuiSec == null || switchMiuiBrowser == null ||
                vpnButton == null   || selectHosts == null) {
            throw new IllegalStateException("Critical UI components missing!");
        }
    }

    private void initializeSwitchStates() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false);

        switchFlyme.setChecked(prefs.getBoolean(SWITCH_FLYME, true));
        switchMiuiApp.setChecked(prefs.getBoolean(SWITCH_MIUI_APP, false));
        switchMiuiSec.setChecked(prefs.getBoolean(SWITCH_MIUI_SEC, false));
        switchMiuiBrowser.setChecked(prefs.getBoolean(SWITCH_MIUI_BROWSER, false));
    }

    private void updateSelectedHostsDisplay() {
        Set<String> enabled = new HashSet<>();
        if (switchFlyme.isChecked())      enabled.add("Flyme广告屏蔽");
        if (switchMiuiApp.isChecked())     enabled.add("MIUI 安装检测屏蔽");
        if (switchMiuiSec.isChecked())     enabled.add("MIUI APP 内广告");
        if (switchMiuiBrowser.isChecked()) enabled.add("MIUI 浏览器净化");
        if (enabled.isEmpty()) enabled.add("（无）");
        tvSelectedHosts.setText("当前已选规则： " + String.join("、", enabled));
    }

    private void setupSwitchListeners() {
        CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            String key;
            int id = buttonView.getId();
            if (id == R.id.switch_flyme)           key = SWITCH_FLYME;
            else if (id == R.id.switch_miui_app)   key = SWITCH_MIUI_APP;
            else if (id == R.id.switch_miui_sec)   key = SWITCH_MIUI_SEC;
            else if (id == R.id.switch_miui_browser) key = SWITCH_MIUI_BROWSER;
            else return;

            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putBoolean(key, isChecked)
                    .apply();

            updateSelectedHostsDisplay();

            if (VhostsService.isRunning()) {
                new Thread(() -> {
                    try {
                        generateCombinedHosts();
                        File out = new File(getFilesDir(), COMBINED_HOSTS);
                        VhostsService.reloadHosts(getApplicationContext(), out.getAbsolutePath());
                    } catch (Exception e) {
                        mainHandler.post(() ->
                                Toast.makeText(this,
                                        "规则更新失败: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show()
                        );
                    }
                }).start();
            }
        };

        switchFlyme.setOnCheckedChangeListener(listener);
        switchMiuiApp.setOnCheckedChangeListener(listener);
        switchMiuiSec.setOnCheckedChangeListener(listener);
        switchMiuiBrowser.setOnCheckedChangeListener(listener);
    }

    private void setupVpnButton() {
        vpnButton.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                new Thread(() -> {
                    try {
                        generateCombinedHosts();
                        runOnUiThread(() -> {
                            startVPN();
                            btn.setChecked(true);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            btn.setChecked(false);
                            Toast.makeText(this,
                                    "VPN 启动失败: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                        LogUtils.e(TAG, "VPN 启动失败", e);
                    }
                }).start();
            } else {
                shutdownVPN();
            }
        });
    }

    private void setupFileSelector() {
        selectHosts.setOnClickListener(v -> selectFile());
        selectHosts.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
    }

    private void generateCombinedHosts() throws IOException {
        Set<String> enabled = new HashSet<>();
        if (switchFlyme.isChecked())      enabled.add(HOSTS_FLYME);
        if (switchMiuiApp.isChecked())     enabled.add(HOSTS_MIUI_APP);
        if (switchMiuiSec.isChecked())     enabled.add(HOSTS_MIUI_SEC);
        if (switchMiuiBrowser.isChecked()) enabled.add(HOSTS_MIUI_BROWSER);

        if (enabled.isEmpty()) {
            enabled.add(HOSTS_FLYME);
        }

        File outFile = new File(getFilesDir(), COMBINED_HOSTS);
        try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
            fos.write(("# Generated at " + System.currentTimeMillis() + "\n").getBytes());
            for (String name : enabled) {
                try (InputStream is = getAssets().open(name);
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().startsWith("#") && !line.trim().isEmpty()) {
                            fos.write((line + "\n").getBytes());
                        }
                    }
                }
            }
            LogUtils.i(TAG, "合并身材完成，包含 " + enabled.size() + " 个规则集");
        }
        outFile.setReadable(true, false);
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(outFile)));
    }

    private void launch() {
        Uri uri = getIntent().getData();
        if (uri == null) return;
        String s = uri.toString();
        if ("on".equals(s)) {
            if (!VhostsService.isRunning()) VhostsService.startVService(this, 1);
            finish();
        } else if ("off".equals(s)) {
            VhostsService.stopVService(this);
            finish();
        }
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        try {
            Field f = android.provider.DocumentsContract.class.getField("EXTRA_SHOW_ADVANCED");
            intent.putExtra(f.get(null).toString(), true);
        } catch (Exception e) {
            LogUtils.e(TAG, "SET EXTRA_SHOW_ADVANCED", e);
        }
        try {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, SettingsFragment.SELECT_FILE_CODE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_select_error, Toast.LENGTH_LONG).show();
            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean(SettingsFragment.IS_NET, true).apply();
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private void startVPN() {
        waitingForVPNStart = false;
        Intent vpnIntent = VhostsService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, SettingsFragment.VPN_REQUEST_CODE);
        } else {
            onActivityResult(SettingsFragment.VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void shutdownVPN() {
        if (VhostsService.isRunning()) {
            startService(new Intent(this, VhostsService.class)
                    .setAction(VhostsService.ACTION_DISCONNECT));
        }
        setButton(true);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == SettingsFragment.VPN_REQUEST_CODE && res == RESULT_OK) {
            waitingForVPNStart = true;
            startService(new Intent(this, VhostsService.class)
                    .setAction(VhostsService.ACTION_CONNECT));
            setButton(false);
        } else if (req == SettingsFragment.SELECT_FILE_CODE && res == RESULT_OK) {
            setUriByPREFS(data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setButton(!waitingForVPNStart && !VhostsService.isRunning());
    }

    private void setUriByPREFS(Intent intent) {
        Uri uri = intent.getData();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(SettingsFragment.HOSTS_URI, uri.toString()).apply();

            int code = checkHostUri();
            if (code == 1) {
                setButton(true);
                setButton(false);
            } else if (code == -1) {
                Toast.makeText(this, R.string.permission_error, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "permission error", e);
        }
    }

    private int checkHostUri() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(SettingsFragment.IS_NET, false)) {
            try {
                openFileInput(SettingsFragment.NET_HOST_FILE).close();
                return 2;
            } catch (Exception e) {
                return -2;
            }
        } else {
            try {
                getContentResolver().openInputStream(
                                Uri.parse(prefs.getString(SettingsFragment.HOSTS_URI, "")))
                        .close();
                return 1;
            } catch (Exception e) {
                return -1;
            }
        }
    }

    private void setButton(boolean enable) {
        vpnButton  = findViewById(R.id.button_start_vpn);
        selectHosts = findViewById(R.id.button_select_hosts);
        if (enable) {
            vpnButton.setChecked(false);
            selectHosts.setAlpha(1f);
            selectHosts.setClickable(true);
        } else {
            vpnButton.setChecked(true);
            selectHosts.setAlpha(0.5f);
            selectHosts.setClickable(false);
        }
    }
}
