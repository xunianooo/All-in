package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loopback contracts for the signed reply limit a schema 2 document supplies to an Anthropic
 * <em>translation</em> ({@code ProviderQuirks.Snapshot.outputDefault}).
 *
 * <p>Every case goes through a real {@code NanoHTTPD} listener that serves the gateway, and a
 * socket-level mock upstream that records the bytes the vendor would actually receive. Nothing is
 * asserted about internal state: the expectation is always the {@code max_tokens} in the outbound
 * body, because that is the only place the value matters.
 *
 * <p>The paths covered are the three that translate a request into Anthropic Messages and can
 * therefore fall back to the generic {@code 16384} constant:
 *
 * <ul>
 *   <li>{@code forwardMiniMax}: generic Anthropic destinations and MiniMax Code;</li>
 *   <li>{@code tryZCodeGiftOpenAI}: the ZCode gift channel, an Anthropic Messages stream under the
 *       hood;</li>
 *   <li>{@code forwardWorkBuddyNative}: the domestic WorkBuddy Claude relay, when the client spoke
 *       Chat and the body is converted.</li>
 * </ul>
 *
 * <p>And the paths where a rule must do nothing: a client-declared limit, the real catalog, a
 * verified or built-in capability, Guard, a native direct Messages request, and plain ZCode chat.
 */
