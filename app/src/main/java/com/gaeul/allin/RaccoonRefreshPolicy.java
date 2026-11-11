package com.gaeul.allin;

import org.json.JSONObject;

/** Pure Raccoon refresh decisions and token-response parsing for gateway wiring. */
public final class RaccoonRefreshPolicy {
    /**
     * How long before expiry a request-time check renews the browser sign-in: five minutes.
     *
     * <p>Anchored to the reference plugin {@code @magpie-community/opencode-raccoon-auth 0.1.8}
     * ({@code source/index.mjs}). The plugin carries two distinct numbers: {@code const LEAD_MS =
     * 5 * 60 * 1000} (line 24), published as {@code auth.refreshLead} (line 1080) so its host
     * renews the sign-in five minutes before the token ends; and {@code const EARLY_MS = 60 * 1000}
     * (line 23), its own in-process {@code isStale} margin (line 296). A plugin instance never
     * decides a renewal on that one-minute margin alone - its host drives {@code auth.refresh} at
     * the lead - so the host lead is the behaviour to match. All-in is its own host: {@code
     * GatewayServer.ensureRaccoonToken} is the request-time pre-flight before forwarding, which
     * plays that same role.
     *
     * <p>{@code RaccoonRefreshService} also passes this value as {@code
     * AccountTokenCoordinator.ensure}'s margin, so the coordination re-check stays exactly as
     * permissive as the decision above and can never silently swallow a refresh that is already
     * due.
     */
    public static final long EARLY_REFRESH_MILLIS = 5 * 60 * 1000L;
    private RaccoonRefreshPolicy() {}

    public static final class Credentials {
        public final String access, refresh;
        public final long expiresAtMillis;
        public final boolean desktop;
        public Credentials(String access, String refresh, long expiresAtMillis, boolean desktop) {
            this.access = clean(access); this.refresh = clean(refresh);
            this.expiresAtMillis = Math.max(0, expiresAtMillis); this.desktop = desktop;
        }
        private static String clean(String value) { return value == null ? "" : value.trim().replaceFirst("(?i)^Bearer\\s+", "").trim(); }
    }

    public static boolean shouldRefresh(Credentials c, long nowMillis) {
        return c != null && !c.desktop && !c.refresh.isEmpty() && c.expiresAtMillis > 0
                && nowMillis >= c.expiresAtMillis - EARLY_REFRESH_MILLIS;
    }

    /** One replay is permitted only for a rejected 401 and non-desktop credentials. */
    public static boolean mayRetry401(Credentials c, int status, int attempts) {
        return c != null && !c.desktop && !c.refresh.isEmpty() && status == 401 && attempts == 0;
    }

    /** Validate refresh response and retain the old refresh token if rotation is omitted. */
    public static Credentials parseRefresh(JSONObject response, Credentials old, long expiresAtMillis) throws RaccoonAccountService.Failure {
        if (old == null || old.desktop) throw new RaccoonAccountService.Failure("Raccoon refresh not allowed for desktop credentials", true);
        JSONObject data = response == null ? null : response.optJSONObject("data");
        String access = data == null ? "" : data.optString("access_token", "").trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
        if (access.isEmpty()) throw new RaccoonAccountService.Failure("Raccoon refresh returned no access token", true);
        String refresh = data.optString("refresh_token", "").trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
        if (refresh.isEmpty()) refresh = old.refresh;
        return new Credentials(access, refresh, expiresAtMillis, false);
    }
}
