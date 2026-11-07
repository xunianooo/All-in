package com.gaeul.allin;

import org.json.JSONObject;

/** Coordinates refreshes for accounts sharing this instance. No persistence is performed here. */
public final class AccountTokenCoordinator {
    /** Implementations must coordinate their CAS with every writer of the underlying account. */
    public interface Store {
        /** Returns a deep snapshot, or null if the account is absent or its provider is disabled. */
        JSONObject read(String provider, String accountId) throws Exception;
        /**
         * Atomically verify the provider and account are still enabled and the complete current
         * account equals expected, then persist candidate before publishing it. Throw on conflict
         * or persistence failure; return a deep snapshot of the committed account on success.
         * Implementations must detect deletion/replacement even if account IDs are reused (e.g.
         * compare a generation as well as the complete expected JSON). Never mutate either input.
         */
        JSONObject commit(String provider, String accountId, JSONObject expected, JSONObject candidate) throws Exception;
    }

    public interface Refresher {
        /** May mutate snapshot. Return the candidate, or null on refresh failure. */
        JSONObject refresh(JSONObject snapshot, boolean force) throws Exception;
    }

    private final Object[] stripes = new Object[64];

    public AccountTokenCoordinator() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Object();
    }

    private Object stripe(String provider, String accountId) {
        int hash = 31 * provider.hashCode() + accountId.hashCode();
        return stripes[(hash ^ (hash >>> 16)) & (stripes.length - 1)];
    }

    private static JSONObject copy(JSONObject object) throws Exception {
        return object == null ? null : new JSONObject(object.toString());
    }

    private static String access(JSONObject account) {
        String token = account.optString("access", "");
        return token.isEmpty() ? account.optString("token", "") : token;
    }

    /**
     * Returns the current/committed snapshot, or null when absent, disabled, or refresh failed.
     * failedAccess != null means recovery from a 401 and forces refresh unless a different usable
     * token has already been committed. expires is an epoch timestamp in milliseconds; absent or
     * nonpositive expiry preserves an existing token. Exceptions from store/refresher propagate.
     */
    public JSONObject ensure(String provider, String accountId, long marginMs, String failedAccess,
                             Store store, Refresher refresher) throws Exception {
        if (provider == null || accountId == null || store == null || refresher == null)
            throw new IllegalArgumentException("provider, accountId, store and refresher are required");
        if (marginMs < 0) throw new IllegalArgumentException("negative margin");
        synchronized (stripe(provider, accountId)) {
            JSONObject current = store.read(provider, accountId);
            if (current == null || !current.optBoolean("enabled", true)) return null;
            JSONObject expected = copy(current);
            String token = access(expected);
            long expires = expected.optLong("expires", 0L);
            long now = System.currentTimeMillis();
            if (!token.isEmpty()) {
                if (failedAccess != null && !token.equals(failedAccess)) return copy(expected);
                if (failedAccess == null && (expires <= 0 ||
                        (expires > now && expires - now > marginMs))) return copy(expected);
            }
            JSONObject candidate = refresher.refresh(copy(expected), failedAccess != null);
            if (candidate == null) return null;
            if(access(candidate).isEmpty())throw new IllegalStateException("refresher returned no access token");
            JSONObject committed = store.commit(provider, accountId, expected, copy(candidate));
            if (committed == null) throw new IllegalStateException("store returned no committed account");
            return copy(committed);
        }
    }
}
