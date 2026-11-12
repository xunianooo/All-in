package com.gaeul.allin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Read-only account-specific live model catalog. Unknown capabilities are omitted. */
public final class TraeModels {
    private static final String[] FUNCTIONS={"chat_v3","solo_work_lite","solo_agent","solo_agent_lite","solo_coder"};
    private static final int LIMIT=512;
    private static final String MAX_SUFFIX="-max";
    private TraeModels() {}
    private static void check(JSONObject r) throws Exception {
        JSONObject e=r.optJSONObject("error"); if(e==null)e=r;
        String code=e.optString("code",e.optString("Code",r.optString("code","")));
        JSONObject meta=r.optJSONObject("ResponseMetadata"); JSONObject me=meta==null?null:meta.optJSONObject("Error");
        if(me!=null) code=me.optString("Code",code);
        if("1001".equals(code)||"401".equals(code)) throw new TraeAuth.Failure("http_401");
        if(!code.isEmpty()&&!"0".equals(code)) throw new TraeAuth.Failure("models_rejected");
    }
    private static boolean unauthorized(Exception e) { return e instanceof TraeAuth.Failure && "http_401".equals(((TraeAuth.Failure)e).code); }
    private static JSONArray groups(JSONObject r) { JSONArray a=r.optJSONArray("function_configs"); JSONObject data=r.optJSONObject("data"); return a!=null?a:data==null?null:data.optJSONArray("function_configs"); }
    public static JSONObject sync(JSONObject account, NetworkPolicy.Snapshot network, RequestContext request) throws Exception {
        JSONObject lists=null; Exception last=null;
        try {
            JSONObject body=new JSONObject().put("functions",new JSONArray(FUNCTIONS)).put("agent_type","").put("current_config_info",new JSONObject().put("config_name","").put("is_custom_model",false)).put("mode_type",0).put("access_type",0).put("ab_force_vids","").put("ab_autotest_advanced_mode",0).put("show_custom_model",true);
            JSONObject response=TraeAuth.modelRequest(account,"/api/ide/v1/batch_get_detail_param",body,network,request); check(response);
            if(groups(response)!=null) { JSONArray gs=groups(response); for(int i=0;i<Math.min(gs.length(),LIMIT);i++){JSONObject g=gs.optJSONObject(i);if(g!=null&&allowed(g.optString("function"))&&g.optJSONArray("config_info_list")!=null){lists=response;break;}} }
        } catch(Exception e) { if(unauthorized(e))throw e; if(request!=null)request.check(); last=e; }
        if(lists==null) {
            JSONArray all=new JSONArray(); int success=0;
            for(String fn:FUNCTIONS) try {
                JSONObject b=new JSONObject().put("function",fn).put("config_names",JSONObject.NULL).put("need_prompt",false).put("current_config_info",JSONObject.NULL).put("poly_prompt",true).put("mode_type",JSONObject.NULL).put("agent_type",JSONObject.NULL);
                JSONObject r=TraeAuth.modelRequest(account,"/api/ide/v1/get_detail_param",b,network,request); check(r);
                JSONArray a=r.optJSONArray("config_info_list"); JSONObject data=r.optJSONObject("data"); if(a==null&&data!=null)a=data.optJSONArray("config_info_list");
                if(a!=null) { success++; all.put(new JSONObject().put("function",fn).put("config_info_list",a)); }
            } catch(Exception e) { if(unauthorized(e))throw e; if(request!=null)request.check(); last=e; }
            if(success==0) { if(last!=null)throw last; throw new TraeAuth.Failure("invalid_response"); }
            lists=new JSONObject().put("function_configs",all);
        }
        return parse(lists, System.currentTimeMillis());
    }
    private static boolean allowed(String f) { for(String s:FUNCTIONS)if(s.equals(f))return true; return false; }
    private static boolean valid(JSONObject m) { String id=m.optString("config_name"); JSONObject d=m.optJSONObject("display_config"); return !id.isEmpty()&&!id.toLowerCase(java.util.Locale.ROOT).startsWith("custom_model") && (m.optString("usage").isEmpty()||"chat_completion".equals(m.optString("usage"))) && !Boolean.FALSE.equals(m.opt("config_switch")) && !Boolean.TRUE.equals(m.opt("is_custom_model")) && !(d!=null&&Boolean.TRUE.equals(d.opt("is_custom_model"))) && !Boolean.TRUE.equals(m.opt("is_invisible_to_user")); }
    private static JSONObject detail(JSONObject m,String suffix) { JSONArray a=m.optJSONArray("model_detail_list"); if(a!=null)for(int i=0;i<Math.min(a.length(),LIMIT);i++){JSONObject d=a.optJSONObject(i);if(d!=null&&d.optString("model_name").endsWith(suffix))return d;}return null; }
    private static JSONObject metadata(JSONObject m,String fn,JSONObject d,long catalogObservedAt) throws Exception {
        JSONObject out=new JSONObject().put("function",fn).put("maxSupported",false); if(d!=null){out.put("modelName",d.optString("model_name")); int n=positiveInt(d.opt("max_tokens"));if(n>0)out.put("maxOutput",n);}
        JSONObject display=m.optJSONObject("display_config"); Object multi=m.opt("multimodal"); if(!(multi instanceof Boolean)&&display!=null)multi=display.opt("multimodal"); if(multi instanceof Boolean)out.put("multimodal",multi);
        JSONObject levels=m.optJSONObject("reasoning_effort_config"); if(levels!=null){JSONArray options=levels.optJSONArray("options");if(options!=null){JSONArray wire=new JSONArray();for(int i=0;i<Math.min(options.length(),LIMIT);i++){Object value=options.opt(i);if(value instanceof String&&!((String)value).trim().isEmpty())wire.put(value);}out.put("levels",wire);}}
        JSONObject ctx=m.optJSONObject("context_window_tokens"); int window=ctx==null?0:positiveInt(ctx.opt("dev"));
        if(window==0){JSONObject sizes=m.optJSONObject("context_window_size"); Object v=sizes==null?null:sizes.opt("max");if(v instanceof JSONArray)v=((JSONArray)v).opt(0);window=positiveInt(v);}
        if(window==0&&ctx!=null)window=positiveInt(ctx.opt("max")); if(window==0)window=positiveInt(m.opt("prompt_max_tokens")); if(window>0)out.put("contextWindow",window);
        String name=display==null?"":display.optString("display_name");if(name.isEmpty())name=m.optString("display_name",m.optString("display_model_name")); if(!name.isEmpty())out.put("displayName",name);
        if(m.has("display_contact_config")) out.put("display_contact_config",m.opt("display_contact_config"));
        if(catalogObservedAt>0)out.put("catalogObservedAt",catalogObservedAt);
        return ModelBillingMetadata.normalize("trae",out);
    }
    private static int positiveInt(Object value) {
        if (!(value instanceof Number)) return 0;
        double n=((Number)value).doubleValue();
        return Double.isFinite(n)&&n>=1&&n<=Integer.MAX_VALUE&&n==Math.rint(n)?(int)n:0;
    }
    private static String nameOf(JSONObject m) {
        JSONObject d=m.optJSONObject("display_config"); String s=d==null?"":d.optString("display_name","");
        if(s.isEmpty())s=m.optString("display_name",""); if(s.isEmpty())s=m.optString("display_model_name","");
        return s.isEmpty()?m.optString("config_name",""):s;
    }
    private static boolean hasLevels(JSONObject m) {
        JSONObject c=m.optJSONObject("reasoning_effort_config"); JSONArray a=c==null?null:c.optJSONArray("options");
        if(a!=null)for(int i=0;i<Math.min(a.length(),LIMIT);i++) { Object v=a.opt(i); if(v instanceof String && (v.equals("light")||v.equals("medium")||v.equals("high")||v.equals("extra_high")||v.equals("max")))return true; }
        return false;
    }
    private static JSONObject maxDetail(JSONObject model) { JSONArray a=model.optJSONArray("model_detail_list"); if(a!=null)for(int i=0;i<Math.min(a.length(),LIMIT);i++){JSONObject d=a.optJSONObject(i);if(d!=null&&d.optString("model_name").endsWith("__max"))return d;}return null; }
    private static int window(JSONObject model,String key) { JSONObject w=model.optJSONObject("context_window_tokens"); return w==null?0:positiveInt(w.opt(key)); }
    private static final class Entry { final String fn; final JSONObject model; Entry(String fn,JSONObject model){this.fn=fn;this.model=model;} }
    public static JSONObject parse(JSONObject batch) throws Exception { return parse(batch,0); }
    private static JSONObject parse(JSONObject batch,long catalogObservedAt) throws Exception {
        JSONArray groups=groups(batch); if(groups==null)throw new TraeAuth.Failure("invalid_response");
        List<Entry> entries=new ArrayList<>(); int scanned=0; boolean recognized=false;
        for(String fn:FUNCTIONS) for(int g=0;g<Math.min(groups.length(),LIMIT)&&scanned<LIMIT;g++) {
            JSONObject group=groups.optJSONObject(g); if(group==null||!fn.equals(group.optString("function")))continue;
            JSONArray a=group.optJSONArray("config_info_list"); if(a==null)throw new TraeAuth.Failure("invalid_response"); recognized=true;
            for(int i=0;i<a.length()&&scanned<LIMIT;i++){scanned++;JSONObject m=a.optJSONObject(i);if(m!=null&&valid(m))entries.add(new Entry(fn,m));}
        }
        if(!recognized)throw new TraeAuth.Failure("invalid_response");
        Map<String,LinkedHashMap<String,Integer>> names=new LinkedHashMap<>();
        for(Entry e:entries){String id=e.model.optString("config_name"),name=nameOf(e.model); LinkedHashMap<String,Integer> by=names.get(id);if(by==null){by=new LinkedHashMap<>();names.put(id,by);} Integer n=by.get(name);by.put(name,n==null?1:n+1);}
        Map<String,String> agreed=new LinkedHashMap<>();
        for(Map.Entry<String,LinkedHashMap<String,Integer>> e:names.entrySet()){int top=0;String best="";boolean tie=false;for(Map.Entry<String,Integer> n:e.getValue().entrySet()){if(n.getValue()>top){top=n.getValue();best=n.getKey();tie=false;}else if(n.getValue()==top)tie=true;}agreed.put(e.getKey(),tie?"":best);}
        LinkedHashMap<String,Entry> selected=new LinkedHashMap<>(); Map<String,Integer> scores=new LinkedHashMap<>();
        for(Entry e:entries){String id=e.model.optString("config_name"),name=nameOf(e.model);boolean bad=false;
            if(names.get(id).size()>1)for(Entry other:entries)if(other.fn.equals(e.fn)&&!id.equals(other.model.optString("config_name"))&&name.equals(nameOf(other.model))){bad=true;break;}
            int score=((detail(e.model,"__dev")!=null?2:0)+(bad?0:1)+(name.equals(agreed.get(id))?1:0))*2+(hasLevels(e.model)?1:0);
            if(!selected.containsKey(id)||score>scores.get(id)){selected.put(id,e);scores.put(id,score);}
        }
        JSONArray models=new JSONArray(); JSONObject meta=new JSONObject();
        for(Map.Entry<String,Entry> e:selected.entrySet()){Entry v=e.getValue();models.put(e.getKey());meta.put(e.getKey(),metadata(v.model,v.fn,detail(v.model,"__dev"),catalogObservedAt));}
        for(Map.Entry<String,Entry> base:selected.entrySet()) {
            String id=base.getKey(), alias=id+MAX_SUFFIX; if(selected.containsKey(alias))continue;
            ArrayList<Entry> siblings=new ArrayList<>(); siblings.add(base.getValue());
            for(String fn:FUNCTIONS) for(int g=0;g<Math.min(groups.length(),LIMIT);g++) { JSONObject group=groups.optJSONObject(g); if(group==null||!fn.equals(group.optString("function")))continue; JSONArray list=group.optJSONArray("config_info_list"); if(list==null)continue; for(int i=0;i<Math.min(list.length(),LIMIT);i++){JSONObject candidate=list.optJSONObject(i);if(candidate!=null&&id.equals(candidate.optString("config_name"))&&valid(candidate))siblings.add(new Entry(fn,candidate));} }
            Entry maxEntry=null; JSONObject maxDetail=null; for(Entry sibling:siblings){JSONObject d=maxDetail(sibling.model);if(d!=null){maxEntry=sibling;maxDetail=d;break;}}
            if(maxEntry==null)continue;
            ArrayList<Entry> windowOrder=new ArrayList<>(); windowOrder.add(maxEntry); for(Entry sibling:siblings)if(sibling!=maxEntry)windowOrder.add(sibling);
            int maxWindow=0,devWindow=0; for(Entry sibling:windowOrder){if(maxWindow==0)maxWindow=window(sibling.model,"max");if(devWindow==0)devWindow=window(sibling.model,"dev");}
            if(maxWindow<=devWindow)continue;
            JSONObject mm=metadata(base.getValue().model,base.getValue().fn,maxDetail,catalogObservedAt);
            mm.put("function",maxEntry.fn).put("base",id).put("maxMode",true).put("contextWindow",maxWindow).put("maxSupported",true);
            models.put(alias);meta.put(alias,mm);
        }
        return new JSONObject().put("models",models).put("modelMeta",meta);
    }
}
