package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Provider-specific reasoning policy for OpenAI-compatible requests sent as Anthropic Messages. */
final class AnthropicReasoningPolicy {
    enum Provider { ZCODE, MINIMAX }

    private static final Set<String> MINIMAX_PREVIEW_EFFORTS = setOf("low", "medium", "high", "xhigh", "max");
    private static final Set<String> MINIMAX_M3_EFFORTS = setOf("high");

    /**
     * Z.ai's GLM-5.2/5.3 take how hard they think from output_config.effort as well as from a
     * budget: magpie's effortInOutputConfig (internal/gateway/anthropic.go:799-812) lists them,
     * and gateway.go:6211 has them sent with the effort fitted to the model's own levels. Read
     * live by that project on GLM-5.3 (max thought about three times high's).
     */
    private static final Pattern ZCODE_OUTPUT_EFFORT = Pattern.compile("(?i)glm-5\\.[23](?:$|[-.:/\\[])");

    /** magpie's level order (internal/catalog/catalog.go EffortRank), used to fit an effort. */
    private static final String[] EFFORT_RANK = {"none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"};

    /** Live model capabilities. Mode and effort options are separate MiniMax metadata. */
    static final class ModelCapabilities {
        final Set<String> efforts;
        final boolean known;
        final boolean switchable;
        final boolean forcedOn;
        final boolean effortField;
        final boolean dynamicEfforts;

        private ModelCapabilities(Set<String> efforts, boolean known, boolean switchable,
                                  boolean forcedOn, boolean effortField, boolean dynamicEfforts) {
            this.efforts = normalized(efforts);
            this.known = known;
            this.switchable = switchable;
            this.forcedOn = forcedOn;
            this.effortField = effortField;
            this.dynamicEfforts = dynamicEfforts;
        }

        static ModelCapabilities zcode(Set<String> efforts) {
            return new ModelCapabilities(efforts, true, false, false, false, false);
        }

        static ModelCapabilities zcodeDynamic(Set<String> efforts) {
            return new ModelCapabilities(efforts, true, false, false, false, true);
        }

        static ModelCapabilities miniMax(Set<String> efforts, boolean switchable, boolean forcedOn) {
            Set<String> levels = efforts == null ? Collections.<String>emptySet() : efforts;
            return new ModelCapabilities(levels, true, switchable, forcedOn, !levels.isEmpty(), true);
        }

        static ModelCapabilities miniMaxFromCatalog(String model, JSONObject metadata) {
            if (metadata == null) return builtInCapabilities(Provider.MINIMAX, model);
            Object rawMode = metadata.opt("thinkingMode");
            if (rawMode == null) rawMode = metadata.opt("thinking_mode");
            if (rawMode == null) rawMode = metadata.opt("thinking");
            Object rawEfforts = metadata.opt("efforts");
            Set<String> levels = new HashSet<String>();
            if (rawEfforts instanceof com.gaeul.allin.json.JSONArray) {
                com.gaeul.allin.json.JSONArray values = (com.gaeul.allin.json.JSONArray) rawEfforts;
                for (int i = 0; i < values.length(); i++) {
                    Object value = values.opt(i);
                    if (value instanceof String) {
                        String effort = normalizeEffort(value);
                        if (!effort.isEmpty() && !"none".equals(effort) && !"default".equals(effort)) levels.add(effort);
                    }
                }
            }
            String mode = rawMode instanceof String ? ((String) rawMode).trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
            boolean hasMode = "switchable".equals(mode) || "forced_on".equals(mode)
                    || "forced_off".equals(mode) || "off".equals(mode) || "on".equals(mode);
            if (!hasMode && !(rawEfforts instanceof com.gaeul.allin.json.JSONArray))
                return builtInCapabilities(Provider.MINIMAX, model);
            boolean switchable = "switchable".equals(mode);
            boolean forcedOn = "forced_on".equals(mode);
            return new ModelCapabilities(levels, true, switchable, forcedOn, !levels.isEmpty(), true);
        }
    }

    private AnthropicReasoningPolicy() {}

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    /** MiniMax Code verified endpoint limit overrides its conservative catalog for these models. */
    static int verifiedOutputLimit(Provider provider, String model) {
        if (provider != Provider.MINIMAX) return 0;
        return MiniMaxAuth.verifiedModelOutputLimit(model);
    }

