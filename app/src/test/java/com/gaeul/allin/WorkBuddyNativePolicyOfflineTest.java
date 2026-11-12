package com.gaeul.allin;

import java.util.Map;
import com.gaeul.allin.json.JSONObject;

public class WorkBuddyNativePolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        check(WorkBuddyNativePolicy.useNative("workbuddy", "Claude-opus-5.5"));
        check(!WorkBuddyNativePolicy.useNative("workbuddy-ai", "claude-opus-5.5"));
        check(!WorkBuddyNativePolicy.useNative("workbuddy", "glm-5.3"));
        check(!WorkBuddyNativePolicy.useNative("workbuddy", "claude-"));
        JSONObject account = new JSONObject().put("uid", "personal");
        JSONObject body = new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"old\"},{\"type\":\"thinking\",\"thinking\":\"signed\",\"signature\":\"sig\"},{\"type\":\"redacted_thinking\",\"data\":\"opaque\"},{\"type\":\"text\",\"text\":\"DeepSeek Harness\"}]},{\"role\":\"user\",\"content\":\"DSH\"}]}");
        String original = body.toString();
        JSONObject prepared = WorkBuddyNativePolicy.prepare(body, false, null);
        check(original.equals(body.toString()) && prepared.optBoolean("stream"));
        check(prepared.getJSONArray("messages").getJSONObject(0).getJSONArray("content").length() == 4);
        check(prepared.toString().contains("DeepSeek Harness") && prepared.toString().contains("sig") && prepared.toString().contains("opaque"));
        JSONObject nativeRequest = new JSONObject("{\"model\":\"claude-opus-5.5\",\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"format\":{\"type\":\"json_schema\"}},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JSONObject catalog = new JSONObject("{\"data\":{\"models\":[{\"id\":\"claude-opus-5.5\",\"reasoning\":{\"defaultEffort\":\"high\",\"supportedEfforts\":[\"low\",\"high\"]}}]}}");
        JSONObject nativePrepared = WorkBuddyNativePolicy.prepare(nativeRequest,false,null,WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5"));
        check("adaptive".equals(nativePrepared.getJSONObject("thinking").optString("type")), "native request keeps the thinking it was sent with");
        check(!nativePrepared.getJSONObject("output_config").has("effort"), "Messages is not a chat: no catalog default effort is injected");
        check(nativePrepared.getJSONObject("output_config").has("format") && "adaptive".equals(nativePrepared.getJSONObject("thinking").optString("type")), "native thinking/output_config preserved");
        JSONObject explicitEffort = new JSONObject(nativeRequest.toString()).put("output_config",new JSONObject().put("effort","max"));
        check("max".equals(WorkBuddyNativePolicy.prepare(explicitEffort,false,null,WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5")).getJSONObject("output_config").optString("effort")), "explicit effort wins");
        JSONObject chat=new JSONObject("{\"model\":\"workbuddy/claude-opus-5.5\",\"max_tokens\":2000,\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"},{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"Read\",\"description\":\"read\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}],\"tool_choice\":\"required\",\"reasoning_effort\":\"max\"}");
        JSONObject nativeChat=WorkBuddyNativePolicy.fromOpenAIChat(chat,"claude-opus-5.5",WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5"),true,null);
        check(nativeChat.optBoolean("stream") && nativeChat.getJSONArray("tools").getJSONObject(0).has("input_schema") && "any".equals(nativeChat.getJSONObject("tool_choice").optString("type")), "OpenAI tools converted to Anthropic request");
        check("max".equals(nativeChat.getJSONObject("output_config").optString("effort")) && "adaptive".equals(nativeChat.getJSONObject("thinking").optString("type")), "explicit effort mapped natively");
        check(!nativeChat.getJSONObject("thinking").has("budget_tokens") && !nativeChat.getJSONObject("thinking").has("budgetTokens"), "Claude effort is never a ZCode thinking budget");
        JSONObject configOnly=new JSONObject("{\"model\":\"workbuddy/claude-opus-5.5\",\"output_config\":{\"effort\":\"high\"},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JSONObject configOnlyNative=WorkBuddyNativePolicy.fromOpenAIChat(configOnly,"claude-opus-5.5",null,false,null);
        check("adaptive".equals(configOnlyNative.getJSONObject("thinking").optString("type"))&&"high".equals(configOnlyNative.getJSONObject("output_config").optString("effort")),"explicit output_config activates native adaptive thinking");
        JSONObject effortCatalog=new JSONObject("{\"claude-opus-5.5\":{\"reasoning\":{\"defaultEffort\":\"high\",\"supportedEfforts\":[\"low\",\"high\"]}}}");
        JSONObject defaultChat=new JSONObject(chat.toString());defaultChat.remove("reasoning_effort");
        JSONObject defaultNative=WorkBuddyNativePolicy.fromOpenAIChat(defaultChat,"claude-opus-5.5",WorkBuddyNativePolicy.catalogEntry(effortCatalog,"claude-opus-5.5"),false,null);
        check(!defaultNative.has("thinking")
                && (!defaultNative.has("output_config") || !defaultNative.getJSONObject("output_config").has("effort"))
                && defaultNative.getInt("max_tokens") == 2000,
                "a chat with no effort and no catalog output limit invents neither");
        JSONObject smallBudget = new JSONObject(nativeRequest.toString()).put("max_tokens",1024);
        check(!WorkBuddyNativePolicy.prepare(smallBudget,false,null,WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5")).getJSONObject("output_config").has("effort"), "1024 budget has no default effort");
        JSONObject justAbove = new JSONObject(nativeRequest.toString()).put("max_tokens",1025);
        check(!WorkBuddyNativePolicy.prepare(justAbove,false,null,WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5")).getJSONObject("output_config").has("effort"), "1025 budget still carries no injected effort");
        JSONObject catalogLimit = new JSONObject("{\"id\":\"claude-opus-5.5\",\"maxOutputTokens\":48000}");
        JSONObject noCapChat = new JSONObject("{\"model\":\"workbuddy/claude-opus-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        check(WorkBuddyNativePolicy.fromOpenAIChat(noCapChat,"claude-opus-5.5",catalogLimit,false,null).getInt("max_tokens")==48000,
                "the catalog output limit is the native default");
        check(WorkBuddyNativePolicy.fromOpenAIChat(noCapChat,"claude-opus-5.5",null,false,null).getInt("max_tokens")==16384,
                "an unknown model keeps the translation fallback");
        check(WorkBuddyNativePolicy.fromOpenAIChat(new JSONObject(noCapChat.toString()).put("max_tokens",2000),"claude-opus-5.5",catalogLimit,false,null).getInt("max_tokens")==2000,
                "a client cap still wins over the catalog default");
        // The same catalog entry reaches the native relay through prepare, where Anthropic's own
        // need for a limit is filled the way magpie fills it (fillsOutput + askedOutput).
        JSONObject relayNoCap=WorkBuddyNativePolicy.prepare(new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),false,null,catalogLimit);
        check(relayNoCap.getInt("max_tokens")==48000 && !relayNoCap.has("thinking") && !relayNoCap.has("output_config"),
                "the native relay is filled with the model's own output limit and invents no reasoning");
        JSONObject relayNamed=WorkBuddyNativePolicy.prepare(new JSONObject("{\"model\":\"claude-opus-5.5\",\"max_tokens\":3000,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),false,null,catalogLimit);
        check(relayNamed.getInt("max_tokens")==3000,"a named native limit is relayed unchanged");
        check(!WorkBuddyNativePolicy.prepare(new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),false,null,null).has("max_tokens"),
                "an unknown catalog entry leaves the limit to the caller's fallback");
        JSONObject explicitNone = new JSONObject(nativeRequest.toString()).put("thinking",new JSONObject().put("type","disabled"));
        JSONObject nonePrepared=WorkBuddyNativePolicy.prepare(explicitNone,false,null,WorkBuddyNativePolicy.modelMetadata(catalog,"claude-opus-5.5"));
        check("disabled".equals(nonePrepared.getJSONObject("thinking").optString("type")) && !nonePrepared.getJSONObject("output_config").has("effort"), "explicit thinking none is never overridden");
        check(WorkBuddyNativePolicy.signatureRetry(prepared, 400, "{\"internal_code\":11159}", false) == null);
        check(WorkBuddyNativePolicy.signatureRetry(prepared, 400, "thinking_signature_invalid", false) == null);
        check(WorkBuddyNativePolicy.signatureRetry(prepared, 400, "other", false) == null);
        check(WorkBuddyNativePolicy.signatureRetry(prepared, 400, "thinking_signature_invalid", true) == null);
        JSONObject protectedBody = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"signature\":\"sig\",\"thinking\":\"x\"},{\"type\":\"text\",\"text\":\"You are an AI agent, powered by DeepSeek Harness.\"}]}]}");
        String protectedOriginal = protectedBody.toString();
        boolean rejected = false;
        try { WorkBuddyNativePolicy.prepare(protectedBody, true, null); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && protectedOriginal.equals(protectedBody.toString()));
        JSONObject unchangedProtected = WorkBuddyNativePolicy.prepare(protectedBody, false, null);
        check(unchangedProtected.getJSONArray("messages").toString().equals(protectedBody.getJSONArray("messages").toString()));
        JSONObject safeDsh = WorkBuddyNativePolicy.prepare(new JSONObject("{\"messages\":[{\"role\":\"system\",\"content\":\"DeepSeek Harness\"}]}"), true, null);
        check(!safeDsh.toString().contains("DeepSeek Harness"));
        JSONObject empty = WorkBuddyNativePolicy.prepare(new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\"}]}]}"),false,null);
        check("text".equals(empty.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type")));
        Map<String,String> h = WorkBuddyPolicy.nativeHeaders("workbuddy",WorkBuddyNativePolicy.URL,"personal-token",account,"session",prepared);
        check("Bearer personal-token".equals(h.get("Authorization")) && "personal".equals(h.get("X-User-Id")) && !h.containsKey("X-Domain"));
        check("2023-06-01".equals(h.get("anthropic-version")));
        check(WorkBuddyPolicy.nativeHeaders("workbuddy",WorkBuddyNativePolicy.URL,"ck_key",null,"",prepared).get("X-API-Key").equals("ck_key"));
        for (String url : new String[]{"https://evil.example/v1/messages", "https://copilot.tencent.com.evil.example/v1/messages", "http://copilot.tencent.com/v1/messages", WorkBuddyNativePolicy.URL+"?x=1", "https://copilot.tencent.com:443/v1/messages", "https://user@copilot.tencent.com/v1/messages", "https://copilot.tencent.com/v2/chat/completions"})
            check(WorkBuddyPolicy.nativeHeaders("workbuddy",url,"token",account,"",prepared).isEmpty());
        check(WorkBuddyPolicy.nativeHeaders("workbuddy-ai",WorkBuddyNativePolicy.URL,"token",account,"",prepared).isEmpty());
        check(WorkBuddyPolicy.headers("workbuddy",WorkBuddyNativePolicy.URL,"token",account,"","").isEmpty());
        System.out.println("WorkBuddyNativePolicyOfflineTest OK");
    }
}
