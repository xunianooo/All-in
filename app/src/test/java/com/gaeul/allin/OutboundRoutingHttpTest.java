package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;

/** Actual loopback timestamps, synthetic keys and production gateway admission. */
public final class OutboundRoutingHttpTest {
    static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final List<Long> sends=Collections.synchronizedList(new ArrayList<>());
        volatile boolean hold,drop; volatile int status=200;
        final CountDownLatch began=new CountDownLatch(1), release=new CountDownLatch(1);
        Upstream() throws Exception { super("127.0.0.1",0); start(SOCKET_READ_TIMEOUT,true); }
        public Response serve(IHTTPSession session) {
            try {
                Map<String,String> files=new HashMap<>(); session.parseBody(files); JSONObject req=new JSONObject(files.get("postData")); sends.add(System.nanoTime()); began.countDown();
                if(drop) return newChunkedResponse(Response.Status.OK,"application/json",new InputStream() { public int read() throws IOException { throw new IOException("synthetic truncated response"); } });
                if(req.optBoolean("stream")) {
                    byte[] start=bytes("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n");
                    byte[] end=bytes("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n");
                    InputStream input=new InputStream() { int at; boolean waited;
                        public int read() throws IOException { if(at<start.length) return start[at++]&255;
                            if(!waited) { waited=true; if(hold) try { if(!release.await(10,TimeUnit.SECONDS)) throw new IOException("fixture timeout"); } catch(InterruptedException e) { throw new IOException(e); } }
                            int index=at++-start.length; return index<end.length?end[index]&255:-1;
                        }
                        public int read(byte[] b,int off,int len) throws IOException { int first=read(); if(first<0)return -1;b[off]=(byte)first; int n=1; while(n<len && at<start.length) b[off+n++]=(byte)read(); return n; }
                    };
                    return newChunkedResponse(Response.Status.OK,"text/event-stream",input);
                }
                if(hold) release.await(10,TimeUnit.SECONDS);
                if(status!=200) { Response r=newFixedLengthResponse(Response.Status.lookup(status),"application/json","{\"error\":{\"code\":\"rate_limit\"}}");r.addHeader("Retry-After","1");return r; }
                return newFixedLengthResponse(Response.Status.OK,"application/json","{\"id\":\"test\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");
            } catch(Exception e) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.getClass().getSimpleName()); }
        }
        public void close() { release.countDown();stop(); }
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject config) throws Exception { super("127.0.0.1",0); inner=RoutingSetupOfflineTest.server(config);
            RoutingAuditOfflineTest.file(inner,Files.createTempDirectory("resilience-http").resolve("config.json").toFile());
            Field turns=GatewayServer.class.getDeclaredField("groupTurns"); turns.setAccessible(true); turns.set(inner,new ConcurrentHashMap<String,Integer>());
            start(SOCKET_READ_TIMEOUT,true);
        }
        public Response serve(IHTTPSession s) { return inner.serve(s); }
        public void close() { inner.stop();stop(); }
    }
    static JSONObject config(Upstream upstream,long interval,int rpm,int flight,long wait) {
        JSONObject policy=new JSONObject().put("minIntervalMillis",interval).put("rpm",rpm).put("maxInFlight",flight).put("maxQueue",8).put("maxWaitMillis",wait);
        return new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",false))
                .put("providers",new JSONArray().put(new JSONObject().put("id","paced").put("protocol","openai").put("baseUrl","http://127.0.0.1:"+upstream.getListeningPort()+"/v1").put("keys",new JSONArray().put("fixture-one")).put("models",new JSONArray().put("m1").put("m2"))))
                .put("outbound",new JSONObject().put("paced",new JSONObject().put("credential",policy)));
    }
    static HttpURLConnection connection(Gateway gateway,String model,boolean stream) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+"/v1/chat/completions").openConnection(Proxy.NO_PROXY);
        c.setRequestMethod("POST"); c.setDoOutput(true);c.setConnectTimeout(3000);c.setReadTimeout(12000);c.setRequestProperty("Authorization","Bearer fixture-auth");c.setRequestProperty("Content-Type","application/json");
        byte[] body=bytes(new JSONObject().put("model",model).put("stream",stream).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).toString());
        try(OutputStream out=c.getOutputStream()) { out.write(body); } return c;
    }
    static int call(Gateway gateway,String model,boolean stream) throws Exception { HttpURLConnection c=connection(gateway,model,stream);try { int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();if(in!=null)in.readAllBytes();return status; } finally { c.disconnect(); } }
    static OutboundLimiter limiter(Gateway gateway) throws Exception { Field f=GatewayServer.class.getDeclaredField("outboundLimiter");f.setAccessible(true);return (OutboundLimiter)f.get(gateway.inner); }
    static void waitQueued(Gateway gateway) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end) { OutboundLimiter l=limiter(gateway);if(l!=null&&l.queued()>0)return;Thread.sleep(5); } throw new AssertionError("not queued"); }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,250,0,0,2000))) {
            check(call(g,"paced/m1",false)==200 && call(g,"paced/m2",false)==200,"both models succeed");
            long gap=TimeUnit.NANOSECONDS.toMillis(u.sends.get(1)-u.sends.get(0));check(gap>=220,"real cross-model pacing "+gap);System.out.println("timeline gapMillis="+gap);
        }
        try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,1,0,100))) {
            check(call(g,"paced/m1",false)==200,"first RPM");check(call(g,"paced/m2",false)==503,"rolling window bounded wait");check(u.sends.size()==1,"no RPM oversend");
        }
        try(Upstream u=new Upstream();Upstream alternate=new Upstream()) {
            JSONObject cfg=config(u,10000,0,0,1000);JSONObject p=new JSONObject(cfg.getJSONArray("providers").getJSONObject(0).toString());
            p.put("id","ready").put("baseUrl","http://127.0.0.1:"+alternate.getListeningPort()+"/v1");cfg.getJSONArray("providers").put(p);
            cfg.put("routing",new JSONObject().put("groupsEnabled",true)).put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put("paced/m1").put("ready/m1"))));
            try(Gateway g=new Gateway(cfg)) { check(call(g,"paced/m1",false)==200,"preload primary");long start=System.nanoTime();check(call(g,"group/g",false)==200,"immediate alternate");check(alternate.sends.size()==1 && u.sends.size()==1 && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"no queue when alternate ready"); }
        }
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,0,1,4000))) {
                u.hold=true;
                Future<Integer> first=workers.submit(() -> call(g,"paced/m1",true));check(u.began.await(3,TimeUnit.SECONDS),"stream started");
                Future<Integer> second=workers.submit(() -> call(g,"paced/m2",false));waitQueued(g);check(u.sends.size()==1,"inflight slot held through stream");
                u.hold=false;u.release.countDown();check(first.get(5,TimeUnit.SECONDS)==200&&second.get(5,TimeUnit.SECONDS)==200,"slot released after stream");
            }
            try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,0,0,8000))) {
                check(call(g,"paced/m1",false)==200,"preload before reconfigure");Future<Integer> pending=workers.submit(() -> call(g,"paced/m2",false));waitQueued(g);
                JSONObject state=new JSONObject(new String(g.inner.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/outbound","GET","",null)).getData().readAllBytes(),StandardCharsets.UTF_8));
                JSONObject update=new JSONObject().put("settings",new JSONObject()).put("expectedStateToken",state.getString("stateToken"));
                check(g.inner.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/outbound","POST",update.toString(),null)).getStatus().getRequestStatus()==200,"reconfigure");
                check(pending.get(3,TimeUnit.SECONDS)==200 && u.sends.size()==2,"policy relaxation wakes queued request");
            }
            try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,0,1,10000))) {
                u.hold=true;Future<Integer> first=workers.submit(() -> call(g,"paced/m1",false));check(u.began.await(3,TimeUnit.SECONDS),"held start");Future<Integer> second=workers.submit(() -> call(g,"paced/m2",false));waitQueued(g);
                g.inner.stop();u.hold=false;u.release.countDown();try { first.get(5,TimeUnit.SECONDS); } catch(ExecutionException expected) { } try { second.get(5,TimeUnit.SECONDS); } catch(ExecutionException expected) { }
                check(u.sends.size()==1 && limiter(g).queued()==0,"stop cancels queue without sending");
            }
        } finally { workers.shutdownNow();check(workers.awaitTermination(5,TimeUnit.SECONDS),"worker shutdown"); }
        System.out.println("OutboundRoutingHttpTest passed");
    }
}
