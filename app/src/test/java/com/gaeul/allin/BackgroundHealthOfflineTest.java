package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Standalone JVM regression suite; pass gate, serve, health, or all (default). */
public final class BackgroundHealthOfflineTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static String read(InputStream in) throws Exception { return ResponsesGatewayOfflineTest.read(in); }
    static GatewayServer gateway(int port) throws Exception { return ResponsesGatewayOfflineTest.gateway(port); }
    static NanoHTTPD.IHTTPSession model() throws Exception {
        return ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}", "secret", "/v1/chat/completions");
    }
    static NanoHTTPD.IHTTPSession health(String ip, NanoHTTPD.Method method, Map<String,String> headers) {
        return (NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(BackgroundHealthOfflineTest.class.getClassLoader(), new Class<?>[]{NanoHTTPD.IHTTPSession.class}, (p,m,a) -> {
            switch (m.getName()) {
                case "getUri": return "/api/health";
                case "getMethod": return method;
                case "getRemoteIpAddress": return ip;
                case "getHeaders": return headers;
                case "getParameters": case "getParms": return Collections.emptyMap();
                default: return null;
            }
        });
    }
    static void gate() throws Exception {
        ForwardRequestGate active = new ForwardRequestGate();
        check(active.enter(), "enter");
        check(!active.beginRecovery(), "recovery while active");
        active.leave();
        check(active.beginRecovery(), "idle recovery");
        check(!active.enter() && !active.beginRecovery(), "draining blocks admission and second recovery");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i=0; i<2000; i++) {
                ForwardRequestGate gate = new ForwardRequestGate();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> admission = pool.submit(() -> { start.await(); return gate.enter(); });
                Future<Boolean> recovery = pool.submit(() -> { start.await(); return gate.beginRecovery(); });
                start.countDown();
                boolean entered = admission.get(3, TimeUnit.SECONDS), recovering = recovery.get(3, TimeUnit.SECONDS);
                check(entered != recovering, "admission and recovery must be mutually exclusive");
                if (entered) { check(gate.active()==1 && !gate.beginRecovery(), "active blocks recovery"); gate.leave(); }
                else check(!gate.enter(), "draining blocks admission");
            }
        } finally { pool.shutdownNow(); }
    }
    static final String SSE = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n";
    static final String JSON = "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}";
    static void serve() throws Exception {
        // The upstream is blocked before sending headers: admission must already be held.
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try (Socket s=server.accept()) {
                s.setSoTimeout(3000); InputStream in=s.getInputStream(); int state=0,c;
                ByteArrayOutputStream headers=new ByteArrayOutputStream();
                while ((c=in.read())!=-1) { headers.write(c); state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0; if(state==4) break; }
                int length=0;
                for (String line : new String(headers.toByteArray(),StandardCharsets.US_ASCII).split("\\r\\n"))
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                while (length>0) { int n=in.read(); if(n<0) throw new EOFException("truncated request"); length--; }
                received.countDown(); if (!release.await(3,TimeUnit.SECONDS)) throw new IOException("test timed out");
                byte[] body=SSE.getBytes(StandardCharsets.UTF_8);
                OutputStream out=s.getOutputStream(); out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
            } catch(Throwable t) { failure.set(t); received.countDown(); } });
            worker.start(); GatewayServer g=gateway(server.getLocalPort());
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<NanoHTTPD.Response> pending=pool.submit(() -> g.serve(model()));
                check(received.await(3,TimeUnit.SECONDS) && failure.get()==null, "upstream received request");
                check(g.activeForwardRequests()==1 && !g.beginIdleRecovery(), "protected before serve returns");
                release.countDown(); NanoHTTPD.Response response=pending.get(5,TimeUnit.SECONDS);
                check(response.getStatus().getRequestStatus()==200 && g.activeForwardRequests()==1, "stream held after serve");
                check(read(response.getData()).contains("hello"), "SSE body");
                check(g.activeForwardRequests()==1 && !g.beginIdleRecovery(), "EOF alone must not release before HTTP framing flush"); response.close();
                check(g.beginIdleRecovery(), "idle recovery succeeds");
                check(g.serve(model()).getStatus().getRequestStatus()==503 && g.activeForwardRequests()==0, "draining rejects new model request");
            } finally { release.countDown(); pool.shutdownNow(); worker.join(4000); }
            check(!worker.isAlive() && failure.get()==null, "bounded upstream completed: "+failure.get());
        }
        // Two in-flight streams reveal an accidental second decrement on repeated close.
        try (ResponsesGatewayOfflineTest.Mock a=new ResponsesGatewayOfflineTest.Mock(SSE,true);
             ResponsesGatewayOfflineTest.Mock b=new ResponsesGatewayOfflineTest.Mock(SSE,true)) {
            GatewayServer g=gateway(a.server.getLocalPort());
            NanoHTTPD.Response one=g.serve(model());
            ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).put("baseUrl", "http://127.0.0.1:"+b.server.getLocalPort()+"/v1");
            NanoHTTPD.Response two=g.serve(model());
            check(g.activeForwardRequests()==2, "two streams active");
            one.close(); one.close();
            check(g.activeForwardRequests()==1 && !g.beginIdleRecovery(), "cancel releases only once");
            two.close(); check(g.activeForwardRequests()==0, "both streams released");
        }
        for (int status : new int[]{200,400}) {
            try (ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(status==200?JSON:"{\"error\":\"bad\"}",false,status)) {
                GatewayServer g=gateway(mock.server.getLocalPort());
                NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}","secret","/v1/chat/completions"));
                check(r.getStatus().getRequestStatus()==status && g.activeForwardRequests()==1, "JSON/error body holds gate "+status);
                read(r.getData()); check(g.activeForwardRequests()==1, "JSON/error EOF waits for response completion "+status); r.close(); check(g.activeForwardRequests()==0, "JSON/error close releases "+status);
            }
        }
        try (ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(SSE,true)) {
            GatewayServer g=gateway(mock.server.getLocalPort()); NanoHTTPD.Response r=g.serve(model());
            InputStream original=r.getData(); original.close();
            r.setData(new InputStream() { public int read() throws IOException { throw new IOException("injected read failure"); } });
            try { r.getData().read(); throw new AssertionError("read must fail"); }
            catch (IOException expected) { check(g.activeForwardRequests()==1, "read failure waits for send/close completion"); }
            r.close(); r.close(); check(g.activeForwardRequests()==0, "failure close releases once");
        }
    }
    static final class HttpMock implements AutoCloseable {
        final ServerSocket server; final Thread worker; volatile String request; volatile Throwable failure;
        HttpMock(int status, String body, boolean stall) throws Exception {
            server=new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); server.setSoTimeout(4000);
            worker=new Thread(() -> { try (Socket s=server.accept()) {
                s.setSoTimeout(3000); InputStream in=s.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int state=0,c;
                while ((c=in.read())!=-1 && bytes.size()<8192) { bytes.write(c); state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0; if(state==4) break; }
                request=new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
                if(stall) { Thread.sleep(2200); return; }
                byte[] data=body.getBytes(StandardCharsets.UTF_8); OutputStream out=s.getOutputStream();
                out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nLocation: http://127.0.0.1:1/redirect\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(data); out.flush();
            } catch(Throwable t) { failure=t; } }); worker.start();
        }
        public void close() throws Exception { worker.join(5000); server.close(); check(!worker.isAlive() && failure==null, "health mock failed: "+failure); }
    }
    static void probe(int status, String body, boolean stall, String expected, boolean ok, String error) throws Exception {
        try (HttpMock mock=new HttpMock(status,body,stall)) {
            long start=System.nanoTime(); HealthProbe.Result result=HealthProbe.probe(mock.server.getLocalPort(),expected);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            check(result.ok==ok && (error==null || error.equals(result.error)), "probe: "+result.code+" "+result.error);
            check(elapsed<3500, "probe bounded time: "+elapsed);
            check(mock.request!=null && mock.request.startsWith("GET /api/health HTTP/1.") && !mock.request.toLowerCase(Locale.ROOT).contains("authorization:") && !mock.request.contains("secret"), "probe request contains no credentials: "+mock.request);
        }
    }
    static void healthTests() throws Exception {
        GatewayServer g=gateway(1); Map<String,String> spoof=new HashMap<>();
        spoof.put("host","127.0.0.1:3425"); spoof.put("x-forwarded-for","127.0.0.1"); spoof.put("forwarded","for=127.0.0.1"); spoof.put("x-real-ip","127.0.0.1"); spoof.put("accept-encoding","gzip");
        check(g.serve(health("192.0.2.1",NanoHTTPD.Method.GET,spoof)).getStatus().getRequestStatus()==403, "remote spoof denied");
        check(g.serve(health(null,NanoHTTPD.Method.GET,spoof)).getStatus().getRequestStatus()==403, "missing peer denied");
        check(g.serve(health("127.0.0.1",NanoHTTPD.Method.POST,spoof)).getStatus().getRequestStatus()==405, "health GET only");
        NanoHTTPD.Response response=g.serve(health("127.0.0.1",NanoHTTPD.Method.GET,spoof));
        String body=read(response.getData()); JSONObject json=new JSONObject(body);
        check(response.getStatus().getRequestStatus()==200 && body.length()<256 && json.length()==3 && json.optBoolean("ok") && "com.gaeul.allin".equals(json.optString("service")) && g.healthInstanceId().equals(json.optString("instance")), "tiny health identity");
        check(!body.contains("secret") && !body.contains("test-key") && g.activeForwardRequests()==0, "health does not expose config or count as model");
        NanoHTTPD.Response ordinaryJson=NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=UTF-8", "{}");
        NanoHTTPD.Response eventStream=NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/event-stream; charset=UTF-8", "data: x\n\n");
        check(!g.useGzipWhenAccepted(eventStream), "SSE gzip disabled");
        check(g.useGzipWhenAccepted(response)==g.useGzipWhenAccepted(ordinaryJson), "health retains ordinary JSON gzip policy");
        probe(200,body,false,g.healthInstanceId(),true,"");
        probe(200,body,false,"different-instance",false,"IdentityMismatch");
        probe(503,body,false,g.healthInstanceId(),false,"HttpStatus");
        probe(302,body,false,g.healthInstanceId(),false,"HttpStatus");
        char[] huge=new char[4097]; Arrays.fill(huge,'x');
        probe(200,new String(huge),false,g.healthInstanceId(),false,"ResponseTooLarge");
        probe(200,"",true,g.healthInstanceId(),false,"SocketTimeoutException");
    }
    public static void main(String[] args) throws Exception {
        Set<String> selected=new HashSet<>(Arrays.asList(args));
        if(selected.isEmpty() || selected.contains("all") || selected.contains("gate")) gate();
        if(selected.isEmpty() || selected.contains("all") || selected.contains("serve")) {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("background-health-");
            try { UsageLedger.init(dir.toFile()); serve(); }
            finally {
                GatewayStreamReadOfflineTest.closeLedger();
                try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(dir)) {
                    for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                        java.nio.file.Files.deleteIfExists(p);
                }
            }
        }
        if(selected.isEmpty() || selected.contains("all") || selected.contains("health")) healthTests();
        System.out.println("BackgroundHealthOfflineTest OK "+selected);
    }
}
