package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 赠送额度的**小数**在两个真实出口上钉住：{@code GET /api/providers/billing?provider=zcode}
 * 的 allowance 窗口，以及 {@code POST /api/test-model} 的 {@code giftRemaining}。
 * 全程合成数据：摘要走 {@link ZCodeGiftControl} 的测试钩子，赠送发送走本地 fixture，
 * 不碰网络、不碰任何真实账号。
 *
 * <p>事实源是上游 {@code packages/zcode index.mjs}：
 * <ul>
 *   <li>{@code giftSpent}：{@code num(x.remaining_units) <= 0} 才算花完 —— 浮点读，
 *       {@code 0.4} 是「还剩一点」，所以这个包还能花、型号在 {@code startPlan} 的
 *       {@code models} 里，请求真的会走赠送；</li>
 *   <li>{@code startPlan}：多个计划可以同时生效，同一个型号被多个包覆盖时那些**活着的**包
 *       都算（这里按覆盖该型号的桶相加）；</li>
 *   <li>余额里根本没有 {@code remaining_units}、只有一边的数时，金额是**未知**，既不是 0
 *       也不是「用完」。</li>
 * </ul>
 *
 * <p>这个测试要挡住的是把小数按整数处理的两个方向，以及「未知」被折成「0」：
 * <ul>
 *   <li><b>截成 0</b>：0.4 个单位在面板与账本上变成「剩 0」，用户以为赠送没了去买付费
 *       Plan，而路由其实还在走赠送 —— 谎报用完；</li>
 *   <li><b>进成 1</b>：多报一份不存在的额度；</li>
 *   <li><b>未知转 0</b>：读不出来的桶（{@code remaining:-1} + {@code remainderUnknown}）
 *       被写成 0，等于把「不知道」说成「已用完」。</li>
 * </ul>
 *
 * <p>对应的正反两面都断言：小数走赠送且原样显示（不掉到付费 Plan）；真 0 是真用完（窗口
 * 里就是 0，探测如实回落 Plan）；未知不显示成 0，更不因此改路由去花付费额度。
 */
public final class ZCodeGiftDecimalUnitsHttpTest {
    static final String JWT = "fixture-jwt", DEVICE = "fixture-device";
    static final String MODEL = "glm-5.3-flash", OTHER = "glm-5.4";
    static final String PROBE = "zcode/" + MODEL;