    /** Model capability/catalog default, then explicit Guard cap. */
    static int preferredOutputLimit(Provider provider, String model, int catalogOutput, int guardOutputCap) {
        int verified = verifiedOutputLimit(provider, model);
        int modelLimit = verified > 0 ? verified : (catalogOutput > 0 ? catalogOutput : builtInOutputLimit(provider, model));
        if (guardOutputCap > 0) modelLimit = modelLimit > 0 ? Math.min(modelLimit, guardOutputCap) : guardOutputCap;
        return modelLimit;
    }

    /** Never raise a client limit; known model limits and Guard caps can lower it. */
    static int effectiveOutputLimit(Provider provider, String model, int clientOutput,
                                    int catalogOutput, int guardOutputCap) {
        int verified = verifiedOutputLimit(provider, model);
        int modelLimit = preferredOutputLimit(provider, model, catalogOutput, 0);
        int selected = clientOutput > 0 ? clientOutput : modelLimit;
        if (guardOutputCap > 0) selected = selected > 0 ? Math.min(selected, guardOutputCap) : guardOutputCap;
        return selected;
    }

    static void apply(JSONObject request, JSONObject anthropic, Provider provider) throws Exception {
        String model = request == null ? "" : request.optString("model", "");
        apply(request, anthropic, provider, builtInCapabilities(provider, model));
    }

    /** Compatibility overload for live effort options; full MiniMax wiring should pass mode metadata too. */
    static void apply(JSONObject request, JSONObject anthropic, Provider provider,
                      Set<String> modelEfforts) throws Exception {
        String model = request == null ? "" : request.optString("model", "");
        ModelCapabilities caps = modelEfforts == null ? builtInCapabilities(provider, model)
                : provider == Provider.MINIMAX
                    ? ModelCapabilities.miniMax(modelEfforts, isMiniMaxM3(normalizeModel(model)), isMiniMaxPreview(normalizeModel(model)))
                    : ModelCapabilities.zcodeDynamic(modelEfforts);
        apply(request, anthropic, provider, caps);
    }

    static void apply(JSONObject request, JSONObject anthropic, Provider provider,
                      ModelCapabilities capabilities) throws Exception {
        // Compatibility entry point: an already positive max_tokens is read as a known cap.
        apply(request, anthropic, provider, capabilities,
                anthropic != null && positiveInteger(anthropic.opt("max_tokens")) > 0, 0);
    }

