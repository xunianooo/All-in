package com.gaeul.allin;
import org.json.*;

public final class WorkBuddyHarnessPolicyOfflineTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static final String OP="You are an AI agent powered by DeepSeek Harness.";
 static JSONObject m(String r,Object c){return new JSONObject().put("role",r).put("content",c);}
 static JSONObject run(JSONObject in){try{return WorkBuddyRequestPolicy.transform(in,true);}catch(Exception e){throw new AssertionError(e);}}
 public static void main(String[] args)throws Exception {
  String runtime=OP+"\nDeepSeek Harness Web GUI; DeepSeek Harness implementation checkout; DSH itself.";
  String expected="You are an AI agent.\nWeb GUI; agent implementation checkout; the agent itself.";
  JSONArray parts=new JSONArray().put(new JSONObject().put("type","text").put("text",runtime))
   .put(new JSONObject().put("text","DeepSeek Harness and DSH"))
   .put(new JSONObject().put("type","tool_result").put("text",runtime).put("content",runtime))
   .put(new JSONObject().put("type","image").put("text",runtime))
   .put(new JSONObject().put("type","thinking").put("text",runtime).put("signature","fixture"))
   .put(new JSONObject().put("type","text").put("text",runtime).put("signature","fixture"))
   .put(new JSONObject().put("text",runtime).put("opaque",new JSONObject().put("payload","DSH")))
   .put(new JSONObject().put("type","text").put("text",runtime).put("extension",true));
  JSONObject schema=new JSONObject().put("type","object").put("description","The DeepSeek Harness uses DSH.")
   .put("properties",new JSONObject().put("description",new JSONObject().put("type","string").put("description","DSH"))
    .put("DSH_HOME",new JSONObject().put("allOf",new JSONArray().put(new JSONObject().put("description","DeepSeek Harness")))))
   .put("default",new JSONObject().put("description","DSH")).put("examples",new JSONArray().put(new JSONObject().put("description","DSH")))
   .put("enum",new JSONArray().put("DSH")).put("x-opaque",new JSONObject().put("description","DSH"));
  JSONObject tool=new JSONObject().put("type","function").put("function",new JSONObject().put("name","DSH")
   .put("description","DeepSeek Harness uses DSH.").put("parameters",schema));
  JSONObject original=new JSONObject().put("messages",new JSONArray().put(m("developer",runtime+"\nThe DeepSeek Harness uses DSH."))
   .put(m("user",runtime+"\nI use DSH and DeepSeek Harness."))
   .put(m("assistant",parts).put("tool_calls",new JSONArray().put(new JSONObject().put("id","DSH").put("type","function")
    .put("function",new JSONObject().put("name","DSH").put("arguments","{\"description\":\"DSH\"}")))))
   .put(m("tool",runtime).put("tool_call_id","DSH")))
   .put("tools",new JSONArray().put(tool)).put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","DSH")))
   .put("metadata",new JSONObject().put("description","DSH"));
  String before=original.toString();WorkBuddyHarnessPolicy.Report report=new WorkBuddyHarnessPolicy.Report();
  JSONObject out=WorkBuddyRequestPolicy.transform(original,true,report);JSONArray ms=out.getJSONArray("messages");
  check(before.equals(original.toString()),"source unchanged");
  check(ms.getJSONObject(0).getString("role").equals("system"),"developer stays instruction tier");
  check(ms.getJSONObject(0).getString("content").equals(expected+"\nThe agent harness uses the agent harness."),"system prose and runtime");
  check(ms.getJSONObject(1).getString("content").equals(expected+"\nI use DSH and DeepSeek Harness."),"user plain mentions preserved");
  JSONArray changed=ms.getJSONObject(2).getJSONArray("content");check(changed.getJSONObject(0).getString("text").equals(expected),"assistant fixed runtime");
  check(changed.getJSONObject(1).similar(parts.getJSONObject(1)),"missing type text, user prose rules");
  for(int i=2;i<parts.length();i++)check(changed.get(i).equals(parts.get(i))||changed.getJSONObject(i).similar(parts.getJSONObject(i)),"opaque block "+i);
  check(ms.getJSONObject(2).getJSONArray("tool_calls").similar(original.getJSONArray("messages").getJSONObject(2).getJSONArray("tool_calls")),"calls exact");
  check(ms.getJSONObject(3).similar(original.getJSONArray("messages").getJSONObject(3)),"tool result exact");
  JSONObject fn=out.getJSONArray("tools").getJSONObject(0).getJSONObject("function"),sc=fn.getJSONObject("parameters");
  check(fn.getString("name").equals("DSH")&&fn.getString("description").equals("the agent harness uses the agent harness."),"only descriptions");
  check(sc.getJSONObject("properties").has("description")&&sc.getJSONObject("properties").getJSONObject("description").getString("description").equals("the agent harness"),"schema key named description retained");
  check(sc.getJSONObject("properties").getJSONObject("DSH_HOME").getJSONArray("allOf").getJSONObject(0).getString("description").equals("the agent harness"),"recursive schema descriptions");
  for(String k:new String[]{"default","examples","enum","x-opaque"})check(JSONObject.valueToString(sc.get(k)).equals(JSONObject.valueToString(schema.get(k))),"schema values preserved "+k);
  check(out.getJSONObject("metadata").similar(original.getJSONObject("metadata")),"metadata exact");
  check(report.messages>0&&report.descriptions==4,"safe counts");check(run(out).similar(out),"idempotent");
  check(WorkBuddyRequestPolicy.transform(original,false).similar(WorkBuddyRequestPolicy.transform(original)),"disabled baseline");
  for(String value:new String[]{"/root/DSH/Work/OC-182.92.66.95/","C:\\DSH\\work","DSH.md","DSH_HOME","~/.dsh","dsh-session:example","run dsh --help","/root/DeepSeek Harness/Web GUI","DeepSeek_Harness","DeepSeek Harness.md","https://host/DSH","@DSH","DSH-agent","`DSH`","```text\n"+runtime+"\n```","`"+OP+"`"}) {
   check(WorkBuddyHarnessPolicy.text(value,false).equals(value),"path/code preserved: "+value);
  }
  check(WorkBuddyHarnessPolicy.text("DSH. DeepSeek Harness! the DeepSeek Harness;",false).equals("the agent harness. the agent harness! the agent harness;"),"punctuation prose");
  check(WorkBuddyHarnessPolicy.text("deepseek harness dsh Deepseek Harness",false).equals("deepseek harness dsh Deepseek Harness"),"case exact");
  check(WorkBuddyHarnessPolicy.text("DSH and DeepSeek Harness",true).equals("DSH and DeepSeek Harness"),"ordinary user mentions");
  JSONObject unknown=new JSONObject().put("messages",new JSONArray().put(m("future",runtime)));check(run(unknown).getJSONArray("messages").getJSONObject(1).getString("content").equals(runtime),"unknown content preserved");
  System.out.println("WorkBuddyHarnessPolicyOfflineTest passed (pinned 0.2.25 rules, safe deviations, roles, schema, paths, opaque, immutability)");
 }
}
