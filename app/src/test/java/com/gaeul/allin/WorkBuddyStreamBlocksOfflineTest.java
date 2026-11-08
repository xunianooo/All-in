package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Recorded wire shape, synthetic text: empty tool arrays must not become client block boundaries. */
public final class WorkBuddyStreamBlocksOfflineTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String chunk(JSONObject delta,String finish) {
        return "data: "+new JSONObject().put("id","one-message").put("object","chat.completion.chunk").put("model","deepseek-v4.1-flash")
                .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish)))+"\n\n";
    }
    static String wire() {
        StringBuilder out=new StringBuilder();
        for(String text:new String[]{"Answer"," briefly"," in"," Chinese","."," No"," tool"," use"," needed","."})
            out.append(chunk(new JSONObject().put("role","assistant").put("content","").put("reasoning_content",text).put("tool_calls",new JSONArray()),""));
        for(String text:new String[]{"你好","，","这是","正文","。"})
            out.append(chunk(new JSONObject().put("content",text).put("reasoning_content","").put("tool_calls",new JSONArray()),""));
        return out.append(chunk(new JSONObject().put("content","").put("reasoning_content","").put("tool_calls",new JSONArray()),"stop")).append("data: [DONE]\n\n").toString();
    }
    static String relay(String input,boolean mask) throws Exception {
        return relay(input,mask?new PrivacyMasker.Session():null);
    }
    static String relay(String input,PrivacyMasker.Session session) throws Exception {
        boolean mask=session!=null;
        JSONObject cfg=new JSONObject().put("providers",new JSONArray()).put("privacy",new JSONObject().put("enabled",mask));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);
        Method m=GatewayServer.class.getDeclaredMethod("streamForwardResponse",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,java.net.HttpURLConnection.class,String.class);
        m.setAccessible(true);
        NanoHTTPD.Response r=(NanoHTTPD.Response)m.invoke(server,new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),session,null,null,"workbuddy/deepseek-v4.1-flash");
        try(InputStream in=r.getData()){return ResponsesGatewayOfflineTest.read(in);}
    }
    static List<JSONObject> chunks(String wire) {
        List<JSONObject> result=new ArrayList<>();
        for(String line:wire.split("\n"))if(line.startsWith("data:")&&!line.substring(5).trim().equals("[DONE]"))result.add(new JSONObject(line.substring(5).trim()));
        return result;
    }
    static List<JSONObject> events(String wire) {return chunks(wire);}
    static String string(InputStream stream)throws Exception{try(InputStream s=stream){return ResponsesGatewayOfflineTest.read(s);}}
    static void plain(boolean mask) throws Exception {
        String output=relay(wire(),mask);StringBuilder reasoning=new StringBuilder(),text=new StringBuilder();int boundaries=0;
        for(JSONObject chunk:chunks(output)) {
            JSONObject choice=chunk.getJSONArray("choices").getJSONObject(0),delta=choice.getJSONObject("delta");
            if(delta.has("tool_calls"))boundaries++;
            check(!"".equals(choice.opt("finish_reason")),"empty finish_reason is not a valid Chat stream terminal value");
            check(!delta.has("reasoning_content") || !"".equals(delta.opt("reasoning_content")),"empty reasoning field must not reopen client thinking");
            reasoning.append(delta.optString("reasoning_content",""));text.append(delta.optString("content",""));
        }
        check(boundaries==0,"empty tool arrays leaked as client block boundaries: "+boundaries);
        check(reasoning.toString().equals("Answer briefly in Chinese. No tool use needed."),"reasoning deltas preserved");
        check(text.toString().equals("你好，这是正文。"),"text deltas preserved");
        check(output.trim().endsWith("data: [DONE]"),"terminal preserved");
        // Both projected protocols should remain a single thinking item/block.
        AnthropicBridge.StreamState state=new AnthropicBridge.StreamState("workbuddy/deepseek-v4.1-flash");List<String> translated=new ArrayList<>();
        for(JSONObject c:chunks(output))translated.addAll(state.onChunk(c));translated.addAll(state.onEnd());
        int think=0,answer=0;
        for(JSONObject event:events(String.join("",translated)))if("content_block_start".equals(event.optString("type"))) {
            String type=event.getJSONObject("content_block").getString("type");if(type.equals("thinking"))think++;if(type.equals("text"))answer++;
        }
        check(think==1 && answer==1,"Messages has one thinking block and one answer block");
        String responses=string(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)),"workbuddy/deepseek-v4.1-flash"));
        int thoughtItems=0,textItems=0;
        for(JSONObject event:events(responses))if("response.output_item.added".equals(event.optString("type"))) {
            String type=event.getJSONObject("item").getString("type");if(type.equals("reasoning"))thoughtItems++;if(type.equals("message"))textItems++;
        }
        check(thoughtItems==1 && textItems==1,"Responses has one reasoning item and one answer item");
    }
    static void actualTools() throws Exception {
        JSONObject call=new JSONObject().put("index",0).put("id","tool-1").put("type","function").put("function",new JSONObject().put("name","lookup").put("arguments","{}"));
        String input=chunk(new JSONObject().put("tool_calls",new JSONArray().put(call)),"tool_calls")+"data: [DONE]\n\n";
        JSONObject actual=chunks(relay(input,true)).get(0).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0);
        check(actual.similar(call),"real tool identity and arguments preserved");
        for(String finish:new String[]{"length","content_filter","tool_calls","stop"}) {
            JSONObject preserved=chunks(relay(chunk(new JSONObject().put("reasoning_content","thought").put("tool_calls",new JSONArray()),finish)+"data: [DONE]\n\n",true)).get(0);
            check(finish.equals(preserved.getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"real finish preserved: "+finish);
        }
        String error=relay("data: {\"error\":{\"message\":\"synthetic rejection\"}}\n\n",true);
        check(error.contains("synthetic rejection") && !error.contains("data: [DONE]"),"explicit error remains error");
        String broken=relay(chunk(new JSONObject().put("reasoning_content","partial").put("tool_calls",new JSONArray()),""),true);
        check(broken.contains("upstream_error") && !broken.contains("data: [DONE]"),"missing terminal remains failure");
    }
    static String choices(JSONObject... choices) {
        JSONArray a=new JSONArray();for(JSONObject c:choices)a.put(c);
        return "data: "+new JSONObject().put("id","one-message").put("choices",a)+"\n\n";
    }
    static JSONObject choice(int index,String text,String finish) {
        return new JSONObject().put("index",index).put("delta",new JSONObject().put("reasoning_content",text).put("tool_calls",new JSONArray())).put("finish_reason",finish);
    }
    static void privacyFragments() throws Exception {
        PrivacyMasker.Session session=new PrivacyMasker.Session();
        String token=PrivacyMasker.maskText("alice@example.com",session,PrivacyMasker.Config.fromJson(new JSONObject()));
        check(!token.equals("alice@example.com"),"fixture actually has a mapped placeholder");
        int split=token.length()/2;
        String out=relay(choices(choice(0,token.substring(0,split),""))+choices(choice(0,token.substring(split),"stop"))+"data: [DONE]\n\n",session);
        StringBuilder text=new StringBuilder();for(JSONObject chunk:chunks(out))text.append(chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("reasoning_content",""));
        check(text.toString().equals("alice@example.com"),"empty finish never flushes partial privacy placeholder");
        String incomplete=relay(choices(choice(0,"complete","stop"),choice(1,token.substring(0,split),""))+"data: [DONE]\n\n",session);
        check(incomplete.contains("upstream_error") && !incomplete.contains("data: [DONE]"),"one finished choice cannot hide another incomplete choice");
        String complete=relay(choices(choice(0,"complete","stop"),choice(1,token.substring(0,split),""))
                +choices(choice(1,token.substring(split),"stop"))+"data: [DONE]\n\n",session);
        check(complete.contains("alice@example.com") && complete.trim().endsWith("data: [DONE]"),"all choices complete and privacy tail restored");
    }
    static void httpGateway() throws Exception {
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((request,n)->
                new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",wire()))) {
            JSONObject provider=VendorPolicyGatewayHttpTest.provider("workbuddy","openai","https://copilot.tencent.com/v2","deepseek-v4.1-flash");
            JSONObject cfg=VendorPolicyGatewayHttpTest.config(provider).put("setup",new JSONObject().put("done",true));
            try(VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(cfg,upstream,false)) {
                java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+gateway.getListeningPort()+"/v1/chat/completions").openConnection(java.net.Proxy.NO_PROXY);
                c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer fixture-auth");
                JSONObject body=VendorPolicyGatewayHttpTest.body("workbuddy/deepseek-v4.1-flash",true);
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=c.getOutputStream()){out.write(bytes);}
                check(c.getResponseCode()==200,"real gateway HTTP status");
                String output;try(InputStream in=c.getInputStream()){output=ResponsesGatewayOfflineTest.read(in);}finally{c.disconnect();}
                check(upstream.requests.size()==1,"exactly one fake upstream call");
                for(JSONObject event:chunks(output)) {
                    JSONObject choice=event.getJSONArray("choices").getJSONObject(0),delta=choice.getJSONObject("delta");
                    check(!"".equals(choice.opt("finish_reason")),"HTTP strict finish shape");
                    check(!delta.has("tool_calls"),"HTTP no empty tool boundary");
                    check(!"".equals(delta.opt("reasoning_content")),"HTTP no false reasoning start");
                }
                check(output.trim().endsWith("data: [DONE]"),"HTTP terminal retained");
            }
        }
    }
    public static void main(String[] args)throws Exception {plain(false);plain(true);actualTools();privacyFragments();httpGateway();System.out.println("WorkBuddyStreamBlocksOfflineTest passed (Chat empty-tool cleanup, Messages/Responses grouping, real tools, strict failure)");}
}
