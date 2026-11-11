package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

/** Converts known provider usage responses into a minimal account-level snapshot.
 * Null means no readable allowance; it never means exhausted. */
public final class ProviderAllowance {
    private ProviderAllowance() {}
    private static Double number(Object x) {
        if (!(x instanceof Number) && !(x instanceof String)) return null;
        try { double n = Double.parseDouble(x.toString()); return Double.isNaN(n) || Double.isInfinite(n) ? null : n; }
        catch (Exception e) { return null; }
    }
    private static Double nonnegative(Object x) { Double n=number(x); return n != null && n >= 0 ? n : null; }
    private static Double percent(Object x) { Double n=nonnegative(x); return n != null && n <= 100 ? n : null; }
    private static JSONObject base(String unit, long at) throws Exception {
        return new JSONObject().put("kind", "allowance").put("scope", "account").put("unit", unit).put("windows", new JSONArray()).put("observedAt", at);
    }
    private static void window(JSONArray windows, String name, Double remaining, Object reset) throws Exception {
        if (remaining == null) return;
        JSONObject w = new JSONObject().put("name", name).put("remainingPercent", remaining);
        if (reset instanceof Number && nonnegative(reset) != null && ((Number)reset).doubleValue() > 0) w.put("resetAt", reset);
        else if (reset instanceof String && !((String)reset).trim().isEmpty() && ((String)reset).length() <= 64) w.put("resetAt", reset);
        windows.put(w);
    }
    private static JSONObject done(JSONObject o) { return o.has("remaining") || o.has("remainingPercent") || o.optJSONArray("windows").length()>0 ? o : null; }
    public static JSONObject fromCodex(JSONObject raw) { return fromCodex(raw, System.currentTimeMillis()); }
    public static JSONObject fromCodex(JSONObject raw, long at) {
        if (raw == null) return null;
        try {
            JSONObject out=base("percent",at), limits=raw.optJSONObject("rate_limit");
            if (limits != null) for (String key : new String[]{"primary_window", "secondary_window"}) {
                JSONObject w=limits.optJSONObject(key); if (w==null) continue;
                Double used=percent(w.opt("used_percent"));
                window(out.optJSONArray("windows"),key,used==null?null:100-used,w.opt("reset_at"));
            }
            return done(out);
        } catch (Exception e) { return null; }
    }
    public static JSONObject fromCline(JSONObject raw) { return fromCline(raw,System.currentTimeMillis()); }
    public static JSONObject fromCline(JSONObject raw,long at) {
        if(raw==null) return null;
        try {
            Double dollars=nonnegative(raw.opt("balanceUSD"));
            JSONObject out=base(dollars==null?"percent":"USD",at);
            if(dollars!=null) out.put("remaining",dollars);
            JSONArray limits=raw.optJSONArray("limits");
            if(limits!=null) for(int i=0;i<limits.length();i++) {
                JSONObject w=limits.optJSONObject(i); if(w==null) continue;
                String name=w.optString("type",""); Double used=percent(w.opt("percentUsed"));
                if(!name.isEmpty() && name.length()<=64) window(out.optJSONArray("windows"),name,used==null?null:100-used,w.opt("resetsAt"));
            }
            return done(out);
        } catch(Exception e) { return null; }
    }
    public static JSONObject fromCatPaw(JSONObject raw) { return fromCatPaw(raw,System.currentTimeMillis()); }
    public static JSONObject fromCatPaw(JSONObject raw,long at) {
        if(raw==null || raw.has("error")) return null;
        try { Double credit=nonnegative(raw.opt("credit")); if(credit==null) return null; return base("credits",at).put("remaining",credit); }
        catch(Exception e) { return null; }
    }
    /** A snapshot's own reset when it names one; 0 or empty is none, so the month's stands. */
    private static Object copilotReset(Object own, Object root) {
        if(own instanceof Number) return ((Number)own).doubleValue()>0 ? own : root;
        if(own instanceof String) return ((String)own).trim().isEmpty() ? root : own;
        return root;
    }
    public static JSONObject fromCopilot(JSONObject raw) { return fromCopilot(raw,System.currentTimeMillis()); }
    public static JSONObject fromCopilot(JSONObject raw,long at) {
        if(raw==null) return null;
        try {
            JSONObject snapshots=raw.optJSONObject("quota_snapshots"), out=base("percent",at);
            Object rootReset=raw.opt("quota_reset_date_utc"); if(rootReset==null) rootReset=raw.opt("quota_reset_date"); if(rootReset==null) rootReset=raw.opt("limited_user_reset_date");
            for(String name:new String[]{"premium_interactions","chat","completions"}) {
                JSONObject q=snapshots==null?null:snapshots.optJSONObject(name);
                if(q==null) continue;
                boolean premium="premium_interactions".equals(name);
                Object reset=copilotReset(q.opt("quota_reset_at"),rootReset);
                if(q.optBoolean("unlimited",false)) {
                    // A managed pool is labelled unlimited even once it is spent; GitHub then says
                    // has_quota false and VS Code pauses the seat, so the pool is used up, not unlimited.
                    // Any other unlimited snapshot holds no share this model can state, and a share
                    // said for it would be invented: it is left out.
                    if(premium && q.has("has_quota") && !q.optBoolean("has_quota",true)) window(out.optJSONArray("windows"),name,0d,reset);
                    continue;
                }
                Double limit=nonnegative(q.opt("entitlement"));
                // has_quota false says nothing either way: GitHub sends it for an allowance used up,
                // and under token billing for every one. An entitlement of 0 is no allowance at all.
                if(limit!=null && limit<=0) continue;
                Double left=nonnegative(q.opt("quota_remaining")); if(left==null) left=nonnegative(q.opt("remaining"));
                // The counts are the precise reading, clamped to the seat; the percentage is the fallback.
                Double p=percent(q.opt("percent_remaining"));
                if(limit!=null && limit>0 && left!=null) p=Math.max(0,Math.min(100,100*left/limit));
                window(out.optJSONArray("windows"),name,p,reset);
                if(premium && left!=null) {
                    out.put("remaining",left).put("unit",q.optBoolean("token_based_billing",raw.optBoolean("token_based_billing",false))?"credits":"requests");
                }
            }
            if(snapshots==null) {
                JSONObject totals=raw.optJSONObject("monthly_quotas"), lefts=raw.optJSONObject("limited_user_quotas");
                if(totals!=null && lefts!=null) for(String name:new String[]{"chat","completions"}) {
                    Double total=nonnegative(totals.opt(name)),left=nonnegative(lefts.opt(name));
                    if(total!=null && total>0 && left!=null) window(out.optJSONArray("windows"),name,Math.max(0,Math.min(100,100*left/total)),rootReset);
                }
            }
            return done(out);
        } catch(Exception e) { return null; }
    }
    private static Double miniPercent(Object value) {
        if (value instanceof String) {
            String text=((String)value).trim();
            if (text.endsWith("%")) value=text.substring(0,text.length()-1).trim();
        }
        return nonnegative(value);
    }
    /** Account windows remain separate from model-specific pools. */
    public static JSONObject fromMiniMax(JSONObject raw) { return fromMiniMax(raw,System.currentTimeMillis()); }
    public static JSONObject fromMiniMax(JSONObject raw,long at) {
        if(raw==null) return null;
        try {
            JSONObject response=raw.optJSONObject("base_resp"); if(response!=null && response.optInt("status_code",0)!=0) return null;
            JSONArray rows=raw.optJSONArray("model_remains"), pools=new JSONArray(); JSONObject out=base("percent",at);
            if(rows!=null) for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i); if(row==null) continue;
                String model=row.optString("model_name","").trim();
                if(model.isEmpty() || model.length()>64) continue;
                boolean general="general".equalsIgnoreCase(model);
                // The upstream uses two unlimited/zero-count windows for absent pools.
                if(!general && row.optInt("current_interval_status",0)==3 && row.optInt("current_weekly_status",0)==3
                    && Double.valueOf(0).equals(nonnegative(row.opt("current_interval_total_count")))
                    && Double.valueOf(0).equals(nonnegative(row.opt("current_weekly_total_count")))) continue;
                JSONArray modelWindows=new JSONArray();
                for (String part : new String[]{"interval", "weekly"}) {
                    String prefix="current_"+part+"_"; int status=row.optInt(prefix+"status",0);
                    if(status==3) continue;
                    Double left=percent(row.opt(prefix+"remaining_percent"));
                    Double used=miniPercent(row.opt(prefix+"used_percent")), total=miniPercent(row.opt(prefix+"total_percent"));
                    if(total==null && "weekly".equals(part)) { Double boost=nonnegative(row.opt("weekly_boost_permille")); if(boost!=null && boost>1000)total=boost/10; }
                    if(status==2)left=0.0;
                    else if(used!=null && (general ? (total==null || total>0) : true)) {
                        double divisor=total!=null && total>0 ? total : 100;
                        left=Math.max(0,100-used/divisor*100);
                    }
                    Object reset=row.opt("weekly".equals(part)?"weekly_end_time":"end_time");
                    if(reset instanceof Number && ((Number)reset).doubleValue()>0 && ((Number)reset).doubleValue()<1e12) reset=((Number)reset).longValue()*1000L;
                    if(general) window(out.optJSONArray("windows"),part,left,reset);
                    else {
                        Double count=nonnegative(row.opt(prefix+"used_count")), countTotal=nonnegative(row.opt(prefix+"total_count"));
                        boolean validCount=count!=null && countTotal!=null && countTotal>0 && count<=countTotal;
                        // Upstream requires a percentage or exhausted status before displaying counts.
                        if(left==null) continue;
                        JSONObject w=new JSONObject().put("name",part);
                        if(left!=null) w.put("remainingPercent",left);
                        if(validCount) w.put("remaining", status==2 ? 0 : countTotal-count).put("total",countTotal).put("unit","count");
                        if(reset instanceof Number && ((Number)reset).doubleValue()>0) w.put("resetAt",reset);
                        modelWindows.put(w);
                    }
                }
                if(!general && modelWindows.length()>0) pools.put(new JSONObject().put("name",model).put("windows",modelWindows));
            }
            if(pools.length()>0) out.put("modelPools",pools);
            return done(out)!=null || pools.length()>0 ? out : null;
        } catch(Exception e) { return null; }
    }
}
