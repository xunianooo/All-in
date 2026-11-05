package com.gaeul.allin;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import fi.iki.elonen.NanoHTTPD;
public final class PatiencePolicyOfflineTest {
 static void check(boolean b,String m) { if(!b)throw new AssertionError(m); }
 static JSONObject read(GatewayServer s) throws Exception { return new JSONObject(new String(s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/resilience","GET","",null)).getData().readAllBytes(),"UTF-8")); }
 static int save(GatewayServer s,JSONObject settings,String token) { return s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/resilience","POST",new JSONObject().put("settings",settings).put("expectedStateToken",token).toString(),null)).getStatus().getRequestStatus(); }
 public static void main(String[]args) throws Exception {
  check(PatiencePolicy.parse(null).patienceMillis==0&&!PatiencePolicy.parse(null).heartbeat,"default off");
  for(String bad:new String[]{"{\"patienceMillis\":-1}","{\"patienceMillis\":60001}","{\"patienceMillis\":\"20\"}","{\"patienceMillis\":1.5}","{\"heartbeat\":1}","{\"unknown\":true}"}) try { PatiencePolicy.parse(new JSONObject(bad));throw new AssertionError(bad); } catch(IllegalArgumentException expected) { }
  check(PatiencePolicy.rejection("p",429,"{\"error\":{\"code\":\"rate_limit_error\"}}"),"explicit rate");
  check(PatiencePolicy.rejection("p",503,"{\"error\":{\"type\":\"overloaded_error\"}}"),"explicit overload");
  for(String code:new String[]{"user_model_rate_limited","insufficient_quota","authentication_error","random"}) check(!PatiencePolicy.rejection("raccoon",429,new JSONObject().put("error",new JSONObject().put("code",code)).toString()),"hard or unknown "+code);
  for(String[] v:new String[][] {{"429","rate_limit_error","authentication_error"},{"429","authentication_error","rate_limit_error"},{"429","rate_limit","other"},{"503","authentication_error","overloaded_error"},{"503","overloaded_error","insufficient_quota"},{"503","other","overloaded_error"}})
   check(!PatiencePolicy.rejection("p",Integer.parseInt(v[0]),new JSONObject().put("error",new JSONObject().put("code",v[1]).put("type",v[2])).toString()),"conflicting fields "+java.util.Arrays.toString(v));
  check(PatiencePolicy.rejection("p",429,"{\"error\":{\"code\":\"rate_limit\",\"type\":\"rate_limit_error\"}}"),"compatible rate fields");
  check(!PatiencePolicy.rejection("p",503,"{\"error\":{\"message\":\"service unavailable\"}}"),"generic503 unsafe");
  check(PatiencePolicy.retryDeadline(null,100)==60100 && PatiencePolicy.retryDeadline("1",100)==1100,"retry after fallback");
  check(PatiencePolicy.delayMillis(0,0,0)==2000 && PatiencePolicy.delayMillis(1,6000,0)==6000 && PatiencePolicy.delayMillis(2,0,20)==8020,"backoff");
  PatiencePolicy.Window w=new PatiencePolicy.Window(20000);check(w.remainingMillis(1000000000L,61000000000L)==20000,"window starts exhaustion");check(w.remainingMillis(11000000000L,61000000000L)==10000,"window countdown");
  Path dir=Files.createTempDirectory("patience-settings");JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth")).put("providers",new JSONArray()).put("network",new JSONObject().put("mode","direct")).put("outbound",new JSONObject());
  GatewayServer s=RoutingSetupOfflineTest.server(cfg);RoutingAuditOfflineTest.file(s,dir.resolve("config.json").toFile());
  JSONObject first=read(s),settings=new JSONObject().put("patienceMillis",20000).put("heartbeat",true);
  check(save(s,settings,first.getString("stateToken"))==200,"save");check(save(s,settings,first.getString("stateToken"))==409,"stale");
  JSONObject disk=new JSONObject(Files.readString(dir.resolve("config.json")));check(disk.has("network")&&disk.has("outbound")&&disk.getJSONObject("resilience").getBoolean("heartbeat"),"preserved");
  JSONObject now=read(s);RoutingAuditOfflineTest.file(s,dir.resolve("missing/config.json").toFile());check(save(s,new JSONObject(),now.getString("stateToken"))==500,"disk failure");check(read(s).getString("stateToken").equals(now.getString("stateToken")),"rollback");
  NetworkPolicy.configure(null);System.out.println("PatiencePolicyOfflineTest passed");
 }
}
