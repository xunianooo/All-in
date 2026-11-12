package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * GitHub Copilot 认证与调用支撑。
 *
 * 移植自 upstream opencode-copilot-auth 0.0.12（packages/copilot）。
 *
 * 链路（GitHub 官方 OAuth 设备流）：
 *   1. POST https://github.com/login/device/code
 *      {client_id: Iv1.b507a08c87ecfe98, scope: "read:user"}
 *      → device_code / user_code / verification_uri / expires_in / interval
 *   2. 用户在浏览器打开 verification_uri 并输入 user_code
 *   3. 轮询 POST https://github.com/login/oauth/access_token
 *      {client_id, device_code, grant_type: urn:ietf:params:oauth:grant-type:device_code}
 *      → authorization_pending 期间继续等；成功后拿到 GitHub user token（长期）
 *   4. GET https://api.github.com/copilot_internal/v2/token
 *      Authorization: Bearer <github token>
 *      → Copilot 短期 token（约 30 分钟）+ expires_at（epoch 秒）
 *
 * 聊天端点：https://api.githubcopilot.com/chat/completions（OpenAI 兼容）。
 * 除 Authorization 外还必须带 Copilot 客户端指纹头，否则上游直接 401：
 *   Copilot-Integration-Id: vscode-chat
 *   Editor-Version:         vscode/1.140.0
 *   Editor-Plugin-Version:  copilot-chat/0.68.0
 *   User-Agent:             GitHubCopilotChat/0.68.0
 *   Openai-Intent:          conversation-edits
 *   X-Initiator:            user（普通提问）/ agent（工具链、assistant 续写）
 *   带图请求额外加 Copilot-Vision-Request: true
 *
 * 账号存法（config.providers[].accounts[]）：
 *   refresh        = GitHub user token（长期，用来换 Copilot token）
 *   access / token = Copilot 短期 token（转发时用）
 *   expires        = Copilot token 到期毫秒时间戳
 */
public class CopilotAuth {

    public static final String PROVIDER = "github-copilot";
    public static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    public static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    public static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    public static final String USER_URL = "https://api.github.com/user";
    public static final String BASE = "https://api.githubcopilot.com";
    public static final String GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code";

    public static final String UA = "GitHubCopilotChat/0.68.0";
    public static final String EDITOR_VERSION = "vscode/1.140.0";
    public static final String PLUGIN_VERSION = "copilot-chat/0.68.0";

    /** 网络未同步时给面板的兜底模型名（真实清单以 /models 为准） */
    public static final String[] DEFAULT_MODELS = new String[]{
            "gpt-5.1",
            "gpt-5.1-codex",
            "gpt-5.1-codex-mini",
            "gpt-5",
            "gpt-5-mini",
            "claude-sonnet-4.5",
            "claude-opus-4.1",
            "gemini-3-pro-preview",
            "o3",
            "o4-mini"
    };

    public static class Flow {
        public String flowId;              // device_code
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public long expiresIn;             // 秒
        public long intervalMs;
        public long createdAt;
    }

    public static class SignIn {
        public String githubLogin = "";
        public String githubToken = "";    // 长期 token（refresh）
        public String copilotToken = "";   // 短期 token（access）
        public long expires = 0L;
    }

    public static class PollResult {
        public String status;   // pending / slow_down / ready / failed
        public String error;
        public SignIn signIn;
    }

    public static class Tok {
        public String token = "";
        public long expires = 0L;
    }

