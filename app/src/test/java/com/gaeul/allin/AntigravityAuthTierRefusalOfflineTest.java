package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Antigravity tier 拒绝分类的定向离线测试（不联网、不读真实账号、不启服务）。
 *
 * 对照第一事实源：
 *   upstream internal/provider/google.go —— codeAssistError{status,msg} / codeAssistStatus(err)
 *   只在 codeAssistStatus(err) == http.StatusBadRequest 时调用 tierRefused；
 *   429（含 Antigravity 的假 429）、401、403、404、5xx 一律不记。
 *
 * 本测试证明：
 *  1. 状态码来自结构化字段（异常自带 status），不是从错误串里找 "400"；
 *  2. 400 才把 tier 记进 TIER_REFUSED，下一次列表不再先撞一次 400；
 *  3. 429 正文里带 "400"（"retry after 400 seconds"）不再把这个 tier 静音 24 小时
 *     —— 这是旧写法 {@code getMessage().contains("400")} 的实际危害；
 *  4. 负对照：把旧写法原样复刻，同一个 429 夹具下它会误判成 tier 被拒；
 *  5. 抛出的仍是「Code Assist <status>: …」消息，日志/诊断文字不变。
 */
public final class AntigravityAuthTierRefusalOfflineTest {

    private static final String PAID_TIER = "g1-pro-tier";
    private static final String MODELS_PATH = "fetchAvailableModels";

    public static void main(String[] args) throws Exception {
        statusComesFromTheStatusNotTheBody();
        onlyBadRequestMarksTheTierRefused();
        legacyStringMatchMisfiresOnTheSameFixture();
        badRequestSuppressesTheTierOnTheNextList();
        quotaFourTwentyNineKeepsTheTier();
        allFailFourTwentyNineStillKeepsTheTier();
        serverErrorKeepsTheTier();
        acceptedTierIsRecordedAndNotRefused();
        System.out.println("AntigravityAuthTierRefusalOfflineTest: PASS");
    }

    // ------------------------------------------------------------------ 用例

    /** 状态码只认结构化字段；正文里出现的数字不参与分类。 */
    private static void statusComesFromTheStatusNotTheBody() throws Exception {
        AntigravityAuth.CodeAssistException bad = AntigravityAuth.codeAssistRefused(400,
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"Requested entity was not found\"}}");
        check(AntigravityAuth.codeAssistStatus(bad) == 400, "400 应答要留下 status=400");
        check(bad.status() == 400, "异常自身要携带 status");
        check(bad.getMessage().startsWith("Code Assist 400: "), "消息格式保持「Code Assist <status>: …」");
        check(bad.getMessage().contains("INVALID_ARGUMENT"), "正文照旧进消息，日志可读");

        // 正文里出现 400 的 429：状态仍是 429
        AntigravityAuth.CodeAssistException quota = AntigravityAuth.codeAssistRefused(429,
                "{\"error\":{\"code\":429,\"message\":\"Resource exhausted; retry after 400 seconds (400 tokens left)\"}}");
        check(AntigravityAuth.codeAssistStatus(quota) == 429, "429 应答要留下 status=429");
        check(quota.getMessage().contains("400"), "夹具前提：旧写法在这条消息上会命中 \"400\"");

        // 反向：400 正文里出现 429 之类别的码，不影响分类
        AntigravityAuth.CodeAssistException odd = AntigravityAuth.codeAssistRefused(400,
                "{\"error\":{\"code\":400,\"message\":\"429 is not a valid tier\"}}");
        check(AntigravityAuth.codeAssistStatus(odd) == 400, "正文里的 429 不该改变状态");

        check(AntigravityAuth.codeAssistStatus(new java.io.IOException("boom")) == 0,
                "非 Code Assist 应答的状态是 0");
        check(AntigravityAuth.codeAssistStatus(null) == 0, "null 的状态是 0");
        Exception wrapped = new Exception("外层包装", bad);
        check(AntigravityAuth.codeAssistStatus(wrapped) == 400, "cause 链里的结构化状态要能找到（对齐 errors.As）");
    }

