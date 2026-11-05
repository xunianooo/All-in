package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * ZCode 的 Start Plan / gift 通道。
 *
 * 为什么单独一个类：ZCode 送的额度（夜晚赠送、Start Plan、Trust Build）**只认 ZCode 自己的
 * session JWT**，走 zcode.z.ai 的 /api/v1/zcode-plan/anthropic，而不是 api.z.ai 的 /api/anthropic。
 * 换出来的 <id>.<secret> 那把 API Key 只能花 GLM Coding Plan，打 gift 会返回 1113。
 *
 * 而且 zcode.z.ai 只把额度放给「看起来像 ZCode 客户端」的请求，否则一律
 * 405 {"code":3012,"msg":"request has been blocked due to unusual activity."}。
 * 所以这里的 headers 与 body 都按 packages/zcode 的 startHeaders / dress 逐条复刻：
 *
 *  - headers 是**整组替换**，agent 的头一个都不留（不带 x-api-key、不带 X-Device-Mid、
 *    不带 anthropic-beta；只额外追加 x-session-id）。
 *  - system 恒为 3 块，每块一个 cache_control；agent 自己的 system 接在第 3 块尾部，
 *    第 4 块会被 405/3012 拒（#343）。
 *  - messages 最前面插一条 <system-reminder> 日期 turn。
 *  - cache_control 总数固定 4（3 system + 最后一条消息的最后一个块），tools 上一个都不留。
 *  - metadata.user_id 是**字符串化的 JSON**：{"device_id":…,"account_uuid":"","session_id":…}
 */
final class ZCodeGift {

    static final String START_BASE = "https://zcode.z.ai/api/v1/zcode-plan/anthropic";
    static final String APP_VERSION = "3.16.3";
    static final String UA = "ZCode/" + APP_VERSION;
    private static final String EPHEMERAL = "ephemeral";

    private ZCodeGift() {}

