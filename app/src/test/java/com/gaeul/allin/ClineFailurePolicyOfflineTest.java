package com.gaeul.allin;
import org.json.JSONObject;
public final class ClineFailurePolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void test(int input, String body, int expected) {
        JSONObject r = ClineFailurePolicy.classify(input, body);
        check(r.optInt("status") == expected && r.optJSONObject("error").optInt("code") == expected);
        check(!r.toString().contains("SECRET_MARKER"));
    }
    public static void main(String[] args) {
        test(403, "{\"error\":{\"code\":\"ENTITLEMENT_ERROR\",\"message\":\"SECRET_MARKER\"}}", 403);
        test(400, "{\"code\":\"Insufficient_Credits\"}", 429);
        test(403, "{\"error\":{\"code\":\"SPEND_LIMIT_EXCEEDED\"}}", 429);
        test(403, "{\"message\":\"SECRET_MARKER invalid token in prompt\"}", 403);
        test(401, "SECRET_MARKER", 401);
        test(200, "SECRET_MARKER", 502);
        System.out.println("ClineFailurePolicyOfflineTest passed");
    }
}
