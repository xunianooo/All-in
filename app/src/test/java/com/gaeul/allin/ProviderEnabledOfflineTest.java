package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import com.gaeul.allin.json.*;

public final class ProviderEnabledOfflineTest {
    static final String PATH = "/api/providers/enabled";
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static int status(GatewayServer s, String ip, String path, String method, String body, String key) {
        return RoutingSetupOfflineTest.status(s, ip, path, method, body, key);
    }
    static int post(GatewayServer s, String body) { return status(s,"127.0.0.1",PATH,"POST",body,null); }
    static String body(String id, boolean enabled) { return new JSONObject().put("providerId",id).put("enabled",enabled).toString(); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("provider-enabled").toFile();
        File disk = new File(dir,"config.json");
        JSONObject account = new JSONObject().put("token","fixture-token");
        JSONObject key = new JSONObject().put("key","fixture-key");
        JSONObject a = new JSONObject().put("id","alpha").put("accounts",new JSONArray().put(account)).put("keys",new JSONArray().put(key));
        JSONObject b = new JSONObject().put("id","beta").put("enabled",false);
        JSONObject codex = new JSONObject().put("id","codex").put("enabled",false).put("accounts",new JSONArray().put(new JSONObject().put("enabled",false).put("refresh","fixture")));
        JSONObject copilot = new JSONObject().put("id","github-copilot").put("enabled",false).put("accounts",new JSONArray().put(new JSONObject().put("enabled",false).put("refresh","fixture")));
        JSONArray providers = new JSONArray().put(a).put(b).put(codex).put(copilot);
        JSONObject cfg = new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth")).put("providers",providers);
        GatewayServer s = RoutingSetupOfflineTest.server(cfg);
        RoutingAuditOfflineTest.file(s,disk);
        check(status(s,"192.0.2.1",PATH,"POST",body("alpha",false),"fixture-auth")==403,"setup gate");
        cfg.put("setup",new JSONObject().put("done",true));
        check(status(s,"192.0.2.1",PATH,"POST",body("alpha",false),null)==401,"remote authentication");
        check(status(s,"127.0.0.1",PATH,"GET","",null)==405,"GET rejected");
        for (String bad : new String[]{"{}","not-json","{\"providerId\":\"alpha\",\"enabled\":\"false\"}","{\"providerId\":1,\"enabled\":true}","{\"providerId\":\"  \",\"enabled\":true}","{\"providerId\":\"alpha\",\"enabled\":true,\"accounts\":[]}"})
            check(post(s,bad)==400,"invalid body rejected");
        check(post(s,body("unknown",true))==404,"unknown provider");
        providers.put(new JSONObject().put("id","alpha"));
        check(post(s,body("alpha",true))==409,"duplicate provider");
        providers.remove(providers.length()-1);
        NanoHTTPD.Response configResponse = s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null));
        JSONObject snapshot = new JSONObject(new String(configResponse.getData().readAllBytes(),StandardCharsets.UTF_8));
        String token = snapshot.getJSONObject("providerStateTokens").getString("alpha");
        check(!cfg.has("providerStateTokens") && !token.contains("fixture"),"tokens are opaque and do not pollute config");
        check(snapshot.getJSONArray("providers").getJSONObject(0).toString().equals(ConfigurationView.redact(cfg, GatewayManagementCredential.get()).getJSONArray("providers").getJSONObject(0).toString()) && !snapshot.toString().contains("fixture-token") && !snapshot.toString().contains("fixture-key"),"snapshot matches redacted provider");
        for (Object invalid : new Object[]{"", " ", 1, JSONObject.NULL})
            check(post(s,new JSONObject(body("alpha",true)).put("expectedStateToken",invalid).toString())==400,"invalid token");
        String conditional = new JSONObject(body("alpha",true)).put("expectedStateToken",token).toString();
        NanoHTTPD.Response first = s.serve(RoutingSetupOfflineTest.request("127.0.0.1",PATH,"POST",conditional,null));
        check(first.getStatus().getRequestStatus()==200,"conditional write");
        JSONObject firstBody = new JSONObject(new String(first.getData().readAllBytes(),StandardCharsets.UTF_8));
        check(!firstBody.getString("stateToken").equals(token),"write advances token");
        check(post(s,conditional)==409 && a.getBoolean("enabled"),"stale request cannot write");
        JSONObject diskAfterConditional = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
        check(!diskAfterConditional.has("providerStateTokens"),"tokens not persisted");
        NanoHTTPD.Response refreshed = s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null));
        JSONObject refreshedBody = new JSONObject(new String(refreshed.getData().readAllBytes(),StandardCharsets.UTF_8));
        check(firstBody.getString("stateToken").equals(refreshedBody.getJSONObject("providerStateTokens").getString("alpha")),"response and next snapshot agree");
        check(status(s,"192.0.2.1","/api/config","GET","",null)==401,"config authentication still enforced");
        // Both conditional writes come from one snapshot and must not invalidate each other.
        JSONObject bothTokens = refreshedBody.getJSONObject("providerStateTokens");
        String alphaBefore = bothTokens.getString("alpha");
        String betaBefore = bothTokens.getString("beta");
        ExecutorService tokenExecutor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> alphaWrite = tokenExecutor.submit(() -> { start.await(); return post(s,new JSONObject(body("alpha",false)).put("expectedStateToken",alphaBefore).toString()); });
            Future<Integer> betaWrite = tokenExecutor.submit(() -> { start.await(); return post(s,new JSONObject(body("beta",true)).put("expectedStateToken",betaBefore).toString()); });
            start.countDown();
            check(alphaWrite.get()==200 && betaWrite.get()==200,"different providers from same snapshot both succeed");
        } finally { tokenExecutor.shutdownNow(); }
        check(post(s,body("alpha",true))==200,"return alpha to earlier value");
        check(post(s,new JSONObject(body("alpha",false)).put("expectedStateToken",alphaBefore).toString())==409
                && a.getBoolean("enabled"),"old token rejected after provider returns to original value");
        account.put("token","fixture-refreshed");
        NanoHTTPD.Response response = s.serve(RoutingSetupOfflineTest.request("127.0.0.1",PATH,"POST",body("alpha",false),null));
        check(response.getStatus().getRequestStatus()==200,"success");
        JSONObject result = new JSONObject(new String(response.getData().readAllBytes(),StandardCharsets.UTF_8));
        check(result.length()==4 && result.getBoolean("ok") && result.getString("providerId").equals("alpha") && !result.getBoolean("enabled"),"safe actual response");
        check(a.getJSONArray("accounts").getJSONObject(0)==account && a.getJSONArray("keys").getJSONObject(0)==key,"nested identities retained");
        JSONObject saved = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
        check(saved.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token").equals("fixture-refreshed"),"refreshed account preserved on disk");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i=0;i<20;i++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> x = executor.submit(() -> { start.await(); return post(s,body("alpha",true)); });
                Future<Integer> y = executor.submit(() -> { start.await(); return post(s,body("beta",true)); });
                start.countDown(); check(x.get()==200 && y.get()==200,"concurrent different provider");
                saved = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
                check(saved.getJSONArray("providers").getJSONObject(0).getBoolean("enabled") && saved.getJSONArray("providers").getJSONObject(1).getBoolean("enabled"),"both changes persisted");
                CountDownLatch same = new CountDownLatch(1);
                Future<Integer> on = executor.submit(() -> { same.await(); return post(s,body("alpha",true)); });
                Future<Integer> off = executor.submit(() -> { same.await(); return post(s,body("alpha",false)); });
                same.countDown(); check(on.get()==200 && off.get()==200,"concurrent same provider");
                saved = new JSONObject(new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8));
                check(saved.getJSONArray("providers").getJSONObject(0).getBoolean("enabled")==a.getBoolean("enabled"),"same provider disk matches memory");
            }
        } finally { executor.shutdownNow(); }
        RoutingAuditOfflineTest.file(s,new File(new File(dir,"missing"),"config.json"));
        a.put("enabled",true);
        check(post(s,body("alpha",false))==500 && a.getBoolean("enabled"),"failure rolls back value");
        a.remove("enabled");
        check(post(s,body("alpha",false))==500 && !a.has("enabled"),"failure restores absence");
        check(status(s,"127.0.0.1","/backend-api/codex/responses","POST","{}","fixture-auth")==403,"disabled codex blocked");
        check(status(s,"127.0.0.1","/responses","POST","{\"model\":\"github-copilot/test\"}","fixture-auth")==403,"disabled copilot blocked");
        check(status(s,"127.0.0.1","/legacy/responses","POST","{}","fixture-auth")==404,"legacy alias is not registered");
        codex.put("enabled",true); copilot.put("enabled",true);
        check(status(s,"127.0.0.1","/backend-api/codex/responses","POST","{}","fixture-auth")==401,"disabled codex account blocked");
        check(status(s,"127.0.0.1","/responses","POST","{\"model\":\"github-copilot/test\"}","fixture-auth")==401,"disabled copilot account blocked");
        // A valid cached token ensures the wrong account-selection path would attempt
        // a connection to loopback instead of failing early during token refresh.
        JSONObject cpAccount = copilot.getJSONArray("accounts").getJSONObject(0);
        cpAccount.put("access", "fixture-access").put("expires", System.currentTimeMillis()+3600000L);
        copilot.put("baseUrl", "http://127.0.0.1:1").put("protocol", "copilot");
        check(status(s,"127.0.0.1","/responses","POST","{\"model\":\"github-copilot/test\"}","fixture-auth")==401,"legacy disabled account rejected before upstream");
        check(RoutingSetupOfflineTest.candidates(s,"github-copilot/test").isEmpty(),"ordinary copilot disabled account rejected");
        JSONObject miniAccount = new JSONObject().put("enabled",false).put("token","fixture-token").put("access","fixture-access").put("expires",System.currentTimeMillis()+3600000L);
        JSONObject mini = new JSONObject().put("id","minimax-code").put("protocol","minimax").put("accounts",new JSONArray().put(miniAccount));
        providers.put(mini);
        check(RoutingSetupOfflineTest.candidates(s,"minimax-code/test").isEmpty(),"ordinary minimax disabled account rejected");
        JSONObject disabled = new JSONObject().put("id","disabled-generic").put("enabled",false).put("keys",new JSONArray().put("fixture-a"));
        JSONObject available = new JSONObject().put("id","available-generic").put("enabled",true).put("baseUrl","http://127.0.0.1:1").put("keys",new JSONArray().put("fixture-b"));
        cfg.put("providers",new JSONArray().put(disabled).put(available));
        cfg.put("routing",new JSONObject().put("bareModelFallback",true));
        java.util.List<?> targets = RoutingSetupOfflineTest.candidates(s,"unknown-model");
        check(targets.size()==1,"bare fallback skips disabled first provider");
        Object target = targets.get(0);
        java.lang.reflect.Field providerId = target.getClass().getDeclaredField("providerId"); providerId.setAccessible(true);
        check("available-generic".equals(providerId.get(target)),"bare fallback selects enabled provider");
        available.put("enabled",false);
        java.lang.reflect.Method forward = GatewayServer.class.getDeclaredMethod("forwardAttempt",target.getClass(),JSONObject.class,boolean.class,String.class,PrivacyMasker.Session.class,ContextGuard.Config.class,ContextGuard.Result.class);
        forward.setAccessible(true);
        Object attempt = forward.invoke(s,target,new JSONObject(),false,"unknown-model",null,null,null);
        java.lang.reflect.Field code = attempt.getClass().getDeclaredField("status"); code.setAccessible(true);
        check(code.getInt(attempt)==403,"preselected disabled provider blocked before connection");
        System.out.println("ProviderEnabledOfflineTest OK");
    }
}
