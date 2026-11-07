package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Local gateway with synthetic gift-control transport; never calls a provider. */
public final class ZCodeGiftControlGatewayHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class Fixture implements ZCodeGiftControl.Transport {
        final AtomicInteger summaries = new AtomicInteger(), claims = new AtomicInteger();
        volatile String mode = "normal", observedJwt = "";
        public JSONObject summary(String jwt, String device) throws Exception {
            observedJwt = jwt; summaries.incrementAndGet();
            if (mode.equals("summary-fail")) return new JSONObject().put("ok", false).put("error", "fixture unavailable");
            if (mode.equals("unknown")) return new JSONObject().put("ok", true).put("claimable", new JSONArray())
                    .put("buckets", new JSONArray().put(new JSONObject().put("remaining", -1).put("capabilities", new JSONArray().put("model:glm-5.3-flash"))));
            return new JSONObject().put("ok", true)
                    .put("claimable", new JSONArray().put(new JSONObject().put("planId", "one")).put(new JSONObject().put("planId", "two")))
                    .put("buckets", new JSONArray().put(new JSONObject().put("remaining", 5).put("capabilities", new JSONArray().put("model:glm-5.3-flash"))))
                    .put("remainingUnits", 5).put("hasGift", true);
        }
        public JSONObject claim(String jwt, String device, String id) throws Exception {
            observedJwt = jwt; claims.incrementAndGet();
            if (mode.equals("limit") || (mode.equals("partial") && id.equals("two"))) throw new ZCodeAuth.ServiceException(429, 3002, 47000);
            return new JSONObject();
        }
    }
    static final class Gateway extends NanoHTTPD {
        final GatewayServer gateway;
        Gateway(JSONObject config) throws Exception { super("127.0.0.1", 0); gateway=RoutingSetupOfflineTest.server(config); }
        public Response serve(IHTTPSession session) { return gateway.serve(session); }
        protected boolean useGzipWhenAccepted(Response response) { return gateway.useGzipWhenAccepted(response); }
    }
    static final class Reply { int status; String retry; JSONObject body; }
    static Reply request(int port, String path, String post) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(5000); c.setRequestProperty("Authorization", "Bearer fixture-gateway");
        if (post != null) { c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            try(OutputStream out=c.getOutputStream()) { out.write(post.getBytes(StandardCharsets.UTF_8)); } }
        try {
            Reply r=new Reply(); r.status=c.getResponseCode(); r.retry=c.getHeaderField("Retry-After");
            try(InputStream in=r.status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
                while((n=in.read(b))!=-1)bytes.write(b,0,n);
                r.body=new JSONObject(bytes.toString("UTF-8"));
            }
            return r;
        } finally { c.disconnect(); }
    }
    static JSONObject config() {
        return new JSONObject().put("server",new JSONObject().put("authKey","fixture-gateway"))
            .put("providers",new JSONArray().put(new JSONObject().put("id","zcode").put("protocol","openai")
                .put("baseUrl","http://127.0.0.1:1/v1").put("keys",new JSONArray().put("fixture-key"))
                .put("accounts",new JSONArray().put(new JSONObject().put("jwt","first").put("device","d")).put(new JSONObject().put("jwt","second").put("device","e")))
                .put("models",new JSONArray().put("glm-5.3-flash"))));
    }
    public static void main(String[] args) throws Exception {
        final long[] now={1000}; Fixture fake=new Fixture();
        ZCodeGiftControl.setTestHooks(() -> now[0], fake);
        Gateway g=new Gateway(config()); g.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        try {
            String panel="/api/zcode/gift";
            Reply first=request(g.getListeningPort(),panel,null);
            check(first.status==200 && first.body.optBoolean("ok") && first.body.optBoolean("complete") && fake.summaries.get()==1 && fake.observedJwt.equals("first"),"panel summary first account");
            Reply second=request(g.getListeningPort(),panel,null); check(second.body.optBoolean("ok") && fake.summaries.get()==1,"panel cached summary");
            Reply claim=request(g.getListeningPort(),panel+"?action=claim&planId=one",null);
            check(claim.status==200 && claim.body.optBoolean("ok") && claim.body.optJSONObject("gift").optBoolean("complete") && fake.claims.get()==1 && fake.summaries.get()==2,"successful claim refreshes cache");
            request(g.getListeningPort(),panel,null); check(fake.summaries.get()==2,"fresh summary shared");
            fake.mode="limit"; Reply limited=request(g.getListeningPort(),panel+"?action=claim&planId=one",null);
            check(limited.status==200 && !limited.body.optBoolean("ok") && limited.body.optInt("status")==429 && "47".equals(limited.retry),"limited claim response");
            int before=fake.claims.get(); Reply held=request(g.getListeningPort(),panel+"?action=claim&planId=one",null);
            check(held.status==200 && held.body.optInt("status")==429 && "47".equals(held.retry) && fake.claims.get()==before,"held claim no upstream");
            now[0]+=47000; fake.mode="partial";
            Reply batch=request(g.getListeningPort(),panel+"?action=claimall",null);
            check(batch.status==200 && !batch.body.optBoolean("ok") && !batch.body.optBoolean("complete") && batch.body.getJSONArray("claimed").length()==1
                    && batch.body.getJSONArray("claimed").getString(0).equals("one") && batch.body.getJSONArray("failed").length()==1
                    && batch.body.optInt("status")==429 && "47".equals(batch.retry),"partial batch retains success and limit");
            now[0]+=47000; fake.mode="summary-fail"; ZCodeGiftControl.invalidate("first","d");
            String payload=new JSONObject().put("model","zcode/glm-5.3-flash").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).toString();
            Reply inference=request(g.getListeningPort(),"/v1/chat/completions",payload);
            check(inference.status==502 && inference.body.toString().contains("计费"),"failed summary stops paid fallback");
            fake.mode="unknown"; ZCodeGiftControl.invalidate("first","d");
            inference=request(g.getListeningPort(),"/v1/chat/completions",payload);
            check(inference.status==502,"unknown remaining stops paid fallback");
            System.out.println("ZCodeGiftControlGatewayHttpTest PASS");
        } finally { g.stop(); ZCodeGiftControl.setTestHooks(null,null); }
    }
}
