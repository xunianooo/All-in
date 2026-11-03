package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * OpenAI Codex / ChatGPT 订阅认证与原生协议桥接。
 *
 * 参照 upstream Magpie core (internal/provider/codex_*.go, internal/gateway/codex_*.go)：
 * 1. 凭据导入与存储：
 *    - 支持粘贴导入 ~/.codex/auth.json、Cockpit Tools / Sub2API / CLIProxyAPI 导出的 JSON；
 *    - 支持直接粘贴 refresh_token；
 *    - 自动通过 JWT 解码提取 email、chatgpt_account_id、chatgpt_plan_type (plus/pro/team/free)；
 * 2. OAuth 2.0 PKCE 认证：
 *    - Client ID: app_EMoamEEZ73f0CkXaXp7hrann (Codex CLI 官方 OAuth Client)
 *    - Authorize: https://auth.openai.com/oauth/authorize
 *    - Token: https://auth.openai.com/oauth/token
 *    - 回调地址: http://localhost:1455/auth/callback (支持自动捕获或手动粘贴回调链接/Code)
 * 3. 自动续期（Token Refresh）：
 *    - 当 access_token 剩余有效期不足 5 分钟或上游报 401 时，使用 refresh_token 自动续期
 * 4. 额度与限额查询：
 *    - https://chatgpt.com/backend-api/wham/usage 获取 Primary/Secondary RateLimit 与 Credits 余额
 * 5. 模型同步：
 *    - https://chatgpt.com/backend-api/codex/models?client_version=0.159.0
 * 6. 原生 Responses 与 OpenAI Chat 协议双向桥接：
 *    - 原生 Responses API 转发（POST /backend-api/codex/responses）
 *    - Chat Completions (/v1/chat/completions) 转 Codex Responses 并将 SSE 转回 chat.completion.chunk
 */
public class CodexAuth {

    public static final String PROVIDER = "codex";
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    public static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    public static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
    public static final int CALLBACK_PORT = 1455;
    public static final String SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke";

    public static final String CODEX_BASE = "https://chatgpt.com/backend-api/codex";
    public static final String CODEX_RESPONSES = "https://chatgpt.com/backend-api/codex/responses";
    public static final String CODEX_MODELS = "https://chatgpt.com/backend-api/codex/models";
    public static final String WHAM_USAGE = "https://chatgpt.com/backend-api/wham/usage";

    public static final String CODEX_VERSION = "0.159.0";
    public static final String ORIGINATOR = "codex_cli_rs";
    public static final String USER_AGENT = "codex_cli_rs/0.159.0 (Linux; aarch64) xterm-256color";

    // 默认内置模型
    public static final String[] DEFAULT_MODELS = new String[]{
            "gpt-5-codex",
            "gpt-5-codex-mini",
            "gpt-5",
            "gpt-5-mini",
            "gpt-5.1",
            "gpt-5.2",
            "o3",
            "o3-mini",
            "o1",
            "o1-mini",
            "o1-preview",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.5-preview"
    };

    private static Proxy sLocalProxy = null;
    private static long sLastProxyCheck = 0;

    /** 本地 7890 代理探测：统一走 ClineAuth 那一份，省掉第 5 份重复实现（含 fd 泄漏修复） */
    public static Proxy getLocalProxy() {
        return ClineAuth.getLocalProxy();
    }

