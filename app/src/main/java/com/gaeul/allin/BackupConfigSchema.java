package com.gaeul.allin;

import java.util.*;
import org.json.*;

/** Validates consumed configuration fields while preserving unrelated legacy fields. */
final class BackupConfigSchema {
    private static final Set<String> PROTOCOLS = new HashSet<>(Arrays.asList("openai","anthropic","responses","gemini","antigravity","qoder","codex","copilot","minimax","deepseek-web","cline","catpaw"));
    static void validate(JSONObject root) throws Exception {
        JSONObject server = object(root, "server", true);
        optionalString(server, "authKey"); optionalString(server, "host");
        if (server.has("port")) integer(server.opt("port"), 1, 65535);
        JSONArray providers = array(root,"providers",true);
        Set<String> ids = new HashSet<>();
        for (int i=0;i<providers.length();i++) {
            Object raw=providers.opt(i); if (!(raw instanceof JSONObject)) bad(); JSONObject p=(JSONObject)raw;
            Object id=p.opt("id"); if (!(id instanceof String) || ((String)id).trim().isEmpty() || !ids.add((String)id)) bad();
            if (p.has("protocol")) { Object proto=p.opt("protocol"); if (!(proto instanceof String) || !PROTOCOLS.contains(proto)) bad(); }
            JSONArray models=array(p,"models",false), keys=array(p,"keys",false), accounts=array(p,"accounts",false);
            if (models!=null) for(int j=0;j<models.length();j++) if (!(models.opt(j) instanceof String)) bad();
            if (accounts!=null) for(int j=0;j<accounts.length();j++) if (!(accounts.opt(j) instanceof JSONObject)) bad();
            if (keys!=null) for(int j=0;j<keys.length();j++) {
                Object k=keys.opt(j);
                if (k instanceof String) { if (((String)k).length()>16384) bad(); }
                else if (k instanceof JSONObject) { JSONObject item=(JSONObject)k; Object value=item.opt("key");
                    if (!(value instanceof String) || ((String)value).length()>16384) bad();
                    optionalBoolean(item,"off"); optionalBoolean(item,"enabled");
                } else bad();
            }
        }
        JSONObject network=object(root,"network",false); if(network!=null) NetworkPolicy.parse(network);
        JSONObject resilience=object(root,"resilience",false); if(resilience!=null) PatiencePolicy.parse(resilience);
        JSONObject affinity=object(root,"affinity",false); if(affinity!=null) { only(affinity,"enabled"); optionalBoolean(affinity,"enabled"); }
        JSONObject outbound=object(root,"outbound",false);
        if (outbound!=null) {
            if(outbound.length()>256) bad(); Iterator<String> it=outbound.keys();
            while(it.hasNext()) { String id=it.next(); if(id.isEmpty() || id.length()>128) bad();
                JSONObject domains=object(outbound,id,true); only(domains,"credential","shared");
                for(String name:new String[]{"credential","shared"}) if(domains.has(name)) OutboundLimiter.Policy.fromJson(object(domains,name,true));
            }
        }
    }
    private static void only(JSONObject o,String... fields) { Set<String> allowed=new HashSet<>(Arrays.asList(fields)); Iterator<String> i=o.keys(); while(i.hasNext()) if(!allowed.contains(i.next())) bad(); }
    private static JSONObject object(JSONObject o,String key,boolean required) { Object v=o.opt(key); if(!o.has(key) && !required) return null; if(!(v instanceof JSONObject)) bad(); return (JSONObject)v; }
    private static JSONArray array(JSONObject o,String key,boolean required) { Object v=o.opt(key); if(!o.has(key) && !required) return null; if(!(v instanceof JSONArray)) bad(); return (JSONArray)v; }
    private static void optionalString(JSONObject o,String key) { if(o.has(key) && !(o.opt(key) instanceof String)) bad(); }
    private static void optionalBoolean(JSONObject o,String key) { if(o.has(key) && !(o.opt(key) instanceof Boolean)) bad(); }
    private static void integer(Object v,int min,int max) { if(!(v instanceof Number)) bad(); double d=((Number)v).doubleValue(); if(d<min || d>max || d!=Math.floor(d)) bad(); }
    private static void bad() { throw new IllegalArgumentException("invalid backup configuration"); }
}
