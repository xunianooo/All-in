package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/**
 * 各供应商的"读不出来"与"读到了零"必须分得开。
 *
 * <p>两处历史上真会混的地方：
 * <ul>
 *   <li>ZCode 赠送额度：摘要里的合计不区分型号，拿它回答"这个型号还剩多少"会串额度；</li>
 *   <li>Cline 用量：三段查询全失败时仍然返回 ok:true，看起来像账号一切正常。</li>
 * </ul>
 */
public final class ProviderLayersOfflineTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void eq(Object actual, Object want, String message) {
        if (actual == null ? want != null : !actual.equals(want)) {
            throw new AssertionError(message + ": " + actual + " != " + want);
        }
    }

    static JSONObject bucket(String model, long remaining) throws Exception {
        return new JSONObject()
                .put("name", model)
                .put("remaining", remaining)
                .put("capabilities", new JSONArray().put("model:" + model));
    }

    static void giftRemaining() throws Exception {
        JSONObject summary = new JSONObject()
                .put("ok", true)
                .put("complete", true)
                .put("remainingUnits", 107)
                .put("buckets", new JSONArray().put(bucket("glm-5.3", 100)).put(bucket("glm-5.3-flash", 7)));

        eq(ZCodeGift.remainingFor(summary, "glm-5.3"), 100L, "the model's own bucket");
        eq(ZCodeGift.remainingFor(summary, "glm-5.3-flash"), 7L, "the other model's bucket");
        // 没有被任何桶覆盖：这是"不知道"，不是"用完了"
        eq(ZCodeGift.remainingFor(summary, "not-covered"), -1L, "an uncovered model is unknown");
        // 摘要的合计不能冒充某个型号的剩余
        check(ZCodeGift.remainingFor(summary, "glm-5.3") != summary.optLong("remainingUnits", 0),
                "the account-wide total must not answer for one model");

        JSONObject zero = new JSONObject().put("buckets", new JSONArray().put(bucket("m", 0)));
        eq(ZCodeGift.remainingFor(zero, "m"), 0L, "a real zero stays zero");

        JSONObject missing = new JSONObject().put("buckets", new JSONArray()
                .put(new JSONObject().put("capabilities", new JSONArray().put("model:m"))));
        eq(ZCodeGift.remainingFor(missing, "m"), -1L, "a bucket without remaining is unknown");

        eq(ZCodeGift.remainingFor(null, "m"), -1L, "no summary is unknown");
        eq(ZCodeGift.remainingFor(new JSONObject(), "m"), -1L, "no buckets is unknown");

        // 能力标签是 model:xxx，不能被别的标签误命中
        JSONObject other = new JSONObject().put("buckets", new JSONArray().put(
                new JSONObject().put("remaining", 5).put("capabilities", new JSONArray().put("tool:search"))));
        eq(ZCodeGift.remainingFor(other, "search"), -1L, "capability kinds are not interchangeable");
    }

    static void clineRead() {
        // 401 与断网说的是两件事，处置也不同
        eq(ClineAuth.readKind(401), FailureLayers.Kind.AUTH, "401 kind");
        eq(ClineAuth.readKind(403), FailureLayers.Kind.RESTRICTED, "403 kind");
        eq(ClineAuth.readKind(404), FailureLayers.Kind.NOT_FOUND, "404 kind");
        eq(ClineAuth.readKind(429), FailureLayers.Kind.RATE, "429 kind");
        eq(ClineAuth.readKind(500), FailureLayers.Kind.UNREAD, "an unreadable answer");
        eq(ClineAuth.readKind(0), FailureLayers.Kind.NETWORK, "no status is a link problem");

        check(ClineAuth.readFailure(401).contains("401"), "401 says its code");
        check(ClineAuth.readFailure(0).length() > 0, "no status still says something");
        check(!ClineAuth.readFailure(401).equals(ClineAuth.readFailure(0)),
                "a rejected sign-in and a dead link must read differently");
    }

    public static void main(String[] args) throws Exception {
        giftRemaining();
        clineRead();
        System.out.println("ProviderLayersOfflineTest passed");
    }
}
