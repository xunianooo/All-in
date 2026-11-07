package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Account-scoped gift summary cache and claim rate control. No credentials are retained. */
public final class ZCodeGiftControl {
    private static final long CACHE_MS = 10 * 60_000L, IDLE_MS = 24 * 60 * 60_000L;
    private static final long INITIAL_WAIT_MS = 30 * 60_000L, MAX_WAIT_MS = ZCodeAuth.MAX_RETRY_AFTER_MS;
    private static final int MAX_ACCOUNTS = 512;
    public interface Clock { long now(); }
    public interface Transport {
        JSONObject summary(String jwt, String device) throws Exception;
        JSONObject claim(String jwt, String device, String planId) throws Exception;
    }
    private static final Clock SYSTEM_CLOCK = System::currentTimeMillis;
    private static final Transport DEFAULT_TRANSPORT = new Transport() {
        public JSONObject summary(String jwt, String device) { return ZCodeAuth.giftSummary(jwt, device); }
        public JSONObject claim(String jwt, String device, String id) throws Exception { return ZCodeAuth.giftClaim(jwt, device, id); }
    };
    private static volatile Clock clock = SYSTEM_CLOCK;
    private static volatile Transport transport = DEFAULT_TRANSPORT;
    private static final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private static final class Entry {
        int users; long touched, generation, cachedAt, until, waitMs;
        int limitCode; boolean loading; final Object claimLock = new Object(); JSONObject cached;
    }
    private ZCodeGiftControl() { }

