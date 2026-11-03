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
        boolean granted = true;
        boolean runtime = true;
        int importance = -1;
        try {
            Object nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    Method m = nm.getClass().getMethod("areNotificationsEnabled");
                    Object r = m.invoke(nm);
                    if (r instanceof Boolean) granted = ((Boolean) r).booleanValue();
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
                        == 0; // PackageManager.PERMISSION_GRANTED
                if (!runtime) granted = false;
            }
        } catch (Throwable ignored) {
        }
        put(noti, "granted", granted);
        put(noti, "runtime", runtime);
        put(noti, "importance", importance);
        put(noti, "ok", granted);
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
        put(battery, "ok", !known || ignored);
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
        // 所以不假装能检测，改成「用户自己确认 + 用冻结事实反推」：
        //   acked   —— 用户点过「我已设置」
        //   ok      —— 只有 acked 才算 ok，绝不硬编码成失败
        JSONObject auto = new JSONObject();
        boolean autoAck = isAcked(ctx, "autostart");
        put(auto, "detectable", false);
        put(auto, "acked", autoAck);
        put(auto, "ackedAt", ackedAt(ctx, "autostart"));
        put(auto, "ok", autoAck);
        put(out, "autostart", auto);

        JSONObject powersave = new JSONObject();
        boolean psAck = isAcked(ctx, "powersave");
        put(powersave, "detectable", false);
        put(powersave, "acked", psAck);
        put(powersave, "ackedAt", ackedAt(ctx, "powersave"));
        put(powersave, "ok", psAck);
        put(out, "powersave", powersave);

        put(out, "sdk", Build.VERSION.SDK_INT);
        put(out, "packageName", pkg);
        put(out, "rom", isMiui() ? "miui" : "other");
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

    /** 是否小米系 ROM */
    public static boolean isMiui() {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
            String v = (o == null) ? "" : o.toString();
            if (v != null && v.length() > 0) return true;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class).invoke(null, "ro.miui.build.region");
            String v = (o == null) ? "" : o.toString();
            if (v != null && v.length() > 0) return true;
        } catch (Throwable ignored) {
        }
        return false;
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
    /**
     * 小米/Redmi 的「应用信息」页：一页里同时有「省电策略」和「自启动」两项，
     * 是本机实测唯一能直接落到应用级省电策略的入口。
     * （PowerSettings 打开的是全局省电页，改不了单个应用的"无限制"）
     */
    private static boolean openMiuiAppInfo(Context ctx, String pkg) {
        // 首选系统标准 action：MIUI 会把它路由到自家的「应用信息」页，
        // 而直接点名私有类名 com.miui.appmanager.ApplicationsDetailsActivity
        // 在普通应用身份下会被拦（实测启动后立刻回退），只有 root shell 能启动。
        if (openAppDetails(ctx)) return true;
        String[][] candidates = new String[][]{
                {"com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"},
                {"com.miui.securitycenter", "com.miui.appmanager.AppManagerMainActivity"}
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setClassName(c[0], c[1]);
                i.putExtra("packageName", pkg);
                i.putExtra("extra_package_uid", ctx.getApplicationInfo().uid);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

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
            // 本机实测：应用信息页里直接就有「自启动」开关，比独立的自启动列表页更好找
            if (isMiui() && openMiuiAppInfo(ctx, pkg)) return true;
            // 国产 ROM 的自启动管理页不在 AOSP 标准里，按常见程度逐个尝试。
            String[][] candidates = new String[][]{
                    // 小米 / Redmi (MIUI / HyperOS)
                    {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                    // OPPO / 一加 / realme (ColorOS)
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                    {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                    {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                    // vivo
                    {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                    // 华为 / 荣耀
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                    // 三星 / 其它
                    {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                    {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"}
            };
            for (String[] c : candidates) {
                try {
                    Intent i = new Intent();
                    i.setClassName(c[0], c[1]);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                } catch (Throwable ignoredAuto) {
                }
            }
            return openAppDetails(ctx);
        }

        if ("powersave".equals(t)) {
            // 应用级「省电策略 → 无限制」只在本应用的详情页里，全局省电页改不了它。
            if (isMiui() && openMiuiAppInfo(ctx, pkg)) return true;
            String[][] powerPages = new String[][]{
                    {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
                    {"com.miui.securitycenter", "com.miui.powercenter.PowerMainActivity"},
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"}
            };
            for (String[] c : powerPages) {
                try {
                    Intent i = new Intent();
                    i.setClassName(c[0], c[1]);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                } catch (Throwable ignoredPw) {
                }
            }
            // 退回系统的电池使用情况页
            try {
                Intent i = new Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignoredPw2) {
            }
            return openAppDetails(ctx);
        }

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
