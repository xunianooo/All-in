package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The vendored plugin's disabled-model quota answer (opencode-qoder-auth 0.2.64, index.mjs:2810-2843
 * spentOf, :3278-3300 the listing's off set, :3574-3594 the 429) over the real request chain:
 * catalog sync → persisted account record → /v1/chat/completions, /v1/messages and /api/test-model.
 *
 * <p>Every upstream is a loopback stand-in: one path each for the listing, the credits and the chat,
 * plus the token refresh. The gateway's Qoder transport is pointed at those paths, so no request can
 * leave the machine — and every case asserts it, by counting what the stand-in saw and by checking
 * the exact URL the gateway asked for.
 *
 * <p>The boundaries are what most of these cases are about: only a <em>stated</em> off from a listing
 * read <em>for this request</em> ({@code "enable": "false"} is on to JavaScript, and an old sync's
 * record is not this request's listing), and only when <em>every</em> account that could serve the
 * model is confirmed with its own reading, is a 429. Anything else keeps the answer the gateway gave
 * before.
 */
public final class QoderDisabledModelQuotaHttpTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void eq(int got, int want, String why) {
        if (got != want) throw new AssertionError(why + ": got " + got + " want " + want);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** Only ever appears in a stand-in's body, never in an answer: a leak would show it. */
    private static final String SECRET = "upstream-secret-must-not-leak";
    private static final String API_KEY = "fixture-auth";
    private static final String DISABLED = "qwen";
    private static final String SERVED = "dfmodel";

    // ---- the stand-in ---------------------------------------------------------------------

    static final class Upstream implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        /** A cached pool so a deliberately slow stand-in cannot queue behind another request. */
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool();
        final AtomicInteger models = new AtomicInteger(), usage = new AtomicInteger();
        final AtomicInteger chat = new AtomicInteger(), refresh = new AtomicInteger();
        final Map<String, JSONObject> listings = new ConcurrentHashMap<String, JSONObject>();
        final Map<String, JSONObject> usages = new ConcurrentHashMap<String, JSONObject>();
        final List<String> urls = java.util.Collections.synchronizedList(new ArrayList<String>());
        volatile int listingStatus = 200, usageStatus = 200;
        /** The device token whose usage read answers {@link #failureStatus}, for one account of several. */
        volatile String failingToken = "";
        volatile int failureStatus = 500;
        volatile long usageDelayMillis, listingDelayMillis;
        volatile boolean drip;
        volatile long dripMillis = 40;
        volatile String lastUsageToken = "", lastModelsUser = "";

        Upstream() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                drain(exchange.getRequestBody());
                int status = 200;
                String wire;
                if ("/models".equals(path)) {
                    models.incrementAndGet();
                    String user = exchange.getRequestHeaders().getFirst("Cosy-User");
                    lastModelsUser = user == null ? "" : user;
                    if (listingDelayMillis > 0) {
                        try { Thread.sleep(listingDelayMillis); } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    JSONObject listing = listings.get(lastModelsUser);
                    status = listingStatus;
                    wire = listing == null ? blankListing() : listing.toString();
                } else if ("/usage".equals(path)) {
                    usage.incrementAndGet();
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    lastUsageToken = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
                    if (drip) { drip(exchange); return; }
                    if (usageDelayMillis > 0) {
                        try { Thread.sleep(usageDelayMillis); } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    JSONObject envelope = usages.get(lastUsageToken);
                    status = usageStatus;
                    if (!failingToken.isEmpty() && failingToken.equals(lastUsageToken)) status = failureStatus;
                    wire = envelope == null ? "{\"displayMode\":\"qoder\",\"qoderUsage\":{}}" : envelope.toString();
                } else if ("/refresh".equals(path)) {
                    refresh.incrementAndGet();
                    wire = "{\"token\":\"refreshed-token\",\"refresh_token\":\"refreshed-refresh\",\"expires_in\":3600}";
                } else {
                    chat.incrementAndGet();
                    wire = successSse();
                }
                byte[] out = bytes(wire);
                exchange.getResponseHeaders().add("Content-Type",
                        "/chat".equals(path) ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream body = exchange.getResponseBody()) { body.write(out); }
            });
            server.setExecutor(pool);
            server.start();
        }

        /** Chunked, a byte at a time: no single read ever times out, the body never ends early. */
        private void drip(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            long until = System.currentTimeMillis() + 20000;
            try {
                while (System.currentTimeMillis() < until) {
                    body.write(' ');
                    body.flush();
                    Thread.sleep(dripMillis);
                }
            } catch (InterruptedException | java.io.IOException stopped) {
                // the caller disconnected: expected
            } finally {
                try { body.close(); } catch (java.io.IOException ignored) { }
            }
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        public void close() { server.stop(0); pool.shutdownNow(); }

        private static void drain(InputStream in) throws java.io.IOException {
            byte[] sink = new byte[4096];
            while (in.read(sink) != -1) { /* the bodies are tiny; nothing here reads them */ }
        }
    }

    /** An empty listing, as text: the stand-in's handler may not throw a checked exception. */
    static String blankListing() {
        try {
            return new JSONObject().put("app", new JSONArray()).put("chat", new JSONArray()).toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The listing Qoder's app scene gives one account: [key, on|off]. */
    static JSONObject listing(String[][] entries) throws Exception {
        JSONArray scene = new JSONArray();
        for (String[] entry : entries)
            scene.put(new JSONObject().put("key", entry[0]).put("enable", "on".equals(entry[1]))
                    .put("display_name", entry[0]));
        return new JSONObject().put("app", scene).put("chat", new JSONArray());
    }

    /** A usage envelope: the pools Qoder spends in turn, as it sends them. */
    static JSONObject usage(double total, double used) throws Exception {
        return new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", total).put("used", used)));
    }

    /** One SSE frame of Qoder's envelope shape: the body is JSON inside a JSON string. */
    static String frame(JSONObject chunk) throws Exception {
        return "data: " + new JSONObject().put("statusCodeValue", 200).put("body", chunk.toString()) + "\n\n";
    }

    static JSONObject delta(JSONObject delta, String finish) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", delta).put("finish_reason", finish == null ? JSONObject.NULL : finish)));
    }

    static String successSse() {
        try {
            return frame(delta(new JSONObject().put("content", "hi-from-qoder"), null))
                    + frame(delta(new JSONObject(), "stop"))
                    + "data: [DONE]\n\n";
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static JSONObject account(String id, String deviceToken) throws Exception {
        return new JSONObject().put("id", id).put("name", id).put("enabled", true)
                .put("token", "job-token-" + id).put("refresh", "refresh-" + id)
                .put("deviceToken", deviceToken).put("deviceRefresh", "device-refresh-" + id)
                .put("deviceChat", true).put("uid", id).put("machineId", "machine-" + id)
                .put("expiresAt", System.currentTimeMillis() + 86400000L);
    }

    static JSONObject config(JSONArray accounts) throws Exception {
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("name", "Qoder").put("enabled", true)
                .put("protocol", "qoder").put("baseUrl", "https://gateway.qoder.com.cn")
                .put("models", new JSONArray()).put("modelMeta", new JSONObject())
                .put("accounts", accounts);
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", API_KEY))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(qoder));
    }

    /** The gateway under a loopback proxy, with every Qoder connection pointed at the stand-in. */
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        final Path dir;
        final File configFile;
        final Upstream upstream;
        Gateway(JSONObject config, Upstream upstream) throws Exception {
            super("127.0.0.1", 0);
            this.upstream = upstream;
            inner = RoutingSetupOfflineTest.server(config);
            dir = Files.createTempDirectory("qoder-quota-");
            configFile = dir.resolve("config.json").toFile();
            UsageLedger.init(dir.toFile());
            RoutingAuditOfflineTest.file(inner, configFile);
            java.lang.reflect.Field lock = GatewayServer.class.getDeclaredField("qoderSyncLock");
            lock.setAccessible(true); lock.set(inner, new Object());
            AtomicConfigFile.write(configFile, config.toString());
            final String base = upstream.base();
            inner.setQoderConnectionsForTests(url -> {
                upstream.urls.add(url);
                return (HttpURLConnection) URI.create(local(url, base)).toURL().openConnection(Proxy.NO_PROXY);
            });
            inner.setQoderUsageTimeoutForTests(1200);
            start(SOCKET_READ_TIMEOUT, true);
        }

        /** Which stand-in path a Qoder URL stands for; nothing here reaches a real host. */
        static String local(String url, String base) {
            if (url.contains("/sash/api/")) return base + "/usage";
            if (url.contains("model/list")) return base + "/models";
            if (url.contains("deviceToken")) return base + "/refresh";
            return base + "/chat";
        }

        String port() { return Integer.toString(getListeningPort()); }

        JSONObject provider() throws Exception {
            return ResponsesGatewayOfflineTest.config(inner).getJSONArray("providers").getJSONObject(0);
        }

        JSONObject liveAccount(int index) throws Exception {
            return provider().getJSONArray("accounts").getJSONObject(index);
        }

        JSONObject storedAccount(int index) throws Exception {
            return new JSONObject(new String(Files.readAllBytes(configFile.toPath()), "UTF-8"))
                    .getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(index);
        }

        public Response serve(IHTTPSession session) { return inner.serve(session); }

        public void close() {
            inner.setQoderConnectionsForTests(null);
            stop();
        }
    }

    static final class Result {
        int status; String body = "";
        JSONObject json() throws Exception { return new JSONObject(body); }
    }

    static Result call(Gateway gateway, String path, String body, boolean management) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + gateway.port() + path)
                .toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (management) c.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        else c.setRequestProperty("Authorization", "Bearer " + API_KEY);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes(body)); }
        Result result = new Result();
        try {
            result.status = c.getResponseCode();
            InputStream in = result.status >= 400 ? c.getErrorStream() : c.getInputStream();
            try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (stream != null) {
                    byte[] buf = new byte[2048];
                    for (int n; (n = stream.read(buf)) != -1; ) out.write(buf, 0, n);
                }
                result.body = out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
        return result;
    }

    static Result sync(Gateway gateway) throws Exception {
        return call(gateway, "/api/auth/qoder_cn/sync-models", "", true);
    }

    static Result chat(Gateway gateway, String model) throws Exception {
        String body = new JSONObject().put("model", model)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")))
                .toString();
        return call(gateway, "/v1/chat/completions", body, false);
    }

    static Result anthropic(Gateway gateway, String model) throws Exception {
        String body = new JSONObject().put("model", model).put("max_tokens", 64)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "hello")))))
                .toString();
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + gateway.port() + "/v1/messages")
                .toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("Authorization", "Bearer " + API_KEY);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes(body)); }
        Result result = new Result();
        try {
            result.status = c.getResponseCode();
            InputStream in = result.status >= 400 ? c.getErrorStream() : c.getInputStream();
            try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (stream != null) {
                    byte[] buf = new byte[2048];
                    for (int n; (n = stream.read(buf)) != -1; ) out.write(buf, 0, n);
                }
                result.body = out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
        return result;
    }

    static Result probe(Gateway gateway, String model) throws Exception {
        return call(gateway, "/api/test-model", new JSONObject().put("model", model).toString(), true);
    }

    /**
     * Nothing of an account or a token may appear in an answer: the fixture's own secrets, the
     * device tokens, the job tokens and the refresh pair.
     */
    static void noLeak(Result result) {
        for (String value : new String[]{"job-token-", "device-a", "device-b", "device-refresh-",
                "refresh-acc-", SECRET, "refreshed-token", "@", "machine-"})
            check(!result.body.contains(value), "answer leaked " + value + ": " + result.body);
    }

    /** B4: every Qoder URL the gateway asked for is one of Qoder's own, over https. */
    static void onlyQoderHosts(Upstream upstream) {
        for (String url : new ArrayList<String>(upstream.urls)) {
            check(url.startsWith("https://"), "no insecure Qoder url: " + url);
            String host = URI.create(url).getHost();
            check("openapi.qoder.com.cn".equals(host) || "gateway.qoder.com.cn".equals(host),
                    "only Qoder's own hosts, got " + host + " from " + url);
        }
    }

    // ---- the cases -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);

        // 1. A sync keeps the listing's off set on the account it read, on disk and in the answer.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(
                config(new JSONArray().put(account("acc-a", "device-a"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", usage(800, 800));
            Result synced = sync(gateway);
            eq(synced.status, 200, "sync status: " + synced.body);
            eq(synced.json().getJSONArray("models").length(), 1, "enabled model listed: " + synced.body);
            JSONArray off = synced.json().getJSONArray("disabledModels");
            check(off.length() == 1 && DISABLED.equals(off.getJSONObject(0).getString("key")),
                    "the off model is reported: " + synced.body);
            JSONObject saved = gateway.storedAccount(0);
            eq(saved.getJSONArray("models").length(), 1, "models persisted");
            check(QoderCatalog.storedOff(saved, DISABLED), "off set persisted: " + saved);
            check(QoderCatalog.observedAt(saved) > 0, "the record says when the listing was read");
            check(!QoderCatalog.storedOff(saved, SERVED), "record does not say the served model is off");
            onlyQoderHosts(upstream);

            // 2. A failed sync keeps what the last good one wrote — the off set and its stamp too.
            long stamp = QoderCatalog.observedAt(saved);
            upstream.listingStatus = 500;
            check(sync(gateway).status != 200, "failed sync");
            JSONObject after = gateway.storedAccount(0);
            eq(after.getJSONArray("models").length(), 1, "old models kept after a failed sync");
            check(QoderCatalog.storedOff(after, DISABLED), "old off set kept after a failed sync");
            eq((int) (QoderCatalog.observedAt(after) - stamp), 0, "and so is its stamp");
            upstream.listingStatus = 200;
        }

        // 3. The whole point: stated off + spent answers 429, without ever calling the chat endpoint.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(
                config(new JSONArray().put(account("acc-a", "device-a"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", usage(800, 800));
            check(sync(gateway).status == 200, "sync before the quota case");
            int listingReads = upstream.models.get();
            int usageReads = upstream.usage.get();

            Result r = chat(gateway, "qoder_cn/" + DISABLED);
            eq(r.status, 429, "off + spent is 429: " + r.body);
            String said = r.json().getJSONObject("error").getString("message");
            check(said.equals("usage limit reached: the account's credits are used up (800 / 800 credits), "
                    + "and Qoder lists \"" + DISABLED + "\" off for it until they refresh"), "Qoder's sentence: " + said);
            eq(upstream.chat.get(), 0, "a quota answer never reaches the chat endpoint");
            eq(upstream.models.get() - listingReads, 1, "the listing was read again for this request");
            eq(upstream.usage.get() - usageReads, 1, "exactly one read-only usage read");
            check("device-a".equals(upstream.lastUsageToken), "read with the account's own device token, not " + upstream.lastUsageToken);
            onlyQoderHosts(upstream);
            noLeak(r);

            // 4. The same model with credits left: the answer it always gave (400), not a 429.
            upstream.usages.put("device-a", usage(800, 10));
            Result plenty = chat(gateway, "qoder_cn/" + DISABLED);
            eq(plenty.status, 400, "credits left is still 400: " + plenty.body);
            check(plenty.body.contains("No available API Key or active account"), "the original answer: " + plenty.body);
            eq(upstream.chat.get(), 0, "no chat call with credits left");

            // 5. A usage that cannot be read says nothing: still 400.
            upstream.usages.put("device-a", usage(800, 800));
            upstream.usageStatus = 500;
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "unreadable usage is 400");
            upstream.usageStatus = 200;

            // 6. A refused read (401) is no verdict either, and it must not spend the account's
            //    refresh token to upgrade a 400 into a 429.
            JSONObject before = new JSONObject(gateway.storedAccount(0).toString());
            upstream.usageStatus = 401;
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "a refused usage read is 400");
            eq(upstream.refresh.get(), 0, "no token rotation for a read-only probe");
            check(new JSONObject(gateway.storedAccount(0).toString()).similar(before), "the account is untouched");
            upstream.usageStatus = 200;

            // 7. A usage that times out says nothing either, and does not hang the request.
            upstream.usageDelayMillis = 3000;
            gateway.inner.setQoderUsageTimeoutForTests(300);
            long began = System.currentTimeMillis();
            Result slow = chat(gateway, "qoder_cn/" + DISABLED);
            long elapsed = System.currentTimeMillis() - began;
            upstream.usageDelayMillis = 0;
            gateway.inner.setQoderUsageTimeoutForTests(1200);
            eq(slow.status, 400, "a timed-out usage read is 400: " + slow.body);
            check(elapsed < 2500, "the read is bounded by its timeout, took " + elapsed + "ms");

            // 8. A body that dribbles forever is stopped by the whole-read deadline, not by a socket
            //    timeout that every single read keeps resetting.
            upstream.drip = true;
            upstream.dripMillis = 40;
            gateway.inner.setQoderUsageTimeoutForTests(500);
            began = System.currentTimeMillis();
            Result dripped = chat(gateway, "qoder_cn/" + DISABLED);
            elapsed = System.currentTimeMillis() - began;
            upstream.drip = false;
            gateway.inner.setQoderUsageTimeoutForTests(1200);
            eq(dripped.status, 400, "a drip is no verdict: " + dripped.body);
            check(elapsed < 4000, "and the request is not held by it, took " + elapsed + "ms");
            eq(upstream.chat.get(), 0, "still no chat call");

            // 9. A model the listing never names is unknown, and no usage read is spent on it.
            usageReads = upstream.usage.get();
            eq(chat(gateway, "qoder_cn/gpt-never").status, 400, "an unnamed model is 400");
            eq(upstream.usage.get(), usageReads, "no usage read for a model the listing never named");

            // 10. A model the account does serve goes through the chat endpoint, with no probe at all.
            int modelsNow = upstream.models.get(), usagesNow = upstream.usage.get();
            Result served = chat(gateway, "qoder_cn/" + SERVED);
            eq(served.status, 200, "the served model answers: " + served.body);
            check(served.body.contains("hi-from-qoder"), "the model's own text: " + served.body);
            eq(upstream.chat.get(), 1, "one chat call");
            eq(upstream.models.get(), modelsNow, "a served model costs no listing read");
            eq(upstream.usage.get(), usagesNow, "a served model costs no quota read");

            // 11. /api/test-model answers the same way, in the panel's own shape.
            Result probed = probe(gateway, "qoder_cn/" + DISABLED);
            eq(probed.status, 200, "test-model envelope status");
            JSONObject verdict = probed.json();
            check(!verdict.optBoolean("ok", true), "test-model must not claim ok: " + verdict);
            eq(verdict.optInt("statusCode", 0), 429, "test-model statusCode");
            check("quota".equals(verdict.optString("errorKind")), "test-model errorKind=" + verdict.optString("errorKind"));
            check("quota_exhausted".equals(verdict.optString("reason")), "test-model reason=" + verdict.optString("reason"));
            check(verdict.optString("error").contains("credits are used up"), "test-model error: " + verdict.optString("error"));
            eq(upstream.chat.get(), 1, "a confirmed quota is not probed upstream");
            noLeak(probed);

            // 12. test-model for the served model really does probe it.
            Result probedServed = probe(gateway, "qoder_cn/" + SERVED);
            check(probedServed.json().optBoolean("ok", false), "test-model ok for the served model: " + probedServed.body);
            eq(probedServed.json().optInt("statusCode", 0), 200, "test-model statusCode 200");
            eq(upstream.chat.get(), 2, "the served model was probed");

            // 13. The Anthropic-shaped entry point resolves candidates the same way.
            Result messages = anthropic(gateway, "qoder_cn/" + DISABLED);
            eq(messages.status, 429, "/v1/messages gets the same quota: " + messages.body);
            check(messages.body.contains("credits are used up"), "/v1/messages carries Qoder's sentence: " + messages.body);
            eq(upstream.chat.get(), 2, "no chat call on the messages path either");
            noLeak(messages);
        }

        // 14. An off set from an older sync is never the basis of a refusal: Qoder turns the model
        //     back on when its credits refresh, and that record would then refuse a working model.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(
                config(new JSONArray().put(account("acc-a", "device-a"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", usage(800, 800));
            check(sync(gateway).status == 200, "sync for the staleness case");
            // The account's record still states it off — and keeps saying so.
            check(QoderCatalog.storedOff(gateway.storedAccount(0), DISABLED), "the record says off");

            // Qoder now lists the model on again. No sync has run, so the stored models still lack it
            // and the candidates are empty — the very path that used to trust the old record.
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "on"}}));
            int usageReads = upstream.usage.get();
            Result turnedOn = chat(gateway, "qoder_cn/" + DISABLED);
            eq(turnedOn.status, 400, "a model Qoder lists on is not a quota: " + turnedOn.body);
            eq(upstream.usage.get(), usageReads, "and no quota read is spent on it");
            check(!turnedOn.body.contains("credits are used up"), "no quota sentence: " + turnedOn.body);

            // A live sync makes the model usable again, and the request then simply works.
            check(sync(gateway).status == 200, "resync after the model was turned on");
            Result served = chat(gateway, "qoder_cn/" + DISABLED);
            eq(served.status, 200, "the model works again: " + served.body);

            // And if the live listing cannot be read at all, nothing is judged either.
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            check(sync(gateway).status == 200, "sync before the unreadable-listing case");
            gateway.liveAccount(0).put("models", new JSONArray().put(SERVED));  // stored目录回到「不含它」
            upstream.listingStatus = 500;
            usageReads = upstream.usage.get();
            Result unreadable = chat(gateway, "qoder_cn/" + DISABLED);
            eq(unreadable.status, 400, "an unreadable live listing is no quota: " + unreadable.body);
            eq(upstream.usage.get(), usageReads, "and no quota read either");
            upstream.listingStatus = 200;
        }

        // 15. B7: a pool named as null is not a pool — the snake_case spelling behind it is hidden,
        //     exactly as `u[a] !== undefined ? u[a] : u[b]` reads it.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(
                config(new JSONArray().put(account("acc-a", "device-a"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                    new JSONObject().put("userQuota", JSONObject.NULL)
                            .put("user_quota", new JSONObject().put("total", 800).put("used", 800))));
            check(sync(gateway).status == 200, "sync for the null-pool case");
            Result r = chat(gateway, "qoder_cn/" + DISABLED);
            eq(r.status, 400, "a null camel pool is no quota: " + r.body);
            check(!r.body.contains("credits are used up"), "no quota sentence: " + r.body);
        }

        // 16. Two accounts, both stating it off: the model is only spent when BOTH are, and the
        //     sentence quotes each account's own reading.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(new JSONArray()
                .put(account("acc-a", "device-a")).put(account("acc-b", "device-b"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.listings.put("acc-b", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", usage(800, 10));
            upstream.usages.put("device-b", usage(800, 800));
            check(sync(gateway).status == 200, "two-account sync");

            // The first account still has credits: no 429 for the model, whatever the second spent.
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "one account with credits means no 429");

            // And with the order reversed: the first is spent while the second has credits.
            upstream.usages.put("device-a", usage(800, 800));
            upstream.usages.put("device-b", usage(800, 10));
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "a later account with credits also means no 429");

            // The second account cannot be read: still no 429 — that is not a confirmation either.
            upstream.usages.put("device-a", usage(800, 800));
            upstream.usages.put("device-b", usage(800, 800));
            upstream.failingToken = "device-b";
            upstream.failureStatus = 500;
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "an unreadable account means no 429");

            // A refused read on the second account is no confirmation either, and rotates nothing.
            upstream.failureStatus = 401;
            eq(chat(gateway, "qoder_cn/" + DISABLED).status, 400, "a refused account means no 429");
            eq(upstream.refresh.get(), 0, "no rotation, refresh=" + upstream.refresh.get());
            upstream.failingToken = "";

            // Both spent: now it is the model's quota, and no single count speaks for both.
            upstream.usages.put("device-a", usage(800, 800));
            upstream.usages.put("device-b", usage(1200, 1200));
            Result quota = chat(gateway, "qoder_cn/" + DISABLED);
            eq(quota.status, 429, "both accounts spent is 429: " + quota.body);
            String said = quota.json().getJSONObject("error").getString("message");
            check(said.contains("the accounts' credits are used up"), "plural wording: " + said);
            check(said.contains("account 1: 800 / 800 credits"), "account 1's own reading: " + said);
            check(said.contains("account 2: 1200 / 1200 credits"), "account 2's own reading: " + said);
            check(!said.contains("all 2 accounts"), "no single count lent to all: " + said);
            check(said.contains("off for them until they refresh"), "plural pronoun: " + said);
            eq(upstream.chat.get(), 0, "no chat call for either account");
            onlyQoderHosts(upstream);
            noLeak(quota);
        }

        // 17. One account still serves it: the request goes there, and no quota read happens.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(new JSONArray()
                .put(account("acc-a", "device-a")).put(account("acc-b", "device-b"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.listings.put("acc-b", listing(new String[][]{{SERVED, "on"}, {DISABLED, "on"}}));
            upstream.usages.put("device-a", usage(800, 800));
            check(sync(gateway).status == 200, "split sync");
            int usageReads = upstream.usage.get();
            Result r = chat(gateway, "qoder_cn/" + DISABLED);
            eq(r.status, 200, "the account that serves it is used: " + r.body);
            check(r.body.contains("hi-from-qoder"), "served by the second account: " + r.body);
            eq(upstream.usage.get(), usageReads, "no quota read when any account can serve it");
            eq(upstream.chat.get(), 1, "one chat call");
        }

        // 18. A provider that is not Qoder is never judged by this rule.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(
                config(new JSONArray().put(account("acc-a", "device-a"))), upstream)) {
            upstream.listings.put("acc-a", listing(new String[][]{{SERVED, "on"}, {DISABLED, "off"}}));
            upstream.usages.put("device-a", usage(800, 800));
            check(sync(gateway).status == 200, "sync for the non-Qoder case");
            int models = upstream.models.get(), usages = upstream.usage.get();
            Result r = chat(gateway, "openai/" + DISABLED);
            check(r.status == 404 || r.status == 400, "another provider keeps its own answer: " + r.status + " " + r.body);
            eq(upstream.models.get(), models, "no Qoder listing read for another provider");
            eq(upstream.usage.get(), usages, "no Qoder usage read for another provider");
        }

        System.out.println("QoderDisabledModelQuotaHttpTest ok");
    }
}
