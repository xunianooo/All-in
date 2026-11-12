package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic loopback only. The bridge fixture is replayed unchanged before/after the fix. */
public final class ProtocolDiagnosticsHttpTest {
    static final JSONObject SAMPLES = new JSONObject();
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean missingId = true;
        volatile int rejectCount, status = 429;
        volatile String code = "rate_limit_error";
        volatile long delay;
        Upstream() throws Exception { super("127.0.0.1", 0); start(SOCKET_READ_TIMEOUT, true); }
        public Response serve(IHTTPSession session) {
            try {
                Map<String,String> files = new HashMap<>(); session.parseBody(files);
                JSONObject request = new JSONObject(files.get("postData")); int n = calls.incrementAndGet();
                if (delay > 0) Thread.sleep(delay);
                if (n <= rejectCount) {
                    Response response = newFixedLengthResponse(Response.Status.lookup(status), "application/json",
                            new JSONObject().put("error", new JSONObject().put("code", code)).toString());
                    response.addHeader("Retry-After", "1"); return response;
                }
                if (request.optBoolean("stream")) {
                    String body = session.getUri().endsWith("/messages")
                            ? "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"native-fixture\",\"role\":\"assistant\",\"content\":[]}}\n\n"
                              + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                              + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                              + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                              + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
                              + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                            : "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                              + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                    return newChunkedResponse(Response.Status.OK,"text/event-stream",new ByteArrayInputStream(bytes(body)));
                }
                JSONObject call = new JSONObject().put("type", "function").put("function",
                        new JSONObject().put("name", "lookup").put("arguments", "{}"));
                if (!missingId) call.put("id", "call-preserved");
                JSONObject reply = new JSONObject().put("id", "response-fixture").put("model", "actual-model")
                        .put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject()
                                .put("role", "assistant").put("tool_calls", new JSONArray().put(call))).put("finish_reason", "tool_calls")));
                return newFixedLengthResponse(Response.Status.OK, "application/json", reply.toString());
            } catch (Exception failure) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "fixture failed"); }
        }
        public void close() { stop(); }
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject cfg, Path directory) throws Exception {
            super("127.0.0.1", 0); inner = RoutingSetupOfflineTest.server(cfg);
            RoutingAuditOfflineTest.file(inner, directory.resolve("config.json").toFile());
            Field turns = GatewayServer.class.getDeclaredField("groupTurns"); turns.setAccessible(true);
            turns.set(inner, new ConcurrentHashMap<String,Integer>()); start(SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() { inner.stop(); stop(); }
    }
    static JSONObject config(Upstream first, Upstream second) {
        JSONObject a = new JSONObject().put("id", "diagnostic-a").put("protocol", "openai")
                .put("baseUrl", "http://127.0.0.1:" + first.getListeningPort() + "/v1")
                .put("keys", new JSONArray().put("synthetic-key-a")).put("models", new JSONArray().put("m"));
        JSONObject b = new JSONObject(a.toString()).put("id", "diagnostic-b")
                .put("baseUrl", "http://127.0.0.1:" + second.getListeningPort() + "/v1")
                .put("keys", new JSONArray().put("synthetic-key-b"));
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(a).put(b))
                .put("routing", new JSONObject().put("groupsEnabled", true))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "contract").put("strategy", "order")
                        .put("targets", new JSONArray().put("diagnostic-a/m").put("diagnostic-b/m"))));
    }
    static JSONObject primaryOnly(JSONObject config) {
        config.getJSONArray("groups").getJSONObject(0).put("targets",new JSONArray().put("diagnostic-a/m"));return config;
    }
    static final class Result { int status; String body; }
    static Result request(Gateway gateway, String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + gateway.getListeningPort() + path).openConnection(java.net.Proxy.NO_PROXY);
        conn.setReadTimeout(12000); conn.setConnectTimeout(3000);
        conn.setRequestProperty("Authorization", "Bearer fixture-auth");
        if (path.startsWith("/api/")) conn.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        if (body != null) {
            conn.setDoOutput(true); conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = conn.getOutputStream()) { output.write(bytes(body)); }
        }
        Result result = new Result(); result.status = conn.getResponseCode();
        try (InputStream input = result.status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            result.body = input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally { conn.disconnect(); }
        return result;
    }
    static String payload(boolean messages, boolean stream) {
        return new JSONObject().put("model", "group/contract").put("stream", stream).put("max_tokens", 32)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "prompt-sentinel-not-in-diagnostics"))).toString();
    }
    static void awaitRows(List<UsageLedger.Row> rows,int count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(rows.size()<count && System.nanoTime()<until)Thread.sleep(5);
        check(rows.size()==count,"ledger row count "+rows.size()+" != "+count);
    }
    static void counts(List<UsageLedger.Row> rows,int attempts) throws Exception {
        awaitRows(rows,attempts+1);String id=null;int terminal=0,attempt=0;
        synchronized(rows) {for(UsageLedger.Row row:rows) {
            if(id==null)id=row.requestId;check(id.equals(row.requestId),"one logical ID");
            if(row.terminal)terminal++;if(row.attempt>0)attempt++;
        }}
        check(terminal==1 && attempt==attempts,"one terminal, no second ledger");
    }
    static void sample(Gateway gateway,String name) throws Exception {
        Result response=request(gateway,"/api/sessions?limit=50",null);check(response.status==200,"sessions API");
        JSONObject sessions=new JSONObject(response.body);JSONArray rows=sessions.getJSONArray("sessions");
        Map<String,JSONObject> expected=new HashMap<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i),diagnostics=row.getJSONObject("diagnostics");
            RequestDiagnosticsOfflineTest.whitelist(diagnostics);
            expected.put(row.getString("requestId")+":"+row.getInt("attempt"),diagnostics);
        }
        Result usageResponse=request(gateway,"/api/usage?days=1",null);check(usageResponse.status==200,"usage API");
        JSONArray usageRecent=new JSONObject(usageResponse.body).getJSONArray("recent"), projected=new JSONArray();
        for(int i=0;i<usageRecent.length();i++) {
            JSONObject row=usageRecent.getJSONObject(i),diagnostics=row.getJSONObject("diagnostics");
            String key=row.getString("requestId")+":"+row.getInt("attempt");
            check(diagnostics.similar(expected.get(key)),"shared sessions/usage diagnostics projection");
            projected.put(diagnostics);
        }
        SAMPLES.put(name,new JSONObject().put("sessionsResponse",sessions).put("usageRecentDiagnostics",projected));
    }
    static Path fresh(Path root,String name,List<UsageLedger.Row> rows) throws Exception {
        Path dir=Files.createDirectories(root.resolve(name));UsageLedger.init(dir.toFile());rows.clear();return dir;
    }
    static void bridgeFixture(List<UsageLedger.Row> rows, Path root) throws Exception {
        rows.clear();
        try (Upstream a = new Upstream(); Upstream b = new Upstream(); Gateway g = new Gateway(config(a,b), root)) {
            b.missingId = false;
            Result result = request(g, "/v1/messages", payload(true,false));
            System.out.println("bridge fixture HTTP=" + result.status + " firstCalls=" + a.calls + " fallbackCalls=" + b.calls + " body=" + result.body);
            check(result.status == 502, "Messages missing output tool ID must be HTTP502, not a HTTP200 error envelope");
            check(a.calls.get() == 1 && b.calls.get() == 0, "executed invalid tool response must never replay/fallback");
            check(new JSONObject(result.body).has("error"), "safe protocol error body");counts(rows,1);
            for(UsageLedger.Row row:rows) {
                JSONObject d=RequestDiagnostics.json(row);check("protocol".equals(d.getString("failureKind")),"protocol failure source");
                check("unknown_execution".equals(d.getString("executionState")),"unusable executed response is not not_sent/rejected");
                check(d.isNull("deliveryConfirmed"),"early bridge error has no writer confirmation");
            }
            sample(g,"messagesBridgeError");
        }
    }
    static void validAndUnknown(List<UsageLedger.Row> rows,Path root) throws Exception {
        Path dir=fresh(root,"valid",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream();Gateway g=new Gateway(config(a,b),dir)) {
            a.missingId=false;Result result=request(g,"/v1/messages",payload(true,false));
            check(result.status==200 && result.body.contains("call-preserved"),"valid output ID preserved");awaitRows(rows,1);
            JSONObject d=RequestDiagnostics.json(rows.get(0));
            check(d.isNull("executionState") && d.isNull("deliveryConfirmed"),"early success not guessed delivered");
            check("m".equals(d.getJSONObject("target").getString("model")),"route model, not request group or reported response model");
            check("unknown".equals(rows.get(0).effectiveUsageSource()),"no usage counters stays unknown");sample(g,"earlySuccess");
        }
        dir=fresh(root,"special",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream()) {
            a.rejectCount=10;
            JSONObject cfg=primaryOnly(config(a,b));cfg.getJSONArray("providers").getJSONObject(0).put("type","subscription");
            try(Gateway g=new Gateway(cfg,dir)) {
                check(request(g,"/v1/chat/completions",payload(false,false)).status==429,"special rejection");counts(rows,1);
                check(RequestDiagnostics.json(rows.get(0)).isNull("executionState"),"uncovered special transport sent=false is null");sample(g,"specialUnobserved");
            }
        }
    }
    static void queueAndPatience(List<UsageLedger.Row> rows,Path root) throws Exception {
        Path dir=fresh(root,"queue",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream()) {
            a.missingId=false;JSONObject cfg=primaryOnly(config(a,b));
            JSONObject policy=new JSONObject().put("minIntervalMillis",0).put("rpm",1).put("maxInFlight",0).put("maxQueue",8).put("maxWaitMillis",80);
            cfg.put("outbound",new JSONObject().put("diagnostic-a",new JSONObject().put("credential",policy)));
            try(Gateway g=new Gateway(cfg,dir)) {
                check(request(g,"/v1/chat/completions",payload(false,false)).status==200,"preload RPM");awaitRows(rows,1);rows.clear();
                check(request(g,"/v1/chat/completions",payload(false,false)).status==503,"bounded queue timeout");counts(rows,1);
                UsageLedger.Row attempt=rows.get(0);JSONObject d=RequestDiagnostics.json(attempt);
                check("not_sent".equals(d.getString("executionState")) && "admission".equals(d.getString("failureKind")),"queue rejected locally before handoff");
                check(d.getLong("queuedMs")>=60 && a.calls.get()==1 && b.calls.get()==0,"finally measures timeout and does not send");sample(g,"queueTimeout");
            }
        }
        dir=fresh(root,"patience",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream()) {
            a.rejectCount=1;a.missingId=false;JSONObject cfg=primaryOnly(config(a,b));
            cfg.put("resilience",new JSONObject().put("patienceMillis",10000).put("heartbeat",false));
            try(Gateway g=new Gateway(cfg,dir)) {
                check(request(g,"/v1/chat/completions",payload(false,false)).status==200,"patience rejection then success");awaitRows(rows,2);
                check(rows.stream().filter(UsageLedger.Row::isTerminal).count()==1 && a.calls.get()==2,"one logical terminal after two attempts");
                JSONObject failed=RequestDiagnostics.json(rows.get(0)),ended=RequestDiagnostics.json(rows.get(1));
                check("rejected".equals(failed.getString("executionState")),"structured 429 confirmed rejection");
                check(ended.getLong("patienceWaitMs")>=1900 && ended.getLong("logicalDurationMs")>=ended.getLong("patienceWaitMs"),"actual monotonic wait, not configured window");
                check(ended.isNull("deliveryConfirmed") && ended.isNull("executionState"),"retry successful early row still unconfirmed");sample(g,"patienceSuccess");
            }
        }
    }
    static void heldAndNative(List<UsageLedger.Row> rows,Path root) throws Exception {
        Path dir=fresh(root,"held",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream()) {
            a.delay=3500;a.rejectCount=1;JSONObject cfg=primaryOnly(config(a,b));
            cfg.put("resilience",new JSONObject().put("patienceMillis",0).put("heartbeat",true));
            try(Gateway g=new Gateway(cfg,dir)) {
                Result result=request(g,"/v1/chat/completions",payload(false,true));
                check(result.status==200 && result.body.contains(": heartbeat") && result.body.contains("error"),"late rejection keeps committed HTTP200");counts(rows,1);
                for(UsageLedger.Row row:rows) {JSONObject d=RequestDiagnostics.json(row);
                    check("headers".equals(d.getString("responsePhase")),"heartbeat/framing never business");
                    check(!"delivered".equals(d.optString("executionState")) && d.isNull("deliveryConfirmed"),"error not successful delivery");}
                sample(g,"heldLateRejection");
            }
        }
        dir=fresh(root,"native",rows);
        try(Upstream a=new Upstream();Upstream b=new Upstream()) {
            JSONObject cfg=primaryOnly(config(a,b));cfg.getJSONArray("providers").getJSONObject(0).put("protocol","anthropic");
            try(Gateway g=new Gateway(cfg,dir)) {
                Result result=request(g,"/v1/messages",payload(true,true));check(result.status==200 && result.body.contains("message_stop"),"valid native Messages stream");awaitRows(rows,1);
                JSONObject d=RequestDiagnostics.json(rows.get(0));
                check("delivered".equals(d.getString("executionState")) && d.getBoolean("deliveryConfirmed"),"existing native terminal + writer callback confirms delivery");
                check("headers".equals(d.getString("responsePhase")),"conservative phase separate from exact native delivery callback");
                check("unknown".equals(rows.get(0).effectiveUsageSource()),"delivered without usage stays unknown");sample(g,"nativeDelivered");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null); Path root = Files.createTempDirectory("protocol-diagnostics-http-");
        UsageLedger.init(root.toFile()); List<UsageLedger.Row> rows = Collections.synchronizedList(new ArrayList<>());
        UsageLedger.setHooks(rows::add);
        try {
            bridgeFixture(rows,root);
            if(args.length==0 || !"bridge".equals(args[0])) {validAndUnknown(rows,root);queueAndPatience(rows,root);heldAndNative(rows,root);}
            String output=System.getProperty("allin.diagnostics.samples");if(output!=null)Files.write(Paths.get(output),bytes(SAMPLES.toString(2)));
            System.out.println("ProtocolDiagnosticsHttpTest OK: bridge/no replay, null coverage, queue/patience, held headers, native callback");
        } finally {
            UsageLedger.setHooks(null); java.lang.reflect.Method close = UsageLedger.class.getDeclaredMethod("closeWriter"); close.setAccessible(true); close.invoke(null);
        }
    }
}
