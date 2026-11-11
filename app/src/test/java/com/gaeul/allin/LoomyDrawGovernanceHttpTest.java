package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Governance contracts for Loomy's image route: the draw must obey the same privacy switch, the same
 * model and account governance, the same request cancellation and the same outbound limiter as every
 * other model route. All traffic is synthetic loopback.
 */
public final class LoomyDrawGovernanceHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static final String ANSWER = "{\"created\":1700000000,\"data\":[{\"url\":\"https://cdn.example/a.png\"}],"
            + "\"points_consumed\":110}";
    static final String EMAIL = "user@example.com";

    static VendorPolicyGatewayHttpTest.Reply ok(int n) {
        return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", ANSWER);
    }

    static JSONObject draw(String model) {
        return new JSONObject().put("model", model).put("prompt", "portrait of " + EMAIL + " in a red hat");
    }

    /** A provider exercising every governance input: models, one account, whitelist, privacy, limits. */
    static JSONObject provider(JSONObject account, String... models) {
        JSONArray list = new JSONArray();
        for (String model : models) list.put(model);
        return new JSONObject().put("id", "loomy").put("name", "Loomy").put("protocol", "openai")
                .put("baseUrl", "https://loomyad.xunfei.cn/api/v1")
                .put("accounts", new JSONArray().put(account))
                .put("models", list);
    }

    static JSONObject account(String token) {
        return new JSONObject().put("id", "loomy-account").put("enabled", true).put("token", token);
    }

    static JSONObject config(JSONObject provider, JSONObject privacy, JSONObject exposed, JSONObject outbound) {
        JSONObject config = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("failover", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(provider));
        config.put("privacy", privacy == null ? new JSONObject().put("enabled", false) : privacy);
        if (exposed != null) config.put("exposed", exposed);
        if (outbound != null) config.put("outbound", outbound);
        return config;
    }

    /** The gateway's outbound limiter, so tickets can be proven returned. */
    static OutboundLimiter limiter(GatewayServer gateway) throws Exception {
        java.lang.reflect.Field field = GatewayServer.class.getDeclaredField("outboundLimiter");
        field.setAccessible(true);
        return (OutboundLimiter) field.get(gateway);
    }

    static int active(GatewayServer gateway) throws Exception { return limiter(gateway).active(); }
    static int queued(GatewayServer gateway) throws Exception { return limiter(gateway).queued(); }

    static VendorPolicyGatewayHttpTest.Result send(VendorPolicyGatewayHttpTest.Gateway gateway, JSONObject body)
            throws Exception {
        return VendorPolicyGatewayHttpTest.post(gateway, "/v1/images/generations", body);
    }

    // ------------------------------------------------------------------ privacy

    /**
     * The privacy switch applies to a prompt exactly as it does to chat text: the masked form leaves,
     * the counters the dashboard reports advance, and the answer is restored.
     */
    static void privacyOn() throws Exception {
        TestPrivacyScope.initialize();
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1"),
                             new JSONObject().put("enabled", true).put("maskEmails", true), null, null),
                     upstream, false)) {
            VendorPolicyGatewayHttpTest.Result result = send(gateway, draw("loomy/draw-1"));
            check(result.status == 200, "masked draw answers: " + result.status + " " + result.body);
            String sent = upstream.requests.get(0).body.toString();
            check(!sent.contains(EMAIL), "the prompt's address never leaves masked: " + sent);
            check(sent.contains("{{MASK_"), "a placeholder stands in for it: " + sent);
            check(sent.contains("in a red hat"), "the rest of the prompt is sent verbatim: " + sent);
            check(gateway.gateway.lastMaskEnabled, "the reported privacy state says masked");
            check(gateway.gateway.lastMaskedCount >= 1,
                    "the draw counts as a masked request: " + gateway.gateway.lastMaskedCount);
            check(gateway.gateway.totalMasked.get() >= 1, "the running total advanced");
            check(result.body.contains("https://cdn.example/a.png"), "the answer is passed back");
        }
    }

    /** With the switch off nothing is masked — the same baseline as chat. */
    static void privacyOff() throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1"),
                             new JSONObject().put("enabled", false), null, null), upstream, false)) {
            VendorPolicyGatewayHttpTest.Result result = send(gateway, draw("loomy/draw-1"));
            check(result.status == 200, "unmasked draw answers: " + result.status + " " + result.body);
            check(upstream.requests.get(0).body.toString().contains(EMAIL),
                    "with privacy off the prompt is relayed as written");
            check(!gateway.gateway.lastMaskEnabled, "the reported privacy state says unmasked");
        }
    }

    // ------------------------------------------------------------------ model and account governance

    /** The exposed allow list is the gateway's model whitelist; it applies to draws too. */
    static void exposedWhitelist() throws Exception {
        JSONObject exposed = new JSONObject().put("enabled", true)
                .put("items", new JSONArray().put("loomy/draw-1"));
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1", "draw-2"), null, exposed, null),
                     upstream, false)) {
            VendorPolicyGatewayHttpTest.Result listed = send(gateway, draw("loomy/draw-1"));
            check(listed.status == 200, "a listed model draws: " + listed.status + " " + listed.body);
            check(upstream.requests.size() == 1, "one request for the listed model");
            VendorPolicyGatewayHttpTest.Result hidden = send(gateway, draw("loomy/draw-2"));
            check(hidden.status == 403, "an unlisted model is refused: " + hidden.status + " " + hidden.body);
            check(upstream.requests.size() == 1, "an unlisted model never reaches the vendor");
        }
    }

    /** A model the provider does not offer is not servable, even with the allow list off. */
    static void unknownModel() throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1"), null, null, null), upstream, false)) {
            VendorPolicyGatewayHttpTest.Result result = send(gateway, draw("loomy/never-listed"));
            check(result.status == 403, "an unknown model is refused: " + result.status + " " + result.body);
            check(upstream.requests.isEmpty(), "and never dispatched");
        }
    }

    /** An account the catalog cannot vouch for is not used: disabled, off, expired, or out of scope. */
    static void accountUnavailable(String kind) throws Exception {
        JSONObject account = account("loomy-session");
        String expected;
        if ("disabled".equals(kind)) { account.put("enabled", false); expected = "signin"; }
        else if ("off".equals(kind)) { account.put("off", true); expected = "signin"; }
        else if ("expired".equals(kind)) { account.put("expiresAt", System.currentTimeMillis() - 1000L); expected = "signin"; }
        else { account.put("models", new JSONArray().put("other-model")); expected = "governance"; }
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account, "draw-1"), null, null, null), upstream, false)) {
            VendorPolicyGatewayHttpTest.Result result = send(gateway, draw("loomy/draw-1"));
            if ("signin".equals(expected)) {
                check(result.status == 401, kind + " account cannot draw: " + result.status + " " + result.body);
                check(result.body.contains("sign in again"), kind + " says what to do: " + result.body);
            } else {
                check(result.status == 403,
                        kind + " scoped account does not offer the model: " + result.status + " " + result.body);
            }
            check(upstream.requests.isEmpty(), kind + " account never dispatched");
        }
    }

    /** An empty or foreign model is the caller's error, and never a wildcard. */
    static void modelRequired() throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> ok(n));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1"), null, null, null), upstream, false)) {
            for (JSONObject body : new JSONObject[]{
                    new JSONObject().put("prompt", "a hat"),
                    new JSONObject().put("model", "").put("prompt", "a hat"),
                    new JSONObject().put("model", "   ").put("prompt", "a hat"),
                    new JSONObject().put("model", "loomy/").put("prompt", "a hat")}) {
                VendorPolicyGatewayHttpTest.Result result = send(gateway, body);
                check(result.status == 400, "a missing model is refused: " + result.status + " " + result.body);
            }
            VendorPolicyGatewayHttpTest.Result foreign = send(gateway, draw("openai/gpt-image-1"));
            check(foreign.status == 400, "another provider's model is refused: " + foreign.status);
            check(upstream.requests.isEmpty(), "no request without a valid model");
        }
    }

    // ------------------------------------------------------------------ cancellation and pacing

    /**
     * The draw goes through the outbound limiter: with a minimum interval configured the second draw
     * waits for its own slot, and both tickets are returned.
     */
    static void limiterPacing() throws Exception {
        final List<Long> arrivals = Collections.synchronizedList(new ArrayList<Long>());
        JSONObject outbound = new JSONObject().put("loomy", new JSONObject()
                .put("credential", new JSONObject().put("minIntervalMillis", 400)));
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> { arrivals.add(Long.valueOf(System.currentTimeMillis())); return ok(n); });
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config(provider(account("loomy-session"), "draw-1"), null, null, outbound), upstream, false)) {
            check(send(gateway, draw("loomy/draw-1")).status == 200, "first draw answers");
            check(send(gateway, draw("loomy/draw-1")).status == 200, "second draw answers");
            check(arrivals.size() == 2, "two upstream requests: " + arrivals.size());
            long gap = arrivals.get(1).longValue() - arrivals.get(0).longValue();
            check(gap >= 300, "the second draw waited for the limiter's interval: " + gap + "ms");
            check(active(gateway.gateway) == 0, "no ticket left active: " + active(gateway.gateway));
            check(queued(gateway.gateway) == 0, "nothing left waiting");
        }
    }

    /**
     * A deadline that expires while the vendor is still thinking ends the request as cancelled and
     * closes the connection — the limiter ticket is returned and nothing is replayed.
     */
    static void cancellation() throws Exception {
        final List<Long> arrivals = Collections.synchronizedList(new ArrayList<Long>());
        JSONObject config = config(provider(account("loomy-session"), "draw-1"), null, null, null);
        config.put("failover", new JSONObject().put("enabled", false).put("requestBudgetMs", 1000));
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> {
                    arrivals.add(Long.valueOf(System.currentTimeMillis()));
                    try { Thread.sleep(4000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                    return ok(n);
                });
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     config, upstream, false)) {
            long started = System.currentTimeMillis();
            VendorPolicyGatewayHttpTest.Result result = send(gateway, draw("loomy/draw-1"));
            long elapsed = System.currentTimeMillis() - started;
            check(result.status == 503, "an expired deadline is not a vendor answer: " + result.status + " " + result.body);
            check(elapsed < 3500, "the deadline cut the wait instead of the vendor's delay: " + elapsed + "ms");
            check(arrivals.size() == 1, "the draw is never replayed: " + arrivals.size());
            check(active(gateway.gateway) == 0,
                    "the cancelled send's ticket was returned: " + active(gateway.gateway));
        }
    }

    public static void main(String[] args) throws Exception {
        privacyOn();
        privacyOff();
        exposedWhitelist();
        unknownModel();
        for (String kind : new String[]{"disabled", "off", "expired", "scoped"}) accountUnavailable(kind);
        modelRequired();
        limiterPacing();
        cancellation();
        System.out.println("LoomyDrawGovernanceHttpTest passed");
    }
}
