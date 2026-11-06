package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only integration test; synthetic accounts and responses, no external services. */
public final class ZCodeGiftUsageHttpTest {
    static final String MODEL = "zcode/glm-5.3-flash", UPSTREAM = "GLM-5.3-Flash";
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static String event(String type, String rest) { return "event: " + type + "\ndata: {\"type\":\"" + type + "\"" + rest + "}\n\n"; }
    static String stream(String usage, boolean complete) {
        if (usage.equals("nulls")) return stream("none", complete).replace("\"content\":[]}", "\"content\":[],\"usage\":{\"input_tokens\":null,\"output_tokens\":null,\"cache_read_input_tokens\":null}}");
        if (usage.equals("cache")) return stream("full", complete).replace("\"input_tokens\":11}", "\"input_tokens\":11,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}");
        if (usage.equals("cacheonly")) return stream("none", complete).replace("\"content\":[]}", "\"content\":[],\"usage\":{\"cache_read_input_tokens\":3}}");
        String start = event("message_start", ",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + UPSTREAM + "\",\"content\":[]" + (usage.equals("none") ? "" : ",\"usage\":{\"input_tokens\":11}") + "}");
        String text = event("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")
                + event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}")
                + event("content_block_stop", ",\"index\":0");
        if (!complete) return start + text;
        return start + text + event("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"}" + (usage.equals("full") ? ",\"usage\":{\"output_tokens\":7}" : "")) + event("message_stop", "");
    }
    static String json(String usage) {
        if (usage.equals("nulls")) return json("none").replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":null,\"output_tokens\":null,\"cache_read_input_tokens\":null}");
        if (usage.equals("cache")) return json("full").replace("\"input_tokens\":11", "\"input_tokens\":11,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4");
        if (usage.equals("cacheonly")) return json("none").replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"end_turn\",\"usage\":{\"cache_read_input_tokens\":3}");
        return "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + UPSTREAM + "\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\"" +
                (usage.equals("none") ? "" : ",\"usage\":{\"input_tokens\":11" + (usage.equals("full") ? ",\"output_tokens\":7" : "") + "}") + "}";
    }
    static final class Mock implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        final AtomicInteger calls = new AtomicInteger(), disconnected = new AtomicInteger();
        volatile String mode = "json-full";
        volatile boolean allowed = true;
        volatile Throwable failure;
        volatile Socket active;
        Mock() throws Exception {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")); server.setSoTimeout(500);
            thread = new Thread(this::run, "gift-mock"); thread.setDaemon(true); thread.start();
        }
        void run() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    active = s; s.setSoTimeout(10000);
                    InputStream in = s.getInputStream(); ByteArrayOutputStream h = new ByteArrayOutputStream(); int state = 0, c;
                    while (state < 4 && (c = in.read()) != -1) {
                        h.write(c); state = state == 0 && c == '\r' ? 1 : state == 1 && c == '\n' ? 2 : state == 2 && c == '\r' ? 3 : state == 3 && c == '\n' ? 4 : 0;
                        if (h.size() > 65536) throw new IOException("headers too large");
                    }
                    int length = 0; for (String line : h.toString("UTF-8").split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    for (int i = 0; i < length; i++) if (in.read() < 0) throw new EOFException();
                    int call = calls.incrementAndGet(); String current = mode; OutputStream out = s.getOutputStream();
                    int status = (current.equals("fail") || current.equals("fail-usage")) ? 503 : current.equals("retry") && (call % 2 == 1) ? 405 : 200;
                    String content = status != 200 ? (current.equals("fail-usage") ? "{\"error\":{\"message\":\"fixture failure\"},\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}" : "fixture failure") : current.equals("generic") ? "{\"id\":\"m\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}" : current.startsWith("json-") ? json(current.substring(5)) : current.equals("retry") ? json("full") : stream(current.equals("partial") ? "partial" : current.equals("none") ? "none" : current.equals("nulls") ? "nulls" : current.equals("cache") ? "cache" : current.equals("cacheonly") ? "cacheonly" : "full", !current.equals("truncated") && !current.equals("cancel"));
                    byte[] data = content.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: " + (current.startsWith("json-") || current.equals("retry") || current.equals("generic") || status != 200 ? "application/json" : "text/event-stream") + "\r\n" + (current.equals("cancel") ? "" : "Content-Length: " + data.length + "\r\n") + "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.UTF_8)); out.write(data); out.flush();
                    if (current.equals("cancel")) {
                        char[] chars = new char[8192]; Arrays.fill(chars, 'x');
                        byte[] more = event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + JSONObject.quote(new String(chars)) + "}").getBytes(StandardCharsets.UTF_8);
                        // Observe peer EOF/reset as well as write failure. A platform may
                        // accept buffered writes after close, or drain the finite fixture
                        // before the client reset reaches this thread.
                        s.setSoTimeout(100);
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        try {
                            while (System.nanoTime() < until) {
                                out.write(more); out.flush();
                                try { if (in.read() == -1) { disconnected.incrementAndGet(); break; } }
                                catch (SocketTimeoutException pending) { /* peer still open */ }
                            }
                        } catch (IOException expected) { disconnected.incrementAndGet(); }
                    }
                } catch (SocketTimeoutException ignored) { }
                catch (SocketException e) { if (!server.isClosed()) failure = e; }
                catch (Throwable e) { if (!server.isClosed()) failure = e; }
                finally { active = null; }
            }
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); Socket s = active; if (s != null) s.close(); thread.join(10000); check(!thread.isAlive(), "mock shutdown"); if (failure != null) throw new AssertionError("mock failure", failure); }
    }
    static final class Transport implements GatewayServer.GiftTransport {
        final Mock mock;
        final List<Boolean> dressed = Collections.synchronizedList(new ArrayList<Boolean>());
        Transport(Mock mock) { this.mock = mock; }
        public JSONObject summary(String jwt, String device) {
            return new JSONObject().put("ok", true).put("buckets", new JSONArray().put(new JSONObject().put("remaining", 1).put("capabilities", new JSONArray().put("model:" + (mock.allowed ? UPSTREAM : "other")))));
        }
        public ZCodeGift.Stream open(String jwt, String device, String site, String model, JSONObject body, boolean dress) {
            dressed.add(dress); ZCodeGift.Stream result = new ZCodeGift.Stream();
            try {
                check(UPSTREAM.equals(model), "actual upstream model case");
                HttpURLConnection conn = (HttpURLConnection)new URL("http://127.0.0.1:" + mock.port() + "/gift").openConnection();
                conn.setConnectTimeout(3000); conn.setReadTimeout(10000); conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                result.conn = conn; result.status = conn.getResponseCode();
                if (result.status < 400) result.is = conn.getInputStream();
                else { try (InputStream error = conn.getErrorStream()) { if (error != null) result.errorBody = read(error); } }
            } catch (Exception e) { result.status = 0; result.error = "fixture transport error"; }
            return result;
        }
    }
    static final class CountingConnection extends HttpURLConnection {
        int disconnects;
        CountingConnection() throws Exception { super(new URL("http://127.0.0.1/fixture")); }
        @Override public void disconnect() { disconnects++; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    static final class CountingInput extends InputStream {
        int closes;
        @Override public int read() throws IOException { throw new IOException("fixture read failure"); }
        @Override public void close() { closes++; }
    }
    static final class SyntheticTransport implements GatewayServer.GiftTransport {
        int summaries, opens;
        String mode;
        final List<CountingConnection> connections = new ArrayList<>();
        final List<CountingInput> inputs = new ArrayList<>();
        SyntheticTransport(String mode) { this.mode = mode; }
        public JSONObject summary(String jwt, String device) {
            summaries++;
            if (mode.equals("summary-fail")) return new JSONObject().put("ok", false).put("error", "fixture summary failure");
            return new JSONObject().put("ok", true).put("buckets", new JSONArray().put(new JSONObject().put("remaining", 1).put("capabilities", new JSONArray().put("model:" + UPSTREAM))));
        }
        public ZCodeGift.Stream open(String jwt, String device, String site, String model, JSONObject body, boolean dressed) {
            opens++;
            try {
                ZCodeGift.Stream result = new ZCodeGift.Stream();
                CountingConnection conn = new CountingConnection(); connections.add(conn); result.conn = conn;
                result.status = 200;
                if (!mode.equals("null-stream")) { CountingInput in = new CountingInput(); inputs.add(in); result.is = in; }
                result.refused = mode.equals("refused");
                return result;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        void checkClosed() {
            for (CountingConnection c : connections) check(c.disconnects == 1, "connection disconnected exactly once: " + c.disconnects);
            for (CountingInput in : inputs) check(in.closes == 1, "input closed exactly once: " + in.closes);
        }
    }
    static final class Gateway extends NanoHTTPD {
        final GatewayServer gateway;
        Gateway(JSONObject cfg, GatewayServer.GiftTransport t) throws Exception { super("127.0.0.1", 0); gateway = RoutingSetupOfflineTest.server(cfg); gateway.setGiftTransportForTest(t); }
        @Override public Response serve(IHTTPSession s) { return gateway.serve(s); }
        @Override protected boolean useGzipWhenAccepted(Response r) { return gateway.useGzipWhenAccepted(r); }
    }
    static JSONObject config(int port) {
        return new JSONObject().put("server", new JSONObject().put("authKey", "fixture-gateway"))
                .put("providers", new JSONArray().put(new JSONObject().put("id", "zcode").put("protocol", "openai")
                        .put("baseUrl", "http://127.0.0.1:" + port + "/v1").put("keys", new JSONArray().put("fixture-key"))
                        .put("accounts", new JSONArray().put(new JSONObject().put("jwt", "fixture-jwt").put("device", "fixture-device")))
                        .put("models", new JSONArray().put("glm-5.3-flash"))));
    }
    static String body(boolean stream) { return new JSONObject().put("model", MODEL).put("max_tokens", 64).put("stream", stream).put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"))).toString(); }
    static String read(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) != -1) b.write(buf, 0, n); return b.toString("UTF-8"); }
    static HttpURLConnection post(int port, boolean messages, boolean stream) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + (messages ? "/v1/messages" : "/v1/chat/completions")).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept-Encoding", "gzip");
        if (messages) c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream out = c.getOutputStream()) { out.write(body(stream).getBytes(StandardCharsets.UTF_8)); } return c;
    }
    static String request(Gateway g, boolean messages, boolean stream, int status) throws Exception {
        HttpURLConnection c = post(g.getListeningPort(), messages, stream);
        try {
            check(c.getResponseCode() == status, "HTTP status expected " + status + " got " + c.getResponseCode());
            if (stream && status == 200) check(!"gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding")), "SSE gzip disabled");
            InputStream source = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (source == null) return "";
            if ("gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding"))) source = new java.util.zip.GZIPInputStream(source);
            try (InputStream in = source) { return read(in); }
        } finally { c.disconnect(); }
    }
    static void idle(Gateway g) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (g.gateway.activeForwardRequests() != 0 && System.nanoTime() < end) Thread.sleep(10);
        check(g.gateway.activeForwardRequests() == 0, "tracked response released");
    }
    static void cancel(Gateway g, boolean messages) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", g.getListeningPort())) {
            socket.setSoTimeout(10000); byte[] b = body(true).getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream(); out.write(("POST " + (messages ? "/v1/messages" : "/v1/chat/completions") + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(b); out.flush();
            InputStream in = socket.getInputStream(); ByteArrayOutputStream received = new ByteArrayOutputStream(); byte[] buf = new byte[512];
            while (!received.toString("UTF-8").contains("hello")) { int n = in.read(buf); check(n > 0, "cancel first content"); received.write(buf, 0, n); }
            socket.setSoLinger(true, 0);
        }
    }
    static List<UsageLedger.Row> snapshot(List<UsageLedger.Row> rows, int from) { synchronized (rows) { return new ArrayList<UsageLedger.Row>(rows.subList(from, rows.size())); } }
    static UsageLedger.Row verify(List<UsageLedger.Row> all, int from, int attempts, int status) {
        List<UsageLedger.Row> rows = snapshot(all, from); check(rows.size() == attempts, "attempt rows expected " + attempts + " got " + rows.size());
        String id = rows.get(0).requestId; check(id != null && !id.isEmpty(), "logical request id"); int terminals = 0;
        for (int i = 0; i < rows.size(); i++) {
            UsageLedger.Row row = rows.get(i); check(id.equals(row.requestId), "shared logical id");
            check("zcode".equals(row.prov) && "gift".equals(row.ep), "gift provider and endpoint");
            check(UPSTREAM.equals(row.model) && MODEL.equals(row.req), "requested and actual model");
            check(row.attempt == i + 1, "attempt ordinal"); if (row.terminal) terminals++;
            if (i < rows.size() - 1) check(!row.terminal && row.status >= 400, "retry failure nonterminal");
        }
        UsageLedger.Row last = rows.get(rows.size() - 1); check(terminals == 1 && last.terminal, "exactly one terminal");
        check(last.status == status && last.tries == attempts, "terminal status and tries: " + last.status + "/" + last.tries); return last;
    }
    static void usage(UsageLedger.Row row, String kind) {
        if (kind.equals("full")) check(row.inputKnown && row.outputKnown && row.in == 11 && row.out == 7 && "known".equals(row.usageSource), "known usage");
        else if (kind.equals("partial")) check(row.inputKnown && !row.outputKnown && row.in == 11 && "partial".equals(row.usageSource), "input-only usage must remain partial");
        else check(!row.inputKnown && !row.outputKnown && (row.usageSource == null || row.usageSource.isEmpty() || row.usageSource.equals("unknown")), "absent usage unknown");
    }
    interface Case { void run() throws Exception; }
    static void runCase(String name, List<String> failures, Case test) {
        try { test.run(); System.out.println("PASS " + name); } catch (Throwable e) { failures.add(name + ": " + e); System.err.println("FAIL " + name + ": " + e); }
    }
    public static void main(String[] args) throws Exception {
        List<UsageLedger.Row> rows = Collections.synchronizedList(new ArrayList<UsageLedger.Row>()); List<String> failures = new ArrayList<String>();
        java.nio.file.Path ledgerDir = Files.createTempDirectory("gift-http-ledger-");
        UsageLedger.init(ledgerDir.toFile()); UsageLedger.setHooks(rows::add);
        try (Mock mock = new Mock()) {
            Transport t = new Transport(mock); Gateway g = new Gateway(config(mock.port()), t); g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            try {
                for (boolean messages : new boolean[]{false, true}) for (boolean streaming : new boolean[]{false, true}) for (String kind : new String[]{"full", "partial", "none"}) {
                    final boolean m = messages, s = streaming; final String k = kind;
                    runCase((m ? "messages" : "chat") + (s ? " stream " : " json ") + k, failures, () -> {
                        mock.mode = s ? k : "json-" + k; int before = rows.size();
                        String response = request(g, m, s, 200); idle(g); check(response.contains("hello"), "response content");
                        usage(verify(rows, before, 1, 200), k);
                    });
                }
                for (boolean messages : new boolean[]{false, true}) for (boolean streaming : new boolean[]{false, true}) {
                    final boolean m = messages, st = streaming;
                    runCase((m ? "messages" : "chat") + (st ? " stream" : " json") + " null usage", failures, () -> {
                        mock.mode = st ? "nulls" : "json-nulls"; int before = rows.size(); request(g, m, st, 200); idle(g); usage(verify(rows, before, 1, 200), "none");
                    });
                    runCase((m ? "messages" : "chat") + (st ? " stream" : " json") + " cache usage", failures, () -> {
                        mock.mode = st ? "cache" : "json-cache"; int before = rows.size(); request(g, m, st, 200); idle(g);
                        UsageLedger.Row row = verify(rows, before, 1, 200);
                        check(row.inputKnown && row.outputKnown && row.out == 7 && row.cacheRead == 3 && row.cacheWrite == 4 && row.cacheInputTokens == 18,
                                "cache counts and denominator: input=" + row.in + ", read=" + row.cacheRead + ", write=" + row.cacheWrite + ", denominator=" + row.cacheInputTokens);
                    });
                    runCase((m ? "messages" : "chat") + (st ? " stream" : " json") + " cache only", failures, () -> {
                        mock.mode = st ? "cacheonly" : "json-cacheonly"; int before = rows.size(); request(g, m, st, 200); idle(g);
                        UsageLedger.Row row = verify(rows, before, 1, 200); check(!row.inputKnown && !row.outputKnown && row.cacheRead == 3, "cache alone does not establish input total");
                    });
                }
                runCase("conversion field presence", failures, () -> {
                    JSONObject full = new JSONObject(json("none"));
                    full.put("usage", new JSONObject().put("output_tokens", 7).put("input_tokens", JSONObject.NULL).put("cache_read_input_tokens", 3).put("cache_creation_input_tokens", JSONObject.NULL));
                    JSONObject converted = ZCodeAnthropic.toOpenAI(full, MODEL).getJSONObject("usage");
                    check(!converted.has("prompt_tokens") && converted.getInt("completion_tokens") == 7 && !converted.has("total_tokens"), "output-only conversion");
                    check(converted.getJSONObject("prompt_tokens_details").getInt("cached_tokens") == 3 && !converted.getJSONObject("prompt_tokens_details").has("cache_write_tokens"), "known cache fields only");
                    full.put("usage", new JSONObject().put("input_tokens", JSONObject.NULL).put("output_tokens", JSONObject.NULL));
                    check(!ZCodeAnthropic.toOpenAI(full, MODEL).has("usage"), "null-only usage omitted");
                    full.put("usage", new JSONObject().put("input_tokens", 11).put("cache_read_input_tokens", 3).put("cache_creation_input_tokens", 4));
                    converted = ZCodeAnthropic.toOpenAI(full, MODEL).getJSONObject("usage");
                    check(converted.getInt("prompt_tokens") == 18 && !converted.has("completion_tokens") && !converted.has("total_tokens"), "input-only conversion with cache");
                });
                runCase("HTTP failure with usage", failures, () -> { mock.mode = "fail-usage"; int before = rows.size(); request(g, false, false, 503); idle(g); usage(verify(rows, before, 1, 503), "full"); });
                runCase("HTTP failure", failures, () -> { mock.mode = "fail"; int before = rows.size(); request(g, false, false, 503); idle(g); verify(rows, before, 1, 503); });
                runCase("405 is not replayed", failures, () -> { mock.calls.set(0); mock.mode = "retry"; t.dressed.clear(); int before = rows.size(); request(g, false, false, 405); idle(g); verify(rows, before, 1, 405);check(mock.calls.get()==1 && t.dressed.equals(Arrays.asList(true)),"single dressed request without replay"); });
                for (boolean messages : new boolean[]{false, true}) {
                    final boolean m = messages;
                    runCase((m ? "messages" : "chat") + " truncated", failures, () -> { mock.mode = "truncated"; int before = rows.size(); request(g, m, true, 200); idle(g); verify(rows, before, 1, 502); });
                    runCase((m ? "messages" : "chat") + " cancel", failures, () -> { mock.mode = "cancel"; int before = rows.size(), closed = mock.disconnected.get(); cancel(g, m); idle(g); verify(rows, before, 1, 502); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (mock.disconnected.get() == closed && System.nanoTime() < end) Thread.sleep(10); check(mock.disconnected.get() > closed, "cancel closes upstream socket"); });
                }
                runCase("unmatched fallback and next request", failures, () -> {
                    mock.allowed = false; mock.mode = "generic"; int before = rows.size(), opened = t.dressed.size();
                    request(g, false, false, 200); idle(g); check(t.dressed.size() == opened, "unmatched gift opens no stream");
                    List<UsageLedger.Row> fallback = snapshot(rows, before); check(!fallback.isEmpty(), "fallback recorded");
                    int terminalCount = 0; String fallbackId = fallback.get(0).requestId;
                    check(fallbackId != null && !fallbackId.isEmpty(), "fallback logical id");
                    for (UsageLedger.Row r : fallback) {
                        check(!"gift".equals(r.ep), "unmatched must not create gift attempt");
                        check(fallbackId.equals(r.requestId), "fallback rows share logical id");
                        if (r.terminal) terminalCount++;
                    }
                    check(terminalCount == 1, "fallback has exactly one terminal");
                    mock.allowed = true; mock.mode = "json-full"; before = rows.size(); request(g, false, false, 200); idle(g); usage(verify(rows, before, 1, 200), "full");
                });
                for (boolean disableProvider : new boolean[]{true, false}) {
                    final boolean providerDisabled = disableProvider;
                    runCase(providerDisabled ? "disabled provider skips gift" : "disabled account skips gift", failures, () -> {
                        JSONObject cfg = config(mock.port());
                        JSONObject provider = cfg.getJSONArray("providers").getJSONObject(0);
                        provider.put("keys", new JSONArray());
                        if (providerDisabled) provider.put("enabled", false);
                        else provider.getJSONArray("accounts").getJSONObject(0).put("enabled", false);
                        SyntheticTransport fake = new SyntheticTransport("refused");
                        Gateway disabled = new Gateway(cfg, fake);
                        int before = rows.size(), mockBefore = mock.calls.get();
                        NanoHTTPD.Response result = disabled.gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", body(false), null));
                        try { check(result.getStatus().getRequestStatus() >= 400, "disabled fallback fails"); }
                        finally { result.close(); }
                        check(fake.summaries == 0 && fake.opens == 0 && mock.calls.get() == mockBefore, "disabled path must not call upstream");
                        for (UsageLedger.Row row : snapshot(rows, before)) check(!"gift".equals(row.ep), "disabled path has no gift attempt");
                    });
                }
                runCase("refused HTTP 200 is not replayed", failures, () -> {
                    SyntheticTransport fake = new SyntheticTransport("refused"); Gateway synthetic = new Gateway(config(mock.port()), fake);
                    int before = rows.size();
                    NanoHTTPD.Response result = synthetic.gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", body(false), null));
                    try { check(result.getStatus().getRequestStatus() == 405, "refusal returns 405"); } finally { result.close(); }
                    check(fake.opens == 1, "refusal must not retry dressed request"); verify(rows, before, 1, 405); fake.checkClosed();
                });
                for (boolean messages : new boolean[]{false, true}) {
                    final boolean m = messages;
                    runCase((m ? "messages" : "chat") + " failed gift summary does not use paid plan", failures, () -> {
                        SyntheticTransport synthetic = new SyntheticTransport("summary-fail"); g.gateway.setGiftTransportForTest(synthetic);
                        int before = rows.size(), calls = mock.calls.get();
                        try {
                            request(g, m, false, 502); idle(g);
                            check(synthetic.summaries == 1 && synthetic.opens == 0, "summary queried once; no gift send");
                            check(mock.calls.get() == calls, "failed summary must not fall back to paid plan");
                            check(snapshot(rows, before).isEmpty(), "no ledger charge for unsent request");
                        } finally { g.gateway.setGiftTransportForTest(t); }
                    });
                }
                for (boolean disableProvider : new boolean[]{true, false}) {
                    final boolean provider = disableProvider;
                    runCase("test-model disabled " + (provider ? "provider" : "account") + " does not probe gift", failures, () -> {
                        JSONObject cfg = config(mock.port());
                        JSONObject zcode = cfg.getJSONArray("providers").getJSONObject(0);
                        if (provider) zcode.put("enabled", false);
                        else { zcode.getJSONArray("accounts").getJSONObject(0).put("enabled", false); zcode.put("keys", new JSONArray()); }
                        SyntheticTransport synthetic = new SyntheticTransport("summary-fail");
                        Gateway disabled = new Gateway(cfg, synthetic); disabled.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                        int calls = mock.calls.get();
                        try {
                            HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + disabled.getListeningPort() + "/api/test-model").openConnection();
                            c.setConnectTimeout(3000); c.setReadTimeout(5000); c.setRequestMethod("POST"); c.setDoOutput(true);
                            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer fixture-gateway");
                            try {
                                try (OutputStream out = c.getOutputStream()) { out.write(new JSONObject().put("model", MODEL).toString().getBytes(StandardCharsets.UTF_8)); }
                                check(c.getResponseCode() == 200, "test-model response status");
                                JSONObject response; try (InputStream in = c.getInputStream()) { response = new JSONObject(read(in)); }
                                check(!response.optBoolean("ok", true), "disabled model must not succeed");
                                check(!"gift".equals(response.optString("billing")), "disabled session must not enter gift probe");
                                check(synthetic.summaries == 0 && synthetic.opens == 0, "no injected gift calls");
                                check(mock.calls.get() == calls, "disabled model made no generic call");
                            } finally { c.disconnect(); }
                        } finally { disabled.stop(); }
                    });
                }
                for (String mode : new String[]{"null-stream", "read-failure"}) {
                    runCase("pre-handoff cleanup " + mode, failures, () -> {
                        SyntheticTransport fake = new SyntheticTransport(mode); Gateway synthetic = new Gateway(config(mock.port()), fake);
                        int before = rows.size();
                        NanoHTTPD.Response result = synthetic.gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", body(mode.equals("null-stream")), null));
                        try { check(result.getStatus().getRequestStatus() == 502, "failure returns 502"); } finally { result.close(); }
                        verify(rows, before, 1, 502); fake.checkClosed();
                    });
                }
                runCase("same-thread cleanup", failures, () -> {
                    mock.mode = "json-full"; mock.allowed = true; int before = rows.size();
                    NanoHTTPD.Response first = g.gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", body(false), null));
                    try { check(first.getStatus().getRequestStatus() == 200, "direct gift status"); try (InputStream in = first.getData()) { read(in); } } finally { first.close(); }
                    verify(rows, before, 1, 200); mock.allowed = false; mock.mode = "generic"; before = rows.size();
                    NanoHTTPD.Response next = g.gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", body(false), null));
                    try { check(next.getStatus().getRequestStatus() == 200, "direct fallback status"); try (InputStream in = next.getData()) { read(in); } } finally { next.close(); }
                    for (UsageLedger.Row r : snapshot(rows, before)) check(!"gift".equals(r.ep), "same-thread gift state leaked"); idle(g);
                });
            } finally { g.stop(); }
        } finally {
            UsageLedger.setHooks(null);
            GatewayStreamReadOfflineTest.closeLedger();
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(ledgerDir)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " failed: " + failures);
        System.out.println("ZCodeGiftUsageHttpTest OK");
    }
}
