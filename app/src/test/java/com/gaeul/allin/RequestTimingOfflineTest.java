package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import fi.iki.elonen.NanoHTTPD;

public final class RequestTimingOfflineTest {
    static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
    static JSONObject cfg(GatewayServer s) throws Exception { Field f=GatewayServer.class.getDeclaredField("configJson");f.setAccessible(true);return (JSONObject)f.get(s); }
    static NanoHTTPD.Response update(GatewayServer s,JSONObject patch) {
        return s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/guard","POST",patch.toString(),null));
    }
    static void timing() throws Exception {
        check(RequestTiming.requestMillis(null)==900000 && RequestTiming.requestMillis(new JSONObject())==900000,"default 15 min");
        check(RequestTiming.attemptMillis(null)==60000,"candidate still 60s");
        for(long legacy:new long[]{0,-1,500,1000,60000,240000,900000}) {
            JSONObject f=new JSONObject().put("attemptBudgetMs",legacy);
            check(RequestTiming.requestMillis(f)==Math.max(1000,Math.min(300000,legacy)),"legacy effective limit "+legacy);
            check(RequestTiming.attemptMillis(f)==Math.max(1000,Math.min(300000,legacy)),"legacy attempt "+legacy);
        }
        JSONObject f=new JSONObject().put("requestBudgetMs",900000).put("attemptBudgetMs",1000);
        check(RequestTiming.requestMillis(f)==900000 && RequestTiming.attemptMillis(f)==1000,"independent budgets");
        check(RequestTiming.requestMillis(new JSONObject().put("requestBudgetMs",Long.MAX_VALUE))==1800000,"30 min hard cap");
        check(RequestTiming.requestMillis(new JSONObject().put("attemptBudgetMs","bad"))==60000,"malformed legacy preserved");
        RequestContext c=new RequestContext("fixture",null,900000);
        check(c.remainingMillis()>899000 && c.remainingMillis()<=900000,"context no five minute ceiling");c.close();
        c=new RequestContext("fixture",null,Long.MAX_VALUE);check(c.remainingMillis()>1799000&&c.remainingMillis()<=1800000,"context absolute cap");c.close();
        AtomicLong clock=new AtomicLong(); CommercialKeyPool.Budget b=CommercialKeyPool.Budget.deferred(clock::get,8,1000);
        clock.set(80000);check(b.tryStart(),"affinity admission before first attempt not retry expiry");
        clock.addAndGet(1001);check(!b.tryStart()&&b.attempts()==1,"slow first attempt no further candidate");
        c=new RequestContext("fixture",null,900000);check(!c.isCancelled(),"attempt expiry does not cancel request");c.close();
        b=new CommercialKeyPool.Budget(clock::get,2,1000);clock.addAndGet(1001);check(!b.tryStart(),"existing immediate constructor unchanged");
        ContextGuard.Config guard=ContextGuard.Config.fromJson(null);check(guard.requestTimeoutSeconds==120&&guard.connectTimeoutSeconds==30,"idle/connect unchanged");
    }
    static void saving() throws Exception {
        Path dir=Files.createTempDirectory("request-timing-config");
        JSONObject root=new JSONObject().put("server",new JSONObject().put("authKey","fixture"))
            .put("failover",new JSONObject().put("attemptBudgetMs",150000).put("requestBudgetMs",900000).put("maxAttempts",4).put("maxTargets",7).put("future",new JSONObject().put("x",1)))
            .put("guard",new JSONObject().put("requestTimeoutSeconds",240));
        GatewayServer s=RoutingSetupOfflineTest.server(root);Path file=dir.resolve("config.json");RoutingAuditOfflineTest.file(s,file.toFile());
        try {
            check(update(s,new JSONObject().put("failover",new JSONObject().put("enabled",false).put("targets",new JSONArray().put("alpha/m")))).getStatus().getRequestStatus()==200,"save success");
            JSONObject persisted=new JSONObject(new String(Files.readAllBytes(file),"UTF-8")); JSONObject fo=persisted.getJSONObject("failover");
            check(fo.getLong("requestBudgetMs")==900000&&fo.getLong("attemptBudgetMs")==150000&&fo.getInt("maxAttempts")==4&&fo.getInt("maxTargets")==7&&fo.getJSONObject("future").getInt("x")==1,"partial form preserves all hidden fields");
            check(persisted.getJSONObject("guard").getInt("requestTimeoutSeconds")==240,"guard preserved");
            NanoHTTPD.Response get=s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/guard","GET","",null));get.getData().readAllBytes();
            check(!cfg(s).getJSONObject("failover").has("resting"),"GET never decorates live config");
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a=pool.submit(()->update(s,new JSONObject().put("guard",new JSONObject().put("requestTimeoutSeconds",300))).getStatus().getRequestStatus());
                Future<Integer> b=pool.submit(()->update(s,new JSONObject().put("failover",new JSONObject().put("requestBudgetMs",1200000))).getStatus().getRequestStatus());
                check(a.get()==200&&b.get()==200,"parallel saves");
            } finally {pool.shutdownNow();}
            check(cfg(s).getJSONObject("guard").getInt("requestTimeoutSeconds")==300&&cfg(s).getJSONObject("failover").getLong("requestBudgetMs")==1200000,"parallel patches survive");
            String before=cfg(s).toString();byte[] disk=Files.readAllBytes(file);
            check(update(s,new JSONObject().put("failover",new JSONObject().put("requestBudgetMs",0))).getStatus().getRequestStatus()==400,"invalid budget rejected");
            check(cfg(s).toString().equals(before)&&Arrays.equals(Files.readAllBytes(file),disk),"invalid budget no change");
            RoutingAuditOfflineTest.file(s,dir.resolve("missing/config.json").toFile());
            check(update(s,new JSONObject().put("failover",new JSONObject().put("enabled",true))).getStatus().getRequestStatus()==500,"failed disk save not success");
            check(cfg(s).toString().equals(before)&&Arrays.equals(Files.readAllBytes(file),disk),"disk failure live config unchanged");
            check(RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/guard","POST","{}","fixture")==401,"inference key cannot manage config");
        } finally {s.stop();}
    }
    static void cancellation() throws Exception {
        RequestContext c=new RequestContext("fixture",null,60000);AtomicInteger disconnect=new AtomicInteger(),closed=new AtomicInteger();
        HttpURLConnection conn=new HttpURLConnection(new URL("http://127.0.0.1")){public void connect(){}public void disconnect(){disconnect.incrementAndGet();}public boolean usingProxy(){return false;}};
        c.attach(conn);c.onClose(closed::incrementAndGet);c.cancel("fixture");c.close();check(disconnect.get()==1&&closed.get()==1&&!c.mayReplay(),"cancel once and no replay");
        Map<String,String> h=new HashMap<>();h.put("content-length","1");
        check(IngressBody.read(new ByteArrayInputStream(new byte[]{1}),h,"POST",65536,c).status==408,"pre-cancel upload");
        RequestContext during=new RequestContext("fixture",null,60000);
        InputStream input=new ByteArrayInputStream(new byte[]{1}) {public synchronized int read(byte[] b,int off,int len){during.cancel("upload");return super.read(b,off,len);}};
        check(IngressBody.read(input,h,"POST",65536,during).status==408,"cancel during upload not forwarded");
        GatewayServer s=RoutingSetupOfflineTest.server(new JSONObject());Method register=GatewayServer.class.getDeclaredMethod("registerContext",RequestContext.class),finish=GatewayServer.class.getDeclaredMethod("finishContext",RequestContext.class);register.setAccessible(true);finish.setAccessible(true);
        List<RequestContext> active=new ArrayList<>();
        try {
            for(int i=0;i<64;i++){RequestContext r=new RequestContext("fixture",null,900000);active.add(r);check((Boolean)register.invoke(s,r),"bounded 64 admission");}
            RequestContext extra=new RequestContext("fixture",null,900000);check(!(Boolean)register.invoke(s,extra),"65th rejected");extra.close();
            finish.invoke(s,active.remove(0));RequestContext again=new RequestContext("fixture",null,900000);active.add(again);check((Boolean)register.invoke(s,again),"slot reusable");
            s.stop();for(RequestContext r:active)check(r.isCancelled(),"stop cancels long contexts");
        } finally {for(RequestContext r:active)finish.invoke(s,r);s.stop();}
    }
    public static void main(String[] args) throws Exception {timing();saving();cancellation();System.out.println("RequestTimingOfflineTest PASS");}
}