    /** Codex/zcode 的 Plan 回包：只要 200 与一个能读的正文，用于证明「落到了付费 Plan」。 */
    static final String PLAN_BODY = "{\"id\":\"fixture-plan\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static void eq(Object actual, Object want, String message) {
        if (actual == null ? want != null : !actual.equals(want)) {
            throw new AssertionError(message + ": " + actual + " != " + want);
        }
    }

    static boolean near(double actual, double want) { return Math.abs(actual - want) <= 1e-9; }

    /** 摘要里 remaining 的两种写法：整数是 Long、带小数是 Double（{@code ZCodeAuth.giftNumber}）。 */
    static Object amount(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15) return Long.valueOf((long) value);
        return Double.valueOf(value);
    }

    /**
     * 一个桶，形状照着 {@code ZCodeAuth.giftSummary} 真的会给出的样子：
     * {@code remaining}（{@code -1} 表示读不出来）、{@code remainderUnknown}、{@code spent}、
     * {@code capabilities}。
     */
    static JSONObject bucket(double remaining, String model, boolean unknown, boolean spent) throws Exception {
        return new JSONObject()
                .put("capabilities", new JSONArray().put("model:" + model))
                .put("remaining", amount(remaining))
                .put("remainderUnknown", unknown)
                .put("spent", spent);
    }

    static JSONObject summary(JSONObject... buckets) throws Exception {
        JSONArray all = new JSONArray();
        for (JSONObject b : buckets) all.put(b);
        return new JSONObject().put("ok", true).put("claimable", new JSONArray()).put("buckets", all);
    }

    static void setSummary(final JSONObject data) {
        ZCodeGiftControl.setTestHooks(null, new ZCodeGiftControl.Transport() {
            public JSONObject summary(String jwt, String device) { return data; }
            public JSONObject claim(String jwt, String device, String plan) { throw new AssertionError("claim"); }
        });
    }

    // ===== 出口一：/api/providers/billing?provider=zcode 的 allowance 窗口 =====

    static NanoHTTPD.IHTTPSession billingRequest(String providerId) {
        final NanoHTTPD.IHTTPSession base = RoutingSetupOfflineTest.request(
                "127.0.0.1", "/api/providers/billing", "GET", "", null);
        return (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(
                ZCodeGiftDecimalUnitsHttpTest.class.getClassLoader(),
                new Class[]{NanoHTTPD.IHTTPSession.class}, (p, m, a) -> {
                    if ("getParameters".equals(m.getName()))
                        return Collections.singletonMap("provider", Collections.singletonList(providerId));
                    return m.invoke(base, a);
                });
    }

    static JSONObject billingConfig(String... models) throws Exception {
        JSONArray list = new JSONArray();
        for (String m : models) list.put(m);
        JSONObject account = new JSONObject().put("id", "z1").put("name", "one").put("jwt", JWT).put("device", DEVICE);
        JSONObject provider = new JSONObject().put("id", "zcode").put("enabled", true)
                .put("models", list).put("accounts", new JSONArray().put(account));
        return new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-gateway"))
                .put("providers", new JSONArray().put(provider));
    }

    /** 打一次 billing 接口，返回解析后的 JSON；窗口怎么摆由摘要决定。 */
    static JSONObject billing(JSONObject snapshot, String... models) throws Exception {
        setSummary(snapshot);
        GatewayServer server = RoutingSetupOfflineTest.server(billingConfig(models));
        NanoHTTPD.Response response = server.serve(billingRequest("zcode"));
        check(response.getStatus().getRequestStatus() == 200, "billing must answer 200");
        return new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));
    }

    static JSONObject window(JSONObject bill, String model) throws Exception {
        JSONObject account = bill.getJSONArray("accounts").getJSONObject(0);
        JSONObject allowance = account.optJSONObject("allowance");
        if (allowance == null) return null;
        JSONArray windows = allowance.optJSONArray("windows");
        for (int i = 0; windows != null && i < windows.length(); i++) {
            JSONObject w = windows.getJSONObject(i);
            if (model.equalsIgnoreCase(w.optString("name", ""))) return w;
        }
        return null;
    }

    // ===== 出口二：/api/test-model 的 giftRemaining =====

    static final class Fixture extends NanoHTTPD implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        volatile String body = "{\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}";

        Fixture(String what) throws IOException { super("127.0.0.1", 0); start(SOCKET_READ_TIMEOUT, true); }

        @Override public Response serve(IHTTPSession session) {
            // 每个 POST 的 body 都先读掉：keep-alive 下没读的 body 会被当成下一个请求。
            try { if (session.getMethod() == Method.POST) session.parseBody(new HashMap<String, String>()); }
            catch (Exception e) { throw new IllegalStateException("fixture body unreadable", e); }
            calls.incrementAndGet();
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json", body);
            response.addHeader("Connection", "close");
            return response;
        }

        public void close() { stop(); }
    }

    static final class Gateway extends NanoHTTPD {
        final GatewayServer gateway;

        Gateway(JSONObject cfg, int planPort) throws Exception {
            super("127.0.0.1", 0);
            gateway = RoutingSetupOfflineTest.server(cfg);
            gateway.setGiftTransportForTest(null);   // 真的走 ZCodeGift.send，但目标是本地 fixture
            gateway.setVendorConnectionsForTests(
                    url -> NetworkPolicy.openHttp("http://127.0.0.1:" + planPort + new URL(url).getFile()));
        }

        @Override public Response serve(IHTTPSession session) { return gateway.serve(session); }
    }

    static JSONObject probe(Gateway gateway) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + gateway.getListeningPort() + "/api/test-model").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-all-in-management", GatewayManagementCredential.get());
        try (OutputStream out = c.getOutputStream()) {
            out.write(new JSONObject().put("model", PROBE).toString().getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream source = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        try (InputStream in = source) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) body.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        String text = body.toString("UTF-8");
        check(!text.contains(JWT) && !text.contains(DEVICE), "probe leaked credentials: " + text);
        return new JSONObject(text);
    }

    // ===== 场景 =====

    /**
     * 正例：0.4 个单位。
     *
     * <p>窗口里必须就是 {@code 0.4}（不是 0，也不是 1），探测也要报 {@code giftRemaining: 0.4}，
     * 并且**一次都没有打到付费 Plan** —— 这正是「按 long 截成 0」会毁掉的东西。
     */
    static void fractionIsKept(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject snapshot = summary(bucket(0.4, MODEL, false, false));

        JSONObject bill = billing(snapshot, MODEL);
        JSONObject w = window(bill, MODEL);
        check(w != null, "0.4 live bucket must produce a window: " + bill);
        eq("model", w.getString("scope"), "the window is per model");
        eq("gift_units", w.getString("unit"), "in gift units");
        check(near(w.getDouble("remaining"), 0.4), "0.4 must stay 0.4, not 0 and not 1: " + w);
        check(w.toString().contains("\"remaining\":0.4"), "the wire value is 0.4, not a rounded integer: " + bill);

        int planCalls = plan.calls.get();
        setSummary(snapshot);
        JSONObject r = probe(gateway);
        check(r.optBoolean("ok"), "0.4 means the gift can still spend: " + r);
        eq("gift", r.getString("billing"), "the probe must use the gift, not the paid plan");
        check(r.has("giftRemaining"), "the probe reports how much is left: " + r);
        check(near(r.getDouble("giftRemaining"), 0.4), "and it is 0.4, not 0: " + r);
        check(r.toString().contains("\"giftRemaining\":0.4"), "the wire value is 0.4: " + r);
        eq(planCalls, plan.calls.get(), "0.4 must never fall back to the paid plan");
    }

    /** 正例：多桶合成 —— 同一个型号被两个活着的包覆盖时相加，小数照样保留。 */
    static void sumAcrossBuckets(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject sum = summary(bucket(0.4, MODEL, false, false), bucket(0.6, MODEL, false, false));
        JSONObject bill = billing(sum, MODEL);
        JSONObject w = window(bill, MODEL);
        check(w != null, "two live buckets for one model still make a window: " + bill);
        // 0.4+0.6 是浮点噪声下的整单位：按 ZCodeAuth.giftNumber 的规矩写整数，不写 0.999…
        check(near(w.getDouble("remaining"), 1.0), "0.4+0.6 is one unit: " + w);
        check(w.toString().contains("\"remaining\":1"), "written as the integer 1: " + bill);

        JSONObject mixed = summary(bucket(0.4, MODEL, false, false), bucket(12.5, MODEL, false, false),
                bucket(99, OTHER, false, false));
        JSONObject mixedBill = billing(mixed, MODEL, OTHER);
        JSONObject mw = window(mixedBill, MODEL);
        check(mw != null, "the mixed total still has a window: " + mixedBill);
        check(near(mw.getDouble("remaining"), 12.9), "0.4+12.5 must be 12.9, not 12 (truncated) or 13: " + mw);
        check(!mw.toString().contains("\"remaining\":12,"), "never truncated to the whole part: " + mixedBill);
        JSONObject ow = window(mixedBill, OTHER);
        check(ow != null && near(ow.getDouble("remaining"), 99), "the other model keeps its own bucket: " + mixedBill);

        int planCalls = plan.calls.get();
        setSummary(mixed);
        JSONObject r = probe(gateway);
        eq("gift", r.getString("billing"), "a summed gift is still the gift: " + r);
        check(r.has("giftRemaining") && near(r.getDouble("giftRemaining"), 12.9),
                "the probe sums the same buckets, decimals kept: " + r);
        eq(planCalls, plan.calls.get(), "a summed gift must not fall back to the paid plan");
    }

    /** 反例：真 0 是真用完 —— 窗口里就是 0，探测如实回落付费 Plan，且不报 giftRemaining。 */
    static void realZeroIsSpent(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject snapshot = summary(bucket(0, MODEL, false, true));

        JSONObject bill = billing(snapshot, MODEL);
        JSONObject w = window(bill, MODEL);
        check(w != null, "a spent bucket still states a real zero: " + bill);
        check(near(w.getDouble("remaining"), 0), "a real zero stays zero: " + w);
        check(w.toString().contains("\"remaining\":0"), "and it is written as 0: " + bill);

        int giftCalls = gift.calls.get(), planCalls = plan.calls.get();
        setSummary(snapshot);
        JSONObject r = probe(gateway);
        check(!r.has("giftRemaining"), "a spent gift reports nothing left, not a fake zero: " + r);
        eq("plan", r.optString("billing"), "a spent gift falls back to the paid plan: " + r);
        eq(giftCalls, gift.calls.get(), "nothing was sent to the gift");
        eq(planCalls + 1, plan.calls.get(), "exactly one plan attempt");
    }

    /** 反例：整个型号的额度未知 —— 不补 0，也不因此判定用完去花付费额度。 */
    static void unknownIsNotZero(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject snapshot = summary(bucket(-1, MODEL, true, false));

        JSONObject bill = billing(snapshot, MODEL);
        check(window(bill, MODEL) == null, "an unreadable bucket must not become a window: " + bill);
        JSONObject account = bill.getJSONArray("accounts").getJSONObject(0);
        eq("unknown", account.optString("errorKind"), "the account says unknown, not zero: " + bill);
        check(!account.has("allowance") && !bill.toString().contains("\"remaining\":0"),
                "unknown must never be written as a zero: " + bill);

        int planCalls = plan.calls.get(), giftCalls = gift.calls.get();
        setSummary(snapshot);
        JSONObject r = probe(gateway);
        eq("gift", r.getString("billing"), "unknown is not spent: the gift still serves the model: " + r);
        check(r.optBoolean("ok"), "the gift request itself succeeded: " + r);
        check(!r.has("giftRemaining"), "an unknown amount is left out, never written as 0: " + r);
        check(!r.toString().contains("\"giftRemaining\":0"), "no zero on the wire either: " + r);
        eq(giftCalls + 1, gift.calls.get(), "the gift was tried once");
        eq(planCalls, plan.calls.get(), "unknown must not push the request onto the paid plan");
    }

    /**
     * 反例：同一型号的多个桶里只要有一个读不出来，整份合计就得报未知。
     * 只报读得出的那 0.4 是少报 —— 少报本身不会多花钱，但会让用户以为额度快没了；
     * 这里要求「要么如实相加，要么不报」，不能挑着报。
     */
    static void partialUnknownIsNotAReport(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject snapshot = summary(bucket(0.4, MODEL, false, false), bucket(-1, MODEL, true, false));

        JSONObject bill = billing(snapshot, MODEL);
        check(window(bill, MODEL) == null, "one unreadable sibling makes the sum unknown: " + bill);
        eq("unknown", bill.getJSONArray("accounts").getJSONObject(0).optString("errorKind"),
                "and the account says so instead of 0.4 or 0: " + bill);

        int planCalls = plan.calls.get();
        setSummary(snapshot);
        JSONObject r = probe(gateway);
        eq("gift", r.getString("billing"), "the readable sibling still keeps the gift route open: " + r);
        check(!r.has("giftRemaining"), "but no partial amount is reported: " + r);
        eq(planCalls, plan.calls.get(), "a partial unknown never falls back to the paid plan");
    }

    /** 反例：这个型号一个桶都不覆盖 —— 未知，不是 0；探测如实说明原因并回落 Plan。 */
    static void uncoveredModelIsUnknown(Fixture gift, Fixture plan, Gateway gateway) throws Exception {
        JSONObject snapshot = summary(bucket(12.5, OTHER, false, false));

        JSONObject bill = billing(snapshot, MODEL, OTHER);
        check(window(bill, MODEL) == null, "an uncovered model has no window: " + bill);
        check(!bill.toString().contains("\"remaining\":0,") && !bill.toString().endsWith("\"remaining\":0}"),
                "and certainly not a zero window: " + bill);
        JSONObject other = window(bill, OTHER);
        check(other != null && near(other.getDouble("remaining"), 12.5), "the covered model still shows 12.5: " + bill);

        int giftCalls = gift.calls.get(), planCalls = plan.calls.get();
        setSummary(snapshot);
        JSONObject r = probe(gateway);
        eq("plan", r.optString("billing"), "an uncovered model goes to the plan: " + r);
        eq("model_not_in_gift", r.optString("giftFallbackReason"), "and says why, honestly: " + r);
        check(!r.has("giftRemaining"), "nothing is claimed about an unknown amount: " + r);
        eq(giftCalls, gift.calls.get(), "the gift was not asked to serve a model it does not cover");
        eq(planCalls + 1, plan.calls.get(), "exactly one plan attempt");
    }

    public static void main(String[] args) throws Exception {
        try (Fixture gift = new Fixture("gift"); Fixture plan = new Fixture("plan")) {
            plan.body = PLAN_BODY;
            ZCodeGift.setFixtureBase("http://127.0.0.1:" + gift.getListeningPort());
            final JSONObject cfg = ZCodeGiftUsageHttpTest.config(plan.getListeningPort());
            final String before = cfg.toString();
            Gateway gateway = new Gateway(cfg, plan.getListeningPort());
            gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            try {
                fractionIsKept(gift, plan, gateway);
                sumAcrossBuckets(gift, plan, gateway);
                realZeroIsSpent(gift, plan, gateway);
                unknownIsNotZero(gift, plan, gateway);
                partialUnknownIsNotAReport(gift, plan, gateway);
                uncoveredModelIsUnknown(gift, plan, gateway);
                eq(before, cfg.toString(), "the fixture config was not rewritten");
                System.out.println("ZCodeGiftDecimalUnitsHttpTest passed");
            } finally {
                gateway.stop();
                gateway.gateway.stop();
            }
        } finally {
            ZCodeGift.setFixtureBase(null);
            ZCodeGift.resetCooldownForTests();
            ZCodeGift.resetBusyForTests();
            ZCodeGiftControl.setTestHooks(null, null);
        }
    }
}
