package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final Codex-specific shaping for a Responses body, applied immediately before serialization. */
public final class CodexRequestPolicy {
    private static final String[] OUTPUT_LIMIT_FIELDS = {
            "max_tokens", "max_completion_tokens", "max_output_tokens"
    };
    private static final String[] CODEX_REMOVED_OUTPUT_FIELDS = {
            "max_completion_tokens", "max_output_tokens"
    };
    private static final String[] CODEX_REMOVED_FIELDS = {
            "temperature", "top_p", "previous_response_id", "user",
            "safety_identifier", "prompt_cache_options"
    };

    private CodexRequestPolicy() { }

    /** Provenance for a client advisory or an explicitly required Guard output restriction. */
    public enum OutputLimitSource {
        CLIENT_EXPLICIT,
        CONTEXT_GUARD,
        UNATTRIBUTED
    }

    /**
     * Immutable provenance captured before final Codex shaping.
     * The client value must come from the original inbound request, before middleware and Guard.
     */
    public static final class OutputConstraints {
        public final int clientExplicitLimit;
        /** Guard cap requiring enforcement; the Gateway passes the enabled configured cap. */
        public final int guardAppliedLimit;

        private OutputConstraints(int clientLimit, int guardLimit) {
            this.clientExplicitLimit = clientLimit;
            this.guardAppliedLimit = guardLimit;
        }

        public static OutputConstraints none() {
            return new OutputConstraints(0, 0);
        }

        /** Build provenance from the ingress value and the actual ContextGuard result. */
        public static OutputConstraints fromGateway(int clientExplicitLimit, ContextGuard.Result guardResult) {
            if (clientExplicitLimit < 0) throw new IllegalArgumentException("client output limit cannot be negative");
            int applied = guardResult == null || guardResult.appliedMaxOutputTokens == null
                    ? 0 : guardResult.appliedMaxOutputTokens.intValue();
            if (applied < 0) throw new IllegalArgumentException("Guard output limit cannot be negative");
            return new OutputConstraints(clientExplicitLimit, applied);
        }

        /** Explicit constructor for callers that already captured both sources. */
        public static OutputConstraints of(int clientExplicitLimit, int guardAppliedLimit) {
            if (clientExplicitLimit < 0 || guardAppliedLimit < 0)
                throw new IllegalArgumentException("output limits cannot be negative");
            return new OutputConstraints(clientExplicitLimit, guardAppliedLimit);
        }
    }

    /** A successful, detached body ready for the caller's final serialization step. */
    public static final class Prepared {
        public final JSONObject body;
        public final int clientExplicitLimit;
        public final int guardAppliedLimit;
        /** Strictest requested limit for diagnostics; not a claim of backend enforcement. */
        public final int effectiveOutputLimit;
        public final boolean hasUnattributedTighterBodyLimit;

        private Prepared(JSONObject body, OutputConstraints constraints, int effectiveLimit, boolean unattributed) {
            this.body = body;
            this.clientExplicitLimit = constraints.clientExplicitLimit;
            this.guardAppliedLimit = constraints.guardAppliedLimit;
            this.effectiveOutputLimit = effectiveLimit;
            this.hasUnattributedTighterBodyLimit = unattributed;
        }
    }

    /** Local rejection: retaining a field does not verify backend output-cap enforcement. */
    public static final class UnpreservableOutputLimitException extends IllegalArgumentException {
        public final int clientExplicitLimit;
        public final int guardAppliedLimit;
        public final int bodyOutputLimit;
        public final boolean unattributedBodyLimit;
        public final List<OutputLimitSource> sources;

        private UnpreservableOutputLimitException(int clientLimit, int guardLimit, int bodyLimit,
                                                  boolean unattributed, List<OutputLimitSource> sources) {
            super(message(clientLimit, guardLimit, bodyLimit, unattributed));
            this.clientExplicitLimit = clientLimit;
            this.guardAppliedLimit = guardLimit;
            this.bodyOutputLimit = bodyLimit;
            this.unattributedBodyLimit = unattributed;
            this.sources = Collections.unmodifiableList(new ArrayList<OutputLimitSource>(sources));
        }

        private static String message(int client, int guard, int body, boolean unattributed) {
            StringBuilder text = new StringBuilder("Codex backend output limit enforcement is not verified; the explicitly configured Context Guard output cap cannot be guaranteed; request was not sent. Set Context Guard maxOutputTokens=0 (or disable Guard), or choose a provider with verified output caps");
            if (client > 0) text.append("; client explicit limit=").append(client);
            if (guard > 0) text.append("; Context Guard applied limit=").append(guard);
            if (body > 0) text.append("; body limit=").append(body);
            if (unattributed) text.append("; body limit provenance is unknown");
            return text.toString();
        }
    }

