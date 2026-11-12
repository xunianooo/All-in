package com.gaeul.allin;

import java.util.List;
import java.util.Set;
import com.gaeul.allin.json.JSONObject;

/**
 * The refusal Android answers from what it knows, instead of asking Qoder: a model the account's
 * own listing states off while the account's credits are used up.
 *
 * <p>Qoder's listing is what names a model at all, and on international personal accounts it takes
 * the models that cost credits off once the credits are spent, on again when they refresh
 * (magpie-community/plugins#880). The plugin answers such a request as Qoder's own quota codes are
 * answered — 429 — so a routing group fails over and rests the account
 * (opencode-qoder-auth 0.2.64, index.mjs:3574-3594):
 *
 * <pre>
 * usage limit reached: the account's credits are used up (800 / 800 credits), and Qoder
 * lists "dfmodel" off for it until they refresh
 * </pre>
 *
 * <p>Only that pair is a 429 here, and every input has to be confirmed:
 *
 * <ul>
 *   <li>the model is <b>stated</b> off by a listing read <b>at request time</b>
 *       ({@link QoderCatalog#statedOffKeys}) — never by a record a sync wrote hours ago, because
 *       Qoder turns a model back on when its credits refresh and that record would then refuse a
 *       model that works;</li>
 *   <li>the usage envelope says the account has none of the pools it spends in turn left
 *       ({@link QoderCredits#spent});</li>
 *   <li>every account of the provider that could serve the model has been confirmed the same way
 *       ({@link #confirmed(String, java.util.List)}) — one account's exhaustion is no statement
 *       about the model, so a caller with several accounts asks all of them and keeps its own
 *       answer when any one cannot be read or still has credits.</li>
 * </ul>
 *
 * <p>The sentence never lends one account's numbers to another: with several accounts it lists
 * each one's own reading, in order, and names no account (no uid, no address) at all.
 *
 * <p>Anything unconfirmed returns null. Null is "keep what you answer today", never "some other
 * refusal".
 */
public final class QoderRefusal {
    private QoderRefusal() {}

    /** The status Qoder's own quota codes are answered with. */
    public static final int QUOTA = 429;

    /** The kind the gateway's ledger and diagnostics record for a quota. */
    public static final String QUOTA_KIND = "quota";

    /** Qoder's own word for the answer, as the plugin gives it for a quota. */
    public static final String QUOTA_SAID = "usage limit reached: ";

    /**
     * The refusal for a request of {@code model} on one account, or null when nothing is
     * confirmed.
     *
     * @param liveListing the off keys a listing read <em>for this request</em> stated, or null when
     *                    no such listing could be read — null is no confirmation, so the caller
     *                    keeps its own answer
     * @param usage       the account's usage envelope, or null when none could be read
     */
    public static JSONObject disabledModel(Set<String> liveListing, String model, JSONObject usage)
            throws Exception {
        JSONObject refusal = one(liveListing, model, usage);
        return refusal;
    }

    /** The same, from a usage envelope this caller already read as spent. */
    public static JSONObject disabledModel(Set<String> liveListing, String model, String spent)
            throws Exception {
        return confirmed(liveListing, model, spent == null ? null : java.util.Collections.singletonList(spent));
    }

    /**
     * The refusal once every account that could serve the model has been confirmed, each with its
     * own reading.
     *
     * @param spentPerAccount what {@link QoderCredits#spent} read for each confirmed account, in
     *                        order, e.g. "800 / 800 credits"; the sentence quotes each one for
     *                        itself and never one of them for all
     * @return the 429 refusal, or null when the inputs are not confirmed
     */
    public static JSONObject confirmed(Set<String> liveListing, String model, List<String> spentPerAccount)
            throws Exception {
        String key = QoderCatalog.key(model);
        if (key.isEmpty()) return null;
        if (liveListing == null || !liveListing.contains(key)) return null;
        if (spentPerAccount == null || spentPerAccount.isEmpty()) return null;
        for (String spent : spentPerAccount) {
            if (spent == null || spent.trim().isEmpty()) return null;
        }
        int accounts = spentPerAccount.size();
        StringBuilder counted = new StringBuilder();
        for (int i = 0; i < accounts; i++) {
            if (i > 0) counted.append("; ");
            if (accounts > 1) counted.append("account ").append(i + 1).append(": ");
            counted.append(spentPerAccount.get(i).trim());
        }
        JSONObject out = new JSONObject();
        out.put("status", QUOTA);
        out.put("error_kind", QUOTA_KIND);
        out.put("error", QUOTA_SAID
                + (accounts > 1 ? "the accounts' credits are used up (" : "the account's credits are used up (")
                + counted + "), and Qoder lists \"" + key + "\" off for "
                + (accounts > 1 ? "them" : "it") + " until they refresh");
        // A quota is the account's usage, not its sign-in: the token is still good for the models
        // the listing has on and for its usage read.
        out.put("sign_in", "");
        return out;
    }

    /** One account's confirmation, or null. */
    private static JSONObject one(Set<String> liveListing, String model, JSONObject usage) throws Exception {
        String spent = QoderCredits.spent(usage);
        if (spent.isEmpty()) return null;
        return confirmed(liveListing, model, java.util.Collections.singletonList(spent));
    }

    /**
     * Whether a listing states this model off. Only ever true on that positive statement: a listing
     * that keeps the model on, or never names it, is not this refusal's business — the caller's own
     * "unknown or disabled" answer stands.
     */
    public static boolean statedOff(Set<String> liveListing, String model) {
        String key = QoderCatalog.key(model);
        return !key.isEmpty() && liveListing != null && liveListing.contains(key);
    }

    /** The status of a refusal this class returned, 0 when it returned none. */
    public static int status(JSONObject refusal) { return refusal == null ? 0 : refusal.optInt("status", 0); }

    /** The sentence of a refusal this class returned, "" when it returned none. */
    public static String message(JSONObject refusal) { return refusal == null ? "" : refusal.optString("error", ""); }

    /** The off models a listing has, as a catalog record keeps them (for a sync to persist). */
    public static List<JSONObject> disabled(JSONObject listing) throws Exception {
        return QoderCatalog.disabled(listing);
    }

    /**
     * The refusal as the exception a caller's own error path can carry, null when nothing is
     * confirmed — so a request path that takes no status of its own needs one line:
     * {@code Quota refusal = QoderRefusal.quota(...); if (refusal != null) throw refusal;}
     */
    public static Quota quota(Set<String> liveListing, String model, JSONObject usage) throws Exception {
        JSONObject refusal = disabledModel(liveListing, model, usage);
        return refusal == null ? null : new Quota(refusal);
    }

    /** The same, once every account has been confirmed ({@link #confirmed}). */
    public static Quota quota(Set<String> liveListing, String model, List<String> spentPerAccount) throws Exception {
        JSONObject refusal = confirmed(liveListing, model, spentPerAccount);
        return refusal == null ? null : new Quota(refusal);
    }

    /** A confirmed refusal: Qoder's 429, its sentence, and nothing said about the sign-in. */
    public static final class Quota extends Exception {
        private static final long serialVersionUID = 1L;
        private final String message;
        private final int status;
        private Quota(JSONObject refusal) {
            super(message(refusal));
            this.status = QoderRefusal.status(refusal);
            this.message = QoderRefusal.message(refusal);
        }
        /** 429 for a refusal this class confirmed, 0 for none. */
        public int status() { return status; }
        /** Qoder's sentence, as the request's body says it. */
        public String said() { return message; }
        /** "" — a quota is the account's usage, not its sign-in. */
        public String signIn() { return ""; }
    }
}