    private static String key(String jwt, String device) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] a = (jwt == null ? "" : jwt).getBytes(StandardCharsets.UTF_8);
            byte[] b = (device == null ? "" : device).getBytes(StandardCharsets.UTF_8);
            d.update(new byte[]{(byte)(a.length >>> 24), (byte)(a.length >>> 16), (byte)(a.length >>> 8), (byte)a.length});
            d.update(a); d.update(b);
            StringBuilder hex = new StringBuilder(64);
            for (byte x : d.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static Entry acquire(String jwt, String device) {
        String key = key(jwt, device); long now = clock.now();
        synchronized (entries) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Entry x = it.next().getValue();
                if (x.users == 0 && now >= x.touched && now - x.touched >= IDLE_MS && now >= x.until) it.remove();
            }
            Entry e = entries.get(key);
            if (e == null) {
                if (entries.size() >= MAX_ACCOUNTS) {
                    it = entries.entrySet().iterator();
                    while (it.hasNext()) { Entry x = it.next().getValue(); if (x.users == 0 && now >= x.until) { it.remove(); break; } }
                }
                if (entries.size() >= MAX_ACCOUNTS) return null;
                e = new Entry(); entries.put(key, e);
            }
            e.users++; e.touched = now; return e;
        }
    }
    private static void release(Entry e) { synchronized (entries) { e.touched = clock.now(); e.users--; } }
    private static JSONObject copy(JSONObject o) { try { return new JSONObject(o.toString()); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static JSONObject failure(String message) { try { return new JSONObject().put("ok", false).put("complete", false).put("error", message); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static JSONObject held(Entry e, long now) {
        JSONObject out = failure("ZCode gift 请求受限，请稍后重试");
        try { out.put("status", 429).put("businessCode", e.limitCode).put("retryAfterMs", Math.max(1, e.until - now)).put("until", e.until); } catch (Exception ignored) { }
        return out;
    }
    private static boolean limited(JSONObject o) { return o != null && (o.optInt("status") == 429 || o.optInt("businessCode") == 3002); }
    private static JSONObject error(Exception ex) {
        JSONObject o = failure(ex instanceof ZCodeAuth.ServiceException ? ex.getMessage() : "ZCode gift 请求失败");
        if (ex instanceof ZCodeAuth.ServiceException) try {
            ZCodeAuth.ServiceException e = (ZCodeAuth.ServiceException)ex;
            o.put("status", e.status).put("businessCode", e.businessCode).put("retryAfterMs", e.retryAfterMs);
        } catch (Exception ignored) { }
        return o;
    }
    private static void noteLimit(Entry e, JSONObject result) {
        long said = result.optLong("retryAfterMs", 0);
        long ms = said > 0 ? Math.min(MAX_WAIT_MS, said) : Math.min(MAX_WAIT_MS, Math.max(INITIAL_WAIT_MS, e.waitMs * 2));
        e.waitMs = ms; e.limitCode = result.optInt("businessCode", 0); long now = clock.now(); e.until = now > Long.MAX_VALUE - ms ? Long.MAX_VALUE : now + ms;
    }
    private static boolean validSummary(JSONObject o) {
        if (o == null || !o.optBoolean("ok", false) || o.optJSONArray("claimable") == null || o.optJSONArray("buckets") == null) return false;
        JSONArray buckets = o.optJSONArray("buckets");
        for (int i=0; i<buckets.length(); i++) {
            JSONObject b = buckets.optJSONObject(i);
            if (b == null || !b.has("remaining") || b.isNull("remaining") || b.optLong("remaining", -1) < 0 || b.optJSONArray("capabilities") == null) return false;
        }
        return true;
    }
    /** Returns a deep snapshot. A failed or incomplete read never becomes a cached zero. */
    public static JSONObject summary(String jwt, String device) {
        Entry e = acquire(jwt, device); if (e == null) return failure("ZCode gift 账号状态已满，请稍后重试");
        try {
            long generation;
            synchronized (e) {
                while (true) {
                    long now = clock.now();
                    if (now < e.until) return held(e, now);
                    if (e.cached != null && now >= e.cachedAt && now - e.cachedAt < CACHE_MS) return copy(e.cached);
                    if (!e.loading) { e.loading = true; generation = e.generation; break; }
                    try { e.wait(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return failure("ZCode gift 查询已中断"); }
                }
            }
            JSONObject result;
            try { result = transport.summary(jwt, device); if (result == null) result = failure("ZCode gift 查询结果为空"); else result = copy(result); }
            catch (Exception ex) { result = error(ex); }
            synchronized (e) {
                try {
                    if (limited(result)) { noteLimit(e, result); return held(e, clock.now()); }
                    if (!validSummary(result)) {
                        if (result.optBoolean("ok", false)) return failure("ZCode gift 数据不完整");
                        try { result.put("complete", false); } catch (Exception ignored) { }
                        return copy(result);
                    }
                    try { result.put("complete", true); } catch (Exception ignored) { }
                    if (generation == e.generation) { e.cached = copy(result); e.cachedAt = clock.now(); e.waitMs = 0; }
                    else return failure("ZCode gift 状态已变化，请刷新");
                    return copy(result);
                } finally { e.loading = false; e.notifyAll(); }
            }
        } finally { release(e); }
    }
    /** Invalidate only this account; an older in-flight summary cannot publish. */
    public static void invalidate(String jwt, String device) {
        Entry e = acquire(jwt, device); if (e == null) return;
        try { synchronized (e) { e.generation++; e.cached = null; } } finally { release(e); }
    }
    /** Returns {ok,claimed,giftId} or structured error. Successful claims invalidate immediately. */
    public static JSONObject claim(String jwt, String device, String giftId) {
        if (giftId == null || giftId.trim().isEmpty()) return failure("缺少 planId");
        Entry e = acquire(jwt, device); if (e == null) return failure("ZCode gift 账号状态已满，请稍后重试");
        try {
            synchronized (e.claimLock) {
            synchronized (e) { if (clock.now() < e.until) return held(e, clock.now()); }
            JSONObject result;
            try { transport.claim(jwt, device, giftId); result = new JSONObject().put("ok", true).put("claimed", giftId); }
            catch (Exception ex) { result = error(ex); }
            synchronized (e) {
                if (limited(result)) { noteLimit(e, result); return held(e, clock.now()); }
                if (result.optBoolean("ok", false)) { e.generation++; e.cached = null; e.waitMs = 0; }
                return result;
            }
            }
        } finally { release(e); }
    }
    /** Returns per-item claimed/failed arrays. A limited claim stops the batch. */
    public static JSONObject claimAll(String jwt, String device) {
        JSONObject sum = summary(jwt, device); JSONArray done = new JSONArray(), failed = new JSONArray();
        JSONObject out = new JSONObject();
        try {
            out.put("claimed", done).put("failed", failed);
            if (!sum.optBoolean("ok", false) || !sum.optBoolean("complete", false)) {
                out.put("ok", false).put("complete", false).put("error", sum.optString("error", "gift 查询失败"));
                for (String field : new String[]{"status", "businessCode", "retryAfterMs", "until"}) if (sum.has(field)) out.put(field, sum.opt(field));
                return out;
            }
            JSONArray plans = sum.optJSONArray("claimable");
            for (int i=0; i<plans.length(); i++) {
                JSONObject p = plans.optJSONObject(i); String id = p == null ? "" : p.optString("planId", "");
                if (id.isEmpty()) { failed.put(failure("gift 计划缺少 planId")); continue; }
                JSONObject result = claim(jwt, device, id);
                if (result.optBoolean("ok", false)) done.put(id);
                else {
                    result.put("planId", id); failed.put(result);
                    if (limited(result)) { for (String field : new String[]{"status", "businessCode", "retryAfterMs", "until"}) if (result.has(field)) out.put(field, result.opt(field)); break; }
                }
            }
            return out.put("ok", failed.length() == 0).put("complete", failed.length() == 0);
        } catch (Exception ex) { return failure("ZCode gift 批量领取失败"); }
    }
    /** Tests must call only when no operation is in flight. Null restores production defaults. */
    public static void setTestHooks(Clock testClock, Transport testTransport) {
        synchronized (entries) {
            for (Entry e : entries.values()) if (e.users != 0) throw new IllegalStateException("gift operation in flight");
            entries.clear(); clock = testClock == null ? SYSTEM_CLOCK : testClock; transport = testTransport == null ? DEFAULT_TRANSPORT : testTransport;
        }
    }
}
