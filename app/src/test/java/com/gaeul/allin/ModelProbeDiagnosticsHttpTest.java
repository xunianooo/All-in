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

/**
 * /api/test-model 的分层诊断，走真实环回。
 *
 * <p>这些用例守的是同一件事：失败必须说清是哪一层、还能不能换别的，
 * 不能把 401、404、DNS 失败和上游 5xx 都说成同一句"查询失败"；
 * 也不能把空正文说成"握手成功"。同时保证展示文案里没有凭据。
 */
public final class ModelProbeDiagnosticsHttpTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static final String KEY = "sk-synthetic-probe-key-12345678";
    static final String BODY_SECRET = "sk-upstream-body-secret-9999";

    /** 可按用例改回包的上游。 */
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        volatile int status = 200;
        volatile String body = "{}";
        volatile String retryAfter = null;

        Upstream() throws IOException { super("127.0.0.1", 0); start(SOCKET_READ_TIMEOUT, true); }

        public Response serve(IHTTPSession session) {
            calls.incrementAndGet();
            Response response = newFixedLengthResponse(Response.Status.lookup(status), "application/json", body);
            if (retryAfter != null) response.addHeader("Retry-After", retryAfter);
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
                    .put("keys", new JSONArray().put(KEY))
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

        /** 让这一跳直接抛指定的异常，模拟 DNS / TLS / 超时 / 拒绝。 */
        void failTransport(final IOException failure) {
            inner.setVendorConnectionsForTests(url -> { throw failure; });
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
        noSecret(result);
        return result;
    }

    /** 无论怎么说，展示出来的东西里都不能有凭据。 */
    static void noSecret(JSONObject result) {
        String text = result.toString();
        check(!text.contains(KEY), "probe must not echo the key: " + text);
        check(!text.contains(BODY_SECRET), "probe must not echo the credential-looking body: " + text);
    }

    static void rateLimit() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 429;
            upstream.body = "{\"error\":{\"code\":\"rate_limit\",\"message\":\"synthetic rate limit\"}}";
            upstream.retryAfter = "37";
            JSONObject result = probe(gateway);
            check(!result.getBoolean("ok"), "429 must fail: " + result);
            check(result.getInt("statusCode") == 429, "statusCode: " + result);
            check("37".equals(result.getString("retryAfter")), "retryAfter: " + result);
            check("rate".equals(result.getString("errorKind")), "errorKind: " + result);
            check(!result.getBoolean("localRejection"), "upstream rejection is not local: " + result);
            check(!result.getBoolean("executionUnknown"), "received upstream response: " + result);
            check("inference".equals(result.getString("failureLayer")), "layer: " + result);
            check(result.isNull("canTryOtherModel"), "other model availability is unknown: " + result);
            check(upstream.calls.get() == 1, "expected exactly one upstream call: " + upstream.calls.get());
        }
    }

    static void unauthorized() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 401;
            upstream.body = "{\"error\":{\"message\":\"invalid token " + BODY_SECRET + "\"}}";
            JSONObject result = probe(gateway);
            check(!result.getBoolean("ok"), "401 must fail: " + result);
            check(result.getInt("statusCode") == 401, "401 keeps its code: " + result);
            check("auth".equals(result.getString("errorKind")), "errorKind: " + result);
            check("login".equals(result.getString("failureLayer")), "401 is the login layer: " + result);
            check(result.isNull("canTryOtherModel"), "other model availability is unknown: " + result);
            check(result.getString("advice").length() > 0, "advice: " + result);
        }
    }

    static void restricted() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 403;
            upstream.body = "{\"error\":{\"message\":\"account restricted\"}}";
            JSONObject result = probe(gateway);
            check("restricted".equals(result.getString("errorKind")), "errorKind: " + result);
            check("login".equals(result.getString("failureLayer")), "403 is still the login layer: " + result);
            // 受限不是密钥写错：建议不能让人去换 Key
            check(result.getString("advice").contains("凭据"), "403 advice: " + result);
            check(result.isNull("canTryOtherModel"), "other model availability is unknown: " + result);
        }
    }

    static void notFound() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 404;
            upstream.body = "{\"error\":{\"message\":\"no such route\"}}";
            JSONObject result = probe(gateway);
            check("not_found".equals(result.getString("errorKind")), "errorKind: " + result);
            check("catalog".equals(result.getString("failureLayer")), "404 is the catalog layer: " + result);
            check(result.isNull("canTryOtherModel"), "other model availability is unknown: " + result);
        }
    }

    static void methodNotAllowed() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 405;
            upstream.body = "{\"message\":\"method not allowed\"}";
            JSONObject result = probe(gateway);
            // 404 与 405 不能塌成同一个词：一个是端点搬走了，一个是方法/协议用错了
            check("method_not_allowed".equals(result.getString("errorKind")), "errorKind: " + result);
            check("catalog".equals(result.getString("failureLayer")), "405 is the catalog layer: " + result);
        }
    }

    static void emptyReply() throws Exception {
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(upstream.getListeningPort())) {
            upstream.status = 200;
            upstream.body = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
            JSONObject result = probe(gateway);
            check(!result.getBoolean("ok"), "an empty reply is not a pass: " + result);
            check("empty_reply".equals(result.getString("errorKind")), "errorKind: " + result);
            check(result.getInt("statusCode") == 200, "the upstream did answer 200: " + result);
            check("inference".equals(result.getString("failureLayer")), "layer: " + result);
            check(!result.toString().contains("握手成功"), "must not fake a handshake: " + result);
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
            // 被拒和上游 502 不能长得一样
            check("refused".equals(result.getString("errorKind")), "errorKind: " + result);
            check("network".equals(result.getString("failureLayer")), "layer: " + result);
            check("refused".equals(result.getString("transportKind")), "transportKind: " + result);
        }
    }

    static void dnsFailure() throws Exception {
        try (Gateway gateway = new Gateway(1)) {
            gateway.failTransport(new UnknownHostException("synthetic.invalid"));
            JSONObject result = probe(gateway);
            check("dns".equals(result.getString("errorKind")), "errorKind: " + result);
            check("network".equals(result.getString("failureLayer")), "dns is our own network: " + result);
            check(result.getInt("statusCode") == 502, "statusCode: " + result);
            // 本机解析失败不能让人去换密钥
            check(result.getString("advice").contains("DNS"), "advice: " + result);
        }
    }

    static void tlsFailure() throws Exception {
        try (Gateway gateway = new Gateway(1)) {
            gateway.failTransport(new javax.net.ssl.SSLException("synthetic handshake failure"));
            JSONObject result = probe(gateway);
            check("tls".equals(result.getString("errorKind")), "errorKind: " + result);
            check("tls".equals(result.getString("transportKind")), "transportKind: " + result);
            check("network".equals(result.getString("failureLayer")), "tls is not the account: " + result);
        }
    }

    static void timeoutFailure() throws Exception {
        try (Gateway gateway = new Gateway(1)) {
            gateway.failTransport(new SocketTimeoutException("synthetic read timeout"));
            JSONObject result = probe(gateway);
            check("timeout".equals(result.getString("errorKind")), "errorKind: " + result);
            check(result.isNull("canTryOtherModel"), "other model availability is unknown: " + result);
        }
    }

    static void sentTimeout() throws Exception {
        try (Gateway gateway = new Gateway(1)) {
            AtomicInteger writes = new AtomicInteger();
            gateway.inner.setVendorConnectionsForTests(url -> new HttpURLConnection(new URL(url)) {
                public void disconnect() { }
                public boolean usingProxy() { return false; }
                public void connect() { }
                public OutputStream getOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override public synchronized void write(byte[] b, int off, int len) { writes.addAndGet(len); super.write(b,off,len); }
                        @Override public synchronized void write(int b) { writes.incrementAndGet(); super.write(b); }
                    };
                }
                public int getResponseCode() throws IOException { throw new SocketTimeoutException("synthetic read timeout"); }
            });
            JSONObject result = probe(gateway);
            check(writes.get() > 0, "request bytes written");
            check(result.optBoolean("executionUnknown"), "sent timeout execution unknown: " + result);
            check("timeout".equals(result.optString("transportKind")), "timeout retained: " + result);
            check(result.optString("advice").contains("计费"), "execution advice: " + result);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> only = Arrays.asList(args);
        boolean all = only.isEmpty();
        if (all || only.contains("rate")) rateLimit();
        if (all || only.contains("connection")) connectionFailure();
        if (all || only.contains("unauthorized")) unauthorized();
        if (all || only.contains("restricted")) restricted();
        if (all || only.contains("notfound")) notFound();
        if (all || only.contains("method")) methodNotAllowed();
        if (all || only.contains("empty")) emptyReply();
        if (all || only.contains("dns")) dnsFailure();
        if (all || only.contains("tls")) tlsFailure();
        if (all || only.contains("timeout")) timeoutFailure();
        if (all || only.contains("sent-timeout")) sentTimeout();
        System.out.println("ModelProbeDiagnosticsHttpTest passed");
    }
}
