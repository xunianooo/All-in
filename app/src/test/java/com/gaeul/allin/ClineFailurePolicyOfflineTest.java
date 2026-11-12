package com.gaeul.allin;
import com.gaeul.allin.json.JSONObject;

/**
 * 夹具逐条对齐上游 opencode-cline-auth（第一事实源）：
 * {@code source/index.mjs} 的 {@code failure()} 与 {@code source/index.test.mjs}
 * 的 "failure maps the refusals OpenAI's way"、"failure keys on Cline's codes and
 * whole phrases, not loose substrings"。
 *
 * <p>每个用例都同时检查两件事：归一后的状态码，以及绝不回显上游文本（SECRET_MARKER）。
 * 后缀 " // upstream:" 标注上游对应断言；" // derived:" 标注由上游该行的判定式直接推出、
 * 上游没有逐字夹具的用例（403 措辞锚定、401 与 429 的先后）。
 */
public final class ClineFailurePolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }

    static JSONObject classify(int input, String body) {
        JSONObject r = ClineFailurePolicy.classify(input, body);
        check(r.optInt("status") == r.optJSONObject("error").optInt("code"), "status mirrors error.code");
        check(r.getJSONObject("error").optString("type").equals("cline_error"), "type is cline_error");
        check(!r.optJSONObject("error").optString("message").isEmpty(), "message is never empty");
        check(!r.toString().contains("SECRET_MARKER"), "never echoes upstream text");
        return r;
    }

    static String message(int input, String body) {
        return classify(input, body).getJSONObject("error").optString("message");
    }

    static void test(int input, String body, int expected) {
        JSONObject r = classify(input, body);
        check(r.optInt("status") == expected, "status " + r.optInt("status") + " != " + expected + " for " + body);
    }

    /** 一个 JSON 错误体，字段与上游夹具同形。 */
    static String err(String code, String msg) {
        return code.isEmpty()
                ? "{\"error\":{\"message\":\"" + msg + "\"}}"
                : "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}}";
    }

    public static void main(String[] args) {
        // ---- 登录失效：401 一律，403 只在整句写明时 ----
        test(401, err("", "expired token"), 401);                                    // upstream: failure(401,"expired token") → 401
        test(401, err("", "SECRET_MARKER anything at all"), 401);                    // upstream: failure(401,"anything at all") → 401

        test(403, err("", ""), 403);                                                 // upstream: failure(403,"") → 403 ("Forbidden" passes through)
        test(403, err("", "Unauthorized"), 401);                                     // upstream: failure(403,"Unauthorized") → 401  ← 本次修正
        test(403, err("", "invalid_token"), 401);                                    // upstream: 403 整句 invalid_token → 401
        test(403, err("", "not signed in"), 401);                                    // upstream: 403 整句 not signed in → 401
        test(403, err("", "token expired"), 401);                                    // upstream: 403 整句 token expired → 401
        test(403, err("", "access token expired"), 401);                             // upstream: (?:access )?token expired → 401
        test(403, err("", "refresh token expired"), 401);                            // upstream: (?:refresh )?token expired → 401
        test(403, err("", "sign-in"), 401);                                          // upstream: sign[ -]?in 整句 → 401

        // ---- 反向夹具：普通权限 / 参数 / 配额 403 绝不能被误判成登录失效 ----
        test(403, err("", "max_tokens exceeds the model's limit"), 403);             // upstream: 403 that merely mentions "tokens" is not a lapsed sign-in
        test(403, err("", "cline-free/mimo is only available via Cline product surfaces"), 403); // upstream: gated 403 passes through
        test(403, err("", "SECRET_MARKER invalid token in prompt"), 403);            // upstream: 短语只在整句时才算
        test(403, err("", "Error 403: Unauthorized"), 403);                          // derived: 上游用 ^…$ 锚定，带前缀的短语不算
        test(403, err("", "sign-in required"), 403);                                 // derived: 整句不是 "sign-in"
        test(403, err("", "quota exceeded"), 429);                                   // derived: 普通配额是限流，不是登录失效
        test(403, err("", "spend limit"), 429);                                      // derived: 支出上限同上

        // ---- 订阅门槛：账号没问题，缺的是 ClinePass ----
        JSONObject entitlement = classify(403, err("ENTITLEMENT_ERROR", "Error 403: the user is not subscribed to required model plan"));
        check(entitlement.optInt("status") == 403, "entitlement stays 403");         // upstream: ENTITLEMENT_ERROR → 403
        check(entitlement.getJSONObject("error").optString("message").contains("ClinePass"), "says which plan"); // upstream: message contains ClinePass
        test(403, err("", "Error 403: the user is not subscribed to required model plan"), 403); // upstream: gateway wording → 403
        test(403, err("", "no access to clinepass subscription models yet. subscribe to clinepass"), 403); // upstream: NOT_SUBSCRIBED_FORMATTED → 403
        // 代码优先于措辞：订阅的 403 即使正文写着 Unauthorized 也仍是订阅门槛（上游 entitlement 分支在前）
        test(403, err("ENTITLEMENT_ERROR", "Unauthorized"), 403);                    // derived: 上游顺序 entitlement → sign-in

        // ---- 余额：402 与 insufficient_credits，诊断要指向充值 ----
        JSONObject broke = classify(402, err("insufficient_credits", "Insufficient balance. Your Cline Credits balance is $0.00"));
        check(broke.optInt("status") == 429, "out of credits is 429");               // upstream: 402 → 429
        String brokeMsg = broke.getJSONObject("error").optString("message");
        check(brokeMsg.contains("余额不足"), "balance diagnosis");
        check(brokeMsg.contains("app.cline.bot/credits"), "top-up pointer (upstream default buy_credits_url)");
        test(402, err("", "SECRET_MARKER"), 429);                                    // upstream: status 402 alone is the empty account
        test(400, err("Insufficient_Credits", ""), 429);                             // upstream: code insufficient_credits → 429
        test(403, err("insufficient_credits", ""), 429);                             // upstream: 403 + insufficient_credits 是余额，不是登录失效
        test(400, err("", "insufficient_credits"), 429);                             // upstream: 措辞命中 insufficient_credits → 429
        // 反向夹具：句子里的 "credit" 不是余额不足
        test(400, err("", "this credit card was declined by the issuer"), 400);      // upstream: "credit" somewhere in the text is not a rate limit either

        // ---- 限流 / 支出上限 ----
        test(429, err("", "rate limited"), 429);                                     // upstream: failure(429,"rate limited") → 429
        test(429, err("SPEND_LIMIT_EXCEEDED", "org budget spent"), 429);             // upstream: SPEND_LIMIT_EXCEEDED → 429
        test(400, err("", "rate limit exceeded"), 429);                              // derived: 上游限流分支也认措辞（任意状态）
        test(400, err("", "too many requests"), 429);                                // derived: 同上
        // 顺序：401 排在限流之前，所以带 SPEND_LIMIT_EXCEEDED 的 401 仍是 401
        test(401, err("SPEND_LIMIT_EXCEEDED", "org budget spent"), 401);             // derived: 上游 sign-in 分支在 rate 分支之前

        // ---- 其它状态：不认识的码归一 502，正文为空也要有话说 ----
        test(500, err("", "boom"), 500);                                             // upstream: failure(500,"boom") → 500
        test(90, err("", "weird"), 502);                                             // upstream: failure(90,"weird") → 502
        test(503, "", 503);                                                          // upstream: failure(503,"").message is truthy
        test(200, "SECRET_MARKER", 502);                                             // 非 4xx/5xx 输入按 502 处理

        // ---- 路由拒绝：只有整句是上游那句时给 providerOptions 提示 ----
        JSONObject routing = classify(403, "{\"error\":{\"message\":\"client-supplied \\\"providerOptions\\\" model routing is not supported\"}}");
        check(routing.optInt("status") == 403 && routing.getJSONObject("error").optString("message").contains("providerOptions"), "specific routing rejection guidance"); // upstream: ROUTING_REJECTION 整句
        test(403, "{\"error\":{\"message\":\"Error 403: client-supplied \\\"providerOptions\\\" model routing is not supported\"}}", 403); // upstream: 该前缀由这条分支显式允许
        JSONObject unrelated = classify(403, "{\"error\":{\"message\":\"SECRET_MARKER providerOptions not supported for another reason\"}}");
        check(!unrelated.getJSONObject("error").optString("message").contains("disabled client-supplied"), "routing hint only for exact upstream refusal"); // upstream: routingRejected 对前缀/附加词为 false

        System.out.println("ClineFailurePolicyOfflineTest passed");
    }
}
