package com.gaeul.allin;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.gaeul.allin.json.*;
import fi.iki.elonen.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

public final class ProviderQuirksHttpTest {
    static class Conn extends HttpURLConnection {
        final Map<String,String> h=new HashMap<>();
        Conn() throws Exception { super(new URL("https://copilot.tencent.com/v2/chat/completions")); }
        public void setRequestProperty(String k,String v) { h.put(k,v); }
        public void connect() {} public void disconnect() {} public boolean usingProxy() {return false;}
    }
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    static void heldWorker(ProviderQuirks.Snapshot q) throws Exception {
        Path dir=Files.createTempDirectory("quirks-held-ledger-");UsageLedger.init(dir.toFile());
        ServerSocket server=new ServerSocket(0); java.util.concurrent.CountDownLatch received=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker=new Thread(() -> { try(Socket socket=server.accept()) {
            InputStream in=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int state=0,c;
            while((c=in.read())!=-1) {header.write(c);state=(state==0&&c==13)?1:(state==1&&c==10)?2:(state==2&&c==13)?3:(state==3&&c==10)?4:0;if(state==4)break;}
            int len=0;for(String line:header.toString("UTF-8").split("\\r\\n")) if(line.toLowerCase().startsWith("content-length:")) len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            while(len-->0 && in.read()!=-1) {} received.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("timed out");
            String event="data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] body=event.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        } catch(Throwable e) {failure.set(e);} }); worker.start();
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(server.getLocalPort(),"openai");ResponsesGatewayOfflineTest.config(g).put("resilience",new JSONObject().put("heartbeat",true));
        Field f=GatewayServer.class.getDeclaredField("quirksSnapshot");f.setAccessible(true);f.set(g,q);
        NanoHTTPD.IHTTPSession request=ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}","secret","/v1/chat/completions");
        NanoHTTPD.Response response=g.serve(request); check(received.await(5,java.util.concurrent.TimeUnit.SECONDS));
        // Change published snapshot after admission while held worker is blocked upstream.
        f.set(g,null); release.countDown();ByteArrayOutputStream wire=new ByteArrayOutputStream();Method send=AllInTrackedResponse.class.getDeclaredMethod("send",OutputStream.class);send.setAccessible(true);send.invoke(response,wire);
        worker.join(5000);server.close();g.stop();if(failure.get()!=null)throw new AssertionError(failure.get());
        check(new String(wire.toByteArray(),StandardCharsets.UTF_8).contains("[DONE]"));
        boolean found=false;try(java.util.stream.Stream<Path> paths=Files.list(dir.resolve("usage"))) { for(Path path:(Iterable<Path>)paths::iterator) for(String line:Files.readAllLines(path)) {JSONObject row=new JSONObject(line);JSONObject diag=row.optJSONObject("diagnostics");if(diag!=null && "1.0.2".equals(diag.optString("rulesVersion")))found=true;} }
        check(found);
    }
    public static void main(String[] args) throws Exception {
        ProviderQuirks.Snapshot q=ProviderQuirks.parse(Files.readAllBytes(Paths.get(java.nio.file.Files.exists(java.nio.file.Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json")));
        Map<String,String> matched=q.headers("workbuddy","openai","https://copilot.tencent.com/v2/chat/completions");
        check(!matched.isEmpty());
        check(q.headers("workbuddy","openai","https://evil.example/v2/chat/completions").isEmpty());
        check(q.headers("workbuddy","anthropic","https://copilot.tencent.com/v2/chat/completions").isEmpty());
        Method apply=GatewayServer.class.getDeclaredMethod("applyQuirkHeaders",HttpURLConnection.class,Map.class); apply.setAccessible(true);
        Conn c=new Conn(); c.setRequestProperty("Authorization","Bearer synthetic"); apply.invoke(null,c,matched);
        check(!c.getInstanceFollowRedirects() && "Bearer synthetic".equals(c.h.get("Authorization")) && c.h.containsKey("User-Agent"));
        Conn fallback=new Conn(); fallback.setInstanceFollowRedirects(true); apply.invoke(null,fallback,Collections.emptyMap()); check(fallback.getInstanceFollowRedirects() && fallback.h.isEmpty());
        RequestContext held=new RequestContext("/v1/messages",NetworkPolicy.capture(),60000,q,"s","a",1); check(held.quirks==q && "1.0.2".equals(held.quirks.ruleVersion)); held.close();
        heldWorker(q);
        System.out.println("ProviderQuirksHttpTest PASS");
    }
}