    /** 只有 400 算 tier 被拒：429/401/403/404/5xx 和「没有结构状态的错误」都不算。 */
    private static void onlyBadRequestMarksTheTierRefused() throws Exception {
        check(AntigravityAuth.marksTierRefused(PAID_TIER,
                        AntigravityAuth.codeAssistRefused(400, "{\"error\":{\"code\":400}}")),
                "400 必须算 tier 被拒");
        check(AntigravityAuth.marksTierRefused(PAID_TIER,
                        new Exception("外层", AntigravityAuth.codeAssistRefused(400, "{}"))),
                "包了一层的 400 也要算");

        check(!AntigravityAuth.marksTierRefused(PAID_TIER,
                        AntigravityAuth.codeAssistRefused(429,
                                "{\"error\":{\"code\":429,\"message\":\"retry after 400 seconds\"}}")),
                "429（正文含 400）绝不能算 tier 被拒");
        check(!AntigravityAuth.marksTierRefused(PAID_TIER,
                        AntigravityAuth.codeAssistRefused(429, "{\"error\":{\"message\":\"1400 tokens\"}}")),
                "正文里的 1400 也不能算");
        int[] other = {401, 403, 404, 500, 503};
        for (int status : other) {
            check(!AntigravityAuth.marksTierRefused(PAID_TIER,
                            AntigravityAuth.codeAssistRefused(status,
                                    "{\"error\":{\"code\":" + status + ",\"message\":\"see 400 for details\"}}")),
                    status + " 不能算 tier 被拒（正文里就算有 400 也不行）");
        }

        check(!AntigravityAuth.marksTierRefused("",
                        AntigravityAuth.codeAssistRefused(400, "{}")),
                "没命名 tier 的请求谈不上 tier 被拒（对齐上游 t != \"\" 的条件）");
        check(!AntigravityAuth.marksTierRefused(null,
                        AntigravityAuth.codeAssistRefused(400, "{}")), "null tier 不记");
        check(!AntigravityAuth.marksTierRefused(PAID_TIER,
                        new java.io.IOException("Code Assist 400: 只有文字，没有结构状态")),
                "没有结构状态的错误不靠文字猜（这正是旧写法的漏洞）");
        check(!AntigravityAuth.marksTierRefused(PAID_TIER, null), "null 错误不记");
    }

    /** 负对照：把旧的 contains("400") 写法原样复刻，同一个 429 夹具下它会误判。 */
    private static void legacyStringMatchMisfiresOnTheSameFixture() throws Exception {
        AntigravityAuth.CodeAssistException quota = AntigravityAuth.codeAssistRefused(429,
                "{\"error\":{\"code\":429,\"message\":\"Quota exceeded: retry after 400 seconds\"}}");
        AntigravityAuth.CodeAssistException bad = AntigravityAuth.codeAssistRefused(400,
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}");

        check(legacyWouldMarkRefused(quota),
                "负对照前提：旧写法确实会把这条 429 当成 400");
        check(!AntigravityAuth.marksTierRefused(PAID_TIER, quota),
                "新写法在同一个夹具上不再误判");
        check(legacyWouldMarkRefused(bad) && AntigravityAuth.marksTierRefused(PAID_TIER, bad),
                "真 400 上新旧一致，改动没有放开真拒绝");
    }

    /** 400 之后，下一次列表不再先撞一次 400：tier 被排除，直接问「不命名」。 */
    private static void badRequestSuppressesTheTierOnTheNextList() throws Exception {
        String accKey = newAccKey("400");
        FakeModels post = new FakeModels(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}");
        JSONObject acc = account(accKey, PAID_TIER);

        JSONArray first = AntigravityAuth.fetchModels(acc, "access-token", "project-1", post);
        check(first.length() == 1, "400 之后要回落到「不命名 tier」并拿到清单");
        check("gemini-3.1-pro".equals(first.getJSONObject(0).optString("id")), "清单内容照旧");
        check(post.tiers.equals(java.util.Arrays.asList(PAID_TIER, "")),
                "第一次应按「tier、无 tier」顺序问：" + post.tiers);

        FakeModels second = new FakeModels(200, "");
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", second);
        check(second.tiers.equals(java.util.Collections.singletonList("")),
                "24 小时内不该再命名被拒的 tier：" + second.tiers);
        check(!second.tiers.contains(PAID_TIER), "被拒的 tier 必须被静音");
    }

    /** 429（正文含 400）之后，tier 必须照旧命名：额度用光/假 429 不是 tier 不存在。 */
    private static void quotaFourTwentyNineKeepsTheTier() throws Exception {
        String accKey = newAccKey("429");
        FakeModels post = new FakeModels(429,
                "{\"error\":{\"code\":429,\"message\":\"Quota exceeded: retry after 400 seconds\"}}");
        JSONObject acc = account(accKey, PAID_TIER);

        JSONArray models = AntigravityAuth.fetchModels(acc, "access-token", "project-1", post);
        check(models.length() == 1, "429 之后要回落到「不命名 tier」并拿到清单");
        check(post.tiers.equals(java.util.Arrays.asList(PAID_TIER, "")),
                "第一次仍是由 tier 起问：" + post.tiers);

        FakeModels second = new FakeModels(200, "");
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", second);
        check(second.tiers.contains(PAID_TIER),
                "429 正文里的 400 不能把这个 tier 静音 24 小时：" + second.tiers);
    }

