package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
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
    private static final class Fixture implements AutoCloseable {
        final Object upstream, backup, gateway;
        Fixture(Boolean vision) throws Exception { this(vision, false, false); }
        Fixture(Boolean vision, boolean mismatch, boolean cap) throws Exception {
            upstream = construct(UP, new Class<?>[]{String.class}, sse());
            backup = construct(UP, new Class<?>[]{String.class}, sse());
            JSONObject config = (JSONObject)invoke("config", new Class<?>[]{UP}, backup);
            JSONObject meta = config.getJSONArray("providers").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("fixture-model");
            if (vision == null) meta.remove("is_vl"); else meta.put("is_vl", vision);
            meta.put("api_key", "listing-secret").put("url", "https://listing.invalid/secret")
                .put("minimal_version", "99.0").put("price", 12345).put("private_listing_field", "private-marker");
            if (mismatch) meta.put("key", "different-model");
            if (cap) config.put("guard", new JSONObject().put("enabled", true).put("maxOutputTokens", 63));
            config.getJSONObject("failover").put("enabled", false);
            gateway = construct(GW, new Class<?>[]{JSONObject.class, UP}, config, upstream);
        }
        int calls() throws Exception { return ((java.util.concurrent.atomic.AtomicInteger)field(upstream,"calls")).get(); }
        JSONObject sent() throws Exception { return new JSONObject(QoderAuth.decodeBody((String)field(upstream,"lastRequest"))); }
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
            JSONObject[] bad = { request().put("max_tokens",63), request().put("tool_choice","required"), request().put("parallel_tool_calls",false),
                    request().put("messages",new JSONArray().put(new JSONObject().put("role","tool").put("tool_call_id","missing").put("content","x"))) };
            for (JSONObject req : bad) {
                Result r = f.post("/v1/chat/completions",req,null);
                check(r.status == 400,"guard status " + r.status + " " + r.body);
                check(f.calls() == 0,"guard reached upstream");
            }
            JSONObject signed = new JSONObject().put("model","qoder_cn/fixture-model").put("max_tokens",128)
                    .put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","reason").put("signature","signed-native")))));
            Result r = f.post("/v1/messages",signed,null);
            check(r.status == 400,"signed history status " + r.status + " " + r.body);
            check(f.calls() == 0,"signed history reached upstream");
        }
    }
    private static void configurationGuards() throws Exception {
        try (Fixture f = new Fixture(true, true, false)) {
            Result r = f.post("/v1/chat/completions", request(), null);
            check(r.status == 400, "mismatched catalog key status " + r.status + " " + r.body);
            check(f.calls() == 0, "mismatched catalog key reached upstream");
        }
        try (Fixture f = new Fixture(true, false, true)) {
            Result r = f.post("/v1/chat/completions", request().put("max_tokens", 128), null);
            check(r.status == 400, "guard output cap status " + r.status + " " + r.body);
            check(f.calls() == 0, "guard output cap reached upstream");
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
            check(f.post("/v1/chat/completions",request(),null).status == 200,"anonymous session 1"); String c = f.sent().getString("session_id");
            check(f.post("/v1/chat/completions",request(),null).status == 200,"anonymous session 2"); String d = f.sent().getString("session_id");
            check(!c.equals(d),"anonymous session reused");
        }
    }
    public static void main(String[] args) throws Exception { images(); localGuards(); configurationGuards(); sessions(); System.out.println("QoderAgentBoundaryHttpTest OK"); }
}