    // ===================== 身份派生 =====================

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    /** 与插件 uuidOf 一致：由 seed 确定性地做出一个 v4 形状的 UUID，同 seed 每次相同 */
    static String uuidOf(String seed) {
        String h = sha256Hex(seed);
        int v = (Integer.parseInt(h.substring(16, 17), 16) & 3) | 8;
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16)
                + "-" + Integer.toHexString(v) + h.substring(17, 20) + "-" + h.substring(20, 32);
    }

    /** x-session-id：同一设备恒定，同一会话恒定 */
    static String sessionOf(String agentSessionId, String device) {
        String own = agentSessionId == null ? "" : agentSessionId.trim();
        if (own.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return own.toLowerCase();
        }
        return own.isEmpty()
                ? uuidOf("zcode-session-device\0" + (device == null ? "" : device))
                : uuidOf("zcode-session\0" + own);
    }

    /** metadata.user_id 是字符串化的 JSON，键序固定，account_uuid 恒为空串 */
    static String userIdOf(String device, String session) {
        return "{\"device_id\":\"" + device + "\",\"account_uuid\":\"\",\"session_id\":\"" + session + "\"}";
    }

    // ===================== 本机环境（ZCode 客户端会报这些） =====================

    private static String platform() {
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) arch = "arm64";
        else if (arch.contains("x86_64") || arch.contains("amd64")) arch = "x64";
        return "linux-" + arch;
    }

    private static String osCategory() {
        return "linux";
    }

    /** 对应 os.release()：Android 上是内核版本串 */
    private static String osRelease() {
        return System.getProperty("os.version", "unknown");
    }

    /** 对应 osVersion()：platform + " " + release() + " " + arch()（与 x-os-version 不同） */
    private static String fullOsVersion() {
        return "linux " + osRelease() + " " + System.getProperty("os.arch", "");
    }

    private static String locale() {
        Locale l = Locale.getDefault();
        if (l == null) return "unknown";
        String lang = l.getLanguage();
        String country = l.getCountry();
        if (lang == null || lang.isEmpty()) return "unknown";
        return country == null || country.isEmpty() ? lang : lang + "-" + country;
    }

    private static String timezone() {
        TimeZone tz = TimeZone.getDefault();
        return tz == null ? "unknown" : tz.getID();
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date());
    }

    // ===================== body 整形（dress） =====================

    /** # Environment 段，与插件 environment() 一致 */
    private static String environmentBlock(String provider, String model) {
        StringBuilder sb = new StringBuilder();
        sb.append(ZCodePrompt.ENV_HEADING).append("\n");
        sb.append(ZCodePrompt.ENV_INVOKED_LINE).append("\n");
        sb.append("- ").append(ZCodePrompt.ENV_PLATFORM_LABEL).append(": linux\n");
        sb.append("- ").append(ZCodePrompt.ENV_OS_VERSION_LABEL).append(": ").append(fullOsVersion());
        if (model != null && !model.isEmpty()) {
            sb.append("\n").append(ZCodePrompt.ENV_POWERED_BY_LINE
                    .replace("{provider}", provider)
                    .replace("{model}", model));
        }
        return sb.toString();
    }

    /** 最前面那条 <system-reminder> 日期 turn，标签紧贴正文，前后不留空白 */
    private static JSONObject contextPrefix() {
        String body = ZCodePrompt.CTX_INTRO + "\n"
                + ZCodePrompt.CTX_CURRENT_DATE_HEADING + "\n"
                + ZCodePrompt.CTX_CURRENT_DATE_LINE.replace("{date}", today()) + "\n"
                + "\n"
                + ZCodePrompt.CTX_OUTRO;
        JSONArray content = new JSONArray();
        JSONObject t = new JSONObject();
        t.put("type", "text");
        t.put("text", "<system-reminder>" + body + "</system-reminder>");
        content.put(t);
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", content);
        return m;
    }

    private static JSONObject cachedText(String text) {
        JSONObject o = new JSONObject();
        o.put("type", "text");
        o.put("text", text);
        JSONObject cc = new JSONObject();
        cc.put("type", EPHEMERAL);
        o.put("cache_control", cc);
        return o;
    }

    /** 收集 agent 自己的 system 文本（数组或字符串），用于接到第 3 块尾部 */
    private static java.util.List<String> ownSystem(JSONObject body) {
        java.util.List<String> own = new java.util.ArrayList<String>();
        Object sys = body.opt("system");
        if (sys instanceof String) {
            String s = (String) sys;
            if (!s.trim().isEmpty()) own.add(s);
        } else if (sys instanceof JSONArray) {
            JSONArray arr = (JSONArray) sys;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                if (!"text".equals(x.optString("type"))) continue;
                String t = x.optString("text", "");
                if (t != null && !t.trim().isEmpty()) own.add(t);
            }
        }
        return own;
    }

    /**
     * 把一份 Anthropic Messages 请求体整成 ZCode 客户端的样子。
     * 只改 system / messages / metadata.user_id / tools[].cache_control，其余字段原样保留。
     */
    static JSONObject dress(JSONObject body, String site, String model, String device, String session) {
        String provider = "bigmodel".equals(site) ? "bigmodel-api" : "zai-api";
        java.util.List<String> own = ownSystem(body);

        java.util.List<String> dynamic = new java.util.ArrayList<String>();
        dynamic.add(ZCodePrompt.BEFORE_ENVIRONMENT);
        dynamic.add(environmentBlock(provider, model));
        dynamic.add(ZCodePrompt.AFTER_ENVIRONMENT);
        dynamic.addAll(own);

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < dynamic.size(); i++) {
            if (i > 0) joined.append("\n\n");
            joined.append(dynamic.get(i));
        }

        JSONArray sections = new JSONArray();
        sections.put(cachedText(ZCodePrompt.CLI_PREFIX));
        StringBuilder stable = new StringBuilder();
        for (int i = 0; i < ZCodePrompt.STABLE_SECTIONS.length; i++) {
            if (i > 0) stable.append("\n\n");
            stable.append(ZCodePrompt.STABLE_SECTIONS[i]);
        }
        sections.put(cachedText(stable.toString()));
        sections.put(cachedText("\n\n" + joined));
        body.put("system", sections);

        // 前面插日期 turn
        JSONArray old = body.optJSONArray("messages");
        JSONArray msgs = new JSONArray();
        msgs.put(contextPrefix());
        for (int i = 0; old != null && i < old.length(); i++) msgs.put(old.opt(i));
        body.put("messages", msgs);

        // 清掉消息上所有 cache 标记，只补最后一个块
        JSONObject last = null;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if ("system".equals(m.optString("role"))) continue;
            last = m;
            Object c = m.opt("content");
            if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject x = arr.optJSONObject(k);
                    if (x != null) x.remove("cache_control");
                }
            }
        }
        if (last != null) {
            Object c = last.opt("content");
            if (c instanceof String) {
                JSONArray arr = new JSONArray();
                arr.put(cachedText((String) c));
                try { last.put("content", arr); } catch (Exception ignored) {}
            } else if (c instanceof JSONArray && ((JSONArray) c).length() > 0) {
                JSONArray arr = (JSONArray) c;
                JSONObject x = arr.optJSONObject(arr.length() - 1);
                if (x != null && !x.has("cache_control")) {
                    JSONObject cc = new JSONObject();
                    try {
                        cc.put("type", EPHEMERAL);
                        x.put("cache_control", cc);
                    } catch (Exception ignored) {}
                }
            }
        }

        // tools 上一个 cache 标记都不留
        JSONArray tools = body.optJSONArray("tools");
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t != null) t.remove("cache_control");
        }

        // metadata 浅合并，补 user_id
        JSONObject meta = body.optJSONObject("metadata");
        if (meta == null) meta = new JSONObject();
        try {
            meta.put("user_id", userIdOf(device, session));
            body.put("metadata", meta);
        } catch (Exception ignored) {}
        return body;
    }

    // ===================== 发请求 =====================

    /** 一次调用的结果 */
    static class Answer {
        int status;
        String body = "";
        /** 命中「账号被 ZCode 风控」或「网络被边缘防火墙拦」时为 true */
        boolean refused;
        boolean edge;
        String error = "";
    }

    /**
     * 发一次 Start Plan / gift 请求。
     * 注意 headers 是整组新建，不透传任何 agent 的头。
     */
    static Answer send(String jwt, String device, String site, String model,
                       JSONObject anthropicBody, String agentSessionId, int timeoutMs) {
        Answer out = new Answer();
        HttpURLConnection conn = null;
        try {
            String sid = sessionOf(agentSessionId, device);
            JSONObject body = dress(anthropicBody, site, model, device, sid);

            conn = NetworkPolicy.openHttp(START_BASE + "/v1/messages");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("HTTP-Referer", "https://zcode.z.ai");
            conn.setRequestProperty("User-Agent", UA + " ai-sdk/anthropic/3.0.81");
            conn.setRequestProperty("X-ZCode-App-Version", APP_VERSION);
            conn.setRequestProperty("X-Title", "Z Code@cli");
            conn.setRequestProperty("X-Release-Channel", "production");
            conn.setRequestProperty("X-Client-Language", locale());
            conn.setRequestProperty("X-Client-Timezone", timezone());
            conn.setRequestProperty("X-ZCode-Agent", "glm");
            conn.setRequestProperty("X-Platform", platform());
            conn.setRequestProperty("X-Os-Category", osCategory());
            conn.setRequestProperty("X-Os-Version", osRelease());
            conn.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-session-type", "main");
            conn.setRequestProperty("x-zcode-trace-id", UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-query-source", "main_turn");
            conn.setRequestProperty("x-session-id", sid);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            out.status = conn.getResponseCode();
            InputStream is = (out.status >= 200 && out.status < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
                is.close();
                out.body = new String(bo.toByteArray(), "UTF-8");
            }
            if (out.status >= 400) {
                out.refused = out.status == 405
                        || out.body.matches("(?is).*(unusual activity|request has been blocked|\"code\"\\s*:\\s*\"?3012\\b).*");
                out.edge = out.body.matches("(?is).*(errors\\.aliyun\\.com|<html).*");
                if (!out.refused) out.error = "HTTP " + out.status;
            }
        } catch (Exception e) {
            out.status = -1;
            out.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }


    // ===================== 额度支持的模型 =====================

    /**
     * 从 billing/balance 的 capabilities 里解析出 gift 真正支持的模型名。
     * 每个包的 capabilities 形如 ["model:glm-5.3-flash"]，注意还有 "tool:web-search" 这类非模型项。
     */
    static java.util.List<String> allowedModels(JSONObject summary) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        JSONArray buckets = summary == null ? null : summary.optJSONArray("buckets");
        for (int i = 0; buckets != null && i < buckets.length(); i++) {
            JSONObject b = buckets.optJSONObject(i);
            if (b == null) continue;
            long rem = b.optLong("remaining", -1);
            if (rem == 0) continue;              // 用光了就不列
            JSONArray caps = b.optJSONArray("capabilities");
            for (int k = 0; caps != null && k < caps.length(); k++) {
                String c = caps.optString(k, "");
                if (!c.startsWith("model:")) continue;
                String m = c.substring(6);
                if (!m.isEmpty() && !out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    /** 打开一条流式请求：返回的连接由调用方读 InputStream 并负责 disconnect */
    static class Stream {
        HttpURLConnection conn;
        int status;
        InputStream is;
        String errorBody = "";
        boolean refused;
        boolean edge;
        String error = "";
    }

    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject anthropicBody, String agentSessionId, int timeoutMs,
                             boolean dressBody) {
        Stream out = new Stream();
        try {
            String sid = sessionOf(agentSessionId, device);
            JSONObject body = dressBody
                    ? dress(anthropicBody, site, model, device, sid)
                    : new JSONObject(anthropicBody.toString());
            if (!dressBody) {
                // 不 dress 时也要补 metadata.user_id：BigModel 的 prompt cache 靠它
                JSONObject meta = body.optJSONObject("metadata");
                if (meta == null) meta = new JSONObject();
                meta.put("user_id", userIdOf(device, sid));
                body.put("metadata", meta);
            }

            HttpURLConnection conn = NetworkPolicy.openHttp(START_BASE + "/v1/messages");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("HTTP-Referer", "https://zcode.z.ai");
            conn.setRequestProperty("User-Agent", UA + " ai-sdk/anthropic/3.0.81");
            conn.setRequestProperty("X-ZCode-App-Version", APP_VERSION);
            conn.setRequestProperty("X-Title", "Z Code@cli");
            conn.setRequestProperty("X-Release-Channel", "production");
            conn.setRequestProperty("X-Client-Language", locale());
            conn.setRequestProperty("X-Client-Timezone", timezone());
            conn.setRequestProperty("X-ZCode-Agent", "glm");
            conn.setRequestProperty("X-Platform", platform());
            conn.setRequestProperty("X-Os-Category", osCategory());
            conn.setRequestProperty("X-Os-Version", osRelease());
            conn.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-session-type", "main");
            conn.setRequestProperty("x-zcode-trace-id", UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-query-source", "main_turn");
            conn.setRequestProperty("x-session-id", sid);
            if (body.optBoolean("stream", false)) {
                conn.setRequestProperty("Accept", "text/event-stream");
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            out.conn = conn;
            out.status = conn.getResponseCode();
            if (out.status >= 200 && out.status < 400) {
                out.is = conn.getInputStream();
            } else {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = es.read(buf)) != -1) bo.write(buf, 0, n);
                    es.close();
                    out.errorBody = new String(bo.toByteArray(), "UTF-8");
                }
                out.refused = out.status == 405
                        || out.errorBody.matches("(?is).*(unusual activity|request has been blocked|\"code\"\\s*:\\s*\"?3012\\b).*");
                out.edge = out.errorBody.matches("(?is).*(errors\\.aliyun\\.com|<html).*");
                out.error = "HTTP " + out.status;
            }
        } catch (Exception e) {
            out.status = -1;
            out.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
        return out;
    }

    /** 把 Anthropic 的 message 响应压成纯文本（给网关转 OpenAI 用） */
    static String textOf(JSONObject msg) {
        if (msg == null) return "";
        JSONArray content = msg.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            if ("text".equals(c.optString("type"))) sb.append(c.optString("text", ""));
        }
        return sb.toString();
    }

    /** 把 Anthropic 的 thinking 块压出来 */
    static String thinkingOf(JSONObject msg) {
        if (msg == null) return "";
        JSONArray content = msg.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            if ("thinking".equals(c.optString("type"))) sb.append(c.optString("thinking", ""));
        }
        return sb.toString();
    }
}
