package com.gaeul.allin;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded, structured interpretation of Qoder failures and request limits. */
public final class QoderFailurePolicy {
    private QoderFailurePolicy() {}
    private static final Set<String> QUOTA = new HashSet<>(Arrays.asList("110","112","113","114","115","116","117","118","119","122"));
    private static final Set<String> REQUEST = new HashSet<>(Arrays.asList("406","416","430","80411","80412","100400","100401","100403"));
    public static final int MAX_ERROR_BYTES = 16384;
    public static final int DEFAULT_MAX_TOKENS = 32000;

    public static int payloadLimit(JSONObject payload) {
        Integer a = limit(payload, "max_tokens"), b = limit(payload, "max_completion_tokens");
        int result = a == null ? (b == null ? DEFAULT_MAX_TOKENS : b) : (b == null ? a : Math.min(a, b));
        if (result < 64) throw new IllegalArgumentException("Qoder requires max_tokens >= 64; configured limit cannot be honored");
        return result;
    }
    private static Integer limit(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        Object value = o.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
        try {
            int n = new BigDecimal(value.toString()).intValueExact();
            if (n <= 0) throw new IllegalArgumentException("Invalid " + key);
            return n;
        } catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Invalid " + key); }
    }
    private static JSONObject object(Object v) {
        if (v instanceof JSONObject) return (JSONObject)v;
        if (!(v instanceof String) || ((String)v).length() > MAX_ERROR_BYTES) return null;
        try { return new JSONObject(((String)v).trim()); } catch (Exception e) { return null; }
    }
    private static String code(JSONObject o) {
        if (o == null) return "";
        Object v = o.opt("code");
        String s = v instanceof String || v instanceof Number ? String.valueOf(v) : "";
        return s.matches("[0-9]{1,9}") ? s : "";
    }
    private static boolean authoritative(String c) { return "105".equals(c) || QUOTA.contains(c) || REQUEST.contains(c); }
    public static JSONObject failure(int upstreamStatus, String body) throws Exception {
        JSONObject root = object(body), cur = root, queue = null;
        String found = "";
        // Only known object wrappers; never inspect arbitrary text for digits.
        for (int depth = 0; cur != null && depth < 5; depth++) {
            String c = code(cur);
            if (!c.isEmpty() && (found.isEmpty() || "401".equals(found) || "403".equals(found))) found = c;
            JSONObject q = object(cur.opt("queue"));
            if (q != null) queue = q;
            if (cur.optBoolean("isQueued", false) || (cur.has("serviceAvailable") && Boolean.FALSE.equals(cur.opt("serviceAvailable")))) queue = cur;
            if (authoritative(found)) break;
            JSONObject next = object(cur.opt("body"));
            if (next == null) next = object(cur.opt("message"));
            if (next == null) next = object(cur.opt("details"));
            if (next == null) next = object(cur.opt("error"));
            cur = next;
        }
        boolean queued = !authoritative(found) && ("10605".equals(found) || (queue != null && (queue.optBoolean("isQueued", false) || (queue.has("serviceAvailable") && Boolean.FALSE.equals(queue.opt("serviceAvailable"))))));
        int status; String kind; String message; int retry = 0;
        if (queued) {
            status = 503; kind = "queue"; message = "Qoder queued the request; try again later";
            double n = queue == null ? 0 : queue.optDouble("retryAfterSeconds", 0);
            retry = Double.isNaN(n) || Double.isInfinite(n) || n <= 0 ? 30 : (int)Math.min(3600, Math.ceil(n));
        } else if ("105".equals(found)) { status = 401; kind = "auth"; message = "Qoder sign-in expired"; }
        else if (QUOTA.contains(found)) { status = 429; kind = "quota"; message = "Qoder usage limit reached"; }
        else if (REQUEST.contains(found)) { status = 400; kind = "request"; message = "Qoder refused the request"; }
        else { status = upstreamStatus >= 400 && upstreamStatus <= 599 ? upstreamStatus : 502; kind = "upstream"; message = "Qoder request failed"; }
        JSONObject out = new JSONObject();
        out.put("error", message); out.put("status", status); out.put("upstream_status", upstreamStatus);
        out.put("qoder_code", found); out.put("error_kind", kind);
        if (retry > 0) out.put("retry_after", retry);
        return out;
    }
}
