package com.gaeul.allin;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public final class ClineAuthPolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        JSONObject a = new JSONObject().put("token", "old").put("refresh", "r").put("expires", 1);
        AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        ClineAuth.RefreshTransport transport = r -> {
            calls.incrementAndGet(); Thread.sleep(40);
            ClineAuth.SignIn n = new ClineAuth.SignIn(); n.access = "new"; n.refresh = "rotated";
            n.expires = System.currentTimeMillis() + 600000; return n;
        };
        Runnable task = () -> { try { start.await(); if (!ClineAuth.refreshAccount(a, transport)) failures.incrementAndGet(); } catch (Exception e) { failures.incrementAndGet(); } };
        Thread x = new Thread(task), y = new Thread(task); x.start(); y.start(); start.countDown(); x.join(); y.join();
        check(failures.get() == 0 && calls.get() == 1 && "new".equals(a.optString("token")));
        JSONObject b = new JSONObject().put("token", "old").put("refresh", "r").put("expires", 1);
        ClineAuth.RefreshCandidate candidate = ClineAuth.refreshCandidate(b, false, transport);
        check(candidate != null && candidate.matches(b) && "old".equals(b.optString("token")));
        b.put("refresh", "replacement"); check(!candidate.matches(b));
        check(ClineAuth.refreshCandidate(a, false, transport) == null);
        check(ClineAuth.refreshCandidate(a, true, transport) != null);
        check(ClineAuth.refreshCandidate(b, false, r -> { throw new Exception("SECRET_MARKER"); }) == null);
        requestLeadTiming();

        JSONObject rec = new JSONObject("{\"data\":{\"recommended\":[{\"model_id\":\"maker/vision:fast\",\"display_name\":\"Vision Fast\"}],\"free\":[{\"key\":\"maker/text\",\"free\":true}],\"clinePass\":[{\"slug\":\"cline-pass/vision\"},{\"slug\":\"cline-pass/vision-clear\"}]}} ");
        JSONObject cloud = new JSONObject("{\"data\":["
                + "{\"id\":\"maker/vision:fast\",\"context_length\":64000,\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}},"
                + "{\"id\":\"maker/vision\",\"input_modalities\":[\"text\",\"image\"]},"
                + "{\"id\":\"maker/text\",\"architecture\":{\"input_modalities\":[\"text\"]}},"
                + "{\"id\":\"maker/vision-alt\",\"input_modalities\":[\"text\"]},"
                + "{\"id\":\"other/vision\",\"input_modalities\":[\"text\"]},"
                + "{\"id\":\"maker/vision-clear\",\"input_modalities\":[\"text\",\"image\"]}]} ");
        JSONObject meta = new JSONObject();
        java.util.List<String> models = ClineAuth.fetchFeedStrict(meta, url -> url.endsWith("recommended-models") ? rec : cloud);
        check(models.size() == 8 && models.contains("maker/vision:fast") && models.contains("maker/text")
                && models.contains("cline-pass/vision") && models.contains("cline-pass/vision-clear"), "all recommendation groups and aliases");
        check("Vision Fast".equals(meta.getJSONObject("maker/vision:fast").optString("name")), "display_name");
        check(meta.getJSONObject("maker/vision:fast").optInt("context") == 64000, "context retained");
        check(meta.getJSONObject("maker/vision:fast").getJSONObject("input").optBoolean("image"), "direct image capability");
        check(!meta.getJSONObject("cline-pass/vision").has("input"), "ambiguous bare model name remains unknown");
        check(meta.getJSONObject("cline-pass/vision-clear").getJSONObject("input").optBoolean("image"), "unambiguous maker id match");
        check(!meta.getJSONObject("maker/text").getJSONObject("input").optBoolean("image"), "explicit text only");
        check(!meta.getJSONObject("cline-pass/vision-clear").getJSONObject("input").optBoolean("video"), "complete modality flags");
        System.out.println("ClineAuthPolicyOfflineTest passed");
    }

    /**
     * 请求前预检的临期时机，必须对齐插件的<b>请求内</b> threshold，不能拿宿主的
     * proactive hook 顶替。
     *
     * <p>上游 {@code opencode-cline-auth/source/index.mjs:25}：
     * {@code const REFRESH_LEAD = 5 * 60 * 1000 // a token this close to its end is
     * refreshed before a request}。请求前那条判定在 {@code index.mjs:967}／
     * {@code index.mjs:972}：{@code expires - Date.now() > REFRESH_LEAD} 才照用，否则先换。
     *
     * <p>而 {@code index.mjs:26} 的 {@code RENEW_LEAD = 10 * 60 * 1000} 只经
     * {@code index.mjs:1073} 的 {@code refreshLead: RENEW_LEAD} 交给宿主，由宿主在请求
     * 之外主动调 {@code auth.refresh}；{@code index.mjs:1066-1071} 的注释说得很清楚：
     * 请求内那条检查是留给不调这个 hook 的客户端的。All-in 走的就是请求内那条，
     * 所以这里钉的是 5 分钟，不是 10 分钟。
     */
    static void requestLeadTiming() throws Exception {
        AtomicInteger spent = new AtomicInteger();
        ClineAuth.RefreshTransport counting = r -> {
            spent.incrementAndGet();
            ClineAuth.SignIn n = new ClineAuth.SignIn();
            n.access = "rotated-access"; n.refresh = "rotated-refresh";
            n.expires = System.currentTimeMillis() + 3600000L;
            return n;
        };
        long now = System.currentTimeMillis();

        // 剩 4 分钟（< REFRESH_LEAD，index.mjs:25）：插件会先换，这里必须换。
        JSONObject near = new JSONObject().put("token", "t").put("refresh", "r4").put("expires", now + 240000L);
        ClineAuth.RefreshCandidate nearCandidate = ClineAuth.refreshCandidate(near, false, counting);
        check(nearCandidate != null, "4 分钟剩余必须按 REFRESH_LEAD 先换（index.mjs:25 / 967）");
        check(spent.get() == 1, "临期时正好花一次一次性凭据");
        check("t".equals(near.optString("token")), "refreshCandidate 不就地改账号");

        // 剩 6 分钟（> REFRESH_LEAD）：插件照用，这里不许花掉一次性凭据。
        JSONObject still = new JSONObject().put("token", "t2").put("refresh", "r6").put("expires", now + 360000L);
        check(ClineAuth.refreshCandidate(still, false, counting) == null, "6 分钟剩余照用，不该换（index.mjs:967）");
        check(spent.get() == 1, "6 分钟剩余不许花掉一次性凭据");

        // 负对照：若误把宿主的 RENEW_LEAD（index.mjs:26，10 分钟）当成请求内 threshold，
        // 剩 9 分钟就会被换掉。这里钉住“不换”，证明两条判据没有混在一起。
        JSONObject nine = new JSONObject().put("token", "t3").put("refresh", "r9").put("expires", now + 540000L);
        check(ClineAuth.refreshCandidate(nine, false, counting) == null, "10 分钟是宿主 proactive hook，不是请求内 threshold");
        check(spent.get() == 1, "9 分钟剩余不许花掉一次性凭据");

        // 同一个门槛的另一个入口（legacy adapter）也得是 5 分钟。
        JSONObject six = new JSONObject().put("token", "t6").put("refresh", "r6b").put("expires", now + 360000L);
        check(ClineAuth.refreshAccount(six, counting), "未临期时 refreshAccount 直接放行");
        check(spent.get() == 1 && "t6".equals(six.optString("token")), "未临期时 refreshAccount 不动凭据");
        JSONObject four = new JSONObject().put("token", "t4").put("refresh", "r4b").put("expires", now + 240000L);
        check(ClineAuth.refreshAccount(four, counting), "临期时 refreshAccount 换到新对");
        check(spent.get() == 2 && "rotated-access".equals(four.optString("access")), "临期刷新确实落了新 access");

        // force 入口不受临期门槛约束：什么时候换由调用方的围栏逻辑决定。
        JSONObject forced = new JSONObject().put("token", "t7").put("refresh", "r7").put("expires", now + 3600000L);
        check(ClineAuth.refreshCandidate(forced, true, counting) != null && spent.get() == 3, "force 不走临期门槛");
    }
}
