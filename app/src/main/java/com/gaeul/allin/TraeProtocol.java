package com.gaeul.allin;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Trae CN wire conversion. Event framing and HTTP belong to the caller.
 * Literal text containing a recognized tool marker is reserved for tool syntax;
 * malformed, unsupported or truncated markers fail the stream, even in quoted prose.
 * Plain text already emitted before a later error cannot be retracted. Tool calls
 * are buffered and emitted only after all calls validate at terminal. */
public final class TraeProtocol {
    private TraeProtocol() {}
    // Each SSE payload is limited to 1 MiB, accumulated response to 8 MiB,
    // buffered reasoning/tool JSON to 1 MiB each; image request data is independent.
    private static final int LIMIT=1024*1024, MAX_CALLS=128;
    // Trae emits these progress/telemetry events alongside an answer; several carry bare strings.
    static final java.util.List<String> AUXILIARY_EVENTS=java.util.Collections.unmodifiableList(Arrays.asList(
            "progress_notice","progress_info","progress","queue","queued","metadata","timing","timing_cost","heartbeat","ping","extra_info"));
    private static IOException bad(String s){return new IOException("Trae protocol: "+s);}
    public static final class UpstreamError extends IOException {
        public final String code;
        public final int status;
        public final String safeMessage;
        private UpstreamError(String code, int status, String message) {
            super(message); this.code=code; this.status=status; this.safeMessage=message;
        }
    }
    private static UpstreamError upstreamError(JSONObject body) {
        JSONObject error=body.optJSONObject("error");
        JSONObject metadata=body.optJSONObject("ResponseMetadata");
        JSONObject metaError=metadata==null?null:metadata.optJSONObject("Error");
        JSONObject detail=error!=null?error:metaError!=null?metaError:body;
        Object rawCode=detail.opt("code"); if(rawCode==null)rawCode=detail.opt("Code");
        if(rawCode==null && detail!=body)rawCode=body.opt("code");
        String code=rawCode instanceof Number || rawCode instanceof String ? rawCode.toString() : "";
        if(!code.matches("[0-9]{1,32}"))code="unknown";
        String message=""; for(Object v:new Object[]{detail.opt("message"),detail.opt("msg"),detail.opt("Message"),body.opt("message"),body.opt("msg")}) if(v instanceof String && !((String)v).isEmpty()){message=(String)v;break;}
        String lower=message.toLowerCase(Locale.ROOT);
        int status; String safe;
        if(code.equals("1001") || code.equals("401")) {status=401;safe="Trae 登录已失效，请重新登录";}
        else if(code.equals("4008") || code.equals("1005") || lower.matches("(?s).*\\b(quota|rate|exceed(?:ed|s|ing)?)\\b.*")) {status=429;safe="Trae 请求频率或额度已达限制，请稍后重试";}
        else if(code.equals("4001") || code.equals("4023")) {status=400;safe="Trae 不支持请求的功能";}
        else {status=502;safe="Trae 上游返回错误";}
        return new UpstreamError(code,status,safe);
    }

