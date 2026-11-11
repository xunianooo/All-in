package com.gaeul.allin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * Offline, immutable, request-scoped compatibility decisions. No credentials or I/O.
 *
 * <p>Two schemas share one engine. Schema 1 keeps the original four root fields and the original
 * six rule fields; {@code minimumEngineVersion} there is 1 or 2, where 1 means the document is
 * expressible in the first vocabulary and nothing more, and 2 means it may also use the native
 * Messages tuple and per-binding header subsets. Schema 2 adds exactly one root field,
 * {@code outputDefaults}, and requires {@code minimumEngineVersion} 2 — schema and engine are
 * pinned one-to-one so a document never means two things on two builds.
 *
 * <p>A build whose engine is older than the document requirement rejects the document whole, so a
 * v1 document must stay expressible in v1 terms. Symmetrically, an output default is only readable
 * from a schema 2 document: a schema 1 or engine 1 document cannot carry the field at all.
 *
 * <p>The vocabulary is closed. Each rule must match one exact provider/protocol/origin/path row,
 * may only name headers from that row's allowlist, and may never name a credential, session, or
 * transport header. Every row exists because a live consumer reads it, and a binding whose
 * consumer cannot absorb a partial set keeps the exact-set requirement at every engine version.
 *
 * <p>Each row carries two vocabularies. {@code v1Allowed} is the set a {@code minimumEngineVersion}
 * 1 document must name exactly, frozen at what 1.0.1 and 1.0.2 were signed against, so an already
 * published document can never change meaning. {@code allowed} is the engine 2 set, which may only
 * grow by names the row's live consumer actually applies; a name the transport would drop stays
 * unrepresentable rather than becoming a rule that silently does nothing.
 */
final class ProviderQuirks {
    /** Engine 2 = v1 vocabulary + native Messages tuple + per-binding header subsets + output defaults. */
    static final int ENGINE_VERSION = 2, MAX_BYTES = 32768, MAX_RULES = 32;
    /** Schema 2 = schema 1 plus {@code outputDefaults}; no other schema is representable. */
    static final int SCHEMA_VERSION = 2, MAX_OUTPUT_DEFAULTS = 64, MAX_OUTPUT = 2000000;
    private static final int MAX_HEADER_VALUE = 256, MAX_MODEL = 128;
    /** The one static session value signed v1 rules already carry. */
    private static final String LEGACY_CONVERSATION = "X-Conversation-ID";
    private static final String[] ROOT_FIELDS = {"schemaVersion", "minimumEngineVersion", "ruleVersion", "rules"};
    private static final String[] ROOT_FIELDS_V2 = {"schemaVersion", "minimumEngineVersion", "ruleVersion", "rules", "outputDefaults"};

    /** Providers an output default may name. Identity only; no endpoint is implied or derived. */
    private static final Set<String> OUTPUT_PROVIDERS = names("zcode", "minimax-code", "workbuddy");

