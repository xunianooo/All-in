package com.magpie.pocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * AlarmManager 周期自检的执行体。
 *
 * 判活不看「进程还在不在」，而是打一次本地 HTTP：
 *  - 连不上（ConnectException）→ 服务确实没了，重启前台服务；
 *  - 连上但读不到响应（超时）→ MIUI 冻结态的典型表现（端口 LISTEN 却不回包），
 *    重试一次仍超时就当假活，重启服务；
 *  - 任何 2xx/4xx 响应 → 服务活着（401 只是口令对不上，不代表服务坏了）。
 *
 * 只在自检失败时才重启，正常情况一次自检就是一次连接 + 一次读，代价极小。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.magpie.pocket.KEEPALIVE_TICK";
    private static final String TAG = "Magpie";
    private static final int PORT = 3425;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (context == null) return;
        if (intent != null && intent.getAction() != null
                && !ACTION_TICK.equals(intent.getAction())) {
            return;
        }
        // 探测要连本地端口、还可能要 sleep 重试，别占主线程（主线程被拖住会 ANR，
        // 也会让「立即自检」这个 HTTP 请求等到超时）。
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doTick(appCtx);
            }
        }, "magpie-keepalive");
        t.setDaemon(true);
        t.start();
    }

    private void doTick(Context context) {
        try {
            int code = probe(context);
            boolean alive = code >= 200 && code < 500;
            Log.i(TAG, "keepalive probe code=" + code + " alive=" + alive);

            if (!alive) {
                // 超时（-1）再给一次机会：刚被解冻时上游线程可能还在恢复
                if (code == -1) {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                    code = probe(context);
                    alive = code >= 200 && code < 500;
                    Log.i(TAG, "keepalive retry code=" + code + " alive=" + alive);
                }
                if (!alive) {
                    // 两种「不活」要区别对待，否则 reviveCount 会虚高：
                    //   code == 0  服务真的没了（连接被拒）→ 重启才有意义
                    //   code == -1 端口还在但不回包 = 被冻结的假活 → startForegroundService
                    //              是空操作（服务对象还在，只是线程停着），重启救不回来，
                    //              硬记一次 revive 就是在骗人。真正解冻它的是这条闹钟广播
                    //              本身（系统投递前必须 thaw 目标进程），重试 probe 才是正解。
                    boolean reallyDead = (code == 0);
                    if (reallyDead) {
                        restartService(context);
                    }
                    // 重启/解冻后再确认一次，否则界面上会一直挂着「上次自检：服务异常」，
                    // 明明已经救回来了、显示却没跟上。
                    try { Thread.sleep(1600); } catch (InterruptedException ignored) {}
                    int after = probe(context);
                    if (after >= 200 && after < 500) {
                        code = after;
                        alive = true;
                        Log.i(TAG, (reallyDead ? "keepalive revived ok, code=" : "keepalive recovered from freeze, code=") + after);
                    } else if (!reallyDead) {
                        Log.w(TAG, "keepalive: still blocked after thaw, code=" + after
                                + " (冻结未解，非 root 只能等下一轮)");
                    }
                }
            }
            KeepAliveScheduler.recordCheck(context, alive, code);
        } catch (Throwable t) {
            Log.w(TAG, "keepalive onReceive error: " + t.getMessage());
        } finally {
            // 这里不要再调 schedule()：setInexactRepeating 每次调用都会把首次触发重置成
            // 「现在 + 60 秒」，于是 15 分钟的周期退化成 60 秒醒一次。排程由
            // 服务启动 / 开机广播 / 用户手动开关负责，进程重启不会丢 AlarmManager 里的闹钟。
        }
    }

    /**
     * @return HTTP 状态码；0 表示连不上（服务没了）；-1 表示连上但没响应（冻结/卡死）
     */
    private int probe(Context ctx) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 900);
            s.setSoTimeout(1800);

            String key = readAuthKey(ctx);
            String req = "GET /api/status HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + PORT + "\r\n"
                    + (key.isEmpty() ? "" : "Authorization: Bearer " + key + "\r\n")
                    + "Connection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(req.getBytes("UTF-8"));
            os.flush();

            InputStream is = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[256];
            int n;
            // 只读状态行，读完就别再等 body
            while ((n = is.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
                if (buf.size() > 512) break;
            }
            String head = new String(buf.toByteArray(), "UTF-8");
            int sp = head.indexOf(' ');
            if (sp > 0 && head.length() > sp + 4) {
                try {
                    return Integer.parseInt(head.substring(sp + 1, sp + 4).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        } catch (java.net.SocketTimeoutException te) {
            return -1;
        } catch (Throwable t) {
            return 0;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 自检要带口令，否则拿到 401 —— 虽然也算「活着」，但顺手读一下更准 */
    private String readAuthKey(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "config.json");
            if (!f.canRead()) return "";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            JSONObject cfg = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject server = cfg.optJSONObject("server");
            return server == null ? "" : server.optString("authKey", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void restartService(Context context) {
        Log.w(TAG, "keepalive: gateway looks dead, restarting foreground service");
        Intent svc = new Intent(context, GatewayService.class);
        boolean started = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.lang.reflect.Method m =
                        Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(context, svc);
                started = true;
            } catch (Throwable ignored) {
            }
        }
        if (!started) {
            try {
                context.startService(svc);
                started = true;
            } catch (Throwable ignored) {
            }
        }
        if (started) KeepAliveScheduler.recordRevive(context);
    }
}
