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

    private static volatile boolean RUNNING;
    private static volatile boolean TYPED_FOREGROUND_OK;
    private static volatile int FOREGROUND_TYPE_MASK;
    private static volatile GatewayService current;
    private static volatile String startError = "";
    private final Object lifecycle = new Object();
    private static final Object ENGINE_SERIAL = new Object();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_STOPS = new java.util.concurrent.atomic.AtomicInteger();
    private final RecoveryPolicy recoveryPolicy = new RecoveryPolicy();
    private volatile GatewayServer server;
    private volatile boolean disposed;
    private volatile boolean foreground;
    private volatile long createdAt, lastProbeAt, lastProbeMs;
    private volatile int lastCode, failures, restartCount;
    private volatile String phase = "created", lastReason = "", errorType = "";
    private java.util.concurrent.ScheduledExecutorService worker;
    private final java.util.concurrent.atomic.AtomicBoolean checkQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static boolean isTypedForeground() { return TYPED_FOREGROUND_OK; }
    public static int foregroundTypeMask() { return FOREGROUND_TYPE_MASK; }
    public static boolean isRunning() { return RUNNING; }
    public static boolean isGatewayEnabled(Context context) {
        try { return context.getSharedPreferences("magpie_prefs", MODE_PRIVATE)
                .getBoolean("gateway_enabled", true); }
        catch (Throwable t) { return false; }
    }

    public static void requestHealthCheck(Context context, String reason) {
        if (context == null || !isGatewayEnabled(context)) return;
        GatewayService instance = current;
        if (instance != null && !instance.disposed) {
            instance.enqueue(reason);
            return;
        }
        try {
            Intent intent = new Intent(context, GatewayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
            } else context.startService(intent);
            startError = "";
            KeepAliveScheduler.recordOutcome(context, "restartRequested", "");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            startError = cause.getClass().getSimpleName();
            KeepAliveScheduler.recordOutcome(context, "startRestricted", startError);
            Log.w("Magpie", "Foreground service start failed: " + startError);
        }
    }

    public static org.json.JSONObject healthStatus(Context context) {
        org.json.JSONObject o = new org.json.JSONObject();
        GatewayService s = current;
        try {
            o.put("enabled", context != null && isGatewayEnabled(context));
            o.put("serviceCreatedAt", s == null ? 0 : s.createdAt);
            o.put("pid", android.os.Process.myPid());
            GatewayServer server = s == null ? null : s.server;
            o.put("listenerAlive", server != null && server.isAlive());
            o.put("foreground", s != null && s.foreground);
            o.put("typedForeground", TYPED_FOREGROUND_OK);
            o.put("foregroundTypeMask", FOREGROUND_TYPE_MASK);
            o.put("wakeLockHeld", s != null && s.wakeLock != null && s.wakeLock.isHeld());
            o.put("wifiLockHeld", s != null && s.wifiLock != null && s.wifiLock.isHeld());
            o.put("phase", s == null ? "stopped" : s.phase);
            o.put("healthState", s == null ? "stopped" : s.phase);
            o.put("activeRequests", server == null ? 0 : server.activeForwardRequests());
            o.put("lastProbeAgeSeconds", s == null || s.lastProbeAt == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - s.lastProbeAt) / 1000));
            o.put("lastProbeAt", s == null ? 0 : s.lastProbeAt);
            o.put("lastProbeCode", s == null ? 0 : s.lastCode);
            o.put("lastProbeMs", s == null ? 0 : s.lastProbeMs);
            o.put("consecutiveFailures", s == null ? 0 : s.failures);
            o.put("restartCount", s == null ? 0 : s.restartCount);
            o.put("lastReason", s == null ? "" : s.lastReason);
            o.put("errorType", s == null ? startError : s.errorType);
        } catch (Throwable ignored) { }
        return o;
    }

    @Override public void onCreate() {
        super.onCreate();
        createdAt = System.currentTimeMillis();
        current = this;
        createNotificationChannel();
        startForegroundNotification();
        if (!isGatewayEnabled(this)) {
            phase = "disabled";
            KeepAliveScheduler.cancel(this);
            stopSelf();
            return;
        }
        acquireLocks();
        worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try { check("periodic"); } catch (Throwable t) { RUNNING = false; errorType = t.getClass().getSimpleName(); phase = "checkError"; }
            }
        }, 2, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void enqueue(final String reason) {
        java.util.concurrent.ScheduledExecutorService w = worker;
        if (w == null || disposed || !checkQueued.compareAndSet(false, true)) return;
        try { w.execute(new Runnable() { public void run() {
            try { check(reason); } catch (Throwable t) { RUNNING = false; errorType = t.getClass().getSimpleName(); phase = "checkError"; }
            finally { checkQueued.set(false); }
        } }); } catch (java.util.concurrent.RejectedExecutionException ignored) { checkQueued.set(false); }
    }

    private void check(String reason) {
        if (disposed || !isGatewayEnabled(this)) return;
        lastReason = safeReason(reason);
        GatewayServer own = server;
        if (own == null || !own.isAlive()) {
            RUNNING = false;
            startEngine();
            return;
        }
        phase = "probing";
        long begin = android.os.SystemClock.elapsedRealtime();
        HealthProbe.Result result = HealthProbe.probe(own.healthInstanceId());
        lastProbeMs = android.os.SystemClock.elapsedRealtime() - begin;
        lastProbeAt = System.currentTimeMillis();
        lastCode = result.code;
        errorType = result.error;
        if (disposed || !isGatewayEnabled(this)) return;
        KeepAliveScheduler.recordCheck(this, result.ok, result.code);
        if (result.ok) {
            recoveryPolicy.probeSucceeded();
            failures = 0;
            synchronized (lifecycle) {
                if (disposed || !isGatewayEnabled(this) || server != own) return;
                phase = "healthy";
                RUNNING = own.isAlive();
            }
            return;
        }
        RUNNING = false;
        recoveryPolicy.probeFailed();
        failures = recoveryPolicy.failures();
        phase = "unhealthy";
        long now = android.os.SystemClock.elapsedRealtime();
        if (!recoveryPolicy.mayRecover(now)) return;
        synchronized (ENGINE_SERIAL) {
            synchronized (lifecycle) {
                if (disposed || !isGatewayEnabled(this) || server != own) return;
                if (!own.beginIdleRecovery()) { phase = "waitingForRequests"; return; }
                recoveryPolicy.recoveryStarted(now);
                phase = "restarting";
                server = null;
                restartCount++;
            }
            try { own.stop(); } catch (Throwable t) { errorType = t.getClass().getSimpleName(); }
        }
        startEngine();
    }

    private void startEngine() {
        synchronized (ENGINE_SERIAL) {
            if (disposed || !isGatewayEnabled(this)) return;
            if (PENDING_STOPS.get() > 0) { phase = "waitingForStop"; scheduleFollowup(2000L); return; }
            long now = android.os.SystemClock.elapsedRealtime();
            if (!recoveryPolicy.mayStart(now)) { phase = "startBackoff"; return; }
            GatewayServer old;
            synchronized (lifecycle) {
                if (disposed || !isGatewayEnabled(this)) return;
                old = server;
                if (old != null) {
                    if (old.isAlive()) return;
                    if (!old.beginIdleRecovery()) { phase = "waitingForRequests"; return; }
                    server = null;
                }
            }
            if (old != null) try { old.stop(); } catch (Throwable t) { errorType = t.getClass().getSimpleName(); }
            if (disposed || !isGatewayEnabled(this)) return;
            phase = "starting";
            GatewayServer candidate = null;
            boolean published = false;
            try {
                candidate = new GatewayServer(this, 3425);
                if (disposed || !isGatewayEnabled(this)) return;
                candidate.start();
                if (!candidate.isAlive()) throw new IllegalStateException("listener not alive");
                synchronized (lifecycle) {
                    if (disposed || !isGatewayEnabled(this)) return;
                    server = candidate;
                    published = true;
                    RUNNING = false; // Only the identity probe establishes health.
                    phase = "awaitingProbe";
                    errorType = "";
                }
                recoveryPolicy.startSucceeded();
                if (!disposed && isGatewayEnabled(this)) {
                    KeepAliveScheduler.schedule(this);
                    scheduleFollowup(2000L);
                }
            } catch (Throwable t) {
                if (!disposed) {
                    RUNNING = false;
                    phase = "startFailed";
                    errorType = t.getClass().getSimpleName();
                    long delay = recoveryPolicy.startFailed(android.os.SystemClock.elapsedRealtime());
                    Log.w("Magpie", "Gateway start failed: " + errorType + "; retry in " + delay + "ms");
                    scheduleFollowup(delay);
                }
            } finally {
                if (!published && candidate != null) {
                    try { candidate.stop(); } catch (Throwable t) { Log.w("Magpie", "Candidate stop: " + t.getClass().getSimpleName()); }
                }
            }
        }
    }

    private void scheduleFollowup(long delay) {
        java.util.concurrent.ScheduledExecutorService w = worker;
        if (disposed || w == null) return;
        try { w.schedule(new Runnable() { public void run() { enqueue("periodic"); } },
                delay, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private static String safeReason(String reason) {
        if (reason == null) return "unknown";
        if ("periodic".equals(reason) || "start".equals(reason) || "alarm".equals(reason)
                || "boot".equals(reason) || "manual".equals(reason)) return reason;
        return "external";
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
                foreground = true;
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
        foreground = true;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagpiePocket::WakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) { Log.w("Magpie", "Wake lock: " + t.getClass().getSimpleName()); }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MagpiePocket::WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) { Log.w("Magpie", "Wifi lock: " + t.getClass().getSimpleName()); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isGatewayEnabled(this)) {
            KeepAliveScheduler.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        KeepAliveScheduler.schedule(this);
        enqueue("start");
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (isGatewayEnabled(this)) KeepAliveScheduler.schedule(this);
        else KeepAliveScheduler.cancel(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        disposed = true;
        if (worker != null) worker.shutdownNow();
        final GatewayServer toStop;
        synchronized (lifecycle) {
            if (current == this) current = null;
            RUNNING = false;
            toStop = server;
            if (toStop != null) PENDING_STOPS.incrementAndGet();
            server = null;
        }
        if (toStop != null) {
            new Thread(new Runnable() { public void run() {
                synchronized (ENGINE_SERIAL) {
                    try { toStop.stop(); } catch (Throwable t) {
                        Log.w("Magpie", "Gateway stop: " + t.getClass().getSimpleName());
                    } finally { PENDING_STOPS.decrementAndGet(); }
                }
            } }, "gateway-stop").start();
        }
        TYPED_FOREGROUND_OK = false;
        FOREGROUND_TYPE_MASK = 0;
        foreground = false;
        try { stopForeground(true); } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        if (!isGatewayEnabled(this)) KeepAliveScheduler.cancel(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
