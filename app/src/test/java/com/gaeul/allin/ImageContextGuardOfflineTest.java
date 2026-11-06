package com.gaeul.allin;
import org.json.*;
public final class ImageContextGuardOfflineTest {
    static void ok(boolean x) { if (!x) throw new AssertionError(); }
    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); while (n-- > 0) b.append(s); return b.toString(); }
    static JSONObject msg(String role, Object content) { return new JSONObject().put("role", role).put("content", content); }
    static JSONObject image(String type, int size) { return new JSONObject().put("type", type).put("image_url", "data:image/png;base64," + rep("A", size)); }
    static JSONObject payload(JSONArray a) { return new JSONObject().put("messages", a); }
    public static void main(String[] args) {
        ContextGuard.Config c = new ContextGuard.Config(); c.reserveTokens = 0; c.keepRecent = 0; c.maxBodyBytes = 0; c.maxContextTokens = 300;
        JSONObject old = msg("user", new JSONArray().put(image("image_url", 10000)));
        JSONObject request = payload(new JSONArray().put(old).put(msg("user", "latest")));
        ContextGuard.Result r = ContextGuard.fit(request, c);
        ok(r.error == null && r.dropped == 0 && r.imageCount == 1 && !r.imageTokenEstimateKnown && r.finalTokens < 300 && r.imageBytes > 10000);
        ok(r.payload.getJSONArray("messages").length() == 2 && r.textTokenEstimate == r.finalTokens);
        c.maxBodyBytes = 500;
        r = ContextGuard.fit(request, c);
        ok("budget_exceeded".equals(r.error) && r.payload.getJSONArray("messages").length() == 2 && r.dropped == 0);
        JSONObject block = new JSONObject().put("type", "tool_result").put("content", new JSONArray().put(new JSONObject().put("type", "image").put("source", new JSONObject().put("data", rep("A", 4000)))));
        JSONArray pair = new JSONArray().put(msg("system", "rules")).put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(new JSONObject().put("id", "a")))).put(msg("tool", new JSONArray().put(block)).put("tool_call_id", "a")).put(msg("user", "latest"));
        r = ContextGuard.fit(payload(pair), c);
        ok("budget_exceeded".equals(r.error) && r.payload.getJSONArray("messages").length() == 4 && r.truncated == 0);
        c.maxBodyBytes = 0;
        JSONObject response = new JSONObject().put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", new JSONArray().put(image("input_image", 10000)))));
        r = ContextGuard.fit(response, c); ok(r.error == null && r.imageCount == 1 && r.finalTokens < 300);
        JSONObject toolArgs = new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(new JSONObject().put("arguments", new JSONObject().put("type", "image").put("data", rep("A", 4000)))));
        r = ContextGuard.fit(payload(new JSONArray().put(toolArgs).put(msg("user", "hi"))), c); ok(r.imageCount == 0 && r.dropped == 1);
        c.maxContextTokens = 0; c.maxBodyBytes = 300;
        r = ContextGuard.fit(payload(new JSONArray().put(msg("user", rep("x", 1000))).put(msg("user", "latest"))), c);
        ok(r.error == null && r.dropped == 1 && r.payload.getJSONArray("messages").length() == 1);
        c.enabled = false;
        r = ContextGuard.fit(request, c);
        ok(r.imageCount == 1 && !r.imageTokenEstimateKnown && r.finalTokens == r.textTokenEstimate && r.originalTokens == r.finalTokens);
        c.enabled = true; c.maxBodyBytes = 100;
        r = ContextGuard.fit(response, c);
        ok("budget_exceeded".equals(r.error) && r.imageCount == 1);
        // Image on the assistant also protects every contiguous reply in fallback.
        JSONArray assistantImage = new JSONArray().put(msg("assistant", new JSONArray().put(image("image_url", 1000))).put("tool_calls", new JSONArray().put(new JSONObject().put("id", "b"))))
            .put(msg("tool", "reply").put("tool_call_id", "b")).put(msg("user", "latest"));
        r = ContextGuard.fit(payload(assistantImage), c);
        ok("budget_exceeded".equals(r.error) && r.payload.getJSONArray("messages").length() == 3);
        JSONObject pure = payload(new JSONArray().put(msg("user", "quote \" \\ 界")));
        c.enabled = false; r = ContextGuard.fit(pure, c);
        ok(r.originalTokens == ContextGuard.estimateTokens(pure.toString()) && r.imageTokenEstimateKnown);
        System.out.println("ImageContextGuard offline checks passed");
    }
}