public final class AnthropicOutputDefaultQuirksHttpTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}

    static final int RULE=40000, CLIENT=7333, CATALOG=8192, GUARD=20000, FALLBACK=16384;
    static final String ZCODE_MODEL="glm-9-unknown";
    static final String ZCODE_BUILTIN_MODEL="glm-5.3";
    static final String MINIMAX_MODEL="MiniMax-M4";
    static final String WORKBUDDY_MODEL="claude-opus-5.5";
    static final String GIFT_MODEL="glm-9-gift";

    static final String ANTHROPIC_REPLY="{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\","
            +"\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
            +"\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    static final String ANTHROPIC_SSE="event: message_start\ndata: {\"type\":\"message_start\",\"message\":"
            +"{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
            +"\"usage\":{\"input_tokens\":1}}}\n\n"
            +"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
            +"\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
            +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
            +"\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
            +"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
            +"event: message_delta\ndata: {\"type\":\"message_delta\","
            +"\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n"
            +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    // ------------------------------------------------------------------ documents

    /** One exact provider/model pair, the only shape the table allows. */
    static JSONObject defaultOf(String provider,String model,int maxOutput){
        return new JSONObject().put("provider",provider).put("model",model).put("maxOutput",maxOutput);
    }
    /** A schema 2 document; {@code defaults} empty means "signed but names no limit here". */
    static ProviderQuirks.Snapshot document(String version,JSONObject... defaults)throws Exception{
        JSONArray list=new JSONArray();
        for(JSONObject d:defaults)list.put(d);
        JSONObject root=new JSONObject().put("schemaVersion",2).put("minimumEngineVersion",2)
                .put("ruleVersion",version).put("rules",new JSONArray()).put("outputDefaults",list);
        return ProviderQuirks.parse(root.toString().getBytes(StandardCharsets.UTF_8));
    }
    static ProviderQuirks.Snapshot rule(String provider,String model)throws Exception{
        return document("3.0.0",defaultOf(provider,model,RULE));
    }
    static ProviderQuirks.Snapshot noRule()throws Exception{
        return document("3.0.1");
    }

    // ------------------------------------------------------------------ harness

    static JSONObject provider(String id,String protocol,String base,String model){
        return new JSONObject().put("id",id).put("protocol",protocol).put("baseUrl",base)
                .put("models",new JSONArray().put(model));
    }
    /** param-override, expressed exactly as the dashboard saves it. */
    static JSONObject middlewareSet(int maxTokens){
        return new JSONObject().put("paramOverride",
                new JSONObject().put("set",new JSONObject().put("max_tokens",maxTokens)));
    }
    static JSONObject middlewareCap(int maxTokens){
        return new JSONObject().put("paramOverride",new JSONObject().put("maxTokensCap",maxTokens));
    }
    static JSONObject config(JSONObject provider,int guardCap){
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("privacy",new JSONObject().put("enabled",false))
                .put("providers",new JSONArray().put(provider))
                .put("routing",new JSONObject().put("groupsEnabled",true))
                .put("failover",new JSONObject().put("enabled",true).put("maxTargets",1).put("maxAttempts",1));
        cfg.put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",Math.max(0,guardCap)));
        return cfg;
    }
    static JSONObject chatBody(String route,Integer maxTokens){
        JSONObject body=new JSONObject().put("model",route).put("stream",false)
                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        if(maxTokens!=null)body.put("max_tokens",maxTokens.intValue());
        return body;
    }
    static JSONObject messagesBody(String route,Integer maxTokens){
        JSONObject body=new JSONObject().put("model",route).put("stream",false)
                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        if(maxTokens!=null)body.put("max_tokens",maxTokens.intValue());
        return body;
    }

    /** One gateway listener, one mock upstream, and the rule snapshot this request is admitted with. */
    static final class Session implements AutoCloseable {
        final VendorPolicyGatewayHttpTest.Gateway gateway;
        final VendorPolicyGatewayHttpTest.Upstream upstream;
        private final Path journalDir;
        Session(JSONObject cfg,VendorPolicyGatewayHttpTest.Upstream upstream,ProviderQuirks.Snapshot snapshot,
                boolean minimaxLease)throws Exception{
            this.upstream=upstream;
            this.gateway=new VendorPolicyGatewayHttpTest.Gateway(cfg,upstream,false);
            // The snapshot the gateway would have admitted with; the asset loader must stay out of it,
            // because a snapshot is already present and no package context exists off-device.
            ResponsesGatewayOfflineTest.set(gateway.gateway,"quirksSnapshot",snapshot);
            if(minimaxLease){
                journalDir=Files.createTempDirectory("quirks-output-minimax-");
                ResponsesGatewayOfflineTest.set(gateway.gateway,"miniMaxJournal",
                        MiniMaxTestJournal.fixture(journalDir.toFile()));
                gateway.gateway.setMiniMaxRefreshForTests(token->{
                    throw new AssertionError("a fresh fixture token must not refresh");
                });
            } else journalDir=null;
        }
        /** A Chat client: no Anthropic protocol header, so the response stays Chat-shaped. */
        VendorPolicyGatewayHttpTest.Result post(String path,JSONObject body)throws Exception{
            return send(path,body,false);
        }
        /** An Anthropic client: it declares the protocol version, as Claude Code does. */
        VendorPolicyGatewayHttpTest.Result postAnthropic(String path,JSONObject body)throws Exception{
            return send(path,body,true);
        }
        private VendorPolicyGatewayHttpTest.Result send(String path,JSONObject body,boolean anthropicClient)
                throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path)
                    .openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(20000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            c.setRequestProperty("Authorization","Bearer fixture-auth");
            c.setRequestProperty("Accept-Encoding","identity");
            if(anthropicClient)c.setRequestProperty("anthropic-version","2023-06-01");
            try(OutputStream out=c.getOutputStream()){
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            VendorPolicyGatewayHttpTest.Result result=new VendorPolicyGatewayHttpTest.Result();
            result.status=c.getResponseCode();
            InputStream in=result.status>=400?c.getErrorStream():c.getInputStream();
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(in!=null)try(InputStream stream=in){
                byte[] buffer=new byte[4096]; int n;
                while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);
            }
            result.body=out.toString("UTF-8");
            c.disconnect();
            return result;
        }
        public void close()throws Exception{
            gateway.close();
            if(journalDir!=null)try(java.util.stream.Stream<Path> files=Files.walk(journalDir)){
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{
                    try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){ }
                });
            }
        }
    }

    static VendorPolicyGatewayHttpTest.Upstream upstream(String contentType,String reply)throws Exception{
        return new VendorPolicyGatewayHttpTest.Upstream((request,count)->
                new VendorPolicyGatewayHttpTest.Reply(200,contentType,reply));
    }
    static JSONObject sentBody(VendorPolicyGatewayHttpTest.Upstream upstream){
        check(upstream.requests.size()==1,"exactly one physical upstream request, got "+upstream.requests.size()
                +(upstream.failure==null?"":" failure="+upstream.failure));
        return upstream.requests.get(0).body;
    }
    static int sentMaxTokens(VendorPolicyGatewayHttpTest.Upstream upstream){
        return sentBody(upstream).optInt("max_tokens",-1);
    }

    // ------------------------------------------------------------------ translated paths

    /** The published limit is what the vendor receives on a generic Anthropic destination. */
    static void zcodeAnthropicRuleReachesTheWire()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"zcode Anthropic translation succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==RULE,"the signed rule is the outbound max_tokens: "+sentBody(up));
        }
    }

    /** The value lives in the request body, so a streaming client gets the same one. */
    static void streamingClientGetsTheSameValue()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",ANTHROPIC_SSE);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            JSONObject body=chatBody("zcode/"+ZCODE_MODEL,null).put("stream",true);
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",body);
            check(r.status==200,"streaming translation succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==RULE,"a streaming request carries the same rule: "+sentBody(up));
        }
    }

    /** A client-declared limit is never replaced, and never raised past Guard. */
    static void clientValueWins()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("zcode/"+ZCODE_MODEL,Integer.valueOf(CLIENT)));
            check(r.status==200,"explicit client limit succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==CLIENT,"the client value is authoritative: "+sentBody(up));
        }
    }

    /** The real catalog outranks a rule: it is the model's own limit, not a compatibility default. */
    static void catalogValueWins()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"))
                .put("modelMeta",new JSONObject().put(ZCODE_MODEL,new JSONObject().put("maxOutput",CATALOG)));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"catalog default succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==CATALOG,"the catalog value outranks the rule: "+sentBody(up));
        }
    }

    /** A built-in capability is also a real answer, so the rule stays out of the way. */
    static void builtInCapabilityWins()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_BUILTIN_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_BUILTIN_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("zcode/"+ZCODE_BUILTIN_MODEL,null));
            check(r.status==200,"built-in capability succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==128000,"the built-in model limit outranks the rule: "+sentBody(up));
        }
    }

    /** Guard is a ceiling over whatever the rule says, exactly as over a catalog value. */
    static void guardCapsTheRule()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,GUARD),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"guard-capped rule succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==GUARD,"Guard still caps the value: "+sentBody(up));
        }
    }

    /** MiniMax Code is the same translation with a different vendor; it must not drift. */
    static void minimaxCodeRuleReachesTheWire()throws Exception{
        // protocol "minimax" is what carries the account lease; the destination is still Messages.
        JSONObject p=provider("minimax-code","minimax","https://api.minimax.example/anthropic",MINIMAX_MODEL)
                .put("accounts",new JSONArray().put(new JSONObject().put("id","fixture-account")
                        .put("access","fixture-token").put("token","fixture-token")
                        .put("refresh","fixture-refresh")
                        .put("expires",System.currentTimeMillis()+3600000)));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("minimax-code",MINIMAX_MODEL),true)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("minimax-code/"+MINIMAX_MODEL,null));
            check(r.status==200,"MiniMax Code translation succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==RULE,"the rule reaches the MiniMax Code body: "+sentBody(up));
        }
    }

    /** The gift channel builds its own Anthropic body from the same helper, so it must agree. */
    static void zcodeGiftRuleReachesTheWire()throws Exception{
        JSONObject p=provider("zcode","openai","https://zapi.example/v1",GIFT_MODEL)
                .put("accounts",new JSONArray().put(new JSONObject().put("id","gift-account")
                        .put("jwt","fixture-jwt").put("device","fixture-device")));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",GIFT_MODEL),false)){
            FakeGift gift=new FakeGift();
            s.gateway.gateway.setGiftTransportForTest(gift);
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+GIFT_MODEL,null));
            check(gift.bodies.size()==1,"the gift channel opened exactly one stream, got "+gift.bodies.size()
                    +" status="+r.status+" body="+r.body);
            check(gift.bodies.get(0).optInt("max_tokens",-1)==RULE,
                    "the gift body carries the signed rule: "+gift.bodies.get(0));
            check(up.requests.isEmpty(),"the paid key route was not used");
        }
    }

    static final class FakeGift implements GatewayServer.GiftTransport {
        final List<JSONObject> bodies=Collections.synchronizedList(new ArrayList<JSONObject>());
        public JSONObject summary(String jwt,String device){
            return new JSONObject().put("ok",true).put("buckets",new JSONArray().put(new JSONObject()
                    .put("remaining",1)
                    .put("capabilities",new JSONArray().put("model:"+GIFT_MODEL))));
        }
        public ZCodeGift.Stream open(String jwt,String device,String site,String model,JSONObject body,boolean dressed){
            bodies.add(new JSONObject(body.toString()));
            ZCodeGift.Stream stream=new ZCodeGift.Stream();
            stream.status=200; stream.sent=true;
            stream.is=new ByteArrayInputStream(ANTHROPIC_SSE.getBytes(StandardCharsets.UTF_8));
            return stream;
        }
    }

    // ------------------------------------------------------------------ WorkBuddy native relay

    static JSONObject workbuddyProvider(String model){
        return new JSONObject().put("id","workbuddy").put("protocol","openai")
                .put("baseUrl","https://copilot.tencent.com/v2")
                .put("keys",new JSONArray().put("ck_fixture_workbuddy_token"))
                .put("models",new JSONArray().put(model));
    }

    /** Chat client converted into native Messages: the translation constant is the rule's slot. */
    static void workBuddyNativeRuleReachesTheWire()throws Exception{
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),0),up,
                    rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,null));
            check(r.status==200,"WorkBuddy native relay succeeds: "+r.status+" "+r.body);
            JSONObject sent=sentBody(up);
            check(sent.optInt("max_tokens",-1)==RULE,"the rule replaces the 16384 translation fallback: "+sent);
            check("claude-opus-5.5".equals(sent.optString("model")),"the resolved model is unchanged");
            check(sent.optBoolean("stream"),"the relay still forces upstream SSE");
        }
    }

    static void workBuddyNativeGuardCaps()throws Exception{
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),GUARD),up,
                    rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,null));
            check(r.status==200,"WorkBuddy native guard succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==GUARD,"Guard still caps the relay body: "+sentBody(up));
        }
    }

    static void workBuddyNativeCatalogWins()throws Exception{
        JSONObject p=workbuddyProvider(WORKBUDDY_MODEL).put("modelMeta",new JSONObject()
                .put(WORKBUDDY_MODEL,new JSONObject().put("maxOutput",CATALOG)));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(p,0),up,rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,null));
            check(r.status==200,"WorkBuddy native catalog succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==CATALOG,"the catalog outranks the rule on the relay too: "+sentBody(up));
        }
    }

    static void workBuddyNativeClientValueWins()throws Exception{
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),0),up,
                    rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,Integer.valueOf(CLIENT)));
            check(r.status==200,"WorkBuddy native explicit limit succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==CLIENT,"the client value outranks the rule on the relay: "+sentBody(up));
        }
    }

    /**
     * A native direct Messages request is the client's own document: an omitted {@code max_tokens}
     * keeps the generic fallback it had before, and a rule never enters it.
     */
    static void workBuddyNativeDirectKeepsItsFallback()throws Exception{
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),0),up,
                    rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.postAnthropic("/v1/messages",
                    messagesBody("workbuddy/"+WORKBUDDY_MODEL,null));
            check(r.status==200,"native direct relay succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a native direct request keeps the generic fallback: "+sentBody(up));
        }
    }

    // ------------------------------------------------------------------ middleware precedence

    /**
     * A middleware {@code set} is somebody's explicit decision, exactly like a client value. The
     * published rule only ever replaces the generic constant, so a middleware value of exactly
     * {@code 16384} — the same number as the constant — must survive untouched, and so must any
     * other number. Both are checked on every path that translates into Anthropic Messages.
     */
    static void middlewareSetValueIsNeverReplaced()throws Exception{
        for(Integer middleware:new Integer[]{Integer.valueOf(FALLBACK),Integer.valueOf(50000)}){
            JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                    .put("keys",new JSONArray().put("fixture-key"));
            JSONObject cfg=config(p,0).put("middleware",middlewareSet(middleware.intValue()));
            try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
                Session s=new Session(cfg,up,rule("zcode",ZCODE_MODEL),false)){
                VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                        chatBody("zcode/"+ZCODE_MODEL,null));
                check(r.status==200,"zcode middleware "+middleware+" succeeds: "+r.status+" "+r.body);
                check(sentMaxTokens(up)==middleware.intValue(),
                        "the middleware value "+middleware+" outranks the rule: "+sentBody(up));
            }
            try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                        WorkBuddyNativeGatewayHttpTest.BASE);
                Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),0)
                        .put("middleware",middlewareSet(middleware.intValue())),up,
                        rule("workbuddy",WORKBUDDY_MODEL),false)){
                VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                        chatBody("workbuddy/"+WORKBUDDY_MODEL,null));
                check(r.status==200,"relay middleware "+middleware+" succeeds: "+r.status+" "+r.body);
                check(sentMaxTokens(up)==middleware.intValue(),
                        "a relay middleware value "+middleware+" is not mistaken for the fallback: "+sentBody(up));
            }
        }
    }

    /** The gift channel builds its own body; a middleware value must reach it just the same. */
    static void middlewareSetValueReachesTheGiftBody()throws Exception{
        JSONObject p=provider("zcode","openai","https://zapi.example/v1",GIFT_MODEL)
                .put("accounts",new JSONArray().put(new JSONObject().put("id","gift-account")
                        .put("jwt","fixture-jwt").put("device","fixture-device")));
        JSONObject cfg=config(p,0).put("middleware",middlewareSet(50000));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(cfg,up,rule("zcode",GIFT_MODEL),false)){
            FakeGift gift=new FakeGift();
            s.gateway.gateway.setGiftTransportForTest(gift);
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+GIFT_MODEL,null));
            check(gift.bodies.size()==1,"one gift stream, got "+gift.bodies.size()+" status="+r.status);
            check(gift.bodies.get(0).optInt("max_tokens",-1)==50000,
                    "the middleware value reaches the gift body: "+gift.bodies.get(0));
        }
    }

    /**
     * A middleware {@code maxTokensCap} that lands exactly on {@code 16384} is a cap somebody set,
     * not the translation fallback, so the rule must not raise it back.
     */
    static void middlewareCapOnTheConstantIsNotTheFallback()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        JSONObject cfg=config(p,0).put("middleware",middlewareCap(FALLBACK));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(cfg,up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("zcode/"+ZCODE_MODEL,Integer.valueOf(50000)));
            check(r.status==200,"capped request succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"the middleware cap is not the fallback: "+sentBody(up));
        }
    }

    /** An explicit client value of exactly 16384 is a client value, not the fallback. */
    static void clientValueOnTheConstantIsNotTheFallback()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("zcode/"+ZCODE_MODEL,Integer.valueOf(FALLBACK)));
            check(r.status==200,"explicit 16384 succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a client 16384 is not replaced by the rule: "+sentBody(up));
        }
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(workbuddyProvider(WORKBUDDY_MODEL),0),up,
                    rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,Integer.valueOf(FALLBACK)));
            check(r.status==200,"relay explicit 16384 succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a relay client 16384 stays the client's: "+sentBody(up));
        }
    }

    /** A catalog entry of exactly 16384 is the model's own limit, not the fallback. */
    static void catalogValueOnTheConstantIsNotTheFallback()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"))
                .put("modelMeta",new JSONObject().put(ZCODE_MODEL,
                        new JSONObject().put("maxOutput",FALLBACK)));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"catalog 16384 succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a catalog 16384 is not replaced by the rule: "+sentBody(up));
        }
        JSONObject relay=workbuddyProvider(WORKBUDDY_MODEL).put("modelMeta",new JSONObject()
                .put(WORKBUDDY_MODEL,new JSONObject().put("maxOutput",FALLBACK)));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("text/event-stream",
                    WorkBuddyNativeGatewayHttpTest.BASE);
            Session s=new Session(config(relay,0),up,rule("workbuddy",WORKBUDDY_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("workbuddy/"+WORKBUDDY_MODEL,null));
            check(r.status==200,"relay catalog 16384 succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a relay catalog 16384 stays the model's: "+sentBody(up));
        }
    }

    /** Guard caps whichever value won; it never substitutes the rule for a named value. */
    static void guardCapsANamedValueNotTheRule()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,GUARD),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",
                    chatBody("zcode/"+ZCODE_MODEL,Integer.valueOf(50000)));
            check(r.status==200,"guarded explicit value succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==GUARD,"Guard lowers the client value, not to the rule: "+sentBody(up));
        }
    }

    /**
     * The precedence itself, without HTTP: a declaration recorded for the request wins even if the
     * payload copy handed to the decision has lost the field, and with no declaration the rule
     * fills the constant's place. This is the invariant the two seams above rest on.
     */
    static void theDeclarationOutranksTheDefault()throws Exception{
        GatewayServer server=ResponsesGatewayOfflineTest.gateway(1);
        try{
            Class<?> targetClass=Class.forName("com.gaeul.allin.GatewayServer$RouteTarget");
            java.lang.reflect.Constructor<?> ctor=targetClass.getDeclaredConstructor();ctor.setAccessible(true);
            Object target=ctor.newInstance();
            for(String[] pair:new String[][]{{"providerId","zcode"},{"protocol","anthropic"},
                    {"upstreamModel",ZCODE_MODEL},{"isAnthropic","true"}}){
                java.lang.reflect.Field f=targetClass.getDeclaredField(pair[0]);f.setAccessible(true);
                // RouteTarget.isAnthropic is a boolean; the string rows are set by assignment.
                f.set(target,"isAnthropic".equals(pair[0])?Boolean.TRUE:(Object)pair[1]);
            }
            java.lang.reflect.Method effective=GatewayServer.class.getDeclaredMethod("effectiveOutputLimit",
                    targetClass,JSONObject.class,ContextGuard.Config.class);
            effective.setAccessible(true);
            java.lang.reflect.Field contextField=GatewayServer.class.getDeclaredField("REQUEST_CONTEXT");
            contextField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<RequestContext> contextSlot=(ThreadLocal<RequestContext>)contextField.get(null);
            java.lang.reflect.Field namedField=GatewayServer.class.getDeclaredField("CLIENT_OUTPUT_LIMIT");
            namedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Integer> named=(ThreadLocal<Integer>)namedField.get(null);
            ProviderQuirks.Snapshot snapshot=rule("zcode",ZCODE_MODEL);
            RequestContext context=new RequestContext("chat",NetworkPolicy.capture(),60000,snapshot,"","",0);
            ContextGuard.Config guard=new ContextGuard.Config();
            JSONObject noLimit=new JSONObject().put("model",ZCODE_MODEL);
            try{
                contextSlot.set(context);
                named.remove();
                Object filled=effective.invoke(server,target,noLimit,guard);
                check(((Integer)filled).intValue()==RULE,"with nothing declared the rule fills the constant: "+filled);
                // The recorded declaration survives a payload copy that no longer carries the field.
                named.set(Integer.valueOf(50000));
                Object kept=effective.invoke(server,target,noLimit,guard);
                check(((Integer)kept).intValue()==50000,
                        "a declared value is not replaced even when the payload copy lost it: "+kept);
                named.set(Integer.valueOf(FALLBACK));
                Object asNamed=effective.invoke(server,target,noLimit,guard);
                check(((Integer)asNamed).intValue()==FALLBACK,
                        "a declared 16384 is a declaration, not the fallback: "+asNamed);
            } finally {
                named.remove();
                contextSlot.remove();
            }
        } finally { server.stop(); }
    }

    // ------------------------------------------------------------------ excluded paths

    /** A native direct Messages request on a generic Anthropic destination keeps its omission. */
    static void nativeDirectOnAnthropicKeepsItsOmission()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.postAnthropic("/v1/messages",messagesBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"native direct Anthropic succeeds: "+r.status+" "+r.body);
            check(!sentBody(up).has("max_tokens"),
                    "the client's omission is preserved, no rule and no fallback: "+sentBody(up));
        }
    }

    /** Plain ZCode chat never becomes Anthropic, so neither a rule nor the constant may appear. */
    static void plainZCodeChatIsUntouched()throws Exception{
        JSONObject p=provider("zcode","openai","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        // A non-streaming chat client, so the reply is one JSON chat.completion.
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",
                    VendorPolicyGatewayHttpTest.CHAT);
            Session s=new Session(config(p,0),up,rule("zcode",ZCODE_MODEL),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"plain ZCode chat succeeds: "+r.status+" "+r.body);
            check(!sentBody(up).has("max_tokens"),
                    "plain chat gets no default at all: "+sentBody(up));
        }
    }

    /** No rule for this provider/model: the generic constant is used exactly as before. */
    static void unmatchedDocumentKeepsTheGenericFallback()throws Exception{
        JSONObject p=provider("zcode","anthropic","https://zapi.example/v1",ZCODE_MODEL)
                .put("keys",new JSONArray().put("fixture-key"));
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,noRule(),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"empty document succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"an empty document leaves the fallback: "+sentBody(up));
        }
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,
                    document("3.0.2",defaultOf("zcode","glm-9-something-else",RULE)),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"non-matching document succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a rule for another model cannot match: "+sentBody(up));
        }
        try(VendorPolicyGatewayHttpTest.Upstream up=upstream("application/json",ANTHROPIC_REPLY);
            Session s=new Session(config(p,0),up,
                    document("3.0.3",defaultOf("workbuddy",ZCODE_MODEL,RULE)),false)){
            VendorPolicyGatewayHttpTest.Result r=s.post("/v1/chat/completions",chatBody("zcode/"+ZCODE_MODEL,null));
            check(r.status==200,"cross-provider document succeeds: "+r.status+" "+r.body);
            check(sentMaxTokens(up)==FALLBACK,"a rule for another provider cannot match: "+sentBody(up));
        }
    }

    public static void main(String[] args)throws Exception{
        zcodeAnthropicRuleReachesTheWire();
        streamingClientGetsTheSameValue();
        clientValueWins();
        catalogValueWins();
        builtInCapabilityWins();
        guardCapsTheRule();
        minimaxCodeRuleReachesTheWire();
        zcodeGiftRuleReachesTheWire();
        workBuddyNativeRuleReachesTheWire();
        workBuddyNativeGuardCaps();
        workBuddyNativeCatalogWins();
        workBuddyNativeClientValueWins();
        workBuddyNativeDirectKeepsItsFallback();
        middlewareSetValueIsNeverReplaced();
        middlewareSetValueReachesTheGiftBody();
        middlewareCapOnTheConstantIsNotTheFallback();
        clientValueOnTheConstantIsNotTheFallback();
        catalogValueOnTheConstantIsNotTheFallback();
        guardCapsANamedValueNotTheRule();
        theDeclarationOutranksTheDefault();
        nativeDirectOnAnthropicKeepsItsOmission();
        plainZCodeChatIsUntouched();
        unmatchedDocumentKeepsTheGenericFallback();
        System.out.println("AnthropicOutputDefaultQuirksHttpTest OK (rule reaches zcode/minimax/workbuddy/gift bodies; client, middleware, catalog, built-in, Guard, native and chat all keep precedence, and a 16384 that somebody chose is never mistaken for the fallback)");
    }
}
