package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real gateway HTTP for Trae Max and account credits; only the Trae upstream is loopback. */
public final class TraeMaxUsageGatewayHttpTest {
    private static final String AUTH="fixture-gateway-key";
    private static final String TOKEN="synthetic-trae-account-token";
    private static final String CHAT_PATH="/api/agent/v3/llm_utils_chat";
    private static final String USAGE_PATH="/trae/api/v2/pay/ide_user_ent_usage";
    private static final String CHAT_SSE="event: output\ndata: {\"response\":\"fixture answer\"}\n\n"
            +"event: token_usage\ndata: {\"input_tokens\":5,\"output_tokens\":2}\n\n"
            +"event: done\ndata: {\"finish_reason\":\"stop\"}\n\n";

    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** A local-only synthetic Trae server. It never forwards a request elsewhere. */
    private static final class TraeFixture extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls=new AtomicInteger();
        volatile String path="", requestBody="", authorization="", uid="";
        volatile Throwable failure;
        final boolean usage;

        TraeFixture(boolean usage) throws Exception {
            super("127.0.0.1",0); this.usage=usage;
            start(NanoHTTPD.SOCKET_READ_TIMEOUT,false);
        }
        @Override public Response serve(IHTTPSession session) {
            try {
                path=session.getUri();
                Map<String,String> headers=session.getHeaders();
                authorization=headers.get("authorization"); uid=headers.get("x-uid");
                Map<String,String> files=new HashMap<>();
                if(session.getMethod()==Method.POST) session.parseBody(files);
                requestBody=files.getOrDefault("postData",""); calls.incrementAndGet();
                if(usage && USAGE_PATH.equals(path)) {
                    String json="{\"is_credits_billing\":false,\"user_entitlement_pack_list\":["
                            +"{\"entitlement_base_info\":{\"quota\":{\"credits_limit\":300},\"end_time\":1790000000},\"usage\":{\"credits_amount\":75}},"
                            +"{\"entitlement_base_info\":{\"quota\":{}},\"usage\":{}},"
                            +"{\"entitlement_base_info\":{\"quota\":{\"credits_limit\":100}},\"usage\":{\"credits_amount\":25}}]}";
                    return newFixedLengthResponse(Response.Status.OK,"application/json; charset=UTF-8",json);
                }
                if(!usage && CHAT_PATH.equals(path))
                    return newFixedLengthResponse(Response.Status.OK,"text/event-stream; charset=UTF-8",CHAT_SSE);
                return newFixedLengthResponse(Response.Status.NOT_FOUND,"application/json; charset=UTF-8","{}");
            } catch(Throwable e) {
                failure=e;
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"application/json; charset=UTF-8","{}");
            }
        }
        @Override public void close() { stop(); }
    }

    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject config,Path dir,TraeFixture upstream) throws Exception {
            super("127.0.0.1",0);
            inner=RoutingSetupOfflineTest.server(config);
            ResponsesGatewayOfflineTest.set(inner,"configFile",dir.resolve("config.json").toFile());
            ResponsesGatewayOfflineTest.set(inner,"groupTurns",new java.util.concurrent.ConcurrentHashMap<String,Integer>());
            Files.write(dir.resolve("config.json"),bytes(config.toString()));
            GatewayManagementCredential.init(dir.toFile());
            inner.setTraeOriginForTests("http://127.0.0.1:"+upstream.getListeningPort());
            start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        @Override public Response serve(IHTTPSession session) { return inner.serve(session); }
        @Override protected boolean useGzipWhenAccepted(Response response) { return inner.useGzipWhenAccepted(response); }
        @Override public void close() { stop(); inner.retireForRestore(); }
    }

    private static JSONObject config(TraeFixture upstream) throws Exception {
        JSONObject maxMeta=new JSONObject().put("function","solo_agent").put("modelName","fixture__max")
                .put("maxOutput",128).put("multimodal",false).put("base","fixture")
                .put("contextWindow",1000000).put("maxMode",true);
        JSONObject account=TraeDevice.create().put("id","trae-account-fixture").put("generation","generation-fixture")
                .put("token",TOKEN).put("refresh","synthetic-refresh").put("expires",System.currentTimeMillis()+86400000L)
                .put("uid","synthetic-uid").put("name","Fixture account").put("enabled",true)
                .put("models",new JSONArray().put("fixture-max"))
                .put("modelMeta",new JSONObject().put("fixture-max",maxMeta));
        JSONObject provider=new JSONObject().put("id","trae-cn").put("status","ported").put("protocol","trae")
                .put("enabled",true).put("baseUrl","https://unused.invalid").put("keys",new JSONArray().put(TOKEN))
                .put("models",new JSONArray().put("fixture-max"))
                .put("modelMeta",new JSONObject().put("fixture-max",maxMeta))
                .put("accounts",new JSONArray().put(account));
        String origin="http://127.0.0.1:"+upstream.getListeningPort();
        return new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey",AUTH))
                .put("privacy",new JSONObject().put("enabled",false))
                .put("providers",new JSONArray().put(provider))
                .put("groups",new JSONArray().put(new JSONObject().put("id","trae-max-fixture").put("strategy","order")
                        .put("targets",new JSONArray().put("trae-cn/fixture-max"))))
                .put("routing",new JSONObject().put("groupsEnabled",true))
                .put("failover",new JSONObject().put("enabled",false));
    }

    private static final class Result { int status; String body; }
    private static Result http(Gateway gateway,String path,String method,JSONObject payload) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod(method);
        c.setRequestProperty("Accept-Encoding","identity");
        c.setRequestProperty("Authorization","Bearer "+(path.startsWith("/api/")?GatewayManagementCredential.get():AUTH));
        if(payload!=null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
            try(java.io.OutputStream out=c.getOutputStream()) { out.write(bytes(payload.toString())); }
        }
        Result result=new Result(); result.status=c.getResponseCode();
        InputStream in=result.status>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null) try(InputStream input=in) { byte[] buffer=new byte[2048]; int n; while((n=input.read(buffer))!=-1)out.write(buffer,0,n); }
        result.body=out.toString("UTF-8"); c.disconnect(); return result;
    }

    private static void maxThroughGateway(Path root) throws Exception {
        try(TraeFixture upstream=new TraeFixture(false)) {
            JSONObject config=config(upstream);
            try(Gateway gateway=new Gateway(config,Files.createDirectory(root.resolve("max")),upstream)) {
                JSONObject request=new JSONObject().put("model","trae-cn/fixture-max").put("stream",false).put("max_tokens",64)
                        .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
                Result result=http(gateway,"/v1/chat/completions","POST",request);
                check(result.status==200&&result.body.contains("fixture answer"),
                        "Max must pass baseModel/contextWindow/maxMode to TraeProtocol.request's ten-argument adapter; got HTTP "+result.status+" "+result.body);
                check(upstream.calls.get()==1&&CHAT_PATH.equals(upstream.path),"Trae Max must reach only the mocked chat endpoint");
                check(("Cloud-IDE-JWT "+TOKEN).equals(upstream.authorization),"Trae account authorization header");
                JSONObject wire=new JSONObject(upstream.requestBody);
                check("solo_agent".equals(wire.optString("function")),"Max function from account model metadata");
                check("fixture".equals(wire.optString("config_name"))&&"fixture".equals(wire.optString("model")),"Max uses its base config name");
                check("fixture__max".equals(wire.optString("model_name")),"Max keeps the upstream variant name");
                check(wire.optInt("max_tokens",-1)==64,"requested output budget is retained");
                JSONObject context=wire.optJSONObject("user_message_context");
                JSONObject modelInfo=context==null?null:context.optJSONObject("model_info");
                check(modelInfo!=null&&modelInfo.optInt("prompt_max_tokens",-1)==999936,
                        "Max context budget is passed using the ten-argument TraeProtocol contract");
                check(upstream.failure==null,"mock chat fixture");
            }
        }
    }

    private static void usageThroughGateway(Path root) throws Exception {
        try(TraeFixture upstream=new TraeFixture(true)) {
            TraeAuth.setFixtureBase("http://127.0.0.1:"+upstream.getListeningPort());
            try {
                JSONObject config=config(upstream);
                try(Gateway gateway=new Gateway(config,Files.createDirectory(root.resolve("usage")),upstream)) {
                    Result result=http(gateway,"/api/providers/billing?provider=trae-cn","GET",null);
                    check(result.status==200,"Trae billing route should respond; got HTTP "+result.status+" "+result.body);
                    check(upstream.calls.get()==1&&USAGE_PATH.equals(upstream.path),
                            "Trae billing must call the mocked ide_user_ent_usage endpoint (not report not_provided); gateway returned "+result.body);
                    check(("Cloud-IDE-JWT "+TOKEN).equals(upstream.authorization),"usage request uses account Cloud-IDE-JWT");
                    check("synthetic-uid".equals(upstream.uid),"usage request preserves Trae account identity headers");
                    check(new JSONObject(upstream.requestBody).toString().equals(new JSONObject().put("require_usage",true).put("req_source",0).toString()),
                            "usage request body follows Trae CN contract");
                    JSONObject response=new JSONObject(result.body);
                    JSONArray accounts=response.optJSONArray("accounts");
                    check(accounts!=null&&accounts.length()==1,"one fixture account in billing response");
                    JSONObject allowance=accounts.getJSONObject(0).optJSONObject("allowance");
                    check(allowance!=null&&"allowance".equals(allowance.optString("kind"))
                                    &&"account".equals(allowance.optString("scope"))&&"credits".equals(allowance.optString("unit")),
                            "Trae credits are returned as an account allowance");
                    check(!allowance.has("remaining"),"Trae CN usage is reported as a credits window, not a balance");
                    JSONArray windows=allowance.optJSONArray("windows");
                    check(windows!=null&&windows.length()==1,"feature-only entitlement packs do not create a credits window");
                    JSONObject credits=windows.getJSONObject(0);
                    check("Credits".equals(credits.optString("name"))&&credits.optDouble("used",-1)==25
                                    &&credits.optDouble("amount",-1)==100&&credits.optDouble("limit",-1)==400
                                    &&"credits".equals(credits.optString("unit")),
                            "credits window aggregates pack usage and limits like the community reference");
                    check("2026-09-21T14:13:20.000Z".equals(credits.optString("resetsAt")),"earliest pack expiry is retained");
                    check(allowance.optLong("observedAt",0)>0,"usage snapshot records observation time");
                    check(upstream.failure==null,"mock billing fixture");
                }
            } finally { TraeAuth.setFixtureBase(null); }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("trae-max-usage-gateway-http");
        java.util.List<String> failures=new java.util.ArrayList<>();
        try { maxThroughGateway(root); } catch(Throwable e) { failures.add("Trae Max: "+e.getMessage()); }
        try { usageThroughGateway(root); } catch(Throwable e) { failures.add("Trae usage: "+e.getMessage()); }
        if(!failures.isEmpty()) throw new AssertionError("Expected pending GatewayServer wiring:\n - "+String.join("\n - ",failures));
        System.out.println("TraeMaxUsageGatewayHttpTest OK");
    }
}
