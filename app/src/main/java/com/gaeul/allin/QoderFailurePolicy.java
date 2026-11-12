package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded, structured interpretation of Qoder failures and request limits. */
public final class QoderFailurePolicy {
    private QoderFailurePolicy() {}
    private static final Set<String> QUOTA = new HashSet<>(Arrays.asList("110","112","113","114","115","116","117","118","119","122"));
    private static final Set<String> REQUEST = new HashSet<>(Arrays.asList("406","416","430","80411","80412","100400","100401","100403"));
    /** Qoder's plan queue for the model: busy for now, never a lapsed sign-in (index.mjs:1578). */
    private static final String QUEUED = "10605";
    /**
     * A sign-in this process renewed less than this ago is not renewed again when Qoder refuses it:
     * a refusal that is not about the token's end would otherwise spend a refresh token per request
     * (opencode-qoder-auth index.mjs:59, :3264-3266).
     */
    public static final long RELOGIN_GAP_MS = 5 * 60 * 1000L;
    public static final int MAX_ERROR_BYTES = 16384;
    public static final int DEFAULT_MAX_TOKENS = 32000;
    /** Qoder CN's QoDuck refuses a larger max_tokens whatever the account, as the plugin notes. */
    static final int LMODEL_CAP = 131072;
    private static final int LEARNED_LIMIT = 64;
    /** What refusals taught, by model key. Guarded by its own monitor; oldest entry dropped. */
    private static final java.util.LinkedHashMap<String,Integer> LEARNED = new java.util.LinkedHashMap<>();

