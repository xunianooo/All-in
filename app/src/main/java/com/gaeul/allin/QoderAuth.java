package com.gaeul.allin;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Qoder（国内版 qoder.cn）登录与调用。
 *
 * 移植自 magpie-community/plugins 的 packages/qoder（MIT），只做国内站 qoder-cn：
 * 国际站 qoder.com 在本机网络被 DNS 劫持到 28.0.0.0/8，TLS 建不起来，无法使用。
 *
 * 登录走官方桌面客户端那套"设备流"（和 ZCode 同款思路）：
 *   1. 本地生成 PKCE verifier / challenge、nonce、machineId
 *   2. 打开 https://qoder.cn/device/selectAccounts?... 让用户在 WebView 里登录授权
 *   3. 每 2 秒轮询 openapi 的 /api/v1/deviceToken/poll，拿到 deviceToken
 *   4. 用 deviceToken 换 jobToken（POST /api/v1/me/jobToken）
 *      国内版 CLI 从不换 jobToken；换不到就退回用 deviceToken 直接签名聊天
 *   5. 读 /api/v1/userinfo 拿 uid / 邮箱 / 昵称
 *
 * 聊天必须过 COSY 签名，不是普通 Bearer：
 *   - AES-128-CBC（key 同时充当 IV）加密用户信息 → base64 得 info
 *   - 用 Qoder 内置的 RSA-1024 公钥以 PKCS1 包裹 AES key → base64 得 key
 *   - base64(JSON{v1, requestId, info, cosyVersion, ideVersion}) 为 payload
 *   - md5(payload + "\n" + key + "\n" + ts + "\n" + body + "\n" + path) 为签名
 *   - 请求体本身还要过自定义 base64（打乱字母表 + 首尾各 1/3 交换）
 */
public class QoderAuth {

    /** 国内站 */
    public static final String WEB = "https://qoder.cn";
    public static final String OPENAPI = "https://openapi.qoder.com.cn";
    public static final String API = "https://gateway.qoder.com.cn";
    /** 国内站 CLI（@qodercn-ai/qoderclicn）的生产环境 client id */
    public static final String CLIENT_ID = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb";
    /** 聊天补全的 SSE 入口（查询参数是 Qoder 客户端固定带的） */
    public static final String CHAT_URL = API
            + "/algo/api/v2/service/pro/sse/agent_chat_generation"
            + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
    /** 模型清单入口：GET，body 为空串 */
    public static final String MODELS_URL = API + "/algo/api/v2/model/list?Encode=1";
    /** Qoder 桌面端内置的 1024 位 RSA 公钥，用于包裹每次请求的 AES key */
    public static final String RSA_KEY =
            "-----BEGIN PUBLIC KEY-----\n"
          + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc\n"
          + "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l\n"
          + "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17\n"
          + "XcW+ML9FoCI6AOvOzwIDAQAB\n"
          + "-----END PUBLIC KEY-----";

    // 必须与 Qoder 桌面端（0.4.8 / build 79743374）实际使用的版本一致：
    // 信封同时声明 Cosy-ClientType: 10（桌面端），版本对不上会被拒。
    // 插件取值见 packages/qoder/index.mjs 的 COSY_VERSION。
    public static final String COSY_VERSION = "1.1.69";
    public static final String QODER_SYS =
            "You are a Qoder agent. Use the instructions below and the tools available to you to assist the user.";

    /** 一次进行中的登录 */
    public static class Flow {
        public String flowId;      // 内部标识（nonce）
        public String verifier;
        public String nonce;
        public String machineId;
        public String authorizeUrl;
        public long intervalMs = 2000;
        public long deadline;
    }

