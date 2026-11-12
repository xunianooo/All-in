package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Offline-testable parser for the browser authorization-code exchange used by the Raccoon plugin. */
public final class RaccoonLoginProtocol {
    public static final String PROVIDER_ID = "raccoon";
    public static final String LOGIN_PATH = "/api/web/auth/v1/login_with_authorization_code";
    private RaccoonLoginProtocol() {}

    public static final class Credentials {
        public final String providerId;
        public final String source;
        public final String access;
        public final String refresh;
        /** JWT exp converted to epoch milliseconds, matching the reference plugin's `expires`. */
        public final long expires;
        public final String uid;
        public final String accountId;
        public final String name;

        private Credentials(String access, String refresh, long expires, String uid, String name) {
            this.providerId = PROVIDER_ID;
            this.source = "browser";
            this.access = access;
            this.refresh = refresh;
            this.expires = expires;
            this.uid = uid;
            this.name = name;
            this.accountId = !name.isEmpty() ? name : !uid.isEmpty() ? "Raccoon-" + uid : "商汤小浣熊";
        }
    }

    /** Build the exact authorization-code exchange body shown in the local reference source. */
    public static JSONObject exchangeBody(String authorizationCode) {
        String code = authorizationCode == null ? "" : authorizationCode.trim();
        if (code.isEmpty() || code.length() > 4096) throw new IllegalArgumentException("authorization code missing or too long");
        return new JSONObject().put("authorization_code", code);
    }

    /**
     * Parse only the successful browser code-exchange shape. A cookie access token has no refresh
     * credential and must continue through the legacy path instead of being upgraded here.
     */
    public static Credentials parseExchange(int httpStatus, JSONObject payload) throws IOException {
        if (payload == null) throw new RaccoonAccountService.Failure("Raccoon login response missing", false);
        if (payload.has("code") && !payload.isNull("code") && payload.optInt("code", -1) != 0) {
            int code = payload.optInt("code", -1);
            boolean expiredCode = code == 200035;
            throw new RaccoonAccountService.Failure(error(payload,
                    expiredCode ? "authorization code expired" : "Raccoon API error " + code), expiredCode);
        }
        if (httpStatus < 200 || httpStatus >= 300)
            throw new RaccoonAccountService.Failure(error(payload, "HTTP " + httpStatus),
                    httpStatus >= 400 && httpStatus < 500);
        JSONObject data = payload.optJSONObject("data");
        String access = clean(data == null ? "" : data.optString("access_token", ""));
        String refresh = clean(data == null ? "" : data.optString("refresh_token", ""));
        if (access.isEmpty() || refresh.isEmpty())
            throw new RaccoonAccountService.Failure("Raccoon login response requires access_token and refresh_token", false);

        JSONObject claims = jwtClaims(access);
        long expires = expiryMillis(claims);
        String uid = userId(claims);
        String name = claims == null ? "" : claims.optString("name", "").trim();
        return new Credentials(access, refresh, expires, uid, name);
    }

    private static String error(JSONObject payload, String fallback) {
        if (payload != null) {
            for (String key : new String[]{"details", "message", "msg"}) {
                String value = payload.optString(key, "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return fallback;
    }

    private static JSONObject jwtClaims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2 || parts[1].isEmpty()) return null;
        try {
            return new JSONObject(new String(decodeBase64Url(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long expiryMillis(JSONObject claims) {
        if (claims == null) return 0L;
        Object value = claims.opt("exp");
        long seconds = 0L;
        if (value instanceof Number) seconds = ((Number) value).longValue();
        else if (value instanceof String) {
            try { seconds = Long.parseLong(((String) value).trim()); }
            catch (NumberFormatException ignored) { }
        }
        return seconds > 0 && seconds <= Long.MAX_VALUE / 1000L ? seconds * 1000L : 0L;
    }

    private static String userId(JSONObject claims) {
        if (claims == null) return "";
        for (String key : new String[]{"sid", "iss"}) {
            String hex = claims.optString(key, "").trim().replaceFirst("(?i)^0x", "");
            if (!hex.isEmpty() && hex.matches("(?i)[0-9a-f]+")) {
                try { return new BigInteger(hex, 16).toString(10); }
                catch (NumberFormatException ignored) { }
            }
        }
        for (String key : new String[]{"sub", "id", "user_id"}) {
            String value = claims.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
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
}
