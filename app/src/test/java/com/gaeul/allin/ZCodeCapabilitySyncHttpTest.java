package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
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
import java.util.TreeMap;

/**
 * End-to-end: the real catalog-sync endpoint reads ZCode's provider config and persists each
 * model's declared capabilities, and the already-shipped reasoning policy then uses them.
 *
 * <p>Everything is loopback: the sync's own {@code /models} call, the client-configs call and the
 * provider-config document are served by one local fixture, and the reasoning assertion is made on
 * the Anthropic body the gateway really posts upstream. No real service, no credentials.
 */
public final class ZCodeCapabilitySyncHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String KEY = "fixture-zcode-key";
    static final String AUTH = "fixture-gateway-auth";
    static final String CONFIG_URL = "https://cdn.example.test/zcode-provider.json";

    static final String ANTHROPIC_ANSWER = "{\"id\":\"msg_fixture\",\"type\":\"message\","
            + "\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    // ------------------------------------------------------------------ loopback fixture

    static final class Request {
        String path;
        final Map<String,String> headers = new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
        String body = "";
        String method = "";
    }
    static final class Reply {
        final int status; final String type, body;
        Reply(int status, String type, String body) { this.status = status; this.type = type; this.body = body; }
    }
    interface Script { Reply reply(Request request, int call) throws Exception; }

    static final class Upstream implements AutoCloseable {
        final ServerSocket server;
        final Thread worker;
        final Script script;
        final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
        volatile Throwable failure;
        Upstream(Script script) throws Exception {
            this.script = script;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(400);
            worker = new Thread(this::loop, "zcode-config-fixture");
            worker.setDaemon(true);
            worker.start();
        }
        void loop() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream in = socket.getInputStream();
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int state = 0, b;
                    while (state < 4 && (b = in.read()) != -1) {
                        header.write(b);
                        if (header.size() > 65536) throw new IOException("headers too large");
                        state = state == 0 && b == 13 ? 1 : state == 1 && b == 10 ? 2
                                : state == 2 && b == 13 ? 3 : state == 3 && b == 10 ? 4 : 0;
                    }
                    String[] lines = header.toString("UTF-8").split("\r\n");
                    Request request = new Request();
                    String[] requestLine = lines[0].split(" ");
                    request.method = requestLine[0];
                    request.path = requestLine.length > 1 ? requestLine[1] : "/";
                    int length = 0;
                    for (String line : lines) {
                        int at = line.indexOf(':');
                        if (at <= 0) continue;
                        String name = line.substring(0, at), value = line.substring(at + 1).trim();
                        request.headers.put(name, value);
                        if (name.equalsIgnoreCase("content-length")) length = Integer.parseInt(value);
                    }
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    for (int i = 0; i < length; i++) { int c = in.read(); if (c < 0) throw new EOFException(); body.write(c); }
                    request.body = body.toString("UTF-8");
                    requests.add(request);
                    Reply reply = script.reply(request, requests.size());
                    byte[] payload = reply.body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 " + reply.status + " Fixture\r\nContent-Type: " + reply.type
                            + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(payload);
                    out.flush();
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable failure) {
                    if (!server.isClosed()) this.failure = failure;
                }
            }
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
        Request first(String fragment) {
            for (Request request : new ArrayList<Request>(requests))
                if (request.path.contains(fragment)) return request;
            return null;
        }
        public void close() throws Exception {
            server.close();
            worker.join(9000);
            check(!worker.isAlive(), "fixture stopped");
            if (failure != null) throw new AssertionError("fixture failed", failure);
        }
    }

    // ------------------------------------------------------------------ documents

    static String modelList(String... ids) {
        JSONArray data = new JSONArray();
        for (String id : ids) data.put(new JSONObject().put("id", id));
        return new JSONObject().put("object", "list").put("data", data).toString();
    }

    static String clientConfigs(String url) {
        return new JSONObject().put("code", 0).put("msg", "ok")
                .put("data", new JSONObject().put("configs",
                        new JSONObject().put("builtin_provider_config_json", url))).toString();
    }

    /** A provider config for the zai individual plan: levels, window, output and image declared. */
    static String providerConfig(String plan) throws Exception {
        JSONObject providerRules = new JSONObject().put("providerRules", new JSONArray().put(new JSONObject()
                .put("providerId", plan)
                .put("builtinModelIds", new JSONArray().put("glm-5.3").put("glm-5.3-flash").put("glm-5-turbo"))));
        JSONArray modelRules = new JSONArray()
                .put(new JSONObject().put("modelMatch", "glm-5\\.3")
                        .put("config", new JSONObject()
                                .put("properties", new JSONObject().put("contextWindow", 200000)
                                        .put("inputFormat", new JSONObject().put("supportsImage", true)))
                                .put("optionSpecs", new JSONObject()
                                        .put("maxOutputTokens", new JSONObject().put("max", 65536))
                                        .put("reasoningLevel", new JSONObject()
                                                .put("values", new JSONArray().put("low").put("high"))))))
                .put(new JSONObject().put("modelMatch", "glm-5\\.3-flash")
                        .put("config", new JSONObject()
                                .put("optionSpecs", new JSONObject()
                                        .put("maxOutputTokens", new JSONObject().put("max", 128000))
                                        .put("reasoningLevel", new JSONObject()
                                                .put("values", new JSONArray().put("low").put("medium").put("high"))))));
        JSONObject config = new JSONObject()
                .put("providerConfigRules", providerRules)
                .put("modelConfigRules", new JSONObject().put("modelRules", modelRules));
        return new JSONObject().put("revision", 42).put("config", config).toString();
    }

    // ------------------------------------------------------------------ harness

    static JSONObject provider() {
        return new JSONObject().put("id", "zcode").put("name", "ZCode").put("protocol", "anthropic")
                .put("baseUrl", ZCodeAuth.ZAI_BASE)
                .put("keys", new JSONArray().put(KEY))
                .put("models", new JSONArray().put("stale-model"));
    }

    static JSONObject config() {
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", AUTH))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("failover", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(provider()));
    }

    static final class Harness implements AutoCloseable {
        final GatewayServer gateway;
        final Path dir;
        Harness(JSONObject config, Upstream upstream) throws Exception {
            gateway = RoutingSetupOfflineTest.server(config);
            dir = Files.createTempDirectory("zcode-cap-sync-");
            Path file = dir.resolve("config.json");
            Files.write(file, config.toString().getBytes(StandardCharsets.UTF_8));
            RoutingAuditOfflineTest.file(gateway, file.toFile());
            gateway.setVendorConnectionsForTests(url ->
                    NetworkPolicy.openHttp(upstream.url() + new URL(url).getFile()));
        }
        JSONObject providerNow() throws Exception {
            return ResponsesGatewayOfflineTest.config(gateway).getJSONArray("providers").getJSONObject(0);
        }
        NanoHTTPD.Response serve(String path, String body, String key) {
            return gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", path, "POST", body, key));
        }
        public void close() throws Exception {
            gateway.setVendorConnectionsForTests(null);
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    static String sync(Harness harness) throws Exception {
        NanoHTTPD.Response response = harness.serve("/api/auth/zcode/sync-models", "{}", null);
        String body = ResponsesGatewayOfflineTest.read(response.getData());
        check(response.getStatus().getRequestStatus() == 200,
                "sync answered: " + response.getStatus().getRequestStatus() + " " + body);
        return body;
    }

    static String chatBody(String model, String effort) throws Exception {
        return new JSONObject().put("model", "zcode/" + model).put("stream", false)
                .put("reasoning_effort", effort)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")))
                .toString();
    }

    static JSONObject ask(Harness harness, Upstream upstream, String model, String effort) throws Exception {
        int before = upstream.requests.size();
        NanoHTTPD.Response response = harness.serve("/v1/chat/completions", chatBody(model, effort), AUTH);
        String body = ResponsesGatewayOfflineTest.read(response.getData());
        check(response.getStatus().getRequestStatus() == 200,
                "chat answered: " + response.getStatus().getRequestStatus() + " " + body);
        check(upstream.requests.size() == before + 1, "one upstream call: " + (upstream.requests.size() - before));
        Request sent = upstream.requests.get(upstream.requests.size() - 1);
        check(sent.path.endsWith("/messages"), "the Anthropic endpoint was used: " + sent.path);
        return new JSONObject(sent.body);
    }

    // ------------------------------------------------------------------ cases

    /** The config's declarations are persisted and then decide the sent reasoning controls. */
    static void capabilitiesPersistedAndUsed() throws Exception {
        try (Upstream upstream = new Upstream((request, call) -> {
            if (request.path.endsWith("/models")) return new Reply(200, "application/json",
                    modelList("glm-5.3", "glm-5.3-flash", "glm-5-turbo"));
            if (request.path.contains("/api/v1/client/configs")) return new Reply(200, "application/json",
                    clientConfigs(CONFIG_URL));
            if (request.path.endsWith("/zcode-provider.json")) return new Reply(200, "application/json",
                    providerConfig(ZCodeProviderConfig.PLAN_ZAI));
            if (request.path.endsWith("/messages")) return new Reply(200, "application/json", ANTHROPIC_ANSWER);
            return new Reply(404, "application/json", "{}");
        })) {
            try (Harness harness = new Harness(config(), upstream)) {
                String body = sync(harness);
                check(new JSONObject(body).optBoolean("ok"), "sync ok: " + body);
                check(new JSONObject(body).getInt("count") == 3, "three models listed: " + body);

                JSONObject provider = harness.providerNow();
                JSONArray models = provider.getJSONArray("models");
                check(models.length() == 3, "catalog replaced by the live list: " + models);
                JSONObject meta = provider.getJSONObject("modelMeta");

                JSONObject main = meta.optJSONObject("glm-5.3");
                check(main != null, "glm-5.3 has a saved entry: " + meta);
                JSONArray efforts = main.optJSONArray("efforts");
                check(efforts != null && efforts.length() == 2, "levels saved: " + main);
                check("low".equals(efforts.optString(0)) && "high".equals(efforts.optString(1)),
                        "levels as the document lists them: " + efforts);
                check(main.optInt("contextWindow") == 200000, "context window saved: " + main);
                check(main.optInt("maxOutput") == 65536, "output ceiling saved: " + main);
                check(main.optBoolean("image"), "image support saved: " + main);

                JSONObject flash = meta.optJSONObject("glm-5.3-flash");
                check(flash != null && flash.optJSONArray("efforts").length() == 3, "flash levels saved: " + flash);
                check(flash.optInt("maxOutput") == 128000, "flash output saved: " + flash);
                check(meta.optJSONObject("glm-5-turbo") == null,
                        "a model that declares nothing gains no entry: " + meta);

                // The persisted output ceiling is what the request carries, and the saved levels fit max.
                JSONObject sent = ask(harness, upstream, "glm-5.3", "max");
                check(sent.optInt("max_tokens", -1) == 65536,
                        "the declared output ceiling is used: " + sent.opt("max_tokens"));
                JSONObject outputConfig = sent.optJSONObject("output_config");
                check(outputConfig != null && "high".equals(outputConfig.optString("effort")),
                        "max fits the model's own levels: " + sent);
                JSONObject thinking = sent.optJSONObject("thinking");
                check(thinking != null && "enabled".equals(thinking.optString("type")),
                        "thinking follows the fitted level: " + sent);
                check(thinking.optInt("budget_tokens", 0) == 24000,
                        "the high budget: " + thinking.opt("budget_tokens"));

                // Requests to the config chain carry no credential, and look like this app's other calls.
                Request configsCall = upstream.first("/api/v1/client/configs");
                check(configsCall != null, "the client-configs call happened");
                check(configsCall.headers.get("Authorization") == null, "no credential on the config call");
                check(configsCall.headers.get("User-Agent") != null
                        && configsCall.headers.get("User-Agent").startsWith("ZCode/"), "the ZCode user-agent");
                check(configsCall.headers.get("X-Device-Mid") != null, "the device header on the zcode origin");
                check(configsCall.path.contains("app_version=" + ZCodeAuth.APP_VERSION),
                        "the app version this app already claims: " + configsCall.path);
                check(configsCall.path.contains("platform=android"), "an honest platform token: " + configsCall.path);
                Request documentCall = upstream.first("/zcode-provider.json");
                check(documentCall != null, "the provider document was fetched");
                check(documentCall.headers.get("Authorization") == null,
                        "no credential on the CDN call either");
            }
        }
    }

    /**
     * Requirement: a failed capability read keeps the old catalog. The list may refresh; the
     * capabilities already known must survive, and nothing may be invented.
     */
    static void failureKeepsKnownCapabilities() throws Exception {
        final boolean[] configFails = {false};
        try (Upstream upstream = new Upstream((request, call) -> {
            if (request.path.endsWith("/models")) return new Reply(200, "application/json",
                    configFails[0] ? modelList("glm-5.3", "glm-5.2") : modelList("glm-5.3", "glm-5.3-flash"));
            if (request.path.contains("/api/v1/client/configs")) return configFails[0]
                    ? new Reply(503, "application/json", "{\"code\":500,\"msg\":\"unavailable\"}")
                    : new Reply(200, "application/json", clientConfigs(CONFIG_URL));
            if (request.path.endsWith("/zcode-provider.json")) return new Reply(200, "application/json",
                    providerConfig(ZCodeProviderConfig.PLAN_ZAI));
            if (request.path.endsWith("/messages")) return new Reply(200, "application/json", ANTHROPIC_ANSWER);
            return new Reply(404, "application/json", "{}");
        })) {
            try (Harness harness = new Harness(config(), upstream)) {
                sync(harness);
                JSONObject first = harness.providerNow().getJSONObject("modelMeta").optJSONObject("glm-5.3");
                check(first != null && first.optJSONArray("efforts").length() == 2, "first sync saved levels");

                configFails[0] = true;
                String body = sync(harness);
                check(new JSONObject(body).optBoolean("ok"), "a failed capability read still syncs: " + body);
                JSONObject provider = harness.providerNow();
                check(provider.getJSONArray("models").length() == 2, "the list did refresh: " + provider.getJSONArray("models"));

                JSONObject kept = provider.getJSONObject("modelMeta").optJSONObject("glm-5.3");
                check(kept != null, "the known entry survived the failure: " + provider.getJSONObject("modelMeta"));
                JSONArray efforts = kept.optJSONArray("efforts");
                check(efforts != null && efforts.length() == 2, "its levels survived: " + kept);
                check(kept.optInt("maxOutput") == 65536, "its output ceiling survived: " + kept);
                check(provider.getJSONObject("modelMeta").optJSONObject("glm-5.2") == null,
                        "nothing was invented for a model the config never described");

                // And the preserved level still governs the request.
                JSONObject sent = ask(harness, upstream, "glm-5.3", "max");
                JSONObject outputConfig = sent.optJSONObject("output_config");
                check(outputConfig != null && "high".equals(outputConfig.optString("effort")),
                        "the preserved levels still fit the request: " + sent);
            }
        }
    }

    /** A document that declares nothing leaves the built-in table in charge, exactly as before. */
    static void documentWithoutDeclarations() throws Exception {
        try (Upstream upstream = new Upstream((request, call) -> {
            if (request.path.endsWith("/models")) return new Reply(200, "application/json", modelList("glm-5.3"));
            if (request.path.contains("/api/v1/client/configs")) return new Reply(200, "application/json",
                    clientConfigs(CONFIG_URL));
            if (request.path.endsWith("/zcode-provider.json")) return new Reply(200, "application/json",
                    new JSONObject().put("revision", 1).put("config", new JSONObject()).toString());
            if (request.path.endsWith("/messages")) return new Reply(200, "application/json", ANTHROPIC_ANSWER);
            return new Reply(404, "application/json", "{}");
        })) {
            try (Harness harness = new Harness(config(), upstream)) {
                sync(harness);
                JSONObject meta = harness.providerNow().getJSONObject("modelMeta");
                check(meta.length() == 0, "no entry was invented: " + meta);
                JSONObject sent = ask(harness, upstream, "glm-5.3", "max");
                JSONObject outputConfig = sent.optJSONObject("output_config");
                check(outputConfig != null && "max".equals(outputConfig.optString("effort")),
                        "the built-in levels still apply: " + sent);
                JSONObject thinking = sent.optJSONObject("thinking");
                check(thinking != null && thinking.optInt("budget_tokens", 0) == 32000,
                        "and the built-in budget: " + thinking);
            }
        }
    }

    /** The config address is only followed when it is an https one; anything else is skipped. */
    static void badConfigAddressIsSkipped() throws Exception {
        try (Upstream upstream = new Upstream((request, call) -> {
            if (request.path.endsWith("/models")) return new Reply(200, "application/json", modelList("glm-5.3"));
            if (request.path.contains("/api/v1/client/configs")) return new Reply(200, "application/json",
                    clientConfigs("http://insecure.example.test/provider.json"));
            return new Reply(200, "application/json", providerConfig(ZCodeProviderConfig.PLAN_ZAI));
        })) {
            try (Harness harness = new Harness(config(), upstream)) {
                sync(harness);
                check(upstream.first("/provider.json") == null,
                        "a non-https provider address is never fetched");
                check(harness.providerNow().getJSONObject("modelMeta").length() == 0,
                        "and no capabilities are invented from it");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        capabilitiesPersistedAndUsed();
        failureKeepsKnownCapabilities();
        documentWithoutDeclarations();
        badConfigAddressIsSkipped();
        System.out.println("ZCodeCapabilitySyncHttpTest passed");
    }
}
