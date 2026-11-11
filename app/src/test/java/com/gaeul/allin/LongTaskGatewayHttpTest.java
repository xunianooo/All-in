package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local synthetic HTTP only. Includes actual >60s paths, plus scaled deadlines. */
public final class LongTaskGatewayHttpTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger sends=new AtomicInteger();volatile long slowMillis,streamMillis;volatile int status=200;
        Upstream() throws Exception {super("127.0.0.1",0);start(SOCKET_READ_TIMEOUT,true);}
        public Response serve(IHTTPSession session){
            try{
                Map<String,String> files=new HashMap<>();session.parseBody(files);JSONObject req=new JSONObject(files.get("postData"));sends.incrementAndGet();
                if(slowMillis>0)Thread.sleep(slowMillis);
                if(status!=200)return newFixedLengthResponse(Response.Status.lookup(status),"application/json","{\"error\":{\"code\":\"rate_limit\"}}");
                if(req.optBoolean("stream")){
                    long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(streamMillis);
                    InputStream in=new InputStream(){byte[] frame=new byte[0];int at;boolean done;
                        public int read()throws IOException {byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
                        public int read(byte[] b,int off,int len)throws IOException {
                            if(at==frame.length){if(done)return -1;
                                boolean finish=System.nanoTime()>=end;
                                if(!finish)try{Thread.sleep(100);}catch(InterruptedException e){throw new IOException(e);}
                                String value=finish?"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n":"data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n";
                                frame=value.getBytes("UTF-8");at=0;done=finish;
                            }
                            int n=Math.min(len,frame.length-at);System.arraycopy(frame,at,b,off,n);at+=n;return n;
                        }
                    };return newChunkedResponse(Response.Status.OK,"text/event-stream",in);
                }
                return newFixedLengthResponse(Response.Status.OK,"application/json","{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
            }catch(Exception e){return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain","fixture failed");}
        }
        public void close(){stop();}
    }
    static JSONObject config(Upstream u,JSONObject timing){
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true))
            .put("server",new JSONObject().put("authKey","fixture-auth"))
            .put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",false))
            .put("providers",new JSONArray().put(new JSONObject().put("id","paced").put("protocol","openai")
                .put("baseUrl","http://127.0.0.1:"+u.getListeningPort()+"/v1")
                .put("models",new JSONArray().put("m1")).put("keys",new JSONArray().put("fixture-one").put("fixture-two"))));
        if(timing!=null)cfg.put("failover",timing);return cfg;
    }
    static void idle(OutboundRoutingHttpTest.Gateway g)throws Exception{
        Field f=GatewayServer.class.getDeclaredField("activeContexts");f.setAccessible(true);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){synchronized(g.inner){Set<?> active=(Set<?>)f.get(g.inner);if(active==null||active.isEmpty())return;}Thread.sleep(10);}
        throw new AssertionError("request context leaked");
    }
    static void success(long slow,long stream,JSONObject timing)throws Exception {
        try(Upstream u=new Upstream()){
            u.slowMillis=slow;u.streamMillis=stream;
            try(OutboundRoutingHttpTest.Gateway g=new OutboundRoutingHttpTest.Gateway(config(u,timing))){
                long start=System.nanoTime();HttpURLConnection c=OutboundRoutingHttpTest.connection(g,"paced/m1",stream>0,120000);
                try{check(c.getResponseCode()==200,"slow task status");String body=new String(c.getInputStream().readAllBytes(),"UTF-8");check(body.contains(stream>0?"[DONE]":"ok"),"task completes honestly");}
                finally{c.disconnect();}
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);check(elapsed>=Math.max(slow,stream),"actual duration");check(u.sends.get()==1,"long task no replay");idle(g);
                System.out.println("long task slow="+slow+" stream="+stream+" elapsed="+elapsed+" sends=1");
            }
        }
    }
    static void scaled()throws Exception {
        JSONObject separated=new JSONObject().put("attemptBudgetMs",1000).put("requestBudgetMs",5000);
        success(1600,0,separated);success(0,1800,separated);
        try(Upstream u=new Upstream()){
            u.slowMillis=1600;u.status=429;
            try(OutboundRoutingHttpTest.Gateway g=new OutboundRoutingHttpTest.Gateway(config(u,separated))){
                check(OutboundRoutingHttpTest.call(g,"paced/m1",false)==503,"rejection after candidate window exhausted");check(u.sends.get()==1,"no late second key");idle(g);
            }
        }
        try(Upstream u=new Upstream()){
            u.slowMillis=1800;
            try(OutboundRoutingHttpTest.Gateway g=new OutboundRoutingHttpTest.Gateway(config(u,new JSONObject().put("attemptBudgetMs",1000)))){
                int code=OutboundRoutingHttpTest.call(g,"paced/m1",false);check(code>=400,"explicit legacy total still stops");check(u.sends.get()==1,"unknown execution after timeout not replayed");idle(g);
            }
        }
        try(Upstream u=new Upstream()){
            u.slowMillis=16500;
            JSONObject cfg=config(u,new JSONObject().put("requestBudgetMs",30000));cfg.put("guard",new JSONObject().put("requestTimeoutSeconds",15));
            try(OutboundRoutingHttpTest.Gateway g=new OutboundRoutingHttpTest.Gateway(cfg)){
                HttpURLConnection c=OutboundRoutingHttpTest.connection(g,"paced/m1",false,25000);
                try {int status=c.getResponseCode();check(status>=400,"read idle independent of long total");InputStream body=c.getErrorStream();if(body!=null)body.readAllBytes();}finally{c.disconnect();}
                check(u.sends.get()==1,"idle timeout unknown not replayed");idle(g);
            }
        }
    }
    public static void main(String[] args)throws Exception{
        NetworkPolicy.configure(null);scaled();
        // Actual old 60-second boundary, simultaneously with identical default network policy.
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> a=pool.submit(()->{try{success(62500,0,null);}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> b=pool.submit(()->{try{success(0,63500,null);}catch(Exception e){throw new RuntimeException(e);}});
            a.get(90,TimeUnit.SECONDS);b.get(90,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        System.out.println("LongTaskGatewayHttpTest PASS");
    }
}
