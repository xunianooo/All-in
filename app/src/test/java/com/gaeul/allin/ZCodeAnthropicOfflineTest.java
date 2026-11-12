package com.gaeul.allin;

import java.util.List;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

public final class ZCodeAnthropicOfflineTest {
    private static JSONObject json(String s) throws Exception { return new JSONObject(s); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static JSONObject event(ZCodeAnthropic.StreamState s, String body) throws Exception {
        JSONObject e = json(body);
        List<String> result = s.onEvent(e.optString("type"), e);
        check(!result.isEmpty(), "event produced no chunks: " + body);
        return json(result.get(result.size() - 1));
    }
    private static void usage(JSONObject u, int prompt, int output, int read, int write) {
        check(u.getInt("prompt_tokens") == prompt, "prompt " + u);
        check(u.getInt("completion_tokens") == output, "output " + u);
        check(u.getInt("total_tokens") == prompt + output, "total " + u);
        check(u.getJSONObject("prompt_tokens_details").getInt("cached_tokens") == read, "cache read " + u);
        check(u.getJSONObject("prompt_tokens_details").getInt("cache_write_tokens") == write, "cache write " + u);
    }
    public static void main(String[] args) throws Exception {
        String counts = "\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30,\"output_tokens\":2";
        JSONObject reply = ZCodeAnthropic.toOpenAI(json("{\"stop_reason\":\"end_turn\",\"usage\":{" + counts + "}}"), "m");
        usage(reply.getJSONObject("usage"), 60, 2, 20, 30);
        ZCodeAnthropic.StreamState s = new ZCodeAnthropic.StreamState("m");
        JSONObject start = event(s, "{\"type\":\"message_start\",\"message\":{\"usage\":{" + counts + "}}}");
        usage(start.getJSONObject("usage"), 60, 2, 20, 30);
        JSONObject end = event(s, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30,\"output_tokens\":7}}");
        usage(end.getJSONObject("usage"), 60, 7, 20, 30);
        ZCodeAnthropic.StreamState partial = new ZCodeAnthropic.StreamState("m");
        event(partial, "{\"type\":\"message_start\",\"message\":{\"usage\":{" + counts + "}}}");
        end = event(partial, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":9,\"cache_read_input_tokens\":0}}");
        usage(end.getJSONObject("usage"), 40, 9, 0, 30);
        JSONObject req = ZCodeAnthropic.toAnthropic(json("{\"max_tokens\":20,\"max_completion_tokens\":100,\"parallel_tool_calls\":false,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"foo\"}}],\"tool_choice\":\"required\",\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"x\",\"content\":\"oops\",\"is_error\":true}]}"));
        check(req.getInt("max_tokens") == 20, "two explicit output limits honor the smaller one");
        check("any".equals(req.getJSONObject("tool_choice").getString("type")), "required");
        check(req.getJSONObject("tool_choice").getBoolean("disable_parallel_tool_use"), "parallel");
        JSONObject block = req.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0);
        check(block.getBoolean("is_error"), "tool error");
        check(!req.toString().contains("_toolResults"), "internal marker leaked");
        JSONObject named = ZCodeAnthropic.toAnthropic(json("{\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}],\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"f\"}},\"parallel_tool_calls\":false}"));
        check("f".equals(named.getJSONObject("tool_choice").getString("name")), "named tool");
        check(named.getJSONObject("tool_choice").getBoolean("disable_parallel_tool_use"), "named parallel");
        JSONObject no = ZCodeAnthropic.toAnthropic(json("{\"tools\":[{\"function\":{\"name\":\"f\"}}],\"tool_choice\":\"none\",\"parallel_tool_calls\":false}"));
        check(!no.getJSONObject("tool_choice").has("disable_parallel_tool_use"), "none parallel");
        ZCodeAnthropic.StreamState interleaved = new ZCodeAnthropic.StreamState("m");
        event(interleaved, "{\"type\":\"content_block_start\",\"index\":4,\"content_block\":{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"f\"}}");
        event(interleaved, "{\"type\":\"content_block_start\",\"index\":6,\"content_block\":{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"g\"}}");
        JSONObject delta = event(interleaved, "{\"type\":\"content_block_delta\",\"index\":4,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}");
        check(delta.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0).getInt("index") == 0, "interleaved tool");
        String[] reasons = {"end_turn", "stop_sequence", "refusal", "max_tokens", "tool_use"};
        String[] expected = {"stop", "stop", "content_filter", "length", "tool_calls"};
        for (int i = 0; i < reasons.length; i++) {
            JSONObject message = new JSONObject().put("stop_reason", reasons[i]).put("content", new JSONArray()
                    .put(new JSONObject().put("type", "thinking").put("thinking", "reason"))
                    .put(new JSONObject().put("type", "tool_use").put("id", "x").put("name", "f")));
            JSONObject choice = ZCodeAnthropic.toOpenAI(message, "m").getJSONArray("choices").getJSONObject(0);
            check(expected[i].equals(choice.getString("finish_reason")), "nonstream " + reasons[i]);
            check(choice.getJSONObject("message").has("reasoning_content") && choice.getJSONObject("message").has("tool_calls"), "content preserved");
            ZCodeAnthropic.StreamState state = new ZCodeAnthropic.StreamState("m");
            JSONObject finalChunk = event(state, new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", reasons[i])).toString());
            check(expected[i].equals(finalChunk.getJSONArray("choices").getJSONObject(0).getString("finish_reason")), "stream " + reasons[i]);
        }
        for (String invalid : new String[]{"", "unknown", "model_context_window_exceeded_2"}) {
            boolean failed = false;
            try { ZCodeAnthropic.toOpenAI(new JSONObject().put("stop_reason", invalid), "m"); } catch (IllegalArgumentException ex) { failed = true; }
            check(failed, "nonstream invalid " + invalid);
            failed = false;
            try { new ZCodeAnthropic.StreamState("m").onEvent("message_delta", new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", invalid))); } catch (IllegalArgumentException ex) { failed = true; }
            check(failed, "stream invalid " + invalid);
            // The native surface accepts every reason Anthropic defines and nothing else; widening it for
            // pause_turn must not turn unknown values into accepted ones.
            failed = false;
            try { ZCodeAnthropic.validateStopReason(new JSONObject().put("stop_reason", invalid)); } catch (IllegalArgumentException ex) { failed = true; }
            check(failed, "native invalid " + invalid);
        }
        boolean missingFailed = false;
        try { ZCodeAnthropic.toOpenAI(new JSONObject(), "m"); } catch (IllegalArgumentException ex) { missingFailed = true; }
        check(missingFailed, "missing nonstream stop");
        ZCodeAnthropic.StreamState delayed = new ZCodeAnthropic.StreamState("m");
        event(delayed, "{\"type\":\"message_start\"}");
        JSONObject usageOnly = event(delayed, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":null},\"usage\":{\"output_tokens\":3}}");
        check(usageOnly.getJSONArray("choices").length() == 0, "usage-only must not finish");
        JSONObject late = event(delayed, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        check("stop".equals(late.getJSONArray("choices").getJSONObject(0).getString("finish_reason")), "late finish");
        // Upstream stop reasons Anthropic defines but Chat can only express lossily. Native clients get
        // them verbatim (NativeAnthropicStreamOfflineTest); the Chat projection must stay honest.
        String overflowReason = "model_context_window_exceeded";
        ZCodeAnthropic.validateStopReason(new JSONObject().put("stop_reason", overflowReason));
        JSONObject overflowMessage = new JSONObject().put("stop_reason", overflowReason)
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "partial")));
        JSONObject overflowChoice = ZCodeAnthropic.toOpenAI(overflowMessage, "m").getJSONArray("choices").getJSONObject(0);
        check("length".equals(overflowChoice.getString("finish_reason")), "nonstream projection " + overflowReason);
        check(!overflowChoice.getJSONObject("message").has("tool_calls"), "no fabricated tool_calls for " + overflowReason);
        check("partial".equals(overflowChoice.getJSONObject("message").getString("content")), "content kept for " + overflowReason);
        ZCodeAnthropic.StreamState overflowState = new ZCodeAnthropic.StreamState("m");
        event(overflowState, "{\"type\":\"message_start\"}");
        overflowState.onEvent("content_block_start", json("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        event(overflowState, "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}");
        JSONObject overflowFinal = event(overflowState, new JSONObject().put("type", "message_delta")
                .put("delta", new JSONObject().put("stop_reason", overflowReason)).toString());
        check("length".equals(overflowFinal.getJSONArray("choices").getJSONObject(0).getString("finish_reason")), "stream projection " + overflowReason);
        // pause_turn is a pause: not a finished answer and not a completed tool request. Chat can express
        // neither of those, and both candidate projections would be fabricated - stop calls an unfinished
        // turn complete (silent success) and, beside the paused turn's tool_use blocks, contradicts itself;
        // tool_calls would ask clients to run tools the upstream never requested. So the Chat projection
        // fails explicitly, while the native validation keeps accepting pause_turn verbatim.
        String pauseTurn = "pause_turn";
        ZCodeAnthropic.validateStopReason(new JSONObject().put("stop_reason", pauseTurn));
        ZCodeAnthropic.validateStopReason(new JSONObject().put("type", "message_delta")
                .put("delta", new JSONObject().put("stop_reason", pauseTurn)).getJSONObject("delta"));
        JSONObject pausedWithTool = new JSONObject().put("stop_reason", pauseTurn).put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "one moment"))
                .put(new JSONObject().put("type", "tool_use").put("id", "t").put("name", "f").put("input", new JSONObject())));
        String pausedToolError = null;
        try { ZCodeAnthropic.toOpenAI(pausedWithTool, "m"); } catch (IllegalArgumentException ex) { pausedToolError = ex.getMessage(); }
        check(ZCodeAnthropic.PAUSE_TURN_UNSUPPORTED.equals(pausedToolError), "paused turn with tool_use must fail explicitly: " + pausedToolError);
        // A pause that carries no tool_use is just as unrepresentable: reporting stop would hide that the
        // turn is unfinished, so it must fail too and must never produce a completion body.
        JSONObject pausedTextOnly = new JSONObject().put("stop_reason", pauseTurn)
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "one moment")));
        String pausedTextError = null;
        try { ZCodeAnthropic.toOpenAI(pausedTextOnly, "m"); } catch (IllegalArgumentException ex) { pausedTextError = ex.getMessage(); }
        check(ZCodeAnthropic.PAUSE_TURN_UNSUPPORTED.equals(pausedTextError), "paused turn must fail explicitly: " + pausedTextError);
        // Streaming: the tool_use deltas come from upstream, but the pause itself must never be turned into
        // a stop or tool_calls finish - it aborts before any finish chunk is produced.
        ZCodeAnthropic.StreamState pausedStream = new ZCodeAnthropic.StreamState("m");
        event(pausedStream, "{\"type\":\"message_start\"}");
        event(pausedStream, "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\"}}");
        String pausedStreamError = null;
        List<String> pausedChunks = null;
        try {
            pausedChunks = pausedStream.onEvent("message_delta", json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}"));
        } catch (IllegalArgumentException ex) { pausedStreamError = ex.getMessage(); }
        check(ZCodeAnthropic.PAUSE_TURN_UNSUPPORTED.equals(pausedStreamError), "stream paused turn must fail explicitly: " + pausedStreamError);
        check(pausedChunks == null, "stream paused turn must not emit any chunk");
        // The native surface is untouched by that refusal: the same pause stays a valid stop reason.
        ZCodeAnthropic.validateStopReason(new JSONObject().put("stop_reason", pauseTurn).put("type", "message"));
        // The thinking-only truncation compatibility notice belongs to a real max_tokens cut only; a
        // context window overflow must stay visible as length instead of being masked into stop.
        String notice = null;
        ZCodeAnthropic.StreamState cutThinking = new ZCodeAnthropic.StreamState("m", true);
        cutThinking.onEvent("message_start", json("{\"type\":\"message_start\"}"));
        cutThinking.onEvent("content_block_start", json("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        cutThinking.onEvent("content_block_delta", json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reason\"}}"));
        List<String> cutChunks = cutThinking.onEvent("message_delta", json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"));
        for (String chunk : cutChunks) {
            JSONObject o = json(chunk);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject d = choices.getJSONObject(0).optJSONObject("delta");
            if (d != null && d.has("content")) notice = d.getString("content");
        }
        check(ThinkingTruncationStream.NOTICE.equals(notice), "thinking-only max_tokens keeps the notice");
        check("stop".equals(json(cutChunks.get(cutChunks.size() - 1)).getJSONArray("choices").getJSONObject(0).getString("finish_reason")), "notice suppresses length");
        String overflowNotice = null;
        ZCodeAnthropic.StreamState overflowThinking = new ZCodeAnthropic.StreamState("m", true);
        overflowThinking.onEvent("message_start", json("{\"type\":\"message_start\"}"));
        overflowThinking.onEvent("content_block_start", json("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        overflowThinking.onEvent("content_block_delta", json("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reason\"}}"));
        List<String> overflowChunks = overflowThinking.onEvent("message_delta", json("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"model_context_window_exceeded\"}}"));
        for (String chunk : overflowChunks) {
            JSONObject o = json(chunk);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject d = choices.getJSONObject(0).optJSONObject("delta");
            if (d != null && d.has("content")) overflowNotice = d.getString("content");
        }
        check(overflowNotice == null, "context overflow must not reuse the truncation notice");
        check("length".equals(json(overflowChunks.get(overflowChunks.size() - 1)).getJSONArray("choices").getJSONObject(0).getString("finish_reason")), "context overflow stays length");
        System.out.println("ZCodeAnthropic offline tests passed");
    }
}
