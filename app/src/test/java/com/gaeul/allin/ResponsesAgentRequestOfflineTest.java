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
        String event="data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: {\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\ndata: [DONE]\n\n";
        try(ResponsesGatewayOfflineTest.Mock m=new ResponsesGatewayOfflineTest.Mock(event,true,200,data->{JSONObject o=data.optJSONObject("stream_options");return o!=null && o.optBoolean("include_usage") && !o.has("include_obfuscation");})) { JSONObject req=agent().put("stream",true).put("stream_options",new JSONObject().put("include_usage",true).put("include_obfuscation",true)); GatewayServer g=ResponsesGatewayOfflineTest.gateway(m.server.getLocalPort()); NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(req.toString(),"secret"));String body=ResponsesGatewayOfflineTest.read(r.getData());check(r.getStatus().getRequestStatus()==200 && body.contains("response.output_text.delta") && body.contains("response.completed") && body.contains("input_tokens") && body.contains("output_tokens"),"SSE usage "+body);check(m.received.getJSONObject("stream_options").getBoolean("include_usage") && m.received.getInt("max_tokens")==12,"stream options and guard"); }
    }
    static JSONObject reason(String text) throws Exception { return new JSONObject().put("type","reasoning").put("summary",new JSONArray().put(new JSONObject().put("type","summary_text").put("text",text))); }
    static JSONObject call(String id) throws Exception { return new JSONObject().put("type","function_call").put("call_id",id).put("name","f").put("arguments","{}"); }
    static JSONObject result(String id) throws Exception { return new JSONObject().put("type","function_call_output").put("call_id",id).put("output","result-"+id); }
    static void auditCases() throws Exception {
        JSONObject options=new JSONObject().put("include_usage",true).put("include_obfuscation",true).put("future_option",42);
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","start"))
            .put(reason("first")).put(call("a")).put(reason("second")).put(call("b")).put(result("a")).put(result("b"))
            .put(new JSONObject().put("role","assistant").put("content","previous answer")).put(new JSONObject().put("role","user").put("content","another"))
            .put(reason("third")).put(call("c")).put(result("c")).put(new JSONObject().put("role","user").put("content","finish"));
        JSONObject req=new JSONObject().put("model","mock/m").put("input",history).put("max_output_tokens",100).put("stream_options",options).put("safety_identifier","safety-1").put("foo_bar",123);
        String before=req.toString();
        try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(CHAT,false,200, data->{ JSONObject o=data.optJSONObject("stream_options"); return o!=null && o.optBoolean("include_usage") && !o.has("include_obfuscation") && !o.has("future_option") && !data.has("foo_bar"); })) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort()); NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(before,"secret")); String body=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200,"strict chat endpoint "+body); JSONArray m=mock.received.getJSONArray("messages");
            check(mock.received.getInt("max_tokens")==12 && mock.received.getString("safety_identifier").equals("safety-1"),"guard and safety");
            JSONObject assistant=m.getJSONObject(1); JSONArray calls=assistant.getJSONArray("tool_calls");
            check(calls.length()==2 && calls.getJSONObject(0).getString("id").equals("a") && calls.getJSONObject(1).getString("id").equals("b") && assistant.getString("reasoning_content").contains("first") && assistant.getString("reasoning_content").contains("second"),"parallel tool round "+m);
            check(m.getJSONObject(2).getString("tool_call_id").equals("a") && m.getJSONObject(3).getString("tool_call_id").equals("b"),"both results "+m);
            check(m.getJSONObject(4).getString("content").equals("previous answer") && m.getJSONObject(5).getString("role").equals("user") && m.getJSONObject(6).getJSONArray("tool_calls").getJSONObject(0).getString("id").equals("c") && m.getJSONObject(7).getString("tool_call_id").equals("c"),"turn boundaries "+m);
            check(r.getHeader("X-AllIn-Compatibility-Warning").contains("unknown-fields-ignored"),"unknown warning");
        }
        check(before.equals(req.toString()),"normalization mutates original");
        try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(NATIVE,false,200,data->{JSONObject o=data.optJSONObject("stream_options");return o!=null && o.optBoolean("include_obfuscation") && !o.has("include_usage") && !o.has("future_option") && !data.has("foo_bar") && data.optInt("top_logprobs")==2 && data.has("client_metadata") && data.has("prompt_cache_retention");})) {
            JSONObject n=new JSONObject(before).put("top_logprobs",2).put("client_metadata",new JSONObject().put("client","local")).put("prompt_cache_retention","24h");
            NanoHTTPD.Response r=ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(),"responses").serve(ResponsesGatewayOfflineTest.session(n.toString(),"secret")); String body=ResponsesGatewayOfflineTest.read(r.getData());check(r.getStatus().getRequestStatus()==200,"strict native endpoint "+body);check(mock.received.getInt("max_output_tokens")==12 && mock.received.getString("safety_identifier").equals("safety-1"),"native guard and identity");
        }
        JSONObject chat=new JSONObject().put("model","m").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","x"))).put("stream_options",options).put("user","who");
        JSONObject converted=ResponsesBridge.chatToResponses(chat,"m"); check(converted.getJSONObject("stream_options").length()==1 && converted.getJSONObject("stream_options").getBoolean("include_obfuscation") && converted.getString("user").equals("who"),"chat to responses options "+converted);
        try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(NATIVE,false,200,data->{JSONObject o=data.optJSONObject("stream_options"); return o!=null && o.optBoolean("include_obfuscation") && !o.has("include_usage") && !o.has("future_option");})) {
            JSONObject chatReq=new JSONObject(chat.toString()).put("model","mock/m");
            NanoHTTPD.Response r=ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(),"responses").serve(ResponsesGatewayOfflineTest.session(chatReq.toString(),"secret","/v1/chat/completions"));
            String body=ResponsesGatewayOfflineTest.read(r.getData()); check(r.getStatus().getRequestStatus()==200,"strict converted responses endpoint "+body);
        }
        String[] nullable={"store","max_output_tokens","temperature","top_p","parallel_tool_calls","reasoning","instructions","text","service_tier","prompt_cache_key","include","tools","tool_choice","user","metadata","stream","stream_options"};
        JSONObject nullRequest=new JSONObject().put("model","mock/m").put("input","x");for(String key:nullable)nullRequest.put(key,JSONObject.NULL);
        try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(CHAT,false,200,data->{for(String key:nullable) if(data.has(key)) return false; return true;})) {
            NanoHTTPD.Response r=ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort()).serve(ResponsesGatewayOfflineTest.session(nullRequest.toString(),"secret"));String body=ResponsesGatewayOfflineTest.read(r.getData()); check(r.getStatus().getRequestStatus()==200,"nullable HTTP "+body);
        }
        String[] bad={"\"stream\":\"true\"","\"stream_options\":{\"include_obfuscation\":1}","\"safety_identifier\":42","\"top_logprobs\":21","\"prompt_cache_retention\":\"bad\"","\"client_metadata\":[]","\"context_management\":[{}]","\"previous_response_id\":\"resp\"","\"conversation\":{}","\"prompt\":{}"};
        for(String f:bad) { NanoHTTPD.Response r=ResponsesGatewayOfflineTest.gateway(1).serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"x\","+f+"}","secret"));check(r.getStatus().getRequestStatus()==400,"invalid "+f); }
        for(String field:new String[]{"top_logprobs","prompt_cache_retention","client_metadata","access_programs"}) { JSONObject q=new JSONObject().put("input","x"); q.put(field,field.equals("top_logprobs")?2:field.equals("prompt_cache_retention")?"24h":new JSONObject().put("id","x"));check(ResponsesBridge.nativeOnlyReason(q).contains(field),"semantic native only "+field); }
    }
    public static void main(String[] args) throws Exception { UsageLedger.init(java.nio.file.Files.createTempDirectory("allin-agent-ledger").toFile()); requestCases(); validation(); streaming(); auditCases(); System.out.println("ResponsesAgentRequestOfflineTest OK"); }
}
