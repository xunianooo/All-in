package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Account operations; the caller supplies an authenticated, bounded transport. */
public final class RaccoonAccountService {
    public interface Transport {
        /** Return complete JSON; throw Failure on HTTP errors. Do not follow redirects with credentials. */
        JSONObject request(String method, String path, boolean desktopHeaders) throws IOException;
    }
    public static final class Failure extends IOException {
        public final boolean expired;
        public Failure(String message, boolean expired) { super(message); this.expired = expired; }
    }
    private final Transport transport;
    public RaccoonAccountService(Transport transport) { if (transport == null) throw new IllegalArgumentException(); this.transport = transport; }
    private JSONObject get(String method, String path, boolean desktop) throws IOException {
        JSONObject response = transport.request(method, path, desktop);
        if (response == null) throw new Failure("Raccoon response missing", false);
        if (response.has("code") && !response.isNull("code")) {
            int code = response.optInt("code", -1);
            if (code != 0) throw new Failure("Raccoon API error " + code, code == 200001 || code == 200003);
        }
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }
    private static Long points(JSONObject body) {
        Object x = body.opt("available_points");
        if (x == null || x == JSONObject.NULL || x instanceof Boolean) return null;
        try { double n = Double.parseDouble(x.toString()); return !Double.isNaN(n) && !Double.isInfinite(n) && n >= 0 && n < Long.MAX_VALUE ? (long)Math.floor(n) : null; }
        catch (NumberFormatException e) { return null; }
    }
    /** setting_info may grant daily credits even when called as a balance fallback. */
    public long balance() throws IOException {
        IOException first;
        try { Long n = points(get("GET", "/api/web/points/v1/balance", false)); if (n != null) return n; first = new Failure("Raccoon balance missing", false); }
        catch (Failure e) { if (e.expired) throw e; first = e; }
        catch (IOException e) { first = e; }
        try { Long n = points(get("GET", "/api/web/office/v3/setting_info", false)); if (n != null) return n; }
        catch (Failure e) { if (e.expired) throw e; }
        catch (IOException ignored) { }
        throw first;
    }
    /** Returns unknown if snapshots cannot establish whether credits were granted. */
    public JSONObject checkIn(long nowMillis) throws IOException {
        String path = "/api/web/points/v1/bills?paging.limit=20&paging.offset=0";
        JSONArray before = null;
        try { before = get("GET", path, false).optJSONArray("items"); } catch (Failure e) { if (e.expired) throw e; } catch (IOException ignored) { }
        get("GET", "/api/web/office/v3/setting_info", true);
        if (before == null) return new JSONObject().put("outcome", "unknown");
        JSONArray after;
        try { after = get("GET", path, false).optJSONArray("items"); } catch (Failure e) { if (e.expired) throw e; return new JSONObject().put("outcome", "unknown"); } catch (IOException e) { return new JSONObject().put("outcome", "unknown"); }
        if (after == null) return new JSONObject().put("outcome", "unknown");
        // A full page can hide a newly issued bill; absence is not proof of no grant.
        boolean incomplete = before.length() >= 20 || after.length() >= 20;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US); fmt.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
        String today = fmt.format(new Date(nowMillis)); Set<String> seen = new HashSet<>();
        for (int i=0;i<before.length();i++) { JSONObject item=before.optJSONObject(i); if (item != null) seen.add(key(item)); }
        long total=0;
        for (int i=0;i<after.length();i++) {
            JSONObject item=after.optJSONObject(i); if (item == null || !seen.add(key(item))) continue;
            String date=item.optString("created_at", ""), type=item.optString("biz_type", "");
            long amount=item.optLong("points", 0);
            if (date.startsWith(today) && amount > 0 && (type.isEmpty() || type.equals("daily_grant") || type.equals("reward_grant"))) {
                if (Long.MAX_VALUE-total < amount) throw new Failure("Raccoon grant overflow", false); total+=amount;
            }
        }
        return total > 0 ? new JSONObject().put("outcome", "claimed").put("credit", total) : new JSONObject().put("outcome", incomplete ? "unknown" : "done");
    }
    private static String key(JSONObject item) {
        String id=item.optString("bill_id", ""); if (id.isEmpty()) id=item.optString("biz_id", "");
        return id.isEmpty() ? item.optString("created_at", "") + ":" + item.optString("points", "") + ":" + item.optString("event_name", "") : id;
    }
    /** Explicit opt-in: the POST may claim a one-time reward. Persist settlement per account. */
    public JSONObject claimDesktopOnboarding() throws IOException {
        JSONObject data=get("POST", "/api/web/desktop/v1/login/points/grant", true);
        if (!(data.opt("granted") instanceof Boolean)) return new JSONObject().put("settled", false);
        boolean granted=data.optBoolean("granted"); JSONObject result=new JSONObject().put("settled", true).put("granted", granted);
        if (granted) { JSONObject popup=data.optJSONObject("popup"); if (popup != null && popup.optLong("points",0)>0) result.put("credit",popup.optLong("points")); }
        return result;
    }
}
