package com.gaeul.allin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import com.gaeul.allin.json.JSONObject;

/** Inexact one-shot requests: delivery depends on Android and vendor policy. */
public class KeepAliveScheduler {
    public static final long INTERVAL_MS = 15L * 60L * 1000L;
    private static final int REQ_CODE = 3425;
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("magpie_keepalive", Context.MODE_PRIVATE); }
    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, KeepAliveReceiver.class).setAction(KeepAliveReceiver.ACTION_TICK)
                .putExtra(KeepAliveReceiver.EXTRA_ALARM, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_CODE, i, flags);
    }
    static boolean pendingRequestFresh(String mode,long scheduled,long target,long now,long wallAge) {
        return "inexactOneShot".equals(mode) && scheduled>0 && now>=scheduled && target>scheduled && wallAge>=0
                && Math.abs(wallAge-(now-scheduled))<60000L
                // Allow normal inexact/Doze delay, but never trust cancelled alarm metadata forever.
                && (now<=target || now-target<=2L*INTERVAL_MS);
    }
    public static synchronized boolean schedule(Context c) {
        if (c == null) return false;
        if (!GatewayService.isGatewayEnabled(c)) { cancel(c); return false; }
        SharedPreferences p = prefs(c);
        long now = SystemClock.elapsedRealtime();
        long scheduled = p.getLong("scheduledElapsed", 0);
        long target = p.getLong("nextElapsed", 0);
        long scheduledWall = p.getLong("scheduledAt", 0);
        long wallAge = System.currentTimeMillis() - scheduledWall;
        // A reboot changes elapsed time; wall/elapsed mismatch also catches fast reboots.
        if (pendingRequestFresh(p.getString("mode", ""),scheduled,target,now,wallAge)) return true;
        return request(c, now);
    }
    static synchronized void rearmAfterDelivery(Context c) {
        if (!GatewayService.isGatewayEnabled(c)) { cancel(c); return; }
        request(c, SystemClock.elapsedRealtime());
    }
    private static boolean request(Context c, long now) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) { recordOutcome(c, "scheduleFailed", "AlarmManagerUnavailable"); return false; }
            long target = now + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, pending(c));
            else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, pending(c));
            long wall = System.currentTimeMillis();
            prefs(c).edit().putLong("scheduledAt", wall).putLong("scheduledElapsed", now)
                    .putLong("nextElapsed", target).putLong("nextRequestedAt", wall + INTERVAL_MS)
                    .putString("mode", "inexactOneShot").apply();
            return true;
        } catch (Throwable t) {
            recordOutcome(c, "scheduleFailed", t.getClass().getSimpleName());
            Log.w("Magpie", "Alarm request failed: " + t.getClass().getSimpleName());
            return false;
        }
    }
    public static synchronized void cancel(Context c) {
        if (c == null) return;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c));
        } catch (Throwable ignored) { }
        prefs(c).edit().remove("scheduledAt").remove("scheduledElapsed").remove("nextElapsed")
                .remove("nextRequestedAt").remove("mode").apply();
    }
    public static void tickNow(Context c) {
        if (c == null) return;
        c.sendBroadcast(new Intent(c, KeepAliveReceiver.class).setAction(KeepAliveReceiver.ACTION_TICK));
    }
    static void recordCheck(Context c, boolean alive, int code) {
        prefs(c).edit().putLong("lastCheckAt", System.currentTimeMillis()).putBoolean("lastAlive", alive)
                .putInt("lastCode", code).putLong("lastOutcomeAt", System.currentTimeMillis())
                .putString("lastOutcome", alive ? "healthy" : "unhealthy")
                .putString("lastErrorType", "").apply();
    }
    static void recordOutcome(Context c, String outcome, String error) {
        prefs(c).edit().putString("lastOutcome", outcome).putString("lastErrorType", error)
                .putLong("lastOutcomeAt", System.currentTimeMillis()).apply();
    }
    static void recordRevive(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putInt("reviveCount", p.getInt("reviveCount", 0) + 1).apply();
    }
    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = prefs(c);
            long at = p.getLong("scheduledAt", 0), check = p.getLong("lastCheckAt", 0);
            o.put("scheduled", at > 0); // persisted request, not proof of delivery
            o.put("mode", p.getString("mode", "unknown"));
            o.put("scheduledAt", at);
            o.put("nextRequestedAt", p.getLong("nextRequestedAt", 0));
            o.put("intervalSeconds", INTERVAL_MS / 1000);
            o.put("lastCheckAt", check);
            o.put("lastCheckAgeSeconds", check > 0 ? (System.currentTimeMillis() - check) / 1000 : -1);
            o.put("lastAlive", p.getBoolean("lastAlive", false));
            o.put("lastCode", p.getInt("lastCode", 0));
            o.put("reviveCount", p.getInt("reviveCount", 0));
            o.put("lastOutcome", p.getString("lastOutcome", ""));
            o.put("lastOutcomeAt", p.getLong("lastOutcomeAt", 0));
            o.put("lastErrorType", p.getString("lastErrorType", ""));
            o.put("serviceRunning", GatewayService.isRunning());
        } catch (Throwable ignored) { }
        return o;
    }
}
