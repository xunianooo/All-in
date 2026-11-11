package com.gaeul.allin;

import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Safe, bounded signature-rejection retry rules for WorkBuddy. */
public final class WorkBuddySignatureRetryPolicy {
    private static final Pattern SIGNATURE_REJECTION = Pattern.compile(
            "thinking_signature_invalid|invalid\\s+`?signature`?\\s+in\\s+`?thinking|block is bound to a different conversation|[\\\"']internal_code[\\\"']\\s*:\\s*[\\\"']?11159(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private WorkBuddySignatureRetryPolicy() {}

    public enum StopReason {
        NOT_SIGNATURE_REJECTION,
        HTTP_STATUS_NOT_RETRYABLE,
        RETRY_DISABLED,
        ALREADY_RETRIED,
        OUTCOME_UNKNOWN,
        ENTERPRISE_SKIPPED,
        PROTECTED_HISTORY,
        RETRY_WOULD_BE_LOSSY,
        NOTHING_SAFE_TO_RETRY
    }

    public static final class Decision {
        public final boolean retry;
        /** A deep copy of the retry body when retry=true; otherwise the original body copy. */
        public final JSONObject body;
        public final StopReason reason;
        private Decision(boolean retry, JSONObject body, StopReason reason) {
            this.retry = retry; this.body = body; this.reason = reason;
        }
    }

    /** Request-scoped one-retry budget; create one session per logical WorkBuddy request. */
    public static final class Session {
        private final boolean retryEnabled;
        private final boolean enterpriseAccount;
        private boolean retryCandidateIssued;
        private Session(boolean retryEnabled, boolean enterpriseAccount) {
            this.retryEnabled = retryEnabled; this.enterpriseAccount = enterpriseAccount;
        }
        /** Issues at most one retry candidate, even if the caller examines multiple responses. */
        public synchronized Decision decide(JSONObject sent, int status, String errorBody, boolean outcomeUnknown) {
            Decision decision = evaluate(sent, status, errorBody, retryEnabled, retryCandidateIssued,
                    outcomeUnknown, enterpriseAccount);
            if (decision.retry) retryCandidateIssued = true;
            return decision;
        }
        public synchronized boolean retryCandidateIssued() { return retryCandidateIssued; }
    }

    public static Session newSession(boolean retryEnabled, boolean enterpriseAccount) {
        return new Session(retryEnabled, enterpriseAccount);
    }

    /** Retry only a definitive first 400 refusal and only after explicit gateway opt-in. */
    public static Decision decide(JSONObject sent, int status, String errorBody,
            boolean retryEnabled, boolean alreadyRetried, boolean outcomeUnknown,
            boolean enterpriseAccount) {
        return evaluate(sent, status, errorBody, retryEnabled, alreadyRetried, outcomeUnknown, enterpriseAccount);
    }

    private static Decision evaluate(JSONObject sent, int status, String errorBody,
            boolean retryEnabled, boolean alreadyRetried, boolean outcomeUnknown,
            boolean enterpriseAccount) {
        if (enterpriseAccount) return stop(sent, StopReason.ENTERPRISE_SKIPPED);
        if (outcomeUnknown) return stop(sent, StopReason.OUTCOME_UNKNOWN);
        if (alreadyRetried) return stop(sent, StopReason.ALREADY_RETRIED);
        if (status != 400) return stop(sent, StopReason.HTTP_STATUS_NOT_RETRYABLE);
        if (errorBody == null || !SIGNATURE_REJECTION.matcher(errorBody).find())
            return stop(sent, StopReason.NOT_SIGNATURE_REJECTION);
        if (!retryEnabled) return stop(sent, StopReason.RETRY_DISABLED);
        if (containsProtectedHistory(sent)) return stop(sent, StopReason.PROTECTED_HISTORY);
        JSONObject candidate = emptyUnsignedThinkingOnly(sent);
        return candidate == null
                ? stop(sent, StopReason.RETRY_WOULD_BE_LOSSY)
                : new Decision(true, candidate, null);
    }

    private static Decision stop(JSONObject body, StopReason reason) {
        return new Decision(false, body == null ? null : new JSONObject(body.toString()), reason);
    }

    /**
     * An empty unsigned thinking block carries no text to preserve. Only that exact
     * shape may be removed. Nonempty reasoning, opaque fields, signed blocks and
     * redacted blocks make the retry lossy or protected and stop it.
     */
    private static JSONObject emptyUnsignedThinkingOnly(JSONObject source) {
        if (source == null) return null;
        JSONObject candidate = new JSONObject(source.toString());
        JSONArray messages = candidate.optJSONArray("messages");
        boolean changed = false;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            JSONArray kept = new JSONArray();
            boolean removed = false;
            for (int j = 0; j < content.length(); j++) {
                Object partValue = content.opt(j);
                JSONObject part = partValue instanceof JSONObject ? (JSONObject) partValue : null;
                if (part == null || !"thinking".equals(part.optString("type", ""))) {
                    kept.put(partValue); continue;
                }
                if (part.has("signature")) return null;
                if (!(part.opt("thinking") instanceof String) || !part.optString("thinking", "").isEmpty()
                        || part.length() != 2 || !part.has("type") || !part.has("thinking")) return null;
                removed = true;
            }
            if (removed) {
                if (kept.length() == 0) kept.put(new JSONObject().put("type", "text").put("text", ""));
                message.put("content", kept);
                changed = true;
            }
        }
        return changed ? candidate : null;
    }

    private static boolean containsProtectedHistory(JSONObject body) {
        JSONArray messages = body == null ? null : body.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            if (containsProtected(message)) return true;
        }
        return false;
    }

    private static boolean containsProtected(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("signature") || "redacted_thinking".equals(object.optString("type", ""))) return true;
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();)
                if (containsProtected(object.opt(keys.next()))) return true;
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsProtected(array.opt(i))) return true;
        }
        return false;
    }
}
