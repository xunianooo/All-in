package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 妙手 CatPaw（美团 ai.catpaw.meituan.com）真实现。
 *
 * 移植自 magpie-community/plugins 的 packages/catpaw（MIT），
 * 请求形态按妙手桌面客户端 2026-10-09 的抓包：
 *   round → event(running) → turn(SSE)
 * 上游 SSE 帧是"累积式"的（每帧给的是到目前为止的全文），
 * 所以必须和上一帧做差分才能还原 delta。
 *
 * 三个必须照抄、不能"优化"的点：
 *  1. 请求体里的 source/mode 是 CatX / CATX_APP。抓包里的 CatPawCLI/CLI
 *     已被网关停用（返回 code=400 "source 不存在或未启用: CatPawCLI"）。
 *  2. 余额接口在另一个域（catx.nocode.cn）且只认 X-Auth-Token；
 *     把聊天用的 Cookie: X-Passport-Token 发过去会稳定 401 code 4010。
 *  3. 会话协议只收一段扁平文本 prompt，工具定义/工具结果/图片都送不进去，
 *     带这些的请求必须明确拒绝，不能悄悄丢掉。
 */
public final class CatPawAuth {

    private CatPawAuth() {}

    public static final String ID = "catpaw";
    public static final String NAME = "妙手 CatPaw";
    public static final String ORIGIN = "https://ai.catpaw.meituan.com";
    public static final String PASSPORT = "https://catx.nocode.cn";
    public static final String BALANCE_URL = "https://catx.nocode.cn/api/gateway/credit/balance";

    /** 桌面客户端的模型表：id -> {展示名, modelType, 是否收图} */
    private static final Map<String, Object[]> MODELS = new LinkedHashMap<String, Object[]>();
    static {
        MODELS.put("longcat-2.0", new Object[]{"LongCat-2.0 (限时免费)", 77, Boolean.TRUE});
        MODELS.put("glm-5.3-flashx", new Object[]{"GLM-5.3-FlashX", 98, Boolean.TRUE});
        MODELS.put("glm-5.3-flash", new Object[]{"GLM-5.3-Flash", 91, Boolean.TRUE});
        MODELS.put("glm-5.3", new Object[]{"GLM-5.3", 89, Boolean.FALSE});
        MODELS.put("deepseek-v4-flash", new Object[]{"DeepSeek-V4-Flash", 63, Boolean.FALSE});
        MODELS.put("deepseek-v4-pro", new Object[]{"DeepSeek-V4-Pro", 64, Boolean.FALSE});
        MODELS.put("minimax-m3", new Object[]{"MiniMax-M3", 70, Boolean.TRUE});
        MODELS.put("kimi-k3", new Object[]{"Kimi-K3", 83, Boolean.TRUE});
        MODELS.put("auto", new Object[]{"Auto (自动选择)", 0, Boolean.FALSE});
    }

    public static JSONArray modelList() {
        JSONArray a = new JSONArray();
        Iterator<String> it = MODELS.keySet().iterator();
        while (it.hasNext()) a.put(it.next());
        return a;
    }

    public static JSONObject modelMeta() {
        JSONObject meta = new JSONObject();
        try {
            Iterator<String> it = MODELS.keySet().iterator();
            while (it.hasNext()) {
                String id = it.next();
                Object[] m = MODELS.get(id);
                JSONObject o = new JSONObject();
                o.put("name", (String) m[0]);
                o.put("modelType", ((Integer) m[1]).intValue());
                o.put("image", ((Boolean) m[2]).booleanValue());
                meta.put(id, o);
            }
        } catch (Exception ignored) {}
        return meta;
    }

    /** 未知模型回落到 longcat-2.0 的 type，与插件一致 */
    private static int modelTypeOf(String id) {
        Object[] m = MODELS.get(id);
        if (m == null) return 77;
        return ((Integer) m[1]).intValue();
    }

