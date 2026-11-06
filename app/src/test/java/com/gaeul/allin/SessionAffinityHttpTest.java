package com.gaeul.allin;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import fi.iki.elonen.*;

public final class SessionAffinityHttpTest {
    static void check(boolean v,String m) { if(!v) throw new AssertionError(m); }
    static void stream(String events, boolean delivered, boolean expected) throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"openai");
        RequestContext ctx=new RequestContext("/v1/chat/completions",NetworkPolicy.capture(),60000);
        Field f=GatewayServer.class.getDeclaredField("REQUEST_CONTEXT"); f.setAccessible(true); ThreadLocal<RequestContext> tl=(ThreadLocal<RequestContext>)f.get(null); tl.set(ctx);
        Method m=GatewayServer.class.getDeclaredMethod("streamForwardResponse",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,java.net.HttpURLConnection.class,String.class);m.setAccessible(true);
        NanoHTTPD.Response response=(NanoHTTPD.Response)m.invoke(g,new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),null,null,null,"p"); tl.remove();
        InputStream body=response.getData(); while(body.read()!=-1) { }
        ((AllInTrackedResponse.WriteCompletion)body).onWriteComplete(delivered);
        check(ctx.affinitySemantic.get()==expected,"chat semantic delivered="+delivered+" input="+events);
        body.close();ctx.close();
    }
    static void nativeAnthropic(boolean delivered, boolean expected) throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"anthropic"); RequestContext ctx=new RequestContext("/v1/messages",NetworkPolicy.capture(),60000);
        Field f=GatewayServer.class.getDeclaredField("REQUEST_CONTEXT");f.setAccessible(true);ThreadLocal<RequestContext> local=(ThreadLocal<RequestContext>)f.get(null);local.set(ctx);
        String events="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"x\",\"stop_reason\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"+
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n"+
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        Method method=GatewayServer.class.getDeclaredMethod("nativeAnthropicResponse",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,java.net.HttpURLConnection.class);method.setAccessible(true);
        NanoHTTPD.Response response=(NanoHTTPD.Response)method.invoke(g,new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),null,null,null);local.remove();
        InputStream body=response.getData();AllInTrackedResponse.WriteCompletion completion=(AllInTrackedResponse.WriteCompletion)body;completion.onWriteStart();while(body.read()!=-1) {} completion.onWriteComplete(delivered);
        check(ctx.affinitySemantic.get()==expected,"native anthropic confirmation");body.close();ctx.close();
    }
    static void responses(String events, boolean expected) throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"responses"); RequestContext ctx=new RequestContext("/v1/responses",NetworkPolicy.capture(),60000);
        Field f=GatewayServer.class.getDeclaredField("REQUEST_CONTEXT");f.setAccessible(true);ThreadLocal<RequestContext> local=(ThreadLocal<RequestContext>)f.get(null);local.set(ctx);
        Method method=GatewayServer.class.getDeclaredMethod("nativeResponsesStream",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,java.net.HttpURLConnection.class);method.setAccessible(true);
        NanoHTTPD.Response response=(NanoHTTPD.Response)method.invoke(g,new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),null,null,null);local.remove();
        InputStream body=response.getData(); while(body.read()!=-1) {} check(ctx.affinitySemantic.get()==expected,"responses terminal");body.close();ctx.close();
    }
    static void delivery(boolean success, boolean semantic, boolean epochChanged, boolean cancelled) throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"openai");
        Field af=GatewayServer.class.getDeclaredField("affinity"); af.setAccessible(true); SessionAffinity a=new SessionAffinity(); af.set(g,a);
        SessionAffinity.Key key=a.key(true,"session","scope","protocol","model"); SessionAffinity.Lease lease=a.acquire(key);
        SessionAffinity.Target target=new SessionAffinity.Target("p","origin","model","cred");
        RequestContext ctx=new RequestContext("/v1/chat/completions",NetworkPolicy.capture(),60000,null,"session","scope",0);
        ctx.affinitySemantic.set(semantic);
        if(epochChanged) { Field epoch=GatewayServer.class.getDeclaredField("affinityEpoch");epoch.setAccessible(true);epoch.setLong(g,1); }
        if(cancelled) ctx.cancel("test");
        Class<?> cls=Class.forName("com.gaeul.allin.GatewayServer$AffinityDelivery");
        Constructor<?> ctor=cls.getDeclaredConstructor(GatewayServer.class,InputStream.class,SessionAffinity.Lease.class,SessionAffinity.Target.class,RequestContext.class);ctor.setAccessible(true);
        InputStream body=(InputStream)ctor.newInstance(g,new ByteArrayInputStream(new byte[]{1}),lease,target,ctx);
        AllInTrackedResponse.WriteCompletion completion=(AllInTrackedResponse.WriteCompletion)body; completion.onWriteStart(); completion.onWriteComplete(success);
        SessionAffinity.Lease after=a.acquire(key); check((after.state==SessionAffinity.State.BOUND)==(success && semantic && !epochChanged && !cancelled),"delivery bind decision"); after.close();ctx.close();
    }
    static void restoreLimit() throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"openai"); java.nio.file.Path file=java.nio.file.Files.createTempFile("allin-config-",".json");
        Field field=GatewayServer.class.getDeclaredField("configFile");field.setAccessible(true);field.set(g,file.toFile());
        java.nio.file.Files.write(file,"{}".getBytes(StandardCharsets.UTF_8)); check(g.backupDurableConfig().length==2,"durable read");
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(file.toFile(),"rw")) { f.setLength(4*1024*1024+1); }
        boolean rejected=false; try {g.backupDurableConfig();} catch(IOException expected) {rejected=true;} check(rejected,"4 MiB bound");
        g.retireForRestore(); Method save=GatewayServer.class.getDeclaredMethod("saveConfig",JSONObject.class);save.setAccessible(true);check(!(Boolean)save.invoke(g,new JSONObject()),"retired save");
        java.nio.file.Files.delete(file);
    }
    static void loopback() throws Exception {
        java.net.ServerSocket server=new java.net.ServerSocket(0); java.util.List<String> auth=java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        Thread worker=new Thread(() -> { try { for(int i=0;i<5;i++) try(java.net.Socket sock=server.accept()) {
            InputStream in=sock.getInputStream(); ByteArrayOutputStream head=new ByteArrayOutputStream(); int state=0,b;
            while((b=in.read())!=-1) { head.write(b); state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:0; if(state==4) break; }
            String h=head.toString("UTF-8"); int len=0; for(String line:h.split("\\r\\n")) { if(line.toLowerCase().startsWith("authorization:")) auth.add(line); if(line.toLowerCase().startsWith("content-length:")) len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim()); }
            while(len-->0) if(in.read()<0) throw new IOException();
            byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            OutputStream out=sock.getOutputStream(); out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body);out.flush();
        } } catch(Throwable e) { throw new RuntimeException(e); } }); worker.start();
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(server.getLocalPort(),"openai");
        JSONObject config=ResponsesGatewayOfflineTest.config(g); config.put("affinity",new JSONObject().put("enabled",true));
        config.getJSONArray("providers").getJSONObject(0).put("keys",new JSONArray().put("key-one").put("key-two"));
        Method send=AllInTrackedResponse.class.getDeclaredMethod("send",OutputStream.class); send.setAccessible(true);
        for(int i=0;i<5;i++) { NanoHTTPD.IHTTPSession request=ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}","secret","/v1/chat/completions");
            request.getHeaders().put("x-session-id",i==2?"second-session":"fixture-session");
            if(i==3) { Field p=GatewayServer.class.getDeclaredField("commercialPool");p.setAccessible(true); CommercialKeyPool pool=(CommercialKeyPool)p.get(g);
                java.util.List<CommercialKeyPool.Credential> keys=CommercialKeyPool.list(new JSONArray().put("key-one").put("key-two"));
                CommercialKeyPool.Credential chosen=auth.get(0).contains("key-one")?keys.get(0):keys.get(1);
                pool.cool(CommercialKeyPool.Scope.KEY,"mock\nhttp://127.0.0.1:"+server.getLocalPort()+"/v1",chosen.credentialId,"m",System.currentTimeMillis()+60000);
            }
            NanoHTTPD.Response response=g.serve(request); check(response.getStatus().getRequestStatus()==200,"gateway HTTP status"); send.invoke(response,new ByteArrayOutputStream()); }
        worker.join(5000);server.close();check(!worker.isAlive() && auth.size()==5 && auth.get(0).equals(auth.get(1)),"same session key reuse "+auth);
        check(!auth.get(0).equals(auth.get(2)),"different session key rotation "+auth);
        check(!auth.get(0).equals(auth.get(3)) && auth.get(3).equals(auth.get(4)),"cooldown fallback and rebind "+auth);
    }
    static final class LocalUpstream implements AutoCloseable {
        final java.net.ServerSocket socket; final Thread thread; final java.util.concurrent.BlockingQueue<String> seen=new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1); volatile boolean block; volatile Throwable error;
        LocalUpstream() throws Exception { socket=new java.net.ServerSocket(0); thread=new Thread(() -> { while(!socket.isClosed()) try {
            java.net.Socket client=socket.accept(); new Thread(() -> { try(java.net.Socket c=client) {
                InputStream in=c.getInputStream(); ByteArrayOutputStream head=new ByteArrayOutputStream(); int state=0,b;
                while((b=in.read())!=-1) {head.write(b);state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:0;if(state==4)break;}
                String auth="";int len=0; for(String line:head.toString("UTF-8").split("\\r\\n")) { if(line.toLowerCase().startsWith("authorization:"))auth=line; if(line.toLowerCase().startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim()); }
                while(len-->0 && in.read()!=-1) {} seen.add(auth); if(block) gate.await();
                byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":1000}}".getBytes(StandardCharsets.UTF_8);
                OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            } catch(Exception e) { if(!socket.isClosed() && !(e instanceof java.net.SocketException)) error=e; } }).start();
        } catch(java.net.SocketException e) {break;} catch(Exception e) {error=e;break;} }); thread.setDaemon(true);thread.start(); }
        String next() throws Exception { String x=seen.poll(5,java.util.concurrent.TimeUnit.SECONDS);check(x!=null,"upstream timeout");return x; }
        public void close() throws Exception { gate.countDown();socket.close();thread.join(2000); if(error!=null)throw new AssertionError(error); }
    }
    static NanoHTTPD.IHTTPSession request(String model,String session) throws Exception {
        NanoHTTPD.IHTTPSession s=ResponsesGatewayOfflineTest.session(new JSONObject().put("model",model).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi"))).toString(),"secret","/v1/chat/completions");s.getHeaders().put("x-session-id",session);return s;
    }
    static void send(NanoHTTPD.Response r,OutputStream out) throws Exception { Method m=AllInTrackedResponse.class.getDeclaredMethod("send",OutputStream.class);m.setAccessible(true);m.invoke(r,out); }
    static void groups() throws Exception {
        try(LocalUpstream a=new LocalUpstream(); LocalUpstream b=new LocalUpstream()) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(a.socket.getLocalPort(),"openai");JSONObject cfg=ResponsesGatewayOfflineTest.config(g);
            JSONObject first=cfg.getJSONArray("providers").getJSONObject(0); first.put("id","route-a-test").put("keys",new JSONArray().put("a"));
            JSONObject second=new JSONObject(first.toString()).put("id","route-b-test").put("baseUrl","http://127.0.0.1:"+b.socket.getLocalPort()+"/v1").put("keys",new JSONArray().put("b"));
            cfg.getJSONArray("providers").put(second);cfg.put("affinity",new JSONObject().put("enabled",true)).put("routing",new JSONObject().put("groupsEnabled",true));
            JSONObject group=new JSONObject().put("id","case").put("strategy","usage").put("targets",new JSONArray().put("route-a-test/m").put("route-b-test/m"));cfg.put("groups",new JSONArray().put(group));
            NanoHTTPD.Response r=g.serve(request("group/case","s1"));send(r,new ByteArrayOutputStream());String firstAuth=a.next();
            r=g.serve(request("group/case","s1"));send(r,new ByteArrayOutputStream());check(firstAuth.equals(a.next()),"usage same-session affinity");
            r=g.serve(request("group/case","s2"));send(r,new ByteArrayOutputStream());check(b.next().contains("Bearer b"),"usage different session must disperse");
            group.put("strategy","manual").put("pick","route-b-test/m");
            r=g.serve(request("group/case","s1"));send(r,new ByteArrayOutputStream());check(b.next().contains("Bearer b"),"manual must override prior affinity");
            group.put("strategy","usage");first.put("enabled",false);
            r=g.serve(request("group/case","s1"));send(r,new ByteArrayOutputStream());check(b.next().contains("Bearer b"),"disabled preferred route must fall back");
        }
    }
    static void failedFlushAndEpoch() throws Exception {
        try(LocalUpstream up=new LocalUpstream()) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(up.socket.getLocalPort(),"openai");JSONObject cfg=ResponsesGatewayOfflineTest.config(g);cfg.put("affinity",new JSONObject().put("enabled",true));cfg.getJSONArray("providers").getJSONObject(0).put("keys",new JSONArray().put("one").put("two"));
            NanoHTTPD.Response response=g.serve(request("mock/m","flush"));String first=up.next();
            class FlushFailure extends ByteArrayOutputStream {boolean failed; @Override public void flush() throws IOException {failed=true;throw new IOException("synthetic final flush failure");}}
            FlushFailure broken=new FlushFailure();send(response,broken);check(broken.failed,"flush executed");
            response=g.serve(request("mock/m","flush"));send(response,new ByteArrayOutputStream());String after=up.next();check(!first.equals(after),"failed flush must not bind");
            response=g.serve(request("mock/m","epoch"));String before=up.next(); Method invalidate=GatewayServer.class.getDeclaredMethod("configChangedAffinity");invalidate.setAccessible(true);invalidate.invoke(g);send(response,new ByteArrayOutputStream());
            response=g.serve(request("mock/m","epoch"));send(response,new ByteArrayOutputStream());String later=up.next();check(!before.equals(later),"old delivery after epoch must not bind");
        }
    }
    static void cancelFirst() throws Exception {
        try(LocalUpstream up=new LocalUpstream()) {
            up.block=true;GatewayServer g=ResponsesGatewayOfflineTest.gateway(up.socket.getLocalPort(),"openai");JSONObject cfg=ResponsesGatewayOfflineTest.config(g);cfg.put("affinity",new JSONObject().put("enabled",true));cfg.getJSONArray("providers").getJSONObject(0).put("keys",new JSONArray().put("one").put("two"));
            java.util.concurrent.ExecutorService exec=java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<NanoHTTPD.Response> owner=exec.submit(() -> g.serve(request("mock/m","concurrent")));String first=up.next();
            java.util.concurrent.Future<NanoHTTPD.Response> waiter=exec.submit(() -> g.serve(request("mock/m","concurrent")));
            check(up.seen.poll(250,java.util.concurrent.TimeUnit.MILLISECONDS)==null,"waiter must not fork while owner pending");
            Field field=GatewayServer.class.getDeclaredField("activeContexts");field.setAccessible(true);java.util.Set<RequestContext> contexts=(java.util.Set<RequestContext>)field.get(g);RequestContext ownerContext=null;
            synchronized(g) { for(RequestContext c:contexts) if(ownerContext==null || c.deadlineNanos<ownerContext.deadlineNanos) ownerContext=c; }check(ownerContext!=null,"owner context");ownerContext.cancel("fixture cancellation");
            String second=up.next();check(!first.equals(second),"cancelled owner releases reservation");up.gate.countDown();
            NanoHTTPD.Response response=waiter.get(5,java.util.concurrent.TimeUnit.SECONDS);send(response,new ByteArrayOutputStream()); NanoHTTPD.Response old=owner.get(5,java.util.concurrent.TimeUnit.SECONDS);old.close();
            response=g.serve(request("mock/m","concurrent"));send(response,new ByteArrayOutputStream());check(second.equals(up.next()),"waiter successful delivery binds");exec.shutdownNow();
        }
    }
    public static void main(String[] args) throws Exception {
        UsageLedger.init(java.nio.file.Files.createTempDirectory("affinity-http-ledger-").toFile());
        String finish="data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
        stream(finish+"data: [DONE]\n\n",true,true);
        stream(finish+"data: [DONE]\n\n",false,false);
        stream(finish,true,false);
        stream("data: [DONE]\n\n",true,false);
        stream("data: {\"error\":{}}\n\n"+finish+"data: [DONE]\n\n",true,false);
        check(RequestDiagnostics.Snapshot.parse(new JSONObject().put("schemaVersion",1).put("rulesVersion","made-up")).json().isNull("rulesVersion"),"reject untrusted version");
        check("1.0.0".equals(RequestDiagnostics.Snapshot.parse(new JSONObject().put("schemaVersion",1).put("rulesVersion","1.0.0")).json().getString("rulesVersion")),"version roundtrip");
        check(RequestDiagnostics.Snapshot.parse(new JSONObject().put("schemaVersion",1)).json().isNull("rulesVersion"),"legacy null");
        nativeAnthropic(true,true); nativeAnthropic(false,false);
        responses("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n",true);
        responses("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"failed\"}}\n\n",false);
        responses("data: [DONE]\n\n",false);
        delivery(true,true,false,false); delivery(false,true,false,false); delivery(true,false,false,false);
        delivery(true,true,true,false); delivery(true,true,false,true);
        restoreLimit();
        loopback(); groups(); failedFlushAndEpoch(); cancelFirst();
        System.out.println("SessionAffinityHttpTest PASS");
    }
}
