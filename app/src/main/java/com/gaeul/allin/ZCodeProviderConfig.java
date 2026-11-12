package com.gaeul.allin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/**
 * ZCode's own provider config, read for the capabilities each model declares.
 *
 * <p>Upstream reads the same document in {@code opencode-zcode-auth/source/index.mjs}: the address
 * comes from {@code clientConfigs()} ({@code /api/v1/client/configs}) and names
 * {@code configs.builtin_provider_config_json}; {@code modelsOf(cfg, plan)} then walks
 * {@code config.providerConfigRules.providerRules} and
 * {@code config.modelConfigRules.builtinProviderModelRules} for the plan's model ids and applies
 * {@code config.modelConfigRules.modelRules} to each id, taking {@code properties.contextWindow},
 * {@code optionSpecs.maxOutputTokens.max}, {@code properties.inputFormat.supportsImage} and
 * {@code optionSpecs.reasoningLevel.values} (disabled→none, enabled→high).
 *
 * <p>This class only parses. No network, no credentials, no persistence: the caller fetches the two
 * documents and decides what to save. Anything the document does not state is left absent rather
 * than guessed, and a rule whose pattern cannot be used is skipped as upstream skips it.
 */
public final class ZCodeProviderConfig {

    /** The plan ids ZCode's config uses, as upstream's {@code planID} derives them from a base URL. */
    public static final String PLAN_START = "account:zai-start-plan";
    public static final String PLAN_BIGMODEL = "account:bigmodel-individual-coding-plan";
    public static final String PLAN_ZAI = "account:zai-individual-coding-plan";

    // Bounds: a vendor document is untrusted input, so every walk is finite.
    static final int MAX_MODELS = 200;
    static final int MAX_RULES = 200;
    static final int MAX_PATTERN_CHARS = 200;
    static final int MAX_MODEL_ID_CHARS = 128;
    static final int MAX_EFFORTS = 16;

    private ZCodeProviderConfig() {}

    /** Upstream's planID: which plan's models the config describes for this endpoint. */
    static String planId(String base) {
        String url = base == null ? "" : base;
        if (url.contains("/zcode-plan/")) return PLAN_START;
        if (url.contains("bigmodel.cn")) return PLAN_BIGMODEL;
        return PLAN_ZAI;
    }

