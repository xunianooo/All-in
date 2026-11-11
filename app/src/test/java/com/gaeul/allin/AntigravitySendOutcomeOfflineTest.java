package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Antigravity 出站结果分类的定向离线测试（不联网、不启动真实服务）。
 *
 * 用注入的 Transport 造两个互相隔离的假上游（A = 主 base，B = 备用 base），
 * 证明：
 *  1. 只有「一个字节都没写出」的失败才允许换 base；
 *  2. 部分写 / 写完没响应 / 0 字节写失败，一律按「可能已执行」处理，绝不重放；
 *  3. 取消后既不再建链路也不再写，且如实标记 cancelled；
 *  4. 负对照：复现当前 GatewayServer.forwardAntigravity 的 catch-all 换 base 写法，
 *     同一个夹具下假上游会收到两次请求（即审计 E 条担心的重复执行）。
 */
public final class AntigravitySendOutcomeOfflineTest {

    private static final String BASE_A = "https://daily-cloudcode-pa.fake.invalid";
    private static final String BASE_B = "https://cloudcode-pa.fake.invalid";
    private static final String PATH = AntigravityAuth.STREAM_PATH;
    private static final byte[] BODY = ("{\"model\":\"gemini-3.1-pro\",\"project\":\"fixture\","
            + "\"request\":{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"probe\"}]}]}}")
            .getBytes(StandardCharsets.UTF_8);
    private static final String SS_EVENT = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n";

    public static void main(String[] args) throws Exception {
        stagesClassifyConservatively();
        preWriteFailureFallsThroughToTheSecondUpstream();
        partialWriteIsNeverReplayed();
        zeroByteWriteFailureIsStillTreatedAsSent();
        resetAfterFullWriteIsUnknownAndStops();
        cancellationBeforeSendTouchesNoUpstream();
        cancellationWhileReadingHeadersIsReported();
        cancellationDuringBodyReadFailsClosed();
        cancelAfterAbortStillStopsTheSecondUpstream();
        successSendsExactlyOnceAndDecodesChunked();
        currentGatewayLoopWouldHaveSentTwice();
        System.out.println("AntigravitySendOutcomeOfflineTest: PASS");
    }

    // ------------------------------------------------------------------ 用例

    private static void stagesClassifyConservatively() {
        AntigravityAuth.SendFailure notConnected = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.NOT_CONNECTED, 0, 100, false, BASE_A, 1, "refused", null);
        check(!notConnected.handedOff(), "NOT_CONNECTED must not count as handed off");
        check(notConnected.mayTryAnotherBase(), "NOT_CONNECTED must allow another base");