    private static String hex32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 每次请求都带客户端原样的一套头；m-host 是客户端本机局域网 IP，不能伪造，直接不发 */
    private static Map<String, String> gatewayHeaders(String token) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        String trace = hex32();
        h.put("Content-Type", "application/json");
        h.put("Cookie", "X-Passport-Token=" + token);
        h.put("User-Agent", "axios/1.19.0");
        h.put("m-appkey", "com.sankuai.mcopilot.cli");
        h.put("m-debug", "true");
        h.put("m-traceid", trace);
        h.put("x-request-id", UUID.randomUUID().toString());
        h.put("x-trace-id", trace);
        h.put("x-agent-version", "1.2.0-beta.6");
        return h;
    }

    // ===================== 登录（官方网页通道，服务端轮询，无需回跳端口） =====================

    public static class Flow {
        public String entryUrl = "";
        public String sid = "";
        public String error = "";
    }

    /**
     * ① 问 login-config 取官方登录入口，挂上 state/sid/redirect。
     * 妙手自己的客户端有两条通道，这里只用 poll-token 那条：
     * 浏览器登录占用 sid 之后，服务端 GET poll-token?sid= 直接拿到 token，
     * 服务端对服务端，不监听本机端口，也不受浏览器私有网络检查（PNA）影响。
     */
    public static Flow startLogin() {
        Flow f = new Flow();
        try {
            JSONObject cfg = getJson(PASSPORT + "/api/gateway/passport/login-config", null, 10000);
            JSONObject data = cfg.optJSONObject("data");
            String entry = data == null ? "" : data.optString("loginEntryUrl", "");
            if (entry.isEmpty()) {
                f.error = "妙手登录服务未返回登录入口地址";
                return f;
            }
            String state = hex32();
            String sid = hex32();
            f.sid = sid;
            StringBuilder sb = new StringBuilder(entry);
            sb.append(entry.indexOf('?') >= 0 ? '&' : '?');
            sb.append("state=").append(state);
            sb.append("&sid=").append(sid);
            sb.append("&redirect=").append(URLEncoder.encode("http://127.0.0.1:9/catpaw-callback", "UTF-8"));
            f.entryUrl = sb.toString();
            return f;
        } catch (Exception e) {
            f.error = "无法连接妙手登录服务: " + e.getMessage();
            return f;
        }
    }

    /** ② 轮询 sid 是否已被浏览器登录占用；拿到返回 token，没拿到返回空串 */
    public static String pollToken(String sid) throws Exception {
        JSONObject j = getJson(PASSPORT + "/api/gateway/passport/poll-token?sid="
                + URLEncoder.encode(sid, "UTF-8"), null, 5000);
        Object d = j.opt("data");
        if (d instanceof String) {
            String t = ((String) d).trim();
            return t;
        }
        return "";
    }

    /** ③ 补 uid / 昵称；失败不影响登录（token 已经在手） */
    public static String[] currentUser(String token) {
        String uid = "";
        String name = "妙手网页登录";
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Accept", "application/json");
            h.put("X-Auth-Token", token);
            JSONObject j = getJson(PASSPORT + "/api/gateway/passport/current-user", h, 10000);
            JSONObject data = j.optJSONObject("data");
            if (data != null) {
                String id = data.optString("userId", "");
                if (!"null".equals(id)) uid = id;
                if (!data.optString("userName", "").isEmpty()) name = data.optString("userName");
            }
        } catch (Exception ignored) {}
        return new String[]{uid, name};
    }

    /** 余额：注意这里是 X-Auth-Token，不是聊天那套 Cookie */
    public static JSONObject balance(String token) {
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Accept", "application/json");
            h.put("X-Auth-Token", token);
            h.put("gray-set", "new-agent-sdk");
            JSONObject j = getJson(BALANCE_URL, h, 20000);
            JSONObject out = new JSONObject();
            int code = j.optInt("code", -1);
            if (code == 4010 || code == 4011) {
                out.put("error", "妙手登录态已过期，请重新登录");
                out.put("signIn", "expired");
                return out;
            }
            if (code != 0) {
                out.put("error", "妙手余额查询返回异常 code=" + code);
                return out;
            }
            JSONObject data = j.optJSONObject("data");
            if (data == null) {
                out.put("error", "妙手余额响应缺少 data");
                return out;
            }
            Object credit = data.opt("availableCredits");
            if (credit == null) credit = data.opt("credit");
            if (credit == null) credit = data.opt("balance");
            if (credit == null) credit = data.opt("available");
            out.put("credit", credit == null ? "" : String.valueOf(credit));
            JSONObject plan = data.optJSONObject("userPlan");
            out.put("plan", plan == null ? NAME : plan.optString("planName", NAME));
            return out;
        } catch (Exception e) {
            JSONObject out = new JSONObject();
            try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            return out;
        }
    }

    // ===================== 聊天：round → event → turn =====================

    /** 把 messages 压成协议唯一能承载的那一段文本（插件的 buildPrompt） */
    public static String buildPrompt(JSONArray messages) {
        if (messages == null) return "";
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user");
            String content = contentText(m.opt("content"));
            String block = null;
            if ("system".equals(role) || "developer".equals(role)) block = "[System Instructions]\n" + content;
            else if ("user".equals(role)) block = "[User]\n" + content;
            else if ("assistant".equals(role)) block = "[Assistant]\n" + content;
            if (block == null) continue;
            if (parts.length() > 0) parts.append("\n\n");
            parts.append(block);
        }
        if (messages.length() == 1 && "user".equals(messages.optJSONObject(0).optString("role"))) {
            return contentText(messages.optJSONObject(0).opt("content"));
        }
        return parts.toString();
    }

    private static String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object c = arr.opt(i);
                if (c instanceof JSONObject) sb.append(((JSONObject) c).optString("text", ""));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    /** Validate everything the flattened prompt can represent before any network call. */
    public static String unsupportedReason(JSONArray messages, JSONArray tools) {
        if (tools != null && tools.length() > 0) return "妙手不支持工具";
        if (messages == null || messages.length() == 0) return "妙手需要消息";
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) return "妙手消息格式无效";
            String role = m.optString("role", "");
            if (!"system".equals(role) && !"developer".equals(role)
                    && !"user".equals(role) && !"assistant".equals(role)) return "妙手不支持此消息角色";
            if (m.has("tool_calls") || m.has("function_call") || m.has("tool_call_id")) return "妙手不支持工具历史";
            Object content = m.opt("content");
            if (content instanceof String) continue;
            if (!(content instanceof JSONArray)) return "妙手只支持文本内容";
            JSONArray parts = (JSONArray) content;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null || !"text".equals(part.optString("type")) || !(part.opt("text") instanceof String))
                    return "妙手只支持文本内容";
            }
        }
        return null;
    }

    /** Safe typed error: sentOrUnknown is conservative once a POST is attempted. */
    public static final class Failure extends Exception {
        public final int httpStatus;
        public final Integer businessCode;
        public final boolean sentOrUnknown;
        public Failure(int httpStatus, Integer businessCode, boolean sentOrUnknown) {
            super("妙手请求失败");
            this.httpStatus = httpStatus;
            this.businessCode = businessCode;
            this.sentOrUnknown = sentOrUnknown;
        }
    }
    public static int failureStatus(Throwable error) {
        if (!(error instanceof Failure)) return 502;
        Failure f = (Failure) error;
        if (f.httpStatus == 400 || f.httpStatus == 401 || f.httpStatus == 403 || f.httpStatus == 429) return f.httpStatus;
        return 502;
    }
    public static boolean failureSentOrUnknown(Throwable error) {
        return !(error instanceof Failure) || ((Failure) error).sentOrUnknown;
    }
    public interface ChatTransport {
        ChatResponse post(String url, String body, Map<String, String> headers, int timeoutMs, String accept) throws Exception;
    }
    public static final class ChatResponse {
        public final int status;
        public final byte[] body;
        public ChatResponse(int status, byte[] body) { this.status = status; this.body = body == null ? new byte[0] : body; }
    }
    private static int businessStatus(Integer code) {
        return code != null && (code == 401 || code == 403 || code == 429) ? code : 502;
    }
    private static ChatResponse chatPost(ChatTransport transport, String path, JSONObject body,
                                         Map<String, String> headers, int timeout, String accept) throws Failure {
        try {
            ChatResponse response = transport.post(ORIGIN + path, body.toString(), headers, timeout, accept);
            if (response == null) throw new Failure(502, null, true);
            if (response.status < 200 || response.status >= 300) throw new Failure(response.status, null, true);
            return response;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure(502, null, true); }
    }
    private static final ChatTransport HTTP_CHAT = new ChatTransport() {
        public ChatResponse post(String url, String body, Map<String, String> headers, int timeout, String accept) throws Exception {
            HttpURLConnection conn = NetworkPolicy.openHttp(url);
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(timeout);
                if (accept != null) conn.setRequestProperty("Accept", accept);
                for (Map.Entry<String, String> h : headers.entrySet()) conn.setRequestProperty(h.getKey(), h.getValue());
                try (OutputStream out = conn.getOutputStream()) { out.write(body.getBytes("UTF-8")); }
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) return new ChatResponse(status, null);
                InputStream input = conn.getInputStream();
                if (input == null) throw new java.io.IOException("empty stream");
                try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    return new ChatResponse(status, out.toByteArray());
                }
            } finally { conn.disconnect(); }
        }
    };

    /** 三步会话机；返回 {text, reasoning}，出错抛异常 */
    public static JSONObject chat(String token, String model, JSONArray messages) throws Exception {
        return chat(token, model, messages, HTTP_CHAT);
    }

    public static JSONObject chat(String token, String model, JSONArray messages, ChatTransport transport) throws Exception {
        if (unsupportedReason(messages, null) != null || transport == null) throw new Failure(400, null, false);
        int modelType = modelTypeOf(model);
        String convId = UUID.randomUUID().toString();
        String msgId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        String prompt = buildPrompt(messages);

        // ① round：建会话
        JSONObject round = new JSONObject();
        round.put("conversationId", convId);
        round.put("message", userMessage(msgId, prompt));
        round.put("modelType", modelType);
        round.put("source", "CatX");
        round.put("mode", "CATX_APP");
        round.put("permissionMode", "unsafeBypassPermissions");
        round.put("toolVersion", "2.0.2");
        round.put("repositories", new JSONArray());

        Map<String, String> h1 = gatewayHeaders(token);
        h1.put("m-spanid", "0.2");
        h1.put("m-spanname", "conversation.round");
        ChatResponse roundResponse = chatPost(transport, "/api/agent/conversation/round", round, h1, 30000, "application/json");
        JSONObject r1j;
        try { r1j = new JSONObject(new String(roundResponse.body, "UTF-8")); }
        catch (Exception e) { throw new Failure(502, null, true); }
        if (!r1j.has("code") || r1j.optInt("code", -1) != 0) {
            Integer code = r1j.opt("code") instanceof Number ? r1j.optInt("code") : null;
            throw new Failure(businessStatus(code), code, true);
        }

        // ② event：标记会话运行中
        JSONObject ev = new JSONObject();
        ev.put("conversationId", convId);
        ev.put("eventType", "conversation");
        JSONObject evData = new JSONObject();
        evData.put("status", "running");
        ev.put("data", evData);
        Map<String, String> h2 = gatewayHeaders(token);
        h2.put("m-spanid", "0.3");
        h2.put("m-spanname", "conversation.event");
        ChatResponse eventResponse = chatPost(transport, "/api/agent/conversation/event", ev, h2, 30000, "application/json");
        try {
            JSONObject eventJson = new JSONObject(new String(eventResponse.body, "UTF-8"));
            if (eventJson.has("code") && eventJson.optInt("code", -1) != 0) {
                Integer code = eventJson.opt("code") instanceof Number ? eventJson.optInt("code") : null;
                throw new Failure(businessStatus(code), code, true);
            }
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure(502, null, true); }

        // ③ turn：SSE 答案，累积帧做差分
        JSONObject turn = new JSONObject();
        turn.put("conversationId", convId);
        turn.put("turnRequestId", turnId);
        turn.put("message", userMessage(msgId, prompt));
        turn.put("modelType", modelType);
        turn.put("source", "CatX");
        turn.put("mode", "CATX_APP");
        turn.put("permissionMode", "unsafeBypassPermissions");
        turn.put("toolConfigs", new JSONArray());
        turn.put("toolVersion", "2.0.2");

        Map<String, String> h3 = gatewayHeaders(token);
        h3.put("Accept", "text/event-stream");
        h3.put("enableheartbeat", "true");
        h3.put("m-spanid", "0.4.1");
        h3.put("m-spanname", "conversation.turn");

        ChatResponse turnResponse = chatPost(transport, "/api/agent/conversation/turn", turn, h3, 15 * 60 * 1000, "text/event-stream");
        return parseTurn(turnResponse.body);
    }

    static JSONObject parseTurn(byte[] dataBytes) throws Exception {
        String answer = new String(dataBytes, "UTF-8");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        int frames = 0;
        String[] lines = answer.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) break;
            JSONObject frame;
            try { frame = new JSONObject(data); } catch (Exception e) { continue; }
            frames++;
            JSONObject err = frame.optJSONObject("error");
            if (err != null) {
                Integer code = err.opt("code") instanceof Number ? err.optInt("code") : null;
                throw new Failure(businessStatus(code), code, true);
            }
            JSONObject msg = frame.optJSONObject("message");
            if (msg == null) continue;
            JSONArray content = msg.optJSONArray("content");
            if (content == null) continue;
            for (int k = 0; k < content.length(); k++) {
                JSONObject c = content.optJSONObject(k);
                if (c == null || !"text".equals(c.optString("type"))) continue;
                String cur = c.optString("text", "");
                if (c.has("text")) { text.setLength(0); text.append(cur); }
                String curR = c.optString("reasoningContent", "");
                if (c.has("reasoningContent")) { reasoning.setLength(0); reasoning.append(curR); }
            }
        }
        if (frames == 0 && text.length() == 0) throw new Failure(502, null, true);

        JSONObject out = new JSONObject();
        out.put("text", text.toString());
        out.put("reasoning", reasoning.toString());
        return out;
    }

    private static JSONObject userMessage(String msgId, String prompt) throws Exception {
        JSONObject message = new JSONObject();
        message.put("type", "user");
        message.put("messageId", msgId);
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.put(textPart);
        message.put("content", content);
        message.put("finished", true);
        return message;
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ===================== HTTP 底座 =====================

    private static JSONObject getJson(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(Math.min(timeoutMs, 15000));
        conn.setReadTimeout(timeoutMs);
        if (headers != null) {
            Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(is);
        if (code < 200 || code >= 400) {
            throw new Exception("HTTP " + code + " " + cut(body));
        }
        return new JSONObject(body.isEmpty() ? "{}" : body);
    }

    private static String post(String url, String body, Map<String, String> headers,
                               int timeoutMs, String accept) throws Exception {
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeoutMs);
        if (accept != null) conn.setRequestProperty("Accept", accept);
        Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        if (code < 200 || code >= 400) throw new Exception("HTTP " + code + " " + cut(text));
        return text;
    }

    private static byte[] streamPost(String url, String body, Map<String, String> headers,
                                     int timeoutMs) throws Exception {
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeoutMs);
        Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            String text = readAll(conn.getErrorStream());
            throw new Exception("妙手 turn 失败：HTTP " + code + " " + cut(text));
        }
        InputStream is = conn.getInputStream();
        if (is == null) throw new Exception("妙手 turn 返回了空流");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }
}
