package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/**
 * ZCode's saved catalog entry drives the reasoning levels of the Anthropic translation, and nothing
 * else does.
 *
 * <p>Every case is one real gateway request on loopback with a synthetic provider and credential;
 * what is asserted is the Anthropic body the gateway actually sends upstream. The two halves are:
 * an entry that declares levels ({@code efforts}, upstream's own list, or {@code variants}, the map
 * it hands clients) fits the requested effort to those levels; an entry that declares none leaves
 * the built-in table in charge, exactly as before this wiring existed.
 */
public final class ZCodeDynamicCatalogGatewayHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static int cases;

    /** One Anthropic Messages answer, so a non-streaming client gets a complete reply. */
    static final String ANTHROPIC_ANSWER = "{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"fixture\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    /**
     * Sends one Chat request to the real gateway and returns the Anthropic body it posted upstream.
     * metadata is what a catalog sync would have saved for the model; null means "nothing saved".
     */
    static JSONObject sent(String model, JSONObject metadata, String effort, int clientMaxTokens) throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (request, n) -> new VendorPolicyGatewayHttpTest.Reply(200, "application/json", ANTHROPIC_ANSWER))) {
            JSONObject provider = VendorPolicyGatewayHttpTest.provider("zcode", "anthropic",
                    "http://127.0.0.1:" + upstream.server.getLocalPort() + "/v1", model);
            if (metadata != null) provider.put("modelMeta", new JSONObject().put(model, metadata));
            JSONObject config = VendorPolicyGatewayHttpTest.config(provider)
                    .put("guard", new JSONObject().put("enabled", true).put("maxOutputTokens", 0));
            JSONObject body = VendorPolicyGatewayHttpTest.body("zcode/" + model, false)
                    .put("max_tokens", clientMaxTokens);
            if (effort != null) body.put("reasoning_effort", effort);
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config, upstream, false)) {
                VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway,
                        "/v1/chat/completions", body);
                check(result.status == 200, "request answered: " + result.status + " " + result.body);
                check(upstream.requests.size() == 1, "one upstream request: " + upstream.requests.size());
                cases++;
                return upstream.requests.get(0).body;
            }
        }
    }

    static String effortOf(JSONObject anthropic) {
        JSONObject config = anthropic.optJSONObject("output_config");
        return config == null ? "" : config.optString("effort", "");
    }

    static int budgetOf(JSONObject anthropic) {
        JSONObject thinking = anthropic.optJSONObject("thinking");
        return thinking == null ? 0 : thinking.optInt("budget_tokens", 0);
    }

    /**
     * No saved metadata: the built-in table still decides, which is what every ZCode account looks
     * like today because a ZCode catalog sync stores model ids only.
     */
    static void builtInStillGoverns() throws Exception {
        JSONObject sent = sent("glm-5.3", null, "max", 60000);
        check("max".equals(effortOf(sent)), "built-in levels keep the asked level: " + sent);
        check(budgetOf(sent) == 32000, "built-in max budget: " + sent);
        JSONObject unknownModel = sent("glm-9.9", null, "max", 60000);
        check("".equals(effortOf(unknownModel)),
                "a model with no built-in entry writes no effort field, as before: " + unknownModel);
        check(budgetOf(unknownModel) == 32000, "an unknown model is asked as the agent asked: " + unknownModel);
    }

    /** A saved entry that declares efforts narrows the level to one the model really takes. */
    static void savedEffortsAreUsed() throws Exception {
        JSONObject sent = sent("glm-5.3",
                new JSONObject().put("efforts", new JSONArray().put("low").put("high")), "max", 60000);
        check("high".equals(effortOf(sent)),
                "a level the entry does not declare fits the nearest declared one: " + sent);
        check(budgetOf(sent) == 24000, "and its budget follows the fitted level: " + sent);
        // A level the entry does declare travels unchanged.
        JSONObject kept = sent("glm-5.3",
                new JSONObject().put("efforts", new JSONArray().put("low").put("high")), "low", 60000);
        check("low".equals(effortOf(kept)), "a declared level is kept: " + kept);
        check(budgetOf(kept) == 4096, "at its own budget: " + kept);
    }

    /** Upstream hands clients variants keyed by the same words; that shape is read too. */
    static void savedVariantsAreUsed() throws Exception {
        JSONObject variants = new JSONObject();
        variants.put("low", new JSONObject().put("thinking", new JSONObject().put("type", "enabled")));
        variants.put("medium", new JSONObject().put("thinking", new JSONObject().put("type", "enabled")));
        JSONObject sent = sent("glm-5.3", new JSONObject().put("variants", variants), "max", 60000);
        check("medium".equals(effortOf(sent)),
                "variants keys are the declared levels: " + sent);
        check(budgetOf(sent) == 10000, "medium budget follows: " + sent);
    }

    /**
     * A declaration this code cannot use changes nothing: a word that is not a level is not fitted
     * to, and no level is invented in its place.
     */
    static void unusableDeclarationFallsBackSafely() throws Exception {
        JSONObject sent = sent("glm-5.3",
                new JSONObject().put("efforts", new JSONArray().put("turbo")), "max", 60000);
        check("max".equals(effortOf(sent)),
                "an unrecognised word is never substituted for the asked level: " + sent);
        // Declared as empty, or only as a default the plugin uses for "unset", means no levels.
        JSONObject empty = sent("glm-5.3", new JSONObject().put("efforts", new JSONArray()), "max", 60000);
        check("max".equals(effortOf(empty)), "an empty declaration leaves the built-ins: " + empty);
        JSONObject onlyDefault = sent("glm-5.3",
                new JSONObject().put("efforts", new JSONArray().put("default")), "max", 60000);
        check("max".equals(effortOf(onlyDefault)),
                "a lone default is not a level: " + onlyDefault);
    }

    /** Declared levels also reach a model the built-in table knows nothing about. */
    static void savedLevelsReachUnknownModels() throws Exception {
        JSONObject control = sent("glm-9.9", null, "max", 60000);
        JSONObject declared = sent("glm-9.9",
                new JSONObject().put("efforts", new JSONArray().put("low").put("high")), "max", 60000);
        check(budgetOf(control) == 32000, "without metadata the asked level is used: " + control);
        check(budgetOf(declared) == 24000,
                "with a saved entry the nearest declared level is used: " + declared);
    }

    /** A request that names no effort is untouched by any of this. */
    static void noEffortUnaffected() throws Exception {
        JSONObject sent = sent("glm-5.3",
                new JSONObject().put("efforts", new JSONArray().put("low").put("high")), null, 60000);
        check("".equals(effortOf(sent)) && budgetOf(sent) == 0,
                "no reasoning controls are invented for a request that named none: " + sent);
    }

    public static void main(String[] args) throws Exception {
        builtInStillGoverns();
        savedEffortsAreUsed();
        savedVariantsAreUsed();
        unusableDeclarationFallsBackSafely();
        savedLevelsReachUnknownModels();
        noEffortUnaffected();
        System.out.println("ZCodeDynamicCatalogGatewayHttpTest passed: " + cases + " requests");
    }
}
