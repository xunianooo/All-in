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
    interface CatalogFeedReader { Object read(String url) throws Exception; }

    public static List<String> fetchFeed(JSONObject metaOut) {
        return fetchFeedInternal(metaOut, false, ClineAuth::readCatalogFeed);
    }

    /** Returns an empty list on incomplete/invalid remote feeds; never synthesizes defaults. */
    public static List<String> fetchFeedStrict(JSONObject metaOut) {
        return fetchFeedInternal(metaOut, true, ClineAuth::readCatalogFeed);
    }

    static List<String> fetchFeedStrict(JSONObject metaOut, CatalogFeedReader reader) {
        return fetchFeedInternal(metaOut, true, reader);
    }

    private static Object readCatalogFeed(String url) throws Exception {
        HttpURLConnection conn = openHttp(url);
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000); conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : clientHeaders(null).entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            return new org.json.JSONTokener(readStream(conn.getInputStream())).nextValue();
        } finally { conn.disconnect(); }
    }

    private static List<String> fetchFeedInternal(JSONObject metaOut, boolean strict, CatalogFeedReader reader) {
        JSONObject pending = new JSONObject();
        List<String> result = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();

        try {
            // 1. 获取推荐模型列表（包含 recommended, free, clinePass）
            Object recRaw = reader.read(API + "/ai/cline/recommended-models");
            JSONObject recFeed = recRaw instanceof JSONObject ? (JSONObject) recRaw : null;
            boolean validRec = false;
            if (recFeed != null) {
                JSONObject data = recFeed.optJSONObject("data");
                if (data != null) recFeed = data;

                String[] groups = new String[]{"recommended", "free", "clinePass"};
                for (String g : groups) {
                    JSONArray list = recFeed.optJSONArray(g);
                    if (list == null) { if (strict && recFeed.has(g)) return new ArrayList<String>(); continue; }
                    validRec = true;
                    boolean isPass = "clinePass".equals(g);
                    for (int i = 0; i < list.length(); i++) {
                        boolean isFree = "free".equals(g);
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
                        if (id.trim().isEmpty()) { if (strict) return new ArrayList<String>(); continue; }
                        id = id.trim();
                        if (!seen.contains(id)) {
                            seen.add(id);
                            result.add(id);
                        }
                        if (pending != null) {
                            JSONObject m = pending.optJSONObject(id);
                            if (m == null) {
                                m = new JSONObject();
                                pending.put(id, m);
                            }
                            if (name.isEmpty() || name.equals(id)) name = prettify(id);
                            m.put("name", name);
                            if (isFree) m.put("free", true);
                            if (isPass || id.startsWith("cline-pass/")) m.put("pass", true);
                        }
                    }
                }
            }
            if (strict && (!validRec || result.isEmpty())) return new ArrayList<String>();
        } catch (Throwable e) {
            if (strict) return new ArrayList<String>();
        }

        // 2. 尝试获取云端目录，补充上下文长度
        try {
            Object cloudRaw = reader.read(API + "/ai/cline/models");
            if (cloudRaw instanceof JSONObject || cloudRaw instanceof JSONArray) {
                JSONObject cloudFeed = cloudRaw instanceof JSONObject ? (JSONObject) cloudRaw : null;
                JSONArray list = cloudRaw instanceof JSONArray ? (JSONArray) cloudRaw : cloudFeed.optJSONArray("data");
                if (list == null && cloudFeed != null) list = cloudFeed.optJSONArray("models");
                if (strict && (list == null || list.length() == 0)) return new ArrayList<String>();
                int validCloud = 0;
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) { if (strict) return new ArrayList<String>(); continue; }
                        Object rawId = o.opt("id");
                        if (!(rawId instanceof String) || ((String) rawId).trim().isEmpty()) { if (strict) return new ArrayList<String>(); continue; }
                        String id = ((String) rawId).trim();
                        validCloud++;
                        if (seen.add(id)) result.add(id);
                        JSONObject cloudMeta = pending.optJSONObject(id);
                        if (cloudMeta == null) {
                            cloudMeta = new JSONObject();
                            String name = optFirst(o.optString("display_name", ""), o.optString("displayName", ""), o.optString("name", ""));
                            cloudMeta.put("name", name.isEmpty() ? prettify(id) : name);
                            if (id.startsWith("cline-pass/")) cloudMeta.put("pass", true);
                            pending.put(id, cloudMeta);
                        }
                        int ctx = o.optInt("context_length", 0);
                        JSONObject top = o.optJSONObject("top_provider");
                        if (ctx <= 0 && top != null) ctx = top.optInt("context_length", 0);
                        if (ctx <= 0) ctx = o.optInt("context", 0);
                        if (pending != null) {
                            JSONObject m = pending.optJSONObject(id);
                            if (m != null && ctx > 0) {
                                m.put("context", ctx);
                            }
                        }
                    }
                }
                if (strict && validCloud == 0) return new ArrayList<String>();
            } else if (strict) return new ArrayList<String>();
        } catch (Throwable ignored) {
            if (strict) return new ArrayList<String>();
        }

        // 如果网络完全不可达或未抓到模型，使用内置默认模型快照
        if (!strict && result.isEmpty()) {
            for (String defM : DEFAULT_MODELS) {
                result.add(defM);
                if (pending != null) {
                    JSONObject m = new JSONObject();
                    m.put("name", prettify(defM));
                    if (defM.contains("-free/")) m.put("free", true);
                    if (defM.startsWith("cline-pass/")) m.put("pass", true);
                    pending.put(defM, m);
                }
            }
        }
        if (metaOut != null) {
            java.util.Iterator<String> keys = pending.keys();
            while (keys.hasNext()) { String key = keys.next(); try { metaOut.put(key, pending.get(key)); } catch (Exception e) { return new ArrayList<String>(); } }
        }
        return result;
    }

    /**
     * 查询 Cline 账号额度与 ClinePass 限额
     */
    private static final int USAGE_BODY_LIMIT = 1024 * 1024;

    private static final class UsageRead {
        int status;
        JSONObject json;
        FailureLayers.Kind kind;
    }

    private static UsageRead usageRead(String url, Map<String, String> headers) {
        UsageRead result = new UsageRead();
        HttpURLConnection conn = null;
        try {
            conn = openHttp(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            result.status = conn.getResponseCode();
            if (result.status < 200 || result.status >= 300) {
                result.kind = readKind(result.status);
                return result;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > USAGE_BODY_LIMIT) {
                        result.kind = FailureLayers.Kind.SCHEMA;
                        return result;
                    }
                    bytes.write(buf, 0, n);
                }
            }
            try { result.json = new JSONObject(bytes.toString("UTF-8")); }
            catch (org.json.JSONException e) { result.kind = FailureLayers.Kind.SCHEMA; return result; }
            if (businessFailure(result.json)) result.kind = FailureLayers.Kind.BUSINESS;
        } catch (Exception e) {
            result.kind = FailureLayers.transport(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private static boolean businessFailure(JSONObject obj) {
        Object error = obj.opt("error");
        if (error != null && error != JSONObject.NULL && !Boolean.FALSE.equals(error)
                && !(error instanceof String && ((String) error).isEmpty())) return true;
        if (Boolean.FALSE.equals(obj.opt("success")) || Boolean.FALSE.equals(obj.opt("ok"))) return true;
        Object code = obj.opt("code");
        if (code instanceof Number) {
            double value = ((Number) code).doubleValue();
            if (value != 0 && value != 200) return true;
        } else if (code instanceof String && !((String) code).isEmpty()
                && !"0".equals(code) && !"200".equals(code)) return true;
        return false;
    }

    private static JSONObject usageData(UsageRead r) {
        if (r.json == null || r.kind != null) return null;
        if (r.json.has("data")) {
            JSONObject data = r.json.optJSONObject("data");
            if (data == null) { r.kind = FailureLayers.Kind.SCHEMA; return null; }
            if (businessFailure(data)) { r.kind = FailureLayers.Kind.BUSINESS; return null; }
            return data;
        }
        return r.json;
    }

    private static FailureLayers.Layer usageLayer(FailureLayers.Kind kind, String section) {
        FailureLayers.Layer known = FailureLayers.layer(kind);
        if (known == FailureLayers.Layer.NETWORK || kind == FailureLayers.Kind.AUTH || kind == FailureLayers.Kind.RESTRICTED) return known;
        return "账号信息".equals(section) ? FailureLayers.Layer.LOGIN : FailureLayers.Layer.BALANCE;
    }

    private static JSONObject usageSection(UsageRead r, boolean valid, String name, JSONArray unread) throws Exception {
        JSONObject section = new JSONObject();
        section.put("status", valid ? "ok" : "unknown");
        if (r.status > 0) section.put("httpStatus", r.status);
        if (!valid) {
            if (r.kind == null) r.kind = FailureLayers.Kind.SCHEMA;
            FailureLayers.describe(section, r.kind, usageLayer(r.kind, name));
            section.put("affectsInference", JSONObject.NULL);
            section.put("inferenceStatus", "unknown");
            unread.put(name + ": " + (r.status > 0 && (r.status < 200 || r.status >= 300)
                    ? readFailure(r.status) : "数据未能读取"));
        }
        return section;
    }

    public static JSONObject fetchUsage(String token, boolean isOauth) throws Exception {
        JSONObject out = new JSONObject(), sections = new JSONObject();
        JSONArray unread = new JSONArray();
        String bearer = token;
        if (isOauth && !bearer.toLowerCase(Locale.US).startsWith(WORKOS_PREFIX)) bearer = WORKOS_PREFIX + bearer;
        Map<String, String> headers = clientHeaders(null);
        headers.put("Authorization", "Bearer " + bearer);

        UsageRead me = usageRead(API + "/users/me", headers);
        JSONObject identity = usageData(me);
        String userId = "";
        if (identity != null) {
            userId = optFirst(identity.opt("clineUserId") instanceof String ? identity.optString("clineUserId") : "",
                    identity.opt("subject") instanceof String ? identity.optString("subject") : "",
                    identity.opt("id") instanceof String ? identity.optString("id") : "");
        }
        boolean identityOk = !userId.trim().isEmpty();
        if (identityOk) {
            String plan = optFirst(identity.optString("plan", ""), identity.optString("planType", ""), identity.optString("planName", ""));
            out.put("user", optFirst(identity.optString("email", ""), identity.optString("name", ""), userId));
            if (!plan.isEmpty()) out.put("plan", plan);
        }
        sections.put("identity", usageSection(me, identityOk, "账号信息", unread));

        UsageRead balance = new UsageRead();
        JSONObject bal = null;
        if (identityOk) {
            balance = usageRead(API + "/users/" + URLEncoder.encode(userId, "UTF-8") + "/balance", headers);
            bal = usageData(balance);
        }
        Object value = bal == null ? null : bal.opt("balance");
        double units = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        boolean balanceOk = !Double.isNaN(units) && !Double.isInfinite(units) && units >= 0;
        if (balanceOk) {
            double dollars = units / 1e6;
            out.put("balance", String.format(Locale.US, dollars >= 0.01 ? "$%.2f" : "$%.4f", dollars));
            out.put("balanceUSD", dollars);
        }
        sections.put("balance", usageSection(balance, balanceOk, "余额", unread));

        UsageRead limitsRead = usageRead(API + "/users/me/plan/usage-limits", headers);
        JSONObject limitData = usageData(limitsRead);
        JSONArray limits = limitData == null ? null : limitData.optJSONArray("limits");
        boolean limitsOk = limits != null;
        JSONArray winOut = new JSONArray();
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                JSONObject l = limits.optJSONObject(i);
                Object percent = l == null ? null : l.opt("percentUsed");
                double pct = percent instanceof Number ? ((Number) percent).doubleValue() : Double.NaN;
                if (l == null || !(l.opt("type") instanceof String) || l.optString("type").isEmpty()
                        || Double.isNaN(pct) || Double.isInfinite(pct) || pct < 0) {
                    limitsOk = false;
                    continue;
                }
                JSONObject w = new JSONObject().put("type", l.optString("type")).put("percentUsed", pct);
                if (l.opt("resetsAt") instanceof String) w.put("resetsAt", l.optString("resetsAt"));
                winOut.put(w);
            }
            if (limitsOk || winOut.length() > 0) out.put("limits", winOut);
        }
        sections.put("limits", usageSection(limitsRead, limitsOk, "套餐限额", unread));
        boolean complete = identityOk && balanceOk && limitsOk;
        out.put("ok", identityOk);
        out.put("complete", complete);
        out.put("partial", !complete && (identityOk || balanceOk || limitsOk || winOut.length() > 0));
        out.put("sections", sections);
        out.put("readAt", System.currentTimeMillis());
        if (unread.length() > 0) out.put("unread", unread);
        if (!complete) {
            UsageRead failed = !identityOk ? me : !balanceOk ? balance : limitsRead;
            FailureLayers.describe(out, failed.kind, usageLayer(failed.kind, !identityOk ? "账号信息" : "余额"));
            out.put("affectsInference", JSONObject.NULL);
            out.put("inferenceStatus", "unknown");
            if (!identityOk) {
                out.put("reason", "identity_unread");
                out.put("error", "Cline 账号信息未能读取；这不代表账号有问题，也不代表额度为零。");
            }
        }
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
        return fetchJsonWithHeaders(urlStr, headers, null);
    }

    /**
     * 读一个 JSON，同时把这次读的结果交回去。status[0]：&gt;0 是 HTTP 码，0 是连接没通。
     *
     * <p>原来只有一个返回 null 的版本，401、404 和断网看起来一模一样，于是
     * fetchUsage 最后无条件写 ok:true——账号信息一段没读出来也会被当成"账号正常"。
     */
    private static JSONObject fetchJsonWithHeaders(String urlStr, Map<String, String> headers, int[] status) {
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
            int code = conn.getResponseCode();
            if (status != null) status[0] = code;
            if (code >= 200 && code < 400) {
                String str = readStream(conn.getInputStream());
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 一次读取失败的说法：把 HTTP 码与连接没通分开。 */
    static String readFailure(int status) {
        if (status == 401) return "登录态被拒 (401)";
        if (status == 403) return "被限制访问 (403)";
        if (status == 404) return "端点不存在 (404)";
        if (status == 429) return "被限流 (429)";
        if (status > 0) return "HTTP " + status;
        return "连接没通";
    }

    /** 一次读取失败属于哪一类，喂给 FailureLayers 决定层与建议。 */
    static FailureLayers.Kind readKind(int status) {
        if (status == 401) return FailureLayers.Kind.AUTH;
        if (status == 403) return FailureLayers.Kind.RESTRICTED;
        if (status == 404) return FailureLayers.Kind.NOT_FOUND;
        if (status == 429) return FailureLayers.Kind.RATE;
        if (status > 0) return FailureLayers.Kind.UNREAD;
        return FailureLayers.Kind.NETWORK;
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
