package com.gaeul.allin;
import org.json.*;
import fi.iki.elonen.NanoHTTPD;
public final class GatewayAccessHttpOfflineTest {
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 public static void main(String[] args)throws Exception {
  JSONObject p=new JSONObject().put("id","vendor").put("protocol","openai").put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("fake-key"));
  JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture")).put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p));
  GatewayServer s=RoutingSetupOfflineTest.server(cfg);
  check(RoutingSetupOfflineTest.status(s,"192.168.1.2","/api/gateway-access","GET","","fixture")==401,"inference key cannot manage");
  NanoHTTPD.Response r=s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/gateway-access","GET","",null));
  check(r.getStatus().getRequestStatus()==200 && "no-store".equals(r.getHeader("Cache-Control")),"status and no-store");
  JSONObject body=new JSONObject(new String(r.getData().readAllBytes(),"UTF-8"));
  check(body.getBoolean("ok")&&body.getJSONArray("models").length()==1&&!body.toString().contains("fake-key"),"safe body");
  p.put("enabled",false);
  r=s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/gateway-access","GET","",null));
  body=new JSONObject(new String(r.getData().readAllBytes(),"UTF-8"));
  check(body.getJSONArray("models").length()==0,"fresh configuration");
  check(RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/gateway-access","POST","",null)==405,"read only");
  System.out.println("GatewayAccessHttpOfflineTest PASS");
 }
}
