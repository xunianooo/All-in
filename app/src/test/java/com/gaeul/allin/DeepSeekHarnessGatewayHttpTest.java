package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Harness-shaped inputs, synthetic credentials, and a local fake upstream only. */
public final class DeepSeekHarnessGatewayHttpTest {
    static final String OPENER="You are an AI agent powered by DeepSeek Harness.";
    static final String ADAPTED="You are an AI agent, powered by DeepSeek Harness.";
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class Result {int status;String body;String harness;}
    static Result post(int port,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer fixture-auth");c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("anthropic-version","2023-06-01");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        try {Result result=new Result();result.status=c.getResponseCode();result.harness=c.getHeaderField("X-AllIn-WorkBuddy-Harness");
            InputStream in=result.status>=400?c.getErrorStream():c.getInputStream();
            result.body=in==null?"":ResponsesGatewayOfflineTest.read(in);if(in!=null)in.close();return result;
        }finally{c.disconnect();}
    }
    static JSONObject chat(String provider,boolean stream) {
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","Original system instruction"))
                .put(new JSONObject().put("role","developer").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",OPENER))))
                .put(new JSONObject().put("role","user").put("content","hello"))
                .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL)
                    .put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-1").put("type","function")
                        .put("function",new JSONObject().put("name","lookup").put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content",OPENER))
                .put(new JSONObject().put("role","user").put("content","continue"));
        return new JSONObject().put("model",provider+"/m").put("stream",stream).put("max_tokens",100).put("messages",messages)
                .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","lookup")
                    .put("description",OPENER).put("parameters",new JSONObject().put("type","object")))));
    }
    static JSONObject messages(String provider,boolean stream) {
        return new JSONObject().put("model",provider+"/m").put("stream",stream).put("max_tokens",100)
                .put("system",new JSONArray().put(new JSONObject().put("type","text").put("text",OPENER)))
                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
    }
    static JSONObject responses(String provider,boolean stream) {
        return new JSONObject().put("model",provider+"/m").put("stream",stream).put("max_output_tokens",100)
                .put("input",new JSONArray().put(new JSONObject().put("role","developer").put("content",OPENER))
                        .put(new JSONObject().put("role","user").put("content","hello")));
    }
    static String sse() {
        return WorkBuddyStreamBlocksOfflineTest.chunk(new JSONObject().put("reasoning_content","thinking").put("tool_calls",new JSONArray()),"")
                +WorkBuddyStreamBlocksOfflineTest.chunk(new JSONObject().put("content","hello").put("reasoning_content","").put("tool_calls",new JSONArray()),"stop")+"data: [DONE]\n\n";
    }
    static boolean validChatRoles(JSONObject body) {
        JSONArray a=body.optJSONArray("messages");if(a==null)return false;
        for(int i=0;i<a.length();i++)if(!Arrays.asList("system","user","assistant","tool").contains(a.getJSONObject(i).optString("role")))return false;
        return true;
    }
    static void gateway(String provider,String path,boolean stream,boolean hide) throws Exception {
        boolean wb=provider.startsWith("workbuddy");
        try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((request,n)->{
            if(!validChatRoles(request.body))return new VendorPolicyGatewayHttpTest.Reply(400,"application/json","{\"error\":{\"code\":\"1214\",\"message\":\"角色信息不正确\"}}");
            if(wb) {
                for(Object raw:request.body.getJSONArray("messages")) {
                    JSONObject message=(JSONObject)raw;
                    if("system".equals(message.optString("role")) && message.opt("content").toString().contains(OPENER))
                        return new VendorPolicyGatewayHttpTest.Reply(400,"application/json","{\"code\":11128,\"msg\":\"Illegal API invocation from an unapproved channel\"}");
                }
                return new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",sse());
            }
            return new VendorPolicyGatewayHttpTest.Reply(200,stream?"text/event-stream":"application/json",stream?sse():VendorPolicyGatewayHttpTest.CHAT.replace("ok","hello"));
        })) {
            String base=wb?provider.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2":"https://open.bigmodel.cn/api/coding/paas/v4";
            JSONObject cfg=VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider(provider,"openai",base,"m").put("hideDsh",hide)).put("setup",new JSONObject().put("done",true));
            try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
                JSONObject request=path.endsWith("/messages")?messages(provider,stream):path.endsWith("/responses")?responses(provider,stream):chat(provider,stream);
                String original=request.toString();Result result=post(g.getListeningPort(),path,request);
                check(result.status==200,"harness HTTP "+provider+" "+path+" stream="+stream+" status="+result.status);
                check(request.toString().equals(original),"caller input preserved");check(u.requests.size()==1,"one upstream send, no compatibility retry");
                JSONObject sent=u.requests.get(0).body;check(validChatRoles(sent),"developer mapped before actual send");
                String all=sent.getJSONArray("messages").toString();check(all.contains(wb?"You are an AI agent.":OPENER),"system instruction retained");
                if(path.endsWith("/completions")) {
                    check(sent.getJSONArray("messages").getJSONObject(0).getString("content").equals("Original system instruction"),"multiple system order retained");
                    JSONObject expectedTools=new JSONObject(request.toString());if(wb)WorkBuddyHarnessPolicy.apply(expectedTools);
                    check(sent.getJSONArray("tools").similar(expectedTools.getJSONArray("tools")),"automatic descriptions rewritten");
                    check(sent.getJSONArray("messages").getJSONObject(3).similar(request.getJSONArray("messages").getJSONObject(3)),"tool call identity and arguments retained");
                    check(sent.getJSONArray("messages").getJSONObject(4).similar(request.getJSONArray("messages").getJSONObject(4)),"tool result text not rewritten");
                }
                check(result.body.contains("hello"),"answer survives conversion");
                if(stream)check(!result.body.contains("\"tool_calls\":[]"),"existing stream cleanup preserved");
            }
        }
    }
    static void rejectedNoReplay() throws Exception {
        for(boolean hide:new boolean[]{false,true})for(int status:new int[]{400,403})try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->
                new VendorPolicyGatewayHttpTest.Reply(status,"application/json","{\"code\":11128,\"msg\":\"Illegal API invocation from an unapproved channel\",\"retry_policy\":{\"retry\":false}}"))) {
            JSONObject cfg=VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("workbuddy","openai","https://copilot.tencent.com/v2","m").put("hideDsh",hide))
                    .put("setup",new JSONObject().put("done",true));
            try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
                Result result=post(g.getListeningPort(),"/v1/chat/completions",chat("workbuddy",false));
                check(result.status==status && u.requests.size()==(status==400?2:1),"explicit refusal bounded and 403 not replayed");
                check(result.body.contains("11128"),"refusal not disguised as success");
                check(result.harness!=null && result.harness.contains("messages=") && !result.harness.contains("DeepSeek"),"safe rule hit metadata on refusal");
            }
        }
    }
    static void gift() throws Exception {
        JSONObject cfg=VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("zcode","openai","https://open.bigmodel.cn/api/coding/paas/v4","m"))
                .put("setup",new JSONObject().put("done",true));
        cfg.getJSONArray("providers").getJSONObject(0).put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("jwt","fake-jwt").put("device","fake-device")));
        GatewayServer gateway=RoutingSetupOfflineTest.server(cfg);AtomicInteger calls=new AtomicInteger(),summaries=new AtomicInteger();
        gateway.setGiftTransportForTest(new GatewayServer.GiftTransport() {
            public JSONObject summary(String jwt,String device) {summaries.incrementAndGet();return new JSONObject().put("ok",true).put("buckets",new JSONArray().put(new JSONObject().put("remaining",10).put("capabilities",new JSONArray().put("model:m"))));}
            public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dressed) {
                calls.incrementAndGet();check(body.optString("system").contains(OPENER),"gift system includes former developer");
                for(Object raw:body.getJSONArray("messages"))check(Arrays.asList("user","assistant").contains(((JSONObject)raw).optString("role")),"gift native roles");
                ZCodeGift.Stream s=new ZCodeGift.Stream();s.status=200;
                s.is=new ByteArrayInputStream(VendorPolicyGatewayHttpTest.ANTH.getBytes(StandardCharsets.UTF_8));return s;
            }
        });
        JSONObject input=chat("zcode",false);
        NanoHTTPD.Response response=gateway.serve(GatewayAccessBoundaryOfflineTest.session("192.0.2.1","/v1/chat/completions","POST",input.toString(),Map.of("host","127.0.0.1:3425","authorization","Bearer fixture-auth")));
        String body=ResponsesGatewayOfflineTest.read(response.getData());check(response.getStatus().getRequestStatus()==200 && body.contains("ok"),"gift conversion successful");
        check(calls.get()==1 && summaries.get()==1,"gift sent once");
        input.getJSONArray("messages").getJSONObject(1).put("role","unknown");
        response=gateway.serve(GatewayAccessBoundaryOfflineTest.session("192.0.2.1","/v1/chat/completions","POST",input.toString(),Map.of("host","127.0.0.1:3425","authorization","Bearer fixture-auth")));
        check(GatewayAccessBoundaryOfflineTest.code(response)==400 && calls.get()==1 && summaries.get()==1,"unknown role rejected before gift discovery or send");
    }
    static void nativeSigned() throws Exception {
        try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->
                new VendorPolicyGatewayHttpTest.Reply(200,"application/json",VendorPolicyGatewayHttpTest.ANTH))) {
            JSONObject provider=VendorPolicyGatewayHttpTest.provider("zcode","anthropic","https://open.bigmodel.cn/api/anthropic/v1","m")
                .put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("jwt","fake-jwt").put("device","fake-device")));
            JSONObject cfg=VendorPolicyGatewayHttpTest.config(provider).put("setup",new JSONObject().put("done",true));
            try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
                AtomicInteger giftCalls=new AtomicInteger();
                g.gateway.setGiftTransportForTest(new GatewayServer.GiftTransport() {
                    public JSONObject summary(String a,String b){giftCalls.incrementAndGet();throw new AssertionError("signed history must skip gift discovery");}
                    public ZCodeGift.Stream open(String a,String b,String c,String d,JSONObject e,boolean f){throw new AssertionError("signed history must skip gift send");}
                });
                JSONArray signed=new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","previous thought").put("signature","fake-signature"))
                    .put(new JSONObject().put("type","text").put("text","previous answer"));
                JSONObject request=messages("zcode",false);
                request.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","previous question"))
                    .put(new JSONObject().put("role","assistant").put("content",signed))
                    .put(new JSONObject().put("role","user").put("content","continue")));
                Result result=post(g.getListeningPort(),"/v1/messages",request);
                check(result.status==200 && u.requests.size()==1 && giftCalls.get()==0,"signed native request delivered exactly once without gift");
                check(u.requests.get(0).body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").similar(signed),"native thinking and signature unchanged");
            }
        }
        AtomicInteger unexpected=new AtomicInteger();
        GatewayServer unsupported=RoutingSetupOfflineTest.server(VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("workbuddy","openai","https://copilot.tencent.com/v2","m"))
                .put("setup",new JSONObject().put("done",true)));
        unsupported.setVendorConnectionsForTests(url->{unexpected.incrementAndGet();throw new IOException("must reject protected conversion");});
        JSONObject request=messages("workbuddy",false);
        request.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","x").put("signature","fake-signature"))));
        NanoHTTPD.Response r=unsupported.serve(GatewayAccessBoundaryOfflineTest.session("192.0.2.1","/v1/messages","POST",request.toString(),Map.of("host","127.0.0.1:3425","authorization","Bearer fixture-auth")));
        check(GatewayAccessBoundaryOfflineTest.code(r)==400 && unexpected.get()==0,"protected WorkBuddy history not silently rewritten");
    }
    public static void main(String[] args)throws Exception {
        for(String provider:new String[]{"zcode","workbuddy","workbuddy-ai"})for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})for(boolean stream:new boolean[]{false,true})for(boolean hide:new boolean[]{false,true})gateway(provider,path,stream,hide);
        gift();nativeSigned();rejectedNoReplay();System.out.println("DeepSeekHarnessGatewayHttpTest passed (3 providers x 3 protocols x stream modes, gift, exact single send)");
    }
}
