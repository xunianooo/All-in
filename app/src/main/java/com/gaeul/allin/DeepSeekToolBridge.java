package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.util.*;
import java.util.regex.*;

/** Prompt-based tool calling for DeepSeek Web; no tools are executed here. */
public final class DeepSeekToolBridge {
    private DeepSeekToolBridge() {}
    public static final int MAX_BLOCK = 65536; // UTF-16 code units, including delimiters
    public interface Listener { void onText(String text); void onToolCall(ToolCall call); }
    public static final class ToolCall {
        public final String id, name, arguments;
        ToolCall(String name, String arguments) { this.id="call_"+UUID.randomUUID().toString().replace("-", ""); this.name=name; this.arguments=arguments; }
    }
    public static final class Prepared {
        public final boolean enabled;
        private final JSONArray messages, tools;
        private final Set<String> names;
        private Prepared(JSONArray m, JSONArray t, Set<String> n, boolean e) { messages=m; tools=t; names=Collections.unmodifiableSet(n); enabled=e; }
        public Set<String> allowedNames() { return names; }
        public StreamParser newParser(Listener listener) { return new StreamParser(enabled, names, listener); }
        public String buildPrompt() { return buildPrompt(0); }
        public String buildPrompt(int from) {
            ArrayList<String> parts=new ArrayList<>();
            if(enabled) {
                StringBuilder s=new StringBuilder("[Tool calling]\nYou can call the tools listed at the end of this block. To call one, end your reply with a line of exactly this shape:\n<tool_call>{\"name\":\"tool name\",\"arguments\":{}}</tool_call>\n- \"arguments\" carries the tool's parameters as JSON, following its schema.\nYou may output several <tool_call> blocks in one reply to call tools in parallel.\nDo not wrap a call in code fences, and write nothing after the calls: their results arrive with the next turn.\nUse exactly this markup — never the DSML/内部工具 format, never any other tag style.\nWhen no tool fits, answer normally and call nothing.\nThe tools:");
                for(int i=0;i<tools.length();i++){JSONObject f=tools.optJSONObject(i).optJSONObject("function"); JSONObject row=new JSONObject(); try { row.put("name",f.optString("name"));row.put("description",f.optString("description",""));row.put("parameters",f.opt("parameters") == null ? new JSONObject():f.opt("parameters")); } catch(JSONException e){throw new IllegalStateException(e);} s.append('\n').append(row); } parts.add(s.toString());
            }
            Map<String,String> ids=new HashMap<>();
            for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i); if(m==null)continue; JSONArray cs=m.optJSONArray("tool_calls");if(cs!=null)for(int j=0;j<cs.length();j++){JSONObject c=cs.optJSONObject(j);if(c!=null && c.optJSONObject("function")!=null)ids.put(c.optString("id"),c.optJSONObject("function").optString("name"));}}
            int index=Math.max(0,from);
            boolean spoke=false;
            for(;index<messages.length();index++) { JSONObject m=messages.optJSONObject(index);if(m==null)continue; String role=m.optString("role"), text=content(m.opt("content"));
                if("system".equals(role)||"developer".equals(role)){if(!text.isEmpty())parts.add(text);continue;}
                if("assistant".equals(role)){StringBuilder b=new StringBuilder(text);JSONArray cs=m.optJSONArray("tool_calls");if(cs!=null)for(int j=0;j<cs.length();j++){JSONObject c=cs.optJSONObject(j), f=c==null?null:c.optJSONObject("function");if(f==null||f.optString("name").isEmpty())continue;Object arg=f.opt("arguments");String a=arg instanceof String?(String)arg:arg instanceof JSONObject?arg.toString():"{}";if(!validObject(a))continue;if(b.length()>0)b.append('\n');b.append("<tool_call>{");if(c.opt("id") instanceof String)b.append("\"id\":").append(JSONObject.quote(c.optString("id"))).append(',');b.append("\"name\":").append(JSONObject.quote(f.optString("name"))).append(",\"arguments\":").append(a).append("}</tool_call>");}if(b.length()>0)parts.add("Assistant: "+b);continue;}
                if("tool".equals(role)){if(!text.isEmpty()){String name=ids.get(m.optString("tool_call_id"));String id=m.opt("tool_call_id") instanceof String?m.optString("tool_call_id"):"";parts.add("Tool result"+(name==null||name.isEmpty()?(id.isEmpty()?"":" (id="+id+")"):(id.isEmpty()?" ("+name+")":" ("+name+", id="+id+")"))+": "+text);}continue;}
                if(!text.isEmpty()){parts.add((spoke?"User: ":"")+text);spoke=true;}
            }
            return join(parts,"\n\n");
        }
    }
    private static String content(Object o){if(o instanceof String)return (String)o;if(o instanceof JSONArray){StringBuilder s=new StringBuilder();JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++){Object p=a.opt(i);if(p instanceof String)s.append(p);else if(p instanceof JSONObject)s.append(((JSONObject)p).optString("text",""));}return s.toString();}return "";}
    private static String join(List<String> a,String sep){StringBuilder s=new StringBuilder();for(String v:a){if(s.length()>0)s.append(sep);s.append(v);}return s.toString();}
    /** IllegalArgumentException means the caller should return HTTP 400. */
    public static Prepared analyze(JSONObject request) {
        if(request==null)throw new IllegalArgumentException("request required");
        Object choice=request.opt("tool_choice"); if(choice==null||choice==JSONObject.NULL)choice="auto";
        if(!(choice instanceof String)||(!"auto".equals(choice)&&!"none".equals(choice)))throw new IllegalArgumentException("tool_choice required or named is unsupported");
        Object raw=request.opt("tools");if(raw!=null && raw!=JSONObject.NULL && !(raw instanceof JSONArray))throw new IllegalArgumentException("tools must be an array");
        JSONArray tools=raw instanceof JSONArray?(JSONArray)raw:new JSONArray(); Set<String> names=new LinkedHashSet<>();
        for(int i=0;i<tools.length();i++){JSONObject t=tools.optJSONObject(i), f=t==null?null:t.optJSONObject("function");Object name=f==null?null:f.opt("name");if(t==null||!"function".equals(t.optString("type"))||!(name instanceof String)||((String)name).trim().isEmpty()||!names.add((String)name))throw new IllegalArgumentException("invalid or duplicate tool name");}
        JSONArray messages=request.optJSONArray("messages");if(messages==null)messages=new JSONArray();
        Set<String> historyIds=new HashSet<>();
        for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null || !"assistant".equals(m.optString("role")))continue;Object rawCalls=m.opt("tool_calls");if(rawCalls==null||rawCalls==JSONObject.NULL)continue;if(!(rawCalls instanceof JSONArray))throw new IllegalArgumentException("messages["+i+"].tool_calls must be an array");JSONArray cs=(JSONArray)rawCalls;for(int j=0;j<cs.length();j++){JSONObject c=cs.optJSONObject(j),f=c==null?null:c.optJSONObject("function");String field="messages["+i+"].tool_calls["+j+"]";if(f==null || !(f.opt("name") instanceof String)||f.optString("name").trim().isEmpty())throw new IllegalArgumentException(field+".function.name is invalid");Object id=c.opt("id");if(id!=null&&id!=JSONObject.NULL){if(!(id instanceof String)||((String)id).isEmpty()||!historyIds.add((String)id))throw new IllegalArgumentException(field+".id is invalid or duplicate");}Object a=f.opt("arguments");String args=a instanceof String?(String)a:a instanceof JSONObject?a.toString():null;if(args==null||!validObject(args))throw new IllegalArgumentException(field+".function.arguments must be a JSON object");}}

        try {return new Prepared(new JSONArray(messages.toString()),new JSONArray(tools.toString()),names,!"none".equals(choice)&&!names.isEmpty());}catch(JSONException e){throw new IllegalArgumentException(e);}
    }
    private static final Pattern START=Pattern.compile("<(?:tool_call>|[｜|\\s]*DSML[｜|\\s]*(?:calls|invoke)\\b[^>]*>)");
    private static final Pattern DS_OPEN=Pattern.compile("<[｜|\\s]*DSML[｜|\\s]*(calls|invoke)\\b[^>]*>");
    private static final Pattern INVOKE=Pattern.compile("<[｜|\\s]*DSML[｜|\\s]*invoke\\b([^>]*)>([\\s\\S]*?)<[｜|\\s]*/[｜|\\s]*DSML[｜|\\s]*invoke\\s*>");
    private static final Pattern PARAM=Pattern.compile("<[｜|\\s]*DSML[｜|\\s]*parameter\\b([^>]*)>([\\s\\S]*?)<[｜|\\s]*/[｜|\\s]*DSML[｜|\\s]*parameter\\s*>");
    private static final Pattern NAME=Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
    private static String name(String attrs){Matcher m=NAME.matcher(attrs);return m.find()?m.group(1):null;}
    private static Map<String,String> parseAttributes(String attrs){Map<String,String> result=new HashMap<>();Matcher m=Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"").matcher(attrs);int at=0;while(at<attrs.length()){if(attrs.substring(at).trim().isEmpty())return result;m.region(at,attrs.length());if(!m.lookingAt()||result.put(m.group(1),m.group(2))!=null)return null;at=m.end();}return result;}
    public static final class StreamParser {
        private final boolean enabled; private final Set<String> names; private final Listener listener; private final StringBuilder pending=new StringBuilder();private final ArrayList<ToolCall> calls=new ArrayList<>();private boolean finished;
        private StreamParser(boolean e,Set<String> n,Listener l){if(l==null)throw new IllegalArgumentException("listener required");enabled=e;names=n;listener=l;}
        public void feed(String text){if(finished)throw new IllegalStateException("already finished");if(text==null||text.isEmpty())return;if(!enabled){listener.onText(text);return;} // incremental to bound even a single huge feed
            for(int i=0;i<text.length();){int next=Math.min(text.length(),i+Math.max(1,Math.min(4096,MAX_BLOCK+1-pending.length())));int gt=text.indexOf('>',i);if(gt>=0 && gt<next)next=gt+1;pending.append(text,i,next);i=next;scan(false);}}
        public List<ToolCall> finish(){if(!finished){scan(true);finished=true;}return Collections.unmodifiableList(new ArrayList<>(calls));}
        private void emit(String s){if(!s.isEmpty())listener.onText(s);}
        private void scan(boolean end){while(pending.length()>0){String s=pending.toString();Matcher start=START.matcher(s);if(start.find()) {int pos=start.start();if(pos>0){emit(s.substring(0,pos));pending.delete(0,pos);continue;}String opening=start.group();boolean plain=opening.equals("<tool_call>");String close=plain?"</tool_call>":null;Matcher dm=DS_OPEN.matcher(opening);String kind=null;if(!plain&&dm.matches())kind=dm.group(1);Matcher closing=plain?null:Pattern.compile("<[｜|\\s]*/[｜|\\s]*DSML[｜|\\s]*"+kind+"\\s*>").matcher(s);int stop=plain?findToolClose(s,opening.length()):-1;int finish=stop<0?-1:stop+close.length();if(closing!=null&&closing.find(opening.length()))finish=closing.end();
                    if(finish>=0 && finish<=MAX_BLOCK){String block=s.substring(0,finish);List<ToolCall> parsed=plain?parsePlain(block):parseDsml(block);if(parsed.isEmpty())emit(block);else for(ToolCall c:parsed){calls.add(c);listener.onToolCall(c);}pending.delete(0,finish);continue;}
                    if(pending.length()>MAX_BLOCK || end){emit(s);pending.setLength(0);}return;
                }
                // Hold possible marker prefix from the last '<'; cap malformed openings too.
                int lt=s.lastIndexOf('<'); if(lt<0){emit(s);pending.setLength(0);return;}if(lt>0){emit(s.substring(0,lt));pending.delete(0,lt);continue;}
                if(end||s.length()>MAX_BLOCK||!possibleStart(s)){emit(s);pending.setLength(0);}return;
            }}
        // Closing tags inside JSON strings, including escaped quotes and fenced JSON, are data.
        private int findToolClose(String s,int offset){boolean quoted=false,escaped=false;for(int i=offset;i<s.length();i++){char c=s.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else{if(c=='"')quoted=true;else if(c=='<' && s.startsWith("</tool_call>",i))return i;}}return -1;}
        private boolean possibleStart(String s){if("<tool_call>".startsWith(s))return true;return s.matches("<[｜|\\s]*(?:D(?:S(?:M(?:L(?:[｜|\\s]*(?:(?:c(?:a(?:l(?:l(?:s(?:\\b[\\s\\S]*)?)?)?)?)?)|(?:i(?:n(?:v(?:o(?:k(?:e(?:\\b[\\s\\S]*)?)?)?)?)?)))?)?)?)?)?)?");}
        private List<ToolCall> parsePlain(String block){try {String inner=block.substring(11,block.length()-12).trim();if(inner.startsWith("```")){int nl=inner.indexOf('\n');if(nl<0||!inner.endsWith("```"))return Collections.emptyList();inner=inner.substring(nl+1,inner.length()-3).trim();}if(!validObject(inner))return Collections.emptyList();JSONObject v=new JSONObject(inner);Object n=v.opt("name"),a=v.opt("arguments");if(!(n instanceof String)||!names.contains(n))return Collections.emptyList();String args=a instanceof String?(String)a:a instanceof JSONObject?a.toString():null;if(args==null||!validObject(args))return Collections.emptyList();return Collections.singletonList(new ToolCall((String)n,args));}catch(Exception e){return Collections.emptyList();}}
        private List<ToolCall> parseDsml(String block){try{ArrayList<ToolCall> out=new ArrayList<>();Matcher m=INVOKE.matcher(block);while(m.find()){Map<String,String> invokeAttrs=parseAttributes(m.group(1));String n=invokeAttrs==null?null:invokeAttrs.get("name");if(n==null||!names.contains(n))return Collections.emptyList();JSONObject args=new JSONObject();Matcher p=PARAM.matcher(m.group(2));StringBuilder remainder=new StringBuilder();int last=0;while(p.find()){remainder.append(m.group(2),last,p.start());last=p.end();Map<String,String> attrs=parseAttributes(p.group(1));String key=attrs==null?null:attrs.get("name");if(key==null||args.has(key))return Collections.emptyList();String val=p.group(2);String mode=attrs.get("string");Object parsed;if("true".equals(mode))parsed=val;else if("false".equals(mode)){if(!validJson(val))return Collections.emptyList();parsed=new JSONTokener(val).nextValue();}else if(mode==null){parsed=validJson(val)?new JSONTokener(val).nextValue():val;}else return Collections.emptyList();args.put(key,parsed);}remainder.append(m.group(2).substring(last));if(!remainder.toString().trim().isEmpty())return Collections.emptyList();out.add(new ToolCall(n,args.toString()));}if(out.isEmpty())return out; // reject stray or incomplete invokes instead of partially executing
            String rest=INVOKE.matcher(block).replaceAll("").replaceAll("<[｜|\\s]*/?[｜|\\s]*DSML[｜|\\s]*calls\\s*>","").trim();return rest.isEmpty()?out:Collections.<ToolCall>emptyList();}catch(Exception e){return Collections.emptyList();}}
    }
    private static boolean validObject(String s){return validJson(s)&&s.trim().startsWith("{");}
    private static boolean validJson(String s){try{new Strict(s).run();return true;}catch(RuntimeException e){return false;}}
    /** Strict JSON syntax: com.gaeul.allin.json's Android parser accepts non-JSON extensions. */
    private static final class Strict {final String s;int i;Strict(String s){this.s=s;}void ws(){while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0)i++;}void fail(){throw new IllegalArgumentException();}boolean take(char c){ws();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}void run(){value(0);ws();if(i!=s.length())fail();}void value(int depth){if(depth>128)fail();ws();if(i>=s.length())fail();char c=s.charAt(i);if(c=='"'){str();return;}if(c=='{'){i++;if(take('}'))return;do{ws();str();if(!take(':'))fail();value(depth+1);if(take('}'))return;}while(take(','));fail();}else if(c=='['){i++;if(take(']'))return;do{value(depth+1);if(take(']'))return;}while(take(','));fail();}else {int st=i;while(i<s.length()&&",]} \t\r\n".indexOf(s.charAt(i))<0)i++;String t=s.substring(st,i);if(!t.equals("true")&&!t.equals("false")&&!t.equals("null")&&!t.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))fail();}}void str(){if(i>=s.length()||s.charAt(i++)!='"')fail();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return;if(c<32)fail();if(c=='\\'){if(i>=s.length())fail();c=s.charAt(i++);if(c=='u'){for(int j=0;j<4;j++)if(i>=s.length()||Character.digit(s.charAt(i++),16)<0)fail();}else if("\"\\/bfnrt".indexOf(c)<0)fail();}}fail();}}
}
