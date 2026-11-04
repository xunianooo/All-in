package com.gaeul.allin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 权限与保活中心。
 *
 * 背景：网关是常驻 HTTP 服务，一旦被系统冻结（进程所有线程停在 do_freezer_trap），
 * 端口虽仍处于 LISTEN 状态，但任何请求都不会被处理，表现为"网关突然不响应"。
 * 要避免被冻结，需要同时满足：
 *   1. 前台服务声明了正确的 foregroundServiceType（Android 14+ 要求，否则拿不到 FGS 豁免）
 *   2. 通知没有被用户关闭（FGS 通知被抑制时，部分 ROM 会降级该服务的优先级）
 *   3. 应用在电池优化白名单内（ColorOS / MIUI 等还会额外做"应用冻结""后台限制"）
 *
 * 本类负责：查询上述状态，以及跳转到对应的系统设置页让用户自己开。
 * 注意：工程用的是精简版 android.jar，areNotificationsEnabled / isIgnoringBatteryOptimizations
 * 等新 API 在编译期不存在，因此全部走反射，缺失时按"未知"处理而不是崩溃。
 */
public class PermissionCenter {

    /** 系统设置页的 action 常量（精简 android.jar 里没有 Settings 类，直接用字符串） */
    private static final String ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS";
    private static final String ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    private static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS";
    private static final String ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS";
    private static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";

