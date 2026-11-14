package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.*;
import java.io.IOException;

/** Catalog-only aliases of the pinned npm declarations. Never clamps a request. */
final class UpstreamCatalog {
    private UpstreamCatalog() {}
    private static Long integer(Object raw) {
        if (!(raw instanceof Number)) return null;
        double n=((Number)raw).doubleValue();
        return Double.isFinite(n) && n>=0 && n==Math.rint(n) && n<=Integer.MAX_VALUE ? (long)n : null;
    }
    private static Object first(JSONObject row,String... names) {
        for(String name:names) if(row.has(name) && !row.isNull(name)) return row.opt(name);
        return null;
    }
    static JSONObject metadata(String provider,JSONObject raw) {
        JSONObject out=new JSONObject(raw.toString());
        JSONObject limit=raw.optJSONObject("limit");
        Object context=null,output=null;
        if("loomy".equals(provider)) {
            context=first(raw,"contextWindow","context_window","context_length");
            output=first(raw,"maxOutput","max_tokens");
        } else if("workbuddy".equals(provider)||"workbuddy-ai".equals(provider)) {
            context=first(raw,"contextWindow","maxInputTokens");
            output=first(raw,"maxOutput","maxOutputTokens");
        } else if("raccoon".equals(provider)) {
            context=first(raw,"contextWindow","context_window","context_length");
            output=first(raw,"maxOutput","maxTokens","max_tokens");
        } else if("qoder_cn".equals(provider)||"qoder".equals(provider)) {
            context=first(raw,"contextWindow","context_length","context_window");
            output=first(raw,"maxOutput","max_output_tokens","maxOutputTokens");
        }
        if(context==null && limit!=null) context=limit.opt("context");
        if(output==null && limit!=null) output=limit.opt("output");
        Long c=integer(context),o=integer(output);
        if(c!=null)out.put("contextWindow",c);
        if(o!=null)out.put("maxOutput",o);
        return out;
    }
    /** CLI order plus server-owned custom: models; never adds local URL/key models. */
    static List<String> workbuddy(JSONObject data,JSONObject metadata) throws IOException {
        JSONArray agents=data.optJSONArray("agents"),rows=data.optJSONArray("models");
        if(agents==null)throw new IOException("WorkBuddy missing agents");
        List<String> ids=new ArrayList<>(); boolean cli=false;
        for(int i=0;i<agents.length();i++) {
            JSONObject agent=agents.optJSONObject(i);
            if(agent==null||!"cli".equals(agent.optString("name")))continue;
            cli=true;JSONArray models=agent.optJSONArray("models");
            if(models==null||models.length()==0)throw new IOException("WorkBuddy empty CLI list");
            for(int k=0;k<models.length();k++) {
                Object raw=models.opt(k);
                if(!(raw instanceof String)||((String)raw).trim().isEmpty())throw new IOException("WorkBuddy invalid model id");
                String id=((String)raw).trim();if(!ids.contains(id))ids.add(id);
            }
        }
        if(!cli||ids.isEmpty())throw new IOException("WorkBuddy no CLI list");
        Map<String,JSONObject> byId=new LinkedHashMap<>();
        for(int i=0;rows!=null&&i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String id=row.optString("id","").trim();if(id.isEmpty())continue;
            byId.put(id,row);
            if(id.startsWith("custom:")&&!ids.contains(id))ids.add(id);
        }
        JSONObject staged=new JSONObject();Map<String,String> firstName=new HashMap<>();
        for(String id:ids) {
            JSONObject row=byId.get(id);if(row==null)continue;
            JSONObject copy=metadata("workbuddy",row);
            String name=copy.optString("name",id),previous=firstName.get(name);
            if(previous==null)firstName.put(name,id);
            else {String suffix=id.startsWith(previous+"-")?id.substring(previous.length()+1).toUpperCase(Locale.ROOT):id;copy.put("name",name+" ("+suffix+")");}
            staged.put(id,copy);
        }
        for(Iterator<String> it=staged.keys();it.hasNext();) {String key=it.next();metadata.put(key,staged.opt(key));}
        return ids;
    }
    /** RFC3339 or epoch seconds/milliseconds, as Qoder's pinned when(). */
    static String reset(Object raw) {
        Long t=raw instanceof String?RaccoonAccountService.billTime((String)raw):null;
        if(t==null) try {
            if(!(raw instanceof Number)&&!(raw instanceof String))return null;
            String s=raw.toString().trim();if(s.isEmpty())return null;
            double n=Double.parseDouble(s);if(!Double.isFinite(n)||n<=0)return null;
            n=n<1e12?n*1000:n;if(n>8640000000000000L)return null;t=(long)n;
        } catch(Exception ignored){return null;}
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(t));
    }
}
