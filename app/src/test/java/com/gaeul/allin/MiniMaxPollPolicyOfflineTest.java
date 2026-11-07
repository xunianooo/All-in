package com.gaeul.allin;
import org.json.JSONObject;
public final class MiniMaxPollPolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        MiniMaxAuth.Flow f = new MiniMaxAuth.Flow(); f.intervalMs = 1000;
        for (String s : new String[]{"denied", "access_denied", "expired", "expired_token"})
            check("failed".equals(MiniMaxAuth.pollOutcome(f, 200, new JSONObject().put("status", s)).status));
        check("pending".equals(MiniMaxAuth.pollOutcome(f, 400, new JSONObject().put("error", "authorization_pending")).status));
        check("slow_down".equals(MiniMaxAuth.pollOutcome(f, 400, new JSONObject().put("error", "slow_down")).status) && f.intervalMs == 6000);
        check("pending".equals(MiniMaxAuth.pollOutcome(f, 503, new JSONObject()).status));
        MiniMaxAuth.PollResult r = MiniMaxAuth.pollOutcome(f, 400, new JSONObject().put("error", "SECRET_MARKER"));
        check("failed".equals(r.status) && !r.error.contains("SECRET_MARKER"));
        System.out.println("MiniMaxPollPolicyOfflineTest passed");
    }
}
