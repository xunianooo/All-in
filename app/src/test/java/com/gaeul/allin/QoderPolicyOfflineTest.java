package com.gaeul.allin;

import org.json.JSONObject;

public final class QoderPolicyOfflineTest {
    private static int checks;
    private static void check(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    private static void failure(int http, String body, int status, String kind) throws Exception {
        JSONObject f = QoderFailurePolicy.failure(http, body);
        check(f.getInt("status") == status); check(kind.equals(f.getString("error_kind")));
        check(!f.toString().contains("secret"));
    }
    private static void invalid(String text) throws Exception {
        try { QoderFailurePolicy.payloadLimit(new JSONObject(text)); throw new AssertionError(text); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        failure(403, "{\"code\":10605,\"message\":\"secret\"}", 503, "queue");
        failure(403, "{\"code\":403,\"message\":\"{\\\"code\\\":10605}\"}", 503, "queue");
        failure(403, "{\"code\":105,\"message\":\"{\\\"code\\\":10605}\"}", 401, "auth");
        failure(403, "{\"code\":110,\"queue\":{\"isQueued\":true}}", 429, "quota");
        failure(403, "{\"code\":110,\"message\":\"{\\\"code\\\":10605}\",\"queue\":{\"serviceAvailable\":false}}", 429, "quota");
        failure(403, "{\"code\":416,\"queue\":{\"isQueued\":true}}", 400, "request");
        failure(403, "{\"code\":105,\"queue\":{\"isQueued\":true}}", 401, "auth");
        failure(403, "{\"code\":416}", 400, "request");
        failure(502, "{\"error\":{\"code\":105}}", 401, "auth");
        failure(403, "secret 10605 401", 403, "upstream");
        JSONObject f = QoderFailurePolicy.failure(403, "{\"queue\":{\"isQueued\":true,\"retryAfterSeconds\":999999}}");
        check(f.getInt("retry_after") == 3600); check(f.getInt("upstream_status") == 403);
        check(QoderFailurePolicy.payloadLimit(new JSONObject()) == 32000);
        check(QoderFailurePolicy.payloadLimit(new JSONObject("{\"max_tokens\":64}")) == 64);
        check(QoderFailurePolicy.payloadLimit(new JSONObject("{\"max_tokens\":100,\"max_completion_tokens\":70}")) == 70);
        invalid("{\"max_tokens\":63}"); invalid("{\"max_tokens\":0}"); invalid("{\"max_tokens\":-1}");
        invalid("{\"max_tokens\":\"64\"}"); invalid("{\"max_tokens\":2147483648}");
        invalid("{\"max_tokens\":64.5}"); invalid("{\"max_tokens\":null}");
        invalid("{\"max_tokens\":100,\"max_completion_tokens\":0}");
        System.out.println("Qoder policy offline checks passed: " + checks);
    }
}
