package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

public final class CatPawMiniGatewayHttpTest {
 static void check(boolean x,String m){if(!x)throw new AssertionError(m);}
 static void pools() throws Exception {
  Method m=GatewayServer.class.getDeclaredMethod("safeAllowance",String.class,JSONObject.class); m.setAccessible(true);
  JSONArray pools=new JSONArray();
  for(int i=0;i<25;i++) pools.put(new JSONObject().put("name","model-"+i).put("secret","bad").put("windows",new JSONArray().put(new JSONObject().put("name","interval").put("remaining",0).put("total",12).put("unit","count").put("secret","bad"))));
  pools.put(new JSONObject().put("name","secret-token"));
  JSONObject raw=new JSONObject().put("kind","allowance").put("scope","account").put("unit","percent").put("windows",new JSONArray()).put("modelPools",pools);
  JSONObject result=(JSONObject)m.invoke(null,"minimax-code",raw); check(result.getJSONArray("windows").length()==0,"account stays empty");
  check(m.invoke(null,"other",raw)==null,"other providers reject model pools");
  check(result.getJSONArray("modelPools").length()==20 && !result.toString().contains("secret"),"bounded allowlist");
  check(result.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0).getInt("remaining")==0,"count zero");
 }
 public static void main(String[] a)throws Exception {
  pools();
  JSONObject p=VendorPolicyGatewayHttpTest.provider("catpaw","catpaw","https://example.invalid","auto");
  p.put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("token","fixture-token").put("enabled",true)));
  JSONObject cfg=VendorPolicyGatewayHttpTest.config(p).put("setup",new JSONObject().put("done",true)).put("privacy",new JSONObject().put("enabled",true));
  final int[] upstream={0}; final String[] mask={null}; final int[] fault={0};
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(500,"application/json","{}")); VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
   g.gateway.setCatPawTransportForTests((url,body,h,t,accept)->{
    upstream[0]++;
    if(url.endsWith("/round")) { Matcher match=Pattern.compile("\\{\\{MASK_EMAIL_[^}]+}}").matcher(body); if(match.find()) mask[0]=match.group(); return new CatPawAuth.ChatResponse(200,"{\"code\":0}".getBytes(StandardCharsets.UTF_8)); }
    if(url.endsWith("/event")) return new CatPawAuth.ChatResponse(200,"{}".getBytes(StandardCharsets.UTF_8));
    String text;
    if(url.endsWith("/turn")) {
     JSONObject request=new JSONObject(body);
     String prompt=request.getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text");
     text=prompt.contains("Available tools:")?new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("name","Read").put("arguments",new JSONObject().put("path",mask[0])))).toString():"Hello "+mask[0];
    } else text="Hello "+mask[0];
    String frame="data: "+new JSONObject().put("message",new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text))))+"\n\ndata: [DONE]\n\n";
    if(fault[0]==1) frame=frame.replace("data: [DONE]\n\n", "");
    if(fault[0]==2) frame=frame.replace("data: [DONE]\n\n", "data: {\"message\":\n\ndata: [DONE]\n\n");
    if(fault[0]==3) frame="data: {\"heartbeat\":true}\n\ndata: [DONE]\n\n";
    if(fault[0]==4) frame=frame.replace("data: [DONE]\n\n", "data: [DONE]");
    if(fault[0]==5) frame=frame.replace("data: [DONE]\n\n", "data: {\"finished\":true}\n\n");
    return new CatPawAuth.ChatResponse(200,frame.getBytes(StandardCharsets.UTF_8));
   });
   JSONArray tools=new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","Read").put("parameters",new JSONObject().put("type","object").put("required",new JSONArray().put("path")))));
   JSONArray anthTools=new JSONArray().put(new JSONObject().put("name","Read").put("input_schema",new JSONObject().put("type","object").put("required",new JSONArray().put("path"))));
   for(boolean stream:new boolean[]{false,true}) for(boolean anthropic:new boolean[]{false,true}) {
    JSONObject body;
    String path;
    if(anthropic) {
     path="/v1/messages";
     body=new JSONObject().put("model","catpaw/auto").put("max_tokens",100).put("stream",stream).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","Read alice@example.com"))).put("tools",anthTools).put("tool_choice",new JSONObject().put("type","any"));
    } else {
     path="/v1/chat/completions";
     body=VendorPolicyGatewayHttpTest.body("catpaw/auto",stream).put("tools",tools).put("tool_choice","required");
     body.getJSONArray("messages").getJSONObject(0).put("content","Read alice@example.com");
    }
    int before=upstream[0];
    VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,path,body);
    check(r.status==200,"tool request "+path+" stream="+stream+" "+r.status+" "+r.body);
    check(upstream[0]==before+3,"tool request must complete one round/event/turn");
    check(r.body.contains(anthropic?"tool_use":"tool_calls") && r.body.contains("alice@example.com") && !r.body.contains("MASK_EMAIL"),"tool call and masked argument unwrapped "+r.body);
    body.put("tool_choice",anthropic?new JSONObject().put("type","none"):"none");
    mask[0]=null;
    r=VendorPolicyGatewayHttpTest.post(g,path,body);
    check(r.status==200,"none "+path+" stream="+stream+" "+r.status+" "+r.body);
    check(upstream[0]==before+6,"none must complete round/event/turn");
    check(mask[0]!=null && r.body.contains("alice@example.com") && !r.body.contains("MASK_EMAIL"),"none text unmasked "+r.body);
    check(!r.body.contains("tool_use") && !r.body.contains("tool_calls"),"none emitted tool call "+r.body);
    if(stream) check(r.body.contains(anthropic?"message_stop":"[DONE]"),"stream terminal "+r.body);
    else check(r.body.contains(anthropic?"end_turn":"stop"),"text finish "+r.body);
   }
   // Real local gateway HTTP requests: no unconfirmed turn may emit tools or retry.
   for(int failure=1;failure<=5;failure++) for(boolean stream:new boolean[]{false,true})
    for(boolean anthropic:new boolean[]{false,true}) for(String choice:new String[]{"auto","required"}) {
     fault[0]=failure;
     JSONObject body=VendorPolicyGatewayHttpTest.body("catpaw/auto",stream);
     String path=anthropic?"/v1/messages":"/v1/chat/completions";
     body.put("tools",anthropic?anthTools:tools).put("tool_choice",anthropic?new JSONObject().put("type",choice.equals("required")?"any":"auto"):choice);
     if(anthropic) body.put("max_tokens",100);
     int before=upstream[0];
     VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,path,body);
     check(r.status==502,"unconfirmed turn must fail "+failure+" "+path+" "+stream+" "+r.status+" "+r.body);
     check(upstream[0]==before+3,"unconfirmed turn must not replay");
     check(!r.body.contains("tool_calls") && !r.body.contains("tool_use") && !r.body.contains("[DONE]"),"no executable tool or success terminal on failure");
    }
   fault[0]=1;
   JSONObject textBody=VendorPolicyGatewayHttpTest.body("catpaw/auto",false).put("tools",tools).put("tool_choice","none");
   VendorPolicyGatewayHttpTest.Result fallback=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",textBody);
   check(fallback.status==200 && fallback.body.contains("Hello"),"legacy text EOF fallback retained");
  // A past call that missed the declared schema is prompt text, not a failed turn.
  fault[0]=0;
  JSONObject drift=VendorPolicyGatewayHttpTest.body("catpaw/auto",false).put("tools",tools).put("tool_choice","required");
  drift.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","c1").put("type","function").put("function",new JSONObject().put("name","Read").put("arguments","{\"path\":17}"))))).put(new JSONObject().put("role","tool").put("tool_call_id","c1").put("content","ok"));
  VendorPolicyGatewayHttpTest.Result driftResult=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",drift);
  check(driftResult.status==200,"history schema drift must not fail the turn: "+driftResult.status+" "+driftResult.body);
  check(driftResult.body.contains("tool_calls"),"history drift turn still reaches the tools: "+driftResult.body);

  }
  System.out.println("CatPawMiniGatewayHttpTest passed");
 }
}
