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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Synthetic loopback-only Qoder/Gateway regression; no real credentials or services. */
public final class QoderGatewayHttpTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static final String SECRET = "upstream-secret-must-not-leak";
    private static final String ROTATED = "rotated-token";
    private static final String ROTATED_REFRESH = "rotated-refresh";

    /**
     * A loopback Qoder endpoint. Every connection is answered on its own thread, so two requests can
     * be in flight at once (the concurrency case), and the answer to the n-th call comes from the
     * script when the script has one. Nothing here ever reaches a real Qoder host.
     */
    private static final class Upstream implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        final List<String> authorizations = Collections.synchronizedList(new ArrayList<String>());
        final List<String> configAtCall = Collections.synchronizedList(new ArrayList<String>());
        final List<Thread> handlers = Collections.synchronizedList(new ArrayList<Thread>());
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        final List<String> script = Collections.synchronizedList(new ArrayList<String>());
        volatile String reply;
        /** Read when each call arrives: what the store already said before that answer was sent. */
        volatile Supplier<String> evidence;
        /** Held before answering, so a case can make its answer slower than a deadline. */
        volatile long delayMillis;
        /** Awaited by the first {@code gateParties} calls: both requests are inside before either is answered. */
        volatile CyclicBarrier gate;
        volatile int gateParties;
        volatile String lastRequest;
        volatile Throwable failure;
        Upstream(String response) throws IOException {
            reply = response;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(300);
            worker = new Thread(this::accept, "qoder-loopback"); worker.setDaemon(true); worker.start();
        }
        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket s = listener.accept();
                    sockets.add(s);
                    Thread answer = new Thread(() -> handle(s), "qoder-loopback-answer");
                    answer.setDaemon(true);
                    handlers.add(answer);
                    answer.start();
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable e) { if (!listener.isClosed()) failure = e; }
            }
        }
        private void handle(Socket s) {
            int index = calls.getAndIncrement();
            try (Socket socket = s) {
                socket.setSoTimeout(4000);
                InputStream in = socket.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                int state = 0, b;
                while (state < 4 && (b = in.read()) != -1) {
                    header.write(b); if (header.size() > 65536) throw new IOException("headers too large");
                    state = state == 0 && b == 13 ? 1 : state == 1 && b == 10 ? 2 : state == 2 && b == 13 ? 3 : state == 3 && b == 10 ? 4 : 0;
                }
                int length = 0; String authorization = "";
                for (String line : header.toString("UTF-8").split("\r\n")) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    if (lower.startsWith("authorization:")) authorization = line.substring(line.indexOf(':') + 1).trim();
                }
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                while (length-- > 0) { int c = in.read(); if (c < 0) throw new EOFException("request"); body.write(c); }
                String text = body.toString("UTF-8");
                lastRequest = text;
                bodies.add(text); authorizations.add(authorization);
                Supplier<String> hook = evidence;
                configAtCall.add(hook == null ? "" : hook.get());
                CyclicBarrier sync = gate;
                if (sync != null && index < gateParties) sync.await(20000, TimeUnit.MILLISECONDS);
                long delay = delayMillis; if (delay > 0) Thread.sleep(delay);
                socket.getOutputStream().write(bytes(answer(index))); socket.getOutputStream().flush();
            } catch (IOException ignored) {
                // A client that went away (the cancellation case) is not a fixture failure.
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
            } catch (Throwable e) { if (!listener.isClosed()) failure = e; }
        }
        private String answer(int index) {
            synchronized (script) { if (index < script.size()) return script.get(index); }
            return reply;
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/qoder"; }
        public void close() throws Exception {
            listener.close();
            synchronized (sockets) { for (Socket s : sockets) try { s.close(); } catch (IOException ignored) { } sockets.clear(); }
            worker.join(5000);
            check(!worker.isAlive(), "upstream thread did not stop");
            synchronized (handlers) {
                for (Thread answer : handlers) answer.join(5000);
                for (Thread answer : handlers) check(!answer.isAlive(), "upstream answer thread did not stop");
            }
            check(failure == null, "upstream fixture: " + failure);
        }
    }
    private static String http(int code, String body) {
        return "HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + bytes(body).length + "\r\nConnection: close\r\n\r\n" + body;
    }
    private static String sse(String content) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + bytes(content).length + "\r\nConnection: close\r\n\r\n" + content;
    }
    /** A Qoder envelope carrying one OpenAI chunk, the shape QoderStream reads. */
    private static String envelope(JSONObject chunk) throws Exception {
        return "data: " + new JSONObject().put("statusCodeValue", 200).put("body", chunk.toString()) + "\n\n";
    }
    /** A complete Qoder answer: content, then the finish reason, then the terminal frame. */
    private static String answered(String content) throws Exception {
        String first = envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", content)).put("finish_reason", JSONObject.NULL))));
        String second = envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject()).put("finish_reason", "stop"))));
        return sse(first + second + "data: [DONE]\n\n");
    }
    /** Qoder refusing a chat because the job token it carried lapsed (the built-in's 401). */
    private static String lapsed(String message) throws Exception {
        return new JSONObject().put("code", 105).put("message", message).toString();
    }
    /** The rotating answer a device token exchange gives. */
    private static String renewalAnswer() throws Exception {
        return new JSONObject().put("token", ROTATED).put("refresh_token", ROTATED_REFRESH)
                .put("expires_in", 86400).toString();
    }
    /** That answer as the HTTP response the one-use exchange reads. */
    private static String renewing() throws Exception { return http(200, renewalAnswer()); }
    private static JSONObject account() throws Exception {
        return new JSONObject().put("id", "fixture-account").put("token", "fixture-token")
                .put("deviceToken", "fixture-device-token").put("deviceChat", true)
                .put("uid", "fixture-uid").put("machineId", "fixture-machine")
                .put("expiresAt", System.currentTimeMillis() + 86400000L);
    }
    /** The same account carrying the one-use value a lapsed sign-in is renewed with. */
    private static JSONObject renewableAccount() throws Exception {
        return account().put("refresh", "fixture-refresh").put("deviceRefresh", "fixture-refresh");
    }
    private static JSONObject config(Upstream backup) throws Exception { return config(backup, account(), 0L); }
    private static JSONObject config(Upstream backup, JSONObject account, long requestBudgetMs) throws Exception {
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("enabled", true).put("protocol", "qoder")
                .put("models", new JSONArray().put("fixture-model")).put("accounts", new JSONArray().put(account))
                .put("modelMeta", new JSONObject().put("fixture-model", new JSONObject().put("key", "fixture-model").put("source", "fixture")));
        JSONObject fallback = new JSONObject().put("id", "qoder-test-backup").put("protocol", "openai")
                .put("baseUrl", backup.url()).put("keys", new JSONArray().put("fixture-key"))
                .put("models", new JSONArray().put("fixture-model"));
        JSONObject failover = new JSONObject().put("enabled", true).put("maxTargets", 2).put("maxAttempts", 3);
        if (requestBudgetMs > 0) failover.put("requestBudgetMs", requestBudgetMs);
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("routing", new JSONObject().put("groupsEnabled", true))
                .put("failover", failover)
                .put("providers", new JSONArray().put(qoder).put(fallback))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "qoder-test-group").put("strategy", "order")
                        .put("targets", new JSONArray().put("qoder_cn/fixture-model").put("qoder-test-backup/fixture-model"))));
    }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        final File configFile;
        Gateway(JSONObject config, Upstream qoder) throws Exception { this(config, qoder, null); }
        Gateway(JSONObject config, Upstream qoder, Upstream renewal) throws Exception {
            super("127.0.0.1", 0);
            inner = RoutingSetupOfflineTest.server(config);
            java.nio.file.Path isolated=Files.createTempDirectory("qoder-gateway-");
            UsageLedger.init(isolated.toFile());
            configFile = isolated.resolve("config.json").toFile();
            RoutingAuditOfflineTest.file(inner, configFile);
            Field f = GatewayServer.class.getDeclaredField("groupTurns"); f.setAccessible(true);
            f.set(inner, new ConcurrentHashMap<String, Integer>());
            inner.setQoderConnectionsForTests(url -> {
                boolean renew = url != null && url.contains("/deviceToken/refresh");
                if (renew && renewal == null) throw new IOException("this case has no sign-in to renew");
                return (HttpURLConnection) new URL(renew ? renewal.url() : qoder.url()).openConnection(Proxy.NO_PROXY);
            });
            start(SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() { inner.setQoderConnectionsForTests(null); stop(); }
    }
    private static final class Result { int status; String body, retry; }
    private static Result post(Gateway gateway, String limits) throws Exception { return post(gateway, limits, null); }
    /** One client request; {@code session} makes it its own conversation, as two clients' requests are. */
    private static Result post(Gateway gateway, String limits, String session) throws Exception {
        String body = "{\"model\":\"group/qoder-test-group\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]" + limits + "}";
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + gateway.getListeningPort() + "/v1/chat/completions").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer fixture-auth"); c.setRequestProperty("Content-Type", "application/json");
        if (session != null) c.setRequestProperty("x-session-id", session);
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
    /** The token the store holds for the fixture account, "" when the store says nothing. */
    private static String storedToken(File file) {
        try {
            if (file == null || !file.isFile()) return "";
            JSONObject stored = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray providers = stored.optJSONArray("providers");
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject provider = providers.optJSONObject(i);
                if (provider == null || !"qoder_cn".equals(provider.optString("id"))) continue;
                JSONArray accounts = provider.optJSONArray("accounts");
                if (accounts == null || accounts.length() == 0) return "";
                return accounts.getJSONObject(0).optString("token", "");
            }
        } catch (Exception unreadable) { return ""; }
        return "";
    }
    private static String storedRefresh(File file) {
        try {
            JSONObject stored = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray providers = stored.getJSONArray("providers");
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.getJSONObject(i);
                if (!"qoder_cn".equals(provider.optString("id"))) continue;
                return provider.getJSONArray("accounts").getJSONObject(0).optString("refresh", "");
            }
        } catch (Exception unreadable) { return ""; }
        return "";
    }
    /** The calls Qoder received, grouped by the request_set_id each carried, in arrival order. */
    private static LinkedHashMap<String, List<JSONObject>> byRequestSet(Upstream upstream) throws Exception {
        LinkedHashMap<String, List<JSONObject>> grouped = new LinkedHashMap<String, List<JSONObject>>();
        for (int i = 0; i < upstream.bodies.size(); i++) {
            JSONObject seen = sent(upstream, i);
            String set = seen.optString("request_set_id", "");
            List<JSONObject> calls = grouped.get(set);
            if (calls == null) grouped.put(set, calls = new ArrayList<JSONObject>());
            calls.add(seen);
        }
        return grouped;
    }
    private static JSONObject sent(Upstream upstream, int call) throws Exception {
        return new JSONObject(QoderAuth.decodeBody(upstream.bodies.get(call)));
    }
    /**
     * The budget the request should put on the wire: the smallest cap the caller asked for, and nought
     * else. -1 when the fragment asks for none — an invalid case never reaches upstream, so it has no
     * expected value to check.
     */
    private static int capOf(String limits) throws Exception {
        if (limits == null || limits.trim().isEmpty()) return -1;
        String fragment = limits.startsWith(",") ? limits.substring(1) : limits;
        JSONObject asked = new JSONObject("{" + fragment + "}");
        int cap = asked.has("max_tokens") ? asked.optInt("max_tokens", -1) : -1;
        if (asked.has("max_completion_tokens")) {
            int other = asked.optInt("max_completion_tokens", -1);
            cap = cap < 0 ? other : Math.min(cap, other);
        }
        return cap;
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
            int cap = capOf(limits);
            if (expectedCalls == 1 && cap >= 0) {
                JSONObject request = new JSONObject(QoderAuth.decodeBody(primary.lastRequest));
                check(request.getJSONObject("parameters").getInt("max_tokens") == cap,
                        name + " sent max_tokens " + request.getJSONObject("parameters").opt("max_tokens") + ", not " + cap);
            }
        } finally { rest.remove(key); if (previous != null) rest.put(key, previous); }
    }

    /**
     * A case where Qoder answers a refusal first: what the gateway asks again, how often, and what the
     * store holds when it does. Everything goes over the loopback, renewals included.
     */
    private static final class Case implements AutoCloseable {
        final Upstream chat, renewal, backup;
        final Gateway gateway;
        Case(String first, String second, String renewalAnswer, long requestBudgetMs) throws Exception {
            chat = new Upstream(first);
            chat.script.add(first);
            if (second != null) chat.script.add(second);
            renewal = renewalAnswer == null ? null : new Upstream(renewalAnswer);
            backup = new Upstream(http(500, "backup-called"));
            gateway = new Gateway(config(backup, renewableAccount(), requestBudgetMs), chat, renewal);
            chat.evidence = () -> storedToken(gateway.configFile);
        }
        Result send() throws Exception { return post(gateway, ""); }
        public void close() throws Exception { gateway.close(); chat.close(); backup.close(); if (renewal != null) renewal.close(); }
    }

    /**
     * The verdicts the shipped 0.2.64 failure() itself gives, one row per refusal, produced by
     * running that function under node on the exact same (status, text) pairs: label, answered
     * status, body, then the status and the lapsed flag QoderFailurePolicy must answer. Locked in
     * here so the tarball is not needed at test time; {@code dev} regenerates it.
     */
    private static final String[][] PLUGIN_VERDICTS = {
            {"queue code 10605 @403", "403", "{\"code\":10605,\"message\":\"queued\"}", "503", "false"},
            {"queue code 10605 @400", "400", "{\"code\":10605,\"message\":\"queued\"}", "503", "false"},
            {"queue state isQueued @403", "403", "{\"code\":403,\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":7}}", "503", "false"},
            {"queue state serviceAvailable=false", "403", "{\"code\":403,\"queue\":{\"serviceAvailable\":false}}", "503", "false"},
            {"queue state in message", "403", "{\"code\":403,\"message\":\"{\\\"isQueued\\\":true,\\\"retryAfterSeconds\\\":12}\"}", "503", "false"},
            {"queue state no code", "403", "{\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"queue isQueued not true", "403", "{\"code\":403,\"queue\":{\"isQueued\":false,\"serviceAvailable\":true}}", "401", "true"},
            {"queue retryAfter field only", "403", "{\"code\":403,\"queue\":{\"retryAfterSeconds\":9}}", "401", "true"},
            {"queue retry 0 falls back", "403", "{\"code\":403,\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":0}}", "503", "false"},
            {"queue retry huge", "403", "{\"code\":403,\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":999999}}", "503", "false"},
            {"queue retry negative", "403", "{\"code\":403,\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":-5}}", "503", "false"},
            {"queue wrapper #167", "403", "{\"code\":403,\"message\":\"{\\\"code\\\":10605,\\\"message\\\":\\\"inner\\\"}\"}", "503", "false"},
            {"queue wrapper empty code", "403", "{\"message\":\"{\\\"code\\\":10605,\\\"message\\\":\\\"inner\\\"}\"}", "503", "false"},
            {"queue wrapper 401", "401", "{\"code\":401,\"message\":\"{\\\"code\\\":10605,\\\"message\\\":\\\"inner\\\"}\"}", "503", "false"},
            {"queue wrapper authoritative outer", "403", "{\"code\":110,\"message\":\"{\\\"code\\\":10605}\"}", "429", "false"},
            {"PRIORITY quota code + isQueued", "403", "{\"code\":110,\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"PRIORITY request code + isQueued", "403", "{\"code\":416,\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"PRIORITY 105 + isQueued", "403", "{\"code\":105,\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"PRIORITY quota code + serviceAvailable false", "403", "{\"code\":110,\"queue\":{\"serviceAvailable\":false}}", "503", "false"},
            {"PRIORITY request code + serviceAvailable false", "400", "{\"code\":100400,\"queue\":{\"serviceAvailable\":false}}", "503", "false"},
            {"quota 110 @403", "403", "{\"code\":110,\"message\":\"spent\"}", "429", "false"},
            {"quota 122 @200-mapped", "200", "{\"code\":122,\"message\":\"spent\"}", "429", "false"},
            {"request 100400 @403", "403", "{\"code\":100400,\"message\":\"too long\"}", "400", "false"},
            {"request 406 @400", "400", "{\"code\":406,\"message\":\"sensitive\"}", "400", "false"},
            {"range in message @400", "400", "{\"code\":400,\"message\":\"Range of max_tokens should be [1, 32000]\"}", "400", "false"},
            {"range with vendor marker @400", "400", "{\"code\":400,\"message\":\"Error in upstream response: Range of max_tokens should be [1, 32000]\"}", "400", "false"},
            {"range in details", "400", "{\"code\":400,\"message\":\"refused\",\"details\":\"{\\\"error\\\":{\\\"message\\\":\\\"Range of max_tokens should be [1, 64]\\\"}}\"}", "400", "false"},
            {"range @403 no marker", "403", "{\"code\":400,\"message\":\"range of max_tokens should be [1, 64]\"}", "400", "false"},
            {"range @500", "500", "{\"message\":\"Range of max_tokens should be [1, 64]\"}", "400", "false"},
            {"vendor failed @400", "400", "{\"code\":400,\"message\":\"Error in upstream response\"}", "502", "false"},
            {"vendor failed @403", "403", "{\"code\":403,\"message\":\"Error in upstream response\"}", "502", "false"},
            {"vendor failed @401", "401", "{\"message\":\"Error in upstream response\"}", "502", "false"},
            {"vendor failed @502", "502", "{\"message\":\"Error in upstream response\"}", "502", "false"},
            {"vendor failed in details", "400", "{\"code\":400,\"message\":\"x\",\"details\":\"{\\\"error\\\":{\\\"message\\\":\\\"Error in upstream response\\\"}}\"}", "502", "false"},
            {"vendor marker + quota code", "403", "{\"code\":110,\"message\":\"Error in upstream response\"}", "429", "false"},
            {"lapsed 105 @403", "403", "{\"code\":105,\"message\":\"login expired\"}", "401", "true"},
            {"lapsed 105 @401", "401", "{\"code\":105,\"message\":\"login expired\"}", "401", "true"},
            {"lapsed 105 @400", "400", "{\"code\":105,\"message\":\"login expired\"}", "400", "false"},
            {"lapsed 105 @500", "500", "{\"code\":105,\"message\":\"login expired\"}", "500", "false"},
            {"lapsed 105 @502", "502", "{\"code\":105,\"message\":\"Error in upstream response\"}", "502", "false"},
            {"lapsed bare 401", "401", "{\"message\":\"unauthorized\"}", "401", "true"},
            {"lapsed bare 403", "403", "{\"message\":\"forbidden\"}", "401", "true"},
            {"lapsed 403 other code", "403", "{\"code\":777,\"message\":\"nope\"}", "401", "true"},
            {"lapsed 401 empty body", "401", "", "401", "true"},
            {"lapsed 403 not json", "403", "plain words, not json", "401", "true"},
            {"lapsed 403 json array", "403", "[1,2,3]", "401", "true"},
            {"lapsed 403 null-ish", "403", "null", "401", "true"},
            {"500 plain", "500", "boom", "500", "false"},
            {"502 plain", "502", "bad gateway", "502", "false"},
            {"429 quota word", "403", "{\"message\":\"quota exceeded\"}", "401", "true"},
            {"429 status @429", "429", "{\"message\":\"slow down\"}", "429", "false"},
            {"299 out of range", "200", "{\"code\":105}", "502", "false"},
            {"600 out of range", "600", "{\"code\":105}", "502", "false"},
            {"0 status", "0", "{\"code\":110}", "429", "false"},
            {"details invalid json", "400", "{\"code\":400,\"message\":\"m\",\"details\":\"{not json\"}", "400", "false"},
            {"message object not string", "403", "{\"code\":403,\"message\":{\"a\":1}}", "401", "true"},
            {"code boolean", "403", "{\"code\":true,\"message\":\"m\"}", "401", "true"},
            {"code numeric string", "403", "{\"code\":\"10605\",\"message\":\"m\"}", "503", "false"},
            {"empty body", "403", "", "401", "true"},
            {"whitespace body", "403", "   ", "401", "true"},
            {"nested error wrapper", "400", "{\"error\":{\"code\":105,\"message\":\"inner\"}}", "400", "false"},
            {"queue + range in msg", "403", "{\"code\":10605,\"message\":\"Range of max_tokens should be [1, 64]\"}", "503", "false"},
            {"queue + vendor marker", "403", "{\"code\":10605,\"message\":\"Error in upstream response\"}", "503", "false"},
            {"isQueued + range in msg", "403", "{\"queue\":{\"isQueued\":true},\"message\":\"Range of max_tokens should be [1, 64]\"}", "503", "false"},
            {"quota code + range in msg", "403", "{\"code\":110,\"message\":\"Range of max_tokens should be [1, 64]\"}", "429", "false"},
            {"request code + range in msg", "403", "{\"code\":416,\"message\":\"Range of max_tokens should be [1, 64]\"}", "400", "false"},
            {"request code + vendor marker", "403", "{\"code\":416,\"message\":\"Error in upstream response\"}", "400", "false"},
            {"105 + vendor marker @403", "403", "{\"code\":105,\"message\":\"Error in upstream response\"}", "502", "false"},
            {"105 + range @403", "403", "{\"code\":105,\"message\":\"Range of max_tokens should be [1, 64]\"}", "400", "false"},
            {"401 + quota word", "401", "{\"message\":\"quota exceeded\"}", "401", "true"},
            {"403 + quota word", "403", "{\"message\":\"quota exceeded\"}", "401", "true"},
            {"500 + quota word", "500", "{\"message\":\"quota exceeded\"}", "429", "false"},
            {"400 + quota word", "400", "{\"message\":\"quota exceeded\"}", "429", "false"},
            {"429 + queue state", "429", "{\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"500 + queue state", "500", "{\"queue\":{\"serviceAvailable\":false}}", "503", "false"},
            {"code in body wrapper only", "403", "{\"body\":{\"code\":10605}}", "401", "true"},
            {"code in error wrapper only", "403", "{\"error\":{\"code\":110}}", "401", "true"},
            {"code in message wrapper only", "403", "{\"message\":\"{\\\"code\\\":110}\"}", "401", "true"},
            {"queue state under body wrapper", "403", "{\"body\":{\"queue\":{\"isQueued\":true}}}", "401", "true"},
            {"details object vendor", "400", "{\"code\":400,\"message\":\"x\",\"details\":{\"error\":{\"message\":\"Error in upstream response\"}}}", "502", "false"},
            {"details object range", "400", "{\"code\":400,\"message\":\"x\",\"details\":{\"error\":{\"message\":\"Range of max_tokens should be [1, 8]\"}}}", "400", "false"},
            {"details string invalid", "403", "{\"code\":110,\"details\":\"{not json\"}", "429", "false"},
            {"queue empty object", "403", "{\"code\":403,\"queue\":{}}", "401", "true"},
            {"queue as array", "403", "{\"code\":403,\"queue\":[1,2]}", "401", "true"},
            {"10605 in text only", "403", "code 10605 came back", "401", "true"},
            {"vendor marker plain text", "403", "Error in upstream response", "502", "false"},
            {"range plain text", "403", "Range of max_tokens should be [1, 16]", "400", "false"},
            {"upper case vendor", "400", "{\"message\":\"ERROR IN UPSTREAM RESPONSE\"}", "502", "false"},
            {"empty object body", "403", "{}", "401", "true"},
            {"queue code as float", "403", "{\"code\":10605,\"message\":\"m\"}", "503", "false"},
            {"error-nested range @403", "403", "{\"error\":{\"message\":\"Range of max_tokens should be [1, 65536]\"}}", "400", "false"},
            {"error-nested vendor @403", "403", "{\"error\":{\"message\":\"Error in upstream response\"}}", "502", "false"},
            {"error-nested vendor @400", "400", "{\"error\":{\"message\":\"Error in upstream response\"}}", "502", "false"},
            {"error-nested vendor @401", "401", "{\"error\":{\"message\":\"Error in upstream response\"}}", "502", "false"},
            {"body-nested vendor @403", "403", "{\"body\":{\"message\":\"Error in upstream response\"}}", "502", "false"},
            {"body-nested range @403", "403", "{\"body\":{\"message\":\"Range of max_tokens should be [1, 4]\"}}", "400", "false"},
            {"error-nested quota word @403", "403", "{\"error\":{\"message\":\"quota exceeded\"}}", "401", "true"},
            {"error-nested quota word @404", "404", "{\"error\":{\"message\":\"quota exceeded\"}}", "429", "false"},
            {"error-nested vendor + queue", "403", "{\"error\":{\"message\":\"Error in upstream response\"},\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"no message at all @403", "403", "{\"code\":403}", "401", "true"},
            {"no message at all @400", "400", "{\"code\":400}", "400", "false"},
            {"empty message @403", "403", "{\"code\":403,\"message\":\"\"}", "401", "true"},
            {"zero message @403", "403", "{\"code\":403,\"message\":0}", "401", "true"},
            {"false message @403", "403", "{\"code\":403,\"message\":false}", "401", "true"},
            {"object message @403 vendor", "403", "{\"code\":403,\"message\":{\"text\":\"Error in upstream response\"}}", "401", "true"},
            {"array message @403 vendor", "403", "{\"code\":403,\"message\":[\"Error in upstream response\"]}", "502", "false"},
            {"numeric message @403", "403", "{\"code\":403,\"message\":105}", "401", "true"},
            {"array message @403 range", "403", "{\"code\":403,\"message\":[\"Range of max_tokens should be [1, 2]\"]}", "400", "false"},
            {"details abort skips queue", "403", "{\"code\":403,\"details\":\"{bad\",\"queue\":{\"isQueued\":true}}", "401", "true"},
            {"details abort skips embedded queue", "403", "{\"code\":403,\"details\":\"{bad\",\"message\":\"{\\\"isQueued\\\":true}\"}", "401", "true"},
            {"message abort keeps field queue", "403", "{\"code\":403,\"message\":\"{bad\",\"queue\":{\"isQueued\":true}}", "503", "false"},
            {"details array", "403", "{\"code\":403,\"details\":[1,2]}", "401", "true"},
            {"details null", "403", "{\"code\":403,\"details\":null}", "401", "true"},
            {"details number", "403", "{\"code\":403,\"details\":5}", "401", "true"},
            {"details empty string", "403", "{\"code\":403,\"details\":\"\"}", "401", "true"},
            {"details error message empty", "400", "{\"code\":400,\"message\":\"m\",\"details\":{\"error\":{\"message\":\"\"}}}", "400", "false"},
            {"details error not object", "400", "{\"code\":400,\"message\":\"m\",\"details\":{\"error\":\"Error in upstream response\"}}", "400", "false"},
            {"vendor deep under body", "403", "{\"body\":{\"error\":{\"message\":\"Error in upstream response\"}}}", "502", "false"},
            {"range in details of nested error", "403", "{\"error\":{\"message\":\"x\",\"details\":\"{\\\"error\\\":{\\\"message\\\":\\\"Range of max_tokens should be [1, 9]\\\"}}\"}}", "400", "false"},
    };

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
        // 0.2.64's rule is the answered status (index.mjs:1651), so a 403 that names a code the
        // client does not know is the lapsed sign-in, not a status of its own. This account carries
        // no one-use refresh value, so nothing can be spent on it and the refusal stands.
        scenario("403 with an unknown code is the lapsed sign-in",
                http(403, new JSONObject().put("code", 777).put("message", SECRET).toString()), 401, false, "", 1);
        scenario("truncated SSE", sse("data: {\"statusCodeValue\":"), 502, false, "", 1);
        // 只拒「不是正整数」的预算：0 / 负数 / 字符串 / 超 int。
        for (String limit : new String[]{",\"max_tokens\":0", ",\"max_tokens\":-1", ",\"max_tokens\":\"64\"", ",\"max_tokens\":2147483648"})
            scenario("invalid " + limit, http(403, queue), 400, false, limit, 0);
        // 短预算不再有本地下限：本地 0.2.64 的请求体只按模型上限向下收（index.mjs:1502-1506，
        // `asked > cap ? cap : asked`），所以 63 与 1 都原样下发，是否接受由服务端自己答。
        scenario("short budget 63", http(403, queue), 503, true, ",\"max_tokens\":63", 1);
        scenario("short budget 1", http(403, queue), 503, true, ",\"max_tokens\":1", 1);
        scenario("short dual cap 63", http(403, queue), 503, true, ",\"max_tokens\":64,\"max_completion_tokens\":63", 1);
        scenario("dual cap", http(403, queue), 503, true, ",\"max_tokens\":90,\"max_completion_tokens\":64", 1);

        lapsedSignIn();
        refusalSourceAndResend();
        refusalPriorityOverHttp();
        nestedWordsNeverRenew();
        refusalBodyByLayer();
        System.out.println("QoderGatewayHttpTest passed");
    }

    /**
     * What a refusal is, and what it costs: the status Qoder answered decides, so a 400 that carries
     * 105 is a 400 and spends no refresh token; a renewal and a clamped resend are two separate
     * chances, and every resend is the same request_set_id under a new request_id.
     */
    private static void refusalSourceAndResend() throws Exception {
        // 400 + 105 is a 400: the answered status decides, exactly as the plugin reads it
        // (index.mjs:1651 — `status === 401 || status === 403` on the answered status alone).
        check(QoderFailurePolicy.failure(400, lapsed(SECRET)).optInt("status", 0) == 400,
                "a 400 carrying 105 stays a 400");
        check(!QoderFailurePolicy.failure(400, lapsed(SECRET)).optBoolean("lapsed"),
                "a 400 carrying 105 is not a lapsed sign-in");
        check(QoderFailurePolicy.failure(403, lapsed(SECRET)).optInt("status", 0) == 401,
                "a 403 carrying 105 is the lapsed sign-in Qoder answered");
        check(QoderFailurePolicy.failure(401, lapsed(SECRET)).optBoolean("lapsed"),
                "a 401 carrying 105 is a lapsed sign-in");
        check(QoderFailurePolicy.failure(502, lapsed(SECRET)).optInt("status", 0) == 502,
                "a 502 carrying 105 stays a 502");
        // The same four refusals, read back through the transport's (status, code): 400/500/502
        // keep their status, so nothing renews; the 403 became the classified 401, so it does.
        check(!QoderFailurePolicy.lapsedFailure(QoderFailurePolicy.failure(400, lapsed(SECRET)).getInt("status"), 105),
                "400 + 105 does not renew");
        check(!QoderFailurePolicy.lapsedFailure(QoderFailurePolicy.failure(500, lapsed(SECRET)).getInt("status"), 105),
                "500 + 105 does not renew");
        check(!QoderFailurePolicy.lapsedFailure(QoderFailurePolicy.failure(502, lapsed(SECRET)).getInt("status"), 105),
                "502 + 105 does not renew");
        check(QoderFailurePolicy.lapsedFailure(QoderFailurePolicy.failure(403, lapsed(SECRET)).getInt("status"), 105),
                "403 + 105 renews, as the classified 401");

        Object[][] late = {
                {"105 in a 400", http(400, lapsed(SECRET)), 400},
                {"105 in an envelope 400", sse("data: " + new JSONObject().put("statusCodeValue", 400)
                        .put("body", lapsed(SECRET)) + "\n\n"), 400},
                {"105 in a 502", http(502, lapsed(SECRET)), 502},
        };
        for (Object[] refusal : late) {
            Case quiet = new Case((String) refusal[1], answered("never"), renewing(), 0L);
            try {
                Result r = quiet.send();
                check(r.status == (Integer) refusal[2], refusal[0] + " status=" + r.status + " body=" + r.body);
                check(quiet.chat.calls.get() == 1, refusal[0] + " must not be asked again; calls=" + quiet.chat.calls.get());
                check(quiet.renewal.calls.get() == 0, refusal[0] + " must not spend a refresh token; exchanges="
                        + quiet.renewal.calls.get());
                check(quiet.backup.calls.get() == 0, refusal[0] + " must not fail over; backup calls="
                        + quiet.backup.calls.get());
                check(!r.body.contains(SECRET), refusal[0] + " leaked upstream body");
            } finally { quiet.close(); }
        }

        // A renewal and a clamped resend are two chances: the plugin tracks them apart
        // (index.mjs:3655 `relogged`, :3677 `!reclamped`).
        // A ceiling a refusal named is remembered for the model (the plugin's LEARNED_CAPS), so each
        // clamp case starts from what its own request asked.
        String range = new JSONObject().put("code", 400)
                .put("message", "Error in upstream response: Range of max_tokens should be [1, 32]").toString();
        QoderFailurePolicy.clearLearnedCaps();
        Case chain = new Case(http(401, lapsed(SECRET)), null, renewing(), 0L);
        chain.chat.script.add(http(400, range));
        chain.chat.script.add(answered("clamped"));
        try {
            Result r = post(chain.gateway, ",\"max_tokens\":64");
            check(r.status == 200, "renew then clamp status=" + r.status + " body=" + r.body);
            check(chain.chat.calls.get() == 3, "renew then clamp Qoder calls=" + chain.chat.calls.get());
            check(chain.renewal.calls.get() == 1, "renew then clamp exchanges=" + chain.renewal.calls.get());
            JSONObject first = sent(chain.chat, 0), second = sent(chain.chat, 1), third = sent(chain.chat, 2);
            check(first.getJSONObject("parameters").getInt("max_tokens") == 64
                    && second.getJSONObject("parameters").getInt("max_tokens") == 64,
                    "the renewal's resend still asks what the caller asked");
            check(third.getJSONObject("parameters").getInt("max_tokens") == 32,
                    "the clamped resend asks the ceiling the refusal named: "
                            + third.getJSONObject("parameters").opt("max_tokens"));
            String set = first.getString("request_set_id");
            check(set.equals(second.getString("request_set_id")) && set.equals(third.getString("request_set_id")),
                    "every resend keeps the request_set_id");
            java.util.HashSet<String> ids = new java.util.HashSet<String>();
            ids.add(first.getString("request_id"));
            ids.add(second.getString("request_id"));
            ids.add(third.getString("request_id"));
            check(ids.size() == 3, "every resend carries its own request_id, got " + ids.size());
            check(third.getString("request_id").equals(third.getString("chat_record_id")),
                    "request_id and chat_record_id move together");
        } finally { chain.close(); }

        // A clamped resend on its own is a new request too, as Qoder's client sends it
        // (index.mjs:3632-3636, before the body is signed).
        QoderFailurePolicy.clearLearnedCaps();
        Case clamp = new Case(http(400, range), null, null, 0L);
        clamp.chat.script.add(answered("clamped"));
        try {
            Result r = post(clamp.gateway, ",\"max_tokens\":64");
            check(r.status == 200, "clamp status=" + r.status + " body=" + r.body);
            check(clamp.chat.calls.get() == 2, "clamp Qoder calls=" + clamp.chat.calls.get());
            JSONObject first = sent(clamp.chat, 0), second = sent(clamp.chat, 1);
            check(first.getString("request_set_id").equals(second.getString("request_set_id")),
                    "a clamped resend keeps the request_set_id");
            check(!first.getString("request_id").equals(second.getString("request_id")),
                    "a clamped resend carries a new request_id");
            check(second.getString("request_id").equals(second.getString("chat_record_id")),
                    "a clamped resend moves chat_record_id with request_id");
            check(second.getJSONObject("parameters").getInt("max_tokens") == 32,
                    "a clamped resend asks the named ceiling");
        } finally { clamp.close(); }
    }

    /**
     * What a refusal costs over the loopback, with the answer's own priority order
     * (index.mjs:1628-1653) and a refresh upstream standing by: the exact number of calls Qoder
     * receives, and the exact number of refresh tokens spent.
     */
    private static void refusalPriorityOverHttp() throws Exception {
        String queueState = new JSONObject().put("isQueued", true).put("retryAfterSeconds", 5).toString();
        // A queue state is read before every code, so each of these is the queue: one call, no
        // refresh, and the client is told when to come back rather than being rested or re-signed-in.
        Object[][] queueBeats = {
                {"quota code + queue state", q(403, new JSONObject().put("code", 110)
                        .put("queue", new JSONObject().put("isQueued", true).put("retryAfterSeconds", 5)))},
                {"request code + queue state", q(403, new JSONObject().put("code", 416)
                        .put("queue", new JSONObject().put("isQueued", true)))},
                {"105 + queue state", q(403, new JSONObject().put("code", 105)
                        .put("queue", new JSONObject().put("isQueued", true)))},
                {"not serviceAvailable", q(403, new JSONObject().put("code", 110)
                        .put("queue", new JSONObject().put("serviceAvailable", false)))},
        };
        for (Object[] refusal : queueBeats) {
            Case queued = new Case((String) refusal[1], null, renewing(), 0L);
            try {
                Result r = queued.send();
                check(r.status == 503, refusal[0] + " is the queue, status=" + r.status + " body=" + r.body);
                check(r.retry != null, refusal[0] + " must say when to come back");
                check(queued.chat.calls.get() == 1, refusal[0] + " calls=" + queued.chat.calls.get());
                check(queued.renewal.calls.get() == 0, refusal[0] + " must not spend a refresh token, exchanges="
                        + queued.renewal.calls.get());
                check(queued.backup.calls.get() == 0, refusal[0] + " must not fail over");
            } finally { queued.close(); }
        }

        // The vendor marker and a named max_tokens range are read from the answer's own words before
        // the lapsed rule, whatever status carried them: 502 and 400, and no refresh either way.
        Case vendor = new Case(q(403, new JSONObject().put("code", 403).put("message", "Error in upstream response")),
                null, renewing(), 0L);
        try {
            Result r = vendor.send();
            check(r.status == 502, "403 + vendor marker status=" + r.status + " body=" + r.body);
            check(vendor.chat.calls.get() == 1, "403 + vendor marker calls=" + vendor.chat.calls.get());
            check(vendor.renewal.calls.get() == 0, "403 + vendor marker must not renew; exchanges="
                    + vendor.renewal.calls.get());
            check(!r.body.contains(SECRET), "403 + vendor marker leaked upstream body");
        } finally { vendor.close(); }

        QoderFailurePolicy.clearLearnedCaps();
        Case ranged = new Case(q(403, new JSONObject().put("code", 403)
                .put("message", "Error in upstream response: Range of max_tokens should be [1, 48]")),
                null, renewing(), 0L);
        ranged.chat.script.add(answered("clamped"));
        try {
            Result r = post(ranged.gateway, ",\"max_tokens\":64");
            check(r.status == 200, "403 + range status=" + r.status + " body=" + r.body);
            check(ranged.chat.calls.get() == 2, "a range refused under a 403 is asked again once, calls="
                    + ranged.chat.calls.get());
            check(ranged.renewal.calls.get() == 0, "403 + range must not renew; exchanges="
                    + ranged.renewal.calls.get());
            check(sent(ranged.chat, 1).getJSONObject("parameters").getInt("max_tokens") == 48,
                    "the resend asks the ceiling Qoder named");
            check(sent(ranged.chat, 0).getString("request_set_id")
                    .equals(sent(ranged.chat, 1).getString("request_set_id")), "the clamped resend keeps the set");
        } finally { ranged.close(); }

        // A limit that names no quota code but says "quota" is a 429 (index.mjs:1659) — but only when
        // the answered status is not 401/403, which is answered before it.
        Case limit = new Case(q(400, new JSONObject().put("message", "quota exceeded")), null, renewing(), 0L);
        try {
            Result r = limit.send();
            check(r.status == 429, "400 + quota words status=" + r.status + " body=" + r.body);
            check(limit.chat.calls.get() == 1, "400 + quota words calls=" + limit.chat.calls.get());
            check(limit.renewal.calls.get() == 0, "400 + quota words must not renew");
        } finally { limit.close(); }

        Case lapsedSaysQuota = new Case(q(403, new JSONObject().put("message", "quota exceeded")),
                answered("renewed"), renewing(), 0L);
        try {
            Result r = lapsedSaysQuota.send();
            check(r.status == 200, "403 + quota words status=" + r.status + " body=" + r.body);
            check(lapsedSaysQuota.chat.calls.get() == 2, "403 + quota words is the lapsed sign-in: calls="
                    + lapsedSaysQuota.chat.calls.get());
            check(lapsedSaysQuota.renewal.calls.get() == 1, "403 + quota words renews once: exchanges="
                    + lapsedSaysQuota.renewal.calls.get());
        } finally { lapsedSaysQuota.close(); }

        // A 403 whose body names a code the client does not know is the lapsed sign-in too.
        Case unknown = new Case(q(403, new JSONObject().put("code", 777).put("message", SECRET)),
                answered("renewed"), renewing(), 0L);
        try {
            Result r = unknown.send();
            check(r.status == 200, "403 + unknown code status=" + r.status + " body=" + r.body);
            check(unknown.chat.calls.get() == 2, "403 + unknown code calls=" + unknown.chat.calls.get());
            check(unknown.renewal.calls.get() == 1, "403 + unknown code renews once");
        } finally { unknown.close(); }
    }

    /** A Qoder refusal in HTTP shape: the answered status and the body Qoder sent. */
    private static String q(int status, JSONObject body) { return http(status, body.toString()); }

    /** A refusal whose body stops short of the length it promised: the connection cut mid-body. */
    private static String httpCut(int code, String body, int keep) {
        return "HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes(body).length + "\r\nConnection: close\r\n\r\n"
                + body.substring(0, Math.max(0, Math.min(keep, body.length())));
    }

    /**
     * A platform that hands an answered refusal's body back, as this device's framework does. Only
     * the platform half is replaced — the request is streamed the same way — so the shipped
     * transport's own reading and classification of that body is what runs.
     */
    private static final class AnsweredPlatform extends HttpURLConnection {
        final int code;
        final byte[] body;
        /** What the answer promised; a platform that hands less than this has not finished it. */
        final long declared;
        final AtomicInteger opened = new AtomicInteger(), written = new AtomicInteger(),
                reads = new AtomicInteger(), closed = new AtomicInteger();
        AnsweredPlatform(URL url, int code, String body) { this(url, code, body, -1); }
        AnsweredPlatform(URL url, int code, String body, long declared) {
            super(url); this.code = code; this.body = bytes(body);
            this.declared = declared < 0 ? this.body.length : declared;
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { closed.incrementAndGet(); }
        @Override public void setFixedLengthStreamingMode(int length) { }
        @Override public void setFixedLengthStreamingMode(long length) { }
        @Override public int getContentLength() { return (int) declared; }
        @Override public OutputStream getOutputStream() {
            written.incrementAndGet();
            return new ByteArrayOutputStream();
        }
        @Override public int getResponseCode() { return code; }
        @Override public String getContentType() { return "application/json"; }
        @Override public String getContentEncoding() { return null; }
        @Override public InputStream getInputStream() throws IOException { throw new IOException("no success stream"); }
        @Override public InputStream getErrorStream() {
            return new FilterInputStream(new ByteArrayInputStream(body)) {
                @Override public int read() throws IOException { int n = super.read(); reads.incrementAndGet(); return n; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len); reads.incrementAndGet(); return n;
                }
            };
        }
    }

    /** One refusal through the shipped transport, over a platform that hands its body over. */
    private static QoderStream.Failure refused(AnsweredPlatform platform) throws Exception {
        QoderAuth.SignIn si = new QoderAuth.SignIn();
        si.access = "fixture-token"; si.uid = "fixture-uid"; si.machineId = "fixture-machine";
        JSONObject body = QoderAuth.buildChatBody(si, new JSONArray(), null, 64, "fixture-model",
                new JSONObject().put("key", "fixture-model").put("source", "fixture"));
        try {
            QoderStream.open(si, body, new JSONObject(), "fixture-model", url -> {
                platform.opened.incrementAndGet();
                return platform;
            }, null);
        } catch (QoderStream.Failure refusal) {
            return refusal;
        }
        throw new AssertionError("an answered refusal was opened as a stream");
    }

    /**
     * sub_158: Qoder's words can sit one level down. 0.2.64 starts its message from the whole body
     * and only replaces it with a truthy top-level message (index.mjs:1596, :1612), so
     * {@code {"error":{"message":"Error in upstream response"}}} is still a vendor failure and
     * {@code {"error":{"message":"Range of max_tokens …"}}} is still the request's own ceiling —
     * neither is a lapsed sign-in, and neither may spend a refresh token.
     */
    private static void nestedWordsNeverRenew() throws Exception {
        // A vendor failure behind an error object, at the statuses that would otherwise renew.
        /* Statuses whose body this JVM lets the classification read. A 401 with a streamed request
         * body is not among them: the JDK's HttpURLConnection refuses to retry a streamed body for
         * server authentication, disconnects and drops the answered body, so its error stream is
         * null and the words never reach the classification here (measured; refusalBodyByLayer
         * carries the same refusals over a platform that hands the body back, as this device's
         * framework does). The plugin's own verdict for a 401 carrying those words is the corpus row
         * "vendor failed @401". */
        Object[][] nested = {
                {"403 error-nested vendor", 403, 502},
                {"400 error-nested vendor", 400, 502},
                {"403 body-nested vendor", 403, 502},
                {"500 error-nested vendor", 500, 502},
        };
        for (Object[] row : nested) {
            String body = new JSONObject().put("code", (Integer) row[1] == 400 ? 400 : row[1])
                    .put("error", new JSONObject().put("message", "Error in upstream response")).toString();
            if (((String) row[0]).startsWith("403 body")) body = new JSONObject()
                    .put("body", new JSONObject().put("message", "Error in upstream response")).toString();
            Case vendor = new Case(http((Integer) row[1], body), null, renewing(), 0L);
            try {
                Result r = vendor.send();
                    check(r.status == (Integer) row[2], row[0] + " status=" + r.status + " body=" + r.body);
                check(vendor.chat.calls.get() == 1, row[0] + " calls=" + vendor.chat.calls.get());
                check(vendor.renewal.calls.get() == 0, row[0] + " must not spend a refresh token; exchanges="
                        + vendor.renewal.calls.get());
                check(vendor.backup.calls.get() == 0, row[0] + " must not fail over");
                check(!r.body.contains("upstream response"), row[0] + " echoed the upstream body: " + r.body);
            } finally { vendor.close(); }
        }

        // A ceiling named one level down: the request's own fault, asked again once at it, no renewal.
        QoderFailurePolicy.clearLearnedCaps();
        Case ranged = new Case(http(403, new JSONObject()
                .put("error", new JSONObject().put("message", "Range of max_tokens should be [1, 40]")).toString()),
                null, renewing(), 0L);
        ranged.chat.script.add(answered("clamped"));
        try {
            Result r = post(ranged.gateway, ",\"max_tokens\":64");
            check(r.status == 200, "403 error-nested range status=" + r.status + " body=" + r.body);
            check(ranged.chat.calls.get() == 2, "403 error-nested range calls=" + ranged.chat.calls.get());
            check(ranged.renewal.calls.get() == 0, "403 error-nested range must not renew; exchanges="
                    + ranged.renewal.calls.get());
            check(sent(ranged.chat, 1).getJSONObject("parameters").getInt("max_tokens") == 40,
                    "the resend asks the ceiling Qoder named one level down");
        } finally { ranged.close(); }

        // The same words with no message anywhere: still not a lapsed sign-in, and no body echoed.
        Case silent = new Case(http(403, new JSONObject()
                .put("body", new JSONObject().put("error", new JSONObject()
                        .put("message", "Error in upstream response"))).toString()), null, renewing(), 0L);
        try {
            Result r = silent.send();
            check(r.status == 502, "vendor under body.error status=" + r.status + " body=" + r.body);
            check(silent.renewal.calls.get() == 0, "vendor under body.error must not renew");
            check(!r.body.contains("upstream response"), "vendor under body.error echoed the body: " + r.body);
        } finally { silent.close(); }

        // A queue that is still the queue: the words beside it do not make it a refusal of the request.
        Case queued = new Case(http(403, new JSONObject().put("queue", new JSONObject()
                .put("isQueued", true).put("retryAfterSeconds", 4))
                .put("error", new JSONObject().put("message", "Error in upstream response")).toString()),
                null, renewing(), 0L);
        try {
            Result r = queued.send();
            check(r.status == 503, "queue beside vendor words status=" + r.status + " body=" + r.body);
            check(queued.renewal.calls.get() == 0, "queue beside vendor words must not renew");
            check("4".equals(r.retry), "the queue's own wait is kept, got " + r.retry);
        } finally { queued.close(); }

        // And an answer with no message at all stays the lapsed sign-in it is: one renewal, one resend.
        Case bare = new Case(http(403, new JSONObject().put("code", 403).toString()), answered("renewed"), renewing(), 0L);
        try {
            Result r = bare.send();
            check(r.status == 200, "bare 403 through a renewal status=" + r.status + " body=" + r.body);
            check(bare.chat.calls.get() == 2, "bare 403 calls=" + bare.chat.calls.get());
            check(bare.renewal.calls.get() == 1, "bare 403 renews once; exchanges=" + bare.renewal.calls.get());
        } finally { bare.close(); }
    }

    /**
     * A refusal's body, layer by layer. This device's framework hands a 401's body back even though
     * the request body was streamed (measured on Android 16 through this same transport and through
     * a bare probe of the same call sequence: 401+406 to 400, 401+vendor to 502, 401+range to 400 at
     * the named ceiling, 401+empty to 401, one request, no echo). The JVM this suite runs on does
     * not: with a streamed request body its HttpURLConnection answers a 401 with
     * {@code HttpRetryException("cannot retry due to server authentication, in streaming mode")}
     * after disconnecting (the JDK's getInputStream0, whose {@code streaming()} is true exactly when
     * the body is streamed), so the body is gone before any read could see it and only a second
     * request could get it back — the one thing this transport never does, and the reason no read
     * can be added for it. That is the platform's difference, not the transport's: the transport's
     * own reading and classification of a 401 body is pinned over a platform that answers as the
     * device does, and the platform-independent refusals are pinned over the socket.
     */
    private static void refusalBodyByLayer() throws Exception {
        Object[][] rows = {
                {"401 + request code 406", 401, "{\"code\":406,\"message\":\"sensitive " + SECRET + "\"}", 400, 406, 0, 0},
                {"401 + vendor marker", 401, "{\"message\":\"Error in upstream response\"}", 502, 0, 0, 0},
                {"401 + range", 401, "{\"message\":\"Range of max_tokens should be [1, 32]\"}", 400, 0, 0, 32},
                {"401 + lapsed 105", 401, lapsed(SECRET), 401, 105, 0, 0},
                {"401 + empty body", 401, "", 401, 0, 0, 0},
                {"401 + whitespace body", 401, "   ", 401, 0, 0, 0},
                {"401 + queue code", 401, "{\"code\":10605,\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":7}}", 503, 10605, 7, 0},
                {"401 + queue wrapper", 401, "{\"code\":401,\"message\":\"{\\\"code\\\":10605,\\\"queue\\\":{\\\"isQueued\\\":true,\\\"retryAfterSeconds\\\":7}}\"}", 503, 10605, 7, 0},
                {"403 + vendor marker", 403, "{\"message\":\"Error in upstream response\",\"trace\":\"" + SECRET + "\"}", 502, 0, 0, 0},
                {"403 + request code 100400", 403, "{\"code\":100400,\"message\":\"too long\"}", 400, 100400, 0, 0},
                {"403 + empty body", 403, "", 401, 0, 0, 0},
        };
        for (Object[] row : rows) {
            AnsweredPlatform platform = new AnsweredPlatform(
                    new URL("http://127.0.0.1/answered"), (Integer) row[1], (String) row[2]);
            QoderStream.Failure f = refused(platform);
            check(f.status == (Integer) row[3], row[0] + ": status " + f.status + ", expected " + row[3]);
            check(f.code == (Integer) row[4], row[0] + ": code " + f.code + ", expected " + row[4]);
            check(f.maxTokensCap == (Integer) row[6], row[0] + ": ceiling " + f.maxTokensCap + ", expected " + row[6]);
            if ((Integer) row[5] > 0)
                check(f.retryAfter == (Integer) row[5], row[0] + ": retry-after " + f.retryAfter + ", expected " + row[5]);
            check(f.getMessage() != null && !f.getMessage().contains(SECRET),
                    row[0] + " echoed the upstream body: " + f.getMessage());
            check(f.sentOrUnknown, row[0] + " must be known to have been sent");
            check(QoderFailurePolicy.lapsedFailure(f.status, f.code) == (f.status == 401),
                    row[0] + ": the renewal verdict must follow the classified status");
            // The answered body was read from the platform's stream, the request body was written
            // once and the connection was torn down once: nothing here asks again.
            check(platform.opened.get() == 1, row[0] + ": opened " + platform.opened.get() + " times");
            check(platform.written.get() == 1, row[0] + ": wrote the request body " + platform.written.get() + " times");
            check(platform.reads.get() > 0, row[0] + ": the answered body was never read");
            check(platform.closed.get() == 1, row[0] + ": disconnected " + platform.closed.get() + " times");
        }

        /*
         * A platform that hands a refusal short of what it promised, without saying so: measured on
         * JDK 17, whose HttpURLConnection reads a short fixed-length error body to EOF with no error
         * (24 of 51 bytes) — Android's throws instead (16, measured) — and this same fixture is what
         * the r6 log hit. Half a body is not Qoder's verdict: the plugin's (await res.text()) rejects
         * there and its 502 follows (index.mjs:3651, :3701), and the worst reading of a cut 403 is the
         * lapsed sign-in, which would spend a one-use refresh value and replay the request. So each
         * one is a transport failure: one open, one write, no renewal, no echo — and the same body
         * declared at its real length is still classified, so the 502 is the short read and nothing else.
         */
        Object[][] shortBodies = {
                {"short 400", 400}, {"short 401", 401}, {"short 403", 403},
        };
        for (Object[] row : shortBodies) {
            String half = "{\"code\":403,\"message\":\"" + SECRET;
            String name = (String) row[0];
            AnsweredPlatform cut = new AnsweredPlatform(new URL("http://127.0.0.1/cut"),
                    (Integer) row[1], half, bytes(half).length + 20L);
            QoderStream.Failure f = refused(cut);
            check(f.status == 502, name + ": status " + f.status + ", expected 502 (a body short of its length)");
            check(!QoderFailurePolicy.lapsedFailure(f.status, f.code), name + " must never read as a renewal");
            check(f.getMessage() != null && !f.getMessage().contains(SECRET), name + " echoed the partial body");
            check(cut.opened.get() == 1 && cut.written.get() == 1,
                    name + ": opened " + cut.opened.get() + ", wrote " + cut.written.get());
            check(cut.reads.get() > 0, name + ": the partial body was never read");
            check(cut.closed.get() == 1, name + ": disconnected " + cut.closed.get() + " times");
            // The control: the same bytes, promised exactly, are the answer they are (not a 502).
            AnsweredPlatform whole = new AnsweredPlatform(new URL("http://127.0.0.1/whole"),
                    (Integer) row[1], half);
            QoderStream.Failure w = refused(whole);
            check(w.status != 502, name + " control: the same body at its own length must be classified, got "
                    + w.status);
        }

        // An empty 401 is the lapsed sign-in on either platform: one refusal, one renewal, one resend.
        Case emptyBody = new Case(http(401, ""), answered("renewed"), renewing(), 0L);
        try {
            Result r = emptyBody.send();
            check(r.status == 200, "401 with an empty body status=" + r.status + " body=" + r.body);
            check(emptyBody.chat.calls.get() == 2, "one refusal and one resend, calls=" + emptyBody.chat.calls.get());
            check(emptyBody.renewal.calls.get() == 1, "exchanges=" + emptyBody.renewal.calls.get());
            check(emptyBody.backup.calls.get() == 0, "an empty 401 must not fail over");
            check(!r.body.contains(SECRET), "401 with an empty body echoed the upstream body");
        } finally { emptyBody.close(); }

        // A refusal cut off mid-body: the platform reports the end of the stream, which is not a
        // verdict Qoder gave, so it is a transport failure — never a renewal, never a second ask.
        // A 401 is not put through this: the platform's own report of a cut behind a 401 differs
        // between the two (the JVM drops the body before any read, the device reads and fails), so
        // what a cut costs is pinned where both agree, on a status whose body is delivered.
        for (int status : new int[]{400, 403}) {
            String name = status + " + body cut short";
            Case cut = new Case(httpCut(status, "{\"code\":403,\"message\":\"" + SECRET, 24), null, renewing(), 0L);
            try {
                Result r = cut.send();
                check(r.status == 502, name + " status=" + r.status + " body=" + r.body);
                check(cut.chat.calls.get() == 1, name + " must not be asked again; calls=" + cut.chat.calls.get());
                check(cut.renewal.calls.get() == 0, name + " must not spend a refresh token");
                check(cut.backup.calls.get() == 0, name + " must not fail over");
                check(!r.body.contains(SECRET), name + " echoed the cut body: " + r.body);
            } finally { cut.close(); }
        }

        // A connection that ends before any answer: no status to classify, one request, no replay.
        Case silent = new Case("", null, renewing(), 0L);
        try {
            Result r = silent.send();
            check(r.status == 502, "a connection with no answer status=" + r.status + " body=" + r.body);
            check(silent.chat.calls.get() == 1, "a connection with no answer calls=" + silent.chat.calls.get());
            check(silent.renewal.calls.get() == 0, "a connection with no answer must not renew");
            check(silent.backup.calls.get() == 0, "a connection with no answer must not fail over");
        } finally { silent.close(); }
    }

    /**
     * Only a 401/403 the plugin reads as a lapsed sign-in is renewed, and only once for one request:
     * the same request_set_id under a new request_id, asked again only after the rotation is stored.
     * Everything else — the queue, a quota, a request's own refusal, a transport failure, a stream
     * that ends without a verdict, a renewal that did not finish safely, a cancelled request — keeps
     * its own answer and is never replayed.
     */
    private static void lapsedSignIn() throws Exception {
        /* The verdict is the refusal's own lapsed field, decided by failure() from the answered
         * status after the queue, a quota, a request's refusal, a max_tokens range and the vendor
         * marker (index.mjs:1628-1653). lapsedFailure is that same verdict as the transport reads it
         * back, and is sound only because 401 is emitted from that branch alone — which is what the
         * corpus below checks row by row. */
        check(QoderFailurePolicy.failure(401, "{}").optBoolean("lapsed"), "a 401 is the lapsed sign-in");
        check(QoderFailurePolicy.failure(403, "{}").optBoolean("lapsed"), "a 403 is the lapsed sign-in");
        check(!QoderFailurePolicy.failure(400, lapsed(SECRET)).optBoolean("lapsed"), "a 400 naming 105 is not");
        check(!QoderFailurePolicy.failure(502, lapsed(SECRET)).optBoolean("lapsed"), "a 502 naming 105 is not");
        check(!QoderFailurePolicy.failure(403, new JSONObject().put("code", 10605).put("message", SECRET)
                .put("queue", new JSONObject().put("isQueued", true).put("retryAfterSeconds", 5)).toString()).optBoolean("lapsed"),
                "the queue never reads as a lapsed sign-in");
        // The bridge: only the status the classification emits for a lapsed sign-in renews, and a
        // queue, a quota or a request code never passes for one even if a 401 ever carried it.
        check(QoderFailurePolicy.lapsedFailure(401, 0), "a classified 401 renews");
        check(QoderFailurePolicy.lapsedFailure(401, 105), "105 is the code Qoder names for it");
        check(!QoderFailurePolicy.lapsedFailure(403, 0), "a classified 403 is never emitted, so never renews");
        check(!QoderFailurePolicy.lapsedFailure(401, 10605), "the queue code never renews");
        check(!QoderFailurePolicy.lapsedFailure(401, 110), "a quota code never renews");
        check(!QoderFailurePolicy.lapsedFailure(401, 100400), "a request's refusal never renews");
        check(!QoderFailurePolicy.lapsedFailure(502, 0), "a server fault never renews");
        check(!QoderFailurePolicy.lapsedFailure(503, 10605), "the queue answer is never renewing");
        // Every refusal the shipped failure() was run on, answered the same way here.
        for (String[] row : PLUGIN_VERDICTS) {
            int answered = Integer.parseInt(row[1]);
            JSONObject f = QoderFailurePolicy.failure(answered, row[2]);
            check(f.getInt("status") == Integer.parseInt(row[3]),
                    row[0] + ": status " + f.getInt("status") + ", the plugin answered " + row[3]);
            boolean lapsed = "true".equals(row[4]);
            check(f.optBoolean("lapsed") == lapsed,
                    row[0] + ": lapsed " + f.optBoolean("lapsed") + ", the plugin answered " + lapsed);
            check(f.getInt("upstream_status") == answered, row[0] + ": the answered status is kept");
            check(QoderFailurePolicy.lapsedFailure(f.getInt("status"), f.optInt("qoder_code", 0)) == lapsed,
                    row[0] + ": the transport-side verdict must equal the refusal's own");
        }

        // 401 → the renewal is stored → the same request asked again once → answered.
        Case one = new Case(http(401, lapsed(SECRET)), answered("renewed"), renewing(), 0L);
        try {
            Result r = one.send();
            check(r.status == 200, "renewed 401 status=" + r.status + " body=" + r.body);
            check(!r.body.contains(SECRET), "renewed 401 leaked upstream body");
            check(one.chat.calls.get() == 2, "renewed 401 Qoder calls=" + one.chat.calls.get());
            check(one.renewal.calls.get() == 1, "renewed 401 exchange calls=" + one.renewal.calls.get());
            check(one.backup.calls.get() == 0, "renewed 401 must not fail over");
            JSONObject first = sent(one.chat, 0), second = sent(one.chat, 1);
            check(first.getString("request_set_id").equals(second.getString("request_set_id")),
                    "the resend must keep the request_set_id the refusal was under");
            check(!first.getString("request_id").equals(second.getString("request_id")),
                    "the resend must carry a new request_id");
            check(second.getString("request_id").equals(second.getString("chat_record_id")),
                    "request_id and chat_record_id move together");
            check(!one.chat.authorizations.get(0).equals(one.chat.authorizations.get(1)),
                    "the resend must be signed with the renewed sign-in");
            check(ROTATED.equals(one.chat.configAtCall.get(1)),
                    "the rotation must be stored before the request is asked again (store said \""
                            + one.chat.configAtCall.get(1) + "\" when the resend arrived)");
            check(ROTATED.equals(storedToken(one.gateway.configFile)), "the renewal must be stored");
            check(ROTATED_REFRESH.equals(storedRefresh(one.gateway.configFile)), "the rotated refresh value must be stored");
            check(!rests().containsKey("qoder_cn/fixture-model"), "a renewal must not rest the account");
            // A refusal of the token this process just renewed is not renewed again (RELOGIN_GAP).
            Result again = one.send();
            check(again.status == 401, "refused again status=" + again.status + " body=" + again.body);
            check(one.chat.calls.get() == 3, "refused again Qoder calls=" + one.chat.calls.get());
            check(one.renewal.calls.get() == 1, "a token renewed minutes ago must not be exchanged again; exchanges="
                    + one.renewal.calls.get());
            check(ROTATED.equals(storedToken(one.gateway.configFile)), "a refused renewal must not disturb the store");
        } finally { one.close(); }

        // Qoder also refuses inside a 200 stream, as its first event: the same renewal, once.
        String streamRefusal = sse("data: " + new JSONObject().put("statusCodeValue", 401)
                .put("body", lapsed(SECRET)) + "\n\n");
        Case inside = new Case(streamRefusal, answered("after the event"), renewing(), 0L);
        try {
            Result r = inside.send();
            check(r.status == 200, "lapsed event status=" + r.status + " body=" + r.body);
            check(inside.chat.calls.get() == 2, "lapsed event Qoder calls=" + inside.chat.calls.get());
            check(inside.renewal.calls.get() == 1, "lapsed event exchange calls=" + inside.renewal.calls.get());
            JSONObject first = sent(inside.chat, 0), second = sent(inside.chat, 1);
            check(first.getString("request_set_id").equals(second.getString("request_set_id")),
                    "a refusal inside the stream is answered under the same request_set_id");
            check(!first.getString("request_id").equals(second.getString("request_id")),
                    "a refusal inside the stream is answered under a new request_id");
        } finally { inside.close(); }

        // A refusal that names no code at all is the built-in's 401, and is renewed the same way.
        Case bare = new Case(http(401, new JSONObject().put("message", SECRET).toString()), answered("bare"), renewing(), 0L);
        try {
            Result r = bare.send();
            check(r.status == 200, "bare 401 status=" + r.status + " body=" + r.body);
            check(bare.chat.calls.get() == 2, "bare 401 Qoder calls=" + bare.chat.calls.get());
            check(bare.renewal.calls.get() == 1, "bare 401 exchange calls=" + bare.renewal.calls.get());
        } finally { bare.close(); }

        // A second refusal inside the same request is answered as it is: one request, one renewal.
        Case twice = new Case(http(401, lapsed(SECRET)), http(401, lapsed(SECRET)), renewing(), 0L);
        try {
            Result r = twice.send();
            check(r.status == 401, "twice refused status=" + r.status + " body=" + r.body);
            check(twice.chat.calls.get() == 2, "twice refused Qoder calls=" + twice.chat.calls.get());
            check(twice.renewal.calls.get() == 1, "twice refused exchange calls=" + twice.renewal.calls.get());
        } finally { twice.close(); }

        // Two requests refused at once: one exchange, and both are asked again with the stored sign-in.
        Upstream chat = new Upstream(http(401, lapsed(SECRET)));
        // Both refused requests are refused before either renewal starts (the gate), then both answers.
        chat.script.add(http(401, lapsed(SECRET)));
        chat.script.add(http(401, lapsed(SECRET)));
        chat.script.add(answered("one"));
        chat.script.add(answered("two"));
        Upstream renewal = new Upstream(renewing());
        renewal.delayMillis = 600;
        chat.gate = new CyclicBarrier(2);
        chat.gateParties = 2;
        Upstream backup = new Upstream(http(500, "backup-called"));
        Gateway gateway = new Gateway(config(backup, renewableAccount(), 0L), chat, renewal);
        try {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Result> first = workers.submit(() -> post(gateway, "", "concurrent-a"));
                Future<Result> second = workers.submit(() -> post(gateway, "", "concurrent-b"));
                Result a = first.get(20, TimeUnit.SECONDS), b = second.get(20, TimeUnit.SECONDS);
                check(a.status == 200, "concurrent first status=" + a.status + " body=" + a.body);
                check(b.status == 200, "concurrent second status=" + b.status + " body=" + b.body);
                check(chat.calls.get() == 4, "concurrent Qoder calls=" + chat.calls.get());
                check(renewal.calls.get() == 1, "one exchange for two refused requests, not " + renewal.calls.get());
                check(backup.calls.get() == 0, "concurrent requests must not fail over");
                // Each request keeps its own id: the resends do not borrow each other's request ids.
                LinkedHashMap<String, List<JSONObject>> grouped = byRequestSet(chat);
                check(grouped.size() == 2, "two requests keep their own request_set_id: " + grouped.size());
                for (List<JSONObject> calls : grouped.values()) {
                    check(calls.size() == 2, "each refused request was asked exactly twice: " + calls.size());
                    check(!calls.get(0).getString("request_id").equals(calls.get(1).getString("request_id")),
                            "each request's resend carries its own request_id");
                    check(calls.get(1).getString("request_id").equals(calls.get(1).getString("chat_record_id")),
                            "each request's resend moves chat_record_id with request_id");
                }
            } finally { workers.shutdownNow(); }
        } finally { gateway.close(); chat.close(); renewal.close(); backup.close(); }

        // Authoritative codes keep their own answers and spend no refresh token.
        String quota = new JSONObject().put("code", 110).put("message", SECRET).toString();
        String request = new JSONObject().put("code", 100400).put("message", SECRET).toString();
        Object[][] codes = {
                {"queue 403", http(403, new JSONObject().put("code", 10605).put("message", SECRET)
                        .put("queue", new JSONObject().put("isQueued", true).put("retryAfterSeconds", 5)).toString()), 503},
                {"quota 403", http(403, quota), 429},
                {"request 403", http(403, request), 400},
                {"server 500", http(500, SECRET), 500},
        };
        for (Object[] code : codes) {
            Case refused = new Case((String) code[1], null, renewing(), 0L);
            try {
                Result r = refused.send();
                check(r.status == (Integer) code[2], code[0] + " status=" + r.status + " body=" + r.body);
                check(refused.chat.calls.get() == 1, code[0] + " must not be asked again; calls=" + refused.chat.calls.get());
                check(refused.renewal.calls.get() == 0, code[0] + " must not spend a refresh token; exchanges="
                        + refused.renewal.calls.get());
                check(!r.body.contains(SECRET), code[0] + " leaked upstream body");
            } finally { refused.close(); }
        }

        // A renewal whose answer is a fault, or is not a token pair, is never replayed either.
        Object[][] broken = {
                {"exchange 500", http(500, SECRET), 502, "Qoder HTTP 500"},
                {"exchange incomplete", http(200, new JSONObject().put("token", ROTATED).toString()), 502, "incomplete_token_pair"},
                {"exchange unreadable", http(200, "<html>not a token pair</html>"), 502, "body_unreadable"},
                {"exchange refused", http(403, SECRET), 401, "sign-in lapsed"},
        };
        for (Object[] renewalCase : broken) {
            Case bad = new Case(http(401, lapsed(SECRET)), null, (String) renewalCase[1], 0L);
            try {
                Result r = bad.send();
                check(r.status == (Integer) renewalCase[2], renewalCase[0] + " status=" + r.status + " body=" + r.body);
                // The renewal's own words travel with the answer, as the plugin forwards e.message.
                check(r.body.contains((String) renewalCase[3]),
                        renewalCase[0] + " must say why it stopped \"" + renewalCase[3] + "\": " + r.body);
                check(bad.chat.calls.get() == 1, renewalCase[0] + " must not replay the request; calls=" + bad.chat.calls.get());
                check(bad.renewal.calls.get() == 1, renewalCase[0] + " exchange calls=" + bad.renewal.calls.get());
                check(!r.body.contains(SECRET), renewalCase[0] + " leaked upstream body");
                check(storedToken(bad.gateway.configFile).isEmpty(), renewalCase[0] + " must not store a token it was not given");
                // The one-use value may already be spent upstream: a later request must not exchange it again.
                Result later = bad.send();
                check(later.status == 401, renewalCase[0] + " later status=" + later.status + " body=" + later.body);
                check(bad.chat.calls.get() == 2, renewalCase[0] + " later Qoder calls=" + bad.chat.calls.get());
                check(bad.renewal.calls.get() == 1, renewalCase[0] + " later exchanges=" + bad.renewal.calls.get());
            } finally { bad.close(); }
        }

        // The rotation is not stored: the request is not asked again, and the spent value stays fenced.
        Case unwritable = new Case(http(401, lapsed(SECRET)), null, renewing(), 0L);
        try {
            java.io.File directory = new java.io.File(unwritable.gateway.configFile.getAbsolutePath());
            check(directory.mkdir(), "configuration directory fixture");
            RoutingAuditOfflineTest.file(unwritable.gateway.inner, directory);
            Result r = unwritable.send();
            check(r.status == 502, "unstored rotation status=" + r.status + " body=" + r.body);
            check(unwritable.chat.calls.get() == 1, "unstored rotation must not replay the request; calls="
                    + unwritable.chat.calls.get());
            check(unwritable.renewal.calls.get() == 1, "unstored rotation exchange calls=" + unwritable.renewal.calls.get());
            check(!r.body.contains(SECRET), "unstored rotation leaked upstream body");
            Result later = unwritable.send();
            check(later.status == 401, "unstored rotation later status=" + later.status + " body=" + later.body);
            check(unwritable.chat.calls.get() == 2, "unstored rotation later calls=" + unwritable.chat.calls.get());
            check(unwritable.renewal.calls.get() == 1, "a value the fence says may be spent must never be exchanged again; exchanges="
                    + unwritable.renewal.calls.get());
        } finally { unwritable.close(); }

        // A stream that ends without a verdict: unknown execution, never a renewal and never a replay.
        Case unknown = new Case(sse("data: {\"statusCodeValue\":200,\"body\":\"{\\\"choices\\\":\"}"), null, renewing(), 0L);
        try {
            Result r = unknown.send();
            check(r.status == 502, "unknown execution status=" + r.status + " body=" + r.body);
            check(unknown.chat.calls.get() == 1, "unknown execution must not replay; calls=" + unknown.chat.calls.get());
            check(unknown.renewal.calls.get() == 0, "unknown execution must not spend a refresh token; exchanges="
                    + unknown.renewal.calls.get());
        } finally { unknown.close(); }

        // The request is cancelled while its sign-in is being renewed: the renewal is stored, nothing is replayed.
        Case cancelled = new Case(http(401, lapsed(SECRET)), answered("too late"), renewing(), 1200L);
        cancelled.renewal.delayMillis = 2500;
        try {
            try { cancelled.send(); }
            catch (Exception expected) { /* the cancelled request's own answer is not this case's subject */ }
            check(cancelled.chat.calls.get() == 1, "cancelled request must not be asked again; calls=" + cancelled.chat.calls.get());
            check(cancelled.renewal.calls.get() == 1, "cancelled request exchange calls=" + cancelled.renewal.calls.get());
            check(ROTATED.equals(storedToken(cancelled.gateway.configFile)),
                    "a renewal that finished must still be stored; store said \"" + storedToken(cancelled.gateway.configFile) + "\"");
        } finally { cancelled.close(); }
    }
}
