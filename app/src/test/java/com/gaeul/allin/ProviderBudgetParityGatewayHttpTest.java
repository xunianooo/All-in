package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.Files;

/** Final HTTP request bodies captured on loopback; synthetic providers/credentials only. */
public final class ProviderBudgetParityGatewayHttpTest {
 static int cases;
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 static JSONObject body(String path,String model){
  JSONObject b=new JSONObject().put("model",model).put("stream",false);
  if(path.endsWith("responses"))b.put("input","hello");
  else b.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
  return b;
 }
 static JSONObject run(String id,String protocol,String model,String path,JSONObject extra,int guard,boolean mutate,int status)throws Exception{
  String reply="openai".equals(protocol)?VendorPolicyGatewayHttpTest.CHAT:ZCodeGiftUsageHttpTest.json("full");
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"application/json",reply))){
   JSONObject p=VendorPolicyGatewayHttpTest.provider(id,protocol,"http://127.0.0.1:"+u.server.getLocalPort()+"/v1",model);
   // Conservative catalog must not mask the MiniMax endpoint's verified 512K limit.
   if("minimax".equals(protocol)) {
    p.put("modelMeta",new JSONObject().put(model,new JSONObject().put("maxOutput",128000)));
    p.put("accounts",new JSONArray().put(new JSONObject().put("id","synthetic-mini").put("access","synthetic-access").put("token","synthetic-access").put("refresh","synthetic-refresh").put("expires",System.currentTimeMillis()+3600000)));
   }
   JSONObject cfg=VendorPolicyGatewayHttpTest.config(p).put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",guard));
   if(mutate)cfg.put("middleware",new JSONObject().put("systemPrompt","fixture system"));
   JSONObject b=body(path,id+"/"+model);for(java.util.Iterator<String> it=extra.keys();it.hasNext();){String k=it.next();b.put(k,extra.opt(k));}
   try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)){
    VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(g,path,b);
    check(result.status==status,id+" "+path+" expected "+status+" got "+result.status+" "+result.body);
    check(u.requests.size()==(status==200?1:0),"dispatch count "+u.requests.size());cases++;
    return status==200?u.requests.get(0).body:new JSONObject();
   }
  }
 }
 static void gift(String path,int cap,int explicit,int expected)throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"application/json",ZCodeGiftUsageHttpTest.json("full")))) {
   ZCodeGift.resetBusyForTests();ZCodeGift.resetCooldownForTests();ZCodeGift.setFixtureBase("http://127.0.0.1:"+u.server.getLocalPort());
   JSONObject cfg=ZCodeGiftUsageHttpTest.config(u.server.getLocalPort()).put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",cap));
   ZCodeGiftUsageHttpTest.Gateway g=new ZCodeGiftUsageHttpTest.Gateway(cfg,new ZCodeGiftBusyGatewayHttpTest.Transport());
   g.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT,true);
   try {
    JSONObject b=body(path,ZCodeGiftUsageHttpTest.MODEL);if(explicit>0)b.put("max_tokens",explicit);
    java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection();
    c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("Authorization","Bearer fixture-gateway");c.setRequestProperty("Content-Type","application/json");
    try(java.io.OutputStream out=c.getOutputStream()){out.write(b.toString().getBytes("UTF-8"));}
    int status=c.getResponseCode();String response=ZCodeGiftUsageHttpTest.read(status>=400?c.getErrorStream():c.getInputStream());c.disconnect();
    check(status==200,"gift HTTP "+status+" "+response);check(u.requests.size()==1,"gift sent once");
    JSONObject sent=u.requests.get(0).body;check(sent.optInt("max_tokens",-1)==expected,"gift final body "+sent);check(!sent.has("thinking"),"gift has no invented reasoning");cases++;
   } finally {g.stop();ZCodeGift.setFixtureBase(null);ZCodeGift.resetBusyForTests();ZCodeGift.resetCooldownForTests();}
  }
 }
 static void truncation(String path)throws Exception {
  String stream=NativeAnthropicHttpTest.START+NativeAnthropicHttpTest.FIRST+NativeAnthropicHttpTest.stop(0)
   +NativeAnthropicHttpTest.event("message_delta",",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":1}")+NativeAnthropicHttpTest.event("message_stop","");
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",stream));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("zcode","anthropic","http://127.0.0.1:"+u.server.getLocalPort()+"/v1","GLM-5.3-Flash")),u,false)){
   JSONObject b=body(path,"zcode/GLM-5.3-Flash").put("stream",true);
   VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,path,b);
   check(r.status==200&&r.body.contains(ThinkingTruncationStream.NOTICE),"thinking truncation notice "+path+" "+r.body);
   String finish=path.endsWith("messages")?"end_turn":path.endsWith("responses")?"response.completed":"\"finish_reason\":\"stop\"";
   check(r.body.contains(finish),"compatibility finish preserved "+path);cases++;
  }
 }
 static JSONObject empty(){return new JSONObject();}
 static JSONObject think(int budget){return new JSONObject().put("type","enabled").put("budget_tokens",budget);}
 public static void main(String[] args)throws Exception{
  UsageLedger.init(Files.createTempDirectory("provider-budget-ledger").toFile());
  String chat="/v1/chat/completions",nativePath="/v1/messages",responses="/v1/responses";
  for(String path:new String[]{chat,responses}){
   JSONObject z=run("zcode","anthropic","GLM-5.3-Flash",path,empty(),0,false,200);
   check(z.getInt("max_tokens")==128000&&!z.has("thinking"),"ZCode catalog default, opt-in reasoning "+path);
   JSONObject m=run("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",path,empty(),0,false,200);
   check(m.getInt("max_tokens")==524288&&!m.has("thinking"),"MiniMax verified default "+path);
   JSONObject e=path.equals(chat)?new JSONObject().put("reasoning_effort","max"):new JSONObject().put("reasoning",new JSONObject().put("effort","xhigh"));
   m=run("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",path,e,0,false,200);
   check("adaptive".equals(m.getJSONObject("thinking").getString("type")),"MiniMax adaptive");
   check((path.equals(chat)?"max":"xhigh").equals(m.getJSONObject("output_config").getString("effort")),"MiniMax exact effort");
   String maxField=path.equals(chat)?"max_completion_tokens":"max_output_tokens";
   m=run("minimax-code","minimax","MiniMax-M3",path,new JSONObject().put(maxField,600000),0,false,200);
   check(m.getInt("max_tokens")==600000,"explicit max is not catalog-capped");
   m=run("minimax-code","minimax","MiniMax-M3",path,new JSONObject().put(maxField,600000),8000,false,200);
   check(m.getInt("max_tokens")==8000,"Guard cap is effective");
  }
  for(String model:new String[]{"GLM-5.3","GLM-5.3-Flash","GLM-5.2","GLM-5-Turbo"}){
   JSONObject z=run("zcode","anthropic",model,chat,new JSONObject().put("reasoning_effort","high"),0,false,200);
   check(z.getInt("max_tokens")==("GLM-5-Turbo".equals(model)?64000:128000),"ZCode family default");
   check(z.getJSONObject("thinking").getInt("budget_tokens")==24000,"ZCode high follows the translation budget table");
   if(model.startsWith("GLM-5.3")||"GLM-5.2".equals(model))
    check("high".equals(z.getJSONObject("output_config").getString("effort")),"ZCode GLM-5.x carries the level as output effort");
   else check(!z.has("output_config"),"GLM-5-Turbo carries no output effort");
  }
  JSONObject z=run("zcode","openai","GLM-5.3-Flash",chat,empty(),0,false,200);
  check(!z.has("max_tokens"),"native Chat omission preserved, no artificial 32000");
  z=run("zcode","anthropic","GLM-5.3-Flash",nativePath,empty(),0,false,200);
  check(!z.has("max_tokens")&&!z.has("thinking"),"native omission preserved");
  JSONObject controls=new JSONObject().put("max_tokens",20000).put("thinking",think(12345)).put("output_config",new JSONObject().put("effort","high"));
  for(boolean mutate:new boolean[]{false,true}){
   z=run("zcode","anthropic","GLM-5.3-Flash",nativePath,controls,0,mutate,200);
   check(z.getInt("max_tokens")==20000&&z.getJSONObject("thinking").getInt("budget_tokens")==12345,"exact native controls survive translation");
   check(z.getJSONObject("output_config").getString("effort").equals("high"),"GLM enabled budget and output effort coexist");
   run("zcode","anthropic","GLM-5.3-Flash",nativePath,controls,8000,mutate,400);
  }
  for(boolean mutate:new boolean[]{false,true}){
   JSONObject title=new JSONObject().put("output_config",new JSONObject().put("effort","high"));
   JSONObject sent=run("zcode","anthropic","GLM-5.3-Flash",nativePath,title,0,mutate,200);
   check(!sent.has("thinking")&&sent.getJSONObject("output_config").getString("effort").equals("high"),"native title effort does not enable thinking");
   title.put("thinking",new JSONObject().put("type","disabled"));
   sent=run("zcode","anthropic","GLM-5.3-Flash",nativePath,title,0,mutate,200);
   check("disabled".equals(sent.getJSONObject("thinking").getString("type")),"native disabled remains disabled with effort");
  }
  JSONObject adaptive=new JSONObject().put("max_tokens",524288).put("thinking",new JSONObject().put("type","adaptive")).put("output_config",new JSONObject().put("effort","max"));
  for(boolean mutate:new boolean[]{false,true}){
   JSONObject m=run("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",nativePath,adaptive,8000,mutate,200);
   check(m.getInt("max_tokens")==8000&&"max".equals(m.getJSONObject("output_config").getString("effort")),"native adaptive Guard consistency");
  }
  JSONObject explicit=new JSONObject().put("max_tokens",20000).put("thinking",think(16000));
  run("minimax-code","minimax","MiniMax-M3",chat,explicit,8000,false,400);
  for(String path:new String[]{chat,nativePath}){
   gift(path,0,0,path.equals(chat)?128000:-1);
   gift(path,8000,0,8000);
   gift(path,0,120000,120000);
  }
  for(String path:new String[]{chat,nativePath,responses})truncation(path);
  System.out.println("ProviderBudgetParityGatewayHttpTest PASS cases="+cases);
 }
}
