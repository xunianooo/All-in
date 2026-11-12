package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Account-scoped gift summary cache and claim rate control. No credentials are retained. */
public final class ZCodeGiftControl {
    private static final long CACHE_MS = 10 * 60_000L, IDLE_MS = 24 * 60 * 60_000L;
    private static final long INITIAL_WAIT_MS = 30 * 60_000L, MAX_WAIT_MS = ZCodeAuth.MAX_RETRY_AFTER_MS;
    /** 上游 RECHECK_MS：一次「现在不给领」半小时后可以再问。 */
    private static final long RECHECK_MS = 30 * 60_000L;
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
        // 读取的限流（余额/列表被 429、3002）和领取的限流分开记：上游 rateWait 只挡
        // claimDaily，plansOf / giftOf 照旧读余额，一次被限的领取不该让面板读不到余额。
        long claimUntil, claimWaitMs;
        int limitCode, claimLimitCode;
        boolean loading, refreshWaiting; long completed; final Object claimLock = new Object(); JSONObject cached, lastResult;
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
                if (x.users == 0 && now >= x.touched && now - x.touched >= IDLE_MS && now >= x.until && now >= x.claimUntil) it.remove();
            }
            Entry e = entries.get(key);
            if (e == null) {
                if (entries.size() >= MAX_ACCOUNTS) {
                    it = entries.entrySet().iterator();
                    while (it.hasNext()) { Entry x = it.next().getValue(); if (x.users == 0 && now >= x.until && now >= x.claimUntil) { it.remove(); break; } }
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
        try { out.put("outcome", "failed").put("status", 429).put("businessCode", e.limitCode).put("retryAfterMs", Math.max(1, e.until - now)).put("until", e.until); } catch (Exception ignored) { }
        return out;
    }
    private static boolean limited(JSONObject o) { return o != null && (o.optInt("status") == 429 || o.optInt("businessCode") == 3002); }

    /** 领取被限流的答复：只挡下一次领取，不挡余额读取。 */
    private static JSONObject claimHeld(Entry e, long now) {
        JSONObject out = failure("ZCode gift 领取受限，请稍后重试");
        try { out.put("outcome", "failed").put("status", 429).put("businessCode", e.claimLimitCode)
                .put("retryAfterMs", Math.max(1, e.claimUntil - now)).put("until", e.claimUntil); } catch (Exception ignored) { }
        return out;
    }
    private static void noteClaimLimit(Entry e, JSONObject result) {
        long said = result.optLong("retryAfterMs", 0);
        long ms = said > 0 ? Math.min(MAX_WAIT_MS, said) : Math.min(MAX_WAIT_MS, Math.max(INITIAL_WAIT_MS, e.claimWaitMs * 2));
        e.claimWaitMs = ms; e.claimLimitCode = result.optInt("businessCode", 0);
        long now = clock.now();
        e.claimUntil = now > Long.MAX_VALUE - ms ? Long.MAX_VALUE : now + ms;
    }

    /**
     * 一次领取失败的分类，逐条对齐上游 claimOne 的 outcome：
     *
     * <ul>
     *   <li>1003（HTTP 200 "target already exists"）= 领过了，算 done，不是失败，不动缓存；</li>
     *   <li>1004 = 现在不给领，settled，不进限流（上游半小时后再问）；</li>
     *   <li>3007 / 要验证码 = captcha，交给 ZCode 客户端，不重试；</li>
     *   <li>3002 / HTTP 429 = 限流，交给 noteClaimLimit 排队（只挡领取，不挡读余额）；</li>
     *   <li>其余（含读不懂的回复、网络错误）= failed，绝不当成领到了。</li>
     * </ul>
     */
    private static JSONObject error(Exception ex) {
        if (ex instanceof ZCodeAuth.CaptchaRequiredException) {
            JSONObject captcha = failure("ZCode 的领取要它自己客户端的验证码：请在 ZCode 客户端里领取");
            try { captcha.put("outcome", "captcha"); } catch (Exception ignored) { }
            return captcha;
        }
        if (ex instanceof ZCodeAuth.ClaimUnknownException) {
            String why = ex.getMessage() == null ? "ZCode 的领取状态未知" : ex.getMessage();
            JSONObject unknown = failure(why);
            try { unknown.put("outcome", "unknown"); } catch (Exception ignored) { }
            return unknown;
        }
        if (ex instanceof ZCodeAuth.ServiceException) {
            ZCodeAuth.ServiceException service = (ZCodeAuth.ServiceException)ex;
            // 1003 是「这个计划以前领过」——一句关于过去领取成功的话，只有在回复本身
            // 成功时才成立（上游同样只认 res.ok 的正面答复：`code === 0 && res.ok`）。
            // HTTP 500 的 body 里带 1003 是故障网关给的，说成「已领取」就是把未知
            // 报成已领；这里按未知报，并带上状态和码。
            if (service.businessCode == 1003 && service.status >= 200 && service.status < 300) {
                JSONObject done = new JSONObject();
                try { done.put("ok", true).put("outcome", "done").put("alreadyClaimed", true); } catch (Exception ignored) { }
                return done;
            }
            if (service.businessCode == 1004) {
                JSONObject late = failure("ZCode 说这个计划现在还不可领，稍后再试");
                try { late.put("outcome", "ineligible").put("status", service.status)
                        .put("businessCode", 1004).put("recheckMs", RECHECK_MS); } catch (Exception ignored) { }
                return late;
            }
            if (service.businessCode == 3007) {
                JSONObject captcha = failure("ZCode 的领取要它自己客户端的验证码：请在 ZCode 客户端里领取");
                try { captcha.put("outcome", "captcha").put("status", service.status).put("businessCode", 3007); } catch (Exception ignored) { }
                return captcha;
            }
            JSONObject failed = failure(service.getMessage());
            try { failed.put("outcome", "failed").put("status", service.status)
                    .put("businessCode", service.businessCode).put("retryAfterMs", service.retryAfterMs); } catch (Exception ignored) { }
            return failed;
        }
        JSONObject failed = failure("ZCode gift 请求失败");
        try { failed.put("outcome", "failed"); } catch (Exception ignored) { }
        return failed;
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
            // remaining 是 -1 只表示这份余额算不出剩多少（旧余额只有 used/total 一边），
            // 不是「剩 0」；比 -1 还低的值才是坏数据。
            if (b == null || !b.has("remaining") || b.isNull("remaining") || b.optLong("remaining", -1) < -1 || b.optJSONArray("capabilities") == null) return false;
        }
        return true;
    }
    /** Read an existing summary without contacting upstream or changing cache state. */
    static JSONObject peekSummary(String jwt, String device) {
        synchronized (entries) {
            Entry e = entries.get(key(jwt, device));
            if (e == null) return null;
            synchronized (e) {
                long now = clock.now();
                return e.cached == null || now < e.cachedAt || now - e.cachedAt >= CACHE_MS ? null : copy(e.cached);
            }
        }
    }
    /** Returns a deep snapshot. A failed or incomplete read never becomes a cached zero. */
    public static JSONObject summary(String jwt, String device) {
        return summary(jwt, device, false);
    }
    public static JSONObject summary(String jwt, String device, boolean refresh) {
        Entry e = acquire(jwt, device); if (e == null) return failure("ZCode gift 账号状态已满，请稍后重试");
        try {
            long generation;
            long observed;
            synchronized (e) { observed = e.completed; }
            synchronized (e) {
                while (true) {
                    long now = clock.now();
                    if (now < e.until) return held(e, now);
                    if (refresh && e.completed != observed && e.lastResult != null) return copy(e.lastResult);
                    if (!refresh && e.cached != null && now >= e.cachedAt && now - e.cachedAt < CACHE_MS) return copy(e.cached);
                    if (!e.loading) { e.loading = true; generation = e.generation; break; }
                    if (refresh) e.refreshWaiting = true;
                    try { e.wait(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return failure("ZCode gift 查询已中断"); }
                }
            }
            JSONObject result;
            try { result = transport.summary(jwt, device); if (result == null) result = failure("ZCode gift 查询结果为空"); else result = copy(result); }
            catch (Exception ex) { result = error(ex); }
            synchronized (e) {
                try {
                    if (limited(result)) { noteLimit(e, result); result = held(e, clock.now()); }
                    else if (!validSummary(result)) {
                        if (result.optBoolean("ok", false)) result = failure("ZCode gift 数据不完整");
                        else try { result.put("complete", false); } catch (Exception ignored) { }
                    } else {
                        try { result.put("complete", true); } catch (Exception ignored) { }
                        if (generation == e.generation) { e.cached = copy(result); e.cachedAt = clock.now(); e.waitMs = 0; }
                        else result = failure("ZCode gift 状态已变化，请刷新");
                    }
                    if ((refresh || e.refreshWaiting) && !result.optBoolean("ok", false)) e.cached = null;
                    e.lastResult = copy(result); e.completed++;
                    return copy(result);
                } finally { e.loading = false; e.refreshWaiting = false; e.notifyAll(); }
            }
        } finally { release(e); }
    }
    /** Invalidate only this account; an older in-flight summary cannot publish. */
    public static void invalidate(String jwt, String device) {
        Entry e = acquire(jwt, device); if (e == null) return;
        try { synchronized (e) { e.generation++; e.cached = null; } } finally { release(e); }
    }
    /**
     * 返回 {ok, outcome, claimed} 或结构化错误。成功领到会立刻让缓存失效；
     * 1003（领过了）是 done，不改缓存——它不是一个新领到的额度包。
     */
    public static JSONObject claim(String jwt, String device, String giftId) {
        if (giftId == null || giftId.trim().isEmpty()) return failure("缺少 planId");
        Entry e = acquire(jwt, device); if (e == null) return failure("ZCode gift 账号状态已满，请稍后重试");
        try {
            synchronized (e.claimLock) {
            synchronized (e) { if (clock.now() < e.claimUntil) return claimHeld(e, clock.now()); }
            JSONObject result;
            try {
                transport.claim(jwt, device, giftId);
                result = new JSONObject().put("ok", true).put("outcome", "claimed").put("claimed", giftId);
            } catch (Exception ex) { result = error(ex); }
            synchronized (e) {
                if (limited(result)) { noteClaimLimit(e, result); return claimHeld(e, clock.now()); }
                if (result.optBoolean("ok", false) && !"done".equals(result.optString("outcome", ""))) {
                    e.generation++; e.cached = null; e.waitMs = 0;
                }
                return result;
            }
            }
        } finally { release(e); }
    }

    /**
     * 一次把 feed 里的计划都领一遍，逐条给出结果：
     * {claimed, done, ineligible, captcha, failed}。任何一个计划要验证码就停在那里
     * （同一账号剩下的计划只会换回同样的 3007），被限流也停（上游同样停在这一条）。
     */
    public static JSONObject claimAll(String jwt, String device) {
        JSONObject sum = summary(jwt, device);
        JSONArray claimed = new JSONArray(), done = new JSONArray(), ineligible = new JSONArray();
        JSONArray captcha = new JSONArray(), failed = new JSONArray();
        JSONObject out = new JSONObject();
        try {
            out.put("claimed", claimed).put("done", done).put("ineligible", ineligible)
                    .put("captcha", captcha).put("failed", failed);
            if (!sum.optBoolean("ok", false) || !sum.optBoolean("complete", false)) {
                out.put("ok", false).put("complete", false).put("error", sum.optString("error", "gift 查询失败"));
                for (String field : new String[]{"status", "businessCode", "retryAfterMs", "until"}) if (sum.has(field)) out.put(field, sum.opt(field));
                return out;
            }
            // 可领列表读不出来时一个都不领：未知不是「没有」，更不是「全都领」。
            if (sum.has("claimableKnown") && !sum.optBoolean("claimableKnown", true)) {
                out.put("ok", false).put("complete", false)
                        .put("error", sum.optString("claimableError", "ZCode 的可领取计划未知，未发起领取"));
                return out;
            }
            JSONArray plans = sum.optJSONArray("claimable");
            for (int i=0; i<plans.length(); i++) {
                JSONObject p = plans.optJSONObject(i); String id = p == null ? "" : p.optString("planId", "");
                if (id.isEmpty()) { failed.put(failure("gift 计划缺少 planId")); continue; }
                JSONObject result = claim(jwt, device, id);
                String outcome = result.optString("outcome", result.optBoolean("ok", false) ? "claimed" : "failed");
                if ("claimed".equals(outcome)) { claimed.put(id); continue; }
                if ("done".equals(outcome)) { done.put(id); continue; }
                if ("ineligible".equals(outcome)) { ineligible.put(id); continue; }
                JSONObject item = new JSONObject(result.toString()); item.put("planId", id);
                if ("captcha".equals(outcome)) {
                    captcha.put(item);
                    out.put("outcome", "captcha").put("error", result.optString("error", ""));
                    break;
                }
                failed.put(item);
                if (limited(result)) {
                    out.put("outcome", "failed").put("error", result.optString("error", ""));
                    for (String field : new String[]{"status", "businessCode", "retryAfterMs", "until"}) if (result.has(field)) out.put(field, result.opt(field));
                    break;
                }
            }
            boolean settled = failed.length() == 0 && captcha.length() == 0;
            if (!settled && !out.has("error")) {
                JSONObject first = failed.length() > 0 ? failed.optJSONObject(0) : (captcha.length() > 0 ? captcha.optJSONObject(0) : null);
                out.put("error", first == null ? "ZCode gift 领取失败" : first.optString("error", "ZCode gift 领取失败"));
            }
            return out.put("ok", settled).put("complete", settled);
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
