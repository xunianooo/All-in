package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class NetworkPolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static JSONObject json(String s) throws Exception { return new JSONObject(s); }
    interface Task { void run() throws Exception; }
    static void fails(Task t) throws Exception { try { t.run(); } catch (IllegalArgumentException | IOException e) { return; } throw new AssertionError("expected failure"); }
    static class Server implements AutoCloseable {
        ServerSocket server; Thread thread; AtomicReference<String> received = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Server(String response) throws Exception {
            server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")); server.setSoTimeout(2500);
            thread = new Thread(() -> { try (Socket s = server.accept()) { s.setSoTimeout(2000); InputStream in=s.getInputStream(); ByteArrayOutputStream data=new ByteArrayOutputStream(); int x; while ((x=in.read())!=-1) { data.write(x); byte[] b=data.toByteArray(); int n=b.length; if(n>=4 && b[n-4]==13 && b[n-3]==10 && b[n-2]==13 && b[n-1]==10) break; } received.set(data.toString("US-ASCII")); s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); } catch(Throwable e) { error.set(e); } }); thread.start();
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { thread.join(3500); server.close(); if(thread.isAlive()) throw new AssertionError("server stuck"); if(error.get()!=null) throw new AssertionError(error.get()); }
    }
    static NetworkPolicy.Snapshot proxy(int port) throws Exception { return NetworkPolicy.parse(json("{\"mode\":\"http_proxy\",\"host\":\"127.0.0.1\",\"port\":"+port+"}")); }
    static String read(HttpURLConnection c) throws Exception { c.setConnectTimeout(1500); c.setReadTimeout(1500); try(InputStream in=c.getInputStream()) { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] chunk=new byte[128]; int n; while((n=in.read(chunk))!=-1) b.write(chunk,0,n); return new String(b.toByteArray(),StandardCharsets.US_ASCII); } }
    // Synthetic test-only key, never used by production or any real service.
    static final String FIXTURE_KEY = "MIIKRgIBAzCCCfAGCSqGSIb3DQEHAaCCCeEEggndMIIJ2TCCBbAGCSqGSIb3DQEHAaCCBaEEggWdMIIFmTCCBZUGCyqGSIb3DQEM"
            + "CgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFEJINnIdbXaPaER48mYScuxwLHG4AgInEAIBIDAMBggq"
            + "hkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQX/T/9nhB+Q7vKkomn2CKHASCBNBo/1T5OEdHZfvaJno+AUvmKezc2buYqFqi4uZs3mdl"
            + "P/ccIXqLCRD/13/v2XiEpThttJSNwgngx5ea1Jqqm+buZCn43EoA+gdOTmv8OV+18V2a+TuMFTvlLxrd/dq2RCEFM99YdV5eboBp"
            + "6XTq1uuhiOGpNDKjRmd8/2CbtskitdliVvYkenRj88AKyDsVxFqAiGckdjj0XtnJw3d6sArxmN/iqzl19v5ykhXtvTLaLQU2EKHy"
            + "1BLUGgn3r4tIrUIKVFwzrOAEDk+1srtFvvgRjVIfTsBZqP+BOCm2UTmoKk61RgaQlb16XADFxGo0dcyzJOyTzrS9QRvcIS6yft4C"
            + "dnMBgq+84KpNBNL5zs88Z4TebhEsbIDc+YVdYp3P1Mbaq9B6RzAieUefTVjzwaab2gKEtKc0HcCA9zSuEkQmTtNYOezm1VDvWKLv"
            + "83v07fUSafjhFn2m3mFcoeb6ad5Tsp0Dq70O8zYI1Dsc4YhT1yX4qJsH2DuRZtbjRw4JUrGY++YkwKaedvQA9o55YI2jKDwL0Le4"
            + "4HxfEwqXF1UJ5XDzLvt/sCKQCLel6Ib/MPiuFivv7FHk7jx1rGXnGrR9cwiMf/u03Hr41sbOW+yIwgbbW4OHW1istVde5PpU4r0L"
            + "WhmKeyxgdmt/bRnlyXLo1+v4pqOrZaizJ1PbLZSi3Wf1i/n3HU/718w17p6UVLiVql3PH7LF9U0R6bm+7ABoR/qgan0tQ4fHcjuG"
            + "n/mHkNlpm1aXo16ngR3IDo5jMeeed4jeEsjjFIjOadmQpYwysg/XMqKBiuJwdv4EvOdV15tBNDbW5nyPHVvb7QnPtvjG3Gr2QHUU"
            + "47z7fd6b+tnRmoxw9kdwh0xvlvoWdvXeL1gWlfqgLv+bzalyJhWMhIGxOnGaxwE11695XRD1N6sx8P30WR0cvLdXLmjkZaxXvq0v"
            + "dujAn541rCJuUlj6z4bdY+liF5nsI0HgieXLZtNStUggXL8bsQjSbIz5LT+jDx0bTdBNC+Xl2OKgjEG7FRqKVeT03Y/Y/eaKD8m0"
            + "qMz8rwRNCMqABCtk4RUypm5Wh4NCgmwAF4CRN0YlsbPEokaimWMqP4w7nDTwRslwhthOPzzcggCSu2iohJIqYAdRlUAL0HE2zQMh"
            + "BGz40nRdSyrHMz3RjuOI2pRwApm0vNRxMkOwbH9BJKdvyXV7iW0EVcY72xFkowztFGeXNX3prikmC3c2X1wOK7L6rj1W/ePo1WkR"
            + "uLRxgaFSeIacDuMi9RY5junr2PLY2DMFwYV7t/EN6MFSgrm3+pMK4/Cawl2jej9PaKJtMe8gXI/uu3wJKymoInvfCmBRlxoJLjqR"
            + "gMPRd0tFqCTDN4U4bKR56T43OWvQJO/QgEl8FFdQOfyuCUV4mdYhYfqvgdobibOTDQ+fnhc0XnziP/6AFGV59XoovuSwyD0et3aI"
            + "eVicMiC/SJMF7uJGGJtl21iidVxRRBUhKMjgUsNwfnoSkn9FsVzj7SRw3vpwRiSbutTml42GV2gPiKxzGmNtboDTL5npzeZj7Zp9"
            + "+HaHOTAYyiQ0owR7gE8s0xMXCn1O2CwB2I83fYgOzU5OpXNuEsvr8wsqbgEv0i7YeihkA+3R6nQ/hEEB7zQiAVg46HaGA6SCNr1b"
            + "STFCMB0GCSqGSIb3DQEJFDEQHg4AZgBpAHgAdAB1AHIAZTAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkzODU2Nzk2NDUwMIIEIQYJ"
            + "KoZIhvcNAQcGoIIEEjCCBA4CAQAwggQHBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFAFxJ9bQdipT"
            + "WeNe9G8FFBer2qFiAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQyBvoja8BbpqmE9SNaeX29oCCA5BNY2NpoYx+"
            + "zC3+28CznLRcknxB2QQi5kN9ldcp8J098EsQneUm94YdfTgfsMPxGHY1CAmSTsVY5rkNz43FIz5LqWpcdDEXB79yDErJVV8XJ1E0"
            + "JXSdJzFI2qib4HQoSQX6Q7faI1Geeas+qocwvJcCipgXEjDBvjkrosCNC5VEgdheYruMQ0LlS1IKn11ThpBRUmGztNUxga6+Z/WU"
            + "8SmE/oCEHJ1Q0N+lCDf/zpIVVCfwrCikH8SASerZV9C3Xf0aF5FWWHWmECbjNKldIp9Y9sIvJSV/QItKZLzgfV1sChnB1jdbsVhk"
            + "QOzeKxOFkjKZbrLRi032kQTiELVcysXDYtK0oaGOjz5zBGYLtn31ZQ+DtGZL/QR/UkLkK184jp3rX42NwGOidfFUEd9G3zlartGt"
            + "3mpT3lieUh2688U/cbdW7+0zL/iqXJeEZodkioNSpFq9i1O992GyZ3cBwa//phmwNe8TO+56ePqRrrpQ/DwpZSl7F6UY4SnscP8I"
            + "fT9RhshVQcMiqhT4Q/WFYIDyo24LgJk0GsJUOvNCcAYMkjpkb7U9Wp6+DNk0VtZPBDTD4wd/CzCkqK+0a/MQZsj7lgrSX+HOMgP/"
            + "4hKILw4NuAopZ7gtK5jPFrh/BVr30/EGVBm00Kr3KX9vb6wvYs4X+vqjbdSpl0u0OKfgUQzOpnIB9Auxv4RDis5PAYXNVFoU+uJA"
            + "N2x43VuhoRSvndy2QK5gzf2/AULvLFwvLofzCMxuLUd7tb2bhkwgdrP/BmdOUcVyvsRJETMpe7BfQg//v9ONDwMP6yNZKMuSXhMI"
            + "yqM+wRR/fPCS7WPE1C8h/Z06sTjwH+RatKeVzx9NGBRmn0q8/6zY2RmVyEz5cy+m1YSbRmPMh6qpe0ze93bnio95Sg0DDPuJeIV8"
            + "gYJZbrpWgilsGAbQdGR3tHl4t6DKSjR5sWp4bQAe0ysoY7or2rr5ZyMwwPuOXC/pX6zROAQ7u0/T8jYWDRFOPCiKv9r9JnhLbvI4"
            + "iyWQTdKIrJ9yEViMDPGndLcDETXH2dnOslB2r6sljqG6iWHL+AXtAPQPc6Qse4r2w6rEZ0iBatuuhpwqIdvF81gjnZCENSIGhKv3"
            + "P/5FWfhYZHpr+dAywN3XJIljlc7TjXIoyO45J+7NiiFqLp5GbvsQfhh2av80OPcd85DNzsS6w6dQpBHvJKXD4On8ZjTqIFvmY4th"
            + "plMCIrkwTTAxMA0GCWCGSAFlAwQCAQUABCCOOMcCtNlJq4V6ivdwV0O8vdOSFajCo+tZ2w3ERTmhagQUWEkb09T2gN0RkNX5FQjx"
            + "pqQHdxQCAicQ";
    static javax.net.ssl.SSLContext context(boolean trustFixture, boolean serve) throws Exception {
        java.security.KeyStore keys=java.security.KeyStore.getInstance("PKCS12");
        keys.load(new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE_KEY)), "changeit".toCharArray());
        javax.net.ssl.KeyManager[] km=null; javax.net.ssl.TrustManager[] tm=null;
        if (serve) { javax.net.ssl.KeyManagerFactory f=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); f.init(keys,"changeit".toCharArray()); km=f.getKeyManagers(); }
        if (trustFixture) { java.security.KeyStore trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null,null); trust.setCertificateEntry("fixture",keys.getCertificate("fixture")); javax.net.ssl.TrustManagerFactory f=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); f.init(trust); tm=f.getTrustManagers(); }
        javax.net.ssl.SSLContext context=javax.net.ssl.SSLContext.getInstance("TLS"); context.init(km,tm,null); return context;
    }
    static void tlsFixture(String host, boolean trust, boolean success) throws Exception {
        javax.net.ssl.SSLContext previous=javax.net.ssl.SSLContext.getDefault(); int tries=AntigravityAuth.HANDSHAKE_TRIES;
        ServerSocket listener=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1")); listener.setSoTimeout(3000);
        AtomicReference<String> connect=new AtomicReference<>(); AtomicReference<String> request=new AtomicReference<>(); AtomicReference<Throwable> error=new AtomicReference<>();
        javax.net.ssl.SSLContext serverContext=context(false,true);
        Thread worker=new Thread(() -> { try (Socket plain=listener.accept()) {
            plain.setSoTimeout(3000); ByteArrayOutputStream line=new ByteArrayOutputStream(); int c;
            while ((c=plain.getInputStream().read())!=-1) { line.write(c); byte[] b=line.toByteArray(); int n=b.length; if(n>=4 && b[n-4]==13 && b[n-3]==10 && b[n-2]==13 && b[n-1]==10) break; }
            connect.set(line.toString("US-ASCII"));
            plain.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); plain.getOutputStream().flush();
            javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)serverContext.getSocketFactory().createSocket(plain,"localhost",listener.getLocalPort(),true); tls.setUseClientMode(false); tls.setSoTimeout(3000);
            try { tls.startHandshake(); ByteArrayOutputStream b=new ByteArrayOutputStream(); while((c=tls.getInputStream().read())!=-1) { b.write(c); byte[] a=b.toByteArray(); int n=a.length; if(n>=4 && a[n-4]==13 && a[n-3]==10 && a[n-2]==13 && a[n-1]==10) break; } request.set(b.toString("US-ASCII")); tls.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII)); tls.getOutputStream().flush(); }
            catch(IOException e) { if(success) throw e; }
            finally { tls.close(); }
        } catch(Throwable e) { error.set(e); } });
        try {
            worker.start(); javax.net.ssl.SSLContext.setDefault(context(trust,false)); AntigravityAuth.HANDSHAKE_TRIES=1;
            NetworkPolicy.configure(json("{\"mode\":\"http_proxy\",\"host\":\"127.0.0.1\",\"port\":"+listener.getLocalPort()+"}"));
            if (success) { try(AntigravityAuth.Resp r=AntigravityAuth.request("https://"+host+"/fixture","GET",null,null,null,null,2000,2000)) { check(r.status==200 && r.text().equals("OK")); } }
            else fails(() -> AntigravityAuth.request("https://"+host+"/fixture","GET",null,null,null,null,2000,2000));
        } finally { javax.net.ssl.SSLContext.setDefault(previous); AntigravityAuth.HANDSHAKE_TRIES=tries; NetworkPolicy.configure(null); worker.join(4000); listener.close(); }
        check(!worker.isAlive()); if(error.get()!=null) throw new AssertionError(error.get());
        check(connect.get()!=null && connect.get().startsWith("CONNECT "+host+":443 "));
        if(success) check(request.get()!=null && request.get().startsWith("GET /fixture ")); else check(request.get()==null);
    }
    public static void main(String[] args) throws Exception {
        check(NetworkPolicy.parse(null).mode.equals("default"));
        for(String s:new String[]{"{}","{\"mode\":\"direct\",\"host\":\"x\"}","{\"mode\":\"x\"}","{\"mode\":\"http_proxy\",\"host\":\"http://a\",\"port\":1}","{\"mode\":\"http_proxy\",\"host\":\"a@b\",\"port\":1}","{\"mode\":\"http_proxy\",\"host\":\"a\",\"port\":0}","{\"mode\":\"http_proxy\",\"host\":\"a\",\"port\":1.0}"}) fails(() -> NetworkPolicy.parse(json(s)));
        check(NetworkPolicy.parse(json("{\"mode\":\"http_proxy\",\"host\":\"[::1]\",\"port\":7890}")).toJson().getString("host").equals("::1"));
        NetworkPolicy.Snapshot original=NetworkPolicy.configure(json("{\"mode\":\"direct\"}"));
        fails(() -> NetworkPolicy.configure(json("{\"mode\":\"bad\"}"))); check(NetworkPolicy.current()==original);
        try(NetworkPolicy.Scope scope=NetworkPolicy.use(original)) { NetworkPolicy.configure(null); check(NetworkPolicy.capture()==original); } check(NetworkPolicy.capture()==NetworkPolicy.current());
        fails(() -> original.openHttp("file:///tmp/a"));
        String ok="HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
        try(Server s=new Server(ok)) { check(read(original.openHttp("http://127.0.0.1:"+s.port()+"/direct")).equals("OK")); check(s.received.get().startsWith("GET /direct ")); }
        try(Server s=new Server(ok)) { check(read(proxy(1).openHttp("http://localhost:"+s.port()+"/local")).equals("OK")); }
        try(Server s=new Server(ok)) { check(read(proxy(s.port()).openHttp("http://target.local.invalid/test")).equals("OK")); check(s.received.get().startsWith("GET http://target.local.invalid/test ")); }
        try(Server s=new Server("HTTP/1.1 200 Connection established\r\nX: ok\r\n\r\n")) { try(Socket sock=proxy(s.port()).connectTunnel("target.local.invalid",443,1000,1000)) { check(sock.isConnected()); } check(s.received.get().startsWith("CONNECT target.local.invalid:443 HTTP/1.1")); }
        for(String response:new String[]{"HTTP/1.1 407 Authentication required\r\n\r\n","HTTP/1.1 503 Unavailable\r\n\r\n","HTTP/1.1 200 OK\r\n", "HTTP/1.1 200 OK\r\nX: "+new String(new char[33000]).replace('\0','a')+"\r\n\r\n"}) try(Server s=new Server(response)) { fails(() -> proxy(s.port()).connectTunnel("target.local.invalid",443,1000,1000)); }
        ProxySelector previous=ProxySelector.getDefault();
        try(Server s=new Server("HTTP/1.1 200 OK\r\n\r\n")) { ProxySelector.setDefault(new ProxySelector() { public List<Proxy> select(URI uri) { check(uri.getScheme().equals("https")); return Collections.singletonList(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",s.port()))); } public void connectFailed(URI u,SocketAddress a,IOException e) {} }); try(Socket sock=NetworkPolicy.parse(null).connectTunnel("target.local.invalid",443,1000,1000)) { check(sock.isConnected()); } }
        finally { ProxySelector.setDefault(previous); }
        try(Server target=new Server(ok)) { NetworkPolicy.Snapshot fixed=proxy(1); NetworkPolicy.configure(json("{\"mode\":\"direct\"}")); fails(() -> fixed.connectTunnel("target.local.invalid",target.port(),300,300)); /* target is never selected or contacted */ target.server.close(); target.thread.join(3500); check(target.received.get()==null); target.error.set(null); }
        fails(() -> NetworkPolicy.parse(json("{\"mode\":\"blocked\"}")));
        NetworkPolicy.block(); NetworkPolicy.Snapshot blocked=NetworkPolicy.current(); check(blocked.toJson().getString("mode").equals("blocked"));
        try { blocked.openHttp("https://target.local.invalid/"); throw new AssertionError(); } catch(IOException e) { check(e.getMessage().equals("Outbound network configuration is invalid")); }
        try { blocked.connectTunnel("target.local.invalid",443,300,300); throw new AssertionError(); } catch(IOException e) { check(e.getMessage().equals("Outbound network configuration is invalid")); }
        try(Server s=new Server(ok)) { check(read(blocked.openHttp("http://localhost:"+s.port()+"/repair")).equals("OK")); }
        try(Server s=new Server(ok)) { try(Socket sock=blocked.connectTunnel("127.0.0.1",s.port(),1000,1000)) { sock.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); } }
        // Slow bytes arrive faster than SO_TIMEOUT but cannot extend the total header budget.
        try (ServerSocket slow=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Thread drip=new Thread(() -> { try (Socket socket=slow.accept()) { for (int i=0;i<30;i++) { socket.getOutputStream().write('X'); socket.getOutputStream().flush(); Thread.sleep(40); } } catch(Exception expected) {} });
            drip.start(); long start=System.nanoTime();
            try { proxy(slow.getLocalPort()).connectTunnel("target.local.invalid",443,1000,200); throw new AssertionError(); } catch(SocketTimeoutException expected) {}
            check(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000); drip.join(2000); check(!drip.isAlive());
        }
        // The target name is deliberately unresolvable locally; only the synthetic proxy sees it.
        tlsFixture("proxy-fixture.invalid",true,true);
        tlsFixture("wrong-fixture.invalid",true,false);
        tlsFixture("proxy-fixture.invalid",false,false);
        System.out.println("NetworkPolicyOfflineTest OK");
    }
}
