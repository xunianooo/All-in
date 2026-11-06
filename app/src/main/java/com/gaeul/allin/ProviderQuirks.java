package com.gaeul.allin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Offline, immutable, request-scoped compatibility decisions. No credentials or I/O. */
final class ProviderQuirks {
    static final int ENGINE_VERSION = 1, MAX_BYTES = 32768, MAX_RULES = 32;
    private static final Set<String> HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "User-Agent", "X-Requested-With", "X-Product", "X-Agent-Intent", "X-Agent-Purpose",
            "X-Conversation-ID", "X-Agent-Type", "X-Auth-Refresh-Source", "Accept")));
    private static final class Rule {
        final String id, provider, protocol, origin, path;
        final Map<String,String> headers;
        Rule(String id, String p, String proto, String o, String path, Map<String,String> h) {
            this.id=id; provider=p; protocol=proto; origin=o; this.path=path;
            headers=Collections.unmodifiableMap(new LinkedHashMap<>(h));
        }
    }
    static final class Snapshot {
        final int schemaVersion, engineVersion;
        final String ruleVersion, source;
        private final List<Rule> rules;
        private Snapshot(String version, List<Rule> rs) { this(version, rs, "builtin"); }
        private Snapshot(String version, List<Rule> rs, String origin) {
            schemaVersion=1; engineVersion=ENGINE_VERSION; ruleVersion=version; source=origin;
            rules=Collections.unmodifiableList(new ArrayList<>(rs));
        }
        Snapshot withSource(String source) {
            if (!"builtin".equals(source) && !"verified-cache".equals(source) && !"verified-update".equals(source)) throw new IllegalArgumentException("source");
            return new Snapshot(ruleVersion, rules, source);
        }
        Map<String,String> headers(String provider, String protocol, String url) {
            URI u;
            try { u = new URI(url); } catch (Exception e) { return Collections.emptyMap(); }
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null
                    || u.getRawQuery()!=null || u.getRawFragment()!=null || (u.getPort()!=-1 && u.getPort()!=443)) return Collections.emptyMap();
            String origin="https://"+u.getHost().toLowerCase(Locale.ROOT);
            for (Rule r: rules) if (r.provider.equals(provider) && r.protocol.equals(protocol)
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
        JSONTokener t=new JSONTokener(text); Object parsed=t.nextValue();
        if (!(parsed instanceof JSONObject) || t.nextClean()!=0) throw new IllegalArgumentException("root");
        JSONObject root=(JSONObject)parsed; keys(root,"schemaVersion","minimumEngineVersion","ruleVersion","rules");
        if (!Integer.valueOf(1).equals(root.get("schemaVersion")) || !Integer.valueOf(1).equals(root.get("minimumEngineVersion"))) throw new IllegalArgumentException("version");
        String version=str(root,"ruleVersion",64); if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.]+)?")) throw new IllegalArgumentException("version");
        Object raw=root.get("rules"); if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("rules");
        JSONArray a=(JSONArray)raw; if(a.length()>MAX_RULES) throw new IllegalArgumentException("rules");
        List<Rule> rules=new ArrayList<>(); Set<String> ids=new HashSet<>(), tuples=new HashSet<>();
        for(int i=0;i<a.length();i++) {
            Object item=a.get(i); if (!(item instanceof JSONObject)) throw new IllegalArgumentException("rule");
            JSONObject r=(JSONObject)item; keys(r,"id","provider","protocol","origin","path","headers");
            String id=str(r,"id",64), p=str(r,"provider",64), proto=str(r,"protocol",32), origin=str(r,"origin",128), path=str(r,"path",256);
            if(!id.matches("[a-z0-9._-]+") || !ids.add(id)) throw new IllegalArgumentException("id");
            // Explicit trusted tuples: a rules file cannot expand destinations or provider identities.
            boolean wb=p.equals("workbuddy") && origin.equals("https://copilot.tencent.com") && path.equals("/v2/chat/completions");
            boolean ai=p.equals("workbuddy-ai") && origin.equals("https://www.workbuddy.ai") && path.equals("/v2/chat/completions");
            boolean rac=p.equals("raccoon") && origin.equals("https://xiaohuanxiong.com") && path.equals("/api/web/llm/v2/chat/completions");
            if (!proto.equals("openai") || !(wb||ai||rac) || !tuples.add(p+"\n"+proto+"\n"+origin+"\n"+path)) throw new IllegalArgumentException("tuple");
            Object hdr=r.get("headers"); if (!(hdr instanceof JSONObject)) throw new IllegalArgumentException("headers");
            JSONObject h=(JSONObject)hdr; if(h.length()>HEADERS.size()) throw new IllegalArgumentException("headers");
            Map<String,String> map=new LinkedHashMap<>(); Iterator<String> it=h.keys();
            while(it.hasNext()) { String k=it.next(); String v=str(h,k,256);
                if(!HEADERS.contains(k) || !v.matches("[\\x20-\\x7e]+") || v.contains("${") || v.contains("{{")) throw new IllegalArgumentException("header");
                map.put(k,v);
            }
            // Only these existing header names can be supplied at trusted tuples.
            // Static values belong to the authenticated APK asset, not duplicated
            // Java fingerprints. Credentials and interpolation are unrepresentable.
            Set<String> expected = rac ? new HashSet<>(Arrays.asList("User-Agent", "Accept"))
                    : new HashSet<>(Arrays.asList("User-Agent", "X-Requested-With", "X-Product", "X-Agent-Intent",
                            "X-Agent-Purpose", "X-Conversation-ID", "X-Agent-Type", "X-Auth-Refresh-Source"));
            if(!map.keySet().equals(expected)) throw new IllegalArgumentException("unapproved header set");
            rules.add(new Rule(id,p,proto,origin,path,map));
        }
        return new Snapshot(version,rules);
    }
    private static void keys(JSONObject o,String... expected) {
        Set<String> set=new HashSet<>(Arrays.asList(expected)); if(o.length()!=set.size()) throw new IllegalArgumentException("fields");
        Iterator<String> it=o.keys(); while(it.hasNext()) if(!set.contains(it.next())) throw new IllegalArgumentException("field");
    }
    private static String str(JSONObject o,String k,int max) throws JSONException {
        Object v=o.get(k); if(!(v instanceof String) || ((String)v).isEmpty() || ((String)v).length()>max) throw new IllegalArgumentException("string"); return (String)v;
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