    /**
     * outputLimitKnown is false only when max_tokens is the Anthropic translation fallback and no
     * client, catalog or Guard value exists. Only then may a reasoning budget raise the output
     * limit, as magpie's translation does (internal/gateway/anthropic.go:1043-1055); a client or
     * catalog value is never raised, and a positive Guard cap always keeps the smaller value.
     */
    static void apply(JSONObject request, JSONObject anthropic, Provider provider,
                      ModelCapabilities capabilities, boolean outputLimitKnown,
                      int guardOutputCap) throws Exception {
        if (request == null || anthropic == null || provider == null) return;
        ModelCapabilities caps = capabilities == null
                ? builtInCapabilities(provider, request.optString("model", "")) : capabilities;
        copyExplicitControls(request, anthropic);
        String model = normalizeModel(request.optString("model", ""));
        String requestedEffort = normalizeEffort(request.opt("reasoning_effort"));
        JSONObject outputConfig = anthropic.optJSONObject("output_config");
        String configuredEffort = outputConfig == null ? "" : normalizeEffort(outputConfig.opt("effort"));

        JSONObject explicitConfig = request.optJSONObject("output_config");
        boolean explicitNativeEffort = explicitConfig != null && explicitConfig.has("effort");
        if (!configuredEffort.isEmpty() && !requestedEffort.isEmpty()
                && !requestedEffort.equals(configuredEffort))
            throw new IllegalArgumentException("Conflicting reasoning_effort and output_config.effort");

        // MiniMax fetch forwards the request body unchanged. Keep client-authored native
        // output_config.effort even when its model's default variant list omits that value.
        if (explicitNativeEffort) {
            if (request.has("thinking")) {
                if (request.isNull("thinking") || !(request.opt("thinking") instanceof JSONObject))
                    throw new IllegalArgumentException("thinking must be a non-null object");
            } else if (!requestedEffort.isEmpty()) {
                // A selected OpenAI effort asks for thinking; a native output_config alone does not.
                anthropic.put("thinking", new JSONObject().put("type", "adaptive"));
            }
            validateOutputCompatibility(anthropic);
            return;
        }

        if (request.has("thinking")) {
            if (request.isNull("thinking") || !(request.opt("thinking") instanceof JSONObject))
                throw new IllegalArgumentException("thinking must be a non-null object");
            if (!requestedEffort.isEmpty())
                throw new IllegalArgumentException("Conflicting explicit thinking and reasoning_effort controls");
            validateOutputCompatibility(anthropic);
            return;
        }
        if (!configuredEffort.isEmpty()) {
            if (provider == Provider.ZCODE) {
                // A level named for a Messages upstream is the model's own or its nearest, and
                // asking for one does not turn thinking on by itself (magpie anthropic.go:277-291,
                // 842-855); gateway.go:6228-6231 rewrites the body's level to the fitted one.
                anthropic.put("output_config", outputEffort(fitLevel(configuredEffort, caps), anthropic));
                validateOutputCompatibility(anthropic);
                return;
            }
            requireMiniMaxEffort(model, configuredEffort, caps);
            if (!caps.effortField)
                throw new IllegalArgumentException("This MiniMax model variant does not accept automatically mapped output_config.effort");
            anthropic.put("thinking", new JSONObject().put("type", "adaptive"));
            validateOutputCompatibility(anthropic);
            return;
        }
        if (requestedEffort.isEmpty()) {
            validateOutputCompatibility(anthropic);
            return;
        }

        if (provider == Provider.MINIMAX) {
            requireMiniMaxEffort(model, requestedEffort, caps);
            applyMiniMaxEffort(anthropic, requestedEffort, caps);
        } else {
            applyZCodeEffort(anthropic, model, requestedEffort, fitLevel(requestedEffort, caps),
                    positiveInteger(anthropic.opt("max_tokens")), outputLimitKnown, guardOutputCap);
        }
        validateOutputCompatibility(anthropic);
    }

    static void applyAutomatic(JSONObject request, JSONObject anthropic, Provider provider) throws Exception {
        String model = request == null ? "" : request.optString("model", "");
        applyAutomatic(request, anthropic, provider, builtInCapabilities(provider, model));
    }

    static void applyAutomatic(JSONObject request, JSONObject anthropic, Provider provider,
                               ModelCapabilities capabilities) throws Exception {
        if (request == null || anthropic == null || provider == null) return;
        validateOutputCompatibility(anthropic);
    }

    /** A later Guard cap cannot silently make an explicit thinking budget impossible. */
    static void validateExplicitOutputCompatibility(JSONObject anthropic, JSONObject request,
                                                    int guardOutputCap) {
        if (anthropic == null || request == null || guardOutputCap <= 0 || !request.has("max_tokens")) return;
        int explicitMax = positiveInteger(request.opt("max_tokens"));
        if (explicitMax <= 0 || explicitMax <= guardOutputCap) return;
        JSONObject thinking = request.optJSONObject("thinking");
        Object rawBudget = thinking == null ? null : thinking.opt("budget_tokens");
        if ("enabled".equals(thinking == null ? "" : thinking.optString("type", ""))
                && rawBudget instanceof Number && ((Number) rawBudget).doubleValue() >= guardOutputCap)
            throw new IllegalArgumentException("Guard max_output_tokens conflicts with explicit thinking.budget_tokens");
    }

