package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Production sync route exercised exclusively with synthetic vendor responses. */
public final class WorkBuddyCatalogHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class FakeConnection extends HttpURLConnection {
        final int status; final byte[] body; boolean disconnected;
        FakeConnection(String url, int status, String body) throws Exception {
            super(new URL(url)); this.status = status; this.body = body.getBytes(StandardCharsets.UTF_8);
        }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public void connect() {}
        public int getResponseCode() { return status; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    static void run(int status, String body, boolean success) throws Exception {
        JSONObject provider = new JSONObject().put("id", "workbuddy").put("name", "WorkBuddy").put("protocol", "openai")
                .put("accounts", new JSONArray().put(new JSONObject().put("token", "fixture-token")))
                .put("models", new JSONArray().put("old-model"))
                .put("modelMeta", new JSONObject().put("old-model", new JSONObject().put("multiplier", 4)));
        JSONObject cfg = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false)).put("providers", new JSONArray().put(provider));
        GatewayServer gateway = RoutingSetupOfflineTest.server(cfg);
        RoutingAuditOfflineTest.file(gateway, Files.createTempDirectory("workbuddy-catalog").resolve("config.json").toFile());
        final FakeConnection[] captured = new FakeConnection[1];
        gateway.setVendorConnectionsForTests(url -> {
            try { return captured[0] = new FakeConnection(url, status, body); }
            catch (Exception e) { throw new IOException(e); }
        });
        String oldModels = provider.getJSONArray("models").toString(), oldMeta = provider.getJSONObject("modelMeta").toString();
        NanoHTTPD.Response response = gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/api/auth/workbuddy/sync-models", "POST", "{}", "fixture-auth"));
        String responseBody = ResponsesGatewayOfflineTest.read(response.getData());
        FakeConnection conn = captured[0];
        check(conn != null, "vendor hook used: " + responseBody);
        check(conn.getURL().toString().equals("https://copilot.tencent.com/v3/config"), "url");
        check(conn.getRequestMethod().equals("GET") && !conn.getInstanceFollowRedirects(), "method and redirects");
        String[][] headers = {{"Authorization", "Bearer fixture-token"}, {"User-Agent", "WorkBuddy/5.9.3 WorkBuddy/5.9.3 CLI/2.165.0"},
                {"X-Requested-With", "XMLHttpRequest"}, {"X-Product", "SaaS"}, {"X-IDE-Type", "WorkBuddy"},
                {"X-IDE-Name", "WorkBuddy"}, {"X-IDE-Version", "5.9.3"}, {"X-Agent-Intent", "craft"},
                {"X-Agent-Purpose", "conversation"}, {"X-Agent-Type", "main"}, {"X-Auth-Refresh-Source", "workbuddy"}, {"Accept", "application/json"}};
        for (String[] header : headers) check(header[1].equals(conn.getRequestProperty(header[0])), "header " + header[0]);
        check(conn.disconnected, "connection closed");
        JSONObject live = ResponsesGatewayOfflineTest.config(gateway).getJSONArray("providers").getJSONObject(0);
        if (!success) {
            check(response.getStatus().getRequestStatus() >= 400, "failure status " + responseBody);
            check(!new JSONObject(responseBody).optBoolean("ok", false), "failure not ok");
            check(!responseBody.contains("secret-sentinel"), "no raw upstream error");
            check(live.getJSONArray("models").toString().equals(oldModels), "models retained");
            check(live.getJSONObject("modelMeta").toString().equals(oldMeta), "metadata retained");
        } else {
            check(response.getStatus().getRequestStatus() == 200 && new JSONObject(responseBody).optBoolean("ok"), "success " + responseBody);
            JSONArray models = live.getJSONArray("models");
            check(models.length() == 2 && models.getString(0).equals("new-model") && models.getString(1).equals("auto"), "trimmed unique actual catalog");
            JSONObject meta = live.getJSONObject("modelMeta").getJSONObject("new-model");
            check(meta.getDouble("multiplier") == 2 && !meta.getBoolean("free") && meta.getString("credits").equals("x2.00 credits"), "credits metadata");
        }
    }
    public static void main(String[] args) throws Exception {
        run(401, "secret-sentinel", false); run(500, "secret-sentinel", false); run(302, "secret-sentinel", false);
        run(200, "secret-sentinel", false); run(200, "{}", false);
        run(200, "{\"code\":1,\"message\":\"secret-sentinel\"}", false);
        run(200, "{\"code\":0}", false); run(200, "{\"code\":0,\"data\":{}}", false);
        run(200, "{\"code\":0,\"data\":{\"agents\":[]}}", false);
        run(200, "{\"code\":0,\"data\":{\"agents\":[{\"name\":\"craft\",\"models\":[\"secret-sentinel\"]}]}}", false);
        run(200, "{\"code\":0,\"data\":{\"agents\":[{\"name\":\"cli\"}]}}", false);
        run(200, "{\"code\":0,\"data\":{\"agents\":[{\"name\":\"cli\",\"models\":[]}]}}", false);
        for (String invalid : new String[]{"null", "3", "{}", "\"  \""})
            run(200, "{\"code\":0,\"data\":{\"agents\":[{\"name\":\"cli\",\"models\":[\"valid\"," + invalid + "]}],\"models\":[{\"id\":\"valid\",\"credits\":\"x2\"}]}}", false);
        run(200, "{\"code\":\"0\",\"data\":{\"agents\":[{\"name\":\"cli\",\"models\":[\" new-model \",\"new-model\",\"auto\"]}],\"models\":[{\"id\":\"new-model\",\"credits\":\"x2.00 credits\"}]}}", true);
        run(200, "{\"code\":0,\"data\":{\"agents\":[{\"name\":\"cli\",\"models\":[\"new-model\",\"auto\"]}],\"models\":[{\"id\":\"new-model\",\"credits\":\"x2.00 credits\"}]}}", true);
        run(200, new String(new char[2 * 1024 * 1024 + 1]).replace('\0', ' '), false);
        System.out.println("WorkBuddyCatalogHttpTest passed");
    }
}
