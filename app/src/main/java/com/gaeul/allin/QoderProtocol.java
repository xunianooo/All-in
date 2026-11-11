package com.gaeul.allin;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Projection of OpenAI chat requests onto Qoder's agent chat protocol. */
public final class QoderProtocol {
    /** The domain label a task key is hashed under, and the reason there is no salt: 0.2.64's
     *  sessionOf/taskOf hash with a plain sha256 (index.mjs:1339-1350, :1393-1412), so a session
     *  holds across a gateway restart. A per-process salt started a new Qoder session at every
     *  start, and every turn of a conversation got its own. */
    private static final String SESSION_DOMAIN = "com.gaeul.allin/qoder/session";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** namedSession keeps 128 characters of the agent's session (index.mjs:1358-1364). */
    private static final int NAMED_MAX = 128;
    /** A local bound on the client strings that may seed a session; a longer one falls through
     *  to the next source instead of being hashed whole. */
    private static final int SEED_MAX = 1024;
    private QoderProtocol() {}
    private static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
    private static boolean nil(Object o) { return o == null || o == JSONObject.NULL; }
    private static String required(Object o, String label) {
        if (!(o instanceof String) || ((String)o).trim().isEmpty()) throw bad(label + " required");
        return (String)o;
    }
    private static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    private static JSONArray copy(JSONArray a) throws Exception { return new JSONArray(a.toString()); }
    /** The session_id a turn goes out under, from the seed sessionOf picks (index.mjs:1339-1350):
     *  the session the agent named, else the client's prompt_cache_key, else its user, else the
     *  conversation's opening (its system prompts and first user turn), hashed so no key of the
     *  client's goes to Qoder as it is. The account scope is folded into the bytes, so the same
     *  client session under another account stays another Qoder session; a null or empty scope
     *  leaves the bytes exactly as the plugin's, which is what the anchor test compares. */
    static String digestSession(String scope, String label, String value) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        if (scope != null && !scope.isEmpty()) {
            d.update("scope".getBytes("UTF-8")); d.update((byte)0);
            d.update(scope.getBytes("UTF-8")); d.update((byte)0);
        }
        d.update(label.getBytes("UTF-8")); d.update((byte)0);
        d.update(value.getBytes("UTF-8"));
        byte[] h = d.digest();
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < 16; i++) { int v = h[i] & 255; hex.append(HEX[v >>> 4]).append(HEX[v & 15]); }
        return hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20);
    }
    /** magpie's placeholder session ("magpie-" and 24 hex digits) names no session
     *  (index.mjs:1358-1364), so sessionOf's own hash is taken instead. */
    private static boolean placeholderSession(String named) {
        if (named.length() != 31 || !named.startsWith("magpie-")) return false;
        for (int i = 7; i < named.length(); i++) {
            char c = named.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
    /** textOf of a message's content (index.mjs:1154): a string as it is, else its text blocks. */
    private static String contentText(Object content) {
        if (content instanceof String) return (String)content;
        if (!(content instanceof JSONArray)) return "";
        StringBuilder b = new StringBuilder(); JSONArray a = (JSONArray)content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            Object text = p == null ? null : p.opt("text");
            if (p != null && "text".equals(p.optString("type")) && text instanceof String) b.append((String)text);
        }
        return b.toString();
    }
    private static void quoted(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\b') out.append("\\b");
            else if (c == '\f') out.append("\\f");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20) out.append("\\u00").append(HEX[(c >>> 4) & 15]).append(HEX[c & 15]);
            else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                out.append(c).append(s.charAt(++i));
            else if (Character.isSurrogate(c))
                out.append("\\u").append(HEX[(c >>> 12) & 15]).append(HEX[(c >>> 8) & 15])
                        .append(HEX[(c >>> 4) & 15]).append(HEX[c & 15]);
            else out.append(c);
        }
        out.append('"');
    }
    /** A deterministic encoding for a session seed: strings and arrays as JSON.stringify writes
     *  them, an object's keys sorted so the seed cannot move with the map's iteration order. */
    private static void seedJson(StringBuilder out, Object value) throws Exception {
        if (nil(value)) { out.append("null"); return; }
        if (value instanceof String) { quoted(out, (String)value); return; }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray)value; out.append('[');
            for (int i = 0; i < a.length(); i++) { if (i > 0) out.append(','); seedJson(out, a.opt(i)); }
            out.append(']'); return;
        }
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value; ArrayList<String> keys = new ArrayList<>();
            for (Iterator<String> it = o.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys); out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                quoted(out, keys.get(i)); out.append(':'); seedJson(out, o.opt(keys.get(i)));
            }
            out.append('}'); return;
        }
        if (value instanceof Boolean || value instanceof Number) { out.append(value.toString()); return; }
        out.append("null");
    }
    /** The conversation's opening as sessionOf seeds it: [its system prompts, its first user turn]. */
    private static String chatOpening(JSONArray messages) throws Exception {
        JSONArray systems = new JSONArray(); Object first = ""; boolean seen = false;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i); if (m == null) continue;
            String role = m.optString("role", "");
            if ("system".equals(role) || "developer".equals(role)) systems.put(contentText(m.opt("content")));
            else if (!seen && "user".equals(role)) { seen = true; first = m.has("content") ? m.opt("content") : ""; }
        }
        JSONArray pair = new JSONArray(); pair.put(systems); pair.put(first);
        StringBuilder out = new StringBuilder(); seedJson(out, pair); return out.toString();
    }
    /** The seed sessionOf picks, in the plugin's order, hashed under the account scope. A client
     *  that names no session is no longer given a new random one per request: its conversation's
     *  opening names the session, so every turn of it goes out under the same one. */
    private static String sessionId(String accountScope, String explicit, JSONObject openai, JSONArray messages) throws Exception {
        String label = null, value = null;
        if (explicit != null) {
            String named = explicit.trim();
            if (!named.isEmpty()) {
                if (named.length() > NAMED_MAX) named = named.substring(0, NAMED_MAX);
                if (!placeholderSession(named)) { label = "session"; value = named; }
            }
        }
        if (label == null) {
            Object key = openai.opt("prompt_cache_key");
            if (key instanceof String && !((String)key).trim().isEmpty() && ((String)key).length() <= SEED_MAX) { label = "key"; value = (String)key; }
        }
        if (label == null) {
            Object user = openai.opt("user");
            if (user instanceof String && !((String)user).trim().isEmpty() && ((String)user).length() <= SEED_MAX) { label = "user"; value = (String)user; }
        }
        if (label == null) { String opening = chatOpening(messages); if (!opening.isEmpty()) { label = "chat"; value = opening; } }
        return label == null ? UUID.randomUUID().toString() : digestSession(accountScope, label, value);
    }
    private static final long TASK_IDLE_MS=2L*60*60*1000;
    private static final int TASK_LIMIT=512;
    private static final LinkedHashMap<String,Task> TASKS=new LinkedHashMap<>(16,0.75f,true);
    private static final class Task {
        final String id=UUID.randomUUID().toString(); final long begin; final String name; long used; boolean answered;
        Task(long now, String name) { begin=now; used=now; this.name=name; }
    }
    private static byte[] canonical(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value; ArrayList<String> keys=new ArrayList<>();
            for(Iterator<String> it=o.keys();it.hasNext();) keys.add(it.next()); Collections.sort(keys);
            StringBuilder s=new StringBuilder("{");
            for(String key:keys) s.append(JSONObject.quote(key)).append(':').append(new String(canonical(o.opt(key)),"UTF-8")).append(',');
            return s.append('}').toString().getBytes("UTF-8");
        }
        if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value; StringBuilder s=new StringBuilder("[");
            for(int i=0;i<a.length();i++) s.append(new String(canonical(a.opt(i)),"UTF-8")).append(',');
            return s.append(']').toString().getBytes("UTF-8");
        }
        return (nil(value)?"null":value instanceof String?JSONObject.quote((String)value):value.toString()).getBytes("UTF-8");
    }
    private static boolean toolImageFollowup(JSONObject message) {
        JSONArray blocks=message==null?null:message.optJSONArray("content");
        if(blocks==null || blocks.length()<2) return false;
        JSONObject first=blocks.optJSONObject(0), second=blocks.optJSONObject(1);
        /* The marker a tool's images are announced with has two shapes here: the plugin names the
         * call when it knows it ("[From the result of read (tool call two):]", index.mjs:1279-1283)
         * and AnthropicBridge names only the id ("[From the result of tool call two:]",
         * AnthropicBridge:457). Both are the gateway's own turn, so both are recognised. */
        return first!=null && second!=null && "text".equals(first.optString("type"))
                && first.optString("text").startsWith("[From the result of ")
                && "image_url".equals(second.optString("type"));
    }
    /** The business record's name for a task: the prompt's first ten characters
     *  (index.mjs:1397-1398, qoder-agent-sdk's AgentLifecycle). */
    private static String taskName(String prompt) { return prompt.length() > 10 ? prompt.substring(0, 10) : prompt; }
    private static Task task(String session, JSONArray messages) throws Exception {
        /* 0.2.64's taskOf is the chat's last user turn as it came (index.mjs:1393-1412): the tool
         * loop repeats that very turn, and the user's next words are a new task even when they
         * answer tool results with no assistant turn in between. The one turn it never counts is
         * the one this gateway synthesizes for a tool's images (the plugin makes that one inside
         * qoderMessages, after taskOf has already looked), so a tool loop keeps one task. */
        int at=-1; Object content=JSONObject.NULL;
        for(int i=messages.length()-1;i>=0;i--) {
            JSONObject m=messages.optJSONObject(i);
            if(m!=null && "user".equals(m.optString("role")) && !toolImageFollowup(m)) {at=i; content=m.opt("content"); break;}
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(SESSION_DOMAIN.getBytes("UTF-8")); digest.update((byte)0);
        digest.update("task".getBytes("UTF-8")); digest.update((byte)0);
        digest.update(session.getBytes("UTF-8")); digest.update((byte)0);
        digest.update(Integer.toString(at).getBytes("UTF-8")); digest.update((byte)0); digest.update(canonical(content));
        byte[] h=digest.digest(); StringBuilder key=new StringBuilder(); for(byte b:h) {int v=b&255;key.append(HEX[v>>>4]).append(HEX[v&15]);}
        long now=System.currentTimeMillis();
        synchronized(TASKS) {
            Iterator<Map.Entry<String,Task>> it=TASKS.entrySet().iterator(); while(it.hasNext()) {Task t=it.next().getValue(); if(now-t.used>TASK_IDLE_MS || now<t.used) it.remove();}
            Task t=TASKS.get(key.toString()); if(t==null) {t=new Task(now,taskName(at<0?"":contentText(content))); TASKS.put(key.toString(),t);} t.used=now;
            while(TASKS.size()>TASK_LIMIT) {Iterator<String> oldest=TASKS.keySet().iterator(); oldest.next(); oldest.remove();}
            return t;
        }
    }
    /** Notify only after a successful completed upstream task response. Unknown or expired IDs are ignored. */
    public static void answered(String taskId) {
        if(taskId==null) return;
        synchronized(TASKS) { for(Task t:TASKS.values()) if(t.id.equals(taskId)) {t.answered=true; break;} }
    }
    private static JSONObject modelConfig(JSONObject raw, boolean thinking) throws Exception {
        if(raw==null) throw bad("Qoder model config required");
        String key=required(raw.opt("key"),"Qoder model key").trim();
        if("auto".equalsIgnoreCase(key) || "default".equalsIgnoreCase(key)) throw bad("Explicit Qoder model key required");
        JSONObject out=new JSONObject().put("key",key);
        Object display=raw.opt("display_name"); out.put("display_name",display instanceof String && !((String)display).isEmpty()?display:key);
        Object outer=raw.opt("outer_provider"); if(outer instanceof String && !((String)outer).isEmpty()) out.put("outer_provider",outer); else out.put("model","");
        Object format=raw.opt("format"), source=raw.opt("source");
        out.put("format",format instanceof String?format:"openai").put("is_vl",raw.optBoolean("is_vl",false)).put("is_reasoning",raw.optBoolean("is_reasoning",false) && thinking);
        out.put("api_key","").put("url","").put("source",source instanceof String && !((String)source).isEmpty()?source:"system");
        Object max=raw.opt("max_input_tokens"); long n=max instanceof Number?((Number)max).longValue():0;
        if(!(max instanceof Number) || ((Number)max).doubleValue()!=n || n<=0 || n>Integer.MAX_VALUE) n=200000;
        out.put("max_input_tokens",n); return out;
    }
    private static JSONArray blocks(Object raw, boolean imageAllowed) throws Exception {
        JSONArray out=new JSONArray();
        if(nil(raw)) return out;
        if(raw instanceof String) { if(!((String)raw).isEmpty()) out.put(new JSONObject().put("type","text").put("text",raw)); return out; }
        if(!(raw instanceof JSONArray)) throw bad("Invalid message content");
        JSONArray a=(JSONArray)raw;
        for(int i=0;i<a.length();i++) {
            JSONObject p=a.optJSONObject(i); if(p==null) throw bad("Invalid content block");
            String type=p.optString("type","");
            if("text".equals(type)) {
                ImageContent.keys(p,"type","text"); if(!(p.opt("text") instanceof String)) throw bad("Text required");
                out.put(new JSONObject().put("type","text").put("text",p.get("text")));
            } else if("image_url".equals(type) && imageAllowed) {
                // Qoder's projection has no image detail field. Reject non-auto detail.
                String url=ImageContent.chatUrl(p,true);
                out.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",url)));
            } else throw bad("Unsupported content block");
        }
        return out;
    }
    private static String text(JSONArray blocks) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<blocks.length();i++) { JSONObject p=blocks.optJSONObject(i); if("text".equals(p.optString("type"))) b.append(p.optString("text")); }
        return b.toString();
    }
    private static void strictArgs(Object value) {
        if(!(value instanceof String)) throw bad("Tool arguments must be a strict JSON object");
        try { new BoundedStrictJson((String)value).run(); new JSONObject((String)value); }
        catch(Exception e) { throw bad("Tool arguments must be a strict JSON object"); }
    }
    private static void addCall(Set<String> pending, Set<String> seen, Object id) {
        String key=required(id,"Tool call id"); if(!seen.add(key)) throw bad("Duplicate tool call id"); pending.add(key);
    }
    private static void answer(Set<String> pending, Object id) {
        if(!pending.remove(required(id,"Tool result id"))) throw bad("Unexpected or duplicate tool result");
    }
    private static void complete(Set<String> pending) { if(!pending.isEmpty()) throw bad("Missing tool results"); }
    /** Validate the original client tool history before any generic normalizer can repair it. */
    public static void validateClientHistory(JSONObject raw, String protocol) {
        if(raw==null || protocol==null) throw bad("Request and protocol required");
        for(String key:new String[]{"max_tokens","max_completion_tokens"}) {
            if(!raw.has(key) || nil(raw.opt(key))) continue;
            Object value=raw.opt(key);
            if(!(value instanceof Number)) throw bad("Invalid " + key);
            try {
                int n=new java.math.BigDecimal(value.toString()).intValueExact();
                if(n<=0) throw bad("Invalid " + key);
                // A positive budget is kept as asked whatever its size: 0.2.64 only lowers a
                // request to a model ceiling and never names a 64-token floor (index.mjs:1502-1506).
                // This check is still here so a malformed value cannot be silently rewritten by
                // middleware defaults later on.
            } catch(ArithmeticException | NumberFormatException invalid) { throw bad("Invalid " + key); }
        }
        Set<String> pending=new HashSet<>(), seen=new HashSet<>();
        if("chat".equals(protocol)) {
            JSONArray messages=raw.optJSONArray("messages"); if(messages==null) throw bad("Messages required");
            /* This check runs on the payload the client sent, before any repair, and a tool history
             * whose call ids were lost is refused here in the clear rather than repaired.
             * 0.2.64's qoderMessages repairs such a history instead (index.mjs:1156-1200,
             * :1250-1285), and QoderProtocol.apply does exactly what it does for every history that
             * reaches it — a call without an id gets a deterministic one, a result whose id names no
             * call of its turn answers the earliest open one, and a result with no call left to
             * answer becomes the user's text. What cannot be guaranteed is that a repaired history
             * still reaches Qoder as the client sent it: GatewayServer runs
             * normalizeCoreMessages -> AnthropicBridge.pairToolMessages (AnthropicBridge:411-460)
             * before the adapter, and that normalizer answers a call no result answered with an
             * invented result ("[The result of this tool call is unavailable: the turn was
             * interrupted.]") and drops a repeated result id together with its words and images.
             * Sending a history Qoder reads as something the client never said is worse than naming
             * the defect, so the refusal stays until that normalizer is fixed.
             * An empty tool_calls array is no such history: the plugin takes it as a turn with no
             * calls (index.mjs:1251) and so does the normalizer (AnthropicBridge:416), so it is
             * accepted here too. */
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i); if(m==null) throw bad("Invalid message");
                String role=required(m.opt("role"),"Message role");
                if("tool".equals(role)) { Object name=m.opt("name"); if(!nil(name) && !(name instanceof String)) throw bad("Invalid tool result name"); answer(pending,m.opt("tool_call_id")); continue; }
                complete(pending); Object calls=m.opt("tool_calls");
                if(!nil(calls)) {
                    if(!"assistant".equals(role) || !(calls instanceof JSONArray)) throw bad("Invalid tool calls");
                    JSONArray list=(JSONArray)calls;
                    for(int j=0;j<list.length();j++) {
                        JSONObject c=list.optJSONObject(j); if(c==null || !"function".equals(c.optString("type"))) throw bad("Invalid tool call");
                        JSONObject fn=c.optJSONObject("function"); if(fn==null) throw bad("Tool function required"); required(fn.opt("name"),"Tool function name"); strictArgs(fn.opt("arguments")); addCall(pending,seen,c.opt("id"));
                    }
                }
            }
        } else if("messages".equals(protocol)) {
            JSONArray messages=raw.optJSONArray("messages"); if(messages==null) throw bad("Messages required");
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i); if(m==null) throw bad("Invalid message");
                String role=required(m.opt("role"),"Message role"); JSONArray content=m.optJSONArray("content");
                boolean hadPending=!pending.isEmpty();
                if(hadPending && !"user".equals(role)) throw bad("Missing tool results");
                if(content!=null) for(int j=0;j<content.length();j++) {
                    JSONObject b=content.optJSONObject(j); if(b==null) continue; String type=b.optString("type");
                    if("tool_use".equals(type)) {
                        if(!"assistant".equals(role) || hadPending) throw bad("Invalid tool use");
                        required(b.opt("name"),"Tool function name"); if(!(b.opt("input") instanceof JSONObject)) throw bad("Tool input must be an object");
                        new BoundedStrictJson(b.optJSONObject("input").toString()).run(); addCall(pending,seen,b.opt("id"));
                    } else if("tool_result".equals(type)) {
                        if(!"user".equals(role)) throw bad("Invalid tool result"); answer(pending,b.opt("tool_use_id"));
                    }
                }
                if(hadPending) complete(pending);
            }
        } else if("responses".equals(protocol)) {
            Object input=raw.opt("input"); if(input instanceof String) return;
            if(!(input instanceof JSONArray)) throw bad("Invalid input");
            boolean answering=false; JSONArray items=(JSONArray)input;
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i); if(item==null) throw bad("Invalid input item"); String type=item.optString("type");
                if("function_call".equals(type)) {
                    if(answering) {complete(pending); answering=false;}
                    required(item.opt("name"),"Tool function name"); strictArgs(item.opt("arguments")); addCall(pending,seen,item.opt("call_id"));
                } else if("function_call_output".equals(type)) { answering=true; answer(pending,item.opt("call_id")); }
                else {complete(pending); answering=false;}
            }
        } else throw bad("Unsupported protocol");
        complete(pending);
    }
    /** The id a call the client sent without one goes out with (index.mjs:1166-1179): the same on
     *  every turn of the conversation, made from where the call is and what it says, so the history
     *  Qoder sees does not change under a prompt cache from one request to the next. "at" is the
     *  message's own index in the client's messages and "k" the call's index in its turn, exactly
     *  as the plugin counts them, so the two produce the same id for the same history. */
    static String callID(int at, int k, JSONObject call) throws Exception {
        JSONObject fn=call.optJSONObject("function");
        Object rawName=fn==null?null:fn.opt("name");
        String name=nil(rawName)?"":rawName instanceof String?(String)rawName:JSONObject.valueToString(rawName);
        Object args=fn==null?null:fn.opt("arguments");
        String said=args instanceof String?(String)args:nil(args)?"{}":JSONObject.valueToString(args);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update((Integer.toString(at)+"\0"+Integer.toString(k)+"\0"+name+"\0").getBytes("UTF-8"));
        digest.update(said.getBytes("UTF-8"));
        byte[] h=digest.digest();
        StringBuilder hex=new StringBuilder(32);
        for(int i=0;i<16;i++) { int v=h[i]&255; hex.append(HEX[v>>>4]).append(HEX[v&15]); }
        return "call_"+hex;
    }
    /** The earliest call of the turn that no other result of the turn names, which is the call a
     *  result whose id the client lost answers (index.mjs:1263-1266). */
    private static int openUnnamed(ArrayList<String> open, Set<String> kept) {
        for(int i=0;i<open.size();i++) if(!kept.contains(open.get(i))) return i;
        return -1;
    }
    /** A result with no open call of its turn left to answer becomes text in the user's own turn,
     *  so the words and images the tool returned stay with the conversation (index.mjs:1191-1200,
     *  :1255-1258). The text is trimmed and two results in one turn are separated by a blank line,
     *  as the plugin's asText does it. */
    private static void asText(JSONArray buffered, String text, JSONArray images) {
        String said=text.trim();
        if(said.isEmpty() && images.length()==0) return;
        if(buffered.length()>0) buffered.put(new JSONObject().put("type","text").put("text","\n\n"));
        if(!said.isEmpty()) buffered.put(new JSONObject().put("type","text").put("text",said));
        for(int i=0;i<images.length();i++) buffered.put(images.opt(i));
    }
    private static JSONObject resolve(JSONObject root, String ref) {
        if(!ref.startsWith("#/")) return null;
        Object at=root;
        for(String part:ref.substring(2).split("/",-1)) {
            if(!(at instanceof JSONObject)) return null;
            at=((JSONObject)at).opt(part.replace("~1","/").replace("~0","~"));
        }
        return at instanceof JSONObject?(JSONObject)at:null;
    }
    private static boolean objectBranch(JSONObject root, JSONObject schema, Set<String> seen) {
        if(schema==null || seen.size()>16) return false;
        if(schema.has("type")) return "object".equals(schema.opt("type"));
        Object ref=schema.opt("$ref"); if(!(ref instanceof String) || !seen.add((String)ref)) return false;
        return objectBranch(root,resolve(root,(String)ref),seen);
    }
    private static void typedUnions(JSONObject root, JSONObject s, int depth) throws Exception {
        if(s==null || depth>64) return;
        JSONArray one=s.optJSONArray("oneOf");
        if(!s.has("type") && one!=null && one.length()>0) {
            boolean all=true;
            for(int i=0;i<one.length();i++) if(!objectBranch(root,one.optJSONObject(i),new HashSet<String>())) {all=false;break;}
            if(all) s.put("type","object");
        }
        String[] singles={"items","additionalItems","unevaluatedItems","contains","not","if","then","else","propertyNames","additionalProperties","unevaluatedProperties"};
        String[] lists={"anyOf","oneOf","allOf","prefixItems"};
        String[] maps={"properties","patternProperties","$defs","definitions","dependentSchemas"};
        for(String key:singles) typedUnions(root,s.optJSONObject(key),depth+1);
        for(String key:lists) {JSONArray a=s.optJSONArray(key); if(a!=null) for(int i=0;i<a.length();i++) typedUnions(root,a.optJSONObject(i),depth+1);}
        for(String key:maps) {JSONObject map=s.optJSONObject(key); if(map!=null) for(Iterator<String> it=map.keys();it.hasNext();) typedUnions(root,map.optJSONObject(it.next()),depth+1);}
    }
    private static JSONArray tools(JSONArray input) throws Exception {
        JSONArray out=new JSONArray(); Set<String> names=new HashSet<>();
        for(int i=0;i<input.length();i++) {
            JSONObject t=input.optJSONObject(i); if(t==null || !"function".equals(t.optString("type"))) throw bad("Unsupported tool type");
            JSONObject f=t.optJSONObject("function"); if(f==null) throw bad("Tool function required");
            String name=required(f.opt("name"),"Tool name"); if(!names.add(name)) throw bad("Duplicate tool name");
            Object strict=f.opt("strict"); if(!nil(strict) && (!(strict instanceof Boolean) || (Boolean)strict)) throw bad("Strict tool schemas unsupported");
            Object desc=f.opt("description"); if(!nil(desc) && !(desc instanceof String)) throw bad("Invalid tool description");
            Object schema=f.opt("parameters"); if(!nil(schema) && !(schema instanceof JSONObject)) throw bad("Invalid tool schema");
            JSONObject parameters=nil(schema)?new JSONObject().put("type","object").put("properties",new JSONObject()):copy((JSONObject)schema);
            typedUnions(parameters,parameters,0);
            JSONObject fn=new JSONObject().put("name",name).put("description",nil(desc)?"":desc).put("parameters",parameters);
            out.put(new JSONObject().put("type","function").put("function",fn));
        }
        return out;
    }
    private static final String[] RANK={"none","minimal","low","medium","high","xhigh","max","ultra"};
    private static int rank(String v) { for(int i=0;i<RANK.length;i++) if(RANK[i].equals(v)) return i; return -1; }
    private static void reasoning(JSONObject body, JSONObject request) throws Exception {
        Object a=request.opt("reasoning_effort"), alias=request.opt("reasoningEffort");
        if(!nil(a) && !(a instanceof String) || !nil(alias) && !(alias instanceof String)) throw bad("Invalid reasoning effort");
        if(!nil(a) && !nil(alias) && !((String)a).equalsIgnoreCase((String)alias)) throw bad("Conflicting reasoning effort");
        String want=nil(a)?(nil(alias)?"":((String)alias).toLowerCase(Locale.ROOT)):((String)a).toLowerCase(Locale.ROOT);
        if(rank(want)<0) want="";
        Object budget=body.getJSONObject("parameters").opt("max_tokens");
        if(want.isEmpty() && budget instanceof Number && ((Number)budget).doubleValue()>0 && ((Number)budget).doubleValue()<=1024) want="none";
        JSONObject meta=body.optJSONObject("model_config"), params=body.getJSONObject("parameters");
        JSONObject tc=meta==null?null:meta.optJSONObject("thinking_config");
        boolean known=tc!=null || (meta!=null && meta.opt("is_reasoning") instanceof Boolean);
        boolean thinks=tc!=null?!nil(tc.opt("enabled")):meta!=null && meta.optBoolean("is_reasoning",false);
        boolean always=tc!=null && thinks && nil(tc.opt("disabled"));
        ArrayList<String> levels=new ArrayList<>(); String defaultLevel="";
        if(tc!=null && thinks) {
            JSONObject enabled=tc.optJSONObject("enabled"); JSONObject es=enabled==null?null:enabled.optJSONObject("efforts");
            if(es!=null) for(Iterator<String> it=es.keys();it.hasNext();) { String key=it.next(); levels.add(key); JSONObject entry=es.optJSONObject(key); if(entry!=null && entry.optBoolean("is_default",false)) defaultLevel=key; }
            Collections.sort(levels,(x,y)-> {int ix=rank(x),iy=rank(y); if(ix<0) ix=RANK.length; if(iy<0) iy=RANK.length; return ix==iy?x.compareTo(y):ix-iy;});
        } else if(meta!=null) {
            JSONArray es=meta.optJSONArray("reasoning_efforts"); if(es!=null) for(int i=0;i<es.length();i++) if(es.opt(i) instanceof String) levels.add((String)es.opt(i));
        }
        if(!known) {
            // Unknown model metadata: keep the base model default except an explicit/short-budget opt-out.
            if("none".equals(want)) {params.put("enable_thinking",false); params.remove("reasoning_effort");}
            return;
        }
        boolean enabled=thinks && (!"none".equals(want) || always);
        params.put("enable_thinking",enabled); params.remove("reasoning_effort");
        if(meta!=null && meta.opt("is_reasoning") instanceof Boolean) meta.put("is_reasoning",meta.optBoolean("is_reasoning") && enabled);
        if(!enabled || levels.isEmpty()) return;
        String selected="";
        if(want.isEmpty()) selected=defaultLevel;
        else if("none".equals(want)) selected=levels.get(0);
        else if(levels.contains(want)) selected=want;
        else {
            if("ultra".equals(want) && !levels.contains(want)) want="max";
            int at=rank(want), best=RANK.length, bestRank=-1;
            for(String level:levels) { int r=rank(level); if(r<0 || "none".equals(level)) continue; int dist=Math.abs(r-at); if(dist<best || dist==best && r>bestRank) {best=dist;bestRank=r;selected=level;} }
        }
        if(!selected.isEmpty()) params.put("reasoning_effort",selected);
    }
    private static String historyReasoning(JSONObject message) {
        Object primary=message.opt("reasoning_content");
        if(!nil(primary) && !(primary instanceof String)) throw bad("Invalid reasoning metadata");
        String text=primary instanceof String?(String)primary:"";
        Object alias=message.opt("reasoning");
        if(text.isEmpty() && alias instanceof String) text=(String)alias;
        Object details=message.opt("reasoning_details");
        if(text.isEmpty() && details instanceof JSONArray) {
            StringBuilder result=new StringBuilder(); JSONArray list=(JSONArray)details;
            for(int i=0;i<list.length();i++) {
                JSONObject item=list.optJSONObject(i); if(item==null) continue;
                Object value=item.opt("text"); if(!(value instanceof String)) value=item.opt("summary");
                if(value instanceof String) result.append((String)value);
            }
            text=result.toString();
        }
        return text.trim().isEmpty()?"":text;
    }
    /** True if the OpenAI message content contains an image block; actual validation is performed by apply. */
    public static boolean hasImages(JSONObject request) {
        if(request==null) return false; JSONArray messages=request.optJSONArray("messages"); if(messages==null) return false;
        for(int i=0;i<messages.length();i++) { JSONObject m=messages.optJSONObject(i); JSONArray content=m==null?null:m.optJSONArray("content"); if(content!=null) for(int j=0;j<content.length();j++) {JSONObject part=content.optJSONObject(j); if(part!=null && "image_url".equals(part.optString("type"))) return true;} }
        return false;
    }
    /** True only for the Qoder listing's explicit is_vl capability. False also means unknown. */
    public static boolean supportsImages(JSONObject modelMeta) { return modelMeta!=null && Boolean.TRUE.equals(modelMeta.opt("is_vl")); }
    private static JSONObject user(JSONArray b) throws Exception { return new JSONObject().put("role","user").put("content",b).put("contents",b); }
    /** Returns a new body without changing either input. The base already contains the merged system prompt.
     * accountScope is a stable account identifier; an absent explicitSession deliberately creates a new session.
     * Callers must verify model image support before invoking this helper. */
    private static int positive(Object value) { if(!(value instanceof Number)) return 0; double n=((Number)value).doubleValue(); return Double.isFinite(n)&&n>0&&n<=Integer.MAX_VALUE&&n==Math.rint(n)?(int)n:0; }
    private static int windowFor(JSONObject model, JSONArray messages, JSONArray tools, int reply) throws Exception {
        ArrayList<Integer> windows=new ArrayList<>(); int configuredDefault=0;
        JSONObject configured=model.optJSONObject("context_config");
        if(configured!=null) for(Iterator<String> it=configured.keys();it.hasNext();) {
            JSONObject entry=configured.optJSONObject(it.next()); if(entry==null)continue;
            int size=positive(entry.opt("token_count")); if(size>0&&!windows.contains(size))windows.add(size);
            Object d=entry.opt("is_default"); if(Boolean.TRUE.equals(d)||Integer.valueOf(1).equals(d)||"1".equals(d))configuredDefault=size;
        }
        if(windows.isEmpty()) {
            JSONArray list=model.optJSONArray("available_context_windows"); if(list==null)list=model.optJSONArray("availableContextWindows");
            if(list!=null)for(int i=0;i<list.length();i++){int n=positive(list.opt(i));if(n>0&&!windows.contains(n))windows.add(n);}
            configuredDefault=positive(model.opt("default_context_window")); if(configuredDefault==0)configuredDefault=positive(model.opt("defaultContextWindow"));
        }
        int base=configuredDefault>0?configuredDefault:positive(model.opt("max_input_tokens"));
        if(windows.isEmpty())return base;
        Collections.sort(windows);
        // The upstream client falls back to the largest advertised window when no default is named.
        if(base==0)base=windows.get(windows.size()-1);
        byte[] encoded=new JSONObject().put("m",messages).put("t",tools).toString().getBytes("UTF-8");
        long need=((long)encoded.length+2)/3+Math.max(0,reply);
        if(base>0&&need<=base)return base;
        for(int n:windows)if(n>=need)return n;
        return windows.get(windows.size()-1);
    }

    public static JSONObject apply(JSONObject baseBody, JSONObject openai, String explicitSession, String accountScope) throws Exception {
        if(baseBody==null || openai==null) throw bad("Request required");
        required(accountScope,"Account scope");
        JSONArray input=openai.optJSONArray("messages"); if(input==null) throw bad("Messages required");
        Object n=openai.opt("n");
        if(!nil(n) && (!(n instanceof Number) || ((Number)n).doubleValue()!=1.0)) throw bad("Only one output choice is supported");
        Object stream=openai.opt("stream");
        if(!nil(stream) && !(stream instanceof Boolean)) throw bad("Invalid stream option");
        Object choice=openai.opt("tool_choice");
        /* Qoder names no tool in its request, so a named choice can only be forwarded
         * as "the tools are available": the plugin does the same and never forces one.
         * An unknown name stays a local rejection, since the caller asked for a tool
         * this request cannot offer. */
        String namedChoice="";
        if(!nil(choice) && !(choice instanceof String)) {
            if(!(choice instanceof JSONObject)) throw bad("Invalid tool choice");
            JSONObject select=(JSONObject)choice;
            String type=select.optString("type","");
            if("function".equals(type)||"tool".equals(type)) {
                JSONObject fn=select.optJSONObject("function");
                Object rawName=fn==null?select.opt("name"):fn.opt("name");
                if(!(rawName instanceof String)||((String)rawName).trim().isEmpty()) throw bad("Invalid tool choice");
                namedChoice=((String)rawName).trim();
            } else if(!"auto".equals(type)&&!"none".equals(type)&&!"required".equals(type)&&!"any".equals(type)) throw bad("Invalid tool choice");
            else namedChoice="";
        } else if(!nil(choice) && !(choice.equals("auto")||choice.equals("none")||choice.equals("required"))) {
            namedChoice=((String)choice).trim();
            if(namedChoice.isEmpty()) throw bad("Invalid tool choice");
        }
        Object rawTools=openai.opt("tools"); if(!nil(rawTools) && !(rawTools instanceof JSONArray)) throw bad("Invalid tools");
        JSONArray converted=tools(nil(rawTools)?new JSONArray():(JSONArray)rawTools);
        /* qoderBody carries no parallel-tool knob (index.mjs:1495-1527: tools are forwarded as
         * declared), so a client's parallel_tool_calls=false can only be honoured when no tool
         * reaches Qoder at all: no tools, or tool_choice none. Then nothing can run in parallel
         * and the flag is vacuous, so it is accepted. With tools forwarded the answer may carry
         * several calls at once and this adapter cannot make them serial, so the flag is refused
         * in the clear instead of being dropped without a word. */
        Object parallel=openai.opt("parallel_tool_calls");
        if(!nil(parallel) && !(parallel instanceof Boolean)) throw bad("Invalid parallel_tool_calls");
        boolean toolsForwarded=!"none".equals(choice) && converted.length()>0;
        if(Boolean.FALSE.equals(parallel) && toolsForwarded)
            throw bad("parallel_tool_calls=false cannot be enforced: Qoder may return several tool calls at once when tools are declared; drop the flag or the tools");
        if("required".equals(choice) && converted.length()==0) throw bad("Required tool choice needs an available tool");
        if(!namedChoice.isEmpty()) {
            if(converted.length()==0) throw bad("Named tool choice needs an available tool");
            boolean declared=false;
            for(int i=0;i<converted.length();i++) if(namedChoice.equals(converted.getJSONObject(i).getJSONObject("function").optString("name"))) declared=true;
            if(!declared) throw bad("Named tool choice is not among the declared tools");
        }
        JSONObject body=copy(baseBody); JSONArray sys=body.optJSONArray("system"); if(sys==null) throw bad("Base system required");
        if("required".equals(choice) && !nil(rawTools) && ((JSONArray)rawTools).length()>0) { JSONObject sysBlock=sys.optJSONObject(0); if(sysBlock==null) throw bad("Invalid base system"); String prompt=sysBlock.optString("text",""); if(!prompt.contains("You must call an available function in this response.")) sysBlock.put("text",prompt+"\nYou must call an available function in this response."); }
        JSONObject params=body.optJSONObject("parameters"); if(params==null) throw bad("Base parameters required");
        reasoning(body,openai);
        JSONArray out=new JSONArray().put(new JSONObject().put("role","system").put("content",copy(sys)));
        Map<String,String> names=new LinkedHashMap<String,String>();
        Set<String> ids=new HashSet<String>(); ArrayList<String> open=null; Set<String> kept=new HashSet<String>();
        JSONArray buffered=new JSONArray();
        for(int i=0;i<input.length();i++) {
            JSONObject m=input.optJSONObject(i); if(m==null) throw bad("Invalid message");
            String role=required(m.opt("role"),"Message role");
            /* A system or developer turn is no turn of the chat's history (index.mjs:1222): the
             * plugin skips it before it looks at the turn being answered, so it neither answers a
             * call nor breaks the run of results that belongs to the turn before it. */
            if("system".equals(role)||"developer".equals(role)) continue;
            if("user".equals(role)) { open=null; kept=new HashSet<String>(); }
            if(!"user".equals(role) && !"tool".equals(role) && buffered.length()>0) { out.put(user(buffered)); buffered=new JSONArray(); }
            if("tool".equals(role)) {
                Object name=m.opt("name"); if(!nil(name) && !(name instanceof String)) throw bad("Invalid tool result name");
                Object rawId=m.opt("tool_call_id"); if(!nil(rawId) && !(rawId instanceof String)) throw bad("Invalid tool result id");
                String named=nil(rawId)?"":((String)rawId).trim();
                JSONArray b=blocks(m.opt("content"),true); String value=text(b); JSONArray images=new JSONArray();
                for(int j=0;j<b.length();j++) if("image_url".equals(b.getJSONObject(j).optString("type"))) images.put(b.get(j));
                /* A result naming a call of this turn answers it; one whose id the client lost —
                 * none, or one no call here has — answers the earliest call still open that no
                 * other result of the turn names, which is the order the calls were made in
                 * (index.mjs:1255-1266). A result with no call of its turn left to answer becomes
                 * text in the user's own turn (:1255-1258), which is what the plugin does with it
                 * instead of turning the request away. */
                int at=named.isEmpty()||open==null?-1:open.indexOf(named);
                int pick=at>=0?at:open==null?-1:openUnnamed(open,kept);
                if(pick<0) { asText(buffered,value,images); continue; }
                String answered=open.remove(pick);
                if(images.length()>0) {
                    String of="tool call "+answered;
                    String called=names.get(answered);
                    if(called==null||called.isEmpty()) called=name instanceof String?(String)name:"";
                    if(!called.isEmpty()) of=called+" ("+of+")";
                    buffered.put(new JSONObject().put("type","text").put("text","[From the result of "+of+":]"));
                    for(int j=0;j<images.length();j++) buffered.put(images.get(j));
                    value+=(value.trim().isEmpty()?"":"\n\n")+(images.length()==1?"[The tool returned an image; it follows in the next message.]":"[The tool returned "+images.length()+" images; they follow in the next message.]");
                }
                out.put(new JSONObject().put("role","tool").put("tool_call_id",answered).put("content",value));
                continue;
            }
            if("assistant".equals(role)) {
                Object raw=m.opt("tool_calls"); if(!nil(raw) && !(raw instanceof JSONArray)) throw bad("Invalid tool calls");
                JSONArray list=nil(raw)?null:(JSONArray)raw;
                String thought=historyReasoning(m);
                if(list!=null && list.length()>0) {
                    /* A call the client sent without an id goes out with one, so its result can
                     * answer it instead of being turned into text (index.mjs:1250-1262). */
                    JSONArray made=new JSONArray(); ArrayList<String> turned=new ArrayList<String>();
                    for(int k=0;k<list.length();k++) {
                        JSONObject c=list.optJSONObject(k); if(c==null || !"function".equals(c.optString("type"))) throw bad("Unsupported tool call");
                        Object rawId=c.opt("id"); if(!nil(rawId) && !(rawId instanceof String)) throw bad("Invalid tool call id");
                        String id=nil(rawId)?"":((String)rawId).trim();
                        if(id.isEmpty()) id=callID(i,k,c);
                        if(!ids.add(id)) throw bad("Duplicate tool call id");
                        JSONObject f=c.optJSONObject("function"); if(f==null) throw bad("Tool function required");
                        String name=required(f.opt("name"),"Tool function name"); Object args=f.opt("arguments");
                        strictArgs(args);
                        names.put(id,name); turned.add(id);
                        made.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args)));
                    }
                    JSONObject assistant=new JSONObject().put("role","assistant").put("content",text(blocks(m.opt("content"),false))).put("tool_calls",made);
                    if(!thought.isEmpty()) assistant.put("reasoning_content",thought);
                    out.put(assistant);
                    /* The results after this turn that name a call of it are kept, so a result
                     * whose id was lost must not take the answer of one that named it
                     * (index.mjs:1267-1275). */
                    open=turned; kept=new HashSet<String>();
                    for(int j=i+1;j<input.length();j++) {
                        JSONObject follows=input.optJSONObject(j);
                        if(follows==null || !"tool".equals(follows.optString("role"))) break;
                        Object followsId=follows.opt("tool_call_id");
                        String id=followsId instanceof String?((String)followsId).trim():"";
                        if(!id.isEmpty() && turned.contains(id)) kept.add(id);
                    }
                    continue;
                }
                JSONArray b=blocks(m.opt("content"),false);
                if(b.length()>0) { JSONObject assistant=new JSONObject().put("role","assistant").put("content",b); if(!thought.isEmpty()) assistant.put("reasoning_content",thought); out.put(assistant); }
                open=null; kept=new HashSet<String>();
                continue;
            }
            if(!"user".equals(role)) throw bad("Unsupported message role");
            JSONArray b=blocks(m.opt("content"),true);
            /* The images a tool returned follow in the user's own turn, at its start when one comes
             * next (index.mjs:1278-1284). */
            if(buffered.length()>0) {
                JSONArray merged=new JSONArray();
                for(int j=0;j<buffered.length();j++) merged.put(buffered.get(j));
                for(int j=0;j<b.length();j++) merged.put(b.get(j));
                b=merged; buffered=new JSONArray();
            }
            if(b.length()>0) out.put(user(b));
        }
        if(buffered.length()>0) out.put(user(buffered));
        JSONObject last=out.getJSONObject(out.length()-1);
        if("user".equals(last.optString("role"))) {
            JSONArray b=last.getJSONArray("content"); if(b.length()>0) b.getJSONObject(b.length()-1).put("cache_control",new JSONObject().put("type","ephemeral"));
            last.put("contents",b);
        }
        body.put("messages",out); body.remove("tools"); if(!"none".equals(choice) && converted.length()>0) body.put("tools",converted);
        JSONObject rawWindow=body.optJSONObject("model_config"); if(rawWindow!=null) { int selected=windowFor(rawWindow,input,converted,params.optInt("max_tokens",32000)); if(selected>0) params.put("context_length",selected); }
        JSONObject rawModel=body.optJSONObject("model_config"); JSONObject model=modelConfig(rawModel,params.optBoolean("enable_thinking",false));
        JSONObject business=body.optJSONObject("business"); if(business==null) throw bad("Base business required");
        Object selected=business.opt("model_key"); if(!nil(selected) && (!(selected instanceof String) || !selected.equals(model.optString("key")))) throw bad("Qoder model key mismatch");
        body.put("model_config",model);
        String id=sessionId(accountScope,explicitSession,openai,input); Task t=task(id,input);
        synchronized(TASKS) {
            body.put("session_id",id).put("request_set_id",t.id);
            business.put("id",t.id).put("begin_at",t.begin).put("stage",t.answered?"processing":"start")
                    .put("model_key",model.getString("key")).put("name",t.name);
        }
        return body;
    }
}
