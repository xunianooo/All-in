package com.gaeul.allin;

public final class ZCodeRefusalPolicyOfflineTest {
    private static final String GIFT = "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages";
    private static final String CODING = "https://api.z.ai/api/coding/paas/v4/chat/completions";
    private static final String EDGE = "<!DOCTYPE html><html>Cloudflare error code: 1020 Access denied</html>";
    private static void ok(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        final long[] time = {1000};
        ZCodeRefusalPolicy p = new ZCodeRefusalPolicy(() -> time[0]);
        ok(ZCodeRefusalPolicy.classify(405, "{\"code\":3012}") == ZCodeRefusalPolicy.Kind.ACCOUNT);
        ok(ZCodeRefusalPolicy.classify(400, "{\"error\":{\"code\":\"3012\"}}") == ZCodeRefusalPolicy.Kind.ACCOUNT);
        ok(ZCodeRefusalPolicy.classify(200, "{\"code\":3012}") == ZCodeRefusalPolicy.Kind.NONE);
        ok(ZCodeRefusalPolicy.classify(405, "{\"message\":\"code 3012\"}") == ZCodeRefusalPolicy.Kind.UNKNOWN);
        ok(ZCodeRefusalPolicy.classify(405, "") == ZCodeRefusalPolicy.Kind.UNKNOWN);
        ok(ZCodeRefusalPolicy.classify(403, EDGE) == ZCodeRefusalPolicy.Kind.EDGE);
        ok(ZCodeRefusalPolicy.classify(500, EDGE) == ZCodeRefusalPolicy.Kind.NONE);
        ok(ZCodeRefusalPolicy.classify(403, "<html>unrelated</html>") == ZCodeRefusalPolicy.Kind.NONE);
        p.observe(GIFT, "a", "net", 405, "{\"code\":3012}");
        ok(p.before(GIFT, "a", "net").retryAfterMillis == 180000);
        ok(p.before(GIFT, "b", "net") == null);
        ok(p.before(GIFT, "a", "other") == null);
        ok(p.before(CODING, "a", "net") == null);
        p.observe(GIFT, "a", "net", 403, EDGE);
        ok(p.before(GIFT, "b", "net").kind == ZCodeRefusalPolicy.Kind.EDGE);
        ok(p.before(GIFT, "b", "other") == null);
        time[0] = 500; ok(p.before(GIFT, "a", "net").retryAfterMillis == 180000);
        time[0] = 181000; ok(p.before(GIFT, "a", "net") == null);
        p.observe(GIFT, "a", "net", 405, ""); ok(p.before(GIFT, "a", "net") == null);
        String[] bad = {"http://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages", "https://zcode.z.ai.evil/api/v1/zcode-plan/anthropic/v1/messages", "https://zcode.z.ai@evil/api/v1/zcode-plan/anthropic/v1/messages", "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages/", "https://api.z.ai/api/coding/paas/v4/chat%2Fcompletions"};
        for (String s : bad) { ok(ZCodeRefusalPolicy.trustedOrigin(s) == null); p.observe(s, "a", "net", 405, "{\"code\":3012}"); ok(p.before(s, "a", "net") == null); }
        ok(ZCodeRefusalPolicy.trustedOrigin("https://open.bigmodel.cn/api/anthropic/v1/messages") != null);
        ok(ZCodeRefusalPolicy.trustedOrigin("https://api.z.ai/api/coding/paas/v4/messages") != null);
        ok(p.before(GIFT, "", "net").kind == ZCodeRefusalPolicy.Kind.UNKNOWN);
        for (int i = 0; i < 512; i++) p.observe(GIFT, "a" + i, "net", 405, "{\"code\":3012}");
        p.observe(GIFT, "overflow", "net", 405, "{\"code\":3012}");
        ok(p.before(CODING, "unrelated", "network").kind == ZCodeRefusalPolicy.Kind.EDGE);
        p.clear(); ok(p.before(GIFT, "a0", "net") == null);
        System.out.println("ZCodeRefusalPolicyOfflineTest passed");
    }
}
