package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import fi.iki.elonen.NanoHTTPD;

public final class RequestCoordinationOfflineTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static JSONObject json(NanoHTTPD.Response r) throws Exception { return new JSONObject(new String(r.getData().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
    static NanoHTTPD.Response api(GatewayServer s,String method,String body) { return s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/outbound",method,body,null)); }
    public static void main(String[] args) throws Exception {
        RequestContext request=new RequestContext("chat",NetworkPolicy.capture(),1000);
        check(request.phase()==RequestContext.Phase.UNCOMMITTED && request.mayReplay(),"initial phase");
        AtomicInteger disconnected=new AtomicInteger(),cleaned=new AtomicInteger();
        HttpURLConnection fake=new HttpURLConnection(new URL("http://127.0.0.1/")) {
            public void connect() { } public void disconnect() { disconnected.incrementAndGet(); } public boolean usingProxy() { return false; }
        };
        request.attach(fake); request.onClose(cleaned::incrementAndGet); request.headersWritten(); check(!request.mayReplay(),"committed headers prohibit replay");
        request.businessWritten(); check(request.phase()==RequestContext.Phase.BUSINESS,"business phase"); request.cancel("test"); request.cancel("again");
        check(disconnected.get()==1 && cleaned.get()==1 && request.isCancelled() && request.phase()==RequestContext.Phase.TERMINAL,"idempotent cancel");
        request.onClose(cleaned::incrementAndGet); check(cleaned.get()==2,"late cleanup runs");
        JSONObject a=new JSONObject().put("id","account-private").put("token","first");
        String before=CredentialIdentity.credential("p","https://EXAMPLE.invalid/v1",null,a,"first"); a.put("token","refreshed");
        check(before.equals(CredentialIdentity.credential("p","https://example.invalid/v1",null,a,"refreshed")),"refresh stable");
        check(!before.equals(CredentialIdentity.credential("p","https://other.invalid/v1",null,a,"refreshed")),"origin isolation");
        JSONObject legacy=new JSONObject().put("token","old"); String id=CredentialIdentity.credential("p","u",null,legacy,"old");legacy.put("token","new");
        check(id.equals(CredentialIdentity.credential("p","u",null,legacy,"new")) && legacy.length()==1,"legacy stable without config mutation");
        String wall="{\"error\":{\"code\":\"user_model_rate_limited\",\"message\":\"limited\"}}";
        UpstreamFailure f=UpstreamFailure.classify("raccoon",429,wall,true,false);
        check(f.kind==UpstreamFailure.Kind.ACCOUNT_THROUGHPUT && f.fallback && !f.sameCredentialReplay,"raccoon wall");
        check(UpstreamFailure.classify("raccoon",429,"{\"error\":{\"code\":\"rate_limit\"}}",true,false).kind==UpstreamFailure.Kind.RATE,"ordinary rate");
        check(UpstreamFailure.classify("p",429,"{\"error\":{\"code\":\"insufficient_quota\"}}",true,false).kind==UpstreamFailure.Kind.QUOTA,"quota");
        check(UpstreamFailure.classify("p",0,"",true,false).kind==UpstreamFailure.Kind.UNKNOWN_EXECUTION,"unknown sent outcome");
        check(!UpstreamFailure.classify("p",503,"",true,false).sameCredentialReplay,"503 not broad replay");
        check(!UpstreamFailure.classify("p",429,"",true,true).fallback,"business never fallback");
        check(UpstreamFailure.classify("p",407,"",false,false).kind==UpstreamFailure.Kind.PROXY,"proxy");
        Path dir=Files.createTempDirectory("outbound-settings"); File file=dir.resolve("config.json").toFile();
        JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(new JSONObject().put("id","alpha").put("keys",new JSONArray().put("fixture-key"))))
                .put("network",new JSONObject().put("mode","direct")).put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","usage")));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg); RoutingAuditOfflineTest.file(server,file);
        JSONObject state=json(api(server,"GET","")); check(state.getJSONObject("settings").length()==0,"default no limits");
        JSONObject settings=new JSONObject().put("alpha",new JSONObject().put("credential",new JSONObject().put("minIntervalMillis",250).put("rpm",15).put("maxInFlight",2).put("maxQueue",4).put("maxWaitMillis",500)));
        String body=new JSONObject().put("settings",settings).put("expectedStateToken",state.getString("stateToken")).toString();
        check(api(server,"POST",body).getStatus().getRequestStatus()==200,"save settings");
        check(api(server,"POST",body).getStatus().getRequestStatus()==409,"stale CAS");
        JSONObject saved=new JSONObject(Files.readString(file.toPath()));
        check(saved.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getString(0).equals("fixture-key") && saved.getJSONObject("network").getString("mode").equals("direct") && saved.getJSONArray("groups").getJSONObject(0).getString("strategy").equals("usage"),"independent section preservation");
        JSONObject current=json(api(server,"GET",""));
        for(String invalid:new String[]{"{\"rpm\":\"15\"}","{\"rpm\":-1}","{\"rpm\":1.5}","{\"rpm\":10001}","{\"bogus\":1}"}) {
            String bad=new JSONObject().put("settings",new JSONObject().put("alpha",new JSONObject().put("credential",new JSONObject(invalid)))).put("expectedStateToken",current.getString("stateToken")).toString();
            check(api(server,"POST",bad).getStatus().getRequestStatus()==400,"strict invalid "+invalid);
        }
        RoutingAuditOfflineTest.file(server,new File(dir.resolve("missing").toFile(),"config.json"));
        body=new JSONObject().put("settings",new JSONObject()).put("expectedStateToken",current.getString("stateToken")).toString();
        check(api(server,"POST",body).getStatus().getRequestStatus()==500,"save failure");
        check(json(api(server,"GET","")).getString("stateToken").equals(current.getString("stateToken")),"failed save no token drift");
        NetworkPolicy.configure(null);
        System.out.println("RequestCoordinationOfflineTest passed");
    }
}
