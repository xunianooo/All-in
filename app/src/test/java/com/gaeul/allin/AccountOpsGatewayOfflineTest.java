package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AccountOpsGatewayOfflineTest {
    static void check(boolean x,String why) { if(!x) throw new AssertionError(why); }
    static NanoHTTPD.Response call(GatewayServer s,String ip,String path,String method,String body,String key) {
        return s.serve(RoutingSetupOfflineTest.request(ip,"/api/account-ops/"+path,method,body,key));
    }
    static class Fake extends HttpURLConnection {
        Fake(String url) throws Exception { super(new URL(url)); }
        public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return new ByteArrayInputStream("{\"code\":0,\"data\":{\"available_points\":42}}".getBytes(StandardCharsets.UTF_8)); }
    }
    static void field(GatewayServer s,String name,Object value) throws Exception { java.lang.reflect.Field f=GatewayServer.class.getDeclaredField(name); f.setAccessible(true); f.set(s,value); }
    static JSONObject result(GatewayServer s,String path,JSONObject body) throws Exception {
        return new JSONObject(ResponsesGatewayOfflineTest.read(call(s,"127.0.0.1",path,"POST",body.toString(),null).getData()));
    }
    static void loomy() throws Exception {
        final int[] sends={0}, verifies={0}, wallets={0};
        final String[] mode={"ok"};
        com.sun.net.httpserver.HttpServer upstream=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/", x -> {
            try {
                String path=x.getRequestURI().getPath();
                JSONObject reply;
                if(path.startsWith("/login/")) {
                    JSONObject param=new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getJSONObject("param");
                    check("86".equals(param.getString("ccode")) && "13800138000".equals(param.getString("phone")),"sms parameters");
                    if(path.endsWith("sendMsgCode")) { sends[0]++; check(param.length()==3 && param.getInt("expire")==300,"send contract"); reply=new JSONObject().put("code","000000").put("data",new JSONObject().put("msgid","fake-id")); }
                    else { verifies[0]++; check(param.length()==5 && param.getInt("expire")==1209600 && "fake-id".equals(param.getString("msgid")),"verify contract");
                        reply="123456".equals(param.getString("mcode")) ? new JSONObject().put("code","000000").put("data",new JSONObject().put("session", mode[0].equals("savefail") ? "second-session" : "fake-session")) : new JSONObject().put("code","bad"); }
                } else { wallets[0]++; if(mode[0].equals("fail")) { x.sendResponseHeaders(503,-1); return; } reply=new JSONObject().put("code","000000").put("data",new JSONObject().put("balance",42)); }
                byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200,bytes.length); x.getResponseBody().write(bytes);
            } catch(Throwable e) { e.printStackTrace(); x.sendResponseHeaders(500,-1); } finally { x.close(); }
        }); upstream.start();
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("loomy-test");
        try {
            JSONObject provider=new JSONObject().put("id","loomy").put("enabled",true).put("accounts",new JSONArray());
            JSONObject config=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","inference")).put("providers",new JSONArray().put(provider));
            GatewayServer server=RoutingSetupOfflineTest.server(config); field(server,"loomySmsFlows",new HashMap<String,JSONObject>());
            java.io.File file=dir.resolve("config.json").toFile(); field(server,"configFile",file);
            String origin="http://127.0.0.1:"+upstream.getAddress().getPort(); server.setLoomyForTests(new LoomyAuth(origin,origin,"fake-key","fake-secret"));
            JSONObject sendBody=new JSONObject().put("phone","+86 138-0013-8000");
            check(call(server,"127.0.0.1","loomy/send-code","POST",sendBody.toString(),"inference").getStatus().getRequestStatus()==401,"loomy management auth");
            check(call(server,"192.168.1.2","loomy/send-code","POST",sendBody.toString(),GatewayManagementCredential.get()).getStatus().getRequestStatus()==403 && sends[0]==0,"loomy loopback isolation");
            String flow=result(server,"loomy/send-code",sendBody).getString("flowId");
            JSONObject verify=new JSONObject().put("flowId",flow).put("code","000000");
            JSONObject bad=result(server,"loomy/verify",verify); check(!bad.getBoolean("ok") && bad.getBoolean("retryable") && !bad.getBoolean("flowExpired"),"wrong code retryable");
            mode[0]="fail"; verify.put("code","123456"); JSONObject success=result(server,"loomy/verify",verify);
            check(success.getBoolean("ok") && !success.getBoolean("walletAvailable") && wallets[0]==1,"wallet failure does not block login");
            check(!success.toString().contains("fake-session"),"session secret not returned");
            JSONObject disk=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            check("fake-session".equals(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token")),"session persisted");
            check(result(server,"loomy/verify",verify).getBoolean("flowExpired"),"consumed flow");
            field(server,"configFile",dir.resolve("missing/config.json").toFile()); mode[0]="savefail";
            flow=result(server,"loomy/send-code",sendBody).getString("flowId"); verify.put("flowId",flow);
            JSONObject failed=result(server,"loomy/verify",verify);
            check(!failed.getBoolean("ok") && failed.getString("stage").equals("save") && failed.getBoolean("flowExpired") && !failed.toString().contains("fake-session"),"save failure safely reported");
            check(wallets[0]==1 && result(server,"loomy/verify",verify).getBoolean("flowExpired"),"save failure consumes flow without balance request");
        } finally { upstream.stop(0); java.nio.file.Files.deleteIfExists(dir.resolve("config.json")); java.nio.file.Files.deleteIfExists(dir); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject a=new JSONObject().put("id","one").put("token","secret-test-token");
        JSONObject p=new JSONObject().put("id","raccoon").put("enabled",true).put("accounts",new JSONArray().put(a));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","inference")) .put("providers",new JSONArray().put(p));
        GatewayServer s=RoutingSetupOfflineTest.server(cfg); List<Fake> connections=new ArrayList<>();
        s.setVendorConnectionsForTests(url->{ try { Fake c=new Fake(url); connections.add(c); return c; } catch(Exception e) { throw new IOException(e); } });
        String body="{\"accountId\":\"one\",\"url\":\"https://attacker.test\"}";
        check(call(s,"127.0.0.1","raccoon/balance","POST",body,"inference").getStatus().getRequestStatus()==401,"management required");
        check(call(s,"192.168.1.2","raccoon/balance","POST",body,GatewayManagementCredential.get()).getStatus().getRequestStatus()==403,"local only");
        check(call(s,"127.0.0.1","raccoon/balance","GET","",null).getStatus().getRequestStatus()==405,"post only");
        NanoHTTPD.Response response=call(s,"127.0.0.1","raccoon/balance","POST",body,null);
        String raw=ResponsesGatewayOfflineTest.read(response.getData()); JSONObject result=new JSONObject(raw);
        check(result.getInt("balance")==42 && !raw.contains("secret-test-token"),"balance and secrecy");
        check(connections.size()==1 && connections.get(0).getURL().toString().equals("https://xiaohuanxiong.com/api/web/points/v1/balance"),"fixed origin");
        check(!connections.get(0).getInstanceFollowRedirects() && connections.get(0).getRequestProperty("Authorization").equals("Bearer secret-test-token"),"safe transport");
        check(call(s,"127.0.0.1","raccoon/checkin","POST","{\"accountId\":\"missing\"}",null).getStatus().getRequestStatus()==404,"account required");
        loomy();
        System.out.println("AccountOpsGatewayOfflineTest passed");
    }
}
