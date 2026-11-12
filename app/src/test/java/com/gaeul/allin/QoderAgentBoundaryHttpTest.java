package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Production gateway boundary checks, using the existing isolated loopback fixture. */
public final class QoderAgentBoundaryHttpTest {
    private static final Class<?> ROOT = QoderAgentGatewayHttpTest.class;
    private static final Class<?> UP = nested("Upstream"), GW = nested("Gateway");
    private static Class<?> nested(String n) {
        for (Class<?> c : ROOT.getDeclaredClasses()) if (c.getSimpleName().equals(n)) return c;
        throw new AssertionError(n);
    }
    private static Object construct(Class<?> c, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> x = c.getDeclaredConstructor(types); x.setAccessible(true); return x.newInstance(args);
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ROOT.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    private static String sse() throws Exception {
        String events = QoderAgentGatewayHttpTest.delta(new JSONObject().put("content", "boundary-ok"), null)
                + QoderAgentGatewayHttpTest.delta(new JSONObject(), "stop") + QoderAgentGatewayHttpTest.done();
        return (String)invoke("sse", new Class<?>[]{String.class}, events);
    }
    /** 上游与备份都起来之后、网关起来之前，按用例改配置（中间件 / 路由组 / 供应商）。 */
    private interface Tuning { void apply(JSONObject config) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final Object upstream, backup, gateway;
        Fixture(Boolean vision) throws Exception { this(vision, false, false); }
        Fixture(Boolean vision, boolean mismatch, boolean cap) throws Exception {
            this(vision, mismatch, cap ? new JSONObject().put("enabled", true).put("maxOutputTokens", 63) : null, null, null);
        }
        /** 只配上下文保护：用来证明历史改由 apply 解析之后，guard 仍然按预算裁剪。 */
        Fixture(int maxBodyBytes) throws Exception {
            this(Boolean.TRUE, false, new JSONObject().put("enabled", true).put("maxBodyBytes", maxBodyBytes)
                    .put("maxContextTokens", 2000).put("keepRecent", 1).put("reserveTokens", 256), null, null);
        }
        /** 只配隐私打码：用来证明历史改由 apply 解析之后，打码仍发生在它之前。 */
        Fixture(JSONObject privacy) throws Exception {
            this(Boolean.TRUE, false, null, privacy, null);
        }
        Fixture(Tuning tuning) throws Exception { this(Boolean.TRUE, false, null, null, tuning); }
        Fixture(Boolean vision, boolean mismatch, JSONObject guard, JSONObject privacy) throws Exception {
            this(vision, mismatch, guard, privacy, null);
        }
        Fixture(Boolean vision, boolean mismatch, JSONObject guard, JSONObject privacy, Tuning tuning) throws Exception {
            upstream = construct(UP, new Class<?>[]{String.class}, sse());
            backup = construct(UP, new Class<?>[]{String.class}, sse());
            JSONObject config = (JSONObject)invoke("config", new Class<?>[]{UP}, backup);
            JSONObject meta = config.getJSONArray("providers").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("fixture-model");
            if (vision == null) meta.remove("is_vl"); else meta.put("is_vl", vision);
            meta.put("api_key", "listing-secret").put("url", "https://listing.invalid/secret")
                .put("minimal_version", "99.0").put("price", 12345).put("private_listing_field", "private-marker");
            if (mismatch) meta.put("key", "different-model");
            if (guard != null) config.put("guard", guard);
            if (privacy != null) config.put("privacy", privacy);
            if (tuning != null) tuning.apply(config);
            config.getJSONObject("failover").put("enabled", false);
            gateway = construct(GW, new Class<?>[]{JSONObject.class, UP}, config, upstream);
        }
        int calls() throws Exception { return ((java.util.concurrent.atomic.AtomicInteger)field(upstream,"calls")).get(); }
        int backupCalls() throws Exception { return ((java.util.concurrent.atomic.AtomicInteger)field(backup,"calls")).get(); }
        JSONObject sent() throws Exception { return new JSONObject(QoderAuth.decodeBody((String)field(upstream,"lastRequest"))); }
        /** 非 Qoder 那台上游收到的是普通 HTTP JSON（没有 COSY 信封），原样读回来。 */
        JSONObject backupSent() throws Exception { return new JSONObject((String)field(backup,"lastRequest")); }
        /** 让非 Qoder 上游回一段普通的 OpenAI 响应（默认的 Qoder 信封它读不了）。 */
        void backupReplies(String raw) throws Exception {
            java.lang.reflect.Field reply = backup.getClass().getDeclaredField("reply");
            reply.setAccessible(true); reply.set(backup, raw);
        }
        Result post(String path, JSONObject body, String session) throws Exception {
            int port = ((NanoHTTPD)gateway).getListeningPort();
            HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + path).openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(4000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer fixture-auth"); c.setRequestProperty("Content-Type", "application/json");
            if (session != null) c.setRequestProperty("x-session-id", session);
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode(); InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) try (InputStream source = in) { byte[] b = new byte[4096]; int n; while ((n = source.read(b)) != -1) out.write(b,0,n); }
            c.disconnect(); return new Result(status, out.toString("UTF-8"));
        }
        public void close() throws Exception {
            ((AutoCloseable)gateway).close(); ((AutoCloseable)upstream).close(); ((AutoCloseable)backup).close();
        }
    }
    private static final class Result { final int status; final String body; Result(int s,String b){status=s;body=b;} }
    private static JSONObject request() {
        return new JSONObject().put("model","qoder_cn/fixture-model").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
    }
    private static JSONObject image(String data) { return new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",data)); }
    /** 一段普通的 OpenAI 非流式响应（原样 HTTP），给非 Qoder 上游用。 */
    private static String openAiReply(String content) throws Exception {
        String body = new JSONObject().put("id","chatcmpl-fixture").put("object","chat.completion")
                .put("choices", new JSONArray().put(new JSONObject().put("index",0)
                        .put("message", new JSONObject().put("role","assistant").put("content",content))
                        .put("finish_reason","stop"))).toString();
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
    }
    /** Anthropic 协议形状的图片块（/v1/messages 客户端发的那一种）。 */
    private static JSONObject nativeImage(String base64) throws Exception {
        return new JSONObject().put("type","image")
                .put("source", new JSONObject().put("type","base64").put("media_type","image/png").put("data",base64));
    }
    private static void images() throws Exception {
        String user = "data:image/png;base64,dXNlci1ieXRlcw==", tool = "data:image/png;base64,dG9vbC1ieXRlcw==";
        JSONObject call = new JSONObject().put("id","call-1").put("type","function").put("function",new JSONObject().put("name","read").put("arguments","{}"));
        JSONObject req = request().put("messages",new JSONArray()
                .put(new JSONObject().put("role","user").put("content",new JSONArray().put(image(user))))
                .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(call)))
                .put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content",new JSONArray().put(image(tool)))));
        try (Fixture f = new Fixture(true)) {
            Result r = f.post("/v1/chat/completions",req,null);
            check(r.status == 200,"vision status " + r.status + " " + r.body);
            String wire = f.sent().toString();
            check(wire.contains(user) && wire.contains(tool),"image data changed or lost: " + wire);
            check(wire.contains("call-1"),"tool result association lost");
            JSONObject sent = f.sent(), model = sent.getJSONObject("model_config");
            check("fixture-model".equals(model.getString("key")), "selected model_config key");
            check("fixture-model".equals(sent.getJSONObject("business").getString("model_key")), "business model key");
            for (String key : new String[]{"minimal_version", "price", "thinking_config", "private_listing_field"})
                check(!model.has(key), "raw listing field leaked: " + key);
            check("".equals(model.getString("api_key")) && "".equals(model.getString("url")), "listing credentials leaked");
            check(!wire.contains("listing-secret") && !wire.contains("listing.invalid") && !wire.contains("private-marker"), "raw listing leaked");
        }
        for (Boolean vision : new Boolean[]{false,null}) try (Fixture f = new Fixture(vision)) {
            Result r = f.post("/v1/chat/completions",req,null);
            check(r.status == 400,"non-vision status " + r.status + " " + r.body);
            check(f.calls() == 0,"non-vision reached upstream");
        }
    }
    private static void localGuards() throws Exception {
        try (Fixture f = new Fixture(true)) {
            // parallel_tool_calls=false 只有在真的有 tools 会下发时才做不到：那时必须明说，不能悄悄丢掉。
            // 没有 tools / tool_choice:none 时不可能并行，标志是空操作 —— 那两条是接受路径，见下面的正例。
            /* 工具历史缺 id/错配不再是本地下限：0.2.64 的 qoderMessages 会补齐或配对，见下面
             * repairedHistories() 的正例。这里只剩插件根本收不下的形状。 */
            JSONObject[] bad = { request().put("tools",tools()).put("parallel_tool_calls",false),
                    request().put("parallel_tool_calls","false"),
                    request().put("tool_choice","required"),
                    request().put("messages",new JSONArray().put(new JSONObject().put("role","tool").put("tool_call_id",5).put("content","x"))) };
            for (JSONObject req : bad) {
                Result r = f.post("/v1/chat/completions",req,null);
                check(r.status == 400,"guard status " + r.status + " " + r.body);
                check(f.calls() == 0,"guard reached upstream");
                if (Boolean.FALSE.equals(req.opt("parallel_tool_calls")))
                    check(r.body.contains("cannot be enforced"),"the refusal has to say why: " + r.body);
            }
            JSONObject signed = new JSONObject().put("model","qoder_cn/fixture-model").put("max_tokens",128)
                    .put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","reason").put("signature","signed-native")))));
            Result r = f.post("/v1/messages",signed,null);
            check(r.status == 400,"signed history status " + r.status + " " + r.body);
            check(f.calls() == 0,"signed history reached upstream");

            // 短预算没有本地下限（本地 0.2.64 index.mjs:1502-1506 只向下收到模型上限），原样下发。
            Result shortBudget = f.post("/v1/chat/completions", request().put("max_tokens",63), null);
            check(shortBudget.status == 200, "short budget status " + shortBudget.status + " " + shortBudget.body);
            check(f.sent().getJSONObject("parameters").getInt("max_tokens") == 63,
                    "short budget not forwarded as asked: " + f.sent().getJSONObject("parameters"));
            // 没有 tools 时 parallel_tool_calls=false 是空操作，不该被拒。
            Result noTools = f.post("/v1/chat/completions", request().put("parallel_tool_calls",false), null);
            check(noTools.status == 200, "vacuous parallel flag status " + noTools.status + " " + noTools.body);
            Result noneChoice = f.post("/v1/chat/completions",
                    request().put("tools",tools()).put("tool_choice","none").put("parallel_tool_calls",false), null);
            check(noneChoice.status == 200, "tool_choice none status " + noneChoice.status + " " + noneChoice.body);
        }
    }
    private static JSONArray tools() throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",
                new JSONObject().put("name","read").put("parameters",new JSONObject().put("type","object"))));
    }
    private static void configurationGuards() throws Exception {
        try (Fixture f = new Fixture(true, true, false)) {
            Result r = f.post("/v1/chat/completions", request(), null);
            check(r.status == 400, "mismatched catalog key status " + r.status + " " + r.body);
            check(f.calls() == 0, "mismatched catalog key reached upstream");
        }
        try (Fixture f = new Fixture(true, false, true)) {
            Result r = f.post("/v1/chat/completions", request().put("max_tokens", 128), null);
            // 守卫把 128 下调到 63：63 是合法预算（本地没有下限），所以照常转发，而不是本地 400。
            check(r.status == 200, "guard output cap status " + r.status + " " + r.body);
            check(f.calls() == 1, "guard output cap did not reach upstream");
            check(f.sent().getJSONObject("parameters").getInt("max_tokens") == 63,
                    "guard output cap not forwarded: " + f.sent().getJSONObject("parameters"));
        }
    }
    private static void sessions() throws Exception {
        try (Fixture f = new Fixture(true)) {
            String name = "boundary-session-" + java.util.UUID.randomUUID();
            check(f.post("/v1/chat/completions",request(),name).status == 200,"first session");
            JSONObject first = f.sent(), firstBusiness = first.getJSONObject("business");
            String a = first.getString("session_id"), task = first.getString("request_set_id");
            long begin = firstBusiness.getLong("begin_at");
            check("start".equals(firstBusiness.getString("stage")), "first task stage");
            check(task.equals(firstBusiness.getString("id")), "first business task id");
            check(f.post("/v1/chat/completions",request(),name).status == 200,"second session");
            JSONObject second = f.sent(), secondBusiness = second.getJSONObject("business");
            check(a.equals(second.getString("session_id")),"named session changed");
            check(task.equals(second.getString("request_set_id")) && task.equals(secondBusiness.getString("id")), "same prompt task changed");
            check(begin == secondBusiness.getLong("begin_at"), "same prompt begin changed");
            check("processing".equals(secondBusiness.getString("stage")), "same prompt stage");
            JSONObject next = request().put("messages", new JSONArray()
                    .put(new JSONObject().put("role","user").put("content","hello"))
                    .put(new JSONObject().put("role","assistant").put("content","ok"))
                    .put(new JSONObject().put("role","user").put("content","next")));
            check(f.post("/v1/chat/completions",next,name).status == 200,"next turn");
            JSONObject third = f.sent(), thirdBusiness = third.getJSONObject("business");
            check(a.equals(third.getString("session_id")), "next turn session changed");
            check(!task.equals(third.getString("request_set_id")), "next turn reused task");
            check(third.getString("request_set_id").equals(thirdBusiness.getString("id")), "next business task id");
            check("start".equals(thirdBusiness.getString("stage")), "next turn stage");
            // 客户端不命名会话时，会话不再每次新来一个：本地 0.2.64 index.mjs:1339-1350 的 sessionOf
            // 退到对话开场（system 与第一条 user）的哈希，所以同一段开场必须是同一个 session_id，
            // 换一段开场才是另一段对话；taskOf（:1393-1412）以会话、最后一条 user 的下标与内容做键，
            // 于是同一开场同一提问是同一个 request_set_id，换了提问才是新的。
            String opening = "anonymous-opening-" + java.util.UUID.randomUUID();
            String other = "anonymous-other-" + java.util.UUID.randomUUID();
            check(f.post("/v1/chat/completions",turn(opening),null).status == 200,"anonymous session 1");
            JSONObject anonFirst = f.sent(); String c = anonFirst.getString("session_id");
            check("start".equals(anonFirst.getJSONObject("business").getString("stage")),"anonymous first task stage");
            check(f.post("/v1/chat/completions",turn(opening),null).status == 200,"anonymous session 2");
            JSONObject anonSecond = f.sent(); String d = anonSecond.getString("session_id");
            check(c.equals(d),"anonymous conversation got a new session per request");
            check(anonFirst.getString("request_set_id").equals(anonSecond.getString("request_set_id"))
                    && anonFirst.getJSONObject("business").getLong("begin_at") == anonSecond.getJSONObject("business").getLong("begin_at"),
                    "the same anonymous prompt became a second task");
            // 同一匿名对话的下一轮：开场没变，会话跟着走；新的提问是新的 task。
            check(f.post("/v1/chat/completions",turn(opening,"ok","follow-up"),null).status == 200,"anonymous next turn");
            JSONObject anonThird = f.sent();
            check(c.equals(anonThird.getString("session_id")),"anonymous next turn changed session");
            check(!anonFirst.getString("request_set_id").equals(anonThird.getString("request_set_id")),"anonymous next turn reused task");
            // 负对照：另一段开场是另一段对话，会话与 task 都不许撞上上一段。
            check(f.post("/v1/chat/completions",turn(other),null).status == 200,"anonymous other conversation");
            JSONObject anonOther = f.sent();
            check(!c.equals(anonOther.getString("session_id")),"different anonymous conversation shared a session");
            check(!anonFirst.getString("request_set_id").equals(anonOther.getString("request_set_id")),
                    "different anonymous conversation shared a task");
            check(!anonThird.getString("request_set_id").equals(anonOther.getString("request_set_id")),
                    "another conversation's next turn shared a task");
        }
    }
    /** 客户端丢了 tool id 的历史走到哪一步。0.2.64 的 qoderMessages 会确定性补齐并按顺序配对，
     *  插件从不因为这件事把请求打回（index.mjs:1156-1200, :1250-1285）。
     *
     *  <p>接线之后（GatewayServer.qoderOwnHistory）：一个请求能落到的候选**全是** qoder 适配器时，
     *  历史原样交给 QoderProtocol.apply，由它逐条实现插件的语义；通用严格规范化
     *  （AnthropicBridge.pairToolMessages）不再碰它。所以这里钉的是「真实 HTTP 环回之后，
     *  Qoder 那条线上到底收到了什么」：
     *  <ul>
     *   <li>丢 id 的调用拿到 qoderMessages 那个确定性 id（call_ + 32 hex，每次请求一致）；</li>
     *   <li>丢 id／点错名的结果回答本回合最早还没被回答、也没被别的结果点名的那个调用；</li>
     *   <li>没有调用可回答的结果变成本人回合的文字，**不**补假结果；</li>
     *   <li>同一调用被回答两次：第一份照常回答，第二份变成本人回合的文字（不丢字）；</li>
     *   <li>没人回答的调用原样留着（插件也不替它造结果），线上不出现
     *       「The result of this tool call is unavailable」这类伪造；</li>
     *   <li>工具结果的图片：tool 回合只留文字加一句提示，图片连同「[From the result of …]」挪进
     *       下一段用户回合（插件 index.mjs:1278-1284）。</li>
     *  </ul>
     *  <p>反向：混组（Qoder + 非 Qoder 兜底）、纯非 Qoder 模型、数字 tool_call_id —— 走原来那条严格
     *  规范化，状态码与报文不认、一个字节不上行。 */
    private static final class History {
        final String name;
        final JSONArray messages;
        History(String name, JSONArray messages) { this.name = name; this.messages = messages; }
    }
    private static JSONObject call(String id, String name) throws Exception {
        JSONObject call = new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", "{}"));
        if (id != null) call.put("id", id);
        return call;
    }
    private static JSONObject assistantCall(String id, String name) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(call(id, name)));
    }
    private static JSONObject toolResult(String id, String text) throws Exception {
        JSONObject message = new JSONObject().put("role", "tool").put("content", text);
        if (id != null) message.put("tool_call_id", id);
        return message;
    }
    private static JSONArray messages(JSONObject... items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) array.put(item);
        return array;
    }
    private static java.util.List<History> histories() throws Exception {
        java.util.List<History> out = new java.util.ArrayList<History>();
        out.add(new History("lost-call-id-and-result", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall(null, "read"),
                toolResult(null, "tool-said-ok"))));
        out.add(new History("lost-result-id", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall("a", "read"),
                toolResult(null, "ok-a"))));
        out.add(new History("unknown-result-id", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall("a", "read"),
                toolResult("zzz", "ok-z"))));
        out.add(new History("orphan-result", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                toolResult("missing", "orphan-ok"))));
        out.add(new History("duplicate-answer", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall("a", "read"),
                toolResult("a", "first-answer"),
                toolResult("a", "second-answer"))));
        out.add(new History("unanswered-call", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall("a", "read"))));
        out.add(new History("tool-result-images", messages(
                new JSONObject().put("role", "user").put("content", "hello"),
                assistantCall("call-1", "read"),
                new JSONObject().put("role", "tool").put("tool_call_id", "call-1")
                        .put("content", new JSONArray().put(image("data:image/png;base64,dG9vbC1ieXRlcw=="))))));
        return out;
    }
    /** 线上那串 messages 里文字与图片的全貌，用来断言「没丢、没造」。 */
    private static String wireText(JSONArray wire) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < wire.length(); i++) {
            JSONObject message = wire.optJSONObject(i);
            if (message == null) continue;
            text.append('|').append(message.optString("role", "")).append(':').append(message.opt("content"));
            JSONArray calls = message.optJSONArray("tool_calls");
            for (int k = 0; calls != null && k < calls.length(); k++)
                text.append("|call=").append(calls.optJSONObject(k).optString("id", ""));
            if (message.has("tool_call_id")) text.append("|answers=").append(message.optString("tool_call_id", ""));
        }
        return text.toString();
    }
    private static JSONArray wireOf(JSONObject sent) throws Exception {
        JSONArray wire = sent.optJSONArray("messages");
        check(wire != null && wire.length() > 1, "Qoder body carries no history: " + sent);
        check("system".equals(wire.getJSONObject(0).optString("role")), "the gateway's system turn is missing");
        return wire;
    }
    private static JSONObject firstCall(JSONArray wire) throws Exception {
        for (int i = 0; i < wire.length(); i++) {
            JSONObject message = wire.optJSONObject(i);
            JSONArray calls = message == null ? null : message.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) return calls.getJSONObject(0);
        }
        throw new AssertionError("no tool call went out: " + wireText(wire));
    }
    private static JSONObject firstToolTurn(JSONArray wire) {
        for (int i = 0; i < wire.length(); i++)
            if ("tool".equals(wire.optJSONObject(i).optString("role"))) return wire.optJSONObject(i);
        throw new AssertionError("no tool turn went out: " + wireText(wire));
    }
    private static JSONObject turnAfter(JSONArray wire, JSONObject turn) {
        for (int i = 0; i < wire.length() - 1; i++) if (wire.optJSONObject(i) == turn) return wire.optJSONObject(i + 1);
        throw new AssertionError("nothing follows the tool turn: " + wireText(wire));
    }
    private static int toolTurns(JSONArray wire) {
        int count = 0;
        for (int i = 0; i < wire.length(); i++)
            if ("tool".equals(wire.optJSONObject(i).optString("role"))) count++;
        return count;
    }
    private static void repairedHistories() throws Exception {
        try (Fixture f = new Fixture(true)) {
            /* 空 tool_calls 数组不是「丢了 id 的病史」：插件当成没有调用的那一轮（index.mjs:1251），
             * 前置归一化也这么看（AnthropicBridge:503），所以这里必须照常放行，而不是本地 400。 */
            JSONObject emptyCalls = request().put("messages", new JSONArray()
                    .put(new JSONObject().put("role","user").put("content","hello"))
                    .put(new JSONObject().put("role","assistant").put("content","x").put("tool_calls",new JSONArray())));
            Result r = f.post("/v1/chat/completions", emptyCalls, null);
            check(r.status == 200, "empty tool_calls status " + r.status + " " + r.body);
            JSONObject turned = f.sent().getJSONArray("messages").getJSONObject(2);
            check("assistant".equals(turned.getString("role")) && !turned.has("tool_calls")
                    && turned.getJSONArray("content").getJSONObject(0).getString("text").equals("x"),
                    "a turn with no calls has to go out as the assistant's own words: " + f.sent());

            for (History history : histories()) {
                int before = f.calls();
                Result response = f.post("/v1/chat/completions", request().put("messages", history.messages), null);
                check(response.status == 200, history.name + " was refused: " + response.status + " " + response.body);
                check(f.calls() == before + 1, history.name + " did not reach Qoder exactly once");
                JSONArray wire = wireOf(f.sent());
                String text = wireText(wire);
                /* 严格规范化给没人回答的调用造的那句假结果，任何一条夹具都不许出现在线上。 */
                check(!text.contains("unavailable"), history.name + " invented a result: " + text);
                check(!text.contains("interrupted"), history.name + " invented a result: " + text);
                if ("lost-call-id-and-result".equals(history.name)) {
                    /* 丢 id 的调用拿到插件的确定性 id：call_ + 32 hex；结果回答的就是它。 */
                    String id = firstCall(wire).getString("id");
                    check(id.startsWith("call_") && id.length() == 37, "call id is not qoderMessages': " + id);
                    JSONObject answered = firstToolTurn(wire);
                    check(answered.getString("tool_call_id").equals(id), "the result answered another call: " + text);
                    check(answered.getString("content").contains("tool-said-ok"), "the result text was lost: " + text);
                    /* 同一份历史再问一次，id 必须一模一样（插件 #290 的提示缓存前提）。 */
                    check(f.post("/v1/chat/completions", request().put("messages", history.messages), null).status == 200,
                            "second request of the same history was refused");
                    String again = firstCall(f.sent().getJSONArray("messages")).getString("id");
                    check(id.equals(again), "the same history went out under another id: " + id + " vs " + again);
                } else if ("lost-result-id".equals(history.name) || "unknown-result-id".equals(history.name)) {
                    check(firstCall(wire).getString("id").equals("a"),
                            "the client's own call id was rewritten: " + text);
                    check(firstToolTurn(wire).getString("tool_call_id").equals("a"),
                            history.name + " did not answer the earliest open call: " + text);
                } else if ("orphan-result".equals(history.name)) {
                    /* 没有调用可回答：结果变成本人回合的文字，不补假的是前提（上面已断言）。 */
                    check(toolTurns(wire) == 0, "an orphan result was sent as a tool turn: " + text);
                    check(text.contains("orphan-ok"), "the orphan result's words were lost: " + text);
                    check("user".equals(wire.getJSONObject(wire.length() - 1).getString("role")),
                            "an orphan result has to stay in the user's own turn: " + text);
                } else if ("duplicate-answer".equals(history.name)) {
                    check(toolTurns(wire) == 1, "a repeated answer was sent twice: " + text);
                    check(text.contains("first-answer") && text.contains("second-answer"),
                            "a repeated answer was dropped with its words: " + text);
                    check("user".equals(wire.getJSONObject(wire.length() - 1).getString("role")),
                            "the repeated answer belongs to the user's own turn: " + text);
                } else if ("unanswered-call".equals(history.name)) {
                    check(toolTurns(wire) == 0, "a result was invented for a call nobody answered: " + text);
                    check("assistant".equals(wire.getJSONObject(wire.length() - 1).getString("role")),
                            "the unanswered call has to stay the last turn: " + text);
                } else if ("tool-result-images".equals(history.name)) {
                    JSONObject toolTurn = firstToolTurn(wire);
                    String tool = toolTurn.getString("content");
                    check(tool.contains("[The tool returned an image; it follows in the next message.]"),
                            "the tool turn has to say its image follows: " + text);
                    check(!toolTurn.get("content").toString().contains("image_url"),
                            "Qoder's tool turn holds text only: " + text);
                    JSONObject followUp = turnAfter(wire, toolTurn);
                    check("user".equals(followUp.getString("role")), "the tool's image goes to the user's own turn: " + text);
                    JSONArray blocks = followUp.getJSONArray("content");
                    check("text".equals(blocks.getJSONObject(0).getString("type"))
                            && blocks.getJSONObject(0).getString("text").startsWith("[From the result of read (tool call call-1):]"),
                            "the image has to be announced with the call it came from: " + text);
                    check("image_url".equals(blocks.getJSONObject(1).getString("type"))
                            && blocks.getJSONObject(1).toString().contains("dG9vbC1ieXRlcw=="),
                            "the tool's image itself was lost: " + text);
                }
            }
            /* 数字 tool_call_id 仍然本地拒：apply 自己就不认（插件也不会照搬这种重写），线上一个字节没有。 */
            JSONObject numeric = request().put("messages", new JSONArray().put(new JSONObject()
                    .put("role","tool").put("tool_call_id",5).put("content","x")));
            int before = f.calls();
            Result numericResult = f.post("/v1/chat/completions", numeric, null);
            check(numericResult.status == 400, "numeric id status " + numericResult.status + " " + numericResult.body);
            check(f.calls() == before, "numeric id reached upstream");

            /* 隐私中间件与 guard 仍在历史之前就位：封顶照旧生效，配对不被它改坏。 */
            JSONObject capped = request().put("max_tokens", 4096).put("messages", histories().get(0).messages);
            check(f.post("/v1/chat/completions", capped, null).status == 200, "capped history was refused");
            JSONObject cappedSent = f.sent();
            check(cappedSent.getJSONObject("parameters").getInt("max_tokens") == 4096,
                    "a valid budget must go out as asked: " + cappedSent.getJSONObject("parameters"));
        }
        /* 上下文保护在历史之后、下发之前照旧生效：超预算的早段被裁掉，工具组原样留着（不丢结果、不造假）。 */
        try (Fixture f = new Fixture(1200)) {
            StringBuilder early = new StringBuilder("guard-drops-this-");
            for (int i = 0; i < 4000; i++) early.append("x");
            JSONArray guarded = new JSONArray()
                    .put(new JSONObject().put("role", "user").put("content", early.toString()))
                    .put(new JSONObject().put("role", "user").put("content", "middle"))
                    .put(new JSONObject().put("role", "user").put("content", "hello"))
                    .put(assistantCall(null, "read"))
                    .put(toolResult(null, "tool-said-ok"));
            Result trimmed = f.post("/v1/chat/completions", request().put("messages", guarded), null);
            check(trimmed.status == 200, "guarded history status " + trimmed.status + " " + trimmed.body);
            JSONArray wire = wireOf(f.sent());
            String text = wireText(wire);
            check(!text.contains("guard-drops-this"), "the guard did not trim the history it was given: " + text.substring(0, Math.min(200, text.length())));
            check(text.contains("tool-said-ok"), "the guard dropped a tool result: " + text);
            check(!text.contains("unavailable"), "a trimmed history got an invented result: " + text);
            check(firstCall(wire).getString("id").equals(firstToolTurn(wire).getString("tool_call_id")),
                    "trimming broke the tool pairing: " + text);
        }
        /* 隐私打码仍在历史之前：工具结果里的手机号在线上是打码的，配对与图片处理不受影响。 */
        try (Fixture f = new Fixture(new JSONObject().put("enabled", true).put("maskPhones", true))) {
            String phone = "13812345678";
            JSONArray privateHistory = new JSONArray()
                    .put(new JSONObject().put("role", "user").put("content", "hello"))
                    .put(assistantCall(null, "read"))
                    .put(toolResult(null, "call me on " + phone));
            Result masked = f.post("/v1/chat/completions", request().put("messages", privateHistory), null);
            check(masked.status == 200, "masked history status " + masked.status + " " + masked.body);
            JSONArray wire = wireOf(f.sent());
            String text = wireText(wire);
            check(!text.contains(phone), "the raw phone number went out: " + text);
            check(text.contains("{{MASK_PHONE_"), "the number was dropped instead of masked: " + text);
            check(firstCall(wire).getString("id").equals(firstToolTurn(wire).getString("tool_call_id")),
                    "masking broke the tool pairing: " + text);
        }
        /* 混组（Qoder + 非 Qoder 兜底）与纯非 Qoder 模型：严格规范化的状态码与报文一字未改。 */
        try (Fixture f = new Fixture(true)) {
            for (String model : new String[]{"group/qoder-test-group", "qoder-test-backup/fixture-model"}) {
                for (int i = 0; i < 2; i++) {
                    JSONObject body = new JSONObject().put("model", model).put("messages", histories().get(i).messages);
                    Result refused = f.post("/v1/chat/completions", body, null);
                    check(refused.status == 400, model + " status " + refused.status + " " + refused.body);
                    check(refused.body.contains(i == 0 ? "nonempty string id" : "nonempty string tool_call_id"),
                            model + " has to answer with the strict normalizer's own words: " + refused.body);
                }
            }
            check(f.calls() == 0, "a non-Qoder route reached the Qoder upstream");
            check(f.backupCalls() == 0, "a non-Qoder route reached the fallback upstream");
            /* 混组里一条干净的工具图片历史照旧走严格规范化：图片仍在，配对仍在，没有假结果。
             * 这是「Qoder-only 判定不影响混组」的正面证据。 */
            Result clean = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/qoder-test-group")
                            .put("messages", histories().get(6).messages), null);
            check(clean.status == 200, "mixed-group image history status " + clean.status + " " + clean.body);
            JSONArray wire = wireOf(f.sent());
            String text = wireText(wire);
            check(text.contains("dG9vbC1ieXRlcw=="), "the mixed group lost the tool image: " + text);
            check(!text.contains("unavailable"), "the mixed group got an invented result: " + text);
            check(firstCall(wire).getString("id").equals(firstToolTurn(wire).getString("tool_call_id")),
                    "the mixed group lost the tool pairing: " + text);
        }
        /* 纯 Qoder 的路由组（多账号 / 多 Qoder 供应商）照常拿到修复后的历史：组判定不是「一律 false」，
         * 只是「只要还声明了一个非 Qoder 目标就退回严格规范化」。 */
        try (Fixture f = new Fixture(config -> {
            long future = System.currentTimeMillis() + 86400000L;
            config.getJSONArray("providers").put(new JSONObject().put("id","qoder-cn-2").put("enabled",true)
                    .put("protocol","qoder").put("models", new JSONArray().put("fixture-model"))
                    .put("accounts", new JSONArray().put(new JSONObject().put("id","fixture-account-2")
                            .put("token","fixture-token-2").put("deviceToken","fixture-device-token-2")
                            .put("deviceChat",true).put("uid","fixture-uid-2").put("machineId","fixture-machine-2")
                            .put("expiresAt",future)))
                    .put("modelMeta", new JSONObject().put("fixture-model",
                            new JSONObject().put("key","fixture-model").put("is_vl",true).put("source","fixture"))));
            config.getJSONArray("groups").put(new JSONObject().put("id","qoder-pair").put("strategy","order")
                    .put("targets", new JSONArray().put("qoder_cn/fixture-model").put("qoder-cn-2/fixture-model")));
        })) {
            Result r = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/qoder-pair").put("messages", histories().get(0).messages), null);
            check(r.status == 200, "all-Qoder group status " + r.status + " " + r.body);
            check(f.calls() == 1, "an all-Qoder group did not route to Qoder");
            check(firstCall(wireOf(f.sent())).getString("id").startsWith("call_"),
                    "an all-Qoder group did not get Qoder's repaired history: " + f.sent());
        }
        /* 混组的另一个方向：非 Qoder 目标排在第一位。请求必须落到它身上（Qoder 一次都不该被调用），
         * 工具图片也要原样到它那里 —— 证明这份接线没有把别的供应商的图片或历史改掉。 */
        try (Fixture f = new Fixture(config -> config.getJSONArray("groups").put(new JSONObject()
                .put("id","openai-first-mix").put("strategy","order")
                .put("targets", new JSONArray().put("qoder-test-backup/fixture-model")
                        .put("qoder_cn/fixture-model"))))) {
            f.backupReplies(openAiReply("backup-ok"));
            Result plain = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/openai-first-mix").put("messages", new JSONArray()
                            .put(new JSONObject().put("role","user").put("content","hello"))), null);
            check(plain.status == 200, "openai-first status " + plain.status + " " + plain.body);
            check(f.calls() == 0, "an OpenAI-first group reached Qoder");
            check(f.backupCalls() == 1, "an OpenAI-first group did not reach its first target");
            Result pictured = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/openai-first-mix")
                            .put("messages", histories().get(6).messages), null);
            check(pictured.status == 200, "openai-first image status " + pictured.status + " " + pictured.body);
            JSONArray sent = f.backupSent().getJSONArray("messages");
            String text = wireText(sent);
            check(text.contains("dG9vbC1ieXRlcw=="), "the non-Qoder target lost the tool image: " + text);
            check(!text.contains("_images"), "the _images side channel leaked to a non-Qoder upstream: " + text);
            check(!text.contains("unavailable"), "the non-Qoder target got an invented result: " + text);
            check(f.calls() == 0, "a mixed group's image history reached Qoder as well");
        }
    }

    /** /v1/messages（Anthropic 客户端）的工具结果图片：Qoder 这条线上到底有没有图。
     *
     *  <p>先把事实钉住（与「_images 折叠缺失」的说法相反，实测不丢图）：requestToOpenAI
     *  （AnthropicBridge:244）转成 Chat 时确实把 tool_result 的图片放进 {@code _images} 旁路
     *  （AnthropicBridge:341），而紧跟着的 pairToolMessages(..., translated=true)
     *  （AnthropicBridge:499-543）会把 {@code _images} 折叠成下一段用户回合的 image_url 并删掉旁路。
     *  也就是说这条路上 {@code _images} 根本活不到 Qoder 适配器；QoderProtocol 不认 {@code _images}
     *  这件事因此不会造成丢图，只会造成「这条历史已经被严格规范化改写过」。
     *
     *  <p>所以 qoderOwnHistory 对 NATIVE_REQUEST / RESP_REQUEST 不放行的理由是**语义**而不是图片：
     *  requestToOpenAI 已经替没人回答的调用造了结果、把重复回答连同文字丢掉了、丢 id 的调用直接拒了。
     *  这些是 AnthropicBridge 内部的严格规范化，本批不动它（也就修不了它）；把这种 payload 再送进
     *  「原样历史」入口只会让那份被改写过的历史披上 Qoder-only 的外衣。下面的反面对照量到的正是这一点。
     *
     *  <p>图片本身另有一条真拒绝：tool_result 的块带不透明字段时 requiresNative 为真，网关会走
     *  projectNativeRequest 并最终以 PROTECTED 400 明说（不是静默丢图），见本方法的第三段。 */
    private static void nativeMessages() throws Exception {
        String toolBytes = "dG9vbC1ieXRlcw==";
        JSONObject body = new JSONObject().put("model","qoder_cn/fixture-model").put("max_tokens",128)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role","user").put("content", new JSONArray()
                                .put(new JSONObject().put("type","text").put("text","hello"))))
                        .put(new JSONObject().put("role","assistant").put("content", new JSONArray()
                                .put(new JSONObject().put("type","tool_use").put("id","call-1").put("name","read")
                                        .put("input", new JSONObject()))))
                        .put(new JSONObject().put("role","user").put("content", new JSONArray()
                                .put(new JSONObject().put("type","tool_result").put("tool_use_id","call-1")
                                        .put("content", new JSONArray()
                                                .put(new JSONObject().put("type","text").put("text","ok"))
                                                .put(nativeImage(toolBytes)))))));
        try (Fixture f = new Fixture(true)) {
            Result r = f.post("/v1/messages", body, null);
            check(r.status == 200, "native tool image status " + r.status + " " + r.body);
            String wire = f.sent().toString();
            check(wire.contains(toolBytes), "the tool result image was dropped on the native path: " + wire);
            check(wire.contains("call-1"), "tool pairing lost on the native path: " + wire);
            check(!wire.contains("unavailable") && !wire.contains("interrupted"),
                    "an invented result went out on the native path: " + wire);
            check(!wire.contains("_images"), "the _images side channel leaked onto the wire: " + wire);
            JSONArray sent = wireOf(f.sent());
            /* 图片按插件语义跟在工具回合之后（用户回合），工具回合自己只留文字。 */
            JSONObject toolTurn = firstToolTurn(sent);
            check("call-1".equals(toolTurn.getString("tool_call_id")), "the result answered another call");
            check(turnAfter(sent, toolTurn).optString("content", "").contains(toolBytes)
                            || turnAfter(sent, toolTurn).toString().contains(toolBytes),
                    "the tool image has to follow in the next user turn: " + wireText(sent));
        }
        /* 事实一：requestToOpenAI 已经把 _images 折叠掉（图片在用户回合里，旁路不在了）。
         * 所以「Qoder 不认 _images」并不会让这条路的图片消失 —— 之前那份交接里「图片会丢」的说法
         * 是错的，这里用真实调用把它钉死。 */
        JSONObject translated = AnthropicBridge.requestToOpenAI(new JSONObject(body.toString()));
        check(!translated.toString().contains("_images"),
                "requestToOpenAI was expected to fold _images into the user turn: " + translated);
        check(translated.toString().contains(toolBytes) && QoderProtocol.hasImages(translated),
                "the folded image has to be visible to hasImages: " + translated);
        /* 事实二（这条才是排除 NATIVE_REQUEST 的真正理由）：同一条转换对「没人回答的调用」造结果、
         * 对重复回答丢文字，而 repairQoderHistory 只补 id、修不了这些。这样的 payload 一旦进
         * 「原样历史」入口，就是把一份被改写过的历史当成客户端原话发出去。 */
        JSONObject unanswered = new JSONObject().put("model","qoder_cn/fixture-model").put("max_tokens",128)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role","user").put("content", new JSONArray()
                                .put(new JSONObject().put("type","text").put("text","hello"))))
                        .put(new JSONObject().put("role","assistant").put("content", new JSONArray()
                                .put(new JSONObject().put("type","tool_use").put("id","call-1").put("name","read")
                                        .put("input", new JSONObject())))));
        JSONObject transcribed = AnthropicBridge.requestToOpenAI(unanswered);
        check(transcribed.toString().contains("[The result of this tool call is unavailable"),
                "the native conversion is expected to invent a result for an unanswered call: " + transcribed);
        check(AnthropicBridge.repairQoderHistory(transcribed.getJSONArray("messages")).toString()
                        .contains("[The result of this tool call is unavailable"),
                "repairQoderHistory cannot undo an invented result; only the strict normalizer's absence could");
        /* 事实四（给未来的接线留的钉子）：如果哪天真给 /v1/messages 做 opt-in、却把 _images 留在原地，
         * QoderProtocol 既不读它、hasImages 也看不见它 —— 图片会静默消失，视觉能力检查也会被绕过。
         * 那种 opt-in 必须自己先把 _images 折回 content（或按插件语义并进下一段用户回合）。
         * 本批的放行判断因此对 NATIVE_REQUEST 一律不放行。 */
        JSONObject leaked = new JSONObject().put("max_tokens", 128).put("messages", new JSONArray()
                .put(new JSONObject().put("role","user").put("content","hello"))
                .put(new JSONObject().put("role","assistant").put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray().put(new JSONObject().put("id","call-1")
                                .put("type","function")
                                .put("function", new JSONObject().put("name","read").put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content","ok")
                        .put("_images", new JSONArray().put(image("data:image/png;base64," + toolBytes)))));
        check(!QoderProtocol.hasImages(leaked),
                "an image that only lives in _images must not count as a visible image");
        JSONObject leakedOut = QoderProtocol.apply(new JSONObject()
                        .put("system", new JSONArray().put(new JSONObject().put("type","text").put("text","s")))
                        .put("parameters", new JSONObject())
                        .put("business", new JSONObject())
                        .put("model_config", new JSONObject().put("key","fixture-model")),
                leaked, null, "fixture-scope");
        check(!leakedOut.toString().contains(toolBytes),
                "QoderProtocol drops _images without a word, so a native-path opt-in has to fold them first: "
                        + leakedOut);
        /* 事实三：tool_result 的块带不透明字段时，网关不静默丢图，而是明说不能原生直通。 */
        JSONObject opaque = new JSONObject(body.toString());
        opaque.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
                .put("cache_control", new JSONObject().put("type","ephemeral"));
        try (Fixture f = new Fixture(true)) {
            Result refused = f.post("/v1/messages", opaque, null);
            check(refused.status == 400, "opaque tool result image status " + refused.status + " " + refused.body);
            check(f.calls() == 0, "a request that needs native relay reached Qoder");
        }
    }

    /** 选目标这件事本身：动态 model-map、路由组、隐私打码都还在原位，Qoder-only 只按映射后的最终
     *  候选判定，绝不因为「模型名里带 qoder」就放行。 */
    private static void routingSelection() throws Exception {
        /* 1. 别名 → qoder：判定看的是 model-map 之后的模型，所以病史照样按插件语义修好。 */
        try (Fixture f = new Fixture(config -> config.put("middleware", new JSONObject()
                .put("modelMap", new JSONObject().put("alias-model", "qoder_cn/fixture-model"))))) {
            JSONObject alias = new JSONObject().put("model", "alias-model").put("messages", histories().get(0).messages);
            Result r = f.post("/v1/chat/completions", alias, null);
            check(r.status == 200, "mapped alias status " + r.status + " " + r.body);
            JSONObject sent = f.sent();
            check(firstCall(wireOf(sent)).getString("id").startsWith("call_"),
                    "the mapped alias did not get Qoder's repaired history: " + sent);
            check("fixture-model".equals(sent.getJSONObject("business").getString("model_key")),
                    "the mapped alias went out as another model: " + sent);
        }
        /* 2. qoder 名字 → 别的供应商：判定看映射之后的目标，所以必须还是严格规范化那条 400，
         *    否则一份 Qoder 能读、别家不能读的病史会被发出别的上游。 */
        try (Fixture f = new Fixture(config -> config.put("middleware", new JSONObject()
                .put("modelMap", new JSONObject().put("qoder_cn/fixture-model", "qoder-test-backup/fixture-model"))))) {
            Result r = f.post("/v1/chat/completions",
                    new JSONObject().put("model","qoder_cn/fixture-model").put("messages", histories().get(0).messages), null);
            check(r.status == 400, "remapped-to-openai status " + r.status + " " + r.body);
            check(r.body.contains("nonempty string id"),
                    "a remapped request has to keep the strict normalizer's words: " + r.body);
            check(f.calls() == 0 && f.backupCalls() == 0, "a remapped request reached an upstream");
        }
        /* 3. 混组里有一个当前不可用的非 Qoder 目标：resolveCandidates 会把这一组解析成 Qoder-only，
         *    判定仍然保守地返回 false（组里声明了非 Qoder 目标）。两条断言把这个取舍钉住：
         *    干净历史照常只打到 Qoder，丢 id 的病史仍然本地 400、不悄悄发出别的上游。 */
        try (Fixture f = new Fixture(config -> {
            JSONArray providers = config.getJSONArray("providers");
            providers.put(new JSONObject().put("id","qoder-test-dead").put("protocol","openai")
                    .put("baseUrl","http://127.0.0.1:1/unused").put("models", new JSONArray().put("fixture-model"))
                    .put("keys", new JSONArray()).put("enabled", true));
            config.getJSONArray("groups").put(new JSONObject().put("id","qoder-dead-mix").put("strategy","order")
                    .put("targets", new JSONArray().put("qoder_cn/fixture-model").put("qoder-test-dead/fixture-model")));
        })) {
            Result clean = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/qoder-dead-mix").put("messages", new JSONArray()
                            .put(new JSONObject().put("role","user").put("content","hello"))), null);
            check(clean.status == 200, "dead-mix clean history status " + clean.status + " " + clean.body);
            check(f.calls() == 1, "the dead-mix group did not route to Qoder");
            Result lost = f.post("/v1/chat/completions",
                    new JSONObject().put("model","group/qoder-dead-mix").put("messages", histories().get(0).messages), null);
            check(lost.status == 400, "dead-mix lost history status " + lost.status + " " + lost.body);
            check(lost.body.contains("nonempty string id"),
                    "a group that declares a non-Qoder target keeps the strict normalizer's words: " + lost.body);
            check(f.calls() == 1, "a refused dead-mix history reached Qoder");
        }
        /* 4. 预算字段不合法时不放行原样历史：QoderProtocol.apply 不复查 max_tokens，所以判定里用探针
         *    单独问一遍 validateClientHistory 的那一段；不合法就照旧走严格规范化，让 forwardQoder
         *    还回今天那条 400，一个字节不上行。 */
        try (Fixture f = new Fixture(true)) {
            for (String broken : new String[]{"1.5", "\"abc\"", "0", "-3"}) {
                JSONObject body = new JSONObject("{\"model\":\"qoder_cn/fixture-model\",\"max_tokens\":" + broken
                        + ",\"messages\":" + histories().get(0).messages.toString() + "}");
                Result r = f.post("/v1/chat/completions", body, null);
                check(r.status == 400, "budget " + broken + " status " + r.status + " " + r.body);
            }
            check(f.calls() == 0, "a malformed budget reached Qoder");
        }
        /* 5. 隐私打码与病史修复同时生效：手机号在线上是掩码，调用/结果仍按插件语义配对。 */
        try (Fixture f = new Fixture(new JSONObject().put("enabled", true).put("maskPhones", true))) {
            String phone = "13812345678";
            JSONArray history = new JSONArray()
                    .put(new JSONObject().put("role","user").put("content","call " + phone))
                    .put(assistantCall(null, "read"))
                    .put(toolResult(null, "also " + phone));
            Result r = f.post("/v1/chat/completions", request().put("messages", history), null);
            check(r.status == 200, "masked repaired history status " + r.status + " " + r.body);
            JSONArray sent = wireOf(f.sent());
            String text = wireText(sent);
            check(!text.contains(phone), "the raw phone number went out: " + text);
            check(text.contains("{{MASK_PHONE_"), "the number was dropped instead of masked: " + text);
            check(firstCall(sent).getString("id").startsWith("call_")
                            && firstCall(sent).getString("id").equals(firstToolTurn(sent).getString("tool_call_id")),
                    "masking broke the repaired pairing: " + text);
        }
    }

    /** 环回对账用：把每条夹具「客户端历史」与「Qoder 那条线上的 messages」原样交出去，
     *  由仓库外脚本拿真实 0.2.64 包的 qoderMessages 逐字段比。 */
    private static void wireDump(String path) throws Exception {
        JSONArray dump = new JSONArray();
        try (Fixture f = new Fixture(true)) {
            for (History history : histories()) {
                Result response = f.post("/v1/chat/completions", request().put("messages", history.messages), null);
                check(response.status == 200, history.name + " was refused: " + response.status + " " + response.body);
                dump.put(new JSONObject().put("name", history.name)
                        .put("client", history.messages)
                        .put("wire", f.sent()));
            }
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            for (int i = 0; i < dump.length(); i++) out.write(dump.getJSONObject(i).toString() + "\n");
        }
    }
    /** 一段匿名对话：alternating user/assistant 内容，第一条是它的开场。 */
    private static JSONObject turn(String... turns) {
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < turns.length; i++)
            msgs.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant").put("content", turns[i]));
        return new JSONObject().put("model","qoder_cn/fixture-model").put("messages", msgs);
    }
    public static void main(String[] args) throws Exception {
        /* --wire-dump 只跑历史夹具，把「客户端历史」与「Qoder 线上的 messages」原样写出来，
         * 供仓库外脚本与真实 0.2.64 包的 qoderMessages 逐字段对账。 */
        if (args != null && args.length == 2 && "--wire-dump".equals(args[0])) {
            wireDump(args[1]);
            System.out.println("QoderAgentBoundaryHttpTest wire dump OK");
            return;
        }
        images(); localGuards(); repairedHistories(); nativeMessages(); routingSelection();
        configurationGuards(); sessions();
        System.out.println("QoderAgentBoundaryHttpTest OK");
    }
}
