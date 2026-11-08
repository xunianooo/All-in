package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Gateway integration with synthetic credentials and a loopback-only upstream. */
public final class ZCodeGiftBusyGatewayHttpTest {
    static void check(boolean ok,String msg) { if(!ok) throw new AssertionError(msg); }
    static final class Upstream implements AutoCloseable {
        final ServerSocket server; final Thread thread; final AtomicInteger calls=new AtomicInteger(), paid=new AtomicInteger();
        final List<byte[]> bodies=Collections.synchronizedList(new ArrayList<byte[]>());
        volatile String mode="success-json"; volatile Throwable failure;
        Upstream() throws Exception { server=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1")); thread=new Thread(this::serve,"busy-gateway-upstream"); thread.setDaemon(true); thread.start(); }
        void serve() { while(!server.isClosed()) try(Socket socket=server.accept()) {
            socket.setSoTimeout(10000); InputStream in=socket.getInputStream(); ByteArrayOutputStream header=new ByteArrayOutputStream(); int state=0,v;
            while(state<4 && (v=in.read())!=-1) { header.write(v); state=state==0&&v==13?1:state==1&&v==10?2:state==2&&v==13?3:state==3&&v==10?4:0; if(header.size()>65536) throw new IOException("headers"); }
            String headers=header.toString("UTF-8"); int len=0; for(String line:headers.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            byte[] request=new byte[len]; for(int off=0;off<len;) { int n=in.read(request,off,len-off); if(n<0) throw new EOFException(); off+=n; }
            boolean gift=headers.startsWith("POST /v1/messages "); if(!gift) paid.incrementAndGet(); else bodies.add(request);
            int count=gift?calls.incrementAndGet():0; String m=mode;
            if(gift && m.equals("disconnect") && count==2) continue;
            boolean redirect=gift && m.equals("redirect");
            boolean truncated=gift && m.equals("truncated");
            boolean reject=gift && !redirect && (m.equals("refuse") || m.equals("conflict") || count==1);
            String body; String type;
            if(redirect) { body="{\"message\":\"fixture redirect secret\"}"; type="application/json"; }
            else if(reject) { body=m.equals("conflict")?"{\"code\":3008,\"error\":{\"code\":3009}}":"{\"code\":3008}"; type="application/json"; }
            else if(!gift) { body="{\"id\":\"paid\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"paid fixture\"},\"finish_reason\":\"stop\"}]}"; type="application/json"; }
            else if(m.equals("success-stream")) { body=ZCodeGiftUsageHttpTest.stream("full",true); type="text/event-stream"; }
            else { body=ZCodeGiftUsageHttpTest.json("full"); type="application/json"; }
            byte[] data=body.getBytes(StandardCharsets.UTF_8); OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 "+(redirect?"302 Found":reject?"429 Busy":"200 OK")+"\r\n"+(redirect?"Location: http://127.0.0.1:"+server.getLocalPort()+"/redirect-target\r\n":"")+"Content-Type: "+type+"\r\nContent-Length: "+(data.length+(truncated?50:0))+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(data); out.flush();
        } catch(SocketException e) { if(!server.isClosed()) failure=e; } catch(Throwable e) { if(!server.isClosed()) failure=e; } }
        void reset(String m) { mode=m; calls.set(0); paid.set(0); bodies.clear(); }
        public void close() throws Exception { server.close(); thread.join(5000); check(!thread.isAlive(),"upstream shutdown"); if(failure!=null) throw new AssertionError("upstream",failure); }
    }
    static final class Transport implements GatewayServer.GiftTransport {
        volatile boolean allowed=true; volatile String lastStable; final AtomicInteger opens=new AtomicInteger();
        public JSONObject summary(String jwt,String device) { return new JSONObject().put("ok",true).put("buckets",new JSONArray().put(new JSONObject().put("remaining",1).put("capabilities",new JSONArray().put("model:"+(allowed?ZCodeGiftUsageHttpTest.UPSTREAM:"unrelated-model"))))); }
        public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dressed) { throw new AssertionError("observer overload required"); }
        @SuppressWarnings("unchecked") public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dressed,ZCodeGift.Observer observer,String stableAccount) {
            try { Field f=GatewayServer.class.getDeclaredField("REQUEST_CONTEXT"); f.setAccessible(true); RequestContext c=((ThreadLocal<RequestContext>)f.get(null)).get();
                check(c!=null,"gateway request context"); lastStable=stableAccount; opens.incrementAndGet();
                return ZCodeGift.openStream(jwt,device,site,model,body,"fixture-session",10000,dressed,c,observer,stableAccount);
            } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
    static JSONObject facts(UsageLedger.Row row,int sends,String busy,String source) {
        JSONObject diag=RequestDiagnostics.json(row); JSONObject facts=diag.getJSONObject("zcodeGift");
        check(facts.getInt("sends")==sends,"sends "+facts); check(source.equals(facts.getString("source")),"source "+facts);
        check(busy==null?facts.isNull("busyCode"):busy.equals(facts.optString("busyCode")),"busy code "+facts); return facts;
    }
    static void equalBodies(Upstream u,int n) { check(u.bodies.size()==n,"body count"); for(byte[] b:u.bodies) check(Arrays.equals(b,u.bodies.get(0)),"request bytes changed"); }
    static void reset(Upstream u,String mode) { ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); u.reset(mode); }
    public static void main(String[] args) throws Exception {
        List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<UsageLedger.Row>());
        java.nio.file.Path dir=Files.createTempDirectory("gift-busy-gateway-"); UsageLedger.init(dir.toFile()); UsageLedger.setHooks(rows::add);
        try(Upstream u=new Upstream()) {
            ZCodeGift.setFixtureBase("http://127.0.0.1:"+u.server.getLocalPort()); Transport transport=new Transport();
            JSONObject config=ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
            config.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("uid","fixture-stable-account");
            ZCodeGiftUsageHttpTest.Gateway gateway=new ZCodeGiftUsageHttpTest.Gateway(config,transport); gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
            try {
                for(boolean messages:new boolean[]{false,true}) for(boolean streaming:new boolean[]{false,true}) {
                    reset(u,streaming?"success-stream":"success-json"); int before=rows.size();
                    String result=ZCodeGiftUsageHttpTest.request(gateway,messages,streaming,200); ZCodeGiftUsageHttpTest.idle(gateway);
                    check(result.contains("hello"),"response "+result); UsageLedger.Row row=ZCodeGiftUsageHttpTest.verify(rows,before,1,200);
                    JSONObject f=facts(row,2,"3008","upstream"); check(f.getLong("retryWaitMs")>=900,"retry wait "+f);
                    check(u.calls.get()==2 && u.paid.get()==0,"success upstream counts"); equalBodies(u,2);
                    check(transport.lastStable!=null && transport.lastStable.contains("fixture-stable-account"),"stable account forwarded");
                }
                reset(u,"refuse"); int before=rows.size(); String response=ZCodeGiftUsageHttpTest.request(gateway,false,false,429); ZCodeGiftUsageHttpTest.idle(gateway);
                JSONObject detail=new JSONObject(response).getJSONObject("error"); check("3008".equals(detail.getString("business_code")),"final business code");
                check("upstream".equals(detail.getString("source")),"final source"); UsageLedger.Row refusal=ZCodeGiftUsageHttpTest.verify(rows,before,1,429);
                check(facts(refusal,3,"3008","upstream").getLong("retryWaitMs")>=2800,"three-send wait"); equalBodies(u,3); check(u.paid.get()==0,"refusal used paid fallback");
                reset(u,"disconnect"); before=rows.size(); response=ZCodeGiftUsageHttpTest.request(gateway,false,false,502); ZCodeGiftUsageHttpTest.idle(gateway);
                detail=new JSONObject(response).getJSONObject("error"); check("upstream".equals(detail.getString("source")),"network source");
                UsageLedger.Row failed=ZCodeGiftUsageHttpTest.verify(rows,before,1,502); JSONObject d=RequestDiagnostics.json(failed);
                check("unknown_execution".equals(d.getString("executionState")),"network execution "+d); facts(failed,2,"3008","upstream"); check(u.calls.get()==2 && u.paid.get()==0,"network retry or paid"); equalBodies(u,2);
                reset(u,"conflict"); before=rows.size(); response=ZCodeGiftUsageHttpTest.request(gateway,false,false,429); ZCodeGiftUsageHttpTest.idle(gateway);
                detail=new JSONObject(response).getJSONObject("error"); check(!detail.has("business_code"),"conflicting business code");
                check(!detail.getString("message").toLowerCase(Locale.ROOT).contains("short"),"conflict claims short retry");
                UsageLedger.Row conflict=ZCodeGiftUsageHttpTest.verify(rows,before,1,429); facts(conflict,1,null,"upstream"); check(u.calls.get()==1 && u.paid.get()==0,"conflict replay");
                reset(u,"redirect"); before=rows.size(); response=ZCodeGiftUsageHttpTest.request(gateway,false,false,502); ZCodeGiftUsageHttpTest.idle(gateway);
                UsageLedger.Row redirected=ZCodeGiftUsageHttpTest.verify(rows,before,1,502); facts(redirected,1,null,"upstream");
                check(u.calls.get()==1 && u.paid.get()==0,"redirect followed or replayed");
                check(!response.contains("fixture redirect secret"),"redirect body leaked");

                reset(u,"truncated"); before=rows.size(); response=ZCodeGiftUsageHttpTest.request(gateway,false,false,502); ZCodeGiftUsageHttpTest.idle(gateway);
                UsageLedger.Row shortBody=ZCodeGiftUsageHttpTest.verify(rows,before,1,502); d=RequestDiagnostics.json(shortBody);
                check("unknown_execution".equals(d.getString("executionState")),"truncated execution "+d);
                facts(shortBody,1,null,"upstream"); check(u.calls.get()==1 && u.paid.get()==0,"truncated replay or paid");

                reset(u,"success-json"); Field holdField=ZCodeGift.class.getDeclaredField("giftBusyHold"); holdField.setAccessible(true);
                ZCodeGiftBusyPolicy.BusyHold hold=(ZCodeGiftBusyPolicy.BusyHold)holdField.get(null);
                for(int i=0;i<5;i++) hold.sustainedCap(); check(hold.remainingMillis()>0,"fixture hold armed");
                before=rows.size(); HttpURLConnection held=ZCodeGiftUsageHttpTest.post(gateway.getListeningPort(),false,false);
                try {
                    check(held.getResponseCode()==429,"busy hold status "+held.getResponseCode());
                    String retry=held.getHeaderField("Retry-After"); check(retry!=null && Long.parseLong(retry)>0,"hold Retry-After");
                    InputStream error=held.getErrorStream(); if("gzip".equalsIgnoreCase(held.getHeaderField("Content-Encoding"))) error=new java.util.zip.GZIPInputStream(error);
                    try(InputStream in=error) { response=ZCodeGiftUsageHttpTest.read(in); }
                } finally { held.disconnect(); }
                ZCodeGiftUsageHttpTest.idle(gateway); detail=new JSONObject(response).getJSONObject("error");
                check("local_busy_hold".equals(detail.getString("source")),"hold response source");
                for(String secret:new String[]{"fixture-jwt","fixture-device","fixture-key","fixture-gateway"}) check(!response.contains(secret),"hold leaked secret");
                UsageLedger.Row heldRow=ZCodeGiftUsageHttpTest.verify(rows,before,1,429); facts(heldRow,0,null,"local_busy_hold");
                check("not_sent".equals(RequestDiagnostics.json(heldRow).getString("executionState")),"hold execution");
                check(u.calls.get()==0 && u.paid.get()==0,"hold sent upstream or paid");
                System.out.println("ZCodeGiftBusyGatewayHttpTest PASS");
            } finally { gateway.stop(); ZCodeGift.setFixtureBase(null); ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); }
        } finally { UsageLedger.setHooks(null); }
    }
}
