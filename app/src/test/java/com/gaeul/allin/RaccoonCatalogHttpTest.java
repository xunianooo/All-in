package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Exercises the production sync route with an in-memory vendor connection. */
public final class RaccoonCatalogHttpTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static final class FakeConnection extends HttpURLConnection {
        final int status; final byte[] body; boolean disconnected;
        FakeConnection(String url,int status,String body) throws Exception { super(new URL(url));this.status=status;this.body=body.getBytes(StandardCharsets.UTF_8); }
        public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;} public void connect(){}
        public int getResponseCode(){return status;}
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    static JSONObject provider() { return new JSONObject().put("id","raccoon").put("name","Raccoon").put("protocol","openai")
        .put("accounts",new JSONArray().put(new JSONObject().put("token","fixture-token")))
        .put("models",new JSONArray().put("old-model"))
        .put("modelMeta",new JSONObject().put("old-model",new JSONObject().put("multiplier",4))); }
    static void run(int status,String body,boolean success) throws Exception {
        JSONObject p=provider(); JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true))
            .put("server",new JSONObject().put("authKey","fixture-auth"))
            .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(p));
        GatewayServer gateway=RoutingSetupOfflineTest.server(cfg);
        RoutingAuditOfflineTest.file(gateway,Files.createTempDirectory("raccoon-catalog").resolve("config.json").toFile());
        final FakeConnection[] captured=new FakeConnection[1];
        gateway.setVendorConnectionsForTests(url -> { try { captured[0]=new FakeConnection(url,status,body);return captured[0]; } catch(Exception e) { throw new IOException(e); } });
        String oldModels=p.getJSONArray("models").toString(), oldMeta=p.getJSONObject("modelMeta").toString();
        NanoHTTPD.Response response=gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/raccoon/sync-models","POST","{}",null));
        String responseBody=ResponsesGatewayOfflineTest.read(response.getData());
        check(captured[0]!=null,"vendor hook used: "+responseBody);
        check(captured[0].getURL().toString().equals("https://xiaohuanxiong.com/api/web/llm/v2/model_catalog"),"url");
        check(captured[0].getRequestMethod().equals("GET"),"method");
        check("Bearer fixture-token".equals(captured[0].getRequestProperty("Authorization")),"authorization");
        check(captured[0].disconnected,"connection closed");
        JSONObject live=ResponsesGatewayOfflineTest.config(gateway).getJSONArray("providers").getJSONObject(0);
        if (!success) {
            check(response.getStatus().getRequestStatus()>=400,"failure status "+responseBody);
            check(!new JSONObject(responseBody).optBoolean("ok",false),"failure not ok");
            check(!responseBody.contains("secret-sentinel"),"no raw upstream error");
            check(live.getJSONArray("models").toString().equals(oldModels),"models retained");
            check(live.getJSONObject("modelMeta").toString().equals(oldMeta),"metadata retained");
        } else {
            check(response.getStatus().getRequestStatus()==200 && new JSONObject(responseBody).optBoolean("ok"),"success "+responseBody);
            check(live.getJSONArray("models").length()==1 && live.getJSONArray("models").getString(0).equals("new-model"),"actual catalog only");
            check(live.getJSONObject("modelMeta").getJSONObject("new-model").getInt("multiplier")==2,"metadata saved");
        }
    }
    public static void main(String[] args) throws Exception {
        run(401,"secret-sentinel",false);run(500,"secret-sentinel",false);run(200,"{bad",false);
        run(200,"{}",false);run(200,"{\"data\":{\"categories\":[]}}",false);
        run(200,"{\"data\":{\"categories\":[{\"models\":[]}]}}",false);
        run(200,"{\"data\":{\"categories\":[{\"models\":[{\"name\":\"ok\"},{}]}]}}",false);
        run(200,"{\"data\":{\"categories\":[{\"models\":[{\"name\":\"new-model\",\"billing_multiplier\":2}]}]}}",true);
        System.out.println("RaccoonCatalogHttpTest passed");
    }
}
