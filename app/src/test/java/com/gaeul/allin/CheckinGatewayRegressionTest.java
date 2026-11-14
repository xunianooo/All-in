package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CheckinGatewayRegressionTest {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static NanoHTTPD.Response call(GatewayServer s,String uri,String method,String body,String key){return s.serve(RoutingSetupOfflineTest.request("127.0.0.1",uri,method,body,key));}
    static JSONObject result(GatewayServer s,String uri,String method,String body)throws Exception{return new JSONObject(ResponsesGatewayOfflineTest.read(call(s,uri,method,body,null).getData()));}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("checkin-gateway-");AtomicInteger mutations=new AtomicInteger();
        JSONObject account=WorkBuddyAllowanceRegressionTest.account("one");JSONObject provider=new JSONObject().put("id","workbuddy").put("enabled",true).put("accounts",new JSONArray().put(account)).put("models",new JSONArray());
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-inference")).put("providers",new JSONArray().put(provider));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);ResponsesGatewayOfflineTest.set(server,"configFile",dir.resolve("config.json").toFile());
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        server.setCheckinTransportForTests((p,a)->{mutations.incrementAndGet();started.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test did not release");return new JSONObject().put("outcome","claimed").put("verified",true).put("credit",30);});
        try {
            check(call(server,"/api/checkin/status","GET","","fixture-inference").getStatus().getRequestStatus()==401,"inference key cannot read management");
            JSONObject view=result(server,"/api/checkin/status","GET","");check(view.getBoolean("autoEnabled")&&view.getInt("supported")==1,"default auto and eligible account");check(mutations.get()==0,"status read is not a mutation");check(!view.toString().contains("fixture-one")&&!view.toString().contains("refresh-one"),"status no credentials");
            check(call(server,"/api/checkin/run","GET","",null).getStatus().getRequestStatus()==405,"GET never claims");
            check(call(server,"/api/checkin/run","POST","{\"url\":\"https://attacker.test\"}",null).getStatus().getRequestStatus()==400,"caller cannot inject upstream URL");
            check(call(server,"/api/checkin/run","POST","{\"provider\":\"workbuddy\",\"accountId\":\"missing\"}",null).getStatus().getRequestStatus()==400,"valid selected account required");
            view=result(server,"/api/checkin/settings","POST","{\"autoEnabled\":false}");check(!view.getBoolean("autoEnabled")&&mutations.get()==0,"toggle only saves the flag");
            check(call(server,"/api/checkin/settings","POST","{\"autoEnabled\":\"true\"}",null).getStatus().getRequestStatus()==400,"boolean setting validated");
            JSONObject accepted=result(server,"/api/checkin/run","POST","{\"provider\":\"workbuddy\",\"accountId\":\"one\"}");check(accepted.getBoolean("accepted"),"explicit manual job accepted while auto off");check(started.await(3,TimeUnit.SECONDS),"manual job started");check(!result(server,"/api/checkin/run","POST","{}").getBoolean("accepted"),"one queued batch only");
            // A stale general settings save cannot erase the write-ahead marker or auto-off flag.
            JSONObject stale=result(server,"/api/config","GET","");stale.remove("checkin");
            JSONObject saved=result(server,"/api/config","POST",stale.toString());check(saved.getBoolean("ok"),"unrelated configuration can save");
            JSONObject during=result(server,"/api/checkin/status","GET","");check(!during.getBoolean("autoEnabled"),"general config cannot resurrect auto");check(during.getJSONArray("accounts").getJSONObject(0).getString("outcome").equals("running"),"general config retains pending intent");
            release.countDown();long until=System.nanoTime()+3000000000L;
            do {view=result(server,"/api/checkin/status","GET","");if(!view.getBoolean("running"))break;Thread.sleep(10);}while(System.nanoTime()<until);
            check(!view.getBoolean("running")&&view.getInt("completed")==1,"actual per-account result published");check(view.getJSONArray("accounts").getJSONObject(0).getInt("credit")==30,"known reward displayed");check(!view.getBoolean("autoEnabled"),"result commit preserves concurrent settings");
            result(server,"/api/checkin/run","POST","{}");Thread.sleep(50);check(mutations.get()==1,"daily legacy/manual batch deduplication");
            JSONObject disk=new JSONObject(Files.readString(dir.resolve("config.json")));check(disk.getJSONObject("checkin").getJSONObject("records").length()==1,"durable daily record");
            check(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("access").equals("fixture-one"),"no account credential mutation");
        } finally {release.countDown();server.stop();Files.deleteIfExists(dir.resolve("config.json"));Files.deleteIfExists(dir);}
        System.out.println("CheckinGatewayRegressionTest PASS: "+checks+" checks");
    }
}
