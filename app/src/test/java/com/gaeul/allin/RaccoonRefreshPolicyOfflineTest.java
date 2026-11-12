package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

public final class RaccoonRefreshPolicyOfflineTest {
    static void ok(boolean value) { if (!value) throw new AssertionError(); }

    static RaccoonRefreshPolicy.Credentials browser(String access, String refresh, long expiresAtMillis) {
        return new RaccoonRefreshPolicy.Credentials(access, refresh, expiresAtMillis, false);
    }

    /**
     * The renewal lead is the plugin's host lead (source/index.mjs:24 LEAD_MS), not its in-process
     * isStale margin (source/index.mjs:23 EARLY_MS = 60 s). Pinned so the two cannot drift back.
     */
    static void leadMatchesPluginRefreshLead() {
        ok(RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS == 5 * 60 * 1000L);
        long expires = 1_000_000L;
        // 90 s left: the plugin's own isStale(60 s) would call this fresh, but a plugin host renews
        // it because refreshLead is five minutes. All-in is the host here, so it must renew.
        ok(RaccoonRefreshPolicy.shouldRefresh(browser("a", "r", expires), expires - 90_000L));
        // 6 min left: outside the host lead, still untouched - the lead is not "always refresh".
        ok(!RaccoonRefreshPolicy.shouldRefresh(browser("a", "r", expires), expires - 360_000L));
    }

    static void shouldRefreshBoundary() {
        long expires = 1_000_000L;
        long threshold = expires - RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS;
        ok(threshold == 700_000L);
        RaccoonRefreshPolicy.Credentials c = browser("Bearer a", "r1", expires);
        ok(!RaccoonRefreshPolicy.shouldRefresh(c, threshold - 1));
        ok(RaccoonRefreshPolicy.shouldRefresh(c, threshold));
        ok(RaccoonRefreshPolicy.shouldRefresh(c, expires));
        ok(!RaccoonRefreshPolicy.shouldRefresh(browser("a", "r", expires), Long.MIN_VALUE + 1));
        // Desktop and access-only credentials stay out of refresh regardless of the margin.
        ok(!RaccoonRefreshPolicy.shouldRefresh(new RaccoonRefreshPolicy.Credentials("a", "r", expires, true), Long.MAX_VALUE));
        ok(!RaccoonRefreshPolicy.shouldRefresh(browser("a", "", expires), Long.MAX_VALUE));
        ok(!RaccoonRefreshPolicy.shouldRefresh(browser("a", "r", 0), Long.MAX_VALUE));
        ok(!RaccoonRefreshPolicy.shouldRefresh(null, Long.MAX_VALUE));
    }

    static void mayRetry401StaysBounded() {
        RaccoonRefreshPolicy.Credentials c = browser("a", "r1", 1_000_000L);
        ok(RaccoonRefreshPolicy.mayRetry401(c, 401, 0));
        ok(!RaccoonRefreshPolicy.mayRetry401(c, 401, 1));
        ok(!RaccoonRefreshPolicy.mayRetry401(c, 500, 0));
        ok(!RaccoonRefreshPolicy.mayRetry401(new RaccoonRefreshPolicy.Credentials("a", "r", 0, true), 401, 0));
        ok(!RaccoonRefreshPolicy.mayRetry401(browser("a", "", 0), 401, 0));
    }

