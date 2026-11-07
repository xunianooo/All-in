package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Cline (cline.bot) 认证与协议调用。
 *
 * 移植自 upstream opencode-cline-auth (packages/cline)。
 * 支持两种认证途径：
 * 1. WorkOS 设备流（Device Code Flow）：
 *    - 向 WorkOS 申请 device_code 与 user_code，在浏览器中打开授权页；
 *    - 轮询 WorkOS 拿 access_token / refresh_token；
 *    - 在 Cline 的 /api/v1/auth/register 端点换取账号的真实令牌对 (access + refresh)；
 *    - 刷新走 /api/v1/auth/refresh，自动续期。
 * 2. Cline API Key：
 *    - app.cline.bot 生成的个人 Key，作为普通 Bearer Key 发送。
 *
 * 模型列表：
 * - 推荐模型 /ai/cline/recommended-models 与云端全量目录 /ai/cline/models 均为公开免登录接口；
 * - 包含 free 免费模型、clinePass 专属模型与按用量计费模型。
 */
public class ClineAuth {

    public static final String PROVIDER = "cline";
    public static final String API = "https://api.cline.bot/api/v1";

    public static final String WORKOS_CLIENT = "client_01K3A541FN8TA3EPPHTD2325AR";
    public static final String WORKOS_DEVICE = "https://api.workos.com/user_management/authorize/device";
    public static final String WORKOS_TOKEN = "https://api.workos.com/user_management/authenticate";
    public static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    public static final String WORKOS_PREFIX = "workos:";

    public static final String CLIENT_TYPE = "cline-cli";
    public static final String CLIENT_VERSION = "3.0.68";
    public static final String CLIENT_PLATFORM = "cli";
    public static final String CLIENT_CORE = "0.0.90";

    // 内置默认推荐模型快照（网络未同步或离线时的兜底）
    public static final String[] DEFAULT_MODELS = new String[]{
            "anthropic/claude-sonnet-5.5",
            "anthropic/claude-opus-5.5",
            "anthropic/claude-sonnet-5",
            "openai/gpt-6-astra",
            "openai/gpt-6.1-sol",
            "spacexai/grok-4.7",
            "moonshotai/kimi-k3",
            "cline-free/deepseek-v4.1-flash",
            "stealth/space-bunny-alpha",
            "cline-free/mimo-v2.6-flash",
            "cline-free/muse-spark-1.3-contributor",
            "cline-pass/deepseek-v4.1-flash-1017",
            "cline-pass/deepseek-v4.1-flash",
            "cline-pass/mimo-v2.6-flash",
            "cline-pass/mimo-v2.6-pro",
            "cline-pass/glm-5.3",
            "cline-pass/deepseek-v4-pro",
            "cline-pass/qwen3.8-max",
            "cline-pass/muse-spark-1.3-contributor",
            "cline-pass/kimi-k3",
            "cline-pass/glm-5.3-flash",
            "cline-pass/qwen3.7-max",
            "cline-pass/qwen3.7-plus",
            "cline-pass/minimax-m3",
            "cline-pass/mimo-v2.5-pro",
            "cline-pass/mimo-v2.5"
    };

    public static HttpURLConnection openHttp(String urlStr) throws Exception {
        return NetworkPolicy.openHttp(urlStr);
    }

