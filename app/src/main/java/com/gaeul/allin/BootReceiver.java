package com.gaeul.allin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (um == null || !um.isUserUnlocked()) return;
            } catch (Throwable t) { return; }
        }
        if (!GatewayService.isGatewayEnabled(context)) { KeepAliveScheduler.cancel(context); return; }
        KeepAliveScheduler.schedule(context);
        GatewayService.requestHealthCheck(context, "boot");
    }
}
