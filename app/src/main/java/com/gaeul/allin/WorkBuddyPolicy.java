package com.gaeul.allin;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Request headers for the two fixed WorkBuddy chat endpoints. */
public final class WorkBuddyPolicy {
    private static final byte[] SALT = new byte[32];
    static { new SecureRandom().nextBytes(SALT); }
    private WorkBuddyPolicy() {}

    public static Map<String, String> headers(String provider, String url, String credential,
                                               JSONObject account, String explicitSession,
                                               String logicalRequestId) {
        return headers(provider, url, credential, account, explicitSession, logicalRequestId, null);
    }

    /** The payload must be the final outbound body. A trustworthy explicit session is required:
     * without one we use a random turn ID, avoiding cross-client history collisions. IDs last
     * only for this process; models deliberately do not participate in the turn identity. */
    public static Map<String, String> headers(String provider, String url, String credential,
                                               JSONObject account, String explicitSession,
                                               String logicalRequestId, JSONObject outboundPayload) {
        boolean ai = "workbuddy-ai".equals(provider);
        if (!ai && !"workbuddy".equals(provider)) return Collections.emptyMap();
        try {
            URI u = new URI(url);
            String host = ai ? "www.workbuddy.ai" : "copilot.tencent.com";
            if (!"https".equalsIgnoreCase(u.getScheme()) || !host.equalsIgnoreCase(u.getHost())
                    || u.getRawUserInfo() != null || u.getPort() != -1 || u.getRawQuery() != null
                    || u.getRawFragment() != null || !"/v2/chat/completions".equals(u.getRawPath()))
                return Collections.emptyMap();
        } catch (Exception e) { return Collections.emptyMap(); }
        String key = credential == null ? "" : credential.trim();
        if (!safe(key)) return Collections.emptyMap();
        String uid = value(account, "uid");
        String domain = value(account, "domain");
        boolean apiKey = ai || key.startsWith("ck_") || key.startsWith("pt_");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + key);
        if (apiKey) {
            h.put("X-API-Key", key);
            if (uid.isEmpty()) uid = "anonymous_" + key.substring(Math.max(0, key.length() - 8));
        } else if (!domain.isEmpty()) h.put("X-Domain", domain);
        if (!uid.isEmpty()) h.put("X-User-Id", uid);
        h.put("User-Agent", "WorkBuddy/5.9.3 " + (ai ? "workbuddy-ai" : "WorkBuddy") + "/5.9.3 CLI/2.165.0");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("X-Product", "SaaS");
        h.put("X-IDE-Type", "WorkBuddy");
        h.put("X-IDE-Name", "WorkBuddy");
        h.put("X-IDE-Version", "5.9.3");
        h.put("X-Agent-Intent", "craft");
        h.put("X-Agent-Purpose", "conversation");
        h.put("X-Agent-Type", "main");
        h.put("X-Agent-Runtime", "local-agent");
        h.put("Accept", "application/json");
        h.put("Accept-Encoding", "identity");
        h.put("X-Stainless-Retry-Count", "0");
        h.put("X-Stainless-Timeout", "1200");
        h.put("X-Stainless-Lang", "js");
        h.put("X-Stainless-Package-Version", "6.25.0");
        h.put("X-Stainless-OS", "Linux");
        h.put("X-Stainless-Arch", "arm64");
        h.put("X-Stainless-Runtime", "node");
        h.put("X-Stainless-Runtime-Version", "v22.21.1");
        String session = explicitSession == null ? "" : explicitSession.trim();
        // Account identity must survive credential refresh and be scoped for every session,
        // including UUIDs. Without a trusted account/session, do not correlate requests.
        String stableUid = value(account, "uid");
        String accountId = value(account, "id");
        boolean stableKey = key.startsWith("ck_") || key.startsWith("pt_")
                || (ai && (account == null || !"oauth".equals(account.optString("type"))));
        String identity = !stableUid.isEmpty() ? "uid:" + stableUid : !accountId.isEmpty() ? "id:" + accountId : stableKey ? "key:" + key : "";
        String id = !identity.isEmpty() && !session.isEmpty() && session.length() <= 1024 && safe(session)
                ? scoped("conversation", provider, identity, value(account,"enterpriseId"), domain, session)
                : UUID.randomUUID().toString();
        h.put("X-Conversation-ID", id);
        String turn = turnId(provider, key, account, session, outboundPayload);
        h.put("X-Conversation-Request-ID", turn);
        String message = UUID.randomUUID().toString().replace("-", "");
        h.put("X-Conversation-Message-ID", message);
        h.put("X-Request-ID", message);
        return h;
    }

    private static String turnId(String provider, String key, JSONObject account,
                                 String session, JSONObject payload) {
        // Token refresh must not change identity. Without a stable account identity, only
        // recognizable API keys can serve as an account identity; unknown OAuth falls back.
        String uid = value(account, "uid"), enterprise = value(account, "enterpriseId");
        String domain = value(account, "domain");
        boolean stableAccount = !uid.isEmpty();
        boolean stableKey = key.startsWith("ck_") || key.startsWith("pt_")
                || ("workbuddy-ai".equals(provider) && (account == null || !"oauth".equals(account.optString("type"))));
        String identity = stableAccount ? uid : stableKey ? key : "";
        if (session.isEmpty() || session.length() > 1024 || !safe(session) || identity.isEmpty() || payload == null)
            return randomId();
        JSONArray messages = payload.optJSONArray("messages");
        if (messages == null || messages.length() > 10000) return randomId();
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) return randomId();
            if (!"user".equals(message.optString("role", ""))) continue;
            if (answeredBefore(messages, i)) continue;
            Object content = message.opt("content");
            StringBuilder signature = new StringBuilder();
            if (!userContent(content, signature)) return randomId();
            return scoped("turn", provider, stableAccount ? "account" : "key", identity,
                    enterprise, domain, session, Integer.toString(i), signature.toString()).replace("-", "");
        }
        return randomId();
    }

    // Match upstream's continuation detection: text accompanying a tool result,
    // and user messages following tool replies/calls, still belong to the prior turn.
    private static boolean answeredBefore(JSONArray messages, int index) {
        if (hasBlock(messages.optJSONObject(index), "tool_result")) return true;
        int j = index - 1;
        while (j >= 0) {
            JSONObject previous = messages.optJSONObject(j);
            if (previous == null || !"system".equals(previous.optString("role", ""))) break;
            j--;
        }
        JSONObject previous = j >= 0 ? messages.optJSONObject(j) : null;
        if (previous == null) return false;
        String role = previous.optString("role", "");
        if ("tool".equals(role)) return true;
        if ("user".equals(role) && hasBlock(previous, "tool_result")) return true;
        if (!"assistant".equals(role)) return false;
        JSONArray calls = previous.optJSONArray("tool_calls");
        return (calls != null && calls.length() > 0) || hasBlock(previous, "tool_use");
    }

    private static boolean hasBlock(JSONObject message, String type) {
        JSONArray parts = message == null ? null : message.optJSONArray("content");
        if (parts == null || parts.length() > 1024) return false;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String actual = part.optString("type", "");
            if (type.equals(actual) || ("tool_result".equals(type) && "tool-result".equals(actual))) return true;
        }
        return false;
    }

    private static String randomId() { return UUID.randomUUID().toString().replace("-", ""); }

    // Plain adjacent text blocks have one length-prefixed representation, independent
    // of string versus block format. Metadata and non-text blocks retain their order.
    private static boolean userContent(Object content, StringBuilder out) {
        if (content instanceof String) {
            String text = (String) content;
            return !text.isEmpty() && canonical(text, out, 0);
        }
        if (!(content instanceof JSONArray)) return false;
        JSONArray parts = (JSONArray) content;
        if (parts.length() > 1024) return false;
        StringBuilder text = new StringBuilder();
        boolean found = false;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) return false;
            String type = part.optString("type", "");
            if ("tool_result".equals(type) || "tool-result".equals(type)) continue;
            boolean isText = type.isEmpty() || "text".equals(type) || "input_text".equals(type);
            if (isText) {
                Object value = part.opt("text");
                if (!(value instanceof String)) return false;
                if (part.length() <= (part.has("type") ? 2 : 1)) {
                    if (text.length() + ((String) value).length() > 1048576) return false;
                    text.append((String) value);
                    continue;
                }
            } else if (!"image".equals(type) && !"image_url".equals(type)
                    && !"input_image".equals(type) && !"document".equals(type)
                    && !"file".equals(type)) return false;
            if (text.length() > 0) {
                if (!canonical(text.toString(), out, 0)) return false;
                text.setLength(0);
                found = true;
            }
            if (!canonical(part, out, 0)) return false;
            found = true;
        }
        if (text.length() > 0) {
            if (!canonical(text.toString(), out, 0)) return false;
            found = true;
        }
        return found;
    }

    private static boolean canonical(Object value, StringBuilder out, int depth) {
        if (depth > 16 || out.length() > 1048576) return false;
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) { keys.add(it.next()); if (keys.size() > 1024) return false; }
            Collections.sort(keys);
            out.append('{');
            for (String key : keys) {
                if (!canonical(key, out, depth + 1) || !canonical(obj.opt(key), out, depth + 1)) return false;
            }
            out.append('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 1024) return false;
            out.append('[');
            for (int i = 0; i < array.length(); i++) if (!canonical(array.opt(i), out, depth + 1)) return false;
            out.append(']');
        } else if (value instanceof String) {
            String text = (String) value;
            if (text.length() > 1048576 - out.length()) return false;
            out.append('s').append(text.length()).append(':').append(text);
        } else if (value == null || value == JSONObject.NULL) out.append('n');
        else if (value instanceof Number || value instanceof Boolean) out.append('v').append(value.toString()).append(';');
        else return false;
        return out.length() <= 1048576;
    }

    private static boolean safe(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 32 || s.charAt(i) == 127) return false;
        return true;
    }
    private static String value(JSONObject obj, String name) {
        String s = obj == null ? "" : obj.optString(name, "").trim();
        return safe(s) ? s : "";
    }
    private static String scoped(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(SALT);
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte)(bytes.length >>> 24)); digest.update((byte)(bytes.length >>> 16));
                digest.update((byte)(bytes.length >>> 8)); digest.update((byte)bytes.length); digest.update(bytes);
            }
            byte[] b = digest.digest();
            b[6] = (byte)((b[6] & 15) | 0x40); b[8] = (byte)((b[8] & 63) | 0x80);
            long a = 0, c = 0;
            for (int i = 0; i < 8; i++) a = (a << 8) | (b[i] & 255L);
            for (int i = 8; i < 16; i++) c = (c << 8) | (b[i] & 255L);
            return new UUID(a, c).toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
