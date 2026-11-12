package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ProviderBillingGatewayHttpTest {
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static NanoHTTPD.IHTTPSession request(String ip,String method,String key,String id) {
        // Management endpoints use the private management credential, not the inference key.
        String credential = "fixture-auth".equals(key) ? GatewayManagementCredential.get() : key;
        NanoHTTPD.IHTTPSession base=RoutingSetupOfflineTest.request(ip,"/api/providers/billing",method,"",credential);
        return (NanoHTTPD.IHTTPSession)Proxy.newProxyInstance(ProviderBillingGatewayHttpTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)-> {
            if(m.getName().equals("getParameters")) return Collections.singletonMap("provider",Collections.singletonList(id));
            return m.invoke(base,a);
        });
    }
    static final class MiniConnection extends HttpURLConnection {
        final int status; final byte[] data; boolean disconnected;
        MiniConnection(String url,int status,String body) throws Exception { super(new URL(url)); this.status=status; data=body.getBytes(StandardCharsets.UTF_8); }
        public void disconnect() { disconnected=true; } public boolean usingProxy() { return false; } public void connect() { }
        public int getResponseCode() { return status; } public InputStream getInputStream() { return new ByteArrayInputStream(data); }
    }
    static void miniProtocol() throws Exception {
        String body="{\"base_resp\":{\"status_code\":0},\"model_remains\":[{\"model_name\":\"general\",\"current_interval_remaining_percent\":72,\"current_interval_status\":1,\"end_time\":1790818000000,\"current_weekly_remaining_percent\":0,\"current_weekly_status\":1}]}";
        java.util.List<MiniConnection> calls=new ArrayList<>();
        JSONObject raw=MiniMaxAuth.fetchAllowance("mini-secret","",url->{ MiniConnection c=new MiniConnection(url,calls.isEmpty()?500:200,body); calls.add(c); return c; });
        check(calls.size()==2 && calls.get(0).getURL().toString().equals("https://www.minimaxi.com/backend/account/token_plan/remains_percent") && calls.get(1).getURL().getPath().equals("/v1/api/openplatform/coding_plan/remains"),"minimax endpoints fallback");
        for(MiniConnection c:calls) { check(c.disconnected && c.getRequestMethod().equals("GET") && c.getRequestProperty("Authorization").equals("Bearer mini-secret") && c.getRequestProperty("X-Group-Id")==null,"minimax headers no group"); }
        JSONObject allowance=ProviderAllowance.fromMiniMax(raw); check(allowance.getJSONArray("windows").length()==2 && allowance.getJSONArray("windows").getJSONObject(1).getDouble("remainingPercent")==0,"minimax windows including zero");
        final MiniConnection[] group={null}; MiniMaxAuth.fetchAllowance("mini-secret","group-id",url->group[0]=new MiniConnection(url,200,body));
        check("group-id".equals(group[0].getRequestProperty("X-Group-Id")),"group header");
        boolean failed=false; try { MiniMaxAuth.fetchAllowance("x","",url->new MiniConnection(url,200,"{\"base_resp\":{\"status_code\":1004}}")); } catch(IOException expected) { failed=true; }
        check(failed,"invalid schema not zero");
    }
    public static void main(String[] args) throws Exception {
        JSONObject provider=new JSONObject().put("id","qoder_cn").put("enabled",true)
            .put("models",new JSONArray().put("m").put("unknown"))
            .put("modelMeta",new JSONObject().put("m",new JSONObject().put("price_factor",0)))
            .put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("name","one").put("token","secret-sentinel"))
              .put(new JSONObject().put("id","b").put("name","two")).put(new JSONObject().put("id","off").put("off",true)));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth"))
            .put("providers",new JSONArray().put(provider));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);
        String before=cfg.toString();
        server.setProviderBillingTransportForTest((id,a)-> { if(a.optString("id").equals("b")) throw new Exception("secret-sentinel");
            return new JSONObject().put("kind","allowance").put("scope","account").put("unit","credits").put("remaining",0).put("windows",new JSONArray()).put("observedAt",1).put("secret", "secret-sentinel"); });
        NanoHTTPD.Response response=server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn"));
        String body=ResponsesGatewayOfflineTest.read(response.getData()); JSONObject json=new JSONObject(body);
        check(response.getStatus().getRequestStatus()==200,"status");
        check(!body.contains("secret-sentinel"),"no secrets");
        check(json.getJSONObject("models").getJSONObject("m").getJSONObject("billing").getDouble("value")==0,"zero rate");
        check(!json.getJSONObject("models").getJSONObject("unknown").has("billing"),"unknown rate");
        check(json.getJSONArray("accounts").length()==2 && json.getJSONArray("accounts").getJSONObject(0).getJSONObject("allowance").getInt("remaining")==0,"zero and enabled");
        check(json.getJSONArray("accounts").getJSONObject(1).getString("errorKind").equals("unavailable"),"partial failure");
        check(cfg.toString().equals(before),"config unchanged");
        check(server.serve(request("192.168.1.2","GET",null,"qoder_cn")).getStatus().getRequestStatus()==401,"remote auth");
        check(server.serve(request("127.0.0.1","POST","fixture-auth","qoder_cn")).getStatus().getRequestStatus()==405,"GET only");
        check(server.serve(request("192.168.1.2","GET","fixture-auth","qoder_cn")).getStatus().getRequestStatus()==403,"loopback");
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        server.setProviderBillingTransportForTest((id,a)-> { entered.countDown(); release.await(); return null; });
        Thread t=new Thread(()->server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn"))); t.start();
        check(entered.await(5,TimeUnit.SECONDS),"entered");
        check(server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn")).getStatus().getRequestStatus()==409,"busy");
        release.countDown(); t.join(5000); check(!t.isAlive(),"finished");
        CountDownLatch entered2=new CountDownLatch(1), release2=new CountDownLatch(1);
        server.setProviderBillingTransportForTest((id,a)-> { entered2.countDown(); release2.await(); return new JSONObject().put("kind","allowance").put("scope","account").put("unit","credits").put("remaining",1).put("windows",new JSONArray()); });
        final String[] stale=new String[1]; Thread t2=new Thread(()-> { try { stale[0]=ResponsesGatewayOfflineTest.read(server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn")).getData()); } catch(Exception e) { throw new RuntimeException(e); } });
        t2.start(); check(entered2.await(5,TimeUnit.SECONDS),"generation entered");
        provider.getJSONArray("accounts").getJSONObject(0).put("token","replacement"); release2.countDown(); t2.join(5000);
        check(stale[0]!=null && !stale[0].contains("\"accountId\":\"a\""),"stale account omitted");
        JSONObject m1=new JSONObject().put("price_factor",1).put("catalogObservedAt",12345), m2=new JSONObject().put("price_factor",2).put("catalogObservedAt",12346);
        JSONObject qa=provider.getJSONArray("accounts").getJSONObject(0), qb=provider.getJSONArray("accounts").getJSONObject(1);
        qa.put("modelMeta",new JSONObject().put("m",m1)); qb.put("modelMeta",new JSONObject().put("m",m2));
        server.setProviderBillingTransportForTest((id,a)->null);
        JSONObject diff=new JSONObject(ResponsesGatewayOfflineTest.read(server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn")).getData())).getJSONObject("models").getJSONObject("m");
        check(!diff.has("billing") && diff.getString("billingScope").equals("account") && diff.getJSONArray("billingVariants").length()==2,"account rates differ");
        check(diff.getJSONArray("billingVariants").getJSONObject(0).getJSONObject("billing").getLong("observedAt")==12345,"old timestamp preserved");
        // Consecutive calls must not race the previous worker's cleanup.
        for(int repeat=0;repeat<50;repeat++) {
            NanoHTTPD.Response next=server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn"));
            check(next.getStatus().getRequestStatus()==200,"completed operation left busy: "+repeat);
        }
        m2.put("price_factor",1); JSONObject same=new JSONObject(ResponsesGatewayOfflineTest.read(server.serve(request("127.0.0.1","GET","fixture-auth","qoder_cn")).getData())).getJSONObject("models").getJSONObject("m");
        check(same.getJSONObject("billing").getDouble("value")==1 && !same.has("billingScope"),"same rate");
        JSONObject refreshBefore=new JSONObject().put("id","a").put("token","old").put("refresh","old-r").put("name","same");
        JSONObject refreshAfter=new JSONObject(refreshBefore.toString()).put("token","new").put("refresh","new-r");
        GatewayServer.billingAdoptRefresh(refreshBefore,refreshAfter); check(refreshBefore.getString("token").equals("new"),"safe refresh adopted");
        boolean changed=false; try { GatewayServer.billingAdoptRefresh(refreshBefore,new JSONObject(refreshAfter.toString()).put("name","other")); } catch(IOException expected) { changed=true; }
        check(changed,"identity change rejected");
        JSONObject z=new JSONObject().put("id","zcode").put("enabled",true).put("models",new JSONArray().put("m"))
            .put("accounts",new JSONArray().put(new JSONObject().put("id","z1").put("jwt","fixture-one").put("device","d1")).put(new JSONObject().put("id","z2").put("jwt","fixture-two").put("device","d2")));
        cfg.getJSONArray("providers").put(z); server.setProviderBillingTransportForTest(null);
        ZCodeGiftControl.setTestHooks(null,new ZCodeGiftControl.Transport() {
            public JSONObject summary(String jwt,String device) { return new JSONObject().put("ok",true).put("claimable",new JSONArray()).put("buckets",new JSONArray().put(new JSONObject().put("remaining",jwt.equals("fixture-one")?1:2).put("capabilities",new JSONArray().put("model:m")))); }
            public JSONObject claim(String jwt,String device,String plan) { return new JSONObject(); }
        });
        try {
            JSONObject zs=new JSONObject(ResponsesGatewayOfflineTest.read(server.serve(request("127.0.0.1","GET","fixture-auth","zcode")).getData()));
            check(!zs.getJSONObject("models").getJSONObject("m").has("allowance"),"no merged gift");
            for(int i=0;i<2;i++) { JSONObject w=zs.getJSONArray("accounts").getJSONObject(i).getJSONObject("allowance").getJSONArray("windows").getJSONObject(0);
                check(w.getString("scope").equals("model") && w.getInt("remaining")==i+1,"gift account scope"); }
        } finally { ZCodeGiftControl.setTestHooks(null,null); }
        miniProtocol();
        System.out.println("ProviderBillingGatewayHttpTest passed");
    }
}
