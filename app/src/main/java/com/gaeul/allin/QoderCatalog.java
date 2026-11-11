package com.gaeul.allin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Qoder's model listing, read the way the vendored plugin reads it, and the one thing a request
 * needs from it: whether the account's listing names a model and has it <em>off</em>.
 *
 * <p>Two places ask that. The catalog sync (QoderAuth.readCatalog) reports the off keys of the
 * listing it just read; a request asks about one model, and the plugin answers it from the listing
 * it holds (index.mjs:3278-3300: {@code l.off} is every key the scene names with {@code enable}
 * false). This class holds that answer in one place: {@link #off(JSONObject)} over a listing, and
 * {@link #statedOffKeys(JSONArray)} over a listing read at request time.
 *
 * <p>Two boundaries are deliberate, and both are the conservative side of the plugin:
 *
 * <ul>
 *   <li><b>Only a stated "off" counts.</b> The plugin reads the flag by JavaScript truthiness
 *       ({@code !m.enable}), where the string {@code "false"} is truthy — so a listing that carries
 *       {@code "enable": "false"} is <em>on</em> to it. Android reads that flag as a string and used
 *       to call it off, which would have answered 429 for a model Qoder serves. Here only an
 *       explicit boolean {@code false} (or the number 0) is off; a string, an absent flag, any
 *       other type, and Qoder's own routing keys are all "not confirmed off" — the caller then
 *       keeps the answer it gives today.</li>
 *   <li><b>A stored off set never authorizes a refusal.</b> A sync's off set is a statement about
 *       the moment it was read, so it is kept for the panel and for a person looking at the record,
 *       never for a 429: Qoder turns a model back on as soon as the credits refresh, and an hours
 *       old set would then refuse a model that works. A refusal is judged by a listing read at
 *       request time ({@link QoderRefusal}); this class only says what a listing states.</li>
 * </ul>
 *
 * <p>Nothing here reads the network or an account's token: a listing is JSON the caller already
 * has, and a record is the account object the gateway stores.
 */
public final class QoderCatalog {
    private QoderCatalog() {}

    /** The account field a catalog sync writes the listing's off models into. */
    public static final String OFF_FIELD = "disabledModels";

    /** The account field saying when that off set was read. */
    public static final String OFF_OBSERVED_FIELD = "disabledObservedAt";

    /**
     * How long a stored off set is kept as a *hint* for the panel. Nothing decides anything from
     * it: a refusal needs a listing read at request time (see {@link QoderRefusal}), so this number
     * only bounds what an operator is shown.
     */
    public static final long OFF_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /** The off models one account record keeps, and the longest key kept. */
    public static final int MAX_OFF = 256;
    static final int MAX_KEY = 128;

    /** The keys that route inside Qoder and are never a model a request names. */
    private static final List<String> RESERVED = Arrays.asList("auto", "default");

    /**
     * The listing's entries in the order Qoder's client merges its scenes ({@code sceneModels},
     * index.mjs:968-985): the "app" scene's, which is what a request names, then any "chat" model
     * the app no longer lists, after the model it followed there. Each key once: app's entry wins.
     */
    public static List<JSONObject> entries(JSONObject listing) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (listing == null) return out;
        for (JSONObject m : array(listing.opt("app"))) out.add(m);
        int after = -1;
        for (JSONObject m : array(listing.opt("chat"))) {
            String key = key(m.opt("key"));
            if (key.isEmpty()) continue;
            int found = -1;
            for (int i = 0; i < out.size(); i++) {
                if (key.equals(key(out.get(i).opt("key")))) { found = i; break; }
            }
            if (found >= 0) { after = found; continue; }
            after++;
            out.add(after, m);
        }
        return out;
    }

    /** Every model the listing names, each once, in the order above. */
    public static List<String> keys(JSONObject listing) {
        Set<String> out = new LinkedHashSet<String>();
        for (JSONObject m : entries(listing)) {
            String key = key(m.opt("key"));
            if (!key.isEmpty()) out.add(key);
        }
        return new ArrayList<String>(out);
    }

    /** The models the listing names and states as off ({@link #statedOff(JSONObject)}). */
    public static Set<String> off(JSONObject listing) {
        Set<String> out = new LinkedHashSet<String>();
        for (JSONObject m : entries(listing)) {
            String key = key(m.opt("key"));
            if (!key.isEmpty() && statedOff(m)) out.add(key);
        }
        return out;
    }

    /** The off models in the shape a catalog record keeps: {"key": …, "name": …}. */
    public static List<JSONObject> disabled(JSONObject listing) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject m : entries(listing)) {
            String key = key(m.opt("key"));
            if (key.isEmpty() || !statedOff(m)) continue;
            String name = m.optString("display_name", m.optString("name", "")).trim();
            if (name.isEmpty() || name.length() > MAX_KEY) name = key;
            out.add(new JSONObject().put("key", key).put("name", name));
            if (out.size() >= MAX_OFF) break;
        }
        return out;
    }

    /**
     * Whether a listing entry <em>states</em> that Qoder has it off: an explicit boolean false, or
     * the number 0. Everything else is not a statement:
     *
     * <ul>
     *   <li>the string {@code "false"} — JavaScript truthiness makes that entry <b>on</b> to the
     *       plugin ({@code !"false"} is false), so calling it off here would be a 429 for a model
     *       Qoder serves;</li>
     *   <li>an absent flag or a null — the plugin would read it as off, but nothing was stated, and
     *       a wrong 429 is worse than the 400 it replaces;</li>
     *   <li>any other type (an object, an array).</li>
     * </ul>
     */
    static boolean statedOff(JSONObject entry) {
        if (entry == null) return false;
        Object value = entry.opt("enable");
        if (value instanceof Boolean) return !((Boolean) value).booleanValue();
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            return !Double.isNaN(n) && !Double.isInfinite(n) && n == 0d;
        }
        return false;
    }

    /**
     * The off keys a listing read at request time states, in the shape QoderAuth's catalog keeps
     * them ({"key","name"}). This is the only off input a refusal may use: it comes from a listing
     * read for this request, not from a record written hours ago.
     */
    public static Set<String> statedOffKeys(JSONArray off) {
        Set<String> out = new LinkedHashSet<String>();
        if (off == null) return out;
        for (int i = 0; i < off.length() && out.size() < MAX_OFF; i++) {
            Object item = off.opt(i);
            String key = item instanceof JSONObject ? key(((JSONObject) item).opt("key")) : key(item);
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }

    /**
     * The off keys an account's record kept from its last sync, in the record's order. A record
     * from before the sync kept them has none.
     *
     * <p><b>Never authorizes a refusal.</b> It is a statement about the moment that sync ran, and
     * Qoder turns a model back on when its credits refresh; use it to show a person what the last
     * sync saw, and judge a request by a listing read at request time.
     */
    public static Set<String> storedOffKeys(JSONObject account) {
        if (account == null) return new LinkedHashSet<String>();
        Object value = account.opt(OFF_FIELD);
        return value instanceof JSONArray ? statedOffKeys((JSONArray) value) : new LinkedHashSet<String>();
    }

    /** Whether the record's last sync stated this model off. A hint, never a refusal. */
    public static boolean storedOff(JSONObject account, String model) {
        String wanted = key(model);
        if (wanted.isEmpty()) return false;
        return storedOffKeys(account).contains(wanted);
    }

    /** When the record's off set was read, 0 when it does not say (a hint's own age, for the panel). */
    public static long observedAt(JSONObject account) {
        if (account == null) return 0L;
        Object value = account.opt(OFF_OBSERVED_FIELD);
        if (!(value instanceof Number)) return 0L;
        double n = ((Number) value).doubleValue();
        if (Double.isNaN(n) || Double.isInfinite(n) || n <= 0d) return 0L;
        return (long) n;
    }

    /**
     * Keep the off models of a listing just read on the account record, bounded, replacing what an
     * earlier read left: the listing that was read last is what a request is judged by. Keys
     * Qoder's app routes itself ("auto", "default") and unusable keys are dropped; an empty list
     * clears the field rather than keeping a stale one.
     */
    public static void rememberOff(JSONObject account, JSONObject listing) throws Exception {
        if (account == null) return;
        remember(account, disabled(listing));
    }

    /** The same, from the {"key","name"} entries a catalog read already turned its listing into. */
    public static void remember(JSONObject account, JSONArray disabled) throws Exception {
        List<JSONObject> entries = new ArrayList<JSONObject>();
        if (disabled != null) {
            for (int i = 0; i < disabled.length(); i++) {
                Object item = disabled.opt(i);
                if (item instanceof JSONObject) entries.add((JSONObject) item);
            }
        }
        remember(account, entries);
    }

    /**
     * The same, from a listing a catalog read already turned into {"key","name"} entries, stamped
     * with the moment it was read so a later request can tell how old the statement is.
     */
    public static void remember(JSONObject account, List<JSONObject> disabled) throws Exception {
        remember(account, disabled, System.currentTimeMillis());
    }

    /** The same, stamped with the given read time (0 removes the stamp). */
    public static void remember(JSONObject account, List<JSONObject> disabled, long observedAt) throws Exception {
        if (account == null) return;
        JSONArray kept = new JSONArray();
        Set<String> seen = new LinkedHashSet<String>();
        for (JSONObject entry : disabled == null ? new ArrayList<JSONObject>() : disabled) {
            String key = key(entry == null ? null : entry.opt("key"));
            if (key.isEmpty() || !seen.add(key)) continue;
            String name = entry.optString("name", "").trim();
            if (name.isEmpty() || name.length() > MAX_KEY) name = key;
            kept.put(new JSONObject().put("key", key).put("name", name));
            if (kept.length() >= MAX_OFF) break;
        }
        clearOff(account);
        if (kept.length() > 0) {
            account.put(OFF_FIELD, kept);
            if (observedAt > 0) account.put(OFF_OBSERVED_FIELD, observedAt);
        }
    }

    /** Forget what a record said about off models: the next request then confirms nothing. */
    public static void clearOff(JSONObject account) {
        if (account == null) return;
        account.remove(OFF_FIELD);
        account.remove(OFF_OBSERVED_FIELD);
    }

    /**
     * A model key as both sides of the question use it: Qoder's own, trimmed, printable ASCII and
     * bounded. Empty for anything that is not a key, so a malformed entry is never a model.
     */
    static String key(Object value) {
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > MAX_KEY) return "";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) return "";
        }
        if (RESERVED.contains(text)) return "";
        return text;
    }

    private static List<JSONObject> array(Object value) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (!(value instanceof JSONArray)) return out;
        JSONArray items = (JSONArray) value;
        for (int i = 0; i < items.length(); i++) {
            Object item = items.opt(i);
            if (item instanceof JSONObject) out.add((JSONObject) item);
        }
        return out;
    }
}
