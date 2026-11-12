package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.gaeul.allin.json.*;

public final class NetworkSettingsOfflineTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static NanoHTTPD.Response request(GatewayServer s, String ip, String path, String method, String body, String key) {
        return s.serve(RoutingSetupOfflineTest.request(ip,path,method,body,key));
    }
    static JSONObject json(NanoHTTPD.Response response) throws Exception {
        return new JSONObject(new String(response.getData().readAllBytes(), StandardCharsets.UTF_8));
    }
    static int post(GatewayServer s, String body) { return request(s,"127.0.0.1","/api/network","POST",body,null).getStatus().getRequestStatus(); }
    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("network-settings").toFile();
        File file=new File(dir,"config.json");
        JSONObject provider=new JSONObject().put("id","alpha").put("keys",new JSONArray().put("fixture-key"));
        JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(provider));
        NetworkPolicy.configure(null);
        GatewayServer s=RoutingSetupOfflineTest.server(cfg);
        RoutingAuditOfflineTest.file(s,file);
        check(request(s,"192.0.2.1","/api/network","GET","",null).getStatus().getRequestStatus()==401,"auth required");
        check(json(request(s,"127.0.0.1","/api/network","GET","",null)).getJSONObject("network").getString("mode").equals("default"),"default GET");
        for (String invalid : new String[]{"{}","null","[]","{\"mode\":null}","{\"mode\":\"direct\",\"host\":\"x\"}","{\"mode\":\"http_proxy\",\"host\":\"x\",\"port\":1.0}"})
            check(post(s,invalid)==400,"reject invalid "+invalid);
        JSONObject initial=json(request(s,"127.0.0.1","/api/network","GET","",null));
        String initialToken=initial.getString("stateToken");
        GatewayServer other=RoutingSetupOfflineTest.server(new JSONObject(cfg.toString()));
        String otherToken=json(request(other,"127.0.0.1","/api/network","GET","",null)).getString("stateToken");
        check(!initialToken.equals(otherToken),"tokens belong to individual gateway instances");
        check(post(s,new JSONObject().put("mode","direct").put("expectedStateToken",otherToken).toString())==409,"another instance's token cannot authorize an update");
        String conditional=new JSONObject().put("mode","http_proxy").put("host","proxy.test").put("port",8080).put("expectedStateToken",initialToken).toString();
        check(post(s,conditional)==200,"conditional update");
        check(post(s,conditional)==409,"stale token conflict");
        JSONObject after=json(request(s,"127.0.0.1","/api/network","GET","",null));
        check(!initialToken.equals(after.getString("stateToken")),"token advanced");
        String unchanged=new JSONObject().put("mode","http_proxy").put("host","proxy.test").put("port",8080).put("expectedStateToken",after.getString("stateToken")).toString();
        check(post(s,unchanged)==200 && post(s,unchanged)==409,"unchanged update advances token");
        check(post(s,"{\"mode\":\"direct\",\"expectedStateToken\":null}")==400,"invalid expected token");
        JSONObject saved=new JSONObject(Files.readString(file.toPath()));
        check(saved.getJSONObject("server").getString("authKey").equals("fixture-auth") && saved.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getString(0).equals("fixture-key"),"other data preserved");
        JSONObject stale=new JSONObject(saved.toString()).put("network",new JSONObject().put("mode","direct")).put("providerStateTokens",new JSONObject());
        check(request(s,"127.0.0.1","/api/config","POST",stale.toString(),null).getStatus().getRequestStatus()==200,"legacy update");
        saved=new JSONObject(Files.readString(file.toPath()));
        check(saved.getJSONObject("network").getString("mode").equals("http_proxy") && !saved.has("providerStateTokens"),"legacy network protected");
        String before=saved.toString();
        RoutingAuditOfflineTest.file(s,new File(new File(dir,"missing"),"config.json"));
        String failureToken=json(request(s,"127.0.0.1","/api/network","GET","",null)).getString("stateToken");
        check(post(s,"{\"mode\":\"direct\"}")==500,"write failure");
        check(failureToken.equals(json(request(s,"127.0.0.1","/api/network","GET","",null)).getString("stateToken")),"failed write retains token");
        check(NetworkPolicy.current().mode.equals("http_proxy") && new JSONObject(Files.readString(file.toPath())).toString().equals(before),"failure rollback");
        check(NetworkPolicy.currentScope()==null,"request scope cleared");
        NetworkPolicy.Snapshot old=NetworkPolicy.current();
        try (NetworkPolicy.Scope ignored=NetworkPolicy.use(old)) {
            NetworkPolicy.configure(new JSONObject().put("mode","direct"));
            request(s,"127.0.0.1","/api/network","GET","",null);
            check(NetworkPolicy.capture()==old,"nested scope restored");
        }
        check(NetworkPolicy.currentScope()==null,"scope cleanup");
        // Simulate boot against a synthetic corrupt network member, without Android Context.
        JSONObject invalidCfg=new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("providers",new JSONArray().put(provider)).put("network",new JSONObject().put("mode","invalid"));
        File invalidFile=new File(dir,"invalid.json");
        Files.writeString(invalidFile.toPath(),invalidCfg.toString());
        GatewayServer invalidServer=RoutingSetupOfflineTest.server(invalidCfg);
        RoutingAuditOfflineTest.file(invalidServer,invalidFile);
        java.lang.reflect.Method preflight=GatewayServer.class.getDeclaredMethod("preflightNetworkPolicy"); preflight.setAccessible(true);
        preflight.invoke(invalidServer);
        java.lang.reflect.Method configure=GatewayServer.class.getDeclaredMethod("configureLoadedNetworkPolicy"); configure.setAccessible(true);
        configure.invoke(invalidServer);
        java.lang.reflect.Method save=GatewayServer.class.getDeclaredMethod("saveConfig",JSONObject.class); save.setAccessible(true);
        check(!((Boolean)save.invoke(invalidServer,invalidCfg)),"invalid network rejects unrelated save");
        JSONObject blocked=json(request(invalidServer,"127.0.0.1","/api/network","GET","",null));
        check(blocked.getBoolean("blocked") && blocked.getJSONObject("network").getString("mode").equals("default"),"invalid policy repair placeholder");
        try { NetworkPolicy.openHttp("http://example.invalid/"); throw new AssertionError("invalid policy must block"); }
        catch (java.io.IOException expected) {}
        check(new JSONObject(Files.readString(invalidFile.toPath())).getJSONObject("network").getString("mode").equals("invalid"),"invalid config retained");
        check(post(invalidServer,new JSONObject().put("mode","direct").put("expectedStateToken",blocked.getString("stateToken")).toString())==200,"repair");
        check(!json(request(invalidServer,"127.0.0.1","/api/network","GET","",null)).getBoolean("blocked") && NetworkPolicy.current().mode.equals("direct"),"repair unblocks");
        check(new JSONObject(Files.readString(invalidFile.toPath())).getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getString(0).equals("fixture-key"),"repair retains providers");
        NetworkPolicy.configure(null);
    }
}