    /** Raised for a malformed limit instead of allowing final shaping to erase it. */
    public static final class InvalidOutputLimitException extends IllegalArgumentException {
        public InvalidOutputLimitException(String message) { super(message); }
    }

    /**
     * Prepare the final Codex Responses body with Magpie compatibility: remove
     * max_output_tokens/max_completion_tokens and leave max_tokens unchanged. Ordinary client
     * limits are advisory on this backend, not a reason to reject otherwise valid requests.
     * Only an explicitly configured Context Guard output cap requires verified enforcement.
     * The input JSONObject is never mutated.
     */
    public static Prepared prepareFinalOutbound(JSONObject request, OutputConstraints constraints) {
        if (request == null) throw new IllegalArgumentException("Codex request is required");
        if (constraints == null) constraints = OutputConstraints.none();

        JSONObject body;
        try {
            body = new JSONObject(request.toString());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Codex request cannot be copied", invalid);
        }

        int bodyLimit = 0;
        for (String field : OUTPUT_LIMIT_FIELDS) {
            if (!body.has(field)) continue;
            int value = positiveInteger(body.opt(field), field);
            if (bodyLimit == 0 || value < bodyLimit) bodyLimit = value;
        }

        int effectiveLimit = minPositive(bodyLimit,
                minPositive(constraints.clientExplicitLimit, constraints.guardAppliedLimit));
        boolean unattributed = bodyLimit > 0
                && (constraints.clientExplicitLimit <= 0 && constraints.guardAppliedLimit <= 0
                    || minPositive(constraints.clientExplicitLimit, constraints.guardAppliedLimit) > 0
                       && bodyLimit < minPositive(constraints.clientExplicitLimit, constraints.guardAppliedLimit));
        if (constraints.guardAppliedLimit > 0) {
            List<OutputLimitSource> sources = new ArrayList<OutputLimitSource>();
            if (constraints.clientExplicitLimit > 0) sources.add(OutputLimitSource.CLIENT_EXPLICIT);
            if (constraints.guardAppliedLimit > 0) sources.add(OutputLimitSource.CONTEXT_GUARD);
            if (unattributed) sources.add(OutputLimitSource.UNATTRIBUTED);
            throw new UnpreservableOutputLimitException(constraints.clientExplicitLimit,
                    constraints.guardAppliedLimit, bodyLimit, unattributed, sources);
        }
        for (String field : CODEX_REMOVED_OUTPUT_FIELDS) body.remove(field);
        for (String field : CODEX_REMOVED_FIELDS) body.remove(field);
        Object tier = body.opt("service_tier");
        if (!("priority".equals(tier) || "ultrafast".equals(tier))) body.remove("service_tier");

        body.put("stream", true);
        body.put("store", false);
        if (!body.has("instructions")) body.put("instructions", "");
        body.put("tool_choice", "auto");
        if (!body.has("parallel_tool_calls")) body.put("parallel_tool_calls", true);
        if (!body.has("text")) body.put("text", new JSONObject().put("verbosity", "medium"));
        normalizeReasoning(body);
        normalizeStringInput(body);
        return new Prepared(body, constraints, effectiveLimit, unattributed);
    }

    /**
     * Mirrors internal/provider/codex_request.go sessionHeaders: the body's prompt_cache_key names the
     * conversation in session-id/thread-id, the headers the ChatGPT backend finds a conversation's cached
     * prompt by (#1473); an explicit key wins. A body without one leaves the session the client named, and
     * a client that names none gets the session of its conversation, as Codex CLI's own thread id does.
     * Only those two hyphenated headers are produced: older underscored names never go on, relayed or not.
     */
    public static Map<String,String> sessionHeaders(JSONObject body, Map<String,String> inbound) {
        Map<String,String> result = new LinkedHashMap<String,String>();
        String key = body == null ? null : nonEmptyString(body.opt("prompt_cache_key"));
        if (key != null) return named(result, key);
        String session = inbound == null ? null : nonEmptyString(inbound.get("session-id"));
        String thread = inbound == null ? null : nonEmptyString(inbound.get("thread-id"));
        if (session != null || thread != null) {
            // the client named its conversation: a derived name never replaces it
            if (session != null) result.put("session-id", validHeader(session));
            if (thread != null) result.put("thread-id", validHeader(thread));
            return result;
        }
        String derived = body == null ? null : conversationKey(body);
        return derived == null ? result : named(result, derived);
    }

    private static Map<String,String> named(Map<String,String> headers, String session) {
        String value = validHeader(session);
        headers.put("session-id", value);
        headers.put("thread-id", value);
        return headers;
    }

