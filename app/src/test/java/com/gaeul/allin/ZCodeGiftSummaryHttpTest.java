package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

/** Raw HTTP-envelope regression coverage; synthetic transport, no provider calls. */
public final class ZCodeGiftSummaryHttpTest {
    static String preview, balance;
    static int status = 200, balanceStatus = 200, previews, balances;
    static boolean networkFailure;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Fixture extends HttpURLConnection {
        final boolean isPreview;
        Fixture(URL url) { super(url); isPreview=url.getPath().endsWith("/preview"); if(isPreview)previews++;else balances++; }
        public void connect() { }
        public void disconnect() { }
        public boolean usingProxy() { return false; }
        public int getResponseCode() throws java.io.IOException { if(!isPreview && networkFailure) throw new java.io.IOException("fixture"); return isPreview ? status : balanceStatus; }
        public String getHeaderField(String key) { return "Retry-After".equalsIgnoreCase(key) ? "47" : null; }
        public InputStream getInputStream() { return new ByteArrayInputStream((isPreview ? preview : balance).getBytes(StandardCharsets.UTF_8)); }
        public InputStream getErrorStream() { return getInputStream(); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    }
    static JSONObject summary() { return ZCodeAuth.giftSummary("fixture-session", "fixture-device"); }
    static void unavailable(String why) {
        JSONObject out=summary();
        check(!out.optBoolean("ok"),why+": must fail, not report empty balance");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Fixture(url); }
            protected URLConnection openConnection(URL url, Proxy proxy) { return new Fixture(url); }
        });
        long expires=System.currentTimeMillis()/1000+3600;
        JSONObject bucket=new JSONObject().put("plan_id","fixture-plan").put("show_name","GLM-5.3-Flash")
                .put("remaining_units",500000000).put("total_units",500000000).put("used_units",0)
                .put("expires_at",expires).put("capabilities",new JSONArray().put("model:glm-5.3-flash"));
        JSONObject data=new JSONObject().put("plans",new JSONArray().put(new JSONObject().put("status","active")))
                .put("balances",new JSONArray().put(bucket));
        balance=new JSONObject().put("code",0).put("data",data).toString();
        // Real shape observed: successful preview has no data while balance is live.
        for(String p:new String[]{"{\"code\":0}","{\"code\":0,\"data\":null}","{\"code\":0,\"data\":{}}","{\"code\":0,\"data\":{\"plans\":[]}}"}) {
            preview=p; int before=balances;
            JSONObject out=summary();
            check(out.optBoolean("ok") && out.optBoolean("hasGift") && out.optLong("remainingUnits")==500000000,"empty preview preserves live gift");
            check(out.getJSONArray("claimable").length()==0 && balances==before+1,"still queries actual balance");
            check(ZCodeGift.allowedModels(out).contains("glm-5.3-flash"),"gift model route remains available");
        }
        preview="{\"code\":0,\"data\":{\"plans\":[{\"plan_id\":\"claimable\",\"name\":\"fixture\",\"status\":\"active\"}]}}";
        check(summary().getJSONArray("claimable").length()==1,"claimable plan preserved");
        preview="{\"code\":0,\"data\":{\"plans\":\"bad\"}}"; unavailable("malformed preview");
        check(summary().optString("error").contains("可领取计划"),"specific safe schema error");
        preview="{\"code\":0,\"data\":[]}"; unavailable("malformed non-object data");
        for(String malformed:new String[]{"{}","{\"code\":null}","{\"msg\":\"error\"}"}) {
            preview=malformed;unavailable("missing explicit success marker");
        }
        preview="not-json"; unavailable("invalid JSON");
        preview="{\"code\":3002}"; status=429;
        JSONObject rate=summary();check(!rate.optBoolean("ok") && rate.optInt("status")==429 && rate.optInt("businessCode")==3002 && rate.optLong("retryAfterMs")==47000,"rate limit retained");
        status=401; preview="{\"code\":1001}"; unavailable("expired session");
        status=200; preview="{\"code\":0}";
        for(int code:new int[]{401,429}) {
            balanceStatus=code;balance="{\"code\":1001}";
            JSONObject failure=summary();check(!failure.optBoolean("ok") && failure.optInt("status")==code,"balance HTTP failure retained");
        }
        balanceStatus=200;networkFailure=true;unavailable("balance network failure");networkFailure=false;
        balance="{\"code\":0}"; unavailable("missing balance remains unknown");
        bucket.remove("remaining_units"); balance=new JSONObject().put("code",0).put("data",data).toString(); unavailable("missing remaining remains unknown");
        bucket.put("remaining_units",5);bucket.remove("capabilities");balance=new JSONObject().put("code",0).put("data",data).toString();unavailable("missing capabilities remains unknown");
        balance="{\"code\":0,\"data\":{\"balances\":[]}}";
        check(summary().optBoolean("ok") && !summary().optBoolean("hasGift"),"explicit empty balance is valid");
        ZCodeGiftControl.setTestHooks(null,null);
        try {
            bucket.put("capabilities",new JSONArray().put("model:glm-5.3-flash"));balance=new JSONObject().put("code",0).put("data",data).toString();
            networkFailure=true;
            check(!ZCodeGiftControl.summary("cache-fixture","device").optBoolean("complete"),"failed raw query is incomplete");
            networkFailure=false;
            int before=previews;JSONObject result=ZCodeGiftControl.summary("cache-fixture","device");
            check(result.optBoolean("complete") && result.optBoolean("hasGift"),"raw envelope through default control transport");
            check(ZCodeGiftControl.summary("cache-fixture","device").optBoolean("complete") && previews==before+1,"successful raw result cached");
        } finally { ZCodeGiftControl.setTestHooks(null,null); }
        System.out.println("ZCodeGiftSummaryHttpTest PASS");
    }
}
