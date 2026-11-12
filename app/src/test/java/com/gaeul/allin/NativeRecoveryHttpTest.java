package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback contracts for the two bounded resends this gateway performs:
 * WorkBuddy's domestic native signature refusal, and Qoder's named max_tokens ceiling.
 *
 * <p>Every exchange is synthetic. What is asserted is exactly the part a resend can get wrong:
 * how many physical POSTs happen, which body each one carries, that each send took its own
 * outbound admission (pacing and ticket release), that the live connection is the only one
 * registered for cancellation, and that nothing is replayed unless the first answer was a
 * definitive refusal.
 */
public final class NativeRecoveryHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static final String REFUSAL = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Invalid `signature` in `thinking` block. The block is bound to a different conversation\","
            + "\"internal_code\":\"11159\"}}";
    static final String FOREIGN_SIGNATURE = "signature-from-another-vendor";

    // ------------------------------------------------------------------ WorkBuddy native

    /** Every content part type in the request's assistant messages. */
    static List<String> assistantPartTypes(JSONObject body) {
        List<String> types = new ArrayList<String>();
        JSONArray messages = body.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            JSONArray content = message.optJSONArray("content");
            for (int j = 0; content != null && j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null) types.add(part.optString("type"));
            }
        }
        return types;
    }

    /** The WorkBuddy native request a migrated session sends: its history keeps a foreign signature. */
    static JSONObject nativeWithSignedHistory(boolean stream) throws Exception {
        JSONObject input = WorkBuddyNativeGatewayHttpTest.anthropic(stream);
        input.getJSONArray("messages").put(new JSONObject().put("role", "assistant").put("content", new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "another vendor's thought")
                        .put("signature", FOREIGN_SIGNATURE))
                .put(new JSONObject().put("type", "text").put("text", "earlier answer"))));
        input.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", "and now?"));
        return input;
    }

    static int registeredConnections(GatewayServer gateway) throws Exception {
        Field activeField = GatewayServer.class.getDeclaredField("activeContexts");
        activeField.setAccessible(true);
        Set<?> contexts = (Set<?>) activeField.get(gateway);
        int total = 0;
        for (Object context : new ArrayList<Object>(contexts)) {
            Field connectionField = RequestContext.class.getDeclaredField("connections");
            connectionField.setAccessible(true);
            total += ((Set<?>) connectionField.get(context)).size();
        }
        return total;
    }

    static OutboundLimiter limiter(GatewayServer gateway) throws Exception {
        Field field = GatewayServer.class.getDeclaredField("outboundLimiter");
        field.setAccessible(true);
        return (OutboundLimiter) field.get(gateway);
    }

    /** A confirmed refusal is answered once more, without a thinking block, on its own admission. */
    static void nativeSignatureRecovery() throws Exception {
        final List<Long> sentAt = Collections.synchronizedList(new ArrayList<Long>());
        final List<Integer> registeredAtSend = Collections.synchronizedList(new ArrayList<Integer>());
        final WorkBuddyNativeGatewayHttpTest.GatewayHarness[] harness = new WorkBuddyNativeGatewayHttpTest.GatewayHarness[1];
        try (WorkBuddyNativeGatewayHttpTest.MockUpstream upstream = new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request, count) -> {
                    sentAt.add(Long.valueOf(System.currentTimeMillis()));
                    try { registeredAtSend.add(Integer.valueOf(registeredConnections(harness[0].gateway))); }
                    catch (Exception reflection) { throw new IllegalStateException(reflection); }
                    return count == 1
                            ? new WorkBuddyNativeGatewayHttpTest.Reply(400, "application/json", REFUSAL)
                            : new WorkBuddyNativeGatewayHttpTest.Reply(200, "text/event-stream",
                                    WorkBuddyNativeGatewayHttpTest.BASE);
                })) {
            JSONObject config = WorkBuddyNativeGatewayHttpTest.config("workbuddy", "openai",
                    "https://copilot.tencent.com/v2", "claude-opus-5.5");
            // One send per half second: a resend that skipped admission would arrive immediately.
            config.put("outbound", new JSONObject().put("workbuddy", new JSONObject()
                    .put("credential", new JSONObject().put("minIntervalMillis", 500))));
            try (WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway =
                         new WorkBuddyNativeGatewayHttpTest.GatewayHarness(config, upstream)) {
                harness[0] = gateway;
                JSONObject request = nativeWithSignedHistory(false);
                WorkBuddyNativeGatewayHttpTest.Result response = WorkBuddyNativeGatewayHttpTest.post(
                        gateway.port(), "/v1/messages", request, null);
                check(response.status == 200, "recovered exchange answers: " + response.status + " " + response.body);
                check(upstream.requests.size() == 2, "exactly one resend: " + upstream.requests.size());
                JSONObject first = upstream.requests.get(0).body;
                check(first.toString().contains(FOREIGN_SIGNATURE), "the first send really carried the foreign signature");
                check(assistantPartTypes(first).indexOf("thinking") >= 0, "the first send asked with a thinking block");
                JSONObject second = upstream.requests.get(1).body;
                check(!second.toString().contains(FOREIGN_SIGNATURE), "the resend carries no signature: " + second);
                check(assistantPartTypes(second).indexOf("thinking") < 0, "the resend carries no thinking block: " + second);
                check(second.toString().contains("earlier answer"), "the resend keeps the visible answer");
                check(second.toString().contains("and now?"), "the resend keeps the whole conversation");
                check("claude-opus-5.5".equals(second.optString("model")), "the resend names the same model");
                check(second.optBoolean("stream"), "the resend still asks for the native stream");
                // Every send was admitted, so pacing applied to the resend too.
                long gap = sentAt.get(1).longValue() - sentAt.get(0).longValue();
                check(gap >= 400, "the resend waited for its own pacing slot: " + gap + "ms");
                // Only the live connection is registered; the refused one was unregistered first.
                check(registeredAtSend.get(1).intValue() == 1,
                        "exactly one connection registered for cancellation at the resend: " + registeredAtSend.get(1));
                check(registeredAtSend.get(0).intValue() == 1, "the first send registered exactly one connection");
                OutboundLimiter limiter = limiter(gateway.gateway);
                check(limiter.active() == 0, "both tickets released, active=" + limiter.active());
                check(limiter.queued() == 0, "nothing left waiting");
                check(registeredConnections(gateway.gateway) == 0, "the finished request registered no connection");
            }
        }
    }

    /** A streamed answer is delivered once and never resent. */
    static void nativeStreamedOnce() throws Exception {
        try (WorkBuddyNativeGatewayHttpTest.MockUpstream upstream = new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request, count) -> new WorkBuddyNativeGatewayHttpTest.Reply(200, "text/event-stream",
                        WorkBuddyNativeGatewayHttpTest.BASE));
             WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway = new WorkBuddyNativeGatewayHttpTest.GatewayHarness(
                     WorkBuddyNativeGatewayHttpTest.config("workbuddy", "openai",
                             "https://copilot.tencent.com/v2", "claude-opus-5.5"), upstream)) {
            WorkBuddyNativeGatewayHttpTest.Result response = WorkBuddyNativeGatewayHttpTest.post(
                    gateway.port(), "/v1/messages", nativeWithSignedHistory(true), null);
            check(response.status == 200, "streamed answer " + response.status + " " + response.body);
            check(upstream.requests.size() == 1, "a success is never resent: " + upstream.requests.size());
            check(upstream.requests.get(0).body.toString().contains(FOREIGN_SIGNATURE),
                    "a successful exchange is relayed as the client asked it");
        }
    }

    /** A refusal with nothing to drop must not be answered with the identical request again. */
    static void nativeRefusalWithoutThinkingIsTerminal() throws Exception {
        try (WorkBuddyNativeGatewayHttpTest.MockUpstream upstream = new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request, count) -> new WorkBuddyNativeGatewayHttpTest.Reply(400, "application/json", REFUSAL));
             WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway = new WorkBuddyNativeGatewayHttpTest.GatewayHarness(
                     WorkBuddyNativeGatewayHttpTest.config("workbuddy", "openai",
                             "https://copilot.tencent.com/v2", "claude-opus-5.5"), upstream)) {
            WorkBuddyNativeGatewayHttpTest.Result response = WorkBuddyNativeGatewayHttpTest.post(
                    gateway.port(), "/v1/messages", WorkBuddyNativeGatewayHttpTest.anthropic(false), null);
            check(response.status == 400, "the vendor's status is returned: " + response.status + " " + response.body);
            check(upstream.requests.size() == 1, "no thinking block means no resend: " + upstream.requests.size());
            check(response.body.contains("WorkBuddy native Messages request rejected"),
                    "the refusal is explained: " + response.body);
            check(!response.body.contains(KEY_SENTINEL), "no credential in the error body");
        }
    }

    /** An unrelated 400 is not a signature refusal. */
    static void nativeOtherRefusalIsTerminal() throws Exception {
        String other = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: 2048 > 1024\"}}";
        try (WorkBuddyNativeGatewayHttpTest.MockUpstream upstream = new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request, count) -> new WorkBuddyNativeGatewayHttpTest.Reply(400, "application/json", other));
             WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway = new WorkBuddyNativeGatewayHttpTest.GatewayHarness(
                     WorkBuddyNativeGatewayHttpTest.config("workbuddy", "openai",
                             "https://copilot.tencent.com/v2", "claude-opus-5.5"), upstream)) {
            WorkBuddyNativeGatewayHttpTest.Result response = WorkBuddyNativeGatewayHttpTest.post(
                    gateway.port(), "/v1/messages", nativeWithSignedHistory(false), null);
            check(response.status == 400, "unrelated refusal passes through: " + response.status);
            check(upstream.requests.size() == 1, "only a signature refusal is resent: " + upstream.requests.size());
        }
    }

    /**
     * An exchange whose outcome is unknown is never resent: the fixture drops the socket after the
     * body was accepted, so the gateway cannot know whether the turn ran.
     */
    static void nativeUnknownOutcomeIsNotReplayed() throws Exception {
        try (WorkBuddyNativeGatewayHttpTest.MockUpstream upstream = new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request, count) -> WorkBuddyNativeGatewayHttpTest.Reply.disconnect());
             WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway = new WorkBuddyNativeGatewayHttpTest.GatewayHarness(
                     WorkBuddyNativeGatewayHttpTest.config("workbuddy", "openai",
                             "https://copilot.tencent.com/v2", "claude-opus-5.5"), upstream)) {
            WorkBuddyNativeGatewayHttpTest.Result response = WorkBuddyNativeGatewayHttpTest.post(
                    gateway.port(), "/v1/messages", nativeWithSignedHistory(false), null);
            check(response.status >= 400, "a lost answer is a failure: " + response.status + " " + response.body);
            check(upstream.requests.size() == 1, "an unknown outcome is never replayed: " + upstream.requests.size());
            check(registeredConnections(gateway.gateway) == 0, "the lost connection was unregistered");
            OutboundLimiter limiter = limiter(gateway.gateway);
            check(limiter.active() == 0, "the lost send's ticket was released, active=" + limiter.active());
        }
    }

    /** The provider's key never appears in a relayed error body. */
    static final String KEY_SENTINEL = WorkBuddyNativeGatewayHttpTest.KEY;

    // ------------------------------------------------------------------ Qoder

    static final int QODER_CAP = 8192;
    static final String QODER_RANGE_REFUSAL = "{\"message\":\"error in upstream response: range of max_tokens "
            + "should be [64, " + QODER_CAP + "]\"}";

    static String qoderHttp(int code, String body) {
        return "HTTP/1.1 " + code + " Fixture\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
    }

    static String qoderSse(String content) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "
                + content.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + content;
    }

    static String qoderAnswer() {
        return QoderAgentGatewayHttpTest.delta(new JSONObject().put("content", "qoder answer"), "stop")
                + QoderAgentGatewayHttpTest.usage() + QoderAgentGatewayHttpTest.done();
    }

    /** Scripted raw-HTTP Qoder stand-in: one canned full response per call. */
    static final class RawUpstream implements AutoCloseable {
        final ServerSocket server;
        final Thread worker;
        final List<String> replies = Collections.synchronizedList(new ArrayList<String>());
        final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        volatile Throwable failure;
        RawUpstream(String... scripted) throws Exception {
            for (String reply : scripted) replies.add(reply);
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(400);
            worker = new Thread(this::loop, "qoder-fixture");
            worker.setDaemon(true);
            worker.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/qoder"; }
        void loop() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    InputStream in = socket.getInputStream();
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int state = 0, b;
                    while (state < 4 && (b = in.read()) != -1) {
                        header.write(b);
                        if (header.size() > 65536) throw new IOException("headers too large");
                        state = state == 0 && b == 13 ? 1 : state == 1 && b == 10 ? 2
                                : state == 2 && b == 13 ? 3 : state == 3 && b == 10 ? 4 : 0;
                    }
                    int length = 0;
                    for (String line : header.toString("UTF-8").split("\r\n"))
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    for (int i = 0; i < length; i++) { int c = in.read(); if (c < 0) throw new EOFException(); body.write(c); }
                    bodies.add(body.toString("UTF-8"));
                    String reply;
                    synchronized (replies) {
                        reply = replies.size() > 1 ? replies.remove(0) : replies.get(0);
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(reply.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable failure) {
                    if (!server.isClosed()) this.failure = failure;
                }
            }
        }
        public void close() throws Exception {
            server.close();
            worker.join(5000);
            check(!worker.isAlive(), "fixture stopped");
            if (failure != null) throw new AssertionError("fixture failed", failure);
        }
    }

    static JSONObject qoderConfig() throws Exception {
        JSONObject account = new JSONObject().put("id", "fixture-account").put("token", "fixture-token")
                .put("deviceToken", "fixture-device-token").put("deviceChat", true)
                .put("uid", "fixture-uid").put("machineId", "fixture-machine")
                .put("expiresAt", System.currentTimeMillis() + 86400000L);
        JSONObject qoder = new JSONObject().put("id", "qoder_cn").put("enabled", true).put("protocol", "qoder")
                .put("models", new JSONArray().put("fixture-model"))
                .put("accounts", new JSONArray().put(account))
                .put("modelMeta", new JSONObject().put("fixture-model", new JSONObject()
                        .put("key", "fixture-model").put("source", "fixture").put("is_vl", true)));
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("failover", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(qoder));
    }

    static final class QoderGateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        final Path dir;
        QoderGateway(JSONObject config, RawUpstream upstream) throws Exception {
            super("127.0.0.1", 0);
            inner = RoutingSetupOfflineTest.server(config);
            dir = Files.createTempDirectory("qoder-reclamp-");
            UsageLedger.init(dir.toFile());
            RoutingAuditOfflineTest.file(inner, dir.resolve("config.json").toFile());
            Field turns = GatewayServer.class.getDeclaredField("groupTurns");
            turns.setAccessible(true);
            turns.set(inner, new ConcurrentHashMap<String, Integer>());
            inner.setQoderConnectionsForTests(url -> (HttpURLConnection) new URL(upstream.url())
                    .openConnection(Proxy.NO_PROXY));
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() {
            inner.setQoderConnectionsForTests(null);
            stop();
        }
    }

    static final class Answer { int status; String body; }

    static Answer askQoder(QoderGateway gateway, int maxTokens) throws Exception {
        JSONObject request = new JSONObject().put("model", "qoder_cn/fixture-model").put("stream", false)
                .put("max_tokens", maxTokens)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:"
                + gateway.getListeningPort() + "/v1/chat/completions").openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer fixture-auth");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        Answer answer = new Answer();
        answer.status = connection.getResponseCode();
        InputStream in = answer.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (stream != null) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            answer.body = out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
        return answer;
    }

    static int askedMaxTokens(String encoded) throws Exception {
        return new JSONObject(QoderAuth.decodeBody(encoded)).getJSONObject("parameters").optInt("max_tokens", 0);
    }

    /** A named ceiling is asked again once at it, on its own admission; the reply is delivered. */
    static void qoderReclampsOnce() throws Exception {
        QoderFailurePolicy.clearLearnedCaps();
        try (RawUpstream upstream = new RawUpstream(qoderHttp(400, QODER_RANGE_REFUSAL), qoderSse(qoderAnswer()));
             QoderGateway gateway = new QoderGateway(qoderConfig(), upstream)) {
            Answer answer = askQoder(gateway, 100000);
            check(answer.status == 200, "reclamped answer delivered: " + answer.status + " " + answer.body);
            check(answer.body.contains("qoder answer"), "the answer reaches the client: " + answer.body);
            check(upstream.bodies.size() == 2, "exactly one resend: " + upstream.bodies.size());
            check(askedMaxTokens(upstream.bodies.get(0)) == 100000, "the first send asked what the client asked");
            check(askedMaxTokens(upstream.bodies.get(1)) == QODER_CAP,
                    "the resend asks the ceiling the refusal named: " + askedMaxTokens(upstream.bodies.get(1)));
            // Everything else about the request stays the same turn.
            JSONObject first = new JSONObject(QoderAuth.decodeBody(upstream.bodies.get(0)));
            JSONObject second = new JSONObject(QoderAuth.decodeBody(upstream.bodies.get(1)));
            check(first.optString("request_set_id").equals(second.optString("request_set_id")),
                    "the resend stays the same logical turn");
            // The ceiling is remembered, so the next request is sent it from the start.
            check(QoderFailurePolicy.modelLimit(100000, "fixture-model", null) == QODER_CAP,
                    "a later request starts at the learned ceiling");
        } finally {
            QoderFailurePolicy.clearLearnedCaps();
        }
    }

    /** A refusal naming a ceiling the request did not exceed is terminal: nothing would change. */
    static void qoderNoReclampWhenNothingChanges() throws Exception {
        QoderFailurePolicy.clearLearnedCaps();
        try (RawUpstream upstream = new RawUpstream(qoderHttp(400, QODER_RANGE_REFUSAL));
             QoderGateway gateway = new QoderGateway(qoderConfig(), upstream)) {
            Answer answer = askQoder(gateway, QODER_CAP);
            check(answer.status == 400, "an unchanged request is not resent: " + answer.status + " " + answer.body);
            check(upstream.bodies.size() == 1, "one send only: " + upstream.bodies.size());
        } finally {
            QoderFailurePolicy.clearLearnedCaps();
        }
    }

    /** An unrelated refusal is never turned into a different request. */
    static void qoderOtherRefusalIsTerminal() throws Exception {
        QoderFailurePolicy.clearLearnedCaps();
        try (RawUpstream upstream = new RawUpstream(qoderHttp(400, "{\"message\":\"illegal prompt\"}"));
             QoderGateway gateway = new QoderGateway(qoderConfig(), upstream)) {
            Answer answer = askQoder(gateway, 100000);
            check(answer.status == 400, "unrelated refusal passes through: " + answer.status + " " + answer.body);
            check(upstream.bodies.size() == 1, "only a range refusal is resent: " + upstream.bodies.size());
        } finally {
            QoderFailurePolicy.clearLearnedCaps();
        }
    }

    public static void main(String[] args) throws Exception {
        nativeSignatureRecovery();
        nativeStreamedOnce();
        nativeRefusalWithoutThinkingIsTerminal();
        nativeOtherRefusalIsTerminal();
        nativeUnknownOutcomeIsNotReplayed();
        qoderReclampsOnce();
        qoderNoReclampWhenNothingChanges();
        qoderOtherRefusalIsTerminal();
        System.out.println("NativeRecoveryHttpTest passed");
    }
}
