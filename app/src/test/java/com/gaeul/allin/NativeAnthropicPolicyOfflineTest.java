package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

public final class NativeAnthropicPolicyOfflineTest {
    interface Action { void run() throws Exception; }
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
    static void rejects(Action action, String name) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(name);
    }
    static JSONObject request(String blocks) throws Exception {
        return new JSONObject("{\"model\":\"m\",\"max_tokens\":100,\"messages\":[{\"role\":\"assistant\",\"content\":" + blocks + "}]}");
    }
    public static void main(String[] args) throws Exception {
        TestPrivacyScope.initialize();
        PrivacyMasker.Config config = new PrivacyMasker.Config();
        PrivacyMasker.Session session = new PrivacyMasker.Session();
        JSONObject plain = request("[{\"type\":\"text\",\"text\":\"mail a@example.com\"}]");
        String masked = NativeAnthropicPolicy.maskRequest(plain.toString(), session, config, null);
        check(!masked.contains("a@example.com") && !session.placeholderToReal.isEmpty(), "mask text");
        check(!NativeAnthropicPolicy.requiresNative(plain), "plain conversion");
        JSONObject signed = request("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"signed-value\"}]");
        check(NativeAnthropicPolicy.requiresNative(signed), "empty thinking");
        check(new JSONObject(NativeAnthropicPolicy.maskRequest(signed.toString(), session, config, null))
                .getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
                .getString("signature").equals("signed-value"), "signature preserved");
        JSONObject secret = request("[{\"type\":\"thinking\",\"thinking\":\"a@example.com\",\"signature\":\"x\"}]");
        rejects(() -> NativeAnthropicPolicy.maskRequest(secret.toString(), session, config, null), "signed privacy");
        JSONObject unknown = request("[{\"type\":\"future\",\"value\":\"a@example.com\"}]");
        rejects(() -> NativeAnthropicPolicy.maskRequest(unknown.toString(), session, config, null), "opaque privacy");
        JSONObject wg = new JSONObject("{\"wordGuard\":{\"enabled\":true,\"words\":[\"blocked\"],\"mode\":\"mask\"}}");
        JSONObject word = request("[{\"type\":\"thinking\",\"thinking\":\"blocked\",\"signature\":\"x\"}]");
        rejects(() -> NativeAnthropicPolicy.maskRequest(word.toString(), session, config, wg), "thinking word guard");
        wg.getJSONObject("wordGuard").put("mode", "block");
        rejects(() -> NativeAnthropicPolicy.maskRequest(word.toString(), session, config, wg), "thinking word block");
        JSONObject redacted = request("[{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}]");
        rejects(() -> NativeAnthropicPolicy.maskRequest(redacted.toString(), session, config, wg), "redacted guard");
        JSONObject nested = request("[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":[{\"type\":\"future\",\"payload\":\"x\"}]}]");
        check(NativeAnthropicPolicy.requiresNative(nested), "nested future");
        JSONObject baseline = new JSONObject("{\"model\":\"m\",\"max_tokens\":100,\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"}]}");
        JSONObject current = new JSONObject(baseline.toString());
        NativeAnthropicPolicy.checkNativeRequired(signed, baseline, current, true);
        rejects(() -> NativeAnthropicPolicy.checkNativeRequired(signed, baseline, current, false), "direct disabled");
        current.getJSONArray("messages").getJSONObject(0).put("content", "changed");
        rejects(() -> NativeAnthropicPolicy.checkNativeRequired(signed, baseline, current, true), "guard mutation");
        final JSONObject deleted = new JSONObject(baseline.toString());
        deleted.remove("max_tokens");
        rejects(() -> NativeAnthropicPolicy.checkNativeRequired(signed, baseline, deleted, true), "deleted limit");
        JSONObject multi = new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"f\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"ok\"}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"}]}]}");
        check(NativeAnthropicPolicy.requiresNative(multi), "multi turn tool native");
        NativeAnthropicPolicy.checkNativeRequired(multi, baseline, baseline, true);
        String response = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"}]}";
        check(NativeAnthropicPolicy.unmaskResponse(response, null).equals(response), "null session");
        JSONObject protectedResponse = new JSONObject(NativeAnthropicPolicy.unmaskResponse(response, session));
        check(protectedResponse.getJSONArray("content").getJSONObject(0).getString("signature").equals("x"), "response signed");
        String token = session.placeholderToReal.keySet().iterator().next();
        JSONObject result = new JSONObject(NativeAnthropicPolicy.unmaskResponse(
                "{\"content\":[{\"type\":\"text\",\"text\":\"" + token + "\"}]}", session));
        check(result.getJSONArray("content").getJSONObject(0).getString("text").equals("a@example.com"), "response text");
        JSONObject complex = new JSONObject("{\"model\":\"m\",\"tools\":[{\"name\":\"f\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"a@example.com\",\"default\":\"a@example.com\"}}}}],\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"protocol-id\",\"name\":\"f\",\"input\":{\"id\":\"a@example.com\",\"type\":\"a@example.com\",\"data\":\"a@example.com\",\"source\":\"a@example.com\"}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"protocol-id\",\"content\":\"{\\\"id\\\":\\\"a@example.com\\\"}\"}]}]}");
        JSONObject maskedComplex = new JSONObject(NativeAnthropicPolicy.maskRequest(complex.toString(), session, config, null));
        check(!maskedComplex.toString().contains("a@example.com"), "tool input result and schema masked");
        JSONObject input = maskedComplex.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input");
        for (String k : new String[]{"id", "type", "data", "source"}) check(input.getString(k).contains("MASK_"), "input " + k);
        JSONObject schema = maskedComplex.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema");
        check(schema.getString("type").equals("object") && schema.getJSONObject("properties").has("id"), "schema structure");
        JSONObject mixed = new JSONObject();
        mixed.put("content", new com.gaeul.allin.json.JSONArray()
            .put(new JSONObject().put("type", "thinking").put("thinking", token).put("signature", token))
            .put(new JSONObject().put("type", "text").put("text", token))
            .put(new JSONObject().put("type", "tool_use").put("input", new JSONObject().put("id", token).put("type", token).put("data", token)))
            .put(new JSONObject().put("type", "future").put("payload", token)));
        com.gaeul.allin.json.JSONArray mixedOut = new JSONObject(NativeAnthropicPolicy.unmaskResponse(mixed.toString(), session)).getJSONArray("content");
        check(mixedOut.getJSONObject(0).getString("signature").equals(token) && mixedOut.getJSONObject(0).getString("thinking").equals(token), "signed unchanged");
        check(mixedOut.getJSONObject(1).getString("text").equals("a@example.com"), "mixed text restored");
        check(mixedOut.getJSONObject(2).getJSONObject("input").getString("id").equals("a@example.com"), "mixed tool restored");
        check(mixedOut.getJSONObject(3).getString("payload").equals(token), "unknown unchanged");
        JSONObject raised = new JSONObject(baseline.toString()).put("max_tokens", 101);
        rejects(() -> NativeAnthropicPolicy.checkNativeRequired(signed, baseline, raised, true), "raised cap");
        JSONObject lowered = new JSONObject(baseline.toString()).put("max_tokens", 50);
        NativeAnthropicPolicy.checkNativeRequired(signed, baseline, lowered, true);
        JSONObject prefix = new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"a@example.com\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"safe\",\"signature\":\"sig\"}]},{\"role\":\"user\",\"content\":\"hello\"}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(prefix.toString(), new PrivacyMasker.Session(), config, null), "protected prefix");
        prefix.getJSONArray("messages").getJSONObject(0).put("content", "safe");
        prefix.getJSONArray("messages").getJSONObject(2).put("content", "a@example.com");
        JSONObject afterPrefix = new JSONObject(NativeAnthropicPolicy.maskRequest(prefix.toString(), new PrivacyMasker.Session(), config, null));
        check(afterPrefix.getJSONArray("messages").getJSONObject(2).getString("content").contains("MASK_"), "suffix masked");
        PrivacyMasker.Config custom = new PrivacyMasker.Config();
        custom.customPatterns.add("toolname");
        JSONObject toolName = new JSONObject("{\"tools\":[{\"name\":\"toolname\"}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(toolName.toString(), new PrivacyMasker.Session(), custom, null), "tool name privacy");
        custom.customPatterns.clear(); custom.customPatterns.add("text");
        JSONObject systemBlock = new JSONObject("{\"system\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(systemBlock.toString(), new PrivacyMasker.Session(), custom, null), "system type privacy");
        custom.customPatterns.clear(); custom.customPatterns.add("enumvalue");
        JSONObject enumSchema = new JSONObject("{\"tools\":[{\"name\":\"f\",\"input_schema\":{\"type\":\"string\",\"enum\":[\"enumvalue\"]}}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(enumSchema.toString(), new PrivacyMasker.Session(), custom, null), "schema enum privacy");
        custom.customPatterns.clear(); custom.customPatterns.add("sensitivekey");
        JSONObject keyInput = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"f\",\"input\":{\"sensitivekey\":\"ok\"}}]}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(keyInput.toString(), new PrivacyMasker.Session(), custom, null), "tool key privacy");
        custom.customPatterns.clear(); custom.customPatterns.add("123456");
        JSONObject numberInput = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"f\",\"input\":{\"n\":123456}}]}]}");
        rejects(() -> NativeAnthropicPolicy.maskRequest(numberInput.toString(), new PrivacyMasker.Session(), custom, null), "tool number privacy");
        System.out.println("NativeAnthropicPolicyOfflineTest OK");
    }
}
