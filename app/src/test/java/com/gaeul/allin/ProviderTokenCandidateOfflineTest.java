package com.gaeul.allin;

import org.json.JSONObject;

public final class ProviderTokenCandidateOfflineTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject account = new JSONObject("{\"refresh\":\"old-refresh\",\"access\":\"old-access\",\"nested\":{\"value\":1}}");
        String original = account.toString();
        CopilotAuth.Tok token = new CopilotAuth.Tok();
        token.token = "new-access"; token.expires = 12345;
        JSONObject copilot = CopilotAuth.refreshCandidate(account, refresh -> {
            check(refresh.equals("old-refresh")); return new CopilotAuth.ExchangeResult(token, 200);
        });
        check(account.toString().equals(original));
        check(copilot.optString("access").equals("new-access") && copilot.optString("token").equals("new-access"));
        check(copilot.optLong("expires") == 12345);
        copilot.getJSONObject("nested").put("value", 2);
        check(account.toString().equals(original));
        for (int status : new int[]{401, 429, 500}) {
            try {
                CopilotAuth.refreshCandidate(account, refresh -> new CopilotAuth.ExchangeResult(null, status));
                throw new AssertionError();
            } catch (CopilotAuth.TypedAuthException e) {
                check(e.status == status && e.needsSignIn == (status == 401));
            }
            check(account.toString().equals(original));
        }
        JSONObject anti = AntigravityAuth.refreshCandidate(account, refresh ->
                new AntigravityAuth.CandidateResponse(200, new JSONObject("{\"access_token\":\"new-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}")));
        check(account.toString().equals(original));
        check(anti.optString("access").equals("new-access") && anti.optString("token").equals("new-access"));
        check(anti.optString("refresh").equals("rotated") && anti.optLong("expires") > System.currentTimeMillis());
        anti.getJSONObject("nested").put("value", 2);
        check(account.toString().equals(original));
        for (int status : new int[]{401, 429, 500}) {
            try {
                AntigravityAuth.refreshCandidate(account, refresh -> new AntigravityAuth.CandidateResponse(status, new JSONObject()));
                throw new AssertionError();
            } catch (AntigravityAuth.TypedAuthException e) {
                check(e.status == status && e.needsSignIn == (status == 401));
            }
            check(account.toString().equals(original));
        }
        try {
            AntigravityAuth.refreshCandidate(account, refresh -> new AntigravityAuth.CandidateResponse(400, new JSONObject("{\"error\":\"invalid_grant\"}")));
            throw new AssertionError();
        } catch (AntigravityAuth.TypedAuthException e) { check(e.status == 400 && e.needsSignIn); }
        check(account.toString().equals(original));
    }
}
