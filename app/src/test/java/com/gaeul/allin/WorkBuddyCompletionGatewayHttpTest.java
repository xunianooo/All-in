package com.gaeul.allin;
import com.gaeul.allin.json.*;

/** WorkBuddy stream-to-nonstream status, synthetic upstream and no vendor calls. */
public final class WorkBuddyCompletionGatewayHttpTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static String chunk(JSONObject delta,String reason){return "data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",reason==null?JSONObject.NULL:reason)))+"\n\n";}
 static void run(String scenario)throws Exception {
  String input=chunk(new JSONObject().put("reasoning_content","only thought"),null);
  if(scenario.equals("length"))input+=chunk(new JSONObject(),"length");
  else if(scenario.equals("unknown"))input+=chunk(new JSONObject(),"new_reason");
  else if(scenario.equals("error"))input+="data: {\"error\":{\"message\":\"synthetic\"}}\n\n";
  else if(scenario.equals("tool-length"))input+=chunk(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("id","t").put("function",new JSONObject().put("name","f").put("arguments","{}")))),"length");
  input+="data: [DONE]\n\n";final String source=input;
  try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((req,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",source));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(WorkBuddyCompatibilityGatewayHttpTest.config(),upstream,false)){
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",WorkBuddyCompatibilityGatewayHttpTest.request("/v1/chat/completions",false));
   check(upstream.requests.size()==1,"no replay "+scenario);
   if(scenario.equals("length")||scenario.equals("tool-length")){
    check(result.status==200,"status "+scenario+" "+result.body);JSONObject c=new JSONObject(result.body).getJSONArray("choices").getJSONObject(0);
    check("length".equals(c.getString("finish_reason")),"length preserved "+scenario);
    JSONObject m=c.getJSONObject("message");check("".equals(m.getString("content"))&&"only thought".equals(m.getString("reasoning_content")),"no invented visible text");
    if(scenario.equals("tool-length"))check(m.getJSONArray("tool_calls").length()==1,"tool kept");
   }else check(result.status==502,"invalid completion should fail "+scenario+" "+result.status+" "+result.body);
  }
 }
 public static void main(String[]args)throws Exception{for(String s:new String[]{"length","tool-length","missing","unknown","error"})run(s);System.out.println("WorkBuddyCompletionGatewayHttpTest PASS");}
}