    static void validateOutputCompatibility(JSONObject anthropic) {
        if (anthropic == null) return;
        Object rawThinking = anthropic.opt("thinking");
        JSONObject thinking = anthropic.optJSONObject("thinking");
        if (rawThinking != null && rawThinking != JSONObject.NULL && thinking == null)
            throw new IllegalArgumentException("thinking must be a non-null object");
        String type = thinking == null ? "" : thinking.optString("type", "");
        if (!type.isEmpty() && !"enabled".equals(type) && !"disabled".equals(type) && !"adaptive".equals(type))
            throw new IllegalArgumentException("Unsupported thinking.type");
        if ("enabled".equals(type)) {
            Object rawBudget = thinking.opt("budget_tokens");
            if (!(rawBudget instanceof Number))
                throw new IllegalArgumentException("thinking.budget_tokens must be a positive integer");
            double value = ((Number) rawBudget).doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value) || value < 1024 || value > Integer.MAX_VALUE)
                throw new IllegalArgumentException("thinking.budget_tokens must be an integer of at least 1024");
            int maxTokens = positiveInteger(anthropic.opt("max_tokens"));
            if (maxTokens > 0 && value >= maxTokens)
                throw new IllegalArgumentException("thinking.budget_tokens conflicts with final max_tokens");
        }
        // Native output_config may accompany enabled, adaptive, disabled or omitted
        // thinking. Effort alone must not implicitly opt a title request into reasoning.
    }

    private static int positiveInteger(Object value) {
        if (!(value instanceof Number)) return 0;
        double numeric = ((Number) value).doubleValue();
        return Double.isFinite(numeric) && numeric == Math.rint(numeric)
                && numeric > 0 && numeric <= Integer.MAX_VALUE ? (int) numeric : 0;
    }

    private static void copyExplicitControls(JSONObject request, JSONObject anthropic) throws Exception {
        if (request.has("thinking") && !anthropic.has("thinking")) anthropic.put("thinking", request.opt("thinking"));
        if (request.has("output_config") && !anthropic.has("output_config")) anthropic.put("output_config", request.opt("output_config"));
    }

    /**
     * ZCode's OpenAI-Chat-to-Messages translation, as magpie's translates it: the level reaches the
     * model as its own nearest (fitLevel, gateway.go:6228-6231 + ir.go:967-973), becomes a budget
     * (internal/gateway/ir.go:975-993 budgetOf) that fits under a client, catalog or Guard cap with
     * room left for the answer, and a cap of 2048 or less carries no thinking at all
     * (internal/gateway/anthropic.go:1031-1065). The plugin's variants
     * (opencode-zcode-auth index.mjs:1912-1915) are the budgets a client sends as explicit
     * thinking, taken over earlier and kept exactly as sent; they are not this mapping.
     */
    private static void applyZCodeEffort(JSONObject anthropic, String model, String effort, String level,
                                         int maxTokens, boolean outputLimitKnown, int guardOutputCap)
            throws Exception {
        if ("none".equals(effort)) {
            anthropic.put("thinking", new JSONObject().put("type", "disabled"));
            return;
        }
        // The level travels on its own for the models that take it, whatever the reply limit is:
        // magpie writes output_config.effort before the thinking budget is decided
        // (gateway.go:6206-6213 withOutputEffort, then anthropic.go:1031-1065).
        if (ZCODE_OUTPUT_EFFORT.matcher(model).find())
            anthropic.put("output_config", outputEffort(level, anthropic));
        // magpie's test is on the limit the request carried, not on a default it substitutes:
        // `think && (r.MaxTokens <= 0 || r.MaxTokens > 2048)`. A limit filled in from the catalog
        // is such a limit and is judged the same way; with none named at all the request thinks,
        // whatever the fallback would be.
        if (outputLimitKnown && maxTokens > 0 && maxTokens <= 2048) return;
        int budget = goEffortBudget(level);
        if (outputLimitKnown && maxTokens > 0) {
            budget = Math.min(budget, maxTokens - 1024);
        } else {
            // Nothing names an output limit: leave room for the answer, never above a Guard cap.
            int desired = budget + 4096;
            if (guardOutputCap > 0) desired = Math.min(desired, guardOutputCap);
            if (desired > maxTokens) {
                anthropic.put("max_tokens", desired);
                maxTokens = desired;
            }
            // magpie's own guard only lowers a limit the request already carried; here a cap the
            // user set always wins over a fallback that was just raised.
            if (guardOutputCap > 0 && maxTokens > guardOutputCap) {
                maxTokens = guardOutputCap;
                anthropic.put("max_tokens", maxTokens);
            }
            budget = Math.min(budget, maxTokens - 1024);
        }
        if (budget < 1024) return;
        anthropic.put("thinking", new JSONObject().put("type", "enabled").put("budget_tokens", budget));
    }

    /**
     * magpie's budgetOf (internal/gateway/ir.go:975-993), reached with the level as effortOf leaves
     * it: minimal and none read as low, a vendor's word by the level it names, ultra as max.
     */
    private static int goEffortBudget(String effort) {
        String level = levelOf(effort);
        if ("minimal".equals(level) || "none".equals(level) || "low".equals(level)) return 4096;
        if ("medium".equals(level)) return 10000;
        if ("high".equals(level)) return 24000;
        if ("xhigh".equals(level) || "max".equals(level) || "ultra".equals(level)) return 32000;
        return 10000; // budgetOf's default for a level it does not name
    }

    /** output_config.effort with the level the model is served at, alongside the budget. */
    private static JSONObject outputEffort(String level, JSONObject anthropic) throws Exception {
        JSONObject config = anthropic == null ? null : anthropic.optJSONObject("output_config");
        if (config == null) config = new JSONObject();
        config.put("effort", level);
        return config;
    }

    /**
     * magpie's catalog.NearestEffort (internal/catalog/catalog.go:2315-2340): the level itself
     * where the model takes it, else the closest by EffortRank with a tie going up, never "none"
     * unless that is what was asked. A word the rank does not know is left as it came — the vendor
     * may name it, and a vendor word is placed by the level it names (EffortLevel).
     */
    private static String nearestEffort(String want, Set<String> levels) {
        if (want == null || want.isEmpty()) return "";
        if (levels == null || levels.isEmpty() || levels.contains(want)) return want;
        int at = effortRank(levelOf(want));
        if (at < 0) return want;
        String best = want;
        int distance = Integer.MAX_VALUE;
        for (String level : levels) {
            if ("none".equals(level) || "ultra".equals(level)) continue;
            int index = effortRank(levelOf(level));
            if (index < 0) continue;
            int delta = index - at < 0 ? at - index : index - at;
            if (delta < distance || delta == distance && index > at) {
                best = level;
                distance = delta;
            }
        }
        return best;
    }

    /**
     * magpie's fitLevel/fitFor (internal/gateway/decide.go:773-790): the level the model is asked
     * at is its own nearest, a level it does not declare is not a reason to refuse the request, and
     * a model whose levels are not known is asked as the agent asked — NearestEffort returns the
     * level given when it has none to choose from, so an Ultra is the only word rewritten here.
     */
    static String fitLevel(String want, ModelCapabilities caps) {
        if (want == null || want.isEmpty()) return "";
        if ("ultra".equals(want)) want = "max"; // fitEffort rewrites ultra before fitting
        if (caps == null || !caps.known || caps.efforts.isEmpty()) return want;
        return nearestEffort(want, caps.efforts);
    }

    /** magpie's EffortLevel plus the ultra→max of fitEffort (catalog.go:2285-2330). */
    private static String levelOf(String effort) {
        if (effort == null) return "";
        if ("ultra".equals(effort)) return "max";
        if ("light".equals(effort)) return "low";
        if ("extra_high".equals(effort)) return "xhigh";
        return effort;
    }

    private static int effortRank(String level) {
        for (int i = 0; i < EFFORT_RANK.length; i++) if (EFFORT_RANK[i].equals(level)) return i;
        return -1;
    }

    private static void applyMiniMaxEffort(JSONObject anthropic, String effort,
                                           ModelCapabilities caps) throws Exception {
        if ("none".equals(effort)) {
            anthropic.put("thinking", new JSONObject().put("type", "disabled"));
            return;
        }
        anthropic.put("thinking", new JSONObject().put("type", "adaptive"));
        if (caps.effortField && caps.efforts.contains(effort)) {
            JSONObject config = anthropic.optJSONObject("output_config");
            if (config == null) config = new JSONObject();
            config.put("effort", effort);
            anthropic.put("output_config", config);
        }
    }

    /**
     * MiniMax declares its levels as plugin variants (opencode-minimax-auth index.mjs:168-176),
     * so a level it does not list is refused there. ZCode is not checked this way: magpie fits a
     * named level to the model's own instead (fitLevel, decide.go:773-790), and fitting happens in
     * applyZCodeEffort's caller.
     */
    private static void requireMiniMaxEffort(String model, String effort, ModelCapabilities caps) {
        if ("none".equals(effort)) {
            // Only a model known to keep thinking on may refuse "none". A model whose live
            // metadata has not arrived declares no mode (minimax index.mjs:161-176), so it is
            // not evidence that thinking cannot be turned off.
            if (caps.known && !caps.switchable)
                throw new IllegalArgumentException("reasoning_effort=none is not supported by this MiniMax model");
            return;
        }
        if (!caps.known) return; // no declared levels yet: adaptive thinking, no invented level
        boolean switchableHigh = caps.switchable && "high".equals(effort);
        if (!caps.efforts.contains(effort) && !switchableHigh)
            throw new IllegalArgumentException("reasoning_effort is not supported by this MiniMax model");
    }

    private static ModelCapabilities builtInCapabilities(Provider provider, String modelName) {
        String model = normalizeModel(modelName);
        if (provider == Provider.ZCODE) {
            if ("glm-5.3".equals(model) || "glm-5.3-flash".equals(model) || "glm-5.3-flashx".equals(model))
                return ModelCapabilities.zcode(setOf("low", "high", "max"));
            if ("glm-5.2".equals(model)) return ModelCapabilities.zcode(setOf("none", "high", "max"));
            if ("glm-5-turbo".equals(model)) return ModelCapabilities.zcode(setOf("none", "high"));
            return new ModelCapabilities(Collections.<String>emptySet(), false, false, false, false, false);
        }
        if (isMiniMaxPreview(model)) return new ModelCapabilities(MINIMAX_PREVIEW_EFFORTS, true, false, true, true, false);
        // M3.1-Flash has no built-in entry in the plugin's own list (minimax index.mjs:161-166
        // covers Preview, M3 and M2.7), so its levels and mode are whatever the live catalog says.
        // Without them the model is unknown, not a model that refuses every level: rejecting there
        // turned a working endpoint into a local 400 for any client that names an effort.
        if ("minimax-m3.1-flash".equals(model))
            return new ModelCapabilities(Collections.<String>emptySet(), false, false, false, false, false);
        if (isMiniMaxM3(model)) return new ModelCapabilities(MINIMAX_M3_EFFORTS, true, true, false, false, false);
        if ("minimax-m2.7".equals(model) || "minimax-m2.7-highspeed".equals(model))
            return new ModelCapabilities(Collections.<String>emptySet(), true, false, false, false, false);
        return new ModelCapabilities(Collections.<String>emptySet(), false, false, false, false, false);
    }

    private static Set<String> normalized(Set<String> source) {
        if (source == null) return Collections.emptySet();
        Set<String> result = new HashSet<String>();
        for (String value : source) if (value != null) {
            String normalized = normalizeEffort(value);
            if ("disabled".equals(normalized)) normalized = "none";
            else if ("enabled".equals(normalized)) normalized = "high";
            result.add(normalized);
        }
        return result;
    }

    private static int builtInOutputLimit(Provider provider, String modelName) {
        String model = normalizeModel(modelName);
        if (provider == Provider.ZCODE) {
            if ("glm-5.3".equals(model) || "glm-5.3-flash".equals(model)
                    || "glm-5.3-flashx".equals(model) || "glm-5.2".equals(model)) return 128000;
            if ("glm-5-turbo".equals(model)) return 64000;
        } else if ("minimax-m2.7".equals(model) || "minimax-m2.7-highspeed".equals(model)) return 128000;
        return 0;
    }

    private static String normalizeModel(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeEffort(Object value) {
        if (!(value instanceof String)) return "";
        String effort = ((String) value).trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(effort)) return "none";
        if ("enabled".equals(effort)) return "high";
        return effort;
    }
    private static boolean isMiniMaxPreview(String model) { return "minimax-m3.1-flash-preview".equals(model); }
    private static boolean isMiniMaxM3(String model) { return "minimax-m3".equals(model); }
}