    /** 全失败时抛的还是「Code Assist <status>: …」，且 tier 没被记成被拒。 */
    private static void allFailFourTwentyNineStillKeepsTheTier() throws Exception {
        String accKey = newAccKey("429-all");
        FakeModels post = new FakeModels(429,
                "{\"error\":{\"code\":429,\"message\":\"retry after 400 seconds\"}}");
        post.failAlways = true;
        JSONObject acc = account(accKey, PAID_TIER);

        Exception thrown = null;
        try {
            AntigravityAuth.fetchModels(acc, "access-token", "project-1", post);
        } catch (Exception e) {
            thrown = e;
        }
        check(thrown != null, "全失败必须抛错");
        check(AntigravityAuth.codeAssistStatus(thrown) == 429, "抛出来的异常要带结构化 429");
        check(thrown.getMessage().startsWith("Code Assist 429: "),
                "消息格式不变，日志可读：" + thrown.getMessage());

        FakeModels retry = new FakeModels(200, "");
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", retry);
        check(retry.tiers.contains(PAID_TIER), "429 全失败之后 tier 仍要照旧命名：" + retry.tiers);
    }

    /** 5xx（正文含 400）同样不静音 tier。 */
    private static void serverErrorKeepsTheTier() throws Exception {
        String accKey = newAccKey("503");
        FakeModels post = new FakeModels(503,
                "{\"error\":{\"code\":503,\"message\":\"backend unavailable, see 400 logs\"}}");
        JSONObject acc = account(accKey, PAID_TIER);
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", post);

        FakeModels second = new FakeModels(200, "");
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", second);
        check(second.tiers.contains(PAID_TIER), "5xx 不能静音 tier：" + second.tiers);
    }

    /** tier 被接受时照旧记进账号，下次仍先由它问。 */
    private static void acceptedTierIsRecordedAndNotRefused() throws Exception {
        String accKey = newAccKey("ok");
        FakeModels post = new FakeModels(200, "");
        JSONObject acc = account(accKey, PAID_TIER);

        AntigravityAuth.fetchModels(acc, "access-token", "project-1", post);
        check(post.tiers.equals(java.util.Collections.singletonList(PAID_TIER)),
                "成功时只问一次且命名 tier：" + post.tiers);
        check(PAID_TIER.equals(acc.optString("tier", "")), "被接受的 tier 要记回账号");
        check(post.urls.size() == 1 && post.urls.get(0).contains(MODELS_PATH),
                "打的还是 fetchAvailableModels：" + post.urls);

        FakeModels second = new FakeModels(200, "");
        AntigravityAuth.fetchModels(acc, "access-token", "project-1", second);
        check(second.tiers.contains(PAID_TIER), "被接受的 tier 下次照旧命名：" + second.tiers);
    }

    // ------------------------------------------------------------------ 夹具

    /** 旧写法（fetchModels 里 D 之前的实现），只用于负对照。 */
    private static boolean legacyWouldMarkRefused(Throwable err) {
        return String.valueOf(err.getMessage()).contains("400");
    }

    private static int seq;

    private static String newAccKey(String tag) {
        // TIER_REFUSED 是静态的、记 24 小时：每个用例用唯一 key，互不污染，也不需要清理钩子
        return "tier-refusal-test-" + tag + "-" + System.nanoTime() + "-" + (seq++);
    }

    private static JSONObject account(String accKey, String paidTier) {
        JSONObject acc = new JSONObject();
        acc.put("id", accKey);
        acc.put("paidTier", paidTier);
        acc.put("tier", "");
        acc.put("project", "project-1");
        return acc;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }

    /** 假上游：按请求里命名的 tier 决定回 200 还是回某个状态码（正文由夹具给定）。 */
    static final class FakeModels implements AntigravityAuth.ModelsPost {
        final List<String> tiers = new ArrayList<String>();
        final List<String> urls = new ArrayList<String>();
        final int failStatus;
        final String failBody;
        boolean failAlways;

        FakeModels(int failStatus, String failBody) {
            this.failStatus = failStatus;
            this.failBody = failBody;
        }

        @Override
        public JSONObject post(String url, String access, JSONObject body) throws Exception {
            String tier = "";
            JSONObject ent = body.optJSONObject("entitlement");
            if (ent != null) tier = ent.optString("userTier", "");
            tiers.add(tier);
            urls.add(url);
            boolean refuses = failStatus >= 400 && (failAlways || !tier.isEmpty());
            if (refuses) throw AntigravityAuth.codeAssistRefused(failStatus, failBody);
            return ok();
        }

        private static JSONObject ok() throws Exception {
            JSONObject m = new JSONObject();
            m.put("displayName", "Gemini 3.1 Pro");
            m.put("maxTokens", 1000000);
            m.put("maxOutputTokens", 65536);
            JSONObject models = new JSONObject();
            models.put("gemini-3.1-pro", m);
            JSONObject out = new JSONObject();
            out.put("models", models);
            return out;
        }
    }
}
