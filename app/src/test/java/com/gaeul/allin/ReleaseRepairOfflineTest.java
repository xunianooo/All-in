package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual signature and local HTTP helpers, synthetic data only; no vendor connections. */
public final class ReleaseRepairOfflineTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static String md5(String value) throws Exception {
        byte[] bytes=MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static String component(String value) throws Exception {
        // Independent encodeURIComponent for the fixed ASCII path/query used by the signing contract.
        return URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("%21","!").replace("%27","'")
                .replace("%28","(").replace("%29",")").replace("%7E","~");
    }
    private static final class Capture extends HttpURLConnection {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Capture(String url) throws Exception {super(new URL(url));}
        public void connect(){throw new AssertionError("No vendor connection permitted");}
        public void disconnect(){}
        public boolean usingProxy(){return false;}
        public int getResponseCode(){return 200;}
        public InputStream getInputStream(){return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));}
        public OutputStream getOutputStream(){return sent;}
    }
    private static void signature(boolean post,String body,long now) throws Exception {
        Capture[] seen=new Capture[1];
        MiniMaxAuth.AllowanceConnectionFactory factory=url->{seen[0]=new Capture(url);Thread.sleep(4);return seen[0];};
        if(post)MiniMaxAuth.signedPost("/v1/api/user/info","fixture-only","123",body,now,factory);
        else MiniMaxAuth.signedGet("/v1/api/user/info","fixture-only","123",now,factory);
        Capture actual=seen[0];String query=actual.getURL().getQuery();
        String unix=null;for(String field:query.split("&"))if(field.startsWith("unix="))unix=field.substring(5);
        check(Long.toString(now).equals(unix),"Actual URL contains the one supplied millisecond clock");
        String encodedBody=post?(body==null?"{}":body):"{}";
        String path=actual.getURL().getFile();
        check(md5(component(path)+"_"+encodedBody+md5(Long.toString(now))+"ooui").equals(actual.getRequestProperty("yy")),"yy recomputes from actual URL/body bytes");
        check(Long.toString(now/1000).equals(actual.getRequestProperty("x-timestamp")),"timestamp shares URL clock across a second boundary");
        check(md5((now/1000)+MiniMaxAuth.SIGN_SECRET+(post?encodedBody:"")).equals(actual.getRequestProperty("x-signature")),"actual x-signature matches reference contract");
        check(Arrays.equals(actual.sent.toByteArray(),post?encodedBody.getBytes(StandardCharsets.UTF_8):new byte[0]),"signed bytes are the bytes sent");
        check((post?"POST":"GET").equals(actual.getRequestMethod()),"actual request method");
    }
    private static void localResponse(int status,String body,boolean expected) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket()) {
            server.setReuseAddress(true);server.bind(new InetSocketAddress("127.0.0.1",LocalBridge.PORT));server.setSoTimeout(5000);
            Thread peer=new Thread(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);InputStream in=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
                    int state=0,c;while((c=in.read())>=0){headers.write(c);state=(state==0 && c==13)?1:(state==1 && c==10)?2:(state==2 && c==13)?3:(state==3 && c==10)?4:0;if(state==4)break;if(headers.size()>16384)throw new IOException();}
                    int size=0;for(String line:headers.toString("US-ASCII").split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
                    while(size-->0)if(in.read()<0)throw new EOFException();
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" Test\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
                }catch(Throwable e){failure.set(e);}
            },"local-bridge-regression");peer.start();
            LocalBridge.Result result=LocalBridge.postResult("/fixture", "{}");
            peer.join(6000);check(!peer.isAlive(),"local HTTP fixture exits");if(failure.get()!=null)throw new AssertionError(failure.get());
            check(result.status==status,"LocalBridge preserves actual HTTP status");
            check(result.accountSaved()==expected,"Only confirmed HTTP + boolean ok + string id is successful");
        }
    }
    public static void main(String[] args) throws Exception {
        TestPrivacyScope.initialize();
        for(long now:new long[]{1762920000999L,1762920001000L,1762920001123L}) {
            signature(false,null,now);
            for(String body:new String[]{"",null,"{}","{\"text\":\"中文与表情😀\"}"})signature(true,body,now);
        }
        for(int status:new int[]{400,401,409,500})localResponse(status,"{\"ok\":false}",false);
        for(String body:new String[]{"{\"ok\":false}","","broken","{}","{\"ok\":true}","{\"ok\":true,\"id\":12}","{\"ok\":\"true\",\"id\":\"a\"}","{\"ok\":true,\"id\":\"a\"} garbage"})localResponse(200,body,false);
        localResponse(200,"{\"ok\":true,\"id\":\"account-fixture\"}",true);
        check(!LocalBridge.Result.failure("network").accountSaved(),"transport errors never succeed");
        check(!new LocalBridge.Result(200,"{\"ok\":true,\"id\":\"x\"}","truncated").accountSaved(),"partial transport never succeeds");
        System.out.println("ReleaseRepairOfflineTest PASS checks="+checks);
    }
}
