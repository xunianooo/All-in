package com.gaeul.allin;

public class ProviderRetryPolicyOfflineTest {
    static void check(String input, long now, String expected) {
        String got = ProviderRetryPolicy.safeRetryAfter(input, now);
        if (expected == null ? got != null : !expected.equals(got)) throw new AssertionError(input + " => " + got);
    }
    public static void main(String[] args) {
        long now = 784111777000L;
        check("120", now, "120"); check("9999999999999999999999999999", now, "21600");
        check("Sun, 06 Nov 1994 08:49:37 GMT", now, "0");
        check("Sun, 06 Nov 1994 08:50:37 GMT", now, "60");
        check("-1", now, null); check("1\r\nX-Test: bad", now, null); check(null, now, null);
        if(AntigravityAuth.looksTurnedAway(429,"{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")) throw new AssertionError("ordinary rate limit misclassified");
        System.out.println("ProviderRetryPolicyOfflineTest passed");
    }
}
