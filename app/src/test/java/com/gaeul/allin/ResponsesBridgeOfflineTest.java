package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ResponsesBridgeOfflineTest {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static void rejects(String json, String part) throws Exception {
        try { ResponsesBridge.toChat(new JSONObject(json)); throw new AssertionError("accepted " + part); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains(part), expected.getMessage()); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject request = new JSONObject("{\"model\":\"p/m\",\"instructions\":\"rules\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"look\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,eA==\",\"detail\":\"high\"}]},{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":\"ok\"}],\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}],\"tool_choice\":{\"type\":\"function\",\"name\":\"f\"},\"max_output_tokens\":33,\"reasoning\":{\"effort\":\"high\"}}");
        JSONObject chat = ResponsesBridge.toChat(request);
        JSONArray messages = chat.getJSONArray("messages");
        check(messages.length() == 4 && messages.getJSONObject(0).getString("content").equals("rules"), "instructions");
        check(messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("detail").equals("high"), "image detail");
        check(messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id").equals("c1"), "call id");
        check(messages.getJSONObject(3).getString("tool_call_id").equals("c1"), "tool result id");
        check(chat.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name").equals("f"), "tool schema");
        check(chat.getInt("max_tokens") == 33 && chat.getString("reasoning_effort").equals("high"), "parameters");
        rejects("{\"input\":\"x\",\"previous_response_id\":\"r\"}", "previous_response_id");
        rejects("{\"input\":\"x\",\"store\":true}", "store=true");
        rejects("{\"input\":[{\"type\":\"web_search_call\"}]}", "web_search_call");
        rejects("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"file_id\":\"x\"}]}]}", "file_id");
        JSONObject reply = new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"refusal\":\"no\"},\"finish_reason\":\"stop\"}]}");
        JSONObject converted = ResponsesBridge.chatResponseToResponses(reply,"m");
        check(converted.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("refusal").equals("no"), "refusal json");
        check(ResponsesBridge.responsesResponseToChat(converted,"m").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("refusal").equals("no"), "refusal roundtrip");
        String stream="data: {\"choices\":[{\"delta\":{\"refusal\":\"no\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        java.io.InputStream is=ResponsesBridge.chatStreamToResponses(new java.io.ByteArrayInputStream(stream.getBytes("UTF-8")),"m");
        java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream(); int b; while((b=is.read())!=-1)buf.write(b);
        String events=buf.toString("UTF-8");
        check(events.contains("response.refusal.delta") && events.contains("response.refusal.done") && events.contains("response.completed"), "refusal SSE lifecycle");
        System.out.println("ResponsesBridgeOfflineTest OK");
    }
}
