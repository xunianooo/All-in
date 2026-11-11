package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic loopback-only Qoder/Gateway regression; no real credentials or services. */
public final class QoderAgentGatewayHttpTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static final String SECRET = "upstream-secret-must-not-leak";

    private static final class Upstream implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final AtomicInteger calls = new AtomicInteger();
        volatile String reply;
        volatile String lastRequest;
        volatile Throwable failure;
        volatile Socket active;
        volatile String first, tail;
        volatile boolean cancellationProbe, downstreamProbe;
        final java.util.concurrent.CountDownLatch peerClosed = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch sent = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Upstream(String response) throws IOException {
            reply = response;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(300);
            worker = new Thread(this::run, "qoder-loopback"); worker.setDaemon(true); worker.start();
        }
        void run() {
            while (!listener.isClosed()) {
                try (Socket s = listener.accept()) {
                    active = s; s.setSoTimeout(4000); calls.incrementAndGet();
                    InputStream in = s.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int state = 0, b;
                    while (state < 4 && (b = in.read()) != -1) {
                        header.write(b); if (header.size() > 65536) throw new IOException("headers too large");
                        state = state == 0 && b == 13 ? 1 : state == 1 && b == 10 ? 2 : state == 2 && b == 13 ? 3 : state == 3 && b == 10 ? 4 : 0;
                    }
                    int length = 0;
                    for (String line : header.toString("UTF-8").split("\r\n"))
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    while (length-- > 0) { int c = in.read(); if (c < 0) throw new EOFException("request"); body.write(c); }
                    lastRequest = body.toString("UTF-8");
                    if (first == null) { s.getOutputStream().write(bytes(reply)); s.getOutputStream().flush(); }
                    else {
                        OutputStream out = s.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + first)); out.flush(); sent.countDown();
                        if (cancellationProbe) {
                            s.setSoTimeout(200);
                            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                            while (System.nanoTime() < until && release.getCount() != 0) {
                                try { if (s.getInputStream().read() == -1) { peerClosed.countDown(); break; } }
                                catch (SocketTimeoutException expected) { }
                            }
                        } else if (downstreamProbe) {
                            if (!release.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture gate timeout");
                            // A peer can close gracefully (FIN) without our writes failing
                            // immediately, particularly with Windows TCP send buffering. Observe
                            // EOF as well as reset, instead of flooding until write throws.
                            s.setSoTimeout(100);
                            StringBuilder chunk = new StringBuilder();
                            for (int i=0; i<1024; i++) chunk.append('x');
                            byte[] frame=bytes(delta(new JSONObject().put("content",chunk.toString()),null));
                            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                            while(System.nanoTime()<until && peerClosed.getCount()!=0) {
                                try {
                                    out.write(frame); out.flush();
                                    if(in.read()==-1) { peerClosed.countDown(); break; }
                                } catch(SocketTimeoutException waitingForPeer) {
                                    // No close yet. More data exercises another downstream write.
                                } catch(IOException peerReset) { peerClosed.countDown(); break; }
                            }
                        } else {
                            if (!release.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture gate timeout");
                            out.write(bytes(tail)); out.flush();
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable e) { if (!listener.isClosed()) failure = e; }
                finally { active = null; }
            }
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/qoder"; }
        public void close() throws Exception {
            release.countDown(); listener.close(); Socket s = active; if (s != null) s.close(); worker.join(5000);
            check(!worker.isAlive(), "upstream thread did not stop");
            check(failure == null, "upstream fixture: " + failure);
        }
    }
    private static String http(int code, String body) {
        return "HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + bytes(body).length + "\r\nConnection: close\r\n\r\n" + body;
    }
    private static String sse(String content) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + bytes(content).length + "\r\nConnection: close\r\n\r\n" + content;
    }
    private static JSONObject config(Upstream backup) {
        JSONObject account = new JSONObject().put("id", "fixture-account").put("token", "fixture-token")
                .put("deviceToken", "fixture-device-token").put("deviceChat", true)
                .put("uid", "fixture-uid").put("machineId", "fixture-machine")
                .put("expiresAt", System.currentTimeMillis() + 86400000L);
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("enabled", true).put("protocol", "qoder")
                .put("models", new JSONArray().put("fixture-model")).put("accounts", new JSONArray().put(account))
                .put("modelMeta", new JSONObject().put("fixture-model", new JSONObject().put("key", "fixture-model").put("source", "fixture").put("is_vl", true).put("thinking_config", new JSONObject().put("enabled", new JSONObject().put("efforts", new JSONObject().put("medium", new JSONObject().put("is_default", true)))).put("disabled", new JSONObject()))));
        JSONObject fallback = new JSONObject().put("id", "qoder-test-backup").put("protocol", "openai")
                .put("baseUrl", backup.url()).put("keys", new JSONArray().put("fixture-key"))
                .put("models", new JSONArray().put("fixture-model"));
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("routing", new JSONObject().put("groupsEnabled", true))
                .put("failover", new JSONObject().put("enabled", true).put("maxTargets", 2).put("maxAttempts", 3))
                .put("providers", new JSONArray().put(qoder).put(fallback))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "qoder-test-group").put("strategy", "order")
                        .put("targets", new JSONArray().put("qoder_cn/fixture-model").put("qoder-test-backup/fixture-model"))));
    }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject config, Upstream qoder) throws Exception {
            super("127.0.0.1", 0);
            inner = RoutingSetupOfflineTest.server(config);
            java.nio.file.Path isolated=Files.createTempDirectory("qoder-gateway-");
            UsageLedger.init(isolated.toFile());
            RoutingAuditOfflineTest.file(inner, isolated.resolve("config.json").toFile());
            Field f = GatewayServer.class.getDeclaredField("groupTurns"); f.setAccessible(true);
            f.set(inner, new ConcurrentHashMap<String, Integer>());
            inner.setQoderConnectionsForTests(url -> (HttpURLConnection) new URL(qoder.url()).openConnection(Proxy.NO_PROXY));
            start(SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() { inner.setQoderConnectionsForTests(null); stop(); }
    }
    private static final class Result { int status; String body, retry; }
    private static Result post(Gateway gateway, String limits) throws Exception {
        String body = "{\"model\":\"group/qoder-test-group\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]" + limits + "}";
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + gateway.getListeningPort() + "/v1/chat/completions").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer fixture-auth"); c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) { out.write(bytes(body)); }
        Result result = new Result();
        try {
            result.status = c.getResponseCode(); result.retry = c.getHeaderField("Retry-After");
            InputStream in = result.status >= 400 ? c.getErrorStream() : c.getInputStream();
            try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (stream != null) { byte[] b = new byte[2048]; int n; while ((n = stream.read(b)) != -1) out.write(b, 0, n); }
                result.body = out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, long[]> rests() throws Exception {
        Field f = GatewayServer.class.getDeclaredField("RESTS"); f.setAccessible(true); return (Map<String, long[]>)f.get(null);
    }
    private static void scenario(String name, String wire, int expected, boolean queue, String limits, int expectedCalls) throws Exception {
        String key = "qoder_cn/fixture-model";
        Map<String, long[]> rest = rests(); long[] previous = rest.remove(key);
        try (Upstream primary = new Upstream(wire); Upstream backup = new Upstream(http(500, "backup-called"));
             Gateway gateway = new Gateway(config(backup), primary)) {
            Result r = post(gateway, limits);
            check(r.status == expected, name + " status=" + r.status + " body=" + r.body);
            check(!r.body.contains(SECRET), name + " leaked upstream body");
            check(primary.calls.get() == expectedCalls, name + " Qoder calls=" + primary.calls.get());
            check(backup.calls.get() == 0, name + " unexpected fallback");
            check(!rest.containsKey(key), name + " changed cooldown");
            if (queue) {
                check(r.retry != null, name + " missing Retry-After");
                int retry = Integer.parseInt(r.retry); check(retry >= 1 && retry <= 3600, name + " unbounded retry=" + retry);
                JSONArray recent=UsageLedger.summary(1,"model").getJSONArray("recent");
                boolean codeKept=false;
                for(int i=0;i<recent.length();i++) {JSONObject row=recent.getJSONObject(i);
                    if("qoder_cn".equals(row.optString("prov")) && row.optInt("attempt",0)>0) {codeKept="10605".equals(row.optString("upstreamErrorCode"));break;}}
                check(codeKept,name+" structured queue code missing from final ledger");
            }
            if (expectedCalls == 1 && expected == 503 && limits.contains("64")) {
                JSONObject request = new JSONObject(QoderAuth.decodeBody(primary.lastRequest));
                check(request.getJSONObject("parameters").getInt("max_tokens") == 64, name + " did not preserve cap");
            }
        } finally { rest.remove(key); if (previous != null) rest.put(key, previous); }
    }

    static String envelope(JSONObject value) { return "data: " + new JSONObject().put("statusCodeValue", 200).put("body", value.toString()) + "\n\n"; }
    static String delta(JSONObject value, String finish) {
        return envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index",0).put("delta",value).put("finish_reason", finish == null ? JSONObject.NULL : finish))));
    }
    static JSONObject tool(int index, String id, String name, String args) {
        JSONObject f = new JSONObject(); if (name != null) f.put("name", name); if (args != null) f.put("arguments", args);
        JSONObject t = new JSONObject().put("index",index).put("function", f); if (id != null) t.put("id",id).put("type","function"); return t;
    }
    static String first() { return delta(new JSONObject().put("content","early-marker").put("reasoning_content","thinking-marker"), null); }
    static String done() { return "data: " + new JSONObject().put("statusCodeValue",200).put("body","[DONE]") + "\n\n"; }
    static String usage() { return envelope(new JSONObject().put("choices",new JSONArray()).put("usage",new JSONObject().put("prompt_tokens",23).put("completion_tokens",11).put("prompt_tokens_details",new JSONObject().put("cached_tokens",5)).put("completion_tokens_details",new JSONObject().put("reasoning_tokens",3)))); }
    static String tail() {
        return delta(new JSONObject().put("tool_calls",new JSONArray().put(tool(1,"call-b","two","{\"b\":" )).put(tool(0,"call-a","one","{\"a\":"))),null)
             + delta(new JSONObject().put("tool_calls",new JSONArray().put(tool(0,null,null,"1}")).put(tool(1,null,null,"2}"))),null)
             + delta(new JSONObject(),"tool_calls") + usage() + done();
    }
    static String path(String kind) { return kind.equals("chat") ? "/v1/chat/completions" : kind.equals("messages") ? "/v1/messages" : "/v1/responses"; }
    static JSONObject request(String kind, boolean stream, boolean history) {
        JSONObject r = new JSONObject().put("model","qoder_cn/fixture-model").put("stream",stream);
        JSONObject schema = new JSONObject().put("type","object").put("properties",new JSONObject());
        JSONArray tools = new JSONArray();
        for (String n : new String[]{"one","two"}) {
            if (kind.equals("chat")) tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",n).put("parameters",schema)));
            else if (kind.equals("messages")) tools.put(new JSONObject().put("name",n).put("input_schema",schema));
            else tools.put(new JSONObject().put("type","function").put("name",n).put("parameters",schema));
        }
        r.put("tools",tools);
        if (kind.equals("responses")) {
            JSONArray a=new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            if(history) a.put(new JSONObject().put("type","function_call").put("call_id","call-a").put("name","one").put("arguments","{\"a\":1}"))
                    .put(new JSONObject().put("type","function_call").put("call_id","call-b").put("name","two").put("arguments","{\"b\":2}"))
                    .put(new JSONObject().put("type","function_call_output").put("call_id","call-b").put("output","result-b"))
                    .put(new JSONObject().put("type","function_call_output").put("call_id","call-a").put("output","result-a"));
            r.put("input",a);
        } else {
            JSONArray a=new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            if(history && kind.equals("chat")) {
                JSONArray calls=new JSONArray().put(new JSONObject().put("id","call-a").put("type","function").put("function",new JSONObject().put("name","one").put("arguments","{\"a\":1}")))
                    .put(new JSONObject().put("id","call-b").put("type","function").put("function",new JSONObject().put("name","two").put("arguments","{\"b\":2}")));
                a.put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("reasoning_content","private reasoning").put("tool_calls",calls))
                 .put(new JSONObject().put("role","tool").put("tool_call_id","call-b").put("name","two").put("content","result-b"))
                 .put(new JSONObject().put("role","tool").put("tool_call_id","call-a").put("name","one").put("content","result-a"));
            } else if(history) {
                a.put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","call-a").put("name","one").put("input",new JSONObject().put("a",1)))
                    .put(new JSONObject().put("type","tool_use").put("id","call-b").put("name","two").put("input",new JSONObject().put("b",2)))))
                 .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","call-b").put("content","result-b"))
                    .put(new JSONObject().put("type","tool_result").put("tool_use_id","call-a").put("content","result-a"))));
            }
            r.put("messages",a); if(kind.equals("messages")) r.put("max_tokens",128);
        }
        return r;
    }
    static HttpURLConnection connect(Gateway g,String kind,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path(kind)).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(6000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Authorization","Bearer fixture-auth");
        try(OutputStream o=c.getOutputStream()) { o.write(bytes(body.toString())); } return c;
    }
    static String readAll(InputStream in) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[1024]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); return out.toString("UTF-8"); }
    static String response(Gateway g,String kind,JSONObject body) throws Exception {
        HttpURLConnection c=connect(g,kind,body); try { int code=c.getResponseCode(); String text=readAll(code>=400?c.getErrorStream():c.getInputStream()); check(code==200,kind+" HTTP "+code+" "+text); return text; } finally { c.disconnect(); }
    }
    static void history(String encoded, String kind) throws Exception {
        JSONArray messages=new JSONObject(QoderAuth.decodeBody(encoded)).getJSONArray("messages");
        JSONObject assistant=null; Map<String,JSONObject> results=new HashMap<>();
        for(int i=0;i<messages.length();i++) { JSONObject m=messages.getJSONObject(i); if(m.optJSONArray("tool_calls")!=null) assistant=m; if(m.has("tool_call_id")) results.put(m.getString("tool_call_id"),m); }
        check(assistant!=null,kind+" missing assistant calls: "+messages);
        if(kind.equals("chat")) check("private reasoning".equals(assistant.optString("reasoning_content")),kind+" reasoning lost");
        else if(kind.equals("responses")) check(!assistant.has("reasoning_content"),kind+" fabricated reasoning");
        JSONArray calls=assistant.getJSONArray("tool_calls"); check(calls.length()==2,kind+" call count");
        Map<String,String> names=new HashMap<>(); for(int i=0;i<calls.length();i++) { JSONObject c=calls.getJSONObject(i); names.put(c.getString("id"),c.getJSONObject("function").getString("name")); }
        check("one".equals(names.get("call-a")) && "two".equals(names.get("call-b")),kind+" names/ids "+names);
        check(results.containsKey("call-a") && results.containsKey("call-b"),kind+" result pairing "+messages);
        check(results.get("call-a").toString().contains("result-a") && results.get("call-b").toString().contains("result-b"),kind+" result content");
        if(results.get("call-b").has("name")) check("two".equals(results.get("call-b").getString("name")),"tool name mismatch");
    }
    static void matrix(String kind, boolean streaming) throws Exception {
        try(Upstream primary=new Upstream(sse(first()+tail())); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
            String body=response(g,kind,request(kind,streaming,false));
            check(body.contains("early-marker") && body.contains("call-a") && body.contains("call-b") && body.contains("one") && body.contains("two"),kind+" output "+body);
            if(kind.equals("messages")) {
                if(streaming) check(body.contains("\"input_tokens\":18") && body.contains("\"cache_read_input_tokens\":5") && body.contains("\"output_tokens\":11"),kind+" usage "+body);
                else { JSONObject u=new JSONObject(body).getJSONObject("usage"); check(u.getInt("input_tokens")==18 && u.getInt("cache_read_input_tokens")==5 && u.getInt("output_tokens")==11,kind+" usage "+body); }
            } else check(body.contains("23") && body.contains("11"),kind+" usage "+body);
            JSONObject follow=request(kind,streaming,true);
            if(kind.equals("chat")) follow.put("max_tokens",2048);
            String next=response(g,kind,follow); check(next.contains("early-marker"),kind+" next round"); history(primary.lastRequest,kind);
            if(kind.equals("chat")) {
                JSONObject params=new JSONObject(QoderAuth.decodeBody(primary.lastRequest)).getJSONObject("parameters");
                check(params.getBoolean("enable_thinking") && "medium".equals(params.optString("reasoning_effort")),"model default medium reasoning "+params);
            }
            check(primary.calls.get()==2 && backup.calls.get()==0,kind+" calls");
        }
    }
    static void live(String kind) throws Exception {
        try(Upstream primary=new Upstream(""); Upstream backup=new Upstream(http(500,"backup"))) {
            primary.first=first(); primary.tail=tail();
            try(Gateway g=new Gateway(config(backup),primary)) {
                final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.CountDownLatch observed=new java.util.concurrent.CountDownLatch(1);
                Thread client=new Thread(()-> { try { HttpURLConnection c=connect(g,kind,request(kind,true,false)); try { check(c.getResponseCode()==200,"live status"); InputStream in=c.getInputStream(); StringBuilder seen=new StringBuilder(); int b; while((b=in.read())!=-1) { seen.append((char)b); if(seen.indexOf("early-marker")>=0) observed.countDown(); } check(seen.indexOf("call-a")>=0,"live terminal"); } finally { c.disconnect(); } } catch(Throwable e) { failure.set(e); observed.countDown(); } });
                client.start(); try { check(primary.sent.await(4,java.util.concurrent.TimeUnit.SECONDS),kind+" upstream not sent"); check(observed.await(5,java.util.concurrent.TimeUnit.SECONDS),kind+" buffered until terminal"); check(failure.get()==null,kind+" live "+failure.get()); } finally { primary.release.countDown(); client.join(8000); }
                check(!client.isAlive() && failure.get()==null,kind+" client failure "+failure.get()); check(backup.calls.get()==0,kind+" fallback");
            }
        }
    }
    static void failures(String kind) throws Exception {
        String queue="data: "+new JSONObject().put("statusCodeValue",403).put("body",new JSONObject().put("code",10605).put("queue",new JSONObject().put("retryAfterSeconds",7)).toString())+"\n\n";
        for(String bad:new String[]{first()+queue,first()+delta(new JSONObject().put("tool_calls",new JSONArray().put(tool(0,"call-a","one","{\"x\":"))),null),first()+delta(new JSONObject().put("tool_calls",new JSONArray().put(tool(0,"call-a","one","{bad}"))),null)+delta(new JSONObject(),"tool_calls")+done()}) {
            try(Upstream primary=new Upstream(sse(bad)); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
                HttpURLConnection c=connect(g,kind,request(kind,true,false)); String got=""; try { int status=c.getResponseCode(); if(status<400) try { got=readAll(c.getInputStream()); } catch(IOException expected) {} } finally { c.disconnect(); }
                assertNoSuccessOrTools(kind,got);
                check(backup.calls.get()==0 && primary.calls.get()==1,kind+" late fallback");
            }
        }
    }
    static void assertNoSuccessOrTools(String kind, String wire) throws Exception {
        for(String line:wire.split("\\r?\\n")) {
            if(line.startsWith("event:")) {
                String event=line.substring(6).trim();
                check(!event.equals("message_stop") && !event.equals("response.completed"),kind+" terminal event "+wire);
            }
            if(!line.startsWith("data:")) continue;
            String data=line.substring(5).trim(); if(data.isEmpty()) continue;
            check(!data.equals("[DONE]"),kind+" premature DONE "+wire);
            JSONObject event=new JSONObject(data); String type=event.optString("type");
            check(!type.equals("message_stop") && !type.equals("response.completed") && !type.equals("response.function_call_arguments.done"),kind+" successful terminal "+wire);
            JSONObject block=event.optJSONObject("content_block");
            check(block==null || !"tool_use".equals(block.optString("type")),kind+" executable tool block "+wire);
            JSONObject item=event.optJSONObject("item");
            check(item==null || !"function_call".equals(item.optString("type")),kind+" executable tool item "+wire);
            JSONArray choices=event.optJSONArray("choices");
            for(int i=0;choices!=null && i<choices.length();i++) {
                JSONObject choice=choices.getJSONObject(i), delta=choice.optJSONObject("delta"), message=choice.optJSONObject("message");
                check(choice.isNull("finish_reason"),kind+" premature finish "+wire);
                check((delta==null || !delta.has("tool_calls")) && (message==null || !message.has("tool_calls")),kind+" executable calls "+wire);
            }
        }
    }
    static JSONObject attempt() throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        do {
            JSONArray rows=UsageLedger.summary(1,"model").getJSONArray("recent");
            for(int i=0;i<rows.length();i++) { JSONObject r=rows.getJSONObject(i); if("qoder_cn".equals(r.optString("prov")) && r.optInt("attempt")>0) return r; }
            Thread.sleep(20);
        } while(System.nanoTime()<end);
        throw new AssertionError("missing Qoder attempt ledger row");
    }
    static void usageCases(String kind) throws Exception {
        for(int mode=0;mode<3;mode++) {
            String u=mode==0 ? usage() : mode==1 ? "" : envelope(new JSONObject().put("choices",new JSONArray()).put("usage",new JSONObject().put("prompt_tokens",23)));
            String wire=first()+delta(new JSONObject(),"stop")+u+done();
            try(Upstream primary=new Upstream(sse(wire)); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
                String output=response(g,kind,request(kind,false,false)); JSONObject row=attempt();
                if(mode==0) {
                    check(row.optBoolean("inputKnown") && row.optBoolean("outputKnown") && row.getInt("in")==23 && row.getInt("out")==11,kind+" usage "+row);
                    check(row.getInt("cacheRead")==5 && "known".equals(row.optString("reasoningSource")),kind+" usage details "+row);
                    JSONObject totals=UsageLedger.summary(1,"model").getJSONObject("totals"); check(totals.getLong("reasoning")==3,kind+" reasoning total "+totals);
                } else if(mode==1) {
                    check(!row.optBoolean("inputKnown") && !row.optBoolean("outputKnown") && row.isNull("in") && row.isNull("out"),kind+" invented usage "+row+" "+output);
                } else {
                    check(row.optBoolean("inputKnown") && row.getInt("in")==23 && row.optBoolean("outputKnown") && row.getInt("out")==0,kind+" per-frame partial usage normalization "+row);
                }
            }
        }
    }
    static void invalidHistory(String kind) throws Exception {
        for(int mode=0;mode<3;mode++) {
            JSONObject body=request(kind,false,true);
            if(kind.equals("responses")) {
                JSONArray a=body.getJSONArray("input");
                if(mode==0) a.remove(4); else if(mode==1) a.getJSONObject(4).put("call_id","call-b"); else { JSONObject item=a.getJSONObject(3); a.remove(3); a.put(0,item); }
            } else {
                JSONArray a=body.getJSONArray("messages");
                if(kind.equals("chat")) {
                    if(mode==0) a.remove(3); else if(mode==1) a.getJSONObject(3).put("tool_call_id","call-b"); else { JSONObject item=a.getJSONObject(2); a.remove(2); a.put(0,item); }
                } else {
                    JSONArray blocks=a.getJSONObject(2).getJSONArray("content");
                    if(mode==0) blocks.remove(1); else if(mode==1) blocks.getJSONObject(1).put("tool_use_id","call-b"); else { JSONObject item=a.getJSONObject(2); a.remove(2); a.put(0,item); }
                }
            }
            try(Upstream primary=new Upstream(sse(first()+tail())); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
                HttpURLConnection c=connect(g,kind,body); try {
                    int code=c.getResponseCode(); String text=readAll(code>=400?c.getErrorStream():c.getInputStream());
                    if(kind.equals("chat")) {
                        /* 合法对标之后：这三条病史不再本地 400。chat 落在候选全 Qoder 的路由上，历史原样
                         * 交给 0.2.64 的 qoderMessages（QoderProtocol.apply）去补 id／配对，所以这里按
                         * 上游拿到的报文正向断言（见 repairedChatHistory）。 */
                        check(code==200,kind+" repaired history status "+code+" mode="+mode+" "+text);
                        check(text.contains("early-marker"),kind+" repaired history never saw the reply mode="+mode+" "+text);
                    } else {
                        /* /v1/messages 与 /v1/responses 仍走严格规范化：状态码与「一个字节不上行」不变。 */
                        check(code>=400 && code<500,kind+" invalid history status "+code+" mode="+mode);
                    }
                } finally { c.disconnect(); }
                check(kind.equals("chat") ? primary.calls.get()==1 : primary.calls.get()==0,kind+" invalid history upstream calls mode="+mode+" "+primary.calls.get());
                check(backup.calls.get()==0,kind+" invalid history reached fallback mode="+mode);
                if(kind.equals("chat")) repairedChatHistory(mode,primary.lastRequest);
            }
        }
        if(kind.equals("chat")) nonQoderChatHistory();
    }
    /** The relayed history of one repaired chat case, asserted as 0.2.64 qoderMessages shapes it. */
    static void repairedChatHistory(int mode,String encoded) throws Exception {
        JSONArray wire=new JSONObject(QoderAuth.decodeBody(encoded)).getJSONArray("messages");
        String all=wire.toString();
        check(!all.contains("unavailable") && !all.contains("interrupted"),
                "chat history "+mode+" was answered with an invented result: "+all);
        java.util.List<String> answered=new java.util.ArrayList<String>(), said=new java.util.ArrayList<String>();
        int assistantTurns=0;
        JSONArray open=null;   /* 当前这组工具回合能回答的调用：最近一个带调用的助手回合，离开这组就作废 */
        for(int i=0;i<wire.length();i++) {
            JSONObject turn=wire.getJSONObject(i);
            if(turn.optJSONArray("tool_calls")!=null) {
                JSONArray calls=turn.getJSONArray("tool_calls");
                check(calls.length()==2,"chat history "+mode+" call count "+calls.length()+": "+all);
                for(int k=0;k<calls.length();k++)
                    check(!calls.getJSONObject(k).optString("id").isEmpty(),"chat history "+mode+" left a call without an id: "+all);
                check("private reasoning".equals(turn.optString("reasoning_content")),"chat history "+mode+" dropped the assistant's thinking: "+all);
                assistantTurns++; open=calls; continue;
            }
            if(!"tool".equals(turn.optString("role"))) { open=null; continue; }
            /* Qoder 只收「回答前面那个带调用的助手回合里某个调用」的工具回合。 */
            check(open!=null,"chat history "+mode+" sent a tool turn answering no call of a preceding call turn: "+all);
            String id=turn.getString("tool_call_id"); boolean named=false;
            for(int k=0;k<open.length();k++) if(id.equals(open.getJSONObject(k).optString("id"))) named=true;
            check(named,"chat history "+mode+" answered a call no preceding turn made: "+all);
            answered.add(id); said.add(turn.optString("content"));
        }
        check(assistantTurns==1,"chat history "+mode+" assistant turns "+assistantTurns+": "+all);
        if(mode==0) {
            /* 客户端把回答 call-a 的那一轮整个丢了：插件不替它造结果，call-a 原样没人回答。 */
            check(answered.equals(java.util.Collections.singletonList("call-b")) && "result-b".equals(said.get(0)),
                    "chat history 0 routing "+answered+" "+said+": "+all);
        } else if(mode==1) {
            /* 两份结果都写 call-b：第一份回答 call-b，第二份回答本回合最早还没被回答的 call-a，文字不丢。 */
            check(answered.equals(Arrays.asList("call-b","call-a")),"chat history 1 pairing "+answered+": "+all);
            check(said.equals(Arrays.asList("result-b","result-a")),"chat history 1 words "+said+": "+all);
        } else {
            /* 结果被搬到助手回合之前：它变成用户自己那一轮的文字，后面那条结果回答 call-a。 */
            check(answered.equals(java.util.Collections.singletonList("call-a")) && "result-a".equals(said.get(0)),
                    "chat history 2 routing "+answered+" "+said+": "+all);
            check(all.contains("result-b"),"chat history 2 dropped the orphan result's words: "+all);
        }
    }
    /**
     * 反面对照：同一条 chat 病史改走非 Qoder 路由（Qoder + 非 Qoder 兜底的混组、纯非 Qoder 模型）时，
     * Qoder 适配器一次都不被调用，严格规范化照旧 —— 插件语义只在候选全 Qoder 的路由上生效。
     */
    static void nonQoderChatHistory() throws Exception {
        Map<String,long[]> rest=rests();
        String[] keys=new String[]{"qoder-test-backup/fixture-model","group/qoder-test-group"};
        Map<String,long[]> previous=new HashMap<String,long[]>();
        for(String key:keys) { long[] held=rest.remove(key); if(held!=null) previous.put(key,held); }
        try {
            for(int mode=0;mode<3;mode++) {
                JSONObject body=request("chat",false,true);
                JSONArray messages=body.getJSONArray("messages");
                if(mode==0) messages.remove(3); else if(mode==1) messages.getJSONObject(3).put("tool_call_id","call-b");
                else { JSONObject orphan=messages.getJSONObject(2); messages.remove(2); messages.put(0,orphan); }
                try(Upstream primary=new Upstream(sse(first()+tail())); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
                    /* 混组里还有非 Qoder 候选：按严格口径本地拒绝，两台上游都没有请求。 */
                    check(status(g,"chat",body.put("model","group/qoder-test-group"))==400,
                            "mixed group must refuse what the Qoder-only route repairs mode="+mode);
                    check(primary.calls.get()==0 && backup.calls.get()==0,"mixed group reached an upstream mode="+mode);
                    /* 纯非 Qoder 模型：仍由严格规范化处理（它替没人回答的调用补一条结果），Qoder 适配器不参与。 */
                    int code=status(g,"chat",body.put("model","qoder-test-backup/fixture-model"));
                    check(code!=200,"a non-Qoder route reported the unrepaired history as success mode="+mode+" "+code);
                    check(primary.calls.get()==0,"a non-Qoder route handed the history to the Qoder adapter mode="+mode);
                    check(backup.calls.get()==1,"the non-Qoder route did not reach its own upstream mode="+mode);
                    JSONArray strictWire=new JSONObject(backup.lastRequest).getJSONArray("messages");
                    int answers=0, invented=0;
                    for(int i=0;i<strictWire.length();i++) {
                        JSONObject turn=strictWire.getJSONObject(i);
                        if(!"tool".equals(turn.optString("role"))) continue;
                        answers++; String text=turn.optString("content");
                        if(!"result-a".equals(text) && !"result-b".equals(text)) invented++;
                    }
                    check(answers==2 && invented>=1,"the non-Qoder route no longer runs the strict normalizer: "+strictWire);
                }
            }
        } finally {
            for(String key:keys) rest.remove(key);
            for(Map.Entry<String,long[]> held:previous.entrySet()) rest.put(held.getKey(),held.getValue());
        }
    }
    static void serviceStopCancellation(String kind) throws Exception {
        try(Upstream primary=new Upstream(""); Upstream backup=new Upstream(http(500,"backup"))) {
            primary.first=first(); primary.tail=tail(); primary.cancellationProbe=true;
            try(Gateway g=new Gateway(config(backup),primary)) {
                HttpURLConnection c=connect(g,kind,request(kind,true,false));
                try {
                    check(c.getResponseCode()==200,kind+" stop status"); InputStream in=c.getInputStream(); StringBuilder text=new StringBuilder(); int ch;
                    while((ch=in.read())!=-1 && text.indexOf("early-marker")<0) text.append((char)ch);
                    check(text.indexOf("early-marker")>=0,kind+" missing early content");
                    Thread stop=new Thread(g.inner::stop,"qoder-test-stop"); stop.setDaemon(true); stop.start();
                    check(primary.peerClosed.await(6,java.util.concurrent.TimeUnit.SECONDS),kind+" service stop did not close upstream");
                    stop.join(3000); check(!stop.isAlive(),kind+" service stop blocked");
                } finally { c.disconnect(); }
                check(primary.calls.get()==1 && backup.calls.get()==0,kind+" service stop retried");
            }
        }
    }
    // An idle closed TCP peer is not observable while the gateway is blocked reading upstream.
    // Exercise cancellation only after more upstream data forces a downstream write.
    static void downstreamDisconnectOnWrite(String kind) throws Exception {
        try(Upstream primary=new Upstream(""); Upstream backup=new Upstream(http(500,"backup"))) {
            primary.first=first(); primary.downstreamProbe=true;
            try(Gateway g=new Gateway(config(backup),primary); Socket client=new Socket("127.0.0.1",g.getListeningPort())) {
                client.setSoTimeout(6000); byte[] payload=bytes(request(kind,true,false).toString());
                OutputStream out=client.getOutputStream();
                out.write(bytes("POST "+path(kind)+" HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer fixture-auth\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+payload.length+"\r\n\r\n")); out.write(payload); out.flush();
                InputStream in=client.getInputStream(); StringBuilder text=new StringBuilder(); int ch;
                while((ch=in.read())!=-1 && text.indexOf("early-marker")<0) text.append((char)ch);
                check(text.indexOf("early-marker")>=0,kind+" missing early content before disconnect");
                client.setSoLinger(true,0); client.close(); primary.release.countDown();
                check(primary.peerClosed.await(6,java.util.concurrent.TimeUnit.SECONDS),kind+" continued write did not close upstream");
                check(primary.calls.get()==1 && backup.calls.get()==0,kind+" disconnect retried");
            }
        }
    }
    static void finishAndUnknown(String kind) throws Exception {
        String wire=first()+delta(new JSONObject(),"length")+done();
        try(Upstream primary=new Upstream(sse(wire)); Upstream backup=new Upstream(http(500,"backup")); Gateway g=new Gateway(config(backup),primary)) {
            String result=response(g,kind,request(kind,false,false));
            check(result.contains(kind.equals("messages")?"max_tokens":kind.equals("responses")?"max_output_tokens":"length"),kind+" length "+result);
        }
    }
    /** One tool call plus the terminal frames, so a named choice can be honoured. */
    static String singleCall(String name) {
        return delta(new JSONObject().put("tool_calls", new JSONArray().put(tool(0, "call-a", name, "{\"a\":1}"))), null)
                + delta(new JSONObject(), "tool_calls") + usage() + done();
    }
    /** The same request with the selected tool named, in the shape each surface accepts. */
    static JSONObject namedRequest(String kind, String name) {
        JSONObject named = request(kind, false, false);
        if (kind.equals("messages")) named.put("tool_choice", new JSONObject().put("type", "tool").put("name", name));
        else if (kind.equals("responses")) named.put("tool_choice", new JSONObject().put("type", "function").put("name", name));
        else named.put("tool_choice", new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)));
        return named;
    }
    static int status(Gateway g, String kind, JSONObject body) throws Exception {
        HttpURLConnection c = connect(g, kind, body);
        try { int code = c.getResponseCode(); readAll(code >= 400 ? c.getErrorStream() : c.getInputStream()); return code; }
        finally { c.disconnect(); }
    }
    /**
     * Qoder cannot be told to call one tool, so a named choice travels as "the tools are
     * available"; a reply that then ignores the selected tool still fails closed rather
     * than passing for success, and an undeclared name never leaves the device.
     */
    static void namedChoice(String kind) throws Exception {
        try (Upstream primary = new Upstream(sse(first() + singleCall("one"))); Upstream backup = new Upstream(http(500, "backup"))) {
            try (Gateway g = new Gateway(config(backup), primary)) {
                response(g, kind, namedRequest(kind, "one"));
                JSONObject upstream = new JSONObject(QoderAuth.decodeBody(primary.lastRequest));
                check(upstream.getJSONArray("tools").length() == 2, kind + " named choice dropped the tools");
                check(!upstream.getJSONArray("system").getJSONObject(0).getString("text").contains("You must call an available function"),
                        kind + " named choice must not become a required one");
            }
        }
        try (Upstream primary = new Upstream(sse(first() + singleCall("two"))); Upstream backup = new Upstream(http(500, "backup"))) {
            try (Gateway g = new Gateway(config(backup), primary)) {
                check(status(g, kind, namedRequest(kind, "one")) >= 500,
                        kind + " a reply calling another tool must not be reported as success");
            }
        }
        try (Upstream primary = new Upstream(sse(first() + singleCall("one"))); Upstream backup = new Upstream(http(500, "backup"))) {
            try (Gateway g = new Gateway(config(backup), primary)) {
                int before = primary.calls.get();
                check(status(g, kind, namedRequest(kind, "absent")) >= 400 && status(g, kind, namedRequest(kind, "absent")) < 500,
                        kind + " undeclared named choice is a local rejection");
                check(primary.calls.get() == before, kind + " undeclared named choice reached upstream");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        String q=new JSONObject().put("code",10605).put("queue",new JSONObject().put("retryAfterSeconds",7)).toString();
        scenario("early queue",http(403,q),503,true,",\"max_tokens\":64",1);
        for(String kind:new String[]{"chat","messages","responses"}) { for(boolean stream:new boolean[]{false,true}) matrix(kind,stream); live(kind); failures(kind); finishAndUnknown(kind); usageCases(kind); invalidHistory(kind); serviceStopCancellation(kind); downstreamDisconnectOnWrite(kind); namedChoice(kind); }
        System.out.println("QoderAgentGatewayHttpTest passed");
    }
}
