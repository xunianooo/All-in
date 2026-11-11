package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CatPawToolBridgeOfflineTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONArray tools() throws Exception {
        return new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"Read\",\"description\":\"read file\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"path\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"Write\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"path\",\"text\"],\"additionalProperties\":false}}}]");
    }

    private static JSONArray user(String text) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", text));
    }

    private static void rejects(JSONArray messages, JSONArray declared, Object choice) throws Exception {
        try {
            CatPawToolBridge.prepare(messages, declared, choice);
            throw new AssertionError("accepted invalid history");
        } catch (IllegalArgumentException expected) { }
    }

    private static CatPawAuth.ChatResponse response(String text) throws Exception {
        JSONObject frame = new JSONObject().put("message", new JSONObject().put("content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", text))));
        return new CatPawAuth.ChatResponse(200, ("data: " + frame + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void parsing(JSONArray declared) throws Exception {
        JSONArray parsed = CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"rEaD\",\"arguments\":{\"path\":\"x\",\"limit\":2}}]}", declared, "auto");
        check(parsed.length() == 1, "valid tool envelope parsed");
        JSONObject call = parsed.getJSONObject(0);
        check(call.getString("type").equals("function") && call.getString("id").startsWith("call_"), "OpenAI call shape and id");
        check(call.getJSONObject("function").getString("name").equals("Read"), "declared tool spelling restored");
        check(new JSONObject(call.getJSONObject("function").getString("arguments")).getString("path").equals("x"), "arguments retained");
        check(CatPawToolBridge.parse("```json\n{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]}\n```", declared, "auto").length() == 1, "single JSON fence accepted");
        check(CatPawToolBridge.parse("{\"tool_uses\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]}", declared, "auto").length() == 1, "plugin tool_uses envelope accepted");
        check(CatPawToolBridge.parse("{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}", declared, "auto").length() == 1, "plugin bare call envelope accepted");
        check(CatPawToolBridge.parse("Example: {\"tool_calls\":[]}", declared, "auto").length() == 0, "prose containing JSON is not a call");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]} trailing", declared, "auto").length() == 0, "trailing text rejected");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":17}}]}", declared, "auto").length() == 0, "declared string type enforced");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\",\"limit\":1.5}}]}", declared, "auto").length() == 0, "declared integer type enforced");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\",\"limit\":\"2\"}}]}", declared, "auto").length() == 0, "optional property type enforced");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}}]}", declared, "auto").length() == 0, "required property enforced");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}},{\"name\":\"Write\",\"arguments\":{\"path\":\"x\"}}]}", declared, "auto").length() == 0, "invalid call rejects whole envelope");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}},{\"name\":\"Unknown\",\"arguments\":{}}]}", declared, "auto").length() == 0, "unknown call rejects whole envelope");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Write\",\"arguments\":{\"path\":\"x\",\"text\":\"y\"}}]}", declared, new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "Read"))).length() == 0, "named choice rejects other tools");
        check(CatPawToolBridge.parse("{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]}", declared, "none").length() == 0, "none suppresses calls");
    }

    private static void turnIntegrity(JSONArray declared) throws Exception {
        String call = "{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]}";
        String frame = "data: " + new JSONObject().put("message", new JSONObject().put("content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", call)))) + "\n\n";
        check(!CatPawAuth.parseTurn(frame.getBytes(StandardCharsets.UTF_8)).getBoolean("turnComplete"), "EOF snapshot remains unconfirmed");
        check(CatPawAuth.parseTurn((frame + "data: [DONE]\n").getBytes(StandardCharsets.UTF_8)).getBoolean("turnComplete"), "npm DONE terminal recognized");
        check(!CatPawAuth.parseTurn((frame + "data: [DONE]").getBytes(StandardCharsets.UTF_8)).getBoolean("turnComplete"), "partial terminal line is not dispatched");
        check(!CatPawAuth.parseTurn((frame + "data: {\"message\":\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)).getBoolean("turnComplete"), "malformed later snapshot cannot confirm earlier call");
        check(!CatPawAuth.parseTurn((frame + "data: {\"message\":{\"content\":{}}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)).getBoolean("turnComplete"), "invalid later content cannot confirm earlier call");
        try { CatPawAuth.parseTurn(new byte[]{(byte)0xc3}); throw new AssertionError("invalid UTF-8 accepted"); }
        catch (CatPawAuth.Failure expected) { check(expected.httpStatus == 502, "invalid UTF-8 rejected"); }
        for (String invalid : new String[]{call.substring(0, call.length()-1), call.replace("\"path\"", "path"), call.replace("\"x\"", "'x'"), call.replace("\"path\":\"x\"", "\"path\":\"x\",\"path\":\"y\""), call.replace("{\"path\":\"x\"}", JSONObject.quote("{\"path\":\"x\"} trailing"))})
            check(CatPawToolBridge.parse(invalid, declared, "auto").length() == 0, "strict whole JSON required: " + invalid);
        for (String invalid : new String[]{"data: {}\n\n", "data: {\"heartbeat\":true}\n\ndata: [DONE]\n\n", "data: [DONE]\n\n", "data: {\"message\":", frame.trim()}) {
            try { CatPawAuth.parseTurn(invalid.getBytes(StandardCharsets.UTF_8)); throw new AssertionError("accepted non-content stream"); }
            catch (CatPawAuth.Failure expected) { check(expected.sentOrUnknown && expected.httpStatus == 502, "invalid turn failure"); }
        }
    }

    private static void history(JSONArray declared) throws Exception {
        JSONArray original = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "system instruction"))
                .put(new JSONObject().put("role", "developer").put("content", "developer instruction"))
                .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "read file"))));
        JSONArray prepared = CatPawToolBridge.prepare(original, declared, "required");
        check(prepared.length() == 5, "tool instruction brackets the history: " + prepared.length());
        check(prepared.getJSONObject(0).getString("role").equals("system"), "instruction is system text");
        check(prepared.getJSONObject(prepared.length() - 1).getString("role").equals("system"),
                "the instruction is repeated after the history, as the plugin sends it");
        check(prepared.getJSONObject(0).getString("content").equals(prepared.getJSONObject(prepared.length() - 1).getString("content")),
                "both copies carry the same instruction");
        String built = CatPawAuth.buildPrompt(prepared);
        check(built.contains("[System Instructions]\ndeveloper instruction"), "developer maps to system");
        check(built.split(java.util.regex.Pattern.quote("You can call tools using a strict JSON text response"), -1).length == 3,
                "instruction appears once before and once after the history");
        check(built.lastIndexOf("You can call tools using a strict JSON text response") > built.indexOf("[User]"),
                "the trailing copy is the last thing the model reads");
        check(built.contains("tool_choice=required"), "required is explicit in the instruction");
        JSONArray noTools = CatPawToolBridge.prepare(new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")), declared, "none");
        check(noTools.length() == 1, "no instruction is injected when tools are off");

        rejects(new JSONArray("[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"system\",\"content\":null}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"developer\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"image\"}]}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"tool\",\"tool_call_id\":\"orphan\",\"content\":\"x\"}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"Read\",\"arguments\":\"{}\"}}]}]"), declared, "auto");
        JSONArray schemaDrift = new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":17}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"x\"}]");
        JSONArray driftPrepared = CatPawToolBridge.prepare(schemaDrift, declared, "auto");
        check(driftPrepared.getJSONObject(1).getString("content").contains("{\"path\":17}"),
                "a past call that missed its schema is rendered as prompt text, not rejected");
        rejects(new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"Unknown\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"x\"}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}}]}]"), declared, "auto");
        JSONArray multiplePending = new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray()
                        .put(new JSONObject().put("id", "c1").put("function", new JSONObject().put("name", "Read").put("arguments", "{\"path\":\"x\"}")))))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray()
                        .put(new JSONObject().put("id", "c2").put("function", new JSONObject().put("name", "Read").put("arguments", "{\"path\":\"y\"}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "x"))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c2").put("content", "y"));
        rejects(multiplePending, declared, "auto");
        rejects(new JSONArray("[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"same\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}},{\"id\":\"same\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":\\\"y\\\"}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"same\",\"content\":\"x\"}]"), declared, "auto");
        rejects(new JSONArray("[{\"role\":\"user\",\"content\":\"x\",\"tool_call_id\":\"unexpected\"}]"), declared, "auto");

        JSONArray validHistory = new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "c1").put("type", "function").put("function", new JSONObject().put("name", "Read").put("arguments", "{\"path\":\"x\"}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "contents"));
        JSONArray flattened = CatPawToolBridge.prepare(validHistory, declared, "auto");
        check(flattened.length() == 4 && flattened.getJSONObject(1).getString("role").equals("assistant"), "assistant tool history flattened");
        check(flattened.getJSONObject(2).getString("content").contains("Tool result for c1"), "tool result ID retained");
        check(flattened.getJSONObject(3).getString("role").equals("system"), "instruction repeats after the history");
    }

    private static void forcedRetry(JSONArray declared) throws Exception {
        final int[] turns = {0};
        final List<String> turnBodies = new ArrayList<>();
        CatPawAuth.ChatTransport transport = (url, body, headers, timeout, accept) -> {
            if (url.endsWith("/round")) return new CatPawAuth.ChatResponse(200, "{\"code\":0}".getBytes(StandardCharsets.UTF_8));
            if (url.endsWith("/event")) return new CatPawAuth.ChatResponse(200, "{}".getBytes(StandardCharsets.UTF_8));
            turnBodies.add(body);
            turns[0]++;
            return response(turns[0] == 1 ? "plain previous answer" : "{\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{\"path\":\"x\"}}]}");
        };
        JSONObject result = CatPawToolBridge.complete("token", "auto", user("read"), declared,
                new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "Read")), null, transport);
        check(turns[0] == 2 && result.getJSONArray("tool_calls").length() == 1, "one forced retry produced a call");
        String retryPrompt = new JSONObject(turnBodies.get(1)).getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text");
        check(retryPrompt.contains("[Assistant]\nplain previous answer"), "retry includes previous assistant response");
        check(retryPrompt.contains("tool_choice forces tool \"Read\""), "initial prompt names the forced tool");
        check(retryPrompt.contains("invoke \"Read\""), "retry prompt explicitly names the forced tool");
        check(retryPrompt.contains("satisfying that tool's schema above"), "retry prompt requires schema-valid arguments");
        check(retryPrompt.split(java.util.regex.Pattern.quote("You can call tools using a strict JSON text response"), -1).length == 3,
                "the retry carries the same two bracket copies, not a third");
        check(retryPrompt.lastIndexOf("Your previous reply did not include a valid tool call") > retryPrompt.lastIndexOf("You can call tools using a strict JSON text response"),
                "the retry line is what the model reads last, as the plugin appends it after the bracketed history");

        final int[] requests = {0};
        CatPawAuth.ChatTransport unknown = (url, body, headers, timeout, accept) -> {
            requests[0]++;
            if (url.endsWith("/round")) return new CatPawAuth.ChatResponse(200, "{\"code\":0}".getBytes(StandardCharsets.UTF_8));
            if (url.endsWith("/event")) return new CatPawAuth.ChatResponse(200, "{}".getBytes(StandardCharsets.UTF_8));
            throw new IOException("turn outcome unknown");
        };
        try {
            CatPawToolBridge.complete("token", "auto", user("read"), declared, "required", null, unknown);
            throw new AssertionError("ambiguous turn accepted");
        } catch (CatPawAuth.Failure expected) {
            check(expected.sentOrUnknown && requests[0] == 3, "ambiguous request is not replayed");
        }
    }

    public static void main(String[] args) throws Exception {
        JSONArray declared = tools();
        parsing(declared);
        history(declared);
        turnIntegrity(declared);
        forcedRetry(declared);
        check(CatPawToolBridge.prepare(user("hello"), null, "auto").length() == 1, "plain text path retained");
        System.out.println("CatPawToolBridgeOfflineTest passed");
    }
}
