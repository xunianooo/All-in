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
        nullSamplingIsUnset();
        contentBlocksAreNeverSilentlyDropped();
        illegalRequestTypesRejected();
        nativeReasoningOnlyTurnProjects();
        nativePayloadBlocksAreForwardedNotConverted();
        qoderRepairIsAnIsolatedOptIn();
        System.out.println("AnthropicBridgeRequestTest: all checks passed");
    }

    // Repro: "temperature": null was copied through as NaN/0, and string/bool
    // values were coerced. Absent and null must both stay absent; any other type
    // must fail instead of being guessed.
    private static void nullSamplingIsUnset() throws Exception {
        JSONObject unset = new JSONObject()
                .put("temperature", JSONObject.NULL)
                .put("top_p", JSONObject.NULL)
                .put("stream", JSONObject.NULL)
                .put("stop_sequences", JSONObject.NULL)
                .put("messages", new org.json.JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")));
        JSONObject converted = AnthropicBridge.requestToOpenAI(unset);
        check(!converted.has("temperature") && !converted.has("top_p"), "null sampling must stay unset");
        check(!converted.has("stream") && !converted.has("stop"), "null stream/stop must stay unset");
        converted.toString(); // must stay serialisable: NaN breaks the whole upstream request
        JSONObject kept = AnthropicBridge.requestToOpenAI(new JSONObject().put("temperature", 0.3).put("top_p", 0.9));
        check(Math.abs(kept.getDouble("temperature") - 0.3) < 1e-9, "temperature preserved");
        check(Math.abs(kept.getDouble("top_p") - 0.9) < 1e-9, "top_p preserved");
        for (Object bad : new Object[] {"0.5", Boolean.TRUE, new org.json.JSONArray().put(1)}) {
            rejects(new JSONObject().put("temperature", bad), "temperature must be a number", "temperature");
            rejects(new JSONObject().put("top_p", bad), "top_p must be a number", "top_p");
        }
    }

    // Repro: an array of blocks dropped every unsupported type (document,
    // search_result, unknown, ...) and could drop the whole turn while reporting
    // success. Known native-only blocks stay explicitly skipped; everything else
    // must fail loudly.
    private static void contentBlocksAreNeverSilentlyDropped() throws Exception {
        String[] dropped = {
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"eA==\"}}]}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"search_result\",\"content\":[]}]}]}",
            "{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"only\"}]}]}",
            "{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"mystery\",\"value\":1}]}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":[{\"type\":\"document\",\"source\":{}}]}]}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":7}]}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[]}]}"
        };
        for (String json : dropped) {
            try {
                AnthropicBridge.requestToOpenAI(new JSONObject(json));
                throw new AssertionError("silently accepted content " + json);
            } catch (IllegalArgumentException expected) {
                check(locatesContent(expected.getMessage()), "error must locate content: " + expected.getMessage());
            }
        }
        JSONObject thinking = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"plan\",\"signature\":\"sig\"},"
                + "{\"type\":\"redacted_thinking\",\"data\":\"opaque\"},"
                + "{\"type\":\"text\",\"text\":\"answer\"}]}]}");
        org.json.JSONArray messages = AnthropicBridge.requestToOpenAI(thinking).getJSONArray("messages");
        check("answer".equals(messages.getJSONObject(0).getString("content")), "native-only thinking must not hide text");
        String[] badSystem = {"{\"system\":{\"type\":\"text\",\"text\":\"x\"}}", "{\"system\":[{\"type\":\"image\",\"source\":{}}]}",
                "{\"system\":[42]}", "{\"system\":7}"};
        for (String json : badSystem) {
            try {
                AnthropicBridge.requestToOpenAI(new JSONObject(json));
                throw new AssertionError("silently accepted system " + json);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("system"), "error must locate system: " + expected.getMessage());
            }
        }
    }

    private static boolean locatesContent(String message) {
        return message != null && message.contains("content");
    }

    // Repro: a native Messages client (Claude Code) replays signed thinking blocks in
    // history, and an interrupted turn can hold nothing but that reasoning block. Chat
    // has no field for it, but the native relay forwards the untouched original request,
    // so the projection must keep the turn instead of failing the whole request with 400.
    // Strict Chat conversion stays strict: only the native projection tolerates it.
    private static void nativeReasoningOnlyTurnProjects() throws Exception {
        String raw = "{\"model\":\"fixture\",\"max_tokens\":32,\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hello\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"reason\",\"signature\":\"sig\"}]}]}";
        JSONArray projected = AnthropicBridge.projectNativeRequest(new JSONObject(raw)).getJSONArray("messages");
        check(projected.length() == 2, "reasoning-only assistant turn must stay in the native projection: " + projected);
        check("".equals(projected.getJSONObject(1).getString("content")),
                "reasoning-only turn must project as empty content: " + projected);
        String redacted = "{\"model\":\"fixture\",\"messages\":[{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}]}]}";
        JSONArray redactedProjection = AnthropicBridge.projectNativeRequest(new JSONObject(redacted)).getJSONArray("messages");
        check(redactedProjection.length() == 1 && "".equals(redactedProjection.getJSONObject(0).getString("content")),
                "redacted reasoning-only turn must project: " + redactedProjection);
        try {
            AnthropicBridge.requestToOpenAI(new JSONObject(raw));
            throw new AssertionError("Chat conversion must still reject a reasoning-only turn");
        } catch (IllegalArgumentException expected) {
            check(locatesContent(expected.getMessage()), "rejection must locate content: " + expected.getMessage());
        }
    }

    // Repro: a native Messages client attaches a hosted document or replays a server
    // search result. Chat has no field for either, so the strict conversion rejects the
    // request (real content must not be dropped silently). The native projection may omit
    // them, because the body that is actually relayed is the untouched original - which is
    // only ever used while NativeAnthropicPolicy still certifies the native path. These
    // cases cover both halves plus the negative controls that keep the omission honest.
    private static void nativePayloadBlocksAreForwardedNotConverted() throws Exception {
        String[] payloads = {
            "{\"model\":\"fixture\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"summarize\"},"
                + "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"eA==\"}}]}]}",
            "{\"model\":\"fixture\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"eA==\"}}]}]}",
            "{\"model\":\"fixture\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"check\"},"
                + "{\"type\":\"search_result\",\"source\":\"srv\",\"title\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"hit\"}]}]}]}",
            "{\"model\":\"fixture\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"c1\","
                + "\"name\":\"read\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c1\","
                + "\"content\":[{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"eA==\"}}]}]}]}"
        };
        for (String json : payloads) {
            JSONObject original = new JSONObject(json);
            JSONObject untouched = new JSONObject(json);
            // The omission is only safe while the request still demands the native relay.
            check(NativeAnthropicPolicy.requiresNative(original), "native payload must require the native path: " + json);
            JSONObject projected = AnthropicBridge.projectNativeRequest(original);
            check(jsonEquivalent(original, untouched), "projection must not consume the real payload: " + original);
            JSONObject current = new JSONObject(projected.toString());
            // Positive control: a faithful transcript passes the safety check, so the
            // negative controls below fail for the right reason instead of always throwing.
            NativeAnthropicPolicy.checkNativeRequired(original, projected, current, true);
            try {
                NativeAnthropicPolicy.checkNativeRequired(original, projected, current, false);
                throw new AssertionError("omitted native content must never be accepted without the native path: " + json);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage() != null && expected.getMessage().contains("protected"),
                        "native refusal must name protected content: " + expected.getMessage());
            }
            JSONObject rewritten = new JSONObject(projected.toString());
            rewritten.getJSONArray("messages").put(new JSONObject().put("role", "system").put("content", "injected"));
            try {
                NativeAnthropicPolicy.checkNativeRequired(original, projected, rewritten, true);
                throw new AssertionError("a rewritten transcript must not pass the native check: " + json);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage() != null && expected.getMessage().contains("protected"),
                        "rewritten refusal must name protected content: " + expected.getMessage());
            }
            try {
                AnthropicBridge.requestToOpenAI(new JSONObject(json));
                throw new AssertionError("strict conversion must reject unrepresentable native content: " + json);
            } catch (IllegalArgumentException expected) {
                check(locatesContent(expected.getMessage()), "strict rejection must locate content: " + expected.getMessage());
            }
        }
        // The projection keeps the visible text and never invents a replacement for the
        // omitted block; a turn made only of native payload stays but carries no fake text.
        JSONArray mixed = AnthropicBridge.projectNativeRequest(new JSONObject(payloads[0])).getJSONArray("messages");
        check(mixed.length() == 1 && "summarize".equals(mixed.getJSONObject(0).getString("content")),
                "visible text survives the projection: " + mixed);
        JSONArray documentOnly = AnthropicBridge.projectNativeRequest(new JSONObject(payloads[1])).getJSONArray("messages");
        check(documentOnly.length() == 1 && "".equals(documentOnly.getJSONObject(0).getString("content")),
                "native-only turn projects as empty text instead of a fabricated summary: " + documentOnly);
        JSONArray toolResult = AnthropicBridge.projectNativeRequest(new JSONObject(payloads[3])).getJSONArray("messages");
        check("tool".equals(toolResult.getJSONObject(1).getString("role")) && "".equals(toolResult.getJSONObject(1).getString("content")),
                "tool result carrying a document keeps its slot without fake text: " + toolResult);
        // Unknown block types are still unknown: the projection refuses to guess there and
        // rejects them, so only listed native payload types may be omitted.
        String[] unknown = {
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"mystery\",\"value\":1}]}]}",
            "{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"mystery\",\"value\":1}]}]}",
            "{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"read\",\"input\":{}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c1\",\"content\":[{\"type\":\"mystery\"}]}]}]}"
        };
        for (String json : unknown) {
            try {
                AnthropicBridge.projectNativeRequest(new JSONObject(json));
                throw new AssertionError("projection must not guess an unknown block type: " + json);
            } catch (IllegalArgumentException expected) {
                check(locatesContent(expected.getMessage()), "unknown block rejection must locate content: " + expected.getMessage());
            }
        }
    }

    private static boolean jsonEquivalent(JSONObject left, JSONObject right) {
        return new JSONArray().put(left).toString().equals(new JSONArray().put(right).toString());
    }

    // Repro: tools/tool_choice/thinking/max_tokens/stream/stop_sequences with an
    // illegal type were ignored or coerced instead of failing.
    private static void illegalRequestTypesRejected() throws Exception {
        Object[][] cases = {
            {"max_tokens", "4096"}, {"stream", "true"}, {"tools", "{\"name\":\"x\"}"},
            {"tool_choice", "auto"}, {"thinking", "enabled"}, {"stop_sequences", "stop"}
        };
        for (Object[] entry : cases) {
            String field = (String) entry[0];
            rejects(new JSONObject().put(field, entry[1]), field + " illegal type", field);
        }
        rejects(new JSONObject("{\"tools\":[42]}"), "tools entry must be an object", "tools");
        rejects(new JSONObject("{\"tools\":[{\"name\":\"\"}]}"), "tools name required", "tools");
        rejects(new JSONObject("{\"tools\":[{\"name\":\"x\",\"input_schema\":[]}]}"), "input_schema must be an object", "tools");
        rejects(new JSONObject("{\"tool_choice\":{\"type\":\"mystery\"}}"), "unknown tool_choice type", "tool_choice");
        rejects(new JSONObject("{\"thinking\":{\"type\":\"maybe\"}}"), "unknown thinking type", "thinking");
        rejects(new JSONObject("{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":\"big\"}}"), "budget_tokens type", "budget_tokens");
        rejects(new JSONObject("{\"stop_sequences\":[\"a\",2]}"), "stop sequence entry type", "stop_sequences");
    }

    private static void rejects(JSONObject request, String label, String field) {
        try {
            AnthropicBridge.requestToOpenAI(request);
            throw new AssertionError("accepted " + label);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains(field), label + ": error must name " + field + ", was " + expected.getMessage());
        } catch (AssertionError failed) {
            throw failed;
        } catch (Exception other) {
            throw new AssertionError(label + ": wrong failure " + other);
        }
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

    // The opt-in Qoder history repair (repairQoderHistory) must not touch any of the
    // strict behaviour above, and must not be reachable by accident: it is a separate
    // entry point, its only effect is the call ids the plugin itself would compute, and
    // normalizeOpenAiMessages keeps refusing exactly what it refused before. The full
    // Qoder suite (real adapter + plugin differential) lives in QoderHistoryRepairTest.
    private static void qoderRepairIsAnIsolatedOptIn() throws Exception {
        JSONArray lost = new JSONArray("[{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\","
                + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":1}\"}}]},"
                + "{\"role\":\"tool\",\"content\":\"result\"}]");
        String before = lost.toString();
        try {
            AnthropicBridge.normalizeOpenAiMessages(lost);
            throw new AssertionError("the strict normalizer must still refuse a call without an id");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("id"), "strict refusal must still name the id");
        }
        check(before.equals(lost.toString()), "strict refusal must not rewrite the caller's messages");
        JSONArray repaired = AnthropicBridge.repairQoderHistory(lost);
        check("call_6b41bb068d910234a4cc06aebc323ba5".equals(
                repaired.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id")),
                "repair must use the plugin's callID");
        check(repaired.getJSONObject(2).getString("content").equals("result")
                        && !repaired.toString().contains("interrupted"),
                "repair must never invent a tool result");
        check(repaired.length() == 3 && before.equals(lost.toString()), "repair must not mutate the caller's messages");
        check(AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages", lost)),
                "a history the Qoder adapter can take must be repairable");
        check(!AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages",
                new JSONArray("[{\"role\":\"user\",\"tool_calls\":{}}]"))),
                "a broken tool_calls must never be repairable");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
