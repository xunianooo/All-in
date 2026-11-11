package com.gaeul.allin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cline 刷新围栏判定的离线回归。
 *
 * <p>被验证的核心命题只有一条：<b>哪些失败能证明一次性 refresh token 一个字节都没交付</b>。
 * 这条命题依赖一个前提 —— 凭据只出现在请求体里，请求头里没有。前提本身在这里被测，不是假设：
 * <ol>
 *   <li>{@link #bodyOnlyPremise()}：核对请求头映射、核对核对原语本身、再到线上请求头里找凭据，
 *       并用一个“故意把凭据塞进请求头”的正对照证明这几次检查不是空转；</li>
 *   <li>{@link #stageSplit()}：把 {@code open}（连链路对象都没有）与 {@code connect}
 *       （有了链路、拿不到写体的流）分成两个阶段，两者都必须是“未交付”；</li>
 *   <li>{@link #redirectNotFollowed()}：307/308 不得自动跟随。负对照按旧写法（不关重定向）
 *       复现 JDK 把请求体重发到第二台主机，证明这条断言有牙齿；</li>
 *   <li>真实环回 + 合成故障覆盖其余阶段，并钉住旧 API 与错误文案的兼容性。</li>
 * </ol>
 */
public final class ClineAuthRefreshFenceOfflineTest {

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** 负对照用的标记：只要它出现在异常说明里，就算泄漏。 */
    static final String SECRET = "SECRET-REFRESH-c0ffee";

    /** 线上真正会发的那个凭据值；用它去请求头里找，找不到才算前提成立。 */
    static final String TOKEN = "cline-refresh-token-7f3a9c";

    public static void main(String[] args) throws Exception {
        bodyOnlyPremise();
        stageSplit();
        redirectNotFollowed();
        wireShapeAndSuccess();
        httpRejection();
        unansweredSocket();
        connectRefused();
        preSendStages();
        partialWrite();
        responseTimeout();
        reportGate();
        legacyCompatibility();
        refusalEvidence();
        refusedValueIsNotSpent();
        unknownNeverClaimsRefusal();
        System.out.println("ClineAuthRefreshFenceOfflineTest passed");
    }

    // ── 前提：凭据只在请求体里 ────────────────────────────────────────

    /**
     * “写体之前失败＝凭据未交付”只在凭据不进请求头时成立。这里把前提从口头声明
     * 变成三条可执行证据，外加一条正对照防止检查本身失效。
     */
    static void bodyOnlyPremise() throws Exception {
        // 1. 生产用的请求头映射里没有凭据。
        Map<String, String> headers = ClineAuth.refreshHeaders();
        check(!ClineAuth.headersCarry(TOKEN, headers),
                "production refresh headers must not carry the refresh token");
        check("application/json".equals(headers.get("Content-Type")), "content type preserved");
        check("application/json".equals(headers.get("Accept")), "accept preserved");
        // 头集合与改动前等价：旧的 2 个固定头 + clientHeaders(null) 一个不少。
        Map<String, String> expected = new LinkedHashMap<String, String>(ClineAuth.clientHeaders(null));
        expected.put("Content-Type", "application/json");
        expected.put("Accept", "application/json");
        check(expected.equals(headers), "header set unchanged apart from ordering: " + headers.keySet());
        check(!headers.containsKey("Authorization"), "refresh never sends an Authorization header");

        // 2. 核对原语本身：键、值、空值都要判对，否则上面那条是空转。
        Map<String, String> plantedValue = new LinkedHashMap<String, String>();
        plantedValue.put("X-Trace", "prefix-" + TOKEN + "-suffix");
        check(ClineAuth.headersCarry(TOKEN, plantedValue), "token planted in a header value is detected");
        Map<String, String> plantedKey = new LinkedHashMap<String, String>();
        plantedKey.put(TOKEN, "v");
        check(ClineAuth.headersCarry(TOKEN, plantedKey), "token planted in a header name is detected");
        check(!ClineAuth.headersCarry("", headers), "an empty token cannot be carried");
        check(!ClineAuth.headersCarry(null, headers), "a null token cannot be carried");
        check(!ClineAuth.headersCarry(TOKEN, null), "a missing header map carries nothing");
        check(!ClineAuth.headersCarry(TOKEN, new LinkedHashMap<String, String>()),
                "an empty header map carries nothing");

        // 3. 线上：真实环回抓到的请求头里没有凭据，凭据只出现在请求体里。
        try (Loopback server = new Loopback(200,
                "{\"data\":{\"accessToken\":\"a\",\"refreshToken\":\"r2\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}}")) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            try {
                ClineAuth.refreshToken(TOKEN);
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
            String head = server.headText(), body = server.bodyText();
            check(!head.contains(TOKEN), "the refresh token must not appear in the request head: " + head);
            check(!lower(head).contains("authorization"), "no authorization header on the wire");
            check(body.contains(TOKEN), "the refresh token must travel in the body: " + body);
        }

        // 4. 正对照：同一个环回、同样的抓取方式，把头里故意塞上凭据就会被抓到。
        //    这说明第 3 步的“找不到”是真的找不到，而不是抓取器没工作。
        try (Loopback server = new Loopback(200, "{}")) {
            server.start();
            HttpURLConnection conn = open("http://127.0.0.1:" + server.port() + "/probe");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("X-Trace", TOKEN);
            try {
                OutputStream os = conn.getOutputStream();
                os.write("{}".getBytes("UTF-8"));
                os.close();
                conn.getResponseCode();
            } catch (IOException ignored) {
                // 只要请求头到达就行，答复本身与这条正对照无关。
            } finally {
                conn.disconnect();
            }
            check(server.headText().contains(TOKEN),
                    "the head capture must see a planted token, otherwise the check above is vacuous");
        }
    }

    // ── 阶段拆分：open 与 connect ────────────────────────────────────

    /**
     * {@code open} 与 {@code connect} 是两件事：前者连链路对象都没拿到，后者拿到了链路、
     * 只是拿不到写体的流。两者都必须是“未交付”，但阶段名必须不同，否则读证据的一方
     * 无法区分“连都没连上”和“连上了但没写体”。
     */
    static void stageSplit() throws Exception {
        ClineAuth.setRefreshConnectionsForTests(url -> {
            throw new IOException("no connection object");
        });
        ClineAuth.RefreshReport open;
        try {
            open = ClineAuth.refreshReport(account(TOKEN), true);
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
        check(open.failure != null && "open".equals(open.failure.stage),
                "a factory failure is the open stage: " + (open.failure == null ? "none" : open.failure.stage));
        check(open.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "open stage is NOT_SENT");
        check(open.failure.credentialChannel == ClineAuth.CredentialChannel.BODY_ONLY,
                "the body-only premise is declared on the evidence");
        check(open.failure.provablyNotSent(), "open stage proves nothing was delivered");
        check(open.fenceReleasable(), "the fence gate accepts a proven open stage");
        check(releaseFence(open), "open stage releases the fence");

        ClineAuth.setRefreshConnectionsForTests(url -> new StreamFails(url));
        ClineAuth.RefreshReport connect;
        try {
            connect = ClineAuth.refreshReport(account(TOKEN), true);
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
        check(connect.failure != null && "connect".equals(connect.failure.stage),
                "a missing output stream is the connect stage: "
                        + (connect.failure == null ? "none" : connect.failure.stage));
        check(connect.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "connect stage is NOT_SENT");
        check(!connect.failure.bodyWriteStarted && !connect.failure.bodyWritten, "the body never started");
        check(connect.failure.credentialChannel == ClineAuth.CredentialChannel.BODY_ONLY,
                "connect is only safe because the credential is body-only");
        check(connect.failure.provablyNotSent(), "connect stage proves nothing was delivered");
        check(releaseFence(connect), "connect stage releases the fence");
        check(!"open".equals(connect.failure.stage), "the two pre-send stages stay distinguishable");

        // 两个阶段都必须可重试，且都不带凭据原文。
        check(open.failure.retryable() && connect.failure.retryable(), "both pre-send stages are retryable");
        check(!open.failure.getMessage().contains(TOKEN) && !connect.failure.getMessage().contains(TOKEN),
                "no credential in any pre-send message");
    }

    // ── 307/308 不得自动重放 ────────────────────────────────────────

    static void redirectNotFollowed() throws Exception {
        for (int status : new int[]{307, 308}) {
            try (Recorder target = new Recorder()) {
                target.start();
                try (Loopback origin = new Loopback(status, "", false,
                        "http://127.0.0.1:" + target.port() + "/replayed")) {
                    origin.start();
                    ClineAuth.setRefreshConnectionsForTests(url ->
                            NetworkPolicy.openHttp("http://127.0.0.1:" + origin.port() + url.substring(url.lastIndexOf('/'))));
                    ClineAuth.RefreshReport report;
                    try {
                        report = ClineAuth.refreshReport(account(TOKEN), true);
                    } finally {
                        ClineAuth.setRefreshConnectionsForTests(null);
                    }
                    check(report.candidate == null, "a redirect yields no candidate: " + status);
                    check(report.delivery == ClineAuth.RefreshDelivery.SENT,
                            "a redirect answer still delivered the request: " + status);
                    check(report.failure != null && "status".equals(report.failure.stage),
                            "a redirect is a status stage: " + status);
                    check(report.failure.status == status, "the redirect status is kept: " + status);
                    check(report.failure.getMessage().startsWith("Cline token refresh failed (HTTP " + status + ")"),
                            "message keeps the legacy prefix: " + report.failure.getMessage());
                    check(!report.fenceReleasable(), "a delivered request keeps the fence: " + status);
                    check(origin.requests() == 1, "exactly one request hit the origin: " + origin.requests());
                    // refreshReport 已经返回：如果 JDK 要跟随重定向，连接此刻早已发起，给一点静默期再断言。
                    Thread.sleep(150);
                    check(target.hits() == 0, "the redirect target must never be contacted: " + target.hits());
                    check(target.completed() == 0, "no request may reach the redirect target at all");
                    check(target.seenTokens().isEmpty(), "the one-use value must never be replayed");
                }
            }
        }

        // 负对照：按改动前的写法（不关重定向）重放同一个剧本，第二台主机确实收到了请求体。
        try (Recorder target = new Recorder()) {
            target.start();
            try (Loopback origin = new Loopback(307, "", false,
                    "http://127.0.0.1:" + target.port() + "/replayed")) {
                origin.start();
                int code = sendWithoutRedirectGuard(origin.port(), TOKEN);
                check(code == 200, "the unfollowed control is expected to reach the target: " + code);
                check(target.awaitCompleted(1, 5000),
                        "the unfollowed control never delivered anything to the target");
                check(target.hits() == 1,
                        "the JDK does replay the body on 307 unless redirects are disabled: " + target.hits());
                // seenTokens() 返回的是“含凭据的请求体”，判空即可，不要拿 token 去比整段 body。
                check(!target.seenTokens().isEmpty(),
                        "the replayed request really carried the one-use value: " + target.allBodies());
            }
        }
    }

    /** 改动前的写法：不关自动重定向，直接把请求体交给 JDK。只用于负对照。 */
    static int sendWithoutRedirectGuard(int port, String token) throws Exception {
        HttpURLConnection conn = open("http://127.0.0.1:" + port + "/api/v1/auth/refresh");
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject req = new JSONObject().put("refreshToken", token).put("grantType", "refresh_token");
            OutputStream os = conn.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            os.close();
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    // ── 真实环回：请求形状与成功路径不变 ───────────────────────────────

    static void wireShapeAndSuccess() throws Exception {
        try (Loopback server = new Loopback(200,
                "{\"data\":{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\"}}")) {
            server.start();
            final String[] asked = new String[1];
            ClineAuth.setRefreshConnectionsForTests(url -> {
                asked[0] = url;
                return NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh");
            });
            ClineAuth.SignIn signIn;
            try {
                check(ClineAuth.refreshUrl().endsWith("/api/v1/auth/refresh"), "production refresh path");
                signIn = ClineAuth.refreshToken("wire-refresh");
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
            check(ClineAuth.refreshUrl().equals(asked[0]), "refresh still targets the production URL");
            check("new-access".equals(signIn.access), "access parsed from the real body");
            check("new-refresh".equals(signIn.refresh), "rotated refresh parsed from the real body");

            String head = server.headText();
            String body = server.bodyText();
            check(head.startsWith("POST /api/v1/auth/refresh "), "wire method and path unchanged: " + head);
            check(lower(head).contains("content-type: application/json"), "content type unchanged");
            check(lower(head).contains("accept: application/json"), "accept unchanged");
            check(lower(head).contains("x-client-type: cline-cli"), "client headers unchanged");
            check(body.contains("\"refreshToken\":\"wire-refresh\""), "refresh token travels in the body: " + body);
            check(body.contains("\"grantType\":\"refresh_token\""), "grant type unchanged: " + body);
        }

        // 同一根网线，走报告接口：拿到候选，且一定不是“未交付”。
        try (Loopback server = new Loopback(200,
                "{\"data\":{\"accessToken\":\"a2\",\"refreshToken\":\"r2\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}}")) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            try {
                ClineAuth.RefreshReport report = ClineAuth.refreshReport(account("r1"), true);
                check(report.ready() && report.candidate != null, "candidate from the real wire");
                check("a2".equals(report.candidate.next.access), "candidate carries the new access");
                check(!report.provablyNotSent(), "a completed exchange is not provably not-sent");
                check(!releaseFence(report), "a completed exchange is never fence-released");
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
        }
    }

    // ── 真实环回：Cline 答复了 ────────────────────────────────────────

    static void httpRejection() throws Exception {
        for (int status : new int[]{400, 503}) {
            try (Loopback server = new Loopback(status, "{\"error\":\"invalid_grant\"}")) {
                server.start();
                ClineAuth.setRefreshConnectionsForTests(url ->
                        NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
                ClineAuth.RefreshReport report;
                try {
                    report = ClineAuth.refreshReport(account("r1"), true);
                } finally {
                    ClineAuth.setRefreshConnectionsForTests(null);
                }
                check(report.candidate == null, "rejected refresh yields no candidate");
                check(report.delivery == ClineAuth.RefreshDelivery.SENT, "answered request is SENT: " + status);
                check(report.failure != null && report.failure.status == status, "status kept: " + status);
                // 旧文案前缀保持不变，下游按文本判断的地方不受影响。
                check(report.failure.getMessage().startsWith("Cline token refresh failed (HTTP " + status + ")"),
                        "legacy message prefix: " + report.failure.getMessage());
                check(!report.fenceReleasable(), "http rejection keeps the fence: " + status);
                check(!report.failure.retryable(), "a delivered rejection is not a pre-send retry");
                check(!report.failure.getMessage().contains("r1"), "message carries no credential");
            }
        }

        // 200 但没有可用令牌对：应答已到，同样不能放开围栏。
        try (Loopback server = new Loopback(200, "{\"data\":{\"refreshToken\":\"r2\"}}")) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            ClineAuth.RefreshReport report;
            try {
                report = ClineAuth.refreshReport(account("r1"), true);
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
            check(report.candidate == null, "incomplete pair yields no candidate");
            check(report.delivery == ClineAuth.RefreshDelivery.SENT, "unusable answer was still delivered");
            check("parse".equals(report.failure.stage), "unusable pair is the parse stage");
            check(!report.fenceReleasable(), "unusable answer keeps the fence");
        }
    }

    // ── 真实环回：TCP 通了但没有任何应答 ──────────────────────────────

    static void unansweredSocket() throws Exception {
        try (Loopback server = new Loopback(0, "", true)) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            ClineAuth.RefreshReport report;
            try {
                report = ClineAuth.refreshReport(account("r1"), true);
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
            check(report.candidate == null, "no answer yields no candidate");
            check(report.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT,
                    "an answered-nothing socket may still have received the body: " + report.delivery);
            check(report.failure != null && report.failure.status == 0, "no status was received");
            check(!report.fenceReleasable(), "no answer keeps the fence");
        }
    }

    // ── 真实环回：连接被拒 ───────────────────────────────────────────

    static void connectRefused() throws Exception {
        final int port = closedPort();
        ClineAuth.setRefreshConnectionsForTests(url ->
                NetworkPolicy.openHttp("http://127.0.0.1:" + port + "/api/v1/auth/refresh"));
        ClineAuth.RefreshReport report;
        try {
            report = ClineAuth.refreshReport(account("r1"), true);
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
        // 这就是被修的误锁：没有候选，但凭据一定还是原值。
        check(report.candidate == null, "refused connect yields no candidate");
        check(report.delivery == ClineAuth.RefreshDelivery.NOT_SENT,
                "a refused connect is provably pre-send: " + report.delivery);
        check(report.failure != null && report.failure.provablyNotSent(), "failure itself says pre-send");
        check(releaseFence(report), "nothing was sent, so the fence must be released");
        check(report.failure.retryable(), "pre-send failure is retryable");
        check(report.failure.source.endsWith("ConnectException"),
                "source is the exception class name only: " + report.failure.source);
        check(!report.failure.getMessage().contains("r1"), "message carries no credential");
    }

    // ── 合成故障：确定发送前 ─────────────────────────────────────────

    static void preSendStages() throws Exception {
        // DNS 失败：拿到了链路对象，建连阶段就断了。
        ClineAuth.setRefreshConnectionsForTests(url -> {
            throw new UnknownHostException("api.cline.bot");
        });
        try {
            ClineAuth.RefreshReport report = ClineAuth.refreshReport(account("r1"), true);
            check(report.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "dns failure is pre-send");
            check(releaseFence(report), "dns failure releases the fence");
            check("open".equals(report.failure.stage), "dns failure is an open-stage failure");
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }

        // 空凭据：本地就拒绝，传输层根本不该被调用。
        final int[] calls = new int[1];
        ClineAuth.RefreshTransport counting = token -> {
            calls[0]++;
            return null;
        };
        JSONObject empty = new JSONObject().put("id", "a").put("token", "t").put("expires", 1);
        ClineAuth.RefreshReport report = ClineAuth.refreshReport(empty, true, counting);
        check(calls[0] == 0, "an empty refresh value must not reach the wire");
        check(report.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "empty refresh value is pre-send");
        check("precondition".equals(report.failure.stage), "empty refresh value is a precondition failure");
        check(releaseFence(report), "empty refresh value releases the fence");

        // 账号为空：连一次尝试都没有。
        ClineAuth.RefreshReport none = ClineAuth.refreshReport(null, true, counting);
        check(calls[0] == 0, "a missing account must not reach the wire");
        check(none.delivery == ClineAuth.RefreshDelivery.NOT_ATTEMPTED, "missing account never attempted");
        check(none.failure == null && none.candidate == null, "missing account has no failure detail");
        check(none.provablyNotSent(), "nothing attempted delivered nothing");
        check(none.fenceReleasable(), "nothing attempted is releasable");
    }

    // ── 合成故障：部分写（保守的核心） ───────────────────────────────

    static void partialWrite() throws Exception {
        ClineAuth.setRefreshConnectionsForTests(url -> new PartialWrite(url, 10));
        try {
            ClineAuth.RefreshReport report = ClineAuth.refreshReport(account("r1"), true);
            check(report.candidate == null, "partial write yields no candidate");
            check(report.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT,
                    "a failed body write may already have left the process: " + report.delivery);
            check(report.failure != null && report.failure.bodyWriteStarted, "write had started");
            check(!report.failure.bodyWritten, "the body was not fully written");
            check(!report.failure.provablyNotSent(), "partial write is never provably not-sent");
            check(!report.fenceReleasable(), "partial write keeps the fence");
            check(!report.failure.retryable(), "partial write is not a safe retry");
            check("write".equals(report.failure.stage), "write stage recorded");
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
    }

    // ── 合成故障：请求写完但再也等不到答复 ───────────────────────────

    static void responseTimeout() throws Exception {
        ClineAuth.setRefreshConnectionsForTests(url -> new ResponseTimeout(url));
        try {
            ClineAuth.RefreshReport report = ClineAuth.refreshReport(account("r1"), true);
            check(report.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT, "unanswered read is possibly sent");
            check(report.failure != null && report.failure.bodyWritten, "the whole body had been written");
            check(!report.fenceReleasable(), "unanswered read keeps the fence");
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
    }

    // ── 报告门：跳过、未知异常、账号被改 ─────────────────────────────

    static void reportGate() throws Exception {
        JSONObject fresh = new JSONObject().put("token", "t").put("refresh", "r")
                .put("expires", System.currentTimeMillis() + 3600000L);
        ClineAuth.RefreshReport skipped = ClineAuth.refreshReport(fresh, false);
        check(skipped.candidate == null && skipped.failure == null, "a fresh account is skipped");
        check(skipped.delivery == ClineAuth.RefreshDelivery.NOT_ATTEMPTED, "skip is not an attempt");
        check(releaseFence(skipped), "a skip sent nothing");

        // 负对照：不认识的异常不能证明“没发送”。
        ClineAuth.RefreshReport opaque = ClineAuth.refreshReport(account("r1"), true,
                token -> {
                    throw new IOException(SECRET);
                });
        check(opaque.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT,
                "an opaque transport failure is never treated as pre-send: " + opaque.delivery);
        check(opaque.failure.credentialChannel == ClineAuth.CredentialChannel.UNKNOWN,
                "an opaque failure cannot claim the body-only premise");
        check(!opaque.failure.provablyNotSent(), "an opaque failure proves nothing");
        check(!releaseFence(opaque), "an opaque transport failure keeps the fence");
        check(!opaque.failure.getMessage().contains(SECRET), "no credential in the message");
        check("IOException".equals(opaque.failure.source), "only the class name is kept");
        check(!opaque.failure.toString().contains(SECRET), "toString carries no upstream text");

        // 交换正常返回但没有 access：同样不能算“没发送”。
        ClineAuth.RefreshReport unpaired = ClineAuth.refreshReport(account("r1"), true, token -> {
            ClineAuth.SignIn empty = new ClineAuth.SignIn();
            empty.access = "";
            return empty;
        });
        check(unpaired.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT, "empty pair is not pre-send");
        check(!releaseFence(unpaired), "empty pair keeps the fence");

        ClineAuth.RefreshReport nullPair = ClineAuth.refreshReport(account("r1"), true, token -> null);
        check(nullPair.delivery == ClineAuth.RefreshDelivery.POSSIBLY_SENT, "null pair is not pre-send");
        check(!releaseFence(nullPair), "null pair keeps the fence");

        // 交换发生了但账号被改写：绝不能把过期的那一对交出去。
        final JSONObject raced = account("r1");
        ClineAuth.RefreshReport superseded = ClineAuth.refreshReport(raced, true, token -> {
            ClineAuth.SignIn next = new ClineAuth.SignIn();
            next.access = "fresh-access";
            next.refresh = "fresh-refresh";
            next.expires = System.currentTimeMillis() + 600000L;
            raced.put("refresh", "someone-else"); // 另一个写入方抢先改了账号
            return next;
        });
        check(superseded.candidate == null, "a superseded pair is never handed out");
        check(superseded.delivery == ClineAuth.RefreshDelivery.SENT, "the exchange did happen");
        check(!releaseFence(superseded), "a superseded exchange keeps the fence");

        // 定型异常原样透传：调用方拿到的判定与传输层一致，不被重新猜。
        ClineAuth.RefreshReport typedNotSent = ClineAuth.refreshReport(account("r1"), true, token -> {
            throw typed(ClineAuth.RefreshDelivery.NOT_SENT, "connect");
        });
        check(releaseFence(typedNotSent), "a typed pre-send failure releases the fence");
        ClineAuth.RefreshReport typedPossibly = ClineAuth.refreshReport(account("r1"), true, token -> {
            throw typed(ClineAuth.RefreshDelivery.POSSIBLY_SENT, "write");
        });
        check(!releaseFence(typedPossibly), "a typed possibly-sent failure keeps the fence");

        // 没有 body-only 前提的“未发送”不算数：这就是前提被写进证据的意义。
        ClineAuth.RefreshReport unproven = ClineAuth.refreshReport(account("r1"), true, token -> {
            throw new ClineAuth.ClineRefreshException(ClineAuth.RefreshDelivery.NOT_SENT, "connect", 0,
                    false, false, ClineAuth.CredentialChannel.UNKNOWN, null);
        });
        check(unproven.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "delivery still says not sent");
        check(!unproven.provablyNotSent(), "without the body-only premise it is not provable");
        check(!unproven.fenceReleasable(), "the fence gate itself must require the premise");
        check(!releaseFence(unproven), "an unproven not-sent keeps the fence");
    }

    // ── 旧 API 行为不变 ─────────────────────────────────────────────

    static void legacyCompatibility() throws Exception {
        ClineAuth.RefreshTransport good = token -> {
            ClineAuth.SignIn next = new ClineAuth.SignIn();
            next.access = "new";
            next.refresh = "rotated";
            next.expires = System.currentTimeMillis() + 600000L;
            return next;
        };
        JSONObject a = account("r");
        ClineAuth.RefreshCandidate candidate = ClineAuth.refreshCandidate(a, false, good);
        check(candidate != null && candidate.matches(a), "legacy candidate unchanged");
        check("r".equals(a.optString("refresh")), "legacy candidate never mutates the account");

        check(ClineAuth.refreshCandidate(a, true, token -> {
            throw new Exception(SECRET);
        }) == null, "legacy candidate is still null on failure");
        check(ClineAuth.refreshCandidate(a, true, token -> null) == null, "legacy null pair is still null");
        JSONObject fresh = new JSONObject().put("token", "t").put("refresh", "r")
                .put("expires", System.currentTimeMillis() + 3600000L);
        check(ClineAuth.refreshCandidate(fresh, false, good) == null, "legacy fresh skip unchanged");

        // 新增的阶段拆分不能被旧入口看见：旧的 refreshCandidate 依然只是 null。
        JSONObject openStage = account("r");
        check(ClineAuth.refreshCandidate(openStage, true, token -> {
            throw new ClineAuth.ClineRefreshException(ClineAuth.RefreshDelivery.NOT_SENT, "open", 0,
                    false, false, ClineAuth.CredentialChannel.BODY_ONLY, null);
        }) == null, "legacy candidate stays null for an open-stage failure");

        JSONObject mutate = account("r");
        check(ClineAuth.refreshAccount(mutate, good), "legacy refreshAccount still applies");
        check("new".equals(mutate.optString("token")) && "rotated".equals(mutate.optString("refresh")),
                "legacy refreshAccount mutates as before");

        // 公开的 refreshToken 仍然声明 throws Exception，失败是可捕获的定型异常。
        boolean caught = false;
        try {
            ClineAuth.refreshToken("");
        } catch (ClineAuth.ClineRefreshException typed) {
            caught = true;
            check(typed.delivery == ClineAuth.RefreshDelivery.NOT_SENT, "blank token is pre-send");
            check("precondition".equals(typed.stage), "blank token is a precondition failure");
        } catch (Exception other) {
            check(false, "blank token must stay typed: " + other);
        }
        check(caught, "blank token throws the typed exception");
    }

    // ── 答复定性：Cline 说「这次拒绝了，值还在」还是「这枚值死了」 ──────────

    /**
     * 上游插件只有两分法（{@code index.mjs:220-226}）：400/401/403 加上
     * {@code invalid|expired|revoked|unauthorized} 才是登录失效，其余答复都是「值得稍后再试」。
     * {@code refresh.test.mjs} 两头都钉过：400 invalid_grant → {@code signIn: "expired"}；
     * 503 → 没有 signIn 且「the next try sends it again」。
     *
     * <p>这里用真实环回把这几种答复各跑一遍，同时钉住一条安全不变量：
     * 定性再明确，{@code fenceReleasable()} 与 {@code failure.retryable()} 的保守口径一个字都不改。
     */
    static void refusalEvidence() throws Exception {
        ClineAuth.RefreshReport unavailable = refreshAgainst(503, "upstream down");
        check(unavailable.refusal() == ClineAuth.Refusal.RETRY_LATER,
                "a 503 answer is an explicit retry-later refusal: " + unavailable.refusal());
        check(unavailable.refusedByProvider(), "the refusal really came from Cline");
        check(!unavailable.signInRequired(), "a 503 is not the sign-in gone");
        check(unavailable.mayResendSameValue(), "the plugin re-sends the same refresh token after a 503");
        check(!unavailable.executionUnknown(), "an answered refusal is not an unknown execution");
        check("refused_retry_later:503".equals(unavailable.evidenceCode()),
                "the evidence code names the refusal and the status: " + unavailable.evidenceCode());

        ClineAuth.RefreshReport throttled = refreshAgainst(429, "{\"error\":\"too many requests\"}");
        check(throttled.refusal() == ClineAuth.Refusal.RETRY_LATER, "429 is retry-later");
        check(throttled.mayResendSameValue(), "429 does not replace the one-use value");

        ClineAuth.RefreshReport revoked = refreshAgainst(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token revoked\"}");
        check(revoked.refusal() == ClineAuth.Refusal.SIGN_IN_REQUIRED,
                "400 invalid_grant is the sign-in gone: " + revoked.refusal());
        check(revoked.signInRequired(), "the account needs a new sign-in");
        check(!revoked.mayResendSameValue(), "a dead credential is never sent again");
        check(!revoked.executionUnknown(), "an explicit refusal is not unknown");
        check("refused_sign_in:400".equals(revoked.evidenceCode()), revoked.evidenceCode());

        // 403 在这个插件里一直很谨慎：只有整句措辞才算登录失效，普通 403 保持原类别。
        ClineAuth.RefreshReport forbiddenLapsed = refreshAgainst(403, "token revoked");
        check(forbiddenLapsed.refusal() == ClineAuth.Refusal.SIGN_IN_REQUIRED, "403 revoked is lapsed");
        ClineAuth.RefreshReport forbiddenPlain = refreshAgainst(403, "{\"error\":\"quota exceeded\"}");
        check(forbiddenPlain.refusal() == ClineAuth.Refusal.RETRY_LATER,
                "an ordinary 403 stays retry-later: " + forbiddenPlain.refusal());

        // 判据要求措辞：空正文的 400 不是登录失效，替上游猜会把可重试说成要重新登录。
        ClineAuth.RefreshReport bareBadRequest = refreshAgainst(400, "");
        check(bareBadRequest.refusal() == ClineAuth.Refusal.RETRY_LATER, "a bare 400 is not a lapsed sign-in");
        check(ClineAuth.refusalOf(400, null) == ClineAuth.Refusal.RETRY_LATER, "a null body has no wording");

        // 边界：只有 400–599 是「对这次尝试的答复」。
        check(ClineAuth.refusalOf(200, "invalid") == ClineAuth.Refusal.NOT_REFUSED, "2xx is never a refusal");
        check(ClineAuth.refusalOf(302, "revoked") == ClineAuth.Refusal.NOT_REFUSED, "3xx is never a refusal");
        check(ClineAuth.refusalOf(600, "revoked") == ClineAuth.Refusal.NOT_REFUSED, "outside 400-599 is no refusal");

        // 安全不变量：有答复的拒绝一律保留围栏，也一律不算「可立即原样重试」。
        for (ClineAuth.RefreshReport report : new ClineAuth.RefreshReport[]{
                unavailable, throttled, revoked, forbiddenLapsed, forbiddenPlain, bareBadRequest}) {
            check(!report.fenceReleasable(), "an answered refusal never releases the fence: " + report.evidenceCode());
            check(!report.failure.retryable(), "an answered refusal is not a pre-send retry: " + report.evidenceCode());
            check(!report.failure.provablyNotSent(), "the body did leave the process");
            check(!report.failure.getMessage().contains(TOKEN), "no credential in the message");
            check(!report.failure.getMessage().contains("invalid_grant"),
                    "no upstream text in the message: " + report.failure.getMessage());
            check(!report.failure.getMessage().contains("revoked"),
                    "no upstream wording in the message: " + report.failure.getMessage());
            check(!report.failure.toString().contains("invalid_grant"), "toString stays clean too");
        }

        // 3xx：自动跟随已关闭，它只是一个「已送达但不可用」的答复，不是对凭据的定性。
        ClineAuth.RefreshReport moved = refreshAgainst(307, "", "http://127.0.0.1:1/elsewhere");
        check(moved.refusal() == ClineAuth.Refusal.NOT_REFUSED, "a redirect is not a refusal");
        check(moved.executionUnknown(), "a redirect leaves the outcome unknown");
        check(!moved.mayResendSameValue(), "a redirect is no proof the value survived");
    }

    /**
     * 上游 {@code refresh.test.mjs} 的最后两行就是这条 API 的立论：
     * 「the refresh token wasn't spent: the next try sends it again」——
     * 503 之后重来一次，请求体里仍然是同一枚 {@code r1}，并且这次换到了新对子。
     *
     * <p>两个真实环回把同一件事跑一遍：先 503，再 200。第二次请求体里的 refreshToken
     * 必须还是 {@code r1}（既不是被换掉的值，也不是凭空造的新值），报告必须是 {@code ready}。
     * 这条同时说明为什么「明确拒绝」不等于「未知执行」：前者可以把同一枚值再发一次，
     * 后者不行。
     */
    static void refusedValueIsNotSpent() throws Exception {
        ClineAuth.RefreshReport refused = refreshAgainst(503, "upstream down");
        check(refused.candidate == null, "a refusal hands out no pair");
        check(refused.mayResendSameValue(), "the same value may be sent again");
        check("refused_retry_later:503".equals(refused.evidenceCode()), refused.evidenceCode());

        try (Loopback server = new Loopback(200,
                "{\"data\":{\"accessToken\":\"jwt2\",\"refreshToken\":\"r2\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\"}}")) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            ClineAuth.RefreshReport second;
            try {
                JSONObject account = account("r1");
                second = ClineAuth.refreshReport(account, true);
                check("r1".equals(account.optString("refresh")),
                        "the refused attempt left the stored value untouched");
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
            check(second.ready(), "the retry succeeds: " + second.evidenceCode());
            check("ready".equals(second.evidenceCode()), "a completed exchange reports ready");
            check("jwt2".equals(second.candidate.next.access), "the new access is handed out");
            check("r2".equals(second.candidate.next.refresh), "the rotated value is kept in the pair");
            check(server.bodyText().contains("\"refreshToken\":\"r1\""),
                    "the retry sends the same one-use value, not a replacement: " + server.bodyText());
            check(!server.bodyText().contains("r2"), "nothing invents a rotated value for the retry");
        }
    }

    /**
     * 反向证据：拿不到可定性的答复时，绝不能报出任何一种拒绝。
     *
     * <p>这是「未知执行不重放」的守门测试。只要没答复、写了一半、正文没读全、2xx 解析不出对子
     * 或裸异常被错判成 {@code REFUSED_*}，调用方就会把「可能已消费」的值说成可重试，
     * 那正是重放。
     */
    static void unknownNeverClaimsRefusal() throws Exception {
        // 1. 没收到状态行：TCP 通了，但一个字也没答。
        try (Loopback mute = new Loopback(0, "", true)) {
            mute.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + mute.port() + "/api/v1/auth/refresh"));
            try {
                ClineAuth.RefreshReport silent = ClineAuth.refreshReport(account("r1"), true);
                check(silent.refusal() == ClineAuth.Refusal.NOT_REFUSED, "silence is not a refusal");
                check(silent.executionUnknown(), "silence is unknown execution");
                check(!silent.mayResendSameValue(), "silence must never be retried");
                check(!silent.refusedByProvider(), "silence has no provider verdict");
                check("unknown:response".equals(silent.evidenceCode()), silent.evidenceCode());
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
        }

        // 2. 请求体写到一半。
        ClineAuth.setRefreshConnectionsForTests(url -> new PartialWrite(url, 10));
        try {
            ClineAuth.RefreshReport partial = ClineAuth.refreshReport(account("r1"), true);
            check(partial.refusal() == ClineAuth.Refusal.NOT_REFUSED, "a half write is not a refusal");
            check(partial.executionUnknown(), "a half write is unknown execution");
            check(!partial.mayResendSameValue(), "a half write must never be retried");
            check("unknown:write".equals(partial.evidenceCode()), partial.evidenceCode());
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }

        // 3. 200 到了，但解析不出可用的对子：交换可能已经发生。
        try (Loopback server = new Loopback(200, "{\"data\":{\"refreshToken\":\"r2\"}}")) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            try {
                ClineAuth.RefreshReport unusable = ClineAuth.refreshReport(account("r1"), true);
                check(unusable.refusal() == ClineAuth.Refusal.NOT_REFUSED,
                        "an unparsable 2xx is not a refusal (Cline never said no)");
                check(unusable.executionUnknown(), "an unparsable 2xx may already have spent the value");
                check(!unusable.mayResendSameValue(), "an unparsable 2xx is never resent");
                check("unknown:parse".equals(unusable.evidenceCode()), unusable.evidenceCode());
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
        }

        // 4. 裸异常：不认识的失败不能替上游说话。
        ClineAuth.RefreshReport opaque = ClineAuth.refreshReport(account("r1"), true, token -> {
            throw new IOException(SECRET);
        });
        check(opaque.refusal() == ClineAuth.Refusal.NOT_REFUSED, "an opaque failure is not a refusal");
        check(opaque.executionUnknown(), "an opaque failure is unknown execution");
        check(!opaque.mayResendSameValue(), "an opaque failure is never resent");
        check("unknown:transport".equals(opaque.evidenceCode()), opaque.evidenceCode());

        // 5. 交换发生了但账号被改写：结果不能交出去，值也可能已经消费。
        final JSONObject raced = account("r1");
        ClineAuth.RefreshReport superseded = ClineAuth.refreshReport(raced, true, token -> {
            ClineAuth.SignIn next = new ClineAuth.SignIn();
            next.access = "fresh-access";
            next.refresh = "fresh-refresh";
            next.expires = System.currentTimeMillis() + 600000L;
            raced.put("refresh", "someone-else");
            return next;
        });
        check(superseded.refusal() == ClineAuth.Refusal.NOT_REFUSED, "a superseded exchange is not a refusal");
        check(superseded.executionUnknown(), "a superseded exchange is unknown");
        check(!superseded.mayResendSameValue(), "a superseded exchange is never resent");

        // 6. 对照组：证明未发送与成功各有自己的标签，没有被上面的未知吞掉。
        ClineAuth.setRefreshConnectionsForTests(url -> new StreamFails(url));
        try {
            ClineAuth.RefreshReport notSent = ClineAuth.refreshReport(account("r1"), true);
            check(notSent.mayResendSameValue(), "a proven pre-send failure may retry");
            check(!notSent.executionUnknown(), "a proven pre-send failure is not unknown");
            check("not_sent:connect".equals(notSent.evidenceCode()), notSent.evidenceCode());
        } finally {
            ClineAuth.setRefreshConnectionsForTests(null);
        }
        ClineAuth.RefreshReport skipped = ClineAuth.refreshReport(new JSONObject().put("token", "t")
                .put("refresh", "r").put("expires", System.currentTimeMillis() + 3600000L), false);
        check("not_sent:skipped".equals(skipped.evidenceCode()), skipped.evidenceCode());
    }

    /** 在一个真实环回上跑一次刷新，把报告拿回来；每次尝试必须只有一个请求。 */
    static ClineAuth.RefreshReport refreshAgainst(int status, String body) throws Exception {
        return refreshAgainst(status, body, null);
    }

    static ClineAuth.RefreshReport refreshAgainst(int status, String body, String location) throws Exception {
        try (Loopback server = new Loopback(status, body, false, location)) {
            server.start();
            ClineAuth.setRefreshConnectionsForTests(url ->
                    NetworkPolicy.openHttp("http://127.0.0.1:" + server.port() + "/api/v1/auth/refresh"));
            try {
                ClineAuth.RefreshReport report = ClineAuth.refreshReport(account("r1"), true);
                check(server.requests() == 1, "exactly one request per attempt: " + server.requests());
                return report;
            } finally {
                ClineAuth.setRefreshConnectionsForTests(null);
            }
        }
    }

    // ── 与 GatewayServer 相同的围栏判定 ─────────────────────────────

    /**
     * 复刻 GatewayServer 的围栏决定：没有候选时才谈围栏，且只有“证明未交付”才放开。
     * 放开 = resolveRefreshFence；保留 = 继续误锁。
     */
    static boolean releaseFence(ClineAuth.RefreshReport report) {
        // 与 GatewayServer 的判据一字不差：没有候选，且报告说自己证明未交付。
        // 这里不能再叠加 provablyNotSent()，否则会把 fenceReleasable() 这道门禁遮住、测不到。
        return report.candidate == null && report.fenceReleasable();
    }

    /** 传输层自己给出的定型失败；refreshReport 必须原样透传，不能重新猜。 */
    static ClineAuth.ClineRefreshException typed(ClineAuth.RefreshDelivery delivery, String stage) {
        return new ClineAuth.ClineRefreshException(delivery, stage, 0, false, false,
                ClineAuth.CredentialChannel.BODY_ONLY, new IOException("simulated " + SECRET));
    }

    static JSONObject account(String refresh) throws Exception {
        return new JSONObject().put("id", "acct").put("token", "old").put("access", "old")
                .put("refresh", refresh).put("expires", 1);
    }

    /** 裸连接（不带任何策略），只给正对照与负对照用。 */
    static HttpURLConnection open(String url) {
        try {
            return (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException | IOException unusable) {
            throw new IllegalStateException(unusable);
        }
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static int closedPort() throws Exception {
        ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    // ── 环回服务器 ─────────────────────────────────────────────────

    /** 一次性环回服务器：读一个请求，记下请求形状，再按剧本答复或干脆不答复。 */
    static final class Loopback implements AutoCloseable {
        private final ServerSocket server;
        private final int status;
        private final String responseBody;
        private final boolean mute;
        private final String location;
        private volatile String headText = "";
        private volatile String bodyText = "";
        private volatile int requests;
        private Thread thread;

        Loopback(int status, String responseBody) throws IOException {
            this(status, responseBody, false, null);
        }

        Loopback(int status, String responseBody, boolean mute) throws IOException {
            this(status, responseBody, mute, null);
        }

        Loopback(int status, String responseBody, boolean mute, String location) throws IOException {
            this.server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            this.status = status;
            this.responseBody = responseBody == null ? "" : responseBody;
            this.mute = mute;
            this.location = location;
        }

        int port() {
            return server.getLocalPort();
        }

        int requests() {
            return requests;
        }

        String headText() {
            return headText;
        }

        String bodyText() {
            return bodyText;
        }

        void start() {
            thread = new Thread(this::serve, "cline-refresh-loopback");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try {
                Socket socket = server.accept();
                requests++;
                socket.setSoTimeout(5000);
                InputStream in = socket.getInputStream();
                StringBuilder head = new StringBuilder();
                while (head.indexOf("\r\n\r\n") < 0) {
                    int b = in.read();
                    if (b < 0) break;
                    head.append((char) b);
                }
                headText = head.toString();
                int length = contentLength(headText);
                if (length > 0) {
                    byte[] raw = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int n = in.read(raw, read, length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    bodyText = new String(raw, 0, read, "UTF-8");
                }
                if (!mute) {
                    byte[] payload = responseBody.getBytes("UTF-8");
                    String response = "HTTP/1.1 " + status + " Status\r\nContent-Type: application/json\r\n"
                            + (location == null ? "" : "Location: " + location + "\r\n")
                            + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
                    OutputStream out = socket.getOutputStream();
                    out.write(response.getBytes("UTF-8"));
                    out.write(payload);
                    out.flush();
                }
                socket.close();
            } catch (Throwable ignored) {
                // 客户端主动断开属于预期路径。
            }
        }

        private static int contentLength(String head) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                if (!"content-length".equals(lower(line.substring(0, colon)).trim())) continue;
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }

        @Override
        public void close() {
            try { server.close(); } catch (IOException ignored) { }
            if (thread != null) {
                try {
                    thread.join(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 重定向目标：只回答 200，但把每个到达的请求体记下来。 */
    static final class Recorder implements AutoCloseable {
        private final ServerSocket server;
        private final List<String> bodies = new ArrayList<String>();
        private volatile int hits;
        private volatile int completed;
        private Thread thread;

        Recorder() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        }

        int port() {
            return server.getLocalPort();
        }

        int hits() {
            return hits;
        }

        int completed() {
            return completed;
        }

        /** 等到 n 个请求被完整读完；只用于负对照，避免读到一半就断言。 */
        boolean awaitCompleted(int n, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (completed >= n) return true;
                Thread.sleep(10);
            }
            return completed >= n;
        }

        List<String> allBodies() {
            synchronized (bodies) { return new ArrayList<String>(bodies); }
        }

        List<String> seenTokens() {
            List<String> found = new ArrayList<String>();
            synchronized (bodies) {
                for (String body : bodies) {
                    if (body.contains(TOKEN)) found.add(body);
                }
            }
            return found;
        }

        void start() {
            thread = new Thread(this::serve, "cline-refresh-redirect-target");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    hits++;
                    socket.setSoTimeout(3000);
                    InputStream in = socket.getInputStream();
                    StringBuilder head = new StringBuilder();
                    while (head.indexOf("\r\n\r\n") < 0) {
                        int b = in.read();
                        if (b < 0) break;
                        head.append((char) b);
                    }
                    int length = Loopback.contentLength(head.toString());
                    String body = "";
                    if (length > 0) {
                        byte[] raw = new byte[length];
                        int read = 0;
                        while (read < length) {
                            int n = in.read(raw, read, length - read);
                            if (n < 0) break;
                            read += n;
                        }
                        body = new String(raw, 0, read, "UTF-8");
                    }
                    synchronized (bodies) {
                        bodies.add(body);
                        completed++;
                    }
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: 2\r\nConnection: close\r\n\r\n{}";
                    socket.getOutputStream().write(response.getBytes("UTF-8"));
                    socket.close();
                } catch (Throwable stopping) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            try { server.close(); } catch (IOException ignored) { }
            if (thread != null) {
                try {
                    thread.join(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ── 合成故障连接 ───────────────────────────────────────────────

    /** 取输出流就失败：请求体还没开始写。 */
    static final class StreamFails extends FakeConnection {
        StreamFails(String url) { super(url); }
        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new SocketTimeoutException("connect timed out");
        }
    }

    /** 请求体写到一半失败：进程内无法知道到底写了多少。 */
    static final class PartialWrite extends FakeConnection {
        private final int accept;
        PartialWrite(String url, int accept) {
            super(url);
            this.accept = accept;
        }
        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                private int written;
                @Override
                public void write(int b) throws IOException {
                    if (written >= accept) throw new IOException("connection reset by peer");
                    written++;
                }
                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    if (written + length > accept) {
                        written = accept;
                        throw new IOException("connection reset by peer");
                    }
                    written += length;
                }
            };
        }
    }

    /** 请求体写完，但再也等不到状态行。 */
    static final class ResponseTimeout extends FakeConnection {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ResponseTimeout(String url) { super(url); }
        @Override
        public OutputStream getOutputStream() {
            return sink;
        }
        @Override
        public int getResponseCode() throws IOException {
            throw new SocketTimeoutException("read timed out");
        }
    }

    /** 只为制造某个阶段的失败，其他方法都不应有调用者。 */
    static class FakeConnection extends HttpURLConnection {
        FakeConnection(String url) { super(parse(url)); }
        private static java.net.URL parse(String url) {
            try {
                return new java.net.URI(url).toURL();
            } catch (java.net.URISyntaxException | java.net.MalformedURLException impossible) {
                throw new IllegalArgumentException(impossible);
            }
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() throws IOException { }
    }
}
