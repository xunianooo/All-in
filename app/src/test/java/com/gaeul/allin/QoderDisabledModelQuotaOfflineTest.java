package com.gaeul.allin;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/**
 * Qoder's listing-off plus credits-spent 429, as opencode-qoder-auth 0.2.64 answers it
 * (index.mjs:2810-2843 spentOf, :3278-3300 the listing's off set, :3574-3594 the refusal).
 *
 * <p>Every fixture is a mock of what Qoder's own listing and usage envelope carry; nothing here
 * reaches a network, an account or a token. The blocks below hold the conservative boundaries the
 * plugin's JavaScript gets for free and this port has to state: an {@code enable} flag that is not a
 * boolean ({@code "false"} is <em>on</em> to JavaScript truthiness), a pool field that is present as
 * null (`undefined` vs `null` decide which spelling the plugin reads), an off set that came from an
 * old sync rather than from this request's listing, and a usage read that would otherwise be bounded
 * only by a socket timeout.
 */
public final class QoderDisabledModelQuotaOfflineTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why + " (check " + checks + ")"); }
    private static void eq(int got, int want, String why) {
        checks++;
        if (got != want) throw new AssertionError(why + ": got " + got + " want " + want);
    }
    private static void eq(String got, String want, String why) {
        checks++;
        if (got == null ? want != null : !got.equals(want)) throw new AssertionError(why + ": got \"" + got + "\" want \"" + want + "\"");
    }

    // ---- mocks: what Qoder's listing and usage envelope carry -------------------------------

    static JSONObject entry(String key, boolean enabled, String name) throws Exception {
        return new JSONObject().put("key", key).put("enable", enabled).put("display_name", name);
    }
    /** A marker for "the entry carries no enable field at all". */
    static final Object ABSENT = new Object();
    static JSONObject raw(Object enable) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("key", "flag").put("display_name", "Flag");
            if (enable != ABSENT) entry.put("enable", enable);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return entry;
    }
    static JSONObject listing(JSONArray app, JSONArray chat) throws Exception {
        return new JSONObject().put("app", app).put("chat", chat);
    }
    static JSONObject one(JSONObject entry) throws Exception {
        return listing(new JSONArray().put(entry), new JSONArray());
    }
    /** What QoderAuth's catalog keeps as its off set: {"key","name"} entries. */
    static Set<String> liveOff(String... keys) throws Exception {
        JSONArray kept = new JSONArray();
        for (String key : keys) kept.put(new JSONObject().put("key", key).put("name", key));
        return QoderCatalog.statedOffKeys(kept);
    }
    static JSONObject pool(double total, double used) throws Exception {
        return new JSONObject().put("total", total).put("used", used);
    }
    static JSONObject usage(JSONObject... pools) throws Exception {
        JSONObject u = new JSONObject();
        String[] names = {"userQuota", "addOnQuota", "orgResourcePackage"};
        for (int i = 0; i < pools.length && i < names.length; i++) if (pools[i] != null) u.put(names[i], pools[i]);
        return new JSONObject().put("displayMode", "qoder").put("qoderUsage", u);
    }
    static JSONObject spent800() throws Exception { return usage(pool(800, 800)); }

    // ---- the listing's off set ------------------------------------------------------------

    static void catalog() throws Exception {
        // "app" first, then any "chat" model the app no longer lists, after the model it
        // followed there: app a,b,c and chat b,d gives a,b,d,c (index.mjs:968-985).
        JSONArray app = new JSONArray().put(entry("a", true, "A")).put(entry("b", true, "B")).put(entry("c", false, "C"));
        JSONArray chat = new JSONArray().put(entry("b", true, "B")).put(entry("d", false, "D"));
        JSONObject listed = listing(app, chat);
        List<String> keys = QoderCatalog.keys(listed);
        eq(keys.size() == 4 ? String.join(",", keys) : "size " + keys.size(), "a,b,d,c", "scene merge order");
        Set<String> off = QoderCatalog.off(listed);
        check(off.contains("c") && off.contains("d") && !off.contains("a") && !off.contains("b"), "off set");
        check(off.size() == 2, "off size");

        // Qoder's own routing keys are never a model, nor is an unusable key.
        JSONArray reserved = new JSONArray().put(entry("auto", false, "Auto")).put(entry("default", false, "Default"))
                .put(entry(" keep ", false, "Kept")).put(new JSONObject().put("key", " ").put("enable", false))
                .put(new JSONObject().put("enable", false));
        JSONObject reservedListing = listing(reserved, new JSONArray());
        Set<String> reservedOff = QoderCatalog.off(reservedListing);
        check(!reservedOff.contains("auto") && !reservedOff.contains("default") && !reservedOff.contains(""), "reserved dropped");
        check(reservedOff.contains("keep"), "key trimmed");
        eq(String.join(",", QoderCatalog.keys(reservedListing)), "keep", "named keys");

        // The {"key","name"} shape a catalog record keeps: display_name, else the key.
        JSONArray named = new JSONArray().put(entry("dfmodel", false, "DeepSeek")).put(entry("qfmodel", false, ""));
        List<JSONObject> disabled = QoderCatalog.disabled(listing(named, new JSONArray()));
        check(disabled.size() == 2, "disabled size");
        eq(disabled.get(0).getString("key"), "dfmodel", "disabled key");
        eq(disabled.get(0).getString("name"), "DeepSeek", "disabled name");
        eq(disabled.get(1).getString("name"), "qfmodel", "blank name falls back to key");

        // What a live listing states off, in the shape QoderAuth hands back.
        Set<String> live = liveOff("c", "d");
        check(live.contains("c") && live.contains("d") && live.size() == 2, "live off keys");
        check(liveOff().isEmpty(), "an empty live set states nothing");
        check(!QoderCatalog.statedOffKeys(new JSONArray().put("x").put("auto")).contains("auto"), "reserved never off");

        // A record keeps them for the panel, bounded, and a later read replaces them.
        JSONObject account = new JSONObject().put("id", "acc");
        QoderCatalog.rememberOff(account, listed);
        check(QoderCatalog.storedOff(account, "c") && QoderCatalog.storedOff(account, "d"), "recorded off");
        check(!QoderCatalog.storedOff(account, "a") && !QoderCatalog.storedOff(account, "never"), "recorded on/unknown");
        check(!QoderCatalog.storedOff(account, "auto"), "reserved never off");
        check(QoderCatalog.observedAt(account) > 0, "the record says when it was read");
        QoderCatalog.clearOff(account);
        check(!QoderCatalog.storedOff(account, "c"), "cleared");
        check(account.optJSONArray(QoderCatalog.OFF_FIELD) == null, "field removed");
        check(QoderCatalog.observedAt(account) == 0, "stamp removed with it");
        QoderCatalog.rememberOff(account, listing(new JSONArray(), new JSONArray()));
        check(account.optJSONArray(QoderCatalog.OFF_FIELD) == null, "empty read keeps nothing");
        check(QoderCatalog.observedAt(account) == 0, "an empty read stamps nothing");

        JSONArray many = new JSONArray();
        for (int i = 0; i < QoderCatalog.MAX_OFF + 4; i++) many.put(entry("m" + i, false, "M" + i));
        QoderCatalog.rememberOff(account, listing(many, new JSONArray()));
        check(account.getJSONArray(QoderCatalog.OFF_FIELD).length() == QoderCatalog.MAX_OFF, "bounded record");
        check(QoderCatalog.storedOff(account, "m0") && !QoderCatalog.storedOff(account, "m9999"), "bounded keys");

        // A record written before this field existed says nothing at all.
        check(QoderCatalog.storedOffKeys(new JSONObject()).isEmpty(), "no field, no statement");
        check(!QoderCatalog.storedOff(new JSONObject(), "c"), "no field is not off");
        check(!QoderCatalog.storedOff(null, "c"), "no account is not off");
        JSONObject plain = new JSONObject().put(QoderCatalog.OFF_FIELD, new JSONArray().put("x"));
        check(QoderCatalog.storedOff(plain, "x"), "plain string entries read");
    }

    // ---- B2: an enable flag is off only when it says so -------------------------------------

    static void truthiness() throws Exception {
        // Explicit statements of "off": the boolean false and the number 0.
        check(QoderCatalog.statedOff(raw(Boolean.FALSE)), "boolean false is off");
        check(QoderCatalog.statedOff(raw(Integer.valueOf(0))), "numeric 0 is off");
        check(QoderCatalog.statedOff(raw(Double.valueOf(0d))), "zero double is off");
        // A statement of "on".
        check(!QoderCatalog.statedOff(raw(Boolean.TRUE)), "boolean true is on");
        check(!QoderCatalog.statedOff(raw(Integer.valueOf(1))), "numeric 1 is on");

        // The string "false" is TRUTHY in JavaScript, so the plugin reads that entry as on.
        // Reading it as off here would answer 429 for a model Qoder serves.
        check(!QoderCatalog.statedOff(raw("false")), "the string \"false\" is not off");
        check(!QoderCatalog.statedOff(raw("0")), "the string \"0\" is not off");
        check(!QoderCatalog.statedOff(raw("")), "the empty string is not off");
        check(!QoderCatalog.statedOff(raw("true")), "the string \"true\" is not off");

        // Nothing stated is nothing confirmed.
        check(!QoderCatalog.statedOff(raw(ABSENT)), "an absent flag is not confirmed off");
        check(!QoderCatalog.statedOff(raw(JSONObject.NULL)), "a null flag is not confirmed off");
        check(!QoderCatalog.statedOff(raw(new JSONObject())), "an object flag is not confirmed off");
        check(!QoderCatalog.statedOff(raw(new JSONArray())), "an array flag is not confirmed off");
        check(!QoderCatalog.statedOff(null), "no entry is not off");

        // And the set the listing gives agrees with the single-entry answer.
        check(QoderCatalog.off(one(raw("false"))).isEmpty(), "a string-false entry stays out of the off set");
        check(QoderCatalog.off(one(raw("0"))).isEmpty(), "\"0\" stays out of the off set");
        check(QoderCatalog.off(one(raw(ABSENT))).isEmpty(), "a flagless entry stays out");
        check(QoderCatalog.off(one(raw(Boolean.FALSE))).contains("flag"), "a stated off entry is in the set");
        // The record shape follows the same rule.
        check(QoderCatalog.disabled(one(raw("false"))).isEmpty(), "no record entry for a string flag");
        check(QoderCatalog.disabled(one(raw(Boolean.FALSE))).size() == 1, "record entry for a stated off");

        // End to end: a listing that says "enable": "false" is never a quota, whatever the account
        // spent — the model is on to Qoder's client.
        eq(QoderRefusal.status(QoderRefusal.disabledModel(liveOff(), "flag", spent800())), 0,
                "a listing that does not state it off is never a 429");
        eq(QoderRefusal.status(QoderRefusal.disabledModel(liveOff("flag"), "flag", spent800())), 429,
                "a stated off is a 429 when the credits are gone");
    }

    // ---- B7: presence, not null-ness, picks the pool field ----------------------------------

    static void presenceVersusNull() throws Exception {
        // The plugin reads `u[a] !== undefined ? u[a] : u[b]`, then skips a null pool. A camel name
        // present as null therefore hides the snake_case one; reading the snake name anyway would
        // count a pool the plugin ignores.
        JSONObject camelNull = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("userQuota", JSONObject.NULL)
                .put("user_quota", new JSONObject().put("total", 800).put("used", 800)));
        eq(QoderCredits.spent(camelNull), "", "a camel pool present as null hides the snake one");

        JSONObject snakeNull = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("user_quota", JSONObject.NULL));
        eq(QoderCredits.spent(snakeNull), "", "a snake pool present as null states nothing");

        // Absent (undefined) is the only case that falls back.
        JSONObject camelAbsent = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("user_quota", new JSONObject().put("total", 800).put("used", 800)));
        eq(QoderCredits.spent(camelAbsent), "800 / 800 credits", "an absent camel name falls back to snake");

        // The same rule for the add-on pool: a null camel add-on hides a spent snake add-on, so the
        // account is spent on its plan alone rather than crediting a pool Qoder sent as null.
        JSONObject addOn = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("userQuota", pool(800, 800))
                .put("addOnQuota", JSONObject.NULL)
                .put("add_on_quota", pool(200, 0)));
        eq(QoderCredits.spent(addOn), "800 / 800 credits", "a null add-on hides the snake add-on");

        // A present-but-scalar pool is skipped too, and is not replaced by the other spelling.
        JSONObject scalar = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("userQuota", "800")
                .put("user_quota", new JSONObject().put("total", 800).put("used", 800)));
        eq(QoderCredits.spent(scalar), "", "a scalar camel pool hides the snake one");

        // Inner fields keep the plugin's `??` / typeof rule: a null total falls through to cap, a
        // null used falls through to remaining.
        JSONObject inner = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("userQuota", new JSONObject().put("total", JSONObject.NULL).put("cap", 500)
                        .put("used", JSONObject.NULL).put("remaining", 0)));
        eq(QoderCredits.spent(inner), "500 / 500 credits", "null inner fields fall through");

        // And the decision that consumes it never invents a quota out of a null pool.
        eq(QoderRefusal.status(QoderRefusal.disabledModel(liveOff("flag"), "flag", camelNull)), 0,
                "a null camel pool is no quota");
        JSONObject account = new JSONObject().put("id", "acc");
        QoderCatalog.remember(account, QoderCatalog.disabled(one(entry("flag", false, "Flag"))));
        QoderCatalog.rememberOff(new JSONObject().put("id", "acc"), one(entry("flag", false, "Flag")));
    }

    // ---- the credits a usage envelope states ----------------------------------------------

    static void credits() throws Exception {
        eq(QoderCredits.spent(spent800()), "800 / 800 credits", "spent");
        eq(QoderCredits.spent(usage(pool(800, 100))), "", "credits left");
        eq(QoderCredits.spent(usage(pool(800, 801))), "800 / 800 credits", "used clamped to the pool");
        eq(QoderCredits.spent(null), "", "no envelope");
        eq(QoderCredits.spent(new JSONObject()), "", "empty envelope");
        eq(QoderCredits.spent(new JSONObject().put("displayMode", "enterprise").put("qoderUsage", new JSONObject())), "", "enterprise");
        eq(QoderCredits.spent(new JSONObject().put("displayMode", "qoder")), "", "no usage object");
        eq(QoderCredits.spent(new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONArray())), "", "usage is not an object");

        // Pools spent in turn count together: a plan used up beside add-on credits is not spent.
        eq(QoderCredits.spent(usage(pool(800, 800), pool(200, 50))), "", "add-on credits left");
        eq(QoderCredits.spent(usage(pool(800, 800), pool(200, 200))), "1000 / 1000 credits", "both spent");
        eq(QoderCredits.spent(usage(pool(800, 800), pool(0, 0))), "800 / 800 credits", "empty pool adds nothing");
        eq(QoderCredits.spent(usage(pool(0, 0))), "0 / 0 credits", "a free account's empty pool");

        // snake_case fields, the cap name, and remaining instead of used.
        JSONObject snake = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("user_quota", new JSONObject().put("total", 800).put("used", 800)));
        eq(QoderCredits.spent(snake), "800 / 800 credits", "snake_case pool");
        JSONObject capped = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("cap", 500).put("used", 500)));
        eq(QoderCredits.spent(capped), "500 / 500 credits", "cap name");
        JSONObject left = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", 800).put("remaining", 0)));
        eq(QoderCredits.spent(left), "800 / 800 credits", "remaining instead of used");
        JSONObject someLeft = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", 800).put("remaining", 20)));
        eq(QoderCredits.spent(someLeft), "", "remaining keeps credits");

        // Anything unreadable says nothing rather than "spent".
        JSONObject text = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", 800).put("used", "800")));
        eq(QoderCredits.spent(text), "", "a string count is no count");
        JSONObject noUsed = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", 800)));
        eq(QoderCredits.spent(noUsed), "", "no used or remaining");
        JSONObject negative = new JSONObject().put("displayMode", "qoder").put("qoderUsage",
                new JSONObject().put("userQuota", new JSONObject().put("total", -1).put("used", -1)));
        eq(QoderCredits.spent(negative), "", "negative pool skipped");
        JSONObject dedicated = new JSONObject().put("displayMode", "qoder").put("qoderUsage", new JSONObject()
                .put("userQuota", JSONObject.NULL)
                .put("dedicatedResourcePackages", new JSONArray().put(pool(100, 100))));
        eq(QoderCredits.spent(dedicated), "", "dedicated credits are not spent in turn");
        check(QoderCredits.exhausted(spent800()), "exhausted");
        check(!QoderCredits.exhausted(null) && !QoderCredits.exhausted(usage(pool(800, 0))), "not exhausted");

        // Qoder's own panel writes the count plainly; the plugin's gfmt only changes shape
        // outside 1e-4..1e6 (index.mjs:2762-2767).
        eq(QoderCredits.count(800), "800", "plain count");
        eq(QoderCredits.count(2000), "2000", "no separator");
        eq(QoderCredits.count(183.5), "183.5", "fraction");
        eq(QoderCredits.count(0), "0", "zero");
        eq(QoderCredits.count(1500000), "1.5e+06", "large");
        eq(QoderCredits.count(0.00005), "5e-05", "small");
    }

    // ---- the refusal, and the boundary around it ------------------------------------------

    static void refusal() throws Exception {
        Set<String> live = liveOff("qwen", "dfmodel");
        JSONObject account = new JSONObject().put("id", "acc");

        JSONObject r = QoderRefusal.disabledModel(live, "dfmodel", spent800());
        check(r != null, "off and spent is a refusal");
        check(QoderRefusal.status(r) == 429, "429");
        eq(r.optString("error_kind"), "quota", "kind");
        eq(QoderRefusal.message(r), "usage limit reached: the account's credits are used up (800 / 800 credits), "
                + "and Qoder lists \"dfmodel\" off for it until they refresh", "Qoder's sentence");
        eq(r.optString("sign_in"), "", "the sign-in is untouched");

        // Credits left, or no usage at all: the answer stays what it is today.
        check(QoderRefusal.disabledModel(live, "dfmodel", usage(pool(800, 10))) == null, "credits left");
        check(QoderRefusal.disabledModel(live, "dfmodel", (JSONObject) null) == null, "no usage read");
        check(QoderRefusal.disabledModel(live, "dfmodel", new JSONObject()) == null, "empty usage");
        check(QoderRefusal.disabledModel(live, "dfmodel",
                new JSONObject().put("displayMode", "enterprise")) == null, "usage of another kind");

        // A model the listing serves is never this refusal, whatever the account spent.
        check(QoderRefusal.disabledModel(liveOff("qwen"), "qfmodel", spent800()) == null, "model on");
        // A model the listing never named is unknown, which is the answer it already gets.
        check(QoderRefusal.disabledModel(live, "gpt-x", spent800()) == null, "model never served");
        // Qoder's routing keys are no model.
        check(QoderRefusal.disabledModel(live, "auto", spent800()) == null, "auto");
        check(QoderRefusal.disabledModel(live, " default ", spent800()) == null, "default");
        check(QoderRefusal.disabledModel(live, "  ", spent800()) == null, "blank model");
        check(QoderRefusal.disabledModel(live, null, spent800()) == null, "null model");
        // No live listing at all: no confirmation, whatever a stored record says.
        QoderCatalog.rememberOff(account, one(entry("dfmodel", false, "dfmodel")));
        check(QoderRefusal.disabledModel((Set<String>) null, "dfmodel", spent800()) == null, "no live listing, no refusal");
        check(QoderCatalog.storedOff(account, "dfmodel"), "the stored record still says so, for the panel only");

        // The multi-account sentence quotes each account for itself and names none of them.
        JSONObject two = QoderRefusal.confirmed(live, "dfmodel", Arrays.asList("800 / 800 credits", "1,000 / 1,000 credits"));
        String said = QoderRefusal.message(two);
        check(said.contains("the accounts' credits are used up"), "plural wording: " + said);
        check(said.contains("account 1: 800 / 800 credits"), "account 1's own count: " + said);
        check(said.contains("account 2: 1,000 / 1,000 credits"), "account 2's own count: " + said);
        check(!said.contains("all 2 accounts"), "no single count lent to all: " + said);
        check(said.contains("off for them until they refresh"), "plural pronoun: " + said);
        // Nothing of the account's identity travels in it.
        for (String secret : new String[]{"uid", "email", "machine", "token", "@", "Bearer"})
            check(!said.contains(secret), "the sentence names no account: " + secret + " in " + said);

        // Three accounts keep their own order and their own numbers.
        String three = QoderRefusal.message(QoderRefusal.confirmed(live, "dfmodel",
                Arrays.asList("1 / 1 credits", "2 / 2 credits", "3 / 3 credits")));
        check(three.contains("account 1: 1 / 1 credits; account 2: 2 / 2 credits; account 3: 3 / 3 credits"),
                "each account keeps its own reading: " + three);
        // A list with an unreadable entry confirms nothing.
        check(QoderRefusal.confirmed(live, "dfmodel", Arrays.asList("800 / 800 credits", "")) == null,
                "an unreadable account confirms nothing");
        check(QoderRefusal.confirmed(live, "dfmodel", new ArrayList<String>()) == null, "no accounts, no refusal");
        check(QoderRefusal.confirmed(null, "dfmodel", Arrays.asList("800 / 800 credits")) == null, "no listing, no refusal");
        check(QoderRefusal.confirmed(liveOff("other"), "dfmodel", Arrays.asList("800 / 800 credits")) == null,
                "not stated off, no refusal");

        // Nothing of the usage envelope's other fields travels in the refusal.
        JSONObject noisy = spent800();
        noisy.put("email", "someone@example.com").put("token", "secret-token");
        JSONObject quiet = QoderRefusal.disabledModel(live, "dfmodel", noisy);
        check(quiet != null && !quiet.toString().contains("secret") && !quiet.toString().contains("example.com"),
                "no echo of the envelope");
        check(QoderRefusal.status(null) == 0 && QoderRefusal.message(null).isEmpty(), "null accessors");
        eq(QoderRefusal.disabled(one(entry("dfmodel", false, "D"))).size(), 1, "disabled entries exposed");
        check(QoderRefusal.statedOff(live, "dfmodel") && !QoderRefusal.statedOff(live, "qfmodel"), "statedOff");

        // The exception shape a request path carries.
        QoderRefusal.Quota quota = QoderRefusal.quota(live, "dfmodel", spent800());
        check(quota != null, "quota exception");
        check(quota.status() == 429, "quota status");
        eq(quota.said(), QoderRefusal.message(QoderRefusal.disabledModel(live, "dfmodel", spent800())), "quota sentence");
        eq(quota.getMessage(), quota.said(), "the message is the sentence");
        eq(quota.signIn(), "", "no sign-in verdict");
        check(QoderRefusal.quota(liveOff("qwen"), "qfmodel", spent800()) == null, "no quota when the model is on");
    }

    // ---- the usage read: address, single bounded status, deadline, cancellation --------------

    /** A loopback stand-in for Qoder's usage page: a status, a body, a drip, or a slow header. */
    static final class Fake implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool();
        final List<String> urls = new java.util.ArrayList<String>();
        final AtomicInteger calls = new AtomicInteger();
        volatile int status = 200;
        volatile String body = "";
        volatile boolean tooLarge, drip;
        volatile long dripMillis = 40;
        volatile long headerDelayMillis;
        volatile String seenAuth = "", seenPath = "", seenAgent = "", seenType = "";
        volatile boolean stopped;
        Fake() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                seenAuth = exchange.getRequestHeaders().getFirst("Authorization");
                seenAgent = exchange.getRequestHeaders().getFirst("User-Agent");
                seenType = exchange.getRequestHeaders().getFirst("Cosy-ClientType");
                seenPath = exchange.getRequestURI().getPath();
                if (headerDelayMillis > 0) {
                    try { Thread.sleep(headerDelayMillis); } catch (InterruptedException stopped) { return; }
                }
                if (drip) {
                    // Chunked, one byte at a time: every individual read succeeds, the body never
                    // ends before the caller's own deadline has to stop it.
                    exchange.sendResponseHeaders(200, 0);
                    java.io.OutputStream out = exchange.getResponseBody();
                    long until = System.currentTimeMillis() + 20000;
                    try {
                        while (!stopped && System.currentTimeMillis() < until) {
                            out.write(' ');
                            out.flush();
                            Thread.sleep(dripMillis);
                        }
                    } catch (InterruptedException | java.io.IOException stop) {
                        // the caller disconnected: expected
                    } finally {
                        try { out.close(); } catch (java.io.IOException ignored) { }
                    }
                    return;
                }
                byte[] bytes = tooLarge
                        ? new byte[QoderUsage.MAX_BYTES + 1024]
                        : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.setExecutor(pool);
            server.start();
        }
        QoderUsage.Transport transport() {
            return url -> {
                urls.add(url);
                return (HttpURLConnection) java.net.URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort()
                                + java.net.URI.create(url).getPath()).toURL().openConnection();
            };
        }
        public void close() { stopped = true; server.stop(0); pool.shutdownNow(); }
    }

    /** A budget a test drives: time left, and whether the request was cancelled. */
    static final class Budget implements QoderUsage.Budget {
        long left = Long.MAX_VALUE;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger attached = new AtomicInteger();
        volatile String why = "";
        public long remainingMillis() { return left; }
        public boolean cancelled() { return cancelled.get(); }
        public String reason() { return why; }
        public void attach(HttpURLConnection connection) throws Exception { attached.incrementAndGet(); }
        public void detach(HttpURLConnection connection) { }
    }

    static void usageRead() throws Exception {
        try (Fake fake = new Fake()) {
            // The one address, exactly.
            eq(QoderUsage.url(), "https://openapi.qoder.com.cn/sash/api/v2/me/usage", "the usage address");
            fake.body = spent800().toString();
            JSONObject env = QoderUsage.read("dev-token", 5000, fake.transport());
            eq(QoderCredits.spent(env), "800 / 800 credits", "read envelope");
            eq(fake.urls.size() == 1 ? fake.urls.get(0) : "calls=" + fake.urls.size(),
                    "https://openapi.qoder.com.cn/sash/api/v2/me/usage", "exact url, scheme host and path");
            eq(fake.seenAuth, "Bearer dev-token", "device token sent");
            eq(fake.seenPath, "/sash/api/v2/me/usage", "usage path");
            eq(fake.seenAgent, "Qoder", "client agent");
            eq(fake.seenType, "10", "client type");
            eq(fake.calls.get(), 1, "exactly one request");

            // A slow header leg is allowed the whole budget: the status is asked once, with the
            // whole budget as the read timeout, not a 200 ms slice.
            fake.headerDelayMillis = 600;
            int before = fake.calls.get();
            JSONObject slowHeaders = QoderUsage.read("dev-token", 5000, fake.transport());
            eq(QoderCredits.spent(slowHeaders), "800 / 800 credits", "a slow header leg still reads");
            eq(fake.calls.get() - before, 1, "and it was asked once, not retried");
            fake.headerDelayMillis = 0;

            fake.status = 401;
            boolean failed = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport()); } catch (java.io.IOException e) {
                failed = e.getMessage() != null && e.getMessage().contains("401");
            }
            check(failed, "a refused read is a failure, not a verdict");
            fake.status = 200;

            fake.body = "<html>nope</html>";
            failed = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport()); } catch (java.io.IOException e) { failed = true; }
            check(failed, "a body that is not an envelope");
            fake.body = "{\"displayMode\":\"qoder\"}";
            failed = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport()); } catch (java.io.IOException e) { failed = true; }
            check(failed, "a qoder envelope without its quota data");
            fake.body = "{\"displayMode\":\"other\"}";
            failed = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport()); } catch (java.io.IOException e) { failed = true; }
            check(failed, "another display mode");

            // An enterprise account's envelope is read, and says nothing about these pools.
            fake.body = "{\"displayMode\":\"enterprise\"}";
            eq(QoderCredits.spent(QoderUsage.read("dev-token", 5000, fake.transport())), "", "enterprise usage");

            // A body past the ceiling fails rather than growing.
            fake.body = "";
            fake.tooLarge = true;
            failed = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport()); } catch (java.io.IOException e) { failed = true; }
            check(failed, "bounded body");
            fake.tooLarge = false;
            fake.body = spent800().toString();

            failed = false;
            try { QoderUsage.read("  ", 5000, fake.transport()); } catch (IllegalArgumentException e) { failed = true; }
            check(failed, "no token, no read");
            failed = false;
            try { QoderUsage.read("dev-token", 5000, null); } catch (IllegalArgumentException e) { failed = true; }
            check(failed, "no transport, no read");
        }

        // B3: a body that drips forever cannot outrun the deadline, and leaves no thread behind.
        try (Fake fake = new Fake()) {
            fake.drip = true;
            fake.dripMillis = 40;
            Budget budget = new Budget();
            int threadsBefore = liveThreads();
            long began = System.currentTimeMillis();
            String why = "";
            try {
                QoderUsage.read("dev-token", 400, fake.transport(), budget);
            } catch (java.io.IOException expected) {
                why = String.valueOf(expected.getMessage());
            }
            long elapsed = System.currentTimeMillis() - began;
            check(why.contains("timed out"), "a drip is stopped by the deadline: " + why);
            check(elapsed < 2000, "and it stops near the deadline, took " + elapsed + "ms");
            eq(budget.attached.get(), 1, "the connection was registered so a cancel can close it");
            check(liveThreads() - threadsBefore <= 2, "no thread is left behind: " + (liveThreads() - threadsBefore));
        }

        // B3: a request's own remaining time caps the read even when the timeout is generous.
        try (Fake fake = new Fake()) {
            fake.drip = true;
            fake.dripMillis = 40;
            Budget budget = new Budget();
            budget.left = 400;
            long began = System.currentTimeMillis();
            boolean stopped = false;
            try { QoderUsage.read("dev-token", 20000, fake.transport(), budget); }
            catch (java.io.IOException expected) { stopped = true; }
            long elapsed = System.currentTimeMillis() - began;
            check(stopped, "the request's remaining time ends the read");
            check(elapsed < 2200, "bounded by the budget, took " + elapsed + "ms");
        }

        // B3: a cancelled request stops at once, without opening anything.
        try (Fake fake = new Fake()) {
            Budget budget = new Budget();
            budget.cancelled.set(true);
            budget.why = "client closed";
            boolean cancelled = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport(), budget); }
            catch (java.io.IOException expected) { cancelled = String.valueOf(expected.getMessage()).contains("canceled"); }
            check(cancelled, "a cancelled request never reads");
            eq(fake.calls.get(), 0, "and opens nothing");
        }

        // B3: a budget with no time left fails instead of waiting.
        try (Fake fake = new Fake()) {
            Budget budget = new Budget();
            budget.left = 0;
            boolean refused = false;
            try { QoderUsage.read("dev-token", 5000, fake.transport(), budget); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused, "no time left, no read");
            eq(fake.calls.get(), 0, "no connection either");
        }

        // B3: a cancellation that lands mid-drip ends the read without waiting for the deadline.
        try (Fake fake = new Fake()) {
            fake.drip = true;
            fake.dripMillis = 40;
            final Budget late = new Budget();
            late.left = 20000;
            Thread canceller = new Thread(() -> {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                late.why = "client closed";
                late.cancelled.set(true);
                fake.stopped = true;
            }, "quota-cancel");
            canceller.setDaemon(true);
            canceller.start();
            long began = System.currentTimeMillis();
            boolean stopped = false;
            try { QoderUsage.read("dev-token", 20000, fake.transport(), late); }
            catch (java.io.IOException expected) { stopped = true; }
            long elapsed = System.currentTimeMillis() - began;
            canceller.join(2000);
            check(stopped, "a cancel ends the read");
            check(elapsed < 5000, "well before the deadline, took " + elapsed + "ms");
        }
    }

    /** Threads alive in this JVM's root group, for the "nothing left running" checks. */
    static int liveThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        return root.activeCount();
    }

    public static void main(String[] args) throws Exception {
        catalog();
        truthiness();
        presenceVersusNull();
        credits();
        refusal();
        usageRead();
        System.out.println("QoderDisabledModelQuotaOfflineTest ok: " + checks + " checks");
    }
}
