package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;

public final class ResponsesAgentRequestOfflineTest {
    static void check(boolean ok,String what) { if(!ok) throw new AssertionError(what); }
    static final String CHAT = "{\"id\":\"chat1\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
    static final String NATIVE = "{\"id\":\"r\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
    static JSONObject agent() throws Exception { return new JSONObject("{\"model\":\"mock/m\",\"instructions\":\"rules\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hello\"},{\"type\":\"input_image\",\"image_url\":\"https://example.com/a.png\"}]},{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"think\"}]},{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":\"done\"},{\"role\":\"user\",\"content\":\"next\"}],\"user\":\"alice\",\"stream_options\":{\"include_usage\":true},\"text\":{\"verbosity\":\"medium\",\"format\":{\"type\":\"json_object\"}},\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}],\"tool_choice\":\"auto\",\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"store\":false,\"metadata\":{\"tag\":\"x\"},\"include\":[\"reasoning.encrypted_content\"],\"max_output_tokens\":100,\"foo_bar\":\"ignore\"}"); }
    static void requestCases() throws Exception {
        JSONObject req=agent(); String original=req.toString();
        try(ResponsesGatewayOfflineTest.Mock m=new ResponsesGatewayOfflineTest.Mock(CHAT,false)) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(m.server.getLocalPort());
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(original,"secret")); String body=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200,"chat HTTP "+body);
            JSONObject c=m.received; check(!c.has("foo_bar") && c.getString("user").equals("alice") && c.getInt("max_tokens")==12 && c.getString("reasoning_effort").equals("high"),"chat fields "+c);
            check(c.getJSONObject("response_format").getString("type").equals("json_object") && c.getJSONArray("tools").length()==1,"format/tool");
            JSONArray messages=c.getJSONArray("messages"); check(messages.toString().contains("image_url") && messages.toString().contains("reasoning_content") && messages.toString().contains("think") && messages.toString().contains("tool_call_id") && messages.toString().contains("done"),"history "+messages);
            check(r.getHeader("X-AllIn-Compatibility-Warning")!=null,"degradation warning");
        }
        check(original.equals(req.toString()),"caller mutated");
        try(ResponsesGatewayOfflineTest.Mock m=new ResponsesGatewayOfflineTest.Mock(NATIVE,false)) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(m.server.getLocalPort(),"responses");
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(original,"secret"));String body=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200,"native HTTP "+body); JSONObject n=m.received;
            check(!n.has("foo_bar") && n.getInt("max_output_tokens")==12 && n.getJSONObject("text").getString("verbosity").equals("medium") && n.getJSONObject("reasoning").getString("summary").equals("auto") && n.getJSONArray("input").length()==5,"native fields "+n);
        }
        try(ResponsesGatewayOfflineTest.Mock m=new ResponsesGatewayOfflineTest.Mock(NATIVE,false)) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(m.server.getLocalPort(),"responses");
            JSONObject limited=agent().put("max_tool_calls",2); NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(limited.toString(),"secret"));
            String body=ResponsesGatewayOfflineTest.read(r.getData()); check(r.getStatus().getRequestStatus()==200 && m.received.getInt("max_tool_calls")==2,"native tool limit "+body);
        }
        NanoHTTPD.Response limitedChat=ResponsesGatewayOfflineTest.gateway(1).serve(ResponsesGatewayOfflineTest.session(agent().put("max_tool_calls",2).toString(),"secret"));
        check(limitedChat.getStatus().getRequestStatus()==400,"cross protocol tool limit");
        GatewayServer blocked=ResponsesGatewayOfflineTest.gateway(1,"responses");
        ResponsesGatewayOfflineTest.config(blocked).put("middleware",new JSONObject().put("wordGuard",new JSONObject().put("enabled",true).put("mode","block").put("words",new JSONArray().put("hello"))));
        check(blocked.serve(ResponsesGatewayOfflineTest.session(original,"secret")).getStatus().getRequestStatus()==400,"native cannot bypass word guard");
        JSONObject chat=ResponsesBridge.toChat(req); JSONObject back=ResponsesBridge.chatToResponses(chat,"m");
        check(back.toString().contains("think") && back.toString().contains("function_call_output") && back.toString().contains("input_image") && back.getJSONObject("text").getJSONObject("format").getString("type").equals("json_object"),"protocol roundtrip "+back);
    }
    static void validation() throws Exception {
        String[] ok={"\"user\":null", "\"stream_options\":null", "\"text\":{\"verbosity\":\"low\"}", "\"background\":false", "\"background\":null", "\"previous_response_id\":null", "\"previous_response_id\":\"\"", "\"conversation\":null", "\"prompt\":null", "\"max_tool_calls\":null"};
        for(String f:ok) { JSONObject q=new JSONObject("{\"input\":\"x\","+f+"}"); ResponsesBridge.toChat(q); }
        String[] bad={"\"user\":1", "\"stream_options\":{\"include_usage\":1}", "\"text\":{\"verbosity\":\"bad\"}", "\"background\":true", "\"previous_response_id\":\"r\"", "\"conversation\":{}", "\"prompt\":{}", "\"max_tool_calls\":0", "\"max_tool_calls\":1.5", "\"reasoning\":{\"summary\":\"bad\"}", "\"temperature\":3"};
        for(String f:bad) { NanoHTTPD.Response r=ResponsesGatewayOfflineTest.gateway(1).serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"x\","+f+"}","secret")); check(r.getStatus().getRequestStatus()==400,"expected 400 "+f); }
        JSONObject limit=new JSONObject("{\"input\":\"x\",\"max_tool_calls\":2}"); check(ResponsesBridge.nativeOnlyReason(limit).contains("native"),"tool limit restriction");
        JSONObject enc=new JSONObject("{\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"cipher\",\"summary\":[]}]}");check(ResponsesBridge.nativeOnlyReason(enc).contains("encrypted"),"encrypted restriction");
    }
    static void streaming() throws Exception {
        String event="data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        try(ResponsesGatewayOfflineTest.Mock m=new ResponsesGatewayOfflineTest.Mock(event,true)) { JSONObject req=agent().put("stream",true); GatewayServer g=ResponsesGatewayOfflineTest.gateway(m.server.getLocalPort()); NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(req.toString(),"secret"));String body=ResponsesGatewayOfflineTest.read(r.getData());check(r.getStatus().getRequestStatus()==200 && body.contains("response.output_text.delta") && body.contains("response.completed"),"SSE "+body);check(m.received.getJSONObject("stream_options").getBoolean("include_usage"),"stream options"); }
    }
    public static void main(String[] args) throws Exception { requestCases(); validation(); streaming(); System.out.println("ResponsesAgentRequestOfflineTest OK"); }
}