    private static String nonEmptyString(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    /**
     * The conversation a keyless request belongs to, named the way Codex CLI names its thread
     * (conversationKey): the client's instructions and the input up to its first user message, hashed into
     * a UUID's shape, so every turn of a conversation gets the same name and it names nothing of the
     * client. Null when the request has no user message yet. The client's body is left untouched.
     */
    private static String conversationKey(JSONObject body) {
        Object input = body.opt("input");
        if (input instanceof String) {
            // the shape final shaping gives a plain string input
            input = new JSONArray().put(new JSONObject().put("type", "message").put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text")
                            .put("text", (String) input))));
        }
        if (!(input instanceof JSONArray)) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            return null; // no conversation name rather than one of the client's own
        }
        digest.update(body.optString("instructions", "").getBytes(StandardCharsets.UTF_8));
        JSONArray items = (JSONArray) input;
        boolean user = false;
        for (int i = 0; i < items.length(); i++) {
            Object raw = items.opt(i);
            JSONObject item = raw instanceof JSONObject ? (JSONObject) raw : null;
            if (item != null && "item_reference".equals(item.optString("type"))) continue; // dropped before the key is taken
            digest.update(canonical(item == null ? raw : item).getBytes(StandardCharsets.UTF_8));
            if (item != null && "user".equals(item.optString("role"))) {
                user = true;
                break;
            }
        }
        if (!user) return null;
        String hex = hex(digest.digest());
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    /**
     * A deterministic JSON form of one input item, with the fields upstream drops before taking the key
     * left out (an item's id, a message's or reasoning's status): a client that renumbers them from turn
     * to turn must not move the conversation's session.
     */
    private static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        canonical(value, out);
        return out.toString();
    }

    private static void canonical(Object value, StringBuilder out) {
        if (value == null || value == JSONObject.NULL) {
            out.append("null");
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String type = object.optString("type", "");
            List<String> names = new ArrayList<String>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String name = keys.next();
                if ("id".equals(name)) continue;
                if ("status".equals(name) && (type.isEmpty() || "message".equals(type) || "reasoning".equals(type))) continue;
                names.add(name);
            }
            Collections.sort(names);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                out.append(JSONObject.quote(names.get(i))).append(':');
                canonical(object.opt(names.get(i)), out);
            }
            out.append('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                canonical(array.opt(i), out);
            }
            out.append(']');
        } else if (value instanceof String) {
            out.append(JSONObject.quote((String) value));
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value.toString());
        } else {
            out.append(JSONObject.quote(value.toString()));
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) out.append(HEX[(bytes[i] >> 4) & 0xf]).append(HEX[bytes[i] & 0xf]);
        return out.toString();
    }

    private static String validHeader(String value) {
        if (value.length() > 2048 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid Codex session header");
        return value;
    }

    private static int positiveInteger(Object value, String field) {
        if (!(value instanceof Number))
            throw new InvalidOutputLimitException("Invalid Codex output limit in " + field + ": expected a positive integer");
        try {
            int parsed = new BigDecimal(value.toString()).intValueExact();
            if (parsed <= 0)
                throw new InvalidOutputLimitException("Invalid Codex output limit in " + field + ": expected a positive integer");
            return parsed;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new InvalidOutputLimitException("Invalid Codex output limit in " + field + ": expected a positive integer");
        }
    }

    private static int minPositive(int first, int second) {
        if (first <= 0) return Math.max(0, second);
        if (second <= 0) return first;
        return Math.min(first, second);
    }

    private static void normalizeReasoning(JSONObject body) {
        JSONObject reasoning = body.optJSONObject("reasoning");
        String effort = reasoning == null ? "" : reasoning.optString("effort", "");
        if ("ultra".equals(effort)) reasoning.put("effort", "max");
        if ("none".equals(effort)) {
            body.remove("include");
            return;
        }
        if (reasoning != null && !reasoning.has("summary")) reasoning.put("summary", "auto");
        JSONArray include = body.optJSONArray("include");
        if (include == null) include = new JSONArray();
        boolean found = false;
        for (int i = 0; i < include.length(); i++)
            if ("reasoning.encrypted_content".equals(include.opt(i))) found = true;
        if (!found) include.put("reasoning.encrypted_content");
        body.put("include", include);
    }

    private static void normalizeStringInput(JSONObject body) {
        Object input = body.opt("input");
        if (!(input instanceof String)) return;
        body.put("input", new JSONArray().put(new JSONObject().put("type", "message").put("role", "user")
                .put("content", new JSONArray().put(new JSONObject().put("type", "input_text")
                        .put("text", (String) input)))));
    }
}
