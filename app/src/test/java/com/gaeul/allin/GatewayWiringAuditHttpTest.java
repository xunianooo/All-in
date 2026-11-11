package com.gaeul.allin;

import org.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback gateway contracts for provider request policy wiring. */
public final class GatewayWiringAuditHttpTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void expectNoDispatch(ResponsesGatewayOfflineTest.Mock upstream) throws Exception {
        upstream.server.close();
        upstream.worker.join(5000);
        check(!upstream.worker.isAlive(),"no-dispatch upstream listener stopped");
        check(upstream.received==null,"reasoning conflict does not dispatch or fallback");
        if(upstream.error instanceof SocketException) upstream.error=null;
        if(upstream.error!=null) throw new AssertionError(upstream.error);
    }
    static void cline() throws Exception {
        String model="cline-pass/deepseek-v4.1-flash";
        try(VendorPolicyGatewayHttpTest.Upstream up=new VendorPolicyGatewayHttpTest.Upstream((request,n)->
                new VendorPolicyGatewayHttpTest.Reply(200,"application/json",
                        "{\"data\":{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]},\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}"));
            VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(
                    VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("cline","openai","https://api.cline.bot/api/v1",model)),up,false)) {
            VendorPolicyGatewayHttpTest.Result response=VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",
                    VendorPolicyGatewayHttpTest.body("cline/"+model,false),"actual-cline-session");
            check(response.status==200,"Cline gateway HTTP: "+response.body);
            VendorPolicyGatewayHttpTest.Request sent=up.requests.get(0);
            check("actual-cline-session".equals(sent.headers.get("X-Task-ID")),"X-Task-ID follows actual session");
            check(!sent.body.has("providerOptions")&&!sent.body.has("provider"),"Cline routing remains upstream-owned by default");
            JSONObject result=new JSONObject(response.body);
            check(result.has("choices")&&!result.has("data"),"wrapped Cline response unwrapped");
            check(result.getJSONObject("usage").getInt("total_tokens")==10,"outer usage retained when unwrapping");
        }
    }
    static void loomy() throws Exception {
        try(VendorPolicyGatewayHttpTest.Upstream up=new VendorPolicyGatewayHttpTest.Upstream((request,n)->
                new VendorPolicyGatewayHttpTest.Reply(200,"application/json",VendorPolicyGatewayHttpTest.CHAT))) {
            JSONObject provider=new JSONObject().put("id","loomy").put("protocol","openai").put("baseUrl","https://loomyad.xunfei.cn/api/v1")
                    .put("accounts",new JSONArray().put(new JSONObject().put("id","account").put("token","fixture-token").put("enabled",true)))
                    .put("models",new JSONArray().put("deepseek-chat"));
            try(VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(VendorPolicyGatewayHttpTest.config(provider),up,false)) {
                String image="data:image/png;base64,QUJD";
                JSONObject body=VendorPolicyGatewayHttpTest.body("loomy/deepseek-chat",false);
                body.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray()
                        .put(new JSONObject().put("type","text").put("text","inspect"))
                        .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",image))));
                String before=body.toString();
                VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",body);
                check(result.status==200,"Loomy gateway HTTP: "+result.body);
                check(before.equals(body.toString()),"client request remains unchanged");
                JSONObject sent=up.requests.get(0).body;
                String content=sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text");
                check(content.equals("inspect\n<image_base64>"+image+"</image_base64>"),"legacy image is encoded in outbound copy");
            }
        }
    }
    static void anthropicBudget(String providerId,String protocol,String model,int guard,String mode,int expected) throws Exception {
        String reply="{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        try(ResponsesGatewayOfflineTest.Mock upstream=new ResponsesGatewayOfflineTest.Mock(reply,false)) {
            GatewayServer server=ResponsesGatewayOfflineTest.gateway(upstream.server.getLocalPort(),"openai");
            JSONObject cfg=ResponsesGatewayOfflineTest.config(server);
            JSONObject provider=cfg.getJSONArray("providers").getJSONObject(0);
            provider.put("id",providerId).put("protocol",protocol).put("models",new JSONArray().put(model));
            if("minimax-code".equals(providerId)) {
                provider.put("accounts",new JSONArray().put(new JSONObject().put("id","fixture-account").put("access","fixture-token").put("token","fixture-token").put("refresh","fixture-refresh").put("expires",System.currentTimeMillis()+3600000)));
                java.nio.file.Path dir=Files.createTempDirectory("budget-minimax-");
                ResponsesGatewayOfflineTest.set(server,"configFile",dir.resolve("config.json").toFile());
                ResponsesGatewayOfflineTest.set(server,"miniMaxJournal",MiniMaxTestJournal.fixture(dir.toFile()));
                server.setMiniMaxRefreshForTests(token->{ throw new AssertionError("fresh fixture token must not refresh"); });
            } else {
                provider.put("keys",new JSONArray().put("fixture-key"));
                if("zcode".equals(providerId)) provider.put("accounts",new JSONArray().put(new JSONObject().put("id","gift-account").put("jwt","fixture-jwt").put("device","fixture-device")));
            }
            if(guard>0) cfg.put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",guard));
            else cfg.put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",0));
            if("catalog".equals(mode)) provider.put("modelMeta",new JSONObject().put(model,new JSONObject().put("maxOutput",expected)));
            server.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+upstream.server.getLocalPort()+"/messages"));
            String route=providerId+"/"+model;
            JSONObject request=new JSONObject().put("model",route).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
            if("explicit".equals(mode)) request.put("max_tokens",7333);
            if("explicit-dual".equals(mode)) request.put("max_tokens",14000).put("max_completion_tokens",7333);
            if("explicit-above-cap".equals(mode)) request.put("max_tokens",600000);
            if("mini-effort".equals(mode)) request.put("max_tokens",40000).put("reasoning_effort","xhigh");
            if("mini-thinking-conflict".equals(mode)) request.put("max_tokens",40000).put("reasoning_effort","xhigh")
                    .put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",4096));
            if("mini-thinking-cap-conflict".equals(mode)) request.put("max_tokens",20000)
                    .put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",16000));
            if("message-thinking-guard-conflict".equals(mode)) request.put("max_tokens",20000)
                    .put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",8000));
            if("message-thinking-guard-compatible".equals(mode)) request.put("max_tokens",20000)
                    .put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",4000));
            if("messages".equals(mode) && "minimax-code".equals(providerId))
                request.put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",4000));
            if("mini-thinking-conflict".equals(mode) || "mini-thinking-cap-conflict".equals(mode)) {
                request.put("system","native system");
                request.getJSONArray("messages").getJSONObject(0).put("content",
                        new JSONArray().put(new JSONObject().put("type","text").put("text","hello")));
            }
            if("message-explicit".equals(mode)) request.put("max_tokens",7333);
            if("messages".equals(mode) || "message-explicit".equals(mode)) {
                if("messages".equals(mode)) request.remove("max_tokens");
            }
            if(mode.startsWith("response")) {
                request.remove("messages");request.put("input","hello").put("max_output_tokens",11111);
            }
            String uri=mode.startsWith("response")?"/v1/responses":(mode.startsWith("message")||"messages".equals(mode)?"/v1/messages":"/v1/chat/completions");
            if("zcode".equals(providerId)) server.setGiftTransportForTest(new GatewayServer.GiftTransport(){
                public JSONObject summary(String jwt,String device){return new JSONObject().put("ok",true).put("buckets",new JSONArray());}
                public ZCodeGift.Stream open(String jwt,String device,String site,String giftModel,JSONObject body,boolean dressed){throw new AssertionError("gift fallback fixture should not open");}
            });
            NanoHTTPD.Response result=server.serve(ResponsesGatewayOfflineTest.session(request.toString(),"secret",uri));
            String body=ResponsesGatewayOfflineTest.read(result.getData());
            boolean reject="mini-thinking-conflict".equals(mode) || "mini-thinking-cap-conflict".equals(mode)
                    || "message-thinking-guard-conflict".equals(mode);
            if(reject) {
                check(result.getStatus().getRequestStatus()==400,"reasoning conflict is a terminal 400 before upstream dispatch: "+body);
                if("mini-thinking-conflict".equals(mode))
                    check(body.contains("Conflicting explicit thinking and reasoning_effort controls"),"explicit reasoning conflict is described");
                else
                    check(body.contains("thinking.budget_tokens conflicts with final max_tokens"),"Guard budget conflict is described: "+body);
                expectNoDispatch(upstream);
                return;
            }
            check(result.getStatus().getRequestStatus()==(reject?400:200),"budget route "+route+" "+mode+" status="+result.getStatus().getRequestStatus()+" "+body);
            check(upstream.received!=null,"budget upstream captured "+route+" "+mode);
            check(upstream.received.optInt("max_tokens",-1)==expected,
                    "final Anthropic budget "+route+" "+mode+" expected="+expected+" actual="+upstream.received.optInt("max_tokens",-1)+" body="+upstream.received);
            if("mini-effort".equals(mode)) {
                check("adaptive".equals(upstream.received.getJSONObject("thinking").getString("type")),"MiniMax explicit effort uses adaptive thinking");
                check("xhigh".equals(upstream.received.getJSONObject("output_config").getString("effort")),"MiniMax explicit effort survives chat conversion");
            }
            if("minimax-code".equals(providerId) && "messages".equals(mode))
                check(upstream.received.getJSONObject("thinking").getInt("budget_tokens")==4000
                        && !upstream.received.has("output_config"),"native Messages preserves explicit thinking and does not invent output effort");
            if("message-thinking-guard-compatible".equals(mode))
                check(upstream.received.getJSONObject("thinking").getInt("budget_tokens")==4000,
                        "native Messages retains compatible original thinking after Guard lowers output cap");
        }
    }
    static int giftBudget(int guard,String mode) throws Exception {
        AtomicInteger sentBudget=new AtomicInteger();
        JSONObject giftConfig=ZCodeGiftUsageHttpTest.config(1);
        giftConfig.put("setup",new JSONObject().put("done",true)).put("privacy",new JSONObject().put("enabled",false))
                .put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",guard));
        GatewayServer gift=RoutingSetupOfflineTest.server(giftConfig);
        gift.setGiftTransportForTest(new GatewayServer.GiftTransport(){
            public JSONObject summary(String jwt,String device){return new JSONObject().put("ok",true).put("buckets",new JSONArray().put(new JSONObject().put("remaining",2).put("capabilities",new JSONArray().put("model:GLM-5.3-Flash"))));}
            public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dressed){sentBudget.set(body.optInt("max_tokens",-1));ZCodeGift.Stream r=new ZCodeGift.Stream();r.status=503;r.errorBody="fixture";return r;}
        });
        JSONObject request=new JSONObject().put("model",ZCodeGiftUsageHttpTest.MODEL)
                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")));
        if("explicit".equals(mode)) request.put("max_tokens",7333);
        gift.serve(RoutingSetupOfflineTest.request("127.0.0.1","/v1/chat/completions","POST",request.toString(),"fixture-gateway"));
        return sentBudget.get();
    }
    static void zcodePlanChat(String model,int guard,String mode,int expected) throws Exception {
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((request,n)->
                new VendorPolicyGatewayHttpTest.Reply(200,"application/json",VendorPolicyGatewayHttpTest.CHAT));
            VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(
                    VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("zcode","openai","https://open.bigmodel.cn/api/paas/v4",model)),upstream,false)) {
            org.json.JSONObject body=VendorPolicyGatewayHttpTest.body("zcode/"+model,false);
            if("explicit".equals(mode)) body.put("max_tokens",7333);
            else if("explicit-above-catalog".equals(mode)) body.put("max_tokens",200000);
            else body.remove("max_tokens");
            if(guard>0) ResponsesGatewayOfflineTest.config(gateway.gateway).put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",guard));
            VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",body);
            check(result.status==200,"ZCode Plan Chat status "+result.body);
            int actual=upstream.requests.get(0).body.optInt("max_tokens",-1);
            check(actual==expected,"ZCode Plan final Chat budget expected="+expected+" body="+upstream.requests.get(0).body);
        }
    }
    static void thinkingOnlyTruncationCompatibility() throws Exception {
        ZCodeAnthropic.StreamState state=new ZCodeAnthropic.StreamState("fixture-model",true);
        state.onEvent("content_block_delta",new JSONObject().put("type","content_block_delta").put("index",0)
                .put("delta",new JSONObject().put("type","thinking_delta").put("thinking","analysis")));
        java.util.List<String> chunks=state.onEvent("message_delta",new JSONObject().put("type","message_delta")
                .put("delta",new JSONObject().put("stop_reason","max_tokens")));
        boolean notice=false,normalFinish=false;
        for(String chunk:chunks) {
            JSONObject event=new JSONObject(chunk);
            JSONObject choice=event.optJSONArray("choices")==null?null:event.optJSONArray("choices").optJSONObject(0);
            JSONObject delta=choice==null?null:choice.optJSONObject("delta");
            if(delta!=null && ThinkingTruncationStream.NOTICE.equals(delta.optString("content"))) notice=true;
            if(choice!=null && "stop".equals(choice.optString("finish_reason"))) normalFinish=true;
        }
        check(notice&&normalFinish,"thinking-only truncation notice and compatibility finish remain intact");
    }
    static void budgets() throws Exception {
        check(giftBudget(0,"none")==128000,"ZCode GLM Flash gift catalog default");
        check(giftBudget(0,"explicit")==7333,"ZCode gift explicit limit preserved");
        check(giftBudget(6000,"none")==6000,"ZCode gift Guard cap preserved");
        zcodePlanChat("glm-5.3-flash",0,"none",-1);
        zcodePlanChat("glm-5.3",0,"none",-1);
        zcodePlanChat("glm-5.2",0,"none",-1);
        zcodePlanChat("glm-5.3-flash",6000,"none",6000);
        zcodePlanChat("glm-5.3-flash",0,"explicit",7333);
        zcodePlanChat("glm-5.3-flash",6000,"explicit",6000);
        zcodePlanChat("glm-5.3",0,"explicit-above-catalog",200000);

        anthropicBudget("zcode","anthropic","glm-5.3",0,"chat",128000);
        anthropicBudget("zcode","anthropic","glm-5.3",0,"messages",-1);
        anthropicBudget("zcode","anthropic","glm-5.3-flash",0,"chat",128000);
        anthropicBudget("zcode","anthropic","glm-5.3-flash",6000,"messages",6000);
        anthropicBudget("zcode","anthropic","glm-5.3-flash",0,"explicit",7333);
        anthropicBudget("zcode","anthropic","glm-5.3-flash",6000,"explicit",6000);
        anthropicBudget("zcode","anthropic","glm-5.3-flash",0,"explicit-dual",7333);
        anthropicBudget("zcode","anthropic","glm-5.3",0,"catalog",128000);
        anthropicBudget("zcode","anthropic","glm-5.3",0,"response",11111);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"chat",524288);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"catalog",524288);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",6000,"chat",6000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"explicit",7333);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"explicit-above-cap",600000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"mini-effort",40000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"response",11111);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",6000,"response-guard",6000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"messages",-1);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"mini-thinking-conflict",40000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",8000,"mini-thinking-cap-conflict",8000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",8000,"message-thinking-guard-conflict",8000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",8000,"message-thinking-guard-compatible",8000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",6000,"messages",6000);
        anthropicBudget("minimax-code","minimax","MiniMax-M3.1-Flash-Preview",0,"message-explicit",7333);
        anthropicBudget("minimax-code","minimax","unknown-model",0,"chat",16384);
    }
    public static void main(String[] args)throws Exception {
        UsageLedger.init(java.nio.file.Files.createTempDirectory("gateway-wiring-ledger-").toFile());
        cline(); loomy(); budgets(); thinkingOnlyTruncationCompatibility();
        JSONObject reasoningInput=new JSONObject().put("model","glm-5.3").put("reasoning_effort","max");
        JSONObject reasoningOutput=new JSONObject().put("max_tokens",40000);
        AnthropicReasoningPolicy.apply(reasoningInput,reasoningOutput,AnthropicReasoningPolicy.Provider.ZCODE);
        check(reasoningOutput.getJSONObject("thinking").getInt("budget_tokens")==32000,"ZCode effort projects to the translation budget");
        check("max".equals(reasoningOutput.getJSONObject("output_config").getString("effort")),"GLM-5.3 also carries the level in output_config.effort");
        JSONObject miniOutput=new JSONObject().put("max_tokens",524288);
        AnthropicReasoningPolicy.apply(new JSONObject().put("model","MiniMax-M3.1-Flash-Preview").put("reasoning_effort","xhigh"),miniOutput,AnthropicReasoningPolicy.Provider.MINIMAX);
        check("xhigh".equals(miniOutput.getJSONObject("output_config").getString("effort")),"MiniMax effort reaches Anthropic request");
        System.out.println("GatewayWiringAuditHttpTest passed");
    }
}
