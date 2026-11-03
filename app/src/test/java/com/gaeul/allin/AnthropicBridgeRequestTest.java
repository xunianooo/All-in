package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated request conversion regression tests. Uses only synthetic JSON fixtures. */
public final class AnthropicBridgeRequestTest {
    public static void main(String[] args) throws Exception {
        mixedToolResultsPrecedeUserText();
        toolChoiceUsesOpenAIShapes();
        pairingAndMultimodal();
        splitAssistantCalls();
        userTextClosesPendingExchange();
        malformedMessagesRejected();
        sharedNormalizerAndOrphanBoundary();
        System.out.println("AnthropicBridgeRequestTest: all checks passed");
    }

    private static void mixedToolResultsPrecedeUserText() throws Exception {
        JSONObject request = new JSONObject()
                .put("model", "fixture")
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "assistant").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "tool_use").put("id", "call_a")
                                        .put("name", "a").put("input", new JSONObject()))
                                .put(new JSONObject().put("type", "tool_use").put("id", "call_b")
                                        .put("name", "b").put("input", new JSONObject()))))
                        .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "continue"))
                                .put(new JSONObject().put("type", "tool_result").put("tool_use_id", "call_b")
                                        .put("content", "result b"))
                                .put(new JSONObject().put("type", "tool_result").put("tool_use_id", "call_a")
                                        .put("content", "result a")))));

        JSONArray messages = AnthropicBridge.requestToOpenAI(request).getJSONArray("messages");
        check("assistant".equals(messages.getJSONObject(0).getString("role")), "assistant call position");
        check("tool".equals(messages.getJSONObject(1).getString("role")), "first result role");
        check("call_a".equals(messages.getJSONObject(1).getString("tool_call_id")), "call order result first");
        check("tool".equals(messages.getJSONObject(2).getString("role")), "second result role");
        check("call_b".equals(messages.getJSONObject(2).getString("tool_call_id")), "call order result second");
        check("user".equals(messages.getJSONObject(3).getString("role")), "user text follows results");
        check("continue".equals(messages.getJSONObject(3).getString("content")), "user text retained");
    }

    private static void toolChoiceUsesOpenAIShapes() throws Exception {
        assertToolChoice("auto", "auto", null);
        assertToolChoice("any", "required", null);
        assertToolChoice("none", "none", null);
        assertToolChoice("tool", null, "lookup");
    }

    private static void assertToolChoice(String anthropicType, String expected, String namedTool) throws Exception {
        JSONObject choice = new JSONObject().put("type", anthropicType);
        if (namedTool != null) choice.put("name", namedTool);
        JSONObject request = new JSONObject().put("tool_choice", choice);
        JSONObject converted = AnthropicBridge.requestToOpenAI(request);
        Object actual = converted.get("tool_choice");
        if (expected != null) {
            check(actual instanceof String, anthropicType + " choice must be a string");
            check(expected.equals(actual), anthropicType + " choice value");
        } else {
            check(actual instanceof JSONObject, "named tool choice must be an object");
            JSONObject named = (JSONObject) actual;
            check("function".equals(named.getString("type")), "named tool choice type");
            check("lookup".equals(named.getJSONObject("function").getString("name")), "named tool choice name");
        }
    }

    private static void pairingAndMultimodal() throws Exception {
        JSONObject req = new JSONObject("{\"tool_choice\":{\"type\":\"auto\",\"disable_parallel_tool_use\":true},\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"one\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"two\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"three\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"content\":\"bee\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"is_error\":true,\"content\":[{\"type\":\"text\",\"text\":\"oops\"},{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://example.invalid/img\"}}]},{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"DUPLICATE\"},{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://example.invalid/own\"}},{\"type\":\"text\",\"text\":\"last\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"orphan\",\"content\":\"stray\"}]}]}");
        JSONObject out = AnthropicBridge.requestToOpenAI(req);
        check(!out.getBoolean("parallel_tool_calls"), "disable parallel");
        JSONArray m = out.getJSONArray("messages");
        check("a".equals(m.getJSONObject(1).getString("tool_call_id")), "cross-user first result");
        check(m.getJSONObject(1).getString("content").contains("oops") && m.getJSONObject(1).getString("content").toLowerCase().contains("error"), "error preserved");
        check("b".equals(m.getJSONObject(2).getString("tool_call_id")), "cross-user second result");
        check("c".equals(m.getJSONObject(3).getString("tool_call_id")) && m.getJSONObject(3).getString("content").contains("interrupted"), "missing result");
        String all = m.toString();
        check(!all.contains("DUPLICATE") && all.contains("stray"), "duplicate and orphan");
        check(all.contains("https://example.invalid/img") && all.contains("https://example.invalid/own"), "images preserved");
        check(all.indexOf("first") < all.indexOf("https://example.invalid/own") && all.indexOf("https://example.invalid/own") < all.indexOf("last"), "text image order");
    }

    // Cases from upstream split_calls_test.go: the real answers precede synthetic ones.
    private static void splitAssistantCalls() throws Exception {
        String base = "{\"messages\":[{\"role\":\"user\",\"content\":\"read a and b\"}," +
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"read\",\"input\":{}}]}," +
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"read\",\"input\":{}}]}," +
            "{\"role\":\"assistant\",\"content\":\"reading\"}," +
            "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"content\":\"B!\"}]}]}";
        JSONArray m = AnthropicBridge.requestToOpenAI(new JSONObject(base)).getJSONArray("messages");
        check(m.length() == 4, "split assistant messages join");
        check("reading".equals(m.getJSONObject(1).getString("content")), "assistant text joined");
        check(m.getJSONObject(1).getJSONArray("tool_calls").length() == 2, "both calls joined");
        check("b".equals(m.getJSONObject(2).getString("tool_call_id")) && "B!".equals(m.getJSONObject(2).getString("content")), "real result before synthetic");
        check("a".equals(m.getJSONObject(3).getString("tool_call_id")) && m.getJSONObject(3).getString("content").contains("interrupted"), "missing result last");
        JSONArray text = AnthropicBridge.requestToOpenAI(new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":\"one\"},{\"role\":\"assistant\",\"content\":\"two\"}]}" )).getJSONArray("messages");
        check(text.length() == 2, "text-only assistant messages stay separate");
    }

    private static void userTextClosesPendingExchange() throws Exception {
        JSONObject req = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"x\",\"input\":{}}]},{\"role\":\"user\",\"content\":\"continue\"},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"late\"}]}]}");
        JSONArray m = AnthropicBridge.requestToOpenAI(req).getJSONArray("messages");
        check(m.getJSONObject(1).getString("content").contains("interrupted"), "user text closes pending exchange");
        check("user".equals(m.getJSONObject(2).getString("role")) && "continue".equals(m.getJSONObject(2).getString("content")), "user text after synthetic answer");
        check("user".equals(m.getJSONObject(3).getString("role")) && "late".equals(m.getJSONObject(3).getString("content")), "late answer becomes user text");
    }

    private static void malformedMessagesRejected() throws Exception {
        String[] invalid = {"{\"messages\":{}}", "{\"messages\":[null]}", "{\"messages\":[42]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":7}]}",
                "{\"messages\":[{\"role\":\"assistant\",\"content\":[null]}]}"};
        for (String json : invalid) {
            try { AnthropicBridge.requestToOpenAI(new JSONObject(json)); throw new AssertionError("accepted invalid messages " + json); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("messages"), "error locates messages"); }
        }
    }

    // Upstream pairToolMessages flushes at an orphan; a later matching result
    // is also orphaned, not retroactively paired to a flushed assistant call.
    private static void sharedNormalizerAndOrphanBoundary() throws Exception {
        JSONArray input = new JSONArray("[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"orphan\",\"content\":\"stray\"},{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"late\"}]");
        String before = input.toString();
        JSONArray out = AnthropicBridge.normalizeOpenAiMessages(input);
        check(before.equals(input.toString()), "normalizer must not mutate input");
        check(out.length() == 4, "orphan boundary length");
        check("a".equals(out.getJSONObject(1).getString("tool_call_id")) && out.getJSONObject(1).getString("content").contains("interrupted"), "pending flushed at orphan");
        check("user".equals(out.getJSONObject(2).getString("role")) && out.getJSONObject(2).getString("content").contains("stray"), "orphan kept");
        check("user".equals(out.getJSONObject(3).getString("role")) && out.getJSONObject(3).getString("content").contains("late"), "later answer remains orphan");
        JSONArray dup = new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"a\"},{\"id\":\"b\"}]},{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"B\"},{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"duplicate\"},{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"A\"}]");
        JSONArray normalized = AnthropicBridge.normalizeOpenAiMessages(dup);
        check(normalized.length() == 3 && "a".equals(normalized.getJSONObject(1).getString("tool_call_id")) && "b".equals(normalized.getJSONObject(2).getString("tool_call_id")), "direct Chat duplicate and call order");
        check(!normalized.toString().contains("duplicate"), "duplicate answer dropped");
        JSONArray multimodal = new JSONArray("[{\"role\":\"tool\",\"tool_call_id\":\"orphan\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.invalid/a\"}}]}]");
        Object kept = AnthropicBridge.normalizeOpenAiMessages(multimodal).getJSONObject(0).get("content");
        check(kept instanceof JSONArray && kept.toString().contains("https://example.invalid/a"), "orphan multimodal retained as parts");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
