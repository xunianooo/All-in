package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The 429 that says "the model is off and the credits are used up" is a verdict about an account, so
 * every input it needs has to be confirmed. One of them is a listing read <em>for this request</em>
 * ({@link QoderRefusal#statedOff}); that read is a diagnostic that may not become the verdict:
 *
 * <ul>
 *   <li>every account's read draws from <b>one</b> budget per request
 *       ({@code GatewayServer.qoderUsageBudget}: min of the configured read budget and what is left
 *       of the request), never a fresh one per account;</li>
 *   <li>a budget with no room left starts no read at all, and a read that runs it out leaves no room
 *       for the usage read either — {@code qoderDisabledModelQuota} then answers null, so the request
 *       keeps the answer it had before instead of a 429;</li>
 *   <li>a cancelled request judges nothing and reads nothing;</li>
 *   <li>none of this spends the account's one-shot refresh token or writes the account record.</li>
 * </ul>
 *
 * <p>Cases A-D drive the real {@code /v1/chat/completions} entry against two loopback stand-ins (a
 * listing/usage/refresh/chat path each, no request can leave the machine). Cancellation is not
 * observable over the wire — a client that goes away gets no answer to read — so E and F call the
 * same private entry the HTTP path calls, with the very {@link RequestContext} the gateway installs
 * for a request, and assert what the stand-in saw.
 *
 * <p>The last case is also the recorded gap: {@link QoderAuth#readCatalog} takes no budget and no
 * cancellation, so an in-flight listing read runs to its own 15s/20s timeouts. The budget stops the
 * <em>next</em> read and the verdict; it does not abort this one. Making the read itself obey the
 * budget needs the caller to pass it (see the handoff for the exact anchor and signature).
 *
 * <p><b>The read itself now obeys a budget too.</b> {@link QoderAuth#readCatalog(SignIn,
 * ConnectionFactory, QoderUsage.Budget)} takes the request's {@link QoderUsage.Budget}: one deadline
 * for the whole read (status line and body), both socket timeouts set from what is left of it, and
 * the connection attached before the first read so a cancellation disconnects it instead of waiting.
 * The budget-less overloads delegate to {@code Budget.NONE} and keep the 15s/20s they always had.
 * Case G drives that read directly against its own loopback stand-in — no gateway, no NanoHTTPD — and
 * pins the deadline, the cancellation and the attach/detach pairs.
 *
 * <p>The call site is wired: {@code GatewayServer.qoderRead(si, budget)} hands the
 * {@link QoderUsage.Budget} {@code qoderDisabledModelQuota} already holds to that read, so a listing
 * that runs past the request's budget is aborted instead of finishing on its own timeouts. Case D
 * pins that: the same over-budget listing now returns well inside the delay, still with no verdict.
 */
public final class QoderCatalogBudgetHttpTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void eq(int got, int want, String why) {
        if (got != want) throw new AssertionError(why + ": got " + got + " want " + want);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static final String API_KEY = "fixture-auth";
    /** The model both accounts' listings state off. */
    private static final String MODEL = "qwen";
    /** The model they keep on, so no account can serve it out of turn. */
    private static final String SERVED = "dfmodel";

    // ---- the stand-ins -----------------------------------------------------------------------

    /** A loopback stand-in: one path each for the listing, the credits, the chat and the refresh. */
    static final class Upstream implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        /** A cached pool, so a deliberately slow stand-in cannot queue behind another request. */
        final ExecutorService pool = Executors.newCachedThreadPool();
        final AtomicInteger models = new AtomicInteger(), usage = new AtomicInteger();
        final AtomicInteger chat = new AtomicInteger(), refresh = new AtomicInteger();
        final Map<String, JSONObject> listings = new ConcurrentHashMap<String, JSONObject>();
        final Map<String, JSONObject> usages = new ConcurrentHashMap<String, JSONObject>();
        final List<String> urls = Collections.synchronizedList(new ArrayList<String>());
        volatile long listingDelayMillis;

        Upstream() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                drain(exchange.getRequestBody());
                String wire;
                if ("/models".equals(path)) {
                    models.incrementAndGet();
                    String user = exchange.getRequestHeaders().getFirst("Cosy-User");
                    if (listingDelayMillis > 0) {
                        try { Thread.sleep(listingDelayMillis); } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    JSONObject listing = listings.get(user == null ? "" : user);
                    wire = listing == null ? emptyListing() : listing.toString();
                } else if ("/usage".equals(path)) {
                    usage.incrementAndGet();
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
                    JSONObject envelope = usages.get(token);
                    wire = envelope == null ? "{\"displayMode\":\"qoder\",\"qoderUsage\":{}}" : envelope.toString();
                } else if ("/refresh".equals(path)) {
                    refresh.incrementAndGet();
                    wire = "{\"token\":\"refreshed-token\",\"refresh_token\":\"refreshed-refresh\",\"expires_in\":3600}";
                } else {
                    chat.incrementAndGet();
                    wire = "\"unexpected\"";
                }
                byte[] out = bytes(wire);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream body = exchange.getResponseBody()) { body.write(out); }
            });
            server.setExecutor(pool);
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        public void close() { server.stop(0); pool.shutdownNow(); }

        private static void drain(InputStream in) throws java.io.IOException {
            byte[] sink = new byte[4096];
            while (in.read(sink) != -1) { /* bodies here are tiny */ }
        }
    }

    static String emptyListing() {
        try {
            return new JSONObject().put("app", new JSONArray()).put("chat", new JSONArray()).toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** A listing that states {@link #MODEL} off and {@link #SERVED} on, as Qoder sends it. */
    static JSONObject offListing() throws Exception {
        JSONArray scene = new JSONArray();
        scene.put(new JSONObject().put("key", SERVED).put("enable", true).put("display_name", SERVED));
        scene.put(new JSONObject().put("key", MODEL).put("enable", false).put("display_name", MODEL));
        return new JSONObject().put("app", scene).put("chat", new JSONArray());
    }

    /** A usage envelope with every pool spent. */
    static JSONObject spent() throws Exception {
        return new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", 800).put("used", 800)));
    }

    static JSONObject account(String id) throws Exception {
        return new JSONObject().put("id", id).put("name", id).put("enabled", true)
                .put("token", "job-token-" + id).put("refresh", "refresh-" + id)
                .put("deviceToken", "device-" + id).put("deviceRefresh", "device-refresh-" + id)
                .put("deviceChat", true).put("uid", id).put("machineId", "machine-" + id)
                .put("expiresAt", System.currentTimeMillis() + 86400000L)
                .put("models", new JSONArray()).put("modelMeta", new JSONObject());
    }

    /** The provider with {@code accounts} accounts that serve nothing: every request is a candidate miss. */
    static JSONObject config(int accounts, JSONObject failover) throws Exception {
        JSONArray list = new JSONArray();
        String[] ids = {"acc-a", "acc-b"};
        for (int i = 0; i < accounts && i < ids.length; i++) list.put(account(ids[i]));
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("name", "Qoder").put("enabled", true)
                .put("protocol", "qoder").put("baseUrl", "https://gateway.qoder.com.cn")
                .put("models", new JSONArray()).put("modelMeta", new JSONObject()).put("accounts", list);
        JSONObject config = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", API_KEY))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(qoder));
        config.put("failover", failover == null
                ? new JSONObject().put("enabled", false) : failover.put("enabled", false));
        return config;
    }

    /** The gateway over a loopback proxy, with every Qoder connection pointed at the stand-in. */
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        final File configFile;
        final Upstream upstream;
        final String initialConfig;
        Gateway(JSONObject config, Upstream upstream) throws Exception {
            super("127.0.0.1", 0);
            this.upstream = upstream;
            this.initialConfig = config.toString();
            inner = RoutingSetupOfflineTest.server(config);
            Path dir = Files.createTempDirectory("qoder-budget-");
            configFile = dir.resolve("config.json").toFile();
            UsageLedger.init(dir.toFile());
            RoutingAuditOfflineTest.file(inner, configFile);
            Field lock = GatewayServer.class.getDeclaredField("qoderSyncLock");
            lock.setAccessible(true); lock.set(inner, new Object());
            AtomicConfigFile.write(configFile, config.toString());
            final String base = upstream.base();
            inner.setQoderConnectionsForTests(url -> {
                upstream.urls.add(url);
                return (HttpURLConnection) URI.create(local(url, base)).toURL().openConnection(Proxy.NO_PROXY);
            });
            inner.setQoderUsageTimeoutForTests(8000);
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

        /** The account record as it is on disk: a diagnostic may not rewrite it. */
        String storedConfig() throws Exception {
            return new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
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

    /** One chat request for a model no account serves: the candidate miss the quota path answers. */
    static Result chat(Gateway gateway, String model) throws Exception {
        String body = new JSONObject().put("model", model)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")))
                .toString();
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + gateway.port() + "/v1/chat/completions")
                .toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(4000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
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

    /** The very entry the HTTP path calls, with the request's own context installed. */
    static JSONObject verdict(GatewayServer gateway, RequestContext context, String modelRef) throws Exception {
        Field slotField = GatewayServer.class.getDeclaredField("REQUEST_CONTEXT");
        slotField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<RequestContext> slot = (ThreadLocal<RequestContext>) slotField.get(null);
        RequestContext previous = slot.get();
        slot.set(context);
        try {
            Method quota = GatewayServer.class.getDeclaredMethod("qoderDisabledModelQuota", String.class);
            quota.setAccessible(true);
            return (JSONObject) quota.invoke(gateway, modelRef);
        } finally {
            if (previous == null) slot.remove(); else slot.set(previous);
        }
    }

    /** Nothing of an account or a token may appear in an answer. */
    static void noLeak(Result result) {
        for (String value : new String[]{"job-token-", "device-acc-", "device-refresh-",
                "refresh-acc-", "refreshed-token", "machine-acc-", "fixture-uid"})
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

    /** Neither the answer nor the disk may carry the verdict beyond the decision. */
    static void untouched(Gateway gateway, Result result, Upstream upstream) throws Exception {
        eq(upstream.chat.get(), 0, "a quota decision never reaches the chat endpoint");
        eq(upstream.refresh.get(), 0, "a read-only diagnostic never spends the one-shot refresh");
        check(gateway.storedConfig().equals(gateway.initialConfig), "the account record was rewritten");
        check(!result.body.contains("credits are used up"), "no quota sentence expected: " + result.body);
        noLeak(result);
        onlyQoderHosts(upstream);
    }

    // ---- the read's own budget, with no gateway in the way (independent fixture) --------------

    /** A loopback stand-in for the catalog read alone: one path, one answer, held back on request. */
    static final class CatalogUpstream implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();
        volatile String wire = emptyListing();
        volatile long delayMillis;

        CatalogUpstream() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try { exchange.getRequestBody().close(); } catch (java.io.IOException ignored) { }
                long hold = delayMillis;
                if (hold > 0) {
                    try { Thread.sleep(hold); } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] out = bytes(wire);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream body = exchange.getResponseBody()) { body.write(out); }
                } catch (java.io.IOException gone) {
                    // The read aborted before this answer was sent; the socket is already closed.
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(pool);
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        public void close() { server.stop(0); pool.shutdownNow(); }
    }

    /**
     * The real connection, watched: which timeouts the read asked the socket for, and whether the
     * read closed it. Every method delegates verbatim; nothing here changes what the read does.
     */
    static final class Watched extends HttpURLConnection {
        final HttpURLConnection real;
        final AtomicInteger disconnects = new AtomicInteger();
        volatile int connectTimeout, readTimeout;
        Watched(HttpURLConnection real) { super(real.getURL()); this.real = real; }
        public void disconnect() { disconnects.incrementAndGet(); real.disconnect(); }
        public boolean usingProxy() { return real.usingProxy(); }
        public void connect() throws java.io.IOException { real.connect(); }
        public int getResponseCode() throws java.io.IOException { return real.getResponseCode(); }
        public InputStream getInputStream() throws java.io.IOException { return real.getInputStream(); }
        public InputStream getErrorStream() { return real.getErrorStream(); }
        public void setRequestMethod(String method) throws java.net.ProtocolException { real.setRequestMethod(method); }
        public void setConnectTimeout(int timeout) { connectTimeout = timeout; real.setConnectTimeout(timeout); }
        public void setReadTimeout(int timeout) { readTimeout = timeout; real.setReadTimeout(timeout); }
        public void setRequestProperty(String key, String value) { real.setRequestProperty(key, value); }
    }

    /**
     * The surrounding request's budget as {@code GatewayServer.qoderUsageBudget} builds it: what is
     * left, whether it was cancelled, and the connections a cancellation must disconnect (that is
     * also what {@link RequestContext#cancel} does, so the read is stopped the way a real request
     * stops it).
     */
    static final class TestBudget implements QoderUsage.Budget {
        volatile long remaining;
        volatile boolean cancelled;
        volatile String reason = "";
        final AtomicInteger attaches = new AtomicInteger(), detaches = new AtomicInteger();
        final List<HttpURLConnection> held = new ArrayList<HttpURLConnection>();
        TestBudget(long remainingMillis) { this.remaining = remainingMillis; }
        public long remainingMillis() { return remaining; }
        public boolean cancelled() { return cancelled; }
        public String reason() { return reason; }
        public void attach(HttpURLConnection connection) throws Exception {
            // RequestContext.attach refuses a cancelled request the same way; the read maps this to
            // "cancelled" rather than to an unreadable catalog.
            if (cancelled) throw new IOException("Request cancelled or deadline exceeded");
            attaches.incrementAndGet();
            synchronized (held) { held.add(connection); }
        }
        public void detach(HttpURLConnection connection) {
            detaches.incrementAndGet();
            synchronized (held) { held.remove(connection); }
        }
        int heldCount() { synchronized (held) { return held.size(); } }
        void cancel(String why) {
            cancelled = true;
            reason = why;
            List<HttpURLConnection> open;
            synchronized (held) { open = new ArrayList<HttpURLConnection>(held); held.clear(); }
            for (HttpURLConnection connection : open) {
                try { connection.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** A throwaway sign-in for the read alone: nothing here is a real account or token. */
    static QoderAuth.SignIn catalogAccount() {
        QoderAuth.SignIn si = new QoderAuth.SignIn();
        si.uid = "fixture-uid";
        si.machineId = "machine-fixture";
        si.access = "fixture-token";
        si.refresh = "fixture-refresh";
        si.deviceToken = "fixture-token";
        si.deviceRefresh = "fixture-refresh";
        si.deviceChat = true;
        si.expiresAt = System.currentTimeMillis() + 86400000L;
        return si;
    }

    /** A read that is expected to fail. */
    interface Read { void run() throws Exception; }

    static IOException failure(Read read) throws Exception {
        try { read.run(); }
        catch (IOException expected) { return expected; }
        throw new AssertionError("the read was expected to fail");
    }

    /**
     * G. {@link QoderAuth#readCatalog(SignIn, QoderAuth.ConnectionFactory, QoderUsage.Budget)} on its
     * own: no gateway, no NanoHTTPD, no verdict — only what the read does with the budget it is given.
     *
     * <ul>
     *   <li>G1 room to spare: the catalog is read, the socket timeouts stay 15s/20s, and the
     *       connection is attached once and detached once — nothing outlives the call;</li>
     *   <li>G2 a budget shorter than the answer: the read stops inside it, as a timeout, with both
     *       socket timeouts set from what was left;</li>
     *   <li>G3 a budget that never opens a socket (cancelled, or no time left): no request at all;</li>
     *   <li>G4 cancelled while blocked on the socket: the cancel disconnects the attached connection
     *       and the read aborts in well under the stand-in's delay;</li>
     *   <li>G5 the negative control: the same answer, the budget-less overload, and no abort — so
     *       G2/G4 are the budget's doing, not the fixture's. It also pins the NONE delegation.</li>
     * </ul>
     */
    static void catalogReadBudget() throws Exception {
        try (CatalogUpstream upstream = new CatalogUpstream()) {
            upstream.wire = offListing().toString();
            final List<Watched> opened = new ArrayList<Watched>();
            QoderAuth.ConnectionFactory factory = url -> {
                check(QoderAuth.MODELS_URL.equals(url), "the read asks for Qoder's own catalog address: " + url);
                Watched watched = new Watched((HttpURLConnection) URI.create(upstream.base())
                        .toURL().openConnection(Proxy.NO_PROXY));
                synchronized (opened) { opened.add(watched); }
                return watched;
            };

            // G1. Room to spare: the catalog, unchanged timeouts, one attach and one detach.
            TestBudget room = new TestBudget(60000);
            QoderAuth.Catalog catalog = QoderAuth.readCatalog(catalogAccount(), factory, room);
            check(catalog.models.length() == 1 && SERVED.equals(catalog.models.optString(0)),
                    "G1 the catalog is read: " + catalog.models);
            check(QoderCatalog.statedOffKeys(catalog.disabledModels).contains(MODEL),
                    "G1 the disabled key is reported: " + catalog.disabledModels);
            check(!catalog.freeView, "G1 a paid listing is not a free view");
            eq(upstream.requests.get(), 1, "G1 one request");
            eq(room.attaches.get(), 1, "G1 the connection is attached before the read");
            eq(room.detaches.get(), 1, "G1 ... and detached when it ends");
            eq(room.heldCount(), 0, "G1 nothing is left attached");
            Watched first = opened.get(0);
            eq(first.readTimeout, 20000, "G1 with room to spare the read keeps its own 20s");
            eq(first.connectTimeout, 15000, "G1 ... and its own 15s connect");
            check(first.disconnects.get() >= 1, "G1 the read closes the connection it opened");

            // G2. Shorter budget than the answer: stops inside the budget, as a timeout.
            upstream.requests.set(0);
            opened.clear();
            upstream.delayMillis = 1200;
            TestBudget tight = new TestBudget(500);
            long began = System.currentTimeMillis();
            IOException timedOut = failure(() -> QoderAuth.readCatalog(catalogAccount(), factory, tight));
            long took = System.currentTimeMillis() - began;
            upstream.delayMillis = 0;
            check(timedOut.getMessage().contains("timed out"), "G2 a spent budget is a timeout: " + timedOut);
            check(took < 1100, "G2 the read stops inside the budget, took " + took + "ms");
            eq(tight.attaches.get(), 1, "G2 the connection was attached");
            eq(tight.detaches.get(), 1, "G2 ... and detached when the read stopped");
            eq(tight.heldCount(), 0, "G2 nothing is left attached");
            eq(upstream.requests.get(), 1, "G2 one request, asked once");
            check(opened.get(0).readTimeout <= 500,
                    "G2 the socket read timeout is what the budget had left, was " + opened.get(0).readTimeout);

            // G3. Budgets that never open a socket: cancelled, and no time left.
            upstream.requests.set(0);
            opened.clear();
            TestBudget cancelledEarly = new TestBudget(60000);
            cancelledEarly.cancel("client went away");
            IOException cancelled = failure(() -> QoderAuth.readCatalog(catalogAccount(), factory, cancelledEarly));
            check(cancelled.getMessage().contains("canceled") && cancelled.getMessage().contains("client went away"),
                    "G3 a cancelled request reads nothing: " + cancelled);
            IOException noRoom = failure(() -> QoderAuth.readCatalog(catalogAccount(), factory, new TestBudget(0)));
            check(noRoom.getMessage().contains("no time left"), "G3 a budget with no room reads nothing: " + noRoom);
            eq(upstream.requests.get(), 0, "G3 neither opened a connection");
            eq(cancelledEarly.attaches.get(), 0, "G3 nothing was attached");

            // G4. Cancelled while the read is blocked on the socket: the attach is what a cancel
            //     disconnects, so the read aborts long before the stand-in answers.
            upstream.requests.set(0);
            opened.clear();
            upstream.delayMillis = 3000;
            final TestBudget midway = new TestBudget(8000);
            long started = System.currentTimeMillis();
            Thread cancel = new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                midway.cancel("client went away");
            }, "qoder-catalog-cancel");
            cancel.setDaemon(true);
            cancel.start();
            IOException aborted = failure(() -> QoderAuth.readCatalog(catalogAccount(), factory, midway));
            long abortedAfter = System.currentTimeMillis() - started;
            cancel.join(4000);
            upstream.delayMillis = 0;
            check(aborted.getMessage().contains("canceled"), "G4 a cancelled read aborts: " + aborted);
            check(abortedAfter < 2000, "G4 the read does not wait out the stand-in, took " + abortedAfter + "ms");
            eq(midway.attaches.get(), 1, "G4 the connection was attached before the cancel");
            eq(midway.detaches.get(), 1, "G4 ... and detached when it aborted");
            eq(midway.heldCount(), 0, "G4 the cancel took what it held");
            eq(upstream.requests.get(), 1, "G4 one request");

            // G5. Negative control: the same answer over the budget-less overload is not aborted, and
            //     the socket timeouts are the 15s/20s it always used — so G2/G4 are the budget.
            upstream.requests.set(0);
            opened.clear();
            upstream.delayMillis = 1200;
            long unbudgetedBegan = System.currentTimeMillis();
            QoderAuth.Catalog slow = QoderAuth.readCatalog(catalogAccount(), factory);
            long unbudgeted = System.currentTimeMillis() - unbudgetedBegan;
            upstream.delayMillis = 0;
            check(slow.models.length() == 1, "G5 the budget-less overload still reads the catalog");
            check(unbudgeted >= 1100, "G5 without a budget the read waits out the stand-in: " + unbudgeted + "ms");
            eq(opened.get(0).readTimeout, 20000, "G5 ... and keeps the 20s it always had");
            eq(upstream.requests.get(), 1, "G5 one request");
        }
    }

    // ---- the cases ---------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);

        // G. The read on its own, first: an independent fixture, and the only case that never
        //    reaches a gateway.
        catalogReadBudget();

        // A. Control: enough budget, both accounts state it off and both are spent ⇒ still 429.
        //    Without this, "no 429" below could just mean the verdict never worked.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(2, null), upstream)) {
            upstream.listings.put("acc-a", offListing());
            upstream.listings.put("acc-b", offListing());
            upstream.usages.put("device-acc-a", spent());
            upstream.usages.put("device-acc-b", spent());
            gateway.inner.setQoderUsageTimeoutForTests(8000);
            Result r = chat(gateway, "qoder_cn/" + MODEL);
            eq(r.status, 429, "control: off + spent is 429: " + r.body);
            check(r.body.contains("credits are used up"), "control carries Qoder's sentence: " + r.body);
            eq(upstream.models.get(), 2, "control reads every account's listing");
            eq(upstream.usage.get(), 2, "control reads every account's credits");
            eq(upstream.chat.get(), 0, "control never reaches chat");
            eq(upstream.refresh.get(), 0, "control never refreshes");
            noLeak(r);
            onlyQoderHosts(upstream);

            // B. One budget for the whole verdict: the first listing read (1s of a 1.2s budget) leaves
            //    less than the floor the second read needs, so it never starts — and a request with an
            //    unconfirmed account is not a 429. A per-account budget would have read it and judged.
            upstream.models.set(0); upstream.usage.set(0);
            upstream.listingDelayMillis = 1000;
            gateway.inner.setQoderUsageTimeoutForTests(1200);
            long began = System.currentTimeMillis();
            Result shared = chat(gateway, "qoder_cn/" + MODEL);
            long elapsed = System.currentTimeMillis() - began;
            upstream.listingDelayMillis = 0;
            eq(shared.status, 400, "shared budget keeps the old answer: " + shared.body);
            check(shared.body.contains("No available API Key"), "the pre-existing answer: " + shared.body);
            eq(upstream.models.get(), 1, "the second account's listing read never started");
            eq(upstream.usage.get(), 1, "only the first account's credits were read");
            check(elapsed < 8000, "the verdict stays inside its budget, took " + elapsed + "ms");
            untouched(gateway, shared, upstream);
        }

        // C. The request's own budget, not just the read budget: below the floor that starts a read,
        //    nothing is read at all. No read ⇒ no confirmation ⇒ the old answer.
        try (Upstream upstream = new Upstream();
             Gateway gateway = new Gateway(config(2, new JSONObject().put("requestBudgetMs", 1000)), upstream)) {
            upstream.listings.put("acc-a", offListing());
            upstream.listings.put("acc-b", offListing());
            upstream.usages.put("device-acc-a", spent());
            upstream.usages.put("device-acc-b", spent());
            gateway.inner.setQoderUsageTimeoutForTests(8000);
            Result r = chat(gateway, "qoder_cn/" + MODEL);
            eq(r.status, 400, "a request budget under the floor starts no read: " + r.body);
            eq(upstream.models.get(), 0, "no listing read under a 1s request budget");
            eq(upstream.usage.get(), 0, "no credits read under a 1s request budget");
            untouched(gateway, r, upstream);
        }

        // D. The call site passes the budget: a listing that would outlive the request budget is
        //    aborted at the budget (not at its own 15s/20s timeouts), so the call returns while the
        //    stand-in is still holding the response. It must not leave a verdict behind, either:
        //    there is no time left for the credits read, so the answer stays the old one.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(1, null), upstream)) {
            upstream.listings.put("acc-a", offListing());
            upstream.usages.put("device-acc-a", spent());
            upstream.listingDelayMillis = 2400;
            gateway.inner.setQoderUsageTimeoutForTests(2000);
            long began = System.currentTimeMillis();
            Result r = chat(gateway, "qoder_cn/" + MODEL);
            long elapsed = System.currentTimeMillis() - began;
            upstream.listingDelayMillis = 0;
            eq(r.status, 400, "an over-budget read cannot confirm a quota: " + r.body);
            eq(upstream.models.get(), 1, "the read did start, and was over the budget");
            eq(upstream.usage.get(), 0, "no credits read once the budget is gone");
            check(elapsed < 2400, "the read is aborted at the budget, not at its own timeouts: "
                    + elapsed + "ms");
            untouched(gateway, r, upstream);
        }

        // E. A cancelled request judges nothing: no listing read, no credits read, no 429.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(2, null), upstream)) {
            upstream.listings.put("acc-a", offListing());
            upstream.listings.put("acc-b", offListing());
            upstream.usages.put("device-acc-a", spent());
            upstream.usages.put("device-acc-b", spent());
            gateway.inner.setQoderUsageTimeoutForTests(8000);
            RequestContext cancelled = new RequestContext("qoder-budget-test", NetworkPolicy.capture(), 60000);
            cancelled.cancel("test cancellation");
            JSONObject verdict = verdict(gateway.inner, cancelled, "qoder_cn/" + MODEL);
            check(verdict == null, "a cancelled request judges nothing: " + verdict);
            eq(upstream.models.get(), 0, "a cancelled request reads no listing");
            eq(upstream.usage.get(), 0, "a cancelled request reads no credits");
            eq(upstream.chat.get(), 0, "a cancelled request never reaches chat");
            eq(upstream.refresh.get(), 0, "a cancelled request never refreshes");
        }

        // F. Cancelled in the middle of a listing read: the read that is already running finishes, but
        //    nothing after it happens — no credits read, no verdict. The refresh token is untouched.
        try (Upstream upstream = new Upstream(); Gateway gateway = new Gateway(config(1, null), upstream)) {
            upstream.listings.put("acc-a", offListing());
            upstream.usages.put("device-acc-a", spent());
            upstream.listingDelayMillis = 1000;
            gateway.inner.setQoderUsageTimeoutForTests(8000);
            final RequestContext request = new RequestContext("qoder-budget-test", NetworkPolicy.capture(), 60000);
            Thread cancel = new Thread(() -> {
                try { Thread.sleep(250); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                request.cancel("client went away");
            }, "qoder-cancel");
            cancel.setDaemon(true);
            cancel.start();
            JSONObject verdict = verdict(gateway.inner, request, "qoder_cn/" + MODEL);
            cancel.join(4000);
            upstream.listingDelayMillis = 0;
            check(verdict == null, "a cancellation mid-read is no verdict: " + verdict);
            eq(upstream.models.get(), 1, "the read in flight was started before the cancel");
            eq(upstream.usage.get(), 0, "no credits read after the cancel");
            eq(upstream.chat.get(), 0, "no chat after the cancel");
            eq(upstream.refresh.get(), 0, "no refresh after the cancel");
            check(gateway.storedConfig().equals(gateway.initialConfig), "a cancelled probe rewrote the record");
        }

        System.out.println("QoderCatalogBudgetHttpTest OK");
    }
}