    /**
     * Model identifiers: exact, wildcard-free, and printable. The leading character is
     * alphanumeric so a value can never start with {@code .}, {@code /}, {@code -}, or {@code +},
     * and {@code *} / {@code ?} are absent from the set, so a rule cannot become a pattern.
     */
    private static final String MODEL_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:/+-]{0," + (MAX_MODEL - 1) + "}";

    /**
     * Rule version: exactly three dot-separated parts, each either {@code 0} or a leading-zero-free
     * run of at most nine digits.
     *
     * <p>This is the same grammar {@link SignedQuirksStore#compare} orders. Keeping one grammar in
     * one place means a document that parses is always orderable by the store that installed it: a
     * pre-release suffix such as {@code 1.0.3-rc.1} used to survive parsing and then fail ordering
     * only after the signature had already been accepted, so the document was neither usable nor
     * obviously malformed. It is now refused at parse, before any signature is checked.
     */
    private static final String VERSION_PATTERN =
            "(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})";


    /**
     * The chat vocabulary exactly as 1.0.1 and 1.0.2 were signed against. Frozen: a v1 document
     * must carry this exact set (plus, historically, the static conversation ID), so no future
     * build can quietly change what an already published document means. Nothing here may ever be
     * removed, renamed, or widened retroactively; {@link #CHAT_HEADERS} is the engine 2 set.
     */
    private static final Set<String> CHAT_HEADERS_V1 = names("User-Agent", "X-Requested-With", "X-Product",
            "X-Agent-Intent", "X-Agent-Purpose", "X-Agent-Type", "X-Auth-Refresh-Source");
    private static final Set<String> RACCOON_HEADERS = names("User-Agent", "Accept");
    /** The native Messages vocabulary as engine 2 first shipped it; frozen for the same reason. */
    private static final Set<String> NATIVE_HEADERS_V1 = names("User-Agent", "X-Requested-With", "X-Product",
            "X-IDE-Type", "X-IDE-Name", "X-IDE-Version", "X-Agent-Intent", "X-Agent-Purpose",
            "X-Agent-Type", "X-Agent-Runtime", "Accept");

    /**
     * The rest of the static client fingerprint, shared by both WorkBuddy routes. Every name here
     * is a literal in {@code WorkBuddyPolicy.endpointHeaders}: the SDK's language, package version
     * and platform tuple plus the retry count and timeout it announces. The real SDK would compute
     * the last two per attempt, but this client writes them as constants, so a rule restyles a
     * property of the installed build — never a value the transport recomputes for a given send.
     *
     * <p>Deliberately absent: {@code Accept-Encoding} (transport encoding — the chat path gzips its
     * body and the native path streams SSE), {@code anthropic-version} and {@code anthropic-beta}
     * (protocol framing, and the former is already reserved), {@code X-Stainless-Helper-Method}
     * (this client never sends it, so allowing it would make the fingerprint less like the client,
     * not more), and every credential, account or conversation name.
     */
    private static final Set<String> FINGERPRINT_STATIC = names("X-Stainless-Lang",
            "X-Stainless-Package-Version", "X-Stainless-OS", "X-Stainless-Arch",
            "X-Stainless-Runtime", "X-Stainless-Runtime-Version",
            "X-Stainless-Retry-Count", "X-Stainless-Timeout");

    /**
     * What only the chat route adds on top of the frozen seven: the IDE block, the agent runtime,
     * and the response type the policy asks for. {@code Accept} is here because
     * {@code WorkBuddyPolicy} writes it statically for this destination and the chat writer applies
     * it, so the two halves of the contract name the same twenty values.
     */
    private static final Set<String> CHAT_FINGERPRINT_EXTRA = names("X-IDE-Type", "X-IDE-Name",
            "X-IDE-Version", "X-Agent-Runtime", "Accept");

    /**
     * Engine 2 chat vocabulary: the frozen seven plus {@link #CHAT_FINGERPRINT_EXTRA} plus
     * {@link #FINGERPRINT_STATIC}, which is exactly the set {@code GatewayServer.CHAT_RULE_HEADERS}
     * applies. A name outside it stays unrepresentable rather than becoming a rule the transport
     * silently drops, so both halves must change together —
     * {@code WorkBuddyChatRuleOrderingHttpTest.writerAllowlistMatchesTheChatVocabulary()} pins the
     * other half to the same twenty.
     */
    private static final Set<String> CHAT_HEADERS = union(union(CHAT_HEADERS_V1, CHAT_FINGERPRINT_EXTRA),
            FINGERPRINT_STATIC);

    /**
     * Engine 2 native vocabulary: the frozen eleven plus {@link #FINGERPRINT_STATIC}. This route
     * never sends {@code X-Auth-Refresh-Source}, which is the only difference from the chat set.
     * The native consumer ({@code WorkBuddyNativeTransport.RULE_HEADERS}) names the same nineteen,
     * so every name the engine accepts here is one that transport actually applies.
     */
    private static final Set<String> NATIVE_HEADERS = union(NATIVE_HEADERS_V1, FINGERPRINT_STATIC);

    /**
     * Credential, session, and transport names. The gateway applies rule headers after it sets
     * {@code Authorization} on the connection, so no document may ever name one of these; the
     * single tolerated case is the static conversation ID inside an exact v1 rule set.
     */
    private static final Set<String> RESERVED = lowerNames("authorization", "proxy-authorization",
            "cookie", "set-cookie", "x-api-key", "api-key", "x-domain", "x-user-id",
            LEGACY_CONVERSATION, "x-conversation-request-id", "x-conversation-message-id",
            "x-request-id", "host", "content-type", "content-length", "content-encoding",
            "transfer-encoding", "connection", "x-forwarded-for", "anthropic-version");

    /**
     * Every destination a document may describe, and the names it may set there. Nothing else is
     * reachable: a rule is rejected unless it matches one of these rows exactly.
     *
     * <p>{@code subset} mirrors the consumer that reads the row. A binding whose consumer always
     * supplies the whole static fingerprint itself (WorkBuddy chat via WorkBuddyPolicy, the native
     * Messages transport via WorkBuddyPolicy.nativeHeaders) can be restyled partially: the rule
     * replaces the names it names and the vendor defaults cover the rest. Raccoon cannot, because
     * the gateway's fallback there is keyed on the rule map being empty — a partial raccoon rule
     * would suppress the fallback and leave the remaining names at the JDK default, which is
     * strictly worse than either the v1 rule or no rule at all. Raccoon therefore stays
     * all-or-nothing, exactly as in v1.
     */
    private static final List<Binding> BINDINGS = Collections.unmodifiableList(Arrays.asList(
            new Binding("workbuddy", "openai", "https://copilot.tencent.com", "/v2/chat/completions", 1, CHAT_HEADERS_V1, CHAT_HEADERS, true, true),
            new Binding("workbuddy-ai", "openai", "https://www.workbuddy.ai", "/v2/chat/completions", 1, CHAT_HEADERS_V1, CHAT_HEADERS, true, true),
            new Binding("raccoon", "openai", "https://xiaohuanxiong.com", "/api/web/llm/v2/chat/completions", 1, RACCOON_HEADERS, RACCOON_HEADERS, false, false),
            // Engine 2 only: the CN native Messages route. The protocol identity is "anthropic"
            // because that is the row the gateway actually consults for this destination —
            // GatewayServer.nativeMessagesQuirkHeaders looks this exact tuple up before the first
            // send. No row exists for ("workbuddy","openai","/v1/messages"): the chat path pins
            // WorkBuddy to /v2/chat/completions, so such a row could never be matched and would
            // only invite a signed rule that silently does nothing.
            new Binding("workbuddy", "anthropic", "https://copilot.tencent.com", "/v1/messages", 2, NATIVE_HEADERS_V1, NATIVE_HEADERS, false, true)));

    private static final class Binding {
        final String provider, protocol, origin, path;
        final int minEngine;
        /** Names a {@code minimumEngineVersion} 1 document may use: frozen, never widened. */
        final Set<String> v1Allowed;
        /** Names an engine 2 document may use; a superset of {@link #v1Allowed}. */
        final Set<String> allowed;
        /** v1: additionally accept a static conversation ID, as old signed rules did. */
        final boolean legacyConversation;
        /** Engine 2: may a rule supply a subset of {@link #allowed}? */
        final boolean subset;
        Binding(String provider, String protocol, String origin, String path, int minEngine,
                Set<String> v1Allowed, Set<String> allowed, boolean legacyConversation, boolean subset) {
            this.provider = provider; this.protocol = protocol; this.origin = origin; this.path = path;
            this.minEngine = minEngine;
            this.v1Allowed = v1Allowed; this.allowed = allowed;
            this.legacyConversation = legacyConversation; this.subset = subset;
        }
    }

    private static final class Rule {
        final String id, provider, protocol, origin, path;
        final Map<String, String> headers;
        Rule(String id, String p, String proto, String o, String path, Map<String, String> h) {
            this.id = id; provider = p; protocol = proto; origin = o; this.path = path;
            Map<String, String> copy = new LinkedHashMap<>(h);
            // The vocabulary is reserved-free. Keeping the check here means a Rule value cannot
            // carry a credential even if the parsing rules are ever loosened.
            for (String name : copy.keySet())
                if (isReservedHeaderName(name) && !LEGACY_CONVERSATION.equals(name))
                    throw new IllegalArgumentException("header");
            headers = Collections.unmodifiableMap(copy);
        }
    }

    static final class Snapshot {
        final int schemaVersion, engineVersion, minimumEngineVersion;
        final String ruleVersion, source;
        private final List<Rule> rules;
        /** provider + "\n" + model → output default; both parts exclude control characters. */
        private final Map<String, Integer> outputs;
        private Snapshot(int schema, String version, int minEngine, List<Rule> rs,
                         Map<String, Integer> out, String origin) {
            schemaVersion = schema; engineVersion = ENGINE_VERSION; minimumEngineVersion = minEngine;
            ruleVersion = version; source = origin;
            rules = Collections.unmodifiableList(new ArrayList<>(rs));
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(out));
        }
        Snapshot withSource(String source) {
            if (!"builtin".equals(source) && !"verified-cache".equals(source) && !"verified-update".equals(source)) throw new IllegalArgumentException("source");
            return new Snapshot(schemaVersion, ruleVersion, minimumEngineVersion, rules, outputs, source);
        }
        /**
         * Configured static reply limit for one exact provider and model, or 0 when the document
         * names none. 0 is unambiguous because a valid default is always positive. The Gateway
         * decides what a value is allowed to do; this table only records what was signed.
         */
        int outputDefault(String provider, String model) {
            if (provider == null || model == null) return 0;
            Integer value = outputs.get(provider + "\n" + model);
            return value == null ? 0 : value;
        }
        int outputDefaultCount() { return outputs.size(); }
        Map<String, String> headers(String provider, String protocol, String url) {
            URI u;
            try { u = new URI(url); } catch (Exception e) { return Collections.emptyMap(); }
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null
                    || u.getRawQuery() != null || u.getRawFragment() != null || (u.getPort() != -1 && u.getPort() != 443)) return Collections.emptyMap();
            String origin = "https://" + u.getHost().toLowerCase(Locale.ROOT);
            for (Rule r : rules) if (r.provider.equals(provider) && r.protocol.equals(protocol)
                    && r.origin.equals(origin) && r.path.equals(u.getRawPath())) return r.headers;
            return Collections.emptyMap();
        }
        int ruleCount() { return rules.size(); }
    }

    static Snapshot parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("utf8");
        new StrictJson(text).check();
        JSONTokener t = new JSONTokener(text); Object parsed = t.nextValue();
        if (!(parsed instanceof JSONObject) || t.nextClean() != 0) throw new IllegalArgumentException("root");
        JSONObject root = (JSONObject) parsed;
        // Schema 2 carries exactly one extra root field. The key set is exact per schema, so a
        // schema 1 document that names outputDefaults fails here with "fields": the field cannot
        // be smuggled in under schema 1 or engine 1, and schema 2 cannot omit it.
        int schema = schema(root.get("schemaVersion"));
        keys(root, schema == 1 ? ROOT_FIELDS : ROOT_FIELDS_V2);
        int minEngine = engine(root.get("minimumEngineVersion"));
        if (schema == 2 && minEngine != 2) throw new IllegalArgumentException("engine");
        String version = str(root, "ruleVersion", 64); if (!version.matches(VERSION_PATTERN)) throw new IllegalArgumentException("version");
        Object raw = root.get("rules"); if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("rules");
        JSONArray a = (JSONArray) raw; if (a.length() > MAX_RULES) throw new IllegalArgumentException("rules");
        List<Rule> rules = new ArrayList<>(); Set<String> ids = new HashSet<>(), tuples = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.get(i); if (!(item instanceof JSONObject)) throw new IllegalArgumentException("rule");
            JSONObject r = (JSONObject) item; keys(r, "id", "provider", "protocol", "origin", "path", "headers");
            String id = str(r, "id", 64), p = str(r, "provider", 64), proto = str(r, "protocol", 32), origin = str(r, "origin", 128), path = str(r, "path", 256);
            if (!id.matches("[a-z0-9._-]+") || !ids.add(id)) throw new IllegalArgumentException("id");
            // Explicit trusted tuples: a rules file cannot expand destinations or provider
            // identities, and a document that needs a newer engine than this build is refused
            // whole rather than silently ignored.
            Binding b = binding(p, proto, origin, path);
            if (b == null) throw new IllegalArgumentException("tuple");
            if (b.minEngine > minEngine) throw new IllegalArgumentException("engine");
            if (!tuples.add(p + "\n" + proto + "\n" + origin + "\n" + path)) throw new IllegalArgumentException("tuple");
            Object hdr = r.get("headers"); if (!(hdr instanceof JSONObject)) throw new IllegalArgumentException("headers");
            JSONObject h = (JSONObject) hdr;
            // The vocabulary this document's own engine version may name. A v1 document keeps the
            // frozen set it was signed against, so a newer build cannot re-interpret 1.0.1/1.0.2;
            // an engine 2 document may use the wider set and substitutes into it.
            Set<String> vocabulary = minEngine == 1 ? b.v1Allowed : b.allowed;
            if (h.length() < 1 || h.length() > vocabulary.size() + 1) throw new IllegalArgumentException("headers");
            Map<String, String> map = new LinkedHashMap<>(); Iterator<String> it = h.keys();
            while (it.hasNext()) { String k = it.next(); String v = str(h, k, MAX_HEADER_VALUE);
                boolean legacyStatic = LEGACY_CONVERSATION.equals(k) && b.legacyConversation && minEngine == 1;
                // Static values belong to the authenticated APK asset, not duplicated Java
                // fingerprints. Credentials, session IDs, and interpolation are unrepresentable.
                if (!vocabulary.contains(k) && !legacyStatic) throw new IllegalArgumentException("header");
                if (isReservedHeaderName(k) && !legacyStatic) throw new IllegalArgumentException("header");
                if (!v.matches("[\\x20-\\x7e]+") || v.contains("${") || v.contains("{{")) throw new IllegalArgumentException("header");
                map.put(k, v);
            }
            // Engine 1 documents must stay expressible in the original exact-set vocabulary, and a
            // binding whose consumer cannot absorb a partial set keeps it at every engine version.
            // Only a whole fingerprint can be substituted where the fallback is all-or-nothing.
            if (minEngine == 1 || !b.subset) {
                Set<String> expected = new HashSet<>(vocabulary);
                boolean exact = map.keySet().equals(expected);
                if (!exact && minEngine == 1 && b.legacyConversation) { expected.add(LEGACY_CONVERSATION); exact = map.keySet().equals(expected); }
                if (!exact) throw new IllegalArgumentException("unapproved header set");
            } else if (!vocabulary.containsAll(map.keySet())) throw new IllegalArgumentException("unapproved header set");
            rules.add(new Rule(id, p, proto, origin, path, map));
        }
        Map<String, Integer> defaults = schema == 2 ? parseOutputDefaults(root) : Collections.<String, Integer>emptyMap();
        return new Snapshot(schema, version, minEngine, rules, defaults, "builtin");
    }

    /**
     * Schema 2 only. Every item is exactly provider/model/maxOutput; the provider list is a fixed
     * identity whitelist, the model is an exact wildcard-free string, and the limit is a positive
     * integer a vendor could plausibly accept. Duplicates are refused because the table is a
     * lookup, not a list, and a repeated key would make the signed meaning ambiguous.
     */
    private static Map<String, Integer> parseOutputDefaults(JSONObject root) throws JSONException {
        Object raw = root.get("outputDefaults"); if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("outputDefaults");
        JSONArray a = (JSONArray) raw; if (a.length() > MAX_OUTPUT_DEFAULTS) throw new IllegalArgumentException("outputDefaults");
        Map<String, Integer> out = new LinkedHashMap<>(); Set<String> folded = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.get(i); if (!(item instanceof JSONObject)) throw new IllegalArgumentException("outputDefault");
            JSONObject d = (JSONObject) item; keys(d, "provider", "model", "maxOutput");
            String provider = str(d, "provider", 64), model = str(d, "model", MAX_MODEL);
            if (!OUTPUT_PROVIDERS.contains(provider)) throw new IllegalArgumentException("provider");
            if (!safeModel(model)) throw new IllegalArgumentException("model");
            Object limit = d.get("maxOutput");
            if (!(limit instanceof Integer)) throw new IllegalArgumentException("maxOutput");
            int maxOutput = (Integer) limit;
            if (maxOutput < 1 || maxOutput > MAX_OUTPUT) throw new IllegalArgumentException("maxOutput");
            String key = provider + "\n" + model;
            // Exact repeats are refused everywhere. A case-only repeat is refused too: the lookup
            // is exact, so the second row could never match anything the first did not, and the
            // two rows would read as the same setting with two different values.
            if (out.containsKey(key) || !folded.add(caseFold(provider, model)))
                throw new IllegalArgumentException("duplicate");
            out.put(key, maxOutput);
        }
        return out;
    }

    /** Exact model identity: alphanumeric start, no wildcard, and no ambiguous separators. */
    private static boolean safeModel(String model) {
        if (model == null || model.length() > MAX_MODEL || !model.matches(MODEL_PATTERN)) return false;
        if (model.contains("..") || model.contains("//") || model.contains("./")) return false;
        // A trailing separator would read as a truncated or prefix-matching identifier; every id
        // this gateway ships ends in a letter or digit.
        return "./-:+".indexOf(model.charAt(model.length() - 1)) < 0;
    }

    private static String caseFold(String provider, String model) {
        return provider.toLowerCase(Locale.ROOT) + "\n" + model.toLowerCase(Locale.ROOT);
    }

    /** Only 1 and 2 are schemas; anything else is unresolvable, not merely unknown. */
    private static int schema(Object raw) {
        if (!(raw instanceof Integer)) throw new IllegalArgumentException("version");
        int value = (Integer) raw;
        if (value < 1 || value > SCHEMA_VERSION) throw new IllegalArgumentException("version");
        return value;
    }

    /** Credential, session, or transport name: never settable from a rules document. */
    static boolean isReservedHeaderName(String name) {
        return name != null && RESERVED.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Binding binding(String provider, String protocol, String origin, String path) {
        for (Binding b : BINDINGS) if (b.provider.equals(provider) && b.protocol.equals(protocol)
                && b.origin.equals(origin) && b.path.equals(path)) return b;
        return null;
    }
    private static int engine(Object raw) {
        if (!(raw instanceof Integer)) throw new IllegalArgumentException("version");
        int value = (Integer) raw;
        if (value < 1 || value > ENGINE_VERSION) throw new IllegalArgumentException("version");
        return value;
    }
    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
    /** A frozen set plus an engine 2 extension, keeping insertion order. */
    private static Set<String> union(Set<String> base, Set<String> extra) {
        Set<String> merged = new LinkedHashSet<>(base);
        merged.addAll(extra);
        return Collections.unmodifiableSet(merged);
    }
    private static Set<String> lowerNames(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) set.add(v.toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(set);
    }
    private static void keys(JSONObject o, String... expected) {
        Set<String> set = new HashSet<>(Arrays.asList(expected)); if (o.length() != set.size()) throw new IllegalArgumentException("fields");
        Iterator<String> it = o.keys(); while (it.hasNext()) if (!set.contains(it.next())) throw new IllegalArgumentException("field");
    }
    private static String str(JSONObject o, String k, int max) throws JSONException {
        Object v = o.get(k); if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > max) throw new IllegalArgumentException("string"); return (String) v;
    }
    /** Strict JSON syntax and depth check before the permissive Android JSON parser. */
    private static final class StrictJson {
        final String s; int i; StrictJson(String s){this.s=s;}
        void check(){ value(0); ws(); if(i!=s.length()) fail(); }
        void ws(){while(i<s.length() && (s.charAt(i)==' '||s.charAt(i)=='\n'||s.charAt(i)=='\r'||s.charAt(i)=='\t')) i++;}
        void fail(){throw new IllegalArgumentException("json syntax");}
        boolean take(char c){ws(); if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        void value(int depth){ if(depth>8) fail(); ws(); if(i>=s.length()) fail(); char c=s.charAt(i);
            if(c=='{'){ i++; if(take('}'))return; Set<String> seen=new HashSet<>(); do { ws(); int start=i; string(); String key; try { key=(String)new JSONTokener(s.substring(start,i)).nextValue(); } catch(Exception e) { throw new IllegalArgumentException("key",e); } if(!seen.add(key))fail(); if(!take(':'))fail(); value(depth+1); if(take('}'))return; if(!take(','))fail(); }while(true); }
            if(c=='['){i++;if(take(']'))return;do{value(depth+1);if(take(']'))return;if(!take(','))fail();}while(true);}
            if(c=='"'){string();return;} int start=i; while(i<s.length() && ",}] \r\n\t".indexOf(s.charAt(i))<0)i++;
            String token=s.substring(start,i); if(!token.equals("true")&&!token.equals("false")&&!token.equals("null")&&!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))fail();
        }
        void string(){ws();if(i>=s.length()||s.charAt(i++)!='"')fail();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return;if(c<32)fail();if(c=='\\'){if(i>=s.length())fail();char e=s.charAt(i++);if(e=='u'){for(int n=0;n<4;n++)if(i>=s.length()||Character.digit(s.charAt(i++),16)<0)fail();}else if("\"\\/bfnrt".indexOf(e)<0)fail();}}fail();}
    }
}
