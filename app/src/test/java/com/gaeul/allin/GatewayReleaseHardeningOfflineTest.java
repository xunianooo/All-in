package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Release-audit regression for the parts of the gateway that block a public release:
 * fresh-install secrets, the published default password, configuration save/recovery
 * transactions, and the bounded unauthenticated entry (connections plus OAuth callbacks).
 *
 * Everything runs on loopback with synthetic credentials in a temporary directory; it never
 * touches installed user configuration, real accounts or the network.
 */
public final class GatewayReleaseHardeningOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    // ---------- reflection helpers ----------
    static Object field(Object target, String name) throws Exception {
        Field f = GatewayServer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    static void setField(Object target, String name, Object value) throws Exception {
        Field f = GatewayServer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
    static boolean save(GatewayServer server, JSONObject config) throws Exception {
        return (Boolean) call(server, "saveConfig", new Class<?>[]{JSONObject.class}, config);
    }

    // ---------- HTTP helpers on the existing isolated fixture ----------
    static final class Reply { int status; String body; }
    static Reply call(GatewayServer server, String ip, String path, String method, String body, String key) throws Exception {
        NanoHTTPD.Response response = server.serve(RoutingSetupOfflineTest.request(ip, path, method, body, key));
        Reply reply = new Reply();
        reply.status = response.getStatus().getRequestStatus();
        InputStream data = response.getData();
        if (data != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = data.read(buffer)) != -1) out.write(buffer, 0, n);
            data.close();
            reply.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } else reply.body = "";
        return reply;
    }
    static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        in.close();
        return out.toByteArray();
    }
    static String errorCode(String body) {
        try {
            JSONObject parsed = new JSONObject(body);
            JSONObject error = parsed.optJSONObject("error");
            return error == null ? "" : error.optString("code", "");
        } catch (Exception ignored) { return ""; }
    }

    static File tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toFile();
    }
    static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static GatewayServer fixture(JSONObject config, File configFile) throws Exception {
        GatewayServer server = RoutingSetupOfflineTest.server(config);
        setField(server, "configFile", configFile);
        return server;
    }

    // ================= A: secrets =================
    static void publishedDefaults() throws Exception {
        check(GatewayServer.isPublishedDefaultAuthKey("magpie"), "shipped default is recognised");
        check(GatewayServer.isPublishedDefaultAuthKey(" MAGPIE "), "case and padding do not hide it");
        check(!GatewayServer.isPublishedDefaultAuthKey("magpie2"), "suffixed value is a user key");
        check(!GatewayServer.isPublishedDefaultAuthKey(""), "empty is not a published default");
        check(!GatewayServer.isPublishedDefaultAuthKey(null), "null is not a published default");

        check(!GatewayServer.authKeyAllowsRemoteAccess("magpie"), "published default never allows remote");
        check(!GatewayServer.authKeyAllowsRemoteAccess(""), "empty never allows remote");
        check(!GatewayServer.authKeyAllowsRemoteAccess("short12"), "7 chars is too short for remote");
        check(GatewayServer.authKeyAllowsRemoteAccess("12345678"), "8 chars is the documented minimum");
        check(GatewayServer.authKeyAllowsRemoteAccess("mp-" + "0123456789abcdef0123456789abcdef0123"),
                "generated shape allows remote");

        java.security.SecureRandom random = new java.security.SecureRandom();
        String first = GatewayServer.generateAuthKey(random);
        String second = GatewayServer.generateAuthKey(random);
        check(first.startsWith("mp-"), "generated key is prefixed: " + first);
        check(first.length() == 39, "generated key length: " + first.length());
        check(first.matches("[A-Za-z0-9._~!@#$%^&*+=:-]+"), "generated key uses the accepted charset");
        check(!first.equals(second), "two generated keys differ");
        check(GatewayServer.authKeyAllowsRemoteAccess(first), "generated key allows remote");
        check(!GatewayServer.isPublishedDefaultAuthKey(first), "generated key is not a published default");

        // The shipped asset must be recognised as public, and first run must replace it.
        String asset = read(new File("app/src/main/assets/default_config.json").exists()
                ? new File("app/src/main/assets/default_config.json")
                : new File("src/main/assets/default_config.json"));
        JSONObject shipped = new JSONObject(asset);
        String shippedKey = shipped.getJSONObject("server").optString("authKey", "");
        check(GatewayServer.isPublishedDefaultAuthKey(shippedKey), "asset key is a published default: " + shippedKey);
        check(!GatewayServer.authKeyAllowsRemoteAccess(shippedKey), "asset key cannot authorise remote access");

        JSONObject fresh = new JSONObject(asset);
        check(GatewayServer.applyFirstRunAuthKey(fresh, random), "first run rewrites the key");
        JSONObject freshServer = fresh.getJSONObject("server");
        String generated = freshServer.getString("authKey");
        check(!GatewayServer.isPublishedDefaultAuthKey(generated), "first run key differs from the shipped one");
        check(GatewayServer.authKeyAllowsRemoteAccess(generated), "first run key allows remote");
        check("first-run-random".equals(freshServer.optString("authKeyOrigin")), "origin recorded");
        check(freshServer.optLong("authKeyGeneratedAt", 0) > 0, "generation time recorded");
        check("0.0.0.0".equals(freshServer.optString("host")), "LAN binding setting is preserved");
        check(freshServer.optInt("port", 0) == 3425, "port setting is preserved");
        check(fresh.getJSONArray("providers").length() == shipped.getJSONArray("providers").length(),
                "providers are untouched by key generation");

        // A fresh-install configuration must stay a valid backup payload.
        JSONObject validated = EncryptedConfigBackup.validateConfig(
                fresh.toString().getBytes(StandardCharsets.UTF_8));
        check(validated.getJSONObject("server").getString("authKey").equals(generated),
                "first-run key survives backup schema validation");
        check(validated.getJSONObject("server").optString("authKeyOrigin", "").equals("first-run-random"),
                "first-run marker survives backup schema validation");
        // A restored legacy backup is recognised by the same classifier that gates remote access.
        JSONObject restoredLegacy = new JSONObject("{\"providers\":[],\"server\":{\"authKey\":\"magpie\"}}");
        JSONObject validatedLegacy = EncryptedConfigBackup.validateConfig(
                restoredLegacy.toString().getBytes(StandardCharsets.UTF_8));
        check(GatewayServer.isPublishedDefaultAuthKey(
                        validatedLegacy.getJSONObject("server").getString("authKey")),
                "restored legacy backup is still detected as the published default");
    }

    static void setupStatusFlags() throws Exception {
        File dir = tempDir("hardening-status-");
        JSONObject legacy = new JSONObject("{\"server\":{\"authKey\":\"magpie\"},\"setup\":{\"done\":true},\"providers\":[]}");
        GatewayServer legacyServer = fixture(legacy, new File(dir, "config.json"));
        JSONObject status = (JSONObject) call(legacyServer, "buildSetupStatus", new Class<?>[]{});
        check(status.getBoolean("authKeyIsDefault"), "legacy default flagged");
        check(status.getBoolean("authKeyIsLegacyDefault"), "legacy default flagged explicitly");
        check(status.getBoolean("authKeyMigrationRequired"), "migration required flag");
        check(status.getInt("authKeyMinLengthForRemote") == GatewayServer.MIN_REMOTE_AUTH_KEY_LENGTH,
                "minimum remote length exposed");
        check(status.getBoolean("authKeyIsWeak"), "legacy default counts as weak");
        check(!status.getJSONObject("configRecovery").getBoolean("required"), "healthy config is not in recovery");

        JSONObject strong = new JSONObject("{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},\"providers\":[]}");
        JSONObject strongStatus = (JSONObject) call(fixture(strong, new File(dir, "config.json")),
                "buildSetupStatus", new Class<?>[]{});
        check(!strongStatus.getBoolean("authKeyIsDefault"), "strong key is not default");
        check(!strongStatus.getBoolean("authKeyMigrationRequired"), "strong key needs no migration");
        check(!strongStatus.getBoolean("authKeyIsWeak"), "strong key is not weak");
    }

    static void setupCompletionGates() throws Exception {
        File dir = tempDir("hardening-setup-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"magpie\"},\"setup\":{\"done\":false},\"providers\":[]}");
        GatewayServer legacy = fixture(new JSONObject(read(config)), config);

        Reply keepLegacy = call(legacy, "127.0.0.1", "/api/setup/complete", "POST", "{\"keepCurrent\":true}", null);
        check(keepLegacy.status == 400, "keepCurrent cannot approve the published default, got " + keepLegacy.status);
        check(!new JSONObject(read(config)).getJSONObject("setup").optBoolean("done"), "disk setup stays pending");
        check("magpie".equals(((JSONObject) field(legacy, "configJson")).getJSONObject("server").getString("authKey")),
                "memory key unchanged");

        Reply explicitLegacy = call(legacy, "127.0.0.1", "/api/setup/complete", "POST", "{\"authKey\":\"magpie\"}", null);
        check(explicitLegacy.status == 400, "explicit published default rejected, got " + explicitLegacy.status);
        check(!new JSONObject(read(config)).getJSONObject("setup").optBoolean("done"), "still pending");

        Reply tooShortKeep = call(legacy, "127.0.0.1", "/api/setup/complete", "POST", "{\"keepCurrent\":true}", null);
        check(tooShortKeep.status == 400, "the published default still cannot be kept");

        Reply applied = call(legacy, "127.0.0.1", "/api/setup/complete", "POST", "{\"authKey\":\"fixture-rotated-key\"}", null);
        check(applied.status == 200, "explicit strong key completes setup, got " + applied.status);
        check(new JSONObject(read(config)).getJSONObject("setup").getBoolean("done"), "setup persisted");
        check("fixture-rotated-key".equals(new JSONObject(read(config)).getJSONObject("server").getString("authKey")),
                "rotated key persisted");

        File second = new File(tempDir("hardening-setup-keep-"), "config.json");
        write(second, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":false},\"providers\":[]}");
        GatewayServer strong = fixture(new JSONObject(read(second)), second);
        Reply keepStrong = call(strong, "127.0.0.1", "/api/setup/complete", "POST", "{\"keepCurrent\":true}", null);
        check(keepStrong.status == 200, "keepCurrent still works for a strong key, got " + keepStrong.status);
        check(new JSONObject(read(second)).getJSONObject("setup").getBoolean("done"), "kept setup persisted");

        File third = new File(tempDir("hardening-setup-empty-"), "config.json");
        write(third, "{\"server\":{},\"providers\":[]}");
        GatewayServer empty = fixture(new JSONObject(read(third)), third);
        Reply keepEmpty = call(empty, "127.0.0.1", "/api/setup/complete", "POST", "{\"keepCurrent\":true}", null);
        check(keepEmpty.status == 400, "an empty key cannot complete setup, got " + keepEmpty.status);
        check(!new JSONObject(read(third)).has("setup"), "no setup state written for an empty key");
    }

    static void serverKeyEndpointRejectsPublishedDefault() throws Exception {
        File dir = tempDir("hardening-server-key-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}");
        GatewayServer server = fixture(new JSONObject(read(config)), config);
        Reply rejected = call(server, "127.0.0.1", "/api/server-key", "POST", "{\"authKey\":\"magpie\"}", null);
        check(rejected.status == 400, "server-key refuses the published default, got " + rejected.status);
        check("fixture-strong-key".equals(new JSONObject(read(config)).getJSONObject("server").getString("authKey")),
                "disk keeps the previous key");
        check("fixture-strong-key".equals(((JSONObject) field(server, "configJson")).getJSONObject("server").getString("authKey")),
                "memory keeps the previous key");
        Reply accepted = call(server, "127.0.0.1", "/api/server-key", "POST", "{\"authKey\":\"fixture-second-key\"}", null);
        check(accepted.status == 200, "a strong key is still accepted, got " + accepted.status);
    }

    static void remotePublishedDefaultIsRefused() throws Exception {
        File dir = tempDir("hardening-remote-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"magpie\"},\"setup\":{\"done\":true},"
                + "\"providers\":[{\"id\":\"alpha\",\"protocol\":\"openai\",\"keys\":[\"synthetic\"],\"models\":[\"known\"]}]}");
        GatewayServer legacy = fixture(new JSONObject(read(config)), config);

        Reply remoteModels = call(legacy, "192.0.2.9", "/v1/models", "GET", "", "magpie");
        check(remoteModels.status == 403, "remote inference with the published default is refused, got " + remoteModels.status);
        check("legacy_default_auth_key".equals(errorCode(remoteModels.body)),
                "refusal carries a migration code: " + remoteModels.body);
        check(remoteModels.body.contains("hint"), "refusal carries an actionable hint");

        Reply remoteChat = call(legacy, "192.0.2.9", "/v1/chat/completions", "POST",
                "{\"model\":\"alpha/known\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "magpie");
        check(remoteChat.status == 403, "remote chat is refused too, got " + remoteChat.status);

        Reply remoteManagement = call(legacy, "192.0.2.9", "/api/routing", "GET", "", GatewayManagementCredential.get());
        check(remoteManagement.status == 200,
                "LAN management with the private credential still works, got " + remoteManagement.status);

        Reply localModels = call(legacy, "127.0.0.1", "/v1/models", "GET", "", "magpie");
        check(localModels.status != 403, "loopback keeps working so the key can be rotated, got " + localModels.status);

        File strongFile = new File(tempDir("hardening-remote-strong-"), "config.json");
        write(strongFile, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},"
                + "\"providers\":[{\"id\":\"alpha\",\"protocol\":\"openai\",\"keys\":[\"synthetic\"],\"models\":[\"known\"]}]}");
        GatewayServer strong = fixture(new JSONObject(read(strongFile)), strongFile);
        Reply wrongKey = call(strong, "192.0.2.9", "/v1/models", "GET", "", "not-the-key");
        check(wrongKey.status == 401, "a wrong key stays a plain 401, got " + wrongKey.status);
        check(!"legacy_default_auth_key".equals(errorCode(wrongKey.body)), "no migration code for a wrong key");
        Reply rightKey = call(strong, "192.0.2.9", "/v1/models", "GET", "", "fixture-strong-key");
        check(rightKey.status != 401 && rightKey.status != 403,
                "the configured key authorises remote inference, got " + rightKey.status);
    }

    // ================= C: bounded entry =================
    static void connectionLimits() throws Exception {
        check(GatewayServer.connectionLimit(null) == GatewayServer.DEFAULT_MAX_CONCURRENT_CONNECTIONS,
                "missing config uses the default limit");
        check(GatewayServer.connectionLimit(new JSONObject("{\"server\":{}}"))
                == GatewayServer.DEFAULT_MAX_CONCURRENT_CONNECTIONS, "missing field uses the default limit");
        check(GatewayServer.connectionLimit(new JSONObject("{\"server\":{\"maxConnections\":20}}")) == 20,
                "explicit limit honoured");
        check(GatewayServer.connectionLimit(new JSONObject("{\"server\":{\"maxConnections\":1}}"))
                == GatewayServer.MIN_CONCURRENT_CONNECTIONS, "low limit clamped up");
        check(GatewayServer.connectionLimit(new JSONObject("{\"server\":{\"maxConnections\":100000}}"))
                == GatewayServer.MAX_CONCURRENT_CONNECTIONS, "high limit clamped down");
        check(GatewayServer.connectionLimit(new JSONObject("{\"server\":{\"maxConnections\":\"nope\"}}"))
                == GatewayServer.DEFAULT_MAX_CONCURRENT_CONNECTIONS, "garbage limit falls back");
        check(GatewayServer.DEFAULT_MAX_CONCURRENT_CONNECTIONS >= 64,
                "default leaves room for concurrent SSE clients");
    }

    static final class MiniServer extends NanoHTTPD {
        MiniServer() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
        }
    }

    static final class SocketPair implements AutoCloseable {
        final ServerSocket listener;
        final Socket client;
        final Socket accepted;
        SocketPair() throws Exception {
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            client = new Socket("127.0.0.1", listener.getLocalPort());
            client.setSoTimeout(2000);
            accepted = listener.accept();
        }
        NanoHTTPD.ClientHandler handler(MiniServer server) throws Exception {
            return server.new ClientHandler(accepted.getInputStream(), accepted);
        }
        @Override public void close() throws Exception {
            try { client.close(); } catch (Exception ignored) { }
            try { accepted.close(); } catch (Exception ignored) { }
            try { listener.close(); } catch (Exception ignored) { }
        }
    }

    static void boundedRunnerAdmission() throws Exception {
        MiniServer owner = new MiniServer();
        GatewayServer.BoundedAsyncRunner runner = new GatewayServer.BoundedAsyncRunner(2);
        check(runner.limit() == 2, "limit reported");
        runner.exec(null);
        check(runner.active() == 0, "null handler ignored");

        List<SocketPair> pairs = new ArrayList<>();
        List<NanoHTTPD.ClientHandler> handlers = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                SocketPair pair = new SocketPair();
                pairs.add(pair);
                NanoHTTPD.ClientHandler handler = pair.handler(owner);
                handlers.add(handler);
                runner.exec(handler);
            }
            SocketPair overflowPair = new SocketPair();
            pairs.add(overflowPair);
            NanoHTTPD.ClientHandler overflow = overflowPair.handler(owner);
            handlers.add(overflow);
            runner.exec(overflow);

            long deadline = System.nanoTime() + 2000000000L;
            while (runner.rejected() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            check(runner.rejected() == 1, "the third connection is rejected, got " + runner.rejected());
            check(runner.active() <= 2, "active connections never exceed the limit, got " + runner.active());
            check(runner.serviced() == 2, "only admitted connections are serviced, got " + runner.serviced());
            check(overflowPair.accepted.isClosed(), "the rejected connection is closed, not leaked");
            check(!pairs.get(0).accepted.isClosed(), "admitted connections stay open");

            runner.closed(handlers.get(0));
            check(runner.active() == 1, "closing frees a slot, got " + runner.active());
            SocketPair late = new SocketPair();
            pairs.add(late);
            NanoHTTPD.ClientHandler lateHandler = late.handler(owner);
            handlers.add(lateHandler);
            runner.exec(lateHandler);
            check(runner.rejected() == 1, "a freed slot admits a new connection");
            check(runner.serviced() == 3, "serviced counter advanced");

            runner.closeAll();
            check(runner.active() == 0, "closeAll drops every tracked connection");
            check(late.accepted.isClosed(), "closeAll closes admitted sockets");
        } finally {
            runner.closeAll();
            for (SocketPair pair : pairs) pair.close();
        }
    }

    static void boundedRunnerGuardsRealListener() throws Exception {
        MiniServer server = new MiniServer();
        GatewayServer.BoundedAsyncRunner runner = new GatewayServer.BoundedAsyncRunner(2);
        server.setAsyncRunner(runner);
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        List<Socket> clients = new ArrayList<>();
        try {
            int port = server.getListeningPort();
            check(port > 0, "fixture listener started");
            for (int i = 0; i < 4; i++) {
                Socket client = new Socket("127.0.0.1", port);
                client.setSoTimeout(2000);
                clients.add(client);
            }
            long deadline = System.nanoTime() + 5000000000L;
            while (runner.active() + runner.rejected() < 4 && System.nanoTime() < deadline) Thread.sleep(10);
            check(runner.active() <= 2, "live listener respects the cap, got " + runner.active());
            check(runner.rejected() >= 2, "excess connections are rejected, got " + runner.rejected());

            int closedByServer = 0;
            for (Socket client : clients) {
                try { if (client.getInputStream().read() < 0) closedByServer++; }
                catch (SocketTimeoutException stillOpen) { }
                catch (java.io.IOException broken) { closedByServer++; }
            }
            check(closedByServer >= 2, "rejected sockets are closed, observed " + closedByServer);

            // Free the admitted slots, then prove the accept loop is still alive.
            runner.closeAll();
            deadline = System.nanoTime() + 5000000000L;
            while (runner.active() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
            check(runner.active() == 0, "admitted connections finished after closeAll");
            for (Socket client : clients) { try { client.close(); } catch (Exception ignored) { } }

            Socket fresh = new Socket("127.0.0.1", port);
            fresh.setSoTimeout(3000);
            try {
                OutputStream out = fresh.getOutputStream();
                out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                InputStream in = fresh.getInputStream();
                int b;
                while ((b = in.read()) != -1) response.write(b);
                String text = new String(response.toByteArray(), StandardCharsets.UTF_8);
                check(text.startsWith("HTTP/1.1 200"), "accept loop survived the rejections: " + text);
                check(text.endsWith("ok"), "response body delivered");
            } finally { try { fresh.close(); } catch (Exception ignored) { } }
        } finally {
            for (Socket client : clients) { try { client.close(); } catch (Exception ignored) { } }
            runner.closeAll();
            server.stop();
        }
    }

    // ================= C: OAuth callback helpers =================
    static void callbackSocketIsLoopbackOnly() throws Exception {
        ServerSocket first = GatewayServer.openLoopbackCallbackPort(0);
        try {
            check(first.getInetAddress().isLoopbackAddress(), "callback listener binds loopback only");
            int port = first.getLocalPort();
            check(port > 0, "ephemeral port assigned");
            boolean refused = false;
            try { ServerSocket second = GatewayServer.openLoopbackCallbackPort(port); second.close(); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused, "the same port cannot be bound twice");
        } finally { first.close(); }
        check(first.isClosed(), "listener socket closed on release");
    }

    static void callbackRequestLineIsBounded() throws Exception {
        ServerSocket listener = GatewayServer.openLoopbackCallbackPort(0);
        try {
            int port = listener.getLocalPort();
            Socket client = new Socket("127.0.0.1", port);
            client.setSoTimeout(2000);
            Socket server = listener.accept();
            try {
                client.getOutputStream().write(("GET /oauth-callback?code=abc%3D&state=s1 HTTP/1.1\r\n"
                        + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                String line = GatewayServer.readCallbackRequestLine(server);
                check(line != null && line.startsWith("GET /oauth-callback?code="), "request line read: " + line);
                Map<String, String> query = GatewayServer.callbackQuery(line);
                check("abc=".equals(query.get("code")), "percent-decoded code keeps the equals sign: " + query);
                check("s1".equals(query.get("state")), "state parsed");
                check(GatewayServer.callbackQuery(null).isEmpty(), "null request line has no parameters");
                check(GatewayServer.callbackQuery("GET /oauth-callback HTTP/1.1").isEmpty(), "no query, no parameters");
            } finally {
                client.close();
                server.close();
            }
        } finally { listener.close(); }

        // A client that never sends a line must not hold the handler forever.
        ServerSocket silent = GatewayServer.openLoopbackCallbackPort(0);
        try {
            Socket client = new Socket("127.0.0.1", silent.getLocalPort());
            client.setSoTimeout(2000);
            Socket server = silent.accept();
            try {
                long begin = System.nanoTime();
                boolean timedOut = false;
                try { GatewayServer.readCallbackRequestLine(server); }
                catch (SocketTimeoutException expected) { timedOut = true; }
                long elapsedMillis = (System.nanoTime() - begin) / 1000000L;
                check(timedOut, "a silent callback client times out");
                check(elapsedMillis >= GatewayServer.CALLBACK_READ_TIMEOUT_MILLIS - 500
                        && elapsedMillis < GatewayServer.CALLBACK_READ_TIMEOUT_MILLIS + 5000,
                        "read timeout bounded at " + GatewayServer.CALLBACK_READ_TIMEOUT_MILLIS + " ms, was " + elapsedMillis);
            } finally {
                client.close();
                server.close();
            }
        } finally { silent.close(); }

        // An oversized request line is rejected instead of buffered without bound.
        ServerSocket flood = GatewayServer.openLoopbackCallbackPort(0);
        try {
            Socket client = new Socket("127.0.0.1", flood.getLocalPort());
            client.setSoTimeout(2000);
            Socket server = flood.accept();
            try {
                StringBuilder huge = new StringBuilder("GET /");
                for (int i = 0; i < GatewayServer.CALLBACK_MAX_REQUEST_LINE + 16; i++) huge.append('a');
                client.getOutputStream().write(huge.toString().getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                boolean rejected = false;
                try { GatewayServer.readCallbackRequestLine(server); }
                catch (java.io.IOException expected) { rejected = true; }
                check(rejected, "oversized request line rejected");
            } finally {
                client.close();
                server.close();
            }
        } finally { flood.close(); }
    }

    static void callbackResponseClosesSocket() throws Exception {
        ServerSocket listener = GatewayServer.openLoopbackCallbackPort(0);
        try {
            Socket client = new Socket("127.0.0.1", listener.getLocalPort());
            client.setSoTimeout(3000);
            Socket server = listener.accept();
            GatewayServer.respondAndCloseCallback(server, "<html>done</html>");
            check(server.isClosed(), "callback socket closed after the response");
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = client.getInputStream();
            int b;
            while ((b = in.read()) != -1) received.write(b);
            String text = new String(received.toByteArray(), StandardCharsets.UTF_8);
            check(text.startsWith("HTTP/1.1 200 OK"), "callback response status: " + text);
            check(text.contains("Content-Length: 17"), "content length matches the body");
            check(text.endsWith("<html>done</html>"), "callback body written");
            check(text.contains("Connection: close"), "connection is closed after the callback");
            client.close();
        } finally { listener.close(); }
    }

    static void callbackPageEscapesDynamicText() throws Exception {
        check("".equals(GatewayServer.escapeCallbackText(null)), "null renders empty");
        check("&lt;a&gt;&amp;&quot;&#39;".equals(GatewayServer.escapeCallbackText("<a>&\"'")),
                "markup characters escaped");
        String page = GatewayServer.escapeCallbackText("<script>alert(1)</script>");
        check(!page.contains("<script>"), "script tags neutralised");
        check(page.contains("&lt;script&gt;"), "script tags escaped, not dropped");
    }

    // ================= D: configuration transaction and recovery =================
    static void corruptConfigEntersRecovery() throws Exception {
        File dir = tempDir("hardening-recovery-");
        File config = new File(dir, "config.json");
        String original = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[";
        write(config, original);
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);

        call(server, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture corruption"));
        check((Boolean) field(server, "configRecoveryRequired"), "recovery flag set");
        String rescueName = (String) field(server, "configRecoveryRescueFile");
        check(rescueName != null && rescueName.startsWith("config.corrupt-"), "rescue copy recorded: " + rescueName);
        File rescue = new File(dir, rescueName);
        check(rescue.isFile(), "rescue copy written");
        check(original.equals(read(rescue)), "rescue copy is byte-identical to the damaged file");
        check(original.equals(read(config)), "damaged file is left in place");
        check(((JSONObject) field(server, "configJson")).length() == 0, "memory holds an empty fail-closed config");

        JSONObject mutation = new JSONObject("{\"server\":{\"authKey\":\"attacker\"},\"providers\":[]}");
        check(!save(server, mutation), "regular save is refused during recovery");
        check(original.equals(read(config)), "damaged file not overwritten by a regular save");
        check(original.equals(read(rescue)), "rescue copy not overwritten by a regular save");
        boolean strictRefused = false;
        try { call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, mutation); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            strictRefused = wrapped.getCause() instanceof java.io.IOException;
        }
        check(strictRefused, "strict persistence refused during recovery");
        check(original.equals(read(config)), "damaged file not overwritten by strict persistence");
    }

    /** A post-commit side effect must never turn a persisted configuration into a failure. */
    static void postCommitFailureIsNotASaveFailure() throws Exception {
        File dir = tempDir("hardening-postcommit-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}");
        GatewayServer server = fixture(new JSONObject(read(config)), config);
        JSONObject next = new JSONObject(read(config));
        next.put("schemaVersion", 4);
        next.put("providers", new JSONArray("[{\"id\":\"fixture-prov\",\"protocol\":\"openai\",\"keys\":[\"synthetic\"]}]"));

        GatewayServer.setPostCommitHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected post-commit failure"); }
        });
        boolean saved;
        try {
            saved = save(server, next);
        } finally {
            GatewayServer.setPostCommitHookForTests(null);
        }
        check(saved, "a persisted save reports success even when a later effect throws");
        JSONObject diskNow = new JSONObject(read(config));
        check(diskNow.getJSONArray("providers").length() == 1, "the configuration really reached disk");
        check("fixture-prov".equals(diskNow.getJSONArray("providers").getJSONObject(0).optString("id")),
                "persisted provider matches");
        check(((JSONObject) field(server, "configJson")).getJSONArray("providers").length() == 1,
                "memory keeps the persisted configuration (no rollback)");
        String degraded = (String) field(server, "lastConfigPostCommitError");
        check(degraded != null && degraded.contains("testHook"), "degraded effect recorded: " + degraded);
        check(!degraded.contains("fixture"), "diagnostic holds no credential material");

        // A healthy save clears the diagnostic again.
        GatewayServer.setPostCommitHookForTests(null);
        check(save(server, new JSONObject(read(config))), "second save succeeds");
        check("".equals((String) field(server, "lastConfigPostCommitError")), "diagnostic cleared");
    }

    /**
     * Strict persistence must judge the durable write only. Before this fix an effect that threw
     * after the atomic replacement surfaced as {@code IOException("config persistence failed")},
     * so an ingestion that was already on disk was reported as lost (and retried).
     */
    static void strictPostCommitFailureIsNotASaveFailure() throws Exception {
        File dir = tempDir("hardening-strict-postcommit-");
        File config = new File(dir, "config.json");
        String original = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}";
        write(config, original);
        GatewayServer server = fixture(new JSONObject(original), config);
        JSONObject next = new JSONObject(original);
        next.put("schemaVersion", 4);
        next.put("providers", new JSONArray(
                "[{\"id\":\"fixture-strict\",\"protocol\":\"openai\",\"keys\":[\"synthetic\"]}]"));

        GatewayServer.setPostCommitHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected post-commit failure"); }
        });
        boolean reportedFailure = false;
        try {
            call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, next);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            reportedFailure = wrapped.getCause() instanceof java.io.IOException;
        } finally {
            GatewayServer.setPostCommitHookForTests(null);
        }
        check(!reportedFailure, "a committed strict save must not report an IOException");
        JSONObject diskNow = new JSONObject(read(config));
        check(diskNow.getJSONArray("providers").length() == 1, "the strict save really reached disk");
        check("fixture-strict".equals(diskNow.getJSONArray("providers").getJSONObject(0).optString("id")),
                "the persisted provider is the one that was saved");
        JSONObject published = (JSONObject) field(server, "configJson");
        check(published.getJSONArray("providers").length() == 1, "memory follows the durable configuration");
        check(published.optInt("schemaVersion", 0) == 4, "memory carries the new snapshot");
        String degraded = (String) field(server, "lastConfigPostCommitError");
        check(degraded != null && degraded.contains("testHook"), "the degraded effect is recorded: " + degraded);
        check(!degraded.contains("synthetic"), "the diagnostic holds no credential material");
        check(Boolean.FALSE.equals(field(server, "configRecoveryRequired")),
                "a degraded effect is not a recovery state");

        // A healthy strict save clears the diagnostic again.
        call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, new JSONObject(read(config)));
        check("".equals((String) field(server, "lastConfigPostCommitError")), "diagnostic cleared");
    }

    /**
     * Negative control for the fix above: a strict save that never reached the disk must still be a
     * failure, and nothing may be published in memory or written on disk.
     */
    static void strictWriteFailureIsStillAFailure() throws Exception {
        File dir = tempDir("hardening-strict-write-");
        File config = new File(dir, "config.json");
        String original = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}";
        write(config, original);
        GatewayServer server = fixture(new JSONObject(original), config);
        JSONObject attempted = new JSONObject(original);
        attempted.put("schemaVersion", 4);
        attempted.put("providers", new JSONArray(
                "[{\"id\":\"fixture-unwritten\",\"protocol\":\"openai\",\"keys\":[\"synthetic\"]}]"));

        // Injected write failure: the parent directory of the target does not exist.
        setField(server, "configFile", new File(new File(dir, "missing"), "config.json"));
        boolean reportedFailure = false;
        try {
            call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, attempted);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            reportedFailure = wrapped.getCause() instanceof java.io.IOException;
        }
        check(reportedFailure, "a strict save that never reached the disk still fails");
        check(original.equals(read(config)), "the durable file is unchanged");
        JSONObject published = (JSONObject) field(server, "configJson");
        check(published.optJSONArray("providers").length() == 0, "memory does not adopt an unpersisted snapshot");
        check(published.optInt("schemaVersion", 0) == 0, "memory keeps the previous snapshot");
        check(Boolean.FALSE.equals(field(server, "configRecoveryRequired")),
                "a write failure alone is not a recovery state");

        // The network restriction is still enforced before any write.
        setField(server, "configFile", config);
        JSONObject invalid = new JSONObject(original);
        invalid.put("network", new JSONArray());
        boolean refused = false;
        try {
            call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, invalid);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            refused = wrapped.getCause() instanceof java.io.IOException;
        }
        check(refused, "an invalid network member is still refused before the write");
        check(original.equals(read(config)), "nothing was written for the invalid network configuration");
    }

    /** A fixture that starts unproxied, so "the old policy is still in force" is observable. */
    static GatewayServer unproxiedFixture(File config, String durable) throws Exception {
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);
        NetworkPolicy.configure(new JSONObject().put("mode", "direct"));
        check("direct".equals(NetworkPolicy.current().mode), "fixture starts unproxied");
        return server;
    }

    static boolean nonLoopbackEgressRefused() throws Exception {
        try {
            NetworkPolicy.current().openHttp("http://example.invalid/v1/models");
            return false;
        } catch (java.io.IOException expected) { return true; }
    }

    /**
     * A committed policy whose publication fails may not leave the replaced policy in force.
     *
     * <p>The fixture runs unproxied and then commits an {@code http_proxy} policy while installing
     * that policy fails. The write did reach disk, so the save must not be reported as a failure;
     * but keeping {@code direct} in force would route every new request around the proxy the user
     * just saved, which is the silent bypass the task forbids. The policy must end up blocked, with
     * the local control plane (loopback) still reachable.
     */
    static void networkPublishFailureFailsClosedOnSave() throws Exception {
        File dir = tempDir("hardening-netfail-");
        File config = new File(dir, "config.json");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},"
                + "\"network\":{\"mode\":\"direct\"},\"providers\":[]}";
        GatewayServer server = unproxiedFixture(config, durable);
        NetworkPolicy.Snapshot before = NetworkPolicy.current();

        JSONObject next = new JSONObject(durable);
        next.put("schemaVersion", 4);
        next.put("network", new JSONObject("{\"mode\":\"http_proxy\",\"host\":\"127.0.0.1\",\"port\":3128}"));
        GatewayServer.setNetworkPublishHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected network publish failure"); }
        });
        boolean saved;
        try {
            saved = save(server, next);
        } finally {
            GatewayServer.setNetworkPublishHookForTests(null);
        }

        check(saved, "a committed write is still reported as saved");
        JSONObject diskNow = new JSONObject(read(config));
        check("http_proxy".equals(diskNow.getJSONObject("network").optString("mode")),
                "the committed proxy policy is what reached disk: " + diskNow.optJSONObject("network"));
        check(diskNow.getJSONObject("network").optInt("port", 0) == 3128, "the committed proxy port reached disk");
        check(NetworkPolicy.current() != before, "the replaced policy is not left installed");
        check("blocked".equals(NetworkPolicy.current().mode),
                "publication failure falls back to blocked, got " + NetworkPolicy.current().mode);
        check(nonLoopbackEgressRefused(), "the fallback refuses non-loopback egress");
        NetworkPolicy.current().openHttp("http://127.0.0.1:3425/api/status");

        String degraded = (String) field(server, "lastConfigPostCommitError");
        check(degraded != null && degraded.contains("network("), "the degraded step is recorded: " + degraded);
        check(!degraded.contains("injected"), "no failure message text is exposed: " + degraded);
        check(!degraded.contains("3128"), "no policy detail leaks into the diagnostic: " + degraded);
        JSONObject memory = (JSONObject) field(server, "configJson");
        check(memory.getJSONObject("network").optInt("port", 0) == 3128,
                "memory still follows the committed configuration");
        check(Boolean.FALSE.equals(field(server, "configRecoveryRequired")),
                "a publication failure is not a recovery state");
    }

    /**
     * Same guarantee for the strict entry point, plus recovery: once publication succeeds the
     * blocked fallback is replaced by the committed policy again.
     */
    static void networkPublishFailureFailsClosedOnStrictSave() throws Exception {
        File dir = tempDir("hardening-netfail-strict-");
        File config = new File(dir, "config.json");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},"
                + "\"network\":{\"mode\":\"direct\"},\"providers\":[]}";
        GatewayServer server = unproxiedFixture(config, durable);

        JSONObject next = new JSONObject(durable);
        next.put("network", new JSONObject("{\"mode\":\"http_proxy\",\"host\":\"127.0.0.1\",\"port\":3129}"));
        GatewayServer.setNetworkPublishHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected network publish failure"); }
        });
        boolean reportedFailure = false;
        try {
            call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, next);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            reportedFailure = wrapped.getCause() instanceof java.io.IOException;
        } finally {
            GatewayServer.setNetworkPublishHookForTests(null);
        }
        check(!reportedFailure, "a committed strict save must not report an IOException");
        check("http_proxy".equals(new JSONObject(read(config)).getJSONObject("network").optString("mode")),
                "the strict save reached disk");
        check("blocked".equals(NetworkPolicy.current().mode),
                "the strict entry point fails closed too, got " + NetworkPolicy.current().mode);
        check(nonLoopbackEgressRefused(), "blocked fallback refuses non-loopback egress");

        // The observable state: /api/status names the policy that is actually in force.
        setField(server, "totalTokens", new java.util.concurrent.atomic.AtomicLong());
        Reply statusReply = call(server, "127.0.0.1", "/api/status", "GET", "", null);
        check(statusReply.status == 200, "/api/status reachable, got " + statusReply.status);
        JSONObject statusJson = new JSONObject(statusReply.body);
        check("blocked".equals(statusJson.optString("networkEffectiveMode")),
                "status reports the effective policy: " + statusJson.optString("networkEffectiveMode"));
        // The configured (intended) policy is still what the user saved.
        JSONObject networkPage = new JSONObject(
                call(server, "127.0.0.1", "/api/network", "GET", "", null).body);
        check("http_proxy".equals(networkPage.getJSONObject("network").optString("mode")),
                "the intended policy is unchanged: " + networkPage);

        // Recovery: the next successful publication replaces the blocked fallback.
        call(server, "persistConfigStrict", new Class<?>[]{JSONObject.class}, new JSONObject(read(config)));
        check("http_proxy".equals(NetworkPolicy.current().mode),
                "a successful publish restores the committed policy, got " + NetworkPolicy.current().mode);
        check("".equals((String) field(server, "lastConfigPostCommitError")), "diagnostic cleared");
        check(!nonLoopbackEgressRefused(), "the committed policy is in force again");
        NetworkPolicy.configure(null);
    }

    /**
     * Negative control for over-blocking: an unrelated degraded effect must not block the network,
     * so the fail-closed fallback stays specific to the policy that could not be published.
     */
    static void nonNetworkPostCommitFailureKeepsCommittedPolicy() throws Exception {
        File dir = tempDir("hardening-netok-");
        File config = new File(dir, "config.json");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},"
                + "\"network\":{\"mode\":\"direct\"},\"providers\":[]}";
        GatewayServer server = unproxiedFixture(config, durable);

        JSONObject next = new JSONObject(durable);
        next.put("network", new JSONObject("{\"mode\":\"direct\"}"));
        GatewayServer.setPostCommitHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected post-commit failure"); }
        });
        boolean saved;
        try {
            saved = save(server, next);
        } finally {
            GatewayServer.setPostCommitHookForTests(null);
        }
        check(saved, "the save succeeded");
        check("direct".equals(NetworkPolicy.current().mode),
                "a later degraded effect does not block the published policy, got " + NetworkPolicy.current().mode);
        check(!nonLoopbackEgressRefused(), "the committed policy stays in force");
        String degraded = (String) field(server, "lastConfigPostCommitError");
        check(degraded != null && degraded.contains("testHook"), "only the injected step degraded: " + degraded);
        check(!degraded.contains("network"), "the network step is not blamed: " + degraded);
        NetworkPolicy.configure(null);
    }

    /**
     * A failed write must still keep already-consumed one-time credentials, or the account would
     * be logged out even though upstream invalidated the old value.
     */
    static void failedWritePreservesRotatedCredentials() throws Exception {
        File dir = tempDir("hardening-rotate-");
        File config = new File(dir, "config.json");
        // The account carries an email: since this round a merge needs proven identity, and an
        // entry with no identity evidence is refused on purpose (see noIdentityEvidenceRefusesMerge).
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"codex\","
                + "\"protocol\":\"openai\",\"baseUrl\":\"https://old.invalid\",\"keys\":[],"
                + "\"accounts\":[{\"id\":\"acc_1\",\"email\":\"a@example.invalid\","
                + "\"refresh\":\"old-refresh\",\"access\":\"old-access\","
                + "\"expires\":1000,\"name\":\"fixture\"}]}]}";
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);

        JSONObject attempted = new JSONObject(durable);
        JSONObject account = attempted.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        account.put("refresh", "rotated-refresh");
        account.put("access", "rotated-access");
        account.put("expires", 2000);
        attempted.getJSONArray("providers").getJSONObject(0).put("baseUrl", "https://unpersisted.invalid");

        File missing = new File(new File(dir, "missing"), "config.json");
        setField(server, "configFile", missing);
        setField(server, "configJson", attempted);
        check(!save(server, attempted), "a write to a missing directory fails");

        JSONObject memory = (JSONObject) field(server, "configJson");
        JSONObject memoryAccount = memory.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("rotated-refresh".equals(memoryAccount.optString("refresh")),
                "the rotated refresh token survives in memory: " + memoryAccount);
        check("rotated-access".equals(memoryAccount.optString("access")), "the rotated access token survives");
        check(memoryAccount.optLong("expires", 0) == 2000, "the new expiry survives");

        // The durable copy is authoritative for everything that is not a rotated credential.
        File second = new File(tempDir("hardening-rotate2-"), "config.json");
        write(second, durable);
        setField(server, "configFile", second);
        JSONObject attempted2 = new JSONObject(durable);
        JSONObject account2 = attempted2.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        account2.put("refresh", "rotated-refresh-2");
        attempted2.getJSONArray("providers").getJSONObject(0).put("baseUrl", "https://unpersisted.invalid");
        // Pre-write rejection path: an invalid network member is refused before any write.
        attempted2.put("network", new JSONArray());
        setField(server, "configJson", attempted2);
        check(!save(server, attempted2), "invalid network config is refused");
        JSONObject afterReject = (JSONObject) field(server, "configJson");
        check("https://old.invalid".equals(afterReject.getJSONArray("providers").getJSONObject(0)
                        .optString("baseUrl")),
                "unpersisted non-credential change is rolled back");
        check("rotated-refresh-2".equals(afterReject.getJSONArray("providers").getJSONObject(0)
                        .getJSONArray("accounts").getJSONObject(0).optString("refresh")),
                "rotated credential carried across the rollback");
        check(durable.equals(read(second)), "disk was never modified");

        // A failed write with no durable copy keeps memory instead of inventing a rollback.
        GatewayServer orphan = fixture(new JSONObject(durable), new File(new File(dir, "none"), "config.json"));
        JSONObject orphanConfig = (JSONObject) field(orphan, "configJson");
        orphanConfig.put("schemaVersion", 4);
        check(!save(orphan, orphanConfig), "orphan save fails");
        check((Boolean) field(orphan, "configRecoveryRequired") == Boolean.FALSE, "no false recovery state");
    }

    /** Ingestion helpers must report failure instead of an account id when the write fails. */
    static void accountAddFailureIsReported() throws Exception {
        check(!GatewayServer.accountAddSucceeded(null), "null id is a failure");
        check(!GatewayServer.accountAddSucceeded(""), "empty id is a failure");
        check(!GatewayServer.accountAddSucceeded("err"), "err id is a failure");
        check(!GatewayServer.accountAddSucceeded("zc_err"), "zc_err id is a failure");
        check(!GatewayServer.accountAddSucceeded("qd_err"), "qd_err id is a failure");
        check(!GatewayServer.accountAddSucceeded("wb_err"), "wb_err id is a failure");
        check(GatewayServer.accountAddSucceeded("acc_123"), "real id is a success");

        File dir = tempDir("hardening-add-");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}";
        File config = new File(dir, "config.json");
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);
        // No durable target: the add path cannot persist and must not hand back a usable id.
        setField(server, "configFile", new File(new File(dir, "missing"), "config.json"));
        Method add = GatewayServer.class.getDeclaredMethod("addProviderAccount", String.class, String.class, String.class);
        add.setAccessible(true);
        String id = (String) add.invoke(server, "fixture-prov", "fixture", "synthetic-token");
        check(!GatewayServer.accountAddSucceeded(id), "a failed add returns a failure marker, got " + id);
        check(durable.equals(read(config)), "the real configuration file is untouched");

        // With a working target the same helper still succeeds.
        GatewayServer healthy = fixture(new JSONObject(durable), config);
        String ok = (String) add.invoke(healthy, "fixture-prov", "fixture", "synthetic-token");
        check(GatewayServer.accountAddSucceeded(ok), "a successful add returns an id, got " + ok);
        check(new JSONObject(read(config)).getJSONArray("providers").length() == 1, "account persisted");
    }

    /** Antigravity send outcomes: cancelled / unknown execution / provably-not-sent stay apart. */
    static void antigravitySendVerdictClassifiesConservatively() throws Exception {
        // Wrote bytes, not cancelled: unknown execution, no resend, no settled upstream verdict.
        JSONObject unknown = GatewayServer.antigravitySendVerdict(true, false,
                new java.net.SocketTimeoutException("fixture stall"));
        check(unknown.getInt("status") == 502, "unknown execution answers 502");
        check(unknown.getBoolean("executionUnknown"), "execution is flagged unknown");
        check(unknown.getBoolean("stopFallback"), "unknown execution stops the fallback");
        check(!unknown.getBoolean("localRejection"), "unknown execution is not a local rejection");
        check("unknown_execution".equals(unknown.getString("failureKind")),
                "failure named as unknown execution, got " + unknown.getString("failureKind"));
        check("".equals(unknown.getString("transportKind")),
                "no transport verdict when the request may have run");

        // Never wrote a byte: provably not executed, safe fallback, transport layer names the reason.
        JSONObject notSent = GatewayServer.antigravitySendVerdict(false, false,
                new javax.net.ssl.SSLHandshakeException("fixture handshake"));
        check(notSent.getInt("status") == 502, "not-sent answers 502");
        check(!notSent.getBoolean("executionUnknown"), "not-sent is not unknown");
        check(!notSent.getBoolean("stopFallback"), "not-sent keeps the safe fallback");
        check("network".equals(notSent.getString("failureKind")), "not-sent is a network failure");
        check("tls".equals(notSent.getString("transportKind")), "handshake failure named tls, got "
                + notSent.getString("transportKind"));

        JSONObject refused = GatewayServer.antigravitySendVerdict(false, false,
                new java.net.ConnectException("fixture refused"));
        check("refused".equals(refused.getString("transportKind")), "connect failure named refused");
        JSONObject dns = GatewayServer.antigravitySendVerdict(false, false,
                new java.net.UnknownHostException("fixture.invalid"));
        check("dns".equals(dns.getString("transportKind")), "unknown host named dns");
        JSONObject other = GatewayServer.antigravitySendVerdict(false, false, null);
        check("none".equals(other.getString("transportKind")), "no cause is named none");

        // Cancelled: nothing is concluded, and nothing is replayed.
        JSONObject cancelledUnsent = GatewayServer.antigravitySendVerdict(false, true, null);
        check(cancelledUnsent.getInt("status") == 499, "cancelled answers 499");
        check(cancelledUnsent.getBoolean("localRejection"), "cancelled is a local ending");
        check(!cancelledUnsent.getBoolean("executionUnknown"), "cancelled is not an execution verdict");
        check("".equals(cancelledUnsent.getString("failureKind")), "cancelled names no upstream failure");
        check("".equals(cancelledUnsent.getString("transportKind")), "cancelled names no transport failure");

        JSONObject cancelledAfterWrite = GatewayServer.antigravitySendVerdict(true, true,
                new java.net.SocketTimeoutException("fixture stall"));
        check(cancelledAfterWrite.getInt("status") == 499, "cancelled after writing is still 499");
        check(cancelledAfterWrite.getBoolean("stopFallback"), "cancelled after writing still stops fallback");
        check(!cancelledAfterWrite.getBoolean("executionUnknown"),
                "a cancelled request is never reported as a settled unknown execution");
        check("".equals(cancelledAfterWrite.getString("failureKind")), "no failure kind when cancelled");
    }

    /** The 499 response says what happened instead of "Upstream Error". */
    static void cancelledResponseShape() throws Exception {
        File dir = tempDir("hardening-cancel-");
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"),
                new File(dir, "config.json"));
        Method builder = GatewayServer.class.getDeclaredMethod("cancelledRequestResponse", String.class);
        builder.setAccessible(true);
        NanoHTTPD.Response response = (NanoHTTPD.Response) builder.invoke(server, "fixture cancelled");
        check(response.getStatus().getRequestStatus() == 499, "499 status");
        check("Client Closed Request".equals(response.getStatus().getDescription()),
                "reason phrase is honest, got " + response.getStatus().getDescription());
        String body = new String(readAll(response.getData()), StandardCharsets.UTF_8);
        JSONObject parsed = new JSONObject(body);
        check("request_cancelled".equals(parsed.getJSONObject("error").optString("code")),
                "body names the cancellation: " + body);
        check(parsed.getJSONObject("error").optString("message").contains("cancelled"),
                "body carries the message");
    }

    /**
     * A spent Antigravity login whose account never reached disk must not come back as an id.
     *
     * <p>No socket is opened: the account carries its own project (so {@code ensureProject} returns
     * before any call) and the network policy is set to {@code blocked}, which makes the model listing
     * fail inside the guarded block. The policy is re-applied before each call because a successful
     * save legitimately re-applies the configuration's own (default) policy.
     */
    static void antigravityAccountAddReportsSaveFailure() throws Exception {
        File dir = tempDir("hardening-ag-");
        File config = new File(dir, "config.json");
        String base = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}";
        write(config, base);
        GatewayServer server = fixture(new JSONObject(base), config);
        Method add = GatewayServer.class.getDeclaredMethod("addAntigravityAccount", JSONObject.class, String.class);
        add.setAccessible(true);
        int handshakeTries = AntigravityAuth.HANDSHAKE_TRIES;
        AntigravityAuth.HANDSHAKE_TRIES = 1;
        try {
            NetworkPolicy.block();
            JSONObject signIn = new JSONObject("{\"email\":\"fixture@example.invalid\","
                    + "\"refresh\":\"fixture-refresh\",\"access\":\"fixture-access\","
                    + "\"project\":\"fixture-project\",\"expires\":1000}");
            String savedId = (String) add.invoke(server, signIn, "fixture@example.invalid");
            check(GatewayServer.accountAddSucceeded(savedId), "a persisted login returns an id, got " + savedId);
            JSONObject persisted = new JSONObject(read(config));
            check(persisted.getJSONArray("providers").length() == 1, "antigravity provider created");
            JSONObject provider = persisted.getJSONArray("providers").getJSONObject(0);
            check("antigravity".equals(provider.optString("id")), "provider id");
            check(provider.getJSONArray("accounts").length() == 1, "one account persisted");
            String afterFirst = read(config);

            // Same shape again, but the configuration cannot be written: must throw, never return an id.
            setField(server, "configFile", new File(new File(dir, "missing"), "config.json"));
            NetworkPolicy.block();
            JSONObject second = new JSONObject("{\"email\":\"second@example.invalid\","
                    + "\"refresh\":\"second-refresh\",\"access\":\"second-access\","
                    + "\"project\":\"fixture-project\",\"expires\":2000}");
            Object returned = null;
            Throwable thrown = null;
            try { returned = add.invoke(server, second, "second@example.invalid"); }
            catch (java.lang.reflect.InvocationTargetException wrapped) { thrown = wrapped.getCause(); }
            check(returned == null, "no account id is handed out when the write failed: " + returned);
            check(thrown instanceof RuntimeException, "the failure surfaces, got " + thrown);
            check(thrown.getMessage() != null && thrown.getMessage().contains("未加入账号池"),
                    "the failure says the account was not added: " + thrown.getMessage());
            check(afterFirst.equals(read(config)), "the durable configuration is untouched by the failed add");

            // The spent credential is still in memory, so a later save can persist it.
            JSONObject memory = (JSONObject) field(server, "configJson");
            JSONArray providers = memory.getJSONArray("providers");
            boolean foundSecond = false;
            for (int i = 0; i < providers.length(); i++) {
                JSONArray accounts = providers.getJSONObject(i).optJSONArray("accounts");
                for (int j = 0; accounts != null && j < accounts.length(); j++) {
                    JSONObject account = accounts.getJSONObject(j);
                    if ("second@example.invalid".equals(account.optString("email"))) {
                        foundSecond = "second-refresh".equals(account.optString("refresh"));
                    }
                }
            }
            check(foundSecond, "the unpersisted login stays in memory with its refresh token");
        } finally {
            AntigravityAuth.HANDSHAKE_TRIES = handshakeTries;
            NetworkPolicy.configure(null);
        }
    }

    /**
     * Concurrency stayed as it was: {@code saveConfig} is still one monitor, the post-commit effects
     * still run inside it, and nothing new is shared between requests. Under contention the published
     * state must stay exactly equal to the durable one — that is the invariant the new post-commit
     * bookkeeping could have broken.
     */
    static void concurrentSavesPublishExactlyTheDurableState() throws Exception {
        File dir = tempDir("hardening-concurrent-save-");
        File config = new File(dir, "config.json");
        String base = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}";
        write(config, base);
        final GatewayServer server = fixture(new JSONObject(base), config);
        final int threads = 4, rounds = 8;
        final java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger broken = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<String> markers = java.util.Collections.synchronizedList(new ArrayList<String>());
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int worker = t;
            workers[t] = new Thread(new Runnable() {
                public void run() {
                    for (int r = 0; r < rounds; r++) {
                        String marker = "w" + worker + "-" + r;
                        try {
                            JSONObject next = new JSONObject(base);
                            next.put("schemaVersion", 4);
                            next.put("marker", marker);
                            markers.add(marker);
                            if (save(server, next)) saved.incrementAndGet();
                            else refused.incrementAndGet();
                        } catch (Throwable failure) {
                            broken.incrementAndGet();
                        }
                    }
                }
            }, "fixture-save-" + t);
            workers[t].setDaemon(true);
        }
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join(20000);
        check(broken.get() == 0, "no save threw under contention, saw " + broken.get());
        check(refused.get() == 0, "every save on a writable directory succeeded, refused " + refused.get());
        check(saved.get() == threads * rounds, "all saves reported success, got " + saved.get());
        JSONObject durable = new JSONObject(read(config));
        JSONObject published = (JSONObject) field(server, "configJson");
        check(durable.has("marker"), "the durable file is a complete configuration");
        check(markers.contains(durable.getString("marker")), "the durable marker is one that was written");
        check(durable.getString("marker").equals(published.optString("marker", "")),
                "the published state is exactly the durable one: disk=" + durable.optString("marker")
                        + " memory=" + published.optString("marker"));
        check(published.getJSONArray("providers").length() == 0, "no cross-talk between writers");
        check("".equals((String) field(server, "lastConfigPostCommitError")),
                "no post-commit step degraded during the contention run");
    }

    /** Build a two-account snapshot pair without touching the network or the disk layout. */
    static JSONObject account(String id, String email, String refresh, String access, long expires) throws Exception {
        JSONObject account = new JSONObject();
        account.put("id", id);
        account.put("name", "fixture");
        account.put("type", "oauth");
        if (email != null) account.put("email", email);
        if (refresh != null) account.put("refresh", refresh);
        if (access != null) account.put("access", access);
        if (expires > 0) account.put("expires", expires);
        return account;
    }

    static JSONObject configWith(JSONObject... accounts) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject account : accounts) list.put(account);
        JSONObject provider = new JSONObject();
        provider.put("id", "codex");
        provider.put("protocol", "openai");
        provider.put("accounts", list);
        return new JSONObject().put("providers", new JSONArray().put(provider));
    }

    /** A login that only exists in the failed snapshot must be kept, not dropped. */
    static void carryKeepsNewAccountsAndProviders() throws Exception {
        JSONObject durable = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        JSONObject attempted = configWith(
                account("acc_1", "a@example.invalid", "r-new", "a-new", 2000),
                account("acc_2", "b@example.invalid", "r-b", "a-b", 3000));
        JSONObject freshProvider = new JSONObject();
        freshProvider.put("id", "fixture-new-provider");
        freshProvider.put("accounts", new JSONArray().put(
                account("acc_9", "c@example.invalid", "r-c", "a-c", 4000)));
        attempted.getJSONArray("providers").put(freshProvider);

        JSONObject report = GatewayServer.reconcileAfterFailedSave(durable, attempted);
        check(report.optJSONArray("refusedAccounts").length() == 0,
                "nothing is refused here: " + report.optJSONArray("refusedAccounts"));
        check(report.optInt("carriedAccounts", 0) == 1, "one existing account refreshed");
        check(report.optInt("appendedAccounts", 0) == 1, "the new account is appended");
        check(report.optInt("appendedProviders", 0) == 1, "the new provider is appended");

        JSONArray accounts = durable.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check(accounts.length() == 2, "the new account survived, got " + accounts.length());
        check("r-new".equals(accounts.getJSONObject(0).optString("refresh")), "existing account refreshed");
        check("r-b".equals(accounts.getJSONObject(1).optString("refresh")),
                "the spent credential of the new account survived");
        check(durable.getJSONArray("providers").length() == 2, "the new provider survived");
    }

    /** A reused id, or two entries that are provably different accounts, must never be merged. */
    static void carryRefusesIdReuseAndIdentityConflict() throws Exception {
        // (a) two attempted entries share one id: it is impossible to tell which one the durable
        //     entry is, so nothing is carried.
        JSONObject durable = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        JSONObject attempted = configWith(
                account("acc_1", "a@example.invalid", "r-new", "a-new", 2000),
                account("acc_1", "b@example.invalid", "r-b", "a-b", 3000));
        JSONObject report = GatewayServer.reconcileAfterFailedSave(durable, attempted);
        check(report.optJSONArray("refusedAccounts").length() >= 1, "the ambiguous id is refused");
        check("id reused".contains("id reused") && report.optJSONArray("refusedAccounts")
                .getJSONObject(0).optString("reason").contains("id reused"),
                "the reason names the id reuse: " + report.optJSONArray("refusedAccounts"));
        JSONObject kept = durable.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("r-old".equals(kept.optString("refresh")), "the durable entry keeps its own refresh");
        check("a-old".equals(kept.optString("access")), "the durable entry keeps its own access");
        check(durable.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length() == 1,
                "no extra account was fabricated");

        // (b) same id, different identity: two accounts that collide, not one account to refresh.
        JSONObject durable2 = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        JSONObject attempted2 = configWith(account("acc_1", "b@example.invalid", "r-b", "a-b", 3000));
        JSONObject report2 = GatewayServer.reconcileAfterFailedSave(durable2, attempted2);
        JSONObject refused2 = report2.optJSONArray("refusedAccounts").getJSONObject(0);
        check(refused2.optString("reason").contains("identity"), "the reason names the identity conflict");
        JSONObject kept2 = durable2.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("r-old".equals(kept2.optString("refresh")), "no cross-account credential adoption");
        check("b@example.invalid".equals(attempted2.getJSONArray("providers").getJSONObject(0)
                        .getJSONArray("accounts").getJSONObject(0).optString("email")),
                "the failed snapshot is not mutated by the reconciliation");

        // (c) a duplicate id already on disk is never widened by an append.
        JSONObject durable3 = configWith(
                account("acc_1", "a@example.invalid", "r-old", "a-old", 1000),
                account("acc_1", "b@example.invalid", "r-old-b", "a-old-b", 1000));
        JSONObject attempted3 = configWith(account("acc_3", "c@example.invalid", "r-c", "a-c", 3000));
        GatewayServer.reconcileAfterFailedSave(durable3, attempted3);
        check(durable3.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length() == 3,
                "an unrelated new account is still appended next to the duplicates");
    }

    /**
     * generation/device are binding material: they are carried only when the identity is proven.
     *
     * <p>Part (a) also pins the stricter rule this round introduced: an entry with no identity
     * evidence at all is not merged <em>at all</em>, not just "without binding". Carrying its
     * tokens would put one account's live credentials onto another record whenever two logins
     * collide on the clock-derived id, so the whole merge is refused and reported.
     */
    static void carryCarriesBindingOnlyWhenIdentityConfirmed() throws Exception {
        // (a) no identity evidence at all: nothing is merged — not the tokens, not the binding.
        JSONObject unknownAccount = account("acc_1", null, "r-old", "a-old", 1000);
        unknownAccount.put("generation", "gen-old");
        unknownAccount.put("device", "device-old");
        JSONObject durable = configWith(unknownAccount);
        JSONObject wanted = account("acc_1", null, "r-new", "a-new", 2000);
        wanted.put("generation", "gen-new");
        wanted.put("device", "device-new");
        JSONObject unknownReport = GatewayServer.reconcileAfterFailedSave(durable, configWith(wanted));
        JSONObject kept = durable.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("r-old".equals(kept.optString("refresh")),
                "without identity proof the token is NOT carried: " + kept.optString("refresh"));
        check("a-old".equals(kept.optString("access")), "and neither is the access token");
        check("gen-old".equals(kept.optString("generation")), "generation is never carried blindly");
        check("device-old".equals(kept.optString("device")), "device binding is never carried blindly");
        check(unknownReport.optInt("refusedNoIdentity", 0) == 1,
                "the refusal is reported: " + unknownReport);
        check(unknownReport.optJSONArray("refusedAccounts").getJSONObject(0).optString("reason")
                        .contains("no identity evidence"),
                "the reason names the missing evidence: " + unknownReport.optJSONArray("refusedAccounts"));

        // (b) proven same account (same email): the whole rotated set moves together.
        JSONObject durable2 = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        durable2.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("generation", "gen-old");
        JSONObject wanted2 = account("acc_1", "a@example.invalid", "r-new", "a-new", 2000);
        wanted2.put("generation", "gen-new");
        GatewayServer.reconcileAfterFailedSave(durable2, configWith(wanted2));
        JSONObject kept2 = durable2.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("r-new".equals(kept2.optString("refresh")), "refresh carried");
        check("gen-new".equals(kept2.optString("generation")),
                "the generation travels with the credentials it guards, got " + kept2.optString("generation"));

        // (c) generation never jumps to a different account.
        JSONObject durable3 = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        durable3.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("generation", "gen-a");
        JSONObject wanted3 = account("acc_1", "b@example.invalid", "r-b", "a-b", 3000);
        wanted3.put("generation", "gen-b");
        GatewayServer.reconcileAfterFailedSave(durable3, configWith(wanted3));
        check("gen-a".equals(durable3.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).optString("generation")), "another account's generation is never adopted");
    }

    /** A partial snapshot must not leave the durable entry with a half-replaced credential set. */
    static void carryKeepsCredentialSetComplete() throws Exception {
        JSONObject durable = configWith(account("acc_1", "a@example.invalid", "r-old", "a-old", 1000));
        JSONObject wanted = new JSONObject();
        wanted.put("id", "acc_1");
        wanted.put("email", "a@example.invalid");
        wanted.put("access", "a-new");
        GatewayServer.reconcileAfterFailedSave(durable, configWith(wanted));
        JSONObject kept = durable.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("a-new".equals(kept.optString("access")), "the new access token is carried");
        check("r-old".equals(kept.optString("refresh")),
                "the existing refresh token is not dropped by a partial snapshot");
        check(kept.optLong("expires", 0) == 1000, "the existing expiry is not dropped either");

        // Zero/empty values in the snapshot are not treated as new credentials.
        JSONObject durable2 = configWith(account("acc_2", "b@example.invalid", "r-old", "a-old", 1000));
        JSONObject wanted2 = new JSONObject();
        wanted2.put("id", "acc_2");
        wanted2.put("email", "b@example.invalid");
        wanted2.put("access", "");
        wanted2.put("expires", 0);
        GatewayServer.reconcileAfterFailedSave(durable2, configWith(wanted2));
        JSONObject kept2 = durable2.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("a-old".equals(kept2.optString("access")), "an empty snapshot value never erases a token");
        check(kept2.optLong("expires", 0) == 1000, "a zero snapshot expiry never erases the real one");
    }

    /** A rescue that could not be written atomically must block the irreversible reset. */
    static void rescueWriteFailureBlocksReset() throws Exception {
        File dir = tempDir("hardening-rescue-fail-");
        File config = new File(dir, "config.json");
        String damaged = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[";
        write(config, damaged);
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);
        GatewayServer.setAtomicWriteHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected rescue failure"); }
        });
        try {
            call(server, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        } finally {
            GatewayServer.setAtomicWriteHookForTests(null);
        }
        check(!(Boolean) field(server, "configRecoveryRescueValid"), "an unverified rescue is never claimed valid");
        check("".equals((String) field(server, "configRecoveryRescueFile")), "no rescue file is recorded");
        String rescueError = (String) field(server, "configRecoveryRescueError");
        check(rescueError != null && !rescueError.isEmpty(), "the failure reason is kept: " + rescueError);
        check(damaged.equals(read(config)), "the original file is untouched");
        for (File file : dir.listFiles())
            check(!file.getName().startsWith("config-rescue-"), "no leftover temporary: " + file.getName());

        // Now the reset itself must fail to rescue, and must refuse instead of overwriting.
        GatewayServer.setAtomicWriteHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected rescue failure"); }
        });
        Reply reset;
        try {
            reset = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                    "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        } finally {
            GatewayServer.setAtomicWriteHookForTests(null);
        }
        check(reset.status == 409, "reset is refused without a verified rescue, got " + reset.status);
        check(reset.body.contains("救援") || reset.body.contains("rescue"), "the refusal explains why");
        check(damaged.equals(read(config)), "the damaged bytes are still there, byte for byte");
        check((Boolean) field(server, "configRecoveryRequired"), "still in recovery");

        // With the fault gone the same reset succeeds and archives the bytes first.
        Reply retried = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        check(retried.status == 200, "reset succeeds once a rescue can be written, got " + retried.status);
        int archived = 0;
        for (File file : dir.listFiles()) {
            if (file.getName().startsWith("config.corrupt-")) {
                archived++;
                check(damaged.equals(read(file)), "the archive holds the exact pre-reset bytes");
            }
        }
        check(archived == 1, "exactly one archive, found " + archived);
        check(!(Boolean) field(server, "configRecoveryRequired"), "recovery cleared");
        check(new JSONObject(read(config)).getJSONArray("providers").length() == 0, "rebuilt file is valid");
    }

    /** A rescue that no longer matches the file must be re-created and re-verified before a reset. */
    static void resetVerifiesRescueBytes() throws Exception {
        File dir = tempDir("hardening-rescue-verify-");
        File config = new File(dir, "config.json");
        String damaged = "not json at all";
        write(config, damaged);
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);
        call(server, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        String rescueName = (String) field(server, "configRecoveryRescueFile");
        check(rescueName != null && !rescueName.isEmpty(), "a rescue was written at recovery entry");
        File rescue = new File(dir, rescueName);
        check(GatewayServer.rescueMatches(rescue, damaged.getBytes(StandardCharsets.UTF_8)),
                "the rescue matches the damaged bytes");

        // Same length, different content: a size check would accept this, a byte check must not.
        byte[] tampered = damaged.getBytes(StandardCharsets.UTF_8);
        tampered[0] = 'x';
        write(rescue, new String(tampered, StandardCharsets.UTF_8));
        check(!GatewayServer.rescueMatches(rescue, damaged.getBytes(StandardCharsets.UTF_8)),
                "tampered rescue is detected");
        GatewayServer.setAtomicWriteHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected rescue failure"); }
        });
        Reply refused;
        try {
            refused = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                    "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        } finally {
            GatewayServer.setAtomicWriteHookForTests(null);
        }
        check(refused.status == 409, "a tampered rescue blocks the reset, got " + refused.status);
        check(damaged.equals(read(config)), "the original bytes survive a refused reset");

        // The file changed under us: the archive must follow the bytes actually being replaced.
        String replaced = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[";
        write(config, replaced);
        Reply ok = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        check(ok.status == 200, "reset succeeds after a fresh verified rescue, got " + ok.status);
        String replacedName = GatewayServer.rescueNameFor(replaced.getBytes(StandardCharsets.UTF_8));
        File replacedRescue = new File(dir, replacedName);
        check(replacedRescue.isFile(), "the bytes actually replaced were archived: " + replacedName);
        check(replaced.equals(read(replacedRescue)), "the archive holds the exact replaced bytes");
    }

    /** A file replaced between the rescue and the rebuild is not silently destroyed. */
    static void resetRefusesWhenFileChangedAfterRescue() throws Exception {
        File dir = tempDir("hardening-rescue-toctou-");
        final File config = new File(dir, "config.json");
        final String damaged = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[";
        write(config, damaged);
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);
        call(server, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        check((Boolean) field(server, "configRecoveryRescueValid"), "the rescue is verified at entry");

        // Something outside the gateway replaces the file after the rescue was taken.
        final String unexpected = "hand edited by the user, different length";
        GatewayServer.setBeforeRecoveryCommitHookForTests(new Runnable() {
            public void run() {
                try { write(config, unexpected); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        });
        Reply refused;
        try {
            refused = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                    "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        } finally {
            GatewayServer.setBeforeRecoveryCommitHookForTests(null);
        }
        check(refused.status == 409, "the rebuild is refused, got " + refused.status);
        check(unexpected.equals(read(config)), "the newer bytes are preserved, not overwritten");
        check((Boolean) field(server, "configRecoveryRequired"), "still in recovery");

        // After a retry adopts the new bytes, a reset is allowed again and archives exactly them.
        write(config, damaged);
        Reply reset = call(server, "127.0.0.1", "/api/config/recovery", "POST",
                "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        check(reset.status == 200, "reset succeeds once the rescue matches again, got " + reset.status);
        String archivedName = GatewayServer.rescueNameFor(damaged.getBytes(StandardCharsets.UTF_8));
        check(new File(dir, archivedName).isFile(), "the damaged bytes are archived");
        check(damaged.equals(read(new File(dir, archivedName))), "archive matches the destroyed bytes");
    }

    /**
     * A colliding id with no identity evidence must not merge: this is the exact shape a
     * clock-derived id takes when two logins land in the same millisecond.
     */
    static void noIdentityEvidenceRefusesMerge() throws Exception {
        JSONObject durable = configWith(account("acc_1700000000000", null, "dead-refresh", "dead-access", 1000));
        JSONObject stranger = account("acc_1700000000000", null, "live-refresh", "live-access", 2000);
        JSONObject report = GatewayServer.reconcileAfterFailedSave(durable, configWith(stranger));

        JSONObject kept = durable.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("dead-refresh".equals(kept.optString("refresh")),
                "no cross-account token adoption without evidence, got " + kept.optString("refresh"));
        check("dead-access".equals(kept.optString("access")), "no cross-account access adoption");
        check(kept.optLong("expires", 0) == 1000, "no cross-account expiry adoption");
        check(durable.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length() == 1,
                "no duplicate record is fabricated either");
        check(report.optInt("carriedFields", 0) == 0, "nothing is carried without evidence: " + report);
        check(report.optInt("refusedNoIdentity", 0) == 1, "the refusal is counted: " + report);

        // A strong identity on both sides is what makes the very same merge legal again.
        JSONObject proven = configWith(account("acc_1700000000000", "a@example.invalid",
                "dead-refresh", "dead-access", 1000));
        JSONObject rotated = account("acc_1700000000000", "A@Example.Invalid",
                "live-refresh", "live-access", 2000);
        JSONObject provenReport = GatewayServer.reconcileAfterFailedSave(proven, configWith(rotated));
        JSONObject rotatedKept = proven.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        check("live-refresh".equals(rotatedKept.optString("refresh")),
                "with the same email the rotated token is carried: " + rotatedKept.optString("refresh"));
        check(provenReport.optInt("refusedNoIdentity", 0) == 0, "nothing refused when identity matches");
    }

    /**
     * A caller that mutates a detached copy hands that copy to {@code saveConfig}. The failed
     * write must examine it — reverting against the live {@code configJson} would drop the
     * credentials the copy carried.
     */
    static void detachedCopyCredentialsSurviveFailedWrite() throws Exception {
        File dir = tempDir("hardening-detached-");
        File config = new File(dir, "config.json");
        String durable = "{\"providers\":[{\"id\":\"codex\",\"protocol\":\"openai\",\"accounts\":["
                + "{\"id\":\"acc_1\",\"email\":\"a@example.invalid\",\"refresh\":\"disk-refresh\","
                + "\"access\":\"disk-access\",\"expires\":1000}]}]}";
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);

        // The live configuration is stale, the detached copy holds the freshly rotated set.
        JSONObject live = new JSONObject(durable);
        live.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "stale-refresh");
        setField(server, "configJson", live);

        JSONObject detached = new JSONObject(durable);
        detached.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "rotated-refresh");
        // An entry that only exists in the live configuration must not be lost either.
        JSONObject memoryOnly = account("acc_mem", "m@example.invalid", "mem-refresh", "mem-access", 5000);
        live.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").put(memoryOnly);

        Method revert = GatewayServer.class.getDeclaredMethod("revertToDurableConfig",
                JSONObject.class, JSONObject.class);
        revert.setAccessible(true);
        revert.invoke(server, detached, live);

        JSONObject published = (JSONObject) field(server, "configJson");
        JSONArray accounts = published.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check("rotated-refresh".equals(accounts.getJSONObject(0).optString("refresh")),
                "the detached copy's rotated token is the one carried, got "
                        + accounts.getJSONObject(0).optString("refresh"));
        check(accounts.length() == 2, "the memory-only account is appended, got " + accounts.length());
        check("mem-refresh".equals(accounts.getJSONObject(1).optString("refresh")),
                "the memory-only credential survives the revert");
        check(durable.equals(read(config)), "the durable file was not rewritten by a revert");
        check(!(Boolean) field(server, "configRecoveryRequired"), "a readable durable copy is no recovery case");
    }

    /**
     * When the durable copy itself cannot be parsed, memory is the only surviving state: the
     * gateway must archive the damage and refuse further writes instead of overwriting it later.
     */
    static void unusableDurableCopyEntersRecovery() throws Exception {
        File dir = tempDir("hardening-revert-recovery-");
        File config = new File(dir, "config.json");
        String damaged = "{\"providers\":[{\"id\":\"codex\",\"accounts\":[";
        write(config, damaged);
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);

        JSONObject attempted = new JSONObject("{\"providers\":[{\"id\":\"codex\",\"accounts\":["
                + "{\"id\":\"acc_1\",\"email\":\"a@example.invalid\",\"refresh\":\"spent-refresh\"}]}]}");
        setField(server, "configJson", new JSONObject(attempted.toString()));
        Method revert = GatewayServer.class.getDeclaredMethod("revertToDurableConfig",
                JSONObject.class, JSONObject.class);
        revert.setAccessible(true);
        revert.invoke(server, attempted, null);

        check((Boolean) field(server, "configRecoveryRequired"),
                "an unusable durable copy puts the gateway into recovery");
        check((Boolean) field(server, "configRecoveryRescueValid"),
                "the damage is archived with a verified rescue before anything else happens");
        String rescueName = (String) field(server, "configRecoveryRescueFile");
        File rescue = new File(dir, rescueName == null ? "" : rescueName);
        check(GatewayServer.rescueMatches(rescue, damaged.getBytes(StandardCharsets.UTF_8)),
                "the rescue holds the exact damaged bytes");
        check(damaged.equals(read(config)), "the damaged file is left in place");

        // Memory is kept: it may hold credentials that were already spent upstream.
        JSONObject memory = (JSONObject) field(server, "configJson");
        check("spent-refresh".equals(memory.getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0).optString("refresh")),
                "the in-memory state is not wiped by the failed revert");

        // And no later save may silently overwrite the damage.
        JSONObject replacement = new JSONObject("{\"providers\":[]}");
        check(!save(server, replacement), "regular saves are refused in recovery");
        check(damaged.equals(read(config)), "the damaged bytes survive the refused save");

        // A structurally wrong but parseable file is damage too.
        File otherDir = tempDir("hardening-revert-shape-");
        File shaped = new File(otherDir, "config.json");
        write(shaped, "{\"providers\":{}}");
        GatewayServer shapedServer = fixture(new JSONObject("{\"providers\":[]}"), shaped);
        revert.invoke(shapedServer, new JSONObject("{\"providers\":[]}"), null);
        check((Boolean) field(shapedServer, "configRecoveryRequired"),
                "a wrongly shaped durable copy is damage as well");
    }

    /**
     * The write-failure path must examine the snapshot the caller handed in. A caller that mutates a
     * detached copy keeps the rotated credentials there; reverting against the live {@code configJson}
     * would publish the stale set instead.
     */
    static void writeFailureWithDetachedCopyKeepsItsCredentials() throws Exception {
        File dir = tempDir("hardening-write-detached-");
        File config = new File(dir, "config.json");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":["
                + "{\"id\":\"codex\",\"protocol\":\"openai\",\"accounts\":["
                + "{\"id\":\"acc_1\",\"email\":\"a@example.invalid\",\"refresh\":\"disk-refresh\","
                + "\"access\":\"disk-access\",\"expires\":1000}]}]}";
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);

        JSONObject live = new JSONObject(durable);
        live.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "stale-refresh");
        live.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .put(account("acc_mem", "m@example.invalid", "mem-refresh", "mem-access", 5000));
        setField(server, "configJson", live);

        JSONObject detached = new JSONObject(durable);
        detached.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "rotated-refresh");

        GatewayServer.setConfigWriteHookForTests(new Runnable() {
            public void run() { throw new IllegalStateException("injected disk failure"); }
        });
        boolean saved;
        try { saved = save(server, detached); }
        finally { GatewayServer.setConfigWriteHookForTests(null); }
        check(!saved, "the injected write failure is reported as a failed save");

        JSONObject published = (JSONObject) field(server, "configJson");
        JSONArray accounts = published.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check("rotated-refresh".equals(accounts.getJSONObject(0).optString("refresh")),
                "the detached copy's rotated token is what survives, got "
                        + accounts.getJSONObject(0).optString("refresh"));
        check(accounts.length() == 2, "the memory-only account is not lost, got " + accounts.length());
        check("mem-refresh".equals(accounts.getJSONObject(1).optString("refresh")),
                "the memory-only credential survives");
        check(durable.equals(read(config)), "the durable file is untouched by the failed write");
        check(!(Boolean) field(server, "configRecoveryRequired"), "a healthy durable copy is no recovery case");
    }

    /** The same wiring, reached through the pre-write validation rejection instead. */
    static void preWriteRejectionWithDetachedCopyKeepsItsCredentials() throws Exception {
        File dir = tempDir("hardening-prewrite-detached-");
        File config = new File(dir, "config.json");
        String durable = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":["
                + "{\"id\":\"codex\",\"protocol\":\"openai\",\"accounts\":["
                + "{\"id\":\"acc_1\",\"email\":\"a@example.invalid\",\"refresh\":\"disk-refresh\","
                + "\"access\":\"disk-access\",\"expires\":1000}]}]}";
        write(config, durable);
        GatewayServer server = fixture(new JSONObject(durable), config);
        JSONObject live = new JSONObject(durable);
        live.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "stale-refresh");
        setField(server, "configJson", live);

        JSONObject detached = new JSONObject(durable);
        detached.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                .getJSONObject(0).put("refresh", "rotated-refresh");
        detached.put("network", new JSONArray());   // rejected before any write
        check(!save(server, detached), "an invalid network member is rejected before writing");

        JSONObject published = (JSONObject) field(server, "configJson");
        check("rotated-refresh".equals(published.getJSONArray("providers").getJSONObject(0)
                        .getJSONArray("accounts").getJSONObject(0).optString("refresh")),
                "the detached copy's rotated token survives the pre-write rejection");
        check(durable.equals(read(config)), "the durable file is untouched");
    }

    /** The Qoder catalog read now runs under the request budget (call-site wiring). */
    static void qoderCatalogReadIsBudgetBound() throws Exception {
        Method budgeted = GatewayServer.class.getDeclaredMethod("qoderRead",
                QoderAuth.SignIn.class, QoderUsage.Budget.class);
        check(java.lang.reflect.Modifier.isPrivate(budgeted.getModifiers()), "budgeted read is private");
        // A budget that is already spent must fail before opening anything, and it must be the
        // budget overload doing it (the legal 15s/20s read would have proceeded).
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"),
                new File(tempDir("hardening-qoder-"), "config.json"));
        QoderAuth.SignIn signIn = new QoderAuth.SignIn();
        QoderUsage.Budget spent = new QoderUsage.Budget() {
            public long remainingMillis() { return 0L; }
            public boolean cancelled() { return false; }
            public String reason() { return "fixture budget"; }
            public void attach(java.net.HttpURLConnection connection) { }
            public void detach(java.net.HttpURLConnection connection) { }
        };
        budgeted.setAccessible(true);
        Throwable failure = null;
        try { budgeted.invoke(server, signIn, spent); }
        catch (java.lang.reflect.InvocationTargetException wrapped) { failure = wrapped.getCause(); }
        check(failure instanceof java.io.IOException, "a spent budget fails the read, got " + failure);
        check(failure.getMessage() != null && failure.getMessage().contains("no time left"),
                "the failure is the budget's own: " + failure.getMessage());

        QoderUsage.Budget cancelled = new QoderUsage.Budget() {
            public long remainingMillis() { return 60000L; }
            public boolean cancelled() { return true; }
            public String reason() { return "fixture cancel"; }
            public void attach(java.net.HttpURLConnection connection) { }
            public void detach(java.net.HttpURLConnection connection) { }
        };
        Throwable cancelFailure = null;
        try { budgeted.invoke(server, signIn, cancelled); }
        catch (java.lang.reflect.InvocationTargetException wrapped) { cancelFailure = wrapped.getCause(); }
        check(cancelFailure instanceof java.io.IOException, "a cancelled read fails, got " + cancelFailure);
    }

    /** The account-add HTTP route must answer ok:false when the write fails. */
    static void accountAddRouteReportsFailure() throws Exception {
        String base = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},"
                + "\"providers\":[{\"id\":\"fixture-prov\",\"protocol\":\"openai\",\"keys\":[],\"accounts\":[]}]}";
        File dir = tempDir("hardening-add-route-");
        File config = new File(dir, "config.json");
        write(config, base);
        GatewayServer broken = fixture(new JSONObject(read(config)), config);
        setField(broken, "configFile", new File(new File(dir, "missing"), "config.json"));
        Reply failed = call(broken, "127.0.0.1", "/api/auth/fixture-prov/add", "POST",
                "{\"name\":\"fixture\",\"token\":\"synthetic-token\"}", null);
        check(failed.status == 200, "add route answers even on failure, got " + failed.status);
        JSONObject failedBody = new JSONObject(failed.body);
        check(!failedBody.getBoolean("ok"), "failed write is not reported as success: " + failed.body);
        check(failedBody.optString("error", "").contains("写入失败"), "failure explains the write problem");
        check(!failedBody.has("id"), "no account id handed out for an unpersisted account");

        GatewayServer healthy = fixture(new JSONObject(read(config)), config);
        Reply ok = call(healthy, "127.0.0.1", "/api/auth/fixture-prov/add", "POST",
                "{\"name\":\"fixture\",\"token\":\"synthetic-token\"}", null);
        JSONObject okBody = new JSONObject(ok.body);
        check(okBody.getBoolean("ok"), "successful add still reports success: " + ok.body);
        check(okBody.optString("id", "").startsWith("acc_"), "id returned on success");
        check(new JSONObject(read(config)).getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").length() == 1, "account persisted");

        // Recovery state must also refuse the write, and must say so.
        GatewayServer recovering = fixture(new JSONObject(read(config)), config);
        call(recovering, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        Reply blocked = call(recovering, "127.0.0.1", "/api/auth/fixture-prov/add", "POST",
                "{\"name\":\"other\",\"token\":\"synthetic-token-two\"}", null);
        JSONObject blockedBody = new JSONObject(blocked.body);
        check(!blockedBody.getBoolean("ok"), "recovery blocks the add and reports failure");
        check(new JSONObject(read(config)).getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").length() == 1, "recovery left the durable file alone");
    }

    /** Damage detection is limited to unreadable or wrongly shaped files. */
    static void recoveryEntryIsNarrow() throws Exception {
        check(GatewayServer.configShapeValid(new JSONObject("{\"providers\":[],\"server\":{}}")),
                "plain config is valid");
        check(GatewayServer.configShapeValid(new JSONObject("{}")), "empty object is acceptable");
        check(!GatewayServer.configShapeValid(null), "null is not a config");
        check(!GatewayServer.configShapeValid(new JSONObject("{\"providers\":{}}")), "object providers rejected");
        check(!GatewayServer.configShapeValid(new JSONObject("{\"server\":[]}")), "array server rejected");
        check(!GatewayServer.configShapeValid(new JSONObject("{\"setup\":\"done\"}")), "string setup rejected");

        File dir = tempDir("hardening-narrow-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[]}");
        GatewayServer server = fixture(new JSONObject(read(config)), config);
        check(!(Boolean) field(server, "configRecoveryRequired"), "a healthy config is not in recovery");
        check(save(server, new JSONObject(read(config))), "a healthy config stays writable");

        // Deterministic rescue: retrying recovery on the same bytes must not stack copies.
        File corrupt = new File(tempDir("hardening-rescue-"), "config.json");
        write(corrupt, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[");
        GatewayServer broken = fixture(new JSONObject("{\"providers\":[]}"), corrupt);
        call(broken, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        String first = (String) field(broken, "configRecoveryRescueFile");
        call(broken, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        String second = (String) field(broken, "configRecoveryRescueFile");
        check(first != null && first.equals(second), "rescue name is deterministic: " + first + " / " + second);
        int rescueCopies = 0;
        File[] files = corrupt.getParentFile().listFiles();
        for (int i = 0; files != null && i < files.length; i++)
            if (files[i].getName().startsWith("config.corrupt-")) rescueCopies++;
        check(rescueCopies == 1, "exactly one rescue copy, found " + rescueCopies);
    }

    /** Recovery must be reachable and reversible through the management API. */
    static void recoveryRetryAndReset() throws Exception {
        File dir = tempDir("hardening-recover-api-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[");
        GatewayServer server = fixture(new JSONObject("{\"providers\":[]}"), config);
        call(server, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));

        Reply status = call(server, "127.0.0.1", "/api/config/recovery", "GET", "", null);
        check(status.status == 200, "/api/config/recovery readable, got " + status.status);
        JSONObject statusBody = new JSONObject(status.body);
        check(statusBody.getJSONObject("recovery").getBoolean("required"), "recovery reported as required");

        // Regular saves stay blocked, so recovery cannot be overwritten by accident.
        check(!save(server, new JSONObject("{\"providers\":[],\"server\":{\"authKey\":\"x-key-x\"}}")),
                "regular save still blocked in recovery");

        // Retry against the still-damaged file re-enters recovery instead of pretending success.
        Reply retryBad = call(server, "127.0.0.1", "/api/config/recovery", "POST", "{\"action\":\"retry\"}", null);
        check(retryBad.status == 200, "retry answers with status, got " + retryBad.status);
        check(!new JSONObject(retryBad.body).getBoolean("ok"), "retry on damaged file reports failure");
        check((Boolean) field(server, "configRecoveryRequired"), "still in recovery after a failed retry");

        // Replace the file with a valid configuration: retry now clears recovery and adopts it.
        write(config, "{\"server\":{\"authKey\":\"fixture-recovered-key\"},\"providers\":[]}");
        Reply retryGood = call(server, "127.0.0.1", "/api/config/recovery", "POST", "{\"action\":\"retry\"}", null);
        check(retryGood.status == 200 && new JSONObject(retryGood.body).getBoolean("ok"),
                "retry adopts a repaired file: " + retryGood.body);
        check(!(Boolean) field(server, "configRecoveryRequired"), "recovery cleared");
        check("fixture-recovered-key".equals(((JSONObject) field(server, "configJson"))
                .getJSONObject("server").getString("authKey")), "repaired key adopted");

        // Reset requires an explicit confirmation, then writes a usable configuration.
        File dir2 = tempDir("hardening-reset-");
        File config2 = new File(dir2, "config.json");
        write(config2, "not json at all");
        GatewayServer reset = fixture(new JSONObject("{\"providers\":[]}"), config2);
        call(reset, "enterConfigRecovery", new Class<?>[]{Exception.class}, new Exception("fixture"));
        Reply refused = call(reset, "127.0.0.1", "/api/config/recovery", "POST", "{\"action\":\"reset\"}", null);
        check(refused.status == 400, "reset without confirmation is refused, got " + refused.status);
        check("not json at all".equals(read(config2)), "damaged file untouched after a refused reset");

        Reply resetDone = call(reset, "127.0.0.1", "/api/config/recovery", "POST",
                "{\"action\":\"reset\",\"confirm\":\"reset-config\"}", null);
        check(resetDone.status == 200, "confirmed reset succeeds, got " + resetDone.status + " body=" + resetDone.body);
        check(!(Boolean) field(reset, "configRecoveryRequired"), "recovery cleared after reset");
        JSONObject rebuilt = new JSONObject(read(config2));
        check(rebuilt.has("server") && rebuilt.has("providers"), "rebuilt config has the minimal schema");
        String rebuiltKey = rebuilt.getJSONObject("server").getString("authKey");
        check(!GatewayServer.isPublishedDefaultAuthKey(rebuiltKey), "rebuilt key is not the published default");
        check(GatewayServer.authKeyAllowsRemoteAccess(rebuiltKey), "rebuilt key allows remote access");
        check(rebuilt.getInt("schemaVersion") == 4, "rebuilt config keeps schema version");
        int archived = 0;
        File[] files = dir2.listFiles();
        for (int i = 0; files != null && i < files.length; i++)
            if (files[i].getName().startsWith("config.corrupt-")) archived++;
        check(archived >= 1, "the damaged bytes stay archived, found " + archived);
    }

    /** The ETA export must not hand out a key that the gateway refuses for LAN inference. */
    static void etaImportDoesNotExportDeadKey() throws Exception {
        File[] dirs = { tempDir("hardening-eta-legacy-"), tempDir("hardening-eta-strong-") };
        File legacyFile = new File(dirs[0], "config.json");
        write(legacyFile, "{\"server\":{\"authKey\":\"magpie\"},\"setup\":{\"done\":true},\"providers\":[]}");
        GatewayServer legacy = fixture(new JSONObject(read(legacyFile)), legacyFile);
        Reply legacyReply = call(legacy, "127.0.0.1", "/api/eta/import", "GET", "", null);
        check(legacyReply.status == 200, "ETA import answers, got " + legacyReply.status);
        JSONObject legacyBody = new JSONObject(legacyReply.body);
        check("".equals(legacyBody.optString("apiKey")), "published default is not exported: " + legacyBody);
        check(legacyBody.getBoolean("migrationRequired"), "migration flagged");
        check("legacy_default_auth_key".equals(legacyBody.optString("authKeyBlockedReason")), "reason given");
        check(!legacyBody.getString("snippet").contains("magpie"), "snippet carries no dead credential");
        check(legacyBody.getString("snippet").contains("需先生成新口令"), "snippet asks for a new key");
        check(legacyBody.getString("snippet").startsWith("Base URL: "), "snippet still carries the address");

        File strongFile = new File(dirs[1], "config.json");
        write(strongFile, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},\"providers\":[]}");
        GatewayServer strong = fixture(new JSONObject(read(strongFile)), strongFile);
        Reply strongReply = call(strong, "127.0.0.1", "/api/eta/import", "GET", "", null);
        JSONObject strongBody = new JSONObject(strongReply.body);
        check("fixture-strong-key".equals(strongBody.optString("apiKey")), "a usable key is still exported");
        check(!strongBody.getBoolean("migrationRequired"), "no migration needed for a usable key");
        check(strongBody.getString("snippet").contains("fixture-strong-key"), "snippet carries the usable key");
    }

    static void statusExposesOperationalState() throws Exception {
        File dir = tempDir("hardening-status-http-");
        File config = new File(dir, "config.json");
        write(config, "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},\"providers\":[]}");
        GatewayServer server = fixture(new JSONObject(read(config)), config);
        // The shared fixture helper does not seed every counter this route reads.
        setField(server, "totalTokens", new java.util.concurrent.atomic.AtomicLong());
        Reply status = call(server, "127.0.0.1", "/api/status", "GET", "", null);
        check(status.status == 200, "/api/status reachable, got " + status.status + " body=" + status.body);
        JSONObject parsed = new JSONObject(status.body);
        JSONObject connections = parsed.optJSONObject("connections");
        check(connections != null, "connection counters exposed");
        check(connections.has("active") && connections.has("rejected") && connections.has("limit"),
                "connection counters complete: " + connections);
        JSONObject recovery = parsed.optJSONObject("configRecovery");
        check(recovery != null && !recovery.getBoolean("required"), "recovery state exposed as healthy");
    }


    // ================= D2: write-ahead refresh fence, never evicted, never truncated =========
    /**
     * The fence beside the configuration records every one-use refresh value that was sent, so a
     * restart, a crash or a restored old backup can never replay a spent value. These fixtures
     * inject the adversarial cases the release audit names: a full fence, a damaged or over-long
     * fence file, a provider that spells the same value differently on the wire, and a directory
     * sync that fails after the file was already replaced.
     */
    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }
    static Object staticCall(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
    static int intConstant(String name) throws Exception {
        java.lang.reflect.Field f = GatewayServer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
    static String fenceDigest(String provider, String value) throws Exception {
        return (String) staticCall("refreshFenceDigest", new Class<?>[]{String.class, String.class}, provider, value);
    }
    static File fenceFile(File configFile) { return new File(configFile.getAbsoluteFile().getParentFile(), "refresh-uncertain.fence"); }
    static String fenceText(File configFile) throws Exception {
        File fence = fenceFile(configFile);
        return fence.isFile() ? read(fence) : "";
    }
    static boolean fenced(File configFile, String provider, String value) throws Exception {
        String digest = fenceDigest(provider, value);
        return digest != null && fenceText(configFile).contains(digest);
    }
    static boolean fenceHit(GatewayServer server, String provider, String value) throws Exception {
        return (Boolean) invoke(server, "refreshFenceHit", new Class<?>[]{String.class, String.class}, provider, value);
    }
    static JSONObject providerObject(GatewayServer server, String provider) throws Exception {
        JSONArray providers = ((JSONObject) field(server, "configJson")).getJSONArray("providers");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject candidate = providers.getJSONObject(i);
            if (provider.equals(candidate.optString("id"))) return candidate;
        }
        throw new AssertionError("provider missing: " + provider);
    }
    static JSONObject firstAccount(GatewayServer server, String provider) throws Exception {
        return providerObject(server, provider).getJSONArray("accounts").getJSONObject(0);
    }
    static JSONObject durableAccount(File file, String provider) throws Exception {
        JSONArray providers = new JSONObject(read(file)).getJSONArray("providers");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject candidate = providers.getJSONObject(i);
            if (provider.equals(candidate.optString("id"))) return candidate.getJSONArray("accounts").getJSONObject(0);
        }
        throw new AssertionError("provider missing on disk: " + provider);
    }
    static String syncBody(GatewayServer server) throws Exception {
        Object response = invoke(server, "syncQoderCatalog", new Class<?>[]{JSONObject.class}, new JSONObject());
        return new String(readAll(((fi.iki.elonen.NanoHTTPD.Response) response).getData()), StandardCharsets.UTF_8);
    }
    static ClineAuth.RefreshTransport clineRotating(int[] exchanges, String access, String refresh) {
        return token -> {
            exchanges[0]++;
            ClineAuth.SignIn next = new ClineAuth.SignIn();
            next.access = access; next.refresh = refresh;
            next.expires = System.currentTimeMillis() + 3600000L;
            return next;
        };
    }
    static String clineDurable() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"cline\","
                + "\"protocol\":\"cline\",\"enabled\":true,\"accounts\":[{\"id\":\"cline_1\",\"enabled\":true,"
                + "\"type\":\"oauth\",\"token\":\"old-access\",\"access\":\"old-access\","
                + "\"refresh\":\"old-refresh\",\"expires\":1000}]}]}";
    }

    /** The fence is durable before the one-use value leaves the process, and settles after commit. */
    static void refreshFenceIsWrittenBeforeTheValueIsSent() throws Exception {
        File dir = tempDir("hardening-fence-order-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        final int[] exchanges = {0};
        final boolean[] fencedAtSend = {false};
        server.setClineRefreshForTests(token -> {
            exchanges[0]++;
            fencedAtSend[0] = fenced(durable, "cline", token);
            throw new java.io.IOException("fixture transport down");
        });
        try {
            invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(server, "cline").toString()));
            throw new AssertionError("a down transport was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("请重新登录"),
                    "an attempted exchange with no result asks for a sign-in: " + expected.getMessage());
        }
        check(exchanges[0] == 1, "exactly one exchange, got " + exchanges[0]);
        check(fencedAtSend[0], "the fence was already durable when the transport ran");
        check(fenced(durable, "cline", "old-refresh"), "the unresolved value stays fenced");

        // A committed rotation keeps the value blocked and records the new one only after commit.
        File savedDir = tempDir("hardening-fence-order-ok-");
        File saved = new File(savedDir, "config.json");
        write(saved, clineDurable());
        GatewayServer healthy = fixture(new JSONObject(clineDurable()), saved);
        final int[] savedExchanges = {0};
        healthy.setClineRefreshForTests(clineRotating(savedExchanges, "rotated-access", "rotated-refresh"));
        JSONObject committed = (JSONObject) invoke(healthy, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                new JSONObject(firstAccount(healthy, "cline").toString()));
        check("rotated-refresh".equals(committed.optString("refresh")), "the rotation is returned: " + committed);
        check(fenced(saved, "cline", "old-refresh"), "a committed rotation keeps the replaced value fenced");
        check(!fenced(saved, "cline", "rotated-refresh"), "the freshly stored value is not fenced");
    }

    /** A crash between the exchange and the commit leaves the value fenced for the next process. */
    static void crashBeforeCommitNeverReplaysAfterRestart() throws Exception {
        File dir = tempDir("hardening-fence-crash-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        final int[] exchanges = {0};
        server.setClineRefreshForTests(token -> {
            exchanges[0]++;
            // Simulates process death: no settlement code runs for this exchange.
            throw new StackOverflowError("fixture crash mid-refresh");
        });
        boolean crashed = false;
        try {
            invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(server, "cline").toString()));
        } catch (StackOverflowError expected) { crashed = true; }
        check(crashed && exchanges[0] == 1, "the fixture crashed during one exchange");
        check(fenced(durable, "cline", "old-refresh"), "the crash left a durable fence");
        check(clineDurable().equals(read(durable)), "the crashed exchange changed no account");

        for (int restart = 1; restart <= 2; restart++) {
            final int round = restart;
            GatewayServer again = fixture(new JSONObject(read(durable)), durable);
            again.setClineRefreshForTests(token -> {
                throw new AssertionError("a crashed exchange was replayed after restart " + round);
            });
            try {
                invoke(again, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                        new JSONObject(firstAccount(again, "cline").toString()));
                throw new AssertionError("restart " + round + " treated a crashed exchange as retryable");
            } catch (java.io.IOException expected) {
                check(expected.getMessage() != null && expected.getMessage().contains("请重新登录"),
                        "restart " + round + " asks for a sign-in: " + expected.getMessage());
            }
        }
        check(fenced(durable, "cline", "old-refresh"), "the fence survives every restart");
    }

    /**
     * A full fence refuses new refreshes and evicts nothing. An entry is the only thing standing
     * between a restored old backup and a replay of a value the provider already replaced, so no
     * entry may ever be dropped - not even the oldest one.
     */
    static void fullFenceFailsClosedAndEvictsNothing() throws Exception {
        int limit = intConstant("REFRESH_FENCE_LIMIT");
        check(limit >= 256, "the documented capacity must be usable: " + limit);
        File dir = tempDir("hardening-fence-full-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        StringBuilder fence = new StringBuilder();
        java.util.List<String> filled = new java.util.ArrayList<String>();
        for (int i = 0; i < limit - 1; i++) {
            String value = "already-sent-" + i;
            filled.add(value);
            fence.append("cline ").append(fenceDigest("cline", value)).append('\n');
        }
        write(fenceFile(durable), fence.toString());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        check(!(Boolean) invoke(server, "refreshFenceDamaged", new Class<?>[]{}), "the full fixture is readable");
        final int[] exchanges = {0};
        server.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
        // One slot left: this reservation fits.
        try {
            invoke(server, "reserveRefreshFence", new Class<?>[]{String.class, String.class}, "cline", "slot-filler");
        } catch (java.io.IOException unexpected) { throw new AssertionError("the last free slot was refused: " + unexpected); }
        check(fenceText(durable).split("\n").length == limit, "the fence is exactly full: " + fenceText(durable).split("\n").length);
        // Now it is full: the account must not be refreshed at all.
        try {
            invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(server, "cline").toString()));
            throw new AssertionError("a full fence still sent a one-use value");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("容量已满"),
                    "the failure names the capacity: " + expected.getMessage());
            check(expected.getMessage().contains("不会淘汰"), "the failure says nothing is evicted: " + expected.getMessage());
        }
        check(exchanges[0] == 0, "nothing was sent while the fence was full, got " + exchanges[0]);
        check(fenceText(durable).split("\n").length == limit, "the full fence kept every entry");
        for (int i = 0; i < filled.size(); i += 997)
            check(fenced(durable, "cline", filled.get(i)), "entry " + i + " survived the full fence");
        // The value an old backup would reintroduce is still blocked, and still is after a restart.
        GatewayServer restarted = fixture(new JSONObject(read(durable)), durable);
        check(fenceHit(restarted, "cline", filled.get(0)), "an old value stays blocked after a restart");
        restarted.setClineRefreshForTests(token -> {
            throw new AssertionError("an evicted value was replayed from an old backup");
        });
        try {
            invoke(restarted, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(restarted, "cline").toString()));
            throw new AssertionError("the full fence let a refresh through after a restart");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("容量已满"),
                    "the restart still fails closed: " + expected.getMessage());
        }
    }

    /**
     * A fence that cannot be read as a whole is never truncated into a smaller one and never
     * overwritten: a rewrite would drop the entries that were not understood, which are exactly
     * the ones that must keep blocking.
     */
    static void damagedFenceIsNeverTruncatedOrOverwritten() throws Exception {
        int maxBytes = intConstant("REFRESH_FENCE_MAX_BYTES");
        File dir = tempDir("hardening-fence-damaged-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        String good = "cline " + fenceDigest("cline", "old-refresh") + "\n";
        String[] damaged = {
                good + "not a fence line\n",                        // unparseable
                good + "cline\n",                                    // truncated last line
                good + "cline zz-not-a-digest\n",                    // unparseable digest
                good + "s cline not-a-digest\n",                     // legacy shape, bad digest
        };
        for (int i = 0; i < damaged.length; i++) {
            write(fenceFile(durable), damaged[i]);
            byte[] before = java.nio.file.Files.readAllBytes(fenceFile(durable).toPath());
            GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
            check((Boolean) invoke(server, "refreshFenceDamaged", new Class<?>[]{}),
                    "damaged fence " + i + " is reported as damaged");
            final int[] exchanges = {0};
            server.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
            try {
                invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                        new JSONObject(firstAccount(server, "cline").toString()));
                throw new AssertionError("a damaged fence let a value through: " + i);
            } catch (java.io.IOException expected) {
                check(expected.getMessage() != null && expected.getMessage().contains("损坏")
                                || expected.getMessage().contains("围栏"),
                        "the failure names the fence: " + expected.getMessage());
            }
            check(exchanges[0] == 0, "nothing was sent while the fence was damaged, got " + exchanges[0]);
            check(java.util.Arrays.equals(before,
                            java.nio.file.Files.readAllBytes(fenceFile(durable).toPath())),
                    "the damaged fence file was rewritten: " + i);
            check((Boolean) invoke(server, "refreshFenceHit", new Class<?>[]{String.class, String.class},
                    "cline", "some-other-value"), "a damaged fence blocks rather than allows");
            check(fenced(durable, "cline", "old-refresh"), "the readable entry stays on disk untouched: " + i);
        }
        // Over-long: bigger than the documented maximum is damage, not something to truncate.
        StringBuilder huge = new StringBuilder(good);
        while (huge.length() < maxBytes + 4096) huge.append("cline ").append(fenceDigest("cline", "filler-" + huge.length())).append('\n');
        write(fenceFile(durable), huge.toString());
        byte[] before = java.nio.file.Files.readAllBytes(fenceFile(durable).toPath());
        GatewayServer oversized = fixture(new JSONObject(clineDurable()), durable);
        check((Boolean) invoke(oversized, "refreshFenceDamaged", new Class<?>[]{}), "an over-long fence is damaged");
        final int[] exchanges = {0};
        oversized.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
        try {
            invoke(oversized, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(oversized, "cline").toString()));
            throw new AssertionError("an over-long fence was truncated and used");
        } catch (java.io.IOException expected) { /* fail closed */ }
        check(exchanges[0] == 0, "nothing was sent for an over-long fence, got " + exchanges[0]);
        check(java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(fenceFile(durable).toPath())),
                "the over-long fence was truncated by a rewrite");

        // Well-formed older spellings still block (they are history, not damage): the two-field form
        // without a trailing newline, the release-2 three-field form, and the bare-digest form.
        String legacyText = "cline " + fenceDigest("cline", "two-field")
                + "\nu cline " + fenceDigest("cline", "three-field")
                + "\n" + fenceDigest("cline", "bare") + "\n";
        write(fenceFile(durable), legacyText);
        GatewayServer legacy = fixture(new JSONObject(clineDurable()), durable);
        check(!(Boolean) invoke(legacy, "refreshFenceDamaged", new Class<?>[]{}), "a legacy fence is readable");
        check(fenceHit(legacy, "cline", "two-field"), "a two-field entry blocks its value");
        check(fenceHit(legacy, "cline", "three-field"), "a three-field entry blocks its value");
        check(fenceHit(legacy, "cline", "bare"), "a bare-digest entry blocks its value");

    }

    /**
     * Raccoon trims the stored value and strips a "Bearer " prefix before sending it, so the fence
     * has to hash the value that actually goes on the wire. When it does not, the reservation and
     * its settlement never meet and the account is blocked forever for no reason.
     */
    static void raccoonMaterialIsHashedAsSent() throws Exception {
        check(fenceDigest("raccoon", " Bearer  rc-refresh ").equals(fenceDigest("raccoon", "rc-refresh")),
                "the wire form and the stored spelling share one digest");
        check(!fenceDigest("raccoon", "rc-refresh").equals(fenceDigest("cline", "rc-refresh")),
                "providers do not share a digest");
        File dir = tempDir("hardening-raccoon-normalized-");
        File durable = new File(dir, "config.json");
        String config = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"raccoon\","
                + "\"protocol\":\"raccoon\",\"enabled\":true,\"keys\":[\"old-access\"],"
                + "\"accounts\":[{\"id\":\"rc_1\",\"enabled\":true,\"source\":\"browser\",\"type\":\"oauth\","
                + "\"access\":\"old-access\",\"token\":\"old-access\",\"refresh\":\" Bearer  rc-refresh \","
                + "\"expires\":1000}]}]}";
        write(durable, config);
        GatewayServer server = fixture(new JSONObject(config), durable);
        final int[] exchanges = {0};
        // The provider keeps the same refresh token (no rotation), so the value is still current.
        server.setRaccoonRefreshTransportForTests((path, body) -> {
            exchanges[0]++;
            return new RaccoonRefreshService.Response(200, new JSONObject().put("data",
                    new JSONObject().put("access_token", "rc-new").put("expires_in", 3600)));
        });
        JSONObject result = (JSONObject) invoke(server, "ensureRaccoonToken",
                new Class<?>[]{String.class, String.class, int.class}, "rc_1", null, 0);
        check(exchanges[0] == 1 && "rc-new".equals(storedAccessOf(result)), "the refresh committed: " + result);
        check(!fenced(durable, "raccoon", "rc-refresh"),
                "a value the provider kept is released, not left blocking forever");
        check(!fenceHit(server, "raccoon", "rc-refresh"), "the released value is usable again");

        // A rotating response leaves the old value fenced and the new one usable.
        File rotatedDir = tempDir("hardening-raccoon-normalized-rotated-");
        File rotated = new File(rotatedDir, "config.json");
        write(rotated, config);
        GatewayServer rotating = fixture(new JSONObject(config), rotated);
        final int[] rotatingExchanges = {0};
        rotating.setRaccoonRefreshTransportForTests((path, body) -> {
            rotatingExchanges[0]++;
            return new RaccoonRefreshService.Response(200, new JSONObject().put("data", new JSONObject()
                    .put("access_token", "rc-new").put("refresh_token", "rc-rotated").put("expires_in", 3600)));
        });
        invoke(rotating, "ensureRaccoonToken", new Class<?>[]{String.class, String.class, int.class}, "rc_1", null, 0);
        check(rotatingExchanges[0] == 1, "one rotating exchange");
        check(fenced(rotated, "raccoon", "rc-refresh"), "the replaced value stays fenced");
        check(!fenceHit(rotating, "raccoon", "rc-rotated"), "the new value is not fenced");
    }
    static String storedAccessOf(JSONObject account) throws Exception {
        String access = account.optString("access", "");
        return access.isEmpty() ? account.optString("token", "") : access;
    }

    /**
     * The file is replaced first and synced afterwards. A failed sync must not leave memory
     * claiming a fence the file does not hold (which would block the account for nothing), and
     * must not let the value be sent either (which would break the write-ahead guarantee).
     */
    static void failedDirectorySyncLeavesMemoryAndDiskAgreeing() throws Exception {
        File dir = tempDir("hardening-fence-unsynced-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        final int[] exchanges = {0};
        final int[] syncCalls = {0};
        GatewayServer.setRefreshFenceSyncHookForTests(() -> {
            if (syncCalls[0]++ == 0) throw new IllegalStateException("fixture directory sync failure");
        });
        try {
            server.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
            try {
                invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                        new JSONObject(firstAccount(server, "cline").toString()));
                throw new AssertionError("an unsynced reservation still sent the one-use value");
            } catch (java.io.IOException expected) {
                check(expected.getMessage() != null && expected.getMessage().contains("围栏"),
                        "the failure names the fence: " + expected.getMessage());
            }
            check(exchanges[0] == 0, "nothing was sent without a durable reservation, got " + exchanges[0]);
            check(!fenced(durable, "cline", "old-refresh"), "the unsynced reservation was undone on disk");
            check(!fenceHit(server, "cline", "old-refresh"),
                    "memory still claims a fence the file does not hold");

            // The account is not punished for our own bookkeeping: the retry works.
            JSONObject recovered = (JSONObject) invoke(server, "refreshClineAccount",
                    new Class<?>[]{JSONObject.class}, new JSONObject(firstAccount(server, "cline").toString()));
            check(exchanges[0] == 1, "the retry reached the transport, got " + exchanges[0]);
            check("rotated-refresh".equals(recovered.optString("refresh")), "the retry committed: " + recovered);
            check(fenced(durable, "cline", "old-refresh"), "the retried reservation is durable");
            check(fenceHit(server, "cline", "old-refresh"), "memory and disk agree after the retry");
        } finally { GatewayServer.setRefreshFenceSyncHookForTests(null); }
    }

    /** An unwritable fence blocks the send instead of sending an unfenced one-use value. */
    static void unwritableFenceBlocksTheSend() throws Exception {
        File dir = tempDir("hardening-fence-unwritable-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        File blocked = new File(dir, "refresh-uncertain.fence");
        check(blocked.mkdir(), "the fixture needs a directory where the fence belongs");
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        final int[] exchanges = {0};
        server.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
        try {
            invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                    new JSONObject(firstAccount(server, "cline").toString()));
            throw new AssertionError("an unfenced one-use value was sent");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("围栏"),
                    "the failure names the fence: " + expected.getMessage());
        }
        check(exchanges[0] == 0, "the transport was never reached, got " + exchanges[0]);
        check(clineDurable().equals(read(durable)), "the account was not touched");
        check(!fenceHit(server, "cline", "old-refresh"), "bookkeeping failure did not fence the account");

        check(blocked.delete(), "the fixture removed the offending directory");
        JSONObject recovered = (JSONObject) invoke(server, "refreshClineAccount",
                new Class<?>[]{JSONObject.class}, new JSONObject(firstAccount(server, "cline").toString()));
        check(exchanges[0] == 1, "a repaired fence lets the same account refresh, got " + exchanges[0]);
        check("rotated-refresh".equals(recovered.optString("refresh")), "the recovered exchange committed");
    }

    static String copilotDurable() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"github-copilot\","
                + "\"protocol\":\"copilot\",\"enabled\":true,\"accounts\":[{\"id\":\"cp_1\",\"enabled\":true,"
                + "\"access\":\"cp-old\",\"token\":\"cp-old\",\"refresh\":\"cp-refresh\",\"expires\":1000}]}]}";
    }

    /**
     * GitHub Copilot mints a Copilot token from a durable GitHub token and never rewrites "refresh"
     * (CopilotAuth.refreshCandidate), so that value is reusable and retrying it is safe. Fencing it
     * would lock a healthy account out, so only an observed rotation is fenced.
     */
    static void copilotNonRotatingRefreshIsNotFenced() throws Exception {
        File dir = tempDir("hardening-copilot-nonrotating-");
        File durable = new File(dir, "config.json");
        write(durable, copilotDurable());
        File unwritable = new File(dir, "locked-write-target");
        check(unwritable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(copilotDurable()), unwritable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "cp-new").put("token", "cp-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            invoke(server, "ensureStoredToken", new Class<?>[]{String.class, JSONObject.class, String.class},
                    "github-copilot", new JSONObject(firstAccount(server, "github-copilot").toString()), null);
            throw new AssertionError("an unsaved Copilot exchange was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("未轮换"),
                    "an unchanged value is reported as retryable: " + expected.getMessage());
            check(!expected.getMessage().contains("请重新登录"),
                    "a reusable value is not reported as an unknown outcome: " + expected.getMessage());
        }
        check(exchanges[0] == 1, "exactly one Copilot exchange, got " + exchanges[0]);
        check(!fenceFile(durable).isFile(), "a non-rotating provider records no fence");

        // Retry with working storage commits and records nothing.
        File healthyDir = tempDir("hardening-copilot-retry-");
        File healthy = new File(healthyDir, "config.json");
        write(healthy, copilotDurable());
        GatewayServer retried = fixture(new JSONObject(copilotDurable()), healthy);
        final int[] retries = {0};
        retried.setTokenRefreshForTests((snapshot, force) -> {
            retries[0]++;
            return new JSONObject(snapshot.toString()).put("access", "cp-new").put("token", "cp-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = (JSONObject) invoke(retried, "ensureStoredToken",
                new Class<?>[]{String.class, JSONObject.class, String.class},
                "github-copilot", new JSONObject(firstAccount(retried, "github-copilot").toString()), null);
        check(retries[0] == 1 && "cp-new".equals(committed.optString("access")),
                "the reusable value is exchanged and committed: " + committed);
        check(!fenced(healthy, "github-copilot", "cp-refresh"), "an unchanged value is not fenced");

        // A Copilot build that did rotate loses the old value, and the new one is kept in memory
        // instead of being dropped on the floor: a second attempt finishes that write, and never
        // puts the spent value back on the wire to get there.
        File rotatedDir = tempDir("hardening-copilot-rotated-");
        File rotatedDurable = new File(rotatedDir, "config.json");
        write(rotatedDurable, copilotDurable());
        File rotatedLocked = new File(rotatedDir, "locked-write-target");
        check(rotatedLocked.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer rotating = fixture(new JSONObject(copilotDurable()), rotatedLocked);
        rotating.setTokenRefreshForTests((snapshot, force) ->
                new JSONObject(snapshot.toString()).put("access", "cp-new").put("token", "cp-new")
                        .put("refresh", "cp-rotated").put("expires", System.currentTimeMillis() + 3600000L));
        try {
            invoke(rotating, "ensureStoredToken", new Class<?>[]{String.class, JSONObject.class, String.class},
                    "github-copilot", new JSONObject(firstAccount(rotating, "github-copilot").toString()), null);
            throw new AssertionError("an unsaved Copilot rotation was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "a rotation with no durable write says the new value is memory-only: " + expected.getMessage());
            check(!expected.getMessage().contains("cp-rotated") && !expected.getMessage().contains("cp-refresh"),
                    "the report never echoes a credential: " + expected.getMessage());
        }
        check(fenced(rotatedDurable, "github-copilot", "cp-refresh"),
                "an observed rotation is recorded so the old value is never sent again");
        rotating.setTokenRefreshForTests((snapshot, force) -> {
            throw new AssertionError("the rotated Copilot value was replayed");
        });
        try {
            invoke(rotating, "ensureStoredToken", new Class<?>[]{String.class, JSONObject.class, String.class},
                    "github-copilot", new JSONObject(firstAccount(rotating, "github-copilot").toString()), null);
            throw new AssertionError("an unsaved rotation disappeared without saying so");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the held value is reported again, not silently forgotten: " + expected.getMessage());
        }
    }

    static String antigravityDurable() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"antigravity\","
                + "\"protocol\":\"antigravity\",\"enabled\":true,\"accounts\":[{\"id\":\"ag_1\",\"enabled\":true,"
                + "\"access\":\"ag-old\",\"token\":\"ag-old\",\"refresh\":\"ag-refresh\",\"expires\":1000}]}]}";
    }

    /** A Google refresh that returns no new refresh token is still current, so it is released. */
    static void antigravitySameValueIsReleasedAfterCommit() throws Exception {
        File dir = tempDir("hardening-antigravity-release-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = (JSONObject) invoke(server, "ensureStoredToken",
                new Class<?>[]{String.class, JSONObject.class, String.class},
                "antigravity", new JSONObject(firstAccount(server, "antigravity").toString()), null);
        check("ag-new".equals(committed.optString("access")) && exchanges[0] == 1, "the same-value refresh committed");
        check(!fenced(durable, "antigravity", "ag-refresh"), "a value the provider kept stays usable");
        synchronized (server) { firstAccount(server, "antigravity").put("expires", 1000L); }
        JSONObject second = (JSONObject) invoke(server, "ensureStoredToken",
                new Class<?>[]{String.class, JSONObject.class, String.class},
                "antigravity", new JSONObject(firstAccount(server, "antigravity").toString()), null);
        check(exchanges[0] == 2, "the released value was exchanged again, got " + exchanges[0]);
        check("ag-new".equals(second.optString("access")), "the second cycle committed: " + second);
    }

    static String raccoonDurable() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"raccoon\","
                + "\"protocol\":\"raccoon\",\"enabled\":true,\"keys\":[\"old-access\"],"
                + "\"accounts\":[{\"id\":\"rc_1\",\"enabled\":true,\"source\":\"browser\",\"type\":\"oauth\","
                + "\"access\":\"old-access\",\"token\":\"old-access\",\"refresh\":\"old-refresh\",\"expires\":1000}]}]}";
    }
    static RaccoonRefreshService.Transport raccoonRotating(int[] exchanges) {
        return (path, body) -> {
            exchanges[0]++;
            return new RaccoonRefreshService.Response(200, new JSONObject().put("data", new JSONObject()
                    .put("access_token", "rc-new").put("refresh_token", "rc-rotated").put("expires_in", 3600)));
        };
    }

    static void raccoonLostRotationIsFencedNotReplayed() throws Exception {
        File dir = tempDir("hardening-raccoon-fence-");
        File durable = new File(dir, "config.json");
        write(durable, raccoonDurable());
        File unwritable = new File(dir, "locked-write-target");
        check(unwritable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(raccoonDurable()), unwritable);
        final int[] exchanges = {0};
        server.setRaccoonRefreshTransportForTests(raccoonRotating(exchanges));
        try {
            invoke(server, "ensureRaccoonToken",
                    new Class<?>[]{String.class, String.class, int.class}, "rc_1", null, 0);
            throw new AssertionError("an unsaved Raccoon rotation was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("请重新登录"),
                    "the lost rotation asks for a sign-in: " + expected.getMessage());
        }
        check(exchanges[0] == 1, "exactly one Raccoon exchange, got " + exchanges[0]);
        check(fenced(durable, "raccoon", "old-refresh"), "the spent value is fenced");
        check(raccoonDurable().equals(read(durable)), "the durable Raccoon account is untouched");
        try {
            invoke(server, "ensureRaccoonToken", new Class<?>[]{String.class, String.class, int.class},
                    "rc_1", null, 0);
            throw new AssertionError("the spent Raccoon refresh token was replayed");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("请重新登录"),
                    "the replay stops with a sign-in request: " + expected.getMessage());
        }
        check(exchanges[0] == 1, "no second Raccoon exchange, got " + exchanges[0]);
        GatewayServer restarted = fixture(new JSONObject(read(durable)), durable);
        restarted.setRaccoonRefreshTransportForTests((path, body) -> {
            throw new AssertionError("the spent Raccoon refresh token was replayed after a restart");
        });
        try {
            invoke(restarted, "ensureRaccoonToken", new Class<?>[]{String.class, String.class, int.class},
                    "rc_1", null, 0);
            throw new AssertionError("a restart treated the spent Raccoon credential as refreshable");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("请重新登录"),
                    "a restart still asks for a sign-in: " + expected.getMessage());
        }

        // Control: with storage available the rotation commits and the replaced value stays fenced.
        File healthyDir = tempDir("hardening-raccoon-saved-");
        File healthy = new File(healthyDir, "config.json");
        write(healthy, raccoonDurable());
        GatewayServer saved = fixture(new JSONObject(raccoonDurable()), healthy);
        final int[] savedExchanges = {0};
        saved.setRaccoonRefreshTransportForTests(raccoonRotating(savedExchanges));
        JSONObject savedResult = (JSONObject) invoke(saved, "ensureRaccoonToken",
                new Class<?>[]{String.class, String.class, int.class}, "rc_1", null, 0);
        check(savedExchanges[0] == 1 && "rc-new".equals(savedResult.optString("access")),
                "a saved Raccoon rotation is returned: " + savedResult);
        check("rc-rotated".equals(durableAccount(healthy, "raccoon").optString("refresh")),
                "a saved Raccoon rotation is durable before it is returned");
        check(fenced(healthy, "raccoon", "old-refresh"), "the replaced value is fenced");
        check(!fenced(healthy, "raccoon", "rc-rotated"), "the new value is not fenced");

        // Control: a response that keeps the old refresh token spent nothing, so retry stays open.
        File omitDir = tempDir("hardening-raccoon-omit-");
        File omitDurable = new File(omitDir, "config.json");
        write(omitDurable, raccoonDurable());
        File omitLocked = new File(omitDir, "locked-write-target");
        check(omitLocked.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer omit = fixture(new JSONObject(raccoonDurable()), omitLocked);
        final int[] omitExchanges = {0};
        omit.setRaccoonRefreshTransportForTests((path, body) -> {
            omitExchanges[0]++;
            return new RaccoonRefreshService.Response(200, new JSONObject().put("data",
                    new JSONObject().put("access_token", "rc-new").put("expires_in", 3600)));
        });
        try {
            invoke(omit, "ensureRaccoonToken", new Class<?>[]{String.class, String.class, int.class},
                    "rc_1", null, 0);
            throw new AssertionError("an unsaved Raccoon exchange was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("未轮换"),
                    "the unchanged value is reported as retryable: " + expected.getMessage());
        }
        check(!fenced(omitDurable, "raccoon", "old-refresh"), "a value the provider kept is not fenced");
    }

    static String qoderDurable() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"qoder_cn\","
                + "\"protocol\":\"qoder\",\"enabled\":true,\"models\":[\"old-model\"],"
                + "\"modelMeta\":{\"old-model\":{}},\"accounts\":[{\"id\":\"qd_1\",\"enabled\":true,"
                + "\"uid\":\"uid-1\",\"token\":\"old-access\",\"refresh\":\"old-refresh\","
                + "\"deviceToken\":\"old-access\",\"deviceRefresh\":\"old-refresh\",\"deviceChat\":true,"
                + "\"expiresAt\":1000}]}]}";
    }

    /** The real Qoder sync must fence before the refresh leaves the process. */
    static void qoderSyncFencesBeforeRefreshAndNeverReplays() throws Exception {
        File dir = tempDir("hardening-qoder-sync-");
        File durable = new File(dir, "config.json");
        write(durable, qoderDurable());
        File unwritable = new File(dir, "locked-write-target");
        check(unwritable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(qoderDurable()), unwritable);
        // The fixture bypasses the constructor, so the sync lock has to be supplied explicitly.
        setField(server, "qoderSyncLock", new Object());
        final java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
        final boolean[] fencedAtSend = {false};
        com.sun.net.httpserver.HttpServer upstream = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            boolean refresh = exchange.getRequestURI().getPath().contains("refresh");
            String wire;
            if (refresh) {
                refreshes.incrementAndGet();
                try { fencedAtSend[0] = fenced(durable, "qoder_cn", "old-refresh"); }
                catch (Exception unreadable) { fencedAtSend[0] = false; }
                wire = "{\"token\":\"qd-new\",\"refresh_token\":\"qd-rotated\",\"expires_in\":3600}";
            } else wire = "{\"app\":[{\"key\":\"m1\",\"enable\":true,\"price_factor\":0}]}";
            byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        upstream.start();
        try {
            final int port = upstream.getAddress().getPort();
            server.setQoderConnectionsForTests(url -> (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + port + (url.contains("refresh") ? "/refresh" : "/models")).openConnection());
            String first = syncBody(server);
            check(first.contains("请重新登录") || first.contains("刷新结果未知"),
                    "the unsaved Qoder rotation is reported: " + first);
            check(refreshes.get() == 1, "exactly one Qoder refresh, got " + refreshes.get());
            check(fencedAtSend[0], "the fence was durable before the Qoder refresh was sent");
            check(qoderDurable().equals(read(durable)), "the durable Qoder account is untouched");
            String second = syncBody(server);
            check(refreshes.get() == 1, "the spent Qoder value was replayed: " + refreshes.get());
            check(second.contains("请重新登录"), "the blocked sync asks for a sign-in: " + second);
        } finally { upstream.stop(0); }
    }

    /** The client-facing answer names the sign-in requirement, not just a missing credential. */
    static void fencedCredentialIsReportedToTheClient() throws Exception {
        File dir = tempDir("hardening-cline-answer-");
        File durable = new File(dir, "config.json");
        String config = "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"setup\":{\"done\":true},"
                + "\"privacy\":{\"enabled\":false},"
                + "\"providers\":[{\"id\":\"cline\",\"protocol\":\"openai\","
                + "\"baseUrl\":\"https://api.cline.bot/api/v1\",\"models\":[\"c-line\"],"
                + "\"accounts\":[{\"id\":\"cline_1\",\"enabled\":true,\"type\":\"oauth\","
                + "\"token\":\"old-access\",\"access\":\"old-access\",\"refresh\":\"old-refresh\",\"expires\":1000}]}]}";
        write(durable, config);
        File unwritable = new File(dir, "locked-write-target");
        check(unwritable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(config), unwritable);
        final int[] exchanges = {0};
        server.setClineRefreshForTests(clineRotating(exchanges, "rotated-access", "rotated-refresh"));
        String chat = "{\"model\":\"cline/c-line\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        Reply first = call(server, "127.0.0.1", "/v1/chat/completions", "POST", chat, "fixture-strong-key");
        check(exchanges[0] == 1, "exactly one exchange for the first request, got " + exchanges[0]);
        check(first.status >= 400, "a lost rotation does not answer 200, got " + first.status);
        check(first.body.contains("请重新登录"), "the answer names the sign-in requirement: " + first.body);
        check(!first.body.contains("rotated-refresh") && !first.body.contains("rotated-access"),
                "the answer does not echo credentials: " + first.body);
        Reply second = call(server, "127.0.0.1", "/v1/chat/completions", "POST", chat, "fixture-strong-key");
        check(exchanges[0] == 1, "the second request replayed the spent value, got " + exchanges[0]);
        check(second.status >= 400, "the fenced route does not answer 200, got " + second.status);
        check(second.body.contains("请重新登录"), "the fenced answer still asks for a sign-in: " + second.body);
        java.lang.reflect.Field hint = GatewayServer.class.getDeclaredField("ROUTE_SIGN_IN_HINT");
        hint.setAccessible(true);
        check(((ThreadLocal<?>) hint.get(null)).get() == null, "the sign-in hint was left behind on the thread");
        Reply models = call(server, "127.0.0.1", "/v1/models", "GET", "", "fixture-strong-key");
        check(models.status == 200, "the rest of the gateway still answers, got " + models.status);

        // Control: a saved rotation keeps its route and leaves no sign-in hint behind.
        File healthyDir = tempDir("hardening-cline-answer-ok-");
        File healthy = new File(healthyDir, "config.json");
        write(healthy, config);
        GatewayServer saved = fixture(new JSONObject(config), healthy);
        final int[] savedExchanges = {0};
        saved.setClineRefreshForTests(clineRotating(savedExchanges, "rotated-access", "rotated-refresh"));
        Method resolve = GatewayServer.class.getDeclaredMethod("resolveCandidates", String.class);
        resolve.setAccessible(true);
        check(((List<?>) resolve.invoke(saved, "cline/c-line")).size() == 1, "a saved rotation keeps its route");
        check(savedExchanges[0] == 1, "the healthy fixture refreshed once, got " + savedExchanges[0]);
        check(((ThreadLocal<?>) hint.get(null)).get() == null, "a usable route leaves no sign-in hint behind");
    }

    // ================= E: send-stage evidence may only release what provably never left =========
    /**
     * 围栏的唯一放宽点：传输层自己声明了「凭据一个字节都没交付」。这一组用例把四个供应商的证据
     * 逐个钉住 —— Qoder／小浣熊／Antigravity 只认「链路都没建起来」；Cline 多一条前提：ClineAuth
     * 在发请求之前当场核对过凭据只在请求体里（{@code BODY_ONLY}），所以 {@code open} 与
     * {@code connect}（有链路、没写体，HttpURLConnection 此时只送了请求头，头里没有凭据）都解围栏。
     * 写请求、读完没结果、已答复、未声明证据、崩溃，全部保留。整组用例离线运行，只用注入的失败
     * 与本机环回，不触外网、不用真实账号。
     */
    static boolean gate(String provider, Throwable failure) throws Exception {
        return (Boolean) staticCall("refreshSendProvenNotSent",
                new Class<?>[]{String.class, Throwable.class}, provider, failure);
    }
    static boolean clineReleasable(ClineAuth.RefreshReport report) throws Exception {
        return (Boolean) staticCall("clineRefreshProvenNotSent",
                new Class<?>[]{ClineAuth.RefreshReport.class}, report);
    }
    /** Cline 的定型失败：默认带着 ClineAuth 发请求前核对出来的「凭据只在请求体里」。 */
    static ClineAuth.RefreshReport clineReportWith(ClineAuth.RefreshDelivery delivery, String stage,
                                                   boolean writeStarted, boolean written) throws Exception {
        return clineReportWith(delivery, stage, writeStarted, written, ClineAuth.CredentialChannel.BODY_ONLY);
    }
    static ClineAuth.RefreshReport clineReportWith(ClineAuth.RefreshDelivery delivery, String stage,
                                                   boolean writeStarted, boolean written,
                                                   ClineAuth.CredentialChannel channel) throws Exception {
        JSONObject account = new JSONObject().put("expires", 1000L).put("refresh", "fixture-refresh")
                .put("access", "fixture-access").put("token", "fixture-access");
        return ClineAuth.refreshReport(account, true, token -> {
            throw new ClineAuth.ClineRefreshException(delivery, stage, 0, writeStarted, written, channel, null);
        });
    }
    static AntigravityAuth.SendFailure antigravitySend(AntigravityAuth.SendStage stage,
                                                       boolean writeAttempted) {
        return new AntigravityAuth.SendFailure(stage, 0, 0, false,
                "https://oauth2.googleapis.com/token", 1, "fixture transport failure", null, writeAttempted);
    }
    static QoderAuth.RefreshSendFailure qoderSend(QoderAuth.RefreshSendStage stage) {
        return new QoderAuth.RefreshSendFailure("Qoder refresh failed: fixture", "fixture", stage,
                0L, 0L, false, false, null);
    }
    /** A loopback port nothing listens on: connecting fails inside getOutputStream. */
    static int closedLoopbackPort() throws Exception {
        java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        try { return socket.getLocalPort(); } finally { socket.close(); }
    }
    /**
     * 一条由夹具决定「哪一段线路失败」的连接：getOutputStream 抛异常、写请求失败、读状态行失败，
     * 三种情形都必须让围栏保持。真实的 HttpURLConnection 只能通过端口断链间接制造这些错误，
     * 无法固定到具体阶段，所以这里用最小子类把阶段钉死。
     */
    static final class FakeWire extends java.net.HttpURLConnection {
        private final java.io.OutputStream out;
        private final boolean readFails;
        FakeWire(String url, java.io.OutputStream out, boolean readFails) throws java.io.IOException {
            super(new java.net.URL(url));
            this.out = out;
            this.readFails = readFails;
        }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public java.io.OutputStream getOutputStream() throws java.io.IOException {
            if (out == null) throw new java.io.IOException("fixture: request stream unavailable");
            return out;
        }
        @Override public int getResponseCode() throws java.io.IOException {
            if (readFails) throw new java.io.IOException("fixture: no status line");
            return 200;
        }
        @Override public long getContentLengthLong() { return -1L; }
        @Override public java.io.InputStream getErrorStream() { return null; }
        @Override public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
    }
    static java.io.OutputStream failingSink() {
        return new java.io.OutputStream() {
            @Override public void write(int b) throws java.io.IOException { throw new java.io.IOException("fixture: write failed"); }
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("fixture: write failed");
            }
        };
    }
    static java.io.OutputStream silentSink() {
        return new java.io.OutputStream() { @Override public void write(int b) { } };
    }
    static JSONObject refreshAntigravity(GatewayServer server) throws Exception {
        return (JSONObject) invoke(server, "ensureStoredToken",
                new Class<?>[]{String.class, JSONObject.class, String.class},
                "antigravity", new JSONObject(firstAccount(server, "antigravity").toString()), null);
    }
    static JSONObject refreshRaccoon(GatewayServer server) throws Exception {
        return (JSONObject) invoke(server, "ensureRaccoonToken",
                new Class<?>[]{String.class, String.class, int.class}, "rc_1", null, 0);
    }
    static JSONObject refreshCline(GatewayServer server) throws Exception {
        return (JSONObject) invoke(server, "refreshClineAccount", new Class<?>[]{JSONObject.class},
                new JSONObject(firstAccount(server, "cline").toString()));
    }

    /** 证据判定矩阵：每个供应商只有一个「未写字节的前置阶段」解围栏，其余一律不解。 */
    static void sendEvidenceGateAcceptsOnlyUnwrittenPreSendStages() throws Exception {
        check(gate("raccoon", RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_CONNECTED, 0,
                        "fixture", new java.net.ConnectException("fixture refused"))),
                "a link that never opened releases the Raccoon value");
        check(!gate("raccoon", RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITING, 0, 60,
                        "fixture", null)), "a zero-byte write attempt never releases");
        check(!gate("raccoon", new java.io.IOException("fixture plain")),
                "an undeclared transport error never releases");
        check(!gate("raccoon", null), "a missing failure never releases");

        check(gate("antigravity", antigravitySend(AntigravityAuth.SendStage.NOT_CONNECTED, false)),
                "an unconnected Antigravity link releases");
        check(!gate("antigravity", antigravitySend(AntigravityAuth.SendStage.NOT_CONNECTED, true)),
                "an Antigravity link that accepted bytes never releases");
        check(!gate("antigravity", antigravitySend(AntigravityAuth.SendStage.WRITTEN, true)),
                "a written Antigravity request never releases");
        check(!gate("antigravity", antigravitySend(AntigravityAuth.SendStage.UNKNOWN, false)),
                "an unclassified Antigravity failure never releases");

        check(gate("qoder_cn", qoderSend(QoderAuth.RefreshSendStage.NOT_CONNECTED)),
                "a Qoder factory refusal releases");
        check(!gate("qoder_cn", qoderSend(QoderAuth.RefreshSendStage.CONNECTED)),
                "the Qoder CONNECTED stage covers getOutputStream, so it never releases");
        check(!gate("qoder_cn", qoderSend(QoderAuth.RefreshSendStage.WRITING)),
                "a Qoder write attempt never releases");
        check(!gate("qoder_cn", new java.io.IOException("fixture plain")),
                "an unrecognised Qoder failure never releases");

        check(!gate("cline", new java.io.IOException("fixture plain")),
                "Cline never goes through the stage gate");
        check(!gate("minimax-code", new java.io.IOException("fixture plain")),
                "a provider without declared evidence never releases");

        check(clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "precondition", false, false)),
                "a Cline request that was never built releases");
        check(clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "open", false, false)),
                "a Cline link object that was never obtained releases");
        check(clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "connect", false, false)),
                "a Cline getOutputStream failure releases: the headers carry no credential");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "connect", false, false,
                        ClineAuth.CredentialChannel.UNKNOWN)),
                "without the body-only premise the connect stage proves nothing");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "write", true, false)),
                "a started Cline body write keeps the fence");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.NOT_SENT, "write", false, true)),
                "a completed Cline body write keeps the fence whatever the delivery name says");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.POSSIBLY_SENT, "write", true, false)),
                "a started Cline body write keeps the fence");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.POSSIBLY_SENT, "response", true, true)),
                "a Cline request with no status line keeps the fence");
        check(!clineReleasable(clineReportWith(ClineAuth.RefreshDelivery.SENT, "status", true, true)),
                "an answered Cline exchange keeps the fence");
        check(clineReleasable(ClineAuth.refreshReport(null, true)),
                "a Cline report that never ran the transport releases");
        check(!clineReleasable(ClineAuth.refreshReport(
                        new JSONObject().put("expires", 1000L).put("refresh", "fixture-refresh")
                                .put("access", "fixture-access").put("token", "fixture-access"),
                        true, token -> {
                            ClineAuth.SignIn next = new ClineAuth.SignIn();
                            next.access = "next-access"; next.refresh = "next-refresh";
                            next.expires = System.currentTimeMillis() + 3600000L;
                            return next;
                        })),
                "a completed exchange is settled, never released");
    }

    /** Two Antigravity accounts, so a rotation for one can be shown not to reach the other. */
    static String antigravityPair() {
        return "{\"server\":{\"authKey\":\"fixture-strong-key\"},\"providers\":[{\"id\":\"antigravity\","
                + "\"protocol\":\"antigravity\",\"enabled\":true,\"accounts\":["
                + "{\"id\":\"ag_1\",\"enabled\":true,\"access\":\"ag-old\",\"token\":\"ag-old\","
                + "\"refresh\":\"ag-refresh\",\"expires\":1000},"
                + "{\"id\":\"ag_2\",\"enabled\":true,\"access\":\"ag2-old\",\"token\":\"ag2-old\","
                + "\"refresh\":\"ag2-refresh\",\"expires\":1000}]}]}";
    }
    static JSONObject accountOf(JSONObject root, String provider, int index) throws Exception {
        JSONArray providers = root.getJSONArray("providers");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject candidate = providers.getJSONObject(i);
            if (provider.equals(candidate.optString("id"))) return candidate.getJSONArray("accounts").getJSONObject(index);
        }
        throw new AssertionError("provider missing: " + provider);
    }
    static JSONObject secondAccount(GatewayServer server) throws Exception {
        return accountOf((JSONObject) field(server, "configJson"), "antigravity", 1);
    }

    /**
     * Antigravity 的 refresh_token 按上游语义是可复用的（magpie 的 provider/google.go 把 access
     * token 只刷在内存里，注释写明 "Google's refresh tokens don't turn over"，失败后仍然重发同一个
     * refresh_token），所以「请求发出去、答案没回来」不能把账号永久锁死：这正是旧版预写围栏做的事。
     *
     * <p>负对照在同一个方法里：链路都没建起来（可证明没发）与写完等状态行读超时（可能已发）两种
     * 失败，都必须允许按上游语义再试一次；真正一次性凭据的供应商仍由
     * {@code clineLostAnswerKeepsFenceAndNeverReplays} 等用例钉住围栏不放。
     */
    static void antigravityLostAnswerIsRetriedNotLocked() throws Exception {
        File dir = tempDir("hardening-antigravity-timeout-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        final int[] exchanges = {0};
        // 第一跳：链路没建起来（可证明一个字节都没写）。
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            throw antigravitySend(AntigravityAuth.SendStage.NOT_CONNECTED, false);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a refresh that was never sent was reported as success");
        } catch (java.io.IOException expected) {
            check(exchanges[0] == 1, "exactly one exchange, got " + exchanges[0]);
            check(!expected.getMessage().contains("请重新登录"),
                    "an unsent refresh is retryable, not a sign-in demand: " + expected.getMessage());
        }
        check(!fenced(durable, "antigravity", "ag-refresh"), "an unsent value is not fenced before it is sent");

        // 第二跳：请求整体写出、等状态行时读超时 —— 上游可能已经处理过这次交换。
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            throw antigravitySend(AntigravityAuth.SendStage.WRITTEN, true);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a timed-out refresh was reported as success");
        } catch (java.io.IOException expected) {
            check(exchanges[0] == 2, "the timed-out attempt reached the transport, got " + exchanges[0]);
            check(expected.getMessage().contains("fixture transport failure"),
                    "the transport verdict travels unchanged: " + expected.getMessage());
            check(!expected.getMessage().contains("请重新登录"),
                    "a read timeout is not a sign-in demand: " + expected.getMessage());
        }
        check(!fenced(durable, "antigravity", "ag-refresh"),
                "a lost answer does not fence a value upstream keeps");
        check(!fenceHit(server, "antigravity", "ag-refresh"), "no fence record blocks the retry");

        // 第三跳：网络恢复，同一个值按上游的做法再试一次就成功了。
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 3, "the retry reused the same value, got " + exchanges[0]);
        check("ag-new".equals(committed.optString("access")), "the retry committed: " + committed);
        check("ag-refresh".equals(durableAccount(durable, "antigravity").optString("refresh")),
                "an answer without a new refresh token keeps the stored one: "
                        + durableAccount(durable, "antigravity"));
        check(!fenced(durable, "antigravity", "ag-refresh"), "a value the provider kept stays usable");
    }

    /**
     * 旧版本留下的预写记录（一次读超时写下的那一条）不能再锁住账号，而同一份围栏里真正一次性
     * 凭据的记录必须原样保留 —— 放开的是供应商语义，不是围栏本身。
     */
    static void antigravityLegacyFenceDoesNotLockTheAccount() throws Exception {
        File dir = tempDir("hardening-antigravity-legacy-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        write(fenceFile(durable), "antigravity " + fenceDigest("antigravity", "ag-refresh") + "\n"
                + "cline " + fenceDigest("cline", "old-refresh") + "\n");
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        check(fenceHit(server, "antigravity", "ag-refresh"), "the fixture really holds a legacy record");
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 1, "the legacy record did not block the exchange, got " + exchanges[0]);
        check("ag-new".equals(committed.optString("access")), "the account works again: " + committed);
        check(!fenced(durable, "antigravity", "ag-refresh"),
                "a value Google kept is released instead of locking the account forever");
        check(fenced(durable, "cline", "old-refresh"),
                "the record of a provider whose value really is one-use is kept");
    }

    /**
     * 上游真的换了新 refresh 值时，旧值立刻记为已作废，新值在写盘失败后必须留在内存里等下一次写入，
     * 既不能静默丢掉，也不能写到别的账号上。
     */
    static void antigravityObservedRotationIsHeldWhenTheWriteFails() throws Exception {
        File dir = tempDir("hardening-antigravity-rotation-");
        File durable = new File(dir, "config.json");
        check(durable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(antigravityPair()), durable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a rotation that was not stored was reported as success");
        } catch (java.io.IOException expected) {
            check(exchanges[0] == 1, "exactly one exchange, got " + exchanges[0]);
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the lost write is stated plainly: " + expected.getMessage());
            check(!expected.getMessage().contains("ag-rotated") && !expected.getMessage().contains("ag-refresh"),
                    "the report never echoes a credential: " + expected.getMessage());
        }
        check(fenced(durable, "antigravity", "ag-refresh"), "the replaced value is recorded as spent");
        check(!fenced(durable, "antigravity", "ag-rotated"), "a value that was never sent is not fenced");
        check("ag-refresh".equals(firstAccount(server, "antigravity").optString("refresh")),
                "the failed write did not publish the new value in memory either");
        check("ag2-refresh".equals(secondAccount(server).optString("refresh")),
                "the held rotation was not written onto another account");

        // 写目标恢复：下一跳先把这条待写轮换写完，不必再拿已作废的旧值去交换。
        File target = new File(dir, "config.json.works");
        setField(server, "configFile", target);
        server.setTokenRefreshForTests((snapshot, force) -> {
            throw new AssertionError("the spent Antigravity value was exchanged again");
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 1, "the held rotation was finished without another exchange, got " + exchanges[0]);
        check("ag-new".equals(committed.optString("access")), "the held answer is used: " + committed);
        check("ag-rotated".equals(durableAccount(target, "antigravity").optString("refresh")),
                "the new refresh value became durable: " + durableAccount(target, "antigravity"));
        check("ag2-refresh".equals(accountOf(new JSONObject(read(target)), "antigravity", 1).optString("refresh")),
                "the second account is untouched by the first one's rotation");
        check(fenced(durable, "antigravity", "ag-refresh"), "the spent value stays fenced after the write");
        check(!fenced(durable, "antigravity", "ag-rotated"), "the stored value is not fenced");
    }

    /**
     * 负对照：待写轮换只属于产生它的那一代账号。账号在等待期间被重新登录过，旧轮换就必须作废，
     * 不能盖到新凭据上。
     */
    static void antigravityStaleRotationNeverOverwritesANewSignIn() throws Exception {
        File dir = tempDir("hardening-antigravity-generation-");
        File durable = new File(dir, "config.json");
        check(durable.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(antigravityPair()), durable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a rotation that was not stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the failure is stated: " + expected.getMessage());
        }

        // 期间用户重新登录了这个账号：内存里的账号已经是另一代凭据。
        synchronized (server) {
            firstAccount(server, "antigravity").put("access", "ag-manual").put("token", "ag-manual")
                    .put("refresh", "ag-manual-refresh").put("expires", 1000L);
        }
        File target = new File(dir, "config.json.works");
        setField(server, "configFile", target);
        final String[] seen = {""};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            seen[0] = snapshot.optString("refresh", "");
            return new JSONObject(snapshot.toString()).put("access", "ag-manual-access")
                    .put("token", "ag-manual-access").put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 2, "the new sign-in was refreshed once, got " + exchanges[0]);
        check("ag-manual-refresh".equals(seen[0]),
                "the exchange used the new sign-in, not the stale rotation: " + seen[0]);
        check("ag-manual-refresh".equals(durableAccount(target, "antigravity").optString("refresh")),
                "the stale rotation never reached the file: " + durableAccount(target, "antigravity"));
        check(!fenced(durable, "antigravity", "ag-rotated"), "the stale value was never sent, so it is not fenced");
        check("ag-manual-access".equals(committed.optString("access")), "the new sign-in committed: " + committed);
    }

    /** 一次待写轮换的记录：直接走 GatewayServer 真实的记忆入口，返回它是否真的记下了。 */
    static boolean rememberPending(GatewayServer server, String provider, String id,
                                   JSONObject expected, JSONObject candidate) throws Exception {
        return (Boolean) invoke(server, "rememberPendingRotation",
                new Class<?>[]{String.class, String.class, JSONObject.class, JSONObject.class},
                provider, id, expected, candidate);
    }
    static boolean hasPending(GatewayServer server, String provider, String id) throws Exception {
        return (Boolean) invoke(server, "hasPendingRotation", new Class<?>[]{String.class, String.class},
                provider, id);
    }
    static String rotationMessage(boolean retained) throws Exception {
        Object failure = staticCall("refreshRotationNotStored",
                new Class<?>[]{String.class, boolean.class}, "antigravity", retained);
        return ((Exception) failure).getMessage();
    }

    /**
     * 负对照的另一半：一次真正的新登录本身就是厂商给出的「这个值当前有效」的证据（Google 对同一
     * 账号可能发回同一个 refresh 值），所以它必须收回自己那条「已作废」记录，否则用户重新登录后
     * 会被自己上一次的故障永久挡在门外。只认这次登录拿到的那个值，别的一律不动。
     */
    static void freshSignInReleasesItsOwnSpentRecord() throws Exception {
        File dir = tempDir("hardening-ag-signin-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        write(fenceFile(durable),
                "s antigravity " + fenceDigest("antigravity", "ag-refresh") + "\n"
                        + "s antigravity " + fenceDigest("antigravity", "some-other-value") + "\n");
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            throw new AssertionError("a spent value was sent before the sign-in released it");
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("the spent value was accepted before any sign-in");
        } catch (java.io.IOException blocked) {
            check(blocked.getMessage().contains("请重新登录"),
                    "the spent value asks for a sign-in: " + blocked.getMessage());
        }
        check(exchanges[0] == 0, "nothing was sent, got " + exchanges[0]);

        Method add = GatewayServer.class.getDeclaredMethod("addAntigravityAccount", JSONObject.class, String.class);
        add.setAccessible(true);
        int handshakeTries = AntigravityAuth.HANDSHAKE_TRIES;
        AntigravityAuth.HANDSHAKE_TRIES = 1;
        try {
            NetworkPolicy.block();
            // 同邮箱覆盖同一个账号；登录给出的是同一个 refresh 值。
            JSONObject signIn = new JSONObject("{\"email\":\"fixture@example.invalid\","
                    + "\"refresh\":\"ag-refresh\",\"access\":\"ag-signin-access\","
                    + "\"project\":\"fixture-project\",\"expires\":1000}");
            String savedId = (String) add.invoke(server, signIn, "fixture@example.invalid");
            check(GatewayServer.accountAddSucceeded(savedId), "the sign-in was persisted, got " + savedId);
        } finally {
            AntigravityAuth.HANDSHAKE_TRIES = handshakeTries;
            NetworkPolicy.configure(null);
        }
        check(!fenced(durable, "antigravity", "ag-refresh"),
                "the sign-in released the record for the value it just issued");
        check(fenced(durable, "antigravity", "some-other-value"),
                "a record for another value is untouched");

        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-after-signin")
                    .put("token", "ag-after-signin").put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 1, "the account works again, got " + exchanges[0]);
        check("ag-after-signin".equals(committed.optString("access")), "the refresh committed: " + committed);
    }

    /**
     * 缺陷一：待写轮换曾经用「整份账号 JSON 相等」判断归属，账号的名字、项目、模型清单、配额记账
     * 之类与凭据无关的字段一变，这条记录就被静默丢弃，然后拿已被上游换掉的旧 refresh 值再交换一次。
     *
     * <p>这里先用一次写盘失败的轮换把记录立起来（旧值同时记为已作废），再改掉一串非凭据字段：
     * 下一跳必须把新凭据补写落盘并直接返回，既不再交换，也不回滚那些无关修改。
     */
    static void pendingRotationAppliesAcrossUnrelatedChanges() throws Exception {
        File dir = tempDir("hardening-ag-pending-unrelated-");
        File live = new File(dir, "config.json");
        check(live.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(antigravityPair()), live);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a rotation that could not be stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the record says where the new value is: " + expected.getMessage());
        }
        check(exchanges[0] == 1, "exactly one exchange, got " + exchanges[0]);
        check(hasPending(server, "antigravity", "id:ag_1"), "the rotation is held for this account");

        // 与凭据无关的字段变了：名字、项目、模型清单、配额记账。
        synchronized (server) {
            JSONObject account = firstAccount(server, "antigravity");
            account.put("name", "重命名后的账号").put("project", "p-2")
                    .put("models", new JSONArray().put("gemini-x")).put("remaining", 12.5)
                    .put("lastRefresh", 42L);
        }
        File target = new File(dir, "config.json.works");
        setField(server, "configFile", target);
        server.setTokenRefreshForTests((snapshot, force) -> {
            throw new AssertionError("the spent refresh value was sent again after an unrelated change");
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 1, "the held rotation was applied without another exchange, got " + exchanges[0]);
        check("ag-new".equals(committed.optString("access")), "the held answer was used: " + committed);
        check(!"ag-refresh".equals(committed.optString("refresh")),
                "the spent value is no longer published anywhere: " + committed);
        JSONObject saved = durableAccount(target, "antigravity");
        check("ag-rotated".equals(saved.optString("refresh")), "the new refresh value became durable: " + saved);
        check("重命名后的账号".equals(saved.optString("name")), "the unrelated rename was not rolled back: " + saved);
        check("p-2".equals(saved.optString("project")), "the unrelated project change was kept: " + saved);
        check("ag2-refresh".equals(accountOf(new JSONObject(read(target)), "antigravity", 1).optString("refresh")),
                "the first account's rotation never reached the second one");
        check(!hasPending(server, "antigravity", "id:ag_1"), "the finished rotation was retired");
    }

    /**
     * 负对照：换掉 refresh 值就意味着另一代凭据（重新登录、被重新写入）。这时本代的答案必须作废，
     * 既不能覆盖新凭据，也不能把它写到别处 —— 而当时的交换必须使用新的那一代。
     */
    static void pendingRotationIsRetiredByANewGenerationOnly() throws Exception {
        File dir = tempDir("hardening-ag-pending-generation-");
        File live = new File(dir, "config.json");
        check(live.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(antigravityPair()), live);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a rotation that could not be stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the record says where the new value is: " + expected.getMessage());
        }
        check(hasPending(server, "antigravity", "id:ag_1"), "the rotation is held for this account");

        synchronized (server) {
            firstAccount(server, "antigravity").put("access", "ag-relogin-access")
                    .put("token", "ag-relogin-access").put("refresh", "ag-relogin-refresh").put("expires", 1000L);
        }
        File target = new File(dir, "config.json.works");
        setField(server, "configFile", target);
        final String[] seen = {""};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            seen[0] = snapshot.optString("refresh", "");
            return new JSONObject(snapshot.toString()).put("access", "ag-relogin-new").put("token", "ag-relogin-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        JSONObject committed = refreshAntigravity(server);
        check(exchanges[0] == 2, "the new generation was refreshed once, got " + exchanges[0]);
        check("ag-relogin-refresh".equals(seen[0]),
                "the exchange used the new sign-in, not the retired rotation: " + seen[0]);
        check("ag-relogin-refresh".equals(durableAccount(target, "antigravity").optString("refresh")),
                "the retired rotation never reached the file: " + durableAccount(target, "antigravity"));
        check(!read(target).contains("ag-rotated"), "the retired value was written nowhere");
        check("ag2-refresh".equals(accountOf(new JSONObject(read(target)), "antigravity", 1).optString("refresh")),
                "the retired rotation never reached the second account");
        check(!hasPending(server, "antigravity", "id:ag_1"), "the retired rotation is gone");
    }

    /**
     * 缺陷三：观测到轮换后旧值被记进了围栏，但可复用凭据的供应商根本不看那条记录，于是「记录在，
     * 却照样把旧值再发一次」。
     *
     * <p>这里模拟记录仍在、待写快照已随进程消失（新进程读同一份目录）：刷新必须以「请重新登录」
     * 结束，一个字节都不发。同一张表同时钉住三种不该拦的记录 —— 旧版预写记录（两字段）、旧版的
     * unknown（u）与裸记录（无标签）—— 它们都不是「观测到轮换」，不能重新锁死账号。
     */
    static void spentRotationIsNeverResentWithoutItsRecord() throws Exception {
        check(exchangedWithFence("s antigravity " + fenceDigest("antigravity", "ag-refresh") + "\n") == 0,
                "a spent record blocks the replay of the value it names");
        check(exchangedWithFence("u antigravity " + fenceDigest("antigravity", "ag-refresh") + "\n") == 1,
                "an unknown record is not an observation and must not lock the account");
        check(exchangedWithFence("antigravity " + fenceDigest("antigravity", "ag-refresh") + "\n") == 1,
                "a pre-tag reservation is not an observation either");
        check(exchangedWithFence(fenceDigest("antigravity", "ag-refresh") + "\n") == 1,
                "a bare pre-tag record never locks the account");
        check(exchangedWithFence("s antigravity " + fenceDigest("antigravity", "other-value") + "\n") == 1,
                "a record for another value blocks nothing");
        // 围栏读不懂时无法证明任何单个值已作废；为它锁号正是这次要修的缺陷，所以放行（见剩余限制）。
        check(exchangedWithFence("not a fence line\n") == 1,
                "a damaged fence must not lock a provider whose value is reusable");
        check(exchangedWithFence("s cline " + fenceDigest("cline", "old-refresh") + "\n") == 1,
                "another provider's record blocks nothing here");
    }
    /** 一份带指定围栏内容的 Antigravity 夹具：返回 1 表示真的交换了一次，0 表示被拦下。 */
    static int exchangedWithFence(String fence) throws Exception {
        File dir = tempDir("hardening-ag-spent-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        if (fence != null) write(fenceFile(durable), fence);
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            JSONObject committed = refreshAntigravity(server);
            check("ag-new".equals(committed.optString("access")), "the refresh committed: " + committed);
            check(exchanges[0] == 1, "exactly one exchange, got " + exchanges[0]);
            return 1;
        } catch (java.io.IOException blocked) {
            check(exchanges[0] == 0, "a blocked refresh must not have reached the transport, got " + exchanges[0]);
            check(blocked.getMessage().contains("请重新登录"),
                    "the block asks for a sign-in: " + blocked.getMessage());
            check(blocked.getMessage().contains("未发送"), "the block says nothing was sent: " + blocked.getMessage());
            return 0;
        }
    }

    /**
     * 那条「已作废」的记录必须经得起重启，也必须经得起围栏写不进去：前者决定跨进程不重放，
     * 后者决定本进程内不会因为「写失败了就当没发生过」而把废凭据再发一次。
     */
    static void spentRecordSurvivesARestartAndAnUnwritableFence() throws Exception {
        // (a) 真实往返：noteSpent 写下的内容由新进程重新读回。
        File dir = tempDir("hardening-ag-spent-reload-");
        File live = new File(dir, "config.json");
        check(live.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer first = fixture(new JSONObject(antigravityDurable()), live);
        final int[] exchanges = {0};
        first.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(first);
            throw new AssertionError("a rotation that could not be stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the record says where the new value is: " + expected.getMessage());
        }
        String durableFence = fenceText(new File(dir, "config.json.works"));
        check(durableFence.contains("s antigravity " + fenceDigest("antigravity", "ag-refresh")),
                "the written record is tagged as an observation, not a guess: " + durableFence);

        check(live.delete(), "the fixture released the unwritable target");
        write(live, antigravityDurable());
        GatewayServer restarted = fixture(new JSONObject(antigravityDurable()), live);
        final int[] replayed = {0};
        restarted.setTokenRefreshForTests((snapshot, force) -> {
            replayed[0]++;
            throw new AssertionError("a value observed as replaced was replayed after a restart");
        });
        try {
            refreshAntigravity(restarted);
            throw new AssertionError("the spent value was accepted after a restart");
        } catch (java.io.IOException blocked) {
            check(blocked.getMessage().contains("请重新登录"),
                    "a restarted gateway asks for a sign-in: " + blocked.getMessage());
            check(blocked.getMessage().contains("未发送"), "and says nothing was sent: " + blocked.getMessage());
        }
        check(replayed[0] == 0, "nothing reached the transport after the restart, got " + replayed[0]);

        // (b) 围栏本身写不进去时，记录留在内存里，本进程内照样不许重放。
        File fenceDir = tempDir("hardening-ag-spent-memory-");
        File fenceLive = new File(fenceDir, "config.json");
        check(fenceLive.mkdir(), "the fixture needs an unwritable live target");
        File blockedFence = new File(fenceDir, "refresh-uncertain.fence");
        check(blockedFence.mkdir(), "the fixture needs an unwritable fence path");
        GatewayServer memoryOnly = fixture(new JSONObject(antigravityDurable()), fenceLive);
        final int[] memoryExchanges = {0};
        memoryOnly.setTokenRefreshForTests((snapshot, force) -> {
            memoryExchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(memoryOnly);
            throw new AssertionError("a rotation that could not be stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the record still says where the new value is: " + expected.getMessage());
        }
        check(!fenceFile(fenceLive).isFile(), "the fence really could not be written");
        check((Boolean) invoke(memoryOnly, "refreshValueRecordedAsSpent",
                        new Class<?>[]{String.class, String.class}, "antigravity", "ag-refresh"),
                "a spent value stays known in this process even when the fence cannot be written");
        // 把待写快照撤掉，只留内存里的那条记录：本进程内也必须拦住。
        invoke(memoryOnly, "forgetPendingRotation", new Class<?>[]{String.class, String.class},
                "antigravity", "id:ag_1");
        File writable = new File(fenceDir, "config.json.works");
        setField(memoryOnly, "configFile", writable);
        memoryOnly.setTokenRefreshForTests((snapshot, force) -> {
            memoryExchanges[0]++;
            throw new AssertionError("a spent value was replayed because its fence write failed");
        });
        try {
            refreshAntigravity(memoryOnly);
            throw new AssertionError("the spent value was accepted after the fence write failed");
        } catch (java.io.IOException blocked) {
            check(blocked.getMessage().contains("请重新登录"),
                    "the in-memory record still asks for a sign-in: " + blocked.getMessage());
        }
        check(memoryExchanges[0] == 1, "no replay happened, got " + memoryExchanges[0]);
    }

    /**
     * 缺陷二：remember 曾经吞掉深拷贝失败，调用方却仍然告诉用户「新凭据只保留在本进程内存中」。
     * 现在它必须回报是否真的记下了，措辞也必须跟着事实走。
     */
    static void pendingRetentionIsReportedHonestly() throws Exception {
        File dir = tempDir("hardening-ag-retention-");
        File durable = new File(dir, "config.json");
        write(durable, antigravityDurable());
        GatewayServer server = fixture(new JSONObject(antigravityDurable()), durable);
        JSONObject expected = new JSONObject(firstAccount(server, "antigravity").toString());
        JSONObject candidate = new JSONObject(expected.toString()).put("refresh", "ag-rotated")
                .put("access", "ag-new").put("token", "ag-new");
        check(rememberPending(server, "antigravity", "id:ag_1", expected, candidate),
                "a normal snapshot is retained");
        check(hasPending(server, "antigravity", "id:ag_1"), "the record exists after the call");

        // 一个 JSON 表达不出来的值会让快照根本建不出来：这时不能声称保留。
        JSONObject unbuildable = new JSONObject().put("id", "ag_1").put("refresh", "ag-rotated")
                .put("access", "ag-new").put("quota", new float[]{Float.NaN});
        check(unbuildable.toString() == null, "the fixture really cannot be serialised");
        check(!rememberPending(server, "antigravity", "id:ag_2", expected, unbuildable),
                "a snapshot that cannot be built is reported as not retained");
        check(!hasPending(server, "antigravity", "id:ag_2"), "and nothing was left behind for that account");
        check(!rememberPending(server, "antigravity", "id:ag_3", expected, null),
                "a missing candidate is reported as not retained");

        String held = rotationMessage(true);
        String lost = rotationMessage(false);
        check(held.contains("只保留在本进程内存中") && held.contains("没有写入任何其他账号"),
                "a retained rotation says where it lives and that it went nowhere else: " + held);
        check(lost.contains("无法保留") && !lost.contains("只保留在本进程内存中"),
                "a lost rotation never claims to be retained: " + lost);
        check(lost.contains("请重新登录") && held.contains("进程重启后需要重新登录"),
                "the not-retained case demands a sign-in now, the retained one after a restart: " + lost);
        for (String message : new String[]{held, lost}) {
            check(!message.contains("ag-rotated") && !message.contains("ag-refresh"),
                    "the report never echoes a credential: " + message);
        }
    }

    /**
     * 并发：同一账号的两条请求同时遇到这条待写轮换。只能有一次写入，两边都必须拿到同一份新凭据，
     * 谁都不许再发旧值，也不许出现「一个成功一个报错」。
     */
    static void concurrentPendingRotationIsFinishedOnce() throws Exception {
        File dir = tempDir("hardening-ag-pending-race-");
        File live = new File(dir, "config.json");
        check(live.mkdir(), "the fixture needs an unwritable live target");
        GatewayServer server = fixture(new JSONObject(antigravityPair()), live);
        final int[] exchanges = {0};
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            return new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                    .put("refresh", "ag-rotated").put("expires", System.currentTimeMillis() + 3600000L);
        });
        try {
            refreshAntigravity(server);
            throw new AssertionError("a rotation that could not be stored was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("新凭据只保留在本进程内存中"),
                    "the record says where the new value is: " + expected.getMessage());
        }
        // 与凭据无关的字段在等待期间被改过（例如目录同步写了一版模型清单）：旧实现正是因此把记录
        // 静默丢掉并重发旧值，所以这场竞争必须在这种状态下发生才算数。
        synchronized (server) {
            firstAccount(server, "antigravity").put("models", new JSONArray().put("gemini-race"))
                    .put("name", "竞争中的账号");
        }
        final File target = new File(dir, "config.json.works");
        setField(server, "configFile", target);
        server.setTokenRefreshForTests((snapshot, force) -> {
            exchanges[0]++;
            throw new AssertionError("the spent refresh value was sent again during the race");
        });

        final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        final JSONObject[] results = new JSONObject[2];
        final Throwable[] failures = new Throwable[2];
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    results[index] = refreshAntigravity(server);
                } catch (Throwable failure) { failures[index] = failure; }
            }, "pending-rotation-" + i);
            threads[i].start();
        }
        for (Thread thread : threads) thread.join(60000);
        check(failures[0] == null && failures[1] == null,
                "both requests succeeded: " + failures[0] + " / " + failures[1]);
        check(exchanges[0] == 1, "the spent value was never exchanged again, got " + exchanges[0]);
        for (int i = 0; i < results.length; i++) {
            check(results[i] != null && "ag-new".equals(results[i].optString("access")),
                    "request " + i + " got the held credential: " + results[i]);
        }
        check("ag-rotated".equals(durableAccount(target, "antigravity").optString("refresh")),
                "exactly one durable rotation: " + durableAccount(target, "antigravity"));
        check(!read(target).contains("ag-refresh"), "the spent value never reached the file");
        check("ag2-refresh".equals(accountOf(new JSONObject(read(target)), "antigravity", 1).optString("refresh")),
                "the race never reached the second account");
        check(!hasPending(server, "antigravity", "id:ag_1"), "the finished rotation was retired");
    }

    /**
     * 上游不消费的 refresh 值不该因为围栏本身的状态而无法刷新：一份读不懂的围栏既不能拦住
     * Copilot（它是从长期 GitHub token 里现签短令牌，从不改 refresh），也不能拦住 Antigravity。
     * 负对照：同一份损坏的围栏对真正一次性凭据仍然 fail closed。
     */
    static void reusableRefreshDoesNotDependOnTheFence() throws Exception {
        File dir = tempDir("hardening-fence-unused-");
        File durable = new File(dir, "config.json");
        write(durable, copilotDurable());
        write(fenceFile(durable), "not a fence line\n");
        byte[] damaged = java.nio.file.Files.readAllBytes(fenceFile(durable).toPath());
        GatewayServer server = fixture(new JSONObject(copilotDurable()), durable);
        server.setTokenRefreshForTests((snapshot, force) ->
                new JSONObject(snapshot.toString()).put("access", "cp-new").put("token", "cp-new")
                        .put("expires", System.currentTimeMillis() + 3600000L));
        JSONObject committed = (JSONObject) invoke(server, "ensureStoredToken",
                new Class<?>[]{String.class, JSONObject.class, String.class},
                "github-copilot", new JSONObject(firstAccount(server, "github-copilot").toString()), null);
        check("cp-new".equals(committed.optString("access")),
                "a value upstream keeps is refreshed without the fence: " + committed);
        check(java.util.Arrays.equals(damaged, java.nio.file.Files.readAllBytes(fenceFile(durable).toPath())),
                "a fence this provider does not use was neither repaired nor overwritten");
        check(fenceHit(server, "cline", "old-refresh"),
                "the same unusable fence still blocks a one-use provider");

        File agDir = tempDir("hardening-fence-unused-ag-");
        File agDurable = new File(agDir, "config.json");
        write(agDurable, antigravityDurable());
        write(fenceFile(agDurable), "not a fence line\n");
        GatewayServer ag = fixture(new JSONObject(antigravityDurable()), agDurable);
        ag.setTokenRefreshForTests((snapshot, force) ->
                new JSONObject(snapshot.toString()).put("access", "ag-new").put("token", "ag-new")
                        .put("expires", System.currentTimeMillis() + 3600000L));
        JSONObject agCommitted = refreshAntigravity(ag);
        check("ag-new".equals(agCommitted.optString("access")),
                "Google's value is refreshed without the fence: " + agCommitted);
    }

    /** Qoder：factory.open 失败解围栏；getOutputStream 阶段（已 CONNECTED）保留围栏。 */
    static void qoderUnsentRefreshReleasesFenceAndStreamStageKeepsIt() throws Exception {
        File dir = tempDir("hardening-qoder-unsent-");
        File durable = new File(dir, "config.json");
        write(durable, qoderDurable());
        GatewayServer server = fixture(new JSONObject(qoderDurable()), durable);
        setField(server, "qoderSyncLock", new Object());
        final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        server.setQoderConnectionsForTests(url -> {
            opens.incrementAndGet();
            throw new java.io.IOException("fixture: no link");
        });
        syncBody(server);
        check(opens.get() == 1, "the refresh reached the transport factory once, got " + opens.get());
        check(!fenced(durable, "qoder_cn", "old-refresh"), "a provably unsent Qoder value is released");
        String second = syncBody(server);
        check(opens.get() == 2, "the released value was allowed to leave again, got " + opens.get());
        check(!second.contains("请重新登录"), "an unsent refresh does not demand a sign-in: " + second);

        // Control: the connection object exists, so the stage is already CONNECTED (getOutputStream
        // may have handed request headers to the socket): the fence must stay.
        File streamDir = tempDir("hardening-qoder-stream-");
        File stream = new File(streamDir, "config.json");
        write(stream, qoderDurable());
        GatewayServer stage = fixture(new JSONObject(qoderDurable()), stream);
        setField(stage, "qoderSyncLock", new Object());
        final int closedPort = closedLoopbackPort();
        final java.util.concurrent.atomic.AtomicInteger streamOpens = new java.util.concurrent.atomic.AtomicInteger();
        stage.setQoderConnectionsForTests(url -> {
            streamOpens.incrementAndGet();
            return (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + closedPort + "/api/v1/deviceToken/refresh").openConnection();
        });
        syncBody(stage);
        check(streamOpens.get() == 1, "one Qoder attempt, got " + streamOpens.get());
        check(fenced(stream, "qoder_cn", "old-refresh"),
                "the getOutputStream stage keeps the Qoder fence");
        String replay = syncBody(stage);
        check(streamOpens.get() == 1, "the unknown Qoder value was retried, got " + streamOpens.get());
        check(replay.contains("请重新登录"), "the blocked Qoder value asks for a sign-in: " + replay);
        check(qoderDurable().equals(read(stream)), "the durable Qoder account is untouched");
    }

    /** 小浣熊：链路打不开解围栏；getOutputStream 阶段与写请求都保留围栏。 */
    static void raccoonUnsentRefreshReleasesFenceAndStreamStageKeepsIt() throws Exception {
        File dir = tempDir("hardening-raccoon-unsent-");
        File durable = new File(dir, "config.json");
        write(durable, raccoonDurable());
        GatewayServer server = fixture(new JSONObject(raccoonDurable()), durable);
        final int[] opens = {0};
        server.setVendorConnectionsForTests(url -> {
            opens[0]++;
            throw new java.io.IOException("fixture: no link");
        });
        try {
            refreshRaccoon(server);
            throw new AssertionError("a Raccoon refresh that was never sent was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("fixture: no link"),
                    "the transport wording is preserved: " + expected.getMessage());
        }
        check(opens[0] == 1, "one link attempt, got " + opens[0]);
        check(!fenced(durable, "raccoon", "old-refresh"), "a link that never opened releases the value");
        final int[] exchanges = {0};
        server.setRaccoonRefreshTransportForTests(raccoonRotating(exchanges));
        JSONObject committed = refreshRaccoon(server);
        check(exchanges[0] == 1, "the released value was exchangeable again, got " + exchanges[0]);
        check("rc-new".equals(storedAccessOf(committed)), "the retry committed: " + committed);

        // Control: the connection exists, so getOutputStream (link, TLS, possibly headers) runs.
        File streamDir = tempDir("hardening-raccoon-stream-");
        File stream = new File(streamDir, "config.json");
        write(stream, raccoonDurable());
        GatewayServer stage = fixture(new JSONObject(raccoonDurable()), stream);
        final int closedPort = closedLoopbackPort();
        final int[] stageOpens = {0};
        stage.setVendorConnectionsForTests(url -> {
            stageOpens[0]++;
            return (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + closedPort + RaccoonRefreshService.REFRESH_PATH).openConnection();
        });
        try {
            refreshRaccoon(stage);
            throw new AssertionError("a request whose stream never opened was reported as success");
        } catch (java.io.IOException expected) {
            check(!expected.getMessage().contains("请重新登录"),
                    "the unknown outcome is not a sign-in demand yet: " + expected.getMessage());
        }
        check(stageOpens[0] == 1, "one Raccoon attempt, got " + stageOpens[0]);
        check(fenced(stream, "raccoon", "old-refresh"),
                "the getOutputStream stage keeps the Raccoon fence");
        try {
            refreshRaccoon(stage);
            throw new AssertionError("the fenced Raccoon value was exchanged again");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("请重新登录"),
                    "the fenced value asks for a sign-in: " + expected.getMessage());
        }
        check(stageOpens[0] == 1, "the unknown value was never re-sent, got " + stageOpens[0]);
        check(raccoonDurable().equals(read(stream)), "the durable Raccoon account is untouched");

        // Control: the stream exists but the request write fails - the kernel may already hold
        // part of the bytes, so the outcome is unknown and the fence stays.
        File writeDir = tempDir("hardening-raccoon-write-");
        File writeDurable = new File(writeDir, "config.json");
        write(writeDurable, raccoonDurable());
        GatewayServer writeFailed = fixture(new JSONObject(raccoonDurable()), writeDurable);
        writeFailed.setVendorConnectionsForTests(url -> new FakeWire(
                "http://127.0.0.1/api/web/auth/v1/refresh", failingSink(), false));
        try {
            refreshRaccoon(writeFailed);
            throw new AssertionError("a failed request write was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("write failed"),
                    "the write failure is reported as a write stage: " + expected.getMessage());
        }
        check(fenced(writeDurable, "raccoon", "old-refresh"), "a failed request write keeps the fence");

        // Control: the request was written but no status line arrives - also unknown.
        File readDir = tempDir("hardening-raccoon-read-");
        File readDurable = new File(readDir, "config.json");
        write(readDurable, raccoonDurable());
        GatewayServer unreadable = fixture(new JSONObject(raccoonDurable()), readDurable);
        unreadable.setVendorConnectionsForTests(url -> new FakeWire(
                "http://127.0.0.1/api/web/auth/v1/refresh", silentSink(), true));
        try {
            refreshRaccoon(unreadable);
            throw new AssertionError("an unreadable answer was reported as success");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("could not be read"),
                    "the unreadable answer is reported as a response stage: " + expected.getMessage());
        }
        check(fenced(readDurable, "raccoon", "old-refresh"), "an unreadable answer keeps the fence");
        unreadable.setVendorConnectionsForTests(url -> {
            throw new AssertionError("a value whose answer was lost was sent again");
        });
        try {
            refreshRaccoon(unreadable);
            throw new AssertionError("the fenced Raccoon value was exchanged again");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("请重新登录"),
                    "the lost answer asks for a sign-in: " + expected.getMessage());
        }
    }

    /**
     * 一次性环回刷新端点：读一个完整请求、记下请求体，然后要么答复一份可用的令牌对，
     * 要么在请求体被收下之后直接把连接断掉（对端收到了请求，但我方再也看不到答复）。
     *
     * <p>只监听 127.0.0.1，端口由系统分配；用例结束即关闭，不触外网。
     */
    static final class RefreshEndpoint implements AutoCloseable {
        private final java.net.ServerSocket server;
        private final String responseBody;
        private volatile int hits;
        private volatile String body = "";
        private Thread thread;

        /** responseBody 为 null：读完请求体就断链，模拟「请求已到 Cline、答复丢了」。 */
        RefreshEndpoint(String responseBody) throws Exception {
            this.server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
            this.responseBody = responseBody;
        }
        int port() { return server.getLocalPort(); }
        int hits() { return hits; }
        String body() { return body; }
        void start() {
            thread = new Thread(this::serve, "hardening-cline-refresh-endpoint");
            thread.setDaemon(true);
            thread.start();
        }
        private void serve() {
            try {
                java.net.Socket socket = server.accept();
                hits++;
                socket.setSoTimeout(5000);
                InputStream in = socket.getInputStream();
                StringBuilder head = new StringBuilder();
                while (head.indexOf("\r\n\r\n") < 0) {
                    int b = in.read();
                    if (b < 0) break;
                    head.append((char) b);
                }
                int length = endpointLength(head.toString());
                byte[] raw = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(raw, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(raw, 0, read, StandardCharsets.UTF_8);
                if (responseBody == null) {
                    // 请求体已经被对端收下，然后连接消失：Cline 答复未知，凭据可能已被消费。
                    socket.setSoLinger(true, 0);
                    socket.close();
                    return;
                }
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
                socket.close();
            } catch (Throwable closing) {
                // 客户端主动断开属于预期路径。
            }
        }
        private static int endpointLength(String head) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                if (!"content-length".equalsIgnoreCase(line.substring(0, colon).trim())) continue;
                try { return Integer.parseInt(line.substring(colon + 1).trim()); }
                catch (NumberFormatException ignored) { return 0; }
            }
            return 0;
        }
        @Override public void close() {
            try { server.close(); } catch (Exception ignored) { }
            if (thread != null) {
                try { thread.join(2000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * Cline 端到端隔离：生产路径上的「非发送断网」必须解围栏，同一个值可以再次离开发送。
     *
     * <p>refresh token 只出现在请求体里，这一点由 ClineAuth 在打开链路之前当场核对。端口没人听时，
     * HttpURLConnection 在 {@code getOutputStream()} 里就抛 ConnectException —— 请求头都没送出去，
     * 请求体一个字节没写 —— 所以证据是 {@code NOT_SENT/connect} + {@code BODY_ONLY}，围栏必须放开。
     * 这里跑的是真实 transport（{@link ClineAuth#refreshToken}），只有套接字是本机环回。
     */
    static void clineUnsentRefreshReleasesFenceAndRetries() throws Exception {
        final int refused = closedLoopbackPort();
        File dir = tempDir("hardening-cline-unsent-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        final int[] opens = {0};
        ClineAuth.setRefreshConnectionsForTests(url -> {
            opens[0]++;
            return (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + refused + "/api/v1/auth/refresh").openConnection();
        });
        try {
            refreshCline(server);
            throw new AssertionError("a Cline refresh that never left the process was reported as success");
        } catch (java.io.IOException expected) {
            check(opens[0] == 1, "one Cline link attempt, got " + opens[0]);
            check(expected.getMessage().contains("没有发出"),
                    "the answer says the request never left: " + expected.getMessage());
            check(!expected.getMessage().contains("请重新登录"),
                    "an unsent refresh is retryable, not a sign-in demand: " + expected.getMessage());
        }
        check(!fenced(durable, "cline", "old-refresh"), "a provably unsent value is released on disk");
        check(!fenceHit(server, "cline", "old-refresh"), "the release reached memory as well");
        check(clineDurable().equals(read(durable)), "the durable Cline account is untouched");

        // 解围栏之后同一个值可以再次离开发送：这里由真实环回答复，交换并落盘。
        try (RefreshEndpoint live = new RefreshEndpoint(
                "{\"data\":{\"accessToken\":\"rotated-access\",\"refreshToken\":\"rotated-refresh\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\",\"userInfo\":{\"clineUserId\":\"cu1\"}}}")) {
            live.start();
            ClineAuth.setRefreshConnectionsForTests(url -> (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + live.port() + "/api/v1/auth/refresh").openConnection());
            JSONObject committed = refreshCline(server);
            check(live.hits() == 1, "the released value reached the wire exactly once, got " + live.hits());
            check(live.body().contains("old-refresh"),
                    "the retry carried the very value the provider kept: " + live.body());
            check("rotated-access".equals(committed.optString("access")),
                    "the released value committed the rotation: " + committed);
        } finally { ClineAuth.setRefreshConnectionsForTests(null); }
        JSONObject onDisk = durableAccount(durable, "cline");
        check("rotated-access".equals(onDisk.optString("access")), "the rotation reached the disk: " + onDisk);
        check(fenced(durable, "cline", "old-refresh"), "the replaced value becomes permanently fenced");
        check(!fenced(durable, "cline", "rotated-refresh"), "the freshly stored value is not fenced");
    }

    /**
     * Cline 端到端隔离：请求已经送出、但答复丢了，围栏必须留着，而且绝不再重放。
     *
     * <p>端点把请求体完整读下来再断链 —— 上游确实收到了那个一次性凭据，只是我方没拿到状态行。
     * ClineAuth 因此给出 {@code POSSIBLY_SENT}，围栏不能放开；第二次刷新在读盘之前就被拦下，
     * 连一条新的链路都不许开。
     */
    static void clineLostAnswerKeepsFenceAndNeverReplays() throws Exception {
        File dir = tempDir("hardening-cline-lost-");
        File durable = new File(dir, "config.json");
        write(durable, clineDurable());
        GatewayServer server = fixture(new JSONObject(clineDurable()), durable);
        try (RefreshEndpoint mute = new RefreshEndpoint(null)) {
            mute.start();
            ClineAuth.setRefreshConnectionsForTests(url -> (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + mute.port() + "/api/v1/auth/refresh").openConnection());
            try {
                refreshCline(server);
                throw new AssertionError("a Cline refresh with a lost answer was reported as success");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("请重新登录"),
                        "a lost answer asks for a sign-in: " + expected.getMessage());
            }
            check(mute.hits() == 1, "exactly one Cline request left the process, got " + mute.hits());
            check(mute.body().contains("old-refresh"),
                    "the request really carried the one-use value: " + mute.body());
            check(fenced(durable, "cline", "old-refresh"), "the sent value stays fenced on disk");
            check(fenceHit(server, "cline", "old-refresh"), "the fence is held in memory too");
            check(clineDurable().equals(read(durable)), "the durable Cline account is untouched");

            // 第二次刷新不得再开链路：围栏在读盘之前就拦住，绝不重放。
            try {
                refreshCline(server);
                throw new AssertionError("the fenced Cline value was exchanged again");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("请重新登录"),
                        "the fenced value still asks for a sign-in: " + expected.getMessage());
            }
            check(mute.hits() == 1, "the spent Cline value was replayed: " + mute.hits());
        } finally { ClineAuth.setRefreshConnectionsForTests(null); }
    }

    public static void main(String[] args) throws Exception {
        TestPrivacyScope.initialize();
        publishedDefaults();
        setupStatusFlags();
        setupCompletionGates();
        serverKeyEndpointRejectsPublishedDefault();
        remotePublishedDefaultIsRefused();
        connectionLimits();
        boundedRunnerAdmission();
        boundedRunnerGuardsRealListener();
        callbackSocketIsLoopbackOnly();
        callbackRequestLineIsBounded();
        callbackResponseClosesSocket();
        callbackPageEscapesDynamicText();
        corruptConfigEntersRecovery();
        postCommitFailureIsNotASaveFailure();
        strictPostCommitFailureIsNotASaveFailure();
        strictWriteFailureIsStillAFailure();
        networkPublishFailureFailsClosedOnSave();
        networkPublishFailureFailsClosedOnStrictSave();
        nonNetworkPostCommitFailureKeepsCommittedPolicy();
        failedWritePreservesRotatedCredentials();
        accountAddFailureIsReported();
        accountAddRouteReportsFailure();
        recoveryEntryIsNarrow();
        recoveryRetryAndReset();
        etaImportDoesNotExportDeadKey();
        antigravitySendVerdictClassifiesConservatively();
        cancelledResponseShape();
        antigravityAccountAddReportsSaveFailure();
        carryKeepsNewAccountsAndProviders();
        carryRefusesIdReuseAndIdentityConflict();
        carryCarriesBindingOnlyWhenIdentityConfirmed();
        carryKeepsCredentialSetComplete();
        rescueWriteFailureBlocksReset();
        resetVerifiesRescueBytes();
        resetRefusesWhenFileChangedAfterRescue();
        noIdentityEvidenceRefusesMerge();
        detachedCopyCredentialsSurviveFailedWrite();
        unusableDurableCopyEntersRecovery();
        writeFailureWithDetachedCopyKeepsItsCredentials();
        preWriteRejectionWithDetachedCopyKeepsItsCredentials();
        qoderCatalogReadIsBudgetBound();
        refreshFenceIsWrittenBeforeTheValueIsSent();
        crashBeforeCommitNeverReplaysAfterRestart();
        fullFenceFailsClosedAndEvictsNothing();
        damagedFenceIsNeverTruncatedOrOverwritten();
        raccoonMaterialIsHashedAsSent();
        failedDirectorySyncLeavesMemoryAndDiskAgreeing();
        unwritableFenceBlocksTheSend();
        copilotNonRotatingRefreshIsNotFenced();
        antigravitySameValueIsReleasedAfterCommit();
        raccoonLostRotationIsFencedNotReplayed();
        qoderSyncFencesBeforeRefreshAndNeverReplays();
        fencedCredentialIsReportedToTheClient();
        sendEvidenceGateAcceptsOnlyUnwrittenPreSendStages();
        antigravityLostAnswerIsRetriedNotLocked();
        antigravityLegacyFenceDoesNotLockTheAccount();
        antigravityObservedRotationIsHeldWhenTheWriteFails();
        antigravityStaleRotationNeverOverwritesANewSignIn();
        reusableRefreshDoesNotDependOnTheFence();
        pendingRotationAppliesAcrossUnrelatedChanges();
        pendingRotationIsRetiredByANewGenerationOnly();
        spentRotationIsNeverResentWithoutItsRecord();
        pendingRetentionIsReportedHonestly();
        concurrentPendingRotationIsFinishedOnce();
        spentRecordSurvivesARestartAndAnUnwritableFence();
        freshSignInReleasesItsOwnSpentRecord();
        qoderUnsentRefreshReleasesFenceAndStreamStageKeepsIt();
        raccoonUnsentRefreshReleasesFenceAndStreamStageKeepsIt();
        clineUnsentRefreshReleasesFenceAndRetries();
        clineLostAnswerKeepsFenceAndNeverReplays();
        concurrentSavesPublishExactlyTheDurableState();
        statusExposesOperationalState();
        System.out.println("GatewayReleaseHardeningOfflineTest OK");
    }
}
