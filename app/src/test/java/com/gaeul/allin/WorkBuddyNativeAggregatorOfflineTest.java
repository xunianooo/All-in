package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.gaeul.allin.json.JSONObject;

public class WorkBuddyNativeAggregatorOfflineTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static JSONObject aggregate(String s) throws Exception {
        return WorkBuddyNativeAggregator.aggregate(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        String s = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":17,\"cache_read_input_tokens\":4}}}\n\n"
          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reason\"}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"signed\"}}\n\n"
          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\",\"input\":{}}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a\\\"}\"}}\n\n"
          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
          + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":9,\"server_extra\":3}}\n\n"
          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        JSONObject out = aggregate(s);
        check("msg_1".equals(out.optString("id")), "message metadata retained");
        check("tool_use".equals(out.optString("stop_reason")), "stop reason");
        check(out.getJSONObject("usage").optInt("input_tokens")==17 && out.getJSONObject("usage").optInt("output_tokens")==9 && out.getJSONObject("usage").optInt("server_extra")==3, "real usage merged");
        check("reason".equals(out.getJSONArray("content").getJSONObject(0).optString("thinking")) && "signed".equals(out.getJSONArray("content").getJSONObject(0).optString("signature")), "signed thinking retained");
        check("hello".equals(out.getJSONArray("content").getJSONObject(1).optString("text")), "text deltas combined");
        JSONObject tool=out.getJSONArray("content").getJSONObject(2);
        check("a".equals(tool.getJSONObject("input").optString("path")) && !tool.has("partial_json"), "tool JSON deltas combined");
        check(out.getJSONObject("usage").optInt("cache_read_input_tokens")==4,"real cache usage retained");
        boolean incomplete=false; try { aggregate("data: {\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}\n\n"); } catch(java.io.IOException expected) { incomplete=true; }
        check(incomplete, "truncated stream rejected");
        JSONObject crlf=aggregate(s.replace("\n","\r\n"));
        check("hello".equals(crlf.getJSONArray("content").getJSONObject(1).optString("text")),"CRLF SSE accepted");
        boolean duplicate=false;
        WorkBuddyNativeAggregator.State state=new WorkBuddyNativeAggregator.State();
        state.event("{\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}");
        try { state.event("{\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}"); } catch(java.io.IOException expected) { duplicate=true; }
        check(duplicate,"duplicate start rejected");
        boolean eventAfterEnd=false;
        WorkBuddyNativeAggregator.State ended=new WorkBuddyNativeAggregator.State();
        ended.event("{\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}");
        ended.event("{\"type\":\"message_stop\"}");
        try { ended.event("{\"type\":\"ping\"}"); } catch(java.io.IOException expected) { eventAfterEnd=true; }
        check(eventAfterEnd,"events after message stop rejected");
        System.out.println("WorkBuddyNativeAggregatorOfflineTest OK");
    }
}
