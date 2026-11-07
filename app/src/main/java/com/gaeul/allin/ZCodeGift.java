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
            if (!meta.has("user_id") && device != null && !device.trim().isEmpty() && session != null && !session.trim().isEmpty()) meta.put("user_id", userIdOf(device, session));
            body.put("metadata", meta);
        } catch (Exception ignored) {}
        return body;
    }

    // ===================== 发请求 =====================

    private static final String POLICY_URL = START_BASE + "/v1/messages";
    private static volatile String fixtureBase;
    private static volatile ZCodeRefusalPolicy cooldown = new ZCodeRefusalPolicy();
    private static final OutboundLimiter limiter = new OutboundLimiter(1, 32);
    static {
        java.util.Map<String, OutboundLimiter.Policy> policies = new java.util.HashMap<>();
        policies.put("zcode-gift", new OutboundLimiter.Policy(0, 0, 8, 16, 30000));
        limiter.configure(policies);
    }
    static void resetCooldownForTests() { cooldown = new ZCodeRefusalPolicy(); }
    static void setCooldownClockForTests(ZCodeRefusalPolicy.Clock clock) { cooldown = new ZCodeRefusalPolicy(clock); }
    static void setFixtureBase(String base) {
        if (base == null) { fixtureBase = null; return; }
        try {
            java.net.URI uri = new java.net.URI(base);
            String h = uri.getHost();
            if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)))
                throw new IllegalArgumentException("loopback fixture required");
            fixtureBase = base;
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("invalid fixture"); }
    }
    /** Only call for a request known to use gift; this may suppress unrelated plan routing. */
    static ZCodeRefusalPolicy.Hold hold(String jwt, String device, RequestContext ctx) {
        NetworkPolicy.Snapshot network = ctx != null && ctx.network != null ? ctx.network : NetworkPolicy.capture();
        return cooldown.before(POLICY_URL, accountScope(jwt, device), network.toJson().toString());
    }
    private static String accountScope(String jwt, String device) {
        return jwt == null || jwt.trim().isEmpty() || device == null || device.trim().isEmpty() ? "" : jwt + "\0" + device;
    }
    private static void checkRead(HttpURLConnection conn, RequestContext ctx, int timeoutMs) throws java.io.IOException {
        if (ctx != null) {
            ctx.check();
            conn.setReadTimeout((int)Math.max(1, Math.min(Math.max(1, timeoutMs), ctx.remainingMillis())));
        }
    }
    static class Answer {
        int status; String body = ""; boolean refused, edge, held, sent;
        long retryAfterMillis; ZCodeRefusalPolicy.Kind classification = ZCodeRefusalPolicy.Kind.NONE;
        String error = "";
    }
    static Answer send(String jwt, String device, String site, String model,
                       JSONObject body, String session, int timeoutMs) {
        return send(jwt, device, site, model, body, session, timeoutMs, null);
    }
    static Answer send(String jwt, String device, String site, String model,
                       JSONObject body, String session, int timeoutMs, RequestContext ctx) {
        Stream stream = openStream(jwt, device, site, model, body, session, timeoutMs, true, ctx);
        Answer out = new Answer();
        out.status=stream.status; out.body=stream.errorBody; out.refused=stream.refused; out.edge=stream.edge;
        out.held=stream.held; out.sent=stream.sent; out.retryAfterMillis=stream.retryAfterMillis;
        out.classification=stream.classification; out.error=stream.error;
        if (stream.is != null) try {
            byte[] bytes = readBounded(stream.is, 8 * 1024 * 1024);
            if (bytes == null) { out.status=-1; out.body=""; out.error="Response exceeds local limit"; }
            else out.body = new String(bytes, "UTF-8");
        } catch (Exception ignored) { out.status=-1; out.error="ZCode transport failed"; }
        finally { try { stream.is.close(); } catch (Exception ignored) {} }
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

    static class Stream {
        HttpURLConnection conn; int status; InputStream is; String errorBody = "";
        boolean refused, edge, held, sent; long retryAfterMillis;
        ZCodeRefusalPolicy.Kind classification = ZCodeRefusalPolicy.Kind.NONE;
        String error = "";
    }
    private static byte[] readBounded(InputStream in, int limit) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n=in.read(buf)) != -1) { if (out.size() + n > limit) return null; out.write(buf,0,n); }
        return out.toByteArray();
    }
    private static InputStream decoded(final HttpURLConnection c, InputStream input, final RequestContext ctx, final int timeoutMs) throws java.io.IOException {
        if (input == null) return null;
        InputStream guarded = new java.io.FilterInputStream(input) {
            @Override public int read() throws java.io.IOException {
                checkRead(c, ctx, timeoutMs); int n = in.read(); if (ctx != null) ctx.check(); return n;
            }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                checkRead(c, ctx, timeoutMs); int n = in.read(b, off, len); if (ctx != null) ctx.check(); return n;
            }
        };
        return "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new java.util.zip.GZIPInputStream(guarded) : guarded;
    }
    private static String localError(String message, long retry) {
        JSONObject error = new JSONObject(); JSONObject root = new JSONObject();
        try { error.put("type", "local_cooldown"); error.put("message", message); error.put("retry_after_ms", retry); root.put("error", error); }
        catch (Exception ignored) {} return root.toString();
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject body, String session, int timeoutMs, boolean dressBody) {
        return openStream(jwt, device, site, model, body, session, timeoutMs, dressBody, null);
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject request, String session, int timeoutMs, boolean dressBody, RequestContext ctx) {
        Stream out = new Stream(); HttpURLConnection conn = null; OutboundLimiter.Ticket ticket = null;
        Runnable cleanup = null; boolean owned = false;
        try {
            NetworkPolicy.Snapshot network = ctx != null && ctx.network != null ? ctx.network : NetworkPolicy.capture();
            String account = accountScope(jwt, device);
            String scope = network.toJson().toString();
            ZCodeRefusalPolicy.Hold hold = cooldown.before(POLICY_URL, account, scope);
            if (hold != null) {
                out.status=429; out.held=true; out.retryAfterMillis=hold.retryAfterMillis; out.classification=hold.kind;
                out.errorBody=localError(hold.message, hold.retryAfterMillis); out.error="Local cooldown"; return out;
            }
            if (ctx != null) ctx.check();
            OutboundLimiter.Result admission = limiter.await(java.util.Collections.singleton("zcode-gift"),
                    ctx == null ? System.nanoTime()+30000000000L : ctx.deadlineNanos, ctx);
            if (admission.ticket == null) { out.status=429; out.error="Local outbound limit"; return out; }
            ticket=admission.ticket; final OutboundLimiter.Ticket ownedTicket=ticket;
            cleanup = () -> ownedTicket.close(); if (ctx != null) ctx.onClose(cleanup);
            // Recheck after queueing, before touching request or opening a connection.
            hold=cooldown.before(POLICY_URL,account,scope);
            if (hold != null) { out.status=429; out.held=true; out.retryAfterMillis=hold.retryAfterMillis;
                out.classification=hold.kind; out.errorBody=localError(hold.message,hold.retryAfterMillis); out.error="Local cooldown"; return out; }
            String sid=sessionOf(session,device);
            JSONObject body=dressBody ? dress(new JSONObject(request.toString()),site,model,device,sid) : ZCodeRequestPolicy.marked(request,device,sid);
            byte[] bytes=body.toString().getBytes("UTF-8");
            String base=fixtureBase; conn=network.openHttp(base == null ? POLICY_URL : base + "/v1/messages");
            if (ctx != null) { ctx.attach(conn); ctx.check(); }
            conn.setInstanceFollowRedirects(false); conn.setRequestMethod("POST"); conn.setDoOutput(true);
            int budget=ctx == null ? Integer.MAX_VALUE : (int)Math.max(1,Math.min(Integer.MAX_VALUE,ctx.remainingMillis()));
            conn.setConnectTimeout(Math.min(20000,budget)); conn.setReadTimeout(Math.max(1,Math.min(timeoutMs,budget)));
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.setRequestProperty("content-type","application/json"); conn.setRequestProperty("Accept-Encoding","gzip");
            conn.setRequestProperty("Authorization","Bearer " + jwt); conn.setRequestProperty("anthropic-version","2023-06-01");
            conn.setRequestProperty("HTTP-Referer","https://zcode.z.ai"); conn.setRequestProperty("User-Agent",UA + " ai-sdk/anthropic/3.0.81");
            conn.setRequestProperty("X-ZCode-App-Version",APP_VERSION); conn.setRequestProperty("X-Title","Z Code@cli");
            conn.setRequestProperty("X-Release-Channel","production"); conn.setRequestProperty("X-Client-Language",locale());
            conn.setRequestProperty("X-Client-Timezone",timezone()); conn.setRequestProperty("X-ZCode-Agent","glm");
            conn.setRequestProperty("X-Platform",platform()); conn.setRequestProperty("X-Os-Category",osCategory());
            conn.setRequestProperty("X-Os-Version",osRelease()); conn.setRequestProperty("x-request-id",UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-session-type","main"); conn.setRequestProperty("x-zcode-trace-id",UUID.randomUUID().toString());
            conn.setRequestProperty("x-zcode-query-source","main_turn"); conn.setRequestProperty("x-session-id",sid);
            if (body.optBoolean("stream",false)) conn.setRequestProperty("Accept","text/event-stream");
            if (ctx != null) ctx.check(); ticket.markSent(); out.sent=true;
            try (OutputStream os=conn.getOutputStream()) { if (ctx != null) ctx.check(); os.write(bytes); if (ctx != null) ctx.check(); }
            if (ctx != null) ctx.check();
            if (ctx != null) { ctx.check(); conn.setReadTimeout((int)Math.max(1,Math.min(timeoutMs,ctx.remainingMillis()))); }
            out.status=conn.getResponseCode();
            if (out.status == 429) {
                String retry = ProviderRetryPolicy.safeRetryAfter(conn.getHeaderField("Retry-After"));
                if (retry != null) out.retryAfterMillis = Long.parseLong(retry) * 1000L;
            }
            if (out.status < 200 || out.status >= 300) {
                InputStream es=decoded(conn,conn.getErrorStream(),ctx,timeoutMs); byte[] data=null;
                if (es != null) try (InputStream input=es) { data=readBounded(input,65536); }
                out.errorBody=data == null ? "" : new String(data,"UTF-8");
                out.classification=cooldown.observe(POLICY_URL,account,scope,out.status,data == null ? null : out.errorBody);
                out.refused=out.classification==ZCodeRefusalPolicy.Kind.ACCOUNT || out.classification==ZCodeRefusalPolicy.Kind.EDGE;
                out.edge=out.classification==ZCodeRefusalPolicy.Kind.EDGE; out.error="HTTP " + out.status; return out;
            }
            InputStream input=decoded(conn,conn.getInputStream(),ctx,timeoutMs);
            String contentType=conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
                byte[] data; try (InputStream read=input) { data=readBounded(read,8*1024*1024); }
                if (data == null) { out.status=-1; out.error="Response exceeds local limit"; return out; }
                String text=new String(data,"UTF-8");
                if (data.length <= 65536 && ZCodeRefusalPolicy.classify(405,text)==ZCodeRefusalPolicy.Kind.ACCOUNT) {
                    out.status=405; out.errorBody=text; out.classification=cooldown.observe(POLICY_URL,account,scope,405,text);
                    out.refused=true; out.error="HTTP 405"; return out;
                }
                input=new java.io.ByteArrayInputStream(data);
            }
            final HttpURLConnection c=conn; final Runnable release=cleanup; final RequestContext context=ctx;
            out.conn=conn; out.is=new java.io.FilterInputStream(input) {
                boolean closed;
                private void release() { if (closed) return; closed=true; try { super.close(); } catch (Exception ignored) {}
                    if (context != null) { context.detach(c); context.removeCleanup(release); } c.disconnect(); release.run(); }
                @Override public int read() throws java.io.IOException {
                    try { checkRead(c,context,timeoutMs); int n=in.read(); if (context != null) context.check(); if (n<0) release(); return n; }
                    catch (java.io.IOException | RuntimeException e) { release(); throw e; }
                }
                @Override public int read(byte[] b,int off,int len) throws java.io.IOException {
                    try { checkRead(c,context,timeoutMs); int n=in.read(b,off,len); if (context != null) context.check(); if (n<0) release(); return n; }
                    catch (java.io.IOException | RuntimeException e) { release(); throw e; }
                }
                @Override public void close() { release(); }
            }; owned=true; return out;
        } catch (Exception e) { out.status=-1; out.error="ZCode transport failed"; return out; }
        finally { if (!owned) { if (ctx != null && conn != null) ctx.detach(conn); if (conn != null) conn.disconnect();
            if (ctx != null && cleanup != null) ctx.removeCleanup(cleanup); if (ticket != null) ticket.close(); out.conn=null; } }
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
