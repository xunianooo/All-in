package com.gaeul.allin;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class GatewayService extends Service {
    private static final String CHANNEL_ID = "magpie_pocket_channel";
    private static final int NOTIFICATION_ID = 3425;

    private static volatile boolean RUNNING = false;
    /** 带 foregroundServiceType 的 startForeground 是否成功（供权限页展示真实状态） */
    private static volatile boolean TYPED_FOREGROUND_OK = false;
    private static volatile int FOREGROUND_TYPE_MASK = 0;

    public static boolean isTypedForeground() {
        return TYPED_FOREGROUND_OK;
    }

    public static int foregroundTypeMask() {
        return FOREGROUND_TYPE_MASK;
    }

    private GatewayServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // 供 UI 与开关按钮查询网关当前的真实运行状态
    public static boolean isRunning() {
        return RUNNING;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 尝试创建前台通知与保活
        try {
            createNotificationChannel();
            startForegroundNotification();
        } catch (Throwable t) {
            Log.w("Magpie", "Foreground notification warning: " + t.getMessage());
        }

        try {
            acquireLocks();
        } catch (Throwable t) {
            Log.w("Magpie", "Acquire locks warning: " + t.getMessage());
        }

        // 启动本地网关引擎（带重试与冲突自愈）
        // 放到工作线程：GatewayServer 构造函数要读 config.json、初始化用量账本（解析最多 90 天 jsonl），
        // 失败分支还会 sleep 600ms —— 这些全在主线程做，冷启动/开机/自愈重启都有 ANR 风险。
        new Thread(new Runnable() {
            @Override
            public void run() {
                startGatewayEngine();
            }
        }, "gateway-boot").start();
    }

    private void startGatewayEngine() {
        try {
            server = new GatewayServer(this, 3425);
            server.start();
            RUNNING = true;
            Log.i("Magpie", "GatewayServer started on port 3425");
            try {
                KeepAliveScheduler.schedule(this);
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            Log.w("Magpie", "GatewayServer first start failed: " + e.getMessage() + ", retrying after brief pause...");
            try {
                Thread.sleep(600);
                if (server != null) { try { server.stop(); } catch (Throwable ignored) {} }
                server = new GatewayServer(this, 3425);
                server.start();
                RUNNING = true;
                Log.i("Magpie", "GatewayServer retry start succeeded on port 3425");
            } catch (Throwable e2) {
                // 如果 3425 端口已被占用且正在响应，视为网关仍在运行
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", 3425), 300);
                    s.close();
                    RUNNING = true;
                } catch (Throwable ignored) {
                    RUNNING = false;
                }
                Log.e("Magpie", "GatewayServer start final result: running=" + RUNNING + ", err=" + e2.getMessage());
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> constructor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = constructor.newInstance(CHANNEL_ID, "All In 网关服务", 2);

                Method setDesc = channelClass.getMethod("setDescription", String.class);
                setDesc.invoke(channel, "保持 All In 掌上网关常驻运行与网络畅通");

                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    Method createChannel = NotificationManager.class.getMethod("createNotificationChannel", channelClass);
                    createChannel.invoke(manager, channel);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void startForegroundNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23) ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                flags
            );

            String ip = GatewayServer.getLocalIpAddress();

            Notification.Builder builder = null;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Constructor<Notification.Builder> c = Notification.Builder.class.getConstructor(Context.class, String.class);
                    builder = c.newInstance(this, CHANNEL_ID);
                } catch (Throwable e) {
                    builder = new Notification.Builder(this);
                }
            } else {
                builder = new Notification.Builder(this);
            }

            // 常驻通知：避免被用户滑掉后 ROM 认为该前台服务无意义而降级/冻结
            try {
                Method setOngoing = builder.getClass().getMethod("setOngoing", boolean.class);
                setOngoing.invoke(builder, Boolean.TRUE);
            } catch (Throwable ignored) {}
            try {
                Method setShowWhen = builder.getClass().getMethod("setShowWhen", boolean.class);
                setShowWhen.invoke(builder, Boolean.FALSE);
            } catch (Throwable ignored) {}

            Notification notification = builder
                .setContentTitle("⚡ All In 网关运行中")
                .setContentText("监听: " + ip + ":3425 | 隐私脱敏已就绪")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .build();

            startForegroundWithType(notification);
        } catch (Throwable t) {
            Log.w("Magpie", "startForeground failed: " + t.getMessage());
        }
    }

    /**
     * Android 10+ 的 startForeground(int, Notification, int) 需要显式声明服务类型。
     * 精简 android.jar 里没有这个重载，且 FOREGROUND_SERVICE_TYPE_DATA_SYNC 常量也不存在，
     * 所以用反射调用；低版本或反射失败时退回两参版本（不会崩溃，只是少了类型声明）。
     *
     * 为什么要类型：Android 14+ 对 FGS 的豁免按类型授予，未声明类型的服务
     * 在部分 ROM 上会被当作普通后台进程处理，进而被冻结（do_freezer_trap）。
     */
    private void startForegroundWithType(Notification notification) {
        final int TYPE_DATA_SYNC = 0x00000001;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method m = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                m.invoke(this, Integer.valueOf(NOTIFICATION_ID), notification, Integer.valueOf(TYPE_DATA_SYNC));
                TYPED_FOREGROUND_OK = true;
                FOREGROUND_TYPE_MASK = TYPE_DATA_SYNC;
                Log.i("Magpie", "startForeground with type=dataSync ok");
                return;
            } catch (Throwable t) {
                TYPED_FOREGROUND_OK = false;
                FOREGROUND_TYPE_MASK = 0;
                Log.w("Magpie", "typed startForeground failed: " + t.getMessage());
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagpiePocket::WakeLock");
                wakeLock.acquire();
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MagpiePocket::WifiLock");
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * 用户从最近任务里划掉 App 时，ROM 常在随后几十秒内回收前台服务。
     * 这里补一次排程：闹钟在下个周期会醒来看一眼，服务没了就重新拉起。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            KeepAliveScheduler.schedule(this);
        } catch (Throwable ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        // 服务被停不代表用户想永久关掉；把自检留着，下次闹钟会决定要不要重新拉起
        try {
            KeepAliveScheduler.schedule(this);
        } catch (Throwable ignored) {
        }
        if (server != null) {
            try { server.stop(); } catch (Throwable ignored) {}
            server = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) {}
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
