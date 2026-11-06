package com.gaeul.allin;
import org.json.*;
public final class TraeModelsOfflineTest {
 public static void main(String[] args)throws Exception {
  JSONObject batch=new JSONObject("{\"function_configs\":[{\"function\":\"chat_v3\",\"config_info_list\":[{\"config_name\":\"a\",\"context_window_tokens\":{\"dev\":100,\"max\":1000},\"model_detail_list\":[{\"model_name\":\"a__max\",\"max_tokens\":20}]},{\"config_name\":\"custom_model_1\"}]},{\"function\":\"solo_agent\",\"config_info_list\":[{\"config_name\":\"a\",\"multimodal\":true,\"reasoning_effort_config\":{\"options\":[\"light\",\"high\"]},\"model_detail_list\":[{\"model_name\":\"a__dev\",\"max_tokens\":10}]},{\"config_name\":\"b\"}]}]}");
  JSONObject result=TraeModels.parse(batch),meta=result.getJSONObject("modelMeta"), a=meta.getJSONObject("a");
  if(result.getJSONArray("models").length()!=2||!"solo_agent".equals(a.getString("function"))||!"a__dev".equals(a.getString("modelName"))||a.getInt("maxOutput")!=10||!a.getBoolean("multimodal")||a.getJSONArray("levels").length()!=2||!a.getJSONArray("levels").getString(0).equals("light")||a.getBoolean("maxSupported")||meta.has("a-max")||meta.getJSONObject("b").has("multimodal"))throw new AssertionError(result.toString());
  JSONObject vote=new JSONObject("{\"function_configs\":[{\"function\":\"chat_v3\",\"config_info_list\":[{\"config_name\":\"v41\",\"display_name\":\"official\",\"model_detail_list\":[{\"model_name\":\"bad__dev\"}]},{\"config_name\":\"official\",\"display_name\":\"official\"}]},{\"function\":\"solo_agent\",\"config_info_list\":[{\"config_name\":\"v41\",\"display_name\":\"v41\",\"context_window_tokens\":{\"dev\":123,\"max\":999},\"model_detail_list\":[{\"model_name\":\"good__dev\",\"max_tokens\":1.5}]}]},{\"function\":\"solo_coder\",\"config_info_list\":[{\"config_name\":\"v41\",\"display_name\":\"v41\",\"reasoning_effort_config\":{\"options\":[\"high\"]},\"model_detail_list\":[{\"model_name\":\"better__dev\",\"max_tokens\":2147483648}]}]}]}");
  JSONObject chosen=TraeModels.parse(vote).getJSONObject("modelMeta").getJSONObject("v41");
  if(!chosen.getString("function").equals("solo_coder")||chosen.has("maxOutput")||chosen.has("multimodal"))throw new AssertionError(chosen.toString());
  vote.getJSONArray("function_configs").getJSONObject(2).getJSONArray("config_info_list").getJSONObject(0).remove("reasoning_effort_config");
  chosen=TraeModels.parse(vote).getJSONObject("modelMeta").getJSONObject("v41");
  if(!chosen.getString("function").equals("solo_agent")||chosen.getInt("contextWindow")!=123||chosen.has("maxOutput"))throw new AssertionError(chosen.toString());
  boolean invalid=false;try{TraeModels.parse(new JSONObject("{\"function_configs\":[{\"function\":\"unknown\"}]}"));}catch(Exception e){invalid=true;}if(!invalid)throw new AssertionError("unknown schema accepted");
  System.out.println("TraeModelsOfflineTest OK");
 }
}
