package com.gaeul.allin;

import org.json.JSONObject;
import java.util.Locale;

/** Provider-specific, privacy-safe classification of Cline chat refusals. */
public final class ClineFailurePolicy {
    private ClineFailurePolicy() {}

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
        String safe;
        if (code.equals("entitlement_error") || low.contains("the user is not subscribed to required model plan")
                || low.contains("no access to clinepass subscription models yet. subscribe to clinepass")) {
            status = 403; safe = "该模型需要 ClinePass 订阅";
        } else if (status == 402 || code.equals("insufficient_credits") || low.contains("insufficient_credits")
                || (low.contains("insufficient balance") && low.contains("cline credits balance"))) {
            status = 429; safe = "Cline 账号余额不足";
        } else if (status == 429 || code.equals("spend_limit_exceeded")) {
            status = 429; safe = "Cline 用量或支出限额已达到";
        } else if (status == 401) {
            safe = "Cline 请求认证被拒绝，请检查账号登录状态";
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
