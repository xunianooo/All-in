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
    private static volatile String foregroundStartError = "";
    private final Object lifecycle = new Object();
    private static final Object ENGINE_SERIAL = new Object();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_STOPS = new java.util.concurrent.atomic.AtomicInteger();
    private final RecoveryPolicy recoveryPolicy = new RecoveryPolicy();
    private volatile GatewayServer server;
    // A failed stop keeps the retired listener visible to lifecycle management.
    private volatile boolean backupStopUncertain;
    private volatile boolean disposed;
    private volatile boolean foreground;
    private volatile long createdAt, lastProbeAt, lastProbeMs;
    private volatile int lastCode, failures, restartCount;
    private volatile String phase = "created", lastReason = "", errorType = "";
    private java.util.concurrent.ScheduledExecutorService worker;
    private final java.util.concurrent.atomic.AtomicBoolean checkQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private BackgroundKeepAlive backgroundKeepAlive;

    public static void refreshKeepAlive(Context context) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() { GatewayService s = current; if (s != null && !s.disposed && s.foreground) s.refreshBackgroundExperiment(); }
        });
    }

    private void refreshBackgroundExperiment() {
        if (backgroundKeepAlive == null) backgroundKeepAlive = new BackgroundKeepAlive(this);
        final boolean wantsAudio = BackgroundKeepAlive.audioEnabled(this);
        int target = ForegroundServicePolicy.typeForSdk(Build.VERSION.SDK_INT, wantsAudio);
        if (foreground && FOREGROUND_TYPE_MASK != target) {
            int previousType = FOREGROUND_TYPE_MASK;
            if (!startForegroundNotification()) {
                // A rejected type update does not cancel an already established foreground service.
                foreground = true; TYPED_FOREGROUND_OK = Build.VERSION.SDK_INT >= 29;
                FOREGROUND_TYPE_MASK = previousType;
                backgroundKeepAlive.refresh(false); return;
            }
        }
        boolean allowed = foreground && (Build.VERSION.SDK_INT < 29 || (FOREGROUND_TYPE_MASK & ForegroundServicePolicy.MEDIA_PLAYBACK) != 0);
        backgroundKeepAlive.refresh(allowed);
    }

    public static boolean isTypedForeground() { return TYPED_FOREGROUND_OK; }
    public static int foregroundTypeMask() { return FOREGROUND_TYPE_MASK; }
    public static String foregroundTypeName() {
        GatewayService s = current;
        return s != null && s.foreground ? ForegroundServicePolicy.name(FOREGROUND_TYPE_MASK) : "unknown";
    }
    public static boolean isForegroundStarted() { GatewayService s = current; return s != null && s.foreground; }
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
            if (!instance.foreground && !foregroundStartError.isEmpty()) {
                KeepAliveScheduler.recordOutcome(context, "foregroundStartFailed", foregroundStartError);
            } else instance.enqueue(reason);
            return;
        }
        try {
            Intent intent = new Intent(context, GatewayService.class);
            // Record the request before dispatch: a synchronous promotion failure
            // must remain the newer outcome rather than being overwritten here.
            KeepAliveScheduler.recordOutcome(context, "restartRequested", "");
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
            } else context.startService(intent);
            startError = "";
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
            GatewayServer.ListenPlan plan = server == null ? null : server.listenPlan();
            o.put("listenAddress", plan == null ? "" : plan.host);
            o.put("listenAction", plan == null ? "" : plan.action);
            o.put("listenNote", plan == null ? "" : plan.note);
            o.put("foreground", s != null && s.foreground);
            o.put("typedForeground", TYPED_FOREGROUND_OK);
            o.put("foregroundTypeMask", FOREGROUND_TYPE_MASK);
            o.put("foregroundTypeName", foregroundTypeName());
            o.put("expectedForegroundTypeMask", ForegroundServicePolicy.typeForSdk(Build.VERSION.SDK_INT, BackgroundKeepAlive.audioEnabled(context)));
            o.put("expectedForegroundTypeName", ForegroundServicePolicy.name(ForegroundServicePolicy.typeForSdk(Build.VERSION.SDK_INT, BackgroundKeepAlive.audioEnabled(context))));
            o.put("foregroundStartError", foregroundStartError);
            o.put("wakeLockHeld", s != null && s.wakeLock != null && s.wakeLock.isHeld());
            o.put("wifiLockHeld", s != null && s.wifiLock != null && s.wifiLock.isHeld());
            o.put("backgroundKeepAlive", BackgroundKeepAlive.status(context));
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
        synchronized (ENGINE_SERIAL) { current = this; }
        createNotificationChannel();
        if (!startForegroundNotification()) {
            phase = "foregroundStartFailed";
            RUNNING = false;
            KeepAliveScheduler.cancel(this);
            KeepAliveScheduler.recordOutcome(this, "foregroundStartFailed", foregroundStartError);
            stopSelf();
            return;
        }
        if (!isGatewayEnabled(this)) {
            phase = "disabled";
            KeepAliveScheduler.cancel(this);
            stopSelf();
            return;
        }
        acquireLocks();
        backgroundKeepAlive = new BackgroundKeepAlive(this);
        refreshBackgroundExperiment();
        worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try { check("periodic"); } catch (Throwable t) { RUNNING = false; errorType = t.getClass().getSimpleName(); phase = "checkError"; }
            }
        }, 2, 12, java.util.concurrent.TimeUnit.SECONDS);
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
        if (backupStopUncertain) {
            synchronized (ENGINE_SERIAL) {
                synchronized (lifecycle) { if (disposed || server != own) return; }
                try {
                    if (own != null) own.stop();
                    synchronized (lifecycle) { if (server == own) server = null; backupStopUncertain = false; }
                } catch (Throwable stopFailure) {
                    RUNNING = false; phase = "restoreStopFailed"; errorType = stopFailure.getClass().getSimpleName();
                    scheduleFollowup(2000L); return;
                }
            }
            startEngine(); return;
        }
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
            synchronized (lifecycle) {
                if (disposed || !isGatewayEnabled(this) || server != own) return;
                recoveryPolicy.probeSucceeded();
                failures = 0;
                phase = "healthy";
                RUNNING = own.isAlive();
            }
            return;
        }
        synchronized (lifecycle) {
            if (disposed || !isGatewayEnabled(this) || server != own) return;
            RUNNING = false;
            recoveryPolicy.probeFailed();
            failures = recoveryPolicy.failures();
            phase = "unhealthy";
        }
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

    /** 网关固定端口：server.port 不参与 bind（换端口会让已按 3425 配好的客户端全部失联）。 */
    static final int GATEWAY_PORT = 3425;

    /**
     * 按磁盘配置折算出的监听计划创建网关。
     *
     * 只有配置显式 server.loopbackOnly=true 才 bind 127.0.0.1；旧配置里的 127.0.0.1 与损坏配置各有
     * 明确处理：前者保留历史通配监听（不因升级静默切断既有局域网），后者 fail-closed 到回环。两者都
     * 在日志与 /api/status 的 listenAction 里留下可操作提示。
     */
    static GatewayServer createGatewayServer(Context context) {
        GatewayServer.ListenPlan plan = GatewayServer.planListen(
                context == null ? null : new java.io.File(context.getFilesDir(), "config.json"), GATEWAY_PORT);
        if (plan.loopback() && !plan.actionRequired() && plan.loopbackOnlyRequested)
            Log.i("Magpie", "网关仅监听 127.0.0.1（配置 server.loopbackOnly 生效）");
        if (plan.actionRequired())
            Log.w("Magpie", "监听端点需要处理[" + plan.action + "]：" + plan.note
                    + "；实际监听 " + plan.host + ":" + plan.port);
        return new GatewayServer(context, plan);
    }

    private void startEngine() {
        synchronized (ENGINE_SERIAL) {
            if (disposed || !isGatewayEnabled(this)) return;
            if (backupStopUncertain) { phase = "restoreStopFailed"; scheduleFollowup(2000L); return; }
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
                candidate = createGatewayServer(this);
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

    private boolean startForegroundNotification() {
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
            foregroundStartError = "";
            return true;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            foregroundStartError = cause.getClass().getSimpleName();
            errorType = foregroundStartError;
            foreground = false;
            TYPED_FOREGROUND_OK = false;
            FOREGROUND_TYPE_MASK = 0;
            Log.w("Magpie", "Foreground promotion failed: " + foregroundStartError);
            return false;
        }
    }

    /** API 34+ uses specialUse; 29-33 retains dataSync; older releases use legacy FGS. */
    private void startForegroundWithType(final Notification notification) throws Exception {
        ForegroundServicePolicy.Promoter promoter = new ForegroundServicePolicy.Promoter() {
            @Override public void typed(int type) throws Exception {
                Method method = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                method.invoke(GatewayService.this, Integer.valueOf(NOTIFICATION_ID), notification, Integer.valueOf(type));
            }
            @Override public void legacy() { startForeground(NOTIFICATION_ID, notification); }
        };
        int type;
        try { type = ForegroundServicePolicy.promote(Build.VERSION.SDK_INT, BackgroundKeepAlive.audioEnabled(this), promoter); }
        catch (Exception experimentalFailure) {
            if (!BackgroundKeepAlive.audioEnabled(this)) throw experimentalFailure;
            // Keep the base gateway running even when an OEM rejects the optional media type.
            type = ForegroundServicePolicy.promote(Build.VERSION.SDK_INT, false, promoter);
        }
        foreground = true;
        TYPED_FOREGROUND_OK = Build.VERSION.SDK_INT >= 29;
        FOREGROUND_TYPE_MASK = type;
        Log.i("Magpie", "startForeground type=" + ForegroundServicePolicy.name(type));
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
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MagpiePocket::WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) { Log.w("Magpie", "Wifi lock: " + t.getClass().getSimpleName()); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) { stopSelf(); return START_NOT_STICKY; }
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

    /** Native backup uses the same engine lock as watchdog start/stop. */
    static BackupCoordinator.Backend backupBackend(final Context context) {
        final Context app = context.getApplicationContext();
        return new BackupCoordinator.Backend() {
            /**
             * Reports an absent durable configuration instead of inventing one: the running
             * server throws "config missing" and the file reader throws when the file is gone, so
             * a restore over a configuration that does not exist is refused during the preview,
             * before the gateway is stopped (see BackupCoordinator's class comment).
             */
            public byte[] readConfig() throws Exception {
                synchronized (ENGINE_SERIAL) {
                    if (PENDING_STOPS.get() != 0) throw new IllegalStateException("gateway stopping");
                    GatewayService s = current;
                    GatewayServer own = s == null ? null : s.server;
                    return own != null ? own.backupDurableConfig() : BackupFiles.readFile(
                            new java.io.File(app.getFilesDir(), "config.json"), EncryptedConfigBackup.MAX_CONFIG_BYTES);
                }
            }
            /**
             * Preserve an unparseable config.json before a restore replaces it. The durable bytes
             * are re-read and matched against the CAS anchor, then written to a content-addressed
             * rescue copy (the same name the recovery state already uses) and verified by reading
             * it back. Any failure aborts the restore before the gateway is stopped.
             */
            public String preserveDamagedConfig(byte[] hash, byte[] damaged) throws Exception {
                synchronized (ENGINE_SERIAL) {
                    if (PENDING_STOPS.get() != 0) throw new IllegalStateException("gateway stopping");
                    return BackupRestoreTransaction.preserveDamaged(
                            new java.io.File(app.getFilesDir(), "config.json"), hash, damaged);
                }
            }
            public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) throws Exception {
                synchronized (ENGINE_SERIAL) {
                    if (PENDING_STOPS.get() != 0) throw new IllegalStateException("gateway stopping");
                    org.json.JSONObject restoreConfig = EncryptedConfigBackup.validateConfig(replacement);
                    Object restoreNetwork = restoreConfig.opt("network");
                    if (restoreConfig.has("network") && !(restoreNetwork instanceof org.json.JSONObject))
                        throw new IllegalArgumentException("network config");
                    NetworkPolicy.parse((org.json.JSONObject) restoreNetwork);
                    final GatewayService owner = current;
                    final boolean enabled = isGatewayEnabled(app);
                    if (enabled && (owner == null || owner.disposed)) throw new IllegalStateException("gateway unavailable");
                    final java.io.File config = new java.io.File(app.getFilesDir(), "config.json");
                    BackupRestoreTransaction.Engine engine = new BackupRestoreTransaction.Engine() {
                        public byte[] readCurrent() throws Exception {
                            GatewayServer own = owner == null ? null : owner.server;
                            return own != null ? own.backupDurableConfig() : BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
                        }
                        public void retireAndStop() throws Exception {
                            if (owner == null) return;
                            GatewayServer own;
                            synchronized (owner.lifecycle) { own = owner.server; if (own != null) own.retireForRestore(); RUNNING = false; owner.phase = "restoring"; }
                            if (own != null) {
                                try { own.stop(); }
                                catch (Throwable failure) { owner.backupStopUncertain = true; throw new IllegalStateException("gateway stop failed", failure); }
                                synchronized (owner.lifecycle) { if (owner.server == own) owner.server = null; owner.backupStopUncertain = false; }
                            }
                        }
                        public void restart() throws Exception {
                            if (!enabled) return;
                            if (owner.disposed || current != owner || !isGatewayEnabled(app)) throw new IllegalStateException("gateway lifecycle changed");
                            GatewayServer candidate = createGatewayServer(owner);
                            boolean published = false;
                            synchronized (owner.lifecycle) { owner.server = candidate; }
                            try {
                                candidate.start();
                                if (!candidate.isAlive()) throw new IllegalStateException("gateway failed to start");
                                synchronized (owner.lifecycle) { owner.server = candidate; RUNNING = false; owner.phase = "awaitingProbe"; published = true; }
                                owner.scheduleFollowup(2000L);
                            } finally {
                                if (!published) {
                                    candidate.retireForRestore();
                                    try { candidate.stop(); synchronized (owner.lifecycle) { if (owner.server == candidate) owner.server = null; } }
                                    catch (Throwable stopFailure) { owner.backupStopUncertain = true; throw new IllegalStateException("candidate stop failed", stopFailure); }
                                }
                            }
                        }
                    };
                    try {
                        new BackupRestoreTransaction(config, new java.io.File(app.getFilesDir(), "config-rollback.aibk"),
                                engine, stage -> { }).apply(hash, replacement, rollback);
                    } catch (Exception failure) {
                        if (owner != null) {
                            synchronized (owner.lifecycle) {
                                RUNNING = false;
                                owner.phase = "restoreFailed";
                                owner.errorType = failure.getClass().getSimpleName();
                            }
                            if (!owner.disposed && isGatewayEnabled(app)) owner.scheduleFollowup(2000L);
                        }
                        throw failure;
                    } finally { GatewayAuthKeyHolder.invalidate(); }
                }
            }
        };
    }

    @Override public void onDestroy() {
        final GatewayServer toStop;
        if (backgroundKeepAlive != null) backgroundKeepAlive.close();
        synchronized (ENGINE_SERIAL) {
            disposed = true;
            if (worker != null) worker.shutdownNow();
            synchronized (lifecycle) {
                if (current == this) current = null;
                RUNNING = false;
                toStop = server;
                if (toStop != null) PENDING_STOPS.incrementAndGet();
                server = null;
            }
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
