package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression: a valid tool-only Chat reply may contain an empty content string. */
public final class ResponsesEmptyContentOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JSONObject reply(Object content, boolean tools) throws Exception {
        JSONObject message = new JSONObject().put("role", "assistant");
        if (content != null) message.put("content", content);
        if (tools) message.put("tool_calls", new JSONArray()
                .put(new JSONObject().put("id", "call_lookup").put("type", "function")
                        .put("function", new JSONObject().put("name", "lookup").put("arguments", "{\"q\":\"中文\"}")))
                .put(new JSONObject().put("id", "call_clock").put("type", "function")
                        .put("function", new JSONObject().put("name", "clock").put("arguments", "{}"))));
        return new JSONObject().put("id", "chat_fixture").put("model", "m")
                .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                        .put("message", message).put("finish_reason", tools ? "tool_calls" : "stop")))
                .put("usage", new JSONObject().put("prompt_tokens", 7).put("completion_tokens", 3).put("total_tokens", 10));
    }
    static void calls(JSONObject response) throws Exception {
        check("response".equals(response.getString("object")), "Responses envelope");
        check("completed".equals(response.getString("status")), "completed status");
        JSONArray output = response.getJSONArray("output");
        int functions = 0;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.getJSONObject(i);
            if ("function_call".equals(item.getString("type"))) {
                String id = functions == 0 ? "call_lookup" : "call_clock";
                check(id.equals(item.getString("call_id")), "call ID/order preserved");
                check((functions == 0 ? "lookup" : "clock").equals(item.getString("name")), "function name");
                check((functions == 0 ? "{\"q\":\"中文\"}" : "{}").equals(item.getString("arguments")), "arguments preserved");
                functions++;
            }
        }
        check(functions == 2, "both function calls preserved");
        check(response.getJSONObject("usage").getInt("total_tokens") == 10, "usage preserved");
    }
    public static void main(String[] args) throws Exception {
        for (Object content : new Object[]{"", JSONObject.NULL, null, "text", new JSONArray()}) {
            JSONObject out = ResponsesBridge.chatResponseToResponses(reply(content, true), "mock/m");
            calls(out);
            check(out.getJSONArray("output").length() == ("text".equals(content) ? 3 : 2), "no fabricated empty message");
            check("mock/m".equals(out.getString("model")), "client model preserved");
        }
        JSONObject empty = ResponsesBridge.chatResponseToResponses(reply("", false), "mock/m");
        check(empty.getJSONArray("output").length() == 0, "empty text without tools accepted");
        JSONObject refused = reply("", false);
        refused.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal", "cannot comply");
        check(ResponsesBridge.chatResponseToResponses(refused, "mock/m").toString().contains("cannot comply"), "empty text plus refusal");
        for (Object invalid : new Object[]{42, Boolean.TRUE, new JSONObject(),
                new JSONArray().put(new JSONObject().put("type", "image_url"))}) {
            boolean rejected = false;
            try { ResponsesBridge.chatResponseToResponses(reply(invalid, true), "mock/m"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "unsupported response content remains rejected");
        }
        UsageLedger.init(Files.createTempDirectory("responses-empty-content-").toFile());
        String input = "{\"model\":\"mock/m\",\"input\":\"lookup\",\"stream\":false,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"parameters\":{\"type\":\"object\"}}]}";
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock(reply("", true).toString(), false)) {
            GatewayServer gateway = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort());
            NanoHTTPD.Response response = gateway.serve(ResponsesGatewayOfflineTest.session(input, "secret"));
            String body = ResponsesGatewayOfflineTest.read(response.getData());
            check(response.getStatus().getRequestStatus() == 200, "Responses endpoint must return 200: " + body);
            calls(new JSONObject(body));
            check(mock.requestLine.startsWith("POST /v1/chat/completions "), "same Chat upstream");
            check("lookup".equals(mock.received.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name")), "request tools forwarded");
        }
        String chat = "{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":\"lookup\"}],\"stream\":false}";
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock(reply("", true).toString(), false)) {
            GatewayServer gateway = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort());
            NanoHTTPD.Response response = gateway.serve(ResponsesGatewayOfflineTest.session(chat, "secret", "/v1/chat/completions"));
            JSONObject body = new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));
            check(response.getStatus().getRequestStatus() == 200, "Chat endpoint unchanged");
            check(body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").length() == 2, "Chat tool calls unchanged");
        }
        System.out.println("ResponsesEmptyContentOfflineTest OK");
    }
}