    public static Map<String, String> clientHeaders(String taskId) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("HTTP-Referer", "https://cline.bot");
        h.put("X-Title", "Cline");
        h.put("User-Agent", "Cline/" + CLIENT_VERSION);
        h.put("X-IS-MULTIROOT", "false");
        h.put("X-CLIENT-TYPE", CLIENT_TYPE);
        h.put("X-CLIENT-VERSION", CLIENT_VERSION);
        h.put("X-PLATFORM", CLIENT_PLATFORM);
        h.put("X-PLATFORM-VERSION", CLIENT_VERSION);
        h.put("X-CORE-VERSION", CLIENT_CORE);
        if (taskId != null && !taskId.isEmpty()) {
            h.put("X-Task-ID", taskId);
        }
        return h;
    }

    public static class Flow {
        public String flowId;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public long expiresIn;
        public long intervalMs;
        public long createdAt;
    }

    public static class SignIn {
        public String access;
        public String refresh;
        public long expires;
        public String uid;
        public String email;
        public String name;
        public String accountId;
    }

    public static class PollResult {
        public String status; // pending, slow_down, ready, failed
        public String error;
        public SignIn signIn;
    }

    /**
     * 1. 发起 WorkOS 设备流授权
     */
    public static Flow initDeviceFlow() throws Exception {
        HttpURLConnection conn = openHttp(WORKOS_DEVICE);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        String postBody = "client_id=" + URLEncoder.encode(WORKOS_CLIENT, "UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        if (code < 200 || code >= 400) {
            throw new Exception("WorkOS device code request failed (HTTP " + code + ")");
        }

        JSONObject j = new JSONObject(body);
        Flow f = new Flow();
        f.flowId = j.optString("device_code", "");
        f.userCode = j.optString("user_code", "");
        f.verificationUri = j.optString("verification_uri", "");
        f.verificationUriComplete = j.optString("verification_uri_complete", f.verificationUri);
        if (f.verificationUriComplete.isEmpty()) {
            f.verificationUriComplete = "https://app.cline.bot";
        }
        f.expiresIn = j.optLong("expires_in", 300);
        f.intervalMs = Math.max(1, j.optLong("interval", 5)) * 1000L;
        f.createdAt = System.currentTimeMillis();

        if (f.flowId.isEmpty()) {
            throw new Exception("WorkOS returned incomplete device code response");
        }
        return f;
    }

    /**
     * 2. 轮询 WorkOS 授权结果；用户通过后在 Cline /api/v1/auth/register 换取真实凭据
     */
    public static PollResult pollDeviceFlow(String deviceCode) throws Exception {
        PollResult pr = new PollResult();

        HttpURLConnection conn = openHttp(WORKOS_TOKEN);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        String postBody = "grant_type=" + URLEncoder.encode(DEVICE_GRANT, "UTF-8")
                + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                + "&client_id=" + URLEncoder.encode(WORKOS_CLIENT, "UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);

        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (Exception e) {
            pr.status = "failed";
            pr.error = "WorkOS returned non-JSON response (HTTP " + code + ")";
            return pr;
        }

        String error = j.optString("error", "");
        if ("authorization_pending".equals(error)) {
            pr.status = "pending";
            return pr;
        }
        if ("slow_down".equals(error)) {
            pr.status = "slow_down";
            return pr;
        }
        if (!error.isEmpty()) {
            pr.status = "failed";
            pr.error = "WorkOS authorization rejected";
            return pr;
        }

        String workosAccess = j.optString("access_token", "");
        String workosRefresh = j.optString("refresh_token", "");
        if (workosAccess.isEmpty()) {
            pr.status = "failed";
            pr.error = "WorkOS returned no access_token";
            return pr;
        }

        // 3. 在 Cline register 端点交换账号真实 token
        SignIn si = registerCline(workosAccess, workosRefresh);
        pr.status = "ready";
        pr.signIn = si;
        return pr;
    }

    /**
     * 向 Cline 注册 WorkOS token，获得 Cline 本地令牌对
     */
    private static SignIn registerCline(String workosAccess, String workosRefresh) throws Exception {
        HttpURLConnection conn = openHttp(API + "/auth/register");
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        Map<String, String> clH = clientHeaders(null);
        for (Map.Entry<String, String> e : clH.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        JSONObject req = new JSONObject();
        req.put("accessToken", workosAccess);
        req.put("refreshToken", workosRefresh != null ? workosRefresh : "");
        OutputStream os = conn.getOutputStream();
        os.write(req.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        if (code < 200 || code >= 400) {
            throw new Exception("Cline register failed (HTTP " + code + ")");
        }

        JSONObject res = new JSONObject(body);
        JSONObject data = res.optJSONObject("data");
        if (data == null) data = res;

        return parseAuth(data, workosRefresh);
    }

    /**
     * 刷新 Cline access token
     */
    public static SignIn refreshToken(String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new Exception("Missing refresh token");
        }
        HttpURLConnection conn = openHttp(API + "/auth/refresh");
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        Map<String, String> clH = clientHeaders(null);
        for (Map.Entry<String, String> e : clH.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        JSONObject req = new JSONObject();
        req.put("refreshToken", refreshToken);
        req.put("grantType", "refresh_token");
        OutputStream os = conn.getOutputStream();
        os.write(req.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        if (code < 200 || code >= 400) {
            throw new Exception("Cline token refresh failed (HTTP " + code + ")");
        }

        JSONObject res = new JSONObject(body);
        JSONObject data = res.optJSONObject("data");
        if (data == null) data = res;

        return parseAuth(data, refreshToken);
    }

    /**
     * 更新已有账号对象的 access/refresh/expires
     */
    interface RefreshTransport { SignIn refresh(String token) throws Exception; }
    private static final long FRESH_MARGIN_MS = 120000L;

    /** Immutable pre-refresh credentials plus result, for a caller-managed config CAS.
     * The caller must validate account identity/enabled state and persist atomically. */
    public static final class RefreshCandidate {
        public final String token, access, refresh;
        public final long expires;
        public final SignIn next;
        private RefreshCandidate(JSONObject account, SignIn next) {
            token = account.optString("token", ""); access = account.optString("access", "");
            refresh = account.optString("refresh", ""); expires = account.optLong("expires", 0L);
            this.next = next;
        }
        public boolean matches(JSONObject account) {
            return account != null && token.equals(account.optString("token", ""))
                    && access.equals(account.optString("access", ""))
                    && refresh.equals(account.optString("refresh", ""))
                    && expires == account.optLong("expires", 0L);
        }
    }

    /** Null means already fresh, missing refresh credential, or refresh failure. Never mutates account. */
    public static RefreshCandidate refreshCandidate(JSONObject account, boolean force) {
        return refreshCandidate(account, force, ClineAuth::refreshToken);
    }
    static RefreshCandidate refreshCandidate(JSONObject account, boolean force, RefreshTransport transport) {
        if (account == null) return null;
        synchronized (account) {
            long expires = account.optLong("expires", 0L);
            if (!force && expires > System.currentTimeMillis() + FRESH_MARGIN_MS) return null;
            String refresh = account.optString("refresh", "");
            if (refresh.isEmpty()) return null;
            RefreshCandidate before = new RefreshCandidate(account, null);
            try {
                SignIn next = transport.refresh(refresh);
                if (next == null || next.access == null || next.access.isEmpty() || !before.matches(account)) return null;
                return new RefreshCandidate(account, next);
            } catch (Exception ignored) { return null; }
        }
    }

    /** Legacy shared-object adapter. True also means another caller already refreshed it. */
    public static boolean refreshAccount(JSONObject acc) {
        return refreshAccount(acc, ClineAuth::refreshToken);
    }
    static boolean refreshAccount(JSONObject acc, RefreshTransport transport) {
        if (acc == null) return false;
        synchronized (acc) {
            if (acc.optLong("expires", 0L) > System.currentTimeMillis() + FRESH_MARGIN_MS
                    && !acc.optString("token", "").isEmpty()) return true;
            RefreshCandidate candidate = refreshCandidate(acc, false, transport);
            if (candidate == null || !candidate.matches(acc)) return false;
            SignIn next = candidate.next;
            try {
                acc.put("token", next.access); acc.put("access", next.access);
                if (next.refresh != null && !next.refresh.isEmpty()) acc.put("refresh", next.refresh);
                acc.put("expires", next.expires);
                if (next.email != null && !next.email.isEmpty()) acc.put("email", next.email);
                if (next.uid != null && !next.uid.isEmpty()) acc.put("uid", next.uid);
                return true;
            } catch (Exception ignored) { return false; }
        }
    }

    private static SignIn parseAuth(JSONObject data, String fallbackRefresh) throws Exception {
        String access = data.optString("accessToken", "");
        if (access.isEmpty()) access = data.optString("access_token", "");
        if (access.isEmpty()) throw new Exception("Cline sent back no access token");

        String refresh = data.optString("refreshToken", "");
        if (refresh.isEmpty()) refresh = data.optString("refresh_token", "");
        if (refresh.isEmpty()) refresh = fallbackRefresh;

        JSONObject ui = data.optJSONObject("userInfo");
        if (ui == null) ui = data.optJSONObject("user");
        if (ui == null) ui = new JSONObject();

        SignIn si = new SignIn();
        si.access = access;
        si.refresh = refresh;
        si.expires = isoToMs(data.optString("expiresAt", ""));
        si.uid = optFirst(ui.optString("clineUserId", ""), ui.optString("subject", ""), ui.optString("id", ""));
        si.email = ui.optString("email", "");
        si.name = ui.optString("name", "");
        si.accountId = !si.email.isEmpty() ? si.email : si.uid;
        return si;
    }

    /**
     * 拉取 Cline 推荐模型与全量云端目录（免登录）
     */
    public static List<String> fetchFeed(JSONObject metaOut) {
        List<String> result = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();

        try {
            // 1. 获取推荐模型列表（包含 recommended, free, clinePass）
            JSONObject recFeed = fetchJson(API + "/ai/cline/recommended-models");
            if (recFeed != null) {
                JSONObject data = recFeed.optJSONObject("data");
                if (data != null) recFeed = data;

                String[] groups = new String[]{"recommended", "free", "clinePass"};
                for (String g : groups) {
                    JSONArray list = recFeed.optJSONArray(g);
                    if (list == null) continue;
                    boolean isFree = "free".equals(g);
                    boolean isPass = "clinePass".equals(g);
                    for (int i = 0; i < list.length(); i++) {
                        Object item = list.get(i);
                        String id = "";
                        String name = "";
                        if (item instanceof String) {
                            id = ((String) item).trim();
                        } else if (item instanceof JSONObject) {
                            JSONObject o = (JSONObject) item;
                            id = optFirst(o.optString("id", ""), o.optString("model", ""), o.optString("modelId", ""));
                            name = optFirst(o.optString("name", ""), o.optString("displayName", ""), o.optString("label", ""));
                            if (o.optBoolean("free", false)) isFree = true;
                        }
                        if (id.isEmpty()) continue;
                        if (!seen.contains(id)) {
                            seen.add(id);
                            result.add(id);
                        }
                        if (metaOut != null) {
                            JSONObject m = metaOut.optJSONObject(id);
                            if (m == null) {
                                m = new JSONObject();
                                metaOut.put(id, m);
                            }
                            if (name.isEmpty() || name.equals(id)) name = prettify(id);
                            m.put("name", name);
                            if (isFree) m.put("free", true);
                            if (isPass || id.startsWith("cline-pass/")) m.put("pass", true);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        // 2. 尝试获取云端目录，补充上下文长度
        try {
            JSONObject cloudFeed = fetchJson(API + "/ai/cline/models");
            if (cloudFeed != null) {
                JSONArray list = cloudFeed.optJSONArray("data");
                if (list == null && cloudFeed.has("models")) list = cloudFeed.optJSONArray("models");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) continue;
                        String id = o.optString("id", "").trim();
                        if (id.isEmpty()) continue;
                        int ctx = o.optInt("context_length", 0);
                        if (metaOut != null) {
                            JSONObject m = metaOut.optJSONObject(id);
                            if (m != null && ctx > 0) {
                                m.put("context", ctx);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 如果网络完全不可达或未抓到模型，使用内置默认模型快照
        if (result.isEmpty()) {
            for (String defM : DEFAULT_MODELS) {
                result.add(defM);
                if (metaOut != null) {
                    JSONObject m = new JSONObject();
                    m.put("name", prettify(defM));
                    if (defM.contains("-free/")) m.put("free", true);
                    if (defM.startsWith("cline-pass/")) m.put("pass", true);
                    metaOut.put(defM, m);
                }
            }
        }
        return result;
    }

    /**
     * 查询 Cline 账号额度与 ClinePass 限额
     */
    public static JSONObject fetchUsage(String token, boolean isOauth) throws Exception {
        JSONObject out = new JSONObject();
        String bearer = token;
        if (isOauth && !bearer.toLowerCase().startsWith(WORKOS_PREFIX)) {
            bearer = WORKOS_PREFIX + bearer;
        }

        Map<String, String> headers = clientHeaders(null);
        headers.put("Authorization", "Bearer " + bearer);

        // 1. /users/me
        String meUrl = API + "/users/me";
        JSONObject meObj = fetchJsonWithHeaders(meUrl, headers);
        String userId = "";
        String plan = "";
        if (meObj != null) {
            JSONObject data = meObj.optJSONObject("data");
            if (data != null) meObj = data;
            userId = optFirst(meObj.optString("clineUserId", ""), meObj.optString("subject", ""), meObj.optString("id", ""));
            plan = optFirst(meObj.optString("plan", ""), meObj.optString("planType", ""), meObj.optString("planName", ""));
            out.put("user", optFirst(meObj.optString("email", ""), meObj.optString("name", ""), userId));
            if (!plan.isEmpty()) out.put("plan", plan);
        }

        // 2. /users/{userId}/balance
        if (!userId.isEmpty()) {
            try {
                String balUrl = API + "/users/" + URLEncoder.encode(userId, "UTF-8") + "/balance";
                JSONObject balObj = fetchJsonWithHeaders(balUrl, headers);
                if (balObj != null) {
                    JSONObject data = balObj.optJSONObject("data");
                    if (data != null) balObj = data;
                    double balUnits = balObj.optDouble("balance", -1.0);
                    if (balUnits >= 0) {
                        double dollars = balUnits / 1e6;
                        String balStr = dollars >= 0.01
                                ? String.format(Locale.US, "$%.2f", dollars)
                                : String.format(Locale.US, "$%.4f", dollars);
                        out.put("balance", balStr);
                        out.put("balanceUSD", dollars);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. /users/me/plan/usage-limits (ClinePass 限制)
        try {
            String limUrl = API + "/users/me/plan/usage-limits";
            JSONObject limObj = fetchJsonWithHeaders(limUrl, headers);
            if (limObj != null) {
                JSONObject data = limObj.optJSONObject("data");
                if (data != null) limObj = data;
                JSONArray limits = limObj.optJSONArray("limits");
                if (limits != null) {
                    JSONArray winOut = new JSONArray();
                    for (int i = 0; i < limits.length(); i++) {
                        JSONObject l = limits.optJSONObject(i);
                        if (l == null) continue;
                        String type = l.optString("type", "");
                        double pct = l.optDouble("percentUsed", 0.0);
                        String resetsAt = l.optString("resetsAt", "");
                        JSONObject w = new JSONObject();
                        w.put("type", type);
                        w.put("percentUsed", pct);
                        w.put("resetsAt", resetsAt);
                        winOut.put(w);
                    }
                    out.put("limits", winOut);
                }
            }
        } catch (Throwable ignored) {
        }

        out.put("ok", true);
        return out;
    }

    private static JSONObject fetchJson(String urlStr) {
        try {
            HttpURLConnection conn = openHttp(urlStr);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            Map<String, String> clH = clientHeaders(null);
            for (Map.Entry<String, String> e : clH.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                String str = readStream(conn.getInputStream());
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject fetchJsonWithHeaders(String urlStr, Map<String, String> headers) {
        try {
            HttpURLConnection conn = openHttp(urlStr);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                String str = readStream(conn.getInputStream());
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String prettify(String id) {
        if (id == null) return "";
        String seg = id;
        int lastSlash = id.lastIndexOf('/');
        if (lastSlash >= 0) seg = id.substring(lastSlash + 1);

        String[] parts = seg.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (w.isEmpty()) continue;
            String low = w.toLowerCase(Locale.US);
            if (sb.length() > 0) sb.append(' ');
            if ("gpt".equals(low) || "glm".equals(low) || "ai".equals(low) || "llm".equals(low) || "k3".equals(low)) {
                sb.append(low.toUpperCase(Locale.US));
            } else if ("mimo".equals(low)) {
                sb.append("MiMo");
            } else {
                sb.append(Character.toUpperCase(low.charAt(0))).append(low.substring(1));
            }
        }
        return sb.toString();
    }

    private static String optFirst(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static long isoToMs(String iso) {
        if (iso == null || iso.trim().isEmpty()) return System.currentTimeMillis() + 3600000L;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String clean = iso.replaceAll("\\.[0-9]+", "").replaceAll("Z$", "");
            Date d = sdf.parse(clean);
            if (d != null) return d.getTime();
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() + 3600000L;
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return baos.toString("UTF-8");
    }
}