        AntigravityAuth.SendFailure zeroByteWrite = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.WRITING, 0, 100, false, BASE_A, 1, "broken pipe", null);
        check(zeroByteWrite.handedOff(), "a failed zero-byte write must still be treated as sent");
        check(!zeroByteWrite.mayTryAnotherBase(), "a write attempt must not allow another base");

        AntigravityAuth.SendFailure partial = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.WRITING, 40, 100, false, BASE_A, 1, "broken pipe", null);
        check(partial.handedOff() && !partial.mayTryAnotherBase(), "partial write must not be replayed");

        AntigravityAuth.SendFailure unknown = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.WRITTEN, 100, 100, false, BASE_A, 1, "reset", null);
        check(unknown.handedOff() && !unknown.mayTryAnotherBase(), "post-write reset must not be replayed");

        AntigravityAuth.SendFailure cancelled = new AntigravityAuth.SendFailure(
                AntigravityAuth.SendStage.NOT_CONNECTED, 0, 100, true, BASE_A, 1, "cancelled", null);
        check(!cancelled.mayTryAnotherBase(), "a cancelled request must never be replayed");
        check(cancelled.diagnostic().contains("cancelled"), "cancelled diagnostic must say so");
    }

    private static void preWriteFailureFallsThroughToTheSecondUpstream() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.FAIL_OPEN, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), AntigravityAuth.Cancellation.NONE);
        try {
            check(out.ok(), "second upstream should answer: " + out.diagnostic());
            check(BASE_B.equals(out.usedBase), "usedBase should be the second base");
            check(out.sendCount == 1, "exactly one request may reach an upstream, got " + out.sendCount);
            check(out.openCount == 2, "two connection attempts expected, got " + out.openCount);
            check(a.requestsSeen == 0 && b.requestsSeen == 1, "only the second upstream may see a request");
            check(b.received != null && b.received.length > BODY.length,
                    "second upstream must get the whole request (headers + body)");
            check(contains(b.received, "Authorization: Bearer fixture-token"), "request headers must reach the wire");
            check(new String(b.received, StandardCharsets.UTF_8).endsWith(new String(BODY, StandardCharsets.UTF_8)),
                    "request body must be appended unchanged");
        } finally {
            if (out.resp != null) out.resp.close();
        }
    }

    private static void partialWriteIsNeverReplayed() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.PARTIAL_WRITE, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), AntigravityAuth.Cancellation.NONE);
        check(!out.ok(), "a broken pipe during the body write must not be reported as success");
        check(out.failure != null, "failure must be classified");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITING, "stage should be WRITING, got " + out.failure.stage);
        check(out.failure.bytesWritten > 0 && out.failure.bytesWritten < out.failure.bytesTotal,
                "expected a partly written request, got " + out.failure.bytesWritten + "/" + out.failure.bytesTotal);
        check(out.sendCount == 1, "the request was handed off exactly once, got " + out.sendCount);
        check(b.openCount == 0, "the fallback base must never be opened after a partial write");
        check(b.requestsSeen == 0, "the fallback upstream must never see the request");
        check(a.received != null && a.received.length > 0, "the primary upstream must have seen bytes");
        check(a.requestsSeen == 0, "the truncated request must not count as a complete request");
        check(out.diagnostic().contains("没有换端点重发"), "diagnostic must say the request was not replayed: " + out.diagnostic());
    }

    private static void zeroByteWriteFailureIsStillTreatedAsSent() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.ZERO_WRITE, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), AntigravityAuth.Cancellation.NONE);
        check(out.failure != null && out.failure.stage == AntigravityAuth.SendStage.WRITING,
                "a write that throws must still be classified as WRITING");
        check(out.failure.bytesWritten == 0, "the counting layer must report zero acknowledged bytes");
        check(out.failure.handedOff(), "zero acknowledged bytes after a write attempt must count as handed off");
        check(out.sendCount == 1, "one handoff expected, got " + out.sendCount);
        check(b.openCount == 0 && b.requestsSeen == 0, "the conservative rule must stop the fallback base");
    }

    private static void resetAfterFullWriteIsUnknownAndStops() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.RESET_AFTER_WRITE, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), AntigravityAuth.Cancellation.NONE);
        check(out.failure != null, "a reset with no response is a failure");
        check(out.failure.stage == AntigravityAuth.SendStage.WRITTEN, "stage should be WRITTEN, got " + out.failure.stage);
        check(out.failure.bytesWritten == out.failure.bytesTotal, "the whole request was written before the reset");
        check(a.requestsSeen == 1, "the primary upstream completed one request");
        check(a.closed, "the dead link must be closed");
        check(b.openCount == 0 && b.requestsSeen == 0, "an unknown execution must never be replayed elsewhere");
        check(out.diagnostic().contains("可能已被上游执行"), "diagnostic must say the outcome is unknown: " + out.diagnostic());
    }

    private static void cancellationBeforeSendTouchesNoUpstream() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.OK, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        Signal signal = new Signal();
        signal.cancel("client went away");
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), signal);
        check(!out.ok() && out.cancelled, "a cancelled dispatch must report cancellation");
        check(out.sendCount == 0, "nothing may be sent after cancellation, got " + out.sendCount);
        check(a.openCount == 0 && b.openCount == 0, "no link may be opened after cancellation");
        check(out.diagnostic().contains("取消"), "diagnostic must mention cancellation: " + out.diagnostic());
    }

    private static void cancellationWhileReadingHeadersIsReported() {
        Signal signal = new Signal();
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.CANCEL_ON_HEADER, signal);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), signal);
        check(out.cancelled, "the transport must notice the cancel while reading headers");
        check(out.failure != null && out.failure.cancelled, "failure must carry cancelled=true");
        check(out.failure.stage == AntigravityAuth.SendStage.READING_STATUS,
                "stage should be READING_STATUS, got " + out.failure.stage);
        check(!out.failure.mayTryAnotherBase(), "a cancelled request must not be retried");
        check(out.sendCount == 1, "the request was handed off once, got " + out.sendCount);
        check(b.openCount == 0 && b.requestsSeen == 0, "cancellation must stop the fallback base");
        check(a.closed, "the link must be closed on cancellation");
    }

    private static void cancellationDuringBodyReadFailsClosed() throws Exception {
        Signal signal = new Signal();
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.OK, signal);
        AntigravityAuth.Resp resp = AntigravityAuth.request(BASE_A + PATH, "POST", "fixture-token",
                "text/event-stream", "application/json", BODY, 1000, 5000,
                a, signal, 1);
        try {
            check(resp.status == 200, "fixture should answer 200, got " + resp.status);
            signal.cancel("client went away mid-stream");
            try {
                while (resp.body.read() >= 0) {
                    // 取消后必须抛出，不能把截断的流当正常结束
                }
                throw new AssertionError("reading a cancelled body must fail");
            } catch (AntigravityAuth.SendFailure failure) {
                check(failure.cancelled, "body cancel must be flagged as cancelled");
                check(failure.stage == AntigravityAuth.SendStage.READING_BODY,
                        "stage should be READING_BODY, got " + failure.stage);
                check(failure.handedOff(), "a body that was being read was definitely handed off");
            }
            check(a.closed, "cancelling must close the link");
        } finally {
            resp.close();
        }
    }

    private static void cancelAfterAbortStillStopsTheSecondUpstream() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.STALL_THEN_CANCEL, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        Signal signal = new Signal();
        a.signal = signal;
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), signal);
        check(out.cancelled, "a stalled read that gets cancelled must abort the dispatch");
        check(out.sendCount == 1, "only the first handoff counts, got " + out.sendCount);
        check(b.openCount == 0 && b.requestsSeen == 0, "no replay on the fallback base");
        check(a.closed, "the stalled link must be closed");
    }

    private static void successSendsExactlyOnceAndDecodesChunked() throws Exception {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.OK, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        AntigravityAuth.Dispatch out = AntigravityAuth.dispatch(
                new String[]{BASE_A, BASE_B}, PATH, "fixture-token", BODY, 1000, 5000,
                new Switchboard(a, b), AntigravityAuth.Cancellation.NONE);
        try {
            check(out.ok(), "the first upstream should answer: " + out.diagnostic());
            check(out.sendCount == 1, "exactly one send, got " + out.sendCount);
            check(out.openCount == 1, "exactly one connection attempt, got " + out.openCount);
            check(a.requestsSeen == 1 && b.requestsSeen == 0, "only the first upstream may see the request");
            check(out.resp.status == 200, "status must be parsed from the injected stream");
            check("text/event-stream".equals(out.resp.headers.get("content-type")),
                    "response headers must be parsed, got " + out.resp.headers);
            check(SS_EVENT.equals(out.resp.text()), "chunked body must decode to the fixture event, got '"
                    + out.resp.text() + "'");
            check(out.resp.stage == AntigravityAuth.SendStage.READING_BODY,
                    "a returned response already proves the upstream executed");
        } finally {
            if (out.resp != null) out.resp.close();
        }
        check(a.closed, "closing the response must close the link");
    }

    /**
     * 负对照：把当前 forwardAntigravity 的写法（catch 任意异常就换 base）复现一遍，
     * 同一个「部分写」夹具下两个假上游都会收到请求 —— 这就是审计 E 条说的问题。
     */
    private static void currentGatewayLoopWouldHaveSentTwice() {
        FakeUpstream a = new FakeUpstream(BASE_A, Mode.PARTIAL_WRITE, null);
        FakeUpstream b = new FakeUpstream(BASE_B, Mode.OK, null);
        Switchboard board = new Switchboard(a, b);
        String[] bases = new String[]{BASE_A, BASE_B};
        int legacyRequests = 0;
        for (int i = 0; i < bases.length; i++) {
            try {
                AntigravityAuth.Resp resp = AntigravityAuth.request(bases[i] + PATH, "POST", "fixture-token",
                        "text/event-stream", "application/json", BODY, 1000, 5000,
                        board, AntigravityAuth.Cancellation.NONE, 1);
                resp.close();
                break;
            } catch (Exception ignored) {
                // 旧代码就是这样吞掉所有异常再换 base 的
            }
        }
        legacyRequests = a.requestsSeen + b.requestsSeen + (a.received != null && a.received.length > 0 ? 1 : 0);
        check(a.received != null && a.received.length > 0, "the legacy loop must have reached the first upstream");
        check(b.requestsSeen == 1, "the legacy loop replayed the request on the fallback base");
        check(legacyRequests >= 2, "the legacy loop produced at least two upstream receptions, got " + legacyRequests);
    }

    // ------------------------------------------------------------------ 假上游

    enum Mode { OK, FAIL_OPEN, ZERO_WRITE, PARTIAL_WRITE, RESET_AFTER_WRITE, CANCEL_ON_HEADER, STALL_THEN_CANCEL }

    /** 两个假上游共用的 transport：按 host 分派，绝不联网。 */
    static final class Switchboard implements AntigravityAuth.Transport {
        private final FakeUpstream[] upstreams;

        Switchboard(FakeUpstream... upstreams) { this.upstreams = upstreams; }

        @Override public AntigravityAuth.Link open(String url, String host, int port, int connectMs, int readMs)
                throws IOException {
            for (FakeUpstream upstream : upstreams) {
                if (upstream.host.equals(host)) return upstream.open();
            }
            throw new IOException("fixture: no upstream for " + host);
        }
    }

    static final class FakeUpstream implements AntigravityAuth.Transport {
        final String base;
        final String host;
        final Mode mode;
        Signal signal;
        int openCount;
        int writeCalls;
        int requestsSeen;
        byte[] received;
        boolean closed;

        FakeUpstream(String base, Mode mode, Signal signal) {
            this.base = base;
            this.host = AntigravityAuth.hostOf(base);
            this.mode = mode;
            this.signal = signal;
        }

        @Override public AntigravityAuth.Link open(String url, String host, int port, int connectMs, int readMs)
                throws IOException {
            return open();
        }

        AntigravityAuth.Link open() throws IOException {
            openCount++;
            if (mode == Mode.FAIL_OPEN) throw new IOException("fixture: connection refused");
            return new FakeLink(this);
        }

        void snapshot(byte[] bytes) { received = bytes; }

        void complete(byte[] bytes) { received = bytes; requestsSeen++; }
    }

    static final class FakeLink implements AntigravityAuth.Link {
        private final FakeUpstream upstream;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final InputStream response;
        private boolean flushed;

        FakeLink(FakeUpstream upstream) {
            this.upstream = upstream;
            this.response = upstream.mode == Mode.OK || upstream.mode == Mode.CANCEL_ON_HEADER
                    ? new ByteArrayInputStream(chunkedSse())
                    : new ByteArrayInputStream(new byte[0]);
        }

        @Override public InputStream in() {
            if (upstream.mode == Mode.RESET_AFTER_WRITE) {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        throw new IOException("fixture: connection reset after the request was written");
                    }
                };
            }
            if (upstream.mode == Mode.STALL_THEN_CANCEL) {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                        }
                        upstream.signal.cancel("fixture: client disconnected while waiting for the response");
                        throw new IOException("fixture: aborted while waiting for the status line");
                    }
                };
            }
            if (upstream.mode == Mode.CANCEL_ON_HEADER) {
                return new InputStream() {
                    private final ByteArrayInputStream status =
                            new ByteArrayInputStream("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
                    private final ByteArrayInputStream rest =
                            new ByteArrayInputStream("content-type: text/event-stream\r\n\r\n"
                                    .getBytes(StandardCharsets.UTF_8));

                    @Override public int read() throws IOException {
                        int c = status.read();
                        if (c >= 0) return c;
                        upstream.signal.cancel("fixture: client disconnected while reading headers");
                        return rest.read();
                    }
                };
            }
            return response;
        }

        @Override public OutputStream out() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    upstream.writeCalls++;
                    if (upstream.mode == Mode.ZERO_WRITE) {
                        throw new IOException("fixture: socket closed before any byte was accepted");
                    }
                    if (upstream.mode == Mode.PARTIAL_WRITE && upstream.writeCalls > 1) {
                        // 请求头那一段写出去了，请求体一个字节都没交出去就断了
                        upstream.snapshot(sink.toByteArray());
                        throw new IOException("fixture: broken pipe while writing the request body");
                    }
                    sink.write(b, off, len);
                    if (upstream.mode == Mode.PARTIAL_WRITE) upstream.snapshot(sink.toByteArray());
                }

                @Override public void flush() {
                    if (flushed) return;
                    flushed = true;
                    if (upstream.mode == Mode.OK || upstream.mode == Mode.RESET_AFTER_WRITE
                            || upstream.mode == Mode.CANCEL_ON_HEADER || upstream.mode == Mode.STALL_THEN_CANCEL) {
                        upstream.complete(sink.toByteArray());
                    }
                }
            };
        }

        @Override public void close() {
            upstream.closed = true;
        }
    }

    /** 手工 chunked 的 SSE 响应：验证注入链路下 chunked 解码仍然正常。 */
    private static byte[] chunkedSse() {
        byte[] event = SS_EVENT.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write((Integer.toHexString(event.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(event);
            out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    static final class Signal implements AntigravityAuth.Cancellation {
        private final AtomicBoolean flag = new AtomicBoolean();
        private final List<Runnable> hooks = new ArrayList<Runnable>();

        @Override public boolean isCancelled() { return flag.get(); }

        @Override public void onAbort(Runnable hook) {
            boolean runNow;
            synchronized (this) {
                runNow = flag.get();
                if (!runNow) hooks.add(hook);
            }
            if (runNow) hook.run();
        }

        void cancel(String why) {
            List<Runnable> pending;
            synchronized (this) {
                if (!flag.compareAndSet(false, true)) return;
                pending = new ArrayList<Runnable>(hooks);
                hooks.clear();
            }
            for (Runnable hook : pending) {
                try { hook.run(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ 断言

    private static boolean contains(byte[] data, String needle) {
        return data != null && new String(data, StandardCharsets.UTF_8).contains(needle);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
