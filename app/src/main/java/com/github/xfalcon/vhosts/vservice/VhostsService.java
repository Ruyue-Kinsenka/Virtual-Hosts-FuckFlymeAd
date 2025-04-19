package com.github.xfalcon.vhosts.vservice;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.github.xfalcon.vhosts.util.LogUtils.context;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.github.xfalcon.vhosts.SettingsFragment;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;
import com.github.xfalcon.vhosts.R;

import org.xbill.DNS.Address;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class VhostsService extends VpnService {
    private static final String TAG = VhostsService.class.getSimpleName();
    private static final String VPN_ADDRESS = "192.0.2.111";
    private static final String VPN_ADDRESS6 = "fe80:49b1:7e4f:def2:e91f:95bf:fbb6:1111";
    private static final String VPN_DNS4_DEFAULT = "8.8.8.8";
    private static final String VPN_DNS6 = "2001:4860:4860::8888";

    public static final String BROADCAST_VPN_STATE = VhostsService.class.getName() + ".VPN_STATE";
    public static final String ACTION_CONNECT = VhostsService.class.getName() + ".START";
    public static final String ACTION_DISCONNECT = VhostsService.class.getName() + ".STOP";
    public static final String ACTION_RELOAD_HOSTS = "com.github.xfalcon.vhosts.RELOAD_HOSTS";

    private ParcelFileDescriptor vpnInterface;
    private PendingIntent pendingIntent;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;
    private Selector udpSelector, tcpSelector;
    private ReentrantLock udpSelectorLock, tcpSelectorLock;

    private static File currentHostsFile;

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_RELOAD_HOSTS.equals(intent.getAction())) {
                LogUtils.i(TAG, "收到重载 Hosts 指令");
                setupHostFile();
            }
        }
    };

    @SuppressLint("ForegroundServiceType")
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(reloadReceiver, new IntentFilter(ACTION_RELOAD_HOSTS), Context.RECEIVER_NOT_EXPORTED);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    "vhosts_channel_id", "Vhosts Service", NotificationManager.IMPORTANCE_NONE);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(chan);
            Notification notification = new Notification.Builder(this, "vhosts_channel_id")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Virtual Hosts Running")
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        }

        setupHostFile();
        setupVPN();
        if (vpnInterface == null) {
            LogUtils.e(TAG, "VPN 建立失败，停止服务");
            stopVService();
            return;
        }

        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<>();
            udpSelectorLock = new ReentrantLock();
            tcpSelectorLock = new ReentrantLock();
            executorService = Executors.newFixedThreadPool(5);
            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector, udpSelectorLock));
            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, networkToDeviceQueue,
                    udpSelector, udpSelectorLock, this));
            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector, tcpSelectorLock));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue,
                    tcpSelector, tcpSelectorLock, this));
            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                    deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));

            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            LogUtils.i(TAG, "Service 启动完成");
        } catch (Exception e) {
            LogUtils.e(TAG, "启动线程失败", e);
            stopVService();
        }
    }

    private void setupHostFile() {
        InputStream is = null;
        try {
            if (currentHostsFile != null && currentHostsFile.exists()) {
                LogUtils.i(TAG, "加载合并后的 Hosts: " + currentHostsFile.getAbsolutePath());
                is = new FileInputStream(currentHostsFile);
            } else {
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
                boolean isNet = settings.getBoolean(SettingsFragment.IS_NET, false);
                if (isNet) {
                    LogUtils.i(TAG, "加载网络 Hosts 文件");
                    is = openFileInput(SettingsFragment.NET_HOST_FILE);
                } else {
                    String uriString = settings.getString(SettingsFragment.HOSTS_URI, null);
                    if (uriString != null) {
                        LogUtils.i(TAG, "加载本地选中文件: " + uriString);
                        is = getContentResolver().openInputStream(Uri.parse(uriString));
                    }
                }
            }

            if (is == null) {
                LogUtils.w(TAG, "未找到任何 Hosts 文件，跳过加载");
                return;
            }

            final InputStream finalIs = is;
            new Thread(() -> {
                int result = DnsChange.handle_hosts(finalIs);
                if (result == 0) {
                    Looper.prepare();
                    Toast.makeText(getApplicationContext(),
                            "Hosts文件中无可用记录", Toast.LENGTH_LONG).show();
                    Looper.loop();
                }
            }).start();

        } catch (Exception e) {
            LogUtils.e(TAG, "设置 Hosts 文件失败", e);
            new Thread(() -> {
                Looper.prepare();
                Toast.makeText(getApplicationContext(),
                        "加载 Hosts 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                Looper.loop();
            }).start();
        }
    }

    private void setupVPN() {
        if (vpnInterface == null) {
            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32)
                    .addAddress(VPN_ADDRESS6, 128);

            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
            boolean isCusDns = settings.getBoolean(SettingsFragment.IS_CUS_DNS, false);
            String dns4 = isCusDns
                    ? settings.getString(SettingsFragment.IPV4_DNS, VPN_DNS4_DEFAULT)
                    : VPN_DNS4_DEFAULT;
            try {
                Address.getByAddress(dns4);
            } catch (Exception e) {
                dns4 = VPN_DNS4_DEFAULT;
            }
            builder.addRoute(dns4, 32)
                    .addRoute(VPN_DNS6, 128)
                    .addDnsServer(dns4)
                    .addDnsServer(VPN_DNS6);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String[] whiteList = {
                        "com.android.vending", "com.google.android.gm",
                        "com.google.android.apps.photos", "com.google.android.apps.docs",
                        "com.google.android.apps.translate"
                };
                for (String pkg : whiteList) {
                    try {
                        builder.addDisallowedApplication(pkg);
                    } catch (Exception ignored) {
                    }
                }
            }
            vpnInterface = builder
                    .setSession(getString(R.string.app_name))
                    .setConfigureIntent(pendingIntent)
                    .establish();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_DISCONNECT.equals(action)) {
                stopVService();
                return START_NOT_STICKY;
            } else if (ACTION_RELOAD_HOSTS.equals(action)) {
                new Thread(this::setupHostFile).start();
            }
        }
        return START_STICKY;
    }

    public static boolean isRunning() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (VhostsService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void startVService(Context ctx, int method) {
        Intent prep = VhostsService.prepare(ctx);
        if (prep != null) {
            prep.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(prep);
        }
        Intent svc = new Intent(ctx, VhostsService.class).setAction(ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && method == 2) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    public static void stopVService(Context ctx) {
        ctx.startService(new Intent(ctx, VhostsService.class).setAction(ACTION_DISCONNECT));
    }

    private void stopVService() {
        stopForeground(true);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        cleanup();
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", false));
        stopSelf();
        LogUtils.i(TAG, "Service 已停止");
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(reloadReceiver);
        super.onDestroy();
    }

    private void cleanup() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                LogUtils.e(TAG, "关闭 VPN 接口失败", e);
            }
            vpnInterface = null;
        }
        closeQuietly(udpSelector, tcpSelector);
        deviceToNetworkUDPQueue = null;
        deviceToNetworkTCPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
    }

    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable res : resources) {
            try {
                if (res != null) res.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void reloadHosts(Context context) {
        Intent i = new Intent(context, VhostsService.class).setAction(ACTION_RELOAD_HOSTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void reloadHosts(Context context, String hostsPath) {
        currentHostsFile = new File(hostsPath);
        reloadHosts(context);
    }
}