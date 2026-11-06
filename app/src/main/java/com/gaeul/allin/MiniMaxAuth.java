package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * MiniMax Code（mcode）认证与调用支撑。
 *
 * 移植自 upstream @magpie-community/opencode-minimax-auth（packages/minimax）。
 *
 * 登录：MiniMax Code 自己的 OAuth 设备流（PKCE S256，client `mcode-public`）
 *   1. POST {account}/oauth2/device/code  （form）
 *      {client_id: mcode-public, scope: agent.default, audience: agent-backend,
 *       code_challenge: <S256(verifier)>, code_challenge_method: S256}
 *      → user_code / device_code / verification_uri / expires_in / interval
 *   2. 用户在浏览器确认 user_code
 *   3. POST {account}/oauth2/token（form）
 *      {grant_type: urn:ietf:params:oauth:grant-type:device_code, device_code,
 *       client_id: mcode-public, code_verifier}
 *      → access_token / refresh_token / expires_in；pending 时 status=pending
 *
 * ⚠️ 刷新 token 是一次性的：同一个 refresh_token 只能用一次，第二次会
 * invalid_grant 把账号登出。所以刷新后必须立刻落盘（saveConfig），
 * 并且同一账号的刷新要串行（本类用静态锁）。
 *
 * 聊天：Anthropic Messages，端点 {llm}/mavis/api/v1/llm/v1/messages
 *   {llm} 国内 = https://agent.minimax.cn，国际 = https://agent.minimax.io
 *   必需头：Authorization Bearer <access>、x-api-key: sk-xxx（占位）、
 *          User-Agent: MiniMaxAgent、X-Mavis-Agent-Id: main、
 *          X-Mavis-Timezone-Offset、X-Mavis-Session-Id
 *
 * 模型目录：GET {llm}/mavis/api/v1/models?region=cn&buildEnv=prod
 *   返回 {providers:[{providerId:"minimax", config:{models:{<id>:{...}}, model_order:[...]}}]}
 */
public class MiniMaxAuth {

    public static final String PROVIDER = "minimax-code";
    public static final String SITE = "cn";
    public static final String LANG = "zh";
    public static final String ACCOUNT = "https://account.minimax.cn";
    public static final String LLM = "https://agent.minimax.cn";
    public static final String API = LLM + "/mavis/api/v1/llm/v1";
    public static final String CLIENT_ID = "mcode-public";
    public static final String SCOPE = "agent.default";
    public static final String AUDIENCE = "agent-backend";
    public static final String GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code";
    public static final String PLACEHOLDER_KEY = "sk-xxx";

    /** 提前多久刷新（插件 LEAD_MS = 10 分钟） */
    public static final long LEAD_MS = 10L * 60L * 1000L;

    public static final String[] DEFAULT_MODELS = new String[]{
            "MiniMax-M3.1-Flash-Preview",
            "MiniMax-M3.1-Flash",
            "MiniMax-M3",
            "MiniMax-M2.7",
            "MiniMax-M2.7-highspeed"
    };

    private static final Object REFRESH_LOCK = new Object();

    public static class Flow {
        public String flowId;        // device_code；byUser 变体下等于 user_code
        public String userCode;
        public String verifier;
        public String verificationUri;
        public String verificationUriComplete;
        public boolean pollByUser;
        public long deadline;
        public long intervalMs;
        public long createdAt;
    }

    public static class SignIn {
        public String access = "";
        public String refresh = "";
        public long expires = 0L;
        public String userId = "";
    }

    public static class PollResult {
        public String status;  // pending / slow_down / ready / failed
        public String error;
        public SignIn signIn;
    }

