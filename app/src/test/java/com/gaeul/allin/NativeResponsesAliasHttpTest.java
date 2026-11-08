package com.gaeul.allin;

import org.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** All upstream traffic is redirected by the existing fixture transport to a local fake server. */
public final class NativeResponsesAliasHttpTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JSONObject config() {
        JSONObject account=new JSONObject().put("id","cp-a").put("enabled",true).put("refresh","fake-refresh")
                .put("access","fake-access").put("expires",System.currentTimeMillis()+3600000L);
        JSONObject cp=new JSONObject().put("id","github-copilot").put("protocol","copilot").put("baseUrl","https://api.githubcopilot.com")
                .put("models",new JSONArray().put("m")).put("accounts",new JSONArray().put(account));
        return new JSONObject().put("server",new JSONObject().put("authKey","fake-inference"))
                .put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(cp))
                .put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",12));
    }
    static NanoHTTPD.IHTTPSession request(JSONObject body,String key,String path) {
        Map<String,String> headers=new HashMap<>(Map.of("host","127.0.0.1:3425"));
        if(key!=null)headers.put("authorization","Bearer "+key);
        return GatewayAccessBoundaryOfflineTest.session("192.0.2.1",path,"POST",body.toString(),headers);
    }
    static void guards() throws Exception {
        GatewayServer g=RoutingSetupOfflineTest.server(config());AtomicInteger upstream=new AtomicInteger();
        g.setVendorConnectionsForTests(url->{upstream.incrementAndGet();throw new java.io.IOException("unexpected transport");});
        JSONObject body=new JSONObject().put("model","github-copilot/m").put("input","hello");
        for(String path:new String[]{"/responses","/v1/responses"}) {
            check(GatewayAccessBoundaryOfflineTest.code(g.serve(request(body,null,path)))==401,"missing auth");
            check(GatewayAccessBoundaryOfflineTest.code(g.serve(request(body,"wrong",path)))==401,"wrong auth");
        }
        check(GatewayAccessBoundaryOfflineTest.code(g.serve(request(body,"fake-inference","/legacy/responses")))==404,"unknown alias");
        ResponsesGatewayOfflineTest.config(g).put("guard",new JSONObject().put("enabled",true).put("maxBodyBytes",20));
        check(GatewayAccessBoundaryOfflineTest.code(g.serve(request(body,"fake-inference","/responses")))==400,"guard before account/transport");
        check(upstream.get()==0,"rejected requests never touch upstream");
    }
    static void complete(boolean stream) throws Exception {
        String reply=stream?"event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"output\":[]}}\n\n":"{\"id\":\"r\",\"status\":\"completed\",\"output\":[]}";
        try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(reply,stream)) {
            GatewayServer g=RoutingSetupOfflineTest.server(config());
            g.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:"+mock.server.getLocalPort()+"/responses"));
            JSONObject body=new JSONObject().put("model","github-copilot/m").put("input","Contact alice@example.com")
                    .put("previous_response_id","resp_prior").put("store",true).put("stream",stream).put("max_output_tokens",100)
                    .put("vendor_extension",new JSONObject().put("mode","keep"))
                    .put("tools",new JSONArray().put(new JSONObject().put("type","custom").put("name","lookup").put("format",new JSONObject().put("type","text"))));
            String original=body.toString();
            NanoHTTPD.Response response=g.serve(request(body,"fake-inference","/responses"));
            String result=ResponsesGatewayOfflineTest.read(response.getData());
            check(response.getStatus().getRequestStatus()==200,"authorized native response success: "+response.getStatus().getRequestStatus()+" "+result);
            check(original.equals(body.toString()),"caller input unchanged");
            check("m".equals(mock.received.getString("model")),"provider prefix removed");
            check(mock.received.getBoolean("store") && "resp_prior".equals(mock.received.getString("previous_response_id")),"native state fields preserved");
            check(mock.received.getJSONObject("vendor_extension").similar(body.getJSONObject("vendor_extension")),"unknown native fields preserved");
            check(mock.received.getJSONArray("tools").similar(body.getJSONArray("tools")),"native tool shape preserved");
            check(mock.received.getInt("max_output_tokens")==12,"output limit enforced");
            check(!mock.received.getString("input").contains("alice@example.com"),"privacy applied to native input");
            check(stream?result.contains("response.completed"):new JSONObject(result).getString("status").equals("completed"),"native output shape preserved");
        }
    }
    public static void main(String[] args) throws Exception {guards();complete(false);complete(true);System.out.println("NativeResponsesAliasHttpTest passed (auth, no upstream on rejection, native fields/tools, privacy, budgets, SSE)");}
}
