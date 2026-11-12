package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Standalone offline regression tests; run via test/offline/run-context-guard.sh. */
public final class ContextGuardOfflineTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        untouchedPayloadKeepsUnpairedToolMessages();
        completionTokenCapPreservesField();
        singleMaxTokensAndMissingFields();
        bothOutputFieldsRespectCap();
        smallerCompletionLimitRetained();
        toolPairCleanupRequiresAdjacency();
        disabledGuardLeavesPayloadUnchanged();
        trimmingKeepsSurvivingToolPairsAndCountsRemovals();
        System.out.println("ContextGuard offline checks passed: " + checks);
    }

    private static void untouchedPayloadKeepsUnpairedToolMessages() throws Exception {
        JSONObject input = new JSONObject("{\"messages\":["
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"id\":\"orphan-call\",\"type\":\"function\"}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"orphan-reply\",\"content\":\"x\"}]} ");
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.maxBodyBytes = 0;
        cfg.keepRecent = 500;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        equal(input.getJSONArray("messages").toString(), result.payload.getJSONArray("messages").toString(),
                "no-budget messages retained");
        equal(0, result.dropped, "no-budget dropped count");
        check(!result.changed, "no-budget unchanged flag");
    }

    private static void completionTokenCapPreservesField() throws Exception {
        JSONObject input = new JSONObject("{\"max_completion_tokens\":900,\"messages\":[]}");
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.maxOutputTokens = 256;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        check(!result.payload.has("max_tokens"), "completion-only must not add max_tokens");
        equal(256, result.payload.getInt("max_completion_tokens"), "completion cap");
        equal(256, result.appliedMaxOutputTokens.intValue(), "reported cap");
        check(!input.has("max_tokens"), "input payload not mutated");
    }

    private static void singleMaxTokensAndMissingFields() throws Exception {
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.maxOutputTokens = 256;
        JSONObject onlyMax = ContextGuard.fit(new JSONObject("{\"max_tokens\":900,\"messages\":[]}"), cfg).payload;
        equal(256, onlyMax.getInt("max_tokens"), "single max_tokens capped");
        check(!onlyMax.has("max_completion_tokens"), "single max_tokens does not add completion field");
        JSONObject none = ContextGuard.fit(new JSONObject("{\"messages\":[]}"), cfg).payload;
        equal(256, none.getInt("max_tokens"), "missing fields receive max_tokens cap");
        check(!none.has("max_completion_tokens"), "missing fields do not receive completion field");
    }

    private static void smallerCompletionLimitRetained() throws Exception {
        JSONObject input = new JSONObject("{\"max_completion_tokens\":32,\"messages\":[]}");
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.maxOutputTokens = 256;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        check(!result.payload.has("max_tokens"), "smaller completion-only must not add max_tokens");
        equal(32, result.payload.getInt("max_completion_tokens"), "smaller completion limit retained");
    }

    private static void toolPairCleanupRequiresAdjacency() throws Exception {
        JSONArray messages = new JSONArray("["
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\"}]},"
                + "{\"role\":\"user\",\"content\":\"intervening\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"orphan\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"b\"},{\"id\":\"c\"}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"ok\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"duplicate\"}]");
        String before = messages.toString();
        JSONArray safe = ContextGuard.sanitizeToolPairs(messages);
        equal(3, safe.length(), "orphan, empty shell and duplicate reply removed");
        equal("user", safe.getJSONObject(0).getString("role"), "intervening user preserved");
        equal(1, safe.getJSONObject(1).getJSONArray("tool_calls").length(), "unanswered parallel call removed");
        equal("c", safe.getJSONObject(2).getString("tool_call_id"), "adjacent answered call preserved");
        equal(before, messages.toString(), "pair sanitizer does not mutate input");
    }

    private static void bothOutputFieldsRespectCap() throws Exception {
        JSONObject input = new JSONObject("{\"max_tokens\":1000,\"max_completion_tokens\":500,\"messages\":[]}");
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.maxOutputTokens = 300;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        equal(300, result.payload.getInt("max_tokens"), "max_tokens capped");
        equal(300, result.payload.getInt("max_completion_tokens"), "max_completion_tokens capped");
        equal(1000, input.getInt("max_tokens"), "input max_tokens unchanged");
    }

    private static void disabledGuardLeavesPayloadUnchanged() throws Exception {
        JSONObject input = new JSONObject("{\"max_completion_tokens\":900,\"messages\":["
                + "{\"role\":\"tool\",\"tool_call_id\":\"orphan\",\"content\":\"x\"}]}");
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.enabled = false;
        cfg.maxOutputTokens = 32;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        equal(input.toString(), result.payload.toString(), "disabled guard preserves payload");
        check(result.appliedMaxOutputTokens == null, "disabled guard reports no cap");
    }

    private static void trimmingKeepsSurvivingToolPairsAndCountsRemovals() throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", repeat("old history ", 500)));
        messages.put(new JSONObject("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"id\":\"call-1\",\"type\":\"function\"}]}"));
        messages.put(new JSONObject("{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"ok\"}"));
        messages.put(new JSONObject("{\"role\":\"user\",\"content\":\"recent\"}"));
        JSONObject input = new JSONObject().put("messages", messages);
        ContextGuard.Config cfg = new ContextGuard.Config();
        cfg.keepRecent = 3;
        cfg.maxBodyBytes = 1000;
        cfg.maxContextTokens = 0;
        ContextGuard.Result result = ContextGuard.fit(input, cfg);
        JSONArray kept = result.payload.getJSONArray("messages");
        int calls = 0, replies = 0;
        for (int i = 0; i < kept.length(); i++) {
            JSONObject m = kept.getJSONObject(i);
            if (m.optJSONArray("tool_calls") != null) calls++;
            if ("tool".equals(m.optString("role"))) replies++;
        }
        equal(calls, replies, "surviving tool call/reply pairing");
        equal(1, result.dropped, "dropped count matches removed messages");
        check(calls == 1 && replies == 1, "completed tool pair survives trim");
        check(result.changed, "budget trim changed flag");
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }

    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
