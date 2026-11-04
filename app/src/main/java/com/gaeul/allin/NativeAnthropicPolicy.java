package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Safety boundary for native Anthropic messages. */
final class NativeAnthropicPolicy {
    private static final String PROTECTED = "Native request contains protected content that cannot be safely processed";
    private NativeAnthropicPolicy() {}

    static boolean requiresNative(JSONObject original) {
        JSONArray messages = original == null ? null : original.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && hasProtected(message.opt("content"))) return true;
        }
        return false;
    }

    private static boolean hasProtected(Object content) {
        if (!(content instanceof JSONArray)) return false;
        JSONArray blocks = (JSONArray) content;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) return true;
            String type = b.optString("type", "");
            if (!known(type)) return true;
            if ("tool_result".equals(type) && hasProtected(b.opt("content"))) return true;
        }
        return false;
    }

    private static boolean known(String type) {
        return "text".equals(type) || "tool_use".equals(type) || "tool_result".equals(type) || "image".equals(type);
    }

    static String maskRequest(String raw, PrivacyMasker.Session session, PrivacyMasker.Config config,
                              JSONObject middleware) throws Exception {
        JSONObject request = new JSONObject(raw);
        JSONObject before = new JSONObject(raw);
        JSONArray messages = request.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null) checkBlocks(m.opt("content"), config, middleware);
        }
        PrivacyMasker.Session working = session == null ? new PrivacyMasker.Session() : session.fork();
        maskObject(request, working, config, "root");
        int last = -1;
        JSONArray oldMessages = before.optJSONArray("messages");
        for (int i = 0; oldMessages != null && i < oldMessages.length(); i++) {
            JSONObject m = oldMessages.optJSONObject(i);
            if (m != null && hasProtected(m.opt("content"))) last = i;
        }
        if (last >= 0) {
            for (String key : new String[]{"system", "tools"})
                if (!GatewayServer.jsonEquivalent(before.opt(key), request.opt(key)))
                    throw new IllegalArgumentException(PROTECTED);
            JSONArray newMessages = request.optJSONArray("messages");
            for (int i = 0; i <= last; i++)
                if (!GatewayServer.jsonEquivalent(oldMessages.opt(i), newMessages.opt(i)))
                    throw new IllegalArgumentException(PROTECTED);
        }
        if (session != null) session.replaceFrom(working);
        return request.toString();
    }

    private static void checkBlocks(Object content, PrivacyMasker.Config config, JSONObject middleware) {
        if (!(content instanceof JSONArray)) return;
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b == null) throw new IllegalArgumentException(PROTECTED);
            String type = b.optString("type", "");
            if (!known(type)) {
                String serialized = b.toString();
                if (PrivacyMasker.checkMatch(serialized, config))
                    throw new IllegalArgumentException(PROTECTED);
                if ("thinking".equals(type)) {
                    if (Middleware.hitWord(b.optString("thinking", ""), middleware) != null)
                        throw new IllegalArgumentException(PROTECTED);
                } else if (hasWordRules(middleware)) {
                    throw new IllegalArgumentException(PROTECTED);
                }
            } else if ("tool_result".equals(type)) {
                checkBlocks(b.opt("content"), config, middleware);
            }
        }
    }

    private static boolean hasWordRules(JSONObject middleware) {
        if (!Middleware.wordGuardEnabled(middleware)) return false;
        JSONObject g = middleware.optJSONObject("wordGuard");
        for (String name : new String[]{"words", "patterns"}) {
            JSONArray a = g.optJSONArray(name);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                String s = o == null ? a.optString(i, "") : o.optString("pattern", "");
                if (!s.trim().isEmpty()) return true;
            }
        }
        return false;
    }

    // Context determines which fields are protocol, while tool input is entirely user data.
    private static void maskObject(JSONObject o, PrivacyMasker.Session session,
                                   PrivacyMasker.Config config, String context) throws Exception {
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = o.opt(key);
            if ("data".equals(context)) rejectPrivacyMatch(key, config);
            if (("tool".equals(context) && "name".equals(key))
                    || ("block".equals(context) && "tool_use".equals(o.optString("type")) && "name".equals(key))
                    || ("block".equals(context) && "type".equals(key))) {
                rejectPrivacyMatch(String.valueOf(v), config);
                continue;
            }
            if (("root".equals(context) && ("model".equals(key) || "id".equals(key)))
                    || ("message".equals(context) && "role".equals(key))
                    || ("block".equals(context) && ("type".equals(key) || "id".equals(key)
                        || "tool_use_id".equals(key) || "signature".equals(key)))) continue;
            if ("block".equals(context) && "image".equals(o.optString("type")) && "source".equals(key)) continue;
            String child = "data";
            if ("root".equals(context) && "system".equals(key) && v instanceof JSONArray) child = "blocks";
            else if ("root".equals(context) && "messages".equals(key)) child = "messages";
            else if ("root".equals(context) && "tools".equals(key)) child = "tools";
            else if ("tool".equals(context) && "input_schema".equals(key)) {
                maskSchema(v, session, config);
                continue;
            }
            else if ("schema".equals(context) && "properties".equals(key)) child = "properties";
            else if ("properties".equals(context)) child = "schema";
            else if ("schema".equals(context) && ("items".equals(key) || "additionalProperties".equals(key))) child = "schema";
            else if ("message".equals(context) && "content".equals(key) && v instanceof JSONArray) child = "blocks";
            else if ("block".equals(context) && "tool_result".equals(o.optString("type")) && "content".equals(key)) {
                if (v instanceof JSONArray) child = "blocks";
                else if (v instanceof String) {
                    o.put(key, maskToolResultString((String) v, session, config));
                    continue;
                }
            }
            if ("schema".equals(context) && "type".equals(key) && v instanceof String) continue;
            if (v instanceof String) o.put(key, PrivacyMasker.maskText((String) v, session, config));
            else if ("data".equals(context) && v instanceof Number) rejectPrivacyMatch(String.valueOf(v), config);
            else maskValue(v, session, config, child);
        }
    }

    private static void rejectPrivacyMatch(String value, PrivacyMasker.Config config) {
        if (PrivacyMasker.checkMatch(value, config))
            throw new IllegalArgumentException(PROTECTED);
    }

    // Schema structure is immutable; only annotations with user-facing content are masked.
    private static void maskSchema(Object node, PrivacyMasker.Session session,
                                   PrivacyMasker.Config config) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next(); Object v = o.opt(key);
                rejectPrivacyMatch(key, config);
                if ("description".equals(key) || "default".equals(key)
                        || "example".equals(key) || "examples".equals(key)) {
                    if (v instanceof String) o.put(key, PrivacyMasker.maskText((String) v, session, config));
                    else maskValue(v, session, config, "data");
                } else if ("properties".equals(key) || "$defs".equals(key) || "definitions".equals(key)
                        || "patternProperties".equals(key)) {
                    if (v instanceof JSONObject) {
                        JSONObject entries = (JSONObject) v;
                        Iterator<String> names = entries.keys();
                        while (names.hasNext()) {
                            String name = names.next(); rejectPrivacyMatch(name, config);
                            maskSchema(entries.opt(name), session, config);
                        }
                    } else checkImmutable(v, config);
                } else if ("items".equals(key) || "additionalProperties".equals(key)
                        || "allOf".equals(key) || "anyOf".equals(key) || "oneOf".equals(key)
                        || "not".equals(key) || "prefixItems".equals(key)) {
                    maskSchema(v, session, config);
                } else checkImmutable(v, config);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) maskSchema(a.opt(i), session, config);
        } else checkImmutable(node, config);
    }

    private static void checkImmutable(Object value, PrivacyMasker.Config config) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); rejectPrivacyMatch(k, config); checkImmutable(o.opt(k), config); }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) checkImmutable(a.opt(i), config);
        } else if (value instanceof String || value instanceof Number) rejectPrivacyMatch(String.valueOf(value), config);
    }

    private static void maskValue(Object value, PrivacyMasker.Session session,
                                  PrivacyMasker.Config config, String context) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if ("block".equals(context) && !known(object.optString("type", ""))) return;
            maskObject(object, session, config, context);
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            String child = "messages".equals(context) ? "message" : "blocks".equals(context) ? "block"
                    : "tools".equals(context) ? "tool" : "data";
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof String) a.put(i, PrivacyMasker.maskText((String) v, session, config));
                else if ("data".equals(child) && v instanceof Number) rejectPrivacyMatch(String.valueOf(v), config);
                else maskValue(v, session, config, child);
            }
        }
    }

    private static String maskToolResultString(String text, PrivacyMasker.Session session,
                                               PrivacyMasker.Config config) throws Exception {
        String trimmed = text.trim();
        Object parsed = null;
        try {
            if (trimmed.startsWith("{")) parsed = new JSONObject(text);
            else if (trimmed.startsWith("[")) parsed = new JSONArray(text);
        } catch (Exception invalidJson) {
            // Non-JSON tool output is still ordinary text.
        }
        if (parsed == null) return PrivacyMasker.maskText(text, session, config);
        maskValue(parsed, session, config, "data");
        return parsed.toString();
    }

    static void checkNativeRequired(JSONObject original, JSONObject baseline, JSONObject current,
                                    boolean directAllowed) throws Exception {
        if (!requiresNative(original)) return;
        if (!directAllowed || baseline == null || current == null) throw new IllegalArgumentException(PROTECTED);
        for (String key : new String[]{"max_tokens", "max_completion_tokens"}) {
            if (baseline.has(key)) {
                if (!current.has(key) || current.isNull(key)) throw new IllegalArgumentException(PROTECTED);
                Object before = baseline.opt(key), after = current.opt(key);
                if (before instanceof Number && after instanceof Number) {
                    double oldLimit = ((Number) before).doubleValue();
                    double newLimit = ((Number) after).doubleValue();
                    if (oldLimit > 0 && (newLimit <= 0 || newLimit > oldLimit))
                        throw new IllegalArgumentException(PROTECTED);
                } else if (!String.valueOf(before).equals(String.valueOf(after))) {
                    throw new IllegalArgumentException(PROTECTED);
                }
            }
        }
        if (GatewayServer.nativeAnthropicRequest(original, baseline, current,
                current.optString("model", ""), current.optBoolean("stream", false)) == null)
            throw new IllegalArgumentException(PROTECTED);
    }

    static String unmaskResponse(String raw, PrivacyMasker.Session session) throws Exception {
        if (session == null || session.placeholderToReal.isEmpty()) return raw;
        JSONObject response = new JSONObject(raw);
        // Only content text and tool inputs are known to carry user-visible values.
        JSONArray blocks = response.optJSONArray("content");
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type", "");
            if ("text".equals(type) && b.opt("text") instanceof String)
                b.put("text", PrivacyMasker.unmaskText(b.optString("text"), session));
            if ("tool_use".equals(type)) transformInput(b.opt("input"), session);
        }
        return response.toString();
    }

    private static void transformInput(Object node, PrivacyMasker.Session session) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next(); Object v = o.opt(k);
                if (v instanceof String) o.put(k, PrivacyMasker.unmaskText((String) v, session));
                else transformInput(v, session);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof String) a.put(i, PrivacyMasker.unmaskText((String) v, session));
                else transformInput(v, session);
            }
        }
    }
}
