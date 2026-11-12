package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Official login dataflow, per-account catalog/refresh and actual local HTTP account rotation. */
public final class WorkBuddyMultiAccountRegressionTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static JSONObject oauth(String id,String token,String enterprise,long expires) {
        JSONObject out=MultiAccountGatewayOfflineTest.account(id,"verified-user",token).put("type","oauth")
                .put("domain","copilot.tencent.com").put("expires",expires).put("refreshExpiresAt",System.currentTimeMillis()+86400000);
        if(!enterprise.isEmpty())out.put("enterpriseId",enterprise);return out;
    }
    private static JSONObject provider(String id,JSONArray accounts,String model){return new JSONObject().put("id",id).put("protocol","openai")
            .put("baseUrl",WorkBuddyAuth.origin(id)+"/v2").put("accounts",accounts).put("keys",new JSONArray()).put("models",new JSONArray().put(model));}
    private static JSONObject ask(GatewayServer gateway,String path,Map<String,List<String>> params)throws Exception {
        NanoHTTPD.IHTTPSession base=RoutingSetupOfflineTest.request("127.0.0.1",path,"GET","",null);
        NanoHTTPD.IHTTPSession session=(NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(WorkBuddyMultiAccountRegressionTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->m.getName().equals("getParameters")?params:m.invoke(base,a));
        NanoHTTPD.Response response=gateway.serve(session);
        try{return new JSONObject(ResponsesGatewayOfflineTest.read(response.getData())).put("_http",response.getStatus().getRequestStatus());}
        finally{response.close();}
    }
    private static Map<String,List<String>> query(String... pairs){Map<String,List<String>> result=new HashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],Collections.singletonList(pairs[i+1]));return result;}
    private static final class Official extends HttpURLConnection {
        final FakeApi api;boolean disconnected,seen;final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Official(String url,FakeApi api)throws Exception{super(new URL(url));this.api=api;}
        public void connect(){throw new AssertionError("Real provider connections are forbidden");}
        public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
        public OutputStream getOutputStream(){return sent;}
        public int getResponseCode(){if(!seen){seen=true;api.calls.add(this);}return 200;}
        public InputStream getInputStream(){return new ByteArrayInputStream(api.answer(this).toString().getBytes(StandardCharsets.UTF_8));}
    }
    private static final class FakeApi {
        int states;final List<Official> calls=new ArrayList<>();
        JSONObject answer(Official connection) {
            String path=connection.getURL().getPath(), q=connection.getURL().getQuery();JSONObject data=new JSONObject();
            if(path.endsWith("/auth/state")){int n=++states;data.put("state","state-"+n).put("authUrl","https://copilot.tencent.com/login?state=state-"+n);}
            else if(path.endsWith("/auth/token")){boolean team=q.contains("state-2");data.put("accessToken",team?"fixture-team":"fixture-personal").put("refreshToken",team?"refresh-team":"refresh-personal").put("expiresIn",3600).put("refreshExpiresIn",86400).put("domain","copilot.tencent.com").put("tokenType","Bearer");}
            else if(path.endsWith("/login/account")){boolean team=q.contains("state-2");data.put("uid","verified-user").put("nickname","同名用户").put("type",team?"enterprise":"personal");if(team)data.put("enterpriseId","team-1").put("enterpriseName","测试团队");}
            else if(path.equals("/v3/config")){String own="team-1".equals(connection.getRequestProperty("X-Enterprise-Id"))?"team-only":"personal-only";data.put("agents",new JSONArray().put(new JSONObject().put("name","cli").put("models",new JSONArray().put("shared").put(own))))
                    .put("models",new JSONArray().put(new JSONObject().put("id","shared").put("credits","x1")).put(new JSONObject().put("id",own).put("credits","x2")));}
            else throw new AssertionError("Unexpected official fixture path: "+path);
            return new JSONObject().put("code",0).put("data",data);
        }
        long count(String path){return calls.stream().filter(c->c.getURL().getPath().equals(path)).count();}
    }
    private static void loginAndCatalog(Path root)throws Exception {
        Path file=root.resolve("login.json");GatewayServer gateway=MultiAccountGatewayOfflineTest.gateway(MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider("workbuddy",new JSONArray(),"shared"))),file);
        FakeApi api=new FakeApi();gateway.setVendorConnectionsForTests(url->{try{return new Official(url,api);}catch(Exception e){throw new IOException(e);}});
        String firstId=null;
        for(int i=1;i<=2;i++) {
            JSONObject init=ask(gateway,"/api/auth/workbuddy/init-login",Collections.emptyMap());check(init.optBoolean("ok"),"Official login initialized");
            check(init.getString("authUrl").contains("version=5.9.3") && init.getString("authUrl").contains("loginSessionId="),"Official platform/version and unique browser session used");
            JSONObject saved=ask(gateway,"/api/auth/workbuddy/poll-login",query("state",init.getString("state")));
            check(saved.optBoolean("ok") && saved.has("id") && !saved.has("token"),"Login returns only a confirmed id, never raw credential");
            if(i==1)firstId=saved.getString("id");else check(!firstId.equals(saved.getString("id")),"Personal and enterprise uid do not overwrite each other");
            long prior=api.count("/v2/plugin/auth/token");JSONObject repeated=ask(gateway,"/api/auth/workbuddy/poll-login",query("state",init.getString("state")));
            check(saved.getString("id").equals(repeated.getString("id")) && api.count("/v2/plugin/auth/token")==prior,"Repeated poll reuses committed outcome without fetching/spending credentials again");
        }
        JSONObject disk=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));JSONArray accounts=disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check(accounts.length()==2,"Two verified accounts survive serialization/restart");
        for(int i=0;i<2;i++){JSONObject account=accounts.getJSONObject(i);check(account.has("refresh") && account.getLong("expires")>System.currentTimeMillis() && account.getLong("refreshExpiresAt")>account.getLong("expires"),"OAuth refresh and both expiry clocks persisted");check(account.getString("uid").equals("verified-user") && account.getString("domain").equals("copilot.tencent.com"),"Verified uid and domain persisted");}
        JSONObject synced=MultiAccountGatewayOfflineTest.post(gateway,"/api/auth/workbuddy/sync-models",new JSONObject());check(synced.optBoolean("ok"),"Scoped model catalog synchronizes");
        accounts=MultiAccountGatewayOfflineTest.live(gateway).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check(accounts.getJSONObject(0).getJSONArray("models").toString().contains("personal-only") && !accounts.getJSONObject(0).getJSONArray("models").toString().contains("team-only"),"Personal model cache remains personal");
        check(accounts.getJSONObject(1).getJSONArray("models").toString().contains("team-only") && !accounts.getJSONObject(1).getJSONArray("models").toString().contains("personal-only"),"Enterprise model cache remains enterprise");
        for(Official call:api.calls)if(call.getURL().getPath().equals("/v3/config")) {
            boolean team="Bearer fixture-team".equals(call.getRequestProperty("Authorization"));
            check(team?"team-1".equals(call.getRequestProperty("X-Enterprise-Id")) && "team-1".equals(call.getRequestProperty("X-Tenant-Id")):call.getRequestProperty("X-Enterprise-Id")==null && call.getRequestProperty("X-Tenant-Id")==null,"Actual catalog transport has exactly the selected enterprise scope");
            check(call.disconnected && !call.getInstanceFollowRedirects(),"Catalog credentials cannot follow a redirect");
        }
        int before=api.calls.size();JSONObject rejected=ask(gateway,"/api/auth/workbuddy/poll-login",query("state","state-1","baseUrl","https://attacker.invalid"));
        check(rejected.getInt("_http")==400 && api.calls.size()==before,"Untrusted login origin rejected before transport");
        String redacted=ask(gateway,"/api/config",Collections.emptyMap()).toString();
        check(!redacted.contains("fixture-personal") && !redacted.contains("refresh-personal") && !redacted.contains("refresh-team"),"Configuration view redacts saved OAuth tokens");
    }
    private static JSONObject ensure(GatewayServer gateway,JSONObject account)throws Exception {
        return (JSONObject)MultiAccountGatewayOfflineTest.invoke(gateway,"ensureStoredToken",new Class[]{String.class,JSONObject.class,String.class},"workbuddy",account,null);
    }
    private static void refresh(Path root)throws Exception {
        JSONObject expired=oauth("refresh-A","old-access","team-1",System.currentTimeMillis()-1);
        JSONObject other=oauth("refresh-B","other-access","other-team",System.currentTimeMillis()+3600000);
        Path file=root.resolve("refresh.json");GatewayServer gateway=MultiAccountGatewayOfflineTest.gateway(MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider("workbuddy",new JSONArray().put(expired).put(other),"shared"))),file);
        AtomicInteger calls=new AtomicInteger();gateway.setTokenRefreshForTests((snapshot,force)->WorkBuddyAuth.refreshCandidate("workbuddy",snapshot,(id,account)->{
            calls.incrementAndGet();Thread.sleep(30);return new JSONObject().put("accessToken","new-access").put("refreshToken","new-refresh").put("expiresIn",3600).put("refreshExpiresIn",86400);
        }));
        ExecutorService workers=Executors.newFixedThreadPool(4);
        try {List<Future<JSONObject>> answers=new ArrayList<>();for(int i=0;i<4;i++)answers.add(workers.submit(()->ensure(gateway,expired)));
            for(Future<JSONObject> answer:answers){JSONObject result=answer.get(10,TimeUnit.SECONDS);check(result.getString("token").equals("new-access") && result.getString("enterpriseId").equals("team-1"),"Concurrent refresh retains enterprise identity");}
        }finally{workers.shutdownNow();check(workers.awaitTermination(5,TimeUnit.SECONDS),"Refresh workers stopped");}
        check(calls.get()==1,"Expired account refreshed exactly once across concurrent callers");
        JSONArray persisted=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check("new-refresh".equals(persisted.getJSONObject(0).getString("refresh")) && "other-access".equals(persisted.getJSONObject(1).getString("token")),"Refresh persists only its own account");
        JSONObject staticKey=oauth("static","static-key","",1).put("type","api");ensure(gateway,staticKey);check(calls.get()==1,"Static API key never refreshes");
        for(boolean saveFailure:new boolean[]{false,true}) {
            Path path=root.resolve(saveFailure?"write-fail.json":"unknown.json");JSONObject lost=oauth("lost-"+saveFailure,"lost-access","",1);
            GatewayServer blocked=MultiAccountGatewayOfflineTest.gateway(MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider("workbuddy",new JSONArray().put(lost),"shared"))),path);
            AtomicInteger spent=new AtomicInteger();blocked.setTokenRefreshForTests((snapshot,force)->{
                spent.incrementAndGet();if(!saveFailure)throw new WorkBuddyAuth.Failure("result unknown",false,0);
                ResponsesGatewayOfflineTest.set(blocked,"configFile",root.resolve("missing/config.json").toFile());
                return WorkBuddyAuth.merge(snapshot,new JSONObject().put("accessToken","uncommitted").put("refreshToken","spent-new").put("expiresIn",3600));
            });
            for(int i=0;i<2;i++){try{ensure(blocked,lost);throw new AssertionError("Unsafe refresh succeeded");}catch(IOException expected){checks++;}finally{ResponsesGatewayOfflineTest.set(blocked,"configFile",path.toFile());}}
            check(spent.get()==1,"Unknown result or post-exchange write failure is not replayed");
            check(new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token").equals("lost-access"),"Uncommitted credential never published on disk");
        }
    }
    private static void headersAndHttp()throws Exception {
        JSONObject enterprise=oauth("team","fixture-A","team-1",System.currentTimeMillis()+3600000);
        for(String provider:new String[]{"workbuddy","workbuddy-ai"}) {
            String url=WorkBuddyAuth.origin(provider)+"/v2/chat/completions";
            Map<String,String> head=WorkBuddyPolicy.headers(provider,url,"fixture-A",enterprise,"session","request");
            check("team-1".equals(head.get("X-Enterprise-Id")) && "team-1".equals(head.get("X-Tenant-Id")),"Both sites carry paired enterprise headers");
            check(WorkBuddyPolicy.headers(provider,"https://attacker.invalid/v2/chat/completions","fixture-A",enterprise,"session","request").isEmpty(),"Untrusted chat origin gets no credentials or identity");
            for(String invalid:new String[]{"\r\n","team\r\nInjected: yes","\u0001"}) {
                JSONObject bad=new JSONObject(enterprise.toString()).put("enterpriseId",invalid);
                try{WorkBuddyPolicy.headers(provider,url,"fixture-A",bad,"session","request");throw new AssertionError("Invalid identity accepted");}catch(IllegalArgumentException expected){checks++;}
            }
            JSONArray accounts=new JSONArray().put(enterprise).put(oauth("personal","fixture-B","",System.currentTimeMillis()+3600000));
            JSONObject p=provider(provider,accounts,"model");JSONObject cfg=VendorPolicyGatewayHttpTest.config(p);
            try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(cfg,upstream,false)) {
                for(int i=0;i<4;i++)check(VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body(provider+"/model",i%2==0),"session").status==200,"Actual local HTTP account request succeeds");
                check(upstream.requests.size()==4,"Exactly one physical send per normal request");
                for(int i=0;i<4;i++){Map<String,String> sent=upstream.requests.get(i).headers;check((i%2==0?"Bearer fixture-A":"Bearer fixture-B").equals(sent.get("Authorization")),"Actual outgoing credentials rotate, not just UI rows");check(i%2==0?"team-1".equals(sent.get("X-Enterprise-Id")) && "team-1".equals(sent.get("X-Tenant-Id")):sent.get("X-Enterprise-Id")==null && sent.get("X-Tenant-Id")==null,"No enterprise identity crosses accounts");}
            }
        }
        JSONObject cfg=VendorPolicyGatewayHttpTest.config(provider("workbuddy",new JSONArray().put(enterprise).put(oauth("spare","fixture-B","",System.currentTimeMillis()+3600000)),"model"));
        cfg.getJSONObject("failover").put("maxTargets",1);
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((r,n)->"Bearer fixture-A".equals(r.headers.get("Authorization"))?new VendorPolicyGatewayHttpTest.Reply(429,"application/json","{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"rate_limit_error\",\"message\":\"rate limit\"}}") :new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(cfg,upstream,false)) {
            VendorPolicyGatewayHttpTest.Result fallback=VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body("workbuddy/model",false));
            check(fallback.status==200,"A clear quota failure safely tries the other account; status="+fallback.status+" sends="+upstream.requests.size()+" body="+fallback.body);
            check(upstream.requests.size()==2,"Account fallback does not consume a second provider/model target budget");
            check(VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body("workbuddy/model",false)).status==200 && upstream.requests.size()==3,"Quota cooldown does not block the healthy account");
        }
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(429,"application/json","{\"error\":{\"message\":\"quota\"}}"));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(new JSONObject(cfg.toString()),upstream,false)) {
            check(VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body("workbuddy/model",false)).status==429 && upstream.requests.size()==1,"Ambiguous 429 without a structured rejection is not replayed on another account");
        }
        JSONObject nativeProvider=provider("workbuddy",new JSONArray().put(enterprise),"claude-opus-5.5");
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",WorkBuddyNativeGatewayHttpTest.BASE));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(VendorPolicyGatewayHttpTest.config(nativeProvider),upstream,false)) {
            check(VendorPolicyGatewayHttpTest.post(gateway,"/v1/messages",VendorPolicyGatewayHttpTest.body("workbuddy/claude-opus-5.5",false)).status==200,"Native Messages aggregation works");
            check(upstream.requests.size()==1 && "/v1/messages".equals(upstream.requests.get(0).path) && "team-1".equals(upstream.requests.get(0).headers.get("X-Enterprise-Id")) && "team-1".equals(upstream.requests.get(0).headers.get("X-Tenant-Id")),"Actual native transport carries selected enterprise identity");
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("workbuddy-multi-regression-");
        try {loginAndCatalog(root);refresh(root);headersAndHttp();check(IngressMemoryBudget.usedBytes()==0,"HTTP resources and body budgets released");System.out.println("WorkBuddyMultiAccountRegressionTest PASS checks="+checks);}
        finally{NetworkPolicy.configure(null);MultiAccountGatewayOfflineTest.clean(root);}
    }
}