    // ===== 小工具：base64url / sha256 / 表单 POST =====

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    public static String b64url(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
              .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]);
        }
        return sb.toString();
    }

    private static byte[] b64urlDecode(String s) {
        if (s == null) return new byte[0];
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        byte[] out = new byte[end * 3 / 4 + 3];
        int buf = 0, bits = 0, pos = 0;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '-' || c == '+') v = 62;
            else if (c == '_' || c == '/') v = 63;
            else continue;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[pos++] = (byte) ((buf >> bits) & 0xff);
            }
        }
        byte[] trim = new byte[pos];
        System.arraycopy(out, 0, trim, 0, pos);
        return trim;
    }

    private static String sha256b64url(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return b64url(md.digest(text.getBytes("US-ASCII")));
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection conn = NetworkPolicy.openHttp(urlStr);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    /** 表单 POST；返回 {code, body(JSONObject|null), raw, error} */
    private static JSONObject postForm(String url, String form, int[] codeOut, String[] rawOut) throws Exception {
        return postForm(url,form,codeOut,rawOut,false);
    }
    private static JSONObject postForm(String url, String form, int[] codeOut, String[] rawOut, boolean oneShot) throws Exception {
        HttpURLConnection conn = open(url);
        if(oneShot) { conn.setInstanceFollowRedirects(false); conn.setFixedLengthStreamingMode(form.getBytes("UTF-8").length); }
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        OutputStream os = conn.getOutputStream();
        os.write(form.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String raw = read(is);
        if (codeOut != null && codeOut.length > 0) codeOut[0] = code;
        if (rawOut != null && rawOut.length > 0) rawOut[0] = raw;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=')
              .append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    // ===== 设备流 =====

    public static Flow initDeviceFlow() throws Exception {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        String verifier = b64url(seed);
        String challenge = sha256b64url(verifier);

        int[] code = new int[]{0};
        String[] raw = new String[]{""};
        JSONObject j = postForm(ACCOUNT + "/oauth2/device/code",
                form("client_id", CLIENT_ID, "scope", SCOPE, "audience", AUDIENCE,
                        "code_challenge", challenge, "code_challenge_method", "S256"),
                code, raw);
        if (j == null) {
            throw new Exception("MiniMax 设备码返回非 JSON (HTTP " + code[0] + "): " + raw[0].trim());
        }
        String err = j.optString("error", "");
        if (code[0] < 200 || code[0] >= 400 || !err.isEmpty()) {
            throw new Exception("MiniMax 设备码申请失败 (HTTP " + code[0] + "): "
                    + (err.isEmpty() ? raw[0].trim() : err));
        }

        String userCode = j.optString("user_code", "");
        String deviceCode = j.optString("device_code", "");
        String uri = j.optString("verification_uri", "");
        if (uri.isEmpty()) uri = j.optString("verification_url", "");
        long expiredIn = j.optLong("expired_in", 0L);
        long expiresIn = j.optLong("expires_in", 0L);
        long interval = j.optLong("interval", 5L);

        // MiniMax 自己的变体：没有 device_code，只有 user_code（轮询时用 user_code）
        boolean byUser = deviceCode.isEmpty() && !userCode.isEmpty() && expiredIn > 0;
        if (userCode.isEmpty() || uri.isEmpty() || (deviceCode.isEmpty() && !byUser)) {
            throw new Exception("MiniMax 设备码返回不完整: " + raw[0].trim());
        }

        Flow f = new Flow();
        f.userCode = userCode;
        f.verifier = verifier;
        f.pollByUser = byUser;
        f.flowId = byUser ? userCode : deviceCode;
        f.verificationUri = uri;
        String complete = j.optString("verification_uri_complete", "");
        f.verificationUriComplete = complete.isEmpty() ? uri : complete;
        long secs = expiresIn > 0 ? expiresIn : 600L;
        f.deadline = System.currentTimeMillis() + secs * 1000L;
        f.intervalMs = byUser ? Math.max(1000L, interval) : Math.max(1000L, interval * 1000L);
        f.createdAt = System.currentTimeMillis();
        return f;
    }

    public static PollResult pollDeviceFlow(Flow f) {
        PollResult pr = new PollResult();
        pr.status = "pending";
        if (f == null) {
            pr.status = "failed";
            pr.error = "登录流程已失效，请重新发起";
            return pr;
        }
        if (System.currentTimeMillis() > f.deadline) {
            pr.status = "failed";
            pr.error = "登录超时，请重新发起";
            return pr;
        }
        try {
            String formBody = f.pollByUser
                    ? form("grant_type", GRANT_DEVICE, "user_code", f.userCode,
                           "client_id", CLIENT_ID, "code_verifier", f.verifier)
                    : form("grant_type", GRANT_DEVICE, "device_code", f.flowId,
                           "client_id", CLIENT_ID, "code_verifier", f.verifier);
            int[] code = new int[]{0};
            String[] raw = new String[]{""};
            JSONObject j = postForm(ACCOUNT + "/oauth2/token", formBody, code, raw);
            if (j == null) {
                pr.status = "pending";
                pr.error = "MiniMax 轮询返回非 JSON (HTTP " + code[0] + ")";
                return pr;
            }
            String err = j.optString("error", "");
            String status = j.optString("status", "");
            String access = j.optString("access_token", "");

            if (!access.isEmpty() && err.isEmpty()) {
                long expiresIn = j.optLong("expires_in", 0L);
                SignIn si = new SignIn();
                si.access = access;
                si.refresh = j.optString("refresh_token", "");
                si.expires = expiresIn > 0
                        ? System.currentTimeMillis() + expiresIn * 1000L
                        : System.currentTimeMillis() + 3600L * 1000L;
                si.userId = jwtClaim(access, "email");
                if (si.userId.isEmpty()) si.userId = jwtClaim(access, "name");
                if (si.userId.isEmpty()) si.userId = jwtClaim(access, "sub");
                pr.status = "ready";
                pr.signIn = si;
                return pr;
            }

            if ("authorization_pending".equals(err) || "pending".equals(status)) {
                pr.status = "pending";
                return pr;
            }
            if ("slow_down".equals(err) || "slow_down".equals(status)) {
                f.intervalMs = f.intervalMs + 5000L;
                pr.status = "slow_down";
                return pr;
            }
            if ("access_denied".equals(err) || "denied".equals(status)) {
                pr.status = "failed";
                pr.error = "MiniMax 授权被拒绝";
                return pr;
            }
            if ("expired_token".equals(err) || "expired".equals(status)) {
                pr.status = "failed";
                pr.error = "登录码已过期，请重新发起";
                return pr;
            }
            pr.status = "pending";
            pr.error = err;
            return pr;
        } catch (Exception e) {
            pr.status = "pending";   // 单次网络抖动不算失败
            pr.error = e.getMessage() == null ? "轮询失败" : e.getMessage();
            return pr;
        }
    }

    /** 取 JWT 里的某个 claim（失败返回空串），仅用于给账号起名 */
    public static String jwtClaim(String token, String key) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return "";
            String json = new String(b64urlDecode(parts[1]), "UTF-8");
            JSONObject j = new JSONObject(json);
            String v = j.optString(key, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** 用 refresh_token 换新 access；返回 null 表示被登出（invalid_grant/400）或失败 */
    public static SignIn refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return null;
        try {
            int[] code = new int[]{0};
            String[] raw = new String[]{""};
            JSONObject j = postForm(ACCOUNT + "/oauth2/token",
                    form("grant_type", "refresh_token", "refresh_token", refreshToken,
                            "client_id", CLIENT_ID, "scope", SCOPE, "audience", AUDIENCE),
                    code, raw, true);
            if (j == null) return null;
            String access = j.optString("access_token", "");
            if (access.isEmpty()) return null;
            long expiresIn = j.optLong("expires_in", 0L);
            SignIn si = new SignIn();
            si.access = access;
            String nr = j.optString("refresh_token", "");
            si.refresh = nr.isEmpty() ? refreshToken : nr;
            si.expires = expiresIn > 0
                    ? System.currentTimeMillis() + expiresIn * 1000L
                    : System.currentTimeMillis() + 3600L * 1000L;
            return si;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保证账号手里有可用 access：临期（10 分钟）就用 refresh 换，换了就写回账号对象。
     * 返回 true 表示账号对象被改过（调用方要 saveConfig —— refresh token 是一次性的）。
     */
    public static boolean ensureToken(JSONObject acc) {
        if (acc == null) return false;
        synchronized (REFRESH_LOCK) {
            String access = acc.optString("access", acc.optString("token", ""));
            long exp = acc.optLong("expires", 0L);
            long now = System.currentTimeMillis();
            if (!access.isEmpty() && exp > now + LEAD_MS) return false;
            String rt = acc.optString("refresh", "");
            if (rt.isEmpty()) return false;
            SignIn si = refresh(rt);
            if (si == null || si.access.isEmpty()) return false;
            try {
                acc.put("access", si.access);
                acc.put("token", si.access);
                acc.put("refresh", si.refresh);
                acc.put("expires", si.expires);
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    /** 强制刷新（上游 401 时用，不看时钟）：成功写回账号对象并返回 true */
    public static boolean forceRefresh(JSONObject acc) {
        if (acc == null) return false;
        synchronized (REFRESH_LOCK) {
            String rt = acc.optString("refresh", "");
            if (rt.isEmpty()) return false;
            SignIn si = refresh(rt);
            if (si == null || si.access.isEmpty()) return false;
            try {
                acc.put("access", si.access);
                acc.put("token", si.access);
                acc.put("refresh", si.refresh);
                acc.put("expires", si.expires);
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    // ===== MiniMax Code 的「签名账号 API」：每日签到、user/info =====
    // 这组接口不是 Bearer 就够：网关校验 yy 与 x-signature 两个 MD5 签名，
    // 还要把 mcode 客户端的一整套 query（device_platform/biz_id/app_id/...）带上。

    /** MiniMax Code 的签名密钥（来自客户端二进制，见插件 SIGN_SECRET） */
    public static final String SIGN_SECRET = "I*7Cf%WZ#S&%1RlZJ&C2";
    /** 签名账号 API 的 host（国内站 agent.minimaxi.com，不是聊天用的 agent.minimax.cn） */
    public static final String AGENT = "https://agent.minimaxi.com";
    /** 签到请求要按桌面端 0.6.12 的形态带上这几个 query */
    public static final String CHECKIN_QUERY = "device_platform=web&is_desktop=1&desktop_version=0.6.12";

    private static String md5hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 与 JS encodeURIComponent 完全一致（unreserved = A-Za-z0-9 - _ . ! ~ * ' ( )） */
    public static String encodeURIComponent(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
                    || c == '*' || c == '\'' || c == '(' || c == ')') {
                sb.append(c);
            } else if (c < 0x80) {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                try {
                    for (byte b : String.valueOf(c).getBytes("UTF-8")) {
                        sb.append('%').append(String.format("%02X", b & 0xff));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }

    /** 拼出签到/账号接口的完整 URL（含 mcode 的固定参数与签名时间戳） */
    private static String signedUrl(String path, String userID) {
        long now = System.currentTimeMillis();
        int tzSec = java.util.TimeZone.getDefault().getOffset(now) / 1000;
        StringBuilder q = new StringBuilder();
        q.append("device_platform=web").append("&biz_id=3").append("&app_id=3001")
         .append("&version_code=22201").append("&is_desktop=1").append("&desktop_version=0.6.12")
         .append("&unix=").append(now)
         .append("&timezone_offset=").append(tzSec)
         .append("&sys_language=").append(LANG)
         .append("&lang=").append(LANG)
         .append("&device_id=0")
         .append("&os_name=android")
         .append("&browser_name=mcode")
         .append("&user_id=").append(userID == null || userID.trim().isEmpty() ? "0" : userID.trim())
         .append("&client=mcode");
        return AGENT + path + "?" + q;
    }

    private static void applySignedHeaders(HttpURLConnection conn, String pathWithQuery, String access,
                                           String userID, String jsonBody, long now) {
        long ts = now / 1000;
        String at = pathWithQuery;
        String bodyForSign = jsonBody == null ? "{}" : jsonBody;
        String yy = md5hex(encodeURIComponent(at) + "_" + bodyForSign + md5hex(String.valueOf(now)) + "ooui");
        String sig = jsonBody == null
                ? md5hex(ts + SIGN_SECRET)
                : md5hex(ts + SIGN_SECRET + jsonBody);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "MiniMaxCode");
        conn.setRequestProperty("Authorization", "Bearer " + access);
        conn.setRequestProperty("yy", yy);
        conn.setRequestProperty("x-timestamp", String.valueOf(ts));
        conn.setRequestProperty("x-signature", sig);
    }

    /** 签名 GET：返回 {code, body} */
    private static JSONObject signedGet(String path, String access, String userID) throws Exception {
        long now = System.currentTimeMillis();
        String url = signedUrl(path, userID);
        String pathWithQuery = url.substring(AGENT.length());
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        applySignedHeaders(conn, pathWithQuery, access, userID, null, now);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String raw = read(is);
        JSONObject out = new JSONObject();
        out.put("_status", code);
        try {
            out.put("_body", new JSONObject(raw));
        } catch (Exception e) {
            out.put("_raw", raw);
        }
        return out;
    }

    /** 签名 POST（body 参与签名） */
    private static JSONObject signedPost(String path, String access, String userID, String jsonBody) throws Exception {
        long now = System.currentTimeMillis();
        String url = signedUrl(path, userID);
        String pathWithQuery = url.substring(AGENT.length());
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        applySignedHeaders(conn, pathWithQuery, access, userID, jsonBody, now);
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String raw = read(is);
        JSONObject out = new JSONObject();
        out.put("_status", code);
        try {
            out.put("_body", new JSONObject(raw));
        } catch (Exception e) {
            out.put("_raw", raw);
        }
        return out;
    }

    /** 读账号真实 user id（签到必须以它作 user_id） */
    public static String realUserID(String access) {
        try {
            JSONObject r = signedGet("/v1/api/user/info", access, "");
            JSONObject body = r.optJSONObject("_body");
            if (body == null) return "";
            JSONObject data = body.optJSONObject("data");
            JSONObject u = data == null ? null : data.optJSONObject("userInfo");
            if (u == null && data != null) u = data.optJSONObject("user_info");
            if (u == null) u = body.optJSONObject("userInfo");
            if (u == null) return "";
            String id = u.optString("realUserID", "");
            if (id.isEmpty()) id = u.optString("real_user_id", "");
            return id;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 每日签到：读一周面板（status 1 未开始 / 2 可领 / 3 已领 / 4 不可领），今天可领就领一次。
     * 返回 {ok, outcome(claimed|done|inactive|ineligible|failed), credit, streak, error, status}
     */
    public static JSONObject checkin(String access, String userID) {
        JSONObject out = new JSONObject();
        try {
            String uid = userID == null ? "" : userID.trim();
            if (uid.isEmpty()) uid = realUserID(access);
            if (uid.isEmpty()) {
                out.put("ok", false);
                out.put("error", "读不到 MiniMax 账号的 realUserID，无法签到");
                return out;
            }
            JSONObject st = signedGet("/minimax-cloud/api/v1/signin/status", access, uid);
            JSONObject body = st.optJSONObject("_body");
            out.put("status", st.optInt("_status", 0));
            if (body == null) {
                out.put("ok", false);
                out.put("error", "签到面板返回非 JSON: " + st.optString("_raw", ""));
                return out;
            }
            JSONObject data = body.optJSONObject("data");
            JSONArray days = data == null ? null : data.optJSONArray("days");
            if (days == null || days.length() == 0) {
                out.put("ok", false);
                out.put("error", "签到面板里没有 days 字段: " + body.toString());
                return out;
            }
            JSONObject today = null;
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d != null && d.optBoolean("is_today", false)) {
                    today = d;
                    break;
                }
            }
            if (today == null) {
                out.put("ok", true);
                out.put("outcome", "inactive");
                return out;
            }
            int dstatus = today.optInt("status", 0);
            out.put("dayStatus", dstatus);
            if (dstatus == 3) {
                out.put("ok", true);
                out.put("outcome", "done");
                out.put("credit", today.optInt("points", 0));
                out.put("uid", uid);
                return out;
            }
            if (dstatus == 4) {
                out.put("ok", true);
                out.put("outcome", "ineligible");
                out.put("uid", uid);
                return out;
            }
            if (dstatus != 2) {
                out.put("ok", true);
                out.put("outcome", "inactive");
                out.put("uid", uid);
                return out;
            }
            JSONObject claim = signedPost("/minimax-cloud/api/v1/signin/claim", access, uid, "{}");
            JSONObject cb = claim.optJSONObject("_body");
            out.put("claimStatus", claim.optInt("_status", 0));
            if (cb == null) {
                out.put("ok", false);
                out.put("error", "领取返回非 JSON: " + claim.optString("_raw", ""));
                return out;
            }
            JSONObject cd = cb.optJSONObject("data");
            int r = cd == null ? 0 : cd.optInt("claim_result", 0);
            out.put("ok", true);
            out.put("credit", cd == null ? 0 : cd.optInt("points", 0));
            out.put("outcome", r == 1 ? "claimed" : (r == 2 ? "done" : "failed"));
            if (r != 1 && r != 2) out.put("error", "未知 claim_result: " + (cd == null ? "null" : cd.toString()));
            out.put("uid", uid);
            return out;
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage() == null ? "签到失败" : e.getMessage());
            } catch (Exception ignored) {
            }
            return out;
        }
    }

    /** 模型目录：{llm}/mavis/api/v1/models?region=cn&buildEnv=prod */
    public static java.util.List<String> models(String access) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        HttpURLConnection conn = open(LLM + "/mavis/api/v1/models?region=" + SITE + "&buildEnv=prod");
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (access != null && !access.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + access);
        }
        int code = conn.getResponseCode();
        String raw;
        if (code == 401 || code == 403) {
            // 无凭据时上游也会给一份公开目录，按插件语义再来一次
            HttpURLConnection anon = open(LLM + "/mavis/api/v1/models?region=" + SITE + "&buildEnv=prod");
            anon.setRequestMethod("GET");
            anon.setRequestProperty("Accept", "application/json");
            code = anon.getResponseCode();
            InputStream is2 = (code >= 200 && code < 400) ? anon.getInputStream() : anon.getErrorStream();
            raw = read(is2);
        } else {
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            raw = read(is);
        }
        if (code < 200 || code >= 400) {
            throw new Exception("MiniMax 模型目录 HTTP " + code + ": " + raw.trim());
        }
        JSONObject j = new JSONObject(raw);
        JSONArray provs = j.optJSONArray("providers");
        for (int i = 0; provs != null && i < provs.length(); i++) {
            JSONObject p = provs.optJSONObject(i);
            if (p == null || !"minimax".equals(p.optString("providerId", ""))) continue;
            JSONObject cfg = p.optJSONObject("config");
            JSONObject ms = cfg == null ? null : cfg.optJSONObject("models");
            if (ms == null) continue;
            JSONArray order = cfg.optJSONArray("model_order");
            for (int k = 0; order != null && k < order.length(); k++) {
                String id = order.optString(k, "");
                if (!id.isEmpty() && ms.has(id) && !out.contains(id)) out.add(id);
            }
            java.util.Iterator<String> it = ms.keys();
            while (it.hasNext()) {
                String id = it.next();
                if (!id.trim().isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    /** 聊天必需头（Authorization 由调用方加） */
    public static java.util.Map<String, String> chatHeaders(String sessionId) {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<String, String>();
        h.put("Accept", "text/event-stream, application/json");
        h.put("x-api-key", PLACEHOLDER_KEY);
        h.put("User-Agent", "MiniMaxAgent");
        h.put("X-Mavis-Agent-Id", "main");
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        int offsetSec = (tz.getOffset(System.currentTimeMillis())) / 1000;
        h.put("X-Mavis-Timezone-Offset", String.valueOf(offsetSec));
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "magpie-" + Long.toHexString(System.currentTimeMillis());
        }
        h.put("X-Mavis-Session-Id", sessionId);
        h.put("anthropic-version", "2023-06-01");
        return h;
    }
}
