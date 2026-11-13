package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** No real credentials or upstream network: exact reported rejection sequence over loopback. */
public final class TraeBrowserLoginHttpTest {
    private static final String CRLF = new String(new char[]{13,10});
    private static final String IDE = "ono9krqynydwx5";
    private static final String SOLO = "en1oxy7wnw8j9n";
    private static int checks;
    private static void ok(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }
    private static JSONObject error(int code) { return new JSONObject().put("ResponseMetadata",new JSONObject().put("Error",new JSONObject().put("Code",code))); }
    private static JSONObject token(String suffix) { return new JSONObject().put("Result",new JSONObject().put("Token","fixture-access-"+suffix).put("RefreshToken","fixture-refresh-"+suffix).put("TokenExpireAt","2099-01-01T00:00:00Z")); }

    static final class Fixture implements AutoCloseable {
        final TraeControlLoginHttpTest.Host host;
        final ServerSocket server = new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final AtomicInteger bindings=new AtomicInteger(), validations=new AtomicInteger(), renewals=new AtomicInteger(), models=new AtomicInteger(), claims=new AtomicInteger();
        final Thread accept;
        volatile String bindingMode="rejected", validationMode="valid", renewalMode="valid", lastClient="";
        volatile JSONObject lastRenewal;
        volatile boolean pendingObserved;
        volatile Throwable failure;
        Fixture(TraeControlLoginHttpTest.Host host)throws Exception {
            this.host=host;TraeAuth.setFixtureBase("http://127.0.0.1:"+server.getLocalPort());
            accept=new Thread(()->{while(!server.isClosed())try {Socket socket=server.accept();Thread t=new Thread(()->serve(socket));t.setDaemon(true);t.start();}catch(IOException done){break;}});
            accept.setDaemon(true);accept.start();
        }
        void serve(Socket socket) {
            try(Socket s=socket) {
                s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int[] end={13,10,13,10};int matched=0,ch;
                while(h.size()<32768&&(ch=in.read())>=0){h.write(ch);matched=ch==end[matched]?matched+1:ch==13?1:0;if(matched==4)break;}
                if(matched!=4)throw new IOException("incomplete fixture request");
                String headers=h.toString("UTF-8"),path=headers.split(" ")[1];int length=0;
                for(String line:headers.split(CRLF))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                if(length<0||length>1048576)throw new IOException("fixture request too large");
                byte[] bytes=new byte[length];int at=0;while(at<length){int n=in.read(bytes,at,length-at);if(n<1)throw new IOException("truncated fixture request");at+=n;}
                JSONObject body=length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                int status=200;String response;
                if(path.equals("/trae/api/v3/oauth/ExchangeToken")) {
                    int n=bindings.incrementAndGet();
                    if(bindingMode.equals("drop"))return;
                    if(bindingMode.equals("second-drop")&&n==2)return;
                    if(bindingMode.equals("http502")){status=502;response="upstream unavailable";}
                    else if(bindingMode.equals("http429")){status=429;response=error(429).toString();}
                    else if(bindingMode.equals("html")){response="<html>not a token response</html>";}
                    else if(bindingMode.equals("redirect")){status=302;response="{}";}
                    else if(bindingMode.equals("truncated")){status=401;response=error(20403).toString();}
                    else if(bindingMode.equals("garbage")){status=401;response=error(20403).toString()+" trailing";}
                    else if(bindingMode.equals("conflict")){response=token("conflict").put("ResponseMetadata",error(10101).getJSONObject("ResponseMetadata")).toString();}
                    else if(bindingMode.equals("success")){response=token("bound").toString();}
                    else {boolean solo=SOLO.equals(body.optString("ClientID"));status=solo?400:401;response=error(solo?10101:20403).toString();}
                } else if(path.equals("/cloudide/api/v3/trae/oauth/ExchangeToken")) {
                    int n=renewals.incrementAndGet();lastRenewal=body;lastClient=body.optString("ClientID");
                    JSONObject disk=new JSONObject(new String(Files.readAllBytes(host.file),StandardCharsets.UTF_8));
                    JSONArray aa=disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
                    pendingObserved=aa.length()>0&&"pending".equals(aa.getJSONObject(0).optString("refreshState"));
                    if(renewalMode.equals("drop"))return;
                    if(renewalMode.equals("rejected")){status=401;response=error(10101).toString();}
                    else if(renewalMode.equals("missing-refresh")){response=new JSONObject().put("Result",new JSONObject().put("Token","fixture-access-incomplete")).toString();}
                    else response=token("renewed-"+n).toString();
                } else if(path.equals("/trae/api/v2/pay/ide_user_ent_usage")) {
                    validations.incrementAndGet();
                    if(validationMode.equals("denied")){status=401;response=error(1001).toString();}
                    else if(validationMode.equals("malformed"))response="{}";
                    else if(validationMode.equals("business-error"))response=error(1001).put("user_entitlement_pack_list",new JSONArray()).toString();
                    else response=new JSONObject().put("user_entitlement_pack_list",new JSONArray()).put("is_credits_billing",true).toString();
                } else if(path.contains("batch_get_detail_param")) {
                    models.incrementAndGet();
                    response=new JSONObject().put("function_configs",new JSONArray().put(new JSONObject().put("function","chat_v3").put("config_info_list",new JSONArray().put(new JSONObject().put("config_name","fixture-model").put("display_name","Fixture Model").put("context_window_tokens",new JSONObject().put("dev",4096)))))).toString();
                } else if(path.endsWith("/claim")) { claims.incrementAndGet();response="{}"; }
                else throw new IOException("unexpected fixture path: "+path);
                byte[] data=response.getBytes(StandardCharsets.UTF_8);
                OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+status+" Result"+CRLF+"Content-Type: application/json"+CRLF+"Content-Length: "+(data.length+(bindingMode.equals("truncated")?9:0))+CRLF+"Connection: close"+CRLF+CRLF).getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
            } catch(Throwable e) { failure=e; }
        }
        public void close()throws Exception {server.close();TraeAuth.setFixtureBase(null);accept.join(1000);}
    }
    static JSONObject login(TraeControl c,String uid,String client)throws Exception {
        JSONObject init=c.api("/api/auth/trae-cn/init",Collections.singletonMap("fresh","1"),null);
        URL callback=TraeControlLoginHttpTest.callbackUrl(init);
        JSONObject jwt=new JSONObject().put("Token","fixture-browser-access-"+uid).put("RefreshToken","fixture-browser-refresh-"+uid).put("TokenExpireAt","2099-01-01T00:00:00Z").put("ClientID",client);
        JSONObject info=new JSONObject().put("UserID",uid).put("ScreenName","Fixture User").put("Host","https://api.trae.cn");
        String path=callback.getFile()+"?loginTraceID="+init.getString("flowId")+"&userJwt="+URLEncoder.encode(jwt.toString(),"UTF-8")+"&userInfo="+URLEncoder.encode(info.toString(),"UTF-8");
        String page=TraeAuthHttpTest.response(callback.getPort(),"GET "+path+" HTTP/1.1"+CRLF+"Host: 127.0.0.1:"+callback.getPort()+CRLF+CRLF);
        ok(!page.contains("fixture-browser-access")&&!page.contains("fixture-browser-refresh"),"callback must not leak credentials");
        return c.api("/api/auth/trae-cn/poll",Collections.singletonMap("flowId",init.getString("flowId")),null);
    }
    static JSONArray accounts(TraeControlLoginHttpTest.Host h) { return h.snapshot().getJSONArray("providers").getJSONObject(0).getJSONArray("accounts"); }
    static JSONObject lease(TraeControl c,String id)throws Exception {try(RequestContext r=new RequestContext("fixture-browser-lease",NetworkPolicy.capture(),10000)){return c.lease(id,r);}}
    static void browserSuccessAndRenewal()throws Exception {
        TraeControlLoginHttpTest.Host h=new TraeControlLoginHttpTest.Host();TraeControl c=new TraeControl(h);
        try(Fixture f=new Fixture(h)) {
            JSONObject result=login(c,"browser-user",IDE);
            if(!"ready".equals(result.optString("status")))System.out.println("login diagnostic status="+result.optString("status")+" error="+result.optString("error")+" bind="+f.bindings.get()+" validate="+f.validations.get()+" renew="+f.renewals.get()+" fixture="+(f.failure==null?"none":f.failure.getClass().getSimpleName()));
            ok(result.optBoolean("ok")&&"ready".equals(result.optString("status")),"reported 10101/20403 binding refusals must not discard a verified browser login");
            JSONObject a=accounts(h).getJSONObject(0);String id=a.getString("id");
            ok(!a.optBoolean("bound",true)&&a.optBoolean("browserSessionVerified")&&"browser".equals(a.optString("authMode")),"browser session must be verified and honestly unbound");
            ok(IDE.equals(a.optString("clientId")),"retain callback client identity");
            ok(f.bindings.get()==2&&f.validations.get()==1&&f.renewals.get()==0,"login validates access without spending refresh credential");
            ok(f.models.get()==1&&f.claims.get()==0,"sync models only; no automatic check-in");
            ok(result.optString("hint").contains("设备绑定未启用")&&!result.optString("hint").contains("重新登录"),"accurate non-binding hint");
            ok(!result.toString().contains("fixture-browser-access")&&!result.toString().contains("fixture-browser-refresh"),"poll must not expose tokens");
            h.expire();ExecutorService workers=Executors.newFixedThreadPool(2);
            try {Future<JSONObject> one=workers.submit(()->lease(c,id)),two=workers.submit(()->lease(c,id));ok(one.get(10,TimeUnit.SECONDS).getString("token").equals(two.get(10,TimeUnit.SECONDS).getString("token")),"concurrent ordinary refresh is single-flight");}
            finally {workers.shutdownNow();}
            ok(f.renewals.get()==1&&f.pendingObserved&&f.bindings.get()==2,"single ordinary refresh behind durable pending fence");
            ok(IDE.equals(f.lastClient)&&"-".equals(f.lastRenewal.optString("ClientSecret"))&&f.lastRenewal.has("UserID")&&!f.lastRenewal.has("DeviceProof"),"ordinary refresh follows captured client protocol");
            a=accounts(h).getJSONObject(0);ok(a.getString("refresh").equals("fixture-refresh-renewed-1")&&!a.has("refreshState")&&!a.optBoolean("bound",true),"publish rotated credentials without faking a binding");
            f.renewalMode="drop";h.expire();int before=f.renewals.get();
            for(int attempt=0;attempt<2;attempt++)try {lease(c,id);throw new AssertionError("unknown refresh accepted");}catch(Exception expected){}
            ok(f.renewals.get()==before+1&&!accounts(h).getJSONObject(0).optString("refreshState").isEmpty(),"unknown ordinary refresh never replayed");
            ok(f.failure==null,"fixture completed cleanly");
        } finally {c.close();h.cleanup();}
    }
    static void refusedOrUnknown(String binding,String validation,int expectedBindings,int expectedValidation)throws Exception {
        TraeControlLoginHttpTest.Host h=new TraeControlLoginHttpTest.Host();TraeControl c=new TraeControl(h);
        try(Fixture f=new Fixture(h)) {
            f.bindingMode=binding;f.validationMode=validation;JSONObject r=login(c,"bad-user",IDE);
            ok(!"ready".equals(r.optString("status"))&&accounts(h).length()==0,"unsafe or invalid login cannot enter pool: "+binding+"/"+validation);
            ok(f.bindings.get()==expectedBindings&&f.validations.get()==expectedValidation&&f.renewals.get()==0,"no blind fallback/replay: "+binding+"/"+validation);
        } finally {c.close();h.cleanup();}
    }
    static void saveFailureAndClientIdentity()throws Exception {
        TraeControlLoginHttpTest.Host h=new TraeControlLoginHttpTest.Host();TraeControl c=new TraeControl(h);
        try(Fixture f=new Fixture(h)) {
            h.fail=true;JSONObject r=login(c,"save-user",SOLO);ok(!r.optBoolean("ok")&&accounts(h).length()==0,"save failure is not login success");
            h.fail=false;r=login(c,"save-user",SOLO);ok("ready".equals(r.optString("status")),"new authorization can recover from save failure");
            String id=r.getString("keyId");h.expire();lease(c,id);ok(SOLO.equals(f.lastClient),"ordinary refresh preserves a SOLO callback client too");
            JSONObject before=accounts(h).getJSONObject(0);r=login(c,"second-user",IDE);ok("ready".equals(r.optString("status"))&&accounts(h).length()==2,"another user gets its own account slot");
            ok(accounts(h).getJSONObject(0).toString().equals(before.toString()),"adding browser account preserves existing account bytes");
        } finally {c.close();h.cleanup();}
    }
    static void renewalFailure(String mode,String expectedState)throws Exception {
        TraeControlLoginHttpTest.Host h=new TraeControlLoginHttpTest.Host();TraeControl c=new TraeControl(h);
        try(Fixture f=new Fixture(h)) {
            JSONObject r=login(c,"renewal-user",IDE);ok("ready".equals(r.optString("status")),"prepare verified browser session");
            String id=r.getString("keyId");h.expire();f.renewalMode=mode;
            for(int i=0;i<2;i++)try{lease(c,id);throw new AssertionError("bad ordinary refresh accepted");}catch(Exception expected){}
            ok(f.renewals.get()==1&&expectedState.equals(accounts(h).getJSONObject(0).optString("refreshState")),"ordinary refresh state and no replay: "+mode);
        }finally{c.close();h.cleanup();}
    }
    static void boundAccountOrdinaryFallback()throws Exception {
        TraeControlLoginHttpTest.Host h=new TraeControlLoginHttpTest.Host();TraeControl c=new TraeControl(h);
        try(Fixture f=new Fixture(h)) {
            f.bindingMode="success";JSONObject r=login(c,"bound-user",IDE);ok("ready".equals(r.optString("status"))&&accounts(h).getJSONObject(0).optBoolean("bound"),"normal device-bound login preserved");
            String id=r.getString("keyId");h.expire();f.bindingMode="rejected";JSONObject renewed=lease(c,id);
            ok(f.bindings.get()==3&&f.renewals.get()==1&&f.pendingObserved&&f.validations.get()==0,"existing bound account uses one ordinary exchange after definitive refusals");
            ok(!renewed.optBoolean("bound",true)&&renewed.optBoolean("browserSessionVerified")&&"fixture-access-renewed-1".equals(renewed.optString("token")),"ordinary fallback publishes actual fresh token without claiming binding");
        }finally{c.close();h.cleanup();}
    }
    public static void main(String[] args)throws Exception {
        browserSuccessAndRenewal();
        refusedOrUnknown("drop","valid",1,0);
        refusedOrUnknown("second-drop","valid",2,0);
        refusedOrUnknown("http502","valid",1,0);
        refusedOrUnknown("http429","valid",1,0);
        refusedOrUnknown("html","valid",1,0);
        refusedOrUnknown("rejected","denied",2,1);
        refusedOrUnknown("rejected","malformed",2,1);
        refusedOrUnknown("rejected","business-error",2,1);
        refusedOrUnknown("redirect","valid",1,0);
        refusedOrUnknown("truncated","valid",1,0);
        refusedOrUnknown("garbage","valid",1,0);
        refusedOrUnknown("conflict","valid",1,0);
        renewalFailure("missing-refresh","pending");
        renewalFailure("rejected","requires_relogin");
        boundAccountOrdinaryFallback();
        saveFailureAndClientIdentity();
        System.out.println("TraeBrowserLoginHttpTest OK ("+checks+" checks)");
    }
}