    private static String required(JSONObject o,String k) throws IOException { Object v=o.opt(k); if(!(v instanceof String)||((String)v).trim().isEmpty()) throw bad("missing "+k); return (String)v; }
    // org.json's parser is deliberately permissive. Validate JSON grammar before using it.
    private static final class JsonCheck {
        final String s; int p; JsonCheck(String s){this.s=s;}
        void ws(){while(p<s.length() && " \t\r\n".indexOf(s.charAt(p))>=0)p++;}
        void value(int depth) throws IOException { if(depth>128)throw bad("JSON nesting limit"); ws(); if(p>=s.length())throw bad("truncated JSON"); char c=s.charAt(p);
            if(c=='"'){str();return;} if(c=='{'||c=='['){p++; ws(); char end=c=='{'?'}':']'; Set<String> keys=new HashSet<>(); if(p<s.length()&&s.charAt(p)==end){p++;return;} while(true){if(c=='{'){ws();int keyStart=p;str();String key;try{key=new JSONTokener(s.substring(keyStart,p)).nextValue().toString();}catch(Exception ex){throw bad("invalid JSON key");}if(!keys.add(key))throw bad("duplicate JSON key");ws();if(p>=s.length()||s.charAt(p++)!=':')throw bad("invalid JSON object");} value(depth+1);ws();if(p>=s.length())throw bad("truncated JSON");char x=s.charAt(p++);if(x==end)return;if(x!=',')throw bad("invalid JSON separator");}}
            int start=p; while(p<s.length()&&",]} \t\r\n".indexOf(s.charAt(p))<0)p++;String v=s.substring(start,p);if(!v.matches("true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))throw bad("invalid JSON value"); }
        void str() throws IOException {if(p>=s.length()||s.charAt(p++)!='"')throw bad("invalid JSON string");while(p<s.length()){char c=s.charAt(p++);if(c=='"')return;if(c<32)throw bad("invalid JSON character");if(c=='\\'){if(p>=s.length())throw bad("truncated JSON escape");c=s.charAt(p++);if(c=='u'){for(int i=0;i<4;i++)if(p>=s.length()||Character.digit(s.charAt(p++),16)<0)throw bad("invalid JSON escape");}else if("\"\\/bfnrt".indexOf(c)<0)throw bad("invalid JSON escape");}}throw bad("truncated JSON string");}
    }
    private static JSONObject strictObject(String s) throws Exception {if(s==null||s.length()>LIMIT)throw bad("invalid JSON size");JsonCheck j=new JsonCheck(s);j.value(0);j.ws();if(j.p!=s.length())throw bad("trailing JSON");Object x=new JSONTokener(s).nextValue();if(!(x instanceof JSONObject))throw bad("arguments must be a JSON object");return (JSONObject)x;}
    private static void fields(JSONObject o,String... allowed) throws IOException {try{ImageContent.keys(o,allowed);}catch(IllegalArgumentException ex){throw bad("unsupported object field");}}
    private static void functionType(JSONObject o,boolean required) throws IOException {Object t=o.opt("type");if((required&&t==null)||(t!=null&&(!(t instanceof String)||!"function".equals(t))))throw bad("invalid function type");}
    private static void validateTool(JSONObject tool) throws Exception {fields(tool,"type","function");functionType(tool,false);JSONObject f=tool.optJSONObject("function");if(f==null)throw bad("missing tool function");fields(f,"name","description","parameters","strict");if(f.has("strict")&&!Boolean.FALSE.equals(f.opt("strict")))throw bad("unsupported tool strict");required(f,"name");if(f.has("description")&&!(f.opt("description") instanceof String))throw bad("invalid tool description");Object schema=f.opt("parameters");if(schema!=null&&!(schema instanceof JSONObject)&&!(schema instanceof String))throw bad("invalid tool parameters");}
    private static JSONArray parts(Object content,boolean sees,JSONArray images) throws Exception {
        JSONArray out=new JSONArray(); if(content==null||content==JSONObject.NULL)return out;
        if(content instanceof String){out.put(new JSONObject().put("type","text").put("text",content));return out;}
        if(!(content instanceof JSONArray))throw bad("unsupported message content"); JSONArray a=(JSONArray)content;
        for(int i=0;i<a.length();i++){Object v=a.get(i);if(!(v instanceof JSONObject))throw bad("unsupported content part");JSONObject p=(JSONObject)v;String t=p.optString("type");
            if(t.equals("text")||t.equals("input_text")){try{ImageContent.keys(p,"type","text");}catch(IllegalArgumentException ex){throw bad("unsupported text field");}Object text=p.opt("text");if(!(text instanceof String))throw bad("invalid text part");out.put(new JSONObject().put("type","text").put("text",text));}
            else if(t.equals("image_url")||t.equals("input_image")){if(!sees)throw bad("model cannot accept images");Object im=p.opt("image_url");String url=im instanceof JSONObject?((JSONObject)im).optString("url"):im instanceof String?(String)im:p.optString("url");if(url.isEmpty())throw bad("missing image URL");try { ImageContent.validateUrl(url); ImageContent.keys(p,"type","image_url","url"); if(im instanceof JSONObject) ImageContent.keys((JSONObject)im,"url","detail"); } catch(IllegalArgumentException ex){throw bad("invalid image URL or fields");}JSONObject ip=new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",url));if(im instanceof JSONObject && ((JSONObject)im).has("detail"))throw bad("image detail cannot be represented");if(images!=null)images.put(ip);else out.put(ip);}
            else throw bad("unsupported multimodal part: "+t);
        }return out;
    }
    private static void flush(JSONArray out,JSONArray images) throws Exception {if(images.length()>0){JSONArray c=new JSONArray();for(int i=0;i<images.length();i++)c.put(images.get(i));out.put(new JSONObject().put("role","user").put("content",c));while(images.length()>0)images.remove(0);}}
    public static JSONObject request(JSONObject openai,String function,String modelName,int maxOutput,String session,boolean sees,JSONArray levels) throws Exception {
        if(openai==null)throw bad("missing request");
        // Constructed wire body never copies extension fields. Reject known controls
        // whose semantics cannot be represented; optional sampling hints use model defaults.
        for(String key:new String[]{"response_format","stop","logprobs","top_logprobs","logit_bias","functions","function_call","modalities","audio","prediction","web_search_options"})
            if(openai.has(key) && !openai.isNull(key)) throw bad("unsupported request field: "+key);
        for(String key:new String[]{"top_p","seed","presence_penalty","frequency_penalty"}) {
            Object v=openai.opt(key); if(v==null||v==JSONObject.NULL)continue;
            if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw bad("invalid "+key);
            double d=((Number)v).doubleValue();
            if((key.equals("top_p")&&(d<0||d>1))||(key.equals("seed")&&d!=Math.rint(d))||((key.equals("presence_penalty")||key.equals("frequency_penalty"))&&(d< -2||d>2)))throw bad("invalid "+key);
        }
        Object user=openai.opt("user"), metadata=openai.opt("metadata"), streamOptions=openai.opt("stream_options");
        if(user!=null&&user!=JSONObject.NULL&&!(user instanceof String))throw bad("invalid user");
        if(metadata!=null&&metadata!=JSONObject.NULL&&!(metadata instanceof JSONObject))throw bad("invalid metadata");
        if(streamOptions!=null&&streamOptions!=JSONObject.NULL){
            if(!(streamOptions instanceof JSONObject))throw bad("invalid stream_options");
            JSONObject options=(JSONObject)streamOptions; fields(options,"include_usage");
            if(options.has("include_usage")&&!options.isNull("include_usage")&&!(options.opt("include_usage") instanceof Boolean))throw bad("invalid stream_options");
        }
        if(openai.has("n") && (!(openai.opt("n") instanceof Number) || ((Number)openai.opt("n")).doubleValue()!=1))throw bad("only n=1 supported");
        if(openai.has("stream")&&!(openai.opt("stream") instanceof Boolean))throw bad("invalid stream");
        if(openai.has("temperature")&&(!(openai.opt("temperature") instanceof Number)||!Double.isFinite(((Number)openai.opt("temperature")).doubleValue())||((Number)openai.opt("temperature")).doubleValue()<0||((Number)openai.opt("temperature")).doubleValue()>2))throw bad("invalid temperature");
        if(openai.has("max_tokens")&&openai.has("max_completion_tokens"))throw bad("conflicting output limits");
        if(openai.has("tools")&&!(openai.opt("tools") instanceof JSONArray))throw bad("invalid tools");
        if(openai.has("parallel_tool_calls")&&!(openai.opt("parallel_tool_calls") instanceof Boolean))throw bad("invalid parallel_tool_calls");
        if(session!=null&&session.length()>128)throw bad("session exceeds 128 characters");String model=required(openai,"model");if(function==null||function.trim().isEmpty())throw bad("missing function");JSONArray msgs=openai.optJSONArray("messages");if(msgs==null)throw bad("missing messages");JSONArray out=new JSONArray(), pending=new JSONArray();Set<String> calls=new HashSet<>(), results=new HashSet<>(), outstanding=new HashSet<>();
        for(int i=0;i<msgs.length();i++){JSONObject m=msgs.getJSONObject(i);try{ImageContent.keys(m,"role","content","tool_calls","tool_call_id","reasoning_content","name","metadata");}catch(IllegalArgumentException ex){throw bad("unsupported message field");}String role=required(m,"role");if(m.has("reasoning_content")&&(!role.equals("assistant")||(m.opt("reasoning_content")!=JSONObject.NULL&&!(m.opt("reasoning_content") instanceof String))))throw bad("invalid reasoning content");if(role.equals("developer"))role="system";if(!Arrays.asList("system","user","assistant","tool").contains(role))throw bad("unsupported role");if(!role.equals("tool")){if(!outstanding.isEmpty()&&!(role.equals("assistant")&&out.length()>0&&out.getJSONObject(out.length()-1).optString("role").equals("assistant")))throw bad("incomplete tool results");flush(out,pending);}
            JSONArray content=parts(m.opt("content"),sees,role.equals("tool")?pending:null);JSONObject n=new JSONObject().put("role",role).put("content",content);
            if(m.has("name")&&(!(m.opt("name") instanceof String)||!role.equals("tool")))throw bad("invalid tool message name");
            if(role.equals("tool")){String id=required(m,"tool_call_id");if(!outstanding.remove(id)||!results.add(id))throw bad("unmatched, out-of-run or duplicate tool result");n.put("tool_call_id",id);if(content.length()==0)n.put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",pending.length()>0?"(the result's image follows)":"(no output)")));}
            if(m.has("tool_calls")&&!(m.opt("tool_calls") instanceof JSONArray))throw bad("invalid tool calls");if(m.has("tool_call_id")&&!role.equals("tool"))throw bad("tool_call_id on non-tool message");JSONArray tc=m.optJSONArray("tool_calls");if(tc!=null&&tc.length()>0){if(!role.equals("assistant"))throw bad("tool calls on non-assistant message");JSONArray arr=new JSONArray();for(int k=0;k<tc.length();k++){JSONObject c=tc.getJSONObject(k),f=c.optJSONObject("function");if(f==null)throw bad("missing call function");fields(c,"id","type","function");functionType(c,true);fields(f,"name","arguments");String id=required(c,"id"),name=required(f,"name"),args=required(f,"arguments");strictObject(args);if(!calls.add(id))throw bad("duplicate tool call id across history");outstanding.add(id);arr.put(new JSONObject().put("id",id).put("type","function").put("function_call",new JSONObject().put("name",name).put("arguments",args)));}n.put("tool_calls",arr);if(content.length()==0)n.put("content",JSONObject.NULL);}
            if((role.equals("assistant")||role.equals("user"))&&out.length()>0&&out.getJSONObject(out.length()-1).optString("role").equals(role)){JSONObject prev=out.getJSONObject(out.length()-1);JSONArray pc=prev.optJSONArray("content");if(pc==null)pc=new JSONArray();for(int k=0;k<content.length();k++)pc.put(content.get(k));prev.put("content",pc.length()==0?JSONObject.NULL:pc);JSONArray old=prev.optJSONArray("tool_calls"),add=n.optJSONArray("tool_calls");if(add!=null){if(old==null)old=new JSONArray();for(int k=0;k<add.length();k++)old.put(add.get(k));prev.put("tool_calls",old);}}else out.put(n);
        }if(!outstanding.isEmpty())throw bad("incomplete tool results");flush(out,pending);
        JSONObject body=new JSONObject().put("messages",out).put("function",function).put("config_name",model).put("model",model).put("stream",true).put("request_id",UUID.randomUUID().toString()).put("session_id",session==null||session.isEmpty()?UUID.randomUUID().toString():session);if(modelName!=null&&!modelName.isEmpty())body.put("model_name",modelName);
        Object mt=openai.has("max_completion_tokens")?openai.opt("max_completion_tokens"):openai.opt("max_tokens");if(mt!=null){if(!(mt instanceof Number)||!Double.isFinite(((Number)mt).doubleValue())||((Number)mt).doubleValue()!=Math.rint(((Number)mt).doubleValue())||((Number)mt).doubleValue()<1||((Number)mt).doubleValue()>Integer.MAX_VALUE)throw bad("invalid output limit");int n=((Number)mt).intValue();body.put("max_tokens",maxOutput>0?Math.min(n,maxOutput):n);}if(openai.opt("temperature") instanceof Number)body.put("temperature",openai.get("temperature"));
        JSONArray tools=openai.optJSONArray("tools");Object choice=openai.opt("tool_choice");if(choice!=null&&choice!=JSONObject.NULL&&!(choice instanceof JSONObject)&&(!(choice instanceof String)||!Arrays.asList("none","auto","required").contains(choice)))throw bad("invalid tool choice");if(choice==JSONObject.NULL)throw bad("invalid tool choice");if((tools==null||tools.length()==0)&&choice!=null&&!"none".equals(choice)&&!"auto".equals(choice))throw bad("tool choice requires tools");if((tools==null||tools.length()==0)&&openai.has("parallel_tool_calls"))throw bad("parallel_tool_calls requires tools");if(tools!=null)for(int i=0;i<tools.length();i++)validateTool(tools.getJSONObject(i));if(!calls.isEmpty()&&(tools==null||tools.length()==0||"none".equals(choice)))throw bad("tool history requires native tools");if(tools!=null&&tools.length()>0&&!"none".equals(choice)){JSONArray nt=new JSONArray();Set<String> names=new HashSet<>();for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i),f=tool.optJSONObject("function");if(f==null)throw bad("missing tool function");String name=required(f,"name");if(!names.add(name))throw bad("duplicate tool name");Object schema=f.opt("parameters");String text=schema==null?"{}":schema instanceof String?(String)schema:schema.toString();JSONObject obj=strictObject(text);if((model+" "+modelName).toLowerCase(Locale.ROOT).matches(".*(kimi|moonshot).*")){kimi(obj);text=obj.toString();}nt.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",f.optString("description","")).put("parameters",text)));}body.put("tools",nt);if(choice instanceof String)body.put("tool_choice",choice);else if(choice instanceof JSONObject){JSONObject select=(JSONObject)choice;fields(select,"type","function");functionType(select,true);JSONObject f=select.optJSONObject("function");if(f==null)throw bad("invalid tool choice");fields(f,"name");String name=required(f,"name");if(!names.contains(name))throw bad("unknown tool choice");body.put("tool_choice",name);}if(openai.opt("parallel_tool_calls") instanceof Boolean)body.put("parallel_tool_calls",openai.get("parallel_tool_calls"));}
        if(openai.has("reasoning_effort")&&openai.has("reasoningEffort"))throw bad("conflicting reasoning effort");
        String effortKey=openai.has("reasoning_effort")?"reasoning_effort":openai.has("reasoningEffort")?"reasoningEffort":null;
        if(effortKey!=null){Object raw=openai.opt(effortKey);if(!(raw instanceof String)||((String)raw).trim().isEmpty())throw bad("invalid reasoning effort");String effort=(String)raw, wire=null;
            if(levels!=null)for(int i=0;i<levels.length();i++){Object v=levels.opt(i);if(v instanceof String&&((String)v).equalsIgnoreCase(effort)){wire=(String)v;break;}}
            if(wire==null){String alias=effort.equalsIgnoreCase("low")?"light":effort.equalsIgnoreCase("xhigh")?"extra_high":null;if(alias!=null&&levels!=null)for(int i=0;i<levels.length();i++){Object v=levels.opt(i);if(v instanceof String&&((String)v).equalsIgnoreCase(alias)){wire=(String)v;break;}}}
            // Client defaults may name a level this model does not offer; omit it to use the model default.
            if(wire!=null)body.put("reasoning_effort",wire);
        }return body;
    }
    // Work on the already parsed copy, and visit schema positions only.
    private static void kimi(Object v) throws Exception { kimiNode(v, (JSONObject)v, 0); }
    private static Object refTarget(JSONObject root, String ref) throws Exception {
        if (!ref.equals("#") && !ref.startsWith("#/")) throw bad("unsupported Kimi external reference");
        Object cur=root;
        if (ref.equals("#")) return root;
        for (String token:ref.substring(2).split("/",-1)) {
            if (token.matches(".*~(?![01]).*")) throw bad("invalid Kimi reference");
            token=java.net.URLDecoder.decode(token.replace("+","%2B"), "UTF-8").replace("~1","/").replace("~0","~");
            if (cur instanceof JSONObject) cur=((JSONObject)cur).opt(token);
            else if (cur instanceof JSONArray && token.matches("0|[1-9][0-9]*")) { try {cur=((JSONArray)cur).opt(Integer.parseInt(token));} catch(NumberFormatException ex){cur=null;} }
            else cur=null;
            if (cur==null) throw bad("unresolved Kimi reference");
        }
        return cur;
    }
    private static boolean equalJson(Object a,Object b) {return Objects.equals(String.valueOf(a),String.valueOf(b));}
    private static Set<String> types(Object value) throws Exception {
        Set<String> s=new LinkedHashSet<>();
        if(value instanceof String)s.add((String)value);
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){if(!(a.opt(i) instanceof String))throw bad("invalid Kimi type");s.add(a.getString(i));}}
        else throw bad("invalid Kimi type");
        if(s.isEmpty()||!Arrays.asList("string","number","integer","boolean","object","array","null").containsAll(s))throw bad("invalid Kimi type");return s;
    }
    private static Object intersection(Object x,Object y) throws Exception {
        Set<String> a=types(x),b=types(y),out=new LinkedHashSet<>();
        for(String t:a)if(b.contains(t)||(t.equals("integer")&&b.contains("number")))out.add(t);
        if(a.contains("number")&&b.contains("integer"))out.add("integer");
        if(out.isEmpty())throw bad("incompatible Kimi types");
        if(out.size()==1)return out.iterator().next();JSONArray arr=new JSONArray();for(String t:out)arr.put(t);return arr;
    }
    private static void kimiNode(Object value,JSONObject root,int depth) throws Exception {
        if(depth>64)throw bad("Kimi schema nesting limit");
        if(value instanceof Boolean)return;
        if(!(value instanceof JSONObject))throw bad("invalid Kimi schema");
        JSONObject o=(JSONObject)value;
        if(o.has("$dynamicRef")||o.has("$recursiveRef"))throw bad("unsupported Kimi dynamic reference");
        if(o.has("$ref")) {
            if(!(o.opt("$ref") instanceof String))throw bad("invalid Kimi reference");
            Object target=refTarget(root,o.getString("$ref"));
            if(o.has("type")) {
                if(!(target instanceof JSONObject)||target==o)throw bad("unsupported Kimi reference merge");
                JSONObject t=(JSONObject)target;
                if(t.has("$ref")||t.has("$dynamicRef")||t.has("$recursiveRef"))throw bad("unsupported Kimi reference merge");
                JSONObject copy=new JSONObject(t.toString());
                for(Iterator<String> it=o.keys();it.hasNext();) {String k=it.next();if(k.equals("$ref"))continue;Object own=o.get(k);if(copy.has(k)){if(k.equals("type"))own=intersection(copy.get(k),own);else if(!equalJson(copy.get(k),own))throw bad("conflicting Kimi reference constraints");}copy.put(k,own);}
                ArrayList<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());for(String k:keys)o.remove(k);
                for(Iterator<String> it=copy.keys();it.hasNext();){String k=it.next();o.put(k,copy.get(k));}
            }
        }
        if(o.has("anyOf")&&o.has("type")) {
            JSONArray branches=o.optJSONArray("anyOf");if(branches==null||branches.length()==0)throw bad("invalid Kimi anyOf");Object typ=o.get("type");types(typ);
            JSONArray kept=new JSONArray();
            for(int i=0;i<branches.length();i++) {Object branch=branches.get(i);if(!(branch instanceof JSONObject))throw bad("unsupported Kimi anyOf branch");JSONObject child=(JSONObject)branch;if(child.has("$ref"))throw bad("unsupported Kimi anyOf reference");Object narrowed;try {narrowed=child.has("type")?intersection(typ,child.get("type")):typ;}catch(IOException ex){if(ex.getMessage().contains("incompatible Kimi types"))continue;throw ex;}child.put("type",narrowed);kept.put(child);}
            if(kept.length()==0)throw bad("incompatible Kimi anyOf types");o.put("anyOf",kept);o.remove("type");
        }
        if(!o.has("type")&&(o.has("enum")||o.has("const"))) {
            JSONArray vals=o.optJSONArray("enum");if(vals==null&&o.has("const"))vals=new JSONArray().put(o.get("const"));
            if(vals!=null){String type=null;boolean nullable=false,ok=true;for(int i=0;i<vals.length();i++){Object x=vals.get(i);if(x==JSONObject.NULL){nullable=true;continue;}String t=x instanceof String?"string":x instanceof Boolean?"boolean":x instanceof Number?(((Number)x).doubleValue()==Math.rint(((Number)x).doubleValue())?"integer":"number"):null;if(t==null){ok=false;break;}if(type==null)type=t;else if(!type.equals(t)){if((type.equals("integer")&&t.equals("number"))||(type.equals("number")&&t.equals("integer")))type="number";else {ok=false;break;}}}if(ok&&type!=null)o.put("type",nullable?new JSONArray().put(type).put("null"):type);}
        }
        for(String key:new String[]{"properties","patternProperties","$defs","definitions","dependentSchemas"}){Object map=o.opt(key);if(map!=null){if(!(map instanceof JSONObject))throw bad("invalid Kimi schema map");JSONObject m=(JSONObject)map;for(Iterator<String> it=m.keys();it.hasNext();)kimiNode(m.get(it.next()),root,depth+1);}}
        for(String key:new String[]{"items","additionalProperties","additionalItems","contains","propertyNames","unevaluatedProperties","unevaluatedItems","contentSchema","not","if","then","else","allOf","anyOf","oneOf","prefixItems"}){Object child=o.opt(key);if(child!=null){if(child instanceof JSONArray){JSONArray a=(JSONArray)child;for(int i=0;i<a.length();i++)kimiNode(a.get(i),root,depth+1);}else kimiNode(child,root,depth+1);}}
    }
    public static final class Decoder {
        public boolean terminal; public JSONObject usage; public String finishReason;
        private final String model; private final String chunkId="chatcmpl-"+UUID.randomUUID().toString().replace("-",""); private final long created=System.currentTimeMillis()/1000L; private final Set<String> names=new HashSet<>(); private final List<Call> calls=new ArrayList<>(); private final StringBuilder text=new StringBuilder(); private String thought="",lastThought="";private int size; private boolean ended;
        private static final class Call {String id="",name="",args="";Integer index;}
        public Decoder(JSONArray tools,String model){this.model=model==null?"":model;if(tools!=null)for(int i=0;i<tools.length();i++){JSONObject t=tools.optJSONObject(i);if(t!=null){JSONObject f=t.optJSONObject("function");if(f!=null)names.add(f.optString("name"));}}}
        private JSONObject chunk(JSONObject delta,String finish) throws Exception {return new JSONObject().put("id",chunkId).put("created",created).put("object","chat.completion.chunk").put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish)));}
        private long count(JSONObject o,String... keys) throws IOException {for(String key:keys)if(o!=null&&o.has(key)&&!o.isNull(key)){Object x=o.opt(key);if(!(x instanceof Number))throw bad("invalid usage count");double n=((Number)x).doubleValue();if(!Double.isFinite(n)||n<0||n!=Math.rint(n)||n>9007199254740991d)throw bad("invalid usage count");return ((Number)x).longValue();}return 0;}
        private boolean hasCount(JSONObject o,String... keys){for(String key:keys)if(o!=null&&o.has(key)&&!o.isNull(key))return true;return false;}
        private void putUsage(JSONObject u) throws Exception {
            if(u==null)return;
            long p=count(u,"prompt_tokens","input_tokens","promptTokens","inputTokens"), c=count(u,"completion_tokens","output_tokens","completionTokens","outputTokens");
            boolean hp=hasCount(u,"prompt_tokens","input_tokens","promptTokens","inputTokens"),hc=hasCount(u,"completion_tokens","output_tokens","completionTokens","outputTokens"),ht=hasCount(u,"total_tokens","totalTokens");
            long total=count(u,"total_tokens","totalTokens");if(!hp&&!hc&&!ht)return;
            JSONObject v=new JSONObject();if(hp)v.put("prompt_tokens",p);if(hc)v.put("completion_tokens",c);if(ht)v.put("total_tokens",total);else if(hp&&hc)v.put("total_tokens",p+c);
            JSONObject pd=u.optJSONObject("prompt_tokens_details"), id=u.optJSONObject("input_tokens_details");
            long standard=Math.max(Math.max(count(pd,"cached_tokens"),count(id,"cached_tokens")),Math.max(count(u,"cached_tokens"),count(u,"prompt_cache_hit_tokens")));
            long write=count(pd,"cache_write_tokens"),readNative=count(u,"cache_read_input_tokens","cacheReadInputTokens"),writeNative=count(u,"cache_creation_input_tokens","cacheCreationInputTokens");
            // A zero-only cache report is conservatively unknown; it does not prove a miss.
            if(standard>0||write>0){JSONObject detail=new JSONObject();if(standard>0)detail.put("cached_tokens",standard);if(write>0)detail.put("cache_write_tokens",write);v.put("prompt_tokens_details",detail);}
            else if(readNative>0||writeNative>0){if(readNative>0)v.put("cache_read_input_tokens",readNative);if(writeNative>0)v.put("cache_creation_input_tokens",writeNative);}
            else v.put("magpie_cache_unknown",true);
            JSONObject cd=u.optJSONObject("completion_tokens_details");long reasoning=Math.max(count(u,"reasoning_tokens"),count(cd,"reasoning_tokens"));if(reasoning>0)v.put("completion_tokens_details",new JSONObject().put("reasoning_tokens",reasoning));usage=v;
        }
        private void nativeCall(JSONObject t) throws Exception {String id=t.optString("id",t.optString("tool_call_id",""));Integer ix=null;if(t.has("index")){Object x=t.get("index");if(!(x instanceof Number)||!Double.isFinite(((Number)x).doubleValue())||((Number)x).doubleValue()!=Math.rint(((Number)x).doubleValue())||((Number)x).doubleValue()<0||((Number)x).doubleValue()>Integer.MAX_VALUE)throw bad("invalid tool index");ix=((Number)x).intValue();}JSONObject f=t.optJSONObject("function");if(f==null)f=t.optJSONObject("function_call");if(f==null)f=t;String name=f.optString("name",t.optString("tool_name",""));Object a=f.has("arguments")?f.opt("arguments"):f.has("args")?f.opt("args"):t.opt("params");String args=a==null||a==JSONObject.NULL?"":a instanceof String?(String)a:a.toString();Call found=null;for(Call c:calls)if((!id.isEmpty()&&id.equals(c.id))||(ix!=null&&ix.equals(c.index))){if(found!=null&&found!=c)throw bad("conflicting tool identity");found=c;}if(found==null){if(id.isEmpty()&&ix==null)throw bad("tool fragment has no identity");if(calls.size()>=MAX_CALLS)throw bad("too many tools");found=new Call();calls.add(found);}if(!id.isEmpty()){if(!found.id.isEmpty()&&!id.equals(found.id))throw bad("conflicting tool id");found.id=id;}if(ix!=null){if(found.index!=null&&!ix.equals(found.index))throw bad("conflicting tool index");found.index=ix;}if(!found.name.isEmpty()&&!name.isEmpty()&&names.contains(found.name)&&!found.name.equals(name)&&!name.startsWith(found.name))throw bad("conflicting tool name");found.name=merge(found.name,name);found.args=mergeArguments(found.args,args);if(found.name.length()+found.args.length()>LIMIT)throw bad("tool buffer limit");}
        private boolean objectComplete(String s){try{strictObject(s);return true;}catch(Exception ex){return false;}}
        private String mergeArguments(String old,String next) throws IOException {if(old.isEmpty())return next;if(next.isEmpty()||old.equals(next))return old;if(next.startsWith(old))return next;if(objectComplete(old)){if(objectComplete(next))throw bad("conflicting complete argument snapshots");throw bad("arguments after complete object");}return old+next;}
        private String merge(String old,String next){if(next.isEmpty()||old.equals(next)||old.startsWith(next))return old;if(next.startsWith(old))return next;return old+next;}
        private void finish(JSONObject d) {String f=d.optString("finish_reason",d.optString("stop_reason",d.optString("finishReason",""))).toLowerCase(Locale.ROOT);if(Arrays.asList("length","max_tokens","max_output_tokens","max_length").contains(f))finishReason="length";else if(f.equals("content_filter")||f.equals("sensitive"))finishReason="content_filter";}
        public List<JSONObject> accept(String event,String data) throws Exception {if(terminal||ended)throw bad("event after terminal");if(data==null)data="";size+=data.length();if(data.length()>LIMIT||size>8*LIMIT)throw bad("stream buffer limit");String e=(event==null?"":event).replaceAll("([a-z0-9])([A-Z])","$1_$2").replaceAll("[^A-Za-z0-9]+","_").toLowerCase(Locale.ROOT).replaceAll("^_+|_+$","");if(data.equals("[DONE]")){List<JSONObject> result=complete();terminal=true;return result;}// Progress notices carry a bare string, not an object; they carry no answer.
        boolean auxiliary=AUXILIARY_EVENTS.contains(e);
        if(auxiliary && !data.trim().startsWith("{"))return Collections.emptyList();
        JSONObject d=data.trim().isEmpty()?new JSONObject():strictObject(data);if(e.equals("error")||d.has("error")||(d.optJSONObject("ResponseMetadata")!=null && d.optJSONObject("ResponseMetadata").has("Error"))||(d.optInt("code",0)!=0&&(d.has("message")||d.has("msg"))&&!d.has("response")&&!d.has("content")))throw upstreamError(d);if(e.equals("token_usage")){putUsage(d.optJSONObject("usage")!=null?d.optJSONObject("usage"):d);return Collections.emptyList();}if(e.equals("done")||e.equals("response_done")||e.equals("stream_done")||e.equals("stream_finish")){putUsage(d.optJSONObject("usage"));finish(d);List<JSONObject> result=complete();terminal=true;return result;}if(e.equals("extra_info"))return Collections.emptyList();if(!Arrays.asList("","output","message","delta","content","text","answer","chunk","output_delta","message_delta").contains(e)){if(hasSemantic(d)||!AUXILIARY_EVENTS.contains(e))throw bad("unsupported semantic event: "+e);return Collections.emptyList();}List<JSONObject> out=new ArrayList<>();JSONObject delta=d.optJSONObject("delta");if(delta==null)delta=new JSONObject();String r=firstNonempty(d.opt("reasoning_content"),d.opt("reasoning"));boolean deltaReasoning=r.isEmpty();if(deltaReasoning)r=firstNonempty(delta.opt("reasoning_content"),delta.opt("reasoning"));if(!r.isEmpty()){String add=r;if(!deltaReasoning){ // Top-level Trae reasoning can be cumulative; retain the upstream heuristic.
            if(r.equals(lastThought)&&r.length()>=8)add="";else if(thought.length()>=16&&r.startsWith(thought))add=r.substring(thought.length());else if(thought.length()>=16&&r.length()>=16&&thought.endsWith(r))add="";lastThought=r;}
            thought+=add;if(thought.length()>LIMIT)throw bad("reasoning buffer limit");if(!add.isEmpty())out.add(chunk(new JSONObject().put("reasoning_content",add),null));}Object t=d.has("response")?d.opt("response"):d.has("content")?d.opt("content"):delta.has("content")?delta.opt("content"):d.opt("delta");if(t!=null&&t!=JSONObject.NULL&&!(t instanceof String)&&!(t instanceof JSONObject))throw bad("unsupported output content");if(t instanceof String){text.append((String)t);if(text.length()>LIMIT)throw bad("text buffer limit"); drainText(out,false);}JSONArray a=d.optJSONArray("tool_calls");if(a==null)a=delta.optJSONArray("tool_calls");if(a!=null)for(int i=0;i<a.length();i++)nativeCall(a.getJSONObject(i));putUsage(d.optJSONObject("usage"));finish(d);return out;}
        private static String firstNonempty(Object... values){for(Object v:values)if(v instanceof String&&!((String)v).isEmpty())return (String)v;return "";}
        private static boolean hasSemantic(JSONObject d){for(String k:new String[]{"response","content","delta","tool_calls","toolcalls","error","reasoning","reasoning_content","usage","finish_reason","stop_reason"})if(d.has(k))return true;return false;}
        // Keep only a possible marker prefix or an unfinished call; ordinary text is emitted immediately.
        private static final String[] MARKERS={"<tool_call", "<toolcall", "</tool_call", "</toolcall", "<function_calls", "</function_calls", "<antml:function_calls", "<invoke", "<antml:invoke", "<function=", "<｜DSML｜", "<|DSML|", "</｜DSML｜", "</|DSML|"};
        private void drainText(List<JSONObject> out,boolean last) throws Exception {
            while(text.length()>0){String s=text.toString();int pos=-1;String marker=null;
                for(String m:MARKERS){int x=s.indexOf(m);if(x>=0&&(pos<0||x<pos)){pos=x;marker=m;}}
                if(pos>0){out.add(chunk(new JSONObject().put("content",s.substring(0,pos)),null));text.delete(0,pos);continue;}
                if(pos==0){if(!"<tool_call".equals(marker))throw bad("unsupported textual tool marker");String open="<tool_call>";if(s.length()<open.length()){if(last)throw bad("truncated textual tool marker");return;}if(!s.startsWith(open))throw bad("unsupported textual tool marker");int q=s.indexOf("</tool_call>",open.length());if(q<0){if(last)throw bad("truncated textual tool call");return;} parseTextCall(s.substring(open.length(),q).trim());text.delete(0,q+"</tool_call>".length());continue;}
                int keep=0;for(String m:MARKERS)for(int n=1;n<m.length()&&n<=s.length();n++)if(s.endsWith(m.substring(0,n)))keep=Math.max(keep,n);
                if(last&&keep>0)throw bad("truncated textual tool marker");int emit=s.length()-keep;if(emit>0){out.add(chunk(new JSONObject().put("content",s.substring(0,emit)),null));text.delete(0,emit);}return;
            }
        }
        private void parseTextCall(String body) throws Exception {
            JSONObject call;
            if(body.startsWith("{")){call=strictObject(body);}
            else {Matcher m=Pattern.compile("^([^\\s<]+)\\s*((?:<arg_key>.*?</arg_value>\\s*)+)$",Pattern.DOTALL).matcher(body);if(!m.matches())throw bad("unsupported textual tool call");JSONObject args=new JSONObject();Matcher kv=Pattern.compile("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>",Pattern.DOTALL).matcher(m.group(2));int end=0;while(kv.find()){if(!m.group(2).substring(end,kv.start()).trim().isEmpty()||args.has(kv.group(1)))throw bad("malformed GLM tool call");args.put(kv.group(1),kv.group(2));end=kv.end();}if(!m.group(2).substring(end).trim().isEmpty())throw bad("malformed GLM tool call");call=new JSONObject().put("name",m.group(1)).put("arguments",args);}
            String name=required(call,"name");Object args=call.opt("arguments");if(args==null)throw bad("missing textual tool arguments");String str=args instanceof String?(String)args:args.toString();strictObject(str);if(calls.size()>=MAX_CALLS)throw bad("too many tools");Call c=new Call(); // Text syntax provides no ID; create one once for this adapter call.
            c.id="call_"+UUID.randomUUID().toString().replace("-","");c.name=name;c.args=str;calls.add(c);
        }
        private List<JSONObject> complete() throws Exception {List<JSONObject> out=new ArrayList<>();drainText(out,true);
            Set<String> ids=new HashSet<>();JSONArray arr=new JSONArray();for(int i=0;i<calls.size();i++){Call c=calls.get(i);if(c.id.isEmpty()||c.name.trim().isEmpty()||c.args.isEmpty())throw bad("incomplete native tool call");if(!ids.add(c.id))throw bad("duplicate tool call id");if(!names.contains(c.name))throw bad("unknown tool name");strictObject(c.args);arr.put(new JSONObject().put("index",i).put("id",c.id).put("type","function").put("function",new JSONObject().put("name",c.name).put("arguments",c.args)));}if(arr.length()>0)out.add(chunk(new JSONObject().put("tool_calls",arr),null));if(finishReason==null)finishReason=arr.length()>0?"tool_calls":"stop";out.add(chunk(new JSONObject(),finishReason));ended=true;return out;}
        public List<JSONObject> end() throws Exception {if(!terminal)throw bad("stream ended without terminal event");return Collections.emptyList();}
    }
}
