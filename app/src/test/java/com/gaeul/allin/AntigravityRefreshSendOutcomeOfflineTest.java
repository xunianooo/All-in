package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gaeul.allin.json.JSONObject;

/**
 * Antigravity「刷新 token 交换」出站结果分类的定向离线测试。
 *
 * 不联网、不启动真实服务、不碰任何真实账号：全部请求都打在注入的假 transport 上，
 * 真实 SSLSocket 通路（SOCKET_TRANSPORT）在测试里一次都不会被调用。
 *
 * 要证明的四件事：
 *  1. 「可证明未发送」只有一个来源：链路没建起来（NOT_CONNECTED）且从没调用过 write；
 *     TLS 握手阶段写出去的字节不算请求字节，refresh_token 也不在握手字节里。
 *  2. 只要 write() 被调用过 —— 哪怕它当场抛异常、计数还是 0 字节 —— 一律「可能已发送」，
 *     绝不因为异常叫 IOException 就猜没发出去。
 *  3. token 端点只有一个，一次逻辑交换只建一次链路：任何失败都不换端点重发。
 *  4. 旧行为兼容：refreshCandidate 的注入缝、refresh()/applyRefresh 的写回字段、
 *     TypedAuthException 的 status/needsSignIn 与文案全部保持原样。
 */
public final class AntigravityRefreshSendOutcomeOfflineTest {

    private static final String REFRESH = "fixture-refresh-token";
    private static final String OK_BODY =
            "{\"access_token\":\"new-access\",\"refresh_token\":\"rotated-refresh\","
            + "\"expires_in\":3600,\"token_type\":\"Bearer\"}";

    public static void main(String[] args) throws Exception {
        connectFailureIsProvablyNotSent();
        tlsHandshakeFailureIsProvablyNotSent();
        handshakeBytesNeverCountAsRequestBytes();
        zeroByteWriteFailureIsOnlyMaybeSent();
        writeFailOnBodyIsNeverCalledNotSent();
        resetAfterTheWholeRequestIsMaybeSent();
        statusReadTimeoutIsMaybeSent();
        unreadableStatusLineIsMaybeSent();
        oneLogicalExchangeOpensOneLinkAndNeverRetries();
        cancellationBeforeConnectIsProvablyNotSent();
        cancellationWhileWaitingForTheStatusLineIsMaybeSent();
        successIsAnsweredAndReusesTheChunkedDecoder();
        applyRefreshKeepsTheOldWriteBackSemantics();
        candidateSeamKeepsCompatibilityAndCarriesEvidence();
        gatewayHelperTrustsOnlyLocalEvidence();
        naiveRuleWouldHaveReplayedTheExchange();
        System.out.println("AntigravityRefreshSendOutcomeOfflineTest: PASS");
    }

    // ------------------------------------------------------------------ 1. 建链失败

    private static void connectFailureIsProvablyNotSent() {
        FakeWire wire = new FakeWire(Mode.REFUSE);
        AntigravityAuth.Exchange out = exchange(wire);
        check(!out.answered(), "a refused connection is not an answer");
        check(out.failure != null && out.failure.stage == AntigravityAuth.SendStage.NOT_CONNECTED,
                "stage must be NOT_CONNECTED, got " + (out.failure == null ? "none" : out.failure.stage));
        check(out.failure.bytesWritten == 0, "no byte can have been written");
        check(!out.failure.writeAttempted, "the transport stream was never touched");
        check(out.provablyNotSent(), "a rejected connect is provably not sent");
        check(!out.maybeSent(), "provably not sent is not maybe sent");
        check(wire.openCount == 1, "exactly one link attempt, got " + wire.openCount);
        check(wire.requestBytes() == 0, "the wire must not have seen a single request byte");
    }