    public static HttpURLConnection openHttp(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        Proxy p = getLocalProxy();
        if (p != null) {
            return (HttpURLConnection) url.openConnection(p);
        }
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * 官方 Codex 客户端请求头注入
     */
    public static Map<String, String> clientHeaders(String accountId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("originator", ORIGINATOR);
        h.put("User-Agent", USER_AGENT);
        if (accountId != null && !accountId.isEmpty()) {
            h.put("chatgpt-account-id", accountId);
        }
        return h;
    }

    /**
     * Codex 凭据结构
     */
    public static class SignInInfo {
        public String idToken = "";
        public String accessToken = "";
        public String refreshToken = "";
        public String accountId = "";
        public String email = "";
        public String planType = "chatgpt";
        public String authMode = "chatgpt";
        public long expiresAt = 0;
        public long lastRefresh = 0;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("auth_mode", authMode);
                JSONObject toks = new JSONObject();
                toks.put("id_token", idToken);
                toks.put("access_token", accessToken);
                toks.put("refresh_token", refreshToken);
                toks.put("account_id", accountId);
                obj.put("tokens", toks);
                obj.put("email", email);
                obj.put("plan_type", planType);
                obj.put("expires_at", expiresAt);
                obj.put("last_refresh", lastRefresh);
            } catch (Exception ignored) {
            }
            return obj;
        }
    }

    /**
     * 解析 JWT Payload（Base64URL 解码）
     */
    public static JSONObject decodeJwtPayload(String jwt) {
        if (jwt == null || !jwt.contains(".")) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payload = parts[1];
            // 补齐 Base64 padding
            int pad = 4 - (payload.length() % 4);
            if (pad > 0 && pad < 4) {
                StringBuilder sb = new StringBuilder(payload);
                for (int i = 0; i < pad; i++) sb.append('=');
                payload = sb.toString();
            }
            payload = payload.replace('-', '+').replace('_', '/');
            byte[] bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 从输入的原始文本（JSON 或 Token 文本）解析并生成 SignInInfo。
     */
    public static SignInInfo parseCredentials(String raw) throws Exception {
        if (raw == null) throw new IllegalArgumentException("凭据文本为空");
        String text = raw.trim();
        SignInInfo info = new SignInInfo();

        if (text.startsWith("{") && text.endsWith("}")) {
            JSONObject json = new JSONObject(text);
            // 1. 标准 auth.json 格式: {"tokens": {...}}
            JSONObject toks = json.optJSONObject("tokens");
            if (toks != null) {
                info.accessToken = toks.optString("access_token", "");
                info.refreshToken = toks.optString("refresh_token", "");
                info.idToken = toks.optString("id_token", "");
                info.accountId = toks.optString("account_id", "");
            } else {
                // 2. 平铺格式
                info.accessToken = json.optString("access_token", "");
                info.refreshToken = json.optString("refresh_token", "");
                info.idToken = json.optString("id_token", "");
                info.accountId = json.optString("account_id", "");
            }
            info.email = json.optString("email", "");
            info.planType = json.optString("plan_type", "");
            info.authMode = json.optString("auth_mode", "chatgpt");
        } else if (text.startsWith("sk-")) {
            // API Key 模式
            info.accessToken = text;
            info.authMode = "apikey";
            info.email = "API-Key-" + text.substring(0, Math.min(10, text.length())) + "...";
            return info;
        } else {
            // 单独的 refresh_token 或 access_token
            if (text.startsWith("Bearer ")) {
                text = text.substring(7).trim();
            }
            // 判断是否是 JWT (包含两个点)
            int dotCount = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '.') dotCount++;
            }
            if (dotCount == 2) {
                info.accessToken = text;
            } else {
                info.refreshToken = text;
            }
        }

        // 解析 ID Token 或 Access Token 中的用户信息
        JSONObject claims = decodeJwtPayload(!info.idToken.isEmpty() ? info.idToken : info.accessToken);
        if (claims != null) {
            if (info.email.isEmpty()) {
                info.email = claims.optString("email", "");
            }
            JSONObject authObj = claims.optJSONObject("https://api.openai.com/auth");
            if (authObj != null) {
                if (info.accountId.isEmpty()) {
                    info.accountId = authObj.optString("chatgpt_account_id", "");
                }
                if (info.planType.isEmpty()) {
                    info.planType = authObj.optString("chatgpt_plan_type", "chatgpt");
                }
            }
            long exp = claims.optLong("exp", 0);
            if (exp > 0) {
                info.expiresAt = exp * 1000L;
            }
        }

        if (info.email.isEmpty()) {
            info.email = !info.accountId.isEmpty() ? "Codex-" + info.accountId.substring(0, Math.min(8, info.accountId.length())) : "ChatGPT-User";
        }

        // 如果只有 refresh_token 没有 access_token，立即刷新一次
        if (info.accessToken.isEmpty() && !info.refreshToken.isEmpty()) {
            refreshToken(info, "forced");
        }

        return info;
    }

    /**
     * 刷新 Token
     */
    public static void refreshToken(SignInInfo info, String reason) throws Exception {
        if (info.refreshToken == null || info.refreshToken.isEmpty()) {
            throw new IllegalStateException("无可用的 refresh_token，无法续期");
        }
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", info.refreshToken);
        body.put("refresh_reason", reason != null ? reason : "proactive_expiry");

        HttpURLConnection conn = openHttp(TOKEN_URL);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("originator", ORIGINATOR);
        conn.setRequestProperty("User-Agent", USER_AGENT);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();

        String resStr = bos.toString("UTF-8");
        if (code != 200) {
            throw new Exception("Codex 令牌刷新失败 (" + code + "): " + resStr);
        }

        JSONObject res = new JSONObject(resStr);
        String newAcc = res.optString("access_token", "");
        if (newAcc.isEmpty()) {
            throw new Exception("刷新响应中缺少 access_token: " + resStr);
        }
        info.accessToken = newAcc;
        String newRef = res.optString("refresh_token", "");
        if (!newRef.isEmpty()) info.refreshToken = newRef;
        String newId = res.optString("id_token", "");
        if (!newId.isEmpty()) info.idToken = newId;

        info.lastRefresh = System.currentTimeMillis();
        JSONObject claims = decodeJwtPayload(!info.idToken.isEmpty() ? info.idToken : info.accessToken);
        if (claims != null) {
            long exp = claims.optLong("exp", 0);
            if (exp > 0) info.expiresAt = exp * 1000L;
            if (info.email.isEmpty() || info.email.startsWith("Codex-")) {
                String em = claims.optString("email", "");
                if (!em.isEmpty()) info.email = em;
            }
            JSONObject authObj = claims.optJSONObject("https://api.openai.com/auth");
            if (authObj != null) {
                if (info.accountId.isEmpty()) info.accountId = authObj.optString("chatgpt_account_id", "");
                if (info.planType.isEmpty() || "chatgpt".equals(info.planType)) {
                    info.planType = authObj.optString("chatgpt_plan_type", "chatgpt");
                }
            }
        }
    }

    /**
     * PKCE 流程上下文
     */
    public static class Flow {
        public String flowId = "";
        public String verifier = "";
        public String state = "";
        public String authUrl = "";
        public long createdAt = 0;
        public String status = "pending"; // pending / done / failed
        public SignInInfo result = null;
        public String error = "";
    }

    private static String base64UrlEncode(byte[] bytes) {
        String s = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        return s.trim();
    }

    /**
     * 创建 PKCE OAuth 授权流程
     */
    public static Flow createFlow() throws Exception {
        Flow flow = new Flow();
        flow.flowId = "flow_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
        flow.createdAt = System.currentTimeMillis();

        SecureRandom sr = new SecureRandom();
        byte[] verifierBytes = new byte[32];
        sr.nextBytes(verifierBytes);
        flow.verifier = base64UrlEncode(verifierBytes);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(flow.verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = base64UrlEncode(digest);

        byte[] stateBytes = new byte[16];
        sr.nextBytes(stateBytes);
        flow.state = base64UrlEncode(stateBytes);

        StringBuilder sb = new StringBuilder(AUTHORIZE_URL);
        sb.append("?response_type=code");
        sb.append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
        sb.append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"));
        sb.append("&scope=").append(URLEncoder.encode(SCOPES, "UTF-8"));
        sb.append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"));
        sb.append("&code_challenge_method=S256");
        sb.append("&id_token_add_organizations=true");
        sb.append("&codex_cli_simplified_flow=true");
        sb.append("&state=").append(URLEncoder.encode(flow.state, "UTF-8"));
        sb.append("&originator=").append(URLEncoder.encode(ORIGINATOR, "UTF-8"));

        flow.authUrl = sb.toString();
        return flow;
    }

    /**
     * 使用授权 code 换取 tokens
     */
    public static SignInInfo exchangeCode(String code, String verifier) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(URLEncoder.encode(code, "UTF-8"));
        body.append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"));
        body.append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
        body.append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"));

        HttpURLConnection conn = openHttp(TOKEN_URL);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("originator", ORIGINATOR);
        conn.setRequestProperty("User-Agent", USER_AGENT);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int httpCode = conn.getResponseCode();
        InputStream is = (httpCode >= 200 && httpCode < 400) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();

        String resStr = bos.toString("UTF-8");
        if (httpCode != 200) {
            throw new Exception("Codex 授权换取令牌失败 (" + httpCode + "): " + resStr);
        }

        JSONObject res = new JSONObject(resStr);
        SignInInfo info = new SignInInfo();
        info.accessToken = res.optString("access_token", "");
        info.refreshToken = res.optString("refresh_token", "");
        info.idToken = res.optString("id_token", "");
        info.lastRefresh = System.currentTimeMillis();

        JSONObject claims = decodeJwtPayload(!info.idToken.isEmpty() ? info.idToken : info.accessToken);
        if (claims != null) {
            info.email = claims.optString("email", "");
            long exp = claims.optLong("exp", 0);
            if (exp > 0) info.expiresAt = exp * 1000L;
            JSONObject authObj = claims.optJSONObject("https://api.openai.com/auth");
            if (authObj != null) {
                info.accountId = authObj.optString("chatgpt_account_id", "");
                info.planType = authObj.optString("chatgpt_plan_type", "chatgpt");
            }
        }
        if (info.email.isEmpty()) info.email = "Codex-OAuth-User";

        return info;
    }

    /**
     * 查询用量与配额
     */
    public static JSONObject fetchUsage(SignInInfo info) throws Exception {
        return fetchUsage(info, false);
    }

    /** retried：401 后只刷新并重试一次，避免刷新成功仍 401 时无限自递归 */
    public static JSONObject fetchUsage(SignInInfo info, boolean retried) throws Exception {
        if ("apikey".equals(info.authMode)) {
            JSONObject ret = new JSONObject();
            ret.put("plan_type", "API Key");
            ret.put("status", "ok");
            return ret;
        }

        // 检查 access_token 是否过期，若过期先刷新
        if (info.expiresAt > 0 && (info.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000L) && !info.refreshToken.isEmpty()) {
            try {
                refreshToken(info, "proactive_expiry");
            } catch (Throwable ignored) {
            }
        }

        HttpURLConnection conn = openHttp(WHAM_USAGE);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Authorization", "Bearer " + info.accessToken);
        if (!info.accountId.isEmpty()) {
            conn.setRequestProperty("chatgpt-account-id", info.accountId);
        }
        conn.setRequestProperty("originator", ORIGINATOR);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code == 401 && !info.refreshToken.isEmpty() && !retried) {
            // 只重试一次：刷新成功但上游仍 401（账号被禁 / scope 变更）时不要再递归
            refreshToken(info, "unauthorized_recovery");
            return fetchUsage(info, true);
        }

        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();

        String str = bos.toString("UTF-8");
        if (code != 200) {
            throw new Exception("获取 Codex 用量失败 (" + code + "): " + str);
        }

        return new JSONObject(str);
    }

    /**
     * 授权前的地区/连通性预检。
     *
     * Codex 的 OAuth 授权页是开在系统浏览器里的，浏览器走系统网络，
     * App 这边的 7890 代理探测管不到它。中国大陆等地区的出口 IP 会被
     * OpenAI 直接拒绝（页面回 {"code":"unsupported_country_region_territory"}），
     * 用户在浏览器里只会看到一坨英文 JSON，根本不知道发生了什么。
     *
     * 这里先用一次最轻量的请求探明情况，让面板能提前把原因说清楚。
     * 返回 null 表示可以正常授权；返回字符串就是要展示给用户的原因。
     */
    public static String precheckRegion() {
        HttpURLConnection conn = null;
        try {
            // /v1/models 无 key 时会回 401 —— 能拿到 401 就说明网络与地区都通
            conn = openHttp("https://api.openai.com/v1/models");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(9000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "AllIn/1.0");

            int code = conn.getResponseCode();
            if (code == 401) return null;   // 可达，只是没带 key，符合预期

            java.io.InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readStream(is);
            String low = body == null ? "" : body.toLowerCase();

            if (low.contains("unsupported_country_region_territory")) {
                return "当前网络出口在 OpenAI 不支持的地区（中国大陆等），授权页会被直接拒绝。"
                        + "需要开启代理并切到支持的地区节点（美国/日本/新加坡等），再点授权。";
            }
            if (code == 403 || code == 451) {
                return "OpenAI 拒绝了当前网络出口（HTTP " + code + "）。"
                        + "通常是地区受限或 IP 被风控，请更换代理节点后再试。";
            }
            if (code == 429) {
                return "OpenAI 限流了当前出口 IP（HTTP 429），稍等一会或换个节点再试。";
            }
            return null;   // 其它状态码不拦，交给授权流程本身去报
        } catch (Throwable e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
                return "连不上 OpenAI（连接被拒绝）。当前可能没开代理，或代理没有接管这个域名。";
            }
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("Timeout")) {
                return "连接 OpenAI 超时。没开代理时国内网络基本连不上，请开启代理后重试。";
            }
            return "无法连接 OpenAI：" + msg + "。请检查代理是否开启。";
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readStream(java.io.InputStream is) {
        if (is == null) return "";
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable e) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 同步在线可用模型列表
     */
    public static List<String> syncModels(SignInInfo info) {
        List<String> list = new ArrayList<>();
        if ("apikey".equals(info.authMode)) {
            Collections.addAll(list, DEFAULT_MODELS);
            return list;
        }

        try {
            if (info.expiresAt > 0 && (info.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000L) && !info.refreshToken.isEmpty()) {
                try {
                    refreshToken(info, "proactive_expiry");
                } catch (Throwable ignored) {}
            }

            String urlStr = CODEX_MODELS + "?client_version=" + URLEncoder.encode(CODEX_VERSION, "UTF-8");
            HttpURLConnection conn = openHttp(urlStr);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization", "Bearer " + info.accessToken);
            if (!info.accountId.isEmpty()) {
                conn.setRequestProperty("chatgpt-account-id", info.accountId);
            }
            conn.setRequestProperty("originator", ORIGINATOR);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                JSONObject root = new JSONObject(bos.toString("UTF-8"));
                JSONArray arr = root.optJSONArray("models");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m != null) {
                            String slug = m.optString("slug", "");
                            String vis = m.optString("visibility", "");
                            if (!slug.isEmpty() && !"hide".equalsIgnoreCase(vis)) {
                                if (!list.contains(slug)) list.add(slug);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (list.isEmpty()) {
            Collections.addAll(list, DEFAULT_MODELS);
        }
        return list;
    }

    /**
     * 将 OpenAI Chat Completions payload 转换为 Codex Responses 格式 payload
     */
    public static JSONObject chatToResponses(JSONObject chatReq, String upstreamModel) throws Exception {
        return ResponsesBridge.chatToResponses(chatReq, upstreamModel);
    }

    /**
     * 将提取的增量文字包装为 OpenAI 标准 SSE chunk: data: {"choices":[{"delta":{"content":"..."}}]}
     */
    public static String wrapChatDelta(String deltaText, String model, String id) {
        JSONObject chunk = new JSONObject();
        try {
            chunk.put("id", id != null ? id : "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            JSONObject delta = new JSONObject();
            delta.put("content", deltaText);
            choice.put("delta", delta);
            choice.put("finish_reason", JSONObject.NULL);
            choices.put(choice);
            chunk.put("choices", choices);
        } catch (Exception ignored) {}
        return "data: " + chunk.toString() + "\n\n";
    }

    /**
     * 将结束标记包装为 OpenAI 标准 SSE 结束
     */
    public static String wrapChatDone(String model, String id) {
        JSONObject chunk = new JSONObject();
        try {
            chunk.put("id", id != null ? id : "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            JSONObject delta = new JSONObject();
            choice.put("delta", delta);
            choice.put("finish_reason", "stop");
            choices.put(choice);
            chunk.put("choices", choices);
        } catch (Exception ignored) {}
        return "data: " + chunk.toString() + "\n\ndata: [DONE]\n\n";
    }
}
