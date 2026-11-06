package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic loopback-only Qoder/Gateway regression; no real credentials or services. */
public final class QoderGatewayHttpTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static final String SECRET = "upstream-secret-must-not-leak";

    private static final class Upstream implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final AtomicInteger calls = new AtomicInteger();
        volatile String reply;
        volatile String lastRequest;
        volatile Throwable failure;
        volatile Socket active;
        Upstream(String response) throws IOException {
            reply = response;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(300);
            worker = new Thread(this::run, "qoder-loopback"); worker.setDaemon(true); worker.start();
        }
        void run() {
            while (!listener.isClosed()) {
                try (Socket s = listener.accept()) {
                    active = s; s.setSoTimeout(4000); calls.incrementAndGet();
                    InputStream in = s.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int state = 0, b;
                    while (state < 4 && (b = in.read()) != -1) {
                        header.write(b); if (header.size() > 65536) throw new IOException("headers too large");
                        state = state == 0 && b == 13 ? 1 : state == 1 && b == 10 ? 2 : state == 2 && b == 13 ? 3 : state == 3 && b == 10 ? 4 : 0;
                    }
                    int length = 0;
                    for (String line : header.toString("UTF-8").split("\r\n"))
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    while (length-- > 0) { int c = in.read(); if (c < 0) throw new EOFException("request"); body.write(c); }
                    lastRequest = body.toString("UTF-8");
                    s.getOutputStream().write(bytes(reply)); s.getOutputStream().flush();
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable e) { if (!listener.isClosed()) failure = e; }
                finally { active = null; }
            }
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/qoder"; }
        public void close() throws Exception {
            listener.close(); Socket s = active; if (s != null) s.close(); worker.join(5000);
            check(!worker.isAlive(), "upstream thread did not stop");
            check(failure == null, "upstream fixture: " + failure);
        }
    }
    private static String http(int code, String body) {
        return "HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + bytes(body).length + "\r\nConnection: close\r\n\r\n" + body;
    }
    private static String sse(String content) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + bytes(content).length + "\r\nConnection: close\r\n\r\n" + content;
    }
    private static JSONObject config(Upstream backup) {
        JSONObject account = new JSONObject().put("id", "fixture-account").put("token", "fixture-token")
                .put("deviceToken", "fixture-device-token").put("deviceChat", true)
                .put("uid", "fixture-uid").put("machineId", "fixture-machine")
                .put("expiresAt", System.currentTimeMillis() + 86400000L);
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("enabled", true).put("protocol", "qoder")
                .put("models", new JSONArray().put("fixture-model")).put("accounts", new JSONArray().put(account))
                .put("modelMeta", new JSONObject().put("fixture-model", new JSONObject().put("key", "fixture-model").put("source", "fixture")));
        JSONObject fallback = new JSONObject().put("id", "qoder-test-backup").put("protocol", "openai")
                .put("baseUrl", backup.url()).put("keys", new JSONArray().put("fixture-key"))
                .put("models", new JSONArray().put("fixture-model"));
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("routing", new JSONObject().put("groupsEnabled", true))
                .put("failover", new JSONObject().put("enabled", true).put("maxTargets", 2).put("maxAttempts", 3))
                .put("providers", new JSONArray().put(qoder).put(fallback))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "qoder-test-group").put("strategy", "order")
                        .put("targets", new JSONArray().put("qoder_cn/fixture-model").put("qoder-test-backup/fixture-model"))));
    }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject config, Upstream qoder) throws Exception {
            super("127.0.0.1", 0);
            inner = RoutingSetupOfflineTest.server(config);
            java.nio.file.Path isolated=Files.createTempDirectory("qoder-gateway-");
            UsageLedger.init(isolated.toFile());
            RoutingAuditOfflineTest.file(inner, isolated.resolve("config.json").toFile());
            Field f = GatewayServer.class.getDeclaredField("groupTurns"); f.setAccessible(true);
            f.set(inner, new ConcurrentHashMap<String, Integer>());
            inner.setQoderConnectionsForTests(url -> (HttpURLConnection) new URL(qoder.url()).openConnection(Proxy.NO_PROXY));
            start(SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() { inner.setQoderConnectionsForTests(null); stop(); }
    }
    private static final class Result { int status; String body, retry; }
    private static Result post(Gateway gateway, String limits) throws Exception {
        String body = "{\"model\":\"group/qoder-test-group\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]" + limits + "}";
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + gateway.getListeningPort() + "/v1/chat/completions").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer fixture-auth"); c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) { out.write(bytes(body)); }
        Result result = new Result();
        try {
            result.status = c.getResponseCode(); result.retry = c.getHeaderField("Retry-After");
            InputStream in = result.status >= 400 ? c.getErrorStream() : c.getInputStream();
            try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (stream != null) { byte[] b = new byte[2048]; int n; while ((n = stream.read(b)) != -1) out.write(b, 0, n); }
                result.body = out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, long[]> rests() throws Exception {
        Field f = GatewayServer.class.getDeclaredField("RESTS"); f.setAccessible(true); return (Map<String, long[]>)f.get(null);
    }
    private static void scenario(String name, String wire, int expected, boolean queue, String limits, int expectedCalls) throws Exception {
        String key = "qoder_cn/fixture-model";
        Map<String, long[]> rest = rests(); long[] previous = rest.remove(key);
        try (Upstream primary = new Upstream(wire); Upstream backup = new Upstream(http(500, "backup-called"));
             Gateway gateway = new Gateway(config(backup), primary)) {
            Result r = post(gateway, limits);
            check(r.status == expected, name + " status=" + r.status + " body=" + r.body);
            check(!r.body.contains(SECRET), name + " leaked upstream body");
            check(primary.calls.get() == expectedCalls, name + " Qoder calls=" + primary.calls.get());
            check(backup.calls.get() == 0, name + " unexpected fallback");
            check(!rest.containsKey(key), name + " changed cooldown");
            if (queue) {
                check(r.retry != null, name + " missing Retry-After");
                int retry = Integer.parseInt(r.retry); check(retry >= 1 && retry <= 3600, name + " unbounded retry=" + retry);
                JSONArray recent=UsageLedger.summary(1,"model").getJSONArray("recent");
                boolean codeKept=false;
                for(int i=0;i<recent.length();i++) {JSONObject row=recent.getJSONObject(i);
                    if("qoder_cn".equals(row.optString("prov")) && row.optInt("attempt",0)>0) {codeKept="10605".equals(row.optString("upstreamErrorCode"));break;}}
                check(codeKept,name+" structured queue code missing from final ledger");
            }
            if (expectedCalls == 1 && expected == 503 && limits.contains("64")) {
                JSONObject request = new JSONObject(QoderAuth.decodeBody(primary.lastRequest));
                check(request.getJSONObject("parameters").getInt("max_tokens") == 64, name + " did not preserve cap");
            }
        } finally { rest.remove(key); if (previous != null) rest.put(key, previous); }
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        // Fail directly on broken fixture signing instead of hiding the cause behind Gateway's safe 502.
        QoderAuth.SignIn fixture = new QoderAuth.SignIn();
        fixture.access = "fixture-token"; fixture.uid = "fixture-uid"; fixture.machineId = "fixture-machine";
        JSONObject body = QoderAuth.buildChatBody(fixture, new JSONArray(), null, 64, "fixture-model",
                new JSONObject().put("key", "fixture-model").put("source", "fixture"));
        check(QoderAuth.chatHeaders(QoderAuth.CHAT_URL, fixture, QoderAuth.encodeBody(body.toString()),
                "fixture-model", "fixture").containsKey("Authorization"), "fixture signing");
        UsageLedger.init(Files.createTempDirectory("qoder-ledger-").toFile());
        String queue = new JSONObject().put("code", 10605).put("message", SECRET)
                .put("queue", new JSONObject().put("isQueued", true).put("retryAfterSeconds", 7)).toString();
        String nested = new JSONObject().put("code", 403).put("message", queue).toString();
        scenario("HTTP queue", http(403, queue), 503, true, ",\"max_tokens\":64", 1);
        String env = new JSONObject().put("statusCodeValue", 403).put("body", nested).toString();
        scenario("SSE queue", sse("data: " + env + "\n\n"), 503, true, ",\"max_tokens\":64", 1);
        scenario("auth 105", http(403, new JSONObject().put("code", 105).put("message", SECRET).toString()), 401, false, "", 1);
        scenario("ordinary 403", http(403, new JSONObject().put("code", 777).put("message", SECRET).toString()), 403, false, "", 1);
        scenario("truncated SSE", sse("data: {\"statusCodeValue\":"), 502, false, "", 1);
        for (String limit : new String[]{",\"max_tokens\":63", ",\"max_tokens\":0", ",\"max_tokens\":-1", ",\"max_tokens\":\"64\"", ",\"max_tokens\":2147483648", ",\"max_tokens\":64,\"max_completion_tokens\":63"})
            scenario("invalid " + limit, http(403, queue), 400, false, limit, 0);
        scenario("dual cap", http(403, queue), 503, true, ",\"max_tokens\":90,\"max_completion_tokens\":64", 1);
        System.out.println("QoderGatewayHttpTest passed");
    }
}
