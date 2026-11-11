package com.gaeul.allin;

import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The domestic WorkBuddy Claude Messages relay's one recovery: a thinking block whose signature
 * WorkBuddy's own signing did not make is refused (internal code 11159, or 11133 naming
 * {@code thinking_signature_invalid}); the same history asked again without any thinking block is
 * accepted.
 *
 * <p>Upstream's rule (opencode-workbuddy-auth {@code index.mjs}:3926-3931, {@code :723-740},
 * {@code :4123-4128}): only a native Messages exchange, only for status {@code >= 400}, only when
 * the refusal says the signature is not WorkBuddy's, and only when the body actually carries a
 * thinking block to drop — a body without one would be the identical request again, which is why
 * {@link #withoutThinkingBlocks(JSONObject)} returns {@code null} instead of the same body.
 *
 * <p>Pure transformations: no HTTP, no credentials, no retry state. Every signed thinking block is
 * dropped, because none of them can be told apart from a foreign one before sending; visible text,
 * tool calls, tool results and {@code redacted_thinking} blocks are kept byte for byte.
 */
public final class WorkBuddyNativeSignatureRecovery {

    /** The words WorkBuddy uses for a thinking signature it did not make. */
    private static final Pattern BAD_SIGNATURE = Pattern.compile(
            "thinking_signature_invalid|invalid\\s+`?signature`?\\s+in\\s+`?thinking"
                    + "|block is bound to a different conversation"
                    + "|[\\\"']internal_code[\\\"']\\s*:\\s*[\\\"']?11159(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private WorkBuddyNativeSignatureRecovery() {}

    /**
     * Whether a definitive HTTP refusal is WorkBuddy rejecting a thinking signature. A locally
     * thrown transport failure has no status and is never a signature refusal, so an exchange whose
     * outcome is unknown can never satisfy this.
     */
    public static boolean isSignatureRefusal(int status, String errorBody) {
        if (status < 400 || status > 599) return false;
        if (errorBody == null || errorBody.isEmpty() || errorBody.length() > 1024 * 1024) return false;
        return BAD_SIGNATURE.matcher(errorBody).find();
    }

    /**
     * The same Messages body without any {@code thinking} block, or {@code null} when there is none
     * to drop. An assistant message left with no content keeps one empty text block, as upstream
     * leaves it.
     */
    public static JSONObject withoutThinkingBlocks(JSONObject body) {
        if (body == null) return null;
        JSONObject candidate;
        try {
            candidate = new JSONObject(body.toString());
        } catch (Exception copyFailure) {
            return null;
        }
        JSONArray messages = candidate.optJSONArray("messages");
        int dropped = 0;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role", ""))) continue;
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            JSONArray kept = new JSONArray();
            boolean removed = false;
            for (int j = 0; j < content.length(); j++) {
                Object part = content.opt(j);
                JSONObject block = part instanceof JSONObject ? (JSONObject) part : null;
                if (block != null && "thinking".equals(block.optString("type", ""))) {
                    removed = true;
                    dropped++;
                } else kept.put(part);
            }
            if (!removed) continue;
            if (kept.length() == 0) kept.put(new JSONObject().put("type", "text").put("text", ""));
            message.put("content", kept);
        }
        return dropped == 0 ? null : candidate;
    }
}
