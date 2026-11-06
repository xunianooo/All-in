package com.gaeul.allin;
import org.json.*;

public class ImageNativeContractOfflineTest {
 interface Action {void run() throws Exception;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void reject(Action a)throws Exception{try{a.run();}catch(IllegalArgumentException ok){return;}throw new AssertionError("expected rejection");}
 static JSONObject request(String source,String extras)throws Exception {
  return new JSONObject("{\"model\":\"m\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"+source+extras+"}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"signature-original\"},{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"result\"},{\"type\":\"image\",\"source\":"+source+extras+"}]}]}]}");
 }
 public static void main(String[] args)throws Exception {
  String source="{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"eA==\"}";
  JSONObject nativeRequest=request(source,",\"cache_control\":{\"type\":\"ephemeral\"}");String before=nativeRequest.toString();
  check(NativeAnthropicPolicy.requiresNative(nativeRequest),"requires native");
  reject(()->AnthropicBridge.requestToOpenAI(nativeRequest));
  JSONObject projection=AnthropicBridge.projectNativeRequest(nativeRequest);
  check(before.equals(nativeRequest.toString()),"original unchanged");
  NativeAnthropicPolicy.checkNativeRequired(nativeRequest,projection,projection,true);
  JSONObject direct=GatewayServer.nativeAnthropicRequest(nativeRequest,projection,projection,"new-model",false);
  check(direct!=null && GatewayServer.jsonEquivalent(nativeRequest.get("messages"),direct.get("messages")),"native exact messages");
  check(direct.toString().contains("signature-original") && direct.toString().contains("cache_control"),"signed and cache preserved");
  reject(()->NativeAnthropicPolicy.checkNativeRequired(nativeRequest,projection,projection,false));
  JSONObject changed=new JSONObject(projection.toString()); changed.getJSONArray("messages").getJSONObject(0).put("content","changed");
  reject(()->NativeAnthropicPolicy.checkNativeRequired(nativeRequest,projection,changed,true));
  JSONObject bad=request("{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"%%%\"}","");reject(()->AnthropicBridge.projectNativeRequest(bad));
  JSONObject opaque=request("{\"type\":\"file\",\"file_id\":\"opaque-file\"}",""); check(NativeAnthropicPolicy.requiresNative(opaque),"file requires native");reject(()->AnthropicBridge.requestToOpenAI(opaque)); JSONObject p=AnthropicBridge.projectNativeRequest(opaque);check(GatewayServer.nativeAnthropicRequest(opaque,p,p,"m",false).toString().contains("opaque-file"),"opaque source restored");
  JSONObject onlyImage=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"+source+",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}");check(NativeAnthropicPolicy.requiresNative(onlyImage),"opaque image without signed block");
  JSONObject system=new JSONObject("{\"messages\":[{\"role\":\"system\",\"content\":[{\"type\":\"image_url\",\"image_url\":\"https://example.invalid/x\"}]}]}");reject(()->ZCodeAnthropic.toAnthropic(system));
  JSONObject assistant=new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"image_url\",\"image_url\":\"https://example.invalid/x\"}]}]}");reject(()->ZCodeAnthropic.toAnthropic(assistant));
  try {ImageContent.keys(new JSONObject().put("PRIVATE_FIELD_VALUE",true),"type");throw new AssertionError();}catch(IllegalArgumentException e){check(!e.getMessage().contains("PRIVATE_FIELD_VALUE"),"field name leaked");}
  System.out.println("ImageNativeContractOfflineTest OK");
 }
}
