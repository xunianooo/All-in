package com.gaeul.allin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** An alarm requests a check; it does not guarantee that the OS delivers it. */
public class KeepAliveReceiver extends BroadcastReceiver {
    public static final String ACTION_TICK = "com.gaeul.allin.KEEPALIVE_TICK";
    static final String EXTRA_ALARM = "scheduledAlarm";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_TICK.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final boolean alarm = intent.getBooleanExtra(EXTRA_ALARM, false);
        final PendingResult pending = goAsync();
        try {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (!GatewayService.isGatewayEnabled(app)) {
                            KeepAliveScheduler.cancel(app);
                            return;
                        }
                        if (alarm) KeepAliveScheduler.rearmAfterDelivery(app);
                        GatewayService.requestHealthCheck(app, alarm ? "alarm" : "manual");
                    } catch (Throwable t) {
                        Log.w("Magpie", "Keepalive dispatch: " + t.getClass().getSimpleName());
                    } finally { pending.finish(); }
                }
            }, "keepalive-dispatch").start();
        } catch (Throwable t) {
            Log.w("Magpie", "Keepalive dispatch start: " + t.getClass().getSimpleName());
            pending.finish();
        }
    }
}
