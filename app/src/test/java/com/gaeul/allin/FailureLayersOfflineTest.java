package com.gaeul.allin;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.json.JSONObject;

/**
 * 失败分层与分类的离线回归。
 *
 * <p>这里守的两条线：
 * <ol>
 *   <li>不同原因保留相应类别，并避免超出证据的结论；</li>
 *   <li>网络、认证、额度、目录、推理各自落到该落的层，别把本机断网算到账号头上。</li>
 * </ol>
 */
public final class FailureLayersOfflineTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void eq(Object actual, Object want, String message) {
        if (actual == null ? want != null : !actual.equals(want)) {
            throw new AssertionError(message + ": " + actual + " != " + want);
        }
    }

    static void transport() {
        eq(FailureLayers.transport(new UnknownHostException("x")), FailureLayers.Kind.DNS, "unknown host");
        eq(FailureLayers.transport(new SSLException("x")), FailureLayers.Kind.TLS, "ssl");
        eq(FailureLayers.transport(new SocketTimeoutException("x")), FailureLayers.Kind.TIMEOUT, "timeout");
        eq(FailureLayers.transport(new ConnectException("x")), FailureLayers.Kind.REFUSED, "connect refused");
        eq(FailureLayers.transport(new IOException("wrap", new UnknownHostException("inner"))),
                FailureLayers.Kind.DNS, "cause chain");
        eq(FailureLayers.transport(new IOException("plain")), FailureLayers.Kind.OTHER, "generic io");
        eq(FailureLayers.transport(null), FailureLayers.Kind.NONE, "nothing");
        // 外层是 TLS 就先算 TLS：握手没成，里层的连接异常只是它的原因
        eq(FailureLayers.transport(new SSLException(new ConnectException("c"))),
                FailureLayers.Kind.TLS, "outer wins");
        eq(FailureLayers.transportName(new UnknownHostException("x")), "dns", "transport name");
    }

    static void http() {
        eq(FailureLayers.http(401), FailureLayers.Kind.AUTH, "401");
        eq(FailureLayers.http(403), FailureLayers.Kind.RESTRICTED, "403");
        eq(FailureLayers.http(404), FailureLayers.Kind.NOT_FOUND, "404");
        eq(FailureLayers.http(405), FailureLayers.Kind.METHOD_NOT_ALLOWED, "405");
        eq(FailureLayers.http(429), FailureLayers.Kind.RATE, "429");
        eq(FailureLayers.http(402), FailureLayers.Kind.QUOTA, "402");
        eq(FailureLayers.http(0), FailureLayers.Kind.OTHER, "no status");
        eq(FailureLayers.http(500), FailureLayers.Kind.OTHER, "500 has no finer word");
        // 404 与 405 不是同一件事：端点搬走了和方法用错了，处置不同
        check(FailureLayers.http(404) != FailureLayers.http(405), "404 and 405 must not collapse");
    }

    static void response() {
        eq(FailureLayers.fromResponse(200, "{\"choices\":[]}"), FailureLayers.Kind.OTHER, "plain 200");
        eq(FailureLayers.fromResponse(200, "{\"error\":{\"message\":\"x\"}}"),
                FailureLayers.Kind.BUSINESS, "200 carrying an error");
        eq(FailureLayers.fromResponse(400, "{\"error\":{\"code\":\"insufficient_quota\"}}"),
                FailureLayers.Kind.QUOTA, "quota words under 400");
        eq(FailureLayers.fromResponse(404, "{}"), FailureLayers.Kind.NOT_FOUND, "404 body ignored");
        eq(FailureLayers.fromResponse(401, ""), FailureLayers.Kind.AUTH, "401");
        // 模型名的错要在「模型与端点」层里，别让人去换账号
        eq(FailureLayers.fromResponse(400, "{\"error\":{\"message\":\"model not found\"}}"),
                FailureLayers.Kind.NOT_FOUND, "400 saying the model is unknown");
        eq(FailureLayers.fromResponse(400, "{\"error\":{\"message\":\"bad request\"}}"),
                FailureLayers.Kind.BUSINESS, "structured error is business");
        // 实测 WorkBuddy 的 11102：400 + "service info not found" + displayMsg 中文
        eq(FailureLayers.fromResponse(400, "{\"code\":11102,\"msg\":\"model [x] service info not found\","
                        + "\"displayMsg\":{\"zh\":\"\u5f53\u524d\u6a21\u578b\u4e0d\u53ef\u7528\uff0c\u8bf7\u5207\u6362\u6a21\u578b\"}}"),
                FailureLayers.Kind.NOT_FOUND, "workbuddy's unknown model");
    }

    static void layers() {
        eq(FailureLayers.layer(FailureLayers.Kind.DNS), FailureLayers.Layer.NETWORK, "dns layer");
        eq(FailureLayers.layer(FailureLayers.Kind.TLS), FailureLayers.Layer.NETWORK, "tls layer");
        eq(FailureLayers.layer(FailureLayers.Kind.AUTH), FailureLayers.Layer.LOGIN, "auth layer");
        eq(FailureLayers.layer(FailureLayers.Kind.RESTRICTED), FailureLayers.Layer.LOGIN, "restricted layer");
        eq(FailureLayers.layer(FailureLayers.Kind.QUOTA), FailureLayers.Layer.BALANCE, "quota layer");
        eq(FailureLayers.layer(FailureLayers.Kind.NOT_FOUND), FailureLayers.Layer.CATALOG, "404 layer");
        eq(FailureLayers.layer(FailureLayers.Kind.METHOD_NOT_ALLOWED), FailureLayers.Layer.CATALOG, "405 layer");
        eq(FailureLayers.layer(FailureLayers.Kind.RATE), FailureLayers.Layer.INFERENCE, "rate layer");
        eq(FailureLayers.layer(FailureLayers.Kind.EMPTY_REPLY), FailureLayers.Layer.INFERENCE, "empty layer");
        eq(FailureLayers.layer(FailureLayers.Kind.NONE), FailureLayers.Layer.NONE, "no layer");

        check(FailureLayers.affectsInference(FailureLayers.Kind.DNS) == null, "overall inference unknown");
        check(FailureLayers.canTryOtherModel(FailureLayers.Kind.AUTH) == null, "other model unknown");
    }

    static void describe() {
        JSONObject out = new JSONObject();
        FailureLayers.describe(out, FailureLayers.Kind.DNS);
        eq(out.getString("errorKind"), "dns", "kind");
        eq(out.getString("failureLayer"), "network", "layer");
        eq(out.getString("failureLayerLabel"), "本机到上游", "layer label");
        check(out.isNull("affectsInference"), "overall inference unknown");
        check(out.isNull("canTryOtherModel"), "other model unknown");
        check(!out.getString("advice").isEmpty(), "advice given");

        // 同一个 Kind 在不同上下文属于不同层：gift 摘要读不出来是「可领取活动」
        JSONObject gift = new JSONObject();
        FailureLayers.describe(gift, FailureLayers.Kind.UNREAD, FailureLayers.Layer.ACTIVITY);
        eq(gift.getString("errorKind"), "unread", "unread kind");
        eq(gift.getString("failureLayer"), "activity", "overridden layer");
        eq(FailureLayers.layer(FailureLayers.Kind.UNREAD), FailureLayers.Layer.NONE, "unread has no default layer");

        JSONObject auth = new JSONObject();
        FailureLayers.describe(auth, FailureLayers.Kind.AUTH);
        check(auth.isNull("canTryOtherModel"), "auth does not determine other models");

        // 两张词表之间只有一条桥，映射不能自己漂移
        eq(FailureLayers.fromUpstream(UpstreamFailure.Kind.AUTH), FailureLayers.Kind.AUTH, "auth bridge");
        eq(FailureLayers.fromUpstream(UpstreamFailure.Kind.QUOTA), FailureLayers.Kind.QUOTA, "quota bridge");
        eq(FailureLayers.fromUpstream(UpstreamFailure.Kind.RATE), FailureLayers.Kind.RATE, "rate bridge");
        eq(FailureLayers.fromUpstream(UpstreamFailure.Kind.UNKNOWN_EXECUTION),
                FailureLayers.Kind.UNKNOWN_EXECUTION, "unknown execution bridge");
        eq(FailureLayers.fromUpstream(null), FailureLayers.Kind.NONE, "null bridge");
        for (UpstreamFailure.Kind k : UpstreamFailure.Kind.values()) {
            check(FailureLayers.fromUpstream(k) != null, "no upstream kind may map to null: " + k);
        }
    }

    static void nothingFolded() {
        eq(FailureLayers.transport(new IllegalArgumentException("bad schema")), FailureLayers.Kind.OTHER, "non network exception");
        eq(FailureLayers.http(-1), FailureLayers.Kind.OTHER, "negative status");
        eq(FailureLayers.fromResponse(400, "credit card invalid"), FailureLayers.Kind.OTHER, "credit is not exhausted quota");
        eq(FailureLayers.fromResponse(400, "balance endpoint unavailable"), FailureLayers.Kind.OTHER, "balance is not exhausted quota");
        eq(FailureLayers.fromResponse(400, "quota information unavailable"), FailureLayers.Kind.OTHER, "quota is not exhausted quota");
        eq(FailureLayers.fromResponse(403, "{\"error\":{\"code\":\"model_not_found\"}}"), FailureLayers.Kind.NOT_FOUND, "structured code before HTTP");
        eq(FailureLayers.fromResponse(429, "{\"error\":{\"code\":\"insufficient_quota\"}}"), FailureLayers.Kind.RATE, "429 compatibility");
        check(!FailureLayers.label(FailureLayers.Kind.RESTRICTED).contains("账号"), "403 is request scoped");
        JSONObject out = new JSONObject();
        FailureLayers.describe(out, FailureLayers.Kind.TIMEOUT);
        out.put("transportKind", "timeout");
        FailureLayers.describeExecution(out, true, true);
        eq(out.getString("transportKind"), "timeout", "transport preserved");
        eq(out.getString("errorKind"), "timeout", "category preserved");
        eq(out.getString("advice"), FailureLayers.advice(FailureLayers.Kind.UNKNOWN_EXECUTION), "unknown execution guidance");
        check(out.isNull("affectsInference") && out.isNull("canTryOtherModel"), "scope remains unknown");
    }

    public static void main(String[] args) {
        if (args.length == 0 || "transport".equals(args[0])) transport();
        if (args.length == 0 || "http".equals(args[0])) http();
        if (args.length == 0 || "response".equals(args[0])) response();
        if (args.length == 0 || "layers".equals(args[0])) layers();
        if (args.length == 0 || "describe".equals(args[0])) describe();
        if (args.length == 0 || "fold".equals(args[0])) nothingFolded();
        System.out.println("FailureLayersOfflineTest passed");
    }
}
