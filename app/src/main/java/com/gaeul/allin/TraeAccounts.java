package com.gaeul.allin;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Account refresh coordinator. Store.replace must be an atomic full-object CAS. */
public final class TraeAccounts {
    public interface Store {
        JSONObject current(String id) throws Exception;
        void replace(String id, JSONObject expected, JSONObject next) throws Exception;
    }
    interface Refresher { JSONObject refresh(JSONObject account, NetworkPolicy.Snapshot network, RequestContext request) throws Exception; }
    private final Refresher refresher;
    private final Map<String,Object> locks = new HashMap<>();
    private final Map<String,String> quarantine = new HashMap<>();
    private volatile boolean retired;
    public TraeAccounts() { this(new Refresher() { public JSONObject refresh(JSONObject a, NetworkPolicy.Snapshot n, RequestContext r) throws Exception { return TraeAuth.refresh(a,n,r); }}); }
    TraeAccounts(Refresher refresher) { this.refresher=refresher; }
    public void retire() { retired=true; }
    private synchronized Object lock(String id) throws Exception {
        if(retired) throw new IllegalStateException("retired");
        Object l=locks.get(id); if(l==null) { l=new Object(); locks.put(id,l); } return l;
    }
    private static JSONObject copy(JSONObject a) throws Exception { return new JSONObject(a.toString()); }
    private static String identity(JSONObject a) { return a.optString("id")+"\u0000"+a.optString("generation")+"\u0000"+a.optString("revision")+"\u0000"+a.optString("deviceId")+"\u0000"+a.optString("devicePrivateKey")+"\u0000"+a.optString("token")+"\u0000"+a.optString("refresh"); }
    public JSONObject lease(String id, Store store, NetworkPolicy.Snapshot network, RequestContext request) throws Exception {
        if(id==null || id.isEmpty() || store==null) throw new IllegalArgumentException("invalid_account");
        synchronized(lock(id)) {
            JSONObject original=store.current(id);
            if(original==null || !id.equals(original.optString("id"))) throw new IllegalStateException("account_missing");
            JSONObject expected=copy(original); String fingerprint=identity(expected);
            if(!expected.optBoolean("enabled",true)) throw new IllegalStateException("account_disabled");
            String state=expected.optString("refreshState");
            synchronized(this) { if(retired) throw new IllegalStateException("retired"); if(fingerprint.equals(quarantine.get(id))) throw new IllegalStateException("outcome_unknown"); }
            if("pending".equals(state) || "outcome_unknown".equals(state)) throw new IllegalStateException("outcome_unknown");
            if("requires_relogin".equals(state)) throw new IllegalStateException(state);
            if(!state.isEmpty()) throw new IllegalStateException("outcome_unknown");
            if(expected.optString("token").isEmpty()) throw new IllegalStateException("requires_relogin");
            long expires=expected.optLong("expires",0);
            if(expires>0 && expires-System.currentTimeMillis()>300000L) { if(retired) throw new IllegalStateException("retired"); return expected; }
            JSONObject pending=copy(expected); pending.put("refreshState","pending");
            // The durable intent must be committed before any refresh bytes can be sent.
            store.replace(id,expected,pending);
            // Retired here means the exchange never started: nothing was sent, so put the record back.
            // Left pending it reads as outcome_unknown for good, with no request to blame.
            if(retired) { undoLocal(id,store,pending,expected,fingerprint); throw new IllegalStateException("retired"); }
            JSONObject next;
            try {
                next=refresher.refresh(copy(expected),network,request);
                if(next==null || !id.equals(next.optString("id")) || next.optString("token").isEmpty()) throw new IllegalStateException("invalid_refresh_result");
            } catch(Exception e) {
                boolean unsent=e instanceof TraeAuth.Failure && (((TraeAuth.Failure)e).code.equals("network_error") || ((TraeAuth.Failure)e).code.startsWith("outbound_"));
                boolean relogin=e instanceof TraeAuth.Failure && ((TraeAuth.Failure)e).code.equals("requires_relogin");
                if(!unsent) synchronized(this) { quarantine.put(id,fingerprint); }
                // A failure that never left this device is undone even while retiring: the rollback
                // writes nothing outward, and a stranded "pending" would block the account for good.
                if(unsent || relogin) {
                    JSONObject marked=copy(expected);
                    if(relogin) marked.put("refreshState","requires_relogin");
                    try { store.replace(id,pending,marked); if(relogin) synchronized(this) { quarantine.remove(id); } }
                    catch(Exception saveFailure) { synchronized(this) { quarantine.put(id,fingerprint); } e.addSuppressed(saveFailure); }
                }
                throw e;
            }
            try {
                if(retired) throw new IllegalStateException("retired");
                next.remove("refreshState");
                store.replace(id,pending,copy(next));
                synchronized(this) { quarantine.remove(id); }
                JSONObject published=store.current(id);if(published==null)throw new IllegalStateException("account_changed");return copy(published);
            } catch(Exception e) {
                synchronized(this) { quarantine.put(id,fingerprint); }
                throw e;
            }
        }
    }
    /** Nothing left this device: undo the local pending mark, and fail closed if it cannot be undone. */
    private void undoLocal(String id, Store store, JSONObject pending, JSONObject expected, String fingerprint) {
        try { store.replace(id,pending,copy(expected)); }
        catch(Exception failed) { synchronized(this) { quarantine.put(id,fingerprint); } }
    }
}