    /** 登录成功后的凭据 */
    public static class SignIn {
        // 全部给空串初值：optString(k, 字段) 在字段为 null 时会把 null 当默认值返回，
        // 之后任何 isEmpty() 都会 NPE（曾导致登录轮询一直停在 pending）
        public String access = "";
        public String refresh = "";
        public String deviceToken = "";
        public String deviceRefresh = "";
        public boolean deviceChat;   // true = 直接用 deviceToken 聊天
        public String uid = "";
        public String email = "";
        public String name = "";
        public String machineId = "";
        public long expiresAt;
    }

    public static class PollResult {
        public String status;        // pending / ready / failed
        public String error;
        public SignIn signIn;
        public String account;
    }

    // ================= 编码工具 =================

    private static final char[] COSY_ALPHABET =
            "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!".toCharArray();

    /** 对应插件的 segmentEncode：自定义 base64，'$' 当填充 */
    private static String segmentEncode(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 4 / 3 + 4);
        int acc = 0, nb = 0;
        for (byte bb : bytes) {
            acc = ((acc << 8) | (bb & 0xff)) & 0xffffff;
            nb += 8;
            while (nb >= 6) {
                nb -= 6;
                s.append(COSY_ALPHABET[(acc >> nb) & 63]);
            }
        }
        if (nb > 0) s.append(COSY_ALPHABET[(acc << (6 - nb)) & 63]);
        while (s.length() % 4 != 0) s.append('$');
        return s.toString();
    }

    /** 首尾各 1/3 对调 */
    private static String swapThirds(String s) {
        int t = s.length() / 3;
        if (t == 0) return s;
        return s.substring(s.length() - t) + s.substring(t, s.length() - t) + s.substring(0, t);
    }

    /** 请求体编码：JSON 文本 → 线上字节串 */
    public static String encodeBody(String text) {
        try {
            return swapThirds(segmentEncode(text.getBytes("UTF-8")));
        } catch (Exception e) {
            return text;
        }
    }

    /** 响应体解码：线上字节串 → JSON 文本 */
    public static String decodeBody(String wire) {
        if (wire == null) return "";
        String s = swapThirds(wire);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int g = 0; g + 4 <= s.length(); g += 4) {
            int[] vals = new int[4];
            int n = 0;
            for (int k = 0; k < 4; k++) {
                char c = s.charAt(g + k);
                if (c == '$') continue;
                int idx = indexOfAlphabet(c);
                if (idx < 0) continue;
                vals[n++] = idx;
            }
            if (n == 0) continue;
            long x = 0;
            for (int k = 0; k < n; k++) x = x * 64 + vals[k];
            int bytes = (6 * n) / 8;
            for (int k = 0; k < bytes; k++) {
                int shift = 6 * n - 8 * (k + 1);
                out.write((int) ((x >> shift) & 0xff));
            }
        }
        try {
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static int indexOfAlphabet(char c) {
        for (int i = 0; i < COSY_ALPHABET.length; i++) {
            if (COSY_ALPHABET[i] == c) return i;
        }
        return -1;
    }

    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String b64urlNoPad(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String sha256B64Url(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return b64urlNoPad(md.digest(s.getBytes("UTF-8")));
    }

    private static String hexId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String machineOS() {
        String arch = System.getProperty("os.arch", "aarch64");
        if ("aarch64".equals(arch) || "arm64".equals(arch)) arch = "aarch64";
        else if ("amd64".equals(arch) || "x86_64".equals(arch)) arch = "x86_64";
        return arch + "_linux";
    }

    // ================= COSY 签名 =================

    /** 把用户信息加密成 COSY 信封的 info/key 两段 */
    private static String[] userBlob(SignIn u) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", u.uid == null ? "" : u.uid);
        o.put("aid", "");
        o.put("name", u.name == null ? "" : u.name);
        o.put("email", u.email == null ? "" : u.email);
        o.put("security_oauth_token", u.access == null ? "" : u.access);
        String raw = o.toString();

        // AES-128-CBC，key 同时作为 IV，key 取 16 个 ASCII 字符
        String keyStr = hexId().substring(0, 16);
        byte[] keyBytes = keyStr.getBytes("UTF-8");
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(keyBytes));
        String info = b64(c.doFinal(raw.getBytes("UTF-8")));

        // 用 Qoder 的 RSA 公钥以 PKCS1 包裹这把 AES key
        String pem = RSA_KEY.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        java.security.spec.X509EncodedKeySpec spec =
                new java.security.spec.X509EncodedKeySpec(der);
        java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(spec);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, pub);
        String wrapped = b64(rsa.doFinal(keyBytes));

        return new String[]{info, wrapped};
    }

    /** 为一次调用生成全套 COSY 头；bodyWire 必须是已经过 encodeBody 的线上串 */
    public static java.util.Map<String, String> cosyHeaders(String url, SignIn u, String bodyWire)
            throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String[] blob = userBlob(u);

        JSONObject wrapper = new JSONObject();
        wrapper.put("version", "v1");
        wrapper.put("requestId", UUID.randomUUID().toString());
        wrapper.put("info", blob[0]);
        wrapper.put("cosyVersion", COSY_VERSION);
        wrapper.put("ideVersion", "");
        String payload = b64(wrapper.toString().getBytes("UTF-8"));

        String path = new URL(url).getPath();
        if (path.startsWith("/algo")) path = path.substring(5);

        String sig = md5Hex(payload + "\n" + blob[1] + "\n" + ts + "\n" + bodyWire + "\n" + path);

        String m = u.machineId == null ? hexId() : u.machineId;
        java.util.LinkedHashMap<String, String> h = new java.util.LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        h.put("Accept-Encoding", "identity");
        h.put("Content-Type", "application/json");
        // 关键：Authorization 不是裸签名，而是 "Bearer COSY.<payload>.<sig>"
        h.put("Authorization", "Bearer COSY." + payload + "." + sig);
        h.put("Cosy-Business-Product", "app");
        h.put("Cosy-Business-Type", "agent");
        h.put("Cosy-ClientIp", m);
        h.put("Cosy-ClientType", "10");
        h.put("Cosy-Data-Policy", "disagree");
        h.put("Cosy-Date", String.valueOf(ts));
        h.put("Cosy-Key", blob[1]);
        h.put("Cosy-MachineId", m);
        h.put("Cosy-MachineToken", m);
        h.put("Cosy-MachineType", "5");
        h.put("Cosy-MachineOS", machineOS());
        h.put("Cosy-Scene", "app");
        h.put("Cosy-User", u.uid == null ? "" : u.uid);
        h.put("Cosy-Version", COSY_VERSION);
        h.put("Login-Version", "v2");
        return h;
    }

    /**
     * 聊天的头，对齐插件的 chatHeaders：
     * 聊天与其它接口的头集合不同——要删掉 Cosy-ClientIp / Cosy-MachineToken / Cosy-MachineType，
     * 补上 Cosy-MachineHostname，再加 Accept / Cache-Control / X-Model-Key / X-Model-Source。
     * （MachineToken/MachineType 本该是桌面端 runtime-info 的真实设备身份，
     * 手机端拿不到就省略，而不是拿 machineId 顶替，否则上游直接 500。）
     */
    public static java.util.Map<String, String> chatHeaders(String url, SignIn u, String bodyWire,
                                                            String modelKey, String modelSource)
            throws Exception {
        java.util.Map<String, String> h = cosyHeaders(url, u, bodyWire);
        h.remove("Cosy-ClientIp");
        h.remove("Cosy-MachineToken");
        h.remove("Cosy-MachineType");
        String host = machineHostname();
        if (host != null && !host.isEmpty()) h.put("Cosy-MachineHostname", host);
        h.put("Accept", "text/event-stream");
        h.put("Cache-Control", "no-cache");
        if (modelKey != null && !modelKey.isEmpty()) h.put("X-Model-Key", modelKey);
        if (modelSource != null && !modelSource.isEmpty()) h.put("X-Model-Source", modelSource);
        return h;
    }

    /** 机器名，对齐插件的 machineHostname：可打印 ASCII 直接用，否则兜底 */
    private static String machineHostname() {
        try {
            String n = java.net.InetAddress.getLocalHost().getHostName();
            if (n == null) return "";
            n = n.trim();
            if (n.isEmpty()) return "";
            boolean safe = true;
            for (int i = 0; i < n.length(); i++) {
                char c = n.charAt(i);
                if (c < 0x21 || c > 0x7e) { safe = false; break; }
            }
            if (safe) return n.length() <= 96 ? n : n.substring(0, 88);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.length(); i++) {
                char c = n.charAt(i);
                sb.append((c >= 0x21 && c <= 0x7e) ? c : '-');
            }
            String v = sb.toString().replaceAll("-{2,}", "-");
            v = v.replaceAll("^-+|-+$", "");
            return v.isEmpty() ? "unknown" : (v.length() <= 96 ? v : v.substring(0, 88));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 模型清单与账户类接口用的 COSY 签名（body 为空） */
    public static java.util.Map<String, String> cosyHeadersNoBody(String url, SignIn u) throws Exception {
        return cosyHeaders(url, u, "");
    }

    // ================= 设备流登录 =================

    /** 开一个登录流程：返回要展示给用户授权的页面地址 */
    public static Flow start() throws Exception {
        Flow f = new Flow();
        byte[] vb = new byte[64];
        new SecureRandom().nextBytes(vb);
        f.verifier = b64urlNoPad(vb);
        f.nonce = UUID.randomUUID().toString();
        f.machineId = UUID.randomUUID().toString();
        f.flowId = f.nonce;
        f.deadline = System.currentTimeMillis() + 15 * 60 * 1000L;

        String challenge = sha256B64Url(f.verifier);
        f.authorizeUrl = WEB + "/device/selectAccounts"
                + "?challenge=" + URLEncoder.encode(challenge, "UTF-8")
                + "&challenge_method=S256"
                + "&nonce=" + URLEncoder.encode(f.nonce, "UTF-8")
                + "&machine_id=" + URLEncoder.encode(f.machineId, "UTF-8")
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8");
        return f;
    }

    private static String httpGet(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Magic/App");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(is);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + trim(body, 200));
        }
        return body;
    }

    private static String httpPostJson(String url, String token, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Magic/App");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(is);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + trim(body, 200));
        }
        return body;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 轮询一次。ready 时完成换 token 与读取用户信息 */
    public static PollResult poll(Flow f) throws Exception {
        PollResult pr = new PollResult();
        String url = OPENAPI + "/api/v1/deviceToken/poll"
                + "?nonce=" + URLEncoder.encode(f.nonce, "UTF-8")
                + "&verifier=" + URLEncoder.encode(f.verifier, "UTF-8")
                + "&challenge_method=S256";

        String body;
        try {
            body = httpGet(url, null);
            android.util.Log.d("MagpieQoder", "poll 200 body=" + trim(body, 300));
        } catch (Exception e) {
            // 未授权时 openapi 回 404 NotFound，这属于"还在等"，不是错误
            String msg = String.valueOf(e.getMessage());
            android.util.Log.d("MagpieQoder", "poll 失败: " + msg);
            pr.status = "pending";
            pr.error = msg;
            return pr;
        }

        JSONObject dt;
        try {
            dt = new JSONObject(body);
        } catch (Exception e) {
            pr.status = "pending";
            return pr;
        }
        String deviceToken = dt.optString("token", "");
        if (deviceToken.isEmpty()) deviceToken = dt.optString("device_token", "");
        if (deviceToken.isEmpty() && dt.optJSONObject("data") != null) {
            JSONObject d1 = dt.optJSONObject("data");
            deviceToken = d1.optString("token", "");
            if (deviceToken.isEmpty()) deviceToken = d1.optString("device_token", "");
        }
        if (deviceToken.isEmpty()) {
            android.util.Log.d("MagpieQoder", "poll 无 token，body=" + trim(body, 300));
            pr.status = "pending";
            return pr;
        }
        android.util.Log.d("MagpieQoder", "poll 拿到 deviceToken, len=" + deviceToken.length());

        SignIn si = new SignIn();
        si.deviceToken = deviceToken;
        si.deviceRefresh = dt.optString("refresh_token", "");
        si.machineId = f.machineId;
        si.uid = String.valueOf(dt.opt("user_id") == null ? "" : dt.opt("user_id"));
        si.name = dt.optString("user_name", "");

        // 换 jobToken；国内站 CLI 自身从不换，换不到就退回 deviceToken 直接聊天
        boolean gotJob = false;
        try {
            JSONObject req = new JSONObject();
            req.put("clientId", CLIENT_ID);
            String jr = httpPostJson(OPENAPI + "/api/v1/me/jobToken", deviceToken, req.toString());
            JSONObject jo = new JSONObject(jr);
            String jt = jo.optString("token", "");
            if (jt.isEmpty()) jt = jo.optString("device_token", "");
            if (jt.isEmpty()) jt = jo.optString("access_token", "");
            if (!jt.isEmpty()) {
                si.access = jt;
                si.refresh = jo.optString("refresh_token", "");
                si.expiresAt = expiresAt(jo);
                gotJob = true;
            }
        } catch (Exception ignored) {
        }
        if (!gotJob) {
            // 国内站的常见路径：deviceToken 就是聊天 token
            si.access = deviceToken;
            si.refresh = si.deviceRefresh;
            si.deviceChat = true;
            si.expiresAt = expiresAt(dt);
        }

        // 读用户信息补 uid / 邮箱 / 昵称
        try {
            String ui = httpGet(OPENAPI + "/api/v1/userinfo", deviceToken);
            JSONObject uo = new JSONObject(ui);
            String id = String.valueOf(uo.opt("id") == null ? "" : uo.opt("id"));
            if (id.isEmpty()) id = String.valueOf(uo.opt("user_id") == null ? "" : uo.opt("user_id"));
            if (id.isEmpty()) id = String.valueOf(uo.opt("uid") == null ? "" : uo.opt("uid"));
            if (!id.isEmpty()) si.uid = id;
            String em = uo.optString("email", "");
            if (!em.isEmpty()) si.email = em;
            String nm = uo.optString("name", "");
            if (!nm.isEmpty()) si.name = nm;
        } catch (Exception ignored) {
        }

        if (si.uid == null) si.uid = "";
        if (si.email == null) si.email = "";
        if (si.uid == null) si.uid = "";
        if (si.name == null) si.name = "";
        pr.status = "ready";
        pr.signIn = si;
        pr.account = !si.email.isEmpty() ? si.email : si.uid;
        return pr;
    }

    /** token 到期时间；Qoder 可能给 expire_time / expires_at / expires_in，都没有就按一天算 */
    private static long expiresAt(JSONObject o) {
        long fallback = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
        try {
            Object et = o.opt("expire_time");
            if (et == null) et = o.opt("expires_at");
            if (et instanceof Number) {
                long v = ((Number) et).longValue();
                return v > 1000000000000L ? v : v * 1000L;
            }
            if (et instanceof String) {
                String s = ((String) et).trim();
                if (!s.isEmpty()) {
                    long v = Long.parseLong(s);
                    return v > 1000000000000L ? v : v * 1000L;
                }
            }
            Object ei = o.opt("expires_in");
            if (ei instanceof Number) {
                long ms = ((Number) ei).longValue();
                if (ms < 100000000000L) ms *= 1000L;
                return System.currentTimeMillis() + ms;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    // ================= 聊天（OpenAI 请求 → Qoder 信封 → 事件流）=================

    /** session_id：和插件一样由会话种子派生成 UUID 形状，让同一对话命中上游缓存 */
    public static String sessionIdFor(String seed) {
        try {
            if (seed == null || seed.isEmpty()) seed = "chat";
            String h = sha256Hex(seed);
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-"
                    + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** 文本内容抽取：兼容 string 与 [{type:text,text:...}] 两种形态 */
    public static String textOf(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof org.json.JSONArray) {
            StringBuilder sb = new StringBuilder();
            org.json.JSONArray arr = (org.json.JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof JSONObject) {
                    String t = ((JSONObject) part).optString("text", "");
                    if (!t.isEmpty()) sb.append(t);
                } else if (part != null) {
                    sb.append(part.toString());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * 把 OpenAI chat 请求转成 Qoder 的请求信封。
     * 只移植文本主线路径（system 合并、user/assistant/tool 文本块、tools），
     * 图片与 tool-call 配对的完整实现见插件的 qoderMessages。
     */
    public static JSONObject buildChatBody(SignIn si, org.json.JSONArray messages,
                                           org.json.JSONArray tools, Integer maxTokens,
                                           String modelKey, JSONObject modelConfig) throws Exception {
        StringBuilder sysText = new StringBuilder(QODER_SYS);
        StringBuilder userSys = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("system".equals(role) || "developer".equals(role)) {
                String t = textOf(m.opt("content"));
                if (!t.isEmpty()) {
                    if (userSys.length() > 0) userSys.append("\n\n");
                    userSys.append(t);
                }
            }
        }
        if (userSys.length() > 0) sysText.append("\n\n").append(userSys);

        JSONObject sysBlock = new JSONObject();
        sysBlock.put("type", "text");
        sysBlock.put("text", sysText.toString());

        String request = UUID.randomUUID().toString();
        String sessionId = sessionIdFor("qoder-cn");
        String body = "{\"body\":1}"; // placeholder to keep quotes balanced
        body = null;

        JSONObject params = new JSONObject();
        params.put("enable_thinking", true);
        if (maxTokens != null && maxTokens < 64) {
            throw new IllegalArgumentException("Qoder requires max_tokens >= 64; configured limit cannot be honored");
        }
        params.put("max_tokens", maxTokens == null ? 32000 : maxTokens);
        // 上下文窗口：取模型条目声明的 max_input_tokens
        if (modelConfig != null) {
            int win = modelConfig.optInt("max_input_tokens", 0);
            if (win > 0) params.put("context_length", win);
        }

        JSONObject business = new JSONObject();
        business.put("product", "app");
        business.put("version", COSY_VERSION);
        business.put("type", "agent");
        business.put("id", sessionId);
        business.put("name", "");
        business.put("begin_at", System.currentTimeMillis());
        business.put("stage", "start");

        JSONObject o = new JSONObject();
        o.put("request_id", request);
        o.put("request_set_id", sessionId);
        o.put("chat_record_id", request);
        o.put("session_id", sessionId);
        o.put("parameters", params);
        o.put("business", business);
        o.put("agent_id", "agent_common");
        o.put("task_id", "common");
        o.put("session_type", "app");
        if (modelConfig != null && modelConfig.length() > 0) o.put("model_config", modelConfig);
        o.put("system", new org.json.JSONArray().put(sysBlock));

        // messages：第一条 system 承载 Qoder 系统行 + 用户系统词，其余按文本块平铺
        org.json.JSONArray msgs = new org.json.JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", new org.json.JSONArray().put(sysBlock));
        msgs.put(sysMsg);
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user");
            if ("system".equals(role) || "developer".equals(role)) continue;
            String t = textOf(m.opt("content"));
            if (t.isEmpty()) continue;
            JSONObject mm = new JSONObject();
            mm.put("role", role);
            org.json.JSONArray cb = new org.json.JSONArray();
            JSONObject tb = new JSONObject();
            tb.put("type", "text");
            tb.put("text", t);
            cb.put(tb);
            mm.put("content", cb);
            // 插件 cacheMarked：user 消息同时带一份 contents，缺了上游可能 500
            if ("user".equals(role)) mm.put("contents", cb);
            msgs.put(mm);
        }
        o.put("messages", msgs);

        if (tools != null && tools.length() > 0) {
            org.json.JSONArray qt = new org.json.JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name", "");
                if (name.isEmpty()) continue;
                JSONObject one = new JSONObject();
                one.put("type", "function");
                JSONObject f2 = new JSONObject();
                f2.put("name", name);
                f2.put("description", fn.optString("description", ""));
                Object prm = fn.opt("parameters");
                f2.put("parameters", prm == null ? new JSONObject("{\"type\":\"object\",\"properties\":{}}") : prm);
                one.put("function", f2);
                qt.put(one);
            }
            if (qt.length() > 0) o.put("tools", qt);
        }
        return o;
    }

    /**
     * 发送聊天请求并解析 SSE 事件流。
     * 返回 OpenAI 风格的 piece 数组：{text}/{reasoning}/{tool}/{error}。
     */
    public static org.json.JSONArray chatEvents(SignIn si, JSONObject qoderBody) throws Exception {
        org.json.JSONArray pieces = new org.json.JSONArray();
        String wire = encodeBody(qoderBody.toString());
        String mkey = qoderBody.optJSONObject("model_config") == null ? ""
                : qoderBody.optJSONObject("model_config").optString("key", "");
        String msrc = qoderBody.optJSONObject("model_config") == null ? ""
                : qoderBody.optJSONObject("model_config").optString("source", "");
        java.util.Map<String, String> h = chatHeaders(CHAT_URL, si, wire, mkey, msrc);

        HttpURLConnection conn = (HttpURLConnection) new URL(CHAT_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(300000);
        conn.setRequestProperty("Content-Type", "application/json");
        for (java.util.Map.Entry<String, String> e : h.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        OutputStream os = conn.getOutputStream();
        os.write(wire.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 300) {
            String body = readAll(is);
            conn.disconnect();
            JSONObject err = new JSONObject();
            err.put("error", "HTTP " + code + ": " + trim(body, 300));
            pieces.put(err);
            return pieces;
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            JSONObject env;
            try {
                env = new JSONObject(payload);
            } catch (Exception e) {
                continue;
            }
            String inner = env.optString("body", "");
            Object scv = env.opt("statusCodeValue");
            if (scv != null && scv != JSONObject.NULL) {
                int sc = 0;
                try { sc = Integer.parseInt(String.valueOf(scv)); } catch (Exception ignored) {}
                if (sc != 200) {
                    JSONObject err = new JSONObject();
                    err.put("error", trim(inner.isEmpty() ? payload : inner, 400));
                    pieces.put(err);
                    return pieces;
                }
            }
            if (inner.trim().equals("[DONE]")) break;
            JSONObject chunk;
            try {
                chunk = new JSONObject(inner.trim());
            } catch (Exception e) {
                continue;
            }
            JSONObject delta = chunk.optJSONArray("choices") == null ? null
                    : chunk.optJSONArray("choices").optJSONObject(0) == null ? null
                    : chunk.optJSONArray("choices").optJSONObject(0).optJSONObject("delta");
            if (delta != null) {
                String r = delta.optString("reasoning_content", "");
                if (!r.isEmpty()) {
                    JSONObject p = new JSONObject();
                    p.put("reasoning", r);
                    pieces.put(p);
                }
                String c = delta.optString("content", "");
                if (!c.isEmpty()) {
                    JSONObject p = new JSONObject();
                    p.put("text", c);
                    pieces.put(p);
                }
                org.json.JSONArray tcs = delta.optJSONArray("tool_calls");
                if (tcs != null) {
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject tc = tcs.optJSONObject(i);
                        if (tc == null) continue;
                        String name = tc.optJSONObject("function") == null ? ""
                                : tc.optJSONObject("function").optString("name", "").trim();
                        String args = tc.optJSONObject("function") == null ? ""
                                : tc.optJSONObject("function").optString("arguments", "");
                        if (name.isEmpty() && args.isEmpty()) continue;
                        JSONObject p = new JSONObject();
                        JSONObject tool = new JSONObject();
                        tool.put("index", 0);
                        tool.put("id", tc.optString("id", "call_" + hexId()));
                        tool.put("name", name);
                        tool.put("args", args);
                        p.put("tool", tool);
                        pieces.put(p);
                    }
                }
            }
            Object fr = chunk.optJSONArray("choices") == null ? null
                    : chunk.optJSONArray("choices").optJSONObject(0) == null ? null
                    : chunk.optJSONArray("choices").optJSONObject(0).opt("finish_reason");
            if (fr != null && !JSONObject.NULL.equals(fr) && !String.valueOf(fr).isEmpty()) break;
        }
        br.close();
        conn.disconnect();
        return pieces;
    }

    /**
     * 拉账号自己的模型清单。
     * 对齐插件的 fetchListing + sceneModels + modelInfos：
     *   1. GET /algo/api/v2/model/list?Encode=1，COSY 头，body 为空串
     *   2. 响应过 decodeBody 得到 JSON
     *   3. 取 "app" 场景（Qoder 自己的 App 就是 app，请求里的 session_type 也是 app），
     *      再补上 chat 里 app 已没有的条目
     *   4. 只保留 enable=true 且 key 不是 auto/default 的
     * metaOut 非空时顺带填 {modelKey: config}，聊天请求的 model_config 取自这里。
     */
    public static org.json.JSONArray fetchModels(SignIn si, JSONObject metaOut) {
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            java.util.Map<String, String> h = cosyHeaders(MODELS_URL, si, "");

            HttpURLConnection conn = (HttpURLConnection) new URL(MODELS_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            for (java.util.Map.Entry<String, String> e : h.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            conn.disconnect();
            if (code < 200 || code >= 300) {
                android.util.Log.d("MagpieQoder", "models HTTP " + code + ": " + trim(body, 300));
                return out;
            }

            String json = body.trim();
            // 正常是 Encode=1 的编码体，个别情况是明文 JSON
            if (!json.startsWith("{")) json = decodeBody(body);
            JSONObject root = new JSONObject(json);

            org.json.JSONArray appArr = root.optJSONArray("app");
            org.json.JSONArray chatArr = root.optJSONArray("chat");
            java.util.LinkedHashMap<String, JSONObject> ordered =
                    new java.util.LinkedHashMap<String, JSONObject>();

            if (appArr != null) {
                for (int i = 0; i < appArr.length(); i++) {
                    JSONObject m = appArr.optJSONObject(i);
                    if (m == null) continue;
                    String k = m.optString("key", "");
                    if (k.isEmpty()) continue;
                    ordered.put(k, m);
                }
            }
            if (chatArr != null) {
                for (int i = 0; i < chatArr.length(); i++) {
                    JSONObject m = chatArr.optJSONObject(i);
                    if (m == null) continue;
                    String k = m.optString("key", "");
                    if (k.isEmpty() || ordered.containsKey(k)) continue;
                    ordered.put(k, m);
                }
            }

            for (java.util.Map.Entry<String, JSONObject> e : ordered.entrySet()) {
                String k = e.getKey();
                JSONObject m = e.getValue();
                if ("auto".equals(k) || "default".equals(k)) continue;
                if (!m.optBoolean("enable", false)) continue;
                out.put(k);
                if (metaOut != null) {
                    // 插件里 modelInfo 的 config 就是整条原始条目（config: raw），
                    // 不是某个叫 config 的子字段；聊天的 model_config 直接用它。
                    metaOut.put(k, m);
                }
            }
            android.util.Log.d("MagpieQoder", "models 拉到 " + out.length() + " 个: " + trim(out.toString(), 300));
        } catch (Throwable t) {
            android.util.Log.d("MagpieQoder", "models 失败: " + t);
        }
        return out;
    }
}
