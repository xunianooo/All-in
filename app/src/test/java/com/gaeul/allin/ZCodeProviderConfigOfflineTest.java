package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * ZCode's provider-config parsing, checked against upstream's own walk
 * (opencode-zcode-auth {@code modelsOf}, source/index.mjs:1788-1836).
 *
 * <p>Pure: no network, no persistence. Every assertion here is about what the document says, and
 * what this class refuses to invent when it says nothing.
 */
public final class ZCodeProviderConfigOfflineTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** A document with one plan's rules, as ZCode publishes it. */
    static JSONObject document(String plan, JSONArray builtinIds, JSONArray providerModels, JSONArray modelRules)
            throws Exception {
        JSONObject providerRules = new JSONObject();
        if (builtinIds != null) {
            providerRules.put("providerRules", new JSONArray().put(new JSONObject()
                    .put("providerId", plan).put("builtinModelIds", builtinIds)));
        }
        JSONObject modelConfigRules = new JSONObject();
        if (providerModels != null) modelConfigRules.put("builtinProviderModelRules", providerModels);
        if (modelRules != null) modelConfigRules.put("modelRules", modelRules);
        JSONObject config = new JSONObject();
        if (builtinIds != null) config.put("providerConfigRules", providerRules);
        config.put("modelConfigRules", modelConfigRules);
        return new JSONObject().put("revision", 7).put("config", config);
    }

    static JSONObject rule(String match, int window, int maxOutput, Boolean image, String... efforts)
            throws Exception {
        JSONObject properties = new JSONObject();
        if (window > 0) properties.put("contextWindow", window);
        if (image != null) properties.put("inputFormat", new JSONObject().put("supportsImage", image.booleanValue()));
        JSONObject specs = new JSONObject();
        if (maxOutput > 0) specs.put("maxOutputTokens", new JSONObject().put("max", maxOutput));
        if (efforts != null && efforts.length > 0) {
            JSONArray values = new JSONArray();
            for (String effort : efforts) values.put(effort);
            specs.put("reasoningLevel", new JSONObject().put("values", values));
        }
        JSONObject settings = new JSONObject();
        if (properties.length() > 0) settings.put("properties", properties);
        if (specs.length() > 0) settings.put("optionSpecs", specs);
        return new JSONObject().put("modelMatch", match).put("config", settings);
    }

    static List<ZCodeProviderConfig.Model> models(JSONObject document, String plan) {
        return ZCodeProviderConfig.models(document, plan);
    }

    /** Upstream's planID: the endpoint decides which plan's models the document describes. */
    static void planSelection() {
        check(ZCodeProviderConfig.PLAN_START.equals(ZCodeProviderConfig.planId(
                "https://zcode.z.ai/api/v1/zcode-plan/anthropic")), "start plan base");
        check(ZCodeProviderConfig.PLAN_BIGMODEL.equals(ZCodeProviderConfig.planId(
                "https://open.bigmodel.cn/api/coding/paas/v4")), "bigmodel base");
        check(ZCodeProviderConfig.PLAN_ZAI.equals(ZCodeProviderConfig.planId(
                ZCodeAuth.ZAI_BASE)), "zai base");
        check(ZCodeProviderConfig.PLAN_ZAI.equals(ZCodeProviderConfig.planId(null)), "no base falls to the zai plan");
    }

    /** The ids come from both rule kinds, in order, de-duplicated case-insensitively. */
    static void modelIds() throws Exception {
        JSONObject doc = document(ZCodeProviderConfig.PLAN_ZAI,
                new JSONArray().put("glm-5.3").put("GLM-5.3-Flash"),
                new JSONArray().put(new JSONObject().put("providerId", ZCodeProviderConfig.PLAN_ZAI)
                        .put("modelId", "glm-5-turbo")),
                null);
        List<ZCodeProviderConfig.Model> models = models(doc, ZCodeProviderConfig.PLAN_ZAI);
        check(models.size() == 3, "three ids, no duplicate: " + models.size());
        check("glm-5.3".equals(models.get(0).id), "first spelling kept");
        check("GLM-5.3-Flash".equals(models.get(1).id), "spelling as the document has it");
        check("glm-5-turbo".equals(models.get(2).id), "provider rule id appended");

        // A provider-model rule that disables an id removes it, matched case-insensitively.
        JSONObject disabled = document(ZCodeProviderConfig.PLAN_ZAI,
                new JSONArray().put("glm-5.3").put("glm-5.2"),
                new JSONArray().put(new JSONObject().put("providerId", ZCodeProviderConfig.PLAN_ZAI)
                        .put("modelId", "GLM-5.3").put("config", new JSONObject().put("enabled", false))),
                null);
        List<ZCodeProviderConfig.Model> kept = models(disabled, ZCodeProviderConfig.PLAN_ZAI);
        check(kept.size() == 1 && "glm-5.2".equals(kept.get(0).id), "disabled id dropped, other kept");

        // Another plan's rules contribute nothing.
        JSONObject otherPlan = document("account:someone-else",
                new JSONArray().put("glm-5.3"), null, null);
        check(models(otherPlan, ZCodeProviderConfig.PLAN_ZAI).isEmpty(), "another plan's ids are ignored");
        check(models(new JSONObject(), ZCodeProviderConfig.PLAN_ZAI).isEmpty(), "no config means no models");
        check(models(null, ZCodeProviderConfig.PLAN_ZAI).isEmpty(), "null document means no models");
    }

    /** Every field upstream reads, and the disabled/enabled mapping of its level list. */
    static void declarations() throws Exception {
        JSONArray rules = new JSONArray()
                .put(rule("glm-5\\.[23]", 200000, 65536, Boolean.TRUE, "disabled", "low", "enabled", "max"));
        JSONObject doc = document(ZCodeProviderConfig.PLAN_ZAI,
                new JSONArray().put("glm-5.3").put("glm-5-turbo"), null, rules);
        List<ZCodeProviderConfig.Model> models = models(doc, ZCodeProviderConfig.PLAN_ZAI);

        ZCodeProviderConfig.Model matched = models.get(0);
        check(matched.contextWindow == 200000, "contextWindow read: " + matched.contextWindow);
        check(matched.maxOutput == 65536, "maxOutputTokens.max read: " + matched.maxOutput);
        check(Boolean.TRUE.equals(matched.image), "supportsImage read");
        check(matched.efforts.size() == 4, "four levels: " + matched.efforts);
        check("none".equals(matched.efforts.get(0)), "disabled reads as none");
        check("high".equals(matched.efforts.get(2)), "enabled reads as high");
        check("max".equals(matched.efforts.get(3)), "other words kept as written");

        // A rule that does not match the id leaves it undeclared, not zero.
        ZCodeProviderConfig.Model unmatched = models.get(1);
        check(unmatched.empty(), "an unmatched model declares nothing");
        check(ZCodeProviderConfig.metadata(doc, ZCodeAuth.ZAI_BASE).optJSONObject("glm-5-turbo") == null,
                "and gains no catalog entry");

        // Nothing declared means no entry at all: "not stated" must not read as "zero".
        JSONObject bare = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, null);
        check(ZCodeProviderConfig.metadata(bare, ZCodeAuth.ZAI_BASE).length() == 0,
                "a document without declarations produces no metadata");
    }

    /** The saved entry carries exactly what the gateway's readers consume, plus where it came from. */
    static void metadataShape() throws Exception {
        JSONArray rules = new JSONArray()
                .put(rule("glm-5\\.3", 200000, 65536, Boolean.FALSE, "low", "high"));
        JSONObject doc = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, rules);
        JSONObject meta = ZCodeProviderConfig.metadata(doc, ZCodeAuth.ZAI_BASE);
        JSONObject entry = meta.optJSONObject("glm-5.3");
        check(entry != null, "entry present");
        check(entry.getJSONArray("efforts").length() == 2, "efforts saved: " + entry);
        check(entry.getInt("contextWindow") == 200000, "context saved");
        check(entry.getInt("maxOutput") == 65536, "output saved");
        check(!entry.getBoolean("image"), "declared false image is saved as false");
        check(ZCodeProviderConfig.PLAN_ZAI.equals(entry.optString("zcodePlan")), "plan recorded");
        // The already-shipped reader agrees with the saved shape.
        check(readSavedEfforts(entry).size() == 2, "saved entry is what the reasoning policy reads");
    }

    /** Mirrors GatewayServer's reader: an "efforts" array is what it consumes. */
    static java.util.Set<String> readSavedEfforts(JSONObject entry) {
        java.util.Set<String> levels = new java.util.LinkedHashSet<String>();
        JSONArray values = entry.optJSONArray("efforts");
        for (int i = 0; values != null && i < values.length(); i++) {
            String value = values.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT);
            if ("disabled".equals(value)) value = "none";
            else if ("enabled".equals(value)) value = "high";
            if (!value.isEmpty() && !"default".equals(value)) levels.add(value);
        }
        return levels;
    }

    /** Untrusted input: unusable rules are skipped and every walk is bounded. */
    static void hostileInput() throws Exception {
        // An expression Java cannot compile is skipped, as upstream skips one JavaScript cannot compile.
        JSONArray rules = new JSONArray()
                .put(rule("(", 111, 222, null, "low"))
                .put(rule("glm-5\\.3", 333, 444, null, "high"));
        JSONObject doc = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, rules);
        ZCodeProviderConfig.Model model = models(doc, ZCodeProviderConfig.PLAN_ZAI).get(0);
        check(model.contextWindow == 333, "the usable rule still applies: " + model.contextWindow);

        // A pattern longer than the bound is not even compiled.
        StringBuilder huge = new StringBuilder();
        while (huge.length() < ZCodeProviderConfig.MAX_PATTERN_CHARS + 50) huge.append("a?");
        JSONArray oversized = new JSONArray().put(rule(huge.toString(), 999, 999, null));
        JSONObject ignored = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, oversized);
        check(models(ignored, ZCodeProviderConfig.PLAN_ZAI).get(0).empty(), "an oversized pattern is skipped");

        // A wrong type is not a value.
        JSONArray wrongTypes = new JSONArray().put(new JSONObject().put("modelMatch", 42));
        JSONObject typed = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, wrongTypes);
        check(models(typed, ZCodeProviderConfig.PLAN_ZAI).get(0).empty(), "a non-string pattern is skipped");

        // Non-numeric or non-integral numbers are not limits.
        JSONArray badNumbers = new JSONArray().put(new JSONObject().put("modelMatch", "glm-5\\.3")
                .put("config", new JSONObject()
                        .put("properties", new JSONObject().put("contextWindow", "big"))
                        .put("optionSpecs", new JSONObject()
                                .put("maxOutputTokens", new JSONObject().put("max", 1.5)))));
        JSONObject numbers = document(ZCodeProviderConfig.PLAN_ZAI, new JSONArray().put("glm-5.3"), null, badNumbers);
        check(models(numbers, ZCodeProviderConfig.PLAN_ZAI).get(0).empty(), "invalid numbers are ignored");

        // The model count is bounded.
        JSONArray many = new JSONArray();
        for (int i = 0; i < ZCodeProviderConfig.MAX_MODELS + 50; i++) many.put("model-" + i);
        JSONObject big = document(ZCodeProviderConfig.PLAN_ZAI, many, null, null);
        check(models(big, ZCodeProviderConfig.PLAN_ZAI).size() == ZCodeProviderConfig.MAX_MODELS,
                "the model walk is bounded");

        // An id longer than the bound is not a model id.
        StringBuilder longId = new StringBuilder();
        while (longId.length() < ZCodeProviderConfig.MAX_MODEL_ID_CHARS + 1) longId.append('x');
        JSONObject longIdDoc = document(ZCodeProviderConfig.PLAN_ZAI,
                new JSONArray().put(longId.toString()).put("glm-5.3"), null, null);
        check(models(longIdDoc, ZCodeProviderConfig.PLAN_ZAI).size() == 1, "an oversized id is skipped");
    }

    /** Only an https address this gateway may fetch is accepted. */
    static void configAddress() throws Exception {
        check("https://cdn.example/p.json".equals(ZCodeProviderConfig.configUrl(new JSONObject()
                .put("configs", new JSONObject().put("builtin_provider_config_json", "https://cdn.example/p.json")))),
                "https address accepted");
        for (String rejected : new String[]{
                "http://cdn.example/p.json", "ftp://cdn.example/p.json", "//cdn.example/p.json",
                "javascript:alert(1)", "https://", "https://user:pw@cdn.example/p.json", "not a url", ""}) {
            check(ZCodeProviderConfig.configUrl(new JSONObject().put("configs",
                    new JSONObject().put("builtin_provider_config_json", rejected))).isEmpty(),
                    "rejected: " + rejected);
        }
        check(ZCodeProviderConfig.configUrl(new JSONObject()).isEmpty(), "no configs object");
        check(ZCodeProviderConfig.configUrl(null).isEmpty(), "null document");
        check(ZCodeProviderConfig.configUrl(new JSONObject().put("configs",
                new JSONObject().put("builtin_provider_config_json", 42))).isEmpty(), "non-string address");
        StringBuilder huge = new StringBuilder("https://cdn.example/");
        while (huge.length() < 2100) huge.append('a');
        check(ZCodeProviderConfig.configUrl(new JSONObject().put("configs",
                new JSONObject().put("builtin_provider_config_json", huge.toString()))).isEmpty(),
                "an oversized address is refused");
    }

    public static void main(String[] args) throws Exception {
        planSelection();
        modelIds();
        declarations();
        metadataShape();
        hostileInput();
        configAddress();
        System.out.println("ZCodeProviderConfigOfflineTest passed");
    }
}