    /**
     * The output budget a request names, DEFAULT_MAX_TOKENS when it names none: the plugin's
     * {@code asking = max_completion_tokens || max_tokens || 32000} (index.mjs:1502, :1504).
     * A positive value is kept as asked whatever its size: the plugin only ever lowers it to a
     * model ceiling ({@code cap > 0 && asked > cap ? cap : asked}, index.mjs:1506), and 0.2.64
     * carries no lower bound on a request's own budget. An unusable value is a local error.
     */
    public static int payloadLimit(JSONObject payload) {
        Integer a = limit(payload, "max_tokens"), b = limit(payload, "max_completion_tokens");
        return a == null ? (b == null ? DEFAULT_MAX_TOKENS : b) : (b == null ? a : Math.min(a, b));
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
    private static int positive(Integer n) { return n != null && n > 0 ? n : 0; }

    /** The load-bearing words of a max_tokens range refusal, as the plugin reads them. */
    private static final java.util.regex.Pattern RANGE = java.util.regex.Pattern.compile(
            "(?i)\\brange of max_tokens should be \\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]");

    /** The ceiling a refusal named, 0 when it names none or an impossible one. */
    public static int rangeCap(String body) {
        if (body == null || body.isEmpty() || body.length() > MAX_ERROR_BYTES) return 0;
        java.util.regex.Matcher m = RANGE.matcher(body);
        if (!m.find()) return 0;
        try {
            long low = Long.parseLong(m.group(1)), high = Long.parseLong(m.group(2));
            if (low < 0 || high < 1 || high > Integer.MAX_VALUE || high < low) return 0;
            return (int) high;
        } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Remember the ceiling a refusal named for this model, as the plugin does, so a later
     * request is sent it from the start. Only a lowering is kept: a ceiling above the one
     * already known cannot raise the limit. Returns the effective learned ceiling, 0 if none.
     */
    public static int learnRangeCap(String modelKey, String body) {
        int cap = rangeCap(body);
        if (cap <= 0) return 0;
        return rememberRangeCap(modelKey, cap);
    }

    /**
     * Remember a ceiling a refusal named when only its number is known: the stream keeps structured
     * fields, not the refusal's text. Only a lowering is kept. Returns the effective ceiling, 0 when
     * the model key is unusable.
     */
    public static int rememberRangeCap(String modelKey, int cap) {
        if (cap <= 0 || modelKey == null || modelKey.trim().isEmpty()) return 0;
        String key = modelKey.trim();
        synchronized (LEARNED) {
            Integer known = LEARNED.get(key);
            if (known == null || cap < known) LEARNED.put(key, cap);
            while (LEARNED.size() > LEARNED_LIMIT) {
                java.util.Iterator<String> oldest = LEARNED.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
            return LEARNED.get(key);
        }
    }

    /**
     * Lower a request's max_tokens to the ceiling a refusal named when the caller already knows the
     * number, remembering it as the plugin's reclamp does. False when nothing would change: the
     * same body asked again would be refused again, so it must not be resent.
     */
    public static boolean reclampTo(JSONObject body, String modelKey, int cap) {
        if (cap <= 0 || body == null) return false;
        JSONObject params = body.optJSONObject("parameters");
        if (params == null) return false;
        int asked = params.optInt("max_tokens", 0);
        if (asked <= cap) return false;
        rememberRangeCap(modelKey, cap);
        params.put("max_tokens", cap);
        return true;
    }

    /** Test hook: forget what earlier refusals taught, so cases do not leak into each other. */
    public static void clearLearnedCaps() { synchronized (LEARNED) { LEARNED.clear(); } }

    /**
     * The largest max_tokens a model takes, 0 when none is known: what a refusal named,
     * else the catalog entry's own max_output_tokens (then its alias), else the known
     * QoDuck ceiling. A request asked lower is sent as asked.
     */
    public static int outputCap(String modelKey, JSONObject model) {
        String key = modelKey == null ? "" : modelKey.trim();
        int learned = 0;
        if (!key.isEmpty()) synchronized (LEARNED) { learned = positive(LEARNED.get(key)); }
        if (learned > 0) return learned;
        for (String name : new String[]{"max_output_tokens", "maxOutputTokens"}) {
            try {
                int n = positive(limit(model, name));
                if (n > 0) return n;
            } catch (IllegalArgumentException ignored) { }
        }
        if ("lmodel".equals(key)) return LMODEL_CAP;
        return 0;
    }

    /**
     * The budget actually sent: the catalog's own ceiling, a learned one or the known QoDuck
     * limit lowers a higher ask, and a lower ask is sent as asked (index.mjs:1506, :1550-1558).
     * Nothing is raised: a ceiling below the ask is Qoder's word, not an error here.
     */
    public static int modelLimit(int requested, String modelKey, JSONObject model) {
        String key = modelKey == null ? "" : modelKey.trim();
        int cap = outputCap(key, model);
        // The QoDuck ceiling is what a larger value is refused with, so it caps a higher catalog value too.
        if ("lmodel".equals(key) && (cap == 0 || cap > LMODEL_CAP)) cap = LMODEL_CAP;
        return cap > 0 ? Math.min(requested, cap) : requested;
    }

    /**
     * Lower a request's max_tokens to the ceiling a refusal named, as the plugin's reclamp
     * does, and remember it. True when the body changed and the turn may be asked again.
     */
    public static boolean reclampMaxTokens(JSONObject body, String modelKey, String refusal) {
        int cap = rangeCap(refusal);
        if (cap <= 0 || body == null) return false;
        JSONObject params = body.optJSONObject("parameters");
        if (params == null) return false;
        int asked = params.optInt("max_tokens", 0);
        if (asked <= cap) return false;
        learnRangeCap(modelKey, refusal);
        params.put("max_tokens", cap);
        return true;
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
    /**
     * JavaScript's truthiness, for the two {@code if} tests the plugin makes on the answer's own
     * fields (index.mjs:1612, :1615): {@code ""}, {@code 0}, {@code false}, {@code null} and an
     * absent field are falsy; a string with anything in it, a number that is not zero, true, and
     * every object or array — even an empty one — are truthy.
     */
    static boolean jsTruthy(Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0d;
        return true;
    }
    /**
     * {@code String(value)} as JavaScript writes it, which is the text the plugin then reads its
     * range and vendor words from. A plain object becomes {@code [object Object]}, an array the
     * comma join of its parts, and anything unrecognised the same neutral placeholder, so no value
     * can put words into that text that JavaScript would not have put there.
     */
    static String jsString(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return (String) value;
        if (value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Integer || value instanceof Long) return String.valueOf(value);
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
            return String.valueOf(d);
        }
        if (value instanceof JSONArray) {
            JSONArray list = (JSONArray) value;
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                if (i > 0) joined.append(',');
                joined.append(jsString(list.opt(i)));
            }
            return joined.toString();
        }
        return "[object Object]";
    }
    /**
     * The queue state the answer carries (:1617-1621): its {@code queue} object, else the JSON of
     * one written into its message. Null when it carries none — and also when the message starts
     * like JSON but does not parse, because the plugin's own {@code JSON.parse} throws there, which
     * leaves the queue at whatever the field had already given.
     */
    private static JSONObject queueState(JSONObject answer) {
        if (answer == null) return null;
        Object field = answer.opt("queue");
        JSONObject queue = field instanceof JSONObject ? (JSONObject) field : null;
        Object message = answer.opt("message");
        if (queue == null && message instanceof String && ((String) message).trim().startsWith("{")) {
            JSONObject embedded = object(((String) message).trim());
            if (embedded != null && (embedded.has("isQueued") || embedded.has("serviceAvailable")
                    || embedded.has("retryAfterSeconds"))) queue = embedded;
        }
        return queue;
    }
    /**
     * The lapsed sign-in verdict, as a caller that keeps only the classified status and code can
     * read it back.
     *
     * <p>{@link #failure(int, String)} decides this in its own branch order and publishes it as the
     * refusal's {@code lapsed} field — that field is the answer, not this method. A transport
     * ({@code QoderStream.Failure}) keeps only the classified status and the Qoder code, so this
     * method exists to carry the same verdict across it, and it is sound only because the
     * classification emits {@code 401} from that branch alone: the queue is answered {@code 503},
     * a quota {@code 429}, a refused request and a max_tokens range {@code 400}, an upstream vendor
     * failure {@code 502}, and the final branch reports the answered status only when it is neither
     * 401, 403 nor 429. So {@code 401} appears if and only if Qoder refused the call as a lapsed
     * sign-in. {@code QoderGatewayHttpTest} proves that over a corpus checked against the plugin's
     * own {@code failure()} (index.mjs:1594-1666) run by node; the code guards are the queue/quota/
     * request priority repeated, so a future branch that reuses 401 cannot pass for a renewal.
     */
    public static boolean lapsedFailure(int status, int code) {
        if (status != 401) return false;
        String c = code > 0 ? Integer.toString(code) : "";
        return !QUEUED.equals(c) && !QUOTA.contains(c) && !REQUEST.contains(c);
    }
    /**
     * The same classification, also teaching the model what a range refusal named, so a
     * later request is sent the ceiling from the start. A caller that knows which model
     * was asked uses this; the two-argument form classifies only.
     */
    public static JSONObject failure(int upstreamStatus, String body, String modelKey) throws Exception {
        JSONObject out = failure(upstreamStatus, body);
        if (body != null && "Qoder refused the request max_tokens".equals(out.optString("error"))) {
            int cap = learnRangeCap(modelKey, body);
            if (cap > 0) out.put("max_tokens_cap", cap);
        }
        return out;
    }

    /**
     * Qoder's words for "the vendor behind this model failed": the plugin's own
     * {@code /\berror in upstream response\b/i} (index.mjs:1592), read on the answer's text.
     */
    private static final java.util.regex.Pattern VENDOR_FAILED = java.util.regex.Pattern.compile(
            "(?i)\\berror in upstream response\\b");

    /**
     * A Qoder refusal, classified in the order the plugin's own {@code failure()} uses
     * (opencode-qoder-auth index.mjs:1594-1666), which a node run of that very function is the
     * reference for:
     *
     * <ol>
     *   <li>a status outside 400..599 is answered as 502 first (:1595);</li>
     *   <li>the model queue — the code 10605, or a queue state saying {@code isQueued} or not
     *       {@code serviceAvailable} — is 503 with the queue's own wait (:1628-1635). It is read
     *       <em>before</em> any code, so a 403 or a 400 carrying a quota or request code beside a
     *       queue state is that queue: retrying, not resting the account, is what Qoder asked
     *       for (sub_158);</li>
     *   <li>a plan's quota code is 429 (:1637), a request's own refusal 400 (:1638);</li>
     *   <li>a max_tokens range named in the answer is 400 with that ceiling (:1642-1643), read
     *       whatever the status and without needing the vendor marker beside it;</li>
     *   <li>the vendor marker alone is 502 (:1644);</li>
     *   <li>only now the answered status: 401 and 403 are the lapsed sign-in (:1651-1653). Every
     *       other status keeps itself.</li>
     * </ol>
     *
     * <p>{@code lapsed} is set by that one branch and nowhere else: it is the refusal's verdict,
     * not a reading of the status this method went on to report.
     *
     * <p>The status this gateway reports stays the status Qoder answered except where the plugin
     * maps it (a lapsed sign-in and the queue are 401 and 503, a vendor failure 502, and a range
     * 400), so the classification never invents a status Qoder did not answer.
     */
    public static JSONObject failure(int upstreamStatus, String body) throws Exception {
        // (:1595) A status outside 400..599 is 502 before anything else is read.
        final int answered = upstreamStatus >= 400 && upstreamStatus <= 599 ? upstreamStatus : 502;
        String raw = body == null ? "" : body.trim();
        /* (:1600-1610) The answer as Qoder sent it, plus the one unwrap the plugin makes: when the
         * outer code is absent, 401 or 403 and its message is itself the JSON of a body whose code is
         * the queue's, that inner body is the answer. Nothing else is unwrapped — a real auth,
         * credit or request code on the outer body stays authoritative — and no other field is
         * searched, so a code this gateway found somewhere Qoder did not put it cannot reclassify
         * the refusal. */
        JSONObject root = object(raw), answer = root;
        if (root != null) {
            String outer = code(root);
            Object message = root.opt("message");
            if ((outer.isEmpty() || "401".equals(outer) || "403".equals(outer)) && message instanceof String) {
                JSONObject inner = object(((String) message).trim());
                if (inner != null && inner.opt("code") != null && QUEUED.equals(String.valueOf(inner.opt("code"))))
                    answer = inner;
            }
        }
        String found = code(answer);
        /* (:1596, :1611-1621) The answer's own words, assembled in the plugin's own order. msg
         * STARTS as the whole body it was given and is replaced only by a truthy message field, so a
         * body whose message sits one level down — {"error":{"message":"Error in upstream
         * response"}}, which Qoder does send — still hands its text to the range and vendor tests
         * below. Reading only a top-level message dropped that text and let the refusal fall through
         * to the lapsed branch, spending a refresh token on a vendor failure (sub_158).*/
        String said = raw;
        JSONObject queue = null;
        if (answer != null) {
            Object message = answer.opt("message");
            if (jsTruthy(message)) said = jsString(message);
            /* details is an object or the JSON string of one. A string that does not parse makes
             * the plugin's own JSON.parse throw, which abandons the rest of its try block: the queue
             * state below is then never read, and such a body is the lapsed sign-in it looks like. */
            Object details = answer.opt("details");
            JSONObject detailObject;
            boolean aborted = false;
            if (details instanceof String) {
                detailObject = object(((String) details).trim());
                aborted = detailObject == null;
            } else detailObject = details instanceof JSONObject ? (JSONObject) details : null;
            JSONObject detailError = detailObject == null ? null : object(detailObject.opt("error"));
            Object detailMessage = detailError == null ? null : detailError.opt("message");
            if (jsTruthy(detailMessage)) said = said + ": " + jsString(detailMessage);
            if (!aborted) queue = queueState(answer);
        }
        // (:1628) The queue is read before every code: a queue state is a queue whatever else the
        // body carries, which is what keeps a queued 403 from spending a refresh token (#98).
        boolean queued = QUEUED.equals(found) || (queue != null
                && (queue.optBoolean("isQueued", false)
                    || (queue.has("serviceAvailable") && Boolean.FALSE.equals(queue.opt("serviceAvailable")))));
        // (:1642, :1644) Both are the answer's own words, in that order, whatever status carried them.
        java.util.regex.Matcher rangeMatch = RANGE.matcher(said);
        boolean rangeFailure = rangeMatch.find();
        boolean vendorFailure = !rangeFailure && VENDOR_FAILED.matcher(said).find();
        int status; String kind; String message; int retry = 0; boolean lapsed = false;
        if (queued) {
            status = 503; kind = "queue"; message = "Qoder queued the request; try again later";
            double n = queue == null ? 0.0d : queue.optDouble("retryAfterSeconds", 0.0d);
            retry = (Double.isNaN(n) || Double.isInfinite(n) || n <= 0.0d) ? 30 : (int) Math.min(3600.0d, Math.ceil(n));
        }
        else if (QUOTA.contains(found)) { status = 429; kind = "quota"; message = "Qoder usage limit reached"; }
        else if (REQUEST.contains(found)) { status = 400; kind = "request"; message = "Qoder refused the request"; }
        else if (rangeFailure) { status = 400; kind = "request"; message = "Qoder refused the request max_tokens"; }
        else if (vendorFailure) { status = 502; kind = "upstream"; message = "Qoder upstream provider failed"; }
        // (:1651) The lapsed sign-in is the answered status and nothing else, and this branch is the
        // only one that says so. A 105 beside any other status is that status: Qoder named the code,
        // but a call it did not refuse as a lapsed sign-in must not spend a refresh token.
        else if (answered == 401 || answered == 403) { status = 401; kind = "auth"; message = "Qoder sign-in expired"; lapsed = true; }
        // (:1659) A 429, or an answer that says "quota" anywhere, is a limit rather than a fault.
        else if (answered == 429 || said.toLowerCase(java.util.Locale.ROOT).contains("quota")) {
            status = 429; kind = "quota"; message = "Qoder usage limit reached";
        }
        else { status = answered; kind = "upstream"; message = "Qoder request failed"; }
        JSONObject out = new JSONObject();
        out.put("error", message); out.put("status", status); out.put("upstream_status", upstreamStatus);
        out.put("qoder_code", found); out.put("error_kind", kind);
        out.put("lapsed", lapsed);
        if (retry > 0) out.put("retry_after", retry);
        return out;
    }
}
