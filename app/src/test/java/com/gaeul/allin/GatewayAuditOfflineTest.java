package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public final class GatewayAuditOfflineTest {
    static final class CountingSource extends ByteArrayInputStream {
        int closes;
        CountingSource(String s) { super(s.getBytes(StandardCharsets.UTF_8)); }
        public void close() throws IOException { closes++; super.close(); }
    }
    static final class CountingConnection extends HttpURLConnection {
        int disconnects;
        CountingConnection() throws Exception { super(new URL("http://127.0.0.1/")); }
        public void disconnect() { disconnects++; }
        public boolean usingProxy() { return false; }
        public void connect() {}
    }
    static File ledgerDir;
    static int ledgerCount() throws Exception { return ledgerRows().size(); }
    static java.util.List<String> ledgerRows() throws Exception {
        File dir = new File(ledgerDir,"usage");
        java.util.List<String> rows = new ArrayList<>();
        File[] files=dir.listFiles(); if(files!=null) for(File f:files) rows.addAll(Files.readAllLines(f.toPath(),StandardCharsets.UTF_8));
        return rows;
    }
    static void check(boolean v,String message) { ResponsesGatewayOfflineTest.check(v,message); }
    static InputStream nativeStream(GatewayServer g,CountingSource source,CountingConnection conn) throws Exception { return nativeStream(g,source,conn,null); }
    static InputStream nativeStream(GatewayServer g,CountingSource source,CountingConnection conn,PrivacyMasker.Session mask) throws Exception {
        Field f=GatewayServer.class.getDeclaredField("U_ROW"); f.setAccessible(true); ((ThreadLocal)f.get(null)).set(new UsageLedger.Row());
        Field t=GatewayServer.class.getDeclaredField("U_START"); t.setAccessible(true); ((ThreadLocal)t.get(null)).set(System.currentTimeMillis());
        Method m=GatewayServer.class.getDeclaredMethod("nativeResponsesStream",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class); m.setAccessible(true);
        InputStream out=((NanoHTTPD.Response)m.invoke(g,source,mask,null,conn)).getData();
        ((ThreadLocal)f.get(null)).remove(); ((ThreadLocal)t.get(null)).remove();
        return out;
    }
    static void streamLifecycle() throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"responses");
        String initial="event: response.created\ndata: {\"type\":\"response.created\"}\n\n";
        String done="event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        int before=ledgerCount(); CountingSource src=new CountingSource(initial+done); CountingConnection conn=new CountingConnection();
        InputStream in=nativeStream(g,src,conn); check(in.read()!=-1,"initial byte"); in.close(); in.close();
        check(src.closes==1 && conn.disconnects==1,"cancel must close source and connection exactly once");
        check(ledgerCount()==before+1,"cancel must record once");
        JSONObject cancel=new JSONObject(ledgerRows().get(before)); check(cancel.optInt("st")>=400,"cancel recorded as success");
        before=ledgerCount(); src=new CountingSource(done); conn=new CountingConnection(); in=nativeStream(g,src,conn);
        String response=ResponsesGatewayOfflineTest.read(in); check(response.contains("response.completed"),"terminal response missing");
        check(src.closes==1 && conn.disconnects==1,"terminal must close connection without consumer close");
        check(ledgerCount()==before+1,"terminal must record once"); in.close(); in.close(); check(ledgerCount()==before+1 && src.closes==1 && conn.disconnects==1,"terminal close is idempotent");
        before=ledgerCount(); src=new CountingSource(initial); conn=new CountingConnection(); in=nativeStream(g,src,conn);
        check(ResponsesGatewayOfflineTest.read(in).contains("response.failed"),"EOF without terminal must fail");
        check(ledgerCount()==before+1 && src.closes==1 && conn.disconnects==1,"EOF failure must close and record once"); in.close();
        before=ledgerCount(); src=new CountingSource("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"failed\"}}\n\n"); conn=new CountingConnection(); in=nativeStream(g,src,conn);
        check(ResponsesGatewayOfflineTest.read(in).contains("response.failed"),"invalid terminal status must fail");
        check(ledgerCount()==before+1 && new JSONObject(ledgerRows().get(before)).optInt("st")==502 && src.closes==1 && conn.disconnects==1,"invalid terminal closes and records failure once"); in.close();
        before=ledgerCount(); char[] huge=new char[2*1024*1024+1]; Arrays.fill(huge,'x');
        src=new CountingSource("data: "+new String(huge)); conn=new CountingConnection(); in=nativeStream(g,src,conn);
        check(ResponsesGatewayOfflineTest.read(in).contains("response.failed"),"oversize SSE must fail");
        check(ledgerCount()==before+1 && src.closes==1 && conn.disconnects==1,"oversize must close and record once"); in.close();
    }
    static void pendingLiteral() throws Exception {
        PrivacyMasker.Session mask=new PrivacyMasker.Session(); mask.placeholderToReal.put("{{MASK_PHONE_a}}","real");
        for(String tail:new String[]{"{","{{","{{MASK_PHONE_"}) {
            String text="literal "+tail;
            JSONObject delta=new JSONObject().put("type","response.output_text.delta").put("sequence_number",3).put("item_id","item").put("output_index",2).put("content_index",1).put("delta",text);
            JSONObject done=new JSONObject().put("type","response.output_text.done").put("sequence_number",4).put("item_id","item").put("output_index",2).put("content_index",1).put("text",text);
            JSONObject completed=new JSONObject().put("type","response.completed").put("sequence_number",5).put("response",new JSONObject().put("status","completed").put("output",new JSONArray()));
            String wire="data: "+delta+"\n\ndata: "+done+"\n\ndata: "+completed+"\n\n";
            CountingSource src=new CountingSource(wire); CountingConnection conn=new CountingConnection();
            String result=ResponsesGatewayOfflineTest.read(nativeStream(ResponsesGatewayOfflineTest.gateway(1,"responses"),src,conn,mask));
            check(!result.contains("response.failed") && result.contains("response.completed"),"literal pending suffix failed: "+result);
            StringBuilder actual=new StringBuilder(); long previous=-1; int chunks=0;
            for(String line:result.split("\n")) if(line.startsWith("data: ")) {
                JSONObject event=new JSONObject(line.substring(6)); long number=event.getLong("sequence_number"); check(number>previous,"non-increasing sequence"); previous=number;
                if("response.output_text.delta".equals(event.optString("type"))) { actual.append(event.getString("delta")); chunks++; check("item".equals(event.optString("item_id")) && event.optInt("output_index")==2 && event.optInt("content_index")==1,"flush lost indices"); }
            }
            check(actual.toString().equals(text) && chunks==2,"pending suffix not emitted as delta: "+actual);
        }
    }
    static void terminalFlushAndInterleave() throws Exception {
        PrivacyMasker.Session mask=new PrivacyMasker.Session(); mask.placeholderToReal.put("{{MASK_PHONE_a}}","real");
        JSONObject a=new JSONObject().put("type","response.output_text.delta").put("sequence_number",0).put("item_id","a").put("output_index",0).put("content_index",0).put("delta","A{");
        JSONObject b=new JSONObject().put("type","response.output_text.delta").put("sequence_number",1).put("item_id","b").put("output_index",0).put("content_index",0).put("delta","B{");
        JSONObject end=new JSONObject().put("type","response.completed").put("sequence_number",2).put("response",new JSONObject().put("status","completed").put("output",new JSONArray()));
        String body=ResponsesGatewayOfflineTest.read(nativeStream(ResponsesGatewayOfflineTest.gateway(1,"responses"),new CountingSource("data: "+a+"\n\ndata: "+b+"\n\ndata: "+end+"\n\n"),new CountingConnection(),mask));
        Map<String,String> values=new HashMap<>(); long seq=-1;
        for(String line:body.split("\n")) if(line.startsWith("data: ")) { JSONObject e=new JSONObject(line.substring(6)); check(e.getLong("sequence_number")>seq,"terminal flush ordering"); seq=e.getLong("sequence_number"); if(e.optString("type").endsWith(".delta")) {String id=e.getString("item_id"); values.put(id,values.getOrDefault(id,"")+e.getString("delta"));} }
        check("A{".equals(values.get("a")) && "B{".equals(values.get("b")) && body.contains("response.completed"),"interleaved item tails: "+body);
    }
    static final class LimitInput extends InputStream {
        int remaining=65536, reads; boolean closed;
        public int read() throws IOException { throw new AssertionError("unexpected single read"); }
        public int read(byte[] b,int off,int len) throws IOException {
            reads++; if(remaining==0) throw new IOException("extra blocking read");
            int n=Math.min(len,remaining); Arrays.fill(b,off,off+n,(byte)'x');
            if(remaining==65536) { byte[] key="test-key".getBytes(StandardCharsets.UTF_8); System.arraycopy(key,0,b,off,key.length); }
            remaining-=n; return n;
        }
        public void close() { closed=true; }
    }
    static void boundedErrorRead() throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"responses"); LimitInput input=new LimitInput();
        HttpURLConnection conn=new HttpURLConnection(new URL("http://127.0.0.1/")) {
            public InputStream getErrorStream(){return input;} public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
        };
        Class<?> attemptType=Class.forName("com.gaeul.allin.GatewayServer$Attempt"); Constructor<?> ctor=attemptType.getDeclaredConstructor(); ctor.setAccessible(true); Object attempt=ctor.newInstance();
        Method m=GatewayServer.class.getDeclaredMethod("responsesUpstreamError",attemptType,HttpURLConnection.class,int.class,PrivacyMasker.Session.class,ContextGuard.Result.class,String.class); m.setAccessible(true);
        m.invoke(g,attempt,conn,422,null,null,"test-key");
        Field response=attemptType.getDeclaredField("response"); response.setAccessible(true); NanoHTTPD.Response r=(NanoHTTPD.Response)response.get(attempt);
        String body=ResponsesGatewayOfflineTest.read(r.getData()); check(r.getStatus().getRequestStatus()==422 && !body.contains("test-key") && body.contains("[REDACTED]"),"bounded error status/redaction");
        check(input.closed && input.reads==16,"read past 65536-byte bound");
    }
    static void codexFailureCloses() throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1,"responses");
        CountingSource src=new CountingSource("data: invalid-json\n\n"); CountingConnection conn=new CountingConnection();
        Method m=GatewayServer.class.getDeclaredMethod("codexNativeResponse",InputStream.class,boolean.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class); m.setAccessible(true);
        try { m.invoke(g,src,false,null,null,conn); throw new AssertionError("invalid Codex event accepted"); } catch(InvocationTargetException expected) {}
        check(src.closes==1 && conn.disconnects==1,"Codex parse exception must close upstream and connection");
    }
    static void socketCancel() throws Exception {
        final ServerSocket listener=new ServerSocket(0); final java.util.concurrent.CountDownLatch disconnected=new java.util.concurrent.CountDownLatch(1);
        final Throwable[] error={null};
        Thread server=new Thread(()->{try(Socket socket=listener.accept()) {
            socket.setSoTimeout(5000); InputStream input=socket.getInputStream(); int c,state=0,length=0; ByteArrayOutputStream h=new ByteArrayOutputStream();
            while((c=input.read())!=-1) { h.write(c); state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0; if(state==4)break; }
            for(String line:new String(h.toByteArray(),StandardCharsets.US_ASCII).split("\\r\\n")) if(line.toLowerCase().startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            while(length-->0) if(input.read()<0)throw new EOFException();
            OutputStream out=socket.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\nevent: response.created\ndata: {\"type\":\"response.created\"}\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
            if(input.read()==-1) disconnected.countDown();
        } catch(Throwable e) {error[0]=e;} }); server.start();
        int before=ledgerCount(); GatewayServer g=ResponsesGatewayOfflineTest.gateway(listener.getLocalPort(),"responses");
        NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\",\"stream\":true}","secret"));
        check(r.getData().read()!=-1,"socket first event"); r.getData().close(); r.getData().close();
        check(disconnected.await(5,java.util.concurrent.TimeUnit.SECONDS),"socket upstream not disconnected on cancel"); server.join(5000); listener.close(); if(error[0]!=null)throw new AssertionError(error[0]);
        check(ledgerCount()==before+1 && new JSONObject(ledgerRows().get(before)).optInt("st")>=400,"socket cancel ledger exactly once");
    }
    public static void main(String[] args) throws Exception {
        ledgerDir=Files.createTempDirectory("gateway-audit-ledger").toFile(); UsageLedger.init(ledgerDir);
        streamLifecycle(); boundedErrorRead(); pendingLiteral(); terminalFlushAndInterleave(); codexFailureCloses(); socketCancel();
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock("{\"error\":{\"message\":\"bad input\"}}", false, 422)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\"}", "secret"));
            ResponsesGatewayOfflineTest.check(r.getStatus().getRequestStatus() == 422, "native Responses must preserve upstream HTTP 422, got " + r.getStatus().getRequestStatus());
        }
        String terminal = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock(terminal, true)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\",\"stream\":true}", "secret"));
            java.lang.reflect.Field f = GatewayServer.class.getDeclaredField("U_ROW"); f.setAccessible(true);
            ResponsesGatewayOfflineTest.check(((ThreadLocal<?>) f.get(null)).get() == null, "stream handoff retains usage ThreadLocal on request thread");
            ResponsesGatewayOfflineTest.read(r.getData()); r.getData().close();
        }
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock("{\"error\":{\"message\":\"context length too long\",\"code\":\"context_length_exceeded\"}}", false, 400)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\"}", "secret"));
            String body=ResponsesGatewayOfflineTest.read(r.getData());
            check(body.contains("context_length_exceeded"),"upstream error detail lost: "+body);
        }
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock("{\"error\":{\"message\":\"bad test-key\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}", false, 422)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\"}", "secret"));
            JSONObject error = new JSONObject(ResponsesGatewayOfflineTest.read(r.getData())).getJSONObject("error");
            check(r.getStatus().getRequestStatus()==422 && "gateway_error".equals(error.optString("type")) && "context_length_exceeded".equals(error.optString("code")) && !error.toString().contains("test-key"),"upstream errors must keep gateway contract: "+error);
        }
        System.out.println("GatewayAuditOfflineTest passed");
    }
}
