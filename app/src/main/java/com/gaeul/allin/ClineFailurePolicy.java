package com.gaeul.allin;

import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provider-specific, privacy-safe classification of Cline chat refusals.
 *
 * <p>读法与上游插件 opencode-cline-auth 的 {@code failure()} 逐条对齐（第一事实源：
 * {@code source/index.mjs}，夹具 {@code source/index.test.mjs} 的 "failure maps the refusals
 * OpenAI's way" / "failure keys on Cline's codes and whole phrases, not loose substrings"）。
 * 三件事必须同上游一致，否则用户看到的是错的诊断：
 *
 * <ol>
 *   <li><b>顺序</b>：路由拒绝 → 订阅门槛(ENTITLEMENT/未订阅) → 余额(402/insufficient_credits)
 *       → 登录失效(401，以及措辞明确的 403) → 限流/支出上限(429)。登录失效排在限流之前，
 *       所以带 {@code SPEND_LIMIT_EXCEEDED} 的 401 仍然是 401，不是 429。</li>
 *   <li><b>403 不等于登录失效</b>：上游只把"整句就是登录失效措辞"的 403 归一成 401
 *       （{@code /^(unauthorized|invalid[ _]token|sign[ -]?in|not signed in|token (?:expired|invalid|revoked)|(?:access |refresh )?token expired)$/i}）。
 *       普通权限/配额/参数 403（{@code max_tokens exceeds…}、{@code … only available via Cline
 *       product surfaces}、{@code quota exceeded}）一律保持自己的类别，绝不能被当成退出登录。</li>
 *   <li><b>短语是整句、代码是原词</b>：{@code rate limit}/{@code too many requests}/
 *       {@code quota exceeded}/{@code spend limit} 只在整词边界内算限流；{@code credit}
 *       出现在"credit card declined"里不算余额不足。</li>
 * </ol>
 *
 * <p>与上游唯一的差别是输出：上游把网关原文（含余额数字、buy_credits_url）回给调用方，
 * 这里只给"安全的自有文案"，从不回显上游文本。
 */
public final class ClineFailurePolicy {
    private ClineFailurePolicy() {}

    /** 上游 index.mjs 的 NOT_SUBSCRIBED：带 ClinePass 的模型而账号没订阅。 */
    private static final String NOT_SUBSCRIBED = "the user is not subscribed to required model plan";
    /** 上游 index.mjs 的 NOT_SUBSCRIBED_FORMATTED：同一件事的另一种措辞。 */
    private static final String NOT_SUBSCRIBED_FORMATTED =
            "no access to clinepass subscription models yet. subscribe to clinepass";

    /** 整句即登录失效（上游 SIGNED_OUT 的等价式；{@code matches()} 自带首尾锚定）。 */
    private static final Pattern SIGNED_OUT = Pattern.compile(
            "(?:unauthorized|invalid[ _]token|sign[ -]?in|not signed in|token (?:expired|invalid|revoked)"
                    + "|(?:access |refresh )?token expired)");

    /** 限流或支出上限，按整词匹配（上游 {@code \b(?:rate limit|…)\b} 的等价式）。 */
    private static final Pattern LIMIT = Pattern.compile(
            "\\b(?:rate limit|too many requests|quota exceeded|spend limit)\\b");

    /** Cline 拒收客户端自带 providerOptions 路由（2026-11-06 起），整句才认。 */
    private static final Pattern ROUTING_REJECTION = Pattern.compile(
            "(?s)^(?:error\\s+403:\\s*)?client-supplied [\"“]provideroptions[\"”] model routing is not supported\\.?$");

    /** Returns {status, error:{type,code,message}}; never returns upstream text. */
    public static JSONObject classify(int httpStatus, String body) {
        int status = httpStatus >= 400 && httpStatus <= 599 ? httpStatus : 502;
        String code = "", message = "";
        try {
            JSONObject outer = new JSONObject(body == null ? "" : body);
            JSONObject inner = outer.optJSONObject("error");
            code = inner == null ? "" : inner.optString("code", "");
            if (code.isEmpty()) code = outer.optString("code", "");
            if (code.isEmpty() && inner != null) code = inner.optString("error", "");
            message = inner == null ? outer.optString("message", "") : inner.optString("message", outer.optString("message", ""));
        } catch (Exception ignored) {}
        code = code.toLowerCase(Locale.ROOT);
        String low = message.toLowerCase(Locale.ROOT);
        String whole = low.trim();
        String safe;
        if (status == 403 && ROUTING_REJECTION.matcher(whole).matches()) {
            safe = "Cline has disabled client-supplied providerOptions model routing; remove providerOptions and retry";
        } else if (code.equals("entitlement_error") || whole.contains(NOT_SUBSCRIBED) || whole.contains(NOT_SUBSCRIBED_FORMATTED)) {
            // 账号本身没问题，缺的是那份订阅：绝不能报成余额不足或登录失效。
            status = 403; safe = "该模型需要 ClinePass 订阅";
        } else if (status == 402 || code.equals("insufficient_credits") || low.contains("insufficient_credits")
                || (low.contains("insufficient balance") && low.contains("cline credits balance"))) {
            status = 429; safe = "Cline 账号余额不足，请到 app.cline.bot/credits 充值";
        } else if (status == 401 || (status == 403 && SIGNED_OUT.matcher(whole).matches())) {
            // 上游在这里把"措辞明确的 403"归一成 401：调用方据此重新登录，而普通 403 不会。
            status = 401; safe = "Cline 登录已失效，请重新登录";
        } else if (status == 429 || code.equals("spend_limit_exceeded") || LIMIT.matcher(low).find()) {
            status = 429; safe = "Cline 用量或支出限额已达到";
        } else {
            safe = "Cline 请求失败 (HTTP " + status + ")";
        }
        JSONObject result = new JSONObject(), error = new JSONObject();
        try {
            error.put("type", "cline_error"); error.put("code", status); error.put("message", safe);
            result.put("status", status); result.put("error", error);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return result;
    }
}
