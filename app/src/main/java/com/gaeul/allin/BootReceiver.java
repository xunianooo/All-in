package com.gaeul.allin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * 开机自启：把网关前台服务在系统启动完成后拉起来。
 *
 * 之前 manifest 里声明了 RECEIVE_BOOT_COMPLETED 权限，但没有任何 receiver，
 * 所以重启手机后 3425 不会监听，必须手动打开 App —— 换到别人手机上时这一点尤其明显。
 *
 * 注意：
 *  - targetSdk 31+ 的 receiver 必须显式写 android:exported，否则安装失败；
 *    系统广播可以投递给 exported=false 的 receiver，不需要对外暴露。
 *  - 用户关过网关开关（magpie_prefs/gateway_enabled=false）时不自启，尊重用户选择。
 *  - 精简 android.jar 没有 Context.startForegroundService，只能反射。
 *  - BOOT_COMPLETED 是 Android 12+ FGS 后台启动限制的豁免场景之一。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "magpie_prefs";
    private static final String PREF_GATEWAY_ENABLED = "gateway_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        if (!isGatewayEnabled(context)) return;

        // 启动成功与否没有回调，失败时退回 startService 兜一次
        if (!startFgs(context)) {
            try {
                context.startService(new Intent(context, GatewayService.class));
            } catch (Throwable ignored) {
            }
        }

        // 开机后把周期自检也排上：闹钟排程存在系统里，进程被杀也不丢
        try {
            KeepAliveScheduler.schedule(context);
        } catch (Throwable ignored) {
        }
    }

    private boolean isGatewayEnabled(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return sp.getBoolean(PREF_GATEWAY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean startFgs(Context context) {
        Intent svc = new Intent(context, GatewayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(context, svc);
                return true;
            } catch (Throwable ignored) {
            }
        }
        try {
            context.startService(svc);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
