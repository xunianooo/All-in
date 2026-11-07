package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual model-probe endpoint using only loopback fixtures. */
public final class ZCodeModelProbeLayersHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final String JWT="fixture-jwt", DEVICE="fixture-device", MODEL="zcode/glm-5.3-flash";
    static class Fixture extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        volatile String body="{\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}";
        volatile int status=200;
        Fixture() throws IOException { super("127.0.0.1",0); start(SOCKET_READ_TIMEOUT,true); }
        public Response serve(IHTTPSession s) {
            // Drain each POST before replying so keep-alive never treats the old
            // body as another request (Windows and Unix buffer it differently).
            try { if(s.getMethod()==Method.POST) s.parseBody(new java.util.HashMap<String,String>()); }
            catch(Exception e) { throw new IllegalStateException("fixture request body unreadable",e); }
            calls.incrementAndGet();
            Response r=newFixedLengthResponse(Response.Status.lookup(status),"application/json",body);
            r.addHeader("Connection","close"); return r;
        }
        public void close() { stop(); }
    }
    static final class BrokenResponse implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger calls = new AtomicInteger();
        final Thread worker;
        volatile Throwable failure;
        BrokenResponse() throws IOException {
            server.setSoTimeout(10000);
            worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    InputStream in = client.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int c, state=0;
                    while ((c=in.read())!=-1) {
                        header.write(c);
                        state = (state==0 && c=='\r') ? 1 : (state==1 && c=='\n') ? 2 : (state==2 && c=='\r') ? 3 : (state==3 && c=='\n') ? 4 : 0;
                        if (state==4) break;
                    }
                    String text=header.toString("ISO-8859-1");
                    if (!text.startsWith("POST ")) throw new IOException("expected POST");
                    int length=-1;
                    for(String line:text.split("\r\n")) if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    if(length<0) throw new IOException("missing Content-Length");
                    for(int i=0;i<length;i++) if(in.read()<0) throw new EOFException("request body truncated");
                    calls.incrementAndGet();
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{\"content\":".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush(); client.setSoLinger(true,0);
                } catch(Throwable t) { failure=t; }
            }, "broken-gift-fixture");
            worker.start();
        }
        public void close() throws Exception { worker.join(11000); server.close(); if(worker.isAlive()) throw new AssertionError("fixture hung"); if(failure!=null) throw new AssertionError(failure); }
    }
    static JSONObject probe(int port) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/test-model").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(5000); c.setReadTimeout(10000);
        try(OutputStream o=c.getOutputStream()){o.write(new JSONObject().put("model",MODEL).toString().getBytes(StandardCharsets.UTF_8));}
        ByteArrayOutputStream b=new ByteArrayOutputStream(); try(InputStream in=c.getInputStream()){byte[] buf=new byte[1024]; int n; while((n=in.read(buf))!=-1)b.write(buf,0,n);} finally {c.disconnect();}
        JSONObject r=new JSONObject(b.toString("UTF-8")); String text=r.toString();
        check(!text.contains(JWT)&&!text.contains(DEVICE)&&!text.contains("secret-body"),"credential leak: "+text); return r;
    }
    static JSONObject summary(String model) {
        return new JSONObject().put("ok",true).put("claimable",new JSONArray()).put("buckets",new JSONArray().put(new JSONObject().put("remaining",2).put("capabilities",new JSONArray().put("model:"+model))));
    }
    static void setSummary(final JSONObject data) {
        ZCodeGiftControl.setTestHooks(null,new ZCodeGiftControl.Transport(){
            public JSONObject summary(String j,String d){return data;}
            public JSONObject claim(String j,String d,String p){throw new AssertionError("claim");}
        });
    }
    public static void main(String[] args) throws Exception {
        try(Fixture gift=new Fixture(); Fixture plan=new Fixture()) {
            ZCodeGift.setFixtureBase("http://127.0.0.1:"+gift.getListeningPort());
            JSONObject cfg=ZCodeGiftUsageHttpTest.config(plan.getListeningPort());
            String before=cfg.toString();
            ZCodeGiftUsageHttpTest.Gateway gateway=new ZCodeGiftUsageHttpTest.Gateway(cfg,null);
            gateway.gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:"+plan.getListeningPort()+new URL(url).getFile()));
            gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
            try {
                setSummary(summary("other")); JSONObject r=probe(gateway.getListeningPort());
                check("plan".equals(r.optString("billing")) && gift.calls.get()==0 && plan.calls.get()==1,"not covered uses plan: "+r);
                setSummary(new JSONObject().put("ok",false).put("status",401).put("businessCode",123).put("error",JWT+DEVICE+"secret-body"));
                r=probe(gateway.getListeningPort()); check(!r.optBoolean("ok")&&r.optInt("statusCode")==401&&r.optInt("businessCode")==123&&plan.calls.get()==1,"summary error: "+r);
                setSummary(new JSONObject().put("ok",true)); r=probe(gateway.getListeningPort()); check(!r.optBoolean("ok")&&plan.calls.get()==1,"incomplete summary");
                setSummary(summary("glm-5.3-flash"));
                gift.body="bad json "+JWT+DEVICE+"secret-body"; r=probe(gateway.getListeningPort()); check(!r.optBoolean("ok")&&r.optBoolean("executionUnknown")&&"schema".equals(r.optString("errorKind")),"bad json: "+r);
                gift.body="{\"code\":123,\"error\":\"secret-body\",\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}"; r=probe(gateway.getListeningPort()); check(!r.optBoolean("ok"),"business error: "+r);
                gift.body="{\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}"; r=probe(gateway.getListeningPort()); check(r.optBoolean("ok")&&"gift".equals(r.optString("billing")),"gift success: "+r);
                check(gift.calls.get()==3 && plan.calls.get()==1,"no replay: gift="+gift.calls.get()+", plan="+plan.calls.get());
                for (String bad : new String[]{"{\"success\":false,\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}",
                        "{\"data\":{\"error\":\"failed\"},\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}",
                        "{\"content\":[{\"type\":\"text\",\"text\":123}]}"}) {
                    int calls = gift.calls.get(); gift.body = bad; r = probe(gateway.getListeningPort());
                    check(!r.optBoolean("ok") && gift.calls.get() == calls + 1 && plan.calls.get() == 1, "invalid gift cannot pass or replay: " + r);
                }
                int giftCalls = gift.calls.get();
                try (BrokenResponse broken=new BrokenResponse()) {
                    ZCodeGift.setFixtureBase("http://127.0.0.1:"+broken.server.getLocalPort());
                    r=probe(gateway.getListeningPort());
                    check(broken.calls.get()==1, "fixture received exactly one full request");
                    check(!r.optBoolean("ok") && r.optInt("statusCode")==502 && r.optBoolean("executionUnknown")
                            && !r.optString("transportKind").isEmpty() && !"none".equals(r.optString("transportKind")), "post-send read failure: "+r);
                    check(r.optString("advice").contains("计费"), "unknown execution advice: "+r);
                    check(plan.calls.get()==1, "read failure must not fall back to plan");
                }
                int closedPort; try (ServerSocket unused=new ServerSocket(0)) { closedPort=unused.getLocalPort(); }
                ZCodeGift.setFixtureBase("http://127.0.0.1:"+closedPort);
                r=probe(gateway.getListeningPort());
                check(!r.optBoolean("ok") && r.optInt("statusCode")==502 && r.optBoolean("executionUnknown")
                        && "refused".equals(r.optString("transportKind")), "sent connection failure: "+r);
                check(gift.calls.get()==giftCalls && plan.calls.get()==1,"failed gift never replays");
                check(before.equals(cfg.toString()),"config changed");
                System.out.println("PASS ZCodeModelProbeLayersHttpTest");
            } finally { gateway.stop(); gateway.gateway.stop(); }
        } finally { ZCodeGift.setFixtureBase(null); ZCodeGift.resetCooldownForTests(); ZCodeGiftControl.setTestHooks(null,null); }
    }
}
