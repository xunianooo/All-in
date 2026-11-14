package com.gaeul.allin;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The account refusal must reach both catalog HTTP and the safe billing snapshot. */
public final class CatPawAccountStatusHttpTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    static final class Fake extends HttpURLConnection {
        final int status;boolean disconnected;final ByteArrayOutputStream posted=new ByteArrayOutputStream();
        Fake(String url,int status)throws Exception{super(new URL(url));this.status=status;}
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
        public int getResponseCode(){return status;}public OutputStream getOutputStream(){return posted;}
        public InputStream getErrorStream(){return new ByteArrayInputStream("sensitive-fixture-body".getBytes(StandardCharsets.UTF_8));}
    }
    public static void main(String[] args)throws Exception {
        JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","synthetic-inference"))
            .put("providers",new JSONArray().put(new JSONObject().put("id","catpaw").put("enabled",true).put("protocol","openai")
                .put("models",new JSONArray().put("longcat-2.5")).put("modelMeta",new JSONObject().put("longcat-2.5",new JSONObject().put("modelTypeId",107)))
                .put("accounts",new JSONArray().put(new JSONObject().put("id","fixture-id").put("enabled",true).put("token","synthetic-catalog-token"))).put("keys",new JSONArray())));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);String before=cfg.toString();
        for(int status:new int[]{401,403,429,500}) {
            final Fake[] last={null};server.setCatPawCatalogForTests(url->last[0]=new Fake(url,status));
            NanoHTTPD.Response r=server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/catpaw/sync-models","GET","",null));
            String body=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==(status==500?502:status),"preserve official refusal status "+status);
            check(!body.contains("sensitive-fixture-body")&&!body.contains("synthetic-catalog-token"),"body and credential not exposed");
            check(last[0]!=null&&last[0].disconnected,"transport closed on refusal");
            check(!last[0].getInstanceFollowRedirects(),"no credential redirect");
            check(cfg.toString().equals(before),"failed catalog never clears accounts or models");
        }
        JSONObject refused=CatPawAuth.balanceFailure(new CatPawAuth.AccountFailure(401,4011));
        check("expired".equals(refused.optString("signIn")),"HTTP401 is expired, not unknown zero");
        check(refused.getInt("businessCode")==4011,"business code kept numeric");
        check(!CatPawAuth.balanceFailure(new CatPawAuth.AccountFailure(403,null)).has("signIn"),"403 is not guessed expired");
        check(!CatPawAuth.balanceFailure(new IOException("sensitive-fixture-body")).toString().contains("sensitive-fixture-body"),"unknown exception does not echo raw body");
        check("expired".equals(CatPawAuth.balanceDocument(new JSONObject().put("code",4010)).optString("signIn")),"business auth refusal");
        JSONObject zero=CatPawAuth.balanceDocument(new JSONObject().put("code",0).put("data",new JSONObject().put("availableCredits",0)));
        check(ProviderAllowance.fromCatPaw(zero,1).getDouble("remaining")==0,"known zero still valid");
        check(ProviderAllowance.fromCatPaw(CatPawAuth.balanceDocument(new JSONObject().put("code",0).put("data",new JSONObject())),1)==null,"missing balance stays unknown");
        server.setProviderBillingTransportForTest((id,account)->{throw new CatPawAuth.AccountFailure(401,4011);});
        NanoHTTPD.Response r=server.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth","catpaw"));
        JSONObject billing=new JSONObject(ResponsesGatewayOfflineTest.read(r.getData())).getJSONArray("accounts").getJSONObject(0);
        check("sign_in_required".equals(billing.optString("errorKind")),"real gateway billing reports re-login, not unknown");
        check(!billing.has("allowance"),"expired auth not zero quota");
        check(cfg.toString().equals(before),"billing auth refusal never changes identity");
        System.out.println("CatPawAccountStatusHttpTest PASS: "+checks+" checks");
    }
}
