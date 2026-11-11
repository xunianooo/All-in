package com.gaeul.allin;

import org.json.JSONObject;

/**
 * Raccoon's response side, as the community plugin shapes it: the model a client asked
 * for is the model it reads back, and the sign-in outcome travels as a header.
 *
 * <p>The plugin rewrites only a body that already carries a {@code model} key, keeps
 * everything else byte for byte, and drops the stale {@code content-length}. Streaming
 * bodies are rewritten data line by data line, so a frame this class cannot parse is
 * passed through untouched rather than dropped.
 *
 * <p>Pure transformations: no HTTP, no credentials. The gateway must still apply them
 * (see {@code docs/raccoon-response-policy.md}); until it does, this class is unused.
 */
public final class RaccoonResponsePolicy {

    /** Header the plugin always sets: {@code kept}, {@code renewed} or {@code expired}. */
    public static final String SIGN_IN_HEADER = "X-Magpie-Sign-In";

    private RaccoonResponsePolicy() {}

    /**
     * The sign-in outcome of one exchange: a rejected request is {@code expired}, a
     * request whose token was just rotated is {@code renewed}, and everything else —
     * including a failure that never touched the credential — is {@code kept}.
     */
    public static String signInState(int status, boolean renewed, boolean ok) {
        if (status == 401) return "expired";
        return renewed && ok ? "renewed" : "kept";
    }

    /**
     * A JSON response whose {@code model} names the client's model. Returns the original
     * text whenever it is not a JSON object, carries no {@code model} key, or when the
     * caller named no model; a caller reading the result must also drop
     * {@code Content-Length}, since a shorter name shortens the body.
     */
    public static String rewriteJson(String text, String requestedModel) {
        if (text == null || text.isEmpty() || requestedModel == null || requestedModel.isEmpty()) return text;
        JSONObject body;
        try {
            body = new JSONObject(text);
        } catch (Exception notJson) {
            return text;
        }
        if (!body.has("model")) return text;
        body.put("model", requestedModel);
        return body.toString();
    }

    /**
     * One line of a server-sent event stream, rewritten when it is a {@code data:} frame
     * naming a different model. Non-data lines, {@code [DONE]}, empty payloads and
     * anything unparseable come back unchanged, as the plugin passes them through.
     */
    public static String rewriteSseLine(String line, String requestedModel) {
        if (line == null || requestedModel == null || requestedModel.isEmpty()) return line;
        if (!line.startsWith("data:")) return line;
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return line;
        JSONObject frame;
        try {
            frame = new JSONObject(data);
        } catch (Exception notJson) {
            return line;
        }
        if (!frame.has("model") || requestedModel.equals(frame.optString("model", ""))) return line;
        frame.put("model", requestedModel);
        return "data: " + frame.toString();
    }
}