    /**
     * 采集当前权限状态。
     * 返回值字段：
     *   notifications.granted  —— 通知是否可用（false 时 FGS 通知会被吞掉）
     *   notifications.runtime  —— POST_NOTIFICATIONS 运行时权限是否授予（Android 13+）
     *   battery.ignored        —— 是否已在电池优化白名单
     *   foregroundService.type —— 服务声明的 foregroundServiceType 位掩码，-1 表示读不到
     *   foregroundService.ok   —— 是否满足"有类型"这一前提
     *   frozen                 —— 进程当前是否被冻结（由调用方补充，这里留空）
     */
    public static JSONObject status(Context ctx) {
        JSONObject out = new JSONObject();
        if (ctx == null) return out;
        String pkg = ctx.getPackageName();

        // ---------- 通知 ----------
        JSONObject noti = new JSONObject();
        boolean granted = false;
        boolean notificationKnown = false;
        boolean runtime = true;
        int importance = -1;
        try {
            Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    Method m = nm.getClass().getMethod("areNotificationsEnabled");
                    Object r = m.invoke(nm);
                    if (r instanceof Boolean) { granted = ((Boolean) r).booleanValue(); notificationKnown = true; }
                } catch (Throwable ignored) {
                }
                try {
                    Method m = nm.getClass().getMethod("getImportance");
                    Object r = m.invoke(nm);
                    if (r instanceof Integer) importance = ((Integer) r).intValue();
                } catch (Throwable ignored) {
                }
            }
            // Android 13+ 的运行时权限，未授予时通知一律被丢弃
            if (Build.VERSION.SDK_INT >= 33) {
                runtime = ctx.checkCallingOrSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!runtime) { granted = false; notificationKnown = true; }
            }
        } catch (Throwable ignored) {
        }
        put(noti, "known", notificationKnown);
        put(noti, "granted", granted);
        put(noti, "runtime", runtime);
        put(noti, "importance", importance);
        put(noti, "ok", notificationKnown && granted);
        put(out, "notifications", noti);

        // ---------- 电池优化白名单 ----------
        JSONObject battery = new JSONObject();
        boolean ignored = false;
        boolean known = false;
        try {
            Object pm = ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                Method m = pm.getClass().getMethod("isIgnoringBatteryOptimizations", String.class);
                Object r = m.invoke(pm, new Object[]{pkg});
                if (r instanceof Boolean) {
                    ignored = ((Boolean) r).booleanValue();
                    known = true;
                }
            }
        } catch (Throwable ignored2) {
        }
        put(battery, "ignored", ignored);
        put(battery, "known", known);
        put(battery, "ok", known && ignored);
        put(out, "battery", battery);

        // ---------- 前台服务类型 ----------
        // 说明：该 ROM 的 ServiceInfo 只暴露 FOREGROUND_SERVICE_TYPE_* 常量，
        // 没有 foregroundServiceType 实例字段，反射读不到。
        // 于是改为采信服务启动时「带类型调用 startForeground 是否成功」这一真实结果，
        // 这比读 manifest 元数据更贴近实际运行状态。
        JSONObject fgs = new JSONObject();
        int typeMask = GatewayService.foregroundTypeMask();
        boolean typed = GatewayService.isTypedForeground();
        put(fgs, "type", typeMask);
        put(fgs, "ok", typed);
        put(fgs, "source", "runtime");
        if (!typed && GatewayService.isRunning()) {
            put(fgs, "error", "typed-start-foreground-failed");
        }
        put(out, "foregroundService", fgs);

        // ---------- 自启动 / 省电策略 ----------
        // 这两项在 MIUI/HyperOS 上没有任何公开 API 可读：自启动开关存在
        // com.miui.securitycenter 的私有数据库里，应用级省电策略存在
        // com.miui.powerkeeper 里，普通应用身份都读不到。
        //
        // 无公开接口时只记录用户确认，不能推断系统设置：
        //   acked   —— 用户点过「我已设置」
        //   ok      —— 未经系统验证时为 false
        JSONObject auto = new JSONObject();
        boolean autoAck = isAcked(ctx, "autostart");
        put(auto, "detectable", false);
        put(auto, "acked", autoAck);
        put(auto, "ackedAt", ackedAt(ctx, "autostart"));
        put(auto, "verified", false);
        put(auto, "status", autoAck ? "user_confirmed" : "unknown");
        put(auto, "ok", false);
        put(out, "autostart", auto);

        JSONObject powersave = new JSONObject();
        boolean psAck = isAcked(ctx, "powersave");
        put(powersave, "detectable", false);
        put(powersave, "acked", psAck);
        put(powersave, "ackedAt", ackedAt(ctx, "powersave"));
        put(powersave, "verified", false);
        put(powersave, "status", psAck ? "user_confirmed" : "unknown");
        put(powersave, "ok", false);
        put(out, "powersave", powersave);

        put(out, "sdk", Build.VERSION.SDK_INT);
        put(out, "packageName", pkg);
        put(out, "rom", brandFamily(Build.MANUFACTURER, Build.BRAND));
        return out;
    }

    // ===== 用户确认状态 =====
    // 自启动 / 省电策略都读不到，只能由用户自己确认过一次并记下来。

    private static final String PREFS = "magpie_perms";

    public static boolean isAcked(Context ctx, String target) {
        if (ctx == null || target == null) return false;
        try {
            return ctx.getSharedPreferences(PREFS, 0).getBoolean("ack_" + target, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static long ackedAt(Context ctx, String target) {
        if (ctx == null || target == null) return 0L;
        try {
            return ctx.getSharedPreferences(PREFS, 0).getLong("ackAt_" + target, 0L);
        } catch (Throwable e) {
            return 0L;
        }
    }

    public static boolean setAck(Context ctx, String target, boolean on) {
        if (ctx == null || target == null) return false;
        try {
            ctx.getSharedPreferences(PREFS, 0).edit()
                    .putBoolean("ack_" + target, on)
                    .putLong("ackAt_" + target, on ? System.currentTimeMillis() : 0L)
                    .commit();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String brandFamily(String manufacturer, String brand) {
        String[] values = {brand, manufacturer};
        for (String value : values) {
            String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals("xiaomi") || v.equals("redmi") || v.equals("poco")) return "miui";
            if (v.equals("oppo") || v.equals("oneplus") || v.equals("realme")) return "coloros";
            if (v.equals("vivo") || v.equals("iqoo")) return "vivo";
            if (v.equals("huawei")) return "huawei";
            if (v.equals("honor")) return "honor";
            if (v.equals("samsung")) return "samsung";
        }
        return "other";
    }

    public static boolean isMiui() {
        return "miui".equals(brandFamily(Build.MANUFACTURER, Build.BRAND));
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 跳转到与 target 对应的系统设置页。
     * @param target notifications / battery / app / autostart
     * @return 是否成功唤起了某个页面
     */
    public static boolean openSettings(Context ctx, String target) {
        if (ctx == null) return false;
        String pkg = ctx.getPackageName();
        String t = (target == null) ? "" : target.trim().toLowerCase();

        if ("notifications".equals(t) || "notification".equals(t)) {
            // 先尝试直接跳到本应用的"通知"子页，失败再退到应用详情
            try {
                Intent i = new Intent(ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(EXTRA_APP_PACKAGE, pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
            return openAppDetails(ctx);
        }

        if ("battery".equals(t)) {
            // 优先弹系统的"忽略电池优化"授权框（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
            try {
                Intent i = new Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
            // 退而求其次：电池优化列表页
            try {
                Intent i = new Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
            return openAppDetails(ctx);
        }

        if ("autostart".equals(t)) {
            String rom = brandFamily(Build.MANUFACTURER, Build.BRAND);
            if ("miui".equals(rom) && openAppDetails(ctx)) return true;
            String[][] candidates;
            if ("miui".equals(rom)) candidates = new String[][]{
                    {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}};
            else if ("coloros".equals(rom)) candidates = new String[][]{
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                    {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                    {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"}};
            else if ("vivo".equals(rom)) candidates = new String[][]{
                    {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"}};
            else if ("huawei".equals(rom)) candidates = new String[][]{
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}};
            else candidates = new String[0][];
            for (String[] c : candidates) {
                try {
                    Intent i = new Intent();
                    i.setClassName(c[0], c[1]);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                } catch (Throwable ignoredAuto) { }
            }
            return openAppDetails(ctx);
        }

        if ("powersave".equals(t)) return openAppDetails(ctx);

        return openAppDetails(ctx);
    }

    /** 应用详情页：所有 ROM 都有，作为兜底入口 */
    private static boolean openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