    /**
     * The provider-config address the client-configs document names, or "" when it names none that
     * this gateway may fetch: upstream requires an {@code https://} address and so does this.
     */
    static String configUrl(JSONObject clientConfigs) {
        if (clientConfigs == null) return "";
        JSONObject configs = clientConfigs.optJSONObject("configs");
        Object raw = configs == null ? null : configs.opt("builtin_provider_config_json");
        if (!(raw instanceof String)) return "";
        String url = ((String) raw).trim();
        if (url.length() > 2048 || !url.startsWith("https://")) return "";
        try {
            java.net.URI uri = new java.net.URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null) return "";
        } catch (Exception invalid) {
            return "";
        }
        return url;
    }

    /** One model as the config describes it: only the fields it actually declares are present. */
    static final class Model {
        final String id;
        int contextWindow, maxOutput;
        Boolean image;
        List<String> efforts = new ArrayList<String>();
        Model(String id) { this.id = id; }
        boolean empty() { return contextWindow <= 0 && maxOutput <= 0 && image == null && efforts.isEmpty(); }
    }

    /**
     * The models a plan's rules describe, in the config's own order, de-duplicated the way upstream
     * de-duplicates them: by lowercased id, first spelling kept, and entries the config disables
     * left out.
     */
    static List<Model> models(JSONObject providerConfig, String plan) {
        List<Model> out = new ArrayList<Model>();
        JSONObject config = providerConfig == null ? null : providerConfig.optJSONObject("config");
        if (config == null || plan == null || plan.isEmpty()) return out;

        LinkedHashMap<String, String> ids = new LinkedHashMap<String, String>();
        LinkedHashSet<String> disabled = new LinkedHashSet<String>();

        JSONObject providerRules = config.optJSONObject("providerConfigRules");
        JSONArray rules = providerRules == null ? null : providerRules.optJSONArray("providerRules");
        for (int i = 0; rules != null && i < rules.length() && i < MAX_RULES; i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !plan.equals(rule.optString("providerId", ""))) continue;
            JSONArray builtin = rule.optJSONArray("builtinModelIds");
            for (int j = 0; builtin != null && j < builtin.length() && ids.size() < MAX_MODELS; j++) {
                addId(ids, builtin.opt(j));
            }
        }

        JSONObject modelRules = config.optJSONObject("modelConfigRules");
        JSONArray providerModels = modelRules == null ? null
                : modelRules.optJSONArray("builtinProviderModelRules");
        for (int i = 0; providerModels != null && i < providerModels.length() && i < MAX_RULES; i++) {
            JSONObject rule = providerModels.optJSONObject(i);
            if (rule == null || !plan.equals(rule.optString("providerId", ""))) continue;
            Object id = rule.opt("modelId");
            if (!(id instanceof String)) continue;
            JSONObject settings = rule.optJSONObject("config");
            if (settings != null && Boolean.FALSE.equals(settings.opt("enabled"))) {
                String key = ((String) id).trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) disabled.add(key);
                continue;
            }
            if (ids.size() < MAX_MODELS) addId(ids, id);
        }

        JSONArray perModel = modelRules == null ? null : modelRules.optJSONArray("modelRules");
        for (String id : ids.values()) {
            if (disabled.contains(id.toLowerCase(Locale.ROOT))) continue;
            out.add(describe(id, perModel));
        }
        return out;
    }

    private static void addId(LinkedHashMap<String, String> ids, Object raw) {
        if (!(raw instanceof String)) return;
        String id = ((String) raw).trim();
        if (id.isEmpty() || id.length() > MAX_MODEL_ID_CHARS) return;
        String key = id.toLowerCase(Locale.ROOT);
        if (!ids.containsKey(key)) ids.put(key, id);
    }

    /** Upstream's per-model walk: every matching rule's declared fields, the last one winning. */
    private static Model describe(String id, JSONArray perModel) {
        Model model = new Model(id);
        if (perModel == null) return model;
        for (int i = 0; i < perModel.length() && i < MAX_RULES; i++) {
            JSONObject rule = perModel.optJSONObject(i);
            if (rule == null) continue;
            Object match = rule.opt("modelMatch");
            if (!(match instanceof String)) continue;
            String pattern = (String) match;
            if (pattern.isEmpty() || pattern.length() > MAX_PATTERN_CHARS) continue;
            Pattern compiled;
            try {
                compiled = Pattern.compile("^(?:" + pattern + ")$", Pattern.CASE_INSENSITIVE);
            } catch (Throwable unusable) {
                // Upstream skips a rule whose expression is not one, and so does this.
                continue;
            }
            boolean matches;
            try {
                matches = compiled.matcher(id).matches();
            } catch (Throwable unusable) {
                continue;
            }
            if (!matches) continue;
            JSONObject settings = rule.optJSONObject("config");
            if (settings == null) continue;
            JSONObject properties = settings.optJSONObject("properties");
            int window = positive(properties == null ? null : properties.opt("contextWindow"));
            if (window > 0) model.contextWindow = window;
            if (properties != null && properties.opt("inputFormat") instanceof JSONObject) {
                Object supports = ((JSONObject) properties.opt("inputFormat")).opt("supportsImage");
                if (supports instanceof Boolean) model.image = (Boolean) supports;
            }
            JSONObject specs = settings.optJSONObject("optionSpecs");
            JSONObject outputTokens = specs == null ? null : specs.optJSONObject("maxOutputTokens");
            int output = positive(outputTokens == null ? null : outputTokens.opt("max"));
            if (output > 0) model.maxOutput = output;
            JSONObject reasoning = specs == null ? null : specs.optJSONObject("reasoningLevel");
            JSONArray values = reasoning == null ? null : reasoning.optJSONArray("values");
            if (values != null && values.length() > 0) model.efforts = efforts(values);
        }
        return model;
    }

    /** Upstream's {@code efforts}: disabled→none, enabled→high, de-duplicated, order kept. */
    static List<String> efforts(JSONArray values) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; values != null && i < values.length() && out.size() < MAX_EFFORTS; i++) {
            Object raw = values.opt(i);
            if (!(raw instanceof String)) continue;
            String effort = ((String) raw).trim().toLowerCase(Locale.ROOT);
            if ("disabled".equals(effort)) effort = "none";
            else if ("enabled".equals(effort)) effort = "high";
            if (effort.isEmpty() || effort.length() > 32) continue;
            if (seen.add(effort)) out.add(effort);
        }
        return out;
    }

    private static int positive(Object raw) {
        if (!(raw instanceof Number)) return 0;
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0 || value > Integer.MAX_VALUE) return 0;
        if (value != Math.rint(value)) return 0;
        return (int) value;
    }

    /**
     * What a saved catalog entry should carry for each of the plan's models: {@code efforts},
     * {@code contextWindow}, {@code maxOutput} and {@code image}, plus the plan the rules came from.
     * Models that declare nothing are left out entirely, so an entry never appears with empty data
     * and no consumer can mistake "not stated" for "zero".
     */
    static JSONObject metadata(JSONObject providerConfig, String base) {
        JSONObject out = new JSONObject();
        String plan = planId(base);
        List<Model> models = models(providerConfig, plan);
        for (int i = 0; i < models.size(); i++) {
            Model model = models.get(i);
            if (model.empty()) continue;
            JSONObject entry = new JSONObject();
            try {
                if (!model.efforts.isEmpty()) {
                    JSONArray values = new JSONArray();
                    for (int j = 0; j < model.efforts.size(); j++) values.put(model.efforts.get(j));
                    entry.put("efforts", values);
                }
                if (model.contextWindow > 0) entry.put("contextWindow", model.contextWindow);
                if (model.maxOutput > 0) entry.put("maxOutput", model.maxOutput);
                if (model.image != null) entry.put("image", model.image.booleanValue());
                entry.put("zcodePlan", plan);
                out.put(model.id, entry);
            } catch (Exception ignored) {
                // A single unusable entry must not lose the rest of the document.
            }
        }
        return out;
    }
}
