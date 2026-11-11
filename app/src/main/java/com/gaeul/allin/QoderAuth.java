package com.gaeul.allin;


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

    private static final String BASE64_STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static String base64Encode(byte[] data, String alphabet, boolean padding) {
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int remain = data.length - i;
            int n = (data[i] & 255) << 16;
            if (remain > 1) n |= (data[i + 1] & 255) << 8;
            if (remain > 2) n |= data[i + 2] & 255;
            out.append(alphabet.charAt((n >>> 18) & 63)).append(alphabet.charAt((n >>> 12) & 63));
            if (remain > 1) out.append(alphabet.charAt((n >>> 6) & 63)); else if (padding) out.append('=');
            if (remain > 2) out.append(alphabet.charAt(n & 63)); else if (padding) out.append('=');
        }
        return out.toString();
    }
    private static String b64(byte[] data) { return base64Encode(data, BASE64_STANDARD, true); }
    private static String b64urlNoPad(byte[] data) { return base64Encode(data, BASE64_URL, false); }

    private static byte[] decodePemBase64(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
            clean.append(c);
        }
        String s = clean.toString();
        if (s.isEmpty() || s.length() % 4 != 0) throw new IllegalArgumentException("Invalid PEM base64");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i += 4) {
            int a = BASE64_STANDARD.indexOf(s.charAt(i)), b = BASE64_STANDARD.indexOf(s.charAt(i + 1));
            char cc = s.charAt(i + 2), dc = s.charAt(i + 3);
            int c = cc == '=' ? -1 : BASE64_STANDARD.indexOf(cc);
            int d = dc == '=' ? -1 : BASE64_STANDARD.indexOf(dc);
            boolean last = i + 4 == s.length();
            if (a < 0 || b < 0 || (cc != '=' && c < 0) || (dc != '=' && d < 0)
                    || (c < 0 && d >= 0) || (!last && (c < 0 || d < 0))
                    || (c < 0 && (b & 15) != 0) || (d < 0 && c >= 0 && (c & 3) != 0))
                throw new IllegalArgumentException("Invalid PEM base64");
            out.write((a << 2) | (b >>> 4));
            if (c >= 0) out.write((b << 4) | (c >>> 2));
            if (d >= 0) out.write((c << 6) | d);
        }
        return out.toByteArray();
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
                .replace("-----END PUBLIC KEY-----", "");
        byte[] der = decodePemBase64(pem);
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
        if (u == null || u.machineId == null || u.machineId.trim().isEmpty())
            throw new IllegalArgumentException("Qoder account has no machine ID; sign in again");
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
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
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
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
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
        // 本地上游 0.2.64 没有预算下限：index.mjs:1502-1506 只按模型上限向下收
        // （`asked > cap ? cap : asked`），也不把客户端给的数往上抬。所以这里不拒短预算，
        // 原样下发（只到模型上限）；服务端真要拒绝，会让它自己给出明确的 range。
        params.put("max_tokens", QoderFailurePolicy.modelLimit(maxTokens == null ? 32000 : maxTokens, modelKey, modelConfig));
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
        return chatEvents(si, qoderBody, url -> NetworkPolicy.openHttp(url));
    }

    /**
     * 出站传输工厂。默认实现就是 {@code url -> NetworkPolicy.openHttp(url)}。
     *
     * <p><b>它只负责「拿到一个连接对象」，不负责「已经连上」。</b>默认的
     * {@link NetworkPolicy#openHttp(String)} 只做 URL 解析与代理选择，真实的 DNS /
     * TCP / TLS 全部推迟到 {@code getOutputStream()}（写请求头）时才发生
     * ——见 {@link #refreshCandidate(SignIn, ConnectionFactory)} 的阶段边界说明。
     *
     * <p>实现方必须遵守的契约：
     * <ol>
     *   <li>{@link #open(String)} 抛异常 = 本机连连接对象都没拿到（例如 URL 不可用、
     *       代理配置被拒、策略阻断）。这是唯一能被当成「没发出去」的失败。</li>
     *   <li>真实网络故障（断网、DNS 失败、连接被拒、TLS 失败）在默认传输上不会在
     *       {@code open} 里暴露，而是落在 {@code getOutputStream()} /
     *       {@code getResponseCode()}。按本类的阶段模型，它们一律是 CONNECTED 及
     *       之后阶段的「未知」，<b>不是</b>「没发出去」。</li>
     *   <li>每次调用必须返回新连接：不得缓存、复用、池化，也不得在工厂里做重试、
     *       自动跟随跳转或改方法/头。</li>
     *   <li>HTTP 错误（4xx/5xx）必须当作正常返回：把连接交回来，由阶段模型读状态码。
     *       在工厂里把 4xx/5xx 转成异常会让「上游已经答过」被误判成「未知」。</li>
     *   <li>返回 null 视为「没有传输」，按「没发出去」处理；返回的连接由调用方
     *       disconnect，工厂不得在返回前抢先关闭它。</li>
     * </ol>
     *
     * <p>本接口同时被聊天与目录读取复用，那里对它的要求相同。
     */
    public interface ConnectionFactory { HttpURLConnection open(String url) throws Exception; }
    public static final class ChatTransportException extends Exception {
        public final boolean sentOrUnknown;
        ChatTransportException(boolean sentOrUnknown, Exception cause) {
            super("Qoder transport failed", cause); this.sentOrUnknown = sentOrUnknown;
        }
    }
    public static org.json.JSONArray chatEvents(SignIn si, JSONObject qoderBody, ConnectionFactory factory) throws Exception {
        return chatEvents(si, qoderBody, factory, null);
    }
    public static org.json.JSONArray chatEvents(SignIn si, JSONObject qoderBody, ConnectionFactory factory,
                                                 RequestContext context) throws Exception {
        org.json.JSONArray pieces = new org.json.JSONArray();
        String wire = encodeBody(qoderBody.toString());
        String mkey = qoderBody.optJSONObject("model_config") == null ? ""
                : qoderBody.optJSONObject("model_config").optString("key", "");
        String msrc = qoderBody.optJSONObject("model_config") == null ? ""
                : qoderBody.optJSONObject("model_config").optString("source", "");
        java.util.Map<String, String> h = chatHeaders(CHAT_URL, si, wire, mkey, msrc);

        HttpURLConnection conn = null;
        boolean sentOrUnknown = false;
        try {
        if (context != null) context.check();
        conn = factory.open(CHAT_URL);
        if (context != null) { context.attach(conn); context.check(); }
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        int budget = context == null ? 300000 : (int)Math.max(1, Math.min(300000L, context.remainingMillis()));
        conn.setConnectTimeout(Math.min(15000, budget));
        conn.setReadTimeout(budget);
        byte[] wireBytes = wire.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(wireBytes.length);
        conn.setRequestProperty("Content-Type", "application/json");
        for (java.util.Map.Entry<String, String> e : h.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        if (context != null) context.check();
        sentOrUnknown = true;
        try (OutputStream os = conn.getOutputStream()) {
            if (context != null) context.check();
            os.write(wireBytes);
            os.flush();
            if (context != null) context.check();
        }

        if (context != null) {
            context.check();
            conn.setReadTimeout((int)Math.max(1, Math.min(300000L, context.remainingMillis())));
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is != null && context != null) is = contextInput(is, conn, context);
        if (code < 200 || code >= 300) {
            String body = readBoundedError(is);
            pieces.put(QoderFailurePolicy.failure(code, body));
            return pieces;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
        String line;
        boolean terminal = false;
        int eventChars = 0;
        while ((line = readSseLine(br, context)) != null) {
            if (line.isEmpty()) { eventChars = 0; continue; }
            eventChars += line.length() + 1;
            if (eventChars > MAX_SSE_EVENT_CHARS) throw new java.io.IOException("Qoder SSE event too large");
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) { terminal = true; break; }
            JSONObject env;
            try {
                env = new JSONObject(payload);
            } catch (Exception e) {
                throw new java.io.IOException("Invalid Qoder SSE envelope", e);
            }
            Object bodyValue = env.opt("body");
            String inner = bodyValue instanceof String ? (String)bodyValue : "";
            Object scv = env.opt("statusCodeValue");
            if (scv != null && scv != JSONObject.NULL) {
                int sc;
                try { sc = Integer.parseInt(String.valueOf(scv)); }
                catch (Exception invalid) { throw new java.io.IOException("Invalid Qoder SSE status", invalid); }
                if (sc != 200) {
                    pieces.put(QoderFailurePolicy.failure(sc, inner.isEmpty() ? payload : inner));
                    return pieces;
                }
            }
            if (inner.trim().equals("[DONE]")) { terminal = true; break; }
            JSONObject chunk;
            try {
                chunk = new JSONObject(inner.trim());
            } catch (Exception e) {
                throw new java.io.IOException("Invalid Qoder SSE body", e);
            }
            if (chunk.optJSONArray("choices") == null && (chunk.has("error") || (chunk.has("code") && chunk.has("message")))) {
                pieces.put(QoderFailurePolicy.failure(502, inner));
                return pieces;
            }
            if (chunk.optJSONArray("choices") == null) throw new java.io.IOException("Invalid Qoder SSE chunk");
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
            if (fr instanceof String && !((String)fr).isEmpty()) { terminal = true; break; }
            if (fr != null && !JSONObject.NULL.equals(fr) && !(fr instanceof String))
                throw new java.io.IOException("Invalid Qoder finish reason");
        }
        if (!terminal) throw new java.io.IOException("Incomplete Qoder SSE stream");
        }
        return pieces;
        } catch (Exception e) {
            throw new ChatTransportException(sentOrUnknown, e);
        } finally {
            if (context != null && conn != null) context.detach(conn);
            if (conn != null) conn.disconnect();
        }
    }

    private static final int MAX_SSE_EVENT_CHARS = 1024 * 1024;
    private static String readSseLine(BufferedReader reader, RequestContext context) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while (true) {
            if (context != null) context.check();
            ch = reader.read();
            if (ch == -1 || ch == '\n') break;
            if (line.length() >= MAX_SSE_EVENT_CHARS) throw new java.io.IOException("Qoder SSE line too large");
            line.append((char)ch);
        }
        if (ch == -1 && line.length() == 0) return null;
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        return line.toString();
    }
    private static InputStream contextInput(InputStream input, HttpURLConnection conn, RequestContext context) {
        return new java.io.FilterInputStream(input) {
            private void beforeRead() throws java.io.IOException {
                context.check();
                conn.setReadTimeout((int)Math.max(1, Math.min(300000L, context.remainingMillis())));
            }
            @Override public int read() throws java.io.IOException {
                beforeRead(); int n = in.read(); context.check(); return n;
            }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                beforeRead(); int n = in.read(b, off, len); context.check(); return n;
            }
        };
    }

    private static String readBoundedError(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int remaining = QoderFailurePolicy.MAX_ERROR_BYTES;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                if (n == 0) break;
                out.write(buf, 0, n); remaining -= n;
            }
            return out.toString("UTF-8");
        }
    }

    public static final class Catalog {
        public final org.json.JSONArray models;
        public final JSONObject meta;
        public final boolean freeView;
        public final int discoveredCount;
        public final org.json.JSONArray disabledModels;
        private Catalog(org.json.JSONArray models, JSONObject meta, boolean freeView, int discoveredCount, org.json.JSONArray disabledModels) {
            this.models = models; this.meta = meta; this.freeView = freeView; this.discoveredCount=discoveredCount; this.disabledModels=disabledModels;
        }
    }

    public static final class QoderHttpException extends java.io.IOException {
        public final int status;
        QoderHttpException(int status) { super("Qoder HTTP " + status); this.status = status; }
    }

    /**
     * The read stopped because the surrounding request ran out of time or was cancelled — never
     * because the answer said something. Kept apart from the generic "Qoder catalog read failed"
     * so a caller (and a log) can tell "unknown in time" from "unreadable".
     */
    static final class CatalogBudgetException extends java.io.IOException {
        final boolean canceled;
        CatalogBudgetException(String message, boolean canceled) { super(message); this.canceled = canceled; }
    }

    private static String readBounded(InputStream stream, int max) throws Exception {
        if (stream == null) throw new java.io.IOException("Empty response");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new java.io.IOException("Response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    /**
     * The catalog body, read under the caller's budget: each read may block up to the read timeout
     * (what is left of the budget), so a stall cannot outlast the deadline, and a body that trickles
     * returns often — the loop stops it at the deadline instead of letting every single read reset
     * the clock. Only the catalog read uses this: the refresh and login legs keep
     * {@link #readBounded(InputStream, int)} and the timeouts they always had.
     */
    private static String readBounded(InputStream stream, int max, QoderUsage.Budget budget, long deadlineNanos) throws Exception {
        if (stream == null) throw new java.io.IOException("Empty response");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            for (;;) {
                if (budget.cancelled()) throw catalogFailure(budget, null);
                if (remainingMillis(deadlineNanos) <= 0) throw catalogFailure(budget, null);
                try {
                    n = in.read(buf);
                } catch (java.net.SocketTimeoutException stalled) {
                    // The read timeout is the budget itself: this is the deadline arriving, not a
                    // slice to retry.
                    throw catalogFailure(budget, stalled);
                } catch (java.io.IOException failed) {
                    // A cancelled request closes the socket this read is blocked on; that close is
                    // the cancellation, not a verdict about the answer.
                    if (budget.cancelled()) throw catalogFailure(budget, failed);
                    if (remainingMillis(deadlineNanos) <= 0) throw catalogFailure(budget, failed);
                    throw failed;
                }
                if (n < 0) break;
                total += n;
                if (total > max) throw new java.io.IOException("Response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    /** Milliseconds left, rounded up (as {@code QoderUsage} counts it). */
    private static long remainingMillis(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime() + 999999L) / 1000000L;
    }

    /**
     * Why the read stopped, in words that never carry a token or an upstream body. A cancellation
     * wins over the deadline: both end the read, but only one of them is the caller's doing.
     */
    private static java.io.IOException catalogFailure(QoderUsage.Budget budget, Throwable cause) {
        boolean canceled = budget.cancelled();
        String reason = budget.reason();
        String why = canceled
                ? "Qoder catalog: canceled" + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "")
                : "Qoder catalog: timed out";
        CatalogBudgetException failure = new CatalogBudgetException(why, canceled);
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    /**
     * The response status, asked once and inside the whole-read deadline. The socket timeouts are
     * already what the read has left, so a slow header leg is allowed to take it and a refused
     * socket is the deadline arriving — never a slice to retry.
     */
    private static int answer(HttpURLConnection conn, QoderUsage.Budget budget, long deadlineNanos) throws Exception {
        if (budget.cancelled()) throw catalogFailure(budget, null);
        if (remainingMillis(deadlineNanos) <= 0) throw catalogFailure(budget, null);
        try {
            return conn.getResponseCode();
        } catch (java.net.SocketTimeoutException stalled) {
            throw catalogFailure(budget, stalled);
        } catch (java.io.IOException failed) {
            if (budget.cancelled()) throw catalogFailure(budget, failed);
            if (remainingMillis(deadlineNanos) <= 0) throw catalogFailure(budget, failed);
            throw failed;
        }
    }

    private static boolean freeOf(JSONObject raw) {
        Object price = raw.has("price_factor") && !raw.isNull("price_factor") ? raw.opt("price_factor") : raw.opt("priceFactor");
        if (price instanceof Number && !Double.isNaN(((Number)price).doubleValue()) && !Double.isInfinite(((Number)price).doubleValue())) {
            if (((Number)price).doubleValue() != 0) return false;
            JSONObject promotion = raw.optJSONObject("promotion");
            if (promotion == null) promotion = raw.optJSONObject("prommotion");
            if (promotion != null && promotion.optBoolean("active", false)) {
                Object before = promotion.has("before_promotion_price_factor") && !promotion.isNull("before_promotion_price_factor")
                        ? promotion.opt("before_promotion_price_factor") : promotion.opt("beforePromotionPriceFactor");
                if (before instanceof Number && ((Number)before).doubleValue() > 0) return false;
            }
            return true;
        }
        Object free = raw.has("is_free") && !raw.isNull("is_free") ? raw.opt("is_free") : raw.opt("isFree");
        return Boolean.TRUE.equals(free);
    }

    /** This read's own ceiling: the socket timeouts it has always used (15s connect / 20s read). */
    private static final long CATALOG_CONNECT_MS = 15000L;
    private static final long CATALOG_READ_MS = 20000L;

    /** The catalog, under no request's budget: only this read's own 15s/20s timeouts bound it. */
    public static Catalog readCatalog(SignIn si) throws Exception {
        return readCatalog(si, url -> NetworkPolicy.openHttp(url));
    }

    /**
     * The catalog over the policy-bound opener, read under the surrounding request's budget — what
     * a caller that has a request in hand (the quota diagnostic) should ask for.
     */
    public static Catalog readCatalog(SignIn si, QoderUsage.Budget budget) throws Exception {
        return readCatalog(si, url -> NetworkPolicy.openHttp(url), budget);
    }

    /**
     * The catalog over an explicit transport, under no request's budget: the budget-less overload
     * the gateway's catalog sync and the loopback fixtures already call. It delegates to
     * {@link QoderUsage.Budget#NONE}, so nothing about its timing changed.
     */
    public static Catalog readCatalog(SignIn si, ConnectionFactory factory) throws Exception {
        return readCatalog(si, factory, QoderUsage.Budget.NONE);
    }

    /**
     * The catalog, read under the surrounding request's budget — the one read that a diagnostic
     * (<code>GatewayServer.qoderDisabledModelQuota</code>) makes before it may judge an account.
     *
     * <p><b>One deadline, and the request's cancellation.</b> The whole read — status line, body
     * and every byte in it — must finish inside what the request has left, capped by this read's
     * own ceiling ({@link #CATALOG_READ_MS}); both socket timeouts are set from that remainder, so
     * a stalled {@code read} cannot outlast it. The connection is registered with
     * {@link QoderUsage.Budget#attach} before the first read and forgotten in the finally, so a
     * request cancelled while this read is blocked on the socket disconnects it instead of waiting
     * for the deadline, and no connection outlives the call. {@link QoderUsage.Budget#NONE} leaves
     * both timeouts at 15s/20s, exactly the behaviour the older overloads had.
     *
     * <p><b>Still one request, asked once.</b> No retry, no second address, and a read that stops
     * early throws {@link CatalogBudgetException} — the catalog is then unknown, never partial.
     * Nothing here spends a token, refreshes anything or writes an account record.
     *
     * @param si      the account whose COSY headers sign the read
     * @param factory where to open {@code MODELS_URL}
     * @param budget  the surrounding request's time and cancellation, or null for none
     * @throws CatalogBudgetException when the request ran out of time or was cancelled
     * @throws QoderHttpException     when Qoder answered other than 2xx
     * @throws java.io.IOException    when the answer was unreadable or not a catalog
     */
    public static Catalog readCatalog(SignIn si, ConnectionFactory factory, QoderUsage.Budget budget) throws Exception {
        if (si == null || factory == null) throw new IllegalArgumentException("Qoder catalog requires sign-in and transport");
        final QoderUsage.Budget limit = budget == null ? QoderUsage.Budget.NONE : budget;
        if (limit.cancelled()) throw catalogFailure(limit, null);
        final long left = limit.remainingMillis();
        if (left <= 0) throw new java.io.IOException("Qoder catalog: no time left for this read");
        // One deadline for the whole read. With Budget.NONE the ceiling alone applies, which is the
        // same 15s connect / 20s read this read always used.
        final long deadlineNanos = System.nanoTime() + Math.min(left, CATALOG_READ_MS) * 1000000L;
        HttpURLConnection conn = null;
        boolean attached = false;
        try {
            java.util.Map<String, String> headers = cosyHeaders(MODELS_URL, si, "");
            conn = factory.open(MODELS_URL);
            if (conn == null) throw new java.io.IOException("Qoder catalog: no transport");
            // Attached before the first read, so a cancellation that lands while this read is
            // blocked on the socket disconnects it instead of waiting for the deadline to lapse.
            try { limit.attach(conn); attached = true; }
            catch (Exception cannotAttach) {
                if (limit.cancelled()) throw catalogFailure(limit, cannotAttach);
                throw cannotAttach;
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) Math.max(1L, Math.min(CATALOG_CONNECT_MS, left)));
            conn.setReadTimeout((int) Math.max(1L, Math.min(CATALOG_READ_MS, left)));
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            int code = answer(conn, limit, deadlineNanos);
            if (code < 200 || code >= 300) throw new QoderHttpException(code);
            String wire = readBounded(conn.getInputStream(), 4 * 1024 * 1024, limit, deadlineNanos).trim();
            JSONObject root = new JSONObject(wire.startsWith("{") ? wire : decodeBody(wire));
            java.util.LinkedHashMap<String, JSONObject> ordered = new java.util.LinkedHashMap<>();
            boolean found = false; int after = -1;
            for (String scene : new String[]{"app", "chat"}) {
                if (!root.has(scene)) continue;
                Object value = root.opt(scene);
                if (!(value instanceof org.json.JSONArray)) throw new java.io.IOException("Invalid scene");
                found = true; org.json.JSONArray arr = (org.json.JSONArray)value;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (!(item instanceof JSONObject)) throw new java.io.IOException("Invalid entry");
                    JSONObject m = (JSONObject)item; Object key = m.opt("key"), enabled = m.opt("enable");
                    if (!(key instanceof String) || ((String)key).trim().isEmpty() || !(enabled instanceof Boolean)) throw new java.io.IOException("Invalid entry");
                    String k = ((String)key).trim();
                    if ("app".equals(scene)) { if (!ordered.containsKey(k)) ordered.put(k, m); }
                    else {
                        java.util.ArrayList<String> keys = new java.util.ArrayList<>(ordered.keySet());
                        int index = keys.indexOf(k);
                        if (index >= 0) after = index;
                        else {
                            keys.add(++after, k);
                            java.util.LinkedHashMap<String, JSONObject> merged = new java.util.LinkedHashMap<>();
                            for (String name : keys) merged.put(name, name.equals(k) ? m : ordered.get(name));
                            ordered = merged;
                        }
                    }
                }
            }
            if (!found) throw new java.io.IOException("Missing scenes");
            org.json.JSONArray models = new org.json.JSONArray(); JSONObject meta = new JSONObject();
            boolean paidOff = false, paidOn = false; int discovered=0; org.json.JSONArray disabled=new org.json.JSONArray();
            long catalogObservedAt = System.currentTimeMillis();
            for (java.util.Map.Entry<String, JSONObject> e : ordered.entrySet()) {
                String k = e.getKey(); JSONObject m = e.getValue();
                if ("auto".equals(k) || "default".equals(k)) continue;
                boolean enabled = m.optBoolean("enable", false); discovered++;
                if (!enabled) disabled.put(new JSONObject().put("key",k).put("name",m.optString("display_name",k)));
                if (!freeOf(m)) { if (enabled) paidOn = true; else paidOff = true; }
                if (enabled) { models.put(k); JSONObject observed = new JSONObject(m.toString()).put("catalogObservedAt", catalogObservedAt); meta.put(k, ModelBillingMetadata.normalize("qoder", observed)); }
            }
            if (models.length()==0) throw new java.io.IOException("Qoder model catalog empty");
            return new Catalog(models, meta, paidOff && !paidOn, discovered, disabled);
        } catch (QoderHttpException e) { throw e; }
        catch (CatalogBudgetException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException("Qoder catalog read failed"); }
        finally {
            if (conn != null) {
                if (attached) limit.detach(conn);
                conn.disconnect();
            }
        }
    }

    private static long parseEpoch(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        String text = String.valueOf(value).trim();
        try { long n = Long.parseLong(text); return n > 0 && n < 1000000000000L ? n * 1000L : n; }
        catch (Exception ignored) { }
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"}) {
            try { java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, java.util.Locale.US); f.setLenient(false); return f.parse(text).getTime(); }
            catch (Exception ignored) { }
        }
        return 0;
    }
    private static long refreshExpiry(JSONObject response, boolean device) {
        long now = System.currentTimeMillis();
        if (device) { long end = parseEpoch(response.opt("expires_at")); if (end > 0) return end; }
        else for (String key : new String[]{"expire_time", "expires_at"}) { long end = parseEpoch(response.opt(key)); if (end > now) return end; }
        Object n = response.opt("expires_in");
        if (n instanceof Number) {
            double ms = ((Number)n).doubleValue() * (device ? 1000 : 1);
            if (!Double.isNaN(ms) && !Double.isInfinite(ms) && ms > 0 && ms < Long.MAX_VALUE - now) return now + (long)ms;
        }
        return now + 86400000L;
    }

    // ================= 刷新请求的发送阶段辨别 =================

    /**
     * 一次刷新请求走到了哪一步。
     *
     * <p>只有 {@link #NOT_CONNECTED} 才能断言「本机一个应用字节都没交给链路」。刷新 token 是一次性的，
     * 多花一次就是账号报废，所以对写操作的开头取保守口径——宁可多判一次「未知」，也不放行重放。
     *
     * <p><b>两条传输，可证明的程度不同，不要混着解释：</b>
     * <ul>
     *   <li><b>生产路径</b>（{@link #refreshCandidate(SignIn)}，即 {@link RefreshTransport} 的单次
     *       TLS 传输）：先用 {@link NetworkPolicy.Snapshot#connectTunnel} 显式建连、完成 TLS 握手
     *       （含主机名校验），之后才写请求行。所以策略拒绝、TCP 连不上、TLS／证书／主机名失败都发生在
     *       请求字节之前——这里的 {@link #NOT_CONNECTED} 是<b>真的</b>「没发出去」，同一个一次性
     *       token 可以安全重试。</li>
     *   <li><b>兼容路径</b>（{@link #refreshCandidate(SignIn, ConnectionFactory)}，注入的
     *       {@link HttpURLConnection} 工厂；目录读取与既有测试在用）：连接是惰性的，真实断网／DNS／
     *       连接被拒／TLS 失败发生在 {@code getOutputStream()} 里，因此落在 {@link #CONNECTED}。
     *       这条路径的语义与改动前完全一致。</li>
     * </ul>
     *
     * <p>从请求行开始写起之后的失败一律算「可能已执行」：写出去多少字节都无法证明上游没收到，
     * 写 0 字节也一样（写调用抛异常时字节可能已经进了 socket）。
     *
     * <p><b>单次发送：</b>生产传输自己写一次请求行、写一次请求体、读一次答复，没有任何重试循环，
     * 也不经过 {@link HttpURLConnection}，因此不会触发后者在「请求已写出、连接被掐」时自行重发 POST
     * 的行为（{@code sun.net.http.retryPost}；环回实测会看到第二个连接）。
     */
    public enum RefreshSendStage {
        /**
         * 请求一个字节都还没写出去：URL／策略拒绝、TCP 建连失败、TLS 握手失败，或缺 token、
         * 请求体不合法、传输在 open 阶段就失败。
         */
        NOT_CONNECTED,
        /**
         * 链路已建立，但请求行／请求头还没写完。兼容路径上真实的断网、DNS、连接被拒、TLS 失败
         * 也落在这里（{@code getOutputStream()} 才建连），两者无法区分，一律按「可能已执行」处理。
         */
        CONNECTED,
        /** 正在写请求体：写出去多少字节都算「可能已执行」，写 0 字节也一样（header 可能已发出）。 */
        WRITING,
        /** 请求体写完，正在等状态行。 */
        WRITTEN,
        /** 正在读状态码：读不到就无从判断；读到了就说明上游处理过这次请求。 */
        READING_STATUS,
        /** 状态已到，正在读、解析新的一对 token。 */
        READING_BODY
    }

    /**
     * 刷新失败的可判定结论。GatewayServer 只依赖这一层回答三个问题：
     * 确定没发出去吗（可以复用同一个一次性 token 重试）？可能已执行吗（必须按已花掉处理）？
     * 上游到底答过没有？
     */
    public interface RefreshOutcome {
        RefreshSendStage stage();
        /** 只在本机确定没把请求交给上游时为 true——此时用同一个一次性 token 重试是安全的。 */
        boolean definitelyNotSent();
        /** = definitelyNotSent()；给调用方一个直白的名字。 */
        boolean mayReplay();
        /** 请求可能已被上游执行：一次性 token 必须按已花掉处理，禁止重放。 */
        boolean outcomeUnknown();
        /** 上游给过 HTTP 状态：结论不是「未知」，而是「被答复过」。 */
        boolean answered();
        boolean cancelled();
        long bytesWritten();
        long bytesTotal();
        String reason();
        String diagnostic();
    }

    /**
     * 刷新请求的发送阶段异常。继承 IOException，既有的 catch(IOException)／catch(Exception)
     * 调用方不用改一行；getMessage() 仍是 "Qoder refresh failed: ..." 的形状，
     * GatewayServer 现有的失败阶段拼接逻辑照常工作。
     */
    public static final class RefreshSendFailure extends java.io.IOException implements RefreshOutcome {
        public final RefreshSendStage stage;
        public final long bytesWritten;
        public final long bytesTotal;
        public final boolean cancelled;
        public final boolean answered;
        public final String reason;

        RefreshSendFailure(String message, String reason, RefreshSendStage stage,
                           long bytesWritten, long bytesTotal, boolean cancelled, boolean answered) {
            this(message, reason, stage, bytesWritten, bytesTotal, cancelled, answered, null);
        }

        RefreshSendFailure(String message, String reason, RefreshSendStage stage,
                           long bytesWritten, long bytesTotal, boolean cancelled, boolean answered,
                           Throwable cause) {
            super(message, cause);
            this.reason = sanitizeReason(reason);
            this.stage = stage == null ? RefreshSendStage.NOT_CONNECTED : stage;
            this.bytesTotal = Math.max(0L, bytesTotal);
            this.bytesWritten = Math.max(0L, Math.min(bytesWritten, this.bytesTotal));
            this.cancelled = cancelled;
            this.answered = answered;
        }

        @Override public RefreshSendStage stage() { return stage; }

        /**
         * 保守口径：就地被打断（cancelled）时一律不算「没发出去」——取消可能恰好发生在
         * 请求行已经交给 socket 之后，此时重放同样会让一次性 token 多花一次。
         *
         * <p>边界：生产传输上这一判定覆盖策略拒绝、TCP 建连失败与 TLS 握手失败（请求字节之前）；
         * 兼容的 HttpURLConnection 工厂上它只覆盖 {@code factory.open} 的失败，真实断网落在
         * {@link RefreshSendStage#CONNECTED}，结论是「未知」而不是「没发出去」。
         */
        @Override public boolean definitelyNotSent() {
            return !cancelled && !answered && stage == RefreshSendStage.NOT_CONNECTED;
        }

        @Override public boolean mayReplay() { return definitelyNotSent(); }

        @Override public boolean outcomeUnknown() { return !definitelyNotSent() && !answered; }

        @Override public boolean answered() { return answered; }
        @Override public boolean cancelled() { return cancelled; }
        @Override public long bytesWritten() { return bytesWritten; }
        @Override public long bytesTotal() { return bytesTotal; }
        @Override public String reason() { return reason; }

        /**
         * 给人看的判定。措辞只说本机看得见的事实，并把「兼容路径上断网落在这里」写明。
         */
        @Override public String diagnostic() {
            StringBuilder sb = new StringBuilder();
            if (cancelled) sb.append("Qoder 刷新请求被就地取消，无法证明请求没出去，按可能已执行处理");
            else if (definitelyNotSent()) sb.append("Qoder 刷新请求没有发出去（本机一个请求字节都没写出："
                    + "策略拒绝、TCP 建连或 TLS 握手就失败了），同一个 token 可安全重试");
            else if (answered) sb.append("Qoder 刷新请求已得到上游答复，一次性 token 按已用掉处理");
            else if (stage == RefreshSendStage.CONNECTED)
                sb.append("Qoder 刷新请求的链路已经建立，但请求体还没写出（经 HttpURLConnection 工厂时，"
                        + "断网、DNS、连接被拒、TLS 失败也可能落在这里），无法证明请求没出去，"
                        + "按可能已执行处理，禁止重放");
            else sb.append("Qoder 刷新请求可能已被上游执行，一次性 token 必须当作已用掉，禁止重放");
            sb.append(" [").append(stage).append(", ").append(bytesWritten).append("/").append(bytesTotal).append(" bytes]");
            if (!reason.isEmpty()) sb.append(": ").append(reason);
            return sb.toString();
        }
    }

    /**
     * reason 是要进日志和接口返回的短令牌，只允许可打印 ASCII：控制字符／换行一律替换，
     * 超长直接截断。这样即使某个异常类名或包装层被塞了别的东西，也不会污染日志行。
     */
    static String sanitizeReason(String reason) {
        if (reason == null || reason.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(reason.length(), MAX_REASON_CHARS));
        for (int i = 0; i < reason.length() && sb.length() < MAX_REASON_CHARS; i++) {
            char c = reason.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }

    /** reason 的长度上限：够写 "<token>:<ExceptionSimpleName>"，又不至于把日志撑爆。 */
    static final int MAX_REASON_CHARS = 96;

    /**
     * 把任意刷新异常归一成可判定的结论，供 GatewayServer（以及任何调用方）直接消费：
     * 认不出类型的异常一律按「可能已执行」保守处理，因为无法证明它发生在写请求之前。
     * 传 null 返回 null。
     */
    public static RefreshOutcome refreshOutcome(Throwable failure) {
        if (failure == null) return null;
        if (failure instanceof RefreshOutcome) return (RefreshOutcome) failure;
        if (failure instanceof QoderHttpException) {
            // 上游明确答过：状态码就是结论，既不是「没发出去」也不是「未知」。
            int status = ((QoderHttpException) failure).status;
            return new RefreshVerdict(RefreshSendStage.READING_STATUS, false, true, false, "answered_" + status);
        }
        String name = failure.getClass().getSimpleName();
        return new RefreshVerdict(RefreshSendStage.CONNECTED, false, false, false, "unclassified:" + name);
    }

    /**
     * refreshOutcome 给「不是本类自己抛出的刷新异常」生成的结论。它不是异常，只是判定——
     * 免得调用方把结论当成可抛出的东西再往外扔。这种结论没有字节账本（固定 0），
     * 判定只看 {@link #definitelyNotSent()} 和 {@link #outcomeUnknown()}。
     */
    private static final class RefreshVerdict implements RefreshOutcome {
        private final RefreshSendStage stage;
        private final boolean notSent;
        private final boolean answered;
        private final boolean cancelled;
        private final String reason;
        RefreshVerdict(RefreshSendStage stage, boolean notSent, boolean answered, boolean cancelled, String reason) {
            this.stage = stage == null ? RefreshSendStage.NOT_CONNECTED : stage;
            this.notSent = notSent;
            this.answered = answered;
            this.cancelled = cancelled;
            this.reason = reason == null ? "" : reason;
        }
        @Override public RefreshSendStage stage() { return stage; }
        @Override public boolean definitelyNotSent() { return notSent; }
        @Override public boolean mayReplay() { return notSent; }
        @Override public boolean outcomeUnknown() { return !notSent && !answered; }
        @Override public boolean answered() { return answered; }
        @Override public boolean cancelled() { return cancelled; }
        @Override public long bytesWritten() { return 0L; }
        @Override public long bytesTotal() { return 0L; }
        @Override public String reason() { return reason; }
        @Override public String diagnostic() {
            StringBuilder sb = new StringBuilder(notSent
                    ? "Qoder 刷新请求没有发出去（本机未交给链路），同一个 token 可安全重试"
                    : answered ? "Qoder 刷新请求已得到上游答复"
                    : "Qoder 刷新请求可能已被上游执行，一次性 token 必须当作已用掉");
            if (!reason.isEmpty()) sb.append(": ").append(reason);
            return sb.toString();
        }
    }

    /** 便捷判断：这个刷新失败是否确定没发出去（认不出来的一律 false）。 */
    public static boolean refreshDefinitelyNotSent(Throwable failure) {
        RefreshOutcome outcome = refreshOutcome(failure);
        return outcome != null && outcome.definitelyNotSent();
    }

    /** 一次刷新请求的阶段记账本；所有失败最后都要过它一次才能判断「发没发出去」。 */
    private static final class RefreshTrace {
        RefreshSendStage stage = RefreshSendStage.NOT_CONNECTED;
        long written;
        long total;
        boolean answered;
    }

    /**
     * 记账用的输出流：只统计底层 write 正常返回的字节数。写调用抛异常时字节可能已经
     * 部分进了 socket，所以这个数字只会偏小、绝不偏大；判定只看阶段，不看字节数。
     */
    private static final class TraceOutputStream extends java.io.FilterOutputStream {
        private final RefreshTrace trace;
        TraceOutputStream(java.io.OutputStream out, RefreshTrace trace) {
            super(out);
            this.trace = trace;
        }
        @Override public void write(int b) throws java.io.IOException {
            out.write(b);
            trace.written += 1L;
        }
        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            trace.written += len;
        }
    }

    /** 阶段 + 原因 → 一个 RefreshSendFailure；原因只放短令牌和异常类名，绝不带 token 正文。 */
    private static RefreshSendFailure refreshRefusal(RefreshTrace trace, Exception cause) {
        boolean cancelled = cause instanceof InterruptedException
                || (cause instanceof java.io.InterruptedIOException
                    && !(cause instanceof java.net.SocketTimeoutException));
        String reason = refreshReason(trace, cause, cancelled);
        return new RefreshSendFailure("Qoder refresh failed: " + reason, reason, trace.stage,
                trace.written, trace.total, cancelled, trace.answered, cause);
    }

    private static String refreshReason(RefreshTrace trace, Exception cause, boolean cancelled) {
        String name = cause == null ? "unknown" : cause.getClass().getSimpleName();
        if (cancelled) return "cancelled:" + name;
        switch (trace.stage) {
            case NOT_CONNECTED: return "not_sent:" + name;
            case CONNECTED: return "headers_may_be_queued:" + name;
            case WRITING: return "write_incomplete:" + name;
            case WRITTEN: return "no_response:" + name;
            case READING_STATUS: return "status_unreadable:" + name;
            default: return "body_unreadable:" + name;
        }
    }

    // ================= 单次 TLS 传输（不经过 HttpURLConnection） =================

    /**
     * 一次刷新交换的链路：只能开一次、写一次、读一次。
     *
     * <p>之所以把「建连」和「发请求」拆成两步，是为了让发送阶段可判定：
     * {@link RefreshTransport#open} 只许建连（TCP＋TLS），不写任何请求字节；
     * {@link #request()} 才写请求行与请求头；之后才轮到请求体。因此 open 的失败必然早于
     * 任何应用字节，可以诚实地标成 {@link RefreshSendStage#NOT_CONNECTED}。
     *
     * <p>请求体的流由链路自己持有：调用方只写、只 flush，<b>不要 close</b>——
     * 那会把这条链路上读的方向一起关掉。
     */
    public interface RefreshLink extends java.io.Closeable {
        /** 写出请求行与请求头（只一次），返回只用于写请求体的流。 */
        java.io.OutputStream request() throws Exception;
        /** 读状态行（只一次）。1xx 过渡答复会被跳过；重定向不会被跟随。 */
        int statusLine() throws Exception;
        /** 读响应体（只一次），最多 maxBytes 字节；chunked 与 gzip 在这里解开。 */
        byte[] body(int maxBytes) throws Exception;
    }

    /**
     * 刷新请求的传输。生产实现见 {@link #tlsTransport()}：一条策略隧道 + TLS，单次、不重放。
     * 测试注入假实现来数真实的发送次数。
     */
    public interface RefreshTransport {
        /** 建连（含 TLS 握手），<b>不写任何请求字节</b>。 */
        RefreshLink open(java.net.URI target, int contentLength, int timeoutMs) throws Exception;
    }

    /**
     * 一次刷新交换的时间预算（毫秒），取值对齐上游 {@code openapi()} 的
     * {@code AbortSignal.timeout(20_000)}，但<b>约束力不同，不要当硬件上限</b>：
     *
     * <p><b>它确实约束的：</b>
     * <ul>
     *   <li>{@code connectTunnel} 的 TCP 建连（{@code connectTimeout} = 剩余预算）；</li>
     *   <li>代理 CONNECT 的应答（读超时 = 剩余预算）；</li>
     *   <li>TLS 握手（{@code soTimeout} = 剩余预算）；</li>
     *   <li>每一次读：读之前把 {@code soTimeout} 收敛到「这次交换剩余的时间」，所以
     *       <b>逐步共享同一个 deadline</b>，一次读不会重置成再等 20 秒。</li>
     * </ul>
     *
     * <p><b>它管不到的（必须如实说）：</b>
     * <ul>
     *   <li><b>DNS 解析</b>：{@code InetAddress} / {@code Socket.connect} 的名字解析没有可设的
     *       超时，解析器卡住时这段时间不计入任何预算；</li>
     *   <li><b>阻塞写</b>：Java/Android 的 {@code Socket} 没有写超时（没有 {@code SO_SNDTIMEO}
     *       这样的接口），对端不收、窗口满时 {@code write} 可以一直阻塞。实际风险被两个因素限制：
     *       请求体只有几十到几百字节（上限 {@link #MAX_REFRESH_BODY_BYTES}），且请求行极短；
     *       但这是「风险小」，不是「有超时」；</li>
     *   <li>因此这不是 socket 级的硬 20 秒上限。上游 {@code fetch} 的 AbortSignal 会中止整个
     *       操作（含写），本机做不到完全等价，只能做到「读与建连各有上限」。</li>
     * </ul>
     */
    public static final int REFRESH_TIMEOUT_MS = 20000;

    private static final RefreshTransport TLS_REFRESH_TRANSPORT = new TlsRefreshTransport();

    /** 生产传输（无状态，每次交换都是一条新链路）。 */
    static RefreshTransport tlsTransport() { return TLS_REFRESH_TRANSPORT; }

    /**
     * 严格核对刷新目标：只接受 https、必须带主机名、路径必须是裸路径、不许带 userinfo／query／fragment。
     * 这是「一次性 token 绝不走明文、绝不被拼去别的地方」的第一道闸。
     */
    private static int checkedTargetPort(java.net.URI target) throws java.io.IOException {
        if (target == null) throw new java.io.IOException("Qoder refresh: no target");
        String scheme = target.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme))
            throw new java.io.IOException("Qoder refresh: the refresh target must be https");
        String host = target.getHost();
        if (host == null || host.isEmpty())
            throw new java.io.IOException("Qoder refresh: the refresh target must name a host");
        if (target.getRawUserInfo() != null)
            throw new java.io.IOException("Qoder refresh: the refresh target must not carry user info");
        String path = target.getRawPath();
        if (path == null || !path.startsWith("/"))
            throw new java.io.IOException("Qoder refresh: the refresh target must be a bare path");
        if (target.getRawQuery() != null || target.getRawFragment() != null)
            throw new java.io.IOException("Qoder refresh: the refresh target must be a bare path");
        int port = target.getPort();
        if (port == 0 || port > 65535) throw new java.io.IOException("Qoder refresh: the refresh target has a bad port");
        return port < 0 ? 443 : port;
    }

    /** 一次交换还剩多少毫秒；用完了就抛超时，让整次交换不越过总预算。 */
    private static int millisLeft(long deadlineNanos) throws java.io.IOException {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) throw new java.net.SocketTimeoutException("Qoder refresh: deadline exceeded");
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left) + 1));
    }

    /** Host 头里的 authority：默认端口不写，IPv6 加方括号（与 fetch 的写法一致）。 */
    static String authority(String host, int port) {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return port == 443 ? h : h + ":" + port;
    }

    /**
     * 生产传输：一条 {@link NetworkPolicy#connectTunnel} 隧道 + TLS（{@code setEndpointIdentificationAlgorithm}
     * 写死 "HTTPS"，没有明文回退，不碰 cookie／认证器／重定向）。
     *
     * <p>与上游 {@code fetch} 的差别只在「不跟随重定向」：一次性 refresh token 不该被转发到
     * 另一个来源，3xx 一律按「上游答过、状态非 200」交回调用方。
     */
    static final class TlsRefreshTransport implements RefreshTransport {
        @Override public RefreshLink open(java.net.URI target, int contentLength, int timeoutMs) throws Exception {
            final int port = checkedTargetPort(target);
            final String host = target.getHost();
            final String path = target.getRawPath();
            if (contentLength < 0) throw new java.io.IOException("Qoder refresh: bad request body length");
            if (timeoutMs <= 0) throw new java.io.IOException("Qoder refresh: bad timeout");
            // 一个 deadline：TCP 建连、代理 CONNECT、TLS 握手和每一次读都从它上面取「剩余」。
            // 注意它的边界（见 REFRESH_TIMEOUT_MS）：DNS 解析与阻塞写不在预算内。
            final long deadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            final NetworkPolicy.Snapshot policy = NetworkPolicy.capture();
            final java.net.Socket plain =
                    policy.connectTunnel(host, port, millisLeft(deadlineNanos), millisLeft(deadlineNanos));
            try {
                javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket)
                        ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                                .createSocket(plain, host, port, true);
                try {
                    javax.net.ssl.SSLParameters params = tls.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(params);
                    if (!"HTTPS".equals(tls.getSSLParameters().getEndpointIdentificationAlgorithm()))
                        throw new javax.net.ssl.SSLException("Qoder refresh: hostname verification unavailable");
                    tls.setSoTimeout(millisLeft(deadlineNanos));
                    tls.startHandshake();
                } catch (Exception handshake) {
                    try { tls.close(); } catch (Exception ignored) { }
                    throw handshake;
                }
                return new SingleShotLink(tls, authority(host, port), path, contentLength, millisLeft(deadlineNanos));
            } catch (Exception failure) {
                try { plain.close(); } catch (Exception ignored) { }
                throw failure;
            }
        }
    }

    /**
     * 刷新请求的请求行与请求头：上游 {@code openapi()} 的字段集合，外加显式长度与断开。
     *
     * <p><b>故意不发 User-Agent（不是漏了）</b>。上游与本工程的相关来源都核对过，没有任何一个给出这个端点上的值：
     * <ul>
     *   <li>上游 {@code openapi()}（0.2.64，{@code /api/v1/jobToken/refresh} 与
     *       {@code /api/v1/deviceToken/refresh} 都走它）只写 {@code Accept}、有 body 时的
     *       {@code Content-Type}、有 token 时的 {@code Authorization}，再加
     *       {@code site.clientHeaders?.()}；而 {@code clientHeaders} 只有 QwenWork 站定义
     *       （{@code User-Agent: qoderwork/<version>} 与一串 {@code X-QwenWork-*}），Qoder 与
     *       Qoder CN 两站都没有——那是另一个站、另一个客户端的指纹，不是这个端点的值；</li>
     *   <li>上游确实有一个 Qoder 的 UA 值 {@code User-Agent: Qoder}，但它只出现在<b>别的</b>调用点上：
     *       专用域的 {@code /algo/api/v1/version} 探测、{@code /sash/api/v2/me/usage}、以及
     *       campaign／check-in。这些都不是 token 交换端点，照抄过来就是「借一个别处成立的值」；</li>
     *   <li>本工程里 Qoder 自己的 User-Agent 是 {@code Magic/App}，只出现在登录的 start／poll
     *       两个 {@code httpGet}／{@code httpPostJson} 上，从未出现在 token 交换端点；</li>
     *   <li>旧的 {@code HttpURLConnection} 刷新重载发的是运行时默认 UA（Android 上是
     *       {@code Dalvik/x}，JVM 上是 {@code Java/x}），那是运行时的偶然值，也不是本项目声明的东西。</li>
     * </ul>
     *
     * <p>没有上游声明值、也没有同端点的可信值，就不臆造一个：RFC 9110 的 User-Agent 只是 SHOULD，
     * 缺它不改变这个请求的语义，而凭空发明一个指纹反而等于给自己加一条未经验证的假设。
     */
    static String requestHead(String authority, String path, int contentLength) {
        return "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Accept: application/json\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
    }

    /**
     * 一条已经建好的链路：写一次请求、读一次答复，没有任何重放路径。
     * 包内可见——除了生产传输自己，只有传输层测试会直接构造它（构造时传一个已连通的 socket）。
     */
    static final class SingleShotLink implements RefreshLink {
        private final java.net.Socket socket;
        private final String authority;
        private final String path;
        private final int contentLength;
        private final long deadlineNanos;
        private final java.io.InputStream in;
        private final java.io.OutputStream out;
        private Head head;
        private byte[] body;
        private boolean requested;
        private volatile boolean closed;

        SingleShotLink(java.net.Socket socket, String authority, String path, int contentLength, int timeoutMs)
                throws java.io.IOException {
            if (socket == null || authority == null || path == null)
                throw new java.io.IOException("Qoder refresh: bad link");
            if (contentLength < 0 || timeoutMs <= 0) throw new java.io.IOException("Qoder refresh: bad link");
            this.socket = socket;
            this.authority = authority;
            this.path = path;
            this.contentLength = contentLength;
            this.deadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            this.in = new Wire();
            this.out = socket.getOutputStream();
        }

        /**
         * 每次读之前把 socket 的读超时收敛到「这次交换剩下的时间」：读是逐步共享同一个 deadline 的，
         * 上一段慢不会让下一段重新拿到一整份预算。写方向没有可设的超时（见 {@link #REFRESH_TIMEOUT_MS}）。
         */
        private int remaining() throws java.io.IOException {
            if (closed) throw new java.io.IOException("Qoder refresh: link is closed");
            if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("Qoder refresh: interrupted");
            long left = deadlineNanos - System.nanoTime();
            if (left <= 0) throw new java.net.SocketTimeoutException("Qoder refresh: deadline exceeded");
            return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left) + 1));
        }

        private final class Wire extends java.io.FilterInputStream {
            Wire() throws java.io.IOException { super(socket.getInputStream()); }
            @Override public int read() throws java.io.IOException {
                socket.setSoTimeout(remaining());
                return super.read();
            }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                socket.setSoTimeout(remaining());
                return super.read(b, off, len);
            }
        }

        @Override public java.io.OutputStream request() throws Exception {
            if (requested) throw new java.io.IOException("Qoder refresh: the link is single shot");
            requested = true;
            socket.setSoTimeout(remaining());
            out.write(requestHead(authority, path, contentLength)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            return out;
        }

        @Override public int statusLine() throws Exception {
            if (!requested) throw new java.io.IOException("Qoder refresh: the request was never written");
            if (head == null) head = readHead(in);
            return head.status;
        }

        @Override public byte[] body(int maxBytes) throws Exception {
            if (head == null) throw new java.io.IOException("Qoder refresh: no status line yet");
            if (maxBytes <= 0) throw new java.io.IOException("Qoder refresh: bad response bound");
            if (body == null) body = readBody(in, head, maxBytes);
            return body;
        }

        @Override public void close() {
            closed = true;
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    /** 响应头解析结果：状态码 + 决定响应体边界与编码的三件事。 */
    static final class Head {
        final int status;
        final long contentLength;
        final boolean chunked;
        final String encoding;
        Head(int status, long contentLength, boolean chunked, String encoding) {
            this.status = status;
            this.contentLength = contentLength;
            this.chunked = chunked;
            this.encoding = encoding;
        }
    }

    /**
     * 读状态行与全部响应头。1xx 过渡答复跳过（上游 fetch 也这样处理），其余原样返回状态码——
     * 包括 3xx：重定向不跟随，交回调用方按「状态非 200」处理。
     *
     * <p><b>只有三种头决定「这个答复怎么读」：{@code content-length}、{@code transfer-encoding}、
     * {@code content-encoding}。它们一冲突就没法安全解析，一律拒绝：</b>
     * <ul>
     *   <li>Content-Length 与 Transfer-Encoding 并存（RFC 7230 §3.3.3 的歧义源）；</li>
     *   <li>同一个头出现两次而值不同；Transfer-Encoding 出现两次（哪怕是两个 chunked）；</li>
     *   <li>不是 chunked 的 Transfer-Encoding、不是十进制数字的 Content-Length、互相矛盾的
     *       Content-Encoding（同一个值重复两次是可以的）。</li>
     * </ul>
     *
     * <p>其余重复头（Via、Set-Cookie、自定义 trace 头……）一律忽略：它们既不决定边界也不决定解码，
     * 拿它们判死只会让「上游已经轮换、本机却读不出新 token」这种纯损失场景成立。这正是与
     * 上游 {@code fetch}（h1 解析器容忍重复头）对齐的地方——上游也只在 framing 上严格。
     *
     * <p>同样的道理也适用于这些头的<b>值</b>：既然它们被忽略，值里出现 obs-text（0x80-0xFF 的非
     * ASCII 字节，例如带中文的 Set-Cookie 名或自定义 trace 值）就不该让整次交换作废，所以
     * {@link #httpLine} 只对控制字符严格。但上面那三种 framing 头例外：它们的值必须纯 ASCII，
     * 否则 {@code "chunked"}／十进制长度／{@code "gzip"} 的相等比较就不可信了——这一条不放宽。
     */
    static Head readHead(java.io.InputStream in) throws java.io.IOException {
        int[] budget = {32768};
        for (int interim = 0; ; interim++) {
            String status = httpLine(in, budget, 4096);
            if (!status.matches("HTTP/1\\.[01] [1-9][0-9]{2}(?: [\\x20-\\x7E]*)?"))
                throw new java.io.IOException("Qoder refresh: malformed status line");
            int code = Integer.parseInt(status.substring(9, 12));
            java.util.List<String> lengths = new java.util.ArrayList<>(1);
            java.util.List<String> transfers = new java.util.ArrayList<>(1);
            java.util.List<String> encodings = new java.util.ArrayList<>(1);
            int count = 0;
            while (true) {
                String line = httpLine(in, budget, 8192);
                if (line.isEmpty()) break;
                if (++count > 64) throw new java.io.IOException("Qoder refresh: too many response headers");
                int colon = line.indexOf(':');
                if (colon < 1) throw new java.io.IOException("Qoder refresh: malformed response header");
                String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+"))
                    throw new java.io.IOException("Qoder refresh: malformed response header name");
                String value = line.substring(colon + 1).trim();
                // 决定边界／解码的三种头必须是纯 ASCII：obs-text 只在不相关的头值里被容忍
                // （见 httpLine）。一旦非 ASCII 进了这些值，「相等／不相等」的比较就不再可信，
                // 所以这里先拒绝，再谈 chunked／长度／编码的语义。
                if (isFramingHeader(key) && !asciiOnly(value))
                    throw new java.io.IOException("Qoder refresh: non-ASCII response framing header");
                if ("content-length".equals(key)) lengths.add(value);
                else if ("transfer-encoding".equals(key)) transfers.add(value);
                else if ("content-encoding".equals(key)) encodings.add(value);
                // 其余头（含重复的）不参与判定：不决定边界，也不决定解码。
            }
            if (code / 100 == 1) {
                if (interim >= 4) throw new java.io.IOException("Qoder refresh: too many interim responses");
                continue;
            }
            if (!lengths.isEmpty() && !transfers.isEmpty())
                throw new java.io.IOException("Qoder refresh: ambiguous response framing");
            boolean chunked = false;
            long declared = -1L;
            if (!transfers.isEmpty()) {
                if (transfers.size() > 1) throw new java.io.IOException("Qoder refresh: ambiguous response framing");
                if (!"chunked".equalsIgnoreCase(transfers.get(0)))
                    throw new java.io.IOException("Qoder refresh: unsupported transfer encoding");
                chunked = true;
            } else if (!lengths.isEmpty()) {
                String first = lengths.get(0);
                if (!first.matches("0|[1-9][0-9]{0,9}"))
                    throw new java.io.IOException("Qoder refresh: malformed content length");
                for (int i = 1; i < lengths.size(); i++)
                    if (!first.equals(lengths.get(i)))
                        throw new java.io.IOException("Qoder refresh: conflicting content length");
                declared = Long.parseLong(first);
            }
            String encoding = null;
            if (!encodings.isEmpty()) {
                String first = encodings.get(0).trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = 1; i < encodings.size(); i++)
                    if (!first.equals(encodings.get(i).trim().toLowerCase(java.util.Locale.ROOT)))
                        throw new java.io.IOException("Qoder refresh: conflicting content encoding");
                if (!first.isEmpty() && !"identity".equals(first)) encoding = first;
            }
            return new Head(code, declared, chunked, encoding);
        }
    }

    /** 读响应体：chunked 分块、Content-Length、读到 EOF 三种边界，外加 gzip 解码；一律有上限。 */
    static byte[] readBody(java.io.InputStream in, Head head, int maxBytes) throws java.io.IOException {
        if (head == null) throw new java.io.IOException("Qoder refresh: no response head");
        boolean gzip = false;
        if (head.encoding != null) {
            if ("gzip".equals(head.encoding) || "x-gzip".equals(head.encoding)) gzip = true;
            else throw new java.io.IOException("Qoder refresh: unsupported content encoding");
        }
        byte[] wire = readFramed(in, head, maxBytes);
        if (!gzip) return wire;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPInputStream unzip =
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(wire));
        byte[] buffer = new byte[2048];
        int n;
        while ((n = unzip.read(buffer)) != -1) {
            if (n == 0) continue;
            if (out.size() + n > maxBytes) throw new java.io.IOException("Qoder refresh: response body too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readFramed(java.io.InputStream in, Head head, int maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (head.chunked) {
            // 块长度行、块终止符和 trailer 共用一个有界的行预算：32 KiB 足够 ~1000 个带扩展的块，
            // 又不至于被对端用超长行拖住。数据本身另有 maxBytes 的上限。
            int[] budget = {32768};
            while (true) {
                // 块长度行可以带 chunk extension（RFC 7230 §4.1.1 的 ";name=value"）：它不影响边界，
                // 只忽略。不认扩展会让「上游已轮换、本机读不出新 token」纯损失，而上游 h1 解析器也认。
                String sizeLine = httpLine(in, budget, 256);
                int semi = sizeLine.indexOf(';');
                String hex = semi < 0 ? sizeLine : sizeLine.substring(0, semi);
                if (!hex.matches("[0-9a-fA-F]{1,8}"))
                    throw new java.io.IOException("Qoder refresh: malformed chunk size");
                if (semi >= 0 && !sizeLine.substring(semi).matches("(;[\\x20-\\x7E]*)+"))
                    throw new java.io.IOException("Qoder refresh: malformed chunk extension");
                long n = Long.parseLong(hex, 16);
                if (n == 0) { drainTrailers(in, budget); break; }
                if (n > maxBytes - out.size()) throw new java.io.IOException("Qoder refresh: response body too large");
                copyExact(in, out, (int) n);
                // 块数据后面必须是空的 CRLF 行（这是块边界，不接受任何填充）
                if (!httpLine(in, budget, 2).isEmpty())
                    throw new java.io.IOException("Qoder refresh: malformed chunk terminator");
            }
            return out.toByteArray();
        }
        if (head.contentLength >= 0) {
            if (head.contentLength > maxBytes) throw new java.io.IOException("Qoder refresh: response body too large");
            copyExact(in, out, (int) head.contentLength);
            return out.toByteArray();
        }
        byte[] buffer = new byte[2048];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n == 0) continue;
            if (out.size() + n > maxBytes) throw new java.io.IOException("Qoder refresh: response body too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** 分块的 trailer 只为把连接读到块的结尾：行数和总长度都有界，内容丢弃。 */
    private static void drainTrailers(java.io.InputStream in, int[] budget) throws java.io.IOException {
        for (int lines = 0; ; lines++) {
            if (httpLine(in, budget, 4096).isEmpty()) return;
            if (lines >= 16) throw new java.io.IOException("Qoder refresh: too many trailer lines");
        }
    }

    private static void copyExact(java.io.InputStream in, java.io.OutputStream out, int count) throws java.io.IOException {
        byte[] buffer = new byte[2048];
        int left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, Math.min(left, buffer.length));
            if (n < 0) throw new java.io.EOFException("Qoder refresh: response body truncated");
            if (n == 0) continue;
            out.write(buffer, 0, n);
            left -= n;
        }
    }

    /**
     * 只读一行 CRLF 结尾的文本；行长和总预算都有界，超了就当答复不可读。
     *
     * <p><b>容忍 obs-text（0x80-0xFF），但不容忍控制字符：</b>RFC 7230 §3.2.6 允许头值里出现
     * obs-text，真实链路上的代理／CDN 与「带中文名的 Set-Cookie、自定义 trace 头」也确实会发。
     * 这些头不决定「这个答复怎么读」，为它们判死是纯损失——上游已经轮换、本机却读不出新 token。
     * 其余控制字符（NUL 一类，以及 0x7F DEL）仍然一律拒绝：它们不是文本，容忍只会给解析留缝。
     *
     * <p>解码固定用 ISO-8859-1（一个字节一个码点，逐字节保真），所以调用方拿到的仍是原始字节的
     * 一比一映射，ASCII 判定与 token 判定不受影响。决定边界／解码的三种头另由 {@link #readHead}
     * 要求纯 ASCII（见那里的说明），非 ASCII 在那里一定被拒绝——容忍只发生在不相关的头上。
     */
    private static String httpLine(java.io.InputStream in, int[] budget, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0) throw new java.io.EOFException("Qoder refresh: response truncated");
            if (--budget[0] < 0) throw new java.io.IOException("Qoder refresh: response headers too large");
            if (b == '\n') {
                if (previous != '\r') throw new java.io.IOException("Qoder refresh: malformed line ending");
                byte[] value = line.toByteArray();
                return new String(value, 0, value.length - 1, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            if (b == 127 || (b < 32 && b != '\r' && b != '\t') || previous == '\r' || line.size() >= max)
                throw new java.io.IOException("Qoder refresh: malformed response line");
            line.write(b);
            previous = b;
        }
    }

    /** 只有这三种头决定「这个答复怎么读」：边界与解码。 */
    private static boolean isFramingHeader(String key) {
        return "content-length".equals(key) || "transfer-encoding".equals(key) || "content-encoding".equals(key);
    }

    /** 纯 ASCII（可见字符加 HTAB）判定：obs-text 与其余控制字符都不算。 */
    private static boolean asciiOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7E || (c < 0x20 && c != '\t')) return false;
        }
        return true;
    }

    // ================= 刷新入口 =================

    /**
     * Returns a new rotated candidate; caller persists it atomically before use.
     *
     * <p>生产入口：走 {@link #tlsTransport()} 的单次 TLS 传输——先建连再发请求，只发一次。
     * 失败一律是 {@link RefreshSendFailure}（IOException 子类），调用方用
     * {@link RefreshOutcome#definitelyNotSent()} 判断能否复用同一个一次性 token 重试，用
     * {@link RefreshOutcome#outcomeUnknown()} 判断这次轮换是否「可能已发生」。非 200 仍是
     * {@link QoderHttpException}（上游答过：确定已发出、确定有状态码）。
     */
    public static SignIn refreshCandidate(SignIn si) throws Exception {
        return refreshCandidateSingleShot(si, tlsTransport(), OPENAPI);
    }

    /**
     * 兼容入口：注入一个 {@link HttpURLConnection} 工厂（目录读取与既有测试在用）。
     * 语义与改动前完全一致——真实的断网／DNS／TLS 失败仍落在
     * {@link RefreshSendStage#CONNECTED}（未知），不是「没发出去」。
     */
    public static SignIn refreshCandidate(SignIn si, ConnectionFactory factory) throws Exception {
        return refreshCandidate(si, factory, OPENAPI);
    }

    /**
     * 只在传输契约验证与环回测试里使用的重载：把 openapi base 换成给定值，其余语义完全一致。
     * 包内可见——生产调用方永远走 {@link #refreshCandidate(SignIn, ConnectionFactory)}，
     * 不允许把外部可影响的 base 拼进一次性 token 的交换请求。
     */
    static SignIn refreshCandidate(SignIn si, ConnectionFactory factory, String openApiBase) throws Exception {
        if (si == null || factory == null) throw new IllegalArgumentException("Qoder refresh requires sign-in and transport");
        if (openApiBase == null || openApiBase.trim().isEmpty())
            throw new IllegalArgumentException("Qoder refresh requires an openapi base");
        String refresh = si.refresh;
        if ((refresh == null || refresh.trim().isEmpty()) && si.deviceChat) refresh = si.deviceRefresh;
        if (refresh == null || refresh.trim().isEmpty())
            throw new RefreshSendFailure("Qoder refresh token missing", "missing_refresh_token",
                    RefreshSendStage.NOT_CONNECTED, 0L, 0L, false, false, null);
        HttpURLConnection conn = null;
        RefreshTrace trace = new RefreshTrace();
        try {
            String url = openApiBase + (si.deviceChat ? "/api/v1/deviceToken/refresh" : "/api/v1/jobToken/refresh");
            byte[] body = new JSONObject().put("refresh_token", refresh).toString().getBytes("UTF-8");
            // 字节防御：请求体必须先自证是合理的一小块，再拿去碰网络。refresh token 正常只有
            // 几十到几百字节；空体和超长体只可能是配置损坏或被塞了别的东西，这种情况下
            // 连连接都不该开——NOT_CONNECTED 的判定也就仍是可信的。
            if (body.length <= 0 || body.length > MAX_REFRESH_BODY_BYTES)
                throw new RefreshSendFailure("Qoder refresh failed: bad_request_body", "bad_request_body",
                        RefreshSendStage.NOT_CONNECTED, 0L, 0L, false, false, null);
            trace.total = body.length;
            HttpURLConnection opened = factory.open(url);
            if (opened == null)
                throw new RefreshSendFailure("Qoder refresh failed: no_transport", "no_transport",
                        RefreshSendStage.NOT_CONNECTED, 0L, trace.total, false, false, null);
            conn = opened;
            conn.setRequestMethod("POST"); conn.setConnectTimeout(15000); conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true); conn.setFixedLengthStreamingMode(body.length);
            // 从这里起请求头就可能已经交给链路：先把阶段推上去再写，失败一律按「可能已执行」。
            // 真实断网 / DNS / TLS / 连接被拒就在下面这一行 explode —— 那是 CONNECTED，不是没发出去。
            trace.stage = RefreshSendStage.CONNECTED;
            java.io.OutputStream requestStream = conn.getOutputStream();
            if (requestStream == null)
                throw new RefreshSendFailure("Qoder refresh failed: no_request_stream", "no_request_stream",
                        RefreshSendStage.CONNECTED, 0L, trace.total, false, false, null);
            try (java.io.OutputStream out = new TraceOutputStream(requestStream, trace)) {
                trace.stage = RefreshSendStage.WRITING;
                out.write(body);
                out.flush();
                trace.stage = RefreshSendStage.WRITTEN;
            }
            trace.stage = RefreshSendStage.READING_STATUS;
            int status = conn.getResponseCode(); trace.answered = true;
            if (status != 200) throw new QoderHttpException(status);
            trace.stage = RefreshSendStage.READING_BODY;
            JSONObject json = parseTokenAnswer(readBounded(conn.getInputStream(), MAX_REFRESH_RESPONSE_BYTES));
            return rotated(si, json, trace);
        } catch (QoderHttpException e) { throw e; }
        catch (RefreshSendFailure e) { throw e; }
        catch (Exception e) { throw refreshRefusal(trace, e); }
        finally { if (conn != null) conn.disconnect(); }
    }

    /**
     * 单次 TLS 传输上的刷新：一次 open、一次请求行、一次请求体、一次答复，绝不重放。
     *
     * <p>阶段边界与上游 {@code openapi()} + {@code refreshJob／refreshDevice} 的调用链一致：
     * 端点 {@code /api/v1/jobToken/refresh}、{@code /api/v1/deviceToken/refresh}，body 只有
     * {@code {"refresh_token": ...}}，{@link #REFRESH_TIMEOUT_MS} 的读／建连预算（不是 socket 级硬上限，
     * 边界见该常量的说明），非 200 由调用方按状态码处理。
     *
     * <p>包内可见：生产调用方走 {@link #refreshCandidate(SignIn)}；测试注入假传输来数真实发送次数。
     */
    static SignIn refreshCandidateSingleShot(SignIn si, RefreshTransport transport, String openApiBase) throws Exception {
        if (si == null || transport == null) throw new IllegalArgumentException("Qoder refresh requires sign-in and transport");
        if (openApiBase == null || openApiBase.trim().isEmpty())
            throw new IllegalArgumentException("Qoder refresh requires an openapi base");
        String refresh = si.refresh;
        if ((refresh == null || refresh.trim().isEmpty()) && si.deviceChat) refresh = si.deviceRefresh;
        if (refresh == null || refresh.trim().isEmpty())
            throw new RefreshSendFailure("Qoder refresh token missing", "missing_refresh_token",
                    RefreshSendStage.NOT_CONNECTED, 0L, 0L, false, false, null);
        RefreshLink link = null;
        RefreshTrace trace = new RefreshTrace();
        try {
            String url = openApiBase + (si.deviceChat ? "/api/v1/deviceToken/refresh" : "/api/v1/jobToken/refresh");
            byte[] body = new JSONObject().put("refresh_token", refresh).toString().getBytes("UTF-8");
            if (body.length <= 0 || body.length > MAX_REFRESH_BODY_BYTES)
                throw new RefreshSendFailure("Qoder refresh failed: bad_request_body", "bad_request_body",
                        RefreshSendStage.NOT_CONNECTED, 0L, 0L, false, false, null);
            trace.total = body.length;
            java.net.URI target;
            try { target = new java.net.URI(url); }
            catch (java.net.URISyntaxException malformed) {
                throw new RefreshSendFailure("Qoder refresh failed: bad_request_target", "bad_request_target",
                        RefreshSendStage.NOT_CONNECTED, 0L, trace.total, false, false, malformed);
            }
            link = transport.open(target, body.length, REFRESH_TIMEOUT_MS);
            if (link == null)
                throw new RefreshSendFailure("Qoder refresh failed: no_transport", "no_transport",
                        RefreshSendStage.NOT_CONNECTED, 0L, trace.total, false, false, null);
            // 链路已建立（TCP+TLS，含主机名校验）：请求行还没写，但任何失败都不再能证明「没发出去」。
            trace.stage = RefreshSendStage.CONNECTED;
            java.io.OutputStream request = link.request();
            if (request == null)
                throw new RefreshSendFailure("Qoder refresh failed: no_request_stream", "no_request_stream",
                        RefreshSendStage.CONNECTED, 0L, trace.total, false, false, null);
            trace.stage = RefreshSendStage.WRITING;
            // 按小块写：记账尽量贴近真实进度；只写、只 flush，绝不 close（close 会关掉整条链路）。
            java.io.OutputStream sink = new TraceOutputStream(request, trace);
            for (int off = 0; off < body.length; off += 512)
                sink.write(body, off, Math.min(512, body.length - off));
            request.flush();
            // 请求体已经交给链路：从这里起读状态行，读不到就是「可能已执行」，不是「没发出去」。
            // （这条链路上没有「体写完到读状态之间」的独立步骤，所以 WRITTEN 不会在这里停留。）
            trace.stage = RefreshSendStage.READING_STATUS;
            int status = link.statusLine(); trace.answered = true;
            if (status != 200) throw new QoderHttpException(status);
            trace.stage = RefreshSendStage.READING_BODY;
            JSONObject json = parseTokenAnswer(new String(link.body(MAX_REFRESH_RESPONSE_BYTES), "UTF-8"));
            return rotated(si, json, trace);
        } catch (QoderHttpException e) { throw e; }
        catch (RefreshSendFailure e) { throw e; }
        catch (Exception e) { throw refreshRefusal(trace, e); }
        finally { if (link != null) try { link.close(); } catch (Exception ignored) { } }
    }

    /**
     * 换到的这一对 token：两条传输共用的成功路径，逐字对齐上游 refreshJob／refreshDevice 的字段读取
     * （device 站点没有 token 时退回 {@code device_token}），并把旧账号原样留在原对象上。
     */
    private static SignIn rotated(SignIn si, JSONObject json, RefreshTrace trace) throws RefreshSendFailure {
        Object t = json.opt("token"), r = json.opt("refresh_token");
        if (si.deviceChat && (!(t instanceof String) || ((String)t).trim().isEmpty())) t = json.opt("device_token");
        if (!(t instanceof String) || ((String)t).trim().isEmpty() || !(r instanceof String) || ((String)r).trim().isEmpty())
            throw new RefreshSendFailure("Qoder refresh failed: incomplete_token_pair", "incomplete_token_pair",
                    trace.stage, trace.written, trace.total, false, trace.answered, null);
        SignIn next = new SignIn();
        next.access = (String)t; next.refresh = (String)r; next.deviceChat = si.deviceChat;
        next.deviceToken = si.deviceToken; next.deviceRefresh = si.deviceRefresh;
        if (si.deviceChat) { next.deviceToken = next.access; next.deviceRefresh = next.refresh; }
        next.uid = si.uid; next.email = si.email; next.name = si.name; next.machineId = si.machineId;
        next.expiresAt = refreshExpiry(json, si.deviceChat);
        return next;
    }

    /**
     * 解析上游给出的这一对 token。
     *
     * <p><b>解析器的异常绝不作为 cause 外传。</b>JSON 解析器的报文是「它正在读的正文」派生的
     * （不同的 org.json 实现、不同的 Android 版本都可能把被解析的片段或键值写进 message），
     * 而这段正文正是新签发的一对 token。把它挂成 cause，就等于让一次失败把明文凭据带进日志、
     * 带进任何打印堆栈的地方。所以这里只抛一个不带正文的安全异常：诊断只留阶段与类别。
     *
     * <p>代价是丢掉解析器原始的定位信息；这比不上把凭据写进日志严重。上游此时已经答了 200，
     * 所以这次交换按「已答复、已花掉」处理（不是「没发出去」）。
     */
    private static JSONObject parseTokenAnswer(String text) throws java.io.IOException {
        try {
            return new JSONObject(text);
        } catch (Exception malformed) {
            // 故意不复用 malformed：它的 message 可能夹带被解析的原文。
            throw new MalformedAnswer();
        }
    }

    /** 答复不是一对 JSON token；故意的无正文异常（见 {@link #parseTokenAnswer}）。 */
    static final class MalformedAnswer extends java.io.IOException {
        MalformedAnswer() { super("Qoder refresh: the answer is not a JSON token pair"); }
    }

    /** 一次性 token 交换请求体的上限：正常只有几百字节，超出说明配置已经不对劲。 */
    static final int MAX_REFRESH_BODY_BYTES = 64 * 1024;
    /** 一次性 token 交换响应体的上限（沿用原来的 64 KiB）。 */
    static final int MAX_REFRESH_RESPONSE_BYTES = 64 * 1024;

    public static org.json.JSONArray fetchModels(SignIn si, JSONObject metaOut) {
        try {
            Catalog catalog = readCatalog(si);
            if (metaOut != null) {
                java.util.Iterator<String> keys = catalog.meta.keys();
                while (keys.hasNext()) { String key = keys.next(); metaOut.put(key, catalog.meta.get(key)); }
            }
            return catalog.models;
        } catch (Exception ignored) { return new org.json.JSONArray(); }
    }
}
