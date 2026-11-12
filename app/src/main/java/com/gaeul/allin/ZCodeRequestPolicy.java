package com.gaeul.allin;

import java.net.URI;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONArray;

/** Conservative fingerprint for official ZCode coding-plan Messages requests. */
final class ZCodeRequestPolicy {
    private ZCodeRequestPolicy() {}

    /** Normalize Chat roles on a deep copy; native Messages payloads must not use this. */
    static JSONObject chat(JSONObject input) throws Exception {
        JSONObject copy = new JSONObject(input.toString());
        Object value = copy.opt("messages");
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("messages must be an array");
        JSONArray messages = (JSONArray) value;
        for (int i = 0; i < messages.length(); i++) {
            Object item = messages.get(i);
            if (!(item instanceof JSONObject)) throw new IllegalArgumentException("messages[" + i + "] must be an object");
            JSONObject message = (JSONObject) item;
            Object role = message.opt("role");
            if (!(role instanceof String)) throw new IllegalArgumentException("messages[" + i + "].role must be a supported string");
            switch ((String) role) {
                case "developer": message.put("role", "system"); break;
                case "system": case "user": case "assistant": case "tool": break;
                default: throw new IllegalArgumentException("messages[" + i + "].role is unsupported");
            }
        }
        return copy;
    }

    static boolean isOfficialMessages(String url) {
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null || u.getRawQuery()!=null || u.getRawFragment()!=null
                    || (u.getPort() != -1 && u.getPort() != 443)) return false;
            String host = u.getHost();
            if (!"api.z.ai".equalsIgnoreCase(host) && !"open.bigmodel.cn".equalsIgnoreCase(host)) return false;
            String p = u.getRawPath();
            return "/api/coding/paas/v4/messages".equals(p)
                    || "/api/anthropic/v1/messages".equals(p);
        } catch (Exception e) { return false; }
    }

    /** Caller provides an account-specific stable device and a session derived with ZCodeGift.sessionOf.
     * Existing metadata.user_id (including opaque or null values) and malformed metadata remain untouched.
     * A clone is always returned; native message blocks and signatures are not edited.
     */
    static JSONObject marked(JSONObject request, String device, String session) throws Exception {
        JSONObject copy = new JSONObject(request.toString());
        if (device == null || device.trim().isEmpty() || session == null || session.trim().isEmpty()
                || copy.optJSONArray("messages") == null) return copy;
        Object existing = copy.opt("metadata");
        if (copy.has("metadata") && !(existing instanceof JSONObject)) return copy;
        JSONObject metadata = existing instanceof JSONObject ? (JSONObject) existing : new JSONObject();
        if (metadata.has("user_id")) return copy;
        JSONObject user = new JSONObject();
        user.put("device_id", device);
        user.put("account_uuid", "");
        user.put("session_id", session);
        metadata.put("user_id", user.toString());
        copy.put("metadata", metadata);
        return copy;
    }
}