    private static void tlsHandshakeFailureIsProvablyNotSent() {
        FakeWire wire = new FakeWire(Mode.TLS_FAIL);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.provablyNotSent(), "a failed TLS handshake means the request was never written");
        check(out.failure.stage == AntigravityAuth.SendStage.NOT_CONNECTED,
                "stage should be NOT_CONNECTED, got " + out.failure.stage);
        check(out.failure.bytesWritten == 0, "the handshake is not the request");
        check(wire.requestBytes() == 0, "the request channel must stay empty");
    }

    /**
     * 真实 SSLSocket 通路的形状：open() 只写握手字节（ClientHello 等），请求行与请求体
     * 要等 Link 建好才写。所以握手阶段写出去的字节既不是请求，也带不到 refresh_token。
     */
    private static void handshakeBytesNeverCountAsRequestBytes() {
        FakeWire wire = new FakeWire(Mode.TLS_FAIL);
        AntigravityAuth.Exchange out = exchange(wire);
        check(wire.handshakeBytes() > 0, "the fixture must model a handshake that really wrote bytes");
        check(!new String(wire.handshake(), StandardCharsets.ISO_8859_1).contains(REFRESH),
                "refresh_token must never travel in handshake bytes");
        check(!new String(wire.handshake(), StandardCharsets.ISO_8859_1).contains("POST "),
                "no request line may be written during the handshake");
        check(out.provablyNotSent(), "handshake traffic is not a sent request");
    }

    // ------------------------------------------------------------------ 2. 写了就是可能已发送

    private static void zeroByteWriteFailureIsOnlyMaybeSent() {
        FakeWire wire = new FakeWire(Mode.ZERO_WRITE);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.failure != null, "the write must fail");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITING,
                "stage should be WRITING, got " + out.failure.stage);
        check(out.failure.writeAttempted, "write() was called, so nothing can be proven");
        check(out.failure.bytesWritten == 0, "the counting layer reports zero acknowledged bytes");
        check(!out.provablyNotSent(), "a zero-byte write failure must not be called not sent");
        check(out.maybeSent(), "a zero-byte write failure is maybe sent");
        check(!out.failure.mayTryAnotherBase(), "a write attempt must never allow a replay");
        check(wire.requestBytes() == 0, "the fixture wrote nothing, and still counts as unknown");
    }

    private static void writeFailOnBodyIsNeverCalledNotSent() {
        FakeWire wire = new FakeWire(Mode.WRITE_FAILS_ON_BODY);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.failure != null, "the body write must fail");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITING,
                "stage should be WRITING, got " + out.failure.stage);
        check(out.failure.bytesWritten > 0 && out.failure.bytesWritten < out.failure.bytesTotal,
                "the request must be partly written, got "
                        + out.failure.bytesWritten + "/" + out.failure.bytesTotal);
        check(!out.provablyNotSent(), "a partly written request is never provably not sent");
        check(out.maybeSent(), "a partly written request is maybe sent");
        check(wire.requestBytes() > 0, "the fixture must actually have seen the headers");
        check(wire.openCount == 1, "one attempt only, got " + wire.openCount);
    }

    private static void resetAfterTheWholeRequestIsMaybeSent() {
        FakeWire wire = new FakeWire(Mode.RESET);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.failure != null, "a reset without a status line is a failure");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITTEN,
                "stage should be WRITTEN, got " + out.failure.stage);
        check(out.failure.bytesWritten == out.failure.bytesTotal,
                "the whole request was written before the reset");
        check(out.failure.writeAttempted, "the request was written");
        check(!out.provablyNotSent(), "a written request is not provably unsent");
        check(out.maybeSent(), "a written request may have been executed");
        check(wire.requestBytes() == out.failure.bytesTotal, "the wire saw exactly the whole request");
        check(wire.sawCompleteRequest(), "the fixture must have received a complete request");
    }

    private static void statusReadTimeoutIsMaybeSent() {
        FakeWire wire = new FakeWire(Mode.READ_TIMEOUT);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.failure != null && out.failure.getCause() instanceof SocketTimeoutException,
                "the timeout must be preserved as the cause");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITTEN,
                "a read timeout happens after the request was written, got " + out.failure.stage);
        check(out.maybeSent(), "a silent upstream may still have executed the exchange");
        check(!out.provablyNotSent(), "a timeout proves nothing about delivery");
        check(wire.sawCompleteRequest(), "the request reached the fixture before the timeout");
    }

    private static void unreadableStatusLineIsMaybeSent() {
        FakeWire wire = new FakeWire(Mode.GARBAGE_STATUS);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.failure != null, "an unreadable status line is a failure");
        check(out.maybeSent(), "an answered-then-garbled response is still a sent request");
        check(!out.provablyNotSent(), "nothing here proves the request was not sent");
    }

    // ------------------------------------------------------------------ 3. 不重发

    private static void oneLogicalExchangeOpensOneLinkAndNeverRetries() {
        for (Mode mode : Mode.values()) {
            if (mode == Mode.STALL) continue; // 需要另起线程取消，单独测
            FakeWire wire = new FakeWire(mode);
            AntigravityAuth.Exchange out = exchange(wire);
            check(wire.openCount == 1,
                    mode + ": one logical exchange must open exactly one link, got " + wire.openCount);
            if (out.answered()) check(wire.sawCompleteRequest(), mode + ": an answer needs a whole request");
        }
    }

    // ------------------------------------------------------------------ 4. 取消

    private static void cancellationBeforeConnectIsProvablyNotSent() {
        FakeWire wire = new FakeWire(Mode.OK);
        Signal signal = new Signal();
        signal.cancel();
        AntigravityAuth.Exchange out = AntigravityAuth.exchangeRefresh(REFRESH, wire, signal);
        check(!out.answered(), "a cancelled exchange has no answer");
        check(out.failure != null && out.failure.cancelled, "the failure must carry cancelled=true");
        check(wire.openCount == 0, "no link may be opened after cancellation");
        check(wire.requestBytes() == 0, "nothing may be written after cancellation");
        check(out.provablyNotSent(), "cancel before connect writes nothing, so it is provably not sent");
    }

    private static void cancellationWhileWaitingForTheStatusLineIsMaybeSent() {
        FakeWire wire = new FakeWire(Mode.STALL);
        Signal signal = new Signal();
        // 等夹具真的卡在 read() 里再取消，避免与「取消早于 connect」的合法分支串到同一个断言上。
        Thread canceller = new Thread(() -> {
            try { wire.blocked.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            signal.cancel();
        }, "antigravity-refresh-cancel");
        canceller.start();
        AntigravityAuth.Exchange out = AntigravityAuth.exchangeRefresh(REFRESH, wire, signal);
        try { canceller.join(5000L); } catch (InterruptedException ignored) { }
        check(out.failure != null && out.failure.cancelled, "the stalled read must end as cancelled");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITTEN,
                "the request was written before the cancel, got " + out.failure.stage);
        check(out.maybeSent(), "a cancelled request that was already written may have been executed");
        check(!out.provablyNotSent(), "cancellation after writing proves nothing");
        check(wire.openCount == 1, "one attempt only, got " + wire.openCount);
        check(wire.closed, "the link must be closed on cancellation");
    }

    // ------------------------------------------------------------------ 5. 成功路径

    private static void successIsAnsweredAndReusesTheChunkedDecoder() throws Exception {
        FakeWire wire = new FakeWire(Mode.OK_CHUNKED);
        AntigravityAuth.Exchange out = exchange(wire);
        check(out.answered(), "the fixture answers 200");
        check(out.status == 200, "status must be parsed, got " + out.status);
        check("new-access".equals(out.body.optString("access_token")),
                "the token body must be parsed, got " + out.body);
        check(!out.provablyNotSent() && out.maybeSent(),
                "an answered exchange was definitely sent, so it is maybe-sent by definition");
        check(wire.sawCompleteRequest(), "the fixture must have received a complete request");
        check(out.diagnostic().contains("oauth2.googleapis.com"), "diagnostic names the token host");
        check(!out.diagnostic().contains(REFRESH), "diagnostic must not leak the refresh token");
        check(!out.diagnostic().contains("GOCSPX-"), "diagnostic must not leak the client secret");
    }

    private static void applyRefreshKeepsTheOldWriteBackSemantics() throws Exception {
        // 成功 + 轮换：access/token 同步、expires 前进、refresh 换成新值（旧 refresh() 的确切行为）
        JSONObject account = new JSONObject("{\"refresh\":\"old\",\"access\":\"stale\",\"email\":\"a@b\"}");
        FakeWire wire = new FakeWire(Mode.OK);
        long before = System.currentTimeMillis();
        AntigravityAuth.Exchange out = exchange(wire);
        check(AntigravityAuth.applyRefresh(account, out), "a 200 with access_token must apply");
        check("new-access".equals(account.optString("access")), "access must be updated");
        check("new-access".equals(account.optString("token")), "token must mirror access");
        check("rotated-refresh".equals(account.optString("refresh")), "a rotated refresh token must be stored");
        long expires = account.optLong("expires");
        check(expires >= before + 3600_000L - 5000L, "expires must be now + expires_in, got " + expires);
        check("a@b".equals(account.optString("email")), "unrelated fields must be preserved");

        // 成功但没有新 refresh：旧值保持不动
        JSONObject keep = new JSONObject("{\"refresh\":\"keep-me\",\"access\":\"stale\"}");
        FakeWire plain = new FakeWire(Mode.OK);
        plain.body = "{\"access_token\":\"fresh\",\"expires_in\":60}";
        check(AntigravityAuth.applyRefresh(keep, exchange(plain)), "a 200 without rotation must apply");
        check("keep-me".equals(keep.optString("refresh")), "an unrotated refresh token must not be touched");

        // 失败：账号一个字段都不动
        JSONObject untouched = new JSONObject("{\"refresh\":\"old\",\"access\":\"stale\",\"expires\":5}");
        String original = untouched.toString();
        check(!AntigravityAuth.applyRefresh(untouched, exchange(new FakeWire(Mode.ZERO_WRITE))),
                "a failed exchange must not apply");
        check(untouched.toString().equals(original), "a failed exchange must not modify the account");

        // 被拒：也不动
        FakeWire rejected = new FakeWire(Mode.BAD_STATUS);
        rejected.status = 400;
        rejected.body = "{\"error\":\"invalid_grant\"}";
        check(!AntigravityAuth.applyRefresh(untouched, exchange(rejected)), "a 400 must not apply");
        check(untouched.toString().equals(original), "a rejected exchange must not modify the account");
    }

    // ------------------------------------------------------------------ 6. 旧注入缝兼容

    private static void candidateSeamKeepsCompatibilityAndCarriesEvidence() throws Exception {
        JSONObject account = new JSONObject("{\"refresh\":\"old-refresh\",\"access\":\"old-access\"}");
        String original = account.toString();

        // 旧写法：返回 CandidateResponse 就是拿到响应
        JSONObject ok = AntigravityAuth.refreshCandidate(account, refresh ->
                new AntigravityAuth.CandidateResponse(200, new JSONObject(OK_BODY)));
        check("new-access".equals(ok.optString("access")), "the legacy seam must still work");
        check("rotated-refresh".equals(ok.optString("refresh")), "rotation must still be written back");
        check(account.toString().equals(original), "refreshCandidate must never mutate the account");

        // 旧写法：状态码语义不变（status / needsSignIn / 文案）
        for (int status : new int[]{401, 429, 500}) {
            try {
                AntigravityAuth.refreshCandidate(account, refresh ->
                        new AntigravityAuth.CandidateResponse(status, new JSONObject()));
                throw new AssertionError("status " + status + " must fail");
            } catch (AntigravityAuth.TypedAuthException e) {
                check(e.status == status, "status must be preserved, got " + e.status);
                check(e.needsSignIn == (status == 401), "needsSignIn must follow the old rule");
                check(e.getMessage().contains("HTTP " + status), "the old message shape must stay");
                check(e.maybeSent(), "an answered status means the request was sent");
                check(!e.retryIsSafe(), "an answered 4xx is not retry-safe");
            }
        }
        try {
            AntigravityAuth.refreshCandidate(account, refresh ->
                    new AntigravityAuth.CandidateResponse(400, new JSONObject("{\"error\":\"invalid_grant\"}")));
            throw new AssertionError("invalid_grant must fail");
        } catch (AntigravityAuth.TypedAuthException e) {
            check(e.status == 400 && e.needsSignIn, "invalid_grant must ask for a new sign-in");
        }

        // 旧写法：抛异常 —— 异常本身没有传输层证据，只能按「可能已发送」处理
        try {
            AntigravityAuth.refreshCandidate(account, refresh -> {
                throw new IOException("connection reset");
            });
            throw new AssertionError("a throwing seam must fail");
        } catch (AntigravityAuth.TypedAuthException e) {
            check(e.status == 0 && !e.needsSignIn, "the legacy status-0 shape must stay");
            check(e.stage == AntigravityAuth.SendStage.UNKNOWN, "an exception carries no stage evidence");
            check(!e.provablyNotSent, "an opaque exception must never claim not sent");
            check(!e.retryIsSafe(), "an opaque exception must not be retry-safe");
        }
        check(account.toString().equals(original), "a failed candidate must not mutate the account");

        // 新缝：带上证据的失败
        AntigravityAuth.SendFailure notConnected = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.NOT_CONNECTED, 0, 210, false, AntigravityAuth.TOKEN_URL, 1,
                "connection refused", null);
        try {
            AntigravityAuth.refreshCandidate(account, refresh ->
                    AntigravityAuth.CandidateResponse.failed(notConnected));
            throw new AssertionError("a failed candidate must fail");
        } catch (AntigravityAuth.TypedAuthException e) {
            check(e.provablyNotSent && e.retryIsSafe(), "a link that never opened is retry-safe");
            check(e.stage == AntigravityAuth.SendStage.NOT_CONNECTED, "the stage must travel with the error");
            check(e.diagnostic().contains("never sent"), "the diagnostic must say it was never sent");
        }

        AntigravityAuth.SendFailure wroteNothing = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.WRITING, 0, 210, false, AntigravityAuth.TOKEN_URL, 1,
                "broken pipe", null, true);
        try {
            AntigravityAuth.refreshCandidate(account, refresh ->
                    AntigravityAuth.CandidateResponse.failed(wroteNothing));
            throw new AssertionError("a failed candidate must fail");
        } catch (AntigravityAuth.TypedAuthException e) {
            check(!e.provablyNotSent && !e.retryIsSafe(), "a write attempt is never retry-safe");
            check(e.maybeSent(), "a write attempt is maybe sent");
        }
    }

    // ------------------------------------------------------------------ 7. 网关判定助手

    private static void gatewayHelperTrustsOnlyLocalEvidence() {
        check(!AntigravityAuth.provablyNotSent(null), "no failure means no evidence");
        check(!AntigravityAuth.provablyNotSent(new IOException("connection refused")),
                "a bare IOException from elsewhere must never be read as not sent");
        check(!AntigravityAuth.provablyNotSent(new RuntimeException("unexpected")),
                "a bare RuntimeException must never be read as not sent");

        AntigravityAuth.SendFailure notSent = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.NOT_CONNECTED, 0, 210, false, AntigravityAuth.TOKEN_URL, 1,
                "refused", null);
        check(AntigravityAuth.provablyNotSent(notSent), "our own evidence must be honoured");
        check(AntigravityAuth.provablyNotSent(new IOException("wrapped", notSent)),
                "the decision must survive wrapping");
        check(!AntigravityAuth.provablyNotSent(
                        new AntigravityAuth.SendFailure(AntigravityAuth.SendStage.WRITTEN, 210, 210, false,
                                AntigravityAuth.TOKEN_URL, 1, "reset", null)),
                "a written request is not provably not sent");
        // NOT_CONNECTED 本身不是证据：如果有人在这之前已经写过字节，也不能说没发送。
        check(!AntigravityAuth.provablyNotSent(new AntigravityAuth.SendFailure(
                        AntigravityAuth.SendStage.NOT_CONNECTED, 0, 210, false, AntigravityAuth.TOKEN_URL, 1,
                        "refused after a write", null, true)),
                "a stage without writes is only provable when nothing was written");

        AntigravityAuth.TypedAuthException typed = new AntigravityAuth.TypedAuthException(0, false, notSent);
        check(AntigravityAuth.provablyNotSent(typed), "the typed failure must carry the same conclusion");
        check(AntigravityAuth.sendDiagnostic(typed).contains("oauth2.googleapis.com"),
                "the diagnostic must name the token host");
        check(!AntigravityAuth.sendDiagnostic(typed).contains(REFRESH), "the diagnostic must not leak the token");
    }

    // ------------------------------------------------------------------ 8. 负对照

    /**
     * 负对照：如果按「IOException ⇒ 没发出去」的朴素规则重试，同一个夹具下 token 端点会收到两次
     * 交换请求 —— 这正是本任务要排除的行为。我们的判定只允许第一次。
     */
    private static void naiveRuleWouldHaveReplayedTheExchange() {
        FakeWire wire = new FakeWire(Mode.ZERO_WRITE);
        int naiveSends = 0;
        for (int attempt = 1; attempt <= 2; attempt++) {
            AntigravityAuth.Exchange out = exchange(wire);
            if (out.answered()) break;
            naiveSends++; // 朴素规则：write() 抛 IOException ⇒ 当成没发送，直接重试
        }
        check(naiveSends == 2, "the naive rule replays the exchange, sends=" + naiveSends);
        check(wire.requestAttempts() == 2, "the fake must have seen both attempts");

        FakeWire strict = new FakeWire(Mode.ZERO_WRITE);
        AntigravityAuth.Exchange out = exchange(strict);
        check(!out.answered() && out.maybeSent(), "our rule refuses to replay");
        check(strict.requestAttempts() == 1, "our rule performs exactly one attempt");
    }

    // ------------------------------------------------------------------ 夹具

    private static AntigravityAuth.Exchange exchange(FakeWire wire) {
        return AntigravityAuth.exchangeRefresh(REFRESH, wire, AntigravityAuth.Cancellation.NONE);
    }

    private enum Mode {
        /** TCP / DNS 直接失败：一个字节都没写。 */
        REFUSE,
        /** TLS 握手失败：写了握手字节，但没写请求。 */
        TLS_FAIL,
        /** 写请求时立刻抛异常，一个字节都没被确认。 */
        ZERO_WRITE,
        /** 请求头写完，写请求体时抛异常。 */
        WRITE_FAILS_ON_BODY,
        /** 请求写完，对端连状态行都不给就断开。 */
        RESET,
        /** 请求写完，读状态行超时。 */
        READ_TIMEOUT,
        /** 请求写完，状态行是垃圾。 */
        GARBAGE_STATUS,
        /** 请求写完，卡住等取消。 */
        STALL,
        /** 正常 200 + Content-Length。 */
        OK,
        /** 正常 200 + chunked（Google 的真实形态）。 */
        OK_CHUNKED,
        /** 被拒（状态码由夹具字段决定）。 */
        BAD_STATUS
    }

    /** 假上游：握手字节与请求字节分开记账，这样才能证明「握手 ≠ 请求」。 */
    private static final class FakeWire implements AntigravityAuth.Transport {
        final Mode mode;
        String body = OK_BODY;
        int status = 200;
        int openCount;
        int requestAttempts;
        volatile boolean closed;
        /** STALL 模式用：夹具第一次真正阻塞在 read() 时放行。 */
        final CountDownLatch blocked = new CountDownLatch(1);
        private final ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        FakeWire(Mode mode) { this.mode = mode; }

        @Override public AntigravityAuth.Link open(String url, String host, int port, int connectMs, int readMs)
                throws IOException {
            openCount++;
            check(url.startsWith("https://"), "the token endpoint must stay https");
            check(url.equals(AntigravityAuth.TOKEN_URL), "the token endpoint must be the constant: " + url);
            check(host.equals("oauth2.googleapis.com"), "host must be the token host, got " + host);
            check(port == 443, "port must be 443, got " + port);
            check(connectMs > 0 && readMs > 0, "the timeouts must be finite");
            if (mode == Mode.REFUSE) throw new IOException("connection refused");
            if (mode == Mode.TLS_FAIL) {
                // 握手阶段写出去的是 TLS 记录，不是请求；refresh_token 不可能在这里。
                handshake.write("ClientHello".getBytes(StandardCharsets.US_ASCII));
                throw new IOException("TLS handshake failed");
            }
            return new Wire(this);
        }

        int handshakeBytes() { return handshake.size(); }
        byte[] handshake() { return handshake.toByteArray(); }
        int requestBytes() { return request.size(); }
        /** 假上游看到过几次「请求头开始写」——用来证明没有重放。 */
        int requestAttempts() { return requestAttempts; }

        /** 假上游是否收到了一个完整的请求（头 + 声明的 Content-Length 体）。 */
        boolean sawCompleteRequest() {
            String text = new String(request.toByteArray(), StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            if (split < 0) return false;
            String head = text.substring(0, split);
            int declared = -1;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    declared = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            return declared >= 0 && text.length() - (split + 4) == declared;
        }

        void receive(byte[] b, int off, int len) { request.write(b, off, len); }
    }

    /** 一条假链路：请求走 request 通道，响应由模式决定。 */
    private static final class Wire implements AntigravityAuth.Link {
        private final FakeWire owner;
        private int writeCalls;
        private volatile boolean closed;
        private final byte[] response;

        Wire(FakeWire owner) {
            this.owner = owner;
            owner.requestAttempts++;
            this.response = buildResponse(owner);
        }

        private static byte[] buildResponse(FakeWire owner) {
            String body = owner.body;
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            switch (owner.mode) {
                case RESET:
                    return new byte[0]; // 一断就 EOF，连状态行都没有
                case READ_TIMEOUT:
                case STALL:
                    return null;
                case GARBAGE_STATUS:
                    return "NOT-HTTP AT ALL\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                case BAD_STATUS:
                    return ("HTTP/1.1 " + owner.status + " Bad Request\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + raw.length + "\r\n\r\n" + body)
                            .getBytes(StandardCharsets.US_ASCII);
                case OK_CHUNKED: {
                    String chunked = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n"
                            + Integer.toHexString(raw.length) + "\r\n" + body + "\r\n0\r\n\r\n";
                    return chunked.getBytes(StandardCharsets.UTF_8);
                }
                default:
                    return ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + raw.length + "\r\n\r\n" + body)
                            .getBytes(StandardCharsets.US_ASCII);
            }
        }

        @Override public InputStream in() {
            if (owner.mode == Mode.READ_TIMEOUT) {
                return new InputStream() {
                    @Override public int read() throws IOException { throw new SocketTimeoutException("read timed out"); }
                };
            }
            if (response == null) {
                // 卡住等取消：真实 socket 上表现为 read() 一直阻塞到链路被关闭。
                return new InputStream() {
                    @Override public int read() throws IOException {
                        owner.blocked.countDown();
                        synchronized (Wire.this) {
                            while (!closed) {
                                try { Wire.this.wait(2000L); } catch (InterruptedException e) {
                                    throw new IOException("interrupted");
                                }
                            }
                        }
                        throw new IOException("socket closed");
                    }
                };
            }
            return new ByteArrayInputStream(response);
        }

        @Override public OutputStream out() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (owner.mode == Mode.ZERO_WRITE) throw new IOException("broken pipe");
                    if (owner.mode == Mode.WRITE_FAILS_ON_BODY && writeCalls >= 1) {
                        throw new IOException("broken pipe while writing the body");
                    }
                    writeCalls++;
                    owner.receive(b, off, len);
                }

                @Override public void flush() { }
            };
        }

        @Override public synchronized void close() {
            closed = true;
            owner.closed = true;
            notifyAll();
        }
    }

    /** 可手动触发的取消信号。 */
    private static final class Signal implements AntigravityAuth.Cancellation {
        private final List<Runnable> hooks = new ArrayList<Runnable>();
        private volatile boolean cancelled;

        @Override public boolean isCancelled() { return cancelled; }

        @Override public synchronized void onAbort(Runnable hook) {
            if (hook == null) return;
            if (cancelled) hook.run();
            else hooks.add(hook);
        }

        synchronized void cancel() {
            if (cancelled) return;
            cancelled = true;
            for (Runnable hook : hooks) {
                try { hook.run(); } catch (Throwable ignored) { }
            }
            hooks.clear();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
