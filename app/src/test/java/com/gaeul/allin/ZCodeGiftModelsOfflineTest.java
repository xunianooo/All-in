package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ZCodeGift 的模型清单与型号额度，对着上游 {@code bucketModels} /{@code startPlan} /{@code giftSpent}
 * 逐条钉住。合成摘要，不访问网络、不碰任何真实账号。
 *
 * <p>上游事实（packages/zcode index.mjs）：
 * <ul>
 *   <li>{@code bucketModels}：{@code "model:x"} 取 x，**没有冒号的整段就是模型名**，
 *       {@code "tool:web-search"} 这类不算，两头空白去掉；</li>
 *   <li>{@code giftSpent}：有 remaining 就只看它（{@code <= 0} 即花完），否则看 used/total，
 *       两边都算不出来时是 **false**（＝没花完，也就是"未知"不等于"用完"）；</li>
 *   <li>{@code startPlan}：可花模型只取没花完的包的名字；花完的包仍然说得名字，但一个都花不到。</li>
 * </ul>
 *
 * <p>这里钉住的三件事，是这条路径上真会出错的：裸模型名被当成"不是模型"（于是路由退回付费
 * Coding Plan）、未知额度被写成 0（于是卡片说用完了）、以及按名字取额度时口径与清单不一致
 * （于是卡片说能花、探测却报未知）。
 */
public final class ZCodeGiftModelsOfflineTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void eq(Object actual, Object want, String message) {
        if (actual == null ? want != null : !actual.equals(want)) {
            throw new AssertionError(message + ": " + actual + " != " + want);
        }
    }

    /** org.json 的 JSONArray.equals 比的是对象身份，这里按名字逐个比。 */
    static void eqModels(java.util.List<String> actual, String message, String... want) {
        eq(java.util.Arrays.asList(want), actual, message);
    }

    static JSONObject bucket(String remaining, Object... capabilities) throws Exception {
        JSONObject b = new JSONObject().put("capabilities", new JSONArray(capabilities));
        if (remaining != null) b.put("remaining", number(remaining));
        return b;
    }

    /**
     * 摘要里 remaining 的两种写法：整数是 Long、带小数是 Double
     * （{@code ZCodeAuth.giftNumber} 就是按这个规矩写的）。测试必须照着摘要真的会给出的
     * 形状造数据，否则 {@code optLong} 把 0.4 读成 0 这种事在测试里根本不会发生。
     */
    static Object number(String text) {
        if (text.indexOf('.') < 0) return (Object) Long.valueOf(Long.parseLong(text));
        return (Object) Double.valueOf(Double.parseDouble(text));
    }

    static JSONObject summary(JSONObject... buckets) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject b : buckets) arr.put(b);
        return new JSONObject().put("ok", true).put("buckets", arr);
    }

    static JSONArray list(Object... names) {
        JSONArray arr = new JSONArray();
        for (Object n : names) arr.put(n);
        return arr;
    }

    /** 上游 bucketModels 认裸名字：没有冒号的那段就是模型名，额度照读。 */
    static void bareNameIsAModel() throws Exception {
        JSONObject s = summary(new JSONObject()
                .put("capabilities", list("glm-5.3-flash"))
                .put("remaining", 75));
        eqModels(ZCodeGift.allowedModels(s), "a bare name is a gift model", "glm-5.3-flash");
        eq(75L, ZCodeGift.remainingFor(s, "glm-5.3-flash"), "and its amount is read off the same bucket");
        eq(75L, ZCodeGift.remainingFor(s, "GLM-5.3-Flash"), "the name matches whatever case the caller uses");

        // 反例：带别的冒号前缀的不是模型，不能因为尾巴上像模型名就认
        JSONObject tool = summary(new JSONObject()
                .put("capabilities", list("tool:search", "web-search"))
                .put("remaining", 5));
        check(!ZCodeGift.allowedModels(tool).contains("search"), "a tool capability is not a model");
        eq(-1L, ZCodeGift.remainingFor(tool, "search"), "and asks for no amount");
        check(ZCodeGift.allowedModels(tool).contains("web-search"), "while a colon-free name still counts");
    }

    /** model: 前缀与裸名字是同一句话，空白去掉，重复只留一份。 */
    static void prefixAndBareAgree() throws Exception {
        JSONObject s = summary(new JSONObject()
                .put("capabilities", list("model: glm-5.3 ", " glm-5.4 ", "model:glm-5.3", "model:"))
                .put("remaining", 10));
        eqModels(ZCodeGift.allowedModels(s), "the prefixed and the bare spelling end up as the same names",
                "glm-5.3", "glm-5.4");
        eq(10L, ZCodeGift.remainingFor(s, "glm-5.3"), "the prefixed spelling reads its amount");
        eq(10L, ZCodeGift.remainingFor(s, "glm-5.4"), "and so does the bare one");
        eq(-1L, ZCodeGift.remainingFor(s, "glm-5.3-flash"), "a model nobody covers stays unknown");
    }

    /** 未知额度不是 0：算不出剩多少的包照样能花（上游 giftSpent 给 false），也绝不写成 0。 */
    static void unknownIsNotZero() throws Exception {
        // 一个数都没有
        JSONObject bare = summary(new JSONObject().put("capabilities", list("model:glm-5.3")));
        eqModels(ZCodeGift.allowedModels(bare), "a bucket with no numbers is not spent", "glm-5.3");
        eq(-1L, ZCodeGift.remainingFor(bare, "glm-5.3"), "and its amount is unknown, not zero");
        check(ZCodeGift.remainingFor(bare, "glm-5.3") != 0L, "unknown must never read as zero");

        // 只有 used（旧余额只给了一边）
        JSONObject usedOnly = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3")).put("used", 5));
        eqModels(ZCodeGift.allowedModels(usedOnly), "one side of used/total is still not spent", "glm-5.3");
        eq(-1L, ZCodeGift.remainingFor(usedOnly, "glm-5.3"), "and still unknown");

        // 只有 total
        JSONObject totalOnly = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3")).put("total", 100));
        eqModels(ZCodeGift.allowedModels(totalOnly), "the same for a lone total", "glm-5.3");
        eq(-1L, ZCodeGift.remainingFor(totalOnly, "glm-5.3"), "still unknown");

        // 对照：真的用完了，才是 0 —— 这两件事必须给出不同答案
        JSONObject spent = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3")).put("remaining", 0).put("spent", true));
        check(ZCodeGift.allowedModels(spent).isEmpty(), "a spent bucket serves no model");
        eq(0L, ZCodeGift.remainingFor(spent, "glm-5.3"), "and its amount is a real zero");
        check(ZCodeGift.remainingFor(spent, "glm-5.3") != ZCodeGift.remainingFor(bare, "glm-5.3"),
                "a spent bucket and an unreadable one must not read the same");
    }

    /** 摘要说 spent 但 remaining 读不出来时，仍然按花完算（不反过来把花完的包当未知去花钱）。 */
    static void spentFlagWithoutRemaining() throws Exception {
        JSONObject s = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3")).put("spent", true));
        check(ZCodeGift.allowedModels(s).isEmpty(), "the summary's own spent flag closes the route");
        eq(0L, ZCodeGift.remainingFor(s, "glm-5.3"), "with a known zero");
    }

    /** 同一个型号被花完的包和活着的包同时覆盖：活着的那个说了算。 */
    static void spentThenLiveSameModel() throws Exception {
        JSONObject s = summary(
                new JSONObject().put("capabilities", list("model:GLM-5.3-Flash"))
                        .put("remaining", 0).put("spent", true),
                new JSONObject().put("capabilities", list("model:glm-5.3-flash")).put("remaining", 5));
        eqModels(ZCodeGift.allowedModels(s), "the live bucket's spelling is what routes", "glm-5.3-flash");
        eq(5L, ZCodeGift.remainingFor(s, "GLM-5.3-Flash"), "and its amount answers for the model");
    }

    /** 摘要的 models 是卡片已经算好的可花清单：路由照它走，两边不会各说各话。 */
    static void summaryModelsWin() throws Exception {
        JSONObject s = summary(new JSONObject()
                .put("capabilities", list("tool:web-search")).put("remaining", 5));
        s.put("models", list("glm-5.3"));
        s.put("allModels", list("glm-5.3", "glm-5.4"));
        eqModels(ZCodeGift.allowedModels(s), "the card's own list is the route, not a re-parse of capabilities",
                "glm-5.3");
        check(!ZCodeGift.allowedModels(s).contains("glm-5.4"), "a spent model stays out of the route");

        JSONObject empty = summary(new JSONObject().put("capabilities", list("model:glm-5.3")).put("remaining", 0));
        empty.put("models", new JSONArray());
        check(ZCodeGift.allowedModels(empty).isEmpty(), "an empty list is an empty route, not a fallback guess");
    }

    /** 金额不变：这两个函数只读，读数也不因为口径变了而改。 */
    static void amountsUnchanged() throws Exception {
        JSONObject s = summary(
                new JSONObject().put("capabilities", list("model:glm-5.3"))
                        .put("remaining", 100).put("total", 3_000_000).put("used", 2_999_900),
                new JSONObject().put("capabilities", list("glm-5.3-flash")).put("remaining", 7),
                new JSONObject().put("capabilities", list("model:glm-5.3")).put("remaining", 3));
        s.put("remainingUnits", 110);
        String before = s.toString();

        eq(103L, ZCodeGift.remainingFor(s, "glm-5.3"), "the model's buckets add up, unchanged by the rewrite");
        eq(7L, ZCodeGift.remainingFor(s, "glm-5.3-flash"), "the other model keeps its own amount");
        eq(110L, s.optLong("remainingUnits", -1), "the account total is untouched");
        check(ZCodeGift.remainingFor(s, "glm-5.3") != s.optLong("remainingUnits", 0),
                "and the account-wide total still never answers for one model");
        eqModels(ZCodeGift.allowedModels(s), "both models stay routable", "glm-5.3", "glm-5.3-flash");
        eq(before, s.toString(), "neither call mutates the summary");

        eq(-1L, ZCodeGift.remainingFor(null, "glm-5.3"), "no summary is unknown");
        eq(-1L, ZCodeGift.remainingFor(new JSONObject(), "glm-5.3"), "no buckets is unknown");
        check(ZCodeGift.allowedModels(null).isEmpty(), "and no summary serves no model");
        check(ZCodeGift.allowedModels(new JSONObject()).isEmpty(), "empty summary, empty route");
        eq(-1L, ZCodeGift.remainingFor(s, ""), "an empty model name is unknown, not the whole total");
    }

    /**
     * 小数不是 0：剩 0.4 个单位的包是**活着的**（上游 {@code giftSpent} 是
     * {@code num(remaining_units) <= 0} → false）。
     *
     * <p>这里钉住修之前的那个真错：{@code optLong} 把 0.4 读成 0，于是这个包被判成花完、
     * 它覆盖的型号从可花清单里消失，请求掉到付费 Coding Plan 上 —— 赠送还没用完就开始付钱。
     *
     * <p>同时钉住另一半：<b>不许谎报 0</b>。整单位装不下 0.4，那就报未知（-1），
     * 不能一边说"能花这个型号"一边说"剩 0"。
     */
    static void fractionIsNotSpent() throws Exception {
        // 正例：0.4 个单位。摘要里它是 Double（giftNumber 写的），不是整数。
        JSONObject live = summary(bucket("0.4", "model:glm-5.3"));
        eqModels(ZCodeGift.allowedModels(live), "0.4 units left is not spent: the model still routes", "glm-5.3");
        eq(0.4, ZCodeGift.remainingUnitsFor(live, "glm-5.3"), "and the exact amount is 0.4, not 0");
        eq(-1L, ZCodeGift.remainingFor(live, "glm-5.3"),
                "whole units cannot hold 0.4, so it reports unknown instead of a made-up 0");
        check(ZCodeGift.remainingFor(live, "glm-5.3") != 0L, "a live sub-unit bucket must never read as zero");

        // 正例：12.5 也一样是活着的，精确值照样读得出
        JSONObject half = summary(bucket("12.5", "model:glm-5.3-flash"));
        eqModels(ZCodeGift.allowedModels(half), "12.5 left still routes", "glm-5.3-flash");
        eq(12.5, ZCodeGift.remainingUnitsFor(half, "glm-5.3-flash"), "and the amount keeps its fraction");
        eq(-1L, ZCodeGift.remainingFor(half, "glm-5.3-flash"), "the whole-unit reader says unknown, not 12 and not 13");

        // 反例：真的用完了才是 0。0 与 0.4 必须给出不同答案，两个读法都要一致。
        JSONObject spent = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3"))
                .put("remaining", number("0")).put("spent", true));
        check(ZCodeGift.allowedModels(spent).isEmpty(), "a real zero serves no model");
        eq(0.0, ZCodeGift.remainingUnitsFor(spent, "glm-5.3"), "a real zero stays zero");
        eq(0L, ZCodeGift.remainingFor(spent, "glm-5.3"), "and reads as a whole zero");
        check(ZCodeGift.remainingFor(spent, "glm-5.3") != ZCodeGift.remainingFor(live, "glm-5.3"),
                "spent and still-alive must not answer the same");

        // 反例：读不出来是未知（既不是花完，也不是 0）
        JSONObject unknown = summary(new JSONObject().put("capabilities", list("model:glm-5.3")));
        eqModels(ZCodeGift.allowedModels(unknown), "an unreadable balance still routes", "glm-5.3");
        eq(null, ZCodeGift.remainingUnitsFor(unknown, "glm-5.3"), "with no exact amount");
        eq(-1L, ZCodeGift.remainingFor(unknown, "glm-5.3"), "and an unknown whole amount, never 0");

        // 摘要自己标了 remainderUnknown 时 remaining 只是占位的 -1：仍然未知、仍然能花
        JSONObject placeholder = summary(new JSONObject()
                .put("capabilities", list("model:glm-5.3"))
                .put("remaining", number("-1")).put("remainderUnknown", true).put("spent", false));
        eqModels(ZCodeGift.allowedModels(placeholder), "the -1 placeholder is not a spend", "glm-5.3");
        eq(null, ZCodeGift.remainingUnitsFor(placeholder, "glm-5.3"), "and it stays unknown");
        eq(-1L, ZCodeGift.remainingFor(placeholder, "glm-5.3"), "never 0, never -1-as-a-number");
    }

    /**
     * 同一型号被多个包覆盖：可读的相加；其中有一个读不出来，整份就是未知（不部分少报）；
     * 小数相加正好凑成整数时按浮点噪声容差认（0.4+0.6 是 1，不是 0.999…）。
     */
    static void mixedBuckets() throws Exception {
        // 整数混合：加起来还是整数
        JSONObject whole = summary(
                bucket("100", "model:glm-5.3"),
                bucket("3", "glm-5.3"),
                bucket("0", "model:glm-5.3").put("spent", true));
        eqModels(ZCodeGift.allowedModels(whole), "a spent sibling does not close a live model", "glm-5.3");
        eq(103.0, ZCodeGift.remainingUnitsFor(whole, "glm-5.3"), "the live buckets add up, the spent one adds 0");
        eq(103L, ZCodeGift.remainingFor(whole, "glm-5.3"), "and a whole total still reads as whole units");

        // 小数 + 小数正好是整数：0.4 + 0.6 的浮点噪声不能把答案弄成"未知"
        JSONObject tenths = summary(
                bucket("0.4", "model:glm-5.3"),
                bucket("0.6", "model:glm-5.3"));
        check(ZCodeGift.allowedModels(tenths).contains("glm-5.3"), "both halves are live");
        eq(1.0, ZCodeGift.remainingUnitsFor(tenths, "glm-5.3"), "0.4+0.6 is exactly one unit");
        eq(1L, ZCodeGift.remainingFor(tenths, "glm-5.3"), "and the whole-unit reader may say 1");

        // 小数 + 整数凑不成整数：精确值给得出，整单位报未知（不截成 12、不进成 13）
        JSONObject mixed = summary(
                bucket("12.5", "model:glm-5.3"),
                bucket("7", "model:glm-5.3"));
        eq(19.5, ZCodeGift.remainingUnitsFor(mixed, "glm-5.3"), "the exact total keeps the fraction");
        eq(-1L, ZCodeGift.remainingFor(mixed, "glm-5.3"), "a fractional total has no honest whole-unit answer");

        // 反例：其中一个读不出来 → 整份未知，不能只报读得出的那 7
        JSONObject partial = summary(
                bucket("7", "model:glm-5.3"),
                new JSONObject().put("capabilities", list("model:glm-5.3")));
        eq(null, ZCodeGift.remainingUnitsFor(partial, "glm-5.3"), "one unreadable sibling makes the whole answer unknown");
        eq(-1L, ZCodeGift.remainingFor(partial, "glm-5.3"), "not 7, which would under-report the gift");
        check(ZCodeGift.remainingFor(partial, "glm-5.3") != 0L, "and certainly not 0");

        // 反例：花完的包 + 活着的 0.4：路由看活着的那个，可花清单里它在
        JSONObject liveAfterSpent = summary(
                new JSONObject().put("capabilities", list("model:GLM-5.3-Flash"))
                        .put("remaining", number("0")).put("spent", true),
                bucket("0.4", "model:glm-5.3-flash"));
        eqModels(ZCodeGift.allowedModels(liveAfterSpent), "a live 0.4 sibling keeps the route open", "glm-5.3-flash");
        eq(0.4, ZCodeGift.remainingUnitsFor(liveAfterSpent, "GLM-5.3-Flash"), "with 0.4 of it left");
        eq(-1L, ZCodeGift.remainingFor(liveAfterSpent, "GLM-5.3-Flash"), "and 0.4 is never reported as 0 spent");

        // 反例：一个覆盖该型号的桶都没有 → 未知（不是 0，也不是合计）
        JSONObject elsewhere = summary(bucket("0.4", "model:glm-5.4"));
        eq(null, ZCodeGift.remainingUnitsFor(elsewhere, "glm-5.3"), "an uncovered model is unknown");
        eq(-1L, ZCodeGift.remainingFor(elsewhere, "glm-5.3"), "still unknown for whole units");
    }

    public static void main(String[] args) throws Exception {
        bareNameIsAModel();
        prefixAndBareAgree();
        unknownIsNotZero();
        spentFlagWithoutRemaining();
        spentThenLiveSameModel();
        summaryModelsWin();
        amountsUnchanged();
        fractionIsNotSpent();
        mixedBuckets();
        System.out.println("ZCodeGiftModelsOfflineTest passed");
    }
}
