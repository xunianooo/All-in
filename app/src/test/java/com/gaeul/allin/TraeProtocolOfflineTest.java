package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.util.*;
public class TraeProtocolOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 interface Run {void run() throws Exception;}
 static void fails(Run r)throws Exception{try{r.run();throw new AssertionError("expected failure");}catch(java.io.IOException expected){}}
 static JSONObject readPieces(JSONArray tools,String... pieces) throws Exception {
  TraeProtocol.Decoder d=new TraeProtocol.Decoder(tools,"m");StringBuilder text=new StringBuilder();JSONArray calls=new JSONArray(),finish=new JSONArray();String error=null;
  try{
   for(int k=0;k<=pieces.length;k++){
    boolean last=k==pieces.length;
    List<JSONObject> out=d.accept("output",new JSONObject().put("response",last?"":pieces[k]).toString());
    if(last&&!d.terminal)out.addAll(d.accept("done","{}"));
    for(JSONObject chunk:out){JSONObject choice=chunk.getJSONArray("choices").getJSONObject(0),delta=choice.getJSONObject("delta");text.append(delta.optString("content",""));
     JSONArray tc=delta.optJSONArray("tool_calls");if(tc!=null)for(int i=0;i<tc.length();i++){JSONObject f=tc.getJSONObject(i).getJSONObject("function");calls.put(new JSONArray().put(f.getString("name")).put(new JSONObject(f.getString("arguments"))));}
     if(!choice.isNull("finish_reason"))finish.put(choice.getString("finish_reason"));}
    if(last)break;
   }
  }catch(java.io.IOException ex){error=ex.getMessage();}
  return new JSONObject().put("text",text.toString()).put("calls",calls).put("finish",finish).put("error",error==null?JSONObject.NULL:error);
 }
 static JSONObject readText(JSONArray tools,String s,int size) throws Exception {
  if(size<=0)return readPieces(tools,s);
  String[] pieces=new String[(s.length()+size-1)/size];
  for(int k=0;k<pieces.length;k++)pieces[k]=s.substring(k*size,Math.min(s.length(),(k+1)*size));
  return readPieces(tools,pieces);
 }
 static String oneCall(JSONObject r,String name) throws Exception { check(r.isNull("error"),"no error: "+r.opt("error")); check(r.getString("text").isEmpty(),"no text reaches the answer: "+r.getString("text")); JSONArray calls=r.getJSONArray("calls"); check(calls.length()==1,"one call: "+calls); check(calls.getJSONArray(0).getString(0).equals(name),"named "+name+": "+calls); return calls.getJSONArray(0).getJSONObject(1).toString(); }

 public static void main(String[] args)throws Exception{
  {JSONObject t=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"go\"},{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"r\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"name\":\"run\",\"content\":\"done\"}]}").put("tools",new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}}]"));
   JSONObject wire=TraeProtocol.request(t,"chat","m",100,"s",true,null);
   JSONArray sent=wire.getJSONArray("messages");
   check(sent.getJSONObject(2).getString("tool_call_id").equals("c1") && !sent.getJSONObject(2).has("name"));
   JSONObject badName=new JSONObject(t.toString());badName.getJSONArray("messages").getJSONObject(2).put("name",7);
   try{TraeProtocol.request(badName,"chat","m",100,"s",true,null);throw new AssertionError("bad name");}catch(java.io.IOException expected){}}

  JSONArray tools=new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}}]");
  JSONObject req=new JSONObject("{\"model\":\"kimi\",\"messages\":[{\"role\":\"assistant\",\"content\":\"hello\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}},{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,YWJj\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"ok\"}]}").put("tools",tools);
  String before=req.toString();JSONObject body=TraeProtocol.request(req,"chat","kimi",100,"s",true,null);JSONArray messages=body.getJSONArray("messages");check(messages.length()==4);check(messages.getJSONObject(0).getJSONArray("tool_calls").length()==2);check(messages.getJSONObject(3).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url").endsWith("YWJj"));check(before.equals(req.toString()));check(body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").get("parameters") instanceof String);
  JSONObject blind=TraeProtocol.request(req,"chat","kimi",100,"s",false,null);
  check(blind.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").equals("[an image, which this model can't see]"),"blind model is told an image was there");
  check(!blind.toString().contains("image_url") && !blind.toString().contains("YWJj"),"image bytes must not reach a model that cannot see them");
  JSONObject userImage=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,QQ==\"}]}]}");
  JSONArray seen=TraeProtocol.request(userImage,"chat","m",100,"s",true,null).getJSONArray("messages").getJSONObject(0).getJSONArray("content");
  check(seen.length()==2 && seen.getJSONObject(1).getJSONObject("image_url").getString("url").endsWith("QQ=="),"a seeing model still gets the image part");
  JSONObject detailed=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,QQ==\",\"detail\":\"high\"}}]}]}");
  JSONArray dropped=TraeProtocol.request(detailed,"chat","m",100,"s",true,null).getJSONArray("messages").getJSONObject(0).getJSONArray("content");
  check(dropped.getJSONObject(0).getJSONObject("image_url").optString("url").endsWith("QQ==") && !dropped.toString().contains("detail"),"image detail is dropped, as the plugin drops every field but the url");
  TraeProtocol.Decoder d=new TraeProtocol.Decoder(tools,"m");check(d.accept("output","{\"response\":\"a\"}").get(0).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("content").equals("a"));check(d.accept("output","{\"response\":\"a\"}").size()==1);d.accept("output","{\"tool_calls\":[{\"id\":\"x\",\"index\":0,\"function\":{\"name\":\"run\",\"arguments\":\"{\\\"a\\\":\"}}]}");d.accept("output","{\"tool_calls\":[{\"index\":0,\"function_call\":{\"arguments\":\"{\\\"a\\\":1}\"}}]}");d.accept("TokenUsage","{\"prompt_tokens\":4,\"completion_tokens\":2,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}");List<JSONObject> chunks=d.accept("response_done","{}");check(chunks.get(0).toString().contains("\\\"a\\\":1"));check(d.usage.getBoolean("magpie_cache_unknown"));check(d.finishReason.equals("tool_calls"));d.end();
  fails(()->new TraeProtocol.Decoder(tools,"m").end());fails(()->new TraeProtocol.Decoder(tools,"m").accept("error","{\"code\":4001,\"message\":\"secret\"}"));
  TraeProtocol.Decoder bad=new TraeProtocol.Decoder(tools,"m");bad.accept("output","{\"response\":\"<tool_call>broken\"}");fails(()->bad.accept("done","{}"));
  TraeProtocol.Decoder stream=new TraeProtocol.Decoder(tools,"m");
  List<JSONObject> early=stream.accept("output","{\"response\":\"abc<tool_\"}");check(early.size()==1&&early.get(0).toString().contains("abc"));check(early.get(0).has("id")&&early.get(0).has("created"));
  stream.accept("output","{\"response\":\"call>{\\\"name\\\":\\\"run\\\",\\\"arguments\\\":{}}<\"}");stream.accept("output","{\"response\":\"/tool_call>tail\"}");List<JSONObject> done=stream.accept("done","{}");check(done.toString().contains("tool_calls"));
  TraeProtocol.Decoder partial=new TraeProtocol.Decoder(tools,"m");partial.accept("output","{\"response\":\"hello<tool_\"}");fails(()->partial.accept("done","{}"));
  TraeProtocol.Decoder invalid=new TraeProtocol.Decoder(tools,"m");invalid.accept("output","{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}");fails(()->invalid.accept("done","{}"));
  TraeProtocol.Decoder dup=new TraeProtocol.Decoder(tools,"m");dup.accept("output","{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"name\":\"run\",\"arguments\":\"{\\\"a\\\":1,\\\"a\\\":2}\"}}]}");fails(()->dup.accept("done","{}"));
  fails(()->TraeProtocol.request(req,"chat","kimi",100,new String(new char[129]).replace('\0','x'),true,null));
  String img=messages.getJSONObject(3).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url");byte[] bytes=java.util.Base64.getDecoder().decode(img.substring(img.indexOf(',')+1));check(java.util.Arrays.equals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),java.security.MessageDigest.getInstance("SHA-256").digest("abc".getBytes("UTF-8"))));
  JSONObject base=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"user\",\"content\":\"b\"}]}");
  check(TraeProtocol.request(base,"chat","m",100,"s",true,null).getJSONArray("messages").getJSONObject(0).getJSONArray("content").length()==2);
  for(String field:new String[]{"stop","response_format","logprobs"}) {JSONObject r=new JSONObject(base.toString()).put(field,1);fails(()->TraeProtocol.request(r,"chat","m",100,"s",true,null));}
  JSONObject extra=new JSONObject(base.toString()).put("top_p",0.9).put("seed",2).put("presence_penalty",JSONObject.NULL).put("metadata",new JSONObject()).put("stream_options",new JSONObject().put("include_usage",true)).put("extension",new JSONObject());
  extra.getJSONArray("messages").getJSONObject(0).put("metadata",new JSONObject());String extraBefore=extra.toString();JSONObject extraWire=TraeProtocol.request(extra,"chat","m",100,"s",true,null);check(!extraWire.has("top_p")&&!extraWire.has("extension")&&extraBefore.equals(extra.toString()));
  fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("top_p","bad"),"chat","m",100,"s",true,null));
  fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("stream_options",new JSONObject().put("include_usage","yes")),"chat","m",100,"s",true,null));
  check(TraeProtocol.request(new JSONObject(base.toString()).put("n",1),"chat","m",100,"s",true,null)!=null);fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("n",2),"chat","m",100,"s",true,null));
  JSONObject cap=new JSONObject(base.toString()).put("max_tokens",200).put("temperature",0.5);JSONObject capped=TraeProtocol.request(cap,"chat","m",100,"s",true,null);check(capped.getInt("max_tokens")==100&&capped.getDouble("temperature")==0.5);
  fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("max_tokens",1.1),"chat","m",100,"s",true,null));
  JSONObject maxReq=new JSONObject(base.toString()).put("model","fixture-max");
  JSONObject maxWire=TraeProtocol.request(maxReq,"solo_agent","fixture__max",64,"s",true,null,"fixture",1000000,true);
  check(maxWire.getString("config_name").equals("fixture")&&maxWire.getString("model").equals("fixture")&&maxWire.getString("model_name").equals("fixture__max"));
  check(maxWire.getInt("max_tokens")==64&&maxWire.getJSONObject("user_message_context").getJSONObject("model_info").getInt("prompt_max_tokens")==999936);
  boolean missingBase=false;try{TraeProtocol.request(maxReq,"solo_agent","fixture__max",64,"s",true,null,"",1000000,true);}catch(java.io.IOException expected){missingBase=true;}check(missingBase);
  JSONObject askedMax=TraeProtocol.request(new JSONObject(maxReq.toString()).put("max_completion_tokens",32),"solo_agent","fixture__max",64,"s",true,null,"fixture",1000000,true);
  check(askedMax.getInt("max_tokens")==32&&askedMax.getJSONObject("user_message_context").getJSONObject("model_info").getInt("prompt_max_tokens")==999968);
  JSONObject plain=TraeProtocol.request(new JSONObject(base.toString()),"chat","m",100,"s",true,null,"",1000000,false);check(!plain.has("user_message_context"));
  JSONObject catalog=new JSONObject("{\"function_configs\":[{\"function\":\"chat_v3\",\"config_info_list\":[{\"config_name\":\"fixture\",\"context_window_tokens\":{\"dev\":200000,\"max\":1000000},\"model_detail_list\":[{\"model_name\":\"fixture__max\",\"max_tokens\":64}]}]},{\"function\":\"solo_agent\",\"config_info_list\":[{\"config_name\":\"fixture\",\"context_window_tokens\":{\"dev\":200000,\"max\":1000000},\"model_detail_list\":[{\"model_name\":\"fixture__dev\",\"max_tokens\":8192}]}]}]}");
  JSONObject catalogResult=TraeModels.parse(catalog), maxMeta=catalogResult.getJSONObject("modelMeta").getJSONObject("fixture-max");
  JSONObject catalogPayload=new JSONObject(base.toString()).put("model","fixture-max");
  JSONObject catalogWire=TraeProtocol.request(catalogPayload,maxMeta.getString("function"),maxMeta.getString("modelName"),maxMeta.optInt("maxOutput"),"s",maxMeta.optBoolean("multimodal"),maxMeta.optJSONArray("levels"),maxMeta.optString("base"),maxMeta.optInt("contextWindow"),maxMeta.optBoolean("maxMode"));
  check(catalogResult.getJSONArray("models").toString().contains("fixture-max")&&catalogWire.getString("function").equals(maxMeta.getString("function")));
  check(catalogWire.getString("model").equals("fixture")&&catalogWire.getString("config_name").equals("fixture")&&catalogWire.getString("model_name").equals("fixture__max"));
  check(catalogWire.getInt("max_tokens")==64&&catalogWire.getJSONObject("user_message_context").getJSONObject("model_info").getInt("prompt_max_tokens")==999936);
  JSONObject missing=new JSONObject(req.toString());missing.getJSONArray("messages").remove(3);fails(()->TraeProtocol.request(missing,"chat","kimi",100,"s",true,null));
  JSONObject interrupted=new JSONObject(req.toString());interrupted.getJSONArray("messages").put(2,new JSONObject("{\"role\":\"user\",\"content\":\"next\"}"));fails(()->TraeProtocol.request(interrupted,"chat","kimi",100,"s",true,null));
  JSONObject badimg=new JSONObject(base.toString());badimg.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AB==\"}}]"));fails(()->TraeProtocol.request(badimg,"chat","m",100,"s",true,null));
  JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"type\":{\"enum\":[\"x\"]},\"$ref\":{\"type\":\"string\"}},\"example\":{\"$ref\":\"literal\",\"enum\":[1]}}");JSONArray kt=new JSONArray().put(new JSONObject().put("function",new JSONObject().put("name","run").put("parameters",schema)));JSONObject kr=new JSONObject(base.toString()).put("tools",kt);JSONObject ko=new JSONObject(TraeProtocol.request(kr,"chat","kimi",100,"s",true,null).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("parameters"));check(ko.getJSONObject("properties").getJSONObject("type").getString("type").equals("string"));check(!ko.getJSONObject("example").has("type"));
  TraeProtocol.Decoder parallel=new TraeProtocol.Decoder(tools,"m");parallel.accept("output","{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"a\\\":\"}},{\"index\":1,\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}");parallel.accept("output","{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"run\",\"arguments\":\"{\\\"a\\\":1}\"}},{\"index\":1,\"id\":\"y\"}]}");List<JSONObject> two=parallel.accept("stream.finish","{\"usage\":{\"prompt_tokens\":2}}");check(two.get(0).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").length()==2);check(parallel.usage.getBoolean("magpie_cache_unknown"));
  TraeProtocol.Decoder ix=new TraeProtocol.Decoder(tools,"m");fails(()->ix.accept("output","{\"tool_calls\":[{\"index\":0.5}]}"));
  TraeProtocol.Decoder conflict=new TraeProtocol.Decoder(tools,"m");conflict.accept("output","{\"tool_calls\":[{\"id\":\"x\",\"index\":0}]}");fails(()->conflict.accept("output","{\"tool_calls\":[{\"id\":\"x\",\"index\":1}]}"));
  TraeProtocol.Decoder noTools=new TraeProtocol.Decoder(new JSONArray(),"m");noTools.accept("output","{\"response\":\"<tool_call>{\\\"name\\\":\\\"run\\\",\\\"arguments\\\":{}}</tool_call>\"}");fails(()->noTools.accept("done","{}"));
  for(String[] test:new String[][]{{"{\"code\":4011,\"message\":\"requests have exceeded the rate limit\"}","4011","429"},{"{\"error\":{\"code\":1001,\"msg\":\"SECRET\"}}","1001","401"},{"{\"ResponseMetadata\":{\"Error\":{\"Code\":4023,\"Message\":\"SECRET\"}}}","4023","400"},{"{\"code\":\"SECRET-TOKEN\",\"message\":\"SECRET\"}","unknown","502"}}) {
   try { new TraeProtocol.Decoder(tools,"m").accept("error",test[0]); throw new AssertionError(); }
   catch(TraeProtocol.UpstreamError ex) {check(ex.code.equals(test[1]) && ex.status==Integer.parseInt(test[2]));check(!ex.safeMessage.contains("SECRET") && !ex.getMessage().contains("SECRET"));}
  }
  TraeProtocol.Decoder lateError=new TraeProtocol.Decoder(tools,"m");check(lateError.accept("output","{\"response\":\"hello\"}").size()==1);fails(()->lateError.accept("error","{\"code\":1,\"message\":\"bad\"}"));
  JSONArray levels=new JSONArray("[\"light\",\"high\",\"extra_high\"]");
  for(String[] pair:new String[][]{{"low","light"},{"light","light"},{"xhigh","extra_high"},{"extra_high","extra_high"},{"high","high"}}){JSONObject r=new JSONObject(base.toString()).put("reasoning_effort",pair[0]);check(TraeProtocol.request(r,"chat","m",100,"s",false,levels).getString("reasoning_effort").equals(pair[1]));}
  // Eta defaults to medium even when the selected model only offers light/high.
  for(String key:new String[]{"reasoning_effort","reasoningEffort"}) {
   for(JSONArray supported:new JSONArray[]{new JSONArray("[\"light\",\"high\"]"),new JSONArray(),null}) {
    for(String effort:new String[]{"medium","unsupported","xhigh"}) {
     JSONObject input=new JSONObject(base.toString()).put(key,effort);String original=input.toString();
     JSONObject fallback=TraeProtocol.request(input,"chat","m",100,"s",false,supported);
     check(!fallback.has("reasoning_effort")&&!fallback.has("reasoningEffort"));check(original.equals(input.toString()));
    }
   }
   check(!TraeProtocol.request(new JSONObject(base.toString()).put(key,"low"),"chat","m",100,"s",false,null).has("reasoning_effort"));
   check(TraeProtocol.request(new JSONObject(base.toString()).put(key,"medium"),"chat","m",100,"s",false,new JSONArray("[\"medium\"]")).getString("reasoning_effort").equals("medium"));
   check(TraeProtocol.request(new JSONObject(base.toString()).put(key,"HIGH"),"chat","m",100,"s",false,levels).getString("reasoning_effort").equals("high"));
   for(Object invalidEffort:new Object[]{JSONObject.NULL,1,true,new JSONObject(),new JSONArray(),""," "})fails(()->TraeProtocol.request(new JSONObject(base.toString()).put(key,invalidEffort),"chat","m",100,"s",false,levels));
  }
  fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("reasoning_effort","medium").put("reasoningEffort","high"),"chat","m",100,"s",false,levels));
  fails(()->TraeProtocol.request(new JSONObject(base.toString()).put("reasoning_effort",JSONObject.NULL),"chat","m",100,"s",false,levels));
  JSONObject strictReq=new JSONObject(base.toString()).put("tools",new JSONArray(tools.toString()));strictReq.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",false);check(!TraeProtocol.request(strictReq,"chat","m",100,"s",true,null).getJSONArray("tools").getJSONObject(0).getJSONObject("function").has("strict"));strictReq.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",true);fails(()->TraeProtocol.request(strictReq,"chat","m",100,"s",true,null));strictReq.put("tool_choice","none");fails(()->TraeProtocol.request(strictReq,"chat","m",100,"s",true,null));
  JSONObject emptyTools=new JSONObject(base.toString()).put("tools",new JSONArray()).put("tool_choice","auto");JSONObject emptyOut=TraeProtocol.request(emptyTools,"chat","m",100,"s",true,null);check(!emptyOut.has("tools")&&!emptyOut.has("tool_choice"));
  JSONObject history=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"system\"},{\"role\":\"user\",\"content\":\"who\"},{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"private thought\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{\\\"on\\\":true}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"done\"},{\"role\":\"user\",\"content\":\"next\"}]}").put("tools",tools);String histBefore=history.toString();JSONObject histOut=TraeProtocol.request(history,"chat","m",100,"s",true,null);check(histBefore.equals(history.toString()));check(!histOut.toString().contains("private thought"));JSONObject call=histOut.getJSONArray("messages").getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0);check(call.getString("id").equals("call-1"));check(call.getJSONObject("function_call").getString("arguments").equals("{\"on\":true}"));check(histOut.getJSONArray("messages").getJSONObject(3).getString("tool_call_id").equals("call-1"));
  history.getJSONArray("messages").getJSONObject(2).put("reasoning_content",JSONObject.NULL);TraeProtocol.request(history,"chat","m",100,"s",true,null);history.getJSONArray("messages").getJSONObject(2).put("reasoning_content",123);fails(()->TraeProtocol.request(history,"chat","m",100,"s",true,null));history.getJSONArray("messages").getJSONObject(2).remove("reasoning_content");history.getJSONArray("messages").getJSONObject(1).put("reasoning_content","invalid");fails(()->TraeProtocol.request(history,"chat","m",100,"s",true,null));
  JSONObject invalidType=new JSONObject(base.toString()).put("tools",new JSONArray(tools.toString()));invalidType.getJSONArray("tools").getJSONObject(0).put("type","other");fails(()->TraeProtocol.request(invalidType,"chat","m",100,"s",true,null));
  JSONObject badChoice=new JSONObject(base.toString()).put("tools",tools).put("tool_choice",new JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"run\",\"extra\":true}}"));fails(()->TraeProtocol.request(badChoice,"chat","m",100,"s",true,null));
  JSONObject badHistory=new JSONObject(req.toString());badHistory.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("strict",true);fails(()->TraeProtocol.request(badHistory,"chat","m",100,"s",true,null));
  TraeProtocol.Decoder progress=new TraeProtocol.Decoder(tools,"m");check(progress.accept("queue","{\"position\":2}").isEmpty());fails(()->progress.accept("queue","{\"response\":\"lost\"}"));fails(()->progress.accept("mystery","{}"));
  TraeProtocol.Decoder repeated=new TraeProtocol.Decoder(tools,"m");String deltaReason="{\"reasoning_content\":\"\",\"delta\":{\"reasoning\":\"abcdefgh\"}}";check(repeated.accept("output",deltaReason).size()==1);check(repeated.accept("output",deltaReason).size()==1);
  TraeProtocol.Decoder usage1=new TraeProtocol.Decoder(tools,"m");usage1.accept("token_usage","{\"prompt_tokens\":100,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":30}}");check(usage1.usage.getInt("prompt_tokens")==100&&usage1.usage.getJSONObject("prompt_tokens_details").getInt("cached_tokens")==30&&!usage1.usage.has("cache_read_input_tokens"));
  TraeProtocol.Decoder usage2=new TraeProtocol.Decoder(tools,"m");usage2.accept("token_usage","{\"input_tokens\":100,\"output_tokens\":5,\"cache_read_input_tokens\":30}");check(usage2.usage.getInt("prompt_tokens")==100&&usage2.usage.getInt("cache_read_input_tokens")==30);fails(()->usage2.accept("token_usage","{\"prompt_tokens\":-1}"));fails(()->usage2.accept("token_usage","{\"prompt_tokens\":1.5}"));
  TraeProtocol.Decoder snapshot=new TraeProtocol.Decoder(tools,"m");snapshot.accept("output","{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"run\",\"arguments\":\"{\\\"a\\\":1}\"}}]}");fails(()->snapshot.accept("output","{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"a\\\":2}\"}}]}"));
  TraeProtocol.Decoder truncatedCall=new TraeProtocol.Decoder(tools,"m");truncatedCall.accept("output","{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"run\",\"arguments\":\"{\"}}]}");fails(()->truncatedCall.accept("done","{\"finish_reason\":\"length\"}"));
  JSONObject ms=new JSONObject("{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"maximum\":5,\"$ref\":\"#/$defs/s0\"},\"u\":{\"type\":\"string\",\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]},\"ops\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"op\":{\"enum\":[\"add\",\"remove\"]},\"n\":{\"enum\":[1,2]},\"f\":{\"enum\":[1,2.5]},\"b\":{\"const\":true}}}}},\"$defs\":{\"s0\":{\"type\":\"number\"}},\"default\":{\"enum\":[1],\"$ref\":\"literal\"}}");
  JSONObject mr=new JSONObject(base.toString()).put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","edit").put("parameters",ms))));String orig=mr.toString();
  JSONObject mout=new JSONObject(TraeProtocol.request(mr,"chat","Kimi-K3",100,"s",true,null).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("parameters"));check(orig.equals(mr.toString()));
  JSONObject mp=mout.getJSONObject("properties");check(mp.getJSONObject("n").getString("type").equals("integer"));check(mp.getJSONObject("n").getInt("maximum")==5);check(!mp.getJSONObject("n").has("$ref"));check(mp.getJSONObject("u").getJSONArray("anyOf").length()==1);check(mout.getJSONArray("required").getString(0).equals("n"));check(!mout.getJSONObject("default").has("type"));
  JSONObject ops=mp.getJSONObject("ops").getJSONObject("items").getJSONObject("properties");check(ops.getJSONObject("op").getString("type").equals("string"));check(ops.getJSONObject("n").getString("type").equals("integer"));check(ops.getJSONObject("f").getString("type").equals("number"));check(ops.getJSONObject("b").getString("type").equals("boolean"));
  JSONObject other=new JSONObject(TraeProtocol.request(mr,"chat","glm-5",100,"s",true,null).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("parameters"));check(other.toString().equals(ms.toString()));
  JSONObject schemaConflict=new JSONObject(mr.toString());JSONObject cs=schemaConflict.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");cs.getJSONObject("$defs").getJSONObject("s0").put("maximum",3);fails(()->TraeProtocol.request(schemaConflict,"chat","kimi",100,"s",true,null));
  JSONObject historyText=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"read\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"body\"}]}");
  JSONObject textWire=TraeProtocol.request(historyText,"chat","m",100,"s",true,null);JSONArray textMessages=textWire.getJSONArray("messages");
  check(!textWire.has("tools"));check(textMessages.length()==3);
  check(textMessages.getJSONObject(1).getString("role").equals("assistant")&&!textMessages.getJSONObject(1).has("tool_calls"));
  check(textMessages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").equals("<tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"x\"}}</tool_call>"));
  check(textMessages.getJSONObject(2).getString("role").equals("user")&&!textMessages.getJSONObject(2).has("tool_call_id"));
  check(textMessages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text").equals("Result of read (call c1):\nbody"));
  JSONObject namedButNoTools=new JSONObject(historyText.toString()).put("tools",tools).put("tool_choice","none");
  check(!TraeProtocol.request(namedButNoTools,"chat","m",100,"s",true,null).has("tools"));
  JSONObject unnamedResult=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c9\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c9\",\"content\":\"empty\"}]}");
  check(TraeProtocol.request(unnamedResult,"chat","m",100,"s",true,null).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").startsWith("Result of read (call c9):"));
  JSONObject dualBudget=new JSONObject(base.toString()).put("max_tokens",100).put("max_completion_tokens",40);
  check(TraeProtocol.request(dualBudget,"chat","m",1000,"s",true,null).getInt("max_tokens")==40);

  // A call a model wrote into its text in Qwen's or Anthropic's tags is a call, as the plugin reads it
  // (opencode-trae-auth xmlcall.test.mjs): TextTools.xmlCall/xmlInvoke/paramArgs and quote awareness.
  JSONArray xmlTools=new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"compress\",\"parameters\":{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}}}},{\"type\":\"function\",\"function\":{\"name\":\"mcp__magpie-image__generation_result\",\"parameters\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}},{\"type\":\"function\",\"function\":{\"name\":\"bash\",\"parameters\":{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}}}]");
  String s1Shown="<function=compress><parameter=content>[{\"endId\": \"m03680\", \"startId\": \"m03485\", \"summary\": \"TASK AS OF THIS BLOCK（历史）: ...";
  String s1=s1Shown+"\"}]</parameter></function>";
  String s2="<function_calls><invoke name=\"mcp__magpie-image__generation_result\"> <parameter name=\"id\">image-6636-24</parameter> </invoke> <invoke name=\"mcp__magpie-image__generation_result\"> <parameter name=\"id\">image-6636-25</parameter> </invoke> <invoke name=\"mcp__magpie-image__generation_result\"> <parameter name=\"id\">image-6636-26</parameter> </invoke> </function_calls>";
  String[] s2Ids={"image-6636-24","image-6636-25","image-6636-26"};
  for(int size:new int[]{0,1,2,3,5,7,13,64}){
    String qArgs=oneCall(readText(xmlTools,s1,size),"compress");
    check(new JSONObject(qArgs).getJSONArray("content").getJSONObject(0).getString("endId").equals("m03680"),"a parameter the schema says is an array is its JSON: "+qArgs);
    for(String prefix:new String[]{"","先压缩一下上下文。\n"}){
      JSONObject r=readText(xmlTools,prefix+s1,size);
      check(r.getString("text").equals(prefix)&&r.isNull("error"),"the text before the call is kept as written: "+r);
      check(r.getJSONArray("calls").length()==1,"one call after the text: "+r.getJSONArray("calls"));
    }
    JSONObject many=readText(xmlTools,"取回三张图。\n"+s2+"\n",size);
    check(many.getString("text").equals("取回三张图。\n")&&many.isNull("error"),"three calls, nothing of them in the text: "+many);
    JSONArray calls=many.getJSONArray("calls");check(calls.length()==3,"three calls: "+calls);
    for(int k=0;k<3;k++){check(calls.getJSONArray(k).getString(0).equals("mcp__magpie-image__generation_result"),"call "+k+" is the tool named");check(calls.getJSONArray(k).getJSONObject(1).getString("id").equals(s2Ids[k]),"a parameter the schema says is a string is its text: "+calls.getJSONArray(k));}
    check(many.getJSONArray("finish").length()==1&&many.getJSONArray("finish").getString(0).equals("tool_calls"),"the reply finishes as one that called tools: "+many.getJSONArray("finish"));
  }
  for(int cut=1;cut<s2.length();cut++){JSONObject r=readPieces(xmlTools,s2.substring(0,cut),s2.substring(cut));check(r.isNull("error")&&r.getString("text").isEmpty()&&r.getJSONArray("calls").length()==3,"split at "+cut+" is still the three calls: "+r);}
  for(int cut=1;cut<s1.length();cut++){String cutArgs=oneCall(readPieces(xmlTools,s1.substring(0,cut),s1.substring(cut)),"compress");check(new JSONObject(cutArgs).getJSONArray("content").length()==1,"split at "+cut+" is still the call: "+cutArgs);}
  check(new JSONObject(oneCall(readText(xmlTools,"<function=bash><parameter=command>[1, 2]</parameter></function>",0),"bash")).getString("command").equals("[1, 2]"),"a value the schema says is a string stays its text");
  for(int size:new int[]{0,1,4}){
    JSONObject qwen=readText(xmlTools,"<tool_call>\n<function=bash>\n<parameter=command>\nls -la\n</parameter>\n</function>\n</tool_call>",size);
    check(qwen.getString("text").isEmpty()&&qwen.isNull("error")&&qwen.getJSONArray("calls").length()==1,"Qwen's form inside <tool_call> is the call: "+qwen);
    check(qwen.getJSONArray("calls").getJSONArray(0).getJSONObject(1).getString("command").equals("ls -la"),"its parameter, newlines off: "+qwen.getJSONArray("calls"));
    JSONObject twoInvokes=readText(xmlTools,"<function_calls>\n<invoke name=\"bash\">\n<parameter name=\"command\">ls</parameter>\n<invoke name=\"bash\">\n<parameter name=\"command\">pwd</parameter>\n</function_calls>",size);
    check(twoInvokes.getString("text").isEmpty()&&twoInvokes.isNull("error")&&twoInvokes.getJSONArray("calls").length()==2,"Anthropic's with the closes left off between invokes: "+twoInvokes);
    check(twoInvokes.getJSONArray("calls").getJSONArray(1).getJSONObject(1).getString("command").equals("pwd"),"the second call is read too: "+twoInvokes.getJSONArray("calls"));
  }
  for(String cut:new String[]{s1Shown,s2.substring(0,95),"<function_calls><invoke name=\"bash\"><parameter name=\"command\">ls -"}) for(int size:new int[]{0,1,6}){
    JSONObject r=readText(xmlTools,"Working.\n"+cut,size);
    check(r.getString("text").equals("Working.\n"),"a call cut short is never the answer: "+r.getString("text"));
    check(r.getJSONArray("calls").length()==0,"no half call is sent on: "+r.getJSONArray("calls"));
    check(!r.isNull("error")&&r.getString("error").contains("cut short"),"a call cut short ends the turn as a failure: "+r.opt("error"));
  }
  for(int size:new int[]{0,1,6}){
    // The plugin sends each call as it is read; this adapter buffers every call to the terminal event
    // and sends none of a turn that ends cut short, which is the safer of the two and is kept.
    JSONObject r=readText(xmlTools,s2.substring(0,200),size);
    check(r.getString("text").isEmpty(),"cut inside the second invoke: none of it is the answer: "+r.getString("text"));
    check(r.getJSONArray("calls").length()==0,"a turn cut short sends no call on: "+r.getJSONArray("calls"));
    check(!r.isNull("error")&&r.getString("error").contains("cut short"),"cut inside the second invoke ends the turn as a failure: "+r.opt("error"));
  }
  JSONObject closed=readText(xmlTools,s1Shown+"\"}]</parameter>",0);
  check(closed.isNull("error")&&closed.getJSONArray("calls").length()==1,"its parameter closed, the close of the function left off: the call: "+closed);
  check(closed.getString("text").isEmpty(),"and nothing of it in the text");
  for(int size:new int[]{0,1,5}){
    for(String text:new String[]{"<function_calls><invoke name=\"delete_everything\"><parameter name=\"path\">/</parameter></invoke></function_calls>","<function=nope><parameter=x>1</parameter></function>"}){
      JSONObject r=readText(xmlTools,text,size);
      check(r.getString("text").equals(text)&&r.getJSONArray("calls").length()==0&&r.isNull("error"),"a tool the request didn't declare is no call: "+r);
    }
    JSONObject none=readText(new JSONArray(),s2,size);
    check(none.getString("text").equals(s2)&&none.getJSONArray("calls").length()==0&&none.isNull("error"),"no tools declared at all: "+none);
  }
  for(int size:new int[]{0,1,5}){
    JSONObject named=readText(xmlTools,"<function_calls><invoke name=\"Bash\"><parameter name=\"command\">ls</parameter></invoke></function_calls>",size);
    check(named.isNull("error")&&named.getJSONArray("calls").length()==1&&named.getJSONArray("calls").getJSONArray(0).getString(0).equals("bash"),"a tool the request declares under another case is the one named: "+named);
  }
  // The plugin's judgment of prose (xmlcall.test.mjs, textcall.test.mjs): a tag the stream ended
  // inside, written in an inline code span or a fenced block, is shown as written, and a plain '<'
  // at the very end is text; neither ends the turn.
  for(String proseEnd:new String[]{
    "The tag is `<tool_call name",
    "The tag is `<tool_call",
    "Each call goes in a `<toolcall name",
    "Qwen writes `<function=bash",
    "Write `<function_calls",
    "Use `<invoke name=\"bash\"",
    "Example:\n```xml\n<invoke name=\"bash\"",
    "compares a <",
    "1 < 2 and 3 <",
    "a < b and c > d, <function_calls without a close"})
    for(int size:new int[]{0,1,2,3,5,8}){
      JSONObject r=readText(xmlTools,proseEnd,size);
      check(r.getString("text").equals(proseEnd),"'"+proseEnd+"' at size "+size+" is text as written: "+r.getString("text"));
      check(r.getJSONArray("calls").length()==0&&r.isNull("error"),"'"+proseEnd+"' names no call: "+r);
    }
  // A call's own open marker cut short still ends the turn, as before.
  for(String cut:new String[]{"The <tool_call","The <toolcall name","Run it <tool_call name=\"bash\">\n<parameter name=\"command\">ls"})
    for(int size:new int[]{0,2}){
      JSONObject r=readText(xmlTools,cut,size);
      check(!r.isNull("error"),"a marker cut short is not text: "+r.getString("text"));
      check(!r.getString("text").contains("<tool_call")&&!r.getString("text").contains("<toolcall"),"and never the answer: "+r.getString("text"));
    }
  // A tool named exactly as the request declares it is that tool, whatever its letters; a name that
  // normalizes to nothing, or to what two declared tools share, is no name (a wrong tool is worse).
  JSONArray cnTools=new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"\u8bfb\u53d6\u6587\u4ef6\",\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}}},{\"type\":\"function\",\"function\":{\"name\":\"bash\",\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}}}]");
  for(int size:new int[]{0,1,3,5}){
    JSONObject exact=readText(cnTools,"<function=\u8bfb\u53d6\u6587\u4ef6><parameter=x>1</parameter></function>",size);
    check(exact.isNull("error")&&exact.getJSONArray("calls").length()==1&&exact.getJSONArray("calls").getJSONArray(0).getString(0).equals("\u8bfb\u53d6\u6587\u4ef6"),"a tool declared under a name that is not Latin letters is that tool: "+exact);
    for(String undeclared:new String[]{"<function=\u5220\u9664\u6587\u4ef6><parameter=x>1</parameter></function>","<function=\u4efb\u52a1><parameter=x>1</parameter></function>","<function_calls><invoke name=\"\u5220\u9664\u6587\u4ef6\"><parameter name=\"x\">1</parameter></invoke></function_calls>"}){
      JSONObject r=readText(cnTools,undeclared,size);
      check(r.getJSONArray("calls").length()==0,"a tool the request never declared is not one it did: "+r.getJSONArray("calls"));
      check(r.getString("text").equals(undeclared)&&r.isNull("error"),"and stays the text it was: "+r);
    }
    JSONObject ambiguous=readText(new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}},{\"type\":\"function\",\"function\":{\"name\":\"readFile\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]"),"<invoke name=\"Read-File\"><parameter name=\"x\">1</parameter></invoke>",size);
    check(ambiguous.getJSONArray("calls").length()==0&&ambiguous.isNull("error"),"a name two declared tools share names neither: "+ambiguous);
  }
  JSONObject listed=readText(new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"list_files\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]"),"<function=list-files><parameter=x>1</parameter></function>",0);
  check(listed.isNull("error")&&listed.getJSONArray("calls").length()==1&&listed.getJSONArray("calls").getJSONArray(0).getString(0).equals("list_files"),"a name the request has one tool for, written another way, is that tool: "+listed);
  for(String prose:new String[]{"Claude wraps its calls in <function_calls> tags; each one is an <invoke> block.","Use `<function_calls><invoke name=\"bash\"><parameter name=\"command\">ls</parameter></invoke></function_calls>` to call it.","Example:\n```xml\n<function_calls>\n<invoke name=\"bash\">\n<parameter name=\"command\">ls</parameter>\n</invoke>\n</function_calls>\n```\nThat is the format.","Qwen writes `<function=bash>` and then its parameters.","The <invoke name=\"bash\"> tag names a tool, and the call ends later.","a < b and c > d, <function_calls without a close"})
    for(int size:new int[]{0,1,3,8}){JSONObject r=readText(xmlTools,prose,size);check(r.getString("text").equals(prose)&&r.getJSONArray("calls").length()==0&&r.isNull("error"),"prose that mentions the tags stays text: "+r);}

  System.out.println("TraeProtocolOfflineTest OK");
 }
}
