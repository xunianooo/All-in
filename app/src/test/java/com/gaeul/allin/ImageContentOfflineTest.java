package com.gaeul.allin;
import org.json.*;
import java.security.MessageDigest;
public class ImageContentOfflineTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static JSONObject obj(String s)throws Exception{return new JSONObject(s);}
 static void rejects(RunnableX r)throws Exception{try{r.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException expected){}}
 interface RunnableX{void run()throws Exception;}
 static JSONObject chat(String content)throws Exception{return obj("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":"+content+"}]}");}
 public static void main(String[] args)throws Exception{
  JSONObject tool=obj("{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,eA==\"}},{\"type\":\"text\",\"text\":\"b\"}]}]}");
  String before=tool.toString(); JSONObject out=ZCodeAnthropic.toAnthropic(tool); Object content=out.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).get("content"); check(content instanceof JSONArray && ((JSONArray)content).length()==3,"tool image and order");check(before.equals(tool.toString()),"input mutation");
  rejects(()->ResponsesBridge.chatToResponses(tool,"m"));
  for(String u:new String[]{"data:image/png;base64", "data:image/png,abc", "data:image/png;base64,%%%", "data:image/png;base64,", "https://user:pass@example.com/x", "https://", "https://example.com/a\nfoo", "file:///a"}) {JSONObject c=chat("[{\"type\":\"image_url\",\"image_url\":{\"url\":"+JSONObject.quote(u)+"}}]");rejects(()->ZCodeAnthropic.toAnthropic(c));rejects(()->ResponsesBridge.chatToResponses(c,"m"));}
  JSONObject high=chat("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.invalid/a\",\"detail\":\"high\"}}]");rejects(()->ZCodeAnthropic.toAnthropic(high));check(ResponsesBridge.toChat(ResponsesBridge.chatToResponses(high,"m")).toString().contains("high"),"detail roundtrip");
  StringBuilder b=new StringBuilder();for(int i=0;i<262144;i++)b.append("AAAA");String data=b.toString();String uri="data:image/png;base64,"+data;JSONObject longReq=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",uri))))));String snapshot=longReq.toString();String actual=ZCodeAnthropic.toAnthropic(longReq).getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("data");check(actual.length()==data.length()&&MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(actual.getBytes("UTF-8")),MessageDigest.getInstance("SHA-256").digest(data.getBytes("UTF-8"))),"long data preserved");check(snapshot.equals(longReq.toString()),"long input mutation");
  JSONObject multi=obj("{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"one\",\"is_error\":true,\"content\":[{\"type\":\"image_url\",\"image_url\":\"https://example.invalid/a\"}]},{\"role\":\"tool\",\"tool_call_id\":\"two\",\"content\":\"ok\"}]}");
  JSONArray blocks=ZCodeAnthropic.toAnthropic(multi).getJSONArray("messages").getJSONObject(0).getJSONArray("content");check(blocks.length()==2 && blocks.getJSONObject(0).getBoolean("is_error") && blocks.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type").equals("image") && blocks.getJSONObject(1).getString("tool_use_id").equals("two"),"multiple tools and pure image");
  rejects(()->ZCodeAnthropic.toAnthropic(chat("[{\"type\":\"image_url\"}]")));
  rejects(()->ZCodeAnthropic.toAnthropic(chat("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.invalid/a\"},\"cache_control\":{}}]")));
  JSONObject nativeImage=obj("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"eA==\"}}]}]}");String ni=nativeImage.toString();check(AnthropicBridge.requestToOpenAI(nativeImage).getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url").equals("data:image/jpeg;base64,eA=="),"native image");check(ni.equals(nativeImage.toString()),"native input mutation");
  for(String source:new String[]{"{\"type\":\"base64\",\"data\":\"eA==\"}","{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"%%%\"}","{\"type\":\"future\",\"url\":\"https://example.invalid/a\"}"}) { JSONObject n=obj("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"+source+"}]}]}");rejects(()->AnthropicBridge.requestToOpenAI(n)); }
  System.out.println("ImageContentOfflineTest OK");
 }
}
