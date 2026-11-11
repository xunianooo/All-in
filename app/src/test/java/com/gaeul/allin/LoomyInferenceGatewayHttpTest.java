package com.gaeul.allin;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import fi.iki.elonen.NanoHTTPD;

public final class LoomyInferenceGatewayHttpTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  AtomicInteger calls=new AtomicInteger(); final JSONObject[] captured={null}; final String[] path={null},token={null},auth={null},content={null},accept={null};
  HttpServer up=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  up.createContext("/",x->{try{calls.incrementAndGet();path[0]=x.getRequestURI().getPath();token[0]=x.getRequestHeaders().getFirst("token");auth[0]=x.getRequestHeaders().getFirst("Authorization");content[0]=x.getRequestHeaders().getFirst("Content-Type");accept[0]=x.getRequestHeaders().getFirst("Accept");captured[0]=new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] b="{\"id\":\"x\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}finally{x.close();}});up.start();
  try {
   JSONObject p=new JSONObject().put("id","loomy").put("protocol","openai").put("baseUrl","https://loomyad.xunfei.cn/api/v1").put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("token","fixture-session").put("enabled",true))).put("models",new JSONArray().put("spark-x2.6"));
   JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth")).put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(p));
   GatewayServer g=RoutingSetupOfflineTest.server(cfg);RoutingAuditOfflineTest.file(g,Files.createTempDirectory("loomy-inference").resolve("config.json").toFile());g.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+up.getAddress().getPort()+new URL(url).getFile()));
   NanoHTTPD.Response catalog=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/gateway-access","GET","",null));
   check(ResponsesGatewayOfflineTest.read(catalog.getData()).contains("loomy/spark-x2.6"),"account inventory");
   NanoHTTPD.Response models=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/v1/models","GET","","fixture-auth"));
   check(ResponsesGatewayOfflineTest.read(models.getData()).contains("loomy/spark-x2.6"),"external catalog");
   JSONObject account=p.getJSONArray("accounts").getJSONObject(0);
   for (String field:new String[]{"enabled","off","expiresAt","token"}) {
    Object previous=account.opt(field);
    account.put(field,field.equals("enabled")?false:field.equals("off")?true:field.equals("expiresAt")?1:"");
    check(!GatewayAccessCatalog.build(cfg,System.currentTimeMillis(),null).toString().contains("loomy/spark-x2.6"),"blocked "+field);
    models=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/v1/models","GET","","fixture-auth"));
    check(!ResponsesGatewayOfflineTest.read(models.getData()).contains("loomy/spark-x2.6"),"external blocked "+field);
    if(previous==null) account.remove(field); else account.put(field,previous);
   }
   cfg.put("exposed",new JSONObject().put("enabled",true).put("items",new JSONArray()));
   check(!GatewayAccessCatalog.build(cfg,System.currentTimeMillis(),null).toString().contains("loomy/spark-x2.6"),"whitelist blocks");
   models=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/v1/models","GET","","fixture-auth"));
   check(!ResponsesGatewayOfflineTest.read(models.getData()).contains("loomy/spark-x2.6"),"external whitelist blocks");
   cfg.remove("exposed");
   NanoHTTPD.Response chat=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/v1/chat/completions","POST",new JSONObject().put("model","loomy/spark-x2.6").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi"))).toString(),"fixture-auth"));
   check(chat.getStatus().getRequestStatus()==200,"external chat"); check(calls.get()==1,"external sent once"); calls.set(0);
   NanoHTTPD.Response response=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/test-model","POST",new JSONObject().put("model","loomy/spark-x2.6").toString(),null));
   JSONObject result=new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));check(result.optBoolean("ok"),"probe "+result);check(calls.get()==1,"one send");check("/api/v1/chat/completions".equals(path[0]),"path");check("fixture-session".equals(token[0])&&"Bearer fixture-session".equals(auth[0]),"double auth");check("application/json;charset=utf-8".equals(content[0]),"charset");check("text/event-stream".equals(accept[0]),"accept");check("spark-x2.6".equals(captured[0].getString("model")),"model");
   java.lang.reflect.Method fold=GatewayServer.class.getDeclaredMethod("foldLoomyReasoning",JSONObject.class);fold.setAccessible(true);JSONObject obj=new JSONObject().put("reasoning_effort","minimal").put("reasoning",new JSONObject().put("effort","max"));fold.invoke(null,obj);check("low".equals(obj.getString("reasoning_effort"))&&"high".equals(obj.getJSONObject("reasoning").getString("effort")),"fold");obj.put("reasoning_effort","none");fold.invoke(null,obj);check(!obj.has("reasoning_effort"),"unknown removed");
   System.out.println("Loomy inference probe headers/body passed");
  } finally {up.stop(0);}
 }
}
