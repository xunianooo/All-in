package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real account-add/persistence/selection methods; all identities and credentials are synthetic. */
public final class MultiAccountGatewayOfflineTest {
    private static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static JSONObject config(JSONArray providers){return new JSONObject().put("setup",new JSONObject().put("done",true))
            .put("server",new JSONObject().put("authKey","fixture-inference"))
            .put("privacy",new JSONObject().put("enabled",false)).put("providers",providers);}
    static GatewayServer gateway(JSONObject config,Path file) throws Exception {
        AtomicConfigFile.write(file.toFile(),config.toString());
        GatewayServer gateway=RoutingSetupOfflineTest.server(new JSONObject(config.toString()));
        RoutingAuditOfflineTest.file(gateway,file.toFile());return gateway;
    }
    static Object invoke(GatewayServer gateway,String method,Class<?>[] types,Object... args)throws Exception {
        Method call=GatewayServer.class.getDeclaredMethod(method,types);call.setAccessible(true);
        try{return call.invoke(gateway,args);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;throw wrapped;}
    }
    static JSONObject live(GatewayServer gateway)throws Exception{return ResponsesGatewayOfflineTest.config(gateway);}
    static Object target(GatewayServer gateway,String model)throws Exception{return invoke(gateway,"buildTarget",new Class[]{String.class},model);}
    static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static JSONObject post(GatewayServer gateway,String path,JSONObject input)throws Exception {
        NanoHTTPD.Response response=gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1",path,"POST",input.toString(),null));
        try {JSONObject result=new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));result.put("_http",response.getStatus().getRequestStatus());return result;}
        finally{response.close();}
    }
    static JSONObject account(String id,String uid,String token){return new JSONObject().put("id",id).put("uid",uid).put("name","相同昵称")
            .put("access",token).put("token",token).put("refresh","refresh-"+id).put("enabled",true).put("expires",System.currentTimeMillis()+3600000);}
    static void clean(Path directory)throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException error){throw new UncheckedIOException(error);}});}}
    private static void identities()throws Exception {
        JSONObject provider=new JSONObject().put("id","workbuddy").put("accounts",new JSONArray()).put("keys",new JSONArray());
        JSONObject a=account("a","one","first").put("domain","copilot.tencent.com").put("addedAt",123L);
        String first=AccountPoolPolicy.upsert(provider,a,"workbuddy").getString("id");
        AccountPoolPolicy.upsert(provider,account("b","two","second").put("domain","copilot.tencent.com"),"workbuddy");
        check(provider.getJSONArray("accounts").length()==2,"Different verified owners sharing a nickname coexist");
        JSONObject relog=AccountPoolPolicy.upsert(provider,account("incoming","one","rotated").put("domain","copilot.tencent.com"),"workbuddy");
        check(first.equals(relog.getString("id")) && relog.getLong("addedAt")==123L,"Relogin preserves slot id and original addedAt");
        check(provider.getJSONArray("accounts").length()==2 && !provider.getJSONArray("keys").toString().contains("first"),"Relogin updates rather than replaces pool; stale mirrored key removed");
        AccountPoolPolicy.upsert(provider,account("c","one","team").put("domain","copilot.tencent.com").put("enterpriseId","team"),"workbuddy");
        check(provider.getJSONArray("accounts").length()==3,"Same uid personal and enterprise are separate accounts");
        check(!AccountPoolPolicy.same(new JSONObject().put("site","zai").put("email","same@example.invalid"),new JSONObject().put("site","bigmodel").put("email","same@example.invalid"),"zcode"),"Site boundary retained");
        check(!AccountPoolPolicy.same(new JSONObject().put("name","nickname"),new JSONObject().put("name","nickname"),"workbuddy"),"Unknown owners never merge by display name");
        JSONArray duplicate=new JSONArray().put(account("same-id","one","x"));
        JSONObject added=AccountPoolPolicy.addOrUpdate(duplicate,account("same-id","two","y"),"workbuddy");
        check(!"same-id".equals(added.getString("id")) && duplicate.length()==2,"Incoming id collision cannot overwrite an existing account");
        Set<String> ids=new HashSet<>();for(int i=0;i<1000;i++)ids.add(AccountPoolPolicy.newId("account_"));check(ids.size()==1000,"No millisecond id collisions");
    }
    private static void persistedPool(Path root)throws Exception {
        JSONObject provider=new JSONObject().put("id","workbuddy").put("protocol","openai").put("baseUrl","https://copilot.tencent.com/v2")
                .put("models",new JSONArray().put("m")).put("accounts",new JSONArray()).put("keys",new JSONArray());
        Path file=root.resolve("pool.json");GatewayServer gateway=gateway(config(new JSONArray().put(provider)),file);
        JSONObject first=post(gateway,"/api/auth/workbuddy/add",new JSONObject().put("name","同名").put("token","fixture-A"));
        JSONObject second=post(gateway,"/api/auth/workbuddy/add",new JSONObject().put("name","同名").put("token","fixture-B"));
        check(first.optBoolean("ok") && second.optBoolean("ok") && !first.getString("id").equals(second.getString("id")),"Production HTTP add confirms two distinct same-name slots");
        check(new JSONObject(new String(Files.readAllBytes(file),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==2,"Both accounts are on disk");
        GatewayServer restarted=gateway(new JSONObject(new String(Files.readAllBytes(file),"UTF-8")),root.resolve("restart.json"));
        Map<String,Integer> seen=new HashMap<>();for(int i=0;i<20;i++){String token=(String)field(target(restarted,"workbuddy/m"),"apiKey");seen.put(token,seen.getOrDefault(token,0)+1);}
        check(seen.size()==2 && seen.get("fixture-A")==10 && seen.get("fixture-B")==10,"Restarted pool actually rotates both accounts fairly");
        JSONObject failed;
        GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("injected storage failure");});
        try{failed=post(gateway,"/api/auth/workbuddy/add",new JSONObject().put("name","third").put("token","fixture-C"));}
        finally{GatewayServer.setConfigWriteHookForTests(null);}
        check(!failed.optBoolean("ok"),"Write failure never returns successful add");
        check(live(gateway).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==2,"Failed staged add leaves published pool unchanged");
        check(new JSONObject(new String(Files.readAllBytes(file),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==2,"Failed add leaves disk unchanged");
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<JSONObject>> calls=new ArrayList<>();
            for(int i=0;i<2;i++){final int slot=i;calls.add(executor.submit(()->post(gateway,"/api/auth/workbuddy/add",new JSONObject().put("name","同名").put("token","fixture-parallel-"+slot))));}
            for(Future<JSONObject> call:calls)check(call.get(10,TimeUnit.SECONDS).optBoolean("ok"),"Concurrent add confirmed");
        }finally{executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS),"Concurrent fixture terminated");}
        check(live(gateway).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==4,"Concurrent writes do not lose an existing or new account");
    }
    private static void providerRotation(Path root)throws Exception {
        String[][] providers={{"workbuddy","openai"},{"workbuddy-ai","openai"},{"zcode","openai"},{"codex","codex"},{"custom","openai"},
                {"catpaw","catpaw"},{"antigravity","antigravity"},{"qoder_cn","qoder"},{"trae-cn","trae"},{"loomy","openai"},
                {"raccoon","openai"},{"cline","cline"},{"github-copilot","copilot"},{"minimax-code","minimax"}};
        for(String[] entry:providers) {
            JSONArray accounts=new JSONArray();
            for(int i=0;i<2;i++)accounts.put(account(entry[0]+i,"uid-"+i,"token-"+entry[0]+i).put("type","api").put("source","browser")
                    .put("expiresAt",System.currentTimeMillis()+3600000).put("models",new JSONArray().put("m"))
                    .put("modelMeta",new JSONObject().put("m",new JSONObject().put("key","m"))));
            JSONObject p=new JSONObject().put("id",entry[0]).put("protocol",entry[1]).put("baseUrl","https://example.invalid/v1")
                    .put("models",new JSONArray().put("m")).put("accounts",accounts).put("keys",new JSONArray());
            GatewayServer gateway=gateway(config(new JSONArray().put(p)),root.resolve(entry[0]+".json"));
            Set<String> observed=new HashSet<>();for(int i=0;i<6;i++){Object selected=target(gateway,entry[0]+"/m");check(selected!=null,"Usable synthetic route "+entry[0]);observed.add(((JSONObject)field(selected,"account")).getString("id"));}
            check(observed.size()==2,"Both provider accounts selected: "+entry[0]);
            live(gateway).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("enabled",false);
            for(int i=0;i<3;i++){Object selected=target(gateway,entry[0]+"/m");check(selected!=null && ((JSONObject)field(selected,"account")).getString("id").endsWith("1"),"Disabled account never re-enters selection: "+entry[0]);}
        }
    }
    private static void independentCounters(Path root)throws Exception {
        JSONArray providers=new JSONArray();for(String id:new String[]{"workbuddy","workbuddy-ai"})providers.put(new JSONObject().put("id",id).put("protocol","openai")
                .put("accounts",new JSONArray().put(account(id+"0","u0",id+"-0")).put(account(id+"1","u1",id+"-1"))).put("keys",new JSONArray()));
        GatewayServer gateway=gateway(config(providers),root.resolve("independent.json"));
        Map<String,Set<String>> seen=new HashMap<>();
        for(int i=0;i<8;i++)for(String id:new String[]{"workbuddy","workbuddy-ai"})seen.computeIfAbsent(id,k->new HashSet<>()).add((String)field(target(gateway,id+"/m"),"apiKey"));
        check(seen.get("workbuddy").size()==2 && seen.get("workbuddy-ai").size()==2,"Interleaved providers do not steal each other's rotation counters");
        List<?> candidates=RoutingSetupOfflineTest.candidates(gateway,"workbuddy/m");check(candidates.size()==2,"Same-provider fallback has both account candidates");
        Class<?> type=candidates.get(0).getClass();invoke(gateway,"rest",new Class[]{type,int.class},candidates.get(0),429);
        check((Boolean)invoke(gateway,"isResting",new Class[]{type},candidates.get(0)),"Rejected account rests");
        check(!(Boolean)invoke(gateway,"isResting",new Class[]{type},candidates.get(1)),"Other account is not cooled by the rejected account");
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("allin-multi-account-");NetworkPolicy.block();
        try(NetworkPolicy.Scope ignored=NetworkPolicy.use(NetworkPolicy.capture())) {
            identities();persistedPool(root);providerRotation(root);independentCounters(root);
            check(IngressMemoryBudget.usedBytes()==0,"Account requests release ingress budget");
            System.out.println("MultiAccountGatewayOfflineTest PASS checks="+checks);
        }finally{GatewayServer.setConfigWriteHookForTests(null);NetworkPolicy.configure(null);clean(root);}
    }
}
