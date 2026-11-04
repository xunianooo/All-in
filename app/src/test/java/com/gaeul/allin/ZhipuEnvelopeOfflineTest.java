package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ZhipuEnvelopeOfflineTest {
    static int checks;
    static final String URL = "https://open.bigmodel.cn/api/v1/responses";
    static class Conn extends HttpURLConnection {
        int status = 200; String type = "application/json", encoding;
        Conn() throws Exception { super(new URL(URL)); }
        public int getResponseCode() { return status; }
        public String getContentType() { return type; }
        public String getContentEncoding() { return encoding; }
        public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
    }
    static class Tracked extends ByteArrayInputStream {
        boolean closed;
        Tracked(byte[] data) { super(data); }
        public void close() throws IOException { closed = true; super.close(); }
    }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1024]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toByteArray();
    }
    static void unchanged(String s) throws Exception {
        Tracked in = new Tracked(bytes(s));
        ZhipuEnvelope.Result r = ZhipuEnvelope.inspect(new Conn(), in, URL);
        check(!r.adapted && Arrays.equals(bytes(s), read(r.body))); r.body.close(); check(in.closed);
    }
    public static void main(String[] args) throws Exception {
        unchanged("{\"success\":true,\"code\":1000,\"msg\":\"no\"}");
        unchanged("{\"success\":\"false\",\"code\":1000}");
        unchanged("{\"success\":false}");
        unchanged("{\"id\":\"response\",\"output\":[]}");
        unchanged("{\"success\":false,\"code\":0,\"msg\":\" \"}");
        unchanged("{\"success\":false,\"code\":1000} garbage");
        Tracked original = new Tracked(bytes("{\"success\":false,\"code\":1000,\"msg\":\"bad\",\"key\":\"secret\"}"));
        ZhipuEnvelope.Result adapted = ZhipuEnvelope.inspect(new Conn(), original, URL);
        check(adapted.adapted && adapted.status == 401 && "1000".equals(adapted.code));
        check(adapted.errorBody.contains("bad") && !adapted.errorBody.contains("secret"));
        check(Arrays.equals(bytes(adapted.errorBody), read(adapted.body))); adapted.body.close(); check(original.closed);
        int[][] mapping = {{999,502},{1000,401},{1005,401},{1006,502},{1113,429},{1210,400},{1219,400},{1220,403},{1229,400},{1230,502},{1261,400},{1301,400},{1302,429},{1399,429},{1400,502}};
        for (int[] entry : mapping) check(ZhipuEnvelope.codeStatus(entry[0]) == entry[1]);
        String[] bad = {"https://open.bigmodel.cn.evil.com/api/v1", "https://api.z.ai.evil/api/v1", "https://api.z.ai/api/paas/v4/chat/completions", "https://api.z.ai/api/v1/other", "https://api.z.ai/api/monitoring"};
        for (String url : bad) check(!ZhipuEnvelope.applicable(url,200,"application/json",null,false));
        check(ZhipuEnvelope.applicable("https://api.z.ai/api/monitor/quota",200,"application/json","identity",false));
        check(!ZhipuEnvelope.applicable(URL,200,"text/event-stream",null,false));
        check(!ZhipuEnvelope.applicable(URL,200,"application/json","gzip",false));
        check(!ZhipuEnvelope.applicable(URL,400,"application/json",null,false));
        check(!ZhipuEnvelope.applicable("http://localhost/api/v1",200,"application/json",null,false));
        check(ZhipuEnvelope.applicable("http://localhost/api/v1",200,"application/json",null,true));
        byte[] large = new byte[65537 + 100]; Arrays.fill(large, (byte)'x');
        Tracked big = new Tracked(large); ZhipuEnvelope.Result bigResult = ZhipuEnvelope.inspect(new Conn(), big, URL);
        check(!bigResult.adapted && Arrays.equals(large, read(bigResult.body))); bigResult.body.close(); check(big.closed);
        final IOException expected = new IOException("failed");
        final boolean[] failureClosed = {false};
        InputStream failing = new InputStream() {
            int pos;
            public void close() { failureClosed[0] = true; }
            public int read() throws IOException { if (pos < 3) return "abc".charAt(pos++); throw expected; }
            public int read(byte[] b, int o, int l) throws IOException { if (pos == 3) throw expected; int n = 0; while (n < l && pos < 3) b[o+n++] = (byte)read(); return n; }
        };
        ZhipuEnvelope.Result failed = ZhipuEnvelope.inspect(new Conn(), failing, URL);
        byte[] prefix = new byte[3]; check(failed.body.read(prefix) == 3 && Arrays.equals(prefix,bytes("abc")));
        try { failed.body.read(); throw new AssertionError(); } catch (IOException e) { check(e == expected); }
        failed.body.close(); check(failureClosed[0]);
        System.out.println("ZhipuEnvelope offline checks passed: " + checks);
    }
}
