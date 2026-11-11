package com.gaeul.allin;

import org.json.JSONObject;

/**
 * Qoder's failure classification and request limits, read from the plugin as the first authority:
 * {@code qoder-latest/package/index.mjs} 0.2.64, {@code failure()} at :1594-1665 with QUEUED (:1578),
 * QUOTA_CODES (:1580), REQUEST_CODES (:1583), BUSY_WAIT (:1584), UPSTREAM_FAILED (:1592) and
 * MAX_TOKENS_RANGE (:1548) beside it, and the budget clamp at :1502-1506.
 *
 * <p>Every expectation below is what {@code failure()} itself returns for that status and body,
 * taken by running the plugin's own source under node rather than read off a summary. The port is
 * asked the same question, and any different answer is printed with both sides and counted, so one
 * wrong branch cannot hide the rest and nothing here is written to fit a port that disagrees. A
 * case where the port goes beyond the plugin on purpose (a bounded wait) says so and asserts the
 * port's bound, so the difference is a decision on record rather than a silence.
 */
public final class QoderPolicyOfflineTest {
    private static int checks, mismatches;
    private static void mismatch(String why) { mismatches++; System.out.println("MISMATCH " + why); }
    private static void check(boolean b) { check(b, "check " + (checks + 1)); }
    private static void check(boolean b, String why) { checks++; if (!b) mismatch(why); }
    private static void equal(int got, int want, String what) {
        checks++;
        if (got != want) mismatch(what + ": plugin " + want + ", port " + got);
    }
    /** The port's answer for one refusal, against the plugin's status, lapsed, wait and kind. */
    private static void refusal(int upstream, String body, int status, boolean lapsed, String kind, int retryAfter)
            throws Exception {
        checks++;
        JSONObject f = QoderFailurePolicy.failure(upstream, body);
        String port = "status " + f.optInt("status", -1) + (f.optBoolean("lapsed", false) ? " lapsed" : "")
                + (kind == null ? "" : ", kind " + f.optString("error_kind", "?"))
                + ", retry_after " + f.optInt("retry_after", 0) + ", upstream_status " + f.optInt("upstream_status", -1);
        String plugin = "status " + status + (lapsed ? " lapsed" : "")
                + (kind == null ? "" : ", kind " + kind) + ", retry_after " + retryAfter + ", upstream_status " + upstream;
        if (f.optInt("status", -1) != status || f.optBoolean("lapsed", false) != lapsed
                || (kind != null && !kind.equals(f.optString("error_kind", "?")))
                || f.optInt("retry_after", 0) != retryAfter || f.optInt("upstream_status", -1) != upstream) {
            mismatch("upstream " + upstream + " " + body + System.lineSeparator()
                    + "        plugin: " + plugin + System.lineSeparator()
                    + "        port:   " + port);
        }
        if (f.toString().contains("secret")) mismatch("the refusal echoed the body text: " + body);
    }
    /**
     * A case the port answers differently on purpose: the port's own answer is asserted and the
     * plugin's sits beside it in the report, so the deviation has to be re-decided instead of
     * quietly becoming the expectation.
     */
    private static void deviation(int upstream, String body, int status, boolean lapsed, String kind, int retryAfter)
            throws Exception {
        checks++;
        JSONObject f = QoderFailurePolicy.failure(upstream, body);
        if (f.optInt("status", -1) != status || f.optBoolean("lapsed", false) != lapsed
                || !kind.equals(f.optString("error_kind", "?")) || f.optInt("retry_after", 0) != retryAfter) {
            mismatch("the port's declared deviation changed: upstream " + upstream + " " + body
                    + System.lineSeparator() + "        port is now: status " + f.optInt("status", -1)
                    + (f.optBoolean("lapsed", false) ? " lapsed" : "") + ", kind " + f.optString("error_kind", "?")
                    + ", retry_after " + f.optInt("retry_after", 0));
        }
    }
    private static void invalid(String text) throws Exception {
        checks++;
        try { QoderFailurePolicy.payloadLimit(new JSONObject(text)); mismatch("accepted " + text); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        // ---- the model's queue first, whatever code came with it (index.mjs:1628) -------------
        // Qoder's plan queue: the sign-in is fine, so it is a 503 with the wait and never an auth
        // answer. The plugin tests this before every code, so a quota or a refusal code that also
        // carries a queue state is still the queue.
        refusal(403, "{\"code\":10605,\"message\":\"secret\"}", 503, false, "queue", 30);
        refusal(403, "{\"code\":\"10605\"}", 503, false, "queue", 30);
        refusal(500, "{\"code\":10605}", 503, false, "queue", 30);
        // The one body the plugin unwraps: an HTTP/message wrapper whose outer code is empty, 401
        // or 403 and whose message is the 10605 body (index.mjs:1601-1608, #167).
        refusal(403, "{\"code\":403,\"message\":\"{\\\"code\\\":10605}\"}", 503, false, "queue", 30);
        // The queue's state also counts when the message itself is the JSON carrying it (:1611-1615).
        refusal(403, "{\"message\":\"{\\\"isQueued\\\":true}\"}", 503, false, "queue", 30);
        refusal(403, "{\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":12}}", 503, false, "queue", 12);
        // A wait that is not a positive number falls back to BUSY_WAIT (:1630-1632).
        refusal(403, "{\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":0}}", 503, false, "queue", 30);
        refusal(403, "{\"queue\":{\"serviceAvailable\":false}}", 503, false, "queue", 30);
        refusal(403, "{\"code\":110,\"queue\":{\"isQueued\":true}}", 503, false, "queue", 30);
        refusal(403, "{\"code\":110,\"message\":\"{\\\"code\\\":10605}\",\"queue\":{\"serviceAvailable\":false}}", 503, false, "queue", 30);
        refusal(403, "{\"code\":416,\"queue\":{\"isQueued\":true}}", 503, false, "queue", 30);
        refusal(403, "{\"code\":105,\"queue\":{\"isQueued\":true}}", 503, false, "queue", 30);
        refusal(403, "{\"code\":10605,\"message\":\"{\\\"code\\\":110}\"}", 503, false, "queue", 30);
        // The plugin returns the queue's own wait unbounded (:1630-1632); this port bounds it at an
        // hour. That bound is not a plugin rule, so it is asserted as the port's and printed here.
        deviation(403, "{\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":999999}}", 503, false, "queue", 3600);

        // ---- negative controls: no queue state is no queue ------------------------------------
        // isQueued false, serviceAvailable true or a lone retryAfterSeconds is a queue object with
        // nothing wrong in it, so the queue branch must not fire (:1628).
        refusal(403, "{\"queue\":{\"isQueued\":false}}", 401, true, "auth", 0);
        refusal(403, "{\"queue\":{\"serviceAvailable\":true}}", 401, true, "auth", 0);
        refusal(403, "{\"queue\":{\"retryAfterSeconds\":12}}", 401, true, "auth", 0);
        // And the unwrap is the 10605 case only: any other outer code stays authoritative.
        refusal(403, "{\"code\":110,\"message\":\"{\\\"code\\\":10605}\"}", 429, false, "quota", 0);
        refusal(403, "{\"code\":416,\"message\":\"{\\\"code\\\":10605}\"}", 400, false, "request", 0);

        // ---- the codes Qoder's client knows, before the status is read (index.mjs:1636-1637) ---
        refusal(403, "{\"code\":110}", 429, false, "quota", 0);
        refusal(403, "{\"code\":122}", 429, false, "quota", 0);
        refusal(500, "{\"code\":110}", 429, false, "quota", 0);
        refusal(403, "{\"code\":416}", 400, false, "request", 0);
        refusal(403, "{\"code\":\"416\"}", 400, false, "request", 0);
        refusal(403, "{\"code\":406}", 400, false, "request", 0);
        refusal(403, "{\"code\":430}", 400, false, "request", 0);
        refusal(403, "{\"code\":\"100400\"}", 400, false, "request", 0);
        refusal(403, "{\"code\":80411}", 400, false, "request", 0);
        refusal(500, "{\"code\":416}", 400, false, "request", 0);

        // ---- the vendor's failure and the max_tokens ceiling, before the status rule (:1642-1644)
        // "Error in upstream response" is the model's own vendor failing: a 502, whatever status
        // Qoder sent it in, since nothing is wrong with the request or the account.
        refusal(400, "{\"message\":\"Error in upstream response: secret\"}", 502, false, "upstream", 0);
        refusal(502, "{\"message\":\"Error in upstream response\"}", 502, false, "upstream", 0);
        refusal(401, "{\"message\":\"Error in upstream response\"}", 502, false, "upstream", 0);
        refusal(400, "{\"code\":105,\"message\":\"Error in upstream response\"}", 502, false, "upstream", 0);
        // A refusal that names the range is the request's own fault and says which ceiling to send
        // next (:1642-1650), again whatever status it arrived with.
        String range = "Error in upstream response: Range of max_tokens should be [1, 131072]";
        refusal(400, "{\"message\":\"" + range + "\"}", 400, false, "request", 0);
        equal(QoderFailurePolicy.rangeCap("{\"message\":\"" + range + "\"}"), 131072, "the ceiling the refusal named");
        refusal(401, "{\"message\":\"Range of max_tokens should be [1, 4096]\"}", 400, false, "request", 0);
        equal(QoderFailurePolicy.rangeCap("{\"message\":\"Range of max_tokens should be [1, 4096]\"}"), 4096,
                "the ceiling a 401 carried");
        // The vendor marker and the range may sit in different structured fields: the plugin joins
        // message and details.error.message before reading either.
        String detailsAsString = "{\"message\":\"Error in upstream response\",\"details\":"
                + "\"{\\\"error\\\":{\\\"message\\\":\\\"Range of max_tokens should be [1, 131072]\\\"}}\"}";
        refusal(400, detailsAsString, 400, false, "request", 0);
        equal(QoderFailurePolicy.rangeCap(detailsAsString), 131072, "the ceiling in a details string");
        String detailsAsObject = new JSONObject().put("message", "Error in upstream response")
                .put("details", new JSONObject().put("error", new JSONObject()
                        .put("message", "Range of max_tokens should be [1, 131072]"))).toString();
        refusal(400, detailsAsObject, 400, false, "request", 0);
        equal(QoderFailurePolicy.rangeCap(detailsAsObject), 131072, "the ceiling in a details object");
        // Read from the whole body when the text sits in no message field at all (:1619-1622).
        String rangeOnly = "{\"error\":{\"message\":\"Range of max_tokens should be [1, 65536]\"}}";
        refusal(403, rangeOnly, 400, false, "request", 0);
        equal(QoderFailurePolicy.rangeCap(rangeOnly), 65536, "the ceiling in an unlabelled body");
        // Negative control: words that are neither the vendor's nor a range keep the status sent.
        refusal(400, "{\"message\":\"unrelated upstream words\"}", 400, false, "upstream", 0);
        // Both reads are over the answer's text, which the plugin keeps as the whole raw body when
        // it carries no message field (:1596, :1619-1622): words inside an error wrapper still
        // count, at any status the wrapper came with.
        refusal(403, "{\"error\":{\"message\":\"Error in upstream response\"}}", 502, false, "upstream", 0);
        refusal(400, "{\"error\":{\"message\":\"Error in upstream response\"}}", 502, false, "upstream", 0);
        refusal(403, "{\"body\":{\"message\":\"Error in upstream response\"}}", 502, false, "upstream", 0);
        // The plugin reads an impossible range as the ceiling it names and sends it back (:1648);
        // this port keeps the 400 and refuses a ceiling it cannot use, which is the one place its
        // answer differs here — the status and kind are the plugin's.
        refusal(400, "{\"message\":\"Range of max_tokens should be [200, 100]\"}", 400, false, "request", 0);
        check(QoderFailurePolicy.rangeCap("Range of max_tokens should be [200, 100]") == 0,
                "an impossible range names no ceiling here (the plugin would name 100)");

        // ---- the answered status alone says a sign-in lapsed (:1651) ---------------------------
        // Only 401 and 403 reach that branch, and 105 (loginExpired) has no branch of its own: it
        // is a lapsed sign-in when it arrives as one of those two statuses and in no other case.
        refusal(401, "{\"code\":105}", 401, true, "auth", 0);
        refusal(403, "{\"code\":105}", 401, true, "auth", 0);
        refusal(403, "{\"code\":105,\"message\":\"{\\\"code\\\":10605}\"}", 401, true, "auth", 0);
        // A 401/403 with no code, an unknown code, or a code that merely looks like a status is
        // still the lapsed sign-in the status says it is: no code exempts the status, and none
        // promotes a status that is not 401 or 403.
        refusal(403, "{}", 401, true, "auth", 0);
        refusal(403, "secret 10605 401", 401, true, "auth", 0);
        refusal(403, "{\"code\":999}", 401, true, "auth", 0);
        refusal(403, "{\"code\":401}", 401, true, "auth", 0);
        refusal(403, "{\"message\":\"quota\"}", 401, true, "auth", 0);
        // The plugin reads a code only from the body's own "code" field, plus the 10605 unwrap, so
        // a code nested anywhere else is not one and the status stays what Qoder answered.
        refusal(403, "{\"details\":{\"code\":10605}}", 401, true, "auth", 0);
        refusal(403, "{\"error\":{\"code\":120}}", 401, true, "auth", 0);
        refusal(502, "{\"error\":{\"code\":110}}", 502, false, "upstream", 0);

        // ---- the status Qoder answered, kept when no branch claimed it (:1664) -----------------
        refusal(400, "{\"code\":105}", 400, false, "upstream", 0);
        refusal(502, "{\"code\":105}", 502, false, "upstream", 0);
        refusal(502, "{\"error\":{\"code\":105}}", 502, false, "upstream", 0);
        // A status outside 400-599 is not a refusal at all; the plugin answers 502 (:1595).
        refusal(200, "{\"message\":\"ok\"}", 502, false, "upstream", 0);
        // A bare 429 names no quota in the plugin's reading, so only its status is asserted: the
        // plugin has no kind word of its own for this branch.
        refusal(429, "{\"message\":\"All models failed\"}", 429, false, null, 0);
        // The account's limit is also read out of the words when the status is not 429 (:1659).
        refusal(400, "{\"message\":\"quota exceeded\"}", 429, false, "quota", 0);

        // ---- the output budget: a positive ask is sent as asked (:1502-1506) -------------------
        equal(QoderFailurePolicy.payloadLimit(new JSONObject()), 32000, "no ask takes the plugin's 32000");
        equal(QoderFailurePolicy.payloadLimit(new JSONObject("{\"max_tokens\":64}")), 64, "an ask is kept");
        equal(QoderFailurePolicy.payloadLimit(new JSONObject("{\"max_tokens\":100,\"max_completion_tokens\":70}")), 70,
                "max_completion_tokens wins, as in the plugin");
        for (int budget : new int[]{1, 8, 16, 63}) {
            equal(QoderFailurePolicy.payloadLimit(new JSONObject().put("max_tokens", budget)), budget,
                    "a short budget is kept as asked (" + budget + ")");
            equal(QoderFailurePolicy.payloadLimit(new JSONObject().put("max_completion_tokens", budget)), budget,
                    "a short completion budget is kept as asked (" + budget + ")");
        }
        invalid("{\"max_tokens\":0}"); invalid("{\"max_tokens\":-1}");
        invalid("{\"max_tokens\":\"64\"}"); invalid("{\"max_tokens\":2147483648}");
        invalid("{\"max_tokens\":64.5}"); invalid("{\"max_tokens\":null}");
        invalid("{\"max_completion_tokens\":0}"); invalid("{\"max_tokens\":100,\"max_completion_tokens\":0}");

        // ---- the model's ceiling lowers a higher ask (:1506, :1550-1558) -----------------------
        equal(QoderFailurePolicy.modelLimit(200000, "lmodel", new JSONObject()), 131072, "the QoDuck ceiling");
        equal(QoderFailurePolicy.modelLimit(500, "lmodel", new JSONObject()), 500, "a lower ask is sent as asked");
        equal(QoderFailurePolicy.modelLimit(16, "lmodel", new JSONObject()), 16, "a short ask is not raised");
        equal(QoderFailurePolicy.modelLimit(200000, "other", new JSONObject().put("max_output_tokens", 16384)), 16384,
                "the catalog's own ceiling");
        equal(QoderFailurePolicy.modelLimit(200000, "other", new JSONObject().put("maxOutputTokens", "bad")), 200000,
                "an unusable catalog ceiling caps nothing");
        equal(QoderFailurePolicy.modelLimit(100, "other", new JSONObject().put("max_output_tokens", 63)), 63,
                "a catalog ceiling below 64 still lowers the ask, as :1506 sends it");
        equal(QoderFailurePolicy.modelLimit(20, "other", new JSONObject().put("max_output_tokens", 63)), 20,
                "a lower ask is still sent as asked");
        equal(QoderFailurePolicy.modelLimit(1, "other", new JSONObject().put("max_output_tokens", 63)), 1,
                "a one-token ask is sent as asked");

        // ---- the ceiling a refusal named is remembered for the next request --------------------
        QoderFailurePolicy.clearLearnedCaps();
        String taught = "{\"code\":400,\"message\":\"Error in upstream response: Range of max_tokens should be [1, 65536]\"}";
        equal(QoderFailurePolicy.rangeCap(taught), 65536, "the ceiling in a refusal");
        equal(QoderFailurePolicy.rangeCap("{\"message\":\"plain refusal\"}"), 0, "no range names no ceiling");
        equal(QoderFailurePolicy.rangeCap("Range of MAX_TOKENS should be [1 , 4096 ]"), 4096, "the plugin's own spacing");
        equal(QoderFailurePolicy.learnRangeCap("taught", taught), 65536, "a refusal teaches its ceiling");
        equal(QoderFailurePolicy.modelLimit(200000, "taught", new JSONObject()), 65536, "the learned ceiling applies at once");
        equal(QoderFailurePolicy.modelLimit(1000, "taught", new JSONObject()), 1000, "a lower ask is still as asked");
        QoderFailurePolicy.learnRangeCap("taught", "Range of max_tokens should be [1, 999999]");
        equal(QoderFailurePolicy.modelLimit(200000, "taught", new JSONObject()), 65536,
                "a later, higher refusal cannot raise the ceiling");
        equal(QoderFailurePolicy.learnRangeCap("", taught), 0, "no model key teaches nothing");
        JSONObject asked = new JSONObject().put("parameters", new JSONObject().put("max_tokens", 200000));
        check(QoderFailurePolicy.reclampMaxTokens(asked, "taught2", taught), "a refused body is lowered once");
        equal(asked.getJSONObject("parameters").getInt("max_tokens"), 65536, "the body carries the ceiling");
        check(!QoderFailurePolicy.reclampMaxTokens(asked, "taught2", taught), "a body already at the ceiling is not lowered again");
        equal(QoderFailurePolicy.modelLimit(200000, "taught2", new JSONObject()), 65536, "the reclamp taught the model");
        JSONObject classified = QoderFailurePolicy.failure(400, taught, "taught3");
        equal(classified.optInt("status"), 400, "a range refusal is the request's own");
        equal(classified.optInt("max_tokens_cap"), 65536, "a classified refusal teaches the model it named");
        equal(QoderFailurePolicy.modelLimit(200000, "taught3", new JSONObject()), 65536, "the taught ceiling holds");
        check(!QoderFailurePolicy.failure(400, "{\"message\":\"unrelated\"}", "taught3").has("max_tokens_cap"),
                "only a range refusal teaches a ceiling");
        QoderFailurePolicy.clearLearnedCaps();
        equal(QoderFailurePolicy.modelLimit(200000, "taught2", new JSONObject()), 200000, "cleared for the next case");

        if (mismatches > 0) {
            System.out.println("Qoder policy offline: " + checks + " checks, " + mismatches
                    + " not matching the plugin (qoder 0.2.64 failure(), index.mjs:1594-1665)");
            throw new AssertionError(mismatches + " case(s) still answer differently from the plugin");
        }
        System.out.println("Qoder policy offline checks passed: " + checks + " (all match the plugin)");
    }
}
