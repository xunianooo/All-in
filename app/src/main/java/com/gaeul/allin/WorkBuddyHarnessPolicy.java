package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-send text policy, based on the pinned npm 0.2.25 rules.
 * Deliberately stricter than npm: opaque blocks, schema values and path/code spans survive.
 * No credentials, prompt text, learned prompts or retry state are retained here.
 */
public final class WorkBuddyHarnessPolicy {
    private WorkBuddyHarnessPolicy() {}
    private static final String BEFORE="(?<![A-Za-z0-9_/\\\\.~:@-])";
    private static final String AFTER="(?![A-Za-z0-9_/\\\\@-]|\\.[A-Za-z0-9_])";
    private static final Pattern PROTECTED=Pattern.compile(
        "```[\\s\\S]*?(?:```|\\z)|`[^`\\r\\n]*(?:`|(?=\\r?\\n|\\z))|(?:[A-Za-z][A-Za-z0-9+.-]*://|[A-Za-z]:[\\\\/]|[/~]|\\.{1,2}/)[^\\s`<>\"']+"
        +"|(?<![A-Za-z0-9_])[^\\s`<>\"']*(?:[/\\\\]|_[A-Za-z0-9_]|\\.[A-Za-z0-9_])[^\\s`<>\"']*");
    public static final class Report {
        public int messages, descriptions;
        public String metadata() { return "enabled; messages="+messages+"; descriptions="+descriptions; }
    }
    private static String replace(String s,String re,String to) {return s.replaceAll(re,to);}
    private static String prose(String s,boolean said) {
        // DSH-specific first-send rules; unrelated FLAGGED phrases are not expanded.
        s=replace(s,BEFORE+"You are an AI agent,? powered by DeepSeek Harness\\.?"+AFTER,"You are an AI agent.");
        s=replace(s,BEFORE+"DeepSeek Harness (Web GUI)"+AFTER,"$1");
        s=replace(s,BEFORE+"DeepSeek Harness (implementation checkout)"+AFTER,"agent $1");
        s=replace(s,BEFORE+"DSH itself"+AFTER,"the agent itself");
        s=replace(s,"(?i)Web GUI whose URL the runtime context names\\.\\s+When the user refers",
            "Web GUI whose URL the runtime context names. The interface, when the user refers");
        if(!said) {
            Pattern name=Pattern.compile(BEFORE+"([Tt]he )?DeepSeek Harness"+AFTER);
            Matcher m=name.matcher(s);StringBuffer out=new StringBuffer();
            while(m.find())m.appendReplacement(out,Matcher.quoteReplacement((m.group(1)==null?"the ":m.group(1))+"agent harness"));
            m.appendTail(out);s=out.toString();
            s=replace(s,BEFORE+"DSH"+AFTER,"the agent harness");
        }
        return s;
    }
    static String text(String source,boolean said) {
        Matcher m=PROTECTED.matcher(source);StringBuilder out=new StringBuilder();int last=0;
        while(m.find()) {out.append(prose(source.substring(last,m.start()),said));out.append(m.group());last=m.end();}
        return out.append(prose(source.substring(last),said)).toString();
    }
    private static Object content(Object raw,boolean said,Report report) {
        if(raw instanceof String) {
            String next=text((String)raw,said);if(!next.equals(raw))report.messages++;return next;
        }
        if(raw instanceof JSONArray) {
            JSONArray parts=(JSONArray)raw;
            for(int i=0;i<parts.length();i++) {
                JSONObject p=parts.optJSONObject(i);
                if(p==null || (p.has("type")&&!"text".equals(p.opt("type"))) || !(p.opt("text") instanceof String))continue;
                boolean known=true;
                for(Iterator<String> keys=p.keys();keys.hasNext();) {
                    String key=keys.next();
                    if(!"type".equals(key)&&!"text".equals(key)&&!"cache_control".equals(key)){known=false;break;}
                }
                if(known)p.put("text",content(p.opt("text"),said,report));
            }
        }
        return raw;
    }
    // Traverse schema structure, not examples/default/const/enum or arbitrary extension values.
    private static void schema(JSONObject o,Report report,int depth) {
        if(depth>64)return;
        description(o,report);
        for(String key:new String[]{"properties","patternProperties","$defs","definitions","dependentSchemas"}) {
            JSONObject map=o.optJSONObject(key);if(map!=null)for(Iterator<String> it=map.keys();it.hasNext();) {
                JSONObject child=map.optJSONObject(it.next());if(child!=null)schema(child,report,depth+1);
            }
        }
        for(String key:new String[]{"items","additionalProperties","additionalItems","contains","not","if","then","else","propertyNames","unevaluatedProperties","unevaluatedItems"}) {
            Object child=o.opt(key);if(child instanceof JSONObject)schema((JSONObject)child,report,depth+1);
            else if(child instanceof JSONArray)schemas((JSONArray)child,report,depth+1);
        }
        for(String key:new String[]{"allOf","anyOf","oneOf","prefixItems"}) {
            JSONArray a=o.optJSONArray(key);if(a!=null)schemas(a,report,depth+1);
        }
    }
    private static void schemas(JSONArray a,Report r,int depth) {for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)schema(o,r,depth);}}
    private static void description(JSONObject o,Report report) {
        if(!(o.opt("description") instanceof String))return;
        String previous=o.getString("description"),next=text(previous,false);
        if(!next.equals(previous)){o.put("description",next);report.descriptions++;}
    }
    /** Mutates a caller-owned deep copy only. */
    public static Report apply(JSONObject chat) {
        Report report=new Report();JSONArray messages=chat.optJSONArray("messages");
        for(int i=0;messages!=null&&i<messages.length();i++) {
            JSONObject m=messages.optJSONObject(i);if(m==null)continue;
            String role=m.optString("role");
            if(!"system".equals(role)&&!"developer".equals(role)&&!"user".equals(role)&&!"assistant".equals(role))continue;
            if(m.has("signature"))continue;
            if(m.has("content"))m.put("content",content(m.opt("content"),!"system".equals(role)&&!"developer".equals(role),report));
        }
        if(chat.has("system"))chat.put("system",content(chat.opt("system"),false,report));
        for(String key:new String[]{"tools","functions"}) {
            JSONArray tools=chat.optJSONArray(key);
            for(int i=0;tools!=null&&i<tools.length();i++) {
                JSONObject t=tools.optJSONObject(i);if(t==null)continue;
                String type=t.optString("type","");if(!type.isEmpty()&&!"function".equals(type))continue;
                JSONObject fn=t.optJSONObject("function");if(fn==null)fn=t;
                description(fn,report);
                for(String schemaKey:new String[]{"parameters","input_schema"}) {JSONObject s=fn.optJSONObject(schemaKey);if(s!=null)schema(s,report,0);}
            }
        }
        return report;
    }
}
