package com.gaeul.allin;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LoomyAuthHttpTest {
    static int refresh=0; static String mode="ok";
    static void require(boolean ok) { if(!ok) throw new AssertionError(); }
    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    public static void main(String[] args) throws Exception {
        HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        s.createContext("/", x -> {
            try {
                String path=x.getRequestURI().getPath(), body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); JSONObject response;
                if(path.startsWith("/login/")) {
                    if (mode.equals("account403")) { x.sendResponseHeaders(403,-1); return; }
                    require("POST".equals(x.getRequestMethod()));
                    require("application/json".equals(x.getRequestHeaders().getFirst("Content-Type")));
                    String md5=b64(MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8)));
                    require(md5.equals(x.getRequestHeaders().getFirst("Content-MD5")));
                    String canonical="POST\n"+path+"\n\n"+md5+"\napplication/json\n"+x.getRequestHeaders().getFirst("Date")+"\n"+x.getRequestHeaders().getFirst("Nonce")+"\n\n";
                    Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec("fake-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
                    require(("account fake-key:"+b64(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))).equals(x.getRequestHeaders().getFirst("Authorization")));
                    JSONObject envelope=new JSONObject(body), base=envelope.getJSONObject("base"), param=envelope.getJSONObject("param");
                    require(envelope.length()==2 && base.length()==6);
                    require(base.getString("appid").equals("GM3LOOMY") && base.getString("modelid").equals("Web") && base.getString("version").equals("1.0.0") && base.getString("devid").equals("web") && base.getString("ua").equals("Loomy|Desktop|Electron|macOS") && base.getString("traceid").matches("[0-9a-f]{32}"));
                    require(param.getString("phone").equals("13800138000") && param.getString("ccode").equals("86"));
                    if(path.endsWith("sendMsgCode")) require(param.length()==3 && param.getInt("expire")==300);
                    else { require(path.equals("/login/phone/checkCode") && param.length()==5 && param.getInt("expire")==1209600 && param.getString("mcode").equals("123456") && param.getString("msgid").equals("fake-id")); }
                    response=new JSONObject().put("code","000000").put("data",path.endsWith("sendMsgCode")?new JSONObject().put("msgid","fake-id"):new JSONObject().put("session","fake-session"));
                } else {
                    require("fake-session".equals(x.getRequestHeaders().getFirst("token"))); require("Bearer fake-session".equals(x.getRequestHeaders().getFirst("Authorization")));
                    if(mode.equals("expired")) response=new JSONObject().put("code","100002");
                    else if(path.endsWith("first-login")) { require(body.equals("{}")); refresh++; response=new JSONObject().put("code","000000").put("data",new JSONObject().put("dailyBalance",5000)); }
                    else if(mode.equals("missing")) response=new JSONObject().put("code","000000").put("data",new JSONObject());
                    else response=new JSONObject().put("code","000000").put("data",new JSONObject().put("data",new JSONObject().put("balance",100).put("dailyBalance",refresh>0?5000:3000).put("dailyLimitPoints",5000).put("dailyRemainingPoints",refresh>0?5000:3000)));
                }
                byte[] out=response.toString().getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,out.length);x.getResponseBody().write(out);
            } catch(Throwable e) { e.printStackTrace(); x.sendResponseHeaders(500,-1); } finally { x.close(); }
        }); s.start();
        try {
            for (String bad : new String[]{"http://example.com:80", "http://127.0.0.1:1234@evil.example:80", "http://127.0.0.1:80/path", "http://127.0.0.1:80?x=1", "https://127.0.0.1:80"}) {
                try { new LoomyAuth(bad,bad,"k","s"); throw new AssertionError("accepted non-loopback origin"); }
                catch (IllegalArgumentException expected) { }
            }
            String origin="http://127.0.0.1:"+s.getAddress().getPort(); LoomyAuth api=new LoomyAuth(origin,origin,"fake-key","fake-secret");
            require(api.sendCode("+86 138-0013-8000").equals("fake-id"));
            require(api.checkCode("13800138000","123456","fake-id").equals("fake-session"));
            mode="account403"; try {api.sendCode("13800138000");throw new AssertionError();} catch(LoomyAuth.Failure e) {require(!e.expired && e.getMessage().equals("上游 HTTP 403"));} mode="ok";
            LoomyAuth.Wallets w=api.validateSession("fake-session");require(w.permanent==100 && w.dailyUsed()==2000);
            require(api.refreshDaily("fake-session").outcome.equals("claimed")); require(api.refreshDaily("fake-session").outcome.equals("done"));
            mode="missing"; try {api.refreshDaily("fake-session");throw new AssertionError();} catch(LoomyAuth.Failure e) {require(!e.expired);}
            require(refresh==2); mode="expired"; try {api.usage("fake-session");throw new AssertionError();} catch(LoomyAuth.Failure e) {require(e.expired);}
            try {api.usage("fake-session\tinvalid");throw new AssertionError();} catch(LoomyAuth.Failure e) {require(!e.expired);}
            try {api.sendCode("bad");throw new AssertionError();} catch(LoomyAuth.Failure e) {require(!e.expired);}
            System.out.println("LoomyAuthHttpTest passed");
        } finally {s.stop(0);}
    }
}
