package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;

/** Exercises fetchUsage through NetworkPolicy.openHttp with a synthetic HTTPS handler. */
public final class ClineUsageLayersHttpTest {
    static String[] body = new String[3];
    static int[] status = new int[3];
    static IOException[] failure = new IOException[3];
    static int opened, closed;
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static class Fixture extends HttpURLConnection {
        final int part;
        Fixture(URL url) { super(url); String p=url.getPath(); part=p.endsWith("/balance") ? 1 : p.endsWith("/usage-limits") ? 2 : 0; opened++; }
        public void connect() {}
        public void disconnect() { closed++; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() throws IOException { if (failure[part] != null) throw failure[part]; return status[part]; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body[part].getBytes(StandardCharsets.UTF_8)); }
    }
    static void reset() {
        body = new String[]{"{\"id\":\"fixture\"}", "{\"balance\":0}", "{\"limits\":[]}"};
        status = new int[]{200,200,200}; failure = new IOException[3];
    }
    static JSONObject run() throws Exception {
        int beforeOpen=opened, beforeClose=closed;
        JSONObject out=ClineAuth.fetchUsage("test-token", false);
        check(opened-beforeOpen==closed-beforeClose, "all connections closed");
        return out;
    }
    static JSONObject section(JSONObject o, String key) throws Exception { return o.getJSONObject("sections").getJSONObject(key); }
    static void unknown(JSONObject o, String key) throws Exception {
        check(!o.optBoolean("complete") && o.has("unread"), key+" incomplete and unread");
        check("unknown".equals(section(o,key).optString("status")), key+" unknown");
        check(o.isNull("affectsInference") && "unknown".equals(o.optString("inferenceStatus")), "no inference assertion");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Fixture(url); }
            protected URLConnection openConnection(URL url, Proxy proxy) { return new Fixture(url); }
        });
        reset(); JSONObject o=run();
        check(o.optBoolean("ok") && o.optBoolean("complete") && !o.optBoolean("partial"), "complete real zero");
        check(o.has("balanceUSD") && o.getDouble("balanceUSD")==0 && o.getJSONArray("limits").length()==0, "zero/empty are explicit");
        for (String invalid : new String[]{"{}", "{\"error\":\"denied\"}", "{\"success\":false,\"id\":\"x\"}", "not-json"}) {
            reset(); body[0]=invalid; o=run(); unknown(o,"identity"); check(!o.optBoolean("ok"), "invalid identity");
        }
        for (int code : new int[]{401,403,429,503}) {
            reset(); status[0]=code; o=run(); unknown(o,"identity");
            check(section(o,"identity").optInt("httpStatus")==code, "HTTP retained");
            check("ok".equals(section(o,"limits").optString("status")) && o.optBoolean("partial"), "other successful part retained");
        }
        for (int part=1; part<=2; part++) for (String invalid : new String[]{"{}", "not-json", "{\"error\":\"bad\"}"}) {
            reset(); body[part]=invalid; o=run(); unknown(o,part==1 ? "balance" : "limits");
            check(o.optBoolean("ok") && o.optBoolean("partial"), "identity preserved");
            if (part==1) check(!o.has("balanceUSD"), "missing balance never zero");
            else check(!o.has("limits"), "missing limits never empty");
        }
        reset(); body[2]="{\"limits\":[{\"type\":\"week\"}]}"; o=run(); unknown(o,"limits"); check(!o.has("limits"),"missing percent never zero");
        reset(); body[2]="{\"limits\":[{\"type\":\"week\",\"percentUsed\":0},{\"type\":\"day\"}]}"; o=run(); unknown(o,"limits"); check(o.getJSONArray("limits").length()==1,"valid window retained");
        reset(); status[2]=404; o=run(); unknown(o,"limits"); check(section(o,"limits").optInt("httpStatus")==404,"unsupported endpoint unknown");
        IOException[] errors={new UnknownHostException(),new SSLException("test"),new SocketTimeoutException()};
        String[] kinds={"dns","tls","timeout"};
        for(int i=0;i<errors.length;i++) { reset(); failure[0]=errors[i]; o=run(); unknown(o,"identity"); check(kinds[i].equals(section(o,"identity").optString("errorKind")),"transport kind "+kinds[i]); }
    }
}
