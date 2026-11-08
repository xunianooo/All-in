package com.gaeul.allin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 跨 Activity 共享的网关口令读取（config.json 里的 server.authKey）。
 *
 * LocalBridge 需要它来给本机网关请求带上 Authorization，但 LocalBridge 是
 * 静态工具类，拿不到 Activity 的 getFilesDir()。这里由 App 启动时注入一次数据目录。
 */
final class GatewayAuthKeyHolder {

    private static File filesDir;
    private static String cached;

    private GatewayAuthKeyHolder() {
    }

    static synchronized void init(File dir) {
        if (filesDir == null ? dir != null : !filesDir.equals(dir)) cached = null;
        filesDir = dir;
        GatewayManagementCredential.init(dir);
    }

    static synchronized String get() {
        if (cached != null && !cached.isEmpty()) return cached;
        if (filesDir == null) return "";
        FileInputStream in = null;
        try {
            File f = new File(filesDir, "config.json");
            if (!f.exists()) return "";
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            JSONObject c = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            JSONObject server = c.optJSONObject("server");
            String k = server == null ? "" : server.optString("authKey", "").trim();
            cached = k;
            return k;
        } catch (Throwable e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 网关口令被改过之后清掉缓存，下次重新读 */
    static synchronized void invalidate() {
        cached = null;
    }
}
