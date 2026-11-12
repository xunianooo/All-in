package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

/**
 * Qoder's credits, read from an account's usage envelope as Qoder's own panel counts them.
 *
 * <p>A port of the vendored plugin's {@code spentOf} (opencode-qoder-auth 0.2.64,
 * index.mjs:2810-2843), which is the only statement the plugin accepts as "this account has
 * none left": the pools Qoder spends one after another (the plan's credits, the account's own
 * add-on credits and, on a team, the organisation's shared add-on credits) must all be used up
 * <em>together</em>. A plan used up beside add-on credits is no account used up
 * (magpie-community/plugins#454), and a usage read that cannot be made says nothing.
 *
 * <p>Everything here is a pure function of the envelope it is given: no network, no account
 * state. A caller that has no envelope (Android reads no Qoder usage today: GatewayServer's
 * {@code fetchBillingAllowance} has no Qoder case and QoderAuth reads no usage endpoint) gets
 * the empty answer, which is exactly what keeps a request on its existing behaviour.
 */
public final class QoderCredits {
    private QoderCredits() {}

    /**
     * The pools Qoder spends in turn before an account stops, by the names {@code parseUsage}
     * gives them. Only these three count together; dedicated packages do not.
     */
    public static final String[] TURN_POOLS = {"Credits", "Add-on credits", "Shared credits"};

    /** The envelope's fields for those pools, camel case first as Qoder sends them. */
    private static final String[][] POOL_FIELDS = {
        {"userQuota", "user_quota"},
        {"addOnQuota", "add_on_quota"},
        {"orgResourcePackage", "org_resource_package"},
    };

    /**
     * The credits an envelope says the account has used of what it has ("800 / 800 credits"),
     * and "" when some are left, when it names no pool at all, or when it cannot be read.
     *
     * <p>An empty answer is the <em>only</em> answer a caller may treat as unknown; it is never
     * "the account has credits". A free account's pools of 0 have none left either, as Qoder's
     * client counts them, so those read "0 / 0 credits".
     */
    public static String spent(JSONObject envelope) {
        if (envelope == null) return "";
        // Qoder and Qoder CN send displayMode "qoder"; an enterprise account sends another,
        // and its usage is not these pools.
        if (!"qoder".equals(envelope.optString("displayMode", ""))) return "";
        Object usage = envelope.opt("qoderUsage");
        if (!(usage instanceof JSONObject)) return "";
        JSONObject u = (JSONObject) usage;
        double used = 0d;
        double total = 0d;
        int pools = 0;
        for (String[] names : POOL_FIELDS) {
            // The plugin reads `u[a] !== undefined ? u[a] : u[b]`: presence decides, not the
            // value. A pool named with null is *present* there, so it takes the null and skips
            // (p === null). Falling back to the snake_case name instead would count a pool the
            // plugin ignores — and could turn "used up" out of a field Qoder sent as null.
            Object value = null;
            boolean present = false;
            if (u.has(names[0])) { present = true; value = u.opt(names[0]); }
            else if (u.has(names[1])) { present = true; value = u.opt(names[1]); }
            if (!present) continue;
            // JSONObject.NULL (and any scalar or array) is not a pool: skipped, and not replaced
            // by the other spelling either.
            if (!(value instanceof JSONObject)) continue;
            JSONObject pool = (JSONObject) value;
            Double cap = number(opt(pool, "total"));
            if (cap == null) cap = number(opt(pool, "cap"));
            if (cap == null || cap.doubleValue() < 0d) continue;
            double have = cap.doubleValue();
            Double left = number(opt(pool, "used"));
            if (left == null) {
                Double remaining = number(opt(pool, "remaining"));
                if (remaining != null) left = Double.valueOf(have - remaining.doubleValue());
            }
            // A pool that holds credits but says neither how many are used nor how many are
            // left is no statement at all: the whole envelope is unknown then.
            if (have > 0d && left == null) return "";
            pools++;
            total += have;
            used += have > 0d ? Math.min(Math.max(left.doubleValue(), 0d), have) : 0d;
        }
        if (pools == 0 || used < total) return "";
        return count(used) + " / " + count(total) + " credits";
    }

    /** Whether an envelope says the account has none left. False when it does not say. */
    public static boolean exhausted(JSONObject envelope) { return !spent(envelope).isEmpty(); }

    /**
     * A pool's own field, or null when it carries none. JSONObject.NULL reads as null here because
     * the inner fields are read with `??` and `typeof … === "number"` in the plugin, where a null is
     * simply "not a number" and falls through to the next spelling.
     */
    private static Object opt(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.opt(key);
    }

    /**
     * The number a pool states, or null when it states none: only a JSON number counts, as the
     * plugin's {@code typeof p.used === "number"} does, so "800" is not 800 and NaN is nothing.
     */
    private static Double number(Object value) {
        if (!(value instanceof Number)) return null;
        double n = ((Number) value).doubleValue();
        if (Double.isNaN(n) || Double.isInfinite(n)) return null;
        return Double.valueOf(n);
    }

    /**
     * A credit count as the plugin prints it ({@code gfmt}, index.mjs:2762-2767): plain for the
     * counts an account holds, exponent notation to two digits outside 1e-4..1e6. Qoder's own
     * panel writes "800 / 800 credits"; no separator is inserted.
     */
    public static String count(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n) || n == 0d) return "0";
        double magnitude = Math.abs(n);
        if (magnitude < 1e-4 || magnitude >= 1e6) {
            int exponent = (int) Math.floor(Math.log10(magnitude));
            double mantissa = n / Math.pow(10d, exponent);
            String digits = Integer.toString(Math.abs(exponent));
            if (digits.length() < 2) digits = "0" + digits;
            // log10/pow are not exact (5e-05 / 1e-05 is 5.000000000000001): the mantissa is
            // held to the 15 digits a double carries, which is the shortest form the plugin
            // prints.
            String shown = new java.math.BigDecimal(mantissa)
                    .round(new java.math.MathContext(15)).stripTrailingZeros().toPlainString();
            return shown + "e" + (exponent < 0 ? "-" : "+") + digits;
        }
        if (n == Math.rint(n) && magnitude < 1e15) return Long.toString((long) n);
        return shortest(n);
    }

    /** A double in its shortest plain form, as JavaScript's Number#toString gives it. */
    private static String shortest(double n) {
        return java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
    }
}
