package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** No unsupported DeepSeek schema rewriting may be introduced into protocol bridges. */
public final class CommercialToolSchemaPreservationOfflineTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static JSONObject parameters(JSONObject request) throws Exception {
        return request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
    }
    static boolean same(Object a,Object b)throws Exception {
        if(a instanceof JSONObject && b instanceof JSONObject) {JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;java.util.Iterator<String> keys=x.keys();while(keys.hasNext()){String k=keys.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
        if(a instanceof JSONArray && b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
        return a==null?b==null:a.equals(b);
    }
    public static void main(String[] args) throws Exception {
        JSONObject schema = new JSONObject("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"properties\":{\"$schema\":{\"type\":\"string\",\"const\":\"literal\"},\"nested\":{\"type\":\"object\",\"additionalProperties\":false,\"examples\":[{\"$schema\":\"user-data\"}]}},\"additionalProperties\":false}");
        JSONObject anthropic = new JSONObject().put("model", "deepseek/deepseek-chat").put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).put("tools", new JSONArray().put(new JSONObject().put("name", "inspect").put("input_schema", new JSONObject(schema.toString()))));
        JSONObject responses = new JSONObject().put("model", "deepseek/deepseek-chat").put("input", "hi").put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("name", "inspect").put("parameters", new JSONObject(schema.toString()))));
        check(same(parameters(AnthropicBridge.requestToOpenAI(anthropic)),schema), "Anthropic tool schema changed");
        check(same(parameters(ResponsesBridge.toChat(responses)),schema), "Responses tool schema changed");
        System.out.println("CommercialToolSchemaPreservationOfflineTest passed");
    }
}
