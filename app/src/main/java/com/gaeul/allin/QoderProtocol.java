package com.gaeul.allin;

import java.security.MessageDigest;
import java.security.SecureRandom;
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
    private static final byte[] SALT = new byte[32];
    static { new SecureRandom().nextBytes(SALT); }
    private QoderProtocol() {}
    private static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
    private static boolean nil(Object o) { return o == null || o == JSONObject.NULL; }
    private static String required(Object o, String label) {
        if (!(o instanceof String) || ((String)o).trim().isEmpty()) throw bad(label + " required");
        return (String)o;
    }
    private static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    private static JSONArray copy(JSONArray a) throws Exception { return new JSONArray(a.toString()); }
    private static String session(String scope, String named) throws Exception {
        if (named == null || named.trim().isEmpty()) return UUID.randomUUID().toString();
        MessageDigest d=MessageDigest.getInstance("SHA-256"); d.update(SALT);
        byte[] a=scope.getBytes("UTF-8"), b=named.getBytes("UTF-8");
        d.update(new byte[]{(byte)(a.length>>>24),(byte)(a.length>>>16),(byte)(a.length>>>8),(byte)a.length});
        d.update(a); d.update(b); byte[] h=d.digest();
        StringBuilder hex=new StringBuilder();
        for(int i=0;i<16;i++) { int v=h[i]&255; hex.append("0123456789abcdef".charAt(v>>>4)).append("0123456789abcdef".charAt(v&15)); }
        return hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20);
    }
    private static final long TASK_IDLE_MS=2L*60*60*1000;
    private static final int TASK_LIMIT=512;
    private static final LinkedHashMap<String,Task> TASKS=new LinkedHashMap<>(16,0.75f,true);
    private static final class Task {
        final String id=UUID.randomUUID().toString(); final long begin; long used; boolean answered;
        Task(long now) { begin=now; used=now; }
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
        return first!=null && second!=null && "text".equals(first.optString("type"))
                && first.optString("text").startsWith("[From the result of tool call ")
                && "image_url".equals(second.optString("type"));
    }
    private static boolean toolContinuation(JSONArray messages, int index) {
        int prev=index-1;
        while(prev>=0) {JSONObject m=messages.optJSONObject(prev); if(m!=null && "system".equals(m.optString("role"))) prev--; else break;}
        JSONObject m=prev<0?null:messages.optJSONObject(prev); if(m==null) return false;
        String role=m.optString("role");
        if("tool".equals(role)) return true;
        if("user".equals(role) && toolImageFollowup(m)) return true;
        JSONArray calls=m.optJSONArray("tool_calls");
        return "assistant".equals(role) && calls!=null && calls.length()>0;
    }
    private static Task task(String session, JSONArray messages) throws Exception {
        int at=-1; Object content=JSONObject.NULL;
        for(int i=messages.length()-1;i>=0;i--) {JSONObject m=messages.optJSONObject(i); if(m!=null && "user".equals(m.optString("role")) && !toolContinuation(messages,i)) {at=i; content=m.opt("content"); break;}}
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); digest.update(SALT); digest.update(session.getBytes("UTF-8"));
        digest.update((byte)0); digest.update(Integer.toString(at).getBytes("UTF-8")); digest.update((byte)0); digest.update(canonical(content));
        byte[] h=digest.digest(); StringBuilder key=new StringBuilder(); for(byte b:h) {int v=b&255;key.append("0123456789abcdef".charAt(v>>>4)).append("0123456789abcdef".charAt(v&15));}
        long now=System.currentTimeMillis();
        synchronized(TASKS) {
            Iterator<Map.Entry<String,Task>> it=TASKS.entrySet().iterator(); while(it.hasNext()) {Task t=it.next().getValue(); if(now-t.used>TASK_IDLE_MS || now<t.used) it.remove();}
            Task t=TASKS.get(key.toString()); if(t==null) {t=new Task(now); TASKS.put(key.toString(),t);} t.used=now;
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
        Set<String> pending=new HashSet<>(), seen=new HashSet<>();
        if("chat".equals(protocol)) {
            JSONArray messages=raw.optJSONArray("messages"); if(messages==null) throw bad("Messages required");
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i); if(m==null) throw bad("Invalid message");
                String role=required(m.opt("role"),"Message role");
                if("tool".equals(role)) { Object name=m.opt("name"); if(!nil(name) && !(name instanceof String)) throw bad("Invalid tool result name"); answer(pending,m.opt("tool_call_id")); continue; }
                complete(pending); Object calls=m.opt("tool_calls");
                if(!nil(calls)) {
                    if(!"assistant".equals(role) || !(calls instanceof JSONArray) || ((JSONArray)calls).length()==0) throw bad("Invalid tool calls");
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
    private static JSONArray calls(JSONArray input, Set<String> pending, Set<String> seen) throws Exception {
        if(input.length()==0) throw bad("Empty tool calls");
        JSONArray out=new JSONArray();
        for(int i=0;i<input.length();i++) {
            JSONObject c=input.optJSONObject(i); if(c==null || !"function".equals(c.optString("type"))) throw bad("Unsupported tool call");
            String id=required(c.opt("id"),"Tool call id"); if(!seen.add(id)) throw bad("Duplicate tool call id"); pending.add(id);
            JSONObject f=c.optJSONObject("function"); if(f==null) throw bad("Tool function required");
            String name=required(f.opt("name"),"Tool function name"); Object args=f.opt("arguments");
            strictArgs(args);
            out.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args)));
        }
        return out;
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
    public static JSONObject apply(JSONObject baseBody, JSONObject openai, String explicitSession, String accountScope) throws Exception {
        if(baseBody==null || openai==null) throw bad("Request required");
        required(accountScope,"Account scope");
        JSONArray input=openai.optJSONArray("messages"); if(input==null) throw bad("Messages required");
        Object n=openai.opt("n");
        if(!nil(n) && (!(n instanceof Number) || ((Number)n).doubleValue()!=1.0)) throw bad("Only one output choice is supported");
        Object stream=openai.opt("stream");
        if(!nil(stream) && !(stream instanceof Boolean)) throw bad("Invalid stream option");
        Object choice=openai.opt("tool_choice");
        if(!nil(choice) && (!(choice instanceof String) || !(choice.equals("auto") || choice.equals("none")))) throw bad("Tool choice cannot be enforced by Qoder");
        Object parallel=openai.opt("parallel_tool_calls");
        if(!nil(parallel) && (!(parallel instanceof Boolean) || !(Boolean)parallel)) throw bad("Disabling parallel tools cannot be enforced by Qoder");
        Object rawTools=openai.opt("tools"); if(!nil(rawTools) && !(rawTools instanceof JSONArray)) throw bad("Invalid tools");
        JSONArray converted=tools(nil(rawTools)?new JSONArray():(JSONArray)rawTools);
        JSONObject body=copy(baseBody); JSONArray sys=body.optJSONArray("system"); if(sys==null) throw bad("Base system required");
        JSONObject params=body.optJSONObject("parameters"); if(params==null) throw bad("Base parameters required");
        reasoning(body,openai);
        JSONArray out=new JSONArray().put(new JSONObject().put("role","system").put("content",copy(sys)));
        Set<String> pending=new HashSet<>(), seen=new HashSet<>(); JSONArray deferred=new JSONArray();
        for(int i=0;i<input.length();i++) {
            JSONObject m=input.optJSONObject(i); if(m==null) throw bad("Invalid message");
            String role=required(m.opt("role"),"Message role");
            if("system".equals(role)||"developer".equals(role)) { if(!pending.isEmpty()) throw bad("Missing tool results"); continue; }
            if(!"tool".equals(role) && !pending.isEmpty()) throw bad("Missing tool results");
            if(!"tool".equals(role) && !"user".equals(role) && deferred.length()>0) { out.put(user(deferred)); deferred=new JSONArray(); }
            if("user".equals(role)) {
                JSONArray b=blocks(m.opt("content"),true);
                for(int j=0;j<b.length();j++) deferred.put(b.get(j));
                if(deferred.length()>0) {out.put(user(deferred)); deferred=new JSONArray();}
            } else if("assistant".equals(role)) {
                String thought=historyReasoning(m);
                JSONArray b=blocks(m.opt("content"),false); Object raw=m.opt("tool_calls"); JSONObject assistant=null;
                if(!nil(raw)) { if(!(raw instanceof JSONArray)) throw bad("Invalid tool calls"); assistant=new JSONObject().put("role","assistant").put("content",text(b)).put("tool_calls",calls((JSONArray)raw,pending,seen)); }
                else if(b.length()>0) assistant=new JSONObject().put("role","assistant").put("content",b);
                if(assistant!=null) { if(!thought.isEmpty()) assistant.put("reasoning_content",thought); out.put(assistant); }
            } else if("tool".equals(role)) {
                Object name=m.opt("name"); if(!nil(name) && !(name instanceof String)) throw bad("Invalid tool result name");
                String id=required(m.opt("tool_call_id"),"Tool result id"); if(!pending.remove(id)) throw bad("Unexpected or duplicate tool result");
                JSONArray b=blocks(m.opt("content"),true); String value=text(b); JSONArray images=new JSONArray();
                for(int j=0;j<b.length();j++) if("image_url".equals(b.getJSONObject(j).optString("type"))) images.put(b.get(j));
                if(images.length()>0) {
                    deferred.put(new JSONObject().put("type","text").put("text","[From the result of tool call "+id+":]"));
                    for(int j=0;j<images.length();j++) deferred.put(images.get(j));
                    value+=(value.trim().isEmpty()?"":"\n\n")+(images.length()==1?"[The tool returned an image; it follows in the next message.]":"[The tool returned "+images.length()+" images; they follow in the next message.]");
                }
                out.put(new JSONObject().put("role","tool").put("tool_call_id",id).put("content",value));
            } else throw bad("Unsupported message role");
        }
        if(!pending.isEmpty()) throw bad("Missing tool results");
        if(deferred.length()>0) out.put(user(deferred));
        JSONObject last=out.getJSONObject(out.length()-1);
        if("user".equals(last.optString("role"))) {
            JSONArray b=last.getJSONArray("content"); if(b.length()>0) b.getJSONObject(b.length()-1).put("cache_control",new JSONObject().put("type","ephemeral"));
            last.put("contents",b);
        }
        body.put("messages",out); body.remove("tools"); if(!"none".equals(choice) && converted.length()>0) body.put("tools",converted);
        JSONObject rawModel=body.optJSONObject("model_config"); JSONObject model=modelConfig(rawModel,params.optBoolean("enable_thinking",false));
        JSONObject business=body.optJSONObject("business"); if(business==null) throw bad("Base business required");
        Object selected=business.opt("model_key"); if(!nil(selected) && (!(selected instanceof String) || !selected.equals(model.optString("key")))) throw bad("Qoder model key mismatch");
        body.put("model_config",model);
        String seed=explicitSession, sessionScope=accountScope;
        if(seed==null || seed.trim().isEmpty()) {
            Object key=openai.opt("prompt_cache_key");
            seed=key instanceof String && !((String)key).trim().isEmpty() && ((String)key).length()<=1024
                    ? ((String)key).trim() : null;
            sessionScope=accountScope+"\0cache";
        }
        String id=session(sessionScope,seed); Task t=task(id,input);
        synchronized(TASKS) {
            body.put("session_id",id).put("request_set_id",t.id);
            business.put("id",t.id).put("begin_at",t.begin).put("stage",t.answered?"processing":"start").put("model_key",model.getString("key"));
        }
        return body;
    }
}