    static void parseRefreshKeepsRotationContract() throws Exception {
        RaccoonRefreshPolicy.Credentials c = browser("Bearer a", "r1", 1_000_000L);
        RaccoonRefreshPolicy.Credentials next = RaccoonRefreshPolicy.parseRefresh(
                new JSONObject("{\"data\":{\"access_token\":\"Bearer a2\"}}"), c, 3_000_000L);
        ok(next.access.equals("a2") && next.refresh.equals("r1") && next.expiresAtMillis == 3_000_000L);
        next = RaccoonRefreshPolicy.parseRefresh(
                new JSONObject("{\"data\":{\"access_token\":\"a3\",\"refresh_token\":\"r3\"}}"), c, 4_000_000L);
        ok(next.access.equals("a3") && next.refresh.equals("r3"));
        try { RaccoonRefreshPolicy.parseRefresh(new JSONObject("{}"), c, 0); throw new AssertionError(); }
        catch (RaccoonAccountService.Failure expected) { ok(expected.expired); }
        try { RaccoonRefreshPolicy.parseRefresh(new JSONObject("{\"data\":{\"access_token\":\"x\"}}"),
                new RaccoonRefreshPolicy.Credentials("x", "", 0, true), 0); throw new AssertionError(); }
        catch (RaccoonAccountService.Failure expected) { ok(expected.expired); }
    }

    /** In-memory single-account store: no files, no network, no provider configuration. */
    static final class Store implements AccountTokenCoordinator.Store {
        final String id;
        JSONObject value;
        Store(String id, JSONObject value) throws Exception {
            this.id = id;
            this.value = new JSONObject(value.toString());
        }
        public JSONObject read(String provider, String accountId) throws Exception {
            if (!RaccoonRefreshService.PROVIDER.equals(provider) || !id.equals(accountId)) return null;
            return new JSONObject(value.toString());
        }
        public JSONObject commit(String provider, String accountId, JSONObject expected,
                                 JSONObject candidate) throws Exception {
            if (!RaccoonRefreshService.PROVIDER.equals(provider) || !id.equals(accountId)
                    || !value.toString().equals(expected.toString()))
                throw new IllegalStateException("account CAS conflict");
            value = new JSONObject(candidate.toString());
            return new JSONObject(value.toString());
        }
    }

    static JSONObject account(String id, String access, String refresh, long expires) throws Exception {
        return new JSONObject().put("id", id).put("enabled", true).put("source", "browser")
                .put("type", "oauth").put("access", access).put("token", access)
                .put("refresh", refresh).put("expires", expires);
    }

    /**
     * The pre-flight decision and the coordinator's re-check margin are the same constant, so a
     * token inside the lead must reach the refresher instead of being reported "still fresh".
     */
    static void coordinatorMarginAgreesWithLead() throws Exception {
        long now = System.currentTimeMillis();
        RaccoonRefreshPolicy.Credentials due = browser("old", "r1", now + 4 * 60 * 1000L);
        ok(RaccoonRefreshPolicy.shouldRefresh(due, now));
        Store dueStore = new Store("due", account("due", "old", "r1", due.expiresAtMillis));
        int[] refreshes = {0};
        JSONObject out = new AccountTokenCoordinator().ensure(RaccoonRefreshService.PROVIDER, "due",
                RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS, null, dueStore, (snapshot, force) -> {
                    refreshes[0]++;
                    return new JSONObject(snapshot.toString())
                            .put("access", "new").put("expires", now + 3_600_000L);
                });
        ok(refreshes[0] == 1 && out.optString("access").equals("new"));
        ok(dueStore.value.optString("access").equals("new"));

        RaccoonRefreshPolicy.Credentials fresh = browser("old", "r1", now + 6 * 60 * 1000L);
        ok(!RaccoonRefreshPolicy.shouldRefresh(fresh, now));
        Store freshStore = new Store("fresh", account("fresh", "old", "r1", fresh.expiresAtMillis));
        int[] untouched = {0};
        JSONObject kept = new AccountTokenCoordinator().ensure(RaccoonRefreshService.PROVIDER, "fresh",
                RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS, null, freshStore, (snapshot, force) -> {
                    untouched[0]++;
                    return snapshot;
                });
        ok(untouched[0] == 0 && kept.optString("access").equals("old"));
    }

    public static void main(String[] args) throws Exception {
        leadMatchesPluginRefreshLead();
        shouldRefreshBoundary();
        mayRetry401StaysBounded();
        parseRefreshKeepsRotationContract();
        coordinatorMarginAgreesWithLead();
        System.out.println("RaccoonRefreshPolicyOfflineTest passed");
    }
}
