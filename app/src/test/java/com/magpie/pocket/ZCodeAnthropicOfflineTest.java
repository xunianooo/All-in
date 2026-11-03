package com.magpie.pocket;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

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
        JSONObject reply = ZCodeAnthropic.toOpenAI(json("{\"usage\":{" + counts + "}}"), "m");
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
        check(req.getInt("max_tokens") == 100, "completion limit precedence");
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
        System.out.println("ZCodeAnthropic offline tests passed");
    }
}
