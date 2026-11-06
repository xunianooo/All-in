package com.gaeul.allin;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Lifecycle and account control for the consumer Trae provider. */
public final class TraeControl implements AutoCloseable {
    public interface Host {
        default Object lock() { return this; }
        default long epoch() { return 0; }
        JSONObject config();
        boolean save(JSONObject config);
        boolean retired();
    }
    private final Host host;
    private final TraeAccounts accounts = new TraeAccounts();
    private volatile boolean closed;
    private final Map<String, Login> flows = new ConcurrentHashMap<>();
    private static final long FLOW_RETENTION = 300000L;
    private static final class Login {
        final TraeAuth.Flow flow;
        JSONObject response;
        Login(TraeAuth.Flow flow) { this.flow=flow; }
    }
    public TraeControl(Host host) {
        if (host==null) throw new IllegalArgumentException("host required");
        this.host=host;
    }
    private static JSONObject copy(JSONObject obj) throws Exception { return new JSONObject(obj.toString()); }
    private void live() throws IOException { if (closed || host.retired()) throw new IOException("Trae control retired"); }
    private static JSONObject provider(JSONObject config) throws IOException {
        JSONArray list=config==null?null:config.optJSONArray("providers"); JSONObject found=null;
        for(int i=0;list!=null && i<list.length();i++) {
            JSONObject p=list.optJSONObject(i);
            if(p!=null && "trae-cn".equals(p.optString("id"))) {
                if(found!=null) throw new IOException("duplicate Trae provider");
                found=p;
            }
        }
        if(found==null) throw new IOException("Trae provider missing");
        return found;
    }
    private static int accountIndex(JSONObject p,String id) throws IOException {
        JSONArray aa=p.optJSONArray("accounts"); int found=-1;
        for(int i=0;aa!=null && i<aa.length();i++) {
            JSONObject a=aa.optJSONObject(i);
            if(a!=null && id.equals(a.optString("id"))) { if(found>=0) throw new IOException("duplicate account id"); found=i; }
        }
        return found;
    }
    private static void rebuild(JSONObject p) throws Exception {
        JSONArray aa=p.optJSONArray("accounts"), keys=new JSONArray(), models=new JSONArray();
        JSONObject meta=new JSONObject(); Set<String> seenKeys=new LinkedHashSet<>(), seenModels=new LinkedHashSet<>();
        for(int i=0;aa!=null && i<aa.length();i++) {
            JSONObject a=aa.optJSONObject(i); if(a==null || !a.optBoolean("enabled",true)) continue;
            String token=a.optString("token",""); if(!token.isEmpty() && seenKeys.add(token)) keys.put(token);
            JSONArray mm=a.optJSONArray("models"); JSONObject am=a.optJSONObject("modelMeta");
            for(int j=0;mm!=null && j<mm.length();j++) {
                String m=mm.optString(j,""); if(m.isEmpty() || !seenModels.add(m)) continue;
                models.put(m); if(am!=null && am.optJSONObject(m)!=null) meta.put(m,copy(am.optJSONObject(m)));
            }
        }
        p.put("keys",keys); p.put("models",models); p.put("modelMeta",meta);
        p.put("status","ported"); p.put("protocol","trae"); p.put("baseUrl",TraeAuth.MODEL_HOST);
    }
    private final TraeAccounts.Store store=new TraeAccounts.Store() {
        public JSONObject current(String id) throws Exception {
            synchronized(host.lock()) {
                live(); JSONObject p=provider(host.config()); if(!p.optBoolean("enabled",true)) return null;
                int i=accountIndex(p,id); JSONObject a=i<0?null:p.optJSONArray("accounts").optJSONObject(i);
                return a==null || !a.optBoolean("enabled",true)?null:copy(a).put("_traeEpoch",host.epoch());
            }
        }
        public void replace(String id,JSONObject expected,JSONObject next) throws Exception {
            synchronized(host.lock()) {
                live(); JSONObject p=provider(host.config()); if(!p.optBoolean("enabled",true)) throw new IOException("provider disabled");
                int i=accountIndex(p,id); JSONObject a=i<0?null:p.optJSONArray("accounts").optJSONObject(i);
                JSONObject compare=copy(expected);long epoch=compare.optLong("_traeEpoch",0);compare.remove("_traeEpoch");
                if(a==null || !a.optBoolean("enabled",true) || host.epoch()!=epoch || !a.toString().equals(compare.toString()) || !id.equals(next.optString("id"))) throw new IOException("account changed");
                JSONObject cloned=copy(host.config()); JSONObject target=provider(cloned);
                JSONObject clean=copy(next);clean.remove("_traeEpoch");target.getJSONArray("accounts").put(i,clean); rebuild(target); live();
                if(!host.save(cloned)) throw new IOException("account save failed");
                next.put("_traeEpoch",host.epoch());
            }
        }
    };
    public JSONObject remove(String id) throws Exception {
        if(id==null||id.isEmpty())throw new IllegalArgumentException("accountId required");
        synchronized(host.lock()) {live();JSONObject next=copy(host.config()),p=provider(next);JSONArray old=p.optJSONArray("accounts"),kept=new JSONArray();boolean found=false;
            for(int i=0;old!=null&&i<old.length();i++){JSONObject a=old.getJSONObject(i);if(id.equals(a.optString("id")))found=true;else kept.put(a);}
            if(!found)throw new IllegalArgumentException("account missing");p.put("accounts",kept);rebuild(p);live();if(!host.save(next))throw new IOException("account save failed");
            return new JSONObject().put("ok",true);
        }
    }
    public JSONObject lease(String id,RequestContext request) throws Exception {
        live(); if(request==null) throw new IllegalArgumentException("request required");
        return accounts.lease(id,store,request.network==null?NetworkPolicy.capture():request.network,request);
    }
    @Override public void close() {
        closed=true;
        accounts.retire();
        // Flow.close can wait for a flow lock. Never wait while the caller holds the host lock.
        Thread cleanup=new Thread(() -> { for(Login entry:flows.values()) entry.flow.close(); flows.clear(); }, "trae-close");
        cleanup.setDaemon(true); cleanup.start();
    }
    private synchronized void prune() {
        long now=System.currentTimeMillis(); Iterator<Login> it=flows.values().iterator();
        while(it.hasNext()) { Login e=it.next(); if(now>e.flow.deadline+FLOW_RETENTION) { e.flow.close(); it.remove(); } }
    }
    private synchronized JSONObject init(boolean fresh) throws Exception {
        live(); prune();
        if(fresh) {for(Login e:flows.values())e.flow.close();flows.clear();}
        for(Login e:flows.values()) if("pending".equals(e.flow.status) && System.currentTimeMillis()<e.flow.deadline)
            return new JSONObject().put("ok",true).put("url",e.flow.url).put("flowId",e.flow.flowId).put("status","pending").put("deadline",e.flow.deadline).put("resumed",true);
        if(flows.size()>=4) throw new IOException("too many login flows");
        TraeAuth.Flow f=TraeAuth.startLogin(NetworkPolicy.capture()); flows.put(f.flowId,new Login(f));
        if(closed || host.retired()) { flows.remove(f.flowId); f.close(); throw new IOException("Trae control retired"); }
        return new JSONObject().put("ok",true).put("url",f.url).put("flowId",f.flowId).put("status","pending").put("deadline",f.deadline).put("resumed",false);
    }
    private synchronized Login findFlow(String id) throws Exception {
        live(); prune(); Login e=flows.get(id); return e;
    }
    private JSONObject addAccount(JSONObject result) throws Exception {
        synchronized(host.lock()) {
            live(); JSONObject original=provider(host.config()); if(!original.optBoolean("enabled",true)) throw new IOException("provider disabled");
            JSONObject config=copy(host.config()), p=provider(config); JSONArray aa=p.optJSONArray("accounts");
            if(aa==null) { aa=new JSONArray(); p.put("accounts",aa); }
            String uid=result.optString("uid","").trim(); int index=-1;
            if(!uid.isEmpty()) for(int i=0;i<aa.length();i++) {
                JSONObject existing=aa.optJSONObject(i);
                if(existing!=null && uid.equals(existing.optString("uid",""))) { if(index>=0) throw new IOException("duplicate account uid"); index=i; }
            }
            if(!result.optBoolean("bound",false) || !result.optString("refreshState", "").isEmpty()) throw new IOException("account binding incomplete");
            JSONObject a=copy(result); String id=index<0?"trae_"+UUID.randomUUID():aa.getJSONObject(index).optString("id","");
            if(id.isEmpty()) id="trae_"+UUID.randomUUID();
            a.put("id",id); a.put("generation",UUID.randomUUID().toString()); a.put("enabled",true);
            a.remove("refreshState"); a.remove("models"); a.remove("modelMeta");a.put("addedAt",System.currentTimeMillis());if(a.optString("name","").trim().isEmpty())a.put("name",a.optString("uid","Trae 账号"));
            if(index<0) aa.put(a); else aa.put(index,a);
            rebuild(p); live(); if(!host.save(config)) throw new IOException("account save failed");
            return copy(a);
        }
    }
    private JSONObject syncOne(String id,RequestContext request) throws Exception {
        JSONObject a=lease(id,request); JSONObject data=TraeModels.sync(a,request.network,request);
        request.check(); JSONObject next=copy(a);
        JSONArray models=data.optJSONArray("models"); JSONObject meta=data.optJSONObject("modelMeta");
        if(models==null || meta==null) throw new IOException("invalid model catalog");
        next.put("models",new JSONArray(models.toString())); next.put("modelMeta",copy(meta));
        store.replace(id,a,next); return next;
    }
    private JSONObject poll(String id) throws Exception {
        Login entry=findFlow(id);
        if(entry==null)return new JSONObject().put("ok",false).put("status","missing").put("error","授权流程已失效，请重新发起授权");
        synchronized(entry) {
            if(entry.response!=null) return copy(entry.response);
            String status=entry.flow.status;
            if(!"success".equals(status) && !"binding_failed".equals(status)) {
                if("closed".equals(status)) status="failed";
                JSONObject ret=new JSONObject().put("ok",true).put("flowId",id).put("status",status);
                if(!entry.flow.error.isEmpty()) ret.put("error",entry.flow.error);
                return ret;
            }
            JSONObject result=entry.flow.takeResult();
            if(result==null) return new JSONObject().put("ok",false).put("flowId",id).put("status","failed").put("error","登录结果不可用，请重新登录");
            try {
                JSONObject a=addAccount(result); String aid=a.optString("id"); boolean synced=false;
                try(RequestContext req=new RequestContext("trae-model-sync",NetworkPolicy.capture(),30000)) { syncOne(aid,req); synced=true; }
                catch(Exception ignored) { }
                live(); JSONObject safe=new JSONObject().put("id",aid).put("name",a.optString("name", ""));
                JSONObject ret=new JSONObject().put("ok",true).put("flowId",id).put("status","ready").put("account",safe).put("keyId",aid);
                if(!synced || !a.optBoolean("bound",false)) ret.put("hint",!a.optBoolean("bound",false)?"设备未绑定，请重新登录；请同步模型":"请同步模型");
                entry.response=ret; return copy(ret);
            } catch(Exception failed) {
                entry.response=new JSONObject().put("ok",false).put("flowId",id).put("status","failed").put("error","保存账号失败，请重新登录");
                return copy(entry.response);
            }
        }
    }
    private List<String> accountIds(String requested) throws Exception {
        synchronized(host.lock()) {
            live(); JSONObject p=provider(host.config()); if(!p.optBoolean("enabled",true)) throw new IOException("provider disabled");
            JSONArray aa=p.optJSONArray("accounts"); List<String> ids=new ArrayList<>();
            for(int i=0;aa!=null && i<aa.length();i++) { JSONObject a=aa.optJSONObject(i); if(a==null || !a.optBoolean("enabled",true)) continue;
                String id=a.optString("id",""); if(!id.isEmpty() && (requested.isEmpty() || requested.equals(id))) { if(ids.contains(id)) throw new IOException("duplicate account id"); ids.add(id); }
            }
            if(ids.isEmpty()) throw new IOException("account unavailable"); return ids;
        }
    }
    private JSONObject sync(String id) throws Exception {
        List<String> ids=accountIds(id); int successes=0,failed=0;
        try(RequestContext req=new RequestContext("trae-model-sync",NetworkPolicy.capture(),60000)) {
            for(String aid:ids) {
                try { req.check(); syncOne(aid,req); successes++; }
                catch(Exception e) { req.check(); failed++; }
            }
        }
        synchronized(host.lock()) {
            live(); JSONObject p=provider(host.config()); JSONArray models=p.optJSONArray("models"); if(models==null) models=new JSONArray();
            return new JSONObject().put("ok",successes>0).put("models",new JSONArray(models.toString())).put("count",models.length()).put("partial",failed>0).put("succeeded",successes).put("failed",failed);
        }
    }
    private JSONObject checkin(String id,boolean claim) throws Exception {
        if(id.isEmpty()) throw new IllegalArgumentException("accountId required");
        try(RequestContext req=new RequestContext("trae-checkin",NetworkPolicy.capture(),30000)) {
            JSONObject a=lease(id,req), raw=TraeAuth.checkin(a,req.network,req,claim), out=new JSONObject().put("ok",true).put("accountId",id);
            String outcome=raw.optString("outcome","unknown");
            if(!Arrays.asList("status","done","inactive","unknown","ineligible","rejected").contains(outcome)) outcome="unknown";
            out.put("outcome",outcome); JSONObject status=raw.optJSONObject("status");
            if(status!=null) { JSONObject safe=new JSONObject();
                if(status.opt("checked_in") instanceof Boolean) safe.put("checked_in",status.optBoolean("checked_in"));
                for(String key:new String[]{"credits","extra_credits"}) { Object v=status.opt(key); if(v instanceof Number) { double d=((Number)v).doubleValue(); if(!Double.isNaN(d) && !Double.isInfinite(d) && d>=0) safe.put(key,v); } }
                out.put("status",safe);if(safe.has("credits"))out.put("credits",safe.opt("credits")); }
            return out;
        }
    }
    private static String param(Map<String,String> params,String key) { String value=params==null?null:params.get(key); return value==null?"":value.trim(); }
    public JSONObject api(String path,Map<String,String> params,JSONObject body) throws Exception {
        live(); JSONObject b=body==null?new JSONObject():body;
        if("/api/auth/trae-cn/init".equals(path)) return init("1".equals(param(params,"fresh")));
        if("/api/auth/trae-cn/poll".equals(path)) return poll(param(params,"flowId"));
        if("/api/auth/trae-cn/sync-models".equals(path)) { String id=b.optString("accountId","").trim(); if(id.isEmpty()) id=param(params,"accountId"); return sync(id); }
        if("/api/trae/checkin-status".equals(path)) return checkin(param(params,"accountId"),false);
        if("/api/trae/checkin".equals(path)) return checkin(b.optString("accountId","").trim(),true);
        throw new IllegalArgumentException("unknown Trae API");
    }
}