    /** Read only: the GitHub OAuth token is required, never the inference token.
     * Returns a sanitized allowance, or null when it cannot be read. */
    public static JSONObject fetchAllowance(String githubToken) {
        if (githubToken == null || githubToken.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = open("https://api.github.com/copilot_internal/user");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "token " + githubToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "GitHubCopilotChat/0.68.0");
            conn.setRequestProperty("Editor-Version", "vscode/1.140.0");
            conn.setRequestProperty("Editor-Plugin-Version", "copilot-chat/0.68.0");
            conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat");
            if (conn.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 262144) return null;
                    out.write(buffer, 0, n);
                }
            }
            return ProviderAllowance.fromCopilot(new JSONObject(out.toString("UTF-8")));
        } catch (Exception ignored) { return null; }
        finally { if (conn != null) conn.disconnect(); }
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

    private static String text(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        return read(is);
    }

    private static void postJson(HttpURLConnection conn, String json) throws Exception {
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", UA);
        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.flush();
        os.close();
    }

    /** 1. 申请设备码 */
    public static Flow initDeviceFlow() throws Exception {
        HttpURLConnection conn = open(DEVICE_CODE_URL);
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("scope", "read:user");
        postJson(conn, body.toString());

        int code = conn.getResponseCode();
        String raw = text(conn);
        if (code < 200 || code >= 400) {
            throw new Exception("GitHub 设备码申请失败 (HTTP " + code + "): " + raw);
        }
        JSONObject j = new JSONObject(raw);
        String deviceCode = j.optString("device_code", "");
        String userCode = j.optString("user_code", "");
        String uri = j.optString("verification_uri", "");
        if (deviceCode.isEmpty() || userCode.isEmpty() || uri.isEmpty()) {
            throw new Exception("GitHub 设备码返回不完整: " + raw);
        }
        Flow f = new Flow();
        f.flowId = deviceCode;
        f.userCode = userCode;
        f.verificationUri = uri;
        // GitHub 不返回 verification_uri_complete；实测 ?user_code= 也不会预填，
        // 真正填码靠 LoginActivity 在页面加载完后注入 JS（见 autofillGitHubDeviceCode）
        f.verificationUriComplete = uri + "?user_code=" + URLEncoder.encode(userCode, "UTF-8");
        f.expiresIn = j.optLong("expires_in", 900L);
        long interval = j.optLong("interval", 5L);
        f.intervalMs = Math.max(3000L, interval * 1000L);
        f.createdAt = System.currentTimeMillis();
        return f;
    }

    /** 2. 轮询设备流；ready 时顺手换 Copilot token 并读 GitHub 登录名 */
    public static PollResult pollDeviceFlow(String deviceCode) {
        PollResult pr = new PollResult();
        pr.status = "pending";
        try {
            HttpURLConnection conn = open(ACCESS_TOKEN_URL);
            JSONObject body = new JSONObject();
            body.put("client_id", CLIENT_ID);
            body.put("device_code", deviceCode);
            body.put("grant_type", GRANT_DEVICE);
            postJson(conn, body.toString());

            int code = conn.getResponseCode();
            String raw = text(conn);
            JSONObject j;
            try {
                j = new JSONObject(raw);
            } catch (Exception pe) {
                pr.status = "pending";
                pr.error = "GitHub 轮询返回非 JSON (HTTP " + code + ")";
                return pr;
            }

            String err = j.optString("error", "");
            String accessToken = j.optString("access_token", "");
            if (accessToken.isEmpty()) {
                if ("authorization_pending".equals(err) || err.isEmpty()) {
                    pr.status = "pending";
                    pr.error = err;
                    return pr;
                }
                if ("slow_down".equals(err)) {
                    pr.status = "slow_down";
                    pr.error = err;
                    return pr;
                }
                pr.status = "failed";
                pr.error = "GitHub 拒绝了设备授权: " + (err.isEmpty() ? raw.trim() : err);
                return pr;
            }

            SignIn si = new SignIn();
            si.githubToken = accessToken;
            si.githubLogin = fetchLogin(accessToken);

            Tok t = exchange(accessToken);
            if (t == null || t.token.isEmpty()) {
                pr.status = "failed";
                pr.error = "GitHub 授权成功，但换不到 Copilot token：这个账号没有 Copilot 订阅（或未在组织里启用）。";
                return pr;
            }
            si.copilotToken = t.token;
            si.expires = t.expires;
            pr.status = "ready";
            pr.signIn = si;
            return pr;
        } catch (Exception e) {
            pr.status = "pending";   // 单次网络抖动不算失败
            pr.error = e.getMessage() == null ? "轮询失败" : e.getMessage();
            return pr;
        }
    }

    /** GitHub user token → Copilot 短期 token */
    public static final class ExchangeResult {
        public final Tok token;
        public final int status;
        ExchangeResult(Tok token, int status) { this.token = token; this.status = status; }
        public boolean needsSignIn() { return status == 401; }
    }
    public static final class TypedAuthException extends Exception {
        public final int status;
        public final boolean needsSignIn;
        public final long retryAfterMs;
        TypedAuthException(int status, boolean needsSignIn) {
            super("Copilot token exchange failed (HTTP " + status + ")");
            this.status = status; this.needsSignIn = needsSignIn; this.retryAfterMs = 0L;
        }
    }
    interface CandidateTransport { ExchangeResult exchange(String refresh) throws Exception; }
    public static JSONObject refreshCandidate(JSONObject account) throws Exception {
        return refreshCandidate(account, CopilotAuth::exchangeResult);
    }
    static JSONObject refreshCandidate(JSONObject account, CandidateTransport transport) throws Exception {
        if (account == null) throw new TypedAuthException(0, false);
        JSONObject copy;
        synchronized (account) { copy = new JSONObject(account.toString()); }
        String refresh = copy.optString("refresh", "");
        if (refresh.isEmpty()) throw new TypedAuthException(0, true);
        ExchangeResult result;
        try { result = transport.exchange(refresh); }
        catch (Exception e) { throw new TypedAuthException(0, false); }
        if (result == null || result.token == null || result.token.token == null || result.token.token.isEmpty()
                || result.status < 200 || result.status >= 300) {
            int status = result == null ? 0 : result.status;
            throw new TypedAuthException(status, status == 401);
        }
        copy.put("access", result.token.token);
        copy.put("token", result.token.token);
        copy.put("expires", result.token.expires);
        return copy;
    }
    public static Tok exchange(String githubToken) { return exchangeResult(githubToken).token; }
    public static ExchangeResult exchangeResult(String githubToken) {
        try {
            HttpURLConnection conn = open(COPILOT_TOKEN_URL);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Editor-Version", EDITOR_VERSION);
            conn.setRequestProperty("Editor-Plugin-Version", PLUGIN_VERSION);
            conn.setRequestProperty("Authorization", "Bearer " + githubToken);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) { conn.disconnect(); return new ExchangeResult(null, code); }
            String raw = text(conn);
            JSONObject j = new JSONObject(raw);
            String token = j.optString("token", "");
            long expSec = j.optLong("expires_at", 0L);
            if (token.isEmpty()) return new ExchangeResult(null, code);
            Tok t = new Tok();
            t.token = token;
            t.expires = expSec > 0 ? expSec * 1000L : System.currentTimeMillis() + 25L * 60L * 1000L;
            return new ExchangeResult(t, code);
        } catch (Exception e) {
            return new ExchangeResult(null, 0);
        }
    }

    public static String fetchLogin(String githubToken) {
        try {
            HttpURLConnection conn = open(USER_URL);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Authorization", "Bearer " + githubToken);
            int code = conn.getResponseCode();
            String raw = text(conn);
            if (code < 200 || code >= 400) return "";
            JSONObject j = new JSONObject(raw);
            return j.optString("login", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 保证账号手里有一个可用的 Copilot token：临期（5 分钟内）就用 GitHub token 重换。
     * 彻底失败时回退旧 token（可能已过期，让上游 401 报出去）。
     */
    public static String ensureCopilotToken(JSONObject acc) {
        if (acc == null) return null;
        synchronized (acc) { return ensureCopilotTokenLocked(acc); }
    }
    private static String ensureCopilotTokenLocked(JSONObject acc) {
        if (acc == null) return null;
        String token = acc.optString("access", acc.optString("token", ""));
        long exp = acc.optLong("expires", 0L);
        long now = System.currentTimeMillis();
        if (!token.isEmpty() && exp > now + 5L * 60L * 1000L) return token;

        String gh = acc.optString("refresh", "");
        if (gh.isEmpty()) return token.isEmpty() ? null : token;

        Tok t = exchange(gh);
        if (t == null || t.token.isEmpty()) return token.isEmpty() ? null : token;
        try {
            acc.put("access", t.token);
            acc.put("token", t.token);
            acc.put("expires", t.expires);
        } catch (Exception ignored) {
        }
        return t.token;
    }

    /** 拉 Copilot 模型目录（需要 Copilot token） */
    public static java.util.List<String> models(String copilotToken) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (copilotToken == null || copilotToken.isEmpty()) return out;
        HttpURLConnection conn = open(BASE + "/models");
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + copilotToken);
        for (java.util.Map.Entry<String, String> e : clientHeaders().entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        int code = conn.getResponseCode();
        String raw = text(conn);
        if (code < 200 || code >= 400) {
            throw new Exception("Copilot 模型目录 HTTP " + code + ": " + raw.trim());
        }
        JSONObject j = new JSONObject(raw);
        JSONArray data = j.optJSONArray("data");
        if (data == null) data = j.optJSONArray("models");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** Copilot 客户端指纹头（Authorization 除外） */
    public static java.util.Map<String, String> clientHeaders() {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Editor-Version", EDITOR_VERSION);
        h.put("Editor-Plugin-Version", PLUGIN_VERSION);
        h.put("Copilot-Integration-Id", "vscode-chat");
        h.put("Openai-Intent", "conversation-edits");
        return h;
    }

    /** 这次请求算不算 agent 续写（插件里的 isAgentCall） */
    public static boolean isAgentCall(JSONObject payload) {
        try {
            JSONArray messages = payload.optJSONArray("messages");
            if (messages != null && messages.length() > 0) {
                JSONObject last = messages.optJSONObject(messages.length() - 1);
                if (last != null) {
                    String role = last.optString("role", "");
                    return "tool".equals(role) || "assistant".equals(role);
                }
            }
            JSONArray input = payload.optJSONArray("input");
            if (input != null && input.length() > 0) {
                JSONObject last = input.optJSONObject(input.length() - 1);
                if (last != null) {
                    if ("assistant".equals(last.optString("role", ""))) return true;
                    String type = last.optString("type", "");
                    if (type.length() > 0 && !"message".equals(type)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 是否带图（带图要加 Copilot-Vision-Request） */
    public static boolean isVisionRequest(JSONObject payload) {
        try {
            JSONArray messages = payload.optJSONArray("messages");
            for (int i = 0; messages != null && i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                JSONArray content = m == null ? null : m.optJSONArray("content");
                for (int k = 0; content != null && k < content.length(); k++) {
                    JSONObject part = content.optJSONObject(k);
                    if (part == null) continue;
                    String type = part.optString("type", "");
                    if ("image_url".equals(type) || "input_image".equals(type)) return true;
                }
            }
            JSONArray input = payload.optJSONArray("input");
            for (int i = 0; input != null && i < input.length(); i++) {
                JSONObject m = input.optJSONObject(i);
                JSONArray content = m == null ? null : m.optJSONArray("content");
                for (int k = 0; content != null && k < content.length(); k++) {
                    JSONObject part = content.optJSONObject(k);
                    if (part != null && "input_image".equals(part.optString("type", ""))) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
