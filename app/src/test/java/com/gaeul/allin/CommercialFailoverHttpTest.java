package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Real HTTP on loopback only. Credentials and response payloads are synthetic. */
public final class CommercialFailoverHttpTest {
    private static final String K1 = "synthetic-credential-one", K2 = "synthetic-credential-two";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static void check(boolean b, String label) { if (!b) throw new AssertionError(label); }
    private static final class Reply {
        final int status; final String type; final boolean drop, stream; final long delay;
        Reply(int s, String t) { this(s,t,false,false,0); }
        Reply(int s, String t, boolean d, boolean st, long ms) { status=s; type=t; drop=d; stream=st; delay=ms; }
    }
    private interface Script { Reply reply(int index, String key, String model); }
    private static final class Upstream implements AutoCloseable {
        final ServerSocket socket; final Thread thread; final Script script;
        final List<String> keys = Collections.synchronizedList(new ArrayList<>());
        final List<String> models = Collections.synchronizedList(new ArrayList<>());
        volatile Throwable failure; volatile Socket active;
        volatile boolean cancelStream;
        final CountDownLatch streamDisconnected = new CountDownLatch(1);
        Upstream(Script script) throws Exception {
            this.script=script; socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1")); socket.setSoTimeout(500);
            thread=new Thread(this::run,"commercial-loopback"); thread.setDaemon(true); thread.start();
        }
        void run() {
            while (!socket.isClosed()) try (Socket client=socket.accept()) {
                active=client; client.setSoTimeout(10000); InputStream in=client.getInputStream();
                ByteArrayOutputStream h=new ByteArrayOutputStream(); int state=0,c;
                while (state<4 && (c=in.read())!=-1) { h.write(c); state=state==0&&c=='\r'?1:state==1&&c=='\n'?2:state==2&&c=='\r'?3:state==3&&c=='\n'?4:0; if(h.size()>65536) throw new IOException(); }
                String headers=h.toString("UTF-8"); int length=0; String key="";
                for(String line:headers.split("\r\n")) { int colon=line.indexOf(':'); if(colon<0) continue; String n=line.substring(0,colon).trim().toLowerCase(Locale.ROOT), v=line.substring(colon+1).trim();
                    if(n.equals("content-length")) length=Integer.parseInt(v);
                    if(n.equals("authorization")) key=v.startsWith("Bearer ")?v.substring(7):v;
                    if(n.equals("x-api-key")) key=v;
                }
                byte[] b=new byte[length]; int offset=0; while(offset<length) { int n=in.read(b,offset,length-offset); if(n<0) throw new EOFException(); offset+=n; }
                String model=new JSONObject(new String(b,StandardCharsets.UTF_8)).optString("model");
                keys.add(key); models.add(model); Reply r=script.reply(keys.size(),key,model);
                if(r.delay>0) Thread.sleep(r.delay); if(r.drop) continue;
                OutputStream out=client.getOutputStream();
                if(r.stream) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    if (cancelStream) {
                        char[] content=new char[11000]; Arrays.fill(content,'x');
                        byte[] chunk=("data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"+JSONObject.quote(new String(content))+"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
                        try {
                            // Keep the stream unfinished until the client resets its connection.
                            // The bound prevents a broken cancellation implementation hanging the test.
                            for (int i=0;i<1000;i++) { out.write(chunk); out.flush(); Thread.sleep(10); }
                        } catch (IOException disconnected) { streamDisconnected.countDown(); }
                        continue;
                    }
                    out.write(("data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); continue;
                }
                String body;
                if(r.status>=400) body=new JSONObject().put("error",new JSONObject().put("type",r.type).put("message",r.type))
                        .put("usage",new JSONObject().put("prompt_tokens",3).put("completion_tokens",1).put("prompt_tokens_details",new JSONObject().put("cached_tokens",2)).put("completion_tokens_details",new JSONObject().put("reasoning_tokens",1))).toString();
                else if(headers.startsWith("POST /v1/messages ")) body="{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":11,\"output_tokens\":7,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}}";
                else if(headers.startsWith("POST /v1/responses ")) body="{\"id\":\"r\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";
                else body="{\"id\":\"c\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":3},\"completion_tokens_details\":{\"reasoning_tokens\":4}}}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 "+r.status+" "+(r.status==200?"OK":"Error")+"\r\nContent-Type: application/json\r\nRetry-After: 120\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(bytes); out.flush();
            } catch(SocketTimeoutException ignored) { } catch(Throwable e) { if(!socket.isClosed()) failure=e; } finally { active=null; }
        }
        public void close() throws Exception { socket.close(); Socket a=active; if(a!=null) a.close(); thread.join(10000); check(!thread.isAlive(),"upstream stopped"); check(failure==null,"upstream execution"); }
    }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway;
        Gateway(JSONObject cfg) throws Exception { super("127.0.0.1",0); gateway=RoutingSetupOfflineTest.server(cfg); start(NanoHTTPD.SOCKET_READ_TIMEOUT,true); }
        public Response serve(IHTTPSession s) { return gateway.serve(s); }
        protected boolean useGzipWhenAccepted(Response r) { return gateway.useGzipWhenAccepted(r); }
        public void close() { stop(); }
    }
    private static JSONObject config(Upstream u, String protocol, JSONArray keys, JSONObject failover, boolean group) {
        String id="local"+IDS.incrementAndGet();
        JSONObject provider=new JSONObject().put("id",id).put("protocol",protocol).put("baseUrl","http://127.0.0.1:"+u.socket.getLocalPort()+"/v1").put("keys",keys).put("models",new JSONArray().put("m1").put("m2"));
        return new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth")).put("privacy",new JSONObject().put("enabled",false))
                .put("routing",new JSONObject().put("groupsEnabled",true)).put("failover",failover)
                .put("providers",new JSONArray().put(provider)).put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put(id+"/m1").put(id+"/m2"))));
    }
    private static JSONArray keys() { return new JSONArray().put(K1).put(K2); }
    private static JSONObject options() { return new JSONObject().put("enabled",true).put("maxTargets",1).put("maxAttempts",8); }
    private static String model(JSONObject cfg, boolean group) { return group?"group/g":cfg.getJSONArray("providers").getJSONObject(0).getString("id")+"/m1"; }
    private static final class Result { int status; String retry; }
    private static Result post(Gateway g, JSONObject cfg, String protocol, boolean group, boolean stream) throws Exception {
        String path=protocol.equals("anthropic")?"/v1/messages":protocol.equals("responses")?"/v1/responses":"/v1/chat/completions";
        JSONObject b=new JSONObject().put("model",model(cfg,group)).put("stream",stream);
        if(protocol.equals("responses")) b.put("input","hello");
        else { b.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))); if(protocol.equals("anthropic")) b.put("max_tokens",32); }
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Authorization","Bearer fixture-auth"); c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("anthropic-version","2023-06-01");
        try(OutputStream out=c.getOutputStream()) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        Result r=new Result(); r.status=c.getResponseCode(); r.retry=c.getHeaderField("Retry-After"); InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();
        if(in!=null) try(InputStream input=in) { byte[] buf=new byte[2048]; while(input.read(buf)!=-1) { } } c.disconnect(); return r;
    }
    private static void idle(Gateway g) throws Exception { long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(g.gateway.activeForwardRequests()!=0 && System.nanoTime()<until) Thread.yield(); check(g.gateway.activeForwardRequests()==0,"request released"); }
    private static void attempts(List<UsageLedger.Row> rows, int n, int terminals) {
        int a=0,t=0; String id=null; synchronized(rows) { for(UsageLedger.Row r:rows) { if(r.attempt>0) a++; if(r.terminal) t++; if(id==null) id=r.requestId; check(id.equals(r.requestId),"logical request identity"); } }
        check(a==n && t==terminals,"attempt and terminal counts");
    }
    private static void cancel(Gateway g, JSONObject cfg) throws Exception {
        JSONObject body=new JSONObject().put("model",model(cfg,false)).put("stream",true)
                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",g.getListeningPort())) {
            socket.setSoTimeout(10000); OutputStream out=socket.getOutputStream();
            out.write(("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer fixture-auth\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(bytes); out.flush();
            InputStream in=socket.getInputStream(); ByteArrayOutputStream got=new ByteArrayOutputStream(); byte[] buffer=new byte[1024];
            while(!got.toString("UTF-8").contains("chat.completion.chunk")) { int n=in.read(buffer); check(n>0,"cancel initial frame"); got.write(buffer,0,n); }
            socket.setSoLinger(true,0);
        }
    }
    private static void secondProvider(JSONObject cfg, Upstream other) {
        JSONObject p=cfg.getJSONArray("providers").getJSONObject(0);
        String id="other"+IDS.incrementAndGet();
        JSONObject second=new JSONObject().put("id",id).put("protocol","openai").put("baseUrl","http://127.0.0.1:"+other.socket.getLocalPort()+"/v1").put("keys",keys()).put("models",new JSONArray().put("m2"));
        cfg.getJSONArray("providers").put(second);
        cfg.getJSONArray("groups").getJSONObject(0).put("targets",new JSONArray().put(p.getString("id")+"/m1").put(id+"/m2"));
    }
    private static void mainMatrix(List<UsageLedger.Row> rows) throws Exception {
        for(String protocol:new String[]{"openai","anthropic","responses"}) {
            rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(i==1?429:200,"rate_limit_error"))) {
                JSONObject cfg=config(u,protocol,keys(),options(),false); try(Gateway g=new Gateway(cfg)) {
                    int observedStatus=post(g,cfg,protocol,false,false).status;
                    check(observedStatus==200,"protocol failover status protocol="+protocol+" status="+observedStatus+" upstreamAttempts="+u.keys.size()); idle(g);
                    check(u.keys.size()==2 && u.keys.get(0).equals(K1) && u.keys.get(1).equals(K2),"protocol key rotation"); attempts(rows,2,1);
                    check(rows.size()==2,"success produces two rows"); UsageLedger.Row a=rows.get(0), b=rows.get(1);
                    check(a.attempt==1 && !a.terminal && a.status==429 && b.attempt==2 && b.terminal && b.tries==2 && b.status==200,"attempt metadata");
                    check(!a.credentialId.isEmpty() && !a.credentialId.equals(b.credentialId),"credential attribution");
                    check(a.in==3 && a.out==1 && a.cacheRead==2 && a.reasoning==1 && a.usageSource.equals("known"),"failure usage attribution");
                    check(b.in==11 && b.out==7 && b.usageSource.equals("known"),"success usage attribution");
                    if(protocol.equals("openai")) {
                        check(b.cacheRead==3 && b.reasoning==4,"success token details");
                        JSONObject totals=UsageLedger.summary(1,"model").getJSONObject("totals");
                        check(totals.getLong("requests")==1 && totals.getLong("attempts")==2 && totals.getLong("attemptFailed")==1,"ledger logical and attempt totals");
                    }
                    if(protocol.equals("anthropic")) check(b.cacheRead==3 && b.cacheWrite==4 && b.cacheInputTokens==18,"native cache details");
                }
            }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(429,"rate_limit_error"))) {
            JSONArray filtered=new JSONArray().put(" "+K1+" ").put(K1).put("").put(new JSONObject().put("key",K2)).put(new JSONObject().put("key","disabled-one").put("off",true)).put(new JSONObject().put("key","disabled-two").put("enabled",false));
            JSONObject cfg=config(u,"openai",filtered,options(),false); try(Gateway g=new Gateway(cfg)) {
                long start=System.nanoTime(); Result r=post(g,cfg,"openai",false,false); idle(g);
                check(r.status==429 && r.retry!=null && Integer.parseInt(r.retry)>0 && Integer.parseInt(r.retry)<=120,"cooldown retry header");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<5000,"cooldown returns promptly"); check(u.keys.size()==2,"filter and dedup"); attempts(rows,2,1); check(rows.size()==3 && rows.get(2).attempt==0 && rows.get(2).tries==2,"failure terminal marker");
                // Reuse this exact gateway and configuration so the same in-memory pool is exercised.
                rows.clear(); start=System.nanoTime(); r=post(g,cfg,"openai",false,false); idle(g);
                check(r.status==429 && r.retry!=null && Integer.parseInt(r.retry)>0 && Integer.parseInt(r.retry)<=120,"second cooldown retry header");
                check(u.keys.size()==2,"all cooldown avoids network"); check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<5000,"all cooldown no sleep");
                attempts(rows,0,1); check(rows.size()==1 && rows.get(0).attempt==0 && rows.get(0).tries==0 && rows.get(0).status==429,"second cooldown terminal marker");
            }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(429,"rate_limit_error"))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("enabled",false),false); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",false,false).status==429,"disabled status"); idle(g); check(u.keys.size()==1,"disabled one attempt"); attempts(rows,1,1); check(rows.size()==2 && rows.get(1).attempt==0,"disabled terminal marker"); }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(m.equals("m1")?429:200,"model_rate_limit"))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("maxTargets",2),true); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",true,false).status==200,"ordered fallback status"); idle(g); check(u.models.equals(Arrays.asList("m1","m1","m2")),"all keys before next model"); check(!u.keys.get(0).equals(u.keys.get(1)),"distinct keys in first model"); attempts(rows,3,1); check(rows.get(rows.size()-1).tries==3,"ordered tries"); }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(429,"rate_limit_error"))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("maxAttempts",1),false); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",false,false).status==503,"attempt budget status"); idle(g); check(u.keys.size()==1,"attempt budget count"); attempts(rows,1,1); }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(429,"rate_limit_error",false,false,1100))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("attemptBudgetMs",1000),false); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",false,false).status==503,"time budget status"); idle(g); check(u.keys.size()==1,"time budget count"); attempts(rows,1,1); }
        }
        for(int status:new int[]{401,400,502,0}) {
            rows.clear(); final int code=status;
            try(Upstream u=new Upstream((i,k,m)->new Reply(code==0?200:code, code==400?"context_length_exceeded":code==502?"upstream_bad_gateway":"authentication_error",code==0,false,0))) {
                JSONObject cfg=config(u,"openai",keys(),options().put("maxTargets",2),true); try(Gateway g=new Gateway(cfg)) {
                    Result r=post(g,cfg,"openai",true,false); idle(g);
                    check(u.keys.size()==(code==401?2:1),"classification replay count");
                    if(code==400) check(r.status==400,"context status"); if(code==502 || code==0) check(r.status==502,"unknown error status");
                    attempts(rows,code==401?2:1,1);
                }
            }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(m.equals("m1")?502:200,"provider outage"))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("maxTargets",2),true); try(Gateway g=new Gateway(cfg)) { Result r=post(g,cfg,"openai",true,false); idle(g); check(r.status==429 && u.keys.size()==1,"shared outage cools provider including other model"); attempts(rows,1,1); }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(429,"organization_rate_limit"))) {
            JSONObject cfg=config(u,"openai",keys(),options().put("maxTargets",2),true); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",true,false).status==429,"organization status"); idle(g); check(u.keys.size()==1,"organization does not rotate"); attempts(rows,1,1); }
        }
        for(int status:new int[]{429,502}) {
            rows.clear(); final int code=status;
            try(Upstream first=new Upstream((i,k,m)->new Reply(code,code==429?"organization_rate_limit":"provider outage"));
                Upstream second=new Upstream((i,k,m)->new Reply(200,""))) {
                JSONObject cfg=config(first,"openai",keys(),options().put("maxTargets",2),true); secondProvider(cfg,second);
                try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",true,false).status==200,"shared error alternate provider"); idle(g); check(first.keys.size()==1 && second.keys.size()==1,"shared error route fallback without key replay"); attempts(rows,2,1); }
            }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(200,"",false,true,0))) {
            JSONObject cfg=config(u,"openai",keys(),options(),false); try(Gateway g=new Gateway(cfg)) { check(post(g,cfg,"openai",false,true).status==200,"stream handoff status"); idle(g); check(u.keys.size()==1,"stream disconnect no replay"); attempts(rows,1,1); }
        }
        rows.clear(); try(Upstream u=new Upstream((i,k,m)->new Reply(200,"",false,true,0))) {
            u.cancelStream=true;
            JSONObject cfg=config(u,"openai",keys(),options(),false); try(Gateway g=new Gateway(cfg)) {
                cancel(g,cfg);
                check(u.streamDisconnected.await(10,TimeUnit.SECONDS),"upstream released after cancellation");
                idle(g);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(rows.isEmpty() && System.nanoTime()<deadline) Thread.yield();
                check(u.keys.size()==1 && u.keys.get(0).equals(K1),"cancellation does not replay another credential");
                check(rows.size()==1,"cancellation exactly one ledger row");
                UsageLedger.Row row=rows.get(0);
                check(row.terminal && row.attempt==1 && row.tries==1 && row.status==502,"cancellation terminal failure");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("commercial-failover-"); List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<>());
        try { UsageLedger.init(dir.toFile()); UsageLedger.setHooks(rows::add); mainMatrix(rows); System.out.println("CommercialFailoverHttpTest passed"); }
        finally {
            UsageLedger.setHooks(null);
            Method close=UsageLedger.class.getDeclaredMethod("closeWriter"); close.setAccessible(true); close.invoke(null);
            try(Stream<Path> paths=Files.walk(dir)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{ try { Files.deleteIfExists(p); } catch(IOException e) { throw new UncheckedIOException(e); } }); }
        }
    }
}
