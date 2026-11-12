package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.net.*;
import java.util.*;

/** Terminal reasons through real local gateway/HTTP paths, synthetic input only. */
public final class CompletionStatusGatewayHttpTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static final String[] PATHS={"/v1/chat/completions","/v1/messages","/v1/responses"};
 static String anthropic(boolean stream,String reason){
  if(!stream){JSONObject j=new JSONObject(ZCodeGiftUsageHttpTest.json("full"));if(reason==null)j.remove("stop_reason");else j.put("stop_reason",reason);return j.toString();}
  String src=ZCodeGiftUsageHttpTest.stream("full",true);
  if(reason==null)return src.replace("\"stop_reason\":\"end_turn\"","\"stop_reason\":null");
  return src.replace("\"stop_reason\":\"end_turn\"","\"stop_reason\":\""+reason+"\"");
 }
 static String chat(boolean stream,String reason){
  if(!stream){JSONObject j=new JSONObject(VendorPolicyGatewayHttpTest.CHAT);JSONObject c=j.getJSONArray("choices").getJSONObject(0);if(reason==null)c.remove("finish_reason");else c.put("finish_reason",reason);return j.toString();}
  String raw=VendorPolicyGatewayHttpTest.SSE;
  if(reason==null)return raw.replace("\"finish_reason\":\"tool_calls\"","\"finish_reason\":null");
  return raw.replace("\"finish_reason\":\"tool_calls\"","\"finish_reason\":\""+reason+"\"");
 }
 static void run(boolean gift,String path,boolean stream,String reason)throws Exception {
  boolean responses=path.endsWith("/responses");boolean directGift=gift&&!responses;
  String payload=directGift?anthropic(stream,reason==null?null:(reason.equals("length")?"max_tokens":reason)):chat(stream,reason);
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((req,n)->new VendorPolicyGatewayHttpTest.Reply(200,stream?"text/event-stream":"application/json",payload))) {
   ZCodeGift.resetBusyForTests();ZCodeGift.resetCooldownForTests();ZCodeGift.setFixtureBase("http://127.0.0.1:"+u.server.getLocalPort());
   JSONObject cfg=ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());if(!gift)cfg.getJSONArray("providers").getJSONObject(0).remove("accounts");
   ZCodeGiftUsageHttpTest.Gateway g=new ZCodeGiftUsageHttpTest.Gateway(cfg,new ZCodeGiftBusyGatewayHttpTest.Transport());
   g.gateway.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+u.server.getLocalPort()+new URL(url).getFile()));g.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT,true);
   try{
    ZCodeBoundaryGatewayHttpTest.Reply r=ZCodeBoundaryGatewayHttpTest.post(g.getListeningPort(),path,stream);ZCodeGiftUsageHttpTest.idle(g);
    String label=(gift?"gift":"plan")+" "+path+" stream="+stream+" reason="+reason;
    check(u.requests.size()==1,label+" must send exactly once");
    boolean valid="length".equals(reason);
    if(valid){check(r.status==200,label+" "+r.status+" "+r.body);
      String expected=path.endsWith("/messages")?"max_tokens":responses?"max_output_tokens":"length";
      check(r.body.contains(expected),label+" lost length "+r.body);
    }else if(path.endsWith("/chat/completions")&&!directGift){
      // Native Chat passthrough must not invent a finish; the upstream body is retained.
      check(r.status==200 && !r.body.contains("\"finish_reason\":\"stop\""),label+" false stop "+r.body);
    }else if(!stream){check(r.status==502,label+" expected protocol failure "+r.status+" "+r.body);}
    else{check(r.status==200&&r.body.contains("error"),label+" expected stream error "+r.body);
      check(!r.body.contains("[DONE]")&&!r.body.contains("event: message_stop")&&!r.body.contains("response.completed"),label+" false completion "+r.body);}
    System.out.println("PASS "+label);
   }finally{g.stop();ZCodeGift.setFixtureBase(null);ZCodeGift.resetBusyForTests();ZCodeGift.resetCooldownForTests();}
  }
 }
 public static void main(String[]args)throws Exception{
  List<String> failures=new ArrayList<>();
  for(boolean gift:new boolean[]{true,false})for(String path:PATHS)for(boolean stream:new boolean[]{false,true})for(String reason:new String[]{"length",null,"unknown"}){
   String label=(gift?"gift":"plan")+" "+path+" stream="+stream+" reason="+reason;
   try{run(gift,path,stream,reason);}catch(Throwable e){failures.add(label+": "+e);System.out.println("FAIL "+label+": "+e);}
  }
  if(!failures.isEmpty())throw new AssertionError(failures.size()+" failures "+failures);
  System.out.println("CompletionStatusGatewayHttpTest PASS");
 }
}
