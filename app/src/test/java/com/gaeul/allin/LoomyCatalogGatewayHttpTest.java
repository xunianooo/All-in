package com.gaeul.allin;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;

public final class LoomyCatalogGatewayHttpTest {
 static volatile String payload; static volatile int status=200; static volatile int calls;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static NanoHTTPD.Response call(GatewayServer g,String uri){return g.serve(RoutingSetupOfflineTest.request("127.0.0.1",uri,"GET","",null));}
 public static void main(String[] args)throws Exception {
  HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/api/v1/models",x->{try {calls++; check(x.getRequestMethod().equals("GET"),"method");check("session-fixture".equals(x.getRequestHeaders().getFirst("token")),"token");check("Bearer session-fixture".equals(x.getRequestHeaders().getFirst("Authorization")),"auth");byte[] b=payload.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);}finally{x.close();}});
  upstream.createContext("/api/v2/points/records",x->{try{byte[] b="{\"code\":\"000000\",\"data\":{\"balance\":100,\"dailyBalance\":30,\"dailyLimitPoints\":50,\"dailyRemainingPoints\":30}}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}finally{x.close();}});upstream.start();
  try {
   JSONObject a=new JSONObject().put("id","a").put("token","session-fixture").put("enabled",true);
   JSONObject p=new JSONObject().put("id","loomy").put("enabled",true).put("baseUrl","https://evil.invalid").put("accounts",new JSONArray().put(a)).put("models",new JSONArray().put("old")).put("modelMeta",new JSONObject().put("old",new JSONObject()));
   JSONObject config=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth")).put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(p));
   GatewayServer g=RoutingSetupOfflineTest.server(config);RoutingAuditOfflineTest.file(g,Files.createTempDirectory("loomy-catalog").resolve("config.json").toFile());String origin="http://127.0.0.1:"+upstream.getAddress().getPort();g.setLoomyForTests(new LoomyAuth(origin,origin,"key","secret"));
   for(String bad:new String[]{"{}","{\"data\":[]}","{\"code\":\"bad\",\"data\":[{\"id\":\"x\"}]}","{\"data\":[{\"id\":\"x\"},{}]}"}) {payload=bad;check(call(g,"/api/auth/loomy/sync-models").getStatus().getRequestStatus()>=400,"bad fails");check(ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).getJSONArray("models").getString(0).equals("old"),"old preserved");}
   payload="{\"data\":[{\"id\":\"new\",\"name\":\"Model (x3.0)\",\"capabilities\":{\"vision\":true}}]}";
   check(call(g,"/api/auth/loomy/sync-models").getStatus().getRequestStatus()==200,"sync success");JSONObject live=ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0);check(live.getJSONObject("modelMeta").getJSONObject("new").getJSONObject("billing").getDouble("value")==3,"rate");
   NanoHTTPD.Response bill=g.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth","loomy"));
   JSONObject billing=new JSONObject(ResponsesGatewayOfflineTest.read(bill.getData()));
   JSONObject allowance=billing.getJSONArray("accounts").getJSONObject(0).getJSONObject("allowance");
   check(allowance.getDouble("remaining")==100 && allowance.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==60,"wallet billing");
   check(billing.getJSONObject("models").getJSONObject("new").getJSONObject("billing").getDouble("value")==3,"billing rate");
   System.out.println("LoomyCatalogGatewayHttpTest passed, requests="+calls);
  } finally {upstream.stop(0);}
 }
}
