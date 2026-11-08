package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Automatic, bounded WorkBuddy refusal compatibility. This class never performs I/O.
 * A Session belongs to one logical request; Cache must be shared process-wide.
 * Scope must identify the account and network route without ambiguity. */
public final class WorkBuddyRefusalPolicy {
    private WorkBuddyRefusalPolicy() {}
    public static final String DEFAULT_SYSTEM = "You are a helpful assistant.";
    private static final Pattern REFUSED = Pattern.compile("unapproved channel|illegal api invocation", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENER = Pattern.compile("\\b(You are (?:an?|the)\\s[^\\n.,]{0,60}?\\b(?:agent|assistant))\\s+(powered by|running on|running in|built on|based on)\\b", Pattern.CASE_INSENSITIVE);
    public static boolean eligible(int status, String message) {
        return status == 400 && message != null && REFUSED.matcher(message).find();
    }
    public static final class Proposal {
        public final JSONObject body;
        public final boolean changed;
        private Proposal(JSONObject body, boolean changed) { this.body=body; this.changed=changed; }
    }
    // Reject non-JSON Java objects, non-finite numbers and cycles before serializing.
    private static void validate(Object x, java.util.IdentityHashMap<Object,Boolean> stack) {
        if (x == null || x == JSONObject.NULL || x instanceof String || x instanceof Boolean) return;
        if (x instanceof Number) { if (!Double.isFinite(((Number)x).doubleValue())) throw new IllegalArgumentException("non-finite number"); return; }
        if (!(x instanceof JSONObject) && !(x instanceof JSONArray)) throw new IllegalArgumentException("unsupported non-JSON value");
        if (stack.put(x, Boolean.TRUE)!=null) throw new IllegalArgumentException("cyclic JSON");
        if (x instanceof JSONObject) { JSONObject o=(JSONObject)x; Iterator<String> it=o.keys(); while(it.hasNext()) validate(o.opt(it.next()),stack); }
        else { JSONArray a=(JSONArray)x; for(int i=0;i<a.length();i++) validate(a.opt(i),stack); }
        stack.remove(x);
    }
    private static JSONObject copy(JSONObject source) throws Exception {
        if(source==null) throw new IllegalArgumentException("missing body");
        validate(source,new java.util.IdentityHashMap<Object,Boolean>());
        return new JSONObject(source.toString());
    }
    private static String comma(String s) {
        Matcher m=OPENER.matcher(s); StringBuffer out=new StringBuffer();
        while(m.find()) {
            int start=m.start(), end=m.end();
            int left=start, right=end;
            while(left>0 && !Character.isWhitespace(s.charAt(left-1))) left--;
            while(right<s.length() && !Character.isWhitespace(s.charAt(right))) right++;
            String edge=s.substring(left,start)+s.substring(end,right);
            if(edge.indexOf('/')>=0 || edge.indexOf('\\')>=0 || edge.indexOf(':')>=0) continue;
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1)+", "+m.group(2)));
        }
        m.appendTail(out); return out.toString();
    }
    private static boolean onlyKeys(JSONObject obj, String... allowed) {
        Iterator<String> it=obj.keys();
        while(it.hasNext()) {
            String key=it.next(); boolean known=false;
            for(String name:allowed) if(name.equals(key)) { known=true; break; }
            if(!known) return false;
        }
        return true;
    }
    private static boolean ordinaryTextBlock(JSONObject part) {
        if(!onlyKeys(part,"type","text","cache_control") || !"text".equals(part.optString("type")) || !(part.opt("text") instanceof String)) return false;
        if(!part.has("cache_control")) return true;
        Object value=part.opt("cache_control");
        if(!(value instanceof JSONObject)) return false;
        JSONObject cache=(JSONObject)value;
        return onlyKeys(cache,"type","ttl") && "ephemeral".equals(cache.optString("type"))
            && (!cache.has("ttl") || "5m".equals(cache.opt("ttl")) || "1h".equals(cache.opt("ttl")));
    }
    private static String systemText(Object content) {
        if(content instanceof String) return (String)content;
        if(!(content instanceof JSONArray)) throw new IllegalArgumentException("leading system content is not text");
        JSONArray parts=(JSONArray)content;
        if(parts.length()==0) throw new IllegalArgumentException("empty system blocks cannot be moved losslessly");
        StringBuilder text=new StringBuilder();
        for(int i=0;i<parts.length();i++) {
            JSONObject part=parts.optJSONObject(i);
            if(part==null || !ordinaryTextBlock(part))
                throw new IllegalArgumentException("leading system block contains opaque data or metadata");
            String value=(String)part.opt("text");
            if(value.isEmpty()) throw new IllegalArgumentException("empty system block cannot be moved losslessly");
            if(i>0) text.append("\n");
            text.append(value);
        }
        return text.toString();
    }
    public static Proposal commaCandidate(int status, String message, JSONObject source) throws Exception {
        JSONObject b=copy(source); if(!eligible(status,message)) return new Proposal(b,false);
        JSONArray a=b.optJSONArray("messages"); if(a==null) return new Proposal(b,false);
        boolean changed=false;
        for(int i=0;i<a.length();i++) {
            JSONObject m=a.optJSONObject(i); if(m==null) continue;
            String role=m.optString("role", ""); if(!role.equals("system")&&!role.equals("assistant")) continue;
            if(m.has("signature")) continue;
            Object c=m.opt("content");
            if(c instanceof String) { String t=comma((String)c); if(!t.equals(c)){m.put("content",t);changed=true;} }
            else if(c instanceof JSONArray) { JSONArray parts=(JSONArray)c; for(int j=0;j<parts.length();j++) {
                JSONObject p=parts.optJSONObject(j); if(p==null || !ordinaryTextBlock(p)) continue;
                String old=(String)p.opt("text"), t=comma(old); if(!t.equals(old)){p.put("text",t);changed=true;}
            }}
        }
        return new Proposal(b,changed);
    }
    public static Proposal systemAsUserCandidate(int status, String message, JSONObject source, boolean explicitlyEnabled) throws Exception {
        JSONObject b=copy(source); if(!explicitlyEnabled || !eligible(status,message)) return new Proposal(b,false);
        JSONArray a=b.optJSONArray("messages"); if(a==null) return new Proposal(b,false);
        int lead=0; while(lead<a.length()) { JSONObject m=a.optJSONObject(lead); if(m==null || !"system".equals(m.optString("role"))) break; lead++; }
        boolean middle=false; for(int i=lead;i<a.length();i++) { JSONObject m=a.optJSONObject(i); if(m!=null && "system".equals(m.optString("role"))) middle=true; }
        if(lead==0 && !middle) return new Proposal(b,false);
        // Validate every system message whose role or leading content may be rewritten,
        // including the otherwise unchanged default system in a mixed conversation.
        for(int i=0;i<a.length();i++) {
            JSONObject m=a.optJSONObject(i);
            if(m!=null && "system".equals(m.optString("role"))) {
                if(!onlyKeys(m,"role","content") || !m.has("content"))
                    throw new IllegalArgumentException("system message contains metadata or no content");
                systemText(m.opt("content"));
            }
        }
        boolean defaultOnly=lead==1 && DEFAULT_SYSTEM.equals(a.getJSONObject(0).opt("content"));
        if(defaultOnly && !middle) return new Proposal(b,false);
        if(lead==0 && middle) throw new IllegalArgumentException("cannot introduce default system without a leading system message");
        StringBuilder text=new StringBuilder();
        boolean hasBlocks=false; JSONArray movedBlocks=new JSONArray();
        if(!defaultOnly) for(int i=0;i<lead;i++) {
            JSONObject m=a.getJSONObject(i);
            if(m.length()!=2 || !m.has("content")) throw new IllegalArgumentException("leading system has metadata or no content");
            String value=systemText(m.opt("content"));
            if(value.isEmpty()) throw new IllegalArgumentException("leading system is empty");
            if(i>0) text.append("\n\n"); text.append(value);
            Object content=m.opt("content");
            if(content instanceof JSONArray) hasBlocks=true;
            if(i>0) movedBlocks.put(new JSONObject().put("type","text").put("text","\n\n"));
            if(content instanceof String) movedBlocks.put(new JSONObject().put("type","text").put("text",content));
            else { JSONArray blocks=(JSONArray)content; for(int j=0;j<blocks.length();j++) {
                if(j>0) movedBlocks.put(new JSONObject().put("type","text").put("text","\n"));
                movedBlocks.put(blocks.get(j));
            }}
        }
        JSONArray result=new JSONArray();
        result.put(defaultOnly ? a.getJSONObject(0) : new JSONObject().put("role","system").put("content",DEFAULT_SYSTEM));
        int user=-1;
        for(int i=lead;i<a.length();i++) { Object item=a.get(i); if(item instanceof JSONObject) { JSONObject m=(JSONObject)item;
            if("system".equals(m.optString("role"))) m.put("role","user");
            if(user<0 && "user".equals(m.optString("role"))) user=result.length();
        } result.put(item); }
        if(!defaultOnly) {
            if(user>=0) {
                JSONObject u=result.getJSONObject(user);
                if(u.has("signature")) throw new IllegalArgumentException("first user message has signature");
                JSONArray blocks=u.optJSONArray("content");
                if(blocks!=null) for(int i=0;i<blocks.length();i++) {
                    JSONObject block=blocks.optJSONObject(i);
                    if(block!=null && block.has("signature")) throw new IllegalArgumentException("first user content has signed block");
                }
            }
            String instructions="<instructions>\n"+text+"\n</instructions>";
            if(hasBlocks) {
                JSONObject first=movedBlocks.getJSONObject(0), last=movedBlocks.getJSONObject(movedBlocks.length()-1);
                first.put("text","<instructions>\n"+first.getString("text"));
                last.put("text",last.getString("text")+"\n</instructions>");
                if(user<0) { JSONArray n=new JSONArray().put(result.get(0)).put(new JSONObject().put("role","user").put("content",movedBlocks)); for(int i=1;i<result.length();i++) n.put(result.get(i)); result=n; }
                else { JSONObject u=result.getJSONObject(user); Object c=u.opt("content");
                    movedBlocks.put(new JSONObject().put("type","text").put("text","\n\n"));
                    if(c instanceof String) movedBlocks.put(new JSONObject().put("type","text").put("text",c));
                    else if(c instanceof JSONArray) { JSONArray old=(JSONArray)c; for(int j=0;j<old.length();j++) movedBlocks.put(old.get(j)); }
                    else throw new IllegalArgumentException("first user content cannot be prefixed losslessly");
                    u.put("content",movedBlocks);
                }
            } else if(user<0) { JSONArray n=new JSONArray().put(result.get(0)).put(new JSONObject().put("role","user").put("content",instructions)); for(int i=1;i<result.length();i++) n.put(result.get(i)); result=n; }
            else { JSONObject u=result.getJSONObject(user); Object c=u.opt("content");
                if(c instanceof String) u.put("content",instructions+"\n\n"+(String)c);
                else if(c instanceof JSONArray) { JSONArray parts=new JSONArray().put(new JSONObject().put("type","text").put("text",instructions)); JSONArray old=(JSONArray)c; for(int j=0;j<old.length();j++) parts.put(old.get(j)); u.put("content",parts); }
                else throw new IllegalArgumentException("first user content cannot be prefixed losslessly");
            }
        }
        b.put("messages",result); return new Proposal(b,true);
    }
    /** Shared, bounded and synchronized. Keys are keyed digests, never raw scope or prompt. */
    public static final class Cache {
        private final byte[] secret = new byte[32];
        private final java.util.LinkedHashMap<String,Integer> entries = new java.util.LinkedHashMap<String,Integer>(256,0.75f,true) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String,Integer> e) { return size()>256; }
        };
        public Cache() { new java.security.SecureRandom().nextBytes(secret); }
        private String fingerprint(String provider,String scope,JSONObject body) throws Exception {
            if(provider==null || provider.isEmpty() || scope==null || scope.isEmpty()) return null;
            JSONArray systems=new JSONArray(); JSONArray messages=body.optJSONArray("messages");
            if(messages!=null) for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i);
                if(m!=null && "system".equals(m.optString("role"))) systems.put(m);
            }
            javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));
            // Length-delimited JSON array prevents concatenation ambiguity.
            byte[] hash=mac.doFinal(new JSONArray().put(provider).put(scope).put(systems).toString().getBytes("UTF-8"));
            StringBuilder hex=new StringBuilder(); for(byte x:hash) hex.append(String.format(java.util.Locale.US,"%02x",x & 255)); return hex.toString();
        }
        private synchronized int get(String key) { Integer v=key==null?null:entries.get(key); return v==null?0:v; }
        private synchronized void remember(String key,int stage) { if(key!=null) entries.put(key,Math.max(stage,get(key))); }
        public synchronized int size() { return entries.size(); }
        public Session start(String provider,String scope,JSONObject prepared) throws Exception {
            JSONObject snapshot=copy(prepared); String key=fingerprint(provider,scope,snapshot);
            return new Session(this,key,snapshot,get(key));
        }
    }
    /** Call initial once, then response for the final result or next for a retry decision.
     * next records the supplied response; return null means terminal. Bodies are independent copies. */
    public static final class Session {
        private final Cache cache; private final String key; private JSONObject current;
        private int stage, sends; private boolean answered, terminal;
        private boolean learnedInitial;
        private int resultStatus; private String resultError;
        private boolean systemComma, pendingSystemLearning, sentCurrent;
        private Session(Cache cache,String key,JSONObject source,int learned) throws Exception {
            this.cache=cache; this.key=key; current=source; learnedInitial=learned>0;
            if(learned>=1) { Proposal p=commaCandidate(400,"unapproved channel",current); current=p.body; stage=1; }
            if(learned>=2) { try { Proposal p=systemAsUserCandidate(400,"unapproved channel",current,true); if(p.changed) { current=p.body; stage=2; } } catch(IllegalArgumentException ignored) { /* unsafe input stays unmodified */ } }
        }
        public JSONObject initial() throws Exception { if(sends!=0) throw new IllegalStateException("initial already issued"); sends=1; return copy(current); }
        public int sends() { return sends; }
        public String stageName() { return stage==0 ? "original" : (learnedInitial && sends==1 ? "learned_" : "") + (stage==1 ? "comma" : "system_as_user"); }
        /** Call after guards pass, immediately before each physical send. Idempotent. */
        public void markSent() {
            if(sends==0 || terminal || answered || sentCurrent) return;
            sentCurrent=true;
            if(pendingSystemLearning) { cache.remember(key,2); pendingSystemLearning=false; }
        }
        public void response(int status,String fullError) {
            if(sends==0 || answered || terminal) return;
            answered=true; resultStatus=status; resultError=fullError;
            if(stage==1 && systemComma && status>=200 && status<300) cache.remember(key,1);
        }
        public JSONObject next(int status,String fullError) throws Exception {
            if(sends==0 || terminal) return null;
            response(status,fullError);
            if(!eligible(resultStatus,resultError) || sends>=3) { terminal=true; return null; }
            if(stage<1) {
                Proposal p=commaCandidate(resultStatus,resultError,current); stage=1;
                if(p.changed) {
                    JSONArray before=current.optJSONArray("messages"), after=p.body.optJSONArray("messages");
                    if(before!=null && after!=null) for(int i=0;i<before.length();i++) {
                        JSONObject m=before.optJSONObject(i), n=after.optJSONObject(i);
                        if(m!=null && n!=null && "system".equals(m.optString("role")) && !String.valueOf(m.opt("content")).equals(String.valueOf(n.opt("content")))) systemComma=true;
                    }
                    current=p.body; sends++; answered=false; sentCurrent=false; return copy(current);
                }
            }
            if(stage<2) {
                stage=2; Proposal p;
                try { p=systemAsUserCandidate(resultStatus,resultError,current,true); }
                catch(IllegalArgumentException unsafe) { terminal=true; return null; }
                if(p.changed) { current=p.body; pendingSystemLearning=true; sends++; answered=false; sentCurrent=false; return copy(current); }
            }
            terminal=true; return null;
        }
    }

}
