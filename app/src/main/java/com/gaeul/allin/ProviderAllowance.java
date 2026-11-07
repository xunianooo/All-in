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
    public static JSONObject fromCopilot(JSONObject raw) { return fromCopilot(raw,System.currentTimeMillis()); }
    public static JSONObject fromCopilot(JSONObject raw,long at) {
        if(raw==null) return null;
        try {
            JSONObject snapshots=raw.optJSONObject("quota_snapshots"), out=base("percent",at);
            Object rootReset=raw.opt("quota_reset_date_utc"); if(rootReset==null) rootReset=raw.opt("quota_reset_date"); if(rootReset==null) rootReset=raw.opt("limited_user_reset_date");
            for(String name:new String[]{"premium_interactions","chat","completions"}) {
                JSONObject q=snapshots==null?null:snapshots.optJSONObject(name);
                if(q==null || q.optBoolean("unlimited",false)) continue;
                Double left=nonnegative(q.opt("quota_remaining")); if(left==null) left=nonnegative(q.opt("remaining"));
                Double limit=nonnegative(q.opt("entitlement")); Double p=percent(q.opt("percent_remaining"));
                if(limit!=null && limit>0 && left!=null && left<=limit) p=100*left/limit;
                Object reset=q.opt("quota_reset_at"); if(reset==null) reset=rootReset;
                window(out.optJSONArray("windows"),name,p,reset);
                if(name.equals("premium_interactions") && left!=null && (limit==null || (limit>0 && left<=limit))) {
                    out.put("remaining",left).put("unit",q.optBoolean("token_based_billing",raw.optBoolean("token_based_billing",false))?"credits":"requests");
                }
            }
            if(snapshots==null) {
                JSONObject totals=raw.optJSONObject("monthly_quotas"), lefts=raw.optJSONObject("limited_user_quotas");
                if(totals!=null && lefts!=null) for(String name:new String[]{"chat","completions"}) {
                    Double total=nonnegative(totals.opt(name)),left=nonnegative(lefts.opt(name));
                    if(total!=null && total>0 && left!=null && left<=total) window(out.optJSONArray("windows"),name,100*left/total,rootReset);
                }
            }
            return done(out);
        } catch(Exception e) { return null; }
    }
    /** MiniMax model-specific pools are deliberately excluded from account allowance. */
    public static JSONObject fromMiniMax(JSONObject raw) { return fromMiniMax(raw,System.currentTimeMillis()); }
    public static JSONObject fromMiniMax(JSONObject raw,long at) {
        if(raw==null) return null;
        try {
            JSONObject response=raw.optJSONObject("base_resp"); if(response!=null && response.optInt("status_code",0)!=0) return null;
            JSONArray rows=raw.optJSONArray("model_remains"); JSONObject out=base("percent",at);
            if(rows!=null) for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i); if(row==null || !"general".equalsIgnoreCase(row.optString("model_name",""))) continue;
                for (String part : new String[]{"interval", "weekly"}) {
                    String prefix="current_"+part+"_"; int status=row.optInt(prefix+"status",0);
                    if(status==3) continue;
                    Double left=percent(row.opt(prefix+"remaining_percent"));
                    Double used=nonnegative(row.opt(prefix+"used_percent")), total=nonnegative(row.opt(prefix+"total_percent"));
                    if(total==null && "weekly".equals(part)) { Double boost=nonnegative(row.opt("weekly_boost_permille")); if(boost!=null && boost>1000)total=boost/10; }
                    if(status==2)left=0.0;
                    else if(used!=null && (total==null || total>0))left=Math.max(0,100-used/(total==null?100:total)*100);
                    Object reset=row.opt("weekly".equals(part)?"weekly_end_time":"end_time");
                    if(reset instanceof Number && ((Number)reset).doubleValue()>0 && ((Number)reset).doubleValue()<1e12) reset=((Number)reset).longValue()*1000L;
                    window(out.optJSONArray("windows"),part,left,reset);
                }
            }
            return done(out);
        } catch(Exception e) { return null; }
    }
}
