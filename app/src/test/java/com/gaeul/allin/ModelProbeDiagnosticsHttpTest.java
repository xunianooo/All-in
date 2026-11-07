package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the model probe over loopback with synthetic commercial credentials. */
public final class ModelProbeDiagnosticsHttpTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        Upstream() throws IOException { super("127.0.0.1", 0); start(SOCKET_READ_TIMEOUT, true); }
        public Response serve(IHTTPSession session) {
            calls.incrementAndGet();
            Response response = newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json",
                    "{\"error\":{\"code\":\"rate_limit\",\"message\":\"synthetic rate limit\"}}");
            response.addHeader("Retry-After", "37");
            return response;
        }
        public void close() { stop(); }
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        final Path directory;
        Gateway(int upstreamPort) throws Exception {
            super("127.0.0.1", 0);
            JSONObject provider = new JSONObject().put("id", "probe-fixture").put("protocol", "openai")
                    .put("baseUrl", "https://example.invalid/v1")
                    .put("keys", new JSONArray().put("sk-synthetic-probe-key-12345678"))
                    .put("models", new JSONArray().put("m"));
            JSONObject cfg = new JSONObject().put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", "fixture-auth"))
                    .put("privacy", new JSONObject().put("enabled", false))
                    .put("providers", new JSONArray().put(provider));
            directory = Files.createTempDirectory("model-probe-");
            inner = RoutingSetupOfflineTest.server(cfg);
            RoutingAuditOfflineTest.file(inner, directory.resolve("config.json").toFile());
            inner.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp(
                    "http://127.0.0.1:" + upstreamPort + new URL(url).getFile()));
            start(SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() throws Exception {
            stop(); inner.stop();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        }
    }
    static JSONObject probe(Gateway gateway) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:"
                + gateway.getListeningPort() + "/api/test-model").openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod("POST");
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(new JSONObject().put("model", "probe-fixture/m").toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        check(status == 200, "probe HTTP envelope status: " + status);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[1024]; int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
        } finally { c.disconnect(); }
        JSONObject result = new JSONObject(bytes.toString("UTF-8"));
        check(!result.has("upstreamStatus"), "probe must not claim a separate upstreamStatus");
        return result;
    }
    static void rateLimit() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            JSONObject result = probe(gateway);
            check(!result.getBoolean("ok"), "429 must fail: " + result);
            check(result.getInt("statusCode") == 429, "statusCode: " + result);
            check("37".equals(result.getString("retryAfter")), "retryAfter: " + result);
            check("rate".equals(result.getString("errorKind")), "errorKind: " + result);
            check(!result.getBoolean("localRejection"), "upstream rejection is not local: " + result);
            check(!result.getBoolean("executionUnknown"), "received upstream response: " + result);
            check(upstream.calls.get() == 1, "expected exactly one upstream call: " + upstream.calls.get());
        }
    }
    static void connectionFailure() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = socket.getLocalPort();
        }
        try (Gateway gateway = new Gateway(unusedPort)) {
            JSONObject result = probe(gateway);
            check(!result.getBoolean("ok"), "connection failure must fail: " + result);
            check(result.getInt("statusCode") == 502, "zero status becomes 502: " + result);
            // The commercial forwarding path marks out.sent immediately before getOutputStream().
            check(result.getBoolean("executionUnknown"), "sent attempt has unknown execution: " + result);
            check(!result.getBoolean("localRejection"), "connection failure is not local rejection: " + result);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "rate".equals(args[0])) rateLimit();
        if (args.length == 0 || "connection".equals(args[0])) connectionFailure();
        System.out.println("ModelProbeDiagnosticsHttpTest passed");
    }
}
