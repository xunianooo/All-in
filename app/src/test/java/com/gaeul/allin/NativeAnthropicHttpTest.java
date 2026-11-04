package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback integration tests; no Android context or external network. */
public final class NativeAnthropicHttpTest {
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    static String event(String type, String tail) { return NativeAnthropicStreamOfflineTest.e(type, tail); }
    static String block(int i, String b) { return NativeAnthropicStreamOfflineTest.block(i, b); }
    static String delta(int i, String b) { return NativeAnthropicStreamOfflineTest.delta(i, b); }
    static String stop(int i) { return NativeAnthropicStreamOfflineTest.stop(i); }
    static final String START = event("message_start", ",\"message\":{\"content\":[],\"usage\":{\"input_tokens\":11,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}}");
    static final String FIRST = block(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"extra\":17}") + delta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"hello\"}");
    static final String REST = delta(0,"{\"type\":\"thinking_delta\",\"thinking\":\"\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"part-a\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"part-b\"}") + stop(0)
        + block(1,"{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}") + stop(1)
        + block(2,"{\"type\":\"text\",\"text\":\"\",\"future\":1}") + delta(2,"{\"type\":\"text_delta\",\"text\":\"hello\",\"extra\":true}") + stop(2)
        + block(3,"{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{}}") + delta(3,"{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}") + stop(3)
        + event("future_event", ",\"extra\":{\"x\":1}") + event("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}") + event("message_stop", "");
    static final String ERROR = event("error", ",\"error\":{\"type\":\"overloaded_error\",\"message\":\"fixture\"}");
    static final String BODY = "{\"model\":\"alpha/real-model\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"question\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"reason\",\"signature\":\"fixture-signature\"},{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{\"x\":1}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"result\"}]}]}";
    static final String NONSTREAM = "{\"id\":\"id\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"real-model\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"fixture-signature\",\"extra\":1},{\"type\":\"future\",\"payload\":42}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}";
    static final class Upstream implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final Thread thread;
        volatile String mode = "stream", request, path, headers;
        volatile Throwable failure;
        volatile Socket active;
        Upstream() throws Exception { server.setSoTimeout(1000); thread = new Thread(this::run, "native-http-upstream"); thread.setDaemon(true); thread.start(); }
        void run() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    active = s; s.setSoTimeout(10000); InputStream in = s.getInputStream(); ByteArrayOutputStream headers = new ByteArrayOutputStream(); int state = 0, c;
                    while (state < 4 && (c = in.read()) >= 0) { headers.write(c); state = (state == 0 && c == '\r') ? 1 : (state == 1 && c == '\n') ? 2 : (state == 2 && c == '\r') ? 3 : (state == 3 && c == '\n') ? 4 : 0; if (headers.size() > 65536) throw new IOException("headers too long"); }
                    String h = headers.toString("UTF-8"); this.headers = h; path = h.split(" ")[1]; int length = 0;
                    for (String line : h.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    byte[] bytes = new byte[length]; int off = 0; while (off < length) { int n = in.read(bytes, off, length-off); if (n < 0) throw new EOFException(); off += n; }
                    request = new String(bytes, StandardCharsets.UTF_8); calls.incrementAndGet();
                    OutputStream out = s.getOutputStream(); String m = mode;
                    if (m.equals("json")) { byte[] b = NONSTREAM.getBytes(StandardCharsets.UTF_8); out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(b); out.flush(); continue; }
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    if (m.equals("error")) out.write(ERROR.getBytes(StandardCharsets.UTF_8));
                    else if (m.equals("privacy")) {
                        JSONObject incoming = new JSONObject(request);
                        JSONArray latest = incoming.getJSONArray("messages").getJSONObject(2).getJSONArray("content");
                        String masked = latest.getJSONObject(1).getString("text");
                        String quoted = JSONObject.quote(masked);
                        String stream = START
                            + block(0, "{\"type\":\"thinking\",\"thinking\":\"\"}")
                            + delta(0, "{\"type\":\"thinking_delta\",\"thinking\":" + quoted + "}")
                            + delta(0, "{\"type\":\"signature_delta\",\"signature\":" + quoted + "}") + stop(0)
                            + block(1, "{\"type\":\"text\",\"text\":\"\"}")
                            + delta(1, "{\"type\":\"text_delta\",\"text\":" + quoted + "}") + stop(1)
                            + block(2, "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"f\",\"input\":{}}")
                            + delta(2, "{\"type\":\"input_json_delta\",\"partial_json\":" + JSONObject.quote(new JSONObject().put("email", masked).toString()) + "}") + stop(2)
                            + event("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}") + event("message_stop", "");
                        out.write(stream.getBytes(StandardCharsets.UTF_8));
                    } else if (m.equals("cancel")) {
                        out.write((START + FIRST).getBytes(StandardCharsets.UTF_8)); out.flush();
                        char[] chars = new char[10240]; Arrays.fill(chars, 'x');
                        byte[] chunk = delta(0, "{\"type\":\"thinking_delta\",\"thinking\":" + JSONObject.quote(new String(chars)) + "}").getBytes(StandardCharsets.UTF_8);
                        try { for (int i = 0; i < 4096; i++) { out.write(chunk); out.flush(); } } catch (IOException disconnected) { /* cancellation may close upstream */ }
                        continue;
                    } else { out.write((START + FIRST).getBytes(StandardCharsets.UTF_8)); out.flush(); if (m.equals("stream")) { if (!release.await(8, TimeUnit.SECONDS)) throw new IOException("client did not release upstream"); out.write(REST.getBytes(StandardCharsets.UTF_8)); } }
                    out.flush();
                } catch (SocketTimeoutException ignored) { } catch (SocketException e) { if (!server.isClosed()) failure = e; } catch (Throwable e) { if (!server.isClosed()) failure = e; } finally { active = null; }
            }
        }
        public void close() throws Exception { release.countDown(); server.close(); Socket current = active; if (current != null) current.close(); thread.join(10000); check(!thread.isAlive(), "upstream thread shutdown"); check(failure == null, "upstream failure"); }
    }
    static final class Gateway extends NanoHTTPD {
        final GatewayServer gateway;
        Gateway(JSONObject cfg) throws Exception { super("127.0.0.1", 0); gateway = RoutingSetupOfflineTest.server(cfg); }
        @Override public Response serve(IHTTPSession s) { return gateway.serve(s); }
        @Override protected boolean useGzipWhenAccepted(Response r) { return gateway.useGzipWhenAccepted(r); }
    }
    static HttpURLConnection post(int port, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + "/v1/messages").openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept-Encoding", new JSONObject(body).optBoolean("stream", false) ? "gzip" : "identity");
        c.setRequestProperty("anthropic-version", "2023-06-01"); c.setRequestProperty("anthropic-beta", "fixture-thinking-2026");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } return c;
    }
    static String read(InputStream in) throws Exception { return NativeAnthropicStreamOfflineTest.read(in); }
    static void waitIdle(Gateway g) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (g.gateway.activeForwardRequests() != 0 && System.nanoTime() < end) Thread.sleep(20); check(g.gateway.activeForwardRequests() == 0, "active requests released"); }
    static void row(List<UsageLedger.Row> rows, int count, int status, int input, int output, int cr, int cw) {
        check(rows.size() == count, "usage row count " + count);
        UsageLedger.Row r = rows.get(count - 1);
        check(r.status == status && r.in == input && r.out == output && r.cacheRead == cr && r.cacheWrite == cw, "usage values row " + count);
    }
    static void cancel(int port, String body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10000); socket.setSoTimeout(10000);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(b); out.flush();
            InputStream in = socket.getInputStream(); ByteArrayOutputStream got = new ByteArrayOutputStream(); byte[] chunk = new byte[512];
            while (!got.toString("UTF-8").contains("thinking_delta")) { int n = in.read(chunk); check(n > 0, "cancel first frame"); got.write(chunk, 0, n); }
            socket.setSoLinger(true, 0);
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("native-http-"); Gateway g = null; Upstream u = null;
        List<UsageLedger.Row> rows = Collections.synchronizedList(new ArrayList<UsageLedger.Row>());
        try {
            UsageLedger.init(dir.toFile()); UsageLedger.setHooks(rows::add); u = new Upstream();
            JSONObject cfg = new JSONObject("{\"server\":{\"authKey\":\"magpie\"},\"privacy\":{\"enabled\":true},\"guard\":{\"enabled\":true,\"maxOutputTokens\":50},\"providers\":[{\"id\":\"alpha\",\"protocol\":\"anthropic\",\"keys\":[\"fixture-key-1\",\"fixture-key-2\"],\"models\":[\"real-model\"]}]}");
            cfg.getJSONArray("providers").getJSONObject(0).put("baseUrl", "http://127.0.0.1:" + u.server.getLocalPort() + "/v1");
            g = new Gateway(cfg); java.lang.reflect.Field configField = GatewayServer.class.getDeclaredField("configJson"); configField.setAccessible(true); check(configField.get(g.gateway) == cfg, "fixture config shares reference"); g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true); int port = g.getListeningPort();
            HttpURLConnection c = post(port, BODY); check(c.getResponseCode() == 200, "stream status"); check(!"gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding")), "SSE gzip disabled"); check(String.valueOf(c.getHeaderField("Cache-Control")).contains("no-transform"), "SSE no-transform");
            InputStream in = c.getInputStream(); ByteArrayOutputStream prefix = new ByteArrayOutputStream(); byte[] buffer = new byte[512];
            while (!prefix.toString("UTF-8").contains("thinking_delta")) { int n = in.read(buffer); check(n > 0, "first frame before upstream release"); prefix.write(buffer, 0, n); }
            u.release.countDown(); String received = prefix.toString("UTF-8") + read(in); in.close(); c.disconnect();
            check(received.equals(START + FIRST + REST), "native frames and ordering preserved"); check("/v1/messages".equals(u.path), "upstream path"); check(u.headers.toLowerCase(Locale.ROOT).contains("anthropic-beta: fixture-thinking-2026"), "native beta header retained"); JSONObject sent = new JSONObject(u.request); JSONObject original = new JSONObject(BODY);
            check(sent.getString("model").equals("real-model") && sent.getInt("max_tokens") == 50, "route and output cap"); check(sent.getJSONArray("messages").toString().equals(original.getJSONArray("messages").toString()), "signed history and tool result preserved");
            waitIdle(g); row(rows, 1, 200, 11, 7, 3, 4);
            check(rows.get(0).cacheInputTokens == 18, "native stream cache denominator survives output-only delta");
            u.mode = "json"; c = post(port, new JSONObject(BODY).put("stream", false).toString()); check(c.getResponseCode() == 200, "nonstream status"); check(new JSONObject(read(c.getInputStream())).getJSONArray("content").toString().equals(new JSONObject(NONSTREAM).getJSONArray("content").toString()), "nonstream native content"); c.disconnect(); waitIdle(g); row(rows, 2, 200, 1, 2, 0, 0);
            JSONObject privateBody = new JSONObject(BODY);
            privateBody.getJSONArray("messages").getJSONObject(2).getJSONArray("content").put(new JSONObject().put("type", "text").put("text", "person@example.com"));
            u.mode = "privacy"; c = post(port, privateBody.toString()); check(c.getResponseCode() == 200, "privacy stream status");
            String privacyResult = read(c.getInputStream()); c.disconnect(); waitIdle(g);
            JSONObject privacySent = new JSONObject(u.request); JSONArray sentMessages = privacySent.getJSONArray("messages");
            check(sentMessages.getJSONObject(1).toString().equals(privateBody.getJSONArray("messages").getJSONObject(1).toString()), "signed prefix unchanged");
            String masked = sentMessages.getJSONObject(2).getJSONArray("content").getJSONObject(1).getString("text");
            check(!masked.contains("person@example.com") && masked.contains("MASK_"), "latest user text masked upstream");
            StringBuilder text = new StringBuilder(), tool = new StringBuilder(); boolean thinking = false, signature = false;
            for (String line : privacyResult.split("\n")) if (line.startsWith("data: ")) {
                JSONObject frame = new JSONObject(line.substring(6)); JSONObject d = frame.optJSONObject("delta");
                if (d == null) continue; String type = d.optString("type");
                if (type.equals("thinking_delta")) thinking |= masked.equals(d.optString("thinking"));
                if (type.equals("signature_delta")) signature |= masked.equals(d.optString("signature"));
                if (type.equals("text_delta")) text.append(d.optString("text"));
                if (type.equals("input_json_delta")) tool.append(d.optString("partial_json"));
            }
            check(thinking && signature, "thinking and signature unchanged");
            check(text.toString().equals("person@example.com"), "text restored");
            check(new JSONObject(tool.toString()).getString("email").equals("person@example.com"), "tool input restored");
            row(rows, 3, 200, 11, 7, 3, 4);
            int before = u.calls.get(); JSONObject unsafe = new JSONObject(BODY); unsafe.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("thinking", "a@example.com"); c = post(port, unsafe.toString()); check(c.getResponseCode() >= 400 && c.getResponseCode() < 500, "signed privacy rejected"); c.disconnect(); check(u.calls.get() == before, "privacy rejected before upstream");
            cfg.put("middleware", new JSONObject("{\"wordGuard\":{\"enabled\":true,\"words\":[\"blocked\"],\"mode\":\"mask\"}}")); unsafe.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("thinking", "blocked"); c = post(port, unsafe.toString()); check(c.getResponseCode() >= 400 && c.getResponseCode() < 500, "signed word guard rejected"); c.disconnect(); check(u.calls.get() == before, "word guard before upstream"); cfg.remove("middleware");
            JSONObject guard = cfg.getJSONObject("guard"); guard.put("maxContextTokens", 80).put("reserveTokens", 0).put("keepRecent", 1);
            JSONObject longHistory = new JSONObject(BODY); char[] longText = new char[6000]; Arrays.fill(longText, 'a'); longHistory.getJSONArray("messages").getJSONObject(0).put("content", new String(longText));
            c = post(port, longHistory.toString()); check(c.getResponseCode() >= 400 && c.getResponseCode() < 500, "signed guard pruning rejected"); c.disconnect(); check(u.calls.get() == before, "guard rejected before upstream");
            guard.remove("maxContextTokens"); guard.remove("reserveTokens"); guard.remove("keepRecent");
            cfg.getJSONArray("providers").getJSONObject(0).put("protocol", "openai"); c = post(port, BODY); check(c.getResponseCode() >= 400 && c.getResponseCode() < 500, "signed openai rejected"); c.disconnect(); check(u.calls.get() == before, "openai rejected before upstream"); cfg.getJSONArray("providers").getJSONObject(0).put("protocol", "anthropic");
            waitIdle(g); int attemptedRows = rows.size(); // Existing ledger also records a rejected route attempt.
            u.mode = "truncated"; c = post(port, BODY); check(c.getResponseCode() == 200, "truncated stream status"); String truncated = read(c.getInputStream()); c.disconnect(); check(truncated.contains("event: error") && !truncated.contains("event: message_stop"), "truncation error without stop"); waitIdle(g); check(u.calls.get() == before + 1, "truncation no key fallback"); row(rows, attemptedRows + 1, 502, 11, 0, 3, 4);
            u.mode = "error"; c = post(port, BODY); check(c.getResponseCode() == 200, "upstream error status"); check(read(c.getInputStream()).equals(ERROR), "upstream error preserved once"); c.disconnect(); waitIdle(g); row(rows, attemptedRows + 2, 502, 0, 0, 0, 0);
            u.mode = "cancel"; cancel(port, BODY); waitIdle(g); row(rows, attemptedRows + 3, 502, 11, 0, 3, 4);
            System.out.println("NativeAnthropicHttpTest OK");
        } finally {
            UsageLedger.setHooks(null); if (g != null) g.stop(); if (u != null) u.close();
            GatewayStreamReadOfflineTest.closeLedger();
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) { for (Path p : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p); }
        }
    }
}
