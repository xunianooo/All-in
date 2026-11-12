package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.Iterator;

/** Separate total request lifetime from the window for starting safe candidate attempts.
 * Read/connect idle timeouts remain the independent ContextGuard settings. */
final class RequestTiming {
    static final long DEFAULT_REQUEST_MS = 15 * 60 * 1000L;
    static final long MAX_REQUEST_MS = 30 * 60 * 1000L;
    static final long DEFAULT_ATTEMPT_MS = 60 * 1000L;
    static final long MAX_ATTEMPT_MS = 5 * 60 * 1000L;
    private RequestTiming() {}
    static long requestMillis(JSONObject failover) {
        Long explicit = positive(failover, "requestBudgetMs");
        if (explicit != null) return clamp(explicit, MAX_REQUEST_MS);
        // Old explicit attemptBudgetMs governed BOTH deadlines. Preserve that user's
        // effective total limit until they explicitly provide the new independent field.
        if (failover != null && failover.has("attemptBudgetMs"))
            return clamp(failover.optLong("attemptBudgetMs", DEFAULT_ATTEMPT_MS), MAX_ATTEMPT_MS);
        return DEFAULT_REQUEST_MS;
    }
    static long attemptMillis(JSONObject failover) {
        return failover == null ? DEFAULT_ATTEMPT_MS
                : clamp(failover.optLong("attemptBudgetMs", DEFAULT_ATTEMPT_MS), MAX_ATTEMPT_MS);
    }
    private static long clamp(long value, long max) { return Math.max(1000L, Math.min(max, value)); }
    private static Long positive(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        // Accept decimal integer strings used by older imported configurations.
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try {
            String text = value.toString();
            if (!text.matches("[0-9]+")) return null;
            long number = Long.parseLong(text);
            return number > 0 ? number : null;
        } catch (RuntimeException invalid) { return null; }
    }
    static JSONObject snapshot(JSONObject stored) {
        JSONObject out = stored == null ? new JSONObject() : new JSONObject(stored.toString());
        out.remove("resting"); // Runtime status is never persisted as configuration.
        if (!out.has("enabled")) out.put("enabled", true);
        if (!out.has("maxTargets")) out.put("maxTargets", 3);
        if (!out.has("targets")) out.put("targets", new com.gaeul.allin.json.JSONArray());
        return out;
    }
    static JSONObject merge(JSONObject stored, JSONObject patch) {
        JSONObject out = snapshot(stored);
        if (patch != null) {
            for (String key : new String[]{"requestBudgetMs", "attemptBudgetMs"})
                if (patch.has(key) && positive(patch, key) == null)
                    throw new IllegalArgumentException(key + " must be a positive integer in milliseconds");
            Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"resting".equals(key)) out.put(key, patch.opt(key));
            }
        }
        return out;
    }
}
