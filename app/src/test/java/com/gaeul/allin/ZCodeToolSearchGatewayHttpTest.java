package com.gaeul.allin;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Synthetic credentials and loopback upstream only; no external network or accounts. */
public final class ZCodeToolSearchGatewayHttpTest {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    static JSONObject obj(String s) { return new JSONObject(s); }
    static String event(String type, String fields) { return "event: " + type + "\ndata: {\"type\":\""+type+"\""+fields+"}\n\n"; }
    static String response(boolean stream, String name) {
        if (!stream) return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"model\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"opaque ToolSearchTool\",\"signature\":\"sig-ToolSearchTool\"},{\"type\":\"text\",\"text\":\"body ToolSearchTool\"},{\"type\":\"tool_use\",\"id\":\"call-123\",\"name\":\""+name+"\",\"input\":{\"query\":\"body ToolSearchTool 中文\"}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";
        return event("message_start", ",\"message\":{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"model\",\"content\":[],\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}")
            +event("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}")
            +event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"opaque ToolSearchTool\"}")
            +event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-ToolSearchTool\"}")
            +event("content_block_stop", ",\"index\":0")
            +event("content_block_start", ",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")
            +event("content_block_delta", ",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"body ToolSearchTool\"}")
            +event("content_block_stop", ",\"index\":1")
            +event("content_block_start", ",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-123\",\"name\":\""+name+"\",\"input\":{}}")
            +event("content_block_delta", ",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"body ToolSearchTool 中文\\\"}\"}")
            +event("content_block_stop", ",\"index\":2")
            +event("message_delta", ",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":7}")
            +event("message_stop", "");
    }
    static JSONObject nativeBody(String model, boolean stream, boolean conflict) {
        JSONObject b=obj("{\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"body ToolSearch\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"opaque ToolSearch\",\"signature\":\"sig-ToolSearch\"},{\"type\":\"tool_use\",\"id\":\"history-1\",\"name\":\"ToolSearch\",\"input\":{\"query\":\"body ToolSearch\"}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"history-1\",\"content\":\"result ToolSearch\"}]}],\"tools\":[{\"name\":\"ToolSearch\",\"description\":\"find ToolSearch\",\"defer_loading\":true,\"input_schema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}},{\"name\":\"Bash\",\"defer_loading\":true,\"input_schema\":{\"type\":\"object\"}},{\"name\":\"DeferredToolPlaceholder\",\"input_schema\":{\"type\":\"object\"}}],\"tool_choice\":{\"type\":\"tool\",\"name\":\"ToolSearch\"}}");
        b.put("model",model).put("stream",stream);
        if (conflict) b.getJSONArray("tools").put(obj("{\"name\":\"ToolSearchTool\",\"input_schema\":{\"type\":\"object\"}}"));
        return b;
    }
    static JSONObject chatBody(String model, boolean stream) {
        JSONObject b=obj("{\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"body ToolSearch\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"history-1\",\"type\":\"function\",\"function\":{\"name\":\"ToolSearch\",\"arguments\":\"{\\\"query\\\":\\\"body ToolSearch\\\"}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"history-1\",\"content\":\"result ToolSearch\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"ToolSearch\",\"description\":\"find ToolSearch\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}}}],\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"ToolSearch\"}}}");
        return b.put("model",model).put("stream",stream);
    }
    static JSONObject findTool(JSONArray a,String name) { for(int i=0;i<a.length();i++) { JSONObject t=a.optJSONObject(i); if(t!=null && name.equals(t.optString("name"))) return t; } return null; }
    static void verifyRequest(JSONObject sent, boolean changed, boolean nativeRequest, boolean protectedHistory) {
        JSONArray tools=sent.getJSONArray("tools"); String expected=changed?"ToolSearchTool":"ToolSearch";
        JSONObject tool=findTool(tools,expected); check(tool!=null,"declaration " + sent);
        check(tool.getJSONObject("input_schema").getJSONObject("properties").has("query"),"schema kept");
        if(nativeRequest) {
            check((findTool(tools,"DeferredToolPlaceholder")==null)==changed,"placeholder policy");
            check(tool.has("defer_loading")!=changed,"defer_loading policy");
        }
        check(expected.equals(sent.getJSONObject("tool_choice").getString("name")),"choice " + sent);
        JSONArray ms=sent.getJSONArray("messages"); JSONObject history=null,thinking=null;
        for(int i=0;i<ms.length();i++) {JSONObject m=ms.getJSONObject(i); if(!"assistant".equals(m.optString("role")))continue; JSONArray blocks=m.optJSONArray("content"); if(blocks!=null)for(int j=0;j<blocks.length();j++){JSONObject block=blocks.optJSONObject(j);if(block==null)continue;if("tool_use".equals(block.optString("type")))history=block;if("thinking".equals(block.optString("type")))thinking=block;}}
        check(history!=null && expected.equals(history.optString("name")),"history " + sent);
        check("history-1".equals(history.optString("id")) && "body ToolSearch".equals(history.getJSONObject("input").optString("query")),"history id/input");
        if(protectedHistory) check(thinking!=null && "sig-ToolSearch".equals(thinking.optString("signature")) && "opaque ToolSearch".equals(thinking.optString("thinking")),"signature preserved");
    }
    static void verifyResponse(String result, boolean nativeRequest, boolean stream, String expected) {
        check(result.contains("call-123") && result.contains("body ToolSearchTool") && result.contains("中文"),"response payload preserved " + result);
        if(!stream && nativeRequest) { JSONArray c=obj(result).getJSONArray("content"); check(expected.equals(c.getJSONObject(2).getString("name")),"native json tool name " + result); check("sig-ToolSearchTool".equals(c.getJSONObject(0).getString("signature")),"response signature"); check("body ToolSearchTool 中文".equals(c.getJSONObject(2).getJSONObject("input").getString("query")),"response input"); }
        else if(!stream) {JSONObject call=obj(result).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0); check(expected.equals(call.getJSONObject("function").getString("name")),"chat json name " + result); check("body ToolSearchTool 中文".equals(obj(call.getJSONObject("function").getString("arguments")).getString("query")),"chat arguments");}
        else { if(nativeRequest) check(result.contains("sig-ToolSearchTool") && result.contains("opaque ToolSearchTool"), "stream signature preserved"); boolean named=false; for(String line:result.split("\\r?\\n")) if(line.startsWith("data:")) {String data=line.substring(5).trim(); if(data.isEmpty()||data.equals("[DONE]"))continue; JSONObject v=obj(data); if(nativeRequest) {JSONObject block=v.optJSONObject("content_block"); if(block!=null && "tool_use".equals(block.optString("type"))) named=expected.equals(block.optString("name"));} else {JSONArray choices=v.optJSONArray("choices");if(choices!=null)for(int i=0;i<choices.length();i++){JSONObject delta=choices.getJSONObject(i).optJSONObject("delta");JSONArray calls=delta==null?null:delta.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject f=calls.getJSONObject(j).optJSONObject("function");if(f!=null&&expected.equals(f.optString("name")))named=true;}}}} check(named,"stream tool name " + result); }
    }
    static final class Gift implements GatewayServer.GiftTransport {
        final VendorPolicyGatewayHttpTest.Upstream upstream;
        final java.util.concurrent.atomic.AtomicInteger summaries = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        Gift(VendorPolicyGatewayHttpTest.Upstream u){upstream=u;}
        public JSONObject summary(String jwt,String device){summaries.incrementAndGet(); return obj("{\"ok\":true,\"buckets\":[{\"remaining\":1,\"capabilities\":[\"model:GLM-5.3-Flash\"]}]}");}
        public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dress) {
            opens.incrementAndGet();
            ZCodeGift.Stream s=new ZCodeGift.Stream();
            try { HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+upstream.server.getLocalPort()+"/gift").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}s.conn=c;s.status=c.getResponseCode();s.is=c.getInputStream();return s; } catch(IOException e){throw new IllegalStateException(e);}
        }
    }
    static void matrix(boolean gift, boolean nativeRequest, boolean stream, boolean conflict, boolean otherProvider) throws Exception {
        String provider=otherProvider?"other":"zcode", model=gift?"zcode/glm-5.3-flash":provider+"/model";
        boolean changed=!conflict && !otherProvider;
        try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((req,n)->new VendorPolicyGatewayHttpTest.Reply(200,req.body.optBoolean("stream")?"text/event-stream":"application/json",response(req.body.optBoolean("stream"),changed?"ToolSearchTool":"ToolSearch")))) {
            JSONObject cfg=gift?ZCodeGiftUsageHttpTest.config(u.server.getLocalPort()):VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider(provider,"anthropic","https://open.bigmodel.cn/api/anthropic/v1","model"));
            try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
                if(gift)g.gateway.setGiftTransportForTest(new Gift(u));
                JSONObject body=nativeRequest?nativeBody(model,stream,conflict):chatBody(model,stream);
                if(gift && nativeRequest) body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").remove(0);
                VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(g,nativeRequest?"/v1/messages":"/v1/chat/completions",body);
                String tag="gift="+gift+" native="+nativeRequest+" stream="+stream+" conflict="+conflict+" other="+otherProvider;
                check(result.status==200,tag+" status "+result.status+" "+result.body);
                check(u.requests.size()==1,tag+" one upstream; got "+u.requests.size());
                verifyRequest(u.requests.get(0).body,changed,nativeRequest,nativeRequest && !gift);
                verifyResponse(result.body,nativeRequest,stream,"ToolSearch");
            }
        }
    }
    // Without a native-capable candidate, protected history is rejected before Gift eligibility or network.
    // The safe Gift cases above do not establish support for protected Gift requests.
    static void protectedGiftRejected() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            try (VendorPolicyGatewayHttpTest.Upstream u = new VendorPolicyGatewayHttpTest.Upstream((req,n) ->
                    new VendorPolicyGatewayHttpTest.Reply(200, "application/json", response(false, "ToolSearchTool")))) {
                JSONObject cfg = ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
                try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
                    Gift transport = new Gift(u);
                    g.gateway.setGiftTransportForTest(transport);
                    VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(g, "/v1/messages", nativeBody("zcode/glm-5.3-flash",stream,false));
                    check(result.status == 400, "protected Gift without native candidate must be rejected: " + result.status + " " + result.body);
                    check(transport.summaries.get() == 0 && transport.opens.get() == 0, "protected request must not invoke Gift summary/open");
                    check(u.requests.isEmpty(), "protected request must not reach upstream");
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        for(boolean gift:new boolean[]{false,true}) for(boolean nativeRequest:new boolean[]{false,true}) for(boolean stream:new boolean[]{false,true}) matrix(gift,nativeRequest,stream,false,false);
        matrix(false,true,false,true,false);
        matrix(false,true,false,false,true);
        protectedGiftRejected();
        System.out.println("ZCodeToolSearchGatewayHttpTest passed");
    }
}
