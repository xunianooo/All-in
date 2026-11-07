package com.gaeul.allin;
import org.json.*;
import java.io.IOException;
public class WorkBuddyRequestPolicyOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 interface Run {void run() throws Exception;}
 static void fails(Run r)throws Exception {try{r.run();throw new AssertionError();}catch(IOException expected){}}
 public static void main(String[] args)throws Exception {
  JSONObject original=new JSONObject("{\"model\":\"m\",\"max_tokens\":100,\"max_completion_tokens\":40,\"messages\":[{\"role\":\"developer\",\"content\":\"policy\"},{\"role\":\"assistant\",\"reasoning_content\":\"thought\",\"content\":\"answer\"}]}");
  String before=original.toString();JSONObject out=WorkBuddyRequestPolicy.transform(original);check(before.equals(original.toString()));check(out.getInt("max_tokens")==40&&!out.has("max_completion_tokens"));check(out.getJSONArray("messages").getJSONObject(0).getString("role").equals("system"));check(out.getJSONArray("messages").getJSONObject(1).getString("reasoning").equals("thought"));
  JSONObject r=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"reasoning\":\"keep\",\"reasoning_details\":[{\"text\":\"other\"}]}],\"tools\":[{}],\"tool_choice\":\"none\"}");out=WorkBuddyRequestPolicy.transform(r);check(out.getJSONArray("messages").getJSONObject(0).getString("content").equals("You are a helpful assistant."));check(out.getJSONArray("messages").getJSONObject(2).getString("reasoning").equals("keep"));check(!out.has("tools")&&!out.has("tool_choice"));
  r.put("tool_choice",new JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"run\"}}"));check(WorkBuddyRequestPolicy.transform(r).getString("tool_choice").equals("run"));
  fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("n",2)));fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("max_tokens",1.5)));fails(()->WorkBuddyRequestPolicy.transform(new JSONObject(r.toString()).put("tool_choice",new JSONObject("{\"type\":\"function\"}"))));
  System.out.println("WorkBuddyRequestPolicyOfflineTest OK");
 }
}
