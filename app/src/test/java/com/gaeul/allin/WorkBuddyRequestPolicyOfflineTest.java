package com.gaeul.allin;
import org.json.*;
import java.io.IOException;
public class WorkBuddyRequestPolicyOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 interface Run {void run() throws Exception;}
 static void fails(Run r)throws Exception {try{r.run();throw new AssertionError();}catch(IOException expected){}}
 public static void main(String[] args)throws Exception {
  JSONObject original=new JSONObject("{\"model\":\"m\",\"max_tokens\":100,\"max_completion_tokens\":40,\"messages\":[{\"role\":\"developer\",\"content\":\"policy\"},{\"role\":\"assistant\",\"reasoning_content\":\"thought\",\"content\":\"answer\"}]}");
  String before=original.toString();JSONObject out=WorkBuddyRequestPolicy.transform(original);check(before.equals(original.toString()));check(out.getInt("max_tokens")==100&&!out.has("max_completion_tokens"),"a chat's own max_tokens is kept");check(out.getJSONArray("messages").getJSONObject(0).getString("role").equals("system"));check(out.getJSONArray("messages").getJSONObject(1).getString("reasoning").equals("thought"));
  JSONObject fallback=new JSONObject("{\"model\":\"m\",\"max_completion_tokens\":40,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
  check(WorkBuddyRequestPolicy.transform(fallback).getInt("max_tokens")==40&&!WorkBuddyRequestPolicy.transform(fallback).has("max_completion_tokens"),"max_completion_tokens fills an absent max_tokens");
  JSONObject r=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"reasoning\":\"keep\",\"reasoning_details\":[{\"text\":\"other\"}]}],\"tools\":[{}],\"tool_choice\":\"none\"}");out=WorkBuddyRequestPolicy.transform(r);check(out.getJSONArray("messages").getJSONObject(0).getString("content").equals("You are a helpful assistant."));check(out.getJSONArray("messages").getJSONObject(2).getString("reasoning").equals("keep"));check(!out.has("tools")&&!out.has("tool_choice"));
  r.put("tool_choice",new JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"run\"}}"));check(WorkBuddyRequestPolicy.transform(r).getString("tool_choice").equals("run"));
  fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("n",2)));fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("max_tokens",1.5)));fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("tool_choice",new JSONObject("{\"type\":\"function\"}"))));
  String opener="You are an AI agent powered by DeepSeek Harness.";
  String adapted="You are an AI agent, powered by DeepSeek Harness.";
  JSONObject harness=new JSONObject().put("messages",new JSONArray()
    .put(new JSONObject().put("role","developer").put("content",opener+"\nKeep the project instructions."))
    .put(new JSONObject().put("role","user").put("content",opener))
    .put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",opener))))
    .put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content",opener)))
    .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","DeepSeek_Harness")
      .put("description",opener).put("parameters",new JSONObject().put("type","object").put("description",opener)))));
  String originalHarness=harness.toString();JSONObject compatible=WorkBuddyRequestPolicy.transform(harness);
  JSONArray hm=compatible.getJSONArray("messages");
  check(harness.toString().equals(originalHarness));check(hm.getJSONObject(0).getString("content").equals(adapted+"\nKeep the project instructions."));
  check(hm.getJSONObject(1).similar(harness.getJSONArray("messages").getJSONObject(1)));
  check(hm.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text").equals(adapted));
  check(hm.getJSONObject(3).similar(harness.getJSONArray("messages").getJSONObject(3)));check(compatible.getJSONArray("tools").similar(harness.getJSONArray("tools")));
  check(WorkBuddyRequestPolicy.transform(compatible).similar(compatible));
  for(String unchanged:new String[]{"User quotes: "+opener,"You are an AI agent powered by DeepSeek Harness Desktop.","You are an AI agent powered by DeepSeek Harness. Additional instructions","~/.dsh DSH_HOME dsh-session:example","ordinary request"}) {
    JSONObject plain=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",unchanged)));
    check(WorkBuddyRequestPolicy.transform(plain).getJSONArray("messages").getJSONObject(0).getString("content").equals(unchanged));
  }
  JSONObject web=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","Web GUI whose URL the runtime context names. When the user refers to it, use it.")));
  check(WorkBuddyRequestPolicy.transform(web).getJSONArray("messages").getJSONObject(0).getString("content").equals("Web GUI whose URL the runtime context names. The interface, when the user refers to it, use it."));
  System.out.println("WorkBuddyRequestPolicyOfflineTest OK");
 }
}
