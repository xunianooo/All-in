package com.gaeul.allin;

import fi.iki.elonen.AllInTrackedResponse;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Loopback-only integration tests. No device configuration or real credentials are used. */
public final class RecentUsageRoutingHttpTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String KEY1="synthetic-key-one", KEY2="synthetic-key-two";
    private static Path directory;
    private static RecentUsageScheduler scheduler;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void near(String ref, double expected) {
        double actual=scheduler.score(ref);
        check(actual >= expected-0.2 && actual <= expected+0.01, ref+" score expected "+expected+" got "+actual);
    }
    private static void awaitIdle(String... refs) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            boolean idle=true; for(String r:refs) idle &= scheduler.inFlight(r)==0;
            if(idle) return; Thread.sleep(10);
        }
        throw new AssertionError("leases not released: "+Arrays.toString(refs));
    }
    private interface Script { Reply reply(String key, String model, int n); }
    private static final class Reply {
        int status=200; String body; boolean sse, hold, flood;
        Reply(String body) { this.body=body; }
        Reply status(int code) { status=code; return this; }
        Reply stream() { sse=true; return this; }
        Reply hold() { hold=true; return this; }
        Reply flood() { flood=true; return this; }
    }
    private static final class Upstream implements AutoCloseable {
        final ServerSocket server; final ExecutorService workers=Executors.newCachedThreadPool();
        final Thread acceptor; final Script script; final AtomicInteger requests=new AtomicInteger();
        final List<String> keys=Collections.synchronizedList(new ArrayList<String>());
        final List<String> models=Collections.synchronizedList(new ArrayList<String>());
        final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<Socket>());
        final CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        volatile Throwable failure;
        Upstream(Script script) throws Exception {
            this.script=script; server=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));
            acceptor=new Thread(() -> { while(!server.isClosed()) try {
                final Socket client=server.accept(); sockets.add(client); workers.submit(() -> handle(client));
            } catch(IOException e) { if(!server.isClosed()) failure=e; } }, "usage-fixture-accept");
            acceptor.start();
        }
        void handle(Socket socket) {
            try(Socket client=socket) {
                client.setSoTimeout(10000); InputStream in=client.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream();
                int state=0,c; while(state<4 && (c=in.read())!=-1) { headers.write(c); state=state==0&&c=='\r'?1:state==1&&c=='\n'?2:state==2&&c=='\r'?3:state==3&&c=='\n'?4:0; if(headers.size()>65536) throw new IOException("headers too large"); }
                String key=""; int length=0;
                for(String line:headers.toString("UTF-8").split("\r\n")) { int colon=line.indexOf(':'); if(colon<0) continue;
                    String name=line.substring(0,colon).trim().toLowerCase(Locale.ROOT), value=line.substring(colon+1).trim();
                    if(name.equals("content-length")) length=Integer.parseInt(value);
                    if(name.equals("authorization")) key=value.startsWith("Bearer ")?value.substring(7):value;
                    if(name.equals("x-api-key")) key=value;
                }
                byte[] data=new byte[length]; int off=0; while(off<length) { int n=in.read(data,off,length-off); if(n<0) throw new EOFException(); off+=n; }
                String model=new JSONObject(new String(data,StandardCharsets.UTF_8)).optString("model");
                keys.add(key); models.add(model); Reply r=script.reply(key,model,requests.incrementAndGet());
                OutputStream out=client.getOutputStream();
                if(r.sse) {
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"));
                    out.write(bytes(r.body)); out.flush(); started.countDown();
                    if(r.hold) { if(!release.await(10,TimeUnit.SECONDS)) throw new IOException("hold timed out"); }
                    if(r.flood) {
                        byte[] chunk=bytes("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""+repeat('x',12000)+"\"}}]}\n\n");
                        try { for(int i=0;i<2000;i++) { out.write(chunk); out.flush(); } } catch(IOException disconnected) { }
                    } else if(r.hold) {
                        // never-sent close deliberately disconnects this held upstream before release.
                        // Only the final fixture write may observe the expected peer cancellation.
                        try { out.write(bytes(OPENAI_END)); out.flush(); }
                        catch(SocketException cancelledPeer) { }
                    }
                } else {
                    byte[] body=bytes(r.body);
                    out.write(bytes("HTTP/1.1 "+r.status+" Fixture\r\nContent-Type: application/json\r\nRetry-After: 120\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n")); out.write(body); out.flush();
                }
            } catch(Throwable e) { if(!server.isClosed()) failure=e; }
            finally { sockets.remove(socket); }
        }
        public void close() throws Exception {
            release.countDown(); server.close(); synchronized(sockets) { for(Socket s:sockets) try { s.close(); } catch(IOException ignored) {} }
            acceptor.join(10000); workers.shutdownNow(); check(workers.awaitTermination(10,TimeUnit.SECONDS),"upstream workers stopped");
            check(!acceptor.isAlive(),"upstream acceptor stopped"); if(failure!=null) throw new AssertionError("upstream failed",failure);
        }
    }
    private static String repeat(char ch,int count) { char[] a=new char[count]; Arrays.fill(a,ch); return new String(a); }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject cfg) throws Exception {
            super("127.0.0.1",0); inner=RoutingSetupOfflineTest.server(cfg);
            Field f=GatewayServer.class.getDeclaredField("configFile"); f.setAccessible(true); f.set(inner,directory.resolve("config-"+IDS.incrementAndGet()+".json").toFile());
            f=GatewayServer.class.getDeclaredField("groupTurns"); f.setAccessible(true); f.set(inner,new ConcurrentHashMap<String,Integer>());
            start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        protected boolean useGzipWhenAccepted(Response r) { return inner.useGzipWhenAccepted(r); }
        public void close() { stop(); }
    }
    private static JSONObject provider(Upstream u,String id,String protocol,boolean twoKeys) {
        JSONArray keys=new JSONArray().put(KEY1); if(twoKeys) keys.put(KEY2);
        return new JSONObject().put("id",id).put("protocol",protocol).put("baseUrl","http://127.0.0.1:"+u.server.getLocalPort()+"/v1")
                .put("keys",keys).put("models",new JSONArray().put("model"));
    }
    private static final class Fixture implements AutoCloseable {
        final String a="usagea"+IDS.incrementAndGet(), b="usageb"+IDS.incrementAndGet();
        final String ra=a+"/model", rb=b+"/model"; final Upstream one,two; final JSONObject cfg; final Gateway gateway;
        Fixture(Script first, Script second, String protocol, boolean twoKeys) throws Exception {
            one=new Upstream(first); two=new Upstream(second);
            cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("privacy",new JSONObject().put("enabled",false)).put("routing",new JSONObject().put("groupsEnabled",true))
                .put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",8))
                .put("providers",new JSONArray().put(provider(one,a,protocol,twoKeys)).put(provider(two,b,protocol,twoKeys)))
                .put("groups",new JSONArray().put(group("usage",ra,rb)));
            gateway=new Gateway(cfg);
        }
        public void close() throws Exception { gateway.close(); try { one.close(); } finally { two.close(); } }
    }
    private static JSONObject group(String strategy,String a,String b) {
        return new JSONObject().put("id","g").put("strategy",strategy).put("targets",new JSONArray().put(a).put(b));
    }
    private static String openai(String usage) { return "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":"+usage+"}"; }
    private static final String OA_START="data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n";
    private static final String OPENAI_END="data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\ndata: [DONE]\n\n";
    private static final String AN_START="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"model\",\"content\":[],\"usage\":{\"input_tokens\":11}}}\n\n";
    private static final String AN_END="event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    private static JSONObject request(String model,boolean stream,String protocol) {
        JSONObject result=new JSONObject().put("model",model).put("stream",stream).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        if(protocol.equals("anthropic")) {
            result.put("max_tokens",32);
            if (stream) result.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONArray()
                    .put(new JSONObject().put("type","thinking").put("thinking","reason").put("signature","fixture-signature"))));
        }
        return result;
    }
    private static final class Result { int status; String body; Map<String,List<String>> headers; }
    private static Result http(Gateway gateway,String path,String method,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();
        if(path.startsWith("/api/")) c.setRequestProperty("X-All-In-Management",GatewayManagementCredential.get()); else c.setRequestProperty("Authorization","Bearer fixture-auth");
        c.setConnectTimeout(5000); c.setReadTimeout(15000); c.setRequestMethod(method); c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("anthropic-version","2023-06-01");
        if(body!=null) { c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); try(OutputStream out=c.getOutputStream()) { out.write(bytes(body)); } }
        Result r=new Result(); r.status=c.getResponseCode(); r.headers=c.getHeaderFields(); InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream result=new ByteArrayOutputStream(); if(in!=null) try(InputStream input=in) { byte[] buf=new byte[4096]; int n; while((n=input.read(buf))!=-1) result.write(buf,0,n); }
        r.body=result.toString("UTF-8"); c.disconnect(); return r;
    }
    private static Result post(Fixture f,String model,boolean stream,String protocol) throws Exception {
        return http(f.gateway,protocol.equals("anthropic")?"/v1/messages":"/v1/chat/completions","POST",request(model,stream,protocol).toString());
    }
    private static void apiAndOrdering() throws Exception {
        try(Fixture f=new Fixture((k,m,n)->new Reply(openai("{\"prompt_tokens\":200,\"completion_tokens\":50}")),
                (k,m,n)->new Reply(openai("{\"prompt_tokens\":11,\"completion_tokens\":7}")),"openai",false)) {
            for(String strategy:new String[]{"usage","order","rotate","manual"}) {
                JSONObject g=group(strategy,f.ra,f.rb); if(strategy.equals("manual")) g.put("pick",f.rb);
                Result saved=http(f.gateway,"/api/groups","POST",new JSONObject().put("groups",new JSONArray().put(g)).toString());
                check(saved.status==200 && new JSONObject(saved.body).getJSONArray("groups").getJSONObject(0).getString("strategy").equals(strategy),"API accepts "+strategy+": "+saved.body);
                Result read=http(f.gateway,"/api/groups","GET",null);
                check(new JSONObject(read.body).getJSONArray("groups").getJSONObject(0).getString("strategy").equals(strategy),"API saved "+strategy);
            }
            http(f.gateway,"/api/groups","POST",new JSONObject().put("groups",new JSONArray().put(group("usage",f.ra,f.rb))).toString());
            check(post(f,f.ra,false,"openai").status==200,"explicit A response"); awaitIdle(f.ra); near(f.ra,250);
            check(post(f,"group/g",false,"openai").status==200,"usage group response"); awaitIdle(f.rb);
            check(f.two.requests.get()==1,"group chose lower usage B"); near(f.rb,18);
        }
    }
    private static void accounting() throws Exception {
        try(Fixture f=new Fixture((k,m,n)->new Reply(openai("{\"prompt_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":5}}")),
                (k,m,n)->new Reply("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":11,\"output_tokens\":7,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}}"),"openai",false)) {
            check(post(f,f.ra,false,"openai").status==200,"partial response"); awaitIdle(f.ra); near(f.ra,12);
            f.cfg.getJSONArray("providers").getJSONObject(1).put("protocol","anthropic");
            check(post(f,f.rb,false,"anthropic").status==200,"anthropic cache response"); awaitIdle(f.rb); near(f.rb,25);
        }
        try(Fixture f=new Fixture((k,m,n)->new Reply(openai("{}")),(k,m,n)->new Reply(openai("{}")),"openai",false)) {
            check(post(f,f.ra,false,"openai").status==200,"unknown usage response"); awaitIdle(f.ra); near(f.ra,1);
        }
        try(Fixture f=new Fixture((k,m,n)->new Reply("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate_limit_error\"},\"usage\":{\"prompt_tokens\":500,\"completion_tokens\":500}}").status(429),
                (k,m,n)->new Reply(openai("{\"prompt_tokens\":4,\"completion_tokens\":6}")),"openai",false)) {
            check(post(f,"group/g",false,"openai").status==200,"fallback succeeded"); awaitIdle(f.ra,f.rb);
            near(f.ra,0); near(f.rb,10); check(f.one.requests.get()==1 && f.two.requests.get()==1,"fallback targets");
        }
        try(Fixture f=new Fixture((k,m,n)->k.equals(KEY1)?new Reply("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate_limit_error\"},\"usage\":{\"prompt_tokens\":500,\"completion_tokens\":500}}").status(429):new Reply(openai("{\"prompt_tokens\":4,\"completion_tokens\":6}")),
                (k,m,n)->new Reply(openai("{}")),"openai",true)) {
            check(post(f,f.ra,false,"openai").status==200,"key retry succeeded"); awaitIdle(f.ra);
            check(f.one.keys.equals(Arrays.asList(KEY1,KEY2)),"two credentials attempted"); near(f.ra,10);
        }
    }
    private static void concurrentStreams() throws Exception {
        ExecutorService clients=Executors.newFixedThreadPool(2);
        try(Fixture f=new Fixture((k,m,n)->new Reply(OA_START).stream().hold(),(k,m,n)->new Reply(OA_START).stream().hold(),"openai",false)) {
            Future<Result> first=clients.submit(()->post(f,"group/g",true,"openai"));
            check(f.one.started.await(10,TimeUnit.SECONDS),"first stream started");
            Future<Result> second=clients.submit(()->post(f,"group/g",true,"openai"));
            check(f.two.started.await(10,TimeUnit.SECONDS),"second stream chose B while A held");
            check(scheduler.inFlight(f.ra)==1 && scheduler.inFlight(f.rb)==1,"both stream leases held");
            f.one.release.countDown(); f.two.release.countDown();
            check(first.get(15,TimeUnit.SECONDS).status==200 && second.get(15,TimeUnit.SECONDS).status==200,"stream responses");
            awaitIdle(f.ra,f.rb); near(f.ra,18); near(f.rb,18);
        } finally { clients.shutdownNow(); check(clients.awaitTermination(10,TimeUnit.SECONDS),"client threads stopped"); }
    }
    private static void nativeAndTruncated() throws Exception {
        for(String protocol:new String[]{"openai","anthropic"}) for(boolean complete:new boolean[]{true,false}) {
            String content=protocol.equals("anthropic")?AN_START+(complete?AN_END:""):OA_START+(complete?OPENAI_END:"");
            try(Fixture f=new Fixture((k,m,n)->new Reply(content).stream(),(k,m,n)->new Reply(content).stream(),protocol,false)) {
                Result result=post(f,f.ra,true,protocol); check(result.status==200,"stream HTTP status "+protocol); awaitIdle(f.ra);
                check(result.body.contains(protocol.equals("anthropic") ? (complete ? "message_stop" : "error") : (complete ? "[DONE]" : "error")), "stream terminal framing " + protocol + " complete=" + complete + " body=" + result.body);
                near(f.ra,complete?18:0);
            }
        }
    }
    private static void convertedAnthropic() throws Exception {
        for (boolean complete : new boolean[]{true, false}) {
            String content = OA_START + (complete ? OPENAI_END : "");
            try (Fixture f = new Fixture((k,m,n)->new Reply(content).stream(),
                    (k,m,n)->new Reply(content).stream(), "openai", false)) {
                JSONObject unsigned = request(f.ra, true, "anthropic"); unsigned.getJSONArray("messages").remove(1);
                Result result = http(f.gateway, "/v1/messages", "POST", unsigned.toString());
                check(result.status == 200, "converted HTTP status");
                check(result.body.contains(complete ? "event: message_stop" : "event: error"), "converted terminal event: " + result.body);
                awaitIdle(f.ra); near(f.ra, complete ? 18 : 0);
            }
        }
        try (Fixture f = new Fixture((k,m,n)->new Reply(OA_START).stream().flood(),
                (k,m,n)->new Reply(OA_START).stream(), "openai", false)) {
            resetClient(f, "anthropic"); awaitIdle(f.ra); near(f.ra, 0);
        }
    }
    private static JSONObject responsesRequest(String model) {
        return new JSONObject().put("model",model).put("stream",true).put("input","hello");
    }
    private static void convertedResponses() throws Exception {
        for(boolean complete:new boolean[]{true,false}) {
            String content=OA_START+(complete?OPENAI_END:"");
            try(Fixture f=new Fixture((k,m,n)->new Reply(content).stream(),
                    (k,m,n)->new Reply(content).stream(),"openai",false)) {
                Result result=http(f.gateway,"/v1/responses","POST",responsesRequest(f.ra).toString());
                check(result.status==200,"responses bridge HTTP status");
                if(complete) check(result.body.contains("response.completed"),"responses bridge completion: "+result.body);
                else check(!result.body.contains("response.completed"),"truncated responses must not complete: "+result.body);
                awaitIdle(f.ra); near(f.ra,complete?18:0);
            }
        }
        try(Fixture f=new Fixture((k,m,n)->new Reply(OA_START).stream().flood(),
                (k,m,n)->new Reply(OA_START).stream(),"openai",false)) {
            resetClient(f,"responses"); awaitIdle(f.ra); near(f.ra,0);
        }
    }
    private static void resetClient(Fixture f, String protocol) throws Exception {
        JSONObject resetRequest=protocol.equals("responses")?responsesRequest(f.ra):request(f.ra,true,protocol);
        if (protocol.equals("anthropic") && "openai".equals(f.cfg.getJSONArray("providers").getJSONObject(0).getString("protocol"))) resetRequest.getJSONArray("messages").remove(1);
        byte[] payload=bytes(resetRequest.toString());
        try(Socket socket=new Socket("127.0.0.1",f.gateway.getListeningPort())) {
            socket.setSoTimeout(10000); OutputStream out=socket.getOutputStream();
            out.write(bytes("POST " + (protocol.equals("responses") ? "/v1/responses" : protocol.equals("anthropic") ? "/v1/messages" : "/v1/chat/completions") + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer fixture-auth\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+payload.length+"\r\n\r\n")); out.write(payload); out.flush();
            InputStream in=socket.getInputStream(); byte[] buf=new byte[2048]; ByteArrayOutputStream got=new ByteArrayOutputStream();
            while(!got.toString("UTF-8").contains(protocol.equals("responses") ? "response.output_text.delta" : protocol.equals("anthropic") ? "message_start" : "chat.completion.chunk")) { int n=in.read(buf); check(n>0,"reset received stream"); got.write(buf,0,n); }
            socket.setSoLinger(true,0);
        }
    }
    private static void cancellationAndNeverSent() throws Exception {
        try(Fixture f=new Fixture((k,m,n)->new Reply(OA_START).stream().flood(),(k,m,n)->new Reply(OA_START).stream(),"openai",false)) {
            resetClient(f, "openai"); awaitIdle(f.ra); near(f.ra,0);
        }
        for(String endpoint:new String[]{"openai","responses"}) try(Fixture f=new Fixture((k,m,n)->new Reply(OA_START).stream().hold(),(k,m,n)->new Reply(OA_START).stream(),"openai",false)) {
            String payload=(endpoint.equals("responses")?responsesRequest(f.ra):request(f.ra,true,"openai")).toString();
            NanoHTTPD.IHTTPSession session=(NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(RecentUsageRoutingHttpTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(proxy,method,args)-> {
                switch(method.getName()) {
                    case "getRemoteIpAddress": return "127.0.0.1";
                    case "getUri": return endpoint.equals("responses")?"/v1/responses":"/v1/chat/completions";
                    case "getMethod": return NanoHTTPD.Method.POST;
                    case "getHeaders": case "getAllHeaders": { Map<String,String> headers=new HashMap<>(); headers.put("content-length",Integer.toString(bytes(payload).length)); headers.put("content-type","application/json"); headers.put("host","127.0.0.1:3425"); headers.put("authorization","Bearer fixture-auth"); return headers; }
                    case "getParameters": return Collections.emptyMap();
                    case "getInputStream": return new ByteArrayInputStream(bytes(payload));
                    default: return null;
                }
            });
            NanoHTTPD.Response response=f.gateway.inner.serve(session);
            check(response.getStatus().getRequestStatus()==200,"never-sent response created");
            check(scheduler.inFlight(f.ra)==1,"never-sent lease held"); response.close(); awaitIdle(f.ra); near(f.ra,0);
        }
    }
    // Transport lifecycle fixture for an already buffered SSE response; no provider API is invoked.
    private static NanoHTTPD.Response bufferedResponse(String ref) throws Exception {
        RecentUsageScheduler.Lease lease=scheduler.reserve(ref);
        check(lease!=null,"buffered fixture lease reserved"); lease.requireDelivery();
        UsageLedger.Row row=new UsageLedger.Row(); row.status=200; row.usageSource="known";
        row.in=11; row.out=7; row.recentUsageLease=lease;
        try {
            Class<?> type=Class.forName("com.gaeul.allin.GatewayServer$RecentUsageStream");
            Constructor<?> constructor=type.getDeclaredConstructor(InputStream.class,UsageLedger.Row.class,RecentUsageScheduler.Lease.class);
            constructor.setAccessible(true);
            InputStream data=(InputStream)constructor.newInstance(new ByteArrayInputStream(bytes(OA_START+OPENAI_END)),row,lease);
            UsageLedger.record(row);
            NanoHTTPD.Response response=new AllInTrackedResponse(
                    NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,"text/event-stream",data),()->{});
            check(scheduler.inFlight(ref)==1,"recorded usage waits for delivery"); near(ref,0);
            return response;
        } catch(Exception | Error e) { lease.cancel(); throw e; }
    }
    private static void bufferedStreamDelivery() throws Exception {
        String ref="buffered"+IDS.incrementAndGet()+"/model";
        NanoHTTPD.Response delivered=bufferedResponse(ref);
        NanoHTTPD server=new NanoHTTPD("127.0.0.1",0) {
            public Response serve(IHTTPSession session) { return delivered; }
        };
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getListeningPort()+"/").openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(15000); c.setRequestProperty("Accept-Encoding","identity");
            try {
                check(c.getResponseCode()==200,"buffered fixture HTTP status");
                ByteArrayOutputStream received=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()) { byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1) received.write(buf,0,n); }
                check(received.toString("UTF-8").contains("[DONE]"),"buffered SSE delivered through HTTP");
            } finally { c.disconnect(); }
            awaitIdle(ref); near(ref,18);
        } finally { server.stop(); delivered.close(); }

        ref="buffered"+IDS.incrementAndGet()+"/model";
        NanoHTTPD.Response neverSent=bufferedResponse(ref);
        neverSent.close(); awaitIdle(ref); near(ref,0);

        ref="buffered"+IDS.incrementAndGet()+"/model";
        NanoHTTPD.Response response=bufferedResponse(ref);
        response.setRequestMethod(NanoHTTPD.Method.GET); response.setGzipEncoding(false);
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        final boolean[] finalChunk={false}, finalFlush={false};
        OutputStream sink=new OutputStream() {
            public void write(int b) { wire.write(b); }
            public void write(byte[] b,int off,int len) {
                wire.write(b,off,len);
                if(len==5 && Arrays.equals(Arrays.copyOfRange(b,off,off+len),bytes("0\r\n\r\n"))) finalChunk[0]=true;
            }
            public void flush() throws IOException {
                if(finalChunk[0]) { finalFlush[0]=true; throw new IOException("injected final flush failure"); }
            }
        };
        Method send=NanoHTTPD.Response.class.getDeclaredMethod("send",OutputStream.class); send.setAccessible(true);
        try { send.invoke(response,sink); }
        catch(InvocationTargetException e) { if(!(e.getCause() instanceof IOException)) throw e; }
        finally { response.close(); }
        check(finalFlush[0] && wire.toString("UTF-8").contains("[DONE]"),"failure injected at final flush after SSE body");
        awaitIdle(ref); near(ref,0);
    }
    private static void repeatedCallback() throws Exception {
        String ref="repeat"+IDS.incrementAndGet()+"/model"; RecentUsageScheduler.Lease lease=scheduler.reserve(ref); lease.requireDelivery();
        UsageLedger.Row row=new UsageLedger.Row(); row.status=200; row.usageSource="unknown";
        Class<?> type=Class.forName("com.gaeul.allin.GatewayServer$RecentUsageStream");
        Constructor<?> constructor=type.getDeclaredConstructor(InputStream.class,UsageLedger.Row.class,RecentUsageScheduler.Lease.class); constructor.setAccessible(true);
        lease.complete(row);
        InputStream body=(InputStream)constructor.newInstance(new ByteArrayInputStream(new byte[0]),row,lease);
        ((AllInTrackedResponse.WriteCompletion)body).onWriteComplete(true);
        ((AllInTrackedResponse.WriteCompletion)body).onWriteComplete(true);
        awaitIdle(ref); near(ref,1);
    }
    private static void configuredTiesAndKeys() throws Exception {
        try(Fixture f=new Fixture((k,m,n)->new Reply(openai("{}")),
                (k,m,n)->new Reply(openai("{}")),"openai",true)) {
            Method resolve=GatewayServer.class.getDeclaredMethod("resolveCandidates",String.class); resolve.setAccessible(true);
            Method order=GatewayServer.class.getDeclaredMethod("orderByStrategy",JSONObject.class,List.class); order.setAccessible(true);
            List<String> refs=Arrays.asList(f.ra,f.rb);
            RecentUsageScheduler.Lease warm=scheduler.reserve(f.rb);
            check(warm!=null && scheduler.order(refs).equals(refs),"independent warmup gives A lower load than B");
            try {
                List<?> candidates=(List<?>)resolve.invoke(f.gateway.inner,"group/g");
                check(candidates.size()==2,"both candidates resolved");
                Field providerId=candidates.get(0).getClass().getDeclaredField("providerId"); providerId.setAccessible(true);
                check(providerId.get(candidates.get(0)).equals(f.a) && providerId.get(candidates.get(1)).equals(f.b),"candidate resolution retains configured order");
                check(order.invoke(f.gateway.inner,group("usage",f.ra,f.rb),refs).equals(refs),"usage strategy preserves configured tie order");
            } finally { warm.close(); }
            RecentUsageScheduler.Lease busy=scheduler.reserve(f.ra);
            check(busy!=null,"reserve A to reverse current load after resolution");
            try {
                check(post(f,"group/g",false,"openai").status==200,"dynamic usage request succeeded");
                awaitIdle(f.rb);
                check(f.one.requests.get()==0 && f.two.requests.get()==1,"dispatch uses current load rather than earlier ordering");
                check(f.two.keys.equals(Arrays.asList(KEY1)),"candidate probing does not rotate or duplicate key selection");
            } finally { busy.close(); }
        }
    }
    private static void capacityCompatibility() throws Exception {
        List<RecentUsageScheduler.Lease> leases=new ArrayList<RecentUsageScheduler.Lease>();
        try(Fixture f=new Fixture((k,m,n)->new Reply(openai("{}")),
                (k,m,n)->new Reply(openai("{}")),"openai",false)) {
            try {
                Field entries=RecentUsageScheduler.class.getDeclaredField("entries"); entries.setAccessible(true);
                List<String> existing;
                synchronized(scheduler) { existing=new ArrayList<String>(((Map<String,?>)entries.get(scheduler)).keySet()); }
                for(String ref:existing) {
                    RecentUsageScheduler.Lease lease=scheduler.reserve(ref);
                    check(lease!=null,"reserve existing scheduler entry"); leases.add(lease);
                }
                int needed=4096-scheduler.size();
                check(needed>=0,"scheduler capacity not exceeded");
                String prefix="capacity"+IDS.incrementAndGet()+"-";
                for(int i=0;i<needed;i++) {
                    RecentUsageScheduler.Lease lease=scheduler.reserve(prefix+i+"/model");
                    check(lease!=null,"reserve unique capacity entry "+i); leases.add(lease);
                }
                check(scheduler.size()==4096 && leases.size()==4096,"all capacity entries active");
                check(post(f,f.ra,false,"openai").status==200,"direct request remains available at capacity");
                f.cfg.getJSONArray("groups").put(0,group("order",f.rb,f.ra));
                check(post(f,"group/g",false,"openai").status==200,"order request remains available at capacity");
                int before=f.one.requests.get()+f.two.requests.get();
                f.cfg.getJSONArray("groups").put(0,group("usage",f.ra,f.rb));
                check(post(f,"group/g",false,"openai").status==503,"usage request rejects untracked refs at capacity");
                check(f.one.requests.get()+f.two.requests.get()==before,"capacity rejection makes zero upstream requests");
            } finally { for(RecentUsageScheduler.Lease lease:leases) lease.close(); }
        }
    }
    public static void main(String[] args) throws Exception {
        directory=Files.createTempDirectory("recent-usage-http-");
        Field field=GatewayServer.class.getDeclaredField("RECENT_USAGE"); field.setAccessible(true); scheduler=(RecentUsageScheduler)field.get(null);
        try {
            UsageLedger.init(directory.toFile());
            apiAndOrdering(); accounting(); concurrentStreams(); nativeAndTruncated(); convertedAnthropic(); convertedResponses(); cancellationAndNeverSent(); bufferedStreamDelivery(); repeatedCallback(); configuredTiesAndKeys(); capacityCompatibility();
            System.out.println("RecentUsageRoutingHttpTest passed");
        } finally {
            UsageLedger.setHooks(null); Method close=UsageLedger.class.getDeclaredMethod("closeWriter"); close.setAccessible(true); close.invoke(null);
            try(Stream<Path> files=Files.walk(directory)) { files.sorted(Comparator.reverseOrder()).forEach(p->{ try { Files.deleteIfExists(p); } catch(IOException e) { throw new UncheckedIOException(e); } }); }
        }
    }
}
