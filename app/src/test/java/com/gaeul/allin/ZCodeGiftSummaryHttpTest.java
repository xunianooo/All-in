package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw HTTP-envelope regression coverage for gift discovery, claim gating and the balance
 * rules; synthetic fixtures, no provider calls and no account.
 *
 * <p>Pinned here, because these are the ways discovery and claiming went wrong against
 * upstream packages/zcode (#678, #503, #30, the 11-04 claim replies), and the ways the
 * balance can be misread:
 *
 * <ul>
 *   <li>the claim list comes from {@code GET /api/v1/marketing/touch?seq=N} — the feed the
 *       v3.17 client reads alone, and the only place a banner plan is ever listed — with the
 *       client's version/platform/language headers;</li>
 *   <li>the balance is read as {@code billing/balance?app_version=APP_VERSION} and only the
 *       buckets of a live plan count: an ended plan, one that has not started, a bucket past
 *       its own end, and a bucket no plan owns serve nothing;</li>
 *   <li>a balance without {@code remaining_units} still reads through its {@code used_units}/
 *       {@code total_units}; a spent one stays spent, a live one keeps its models;</li>
 *   <li>no claim is posted before {@code client/configs} says the captcha is off — a config
 *       that is on, unreadable or unreachable posts nothing;</li>
 *   <li>only an explicit success code is a claim: 1003 is done, 1004 is ineligible, 3007 is
 *       captcha, an unreadable body is unknown.</li>
 * </ul>
 *
 * <p>The expected values are the reference plugin's own answers for the same fixtures
 * (packages/zcode claimDaily / claimOne / startPlan / giftOf); see docs for the harness.
 */
public final class ZCodeGiftSummaryHttpTest {
    static String touch, balance, claimBody, configs;
    static int status = 200, balanceStatus = 200, claimStatus = 200, configsStatus = 200;
    static int touches, balances, claims, configReads;
    static boolean networkFailure, configsNetworkFailure;

    /** Per-request observations: the last value seen for each kind of call. */
    static String touchQuery = "", balanceQuery = "", claimPath = "", claimBodySent = "", configsQuery = "";
    static final Map<String,String> lastHeaders = new HashMap<>();

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static final class Fixture extends HttpURLConnection {
        final boolean isTouch, isBalance, isClaim, isConfigs;
        final String query;
        Fixture(URL url) {
            super(url);
            String path = url.getPath();
            isTouch = path.endsWith("/marketing/touch");
            isBalance = path.endsWith("/billing/balance");
            isClaim = path.endsWith("/billing/claim");
            isConfigs = path.endsWith("/client/configs");
            query = url.getQuery() == null ? "" : url.getQuery();
            if (isTouch) { touches++; touchQuery = query; }
            else if (isBalance) { balances++; balanceQuery = query; }
            else if (isClaim) { claims++; claimPath = url.getPath(); claimBodySent = ""; }
            else if (isConfigs) { configReads++; configsQuery = query; }
        }
        public void setRequestProperty(String key, String value) {
            lastHeaders.put(key.toLowerCase(java.util.Locale.ROOT), value);
            super.setRequestProperty(key, value);
        }
        public void connect() { }
        public void disconnect() { }
        public boolean usingProxy() { return false; }
        public int getResponseCode() throws java.io.IOException {
            if (isTouch) return status;
            if (isClaim) return claimStatus;
            if (isConfigs) { if (configsNetworkFailure) throw new java.io.IOException("fixture"); return configsStatus; }
            if (networkFailure) throw new java.io.IOException("fixture");
            return balanceStatus;
        }
        public String getHeaderField(String key) { return "Retry-After".equalsIgnoreCase(key) ? "47" : null; }
        public InputStream getInputStream() {
            return new ByteArrayInputStream((isTouch ? touch : isClaim ? claimBody : isConfigs ? configs : balance).getBytes(StandardCharsets.UTF_8));
        }
        public InputStream getErrorStream() { return getInputStream(); }
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                public void write(byte[] b, int off, int len) {
                    claimBodySent += new String(b, off, len, StandardCharsets.UTF_8);
                }
            };
        }
    }

    static JSONObject summary() { return ZCodeAuth.giftSummary("fixture-session", "fixture-device"); }
    static void unavailable(String why) {
        JSONObject out = summary();
        check(!out.optBoolean("ok"), why + ": must fail, not report an empty claim list");
    }

    /** One banner delivery in the shape ZCode answers marketing/touch: the claim button and the plan's name. */
    static JSONObject banner(String planId, String name, long endsAt) throws Exception {
        JSONObject plan = new JSONObject().put("plan_id", planId).put("name", name).put("ends_at", endsAt);
        JSONObject claim = new JSONObject().put("text", new JSONObject().put("format", "").put("content", ""))
                .put("action", new JSONObject().put("type", "claim_zcode_plan")
                        .put("args", new JSONObject().put("plan_id", planId)));
        JSONObject close = new JSONObject().put("action", new JSONObject().put("type", "close").put("args", JSONObject.NULL));
        JSONObject background = new JSONObject().put("type", "bundle")
                .put("args", new JSONObject().put("zcode_plan", plan));
        return new JSONObject().put("campaign_id", "fixture-campaign").put("resource_position", "banner")
                .put("banner", new JSONObject().put("buttons", new JSONArray().put(claim).put(close))
                        .put("background", background));
    }

    static String feed(JSONObject... deliveries) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject d : deliveries) arr.put(d);
        return new JSONObject().put("code", 0).put("msg", "")
                .put("data", new JSONObject().put("server_time", nowSeconds()).put("language", "zh-CN").put("deliveries", arr))
                .toString();
    }

    static long nowSeconds() { return System.currentTimeMillis() / 1000L; }

    static JSONObject plan(String planId, String status, long startsAt, long endsAt) throws Exception {
        return new JSONObject().put("plan_id", planId).put("user_plan_id", "up-" + planId)
                .put("name", "Festival Grant").put("status", status)
                .put("starts_at", startsAt).put("ends_at", endsAt);
    }

    static JSONObject bucket(String planId, String model, long left, long total, long used, long expiresAt) throws Exception {
        return bucket(planId, model, (double) left, (double) total, (double) used, expiresAt);
    }

    /** Same bucket with counts as doubles (a fraction) or as strings; -1 omits the field. */
    static JSONObject bucket(String planId, String model, double left, double total, double used, long expiresAt) throws Exception {
        return bucket(planId, model, left < 0 ? null : Double.valueOf(left),
                total < 0 ? null : Double.valueOf(total), used < 0 ? null : Double.valueOf(used), expiresAt);
    }

    /** Numeric strings, as an older balance writes them. */
    static JSONObject bucketStrings(String planId, String model, String left, String total, String used, long expiresAt) throws Exception {
        return bucket(planId, model, (Object) left, total == null ? null : (Object) total,
                used == null ? null : (Object) used, expiresAt);
    }

    private static JSONObject bucket(String planId, String model, Object left, Object total, Object used, long expiresAt) throws Exception {
        JSONObject b = new JSONObject().put("plan_id", planId).put("user_plan_id", "up-" + planId)
                .put("show_name", "Limited credit").put("capabilities", new JSONArray().put("model:" + model))
                .put("expires_at", expiresAt);
        if (left != null) b.put("remaining_units", left);
        if (total != null) b.put("total_units", total);
        if (used != null) b.put("used_units", used);
        return b;
    }

    /** plans == null sends no plans key at all (the shape a misread balance has). */
    static void balanceOf(JSONArray plans, JSONArray buckets) throws Exception {
        JSONObject data = new JSONObject().put("server_time", nowSeconds()).put("balances", buckets);
        if (plans != null) data.put("plans", plans);
        balance = new JSONObject().put("code", 0).put("msg", "").put("data", data).toString();
    }

    /** client/configs as ZCode answers it; captchaJson is the raw literal, so a value that is not an object can be pinned too. */
    static String configsWithCaptcha(String captchaJson) {
        return "{\"code\":0,\"msg\":\"\",\"data\":{\"configs\":{\"builtin_provider_config_json\":\"https://example.invalid/config.json\""
                + (captchaJson == null ? "" : ",\"captcha\":" + captchaJson) + "}}}";
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Fixture(url); }
            protected URLConnection openConnection(URL url, Proxy proxy) { return new Fixture(url); }
        });
        long now = nowSeconds();
        long expires = now + 3600;
        configs = configsWithCaptcha(null);
        touch = feed(banner("zcode-v3-start-plan-1030-wk", "ZCODE WEEKEND BUILD", now + 86400));
        balanceOf(new JSONArray().put(plan("fixture-plan", "active", 0, now + 86400)),
                new JSONArray().put(bucket("fixture-plan", "glm-5.3-flash", 500000000, 500000000, 0, expires)));
        claimBody = "{\"code\":0,\"msg\":\"Plan claimed successfully.\"}";

        // 1. A banner plan is discovered from the feed alone, and the balance question
        //    carries the app_version that lets ZCode answer its capabilities.
        JSONObject out = summary();
        check(out.optBoolean("ok"), "feed with a claim banner is a live summary: " + out);
        JSONArray claimable = out.getJSONArray("claimable");
        check(claimable.length() == 1, "one banner = one claimable plan: " + claimable);
        check("zcode-v3-start-plan-1030-wk".equals(claimable.getJSONObject(0).optString("planId")), "plan id from the action");
        check("ZCODE WEEKEND BUILD".equals(claimable.getJSONObject(0).optString("name")), "plan name from zcode_plan.name");
        check(out.optBoolean("hasGift") && out.optLong("remainingUnits") == 500000000, "live balance still read");
        check(ZCodeGift.allowedModels(out).contains("glm-5.3-flash"), "gift model route remains available");
        check(balanceQuery.contains("app_version=" + ZCodeAuth.APP_VERSION),
                "balance asks as the app version: " + balanceQuery);
        check(touchQuery.startsWith("seq="), "feed request carries the client's seq: " + touchQuery);
        check(ZCodeAuth.APP_VERSION.equals(lastHeaders.get("x-zcode-app-version")),
                "feed carries the client version header: " + lastHeaders);
        String platform = lastHeaders.get("x-platform");
        check(platform != null && platform.startsWith("linux-"), "feed names a desktop platform: " + platform);
        String language = lastHeaders.get("x-client-language");
        check("zh-CN".equals(language) || "en-US".equals(language), "feed names one of ZCode's two languages: " + language);

        // 2. The seq counts up, one GET per ask.
        String first = touchQuery;
        int before = touches;
        summary();
        check(touches == before + 1 && !touchQuery.equals(first), "each refresh asks the feed once, with a new seq");

        // 3. Two deliveries keep both plans, and a repeated plan id is listed once.
        touch = feed(banner("plan-a", "ZCODE BUILD A", 0),
                banner("zcode-v3-start-plan-1030-wk", "ZCODE WEEKEND BUILD", now + 86400),
                banner("plan-a", "ZCODE BUILD A", 0));
        JSONArray many = summary().getJSONArray("claimable");
        check(many.length() == 2, "distinct plans only, order preserved: " + many);
        check("plan-a".equals(many.getJSONObject(0).optString("planId"))
                && "zcode-v3-start-plan-1030-wk".equals(many.getJSONObject(1).optString("planId")), "feed order kept");

        // 4. An answered feed with nothing to claim says nothing about the gift itself.
        for (String empty : new String[]{"{\"code\":0}", "{\"code\":0,\"data\":null}", "{\"code\":0,\"data\":{}}",
                "{\"code\":0,\"data\":{\"deliveries\":[]}}"}) {
            touch = empty;
            int reads = balances;
            JSONObject none = summary();
            check(none.optBoolean("ok") && none.optBoolean("hasGift") && none.optLong("remainingUnits") == 500000000,
                    "empty feed preserves live gift: " + none);
            check(none.getJSONArray("claimable").length() == 0 && balances == reads + 1, "still queries the actual balance");
        }

        // 5. A feed that does not read is unknown, never "nothing to claim" — and it is unknown
        //    about the claim list alone: the balance is a separate read and stays readable.
        for (String malformed : new String[]{
                "{\"code\":0,\"data\":{\"deliveries\":\"bad\"}}", "{\"code\":0,\"data\":[]}",
                "{}", "{\"code\":null}", "{\"msg\":\"error\"}", "not-json"}) {
            touch = malformed;
            status = 200;
            JSONObject unread = summary();
            check(unread.optBoolean("ok") && !unread.optBoolean("claimableKnown"),
                    "a feed that doesn't read leaves the claim list unknown: " + malformed + " -> " + unread);
            check(unread.optString("claimableError").contains("可领取计划"),
                    "and names that it is the claim list: " + unread);
            check(unread.getJSONArray("claimable").length() == 0
                    && unread.optBoolean("hasGift") && unread.optLong("remainingUnits") == 500000000,
                    "while the live balance still shows: " + unread);
        }
        touch = "{\"code\":0,\"data\":{\"deliveries\":\"bad\"}}";
        check(summary().optString("claimableError").contains("可领取计划"), "specific safe schema error");
        // a throttled feed is still only the claim list: no claim may leave from it, and the
        // balance is not held because claims are
        touch = "{\"code\":3002}";
        status = 429;
        JSONObject rate = summary();
        check(rate.optBoolean("ok") && !rate.optBoolean("claimableKnown") && !rate.optString("claimableError").isEmpty(),
                "a throttled feed leaves the claim list unknown: " + rate);
        check(!rate.has("status") && !rate.has("retryAfterMs"),
                "and it is not the balance that was limited: " + rate);
        status = 401;
        touch = "{\"code\":1001}";
        JSONObject expired = summary();
        check(expired.optBoolean("ok") && !expired.optBoolean("claimableKnown"),
                "an expired session on the feed leaves the claim list unknown: " + expired);
        status = 200;
        touch = feed(banner("zcode-v3-start-plan-1030-wk", "ZCODE WEEKEND BUILD", now + 86400));

        // 6. Balance failures stay failures.
        for (int code : new int[]{401, 429}) {
            balanceStatus = code;
            String saved = balance;
            balance = "{\"code\":1001}";
            JSONObject failure = summary();
            check(!failure.optBoolean("ok") && failure.optInt("status") == code, "balance HTTP failure retained");
            balance = saved;
        }
        balanceStatus = 200;
        networkFailure = true;
        unavailable("balance network failure");
        networkFailure = false;
        String liveBalance = balance;

        // 7. Only a live plan's own buckets count — the reference giftOf/startPlan rules.
        planLiveness(now, expires);

        // 8. No claim is posted before client/configs says the captcha is off.
        claimGate(now);

        // 9. Only an explicit success code is a claim.
        claimReplies(now);

        // 10. Through the real control transport: one feed read per cache window, failures
        //     never cached, and the claim outcomes mapped for the panel.
        balance = liveBalance;
        ZCodeGiftControl.setTestHooks(null, null);
        try {
            networkFailure = true;
            check(!ZCodeGiftControl.summary("cache-fixture", "device").optBoolean("complete"), "failed raw query is incomplete");
            networkFailure = false;
            int beforeTouches = touches;
            JSONObject result = ZCodeGiftControl.summary("cache-fixture", "device");
            check(result.optBoolean("complete") && result.optBoolean("hasGift"), "raw envelope through default control transport");
            check(ZCodeGiftControl.summary("cache-fixture", "device").optBoolean("complete") && touches == beforeTouches + 1,
                    "successful raw result cached");
        } finally { ZCodeGiftControl.setTestHooks(null, null); }
        controlOutcomes(now);
        reviewFindings(now);
        System.out.println("ZCodeGiftSummaryHttpTest PASS");
    }

    /**
     * The five defects an independent review found, each pinned so it cannot come back.
     *
     * <ol>
     *   <li>a balance with no {@code plans} key is unknown, not a complete zero entitlement;</li>
     *   <li>an unknown remainder is unknown (no total reported) and still neither spent nor
     *       withheld from routing — the two say the same thing instead of contradicting;</li>
     *   <li>a 1003 body under an HTTP failure is not "already claimed";</li>
     *   <li>only the banner's own buttons and background offer claims — a popup's claim action
     *       is never collected;</li>
     *   <li>a feed that fails leaves the balance readable instead of invalidating it.</li>
     * </ol>
     */
    static void reviewFindings(long now) throws Exception {
        long expires = now + 3600;

        // ---- 4. the whole delivery is searched, as the reference's touchPlans walks it -----
        // Its reason is written in the plugin: "a button moved inside the banner is still found".
        // Narrowing to banner.buttons lost a claim button the feed had moved, i.e. the account
        // silently keeps a gift plan it was offered. What decides whether a claim is sent is the
        // user's action and the captcha gate, not the scan face, so the scan follows the plugin.
        JSONObject bannerDelivery = banner("banner-plan", "ZCODE BANNER PLAN", now + 3600);
        JSONObject popupDelivery = new JSONObject().put("campaign_id", "popup-campaign")
                .put("resource_position", "popup")
                .put("popup", new JSONObject().put("buttons", new JSONArray().put(new JSONObject()
                        .put("text", new JSONObject().put("format", "").put("content", ""))
                        .put("action", new JSONObject().put("type", "claim_zcode_plan")
                                .put("args", new JSONObject().put("plan_id", "popup-plan")))))
                        .put("background", new JSONObject().put("args",
                                new JSONObject().put("zcode_plan", new JSONObject().put("plan_id", "popup-plan")
                                        .put("name", "ZCODE POPUP PLAN").put("ends_at", now + 3600)))));
        JSONObject successPopup = new JSONObject(bannerDelivery.toString());
        successPopup.getJSONObject("banner").put("success_popup", new JSONObject().put("buttons",
                new JSONArray().put(new JSONObject().put("action", new JSONObject().put("type", "claim_zcode_plan")
                        .put("args", new JSONObject().put("plan_id", "success-popup-plan"))))));
        touch = feed(bannerDelivery, popupDelivery, successPopup);
        balanceOf(new JSONArray().put(plan("grant", "active", 0, now + 86400)),
                new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        JSONArray found = summary().getJSONArray("claimable");
        java.util.List<String> idsFound = new java.util.ArrayList<String>();
        java.util.List<String> namesFound = new java.util.ArrayList<String>();
        for (int i = 0; i < found.length(); i++) {
            idsFound.add(found.getJSONObject(i).optString("planId"));
            namesFound.add(found.getJSONObject(i).optString("name"));
        }
        check(idsFound.equals(java.util.Arrays.asList("banner-plan", "popup-plan", "success-popup-plan")),
                "every claim action of every delivery is found, in feed order: " + idsFound);
        check("ZCODE BANNER PLAN".equals(namesFound.get(0)) && "ZCODE POPUP PLAN".equals(namesFound.get(1)),
                "a plan takes its own delivery's first zcode_plan name: " + namesFound);
        check("ZCODE BANNER PLAN".equals(namesFound.get(2)),
                "and a plan in the banner's own delivery takes that delivery's name: " + namesFound);
        // a repeated plan id across deliveries is listed once, as the reference's toClaim add does
        touch = feed(bannerDelivery, popupDelivery, bannerDelivery);
        JSONArray deduped = summary().getJSONArray("claimable");
        check(deduped.length() == 2, "a repeated plan id is listed once: " + deduped);
        // a claim action with no name anywhere in its delivery falls back to its own plan id
        JSONObject nameless = new JSONObject().put("campaign_id", "nameless")
                .put("banner", new JSONObject().put("buttons", new JSONArray().put(new JSONObject()
                        .put("action", new JSONObject().put("type", "claim_zcode_plan")
                                .put("args", new JSONObject().put("plan_id", "nameless-plan"))))));
        touch = feed(nameless);
        JSONObject fallback = summary().getJSONArray("claimable").getJSONObject(0);
        check("nameless-plan".equals(fallback.optString("planId")) && "nameless-plan".equals(fallback.optString("name")),
                "a plan with no name is named by its id, as the reference does: " + fallback);

        // the real fixture's shape still reads: buttons[] action + background.args.zcode_plan
        touch = realWeekendFeed(now);
        JSONArray real = summary().getJSONArray("claimable");
        check(real.length() == 1 && "zcode-v3-start-plan-1030-wk".equals(real.getJSONObject(0).optString("planId"))
                && "ZCODE WEEKEND BUILD".equals(real.getJSONObject(0).optString("name")),
                "the real weekend banner still reads: " + real);

        // ---- 5. a feed that fails does not invalidate the balance ------------------------
        balanceOf(new JSONArray().put(plan("grant", "active", 0, now + 86400)),
                new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        String goodBalance = balance;
        status = 500;
        touch = "";
        JSONObject feedDown = summary();
        check(feedDown.optBoolean("ok"), "a failed feed leaves the balance readable: " + feedDown);
        check(!feedDown.optBoolean("claimableKnown") && !feedDown.optString("claimableError").isEmpty(),
                "and the claim list is reported unknown, with why: " + feedDown);
        check(feedDown.getJSONArray("claimable").length() == 0 && feedDown.optBoolean("hasGift")
                && feedDown.optLong("remainingUnits") == 75, "the live balance still shows: " + feedDown);
        status = 200;
        touch = feed(banner("banner-plan", "ZCODE BANNER PLAN", now + 3600));
        balance = goodBalance;
        int claimsBefore = claims;
        // the control transport here is the real one: a feed that fails again, and a list that
        // cannot be read claims nothing (an unknown list is never an empty one)
        status = 500;
        touch = "";
        ZCodeGiftControl.invalidate("review-feed-down", "device");
        JSONObject unknownList = ZCodeGiftControl.claimAll("review-feed-down", "device");
        check(!unknownList.optBoolean("ok") && unknownList.getJSONArray("claimed").length() == 0,
                "an unknown claim list claims nothing: " + unknownList);
        check(claims == claimsBefore, "and posts no claim at all");
        status = 200;
        touch = feed(banner("banner-plan", "ZCODE BANNER PLAN", now + 3600));

        // ---- 3. 1003 under an HTTP failure, through the control layer --------------------
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha(null);
        balance = goodBalance;
        claimStatus = 500;
        claimBody = "{\"code\":1003,\"msg\":\"target already exists\"}";
        ZCodeGiftControl.invalidate("review-500", "device");
        JSONObject notDone = ZCodeGiftControl.claim("review-500", "device", "banner-plan");
        check(!notDone.optBoolean("ok") && !"done".equals(notDone.optString("outcome"))
                && !notDone.optBoolean("alreadyClaimed"),
                "a 1003 under HTTP 500 is not reported as already claimed: " + notDone);
        check(notDone.optInt("status") == 500 && notDone.optInt("businessCode") == 1003,
                "it is carried as unknown with what the reply said: " + notDone);
        claimStatus = 200;
        claimBody = "{\"code\":1003,\"msg\":\"target already exists\"}";
        JSONObject reallyDone = ZCodeGiftControl.claim("review-200", "device", "banner-plan");
        check(reallyDone.optBoolean("ok") && "done".equals(reallyDone.optString("outcome")),
                "a 1003 on a successful response is done: " + reallyDone);
        claimBody = "{\"code\":0,\"msg\":\"Plan claimed successfully.\"}";
    }

    /** The shipped weekend banner fixture, byte for byte: buttons[] action and background.args.zcode_plan. */
    static String realWeekendFeed(long now) throws Exception {
        JSONObject plan = new JSONObject().put("plan_id", "zcode-v3-start-plan-1030-wk")
                .put("name", "ZCODE WEEKEND BUILD").put("starts_at", now - 3600).put("ends_at", now + 86400);
        JSONObject claim = new JSONObject().put("text", new JSONObject().put("format", "").put("content", ""))
                .put("action", new JSONObject().put("type", "claim_zcode_plan")
                        .put("args", new JSONObject().put("plan_id", "zcode-v3-start-plan-1030-wk")));
        JSONObject close = new JSONObject().put("text", new JSONObject().put("format", "").put("content", ""))
                .put("action", new JSONObject().put("type", "close").put("args", JSONObject.NULL));
        JSONObject background = new JSONObject().put("type", "bundle")
                .put("bundle", new JSONObject().put("entry", "index.html"))
                .put("args", new JSONObject().put("zcode_plan", plan));
        JSONObject successPopup = new JSONObject().put("buttons", new JSONArray().put(new JSONObject()
                .put("action", new JSONObject().put("type", "navigate")
                        .put("args", new JSONObject().put("page", "settings")))));
        JSONObject banner = new JSONObject().put("buttons", new JSONArray().put(close).put(claim))
                .put("background", background).put("success_popup", successPopup);
        JSONObject delivery = new JSONObject().put("campaign_id", "2101141259813175296")
                .put("resource_position", "banner").put("priority", 50).put("banner", banner);
        return new JSONObject().put("code", 0).put("msg", "")
                .put("data", new JSONObject().put("server_time", now).put("language", "zh-CN")
                        .put("deliveries", new JSONArray().put(delivery))).toString();
    }

    static void planLiveness(long now, long expires) throws Exception {
        JSONArray live = new JSONArray().put(plan("grant", "active", 0, now + 86400));
        // a live plan with a live bucket: the model counts and the balance is readable
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        JSONObject ok = summary();
        check(ok.optBoolean("ok") && ok.optBoolean("hasGift") && ok.getJSONArray("buckets").length() == 1, "live plan serves: " + ok);
        JSONObject cell = ok.getJSONArray("buckets").getJSONObject(0);
        check(cell.optLong("remaining") == 75 && cell.optLong("total") == 100 && cell.optLong("used") == 25
                && !cell.optBoolean("spent"), "live bucket reads its numbers: " + cell);
        check(cell.getJSONArray("models").length() == 1 && "glm-5.3-flash".equals(cell.getJSONArray("models").optString(0)),
                "its model comes from capabilities: " + cell);
        check(ok.getJSONArray("models").length() == 1, "summary names the live models: " + ok);

        // a plan that ended, one that never started, one still pending: nothing of theirs serves
        JSONArray notLive = new JSONArray().put(plan("grant", "active", 0, now - 1))
                .put(plan("grant", "active", now + 600, now + 86400))
                .put(plan("grant", "pending", 0, now + 86400));
        for (int i = 0; i < notLive.length(); i++) {
            balanceOf(new JSONArray().put(notLive.getJSONObject(i)),
                    new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
            JSONObject dead = summary();
            check(dead.optBoolean("ok") && dead.getJSONArray("buckets").length() == 0 && !dead.optBoolean("hasGift"),
                    "a plan that is not live serves nothing: " + dead);
            check(ZCodeGift.allowedModels(dead).isEmpty(), "and routes no model to the gift");
        }

        // a bucket past its own end is over, even on a live plan
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, now - 1)));
        check(summary().getJSONArray("buckets").length() == 0, "an expired bucket is over");

        // a bucket no plan owns is not counted (reference: owner must be found among the plans)
        balanceOf(live, new JSONArray().put(new JSONObject().put("plan_id", "other").put("user_plan_id", "up-other")
                .put("show_name", "Orphan").put("capabilities", new JSONArray().put("model:glm-5.3-flash"))
                .put("remaining_units", 75).put("total_units", 100).put("used_units", 25).put("expires_at", expires)));
        check(summary().getJSONArray("buckets").length() == 0, "an orphan bucket serves nothing");

        // A balance that answers no plan at all did not answer the question the buckets hang on:
        // that is unknown, not a complete reading of zero entitlement (the reference's giftOf
        // then has no live plan and says so with NO_START, never with a zero card).
        balanceOf(null, new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        JSONObject noPlans = summary();
        check(!noPlans.optBoolean("ok"), "a balance with no plans key is unknown, not a zero: " + noPlans);
        check(noPlans.optString("error").contains("计划"), "and says which part did not read: " + noPlans);
        check(!noPlans.has("buckets") && !noPlans.optBoolean("hasGift"), "it reports no allowance and claims none");
        check(!ZCodeGiftControl.summary("no-plans-fixture", "device").optBoolean("complete"),
                "and the control layer never caches it as complete");

        // an explicit empty plan list is an answer: no live plan, and it is named as such
        balanceOf(new JSONArray(), new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        JSONObject noLive = summary();
        check(noLive.optBoolean("ok") && noLive.optBoolean("noLivePlan") && noLive.optBoolean("plansKnown"),
                "an empty plan list is a definite no-live-plan: " + noLive);
        check(noLive.getJSONArray("buckets").length() == 0 && !noLive.optBoolean("hasGift"),
                "and the unowned bucket still serves nothing: " + noLive);

        // spent: the bucket keeps its model names but serves none of them
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", 0, 100, 100, expires)));
        JSONObject spent = summary();
        JSONObject spentCell = spent.getJSONArray("buckets").getJSONObject(0);
        check(spentCell.optBoolean("spent") && spentCell.optLong("remaining") == 0, "a spent bucket is spent: " + spentCell);
        check(spentCell.getJSONArray("models").length() == 1, "a spent bucket still names its model: " + spentCell);
        check(spent.getJSONArray("models").length() == 0 && spent.getJSONArray("allModels").length() == 1,
                "but it serves none of them: " + spent);
        check(ZCodeGift.allowedModels(spent).isEmpty(), "so the gift route is closed for it");
        // A fraction is not zero: the reference's giftSpent is `num(x.remaining_units) <= 0`, so
        // 0.4 is a live bucket. Truncating it to a long would call it spent and push the account
        // to its paid plan for a model it still holds.
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", 0.4, 100, 99.6, expires)));
        JSONObject fraction = summary();
        JSONObject fractionCell = fraction.getJSONArray("buckets").getJSONObject(0);
        check(!fractionCell.optBoolean("spent"), "0.4 left is not spent: " + fractionCell);
        check(Math.abs(fractionCell.getDouble("remaining") - 0.4) < 1e-9,
                "and it is reported as 0.4, neither truncated to 0 nor rounded up: " + fractionCell);
        check(Math.abs(fraction.getDouble("remainingUnits") - 0.4) < 1e-9,
                "the total carries the fraction too: " + fraction);
        check(fraction.optBoolean("remainingKnown") && !fractionCell.optBoolean("remainderUnknown"),
                "a fraction is a known remainder: " + fractionCell);
        check(ZCodeGift.allowedModels(fraction).contains("glm-5.3-flash"), "so the model still routes");
        // a fraction written as a string reads the same way
        balanceOf(live, new JSONArray().put(bucketStrings("grant", "glm-5.3-flash", null, "1.5", "1.1", expires)));
        JSONObject fractionStrings = summary();
        JSONObject stringCell = fractionStrings.getJSONArray("buckets").getJSONObject(0);
        check(!stringCell.optBoolean("spent") && Math.abs(stringCell.getDouble("remaining") - 0.4) < 1e-9,
                "used/total as strings keep their fraction: " + stringCell);

        // a negative count is nothing left, as the reference's giftSpent reads it (left <= 0)
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", -5, 100, 100, expires)));
        JSONObject negative = summary();
        JSONObject negativeCell = negative.getJSONArray("buckets").getJSONObject(0);
        check(negative.optBoolean("ok") && negativeCell.optBoolean("spent") && negativeCell.optLong("remaining") == 0,
                "a negative remaining is spent, not a crash: " + negativeCell);

        // an older balance without remaining_units still reads through used/total
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", -1, 1000, 1000, expires)));
        JSONObject oldSpent = summary();
        JSONObject oldSpentCell = oldSpent.getJSONArray("buckets").getJSONObject(0);
        check(oldSpentCell.optBoolean("spent") && oldSpentCell.optLong("remaining") == 0,
                "used == total is spent without remaining_units: " + oldSpentCell);
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", -1, 1000, 25, expires)));
        JSONObject oldLive = summary();
        JSONObject oldLiveCell = oldLive.getJSONArray("buckets").getJSONObject(0);
        check(!oldLiveCell.optBoolean("spent") && oldLiveCell.optLong("remaining") == 975
                && oldLive.optLong("remainingUnits") == 975, "used < total leaves the rest: " + oldLiveCell);
        check(ZCodeGift.allowedModels(oldLive).contains("glm-5.3-flash"), "and the model still routes");

        // a bucket whose numbers cannot be completed is unknown, not zero
        balanceOf(live, new JSONArray().put(bucket("grant", "glm-5.3-flash", -1, -1, 5, expires)));
        JSONObject partial = summary();
        JSONObject partialCell = partial.getJSONArray("buckets").getJSONObject(0);
        check(partial.optBoolean("ok") && partial.getJSONArray("buckets").length() == 1
                && partialCell.optLong("remaining") == -1 && partialCell.optBoolean("remainderUnknown"),
                "one side of the numbers is an unknown remainder, not 0: " + partial);
        check(!partial.has("remainingUnits") && !partial.optBoolean("remainingKnown"),
                "an unknown remainder is no measured total, so no total is reported: " + partial);
        check(partialCell.optBoolean("spent") == false && ZCodeGift.allowedModels(partial).contains("glm-5.3-flash"),
                "and it is not spent, so the model still routes (as the reference's giftSpent reads it)");
        check(partial.optBoolean("hasGift") && partial.getJSONArray("models").length() == 1,
                "the account can serve, so hasGift follows the models, not the unknown count: " + partial);
        check(ZCodeGiftControl.summary("partial-fixture", "device").optBoolean("complete"),
                "an explicitly unknown remainder is still a complete read");

        // a bucket with no numbers at all is not an amount of anything
        JSONArray emptyBucket = new JSONArray().put(new JSONObject().put("plan_id", "grant").put("user_plan_id", "up-grant")
                .put("show_name", "No numbers").put("capabilities", new JSONArray().put("model:glm-5.3-flash"))
                .put("expires_at", expires));
        balanceOf(live, emptyBucket);
        check(summary().getJSONArray("buckets").length() == 0, "a bucket with no counts is not a balance");

        // capability spellings: model: is a model, tool: is not, a bare name is
        balanceOf(live, new JSONArray().put(new JSONObject().put("plan_id", "grant").put("user_plan_id", "up-grant")
                .put("show_name", "Mixed").put("capabilities", new JSONArray().put("model:glm-5.3-flash").put("tool:web-search").put("GLM-5.2"))
                .put("remaining_units", 10).put("total_units", 10).put("used_units", 0).put("expires_at", expires)));
        JSONObject mixed = summary();
        JSONArray mixedModels = mixed.getJSONArray("buckets").getJSONObject(0).getJSONArray("models");
        check(mixedModels.length() == 2 && "glm-5.3-flash".equals(mixedModels.optString(0))
                && "GLM-5.2".equals(mixedModels.optString(1)), "model: kept, tool: dropped, bare name kept: " + mixedModels);

        // capabilities missing is an empty model list, not a void read (reference bucketModels:
        // Array.isArray(x.capabilities) ? x.capabilities : []) — the bucket still shows its counts,
        // it just serves no model, so nothing is routed to the gift for it
        balanceOf(live, new JSONArray().put(new JSONObject().put("plan_id", "grant").put("user_plan_id", "up-grant")
                .put("show_name", "No caps").put("remaining_units", 5).put("total_units", 5).put("used_units", 0)
                .put("expires_at", expires)));
        JSONObject noCaps = summary();
        check(noCaps.optBoolean("ok"), "a bucket without capabilities is not a void read: " + noCaps);
        check(noCaps.getJSONArray("buckets").length() == 1, "and it still shows: " + noCaps);
        check(noCaps.getJSONArray("buckets").getJSONObject(0).getJSONArray("models").length() == 0,
                "with no model to serve: " + noCaps);
        check(noCaps.getJSONArray("models").length() == 0 && !noCaps.optBoolean("hasGift"),
                "so nothing routes to the gift, and no model is invented: " + noCaps);
        check(noCaps.getJSONArray("buckets").getJSONObject(0).optLong("remaining") == 5,
                "the counts are still told: " + noCaps);
        check(ZCodeGiftControl.summary("no-caps-fixture", "device").optBoolean("complete"),
                "and a bucket with no capabilities is still a complete read");
        // capabilities that is not an array at all is the same empty list
        balanceOf(live, new JSONArray().put(new JSONObject().put("plan_id", "grant").put("user_plan_id", "up-grant")
                .put("show_name", "Bad caps").put("capabilities", "model:glm-5.3-flash")
                .put("remaining_units", 5).put("expires_at", expires)));
        JSONObject badCaps = summary();
        check(badCaps.optBoolean("ok") && badCaps.getJSONArray("buckets").length() == 1
                && badCaps.getJSONArray("models").length() == 0,
                "a non-array capabilities reads as the same empty list: " + badCaps);
        balanceOf(new JSONArray(), new JSONArray());
        check(summary().optBoolean("ok") && !summary().optBoolean("hasGift"), "explicit empty balance is valid");
    }

    static void claimGate(long now) throws Exception {
        long expires = now + 3600;
        balanceOf(new JSONArray().put(plan("grant", "active", 0, now + 86400)),
                new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        claimStatus = 200;
        claimBody = "{\"code\":0,\"msg\":\"Plan claimed successfully.\"}";

        // captcha on: nothing is posted, and the answer says where to claim it
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha("{\"enabled\":true,\"prefix\":\"no8xfe\",\"region\":\"cn\",\"sceneId\":\"11xygtvd\"}");
        int before = claims;
        boolean gated = false;
        try { ZCodeAuth.giftClaim("gate-one", "device", "zcode-v3-start-plan-1030-wk"); }
        catch (ZCodeAuth.CaptchaRequiredException expected) { gated = true; }
        check(gated && claims == before, "captcha on: the claim is not posted");

        // a captcha value that does not read is unknown, not "off"
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha("\"on\"");
        boolean unknown = false;
        try { ZCodeAuth.giftClaim("gate-two", "device", "zcode-v3-start-plan-1030-wk"); }
        catch (ZCodeAuth.ClaimUnknownException expected) { unknown = true; }
        check(unknown && claims == before, "captcha unreadable: the claim is not posted");

        // a configs read that fails is unknown too
        ZCodeAuth.resetGiftClientConfigsCache();
        configsStatus = 500;
        unknown = false;
        try { ZCodeAuth.giftClaim("gate-three", "device", "zcode-v3-start-plan-1030-wk"); }
        catch (ZCodeAuth.ClaimUnknownException expected) { unknown = true; }
        check(unknown && claims == before, "configs HTTP failure: the claim is not posted");
        configsStatus = 200;
        ZCodeAuth.resetGiftClientConfigsCache();
        configsNetworkFailure = true;
        unknown = false;
        try { ZCodeAuth.giftClaim("gate-four", "device", "zcode-v3-start-plan-1030-wk"); }
        catch (ZCodeAuth.ClaimUnknownException expected) { unknown = true; }
        check(unknown && claims == before, "configs network failure: the claim is not posted");
        configsNetworkFailure = false;

        // captcha switched off (or absent): the claim goes out as before
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha("{\"enabled\":false}");
        int reads = configReads;
        JSONObject claimed = ZCodeAuth.giftClaim("gate-five", "device", "zcode-v3-start-plan-1030-wk");
        check(claimed != null && claims == before + 1, "captcha off: the claim is posted");
        check(configReads == reads + 1, "the config is read once for it");
        check(claimBodySent.contains("\"plan_id\":\"zcode-v3-start-plan-1030-wk\""), "claim body names the plan: " + claimBodySent);
        check(ZCodeAuth.APP_VERSION.equals(lastHeaders.get("x-zcode-app-version")),
                "claim carries the client version header: " + lastHeaders);
        check(lastHeaders.get("x-platform") != null && lastHeaders.get("x-client-language") != null,
                "claim carries platform and language too");
        check(configsQuery.contains("app_version=" + ZCodeAuth.APP_VERSION) && configsQuery.contains("platform="),
                "the config is asked as the client version and platform: " + configsQuery);

        // a read that came through is kept for ten minutes, as upstream keeps it
        configsStatus = 500;
        JSONObject cached = ZCodeAuth.giftClaim("gate-six", "device", "zcode-v3-start-plan-1030-wk");
        check(cached != null && claims == before + 2, "the config read is cached, so a second claim still goes");
        configsStatus = 200;
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha(null);
        JSONObject absent = ZCodeAuth.giftClaim("gate-seven", "device", "zcode-v3-start-plan-1030-wk");
        check(absent != null && claims == before + 3, "no captcha in the configs is no check");
    }

    static void claimReplies(long now) throws Exception {
        claimStatus = 200;
        claimBody = "{\"code\":1003,\"msg\":\"target already exists\"}";
        int code = replyCode();
        check(code == 1003, "a claimed-before reply is read as 1003, not as success: " + code);

        // A claim answer only counts from a response that succeeded: "already claimed" is a
        // claim of fact about a past claim, and the reference only takes a positive claim with
        // res.ok (`code === 0 && res.ok`). Under HTTP 500 a body is an answer from a gateway in
        // trouble, so it is unknown — and unknown neither claims nor reports a claim.
        claimStatus = 500;
        claimBody = "{\"code\":1003,\"msg\":\"target already exists\"}";
        int[] seen = new int[2];
        try { ZCodeAuth.giftClaim("reply-500-1003", "device", "plan"); }
        catch (ZCodeAuth.ServiceException e) { seen[0] = e.status; seen[1] = e.businessCode; }
        check(seen[0] == 500 && seen[1] == 1003, "the 1003 under HTTP 500 is carried, not swallowed: " + seen[0] + "/" + seen[1]);

        // the blocking answers are trusted from any response: trusting them can only stop a
        // claim, which is what the reference does with a 400 + 3007 / 400 + 1004 reply
        claimStatus = 400;
        claimBody = "{\"code\":3007,\"msg\":\"captcha verify failed\"}";
        check(replyCode() == 3007, "3007 is read from its 400 as captcha");
        claimBody = "{\"code\":1004,\"msg\":\"your account is not eligible for this plan yet\"}";
        check(replyCode() == 1004, "1004 is read from its 400 as ineligible");

        claimStatus = 400;
        claimBody = "{\"code\":1004,\"msg\":\"your account is not eligible for this plan yet\"}";
        check(replyCode() == 1004, "1004 is read as 1004");

        claimBody = "{\"code\":3007,\"msg\":\"captcha verify failed\"}";
        check(replyCode() == 3007, "3007 is read as 3007");

        claimStatus = 429;
        claimBody = "";
        int statusSeen = -1;
        long wait = -1;
        try { ZCodeAuth.giftClaim("reply-429", "device", "plan"); }
        catch (ZCodeAuth.ServiceException e) { statusSeen = e.status; wait = e.retryAfterMs; }
        check(statusSeen == 429 && wait == 47000, "429 carries its Retry-After");

        // an answer that does not read is never a claim
        for (String[] answer : new String[][]{
                {"200", "{}"}, {"200", "not json at all"}, {"500", "{\"code\":0}"},
                {"502", "<html>bad gateway</html>"}, {"200", "{\"code\":1001,\"msg\":\"system busy\"}"}}) {
            claimStatus = Integer.parseInt(answer[0]);
            claimBody = answer[1];
            boolean refused = false;
            try { ZCodeAuth.giftClaim("reply-" + answer[0] + answer[1].length(), "device", "plan"); }
            catch (Exception expected) { refused = true; }
            check(refused, "HTTP " + answer[0] + " " + answer[1] + " is not a claim");
        }
        claimStatus = 200;
        claimBody = "{\"code\":0,\"msg\":\"Plan claimed successfully.\"}";
    }

    static int replyCode() throws Exception {
        try { ZCodeAuth.giftClaim("reply-" + claimBody.length() + claimStatus, "device", "plan"); }
        catch (ZCodeAuth.ServiceException e) { return e.businessCode; }
        return 0;
    }

    static void controlOutcomes(long now) throws Exception {
        long expires = now + 3600;
        // nothing in the feed: no claim, and neither the config nor the claim endpoint is asked
        // (the reference reads client/configs only once a plan is there to claim)
        touch = feed();
        int cfgBefore = configReads, claimsBefore = claims;
        ZCodeGiftControl.setTestHooks(null, null);
        JSONObject none = ZCodeGiftControl.claimAll("control-none", "device");
        check(none.optBoolean("ok") && none.getJSONArray("claimed").length() == 0,
                "nothing to claim claims nothing: " + none);
        check(configReads == cfgBefore && claims == claimsBefore,
                "and asks neither the captcha config nor the claim endpoint");
        touch = feed(banner("zcode-v3-start-plan-1030-wk", "ZCODE WEEKEND BUILD", now + 86400));
        balanceOf(new JSONArray().put(plan("grant", "active", 0, now + 86400)),
                new JSONArray().put(bucket("grant", "glm-5.3-flash", 75, 100, 25, expires)));
        ZCodeAuth.resetGiftClientConfigsCache();
        configs = configsWithCaptcha(null);
        ZCodeGiftControl.setTestHooks(null, null);
        try {
            claimStatus = 200;
            claimBody = "{\"code\":1003,\"msg\":\"target already exists\"}";
            JSONObject done = ZCodeGiftControl.claim("control-done", "device", "zcode-v3-start-plan-1030-wk");
            check(done.optBoolean("ok") && "done".equals(done.optString("outcome")),
                    "through the panel: a 1003 is done, not a failure: " + done);
            claimBody = "{\"code\":3007,\"msg\":\"captcha verify failed\"}";
            claimStatus = 400;
            JSONObject captcha = ZCodeGiftControl.claim("control-captcha", "device", "zcode-v3-start-plan-1030-wk");
            check(!captcha.optBoolean("ok") && "captcha".equals(captcha.optString("outcome")),
                    "through the panel: a 3007 is captcha: " + captcha);
            claimStatus = 500;
            claimBody = "";
            JSONObject failed = ZCodeGiftControl.claim("control-failed", "device", "zcode-v3-start-plan-1030-wk");
            check(!failed.optBoolean("ok") && "failed".equals(failed.optString("outcome")) && !failed.has("claimed"),
                    "through the panel: a 500 is failed, never claimed: " + failed);
            // the captcha gate holds for the whole panel path, before any claim leaves
            claimStatus = 200;
            claimBody = "{\"code\":0}";
            ZCodeAuth.resetGiftClientConfigsCache();
            configs = configsWithCaptcha("{\"enabled\":true}");
            int before = claims;
            JSONObject gatedBatch = ZCodeGiftControl.claimAll("control-gate", "device");
            check(!gatedBatch.optBoolean("ok") && gatedBatch.getJSONArray("captcha").length() == 1 && claims == before,
                    "through the panel: captcha on posts nothing: " + gatedBatch);
        } finally { ZCodeGiftControl.setTestHooks(null, null); }
    }
}
