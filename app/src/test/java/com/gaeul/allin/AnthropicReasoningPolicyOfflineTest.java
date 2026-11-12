package com.gaeul.allin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.gaeul.allin.json.JSONObject;

/** Focused offline contracts for ZCode and MiniMax Anthropic reasoning projection. */
public final class AnthropicReasoningPolicyOfflineTest {
    private static JSONObject json(String value) throws Exception { return new JSONObject(value); }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static Set<String> efforts(String... values) { return new HashSet<String>(Arrays.asList(values)); }

    public static void main(String[] args) throws Exception {
        // ZCode levels are the plugin's, but the budget a level is served at comes from magpie's
        // translation table (internal/gateway/ir.go:975-993 budgetOf), which is what a client's
        // reasoning_effort goes through; the plugin's own numbers (index.mjs:1912-1915, max 31999)
        // are the client-sent variants, preserved untouched elsewhere.
        String[] zModels = {"GLM-5.3", "GLM-5.3-Flash", "GLM-5.2", "GLM-5-Turbo"};
        String[][] zLevels = {{"low", "high", "max"}, {"low", "high", "max"}, {"none", "high", "max"}, {"none", "high"}};
        for (int i = 0; i < zModels.length; i++) {
            Set<String> supported = efforts(zLevels[i]);
            for (String level : zLevels[i]) {
                JSONObject body = json("{\"model\":\"" + zModels[i] + "\",\"max_tokens\":50000,\"reasoning_effort\":\"" + level + "\"}");
                JSONObject out = ZCodeAnthropic.toAnthropic(body, true);
                if ("none".equals(level)) check("disabled".equals(out.getJSONObject("thinking").getString("type")), "ZCode none " + zModels[i]);
                else if (supported.contains(level)) {
                    int expected = "low".equals(level) ? 4096 : "high".equals(level) ? 24000 : 32000;
                    check("enabled".equals(out.getJSONObject("thinking").getString("type")), "ZCode level " + zModels[i]);
                    check(out.getJSONObject("thinking").getInt("budget_tokens") == expected,
                            "ZCode translation budget " + zModels[i] + "/" + level);
                    // GLM-5.2/5.3 take the level itself beside the budget (magpie anthropic.go:842-855).
                    if (zModels[i].startsWith("GLM-5.3") || "GLM-5.2".equals(zModels[i]))
                        check(level.equals(out.getJSONObject("output_config").getString("effort")),
                                "ZCode GLM-5.x output_config.effort " + zModels[i] + "/" + level);
                    else check(!out.has("output_config"), "GLM-5-Turbo carries no output effort");
                }
            }
        }
        // A level the model does not declare is fitted to its own, not refused: magpie's
        // fitEffort/NearestEffort (internal/gateway/decide.go:773-790, catalog.go:2315-2340) and
        // gateway.go:6228-6231, which rewrites the body's level to the fitted one.
        JSONObject zFittedMedium = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":50000,\"reasoning_effort\":\"medium\"}"), true);
        check("high".equals(zFittedMedium.getJSONObject("output_config").getString("effort")),
                "medium fits high on low/high/max, a tie going up");
        check(zFittedMedium.getJSONObject("thinking").getInt("budget_tokens") == 24000,
                "the budget follows the fitted level, not the asked one");
        JSONObject zFittedMinimal = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":50000,\"reasoning_effort\":\"minimal\"}"), true);
        check("low".equals(zFittedMinimal.getJSONObject("output_config").getString("effort"))
                        && zFittedMinimal.getJSONObject("thinking").getInt("budget_tokens") == 4096,
                "minimal fits low");
        JSONObject zNoneUndeclared = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3\",\"reasoning_effort\":\"none\"}"), true);
        check("disabled".equals(zNoneUndeclared.getJSONObject("thinking").getString("type")),
                "none turns thinking off even where the model does not declare it");
        JSONObject zTurboMax = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5-Turbo\",\"max_tokens\":50000,\"reasoning_effort\":\"max\"}"), true);
        check(zTurboMax.getJSONObject("thinking").getInt("budget_tokens") == 24000 && !zTurboMax.has("output_config"),
                "GLM-5-Turbo takes none/high only, so max is served at high with no output effort");
        // An unknown model has no levels to fit to: it is asked as the agent asked.
        JSONObject zUnknownMedium = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-future\",\"max_tokens\":50000,\"reasoning_effort\":\"medium\"}"), true);
        check(zUnknownMedium.getJSONObject("thinking").getInt("budget_tokens") == 10000,
                "an unknown model keeps the level it was asked for");
        // An explicit native control is relayed, never refitted.
        JSONObject zNativeEffort = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.2\",\"max_tokens\":50000,\"output_config\":{\"effort\":\"medium\"}}"), true);
        check("medium".equals(zNativeEffort.getJSONObject("output_config").getString("effort")) && !zNativeEffort.has("thinking"),
                "a client's own output_config.effort is relayed unchanged and enables no thinking");
        check(AnthropicReasoningPolicy.fitLevel("ultra", AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts("low", "high", "max"))).equals("max"),
                "ultra is max, as fitEffort's own rewrite");
        check(AnthropicReasoningPolicy.fitLevel("light", AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts("low", "max"))).equals("low"),
                "a vendor word is fitted through EffortLevel");
        // The live catalog's levels are the ones a request is fitted to (magpie p.Efforts(model),
        // filled from the plugin's /v3/config-derived list, decide.go:773-790).
        JSONObject liveLevels = new JSONObject().put("model", "GLM-5.3").put("max_tokens", 50000).put("reasoning_effort", "high");
        JSONObject liveFitted = new JSONObject().put("max_tokens", 50000);
        AnthropicReasoningPolicy.apply(new JSONObject(liveLevels.toString()), liveFitted,
                AnthropicReasoningPolicy.Provider.ZCODE,
                AnthropicReasoningPolicy.ModelCapabilities.zcodeDynamic(efforts("low", "max")));
        check(liveFitted.getJSONObject("thinking").getInt("budget_tokens") == 32000,
                "high between low and max is a tie, so the live catalog fits it up to max");
        JSONObject livePreview = ZCodeAnthropic.toAnthropic(
                json("{\"model\":\"GLM-5.3\",\"max_tokens\":50000,\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE,
                AnthropicReasoningPolicy.ModelCapabilities.zcodeDynamic(efforts("low", "high", "max")));
        check("high".equals(livePreview.getJSONObject("output_config").getString("effort")),
                "a level the live catalog declares is kept as asked");
        JSONObject zAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":50000}"), true);
        check(!zAuto.has("thinking"), "ZCode variant is opt-in; no automatic 4096 thinking budget");
        JSONObject zExplicitConfigured = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3\",\"max_tokens\":50000,\"reasoning_effort\":\"high\"}"),
                true, AnthropicReasoningPolicy.Provider.ZCODE,
                AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts("high")));
        check("enabled".equals(zExplicitConfigured.getJSONObject("thinking").getString("type")),
                "ZCode declared level remains enabled-budget");
        check(zExplicitConfigured.getJSONObject("thinking").getInt("budget_tokens") == 24000,
                "ZCode high uses the translation budget whatever the host model metadata says");
        JSONObject dynamicZ = new JSONObject().put("model", "GLM-dynamic").put("max_tokens", 50000).put("reasoning_effort", "minimal");
        JSONObject dynamicZOut = new JSONObject().put("max_tokens", 50000);
        AnthropicReasoningPolicy.apply(dynamicZ, dynamicZOut, AnthropicReasoningPolicy.Provider.ZCODE,
                AnthropicReasoningPolicy.ModelCapabilities.zcodeDynamic(efforts("minimal", "max")));
        check(dynamicZOut.getJSONObject("thinking").getInt("budget_tokens") == 4096, "minimal is low in the translation chain");
        // A model id that is not in the built-in variant list and has no live metadata yet must
        // still map the model-agnostic effort table instead of rejecting the request.
        JSONObject unknownZ = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-future\",\"max_tokens\":50000,\"reasoning_effort\":\"medium\"}"), true);
        check("enabled".equals(unknownZ.getJSONObject("thinking").getString("type"))
                && unknownZ.getJSONObject("thinking").getInt("budget_tokens") == 10000,
                "unknown ZCode model maps the translation budget table instead of blocking");

        // A reasoning budget must leave room for the answer, and a cap that small cannot think:
        // magpie's translation does both (internal/gateway/anthropic.go:1043-1055). An explicit
        // client value is only ever respected or fitted under, never raised.
        JSONObject tight = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":20000,\"reasoning_effort\":\"high\"}"), true);
        check(tight.getInt("max_tokens") == 20000 && tight.getJSONObject("thinking").getInt("budget_tokens") == 18976,
                "client cap keeps its value and bounds the budget with room left");
        JSONObject atThreshold = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":2048,\"reasoning_effort\":\"high\"}"), true);
        check(atThreshold.getInt("max_tokens") == 2048 && !atThreshold.has("thinking"),
                "a 2048 cap carries no thinking");
        JSONObject justAbove = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":2049,\"reasoning_effort\":\"high\"}"), true);
        check(justAbove.getInt("max_tokens") == 2049 && justAbove.getJSONObject("thinking").getInt("budget_tokens") == 1025,
                "2049 keeps a 1025 budget under the client cap");
        JSONObject unknownHigh = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-future\",\"reasoning_effort\":\"high\"}"), true);
        check(unknownHigh.getInt("max_tokens") == 28096 && unknownHigh.getJSONObject("thinking").getInt("budget_tokens") == 24000,
                "with no limit at all the translation fallback is raised to leave 4096 for the answer");
        // The level and the budget are separate decisions upstream: withOutputEffort writes the
        // effort whatever the reply limit is (gateway.go:6206-6213), and only the budget is
        // withheld on a title-sized cap (anthropic.go:1031-1065).
        check("high".equals(atThreshold.getJSONObject("output_config").getString("effort")),
                "a 2048 cap still carries the level it asked for, just no thinking");
        JSONObject unknownLow = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-future\",\"reasoning_effort\":\"low\"}"), true);
        check(unknownLow.getInt("max_tokens") == 16384 && unknownLow.getJSONObject("thinking").getInt("budget_tokens") == 4096,
                "a budget below the fallback never lowers it");
        JSONObject guardedRaise = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-future\",\"reasoning_effort\":\"max\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE, null, 0, 8000);
        check(guardedRaise.getInt("max_tokens") == 8000 && guardedRaise.getJSONObject("thinking").getInt("budget_tokens") == 6976,
                "a Guard cap bounds the raised fallback too");
        JSONObject guardedCatalog = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE, null, 128000, 8000);
        check(guardedCatalog.getInt("max_tokens") == 8000 && guardedCatalog.getJSONObject("thinking").getInt("budget_tokens") == 6976,
                "a Guard cap stays final over a catalog default");

        // A catalog default is a limit the request carries, so the 2048 rule judges it as magpie's
        // translation judges r.MaxTokens (internal/gateway/anthropic.go:1038): above it, thinking
        // fits under it; at or below it, the request is a title-sized ask and carries none.
        JSONObject catalogSmall = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE, null, 8192, 0);
        check(catalogSmall.getInt("max_tokens") == 8192
                        && catalogSmall.getJSONObject("thinking").getInt("budget_tokens") == 7168,
                "a catalog default above 2048 keeps thinking, fitted under it");
        JSONObject catalogAtLimit = ZCodeAnthropic.toAnthropic(json("{\"model\":\"GLM-5.3-Flash\",\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE, null, 2048, 0);
        check(catalogAtLimit.getInt("max_tokens") == 2048 && !catalogAtLimit.has("thinking")
                        && "high".equals(catalogAtLimit.getJSONObject("output_config").getString("effort")),
                "a catalog default of 2048 carries the level but no thinking");
        // A limit the client named outranks the catalog, so it is the client's number the 2048 rule
        // judges — a catalog default of 128000 cannot re-enable thinking on a 2048 ask.
        JSONObject clientBeatsCatalog = ZCodeAnthropic.toAnthropic(
                json("{\"model\":\"GLM-5.3-Flash\",\"max_tokens\":2048,\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.ZCODE, null, 128000, 0);
        check(clientBeatsCatalog.getInt("max_tokens") == 2048 && !clientBeatsCatalog.has("thinking"),
                "a client-named 2048 stays a title-sized ask beside a larger catalog default");
        // With no limit named at all there is nothing to judge: magpie's `r.MaxTokens <= 0` branch
        // thinks whatever the fallback would be, so a small pre-set value alone cannot suppress it.
        JSONObject unnamed = new JSONObject().put("max_tokens", 1000);
        AnthropicReasoningPolicy.apply(json("{\"model\":\"GLM-5.3-Flash\",\"reasoning_effort\":\"high\"}"), unnamed,
                AnthropicReasoningPolicy.Provider.ZCODE, null, false, 0);
        check(unnamed.getInt("max_tokens") == 28096 && unnamed.getJSONObject("thinking").getInt("budget_tokens") == 24000,
                "an unnamed limit is never read as a title-sized cap");
        JSONObject unnamedGuarded = new JSONObject().put("max_tokens", 20000);
        AnthropicReasoningPolicy.apply(json("{\"model\":\"GLM-5.3-Flash\",\"reasoning_effort\":\"max\"}"), unnamedGuarded,
                AnthropicReasoningPolicy.Provider.ZCODE, null, false, 8000);
        check(unnamedGuarded.getInt("max_tokens") == 8000
                        && unnamedGuarded.getJSONObject("thinking").getInt("budget_tokens") == 6976,
                "a Guard cap lowers a fallback the same code just raised");
        // fitLevel leaves a word it cannot place exactly as magpie's NearestEffort does, and only
        // Ultra is rewritten when there are no levels to fit to.
        check("light".equals(AnthropicReasoningPolicy.fitLevel("light",
                        AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts()))),
                "an unknown model is asked with the word it asked for");
        check("max".equals(AnthropicReasoningPolicy.fitLevel("ultra",
                        AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts()))),
                "ultra is max even with no levels to fit to");
        check("light".equals(AnthropicReasoningPolicy.fitLevel("light",
                        AnthropicReasoningPolicy.ModelCapabilities.zcode(efforts("light", "high")))),
                "a vendor word the model declares is kept as declared");

        check(AnthropicReasoningPolicy.verifiedOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3") == 524288, "verified M3 output capability overrides conservative catalog");
        check(AnthropicReasoningPolicy.verifiedOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3.1-Flash") == 524288, "verified M3.1 Flash output capability");
        check(AnthropicReasoningPolicy.verifiedOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3.1-Flash-Preview") == 524288, "verified Preview output capability");
        check(AnthropicReasoningPolicy.verifiedOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M2.7") == 0, "unverified model uses its live catalog value");
        AnthropicReasoningPolicy.ModelCapabilities previewCatalog=AnthropicReasoningPolicy.ModelCapabilities.miniMaxFromCatalog(
                "MiniMax-live-preview",new JSONObject().put("thinkingMode","forced_on")
                        .put("efforts",new com.gaeul.allin.json.JSONArray().put("low").put("max").put("default")));
        check(previewCatalog.forcedOn && previewCatalog.effortField
                && previewCatalog.efforts.contains("max") && !previewCatalog.efforts.contains("default"),
                "live MiniMax thinking mode and effortOptions drive conversion");
        AnthropicReasoningPolicy.ModelCapabilities switchableCatalog=AnthropicReasoningPolicy.ModelCapabilities.miniMaxFromCatalog(
                "MiniMax-live-switch",new JSONObject().put("thinkingMode","switchable").put("efforts",new com.gaeul.allin.json.JSONArray()));
        check(switchableCatalog.switchable && !switchableCatalog.forcedOn && !switchableCatalog.effortField,
                "live switchable mode is distinct from effort field support");
        check(AnthropicReasoningPolicy.preferredOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3.1-Flash-Preview", 77777, 0) == 524288, "verified limit overrides stale catalog");
        check(AnthropicReasoningPolicy.preferredOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3.1-Flash-Preview", 77777, 8000) == 8000, "Guard cap overrides verified default");
        check(AnthropicReasoningPolicy.preferredOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M2.7", 128000, 0) == 128000, "unverified model uses current catalog");
        check(AnthropicReasoningPolicy.effectiveOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3", 400000, 77777, 0) == 400000, "valid explicit max remains unchanged");
        check(AnthropicReasoningPolicy.effectiveOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3", 600000, 77777, 0) == 600000, "explicit client max remains unchanged even above verified default");
        check(AnthropicReasoningPolicy.effectiveOutputLimit(AnthropicReasoningPolicy.Provider.MINIMAX,
                "MiniMax-M3", 600000, 77777, 8000) == 8000, "Guard can lower an explicit max before capability validation");

        // MiniMax fallback variants mirror the plugin: Preview uses adaptive+effort;
        // M3 switchable exposes none and adaptive-high without output_config.effort.
        String[] previewEfforts = {"low", "medium", "high", "xhigh", "max"};
        for (String level : previewEfforts) {
            JSONObject out = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":524288,\"reasoning_effort\":\"" + level + "\"}"), true,
                    AnthropicReasoningPolicy.Provider.MINIMAX);
            check("adaptive".equals(out.getJSONObject("thinking").getString("type")), "MiniMax Preview adaptive " + level);
            check(level.equals(out.getJSONObject("output_config").getString("effort")), "MiniMax Preview effort " + level);
        }
        JSONObject m3High = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"reasoning_effort\":\"high\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check("adaptive".equals(m3High.getJSONObject("thinking").getString("type")), "MiniMax M3 high adaptive");
        check(!m3High.has("output_config"), "MiniMax M3 high variant carries no output effort");
        JSONObject m3OutputConfig = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"output_config\":{\"effort\":\"xhigh\"}}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(!m3OutputConfig.has("thinking")
                && "xhigh".equals(m3OutputConfig.getJSONObject("output_config").getString("effort")),
                "explicit native M3 output_config effort passes through beyond its default high variant");
        JSONObject m3UnknownOutputConfig = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"output_config\":{\"effort\":\"custom-level\"}}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check("custom-level".equals(m3UnknownOutputConfig.getJSONObject("output_config").getString("effort")),
                "explicit native output_config effort is not capability-filtered");
        rejected(() -> {
            try { ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"reasoning_effort\":\"high\",\"output_config\":{\"effort\":\"xhigh\"}}"), true,
                    AnthropicReasoningPolicy.Provider.MINIMAX); }
            catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }, "conflicting explicit output_config and OpenAI reasoning effort reject");
        JSONObject m3None = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"reasoning_effort\":\"none\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check("disabled".equals(m3None.getJSONObject("thinking").getString("type")), "MiniMax M3 switchable none");
        JSONObject miniAutoDefault = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(miniAutoDefault.getInt("max_tokens") == 524288, "verified MiniMax capability is the automatic output fallback");
        check(!miniAutoDefault.has("thinking"), "MiniMax variant selection is opt-in");
        JSONObject miniAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"max_tokens\":524288}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(!miniAuto.has("thinking"), "MiniMax M3 sends no implicit thinking mode");
        JSONObject previewExplicit = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":524288,\"reasoning_effort\":\"xhigh\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check("adaptive".equals(previewExplicit.getJSONObject("thinking").getString("type")), "Preview explicit variant is adaptive");
        check("xhigh".equals(previewExplicit.getJSONObject("output_config").getString("effort")), "Preview explicit effort is forwarded unchanged");
        JSONObject previewAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":524288,\"output_config\":{\"format\":{\"type\":\"json_schema\"}}}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(!previewAuto.has("thinking"), "Preview effort variant is opt-in");
        check(previewAuto.getJSONObject("output_config").has("format"), "explicit output format survives default reasoning policy");
        JSONObject unknownMiniAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-unknown\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(unknownMiniAuto.getInt("max_tokens") == 16384, "unknown Anthropic model uses upstream translation fallback, not autoThinking 32000");
        JSONObject m27 = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M2.7\",\"max_tokens\":128000}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(!m27.has("thinking"), "M2.7 must not receive invented reasoning variant");
        rejected(() -> {
            try { ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M2.7\",\"reasoning_effort\":\"high\"}"), true,
                    AnthropicReasoningPolicy.Provider.MINIMAX); }
            catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }, "M2.7 fallback declares no effort variants");
        // M3.1-Flash has no built-in entry in the plugin's own model list (minimax index.mjs:161-166),
        // so without live metadata its levels are unknown: an effort maps to adaptive thinking
        // instead of turning a working endpoint into a local 400.
        for (String level : new String[]{"low", "medium", "high", "xhigh", "max"}) {
            JSONObject m31 = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash\",\"max_tokens\":30000,\"reasoning_effort\":\"" + level + "\"}"), true,
                    AnthropicReasoningPolicy.Provider.MINIMAX);
            check("adaptive".equals(m31.getJSONObject("thinking").getString("type")), "M3.1-Flash adaptive " + level);
            check(!m31.has("output_config"), "M3.1-Flash invents no effort in output_config before its catalog arrives " + level);
            check(m31.getInt("max_tokens") == 30000, "M3.1-Flash keeps the client cap " + level);
        }
        JSONObject m31None = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash\",\"reasoning_effort\":\"none\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check("disabled".equals(m31None.getJSONObject("thinking").getString("type")),
                "an unknown thinking mode is not a reason to refuse reasoning_effort=none");
        check(m31None.getInt("max_tokens") == 524288, "M3.1-Flash keeps its verified output capability");
        JSONObject m31Catalog = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash\",\"max_tokens\":30000,\"reasoning_effort\":\"xhigh\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX,
                AnthropicReasoningPolicy.ModelCapabilities.miniMaxFromCatalog("MiniMax-M3.1-Flash",
                        new JSONObject().put("thinkingMode", "forced_on").put("efforts", new com.gaeul.allin.json.JSONArray().put("low").put("xhigh"))));
        check("xhigh".equals(m31Catalog.getJSONObject("output_config").getString("effort")),
                "live M3.1-Flash metadata still drives output_config.effort");
        JSONObject dynamicMini = new JSONObject().put("model", "MiniMax-dynamic").put("max_tokens", 100000).put("reasoning_effort", "xhigh");
        JSONObject dynamicMiniOut = new JSONObject().put("max_tokens", 100000);
        AnthropicReasoningPolicy.apply(dynamicMini, dynamicMiniOut, AnthropicReasoningPolicy.Provider.MINIMAX, efforts("xhigh"));
        check("xhigh".equals(dynamicMiniOut.getJSONObject("output_config").getString("effort")), "dynamic MiniMax effort list honored");
        AnthropicReasoningPolicy.ModelCapabilities liveSwitchable = AnthropicReasoningPolicy.ModelCapabilities.miniMax(efforts(), true, false);
        JSONObject dynamicAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-live\",\"max_tokens\":30000}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX, liveSwitchable);
        check(!dynamicAuto.has("thinking"), "live switchable mode still requires an explicit reasoning variant");

        // Explicit thinking remains byte-for-byte equivalent as JSON; an incompatible final cap is rejected.
        JSONObject explicit = json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":20000,\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":16000},\"output_config\":{\"format\":{\"type\":\"json_schema\"}}}");
        JSONObject explicitOut = ZCodeAnthropic.toAnthropic(explicit, true, AnthropicReasoningPolicy.Provider.MINIMAX);
        check(explicitOut.getJSONObject("thinking").getInt("budget_tokens") == 16000, "explicit thinking budget preserved");
        check(explicitOut.getJSONObject("output_config").has("format"), "explicit output_config preserved");
        explicitOut.put("max_tokens", 8000); // Represents a later Guard cap.
        rejected(() -> AnthropicReasoningPolicy.validateOutputCompatibility(explicitOut), "Guard cap must reject incompatible explicit budget");
        check(explicitOut.getInt("max_tokens") == 8000 && explicitOut.getJSONObject("thinking").getInt("budget_tokens") == 16000,
                "validation rejects without raising max or rewriting explicit budget");
        rejected(() -> AnthropicReasoningPolicy.validateOutputCompatibility(new JSONObject().put("max_tokens", 16000)
                .put("thinking", new JSONObject().put("type", "enabled").put("budget_tokens", 16000))), "budget equal to max must reject");
        AnthropicReasoningPolicy.validateOutputCompatibility(new JSONObject().put("max_tokens", 16000)
                .put("output_config", new JSONObject().put("effort", "high")));
        rejected(() -> {
            try { ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":50000,\"reasoning_effort\":\"high\",\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4096}}"), true,
                    AnthropicReasoningPolicy.Provider.MINIMAX); }
            catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }, "conflicting reasoning_effort and explicit thinking must fail before dispatch");
        rejected(() -> AnthropicReasoningPolicy.validateExplicitOutputCompatibility(
                new JSONObject().put("max_tokens",8000).put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",16000)),
                new JSONObject().put("max_tokens",20000).put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",16000)),8000),
                "Guard output cap conflicting with explicit thinking budget must reject");
        JSONObject compatibleGuard=new JSONObject().put("max_tokens",8000)
                .put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",4096));
        AnthropicReasoningPolicy.validateOutputCompatibility(compatibleGuard);
        JSONObject explicitCompatible = json("{\"model\":\"GLM-5.3\",\"max_tokens\":20000,\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":16000}}");
        check(ZCodeAnthropic.toAnthropic(explicitCompatible, true).getJSONObject("thinking").getInt("budget_tokens") == 16000,
                "compatible explicit thinking is preserved");

        JSONObject dual = ZCodeAnthropic.toAnthropic(json("{\"max_tokens\":5000,\"max_completion_tokens\":9000,\"model\":\"MiniMax-M3\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(dual.getInt("max_tokens") == 5000, "two explicit output caps use their minimum");
        check(!dual.has("thinking"), "explicit output limits do not implicitly enable MiniMax reasoning");
        JSONObject catalogDefault = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX, null, 77777, 0);
        check(catalogDefault.getInt("max_tokens") == 524288, "verified default wins over conservative catalog");
        JSONObject guardedDefault = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX, null, 77777, 8000);
        check(guardedDefault.getInt("max_tokens") == 8000, "Guard cap bounds verified default");
        JSONObject clientWithinVerified = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"max_tokens\":400000}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(clientWithinVerified.getInt("max_tokens") == 400000, "valid explicit client max remains unchanged");
        JSONObject clientAboveVerified = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\",\"max_tokens\":600000}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(clientAboveVerified.getInt("max_tokens") == 600000,
                "client limit is preserved without silently capping it to verified endpoint limit");
        JSONObject guardedAuto = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3\"}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX, null, 77777, 8000);
        check(guardedAuto.getInt("max_tokens") == 8000, "Guard cap bounds verified model default");
        check(!guardedAuto.has("thinking"), "Guard caps do not implicitly enable MiniMax reasoning");

        JSONObject explicitOutputEffort = ZCodeAnthropic.toAnthropic(json("{\"model\":\"MiniMax-M3.1-Flash-Preview\",\"max_tokens\":50000,\"output_config\":{\"effort\":\"xhigh\"}}"), true,
                AnthropicReasoningPolicy.Provider.MINIMAX);
        check(!explicitOutputEffort.has("thinking"), "native output effort alone does not enable thinking");
        check("xhigh".equals(explicitOutputEffort.getJSONObject("output_config").getString("effort")), "explicit effort preserved");

        JSONObject projected = AnthropicBridge.requestToOpenAI(json("{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"xhigh\"}}"));
        check("xhigh".equals(projected.getString("reasoning_effort")), "adaptive effort survives bridge projection");
        JSONObject disabled = AnthropicBridge.requestToOpenAI(json("{\"thinking\":{\"type\":\"disabled\"}}"));
        check("none".equals(disabled.getString("reasoning_effort")), "disabled thinking survives bridge projection");

        ZCodeAnthropic.StreamState trunc = new ZCodeAnthropic.StreamState("m", true);
        trunc.onEvent("content_block_delta", json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"analysis\"}}"));
        List<String> finalChunks = trunc.onEvent("message_delta", json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"));
        boolean notice = false, normalFinish = false;
        for (String chunk : finalChunks) {
            JSONObject obj = json(chunk);
            JSONObject choice = obj.optJSONArray("choices").optJSONObject(0);
            if (choice == null) continue;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null && ThinkingTruncationStream.NOTICE.equals(delta.optString("content"))) notice = true;
            if ("stop".equals(choice.optString("finish_reason"))) normalFinish = true;
        }
        check(notice && normalFinish, "thinking-only truncation notice and compatibility finish remain intact");
        System.out.println("Anthropic reasoning policy offline tests passed");
    }
}
