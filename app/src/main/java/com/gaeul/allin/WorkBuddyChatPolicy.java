package com.gaeul.allin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** WorkBuddy Chat compatibility rules pinned to opencode-workbuddy-auth 0.2.26. */
public final class WorkBuddyChatPolicy {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> TYPES = set("string", "number", "integer", "boolean", "object", "array", "null");
    private static final Set<String> BOOLEAN_SCHEMA_VALUES = set("additionalProperties", "unevaluatedProperties");
    private static final String[] SUB_ONE = {"items", "additionalItems", "unevaluatedItems", "contains", "not", "if", "then", "else", "propertyNames", "additionalProperties", "unevaluatedProperties"};
    private static final String[] SUB_LIST = {"anyOf", "oneOf", "allOf", "prefixItems"};
    private static final String[] SUB_MAP = {"properties", "patternProperties", "$defs", "definitions", "dependentSchemas"};
    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final int MAX_SSE_EVENT_CHARS = 8 * 1024 * 1024;

    private WorkBuddyChatPolicy() {}

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>(); Collections.addAll(out, values); return out;
    }

    /** Request body and reversible name map for gateway response conversion. */
    public static final class Prepared {
        private final JSONObject body;
        private final String originalJson;
        private final Map<String, String> upstreamToClient;
        private final Map<String, String> clientToUpstream;
        private Prepared(JSONObject body, String originalJson, Map<String, String> upstreamToClient, Map<String, String> clientToUpstream) {
            this.body=body; this.originalJson=originalJson;
            this.upstreamToClient=Collections.unmodifiableMap(new LinkedHashMap<>(upstreamToClient));
            this.clientToUpstream=Collections.unmodifiableMap(new LinkedHashMap<>(clientToUpstream));
        }
        public JSONObject body() { return new JSONObject(body.toString()); }
        public Map<String,String> upstreamToClientNames() { return upstreamToClient; }
        public Map<String,String> clientToUpstreamNames() { return clientToUpstream; }
        public boolean changed() { return !body.toString().equals(originalJson); }
        public String restoreJsonResponse(String response) {
            if (response==null || upstreamToClient.isEmpty()) return response;
            try { JSONObject json=new JSONObject(response); return restoreNames(json,upstreamToClient)?json.toString():response; }
            catch(Exception ignored) { return response; }
        }
        public String restoreSseResponse(String response) {
            if (response==null || upstreamToClient.isEmpty()) return response;
            return new SseRestorer(upstreamToClient).feed(response,true);
        }
        public SseRestorer newSseRestorer() { return new SseRestorer(upstreamToClient); }
        public String restoreResponse(String contentType,String response) {
            String type=contentType==null?"":contentType.toLowerCase(Locale.ROOT);
            if(type.contains("text/event-stream"))return restoreSseResponse(response);
            if(type.contains("json"))return restoreJsonResponse(response);
            return response;
        }
    }

    /** Incremental SSE adapter; supports arbitrary chunk boundaries and bounded events. */
    public static final class SseRestorer {
        private final Map<String,String> names;
        private final StringBuilder pending=new StringBuilder();
        private boolean finished;
        private SseRestorer(Map<String,String> names){this.names=names;}
        public synchronized String feed(String chunk,boolean endOfInput) {
            if(finished)throw new IllegalStateException("SSE restorer is already finished");
            if(names.isEmpty()) { if(endOfInput)finished=true; return chunk==null?"":chunk; }
            if(chunk!=null)pending.append(chunk);
            StringBuilder ready=new StringBuilder(); int consumed=0;
            while(true){int end=eventEnd(pending,consumed);if(end<0)break;ready.append(restoreSseEvent(pending.substring(consumed,end),names));consumed=end;}
            if(consumed>0)pending.delete(0,consumed);
            if(pending.length()>MAX_SSE_EVENT_CHARS)throw new IllegalArgumentException("WorkBuddy SSE event exceeds compatibility limit");
            if(endOfInput){if(pending.length()>0)ready.append(restoreSseEvent(pending.toString(),names));pending.setLength(0);finished=true;}
            return ready.toString();
        }
    }

    public static Prepared prepare(JSONObject input,JSONObject modelMetadata)throws Exception {
        if(input==null)throw new IllegalArgumentException("missing WorkBuddy Chat request");
        String original=input.toString();JSONObject out=new JSONObject(original);applyDefaultEffort(out,modelMetadata);
        Map<String,String> upstreamToClient=new LinkedHashMap<>(),clientToUpstream=new LinkedHashMap<>();
        fitTools(out,upstreamToClient,clientToUpstream);normalizeToolChoice(out,clientToUpstream);renameHistory(out,clientToUpstream);
        return new Prepared(out,original,upstreamToClient,clientToUpstream);
    }
    public static Prepared prepareForModel(JSONObject input,JSONObject catalog,String modelId)throws Exception{return prepare(input,catalogEntry(catalog,modelId));}
    public static JSONObject catalogEntry(JSONObject catalog,String modelId){
        if(catalog==null||modelId==null)return null;JSONObject exact=catalog.optJSONObject(modelId);if(exact!=null)return exact;
        for(Iterator<String> keys=catalog.keys();keys.hasNext();){String key=keys.next();if(key.equalsIgnoreCase(modelId))return catalog.optJSONObject(key);}return null;
    }

    private static void applyDefaultEffort(JSONObject chat,JSONObject metadata){
        if(metadata==null||namesEffort(chat))return;
        Object budget=chat.opt("max_tokens");if(budget==null||budget==JSONObject.NULL)budget=chat.opt("max_completion_tokens");
        if(budget instanceof Number){double n=((Number)budget).doubleValue();if(Double.isFinite(n)&&n>0&&n<=1024)return;}
        JSONObject reasoning=metadata.optJSONObject("reasoning");JSONArray supported=reasoning==null?null:reasoning.optJSONArray("supportedEfforts");
        List<String> efforts=new ArrayList<>();
        if(supported!=null&&supported.length()>0){for(int i=0;i<supported.length();i++)if(supported.opt(i) instanceof String)efforts.add(supported.optString(i));}
        else {if(!metadata.optBoolean("supportsReasoning",false))return;efforts.add("low");efforts.add("high");efforts.add("max");String own=reasoning==null?"":reasoning.optString("effort","");if(validEffort(own)&&!efforts.contains(own))efforts.add(own);efforts=orderedEfforts(efforts);}
        String effort=reasoning==null?"":reasoning.optString("defaultEffort",reasoning.optString("effort",""));
        if(validEffort(effort)&&efforts.contains(effort))chat.put("reasoning_effort",effort);
    }
    private static List<String> orderedEfforts(List<String> efforts){String[] order={"low","medium","high","xhigh","max"};List<String> out=new ArrayList<>();for(String e:order)if(efforts.contains(e))out.add(e);return out;}
    private static boolean namesEffort(JSONObject chat){if(chat.has("reasoning_effort"))return true;JSONObject r=chat.optJSONObject("reasoning");return r!=null&&r.has("effort");}
    private static boolean validEffort(String e){return "low".equals(e)||"medium".equals(e)||"high".equals(e)||"xhigh".equals(e)||"max".equals(e);}

    private static void fitTools(JSONObject body,Map<String,String> upstreamToClient,Map<String,String> clientToUpstream){
        JSONArray tools=body.optJSONArray("tools");if(tools==null||tools.length()==0)return;Set<String> taken=new HashSet<>();
        for(int i=0;i<tools.length();i++){JSONObject fn=functionOf(tools.optJSONObject(i));String n=fn==null?"":fn.optString("name","");if(VALID_NAME.matcher(n).matches())taken.add(n);}
        for(int i=0;i<tools.length();i++){
            JSONObject fn=functionOf(tools.optJSONObject(i));if(fn==null)continue;String name=fn.optString("name","");
            if(fn.has("name") && fn.opt("name") instanceof String && !VALID_NAME.matcher(name).matches()){
                if(clientToUpstream.containsKey(name))throw new IllegalArgumentException("duplicate WorkBuddy tool name cannot be mapped safely");
                String safe=safeName(name,taken);fn.put("name",safe);upstreamToClient.put(safe,name);clientToUpstream.put(name,safe);
            }
            if(fn.has("parameters")){
                Object raw=fn.opt("parameters");JSONObject schema=raw instanceof JSONObject?(JSONObject)raw:new JSONObject().put("type","object").put("properties",new JSONObject());
                fitSchemaSyntax(schema,0);fitSchemaShape(schema,0);typeObjectUnions(schema,schema,0);objectRoot(schema,schema,0);fn.put("parameters",schema);
            }
        }
    }
    private static JSONObject functionOf(JSONObject tool){return tool==null?null:tool.optJSONObject("function");}
    private static String safeName(String name,Set<String> taken){String base=name.replaceAll("[^A-Za-z0-9_-]","_");if(base.isEmpty())base="tool";String result=base;for(int i=2;taken.contains(result);i++)result=base+"_"+i;taken.add(result);return result;}
    private static void normalizeToolChoice(JSONObject body,Map<String,String> map){
        if(!body.has("tool_choice"))return;Object raw=body.opt("tool_choice");
        if(raw instanceof String){String name=((String)raw).trim();if("none".equalsIgnoreCase(name)){body.remove("tool_choice");body.remove("tools");body.remove("functions");}else if(map.containsKey(name))body.put("tool_choice",map.get(name));return;}
        if(!(raw instanceof JSONObject)){body.remove("tool_choice");return;}JSONObject choice=(JSONObject)raw;String type=choice.optString("type","").trim().toLowerCase(Locale.ROOT);
        if("none".equals(type)){body.remove("tool_choice");body.remove("tools");body.remove("functions");}
        else if("auto".equals(type)||"required".equals(type))body.put("tool_choice",type);
        else if("function".equals(type)){JSONObject fn=choice.optJSONObject("function");String name=fn==null?"":fn.optString("name","").trim();body.put("tool_choice",map.containsKey(name)?map.get(name):(name.isEmpty()?"auto":name));}
        else body.remove("tool_choice");
    }
    private static void renameHistory(JSONObject body,Map<String,String> map){
        JSONArray messages=body.optJSONArray("messages");for(int i=0;messages!=null&&i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;
            JSONArray calls=m.optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++){JSONObject c=calls.optJSONObject(j),fn=c==null?null:c.optJSONObject("function");if(fn!=null&&map.containsKey(fn.optString("name","")))fn.put("name",map.get(fn.optString("name","")));}
            if("tool".equals(m.optString("role"))&&map.containsKey(m.optString("name","")))m.put("name",map.get(m.optString("name","")));
        }
    }

    private static void fitSchemaSyntax(Object value,int depth){
        if(depth>MAX_SCHEMA_DEPTH)return;if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)fitSchemaSyntax(a.opt(i),depth+1);return;}if(!(value instanceof JSONObject))return;
        JSONObject s=(JSONObject)value;Object p=s.opt("pattern");if(p instanceof String)s.put("pattern",escapeNul((String)p));Object items=s.opt("items");
        if(items instanceof JSONArray){JSONArray old=(JSONArray)items,valid=new JSONArray();for(int i=0;i<old.length();i++){Object item=old.opt(i);if(item instanceof JSONObject||item instanceof JSONArray)valid.put(item);}Object replacement=valid.length()==0?new JSONObject():valid.length()==1?valid.opt(0):new JSONObject().put("anyOf",valid);s.put("items",replacement);s.remove("additionalItems");}
        for(Iterator<String> it=s.keys();it.hasNext();)fitSchemaSyntax(s.opt(it.next()),depth+1);
    }
    private static String escapeNul(String pattern){StringBuilder out=new StringBuilder();for(int i=0;i<pattern.length();i++){char c=pattern.charAt(i);if(c!='\\'||i+1>=pattern.length()){out.append(c);continue;}char n=pattern.charAt(i+1);if(n=='\\'){out.append("\\\\");i++;}else if(n=='0'&&(i+2>=pattern.length()||!Character.isDigit(pattern.charAt(i+2)))){out.append("\\u0000");i++;}else out.append(c);}return out.toString();}
    private static void fitSchemaShape(JSONObject s,int depth){
        if(depth>MAX_SCHEMA_DEPTH)return;normalizeType(s);Object required=s.opt("required");if(s.has("required")){if(!(required instanceof JSONArray))s.remove("required");else{JSONArray a=(JSONArray)required,b=new JSONArray();for(int i=0;i<a.length();i++)if(a.opt(i) instanceof String)b.put(a.opt(i));if(a.length()!=b.length())s.put("required",b);}}
        fitEnum(s);for(String k:SUB_ONE)if(s.has(k)){Object child=s.opt(k);if(child instanceof Boolean&&!BOOLEAN_SCHEMA_VALUES.contains(k)){child=Boolean.TRUE.equals(child)?new JSONObject():new JSONObject().put("not",new JSONObject());s.put(k,child);}fitSchemaValue(child,k,depth+1);}
        for(String k:SUB_LIST)fitSchemaValue(s.opt(k),k,depth+1);for(String k:SUB_MAP)fitSchemaMap(s.optJSONObject(k),depth+1);
    }
    private static void fitSchemaMap(JSONObject map,int depth){if(map==null||depth>MAX_SCHEMA_DEPTH)return;for(Iterator<String> it=map.keys();it.hasNext();){String k=it.next();Object child=map.opt(k);if(child instanceof Boolean){child=Boolean.TRUE.equals(child)?new JSONObject():new JSONObject().put("not",new JSONObject());map.put(k,child);}fitSchemaValue(child,k,depth+1);}}
    private static void fitSchemaValue(Object v,String keyword,int depth){if(depth>MAX_SCHEMA_DEPTH)return;if(v instanceof JSONObject)fitSchemaShape((JSONObject)v,depth);else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){Object child=a.opt(i);if(child instanceof Boolean){child=Boolean.TRUE.equals(child)?new JSONObject():new JSONObject().put("not",new JSONObject());a.put(i,child);}fitSchemaValue(child,keyword,depth+1);}}}
    private static void normalizeType(JSONObject s){if(!s.has("type"))return;Object raw=s.opt("type");JSONArray a=raw instanceof JSONArray?(JSONArray)raw:new JSONArray().put(raw),clean=new JSONArray();Set<String> seen=new HashSet<>();for(int i=0;i<a.length();i++){Object v=a.opt(i);if(v instanceof String){String t=((String)v).toLowerCase(Locale.ROOT);if(TYPES.contains(t)&&seen.add(t))clean.put(t);}}if(clean.length()==0)s.remove("type");else if(clean.length()==1)s.put("type",clean.opt(0));else s.put("type",clean);}
    private static void fitEnum(JSONObject s){
        if(!s.has("enum"))return;Object raw=s.opt("enum");if(!(raw instanceof JSONArray)||((JSONArray)raw).length()==0){s.remove("enum");return;}Object declared=s.opt("type");List<String> types=new ArrayList<>();if(declared instanceof String)types.add((String)declared);else if(declared instanceof JSONArray){JSONArray a=(JSONArray)declared;for(int i=0;i<a.length();i++)if(a.opt(i) instanceof String)types.add(a.optString(i));}int nonNull=0;for(String t:types)if(!"null".equals(t))nonNull++;if(nonNull==1)return;
        JSONArray values=(JSONArray)raw;Map<String,JSONArray> byType=new LinkedHashMap<>();boolean nullSeen=false;for(int i=0;i<values.length();i++){Object v=values.opt(i);String type=jsonType(v);if("null".equals(type)){nullSeen=true;continue;}JSONArray bucket=byType.get(type);if(bucket==null){bucket=new JSONArray();byType.put(type,bucket);}bucket.put(v);}
        if(byType.containsKey("integer")&&byType.containsKey("number")){JSONArray nums=byType.get("number"),ints=byType.remove("integer");for(int i=0;i<ints.length();i++)nums.put(ints.opt(i));}
        if(byType.size()<=1){String only=byType.isEmpty()?"null":byType.keySet().iterator().next();s.put("type",nullSeen&&!"null".equals(only)?new JSONArray().put(only).put("null"):only);return;}
        JSONArray split=new JSONArray();for(Map.Entry<String,JSONArray> e:byType.entrySet())split.put(new JSONObject().put("type",e.getKey()).put("enum",e.getValue()));if(nullSeen)split.put(new JSONObject().put("type","null"));s.remove("type");s.remove("enum");JSONObject union=new JSONObject().put("anyOf",split);JSONArray prior=s.optJSONArray("anyOf");if(prior!=null){JSONArray all=s.optJSONArray("allOf");if(all==null)all=new JSONArray();all.put(new JSONObject().put("anyOf",prior));all.put(union);s.remove("anyOf");s.put("allOf",all);}else s.put("anyOf",split);
    }
    private static String jsonType(Object v){if(v==null||v==JSONObject.NULL)return"null";if(v instanceof Boolean)return"boolean";if(v instanceof String)return"string";if(v instanceof JSONArray)return"array";if(v instanceof JSONObject)return"object";if(v instanceof Number){double n=((Number)v).doubleValue();return Double.isFinite(n)&&n==Math.rint(n)?"integer":"number";}return"null";}
    private static void typeObjectUnions(Object value,JSONObject root,int depth){if(depth>MAX_SCHEMA_DEPTH)return;if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)typeObjectUnions(a.opt(i),root,depth+1);return;}if(!(value instanceof JSONObject))return;JSONObject s=(JSONObject)value;JSONArray one=s.optJSONArray("oneOf");if(!s.has("type")&&one!=null&&one.length()>0){boolean all=true;for(int i=0;i<one.length();i++)if(!objectBranch(root,one.opt(i),new HashSet<String>(),0)){all=false;break;}if(all)s.put("type","object");}for(String k:SUB_ONE)typeObjectUnions(s.opt(k),root,depth+1);for(String k:SUB_LIST)typeObjectUnions(s.opt(k),root,depth+1);for(String k:SUB_MAP){JSONObject map=s.optJSONObject(k);if(map!=null)for(Iterator<String> it=map.keys();it.hasNext();)typeObjectUnions(map.opt(it.next()),root,depth+1);}if(depth==0)objectRoot(s,root,0);}
    private static boolean objectBranch(JSONObject root,Object branch,Set<String> seen,int depth){if(!(branch instanceof JSONObject)||depth>16)return false;JSONObject b=(JSONObject)branch;if(b.has("type"))return"object".equals(b.optString("type",""));String ref=b.optString("$ref","");if(!ref.startsWith("#/")||!seen.add(ref))return false;JSONObject target=localRef(root,ref);return target!=null&&target!=b&&objectBranch(root,target,seen,depth+1);}
    private static JSONObject localRef(JSONObject root,String ref){if(root==null||ref==null||!ref.startsWith("#/"))return null;Object at=root;for(String raw:ref.substring(2).split("/")){String k=raw.replace("~1","/").replace("~0","~");if(at instanceof JSONObject)at=((JSONObject)at).opt(k);else if(at instanceof JSONArray)try{at=((JSONArray)at).opt(Integer.parseInt(k));}catch(Exception e){return null;}else return null;}return at instanceof JSONObject?(JSONObject)at:null;}
    private static boolean objectRoot(JSONObject s,JSONObject root,int depth){
        if(depth>8)return false;JSONArray all=s.optJSONArray("allOf"),any=s.optJSONArray("anyOf"),one=s.optJSONArray("oneOf");if("object".equals(s.optString("type",""))&&all==null&&any==null&&one==null)return false;
        JSONObject props=s.optJSONObject("properties");if(props==null)props=new JSONObject();JSONArray required=s.optJSONArray("required");if(required==null)required=new JSONArray();
        for(int i=0;all!=null&&i<all.length();i++){JSONObject b=materializeBranch(root,all.opt(i),depth);if(b==null)continue;JSONObject p=b.optJSONObject("properties");if(p!=null)copyMissing(props,p);appendStrings(required,b.optJSONArray("required"));}
        List<JSONObject> branches=new ArrayList<>();collectBranches(root,any,branches,depth);collectBranches(root,one,branches,depth);Map<String,List<Object>> byProp=new LinkedHashMap<>();
        for(JSONObject b:branches){JSONObject p=b.optJSONObject("properties");if(p==null)continue;for(Iterator<String> it=p.keys();it.hasNext();){String k=it.next();if(props.has(k))continue;Object v=p.opt(k);List<Object> vals=byProp.get(k);if(vals==null){vals=new ArrayList<>();byProp.put(k,vals);}boolean found=false;for(Object old:vals)if(jsonSimilar(old,v)){found=true;break;}if(!found)vals.add(v);}}
        for(Map.Entry<String,List<Object>> e:byProp.entrySet()){List<Object> vals=e.getValue();if(vals.size()==1)props.put(e.getKey(),vals.get(0));else{JSONArray a=new JSONArray();for(Object v:vals)a.put(v);props.put(e.getKey(),new JSONObject().put("anyOf",a));}}
        if(!branches.isEmpty()){JSONObject p0=branches.get(0).optJSONObject("properties");if(p0!=null)for(Iterator<String> it=p0.keys();it.hasNext();){String k=it.next();boolean every=true;for(JSONObject b:branches){JSONObject p=b.optJSONObject("properties");if(p==null||!p.has(k)||!contains(b.optJSONArray("required"),k)){every=false;break;}}if(every&&!contains(required,k))required.put(k);}}
        s.remove("allOf");s.remove("anyOf");s.remove("oneOf");s.put("type","object");s.put("properties",props);JSONArray unique=new JSONArray();Set<String> names=new HashSet<>();for(int i=0;i<required.length();i++){Object v=required.opt(i);if(v instanceof String&&props.has((String)v)&&names.add((String)v))unique.put(v);}if(unique.length()>0)s.put("required",unique);else s.remove("required");return true;
    }
    private static void collectBranches(JSONObject root,JSONArray alternatives,List<JSONObject> out,int depth){for(int i=0;alternatives!=null&&i<alternatives.length();i++){JSONObject b=materializeBranch(root,alternatives.opt(i),depth);if(b!=null&&("object".equals(b.optString("type",""))||b.opt("properties") instanceof JSONObject))out.add(b);}}
    private static JSONObject materializeBranch(JSONObject root,Object branch,int depth){
        if(!(branch instanceof JSONObject))return null;JSONObject value=(JSONObject)branch;String ref=value.optString("$ref","");
        if(ref.startsWith("#/")){value=localRef(root,ref);if(value==null)return null;}
        if(depth<8&&(value.opt("allOf") instanceof JSONArray||value.opt("anyOf") instanceof JSONArray||value.opt("oneOf") instanceof JSONArray)){
            JSONObject copy=new JSONObject(value.toString());objectRoot(copy,root,depth+1);return copy;
        }
        return value;
    }
    private static void copyMissing(JSONObject to,JSONObject from){for(Iterator<String> it=from.keys();it.hasNext();){String k=it.next();if(!to.has(k))to.put(k,from.opt(k));}}
    private static void appendStrings(JSONArray to,JSONArray from){for(int i=0;from!=null&&i<from.length();i++)if(from.opt(i) instanceof String)to.put(from.opt(i));}
    private static boolean contains(JSONArray a,String want){for(int i=0;a!=null&&i<a.length();i++)if(want.equals(a.opt(i)))return true;return false;}
    private static boolean jsonSimilar(Object a,Object b){if(a==b)return true;if(a==null||b==null)return false;if(a instanceof JSONObject&&b instanceof JSONObject)return((JSONObject)a).similar(b);if(a instanceof JSONArray&&b instanceof JSONArray)return((JSONArray)a).similar(b);return a.equals(b);}

    private static boolean restoreNames(JSONObject response,Map<String,String> names){boolean changed=false;JSONArray choices=response.optJSONArray("choices");for(int i=0;choices!=null&&i<choices.length();i++){JSONObject c=choices.optJSONObject(i);if(c!=null){changed|=restoreCalls(c.optJSONObject("message"),names);changed|=restoreCalls(c.optJSONObject("delta"),names);}}return changed;}
    private static boolean restoreCalls(JSONObject message,Map<String,String> names){boolean changed=false;JSONArray calls=message==null?null:message.optJSONArray("tool_calls");for(int i=0;calls!=null&&i<calls.length();i++){JSONObject c=calls.optJSONObject(i),fn=c==null?null:c.optJSONObject("function");if(fn!=null){String original=names.get(fn.optString("name",""));if(original!=null){fn.put("name",original);changed=true;}}}return changed;}
    private static String restoreSseEvent(String raw,Map<String,String> names){String[] lines=raw.split("\n",-1);StringBuilder data=new StringBuilder();for(String line:lines){String clean=line.endsWith("\r")?line.substring(0,line.length()-1):line;if(!clean.startsWith("data:"))continue;if(data.length()>0)data.append('\n');String v=clean.substring(5);if(v.startsWith(" "))v=v.substring(1);data.append(v);}if(data.length()==0||"[DONE]".contentEquals(data))return raw;try{JSONObject parsed=new JSONObject(data.toString());if(!restoreNames(parsed,names))return raw;StringBuilder out=new StringBuilder(raw.length()+16);boolean wrote=false;for(int i=0;i<lines.length;i++){String line=lines[i],clean=line.endsWith("\r")?line.substring(0,line.length()-1):line;if(clean.startsWith("data:")){if(!wrote){out.append("data: ").append(parsed.toString());wrote=true;}}else out.append(clean);if(i<lines.length-1)out.append('\n');}return out.toString();}catch(Exception ignored){return raw;}}
    private static int eventEnd(CharSequence s,int start){for(int i=start;i<s.length();i++){if(s.charAt(i)!='\n')continue;boolean blank=i==start||(i==start+1&&s.charAt(start)=='\r');if(blank)return i+1;start=i+1;}return -1;}
}
