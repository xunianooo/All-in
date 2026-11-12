package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline regression coverage for AnthropicBridge.StreamState. */
public final class AnthropicBridgeStreamOfflineTest {
    private static JSONObject chunk(String json) throws Exception { return new JSONObject(json); }
    private static void add(List<String> out, List<String> next) { out.addAll(next); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static void validateLifecycle(List<String> events) throws Exception {
        int open = -1, next = 0, starts = 0, stops = 0;
        boolean messageStarted = false, messageStopped = false;
        for (String raw : events) {
            JSONObject ev = new JSONObject(raw.substring(raw.indexOf("data: ") + 6).trim());
            String type = ev.getString("type");
            int index = ev.optInt("index", -1);
            require(!messageStopped, "event after message_stop: " + type);
            if ("message_start".equals(type)) {
                require(!messageStarted, "duplicate message_start");
                messageStarted = true;
            } else {
                require(messageStarted, "event before message_start");
                if ("content_block_start".equals(type)) {
                    require(open == -1, "new block " + index + " before closing block " + open);
                    require(index == next++, "nonsequential block index " + index);
                    open = index;
                    starts++;
                } else if ("content_block_delta".equals(type)) {
                    require(index == open, "delta for closed or unopened block " + index + " (open=" + open + ")");
                } else if ("content_block_stop".equals(type)) {
                    require(index == open, "stop for closed or unopened block " + index);
                    open = -1;
                    stops++;
                } else if ("message_delta".equals(type) || "message_stop".equals(type)) {
                    require(open == -1, "message ended with open block " + open);
                    if ("message_stop".equals(type)) messageStopped = true;
                }
            }
        }
        require(messageStopped && starts == stops, "incomplete message lifecycle");
    }

    private static void reasoningAndLateToolArguments() throws Exception {
        AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
        List<String> events = new ArrayList<String>();
        String[] deltas = {
            "{\"reasoning_content\":\"think1\"}", "{\"content\":\"text1\"}",
            "{\"reasoning_content\":\"think2\"}", "{\"content\":\"text2\"}",
            "{\"tool_calls\":[{\"index\":7,\"id\":\"call7\",\"function\":{\"name\":\"seven\",\"arguments\":\"{\"}}]}",
            "{\"content\":\"tail\"}", "{\"reasoning_content\":\"late thought\"}",
            "{\"tool_calls\":[{\"index\":9,\"id\":\"call9\",\"function\":{\"name\":\"nine\",\"arguments\":\"{}\"}}]}",
            "{\"tool_calls\":[{\"index\":7,\"function\":{\"arguments\":\"}\"}}]}",
            "{\"content\":null}"
        };
        for (String delta : deltas) {
            add(events, state.onChunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray()
                    .put(new JSONObject().put("delta", chunk(delta))))));
        }
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"completion_tokens\":42}}")));
        add(events, state.onEnd());
        validateLifecycle(events);
        String joined = events.toString();
        require(!joined.contains("\"text\":\"null\""), "null content emitted as text");
        require(joined.contains("\"output_tokens\":42"), "usage missing");
        require(joined.contains("\"stop_reason\":\"tool_use\""), "tool finish reason missing");
        require(joined.contains("late thought") && joined.contains("tail"), "deferred text/thinking lost");
    }

    private static void emptyStream() throws Exception {
        AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
        String result = state.onEnd().toString();
        require(result.contains("event: error") && !result.contains("message_stop"), "empty stream must fail");
        require(state.onEnd().isEmpty(), "empty stream duplicated end");
    }

    private static void lateIdentityAndErrors() throws Exception {
        AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
        List<String> e = new ArrayList<String>();
        add(e, s.onChunk(chunk("{\"usage\":{\"prompt_tokens\":19},\"choices\":[{\"index\":1,\"delta\":{\"content\":\"wrong\"}},{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}}]}}]}")));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_late\",\"function\":{\"name\":\"lookup\",\"arguments\":\"}\"}}]}}]}")));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"completion_tokens\":4}}")));
        add(e, s.onEnd()); validateLifecycle(e);
        String all = e.toString();
        require(all.contains("call_late") && all.contains("lookup") && !all.contains("wrong"), "late identity or choice selection");
        require(all.contains("\"input_tokens\":19") && all.contains("\"output_tokens\":4"), "usage");
        AnthropicBridge.StreamState bad = new AnthropicBridge.StreamState("fixture");
        String error = bad.onChunk(chunk("{\"error\":{\"message\":\"broken\"}}" )).toString();
        require(error.contains("event: error") && !error.contains("message_stop") && bad.onEnd().isEmpty(), "upstream error must terminate");
    }

    private static void truncatedAndBounded() throws Exception {
        AnthropicBridge.StreamState truncated = new AnthropicBridge.StreamState("fixture");
        truncated.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"));
        require(truncated.onEnd().toString().contains("event: error"), "truncated stream reported success");
        AnthropicBridge.StreamState empty = new AnthropicBridge.StreamState("fixture");
        empty.onChunk(chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        require(empty.onEnd().toString().contains("message_stop"), "completed empty text must remain supported");
        AnthropicBridge.StreamState bounded = new AnthropicBridge.StreamState("fixture");
        bounded.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"one\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"y\",\"function\":{\"name\":\"two\"}}]}}]}"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1100000; i++) huge.append('x');
        JSONObject call = new JSONObject().put("index", 1).put("function", new JSONObject().put("arguments", huge.toString()));
        JSONObject c = new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("delta", new JSONObject().put("tool_calls", new com.gaeul.allin.json.JSONArray().put(call)))));
        require(bounded.onChunk(c).toString().contains("event: error") && bounded.isFailed(), "deferred buffer overflow must fail");
    }

    private static void finishSemantics() throws Exception {
        String[] finishes = {"stop", "length", "tool_calls", "function_call", "content_filter"};
        String[] expected = {"end_turn", "max_tokens", "tool_use", "tool_use", "refusal"};
        for (int i = 0; i < finishes.length; i++) {
            JSONObject choice = new JSONObject().put("message", new JSONObject().put("content", ""))
                    .put("finish_reason", finishes[i]);
            JSONObject response = new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(choice));
            require(expected[i].equals(AnthropicBridge.responseToAnthropic(response, "fixture").optString("stop_reason")), "nonstream mapping " + finishes[i]);
            AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
            List<String> events = new ArrayList<String>();
            add(events, state.onChunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("delta", new JSONObject()).put("finish_reason", finishes[i])))));
            add(events, state.onEnd());
            require(events.toString().contains("\"stop_reason\":\"" + expected[i] + "\"") && !state.isFailed(), "stream mapping " + finishes[i]);
        }
        for (Object finish : new Object[] {JSONObject.NULL, "", "mystery", 123}) {
            JSONObject choice = new JSONObject().put("message", new JSONObject().put("content", "partial")).put("finish_reason", finish);
            require("error".equals(AnthropicBridge.responseToAnthropic(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(choice)), "fixture").optString("type")), "nonstream invalid finish " + finish);
            AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
            state.onChunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", "partial")).put("finish_reason", finish))));
            String end = state.onEnd().toString();
            require(state.isFailed() && end.contains("event: error") && !end.contains("message_stop"), "stream invalid finish " + finish);
        }
        require("error".equals(AnthropicBridge.responseToAnthropic(new JSONObject(), "fixture").optString("type")), "missing choices");
        require("error".equals(AnthropicBridge.responseToAnthropic(chunk("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"), "fixture").optString("type")), "missing finish");
        String[] deltas = {"{\"reasoning_content\":\"thought\"}", "{\"refusal\":\"no\"}", "{\"tool_calls\":[{\"index\":0,\"id\":\"t\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}"};
        for (String delta : deltas) {
            AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
            state.onChunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("delta", chunk(delta)))));
            state.onChunk(chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
            require(state.onEnd().toString().contains("message_stop") && !state.isFailed(), "valid content " + delta);
        }
        AnthropicBridge.StreamState refusalWithoutFinish = new AnthropicBridge.StreamState("fixture");
        refusalWithoutFinish.onChunk(chunk("{\"choices\":[{\"delta\":{\"refusal\":\"no\"}}]}"));
        require(refusalWithoutFinish.onEnd().toString().contains("event: error"), "refusal must not imply finish");
    }

    private static void fragmentedIdentityAndUsage() throws Exception {
        AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
        List<String> e = new ArrayList<String>();
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"look\",\"arguments\":\"{\"}}]}}]}")));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"up\",\"arguments\":\"}\"}}]}}]}")));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(e, s.onChunk(chunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":32,\"completion_tokens\":7}}")));
        add(e, s.onEnd()); validateLifecycle(e);
        String all = e.toString();
        require(all.contains("\"name\":\"lookup\"") && all.contains("\"input_tokens\":32") && all.contains("\"output_tokens\":7"), "fragmented name or late usage");
        AnthropicBridge.StreamState ended = new AnthropicBridge.StreamState("fixture");
        ended.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        require(ended.hasFinishReason() && ended.onEnd().toString().contains("message_stop"), "finish then EOF must succeed");
    }

    // Repro: an upstream that repeats the complete id/name in every delta produced
    // "call_1call_1"/"lookuplookup"; real fragments must still concatenate.
    private static void repeatedIdentitySnapshots() throws Exception {
        AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
        List<String> e = new ArrayList<String>();
        String[][] deltas = {
            {"call_1", "lookup", "{\"a\":"},
            {"call_1", "lookup", "1"},
            {"call_1", "lookup", "}"}
        };
        for (String[] d : deltas) add(e, s.onChunk(toolChunk(d[0], d[1], d[2])));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(e, s.onEnd());
        validateLifecycle(e);
        String all = e.toString();
        require(!s.isFailed(), "repeated snapshot must still complete");
        require(all.contains("\"id\":\"call_1\"") && !all.contains("call_1call_1"), "repeated id snapshot duplicated");
        require(all.contains("\"name\":\"lookup\"") && !all.contains("lookuplookup"), "repeated name snapshot duplicated");
        require(all.contains("\"type\":\"input_json_delta\""), "argument fragments kept");
    }

    // Fragments must never be guessed away: only an exact repeated complete
    // snapshot is collapsed; real fragments, including repeated text, still
    // concatenate and a later different value is not silently overwritten.
    private static void fragmentIdentityIsNeverGuessed() throws Exception {
        AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
        List<String> e = new ArrayList<String>();
        add(e, s.onChunk(toolChunk("call_", "same_", "{\"a\":")));
        add(e, s.onChunk(toolChunk("repeat", "repeat", "1}")));
        add(e, s.onChunk(toolChunk("repeat", "repeat", "")));
        add(e, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(e, s.onEnd());
        validateLifecycle(e);
        String all = e.toString();
        require(!s.isFailed(), "fragment identity must complete");
        require(all.contains("\"id\":\"call_repeatrepeat\""), "legitimate repeated id fragments must not be deduplicated");
        require(all.contains("\"name\":\"same_repeatrepeat\""), "legitimate repeated name fragments must not be deduplicated");
        AnthropicBridge.StreamState changed = new AnthropicBridge.StreamState("fixture");
        List<String> second = new ArrayList<String>();
        add(second, changed.onChunk(toolChunk("call_a", "f", "{}")));
        add(second, changed.onChunk(toolChunk("call_b", "f", null)));
        add(second, changed.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(second, changed.onEnd());
        String text = second.toString();
        require(!changed.isFailed() && text.contains("call_a") && text.contains("call_b"),
                "a changed identity must not be silently overwritten");
    }

    private static JSONObject toolChunk(String id, String name, String args) throws Exception {
        JSONObject fn = new JSONObject().put("name", name);
        if (args != null && !args.isEmpty()) fn.put("arguments", args);
        JSONObject call = new JSONObject().put("index", 0);
        if (!id.isEmpty()) call.put("id", id);
        call.put("function", fn);
        return chunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("tool_calls", new com.gaeul.allin.json.JSONArray().put(call)))))
                .toString());
    }

    // Repro: complete object arguments are published verbatim, while missing,
    // empty, truncated and non-object argument text must fail explicitly. Zero
    // bytes cannot be distinguished from a dropped payload, so no implicit {}.
    private static void toolArgumentCompleteness() throws Exception {
        AnthropicBridge.StreamState complete = new AnthropicBridge.StreamState("fixture");
        List<String> e = new ArrayList<String>();
        add(e, complete.onChunk(toolChunk("call_c", "lookup", "{\"a\":")));
        add(e, complete.onChunk(toolChunk("call_c", "lookup", "1}")));
        add(e, complete.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(e, complete.onEnd());
        validateLifecycle(e);
        String all = e.toString();
        require(!complete.isFailed(), "complete object arguments must be published");
        require(all.contains("\"partial_json\":\"{\\\"a\\\":\"") && all.contains("\"partial_json\":\"1}\""),
                "argument fragments must stay verbatim");

        String[] rejected = {null, "", "{\"a\":", "{\"a\":1", "[", "[]", "\"x\"", "123", "{}x"};
        for (String args : rejected) {
            AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("fixture");
            List<String> events = new ArrayList<String>();
            add(events, state.onChunk(toolChunk("call_t", "f", args)));
            add(events, state.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
            add(events, state.onEnd());
            String text = events.toString();
            require(state.isFailed() && text.contains("event: error") && !text.contains("message_stop"),
                    "incomplete tool arguments accepted: " + args);
        }
    }

    // Repro: a non-string delta content was stringified into the answer (a JSON
    // blob became the model reply) instead of failing.
    private static void nonStringDeltaContentFails() throws Exception {
        for (String content : new String[] {"{\"k\":1}", "42", "true", "[1,2]"}) {
            AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
            String out = s.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":" + content + "}}]}")).toString();
            require(s.isFailed() && out.contains("event: error") && !out.contains("text_delta"), "non-string delta content accepted: " + content);
            AnthropicBridge.StreamState reasoning = new AnthropicBridge.StreamState("fixture");
            String thought = reasoning.onChunk(chunk("{\"choices\":[{\"delta\":{\"reasoning_content\":" + content + "}}]}")).toString();
            require(reasoning.isFailed() && thought.contains("event: error"), "non-string reasoning delta accepted: " + content);
        }
        AnthropicBridge.StreamState ok = new AnthropicBridge.StreamState("fixture");
        String quiet = ok.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":null}}]}")).toString();
        require(!quiet.contains("event: error"), "null delta content must stay an empty delta");
    }

    // Repro: a non-stream upstream that returns content as parts dropped the
    // whole answer (empty message, stop_reason end_turn). Text parts convert;
    // unknown parts fail explicitly.
    private static void nonStreamContentArrays() throws Exception {
        JSONObject array = chunk("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"part one\"},{\"type\":\"text\",\"text\":\" & two\"}]},\"finish_reason\":\"stop\"}]}");
        JSONObject converted = AnthropicBridge.responseToAnthropic(array, "fixture");
        require("message".equals(converted.optString("type")), "text array content must convert: " + converted);
        require("part one & two".equals(converted.getJSONArray("content").getJSONObject(0).getString("text")), "array text content lost");
        String[] unsupported = {"{\"type\":\"image\"}", "{\"type\":\"text\",\"text\":3}", "[1]", "{\"unknown\":true}", "42"};
        for (String content : unsupported) {
            JSONObject source = chunk("{\"choices\":[{\"message\":{\"content\":" + content + "},\"finish_reason\":\"stop\"}]}");
            JSONObject error = AnthropicBridge.responseToAnthropic(source, "fixture");
            require("error".equals(error.optString("type")), "unsupported response content accepted: " + content);
        }
        JSONObject nullContent = chunk("{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"stop\"}]}");
        require("message".equals(AnthropicBridge.responseToAnthropic(nullContent, "fixture").optString("type")), "null content must stay empty");
    }

    private static void usageRoundTrip() throws Exception {
        JSONObject anth = chunk("{\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30,\"output_tokens\":2}}");
        JSONObject open = ZCodeAnthropic.toOpenAI(anth, "fixture");
        assertUsage(AnthropicBridge.responseToAnthropic(open, "fixture").getJSONObject("usage"), 10, 20, 30, 2);
        AnthropicBridge.StreamState s = new AnthropicBridge.StreamState("fixture");
        List<String> events = new ArrayList<String>();
        add(events, s.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}")));
        add(events, s.onChunk(new JSONObject().put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("index", 0).put("delta", new JSONObject()).put("finish_reason", "stop"))).put("usage", open.getJSONObject("usage"))));
        add(events, s.onEnd()); validateLifecycle(events);
        JSONObject start = null, end = null;
        for (String raw : events) {
            JSONObject e = new JSONObject(raw.substring(raw.indexOf("data: ") + 6).trim());
            if ("message_start".equals(e.optString("type"))) start = e.getJSONObject("message").getJSONObject("usage");
            if ("message_delta".equals(e.optString("type"))) end = e.getJSONObject("usage");
        }
        require(start.getLong("input_tokens") == 0, "start usage unknown");
        assertUsage(end, 10, 20, 30, 2);
        ZCodeAnthropic.StreamState z = new ZCodeAnthropic.StreamState("fixture");
        AnthropicBridge.StreamState back = new AnthropicBridge.StreamState("fixture");
        List<String> roundTrip = new ArrayList<String>();
        String[] source = {
            "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30}}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}"
        };
        for (String raw : source) {
            JSONObject ev = chunk(raw);
            for (String openChunk : z.onEvent(ev.getString("type"), ev)) add(roundTrip, back.onChunk(chunk(openChunk)));
        }
        add(roundTrip, back.onEnd()); validateLifecycle(roundTrip);
        JSONObject streamFinal = null;
        for (String raw : roundTrip) {
            JSONObject ev = new JSONObject(raw.substring(raw.indexOf("data: ") + 6).trim());
            if ("message_delta".equals(ev.optString("type"))) streamFinal = ev.getJSONObject("usage");
        }
        assertUsage(streamFinal, 10, 20, 30, 2);
        AnthropicBridge.StreamState splitUsage = new AnthropicBridge.StreamState("fixture");
        splitUsage.onChunk(chunk("{\"usage\":{\"prompt_tokens\":60,\"prompt_tokens_details\":{\"cached_tokens\":20,\"cache_write_tokens\":30}},\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        splitUsage.onChunk(chunk("{\"usage\":{\"completion_tokens\":2},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        for (String raw : splitUsage.onEnd()) {
            JSONObject ev = new JSONObject(raw.substring(raw.indexOf("data: ") + 6).trim());
            if ("message_delta".equals(ev.optString("type"))) assertUsage(ev.getJSONObject("usage"), 10, 20, 30, 2);
        }
        JSONObject readOnly = chunk("{\"usage\":{\"prompt_tokens\":60,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":20}}}");
        readOnly.put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("finish_reason", "stop").put("message", new JSONObject().put("content", ""))));
        JSONObject u = AnthropicBridge.responseToAnthropic(readOnly, "fixture").getJSONObject("usage");
        require(u.getLong("input_tokens") == 40 && u.getLong("cache_read_input_tokens") == 20 && !u.has("cache_creation_input_tokens"), "absent cache write is unknown");
        JSONObject invalid = chunk("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":-4,\"prompt_tokens_details\":{\"cached_tokens\":-5,\"cache_write_tokens\":30}}}");
        invalid.put("choices", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("finish_reason", "stop").put("message", new JSONObject().put("content", ""))));
        u = AnthropicBridge.responseToAnthropic(invalid, "fixture").getJSONObject("usage");
        assertUsage(u, 0, 0, 30, 0);
    }

    private static void assertUsage(JSONObject u, long input, long read, long write, long output) {
        require(u.getLong("input_tokens") == input && u.getLong("cache_read_input_tokens") == read && u.getLong("cache_creation_input_tokens") == write && u.getLong("output_tokens") == output, "usage mismatch " + u);
    }

    public static void main(String[] args) throws Exception {
        List<String> events = new ArrayList<String>();
        AnthropicBridge.StreamState state = new AnthropicBridge.StreamState("offline-test");
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call0\",\"function\":{\"name\":\"zero\",\"arguments\":\"{\\\"a\\\":\"}}]}}]}")));
        // Tool identity may arrive in later chunks, so blocks can be held until finish.
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call1\",\"function\":{\"name\":\"one\",\"arguments\":\"{\\\"b\\\":\"}}]}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"2}\"}}]}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"before\"}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":2,\"id\":\"call2\",\"function\":{\"name\":\"two\",\"arguments\":\"{}\"}}]}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"after\"}}]}")));
        add(events, state.onChunk(chunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        add(events, state.onEnd());

        validateLifecycle(events);
        require(state.onEnd().isEmpty(), "onEnd must be idempotent");
        require(state.onChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"late\"}}]}" )).isEmpty(),
                "chunks after message_stop must be ignored");
        finishSemantics();
        reasoningAndLateToolArguments();
        emptyStream();
        lateIdentityAndErrors();
        truncatedAndBounded();
        fragmentedIdentityAndUsage();
        repeatedIdentitySnapshots();
        fragmentIdentityIsNeverGuessed();
        toolArgumentCompleteness();
        nonStringDeltaContentFails();
        nonStreamContentArrays();
        usageRoundTrip();

        Map<Integer, String> toolIds = new HashMap<Integer, String>();
        Map<Integer, String> toolArgs = new HashMap<Integer, String>();
        Map<Integer, String> text = new HashMap<Integer, String>();
        Map<Integer, Integer> starts = new HashMap<Integer, Integer>();
        Map<Integer, Integer> stops = new HashMap<Integer, Integer>();
        for (String raw : events) {
            JSONObject ev = new JSONObject(raw.substring(raw.indexOf("data: ") + 6).trim());
            String type = ev.optString("type");
            int index = ev.optInt("index", -1);
            if ("content_block_start".equals(type)) {
                JSONObject block = ev.optJSONObject("content_block");
                if ("tool_use".equals(block.optString("type"))) {
                    toolIds.put(index, block.optString("id"));
                    starts.put(index, starts.containsKey(index) ? starts.get(index) + 1 : 1);
                }
            } else if ("content_block_delta".equals(type)) {
                JSONObject delta = ev.optJSONObject("delta");
                if ("input_json_delta".equals(delta.optString("type"))) {
                    toolArgs.put(index, toolArgs.getOrDefault(index, "") + delta.optString("partial_json"));
                } else if ("text_delta".equals(delta.optString("type"))) {
                    text.put(index, text.getOrDefault(index, "") + delta.optString("text"));
                }
            } else if ("content_block_stop".equals(type)) {
                stops.put(index, stops.containsKey(index) ? stops.get(index) + 1 : 1);
            }
        }
        require(toolIds.size() == 3 && starts.size() == 3, "expected three distinct tool starts");
        require("call0".equals(toolIds.get(0)) && "{\"a\":1}".equals(toolArgs.get(0)), "tool 0 was misrouted");
        require("call1".equals(toolIds.get(1)) && "{\"b\":2}".equals(toolArgs.get(1)), "tool 1 was misrouted");
        require("call2".equals(toolIds.get(3)) && "{}".equals(toolArgs.get(3)), "tool 2 missing after interleaving");
        require("before".equals(text.get(2)) && "after".equals(text.get(4)), "text after a closed block missing");
        require(stops.get(0) == 1 && stops.get(1) == 1 && stops.get(2) == 1 && stops.get(3) == 1 && stops.get(4) == 1, "every tool and text block must stop exactly once: " + stops);
        for (String argsJson : toolArgs.values()) new JSONObject(argsJson);
        require(events.get(events.size() - 1).contains("event: message_stop"), "message_stop missing");
        System.out.println("AnthropicBridgeStreamOfflineTest: PASS");
    }
}
