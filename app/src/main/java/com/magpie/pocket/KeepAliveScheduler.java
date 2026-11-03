package com.magpie.pocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 非 root 保活：用 AlarmManager 周期唤醒本进程做自检。
 *
 * 为什么这个在没有 root 的机器上有用：
 *  - 国产 ROM（MIUI/HyperOS 实测）把亮屏切后台的进程塞进 cgroup.freeze 后，
 *    端口还 LISTEN，但不响应，等于假活。root 靠 watchdog 轮询 cgroup.freeze 解冻。
 *  - 没有 root 时，唯一还能穿透冻结态把进程叫醒的机制就是 AlarmManager：
 *    闹钟广播投递前系统必须先冻结解冻（thaw）目标进程，广播才送得到。
 *    所以「周期闹钟」不是心灵鸡汤，是能把进程定期解冻回来的动作。
 *  - 自检本身同时兼顾另一种死法：服务被 ROM 直接停掉（端口不再监听），
 *    这时广播里直接把前台服务重新拉起来。
 *
 * 只能做到「定期叫醒 + 发现死了就重启」，做不到「永不被冻结」——
 * 15 分钟一次是 setInexactRepeating 在 Doze 下能被保证的下限，别指望更密。
 */
public class KeepAliveScheduler {

    private static final String TAG = "Magpie";
    private static final String PREFS = "magpie_keepalive";
    private static final String KEY_LAST_CHECK = "lastCheckAt";
    private static final String KEY_LAST_ALIVE = "lastAlive";
    private static final String KEY_LAST_CODE = "lastCode";
    private static final String KEY_REVIVE = "reviveCount";
    private static final String KEY_SCHEDULED_AT = "scheduledAt";
    private static final String KEY_SCHEDULED_ELAPSED = "scheduledElapsed";

    /** Doze 下 setInexactRepeating 的实际节流下限：15 分钟。写小了也不会更快。 */
    public static final long INTERVAL_MS = 15L * 60L * 1000L;
    private static final int REQ_CODE = 3425;

    private static final int FLAG_UPDATE_CURRENT = 0x08000000;
    private static final int FLAG_IMMUTABLE = 0x04000000;

    private static PendingIntent buildPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveReceiver.class);
        i.setAction(KeepAliveReceiver.ACTION_TICK);
        int flags = FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags);
    }

    /** 排程（幂等：重复调用只是续上同一个闹钟） */
    public static boolean schedule(Context ctx) {
        if (ctx == null) return false;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return false;
            long nowElapsed = SystemClock.elapsedRealtime();
            long lastElapsed = prefs(ctx).getLong(KEY_SCHEDULED_ELAPSED, 0L);
            // 5 分钟内刚排过就跳过：多个入口（服务 onCreate/onDestroy、Activity、开机广播）
            // 连着调时，不要每次都把首次触发重置成「+60 秒」——那会让 15 分钟周期退化成 60 秒。
            // 用 elapsedRealtime 判断顺带处理了重启：重启后 nowElapsed 变小，条件不成立，会重新排。
            if (lastElapsed > 0 && nowElapsed >= lastElapsed && nowElapsed - lastElapsed < 5L * 60L * 1000L) {
                return true;
            }
            PendingIntent pi = buildPendingIntent(ctx);
            long first = nowElapsed + 60L * 1000L;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, first, INTERVAL_MS, pi);
            prefs(ctx).edit()
                    .putLong(KEY_SCHEDULED_AT, System.currentTimeMillis())
                    .putLong(KEY_SCHEDULED_ELAPSED, nowElapsed)
                    .apply();
            Log.i(TAG, "keepalive alarm scheduled, interval=" + INTERVAL_MS + "ms");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "keepalive schedule failed: " + t.getMessage());
            return false;
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(buildPendingIntent(ctx));
            prefs(ctx).edit().remove(KEY_SCHEDULED_AT).remove(KEY_SCHEDULED_ELAPSED).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 立即触发一次自检（面板上的「立即自检」按钮走这里） */
    public static void tickNow(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, KeepAliveReceiver.class);
            i.setAction(KeepAliveReceiver.ACTION_TICK);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "tickNow failed: " + t.getMessage());
        }
    }

    static void recordCheck(Context ctx, boolean alive, int code) {
        try {
            prefs(ctx).edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putBoolean(KEY_LAST_ALIVE, alive)
                    .putInt(KEY_LAST_CODE, code)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static void recordRevive(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            sp.edit().putInt(KEY_REVIVE, sp.getInt(KEY_REVIVE, 0) + 1).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 给 /api/permissions 用的真实状态；读不到就如实说没有 */
    public static org.json.JSONObject status(Context ctx) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            SharedPreferences sp = prefs(ctx);
            long scheduledAt = sp.getLong(KEY_SCHEDULED_AT, 0);
            long lastCheck = sp.getLong(KEY_LAST_CHECK, 0);
            long now = System.currentTimeMillis();
            o.put("scheduled", scheduledAt > 0);
            o.put("scheduledAt", scheduledAt);
            o.put("intervalSeconds", INTERVAL_MS / 1000);
            o.put("lastCheckAt", lastCheck);
            o.put("lastCheckAgeSeconds", lastCheck > 0 ? (now - lastCheck) / 1000 : -1);
            o.put("lastAlive", sp.getBoolean(KEY_LAST_ALIVE, false));
            o.put("lastCode", sp.getInt(KEY_LAST_CODE, 0));
            o.put("reviveCount", sp.getInt(KEY_REVIVE, 0));
            // 排程只存在内存里，进程被杀后会丢；这里用「服务是否在跑」作为参考
            o.put("serviceRunning", GatewayService.isRunning());
        } catch (Throwable t) {
            try {
                o.put("scheduled", false);
                o.put("error", t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
