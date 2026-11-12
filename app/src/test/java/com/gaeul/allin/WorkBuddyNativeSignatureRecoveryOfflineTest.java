package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** The domestic WorkBuddy native signature refusal's one recovery, without any network. */
public final class WorkBuddyNativeSignatureRecoveryOfflineTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static JSONObject body(String assistantContent) throws Exception {
        return new JSONObject().put("model", "claude-opus-5.5").put("stream", true)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user").put("content", "hello"))
                        .put(new JSONObject().put("role", "assistant").put("content", new JSONArray(assistantContent))));
    }

    static void classification() {
        String[] refusals = {
                "{\"error\":{\"message\":\"Invalid `signature` in `thinking` block\",\"internal_code\":\"11159\"}}",
                "{\"internal_code\":11159}",
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"thinking_signature_invalid: replay the original thinking block and signature unchanged\"}}",
                "{\"error\":{\"message\":\"The block is bound to a different conversation\"}}",
        };
        for (String refusal : refusals)
            check(WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, refusal), "refusal recognised: " + refusal);
        for (int status : new int[]{400, 401, 403, 429, 500, 503})
            check(WorkBuddyNativeSignatureRecovery.isSignatureRefusal(status,
                    "{\"internal_code\":\"11159\"}"), "status " + status + " may carry the refusal, as upstream reads >=400");
        // A success body that merely mentions the words is not a refusal.
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(200, "{\"internal_code\":\"11159\"}"), "200 is not a refusal");
        // Neither is an unrelated 400, an unknown code, or a code that is only a prefix.
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, "{\"error\":{\"message\":\"max_tokens too large\"}}"), "unrelated 400");
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, "{\"internal_code\":\"111590\"}"), "111590 is another error");

        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, null), "no body");
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, ""), "empty body");
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(0, "11159"), "a local failure has no status");
        StringBuilder huge = new StringBuilder("11159");
        while (huge.length() < 1024 * 1024 + 16) huge.append('x');
        check(!WorkBuddyNativeSignatureRecovery.isSignatureRefusal(400, huge.toString()), "an oversized body is not parsed");
    }

    static void dropping() throws Exception {
        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "private thought").put("signature", "another-vendors"))
                .put(new JSONObject().put("type", "text").put("text", "earlier answer"))
                .put(new JSONObject().put("type", "tool_use").put("id", "toolu_1").put("name", "Read")
                        .put("input", new JSONObject().put("path", "src")));
        JSONObject original = new JSONObject().put("model", "claude-opus-5.5").put("stream", true)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user").put("content", "hello"))
                        .put(new JSONObject().put("role", "assistant").put("content", content)));
        String before = original.toString();
        JSONObject reduced = WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(original);
        check(reduced != null, "a signed thinking block is dropped");
        check(before.equals(original.toString()), "the caller's body is never mutated");
        JSONArray kept = reduced.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        check(kept.length() == 2, "only the thinking block left: " + kept);
        check(!"thinking".equals(kept.getJSONObject(0).optString("type")), "no thinking block survives");
        check("earlier answer".equals(kept.getJSONObject(0).optString("text")), "visible answer kept");
        check("Read".equals(kept.getJSONObject(1).optString("name")), "tool call kept");
        check(reduced.getJSONArray("messages").getJSONObject(0).optString("content").equals("hello"), "user text kept");

        // An unsigned block is dropped too: none of them can be told apart before sending.
        JSONObject unsigned = body("[]");
        unsigned.getJSONArray("messages").getJSONObject(1).put("content", new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "")));
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(unsigned) != null, "unsigned thinking dropped");

        // redacted_thinking is kept, as upstream keeps everything but "thinking".
        JSONObject redacted = body("[]");
        redacted.getJSONArray("messages").getJSONObject(1).put("content", new JSONArray()
                .put(new JSONObject().put("type", "redacted_thinking").put("data", "opaque"))
                .put(new JSONObject().put("type", "thinking").put("thinking", "t").put("signature", "s")));
        JSONObject redactedOut = WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(redacted);
        JSONArray redactedKept = redactedOut.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        check(redactedKept.length() == 1 && "redacted_thinking".equals(redactedKept.getJSONObject(0).optString("type")),
                "redacted block kept: " + redactedKept);

        // An assistant message left with nothing keeps one empty text block.
        JSONObject onlyThinking = body("[]");
        onlyThinking.getJSONArray("messages").getJSONObject(1).put("content", new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "t").put("signature", "s")));
        JSONArray emptied = WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(onlyThinking)
                .getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        check(emptied.length() == 1 && "text".equals(emptied.getJSONObject(0).optString("type"))
                && emptied.getJSONObject(0).optString("text").isEmpty(), "empty text block stands in: " + emptied);

        // Nothing to drop means no retry: the identical request would be refused again.
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(body("[]")) == null, "no thinking means no candidate");
        JSONObject plain = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "hello")));
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(plain) == null, "no assistant history means no candidate");
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(null) == null, "null body");
        JSONObject noMessages = new JSONObject().put("model", "claude-opus-5.5");
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(noMessages) == null, "body without messages");
        // A user message's thinking-shaped part is not the vendor's thinking block.
        JSONObject userThinking = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "thinking").put("thinking", "not history")))));
        check(WorkBuddyNativeSignatureRecovery.withoutThinkingBlocks(userThinking) == null, "user content untouched");
    }

    public static void main(String[] args) throws Exception {
        classification();
        dropping();
        System.out.println("WorkBuddyNativeSignatureRecoveryOfflineTest passed");
    }
}
