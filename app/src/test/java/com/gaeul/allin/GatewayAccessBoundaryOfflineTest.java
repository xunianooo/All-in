package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit untrusted sessions; never silently injects bridge credentials into negative cases. */
public final class GatewayAccessBoundaryOfflineTest {
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static NanoHTTPD.IHTTPSession session(String ip,String path,String method,String body,Map<String,String> headers) {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        Map<String,String> h=new HashMap<>(headers);h.put("content-length",Integer.toString(bytes.length));
        return (NanoHTTPD.IHTTPSession)Proxy.newProxyInstance(NanoHTTPD.IHTTPSession.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(proxy,m,a)-> {
            switch(m.getName()) {
                case "getRemoteIpAddress":return ip;
                case "getUri":return path;
                case "getMethod":return NanoHTTPD.Method.valueOf(method);
                case "getHeaders":return h;
                case "getParameters":return Collections.emptyMap();
                case "getInputStream":return new ByteArrayInputStream(bytes);
                default:return null;
            }
        });
    }
    static NanoHTTPD.Response call(GatewayServer s,String ip,String path,String method,String body,Map<String,String> h) {
        return s.serve(session(ip,path,method,body,h));
    }
    static int code(NanoHTTPD.Response r) { int code=r.getStatus().getRequestStatus();try{if(r.getData()!=null)r.getData().close();}catch(Exception ignored){}return code; }
    static String body(NanoHTTPD.Response r) throws Exception { try(InputStream in=r.getData()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);} }
    static Map<String,String> management() {return new HashMap<>(Map.of("host","127.0.0.1:3425","x-all-in-management",GatewayManagementCredential.get(),"origin","null"));}
    public static void main(String[] args) throws Exception {
        JSONObject config=new JSONObject().put("server",new JSONObject().put("authKey","fixture-inference"))
                .put("providers",new JSONArray().put(new JSONObject().put("id","fake").put("protocol","openai")
                        .put("baseUrl","http://127.0.0.1:1").put("models",new JSONArray().put("m"))
                        .put("keys",new JSONArray().put("fake-provider-secret"))
                        .put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("token","fake-account-secret").put("refresh","fake-refresh-secret")))))
                .put("groups",new JSONArray()).put("setup",new JSONObject().put("done",true));
        GatewayServer s=RoutingSetupOfflineTest.server(config);
        File dir=Files.createTempDirectory("access-boundary-").toFile();
        RoutingAuditOfflineTest.file(s,new File(dir,"config.json"));
        for(String ip:new String[]{"127.0.0.1","::1","192.0.2.2"}) {
            for(String path:new String[]{"/api/config","/api/server-key","/api/auth/fake/add","/api/auth/fake/remove","/api/providers/accounts/export"}) {
                for(String token:new String[]{"","wrong","fixture-inference"}) {
                    Map<String,String> h=new HashMap<>(); h.put("host","127.0.0.1:3425");
                    h.put("x-all-in-bridge","1"); h.put("x-forwarded-for","127.0.0.1");
                    if(!token.isEmpty())h.put("authorization","Bearer "+token);
                    check(code(call(s,ip,path,"POST","{}",h))==401,"untrusted management rejected: "+path);
                }
            }
            for(String path:new String[]{"/responses","/v1/responses","/v1/chat/completions","/v1/messages","/backend-api/codex/responses"}) {
                check(code(call(s,ip,path,"POST","{}",Map.of("host","127.0.0.1:3425")))==401,"model key required: "+path);
                check(code(call(s,ip,path,"POST","{}",Map.of("host","127.0.0.1:3425","authorization","Bearer wrong")))==401,"wrong model key rejected");
            }
            check(code(call(s,ip,"/legacy/responses","POST","{}",management()))==404,"unknown alias never dispatched");
        }
        Map<String,String> infer=new HashMap<>(Map.of("host","127.0.0.1:3425","authorization","Bearer fixture-inference"));
        check(code(call(s,"127.0.0.1","/v1/models","GET","",infer))==200,"inference still lists models");
        check(code(call(s,"127.0.0.1","/api/config","GET","",infer))==401,"inference key cannot manage");
        String viewBody=body(call(s,"127.0.0.1","/api/config","GET","",management()));
        check(!viewBody.contains("fixture-inference") && !viewBody.contains("fake-provider-secret")
                && !viewBody.contains("fake-account-secret") && !viewBody.contains("fake-refresh-secret")
                && !viewBody.contains(GatewayManagementCredential.get()),"configuration does not disclose credentials");
        JSONObject view=new JSONObject(viewBody);
        check(code(call(s,"127.0.0.1","/api/config","POST",view.toString(),management()))==200,"redacted config round trip");
        JSONObject disk=new JSONObject(Files.readString(new File(dir,"config.json").toPath()));
        check(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token").equals("fake-account-secret"),"account secret preserved");
        check(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getString(0).equals("fake-provider-secret"),"provider key preserved");
        check(!disk.toString().contains("allin-secret-ref:"),"references never persisted");
        JSONObject verifyRequest=new JSONObject().put("providerId","fake").put("keys",new JSONArray().put("fake-provider-secret"));
        JSONObject verified=new JSONObject(body(call(s,"127.0.0.1","/api/providers/keys/verify","POST",verifyRequest.toString(),management())));
        check(verified.getBoolean("matched"),"raw key verification for uncertain write");
        verifyRequest.put("keys",new JSONArray().put("different-fake-key"));
        verified=new JSONObject(body(call(s,"127.0.0.1","/api/providers/keys/verify","POST",verifyRequest.toString(),management())));
        check(!verified.getBoolean("matched"),"mismatched key verification");
        String exported=body(call(s,"127.0.0.1","/api/providers/accounts/export","POST","{\"providerId\":\"fake\"}",management()));
        check(exported.contains("fake-account-secret"),"explicit authenticated export preserved");
        for(String origin:new String[]{"http://evil.example","null","http://127.0.0.1:3425.evil","http://127.0.0.1:3426"}) {
            Map<String,String> h=new HashMap<>(infer);h.put("origin",origin);
            check(code(call(s,"127.0.0.1","/api/config","POST",view.toString(),h))==403,"cross-origin actual request rejected");
            check(code(call(s,"127.0.0.1","/api/config","OPTIONS","",h))==403,"cross-origin preflight rejected");
        }
        Map<String,String> foreignManagement=management();foreignManagement.put("origin","http://evil.example");
        check(code(call(s,"127.0.0.1","/api/config","GET","",foreignManagement))==403,"management token does not waive origin guard");
        Map<String,String> rebinding=management();rebinding.remove("origin");rebinding.put("host","evil.example:3425");
        check(code(call(s,"127.0.0.1","/api/config","GET","",rebinding))==403,"rebinding Host rejected");
        Map<String,String> same=management();same.put("origin","http://127.0.0.1:3425");
        NanoHTTPD.Response sameResponse=call(s,"127.0.0.1","/api/config","GET","",same);
        check("http://127.0.0.1:3425".equals(sameResponse.getHeader("access-control-allow-origin")),"same-origin CORS explicit");body(sameResponse);
        config.put("setup",new JSONObject().put("done",false));
        // The committed config is now the live copy; use a separate fresh fixture for first-run state.
        GatewayServer firstRun=RoutingSetupOfflineTest.server(config);
        check(code(call(firstRun,"127.0.0.1","/api/setup","GET","",management()))==200,"native first-run management available");
        check(code(call(firstRun,"192.0.2.2","/api/setup","GET","",management()))==403,"first-run remote stays blocked");
        check(code(call(firstRun,"127.0.0.1","/api/setup","GET","",infer))==401,"first-run does not exempt untrusted local clients");
        check(GatewayAccessPolicy.hostAllowed("192.168.1.2:3425") && GatewayAccessPolicy.hostAllowed("[::1]:3425"),"numeric LAN/IPv6 hosts supported");
        System.out.println("GatewayAccessBoundaryOfflineTest passed (untrusted local/remote, management isolation, config round trip, CORS, aliases)");
    }
}
