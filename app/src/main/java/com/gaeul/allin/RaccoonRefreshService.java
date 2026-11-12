package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Standalone Raccoon refresh protocol. Network and persistence are injected so this class can be
 * exercised offline and cannot silently send requests or overwrite provider configuration.
 *
 * <p>The refresh token is one-use material, so a failed POST is not a single event: it is either
 * provably unsent (the link was never established, nothing could reach the vendor) or an unknown
 * outcome (the request may already have been executed). This service only ever reports the second
 * as {@link RaccoonRefreshSend.Failure} with a conservative classification taken from the
 * transport's declared stage and byte count - it never guesses from an exception type. A caller
 * may therefore release a one-use reservation only for
 * {@link RaccoonRefreshSend#releasesReservation(Throwable)} and must keep it otherwise.
 *
 * A refreshable browser account can be stored as {id, accountId, uid, name, enabled, type,
 * source, access, token, refresh, expires, lastRefresh}; unknown account fields are preserved.
 * AccountTokenCoordinator.Store.commit must atomically compare the complete expected account and
 * provider/account enabled state before persisting the candidate.
 */
public final class RaccoonRefreshService {
    public static final String PROVIDER = "raccoon";
    public static final String REFRESH_PATH = "/api/web/auth/v1/refresh";

    public interface Transport {
        /**
         * Sends the refresh POST and returns the vendor response.
         *
         * <p>Every send failure must be declared as {@link RaccoonRefreshSend.Failure} with the
         * stage that was actually reached, because the one-use refresh token in the body may
         * already be spent. A plain {@link IOException} (or any other failure) is treated as an
         * unknown outcome, which never allows the same token to be sent again.
         */
        Response postJson(String path, JSONObject body) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final JSONObject body;
        /** False when the transport could not read a complete response body. */
        public final boolean complete;
        public Response(int status, JSONObject body) { this(status, body, true); }
        public Response(int status, JSONObject body, boolean complete) {
            this.status = status;
            this.body = body;
            this.complete = complete;
        }
    }

    private final AccountTokenCoordinator coordinator;
    private final Transport transport;

    public RaccoonRefreshService(AccountTokenCoordinator coordinator, Transport transport) {
        if (coordinator == null || transport == null) throw new IllegalArgumentException("coordinator and transport required");
        this.coordinator = coordinator;
        this.transport = transport;
    }

    /**
     * Refreshes when the existing policy's 60-second expiry margin is reached. Accounts with no
     * refresh token, desktop credentials, unknown expiry, or a disabled/missing account are left
     * intact; a legacy access-only account therefore remains usable by its caller.
     */
    public JSONObject ensureFresh(String accountId, AccountTokenCoordinator.Store store) throws Exception {
        JSONObject current = readEnabled(accountId, store);
        if (current == null) return null;
        RaccoonRefreshPolicy.Credentials credentials = credentials(current);
        if (credentials.desktop || credentials.refresh.isEmpty()
                || !RaccoonRefreshPolicy.shouldRefresh(credentials, System.currentTimeMillis())) {
            return copy(current);
        }
        return coordinator.ensure(PROVIDER, accountId, RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS,
                null, store, this::refreshCandidate);
    }

    /**
     * Performs at most the policy-permitted refresh after a 401. A stale failedAccess means a
     * concurrent caller already changed the account, so the current account is returned untouched.
     */
    public JSONObject refreshAfter401(String accountId, String failedAccess, int attempts,
                                      AccountTokenCoordinator.Store store) throws Exception {
        JSONObject current = readEnabled(accountId, store);
        if (current == null) return null;
        RaccoonRefreshPolicy.Credentials credentials = credentials(current);
        if (!RaccoonRefreshPolicy.mayRetry401(credentials, 401, attempts)) return copy(current);
        if (!credentials.access.equals(clean(failedAccess))) return copy(current);
        return coordinator.ensure(PROVIDER, accountId, RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS,
                failedAccess, store, this::refreshCandidate);
    }

    private JSONObject readEnabled(String accountId, AccountTokenCoordinator.Store store) throws Exception {
        if (accountId == null || accountId.trim().isEmpty() || store == null)
            throw new IllegalArgumentException("accountId and store required");
        JSONObject current = store.read(PROVIDER, accountId);
        if (current == null || !current.optBoolean("enabled", true)) return null;
        return copy(current);
    }

    private JSONObject refreshCandidate(JSONObject account, boolean force) throws IOException {
        RaccoonRefreshPolicy.Credentials old = credentials(account);
        if (old.desktop || old.refresh.isEmpty()) return copyUnchecked(account);
        Response response;
        try {
            response = transport.postJson(REFRESH_PATH,
                    new JSONObject().put("refresh_token", old.refresh));
        } catch (RaccoonRefreshSend.Failure declared) {
            // Declared stage evidence travels unchanged: the caller must be able to release its
            // one-use reservation for a provably unsent request and to keep it for everything else.
            throw declared;
        } catch (IOException transportFailure) {
            // Fail closed: an undeclared failure may have been a half-written request.
            throw RaccoonRefreshSend.wrap(transportFailure);
        } catch (RuntimeException transportCrash) {
            // A crash while sending proves nothing about whether the vendor saw the request.
            throw RaccoonRefreshSend.wrapCrash(transportCrash);
        }
        if (response == null)
            throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.UNSPECIFIED, -1, -1,
                    "Raccoon refresh response missing", null);
        if (!response.complete)
            // A status without a complete body cannot prove whether the token was rotated.
            throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_BODY, -1, -1,
                    "Raccoon refresh response incomplete", null);
        JSONObject payload = response.body;
        if (response.status < 200 || response.status >= 300) {
            boolean expired = response.status >= 400 && response.status < 500;
            throw new RaccoonAccountService.Failure(errorMessage(payload, "HTTP " + response.status), expired);
        }
        if (payload == null)
            // A success status with no body cannot prove whether the token was rotated either.
            throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_BODY, -1, -1,
                    "Raccoon refresh response carried no body", null);
        if (payload.has("code") && !payload.isNull("code") && payload.optInt("code", -1) != 0) {
            int code = payload.optInt("code", -1);
            throw new RaccoonAccountService.Failure(errorMessage(payload, "Raccoon API error " + code),
                    code == 100002 || code == 200001 || code == 200003);
        }

        long now = System.currentTimeMillis();
        RaccoonRefreshPolicy.Credentials next = RaccoonRefreshPolicy.parseRefresh(payload, old,
                expiryFromResponse(payload, old.expiresAtMillis));
        JSONObject candidate = copyUnchecked(account);
        candidate.put("access", next.access);
        if (candidate.has("token")) candidate.put("token", next.access);
        candidate.put("refresh", next.refresh);
        candidate.put("expires", next.expiresAtMillis);
        candidate.put("lastRefresh", now);
        return candidate;
    }

    private static String errorMessage(JSONObject payload, String fallback) {
        if (payload != null) {
            for (String key : new String[]{"details", "message", "msg"}) {
                String value = payload.optString(key, "").trim();
                if (!value.isEmpty()) return value;
            }
            JSONObject error = payload.optJSONObject("error");
            if (error != null) {
                String value = error.optString("message", "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return fallback;
    }


    private static long expiryFromResponse(JSONObject payload, long fallback) {
        JSONObject data = payload == null ? null : payload.optJSONObject("data");
        Object rawExpiresIn = data == null ? null : data.opt("expires_in");
        long expiresIn = 0L;
        if (rawExpiresIn instanceof Number) expiresIn = ((Number) rawExpiresIn).longValue();
        else if (rawExpiresIn instanceof String) {
            try { expiresIn = Long.parseLong(((String) rawExpiresIn).trim()); }
            catch (NumberFormatException ignored) { }
        }
        long now = System.currentTimeMillis();
        if (expiresIn > 0 && expiresIn <= (Long.MAX_VALUE - now) / 1000L)
            return now + expiresIn * 1000L;
        String access = data == null ? "" : clean(data.optString("access_token", ""));
        String[] parts = access.split("\\.");
        if (parts.length >= 2) {
            try {
                // JWT segments already use base64url; keep '-' and '_' for this decoder.
                String encoded = parts[1];
                JSONObject claims = new JSONObject(new String(decodeBase64Url(encoded), StandardCharsets.UTF_8));
                long exp = claims.optLong("exp", 0L);
                if (exp > 0 && exp <= Long.MAX_VALUE / 1000L) return exp * 1000L;
            } catch (Exception ignored) { }
        }
        return fallback;
    }

    private static RaccoonRefreshPolicy.Credentials credentials(JSONObject account) {
        String access = account.optString("access", "");
        if (access.trim().isEmpty()) access = account.optString("token", "");
        String source = account.optString("source", "");
        boolean desktop = account.optBoolean("desktop", false) || "desktop".equalsIgnoreCase(source);
        return new RaccoonRefreshPolicy.Credentials(access, account.optString("refresh", ""),
                account.optLong("expires", 0L), desktop);
    }

    private static byte[] decodeBase64Url(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((encoded.length() * 3) / 4);
        int buffer = 0, bits = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '=') break;
            int value = alphabet.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("invalid base64url");
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
    }

    private static JSONObject copy(JSONObject object) throws Exception {
        return object == null ? null : new JSONObject(object.toString());
    }

    private static JSONObject copyUnchecked(JSONObject object) throws IOException {
        try { return copy(object); }
        catch (Exception e) { throw new IOException("Could not copy Raccoon account", e); }
    }
}
