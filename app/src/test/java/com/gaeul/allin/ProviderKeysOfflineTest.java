package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;

public final class ProviderKeysOfflineTest {
    static final String PATH = "/api/providers/keys";
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static int request(GatewayServer s, String ip, String method, String body, String key) {
        return RoutingSetupOfflineTest.status(s,ip,PATH,method,body,key);
    }
    static int post(GatewayServer s, String body) { return request(s,"127.0.0.1","POST",body,null); }
    static String payload(String id, JSONArray keys) { return new JSONObject().put("providerId",id).put("keys",keys).toString(); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("provider-keys").toFile();
        File disk = new File(dir,"config.json");
        JSONObject guard = new JSONObject().put("enabled",true);
        JSONObject keyObj = new JSONObject().put("key","old-fixture").put("metadata",new JSONObject().put("n",1));
        JSONArray oldKeys = new JSONArray().put(keyObj);
        JSONObject alpha = new JSONObject().put("id","alpha").put("enabled",false).put("keys",oldKeys);
        JSONObject beta = new JSONObject().put("id","beta");
        JSONObject account = new JSONObject().put("token","fixture-refreshed");
        JSONObject subscription = new JSONObject().put("id","subscription").put("accounts",new JSONArray().put(account));
        JSONArray providers = new JSONArray().put(alpha).put(beta).put(subscription);
        JSONObject cfg = new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth")).put("providers",providers).put("guard",guard);
        GatewayServer s = RoutingSetupOfflineTest.server(cfg);
        RoutingAuditOfflineTest.file(s,disk);
        String valid = payload("alpha",new JSONArray().put(" test-fixture ").put(new JSONObject().put("key"," object-fixture ").put("off",true).put("enabled",false).put("meta",new JSONObject().put("n",2))).put("  "));
        check(request(s,"192.0.2.1","POST",valid,"fixture-auth")==403,"setup gate");
        cfg.put("setup",new JSONObject().put("done",true));
        check(request(s,"192.0.2.1","POST",valid,null)==401,"remote auth");
        check(request(s,"127.0.0.1","GET","",null)==405,"method");
        for (String bad : new String[]{"{}","not-json","{\"providerId\":\"alpha\",\"keys\":{}}","{\"providerId\":\"alpha\",\"keys\":[1]}","{\"providerId\":\"alpha\",\"keys\":[{}]}","{\"providerId\":\"alpha\",\"keys\":[{\"key\":1}]}","{\"providerId\":\"alpha\",\"keys\":[{\"key\":\"x\",\"off\":\"true\"}]}","{\"providerId\":\"alpha\",\"keys\":[{\"key\":\"x\",\"enabled\":null}]}","{\"providerId\":\"alpha\",\"keys\":[],\"accounts\":[]}"})
            check(post(s,bad)==400,"invalid body");
        JSONArray tooMany = new JSONArray(); for (int i=0;i<257;i++) tooMany.put("");
        check(post(s,payload("alpha",tooMany))==400,"count limit");
        char[] chars = new char[16385]; java.util.Arrays.fill(chars,'x');
        check(post(s,payload("alpha",new JSONArray().put(new String(chars))))==400,"length limit");
        check(post(s,payload("missing",new JSONArray()))==404,"missing");
        check(post(s,payload("subscription",new JSONArray()))==403,"subscription");
        providers.put(new JSONObject().put("id","alpha"));
        check(post(s,valid)==409,"duplicate"); providers.remove(providers.length()-1);
        for (Object invalid : new Object[]{"", " ", 1, JSONObject.NULL})
            check(post(s,new JSONObject(valid).put("expectedStateToken",invalid).toString())==400,"invalid token");
        NanoHTTPD.Response config = s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null));
        String stale = new JSONObject(new String(config.getData().readAllBytes(),StandardCharsets.UTF_8))
                .getJSONObject("providerStateTokens").getString("alpha");
        check(post(s,payload("alpha",new JSONArray().put("other-client")))==200,"legacy other client update");
        String staleRequest = new JSONObject(valid).put("expectedStateToken",stale).toString();
        check(post(s,staleRequest)==409 && alpha.getJSONArray("keys").getString(0).equals("other-client"),"stale keys cannot overwrite other client");
        NanoHTTPD.Response currentConfig = s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null));
        String current = new JSONObject(new String(currentConfig.getData().readAllBytes(),StandardCharsets.UTF_8))
                .getJSONObject("providerStateTokens").getString("alpha");
        NanoHTTPD.Response accepted = s.serve(RoutingSetupOfflineTest.request("127.0.0.1",PATH,"POST",new JSONObject(valid).put("expectedStateToken",current).toString(),null));
        JSONObject acceptedBody = new JSONObject(new String(accepted.getData().readAllBytes(),StandardCharsets.UTF_8));
        check(accepted.getStatus().getRequestStatus()==200 && !acceptedBody.getString("stateToken").equals(current),"conditional keys token");
        check(post(s,new JSONObject(valid).put("expectedStateToken",current).toString())==409,"no-op stale keys rejected");
        NanoHTTPD.Response r = s.serve(RoutingSetupOfflineTest.request("127.0.0.1",PATH,"POST",valid,null));
        check(r.getStatus().getRequestStatus()==200,"save");
        JSONObject response = new JSONObject(new String(r.getData().readAllBytes(),StandardCharsets.UTF_8));
        check(response.length()==4 && response.getBoolean("ok") && response.getString("providerId").equals("alpha") && response.getInt("keyCount")==3,"safe response");
        JSONArray updated = alpha.getJSONArray("keys");
        check(updated.getString(0).equals("test-fixture") && updated.getJSONObject(1).getString("key").equals("object-fixture") && updated.getJSONObject(1).getJSONObject("meta").getInt("n")==2 && updated.getString(2).isEmpty(),"normalization metadata");
        check(!alpha.getBoolean("enabled") && cfg.getJSONObject("guard")==guard && subscription.getJSONArray("accounts").getJSONObject(0)==account && account.getString("token").equals("fixture-refreshed"),"other fields and account identity preserved");
        check(CommercialKeyPool.list(updated).size()==1,"disabled and empty keys filtered");
        JSONObject persisted = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
        check(persisted.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").length()==3,"persisted");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int i=0;i<20;i++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> a = executor.submit(() -> { start.await(); return post(s,payload("alpha",new JSONArray().put("a-fixture"))); });
                Future<Integer> b = executor.submit(() -> { start.await(); return post(s,payload("beta",new JSONArray().put("b-fixture"))); });
                Future<Integer> e = executor.submit(() -> { start.await(); return RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/providers/enabled","POST","{\"providerId\":\"alpha\",\"enabled\":true}",null); });
                start.countDown(); check(a.get()==200 && b.get()==200 && e.get()==200,"concurrent writes");
                persisted = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
                JSONArray ps=persisted.getJSONArray("providers");
                check(ps.getJSONObject(0).getBoolean("enabled") && ps.getJSONObject(0).getJSONArray("keys").length()==1 && ps.getJSONObject(1).getJSONArray("keys").length()==1,"concurrent preserved on disk");
            }
        } finally { executor.shutdownNow(); }
        check(post(s,payload("alpha",new JSONArray()))==200 && alpha.getJSONArray("keys").length()==0,"clear");
        RoutingAuditOfflineTest.file(s,new File(new File(dir,"missing"),"config.json"));
        alpha.put("keys",oldKeys);
        check(post(s,payload("alpha",new JSONArray()))==500 && alpha.getJSONArray("keys")==oldKeys && alpha.getJSONArray("keys").getJSONObject(0)==keyObj,"rollback identity");
        beta.remove("keys");
        check(post(s,payload("beta",new JSONArray()))==500 && !beta.has("keys"),"rollback absence");
        System.out.println("ProviderKeysOfflineTest OK");
    }
}
