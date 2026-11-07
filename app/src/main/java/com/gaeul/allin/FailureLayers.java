package com.gaeul.allin;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * 供应商状态的“层”与失败的“类”，只负责解释与展示。
 *
 * <p>分工：{@link UpstreamFailure} 只回答“要不要换账号、能不能重放”，参与路由与冷却；
 * 本类只回答“哪一层坏了、还影不影响推理、换别的模型还行不行、用户该怎么办”，
 * 喂给面板与 {@code /api/test-model}。两者之间只有 {@link #fromUpstream} 一条单向映射，
 * 避免出现两套互相漂移的词表（405 曾经在两处分别被判成 UNKNOWN 与 OTHER，
 * 面板按 errorKind 看和按 refused 看会得出相反结论）。
 *
 * <p>一条硬规则：<b>不把不同原因折叠成一句“查询失败”</b>。缺省是 unknown，
 * 不能是“连接超时或未配置有效 Key”这类伪确定结论——把未知写成确定，用户会去换
 * 本来没坏的密钥。
 */
public final class FailureLayers {
    private FailureLayers() { }

    /**
     * 状态分层。前五层是供应商自己的，NETWORK 是本机到上游这一跳（不属于供应商哪一层，
     * 单独列出来正是为了不把本机网络问题算到账号头上）。
     */
    public enum Layer { LOGIN, ACTIVITY, BALANCE, CATALOG, INFERENCE, NETWORK, NONE }

    /** 失败分类。粒度只到“查出来的原因不同、处置不同”，不再往下拆。 */
    public enum Kind {
        DNS, TLS, TIMEOUT, REFUSED, NETWORK,
        AUTH, RESTRICTED, NOT_FOUND, METHOD_NOT_ALLOWED,
        RATE, QUOTA, BUSINESS, SCHEMA, EMPTY_REPLY,
        UNREAD, UNKNOWN_EXECUTION, LOCAL_REJECTION, COOLDOWN, NO_CANDIDATE,
        OTHER, NONE
    }

    /** 把一个传输层异常判成具体的类；未知异常返回 OTHER，传 null 返回 NONE。 */
    public static Kind transport(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof UnknownHostException) return Kind.DNS;
            if (t instanceof SSLException) return Kind.TLS;
            if (t instanceof SocketTimeoutException) return Kind.TIMEOUT;
            if (t instanceof ConnectException) return Kind.REFUSED;
            if (t instanceof NoRouteToHostException) return Kind.NETWORK;
            Throwable next = t.getCause();
            if (next == t) break;
            t = next;
        }
        return e == null ? Kind.NONE : Kind.OTHER;
    }

    /** 传输层判定结果的字符串形态，直接进 JSON。 */
    public static String transportName(Throwable e) { return name(transport(e)); }

    /** 只按 HTTP 状态判类，不看正文。 */
    public static Kind http(int status) {
        if (status == 401) return Kind.AUTH;
        if (status == 403) return Kind.RESTRICTED;
        if (status == 404) return Kind.NOT_FOUND;
        if (status == 405) return Kind.METHOD_NOT_ALLOWED;
        if (status == 429) return Kind.RATE;
        if (status == 402) return Kind.QUOTA;
        if (status <= 0) return Kind.OTHER;
        return Kind.OTHER;
    }

    /** 路由层的失败词表映射到展示词表。这是两张词表之间唯一的桥。 */
    public static Kind fromUpstream(UpstreamFailure.Kind k) {
        if (k == null) return Kind.NONE;
        switch (k) {
            case RATE: return Kind.RATE;
            case ACCOUNT_THROUGHPUT: return Kind.RATE;
            case QUOTA: return Kind.QUOTA;
            case AUTH: return Kind.AUTH;
            case REQUEST: return Kind.BUSINESS;
            case OVERLOAD: return Kind.OTHER;
            case NETWORK: return Kind.NETWORK;
            case PROXY: return Kind.NETWORK;
            case UNKNOWN_EXECUTION: return Kind.UNKNOWN_EXECUTION;
            default: return Kind.OTHER;
        }
    }

    /** 结构化错误码优先；429 保持既有 rate 契约。 */
    public static Kind fromResponse(int status, String body) {
        if (status <= 0) return Kind.OTHER;
        if (status == 429) return Kind.RATE;
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        boolean hasError = false;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(body == null ? "" : body);
            org.json.JSONObject error = obj.optJSONObject("error");
            Object code = error != null ? error.opt("code") : null;
            if (code == null || code == org.json.JSONObject.NULL) code = obj.opt("code");
            if (code != null && code != org.json.JSONObject.NULL) {
                String c = String.valueOf(code).toLowerCase(Locale.ROOT);
                if (c.equals("insufficient_quota") || c.equals("quota_exceeded")
                        || c.equals("insufficient_balance") || c.equals("insufficient_credits")) return Kind.QUOTA;
                if (c.equals("model_not_found") || c.equals("11102")) return Kind.NOT_FOUND;
                if (c.equals("rate_limit_exceeded")) return Kind.RATE;
                if (c.equals("invalid_api_key") || c.equals("authentication_error")) return Kind.AUTH;
                if (!c.equals("0") && !c.equals("200") && !c.equals("ok") && !c.equals("success")) {
                    Kind statusKind = http(status);
                    return statusKind == Kind.OTHER ? Kind.BUSINESS : statusKind;
                }
            }
            hasError = obj.has("error") && !obj.isNull("error") && !Boolean.FALSE.equals(obj.opt("error"));
            hasError |= Boolean.FALSE.equals(obj.opt("success")) || Boolean.FALSE.equals(obj.opt("ok"));
            org.json.JSONObject data = obj.optJSONObject("data");
            if (data != null) hasError |= (data.has("error") && !data.isNull("error")
                    && !Boolean.FALSE.equals(data.opt("error"))) || Boolean.FALSE.equals(data.opt("success"));
        } catch (Exception ignored) { }
        Kind k = http(status);
        if (status == 400 || status == 404) {
            if (text.contains("model not found") || text.contains("model_not_found")
                    || text.contains("unknown model") || text.contains("invalid model")
                    || text.contains("no such model") || text.contains("model not exist")
                    || text.contains("service info not found") || text.contains("model unavailable")
                    || text.contains("模型不存在") || text.contains("模型不可用")
                    || text.contains("不支持该模型") || text.contains("请切换模型")) return Kind.NOT_FOUND;
        }
        if (k == Kind.OTHER && (text.contains("insufficient_quota") || text.contains("exceed quota")
                || text.contains("insufficient balance") || text.contains("insufficient credits"))) return Kind.QUOTA;
        return hasError && k == Kind.OTHER ? Kind.BUSINESS : k;
    }

    /** 这个词属于哪一层。传输层四类统一归 NETWORK，不算供应商的账。 */
    public static Layer layer(Kind k) {
        if (k == null) return Layer.NONE;
        switch (k) {
            case DNS: case TLS: case TIMEOUT: case REFUSED: case NETWORK: return Layer.NETWORK;
            case AUTH: case RESTRICTED: case NO_CANDIDATE: case COOLDOWN: return Layer.LOGIN;
            case QUOTA: return Layer.BALANCE;
            case NOT_FOUND: case METHOD_NOT_ALLOWED: return Layer.CATALOG;
            case RATE: case BUSINESS: case SCHEMA: case EMPTY_REPLY:
            case UNKNOWN_EXECUTION: case LOCAL_REJECTION: case OTHER: return Layer.INFERENCE;
            default: return Layer.NONE;
        }
    }

    /** 仅凭类别无法判断供应商整体推理或其他模型是否可用。null 表示未知。 */
    public static Boolean affectsInference(Kind k) { return null; }

    public static Boolean canTryOtherModel(Kind k) { return null; }

    /** 这一层的中文名，面板直接用。 */
    public static String layerLabel(Layer l) {
        if (l == null) return "未分层";
        switch (l) {
            case LOGIN: return "登录与凭据";
            case ACTIVITY: return "可领取活动";
            case BALANCE: return "已有额度";
            case CATALOG: return "模型与端点";
            case INFERENCE: return "实际推理";
            case NETWORK: return "本机到上游";
            default: return "未分层";
        }
    }

    /** 这个类的中文短名。 */
    public static String label(Kind k) {
        if (k == null) return "未分类";
        switch (k) {
            case DNS: return "域名解析失败";
            case TLS: return "TLS 握手或证书失败";
            case TIMEOUT: return "超时";
            case REFUSED: return "连接被拒绝";
            case NETWORK: return "链路不通";
            case AUTH: return "认证或凭据被拒";
            case RESTRICTED: return "请求被拒绝 (403)";
            // 不带 HTTP 码：上游常用 400 说"没有这个模型"，写死 404 会自相矛盾
            case NOT_FOUND: return "模型或端点不存在";
            case METHOD_NOT_ALLOWED: return "方法不被接受 (405)";
            case RATE: return "请求受限流";
            case QUOTA: return "额度或余额不足";
            case BUSINESS: return "业务码错误";
            case SCHEMA: return "回包结构认不出";
            case EMPTY_REPLY: return "回包没有任何正文";
            case UNREAD: return "没读出来（不是零）";
            case UNKNOWN_EXECUTION: return "已发出但结果未知";
            case LOCAL_REJECTION: return "本地拦下，未发出";
            case COOLDOWN: return "本地冷却中";
            case NO_CANDIDATE: return "没有可用凭据";
            case OTHER: return "未归类的上游错误";
            case NONE: return "没有失败";
            default: return "未分类";
        }
    }

    /**
     * 一句话可执行的建议。只说这一步该干什么、不该冤枉什么，
     * 不含上游正文、不含凭据，可以直接进面板。
     */
    public static String advice(Kind k) {
        if (k == null) return "";
        switch (k) {
            case DNS: return "域名没解析出来：检查本机网络和 DNS。";
            case TLS: return "TLS 握手或证书未通过：检查代理、系统时间和证书链。";
            case TIMEOUT: return "连接或读取超时：检查网络和超时设置；若请求已发出，先确认执行结果。";
            case REFUSED: return "连接被拒绝：检查地址、端口和网络策略。";
            case NETWORK: return "本机到上游这一跳没通：先查网络和代理，别动账号。";
            case AUTH: return "上游拒了凭据：这个账号或 Key 需要重新登录、刷新或更换。";
            case RESTRICTED: return "供应商拒绝了这次请求：检查凭据、模型权限及供应商返回的原因。";
            case NOT_FOUND: return "这个模型或地址对不上：换模型、核对模型名与 baseUrl，别急着换账号或 Key。";
            case METHOD_NOT_ALLOWED: return "端点不接受这个方法：可能是协议选错（OpenAI 与 Anthropic 走反了），改协议而不是换账号。";
            case RATE: return "上游限流：检查 Retry-After 和限流范围，再决定何时重试。";
            case QUOTA: return "上游报告额度或余额问题：检查对应通道的额度与计费状态。";
            case BUSINESS: return "上游返回业务错误：根据错误码和详情检查请求及配置。";
            case SCHEMA: return "回包结构认不出来：可能是上游改了协议，先记下来，别当成账号故障。";
            case EMPTY_REPLY: return "上游回了 200 但没有任何正文：可能只回了思考、被回空或报文结构变了，不能据此判定可用。";
            case UNREAD: return "这一项没读出来，不代表是零、也不代表没有：稍后再读一次，别拿它当结论。";
            case UNKNOWN_EXECUTION: return "请求已经发出但没拿到可判读的结果：先别重放（可能已经计费），换一家确认。";
            case LOCAL_REJECTION: return "本地就拦下了，没发出去：看预算或策略设置，与上游无关。";
            case COOLDOWN: return "本地正在冷却这一家，这次没有请求上游：等到点即可。";
            case NO_CANDIDATE: return "没有可用凭据：先登录或加 Key，或把这个供应商的开关打开。";
            // 没有失败就不要给建议：空话会让面板看起来像出了问题
            case NONE: return "";
            default: return "错误尚未归类：结合状态码、错误详情及执行状态排查。";
        }
    }

    /** 从字符串还原，未知非空类别返回 OTHER——展示代码不该因为一个名字不认识就抛异常。 */
    public static Kind fromName(String name) {
        if (name == null || name.isEmpty()) return Kind.NONE;
        try {
            return Kind.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Kind.OTHER;
        }
    }

    /** 枚举名的小写形态，直接进 JSON。 */
    public static String name(Kind k) { return k == null ? "none" : k.name().toLowerCase(Locale.ROOT); }

    /** 层名的小写形态，直接进 JSON。 */
    public static String name(Layer l) { return l == null ? "none" : l.name().toLowerCase(Locale.ROOT); }

    /**
     * 把一次失败的展示字段一次写进 JSON：kind、layer、是否影响推理、能否换模型、建议。
     * 调用方只需要再补自己的 statusCode 与原文。
     */
    public static void describe(org.json.JSONObject into, Kind k) { describe(into, k, null); }

    /**
     * 同 {@link #describe(org.json.JSONObject, Kind)}，但层由调用方指明。
     * 同一个 Kind 在不同上下文属于不同层：没读出来的是“可领取活动”，
     * 还是“已有额度”，只有发起这次查询的人知道。
     */
    public static void describe(org.json.JSONObject into, Kind k, Layer layerOverride) {
        if (into == null) return;
        try {
            Layer l = layerOverride != null ? layerOverride : layer(k);
            into.put("errorKind", name(k));
            into.put("failureLayer", name(l));
            into.put("failureLayerLabel", layerLabel(l));
            into.put("failureLabel", label(k));
            into.put("affectsInference", org.json.JSONObject.NULL);
            into.put("canTryOtherModel", org.json.JSONObject.NULL);
            String a = advice(k);
            if (!a.isEmpty()) into.put("advice", a);
            else into.remove("advice");
        } catch (Exception ignored) { }
    }
    /** 保留已有分类和 transportKind，仅在执行结果未知时覆盖执行建议。 */
    public static void describeExecution(org.json.JSONObject into, boolean sent, boolean unknown) {
        if (into == null) return;
        try {
            if (sent && unknown) into.put("advice", advice(Kind.UNKNOWN_EXECUTION));
        } catch (Exception ignored) { }
    }

}
