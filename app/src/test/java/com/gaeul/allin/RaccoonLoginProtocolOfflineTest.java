package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Synthetic plugin-contract fixtures only; this test never opens a socket. */
public final class RaccoonLoginProtocolOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String jwt(String payload) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    static void browserExchangeFixture() throws Exception {
        JSONObject request = RaccoonLoginProtocol.exchangeBody("fixture-authorization-code");
        check(request.length() == 1 && request.optString("authorization_code").equals("fixture-authorization-code"),
                "plugin code exchange request shape");

        String access = "Bearer " + jwt("{\"iss\":\"74350b\",\"name\":\"RaccoonBenjamin\",\"exp\":1800000000}");
        JSONObject response = new JSONObject().put("code", 0).put("data",
                new JSONObject().put("access_token", access).put("refresh_token", "fixture-refresh-token"));
        RaccoonLoginProtocol.Credentials c = RaccoonLoginProtocol.parseExchange(200, response);
        check(c.providerId.equals("raccoon") && c.source.equals("browser"), "provider and origin marked");
        check(c.access.equals(access.substring("Bearer ".length())), "Bearer prefix cleaned");
        check(c.refresh.equals("fixture-refresh-token"), "refresh token extracted");
        check(c.expires == 1_800_000_000_000L, "JWT exp stored in epoch milliseconds");
        check(c.uid.equals("7615755"), "hex JWT issuer maps to stable decimal uid as reference does");
        check(c.name.equals("RaccoonBenjamin") && c.accountId.equals("RaccoonBenjamin"), "account identity preserved");
    }

    static void malformedAndLegacyFixtures() throws Exception {
        JSONObject missingRefresh = new JSONObject().put("code", 0)
                .put("data", new JSONObject().put("access_token", jwt("{\"exp\":1800000000}")));
        try {
            RaccoonLoginProtocol.parseExchange(200, missingRefresh);
            throw new AssertionError("missing refresh must not become refreshable");
        } catch (RaccoonAccountService.Failure expected) {
            check(!expected.expired, "missing plugin credential is reported without corrupting legacy token");
        }

        try {
            RaccoonLoginProtocol.parseExchange(400, new JSONObject().put("code", 200035).put("message", "expired code"));
            throw new AssertionError("expired authorization code must fail");
        } catch (RaccoonAccountService.Failure expected) {
            check(expected.expired && expected.getMessage().contains("expired code"), "vendor code failure classified");
        }

        try {
            RaccoonLoginProtocol.exchangeBody(" ");
            throw new AssertionError("empty code must fail locally");
        } catch (IllegalArgumentException expected) { }

        // Existing cookie login only returns a token and remains access-only; the new parser never
        // invents a refresh token or changes that legacy path's semantics.
        check(RaccoonRefreshPolicy.shouldRefresh(
                new RaccoonRefreshPolicy.Credentials("legacy-access", "", 1L, false), Long.MAX_VALUE) == false,
                "legacy access-only account remains non-refreshing");
    }

    public static void main(String[] args) throws Exception {
        browserExchangeFixture();
        malformedAndLegacyFixtures();
        System.out.println("RaccoonLoginProtocolOfflineTest passed");
    }
}
