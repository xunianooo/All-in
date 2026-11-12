package com.gaeul.allin;
import com.gaeul.allin.json.*;

/** The defaults here are compatibility defaults, not claims about vendor model capability. */
public final class CompletionBudgetOfflineTest {
 static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
 static JSONObject request(){return new JSONObject().put("model","zcode/test").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","x")));}
 static JSONObject convert(JSONObject input,boolean guard,int cap)throws Exception{
  ContextGuard.Config c=new ContextGuard.Config();c.enabled=guard;c.maxOutputTokens=cap;
  ContextGuard.Result result=ContextGuard.fit(input,c);check(result.error==null&&!result.budgetExceeded,"guard accepted fixture");
  return ZCodeAnthropic.toAnthropic(result.payload);
 }
 public static void main(String[] args)throws Exception{
  check(convert(request(),false,0).getInt("max_tokens")==16384,"unknown Anthropic model fallback");
  check(!convert(request(),false,0).has("thinking"),"no implicit reasoning");
  // Both client fields are caps: the stricter one survives projection and Guard.
  check(convert(request().put("max_tokens",20).put("max_completion_tokens",100),false,0).getInt("max_tokens")==20,"stricter legacy client cap");
  check(convert(request().put("max_tokens",100).put("max_completion_tokens",20),false,0).getInt("max_tokens")==20,"stricter completion client cap");
  check(convert(request().put("max_tokens",20).put("max_completion_tokens",100),true,50).getInt("max_tokens")==20,"guard never raises stricter client cap");
  check(convert(request().put("max_tokens",100).put("max_completion_tokens",200),true,50).getInt("max_tokens")==50,"guard caps both client fields");
  check(convert(request().put("max_completion_tokens",100),true,50).getInt("max_tokens")==50,"guard caps completion-only client field");
  check(convert(request().put("max_tokens",64),true,300).getInt("max_tokens")==64,"client below guard");
  check(convert(request(),true,256).getInt("max_tokens")==256,"guard applies when omitted");
  JSONObject r=convert(request().put("reasoning_effort","medium"),false,0);
  check(r.getInt("max_tokens")==16384&&r.getJSONObject("thinking").getInt("budget_tokens")==10000,"medium follows the translation budget table");
  r=convert(request().put("max_tokens",2048).put("reasoning_effort","high"),false,0);
  check(r.getInt("max_tokens")==2048&&!r.has("thinking"),"a 2048 cap carries no thinking, as the upstream translation does");
  r=convert(request().put("max_tokens",2049).put("reasoning_effort","high"),false,0);
  check(r.getInt("max_tokens")==2049&&r.getJSONObject("thinking").getInt("budget_tokens")==1025,"a 2049 cap keeps a budget under it with room for the answer");
  r=convert(request().put("max_tokens",1025).put("reasoning_effort","high"),false,0);
  check(r.getInt("max_tokens")==1025&&!r.has("thinking"),"a cap under 2048 is a title-sized ask with no thinking");
  r=convert(request().put("max_tokens",1024).put("reasoning_effort","high"),false,0);
  check(r.getInt("max_tokens")==1024&&!r.has("thinking"),"small cap unchanged");
  JSONObject responses=new JSONObject().put("model","zcode/test").put("input","hello").put("max_output_tokens",128);
  JSONObject projected=ResponsesBridge.toChat(responses);check(convert(projected,true,256).getInt("max_tokens")==128,"Responses explicit cap");
  JSONObject a=new JSONObject().put("model","zcode/test").put("max_tokens",5000).put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",1234)).put("messages",request().getJSONArray("messages"));
  JSONObject projection=AnthropicBridge.projectNativeRequest(a);
  JSONObject restored=GatewayServer.nativeAnthropicRequest(a,new JSONObject(projection.toString()),new JSONObject(projection.toString()),"test",false);
  check(restored!=null&&restored.getJSONObject("thinking").getInt("budget_tokens")==1234&&restored.getInt("max_tokens")==5000,"native thinking exact");
  System.out.println("CompletionBudgetOfflineTest PASS");
 }
}
