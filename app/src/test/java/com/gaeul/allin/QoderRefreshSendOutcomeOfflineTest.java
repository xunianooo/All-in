package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import com.gaeul.allin.json.JSONObject;

/**
 * Qoder 刷新请求的「单次 TLS 传输 + 发送阶段辨别」离线测试：假传输、真实环回 socket、不联网、不运行 Gradle。
 *
 * <p>刷新 token 是一次性的，所以真正要回答的不是「失败了没有」，而是「失败之前本机到底把请求交出去了没有」，
 * 以及「这次交换到底发了几个请求」。本文件分层钉住这些事实：
 *
 * <ol>
 *   <li><b>可判定的建连</b>：生产传输先 {@link NetworkPolicy.Snapshot#connectTunnel} 建连、再 TLS 握手、
 *       然后才写请求行。因此策略拒绝、TCP 失败、TLS 握手失败都是 {@code NOT_CONNECTED}（真的没发出去，
 *       同一个一次性 token 可以安全重试）；写起请求之后的任何失败都是「未知」。</li>
 *   <li><b>单次发送</b>：一次刷新只 open 一次、写一次请求、读一次答复，没有重试循环，也不经过
 *       {@code HttpURLConnection}（后者在「请求已写出、连接被掐」时会自行重发 POST）。</li>
 *   <li><b>响应语义照上游</b>：chunked 与 gzip 解开、截断与超限当不可读、1xx 过渡答复跳过、
 *       重定向不跟随（一次性 token 不转发到别的来源）、非 200 仍是 {@code QoderHttpException}。</li>
 *   <li><b>只在安全不歧义时容忍</b>：非 framing 的重复头（Via／Set-Cookie／trace 头）、标准
 *       chunk extension（{@code ;name=value}），以及非 framing 头值里的非 ASCII 字节（obs-text）
 *       都被忽略；凡是决定边界或解码的头一冲突就拒绝
 *       （CL+TE、两个不同的 CL、两个 TE、矛盾的 Content-Encoding、含填充的块终止符），
 *       带宽／长度／编码的头值带非 ASCII 同样拒绝。理由很具体：这些容忍掉的差异若被判死，
 *       就是「上游已经轮换、本机却丢新 token」的纯损失；而 framing 一旦松动，丢的是边界本身。</li>
 *   <li><b>解析失败不带走凭据</b>：JSON 解析器的异常绝不作为 cause 外传——它的 message 是
 *       被解析正文派生的，而那正是新签发的一对 token。结论仍是「已答复、已花掉」。</li>
 *   <li><b>身份安全</b>：只接受 https、不许 userinfo／query／fragment；请求头里没有 Authorization／Cookie，
 *       一次性 token 只出现在请求体里，且只出现一次；也没有任何编造的指纹——User-Agent 不发，
 *       理由见 {@link QoderAuth#requestHead}（上游这个端点没有声明值）。</li>
 *   <li><b>兼容</b>：注入 {@code HttpURLConnection} 工厂的旧重载语义一字未改（真实断网仍落 CONNECTED）。</li>
 * </ol>
 *
 * <p><b>时间预算的边界（不粉饰）</b>：{@link QoderAuth#REFRESH_TIMEOUT_MS} 约束的是 TCP 建连、代理
 * CONNECT、TLS 握手和<b>每一次读</b>（读之前把 {@code soTimeout} 收敛到剩余时间，因此逐步共享同一个
 * deadline，本文件用真实环回钉住）。它<b>不</b>约束 DNS 解析，也不约束阻塞写（Java/Android 没有写超时），
 * 所以它不是 socket 级的硬上限。上游 {@code fetch} 的 AbortSignal 连写一起中止，本机做不到完全等价。
 *
 * <p>真实 socket 只连 127.0.0.1：TLS 握手失败用「回非 TLS 字节的环回服务端」制造，明文目标用「有没有连上来」
 * 证明，读预算用「只说一半的环回服务端」验证，全程不碰任何外部地址、不发真实厂商请求。
 */
public final class QoderRefreshSendOutcomeOfflineTest {

    private static final QoderAuth.RefreshSendStage NOT_CONNECTED = QoderAuth.RefreshSendStage.NOT_CONNECTED;
    private static final QoderAuth.RefreshSendStage CONNECTED = QoderAuth.RefreshSendStage.CONNECTED;
    private static final QoderAuth.RefreshSendStage WRITING = QoderAuth.RefreshSendStage.WRITING;
    private static final QoderAuth.RefreshSendStage READING_STATUS = QoderAuth.RefreshSendStage.READING_STATUS;
    private static final QoderAuth.RefreshSendStage READING_BODY = QoderAuth.RefreshSendStage.READING_BODY;

    private static final String BASE = "https://openapi.qoder.com.cn";
    private static final String OK_BODY = "{\"token\":\"access-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":3600}";
    private static final String TRUNCATED_BODY = "{\"token\":\"leak-me-not\"}";
    private static final String SECRET = "SECRET-REFRESH-TOKEN";

    public static void main(String[] args) throws Exception {
        stageRulesAreConservative();
        defaultTransportIsTheSingleShotTlsOne();
        requestHeadCarriesNoCredentialChannel();
        theRefreshRequestSendsNoInventedUserAgent();
        authorityFollowsTheDefaultPort();
        httpFramingIsReadStrictly();
        duplicatedNonFramingHeadersAreTolerated();
        chunkExtensionsAreTolerated();
        nonAsciiIrrelevantHeadersAreTolerated();
        nonAsciiNeverLoosensFraming();
        gzipResponsesAreDecodedAndBounded();
        aRealSocketCarriesExactlyOneRequestAndOneAnswer();
        aRealAnswerWithDuplicateHeadersStillRotates();
        aNonAsciiAnswerStillRotatesOnTheWire();
        aRealAnswerWithDuplicateHeadersStillRotates();
        theRealLinkRefusesASecondRequest();
        theReadDeadlineIsSharedAcrossReads();
        jsonParseFailuresNeverCarryTheAnswer();
        invalidTargetsAreRefusedBeforeAnyByte();
        aTlsHandshakeFailureIsProvenNotSent();
        aPlaintextTargetNeverConnects();
        aBlockedPolicyRefusesBeforeAnySocket();
        aConnectFailureIsProvenNotSent();
        aConnectTimeoutIsProvenNotSent();
        missingTokenNeverOpensALink();
        oversizedBodyNeverOpensALink();
        aRequestLineFailureIsUnknown();
        aNullRequestStreamIsUnknown();
        aNullLinkIsNotSent();
        zeroByteWriteIsUnknown();
        partialWriteIsUnknownAndSpent();
        aFlushFailureIsAWriteFailure();
        aLostStatusLineIsUnknown();
        aRedirectIsNeverFollowed();
        aRejectionStaysAQoderHttpException();
        conflictingFramingIsStillRefused();
        aTruncatedPairKeepsTheLegacyReason();
        aLostBodyAfter200IsAnsweredButSpent();
        successRotatesExactlyOnceAndLeavesTheOldAccount();
        theDevicePathKeepsItsOwnEndpointAndFields();
        secretsNeverReachMessagesOrDiagnostics();
        unclassifiedFailuresAreConservativelyUnknown();
        theLegacyFactoryPathStillBehavesAsBefore();
        aCallerFollowingTheContractStopsAfterOneHandoff();
        legacyCatchAllRetryWouldSpendTheTokenTwice();
        System.out.println("QoderRefreshSendOutcomeOfflineTest: PASS");
    }

    // ------------------------------------------------------------------ 判定规则

    private static void stageRulesAreConservative() throws Exception {
        QoderAuth.RefreshSendFailure notSent = verdict(NOT_CONNECTED, 0, 100, false, false);
        check(notSent.definitelyNotSent(), "NOT_CONNECTED must be the only stage that counts as not sent");
        check(notSent.mayReplay(), "NOT_CONNECTED must allow replaying the same one-use token");
        check(!notSent.outcomeUnknown(), "NOT_CONNECTED must not be reported as unknown");
        check(!notSent.answered(), "nothing was answered");
        check(notSent.diagnostic().contains("没有发出去"), "diagnostic must say it was not sent: " + notSent.diagnostic());
        check(notSent.diagnostic().contains("TLS") || notSent.diagnostic().contains("建连"),
                "diagnostic must name the failures that are provable now: " + notSent.diagnostic());

        QoderAuth.RefreshSendFailure queued = verdict(CONNECTED, 0, 100, false, false);
        check(!queued.definitelyNotSent(), "a connection whose request line may be queued must not count as not sent");
        check(queued.outcomeUnknown(), "the request line may already be on the wire, so this is unknown");
        check(queued.diagnostic().contains("禁止重放"), "the CONNECTED diagnostic must forbid replay: " + queued.diagnostic());

        QoderAuth.RefreshSendFailure zero = verdict(WRITING, 0, 100, false, false);
        check(!zero.definitelyNotSent(), "a failed zero-byte write is still a write attempt");
        check(zero.outcomeUnknown(), "zero written bytes must stay unknown, never 'not sent'");

        QoderAuth.RefreshSendFailure partial = verdict(WRITING, 40, 100, false, false);
        check(partial.outcomeUnknown() && !partial.definitelyNotSent(), "a partial write must not be replayed");
        check(partial.diagnostic().contains("40/100"), "diagnostic must carry the byte window: " + partial.diagnostic());

        QoderAuth.RefreshSendFailure silent = verdict(READING_STATUS, 100, 100, false, false);
        check(silent.outcomeUnknown(), "a lost status line after the whole body must be unknown");
        check(silent.diagnostic().contains("可能已被上游执行"), "diagnostic must warn about execution: " + silent.diagnostic());

        QoderAuth.RefreshSendFailure answered = verdict(READING_BODY, 100, 100, false, true);
        check(answered.answered() && !answered.outcomeUnknown(), "an answered exchange is known, not unknown");
        check(!answered.definitelyNotSent() && !answered.mayReplay(), "an answered exchange can never be replayed");

        QoderAuth.RefreshSendFailure cancelled = verdict(NOT_CONNECTED, 0, 100, true, false);
        check(cancelled.cancelled(), "cancellation must be recorded");
        check(!cancelled.definitelyNotSent() && !cancelled.mayReplay(),
                "cancellation may land after the request line was handed off, so it is never 'not sent'");
        check(cancelled.outcomeUnknown(), "a cancelled refresh is unknown until proven otherwise");
        check(cancelled.diagnostic().contains("取消"), "diagnostic must say it was cancelled: " + cancelled.diagnostic());

        QoderAuth.RefreshSendFailure clamped = verdict(WRITING, 900, 100, false, false);
        check(clamped.bytesWritten() == 100, "written bytes must never exceed the body size, got " + clamped.bytesWritten());
    }

    private static void defaultTransportIsTheSingleShotTlsOne() {
        QoderAuth.RefreshTransport transport = QoderAuth.tlsTransport();
        check(transport != null, "the production transport must exist");
        check(transport instanceof QoderAuth.TlsRefreshTransport,
                "the production entry must not be a HttpURLConnection factory, got " + transport.getClass().getName());
        check(QoderAuth.REFRESH_TIMEOUT_MS == 20000,
                "the read/connect budget must stay the upstream 20s (a client-side budget, not a socket cap), got " + QoderAuth.REFRESH_TIMEOUT_MS);
    }

    private static void requestHeadCarriesNoCredentialChannel() {
        String head = QoderAuth.requestHead("openapi.qoder.com.cn", "/api/v1/jobToken/refresh", 38);
        check(head.startsWith("POST /api/v1/jobToken/refresh HTTP/1.1\r\n"), "request line: " + head.split("\r\n")[0]);
        check(head.contains("Host: openapi.qoder.com.cn\r\n"), "host header missing: " + head);
        check(head.contains("Accept: application/json\r\n"), "accept header missing: " + head);
        check(head.contains("Content-Type: application/json\r\n"), "content type missing: " + head);
        check(head.contains("Content-Length: 38\r\n"), "length must be explicit: " + head);
        check(head.contains("Connection: close\r\n"), "one exchange per connection: " + head);
        check(!head.contains("Authorization") && !head.contains("Cookie") && !head.contains("Bearer"),
                "the once-only token must never ride in a header: " + head);
        check(head.endsWith("\r\n\r\n"), "the head must end with an empty line: " + head);
        check(!head.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding"),
                "a fixed length must not be sent with a chunked body: " + head);
    }

    /**
     * 单次传输不发 User-Agent 是被决定过的，不是漏掉的。
     *
     * <p>上游 {@code openapi()}（0.2.64）只写 Accept／有 body 时的 Content-Type／有 token 时的
     * Authorization，再加 {@code site.clientHeaders?.()}；而 clientHeaders 只有 QwenWork 站定义，
     * Qoder 与 Qoder CN 两站都没有。本工程里 Qoder 唯一的 UA 是 {@code Magic/App}，只用在登录的
     * start／poll 上。既然没有上游声明值、也没有同端点的可信值，就不发——将来若要加，必须先有上游
     * 声明值，这条测试会先把「凭感觉加一个」拦下来。
     */
    private static void theRefreshRequestSendsNoInventedUserAgent() throws Exception {
        String head = QoderAuth.requestHead("openapi.qoder.com.cn", "/api/v1/jobToken/refresh", 38);
        check(!head.toLowerCase(java.util.Locale.ROOT).contains("user-agent"),
                "no User-Agent may be invented for the token refresh: " + head);
        check(head.contains("Accept: application/json\r\n") && head.contains("Content-Type: application/json\r\n"),
                "the headers upstream does declare must stay: " + head);
        check(head.contains("Accept-Encoding: identity\r\n"),
                "the answer must be read as sent: " + head);

        String answer = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + OK_BODY.length() + "\r\nConnection: close\r\n\r\n" + OK_BODY;
        try (Loopback server = new Loopback(answer.getBytes(StandardCharsets.ISO_8859_1), 5000)) {
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", 2, 5000);
            try {
                OutputStream request = link.request();
                request.write("{}".getBytes(StandardCharsets.UTF_8));
                request.flush();
                check(link.statusLine() == 200, "the status must be read");
            } finally {
                link.close();
            }
            check(server.await(4000), "the loopback server must have finished");
            String seen = server.request();
            check(!seen.toLowerCase(java.util.Locale.ROOT).contains("user-agent"),
                    "the request on the wire must carry no User-Agent: " + seen);
            check(seen.contains("Host: 127.0.0.1:" + server.port() + "\r\n"),
                    "the host header must still be there: " + seen);
        }
    }

    private static void authorityFollowsTheDefaultPort() {
        check("openapi.qoder.com.cn".equals(QoderAuth.authority("openapi.qoder.com.cn", 443)),
                "the default port must not appear in Host");
        check("127.0.0.1:8443".equals(QoderAuth.authority("127.0.0.1", 8443)), "an explicit port must appear");
        check("[::1]:8443".equals(QoderAuth.authority("::1", 8443)), "IPv6 must be bracketed");
    }

    // ------------------------------------------------------------------ 响应体解析（纯合成流）

    private static void httpFramingIsReadStrictly() throws Exception {
        QoderAuth.Head head = QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: 5\r\n\r\nhello"));
        check(head.status == 200, "status: " + head.status);
        check(head.contentLength == 5 && !head.chunked && head.encoding == null,
                "framing: length=" + head.contentLength + " chunked=" + head.chunked + " encoding=" + head.encoding);

        InputStream chunked = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n");
        QoderAuth.Head chunkedHead = QoderAuth.readHead(chunked);
        check(chunkedHead.chunked, "the chunked flag must be set");
        check("abcde".equals(new String(QoderAuth.readBody(chunked, chunkedHead, 1024), "UTF-8")),
                "chunked bodies must be reassembled");

        // 只有 trailer 的块也被接受（内容丢弃、行数有界）
        InputStream trailers = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "2\r\nhi\r\n0\r\nX-Trace: 1\r\n\r\n");
        QoderAuth.Head trailerHead = QoderAuth.readHead(trailers);
        check("hi".equals(new String(QoderAuth.readBody(trailers, trailerHead, 64), "UTF-8")),
                "chunk trailers must be drained, not parsed as body");

        expectRefusal("a missing terminal chunk must not be accepted", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhi\r\n");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
        expectRefusal("a chunk past the bound must be refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffff\r\n");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
        expectRefusal("chunked plus content-length is ambiguous", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n"));
            }
        });
        expectRefusal("two differing content-length values must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nok"));
            }
        });
        expectRefusal("an unknown transfer coding must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n"));
            }
        });
        expectRefusal("a malformed status line must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 OK\r\n\r\n"));
            }
        });
        expectRefusal("a bare LF must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
            }
        });
        expectRefusal("a body shorter than its content-length is truncated", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
        expectRefusal("a body past the bound must be refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });

        // 1xx 是过渡答复：跳过它，读真正的状态行（上游 fetch 也这样处理）
        InputStream interim = stream("HTTP/1.1 103 Early Hints\r\nLink: </x>\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        QoderAuth.Head interimHead = QoderAuth.readHead(interim);
        check(interimHead.status == 200, "the final status must be read, got " + interimHead.status);
        check("ok".equals(new String(QoderAuth.readBody(interim, interimHead, 64), "UTF-8")),
                "the interim response must not eat the body");

        // 3xx 原样交出状态码：重定向不跟随，由调用方按非 200 处理
        QoderAuth.Head redirect = QoderAuth.readHead(stream("HTTP/1.1 302 Found\r\nLocation: https://evil.invalid/\r\n\r\n"));
        check(redirect.status == 302, "a redirect must be surfaced as its status, got " + redirect.status);
    }

    /**
     * 非 framing 的重复头必须容忍（正对照）：上游已经轮换、本机却因为 Via／Set-Cookie／自定义 trace 头
     * 出现两次而丢掉新 token，是纯损失。Content-Length 重复但值相同也不算冲突。
     */
    private static void duplicatedNonFramingHeadersAreTolerated() throws Exception {
        InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nVia: 1.1 a\r\nVia: 1.1 b\r\n"
                + "Set-Cookie: x=1\r\nSet-Cookie: y=2\r\nX-Trace: t1\r\nX-Trace: t2\r\n\r\nhello");
        QoderAuth.Head head = QoderAuth.readHead(in);
        check(head.status == 200, "status: " + head.status);
        check(head.contentLength == 5, "length: " + head.contentLength);
        check("hello".equals(new String(QoderAuth.readBody(in, head, 64), "UTF-8")),
                "a duplicated non-framing header must not cost the body");

        InputStream same = stream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok");
        QoderAuth.Head sameHead = QoderAuth.readHead(same);
        check(sameHead.contentLength == 2, "two identical content-length values are not a conflict");
        check("ok".equals(new String(QoderAuth.readBody(same, sameHead, 64), "UTF-8")), "body: not read");

        InputStream sameEncoding = stream("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Encoding: gzip\r\n"
                + "Content-Length: 2\r\n\r\nxx");
        check("gzip".equals(QoderAuth.readHead(sameEncoding).encoding),
                "the same encoding twice is not a conflict");
    }

    /** chunk extension（RFC 7230 §4.1.1）必须容忍（正对照）：它不影响块边界，只忽略。 */
    private static void chunkExtensionsAreTolerated() throws Exception {
        InputStream in = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3;foo=bar\r\nabc\r\n2;x\r\nde\r\n0;last=1\r\n\r\n");
        QoderAuth.Head head = QoderAuth.readHead(in);
        check("abcde".equals(new String(QoderAuth.readBody(in, head, 64), "UTF-8")),
                "chunk extensions must be ignored, not parsed as data");

        InputStream quoted = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "2;n=\"a b\"\r\nhi\r\n0\r\n\r\n");
        QoderAuth.Head quotedHead = QoderAuth.readHead(quoted);
        check("hi".equals(new String(QoderAuth.readBody(quoted, quotedHead, 64), "UTF-8")),
                "a quoted chunk extension value must be tolerated");

        expectRefusal("a chunk extension before the size is not a size", new Frame() {
            @Override public void run() throws Exception {
                InputStream bad = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n;a=1\r\nhi\r\n");
                QoderAuth.readBody(bad, QoderAuth.readHead(bad), 64);
            }
        });
        expectRefusal("a chunk size with a non-hex tail is refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream bad = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2x\r\nhi\r\n");
                QoderAuth.readBody(bad, QoderAuth.readHead(bad), 64);
            }
        });
    }

    /**
     * 非 ASCII（obs-text，0x80-0xFF）出现在<b>不相关</b>的响应头上必须容忍（正对照）。
     *
     * <p>这些头既不决定边界也不决定解码，为它们判死就是「上游已经轮换、本机却读不出新 token」的
     * 纯损失，而 RFC 7230 §3.2.6 本来就允许头值里出现 obs-text。这里用真正的 UTF-8 字节（
     * 「会话」「追踪」一类，每个字节都落在 obs-text 区）构造假响应，而不是把它当 ASCII 塞进去。
     */
    private static void nonAsciiIrrelevantHeadersAreTolerated() throws Exception {
        // 1) Content-Length 定界 + 带中文的 Set-Cookie／自定义 trace 头：头能读，体也读得回来
        InputStream in = rawStream(utf8("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Set-Cookie: 会话=值; Path=/\r\nX-Trace-Name: 追踪标记\r\n"
                        + "Content-Length: 5\r\n\r\n"),
                "hello".getBytes(StandardCharsets.US_ASCII));
        QoderAuth.Head head = QoderAuth.readHead(in);
        check(head.status == 200, "status: " + head.status);
        check(head.contentLength == 5 && !head.chunked && head.encoding == null,
                "framing: length=" + head.contentLength + " chunked=" + head.chunked + " encoding=" + head.encoding);
        check("hello".equals(new String(QoderAuth.readBody(in, head, 64), StandardCharsets.US_ASCII)),
                "a non-ASCII non-framing header must not cost the body");

        // 2) chunked 定界 + 非 ASCII 头：块边界不受影响
        InputStream chunked = rawStream(utf8("HTTP/1.1 200 OK\r\nServer: 云网关/1.0\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"),
                utf8("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"));
        QoderAuth.Head chunkedHead = QoderAuth.readHead(chunked);
        check(chunkedHead.chunked, "the chunked flag must still be set");
        check("abcde".equals(new String(QoderAuth.readBody(chunked, chunkedHead, 64), StandardCharsets.ISO_8859_1)),
                "chunk boundaries must not shift because a header value was non-ASCII");

        // 3) 非 ASCII 出现在 trailer 行：内容丢弃，边界不受影响
        InputStream trailer = rawStream(utf8("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"),
                utf8("2\r\nhi\r\n0\r\nX-Note: 备注\r\n\r\n"));
        QoderAuth.Head trailerHead = QoderAuth.readHead(trailer);
        check("hi".equals(new String(QoderAuth.readBody(trailer, trailerHead, 64), StandardCharsets.ISO_8859_1)),
                "a non-ASCII trailer must still be drained, not parsed as body");

        // 4) 单字节 obs-text（0xE9）走同一判定
        InputStream latin = stream("HTTP/1.1 200 OK\r\nVia: 1.1 caf\u00e9\r\nContent-Length: 2\r\n\r\nok");
        QoderAuth.Head latinHead = QoderAuth.readHead(latin);
        check("ok".equals(new String(QoderAuth.readBody(latin, latinHead, 64), StandardCharsets.US_ASCII)),
                "a single obs-text byte in a header value must be tolerated");

        // 5) 1xx 过渡答复里带非 ASCII 头也不影响读最终答复
        InputStream interim = rawStream(utf8("HTTP/1.1 103 Early Hints\r\nLink: </x>\r\nX-Reason: 预热\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n"),
                "ok".getBytes(StandardCharsets.US_ASCII));
        QoderAuth.Head interimHead = QoderAuth.readHead(interim);
        check(interimHead.status == 200, "the final status must be read, got " + interimHead.status);
        check("ok".equals(new String(QoderAuth.readBody(interim, interimHead, 64), StandardCharsets.US_ASCII)),
                "the interim response must not eat the body");
    }

    /**
     * 但容忍只发生在不相关的头上：决定边界／解码的三种头一旦带非 ASCII 就必须拒绝，
     * 控制字符（NUL、DEL）在任何位置都必须拒绝，状态行也仍然保持纯 ASCII。
     *
     * <p>这不是保守，而是前提：{@code "chunked"}／十进制长度／{@code "gzip"} 都靠「值相等」判定，
     * 非 ASCII 一旦进了这些值，相等比较就不再可信，那么「这个答复读到哪里结束」也就不可信。
     */
    private static void nonAsciiNeverLoosensFraming() throws Exception {
        expectRefusal("a non-ASCII content-length must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nContent-Length: 5中文\r\n\r\nok")));
            }
        });
        expectRefusal("a non-ASCII transfer-encoding must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked中\r\n\r\n")));
            }
        });
        expectRefusal("a non-ASCII content-encoding must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nContent-Encoding: gzip中\r\n"
                        + "Content-Length: 2\r\n\r\nok")));
            }
        });
        expectRefusal("an obs-text tail on content-length must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nContent-Length: 2\u00e9\r\n\r\nok"));
            }
        });
        expectRefusal("a non-ASCII header name must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nX-名字: v\r\nContent-Length: 0\r\n\r\n")));
            }
        });
        expectRefusal("a DEL byte in a header value must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nX-A: a"), new byte[]{0x7f},
                        utf8("b\r\nContent-Length: 0\r\n\r\n")));
            }
        });
        expectRefusal("a NUL byte in a header value must be refused", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 OK\r\nX-A: a"), new byte[]{0x00},
                        utf8("b\r\nContent-Length: 0\r\n\r\n")));
            }
        });
        expectRefusal("a non-ASCII reason phrase keeps the status line strict", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(rawStream(utf8("HTTP/1.1 200 一切正常\r\nContent-Length: 0\r\n\r\n")));
            }
        });
        expectRefusal("a non-ASCII chunk size must be refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream bad = rawStream(utf8("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"),
                        utf8("2中\r\nhi\r\n0\r\n\r\n"));
                QoderAuth.readBody(bad, QoderAuth.readHead(bad), 64);
            }
        });
        expectRefusal("a non-ASCII chunk terminator filler must be refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream bad = rawStream(utf8("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"),
                        utf8("2\r\nhi\r\n\u00e9\r\n0\r\n\r\n"));
                QoderAuth.readBody(bad, QoderAuth.readHead(bad), 64);
            }
        });
    }

    private static void gzipResponsesAreDecodedAndBounded() throws Exception {
        byte[] compressed = gzip("{\"token\":\"access-new\",\"refresh_token\":\"refresh-new\"}");
        InputStream gz = stream("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
                + compressed.length + "\r\n\r\n", compressed);
        QoderAuth.Head head = QoderAuth.readHead(gz);
        check("gzip".equals(head.encoding), "the encoding must be recognised, got " + head.encoding);
        JSONObject decoded = new JSONObject(new String(QoderAuth.readBody(gz, head, 4096), "UTF-8"));
        check("access-new".equals(decoded.getString("token")), "a gzip answer must be decoded: " + decoded);

        final byte[] bomb = gzip(repeat('a', 100 * 1024));
        expectRefusal("a gzip body may not expand past the bound", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
                        + bomb.length + "\r\n\r\n", bomb);
                QoderAuth.readBody(in, QoderAuth.readHead(in), 1024);
            }
        });
        expectRefusal("an unsupported content encoding must be refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: 2\r\n\r\nxx");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
    }

    // ------------------------------------------------------------------ 真实 socket 上的链路

    /**
     * 真实环回 socket 上跑真正的 {@link QoderAuth.SingleShotLink}：写一次请求、读一次答复，
     * 答复用 chunked + gzip 编码，验证分块、解压和「只发一次」同时在真实流上成立。
     */
    private static void aRealSocketCarriesExactlyOneRequestAndOneAnswer() throws Exception {
        byte[] answer = gzip(OK_BODY);
        String framed = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + chunk(answer, 16) + "0\r\n\r\n";
        // ISO-8859-1 一个字符一个字节，gzip 的二进制不会被编码掉（US-ASCII 会把 >127 变成 '?'）
        try (Loopback server = new Loopback(framed.getBytes(StandardCharsets.ISO_8859_1), 5000)) {
            byte[] body = ("{\"refresh_token\":\"" + SECRET + "\"}").getBytes(StandardCharsets.UTF_8);
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", body.length, 5000);
            try {
                OutputStream request = link.request();
                request.write(body);
                request.flush();
                int status = link.statusLine();
                check(status == 200, "status: " + status);
                JSONObject json = new JSONObject(new String(link.body(65536), "UTF-8"));
                check("access-new".equals(json.getString("token")), "the answer must be decoded: " + json);
                check("refresh-new".equals(json.getString("refresh_token")), "the pair must survive: " + json);
            } finally {
                link.close();
            }
            check(server.await(4000), "the loopback server must have finished");
            check(server.accepts == 1, "exactly one connection may be made, got " + server.accepts);
            String seen = server.request();
            check(seen.startsWith("POST /api/v1/jobToken/refresh HTTP/1.1\r\n"),
                    "request line: " + seen.split("\r\n")[0]);
            check(seen.contains("Host: 127.0.0.1:" + server.port() + "\r\n"),
                    "host header must name the same authority: " + seen);
            check(seen.indexOf(SECRET) == seen.lastIndexOf(SECRET) && seen.contains(SECRET),
                    "the one-use token must reach the server exactly once");
            check(seen.endsWith(new String(body, StandardCharsets.UTF_8)),
                    "the body must be the JSON pair and nothing else: " + seen);
            check(!seen.contains("Authorization") && !seen.toLowerCase(java.util.Locale.ROOT).contains("cookie"),
                    "no credential may ride in a header: " + seen);
        }
    }

    private static void theRealLinkRefusesASecondRequest() throws Exception {
        try (Loopback server = new Loopback(
                ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").getBytes(StandardCharsets.US_ASCII),
                5000)) {
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", 2, 5000);
            try {
                link.request().write("{}".getBytes(StandardCharsets.UTF_8));
                try {
                    link.request();
                    throw new AssertionError("a second request must be refused: the link is single shot");
                } catch (IOException expected) {
                    check(String.valueOf(expected.getMessage()).contains("single shot"),
                            "the refusal must say why: " + expected);
                }
            } finally {
                link.close();
            }
        }
    }

    /**
     * 真实环回上的正对照：答复带重复的 Via／Set-Cookie／X-Trace 和两次相同的 Content-Length，
     * 这条请求仍然能把新的一对 token 读回来——「上游已轮换、本机却丢 token」这种纯损失不成立。
     */
    private static void aRealAnswerWithDuplicateHeadersStillRotates() throws Exception {
        String answer = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + OK_BODY.length() + "\r\nContent-Length: " + OK_BODY.length()
                + "\r\nVia: 1.1 a\r\nVia: 1.1 b\r\nSet-Cookie: s=1\r\nSet-Cookie: s=2\r\n"
                + "X-Trace: t1\r\nX-Trace: t2\r\nConnection: close\r\n\r\n" + OK_BODY;
        try (Loopback server = new Loopback(answer.getBytes(StandardCharsets.ISO_8859_1), 5000)) {
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", 2, 5000);
            try {
                OutputStream request = link.request();
                request.write("{}".getBytes(StandardCharsets.UTF_8));
                request.flush();
                check(link.statusLine() == 200, "the status must still be read");
                JSONObject json = new JSONObject(new String(link.body(65536), "UTF-8"));
                check("access-new".equals(json.getString("token")) && "refresh-new".equals(json.getString("refresh_token")),
                        "a duplicated non-framing header must not cost the new pair: " + json);
            } finally {
                link.close();
            }
            check(server.await(4000), "the loopback server must have finished");
            check(server.accepts == 1, "exactly one connection, got " + server.accepts);
        }
    }

    /**
     * 真实环回上的正对照：答复头里有非 ASCII 值（真正的 UTF-8 字节），这条请求仍能把新的一对
     * token 读回来——非 ASCII 无关头不该让「上游已经轮换」变成丢 token。
     */
    private static void aNonAsciiAnswerStillRotatesOnTheWire() throws Exception {
        byte[] answer = raw(
                utf8("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nServer: 网关\r\n"
                        + "X-Trace-Name: 追踪\r\nContent-Length: " + OK_BODY.length()
                        + "\r\nConnection: close\r\n\r\n"),
                OK_BODY.getBytes(StandardCharsets.US_ASCII));
        try (Loopback server = new Loopback(answer, 5000)) {
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", 2, 5000);
            try {
                OutputStream request = link.request();
                request.write("{}".getBytes(StandardCharsets.UTF_8));
                request.flush();
                check(link.statusLine() == 200, "the status must still be read");
                JSONObject json = new JSONObject(new String(link.body(65536), StandardCharsets.UTF_8));
                check("access-new".equals(json.getString("token"))
                                && "refresh-new".equals(json.getString("refresh_token")),
                        "a non-ASCII header value must not cost the new pair: " + json);
            } finally {
                link.close();
            }
            check(server.await(4000), "the loopback server must have finished");
            check(server.accepts == 1, "exactly one connection, got " + server.accepts);
        }
    }

    /**
     * 读预算的性质：读是<b>逐步共享同一个 deadline</b> 的，不是每次读都重新拿一整份。
     * 第一次读被 socket 超时打断后，第二次读必须立刻按「deadline 已过」失败，而不是再等一轮。
     * （写方向与 DNS 解析没有这个约束，见 {@link QoderAuth#REFRESH_TIMEOUT_MS} 的说明。）
     */
    private static void theReadDeadlineIsSharedAcrossReads() throws Exception {
        try (Loopback server = Loopback.rawStream(
                "HTTP/1.1 200 OK\r\nVia: 1.1 a\r\n".getBytes(StandardCharsets.ISO_8859_1), 5000)) {
            Socket client = new Socket("127.0.0.1", server.port());
            QoderAuth.RefreshLink link = new QoderAuth.SingleShotLink(
                    client, "127.0.0.1:" + server.port(), "/api/v1/jobToken/refresh", 2, 400);
            try {
                OutputStream request = link.request();
                request.write("{}".getBytes(StandardCharsets.UTF_8));
                request.flush();
                long start = System.nanoTime();
                try {
                    link.statusLine();
                    throw new AssertionError("a stalling server must trip the read timeout");
                } catch (IOException expected) {
                    check(expected.getMessage() != null || expected.getCause() != null,
                            "a stalled read must surface as an IO failure, got " + expected);
                }
                long first = elapsedMillis(start);
                check(first >= 300, "the first read must have waited for the budget, got " + first + "ms");
                check(first < 3000, "the first read must be bounded by the budget, got " + first + "ms");

                long secondStart = System.nanoTime();
                try {
                    link.statusLine();
                    throw new AssertionError("the deadline has passed; a second read must not wait again");
                } catch (java.net.SocketTimeoutException spend) {
                    check(String.valueOf(spend.getMessage()).contains("deadline exceeded"),
                            "the second read must report the shared deadline: " + spend.getMessage());
                }
                long second = elapsedMillis(secondStart);
                check(second < 250, "a second read must not get a fresh budget, waited " + second + "ms");
            } finally {
                link.close();
            }
        }
    }

    // ------------------------------------------------------------------ 可判定的建连（真实环回）

    private static void invalidTargetsAreRefusedBeforeAnyByte() throws Exception {
        QoderAuth.RefreshTransport transport = QoderAuth.tlsTransport();
        refusalText(transport, "http://127.0.0.1:1/api/v1/jobToken/refresh", "must be https");
        refusalText(transport, "https://user:pw@127.0.0.1:1/api/v1/jobToken/refresh", "user info");
        refusalText(transport, "https://127.0.0.1:1/api/v1/jobToken/refresh?x=1", "bare path");
        refusalText(transport, "https://127.0.0.1:1/api/v1/jobToken/refresh#frag", "bare path");
    }

    /** 真实 TLS 握手失败（对端回非 TLS 字节）：请求一个字节都没写出去，所以是「真的没发出去」。 */
    private static void aTlsHandshakeFailureIsProvenNotSent() throws Exception {
        try (Loopback server = Loopback.tlsGarbage("this is not tls\r\n".getBytes(StandardCharsets.US_ASCII))) {
            QoderAuth.RefreshSendFailure failure = realRefusal(signIn(), "https://127.0.0.1:" + server.port());
            check(failure.stage == NOT_CONNECTED,
                    "a failed TLS handshake must stay NOT_CONNECTED, got " + failure.stage);
            check(failure.definitelyNotSent() && failure.mayReplay(),
                    "nothing of the request was written, so the one-use token is still safe to retry");
            check(!failure.answered() && !failure.outcomeUnknown(), "a handshake failure is neither an answer nor unknown");
            check(failure.reason.startsWith("not_sent:"), "reason: " + failure.reason);
            check(failure.bytesWritten == 0 && failure.bytesTotal > 0,
                    "the body was prepared but nothing was written: " + failure.bytesWritten + "/" + failure.bytesTotal);
            check(failure.getMessage().startsWith("Qoder refresh failed: "), "legacy message shape: " + failure.getMessage());
            check(server.await(4000), "the loopback server must have finished");
            check(server.accepts == 1, "the transport must have tried exactly once, got " + server.accepts);
            check(!server.sawRequestLine(),
                    "no HTTP request may be written before the TLS handshake completes: " + server.request());
            check(QoderAuth.refreshDefinitelyNotSent(failure), "the fence helper must agree this was not sent");
        }
    }

    /** 明文目标：连一次都不许发生（一次性 token 绝不走明文）。 */
    private static void aPlaintextTargetNeverConnects() throws Exception {
        try (Loopback server = new Loopback(null, 400)) {
            QoderAuth.RefreshSendFailure failure = realRefusal(signIn(), "http://127.0.0.1:" + server.port());
            check(failure.stage == NOT_CONNECTED, "a plaintext target must fail before the transport, got " + failure.stage);
            check(failure.definitelyNotSent(), "nothing was sent");
            check(failure.reason.startsWith("not_sent:"), "reason: " + failure.reason);
            check(server.await(3000), "the loopback server must have finished");
            check(server.accepts == 0, "a plaintext target must never be connected, got " + server.accepts);
        }
    }

    /** 网络策略拒绝：在建连之前就失败（不需要 DNS，也不开 socket）。 */
    private static void aBlockedPolicyRefusesBeforeAnySocket() throws Exception {
        NetworkPolicy.block();
        try {
            QoderAuth.RefreshSendFailure failure = realRefusal(signIn(), BASE);
            check(failure.stage == NOT_CONNECTED, "a policy refusal is before the request, got " + failure.stage);
            check(failure.definitelyNotSent() && !failure.outcomeUnknown(),
                    "the token provably never left the process");
            check(failure.reason.startsWith("not_sent:"), "reason: " + failure.reason);
        } finally {
            NetworkPolicy.configure(new JSONObject().put("mode", "default"));
        }
    }

    // ------------------------------------------------------------------ 假传输上的阶段矩阵

    private static void aConnectFailureIsProvenNotSent() throws Exception {
        Wire wire = new Wire(Mode.OPEN_FAIL);
        QoderAuth.SignIn si = signIn();
        QoderAuth.RefreshSendFailure failure = refusal(si, wire);
        check(failure.stage == NOT_CONNECTED, "a failed open must stay NOT_CONNECTED, got " + failure.stage);
        check(failure.definitelyNotSent() && !failure.outcomeUnknown(), "a failed connect cannot have executed");
        check(failure.bytesWritten == 0 && failure.bytesTotal > 0,
                "the body was prepared but nothing was written: " + failure.bytesWritten + "/" + failure.bytesTotal);
        check(failure.reason.startsWith("not_sent:"), "reason: " + failure.reason);
        check(wire.opens == 1 && wire.link.requests == 0 && wire.link.statusReads == 0,
                "only the open attempt may happen: " + wire.opens + "/" + wire.link.requests + "/" + wire.link.statusReads);
        check(wire.lastTarget.endsWith("/api/v1/jobToken/refresh"), "job endpoint expected: " + wire.lastTarget);
        check(wire.lastContentLength > 0, "the declared length must be the real body: " + wire.lastContentLength);
        check(wire.lastTimeoutMs == QoderAuth.REFRESH_TIMEOUT_MS,
                "the read/connect budget must be the upstream 20s, got " + wire.lastTimeoutMs);
        check(QoderAuth.refreshOutcome(failure) == failure, "the throwable is already its own verdict");
    }

    private static void aConnectTimeoutIsProvenNotSent() throws Exception {
        Wire wire = new Wire(Mode.OPEN_TIMEOUT);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == NOT_CONNECTED, "a connect timeout is before the request, got " + failure.stage);
        check(failure.definitelyNotSent(), "a timed-out connect sent nothing");
        check(!failure.cancelled(), "a connect timeout is not a cancellation: " + failure.reason);
        check(failure.reason.equals("not_sent:SocketTimeoutException"), "reason: " + failure.reason);
    }

    private static void missingTokenNeverOpensALink() throws Exception {
        Wire wire = new Wire(Mode.OK);
        QoderAuth.SignIn si = signIn();
        si.refresh = "";
        QoderAuth.RefreshSendFailure failure = refusal(si, wire);
        check(failure.getMessage().equals("Qoder refresh token missing"),
                "the legacy message must not change: " + failure.getMessage());
        check(failure.reason.equals("missing_refresh_token"), "reason: " + failure.reason);
        check(failure.stage == NOT_CONNECTED && failure.definitelyNotSent(), "nothing was sent, nothing was executed");
        check(wire.opens == 0, "no link may be opened for a missing token: " + wire.opens);

        QoderAuth.SignIn device = signIn();
        device.deviceChat = true;
        device.refresh = "";
        device.deviceRefresh = "";
        Wire deviceWire = new Wire(Mode.OK);
        check(refusal(device, deviceWire).definitelyNotSent() && deviceWire.opens == 0,
                "a device-chat sign-in without any refresh token must not open a link either");
    }

    private static void oversizedBodyNeverOpensALink() throws Exception {
        QoderAuth.SignIn oversized = signIn();
        oversized.refresh = repeat('a', QoderAuth.MAX_REFRESH_BODY_BYTES + 1);
        Wire wire = new Wire(Mode.OK);
        QoderAuth.RefreshSendFailure failure = refusal(oversized, wire);
        check(failure.reason.equals("bad_request_body"), "reason: " + failure.reason);
        check(failure.stage == NOT_CONNECTED, "an oversized body must fail before the transport, got " + failure.stage);
        check(failure.definitelyNotSent() && !failure.outcomeUnknown(), "nothing was sent, nothing was spent");
        check(wire.opens == 0, "the transport must stay untouched: " + wire.opens);
        check(!failure.diagnostic().contains("aaaa"), "the body must not leak into the diagnostic");
    }

    private static void aRequestLineFailureIsUnknown() throws Exception {
        Wire wire = new Wire(Mode.REQUEST_FAIL);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == CONNECTED, "a failed request-line write must stay CONNECTED, got " + failure.stage);
        check(!failure.definitelyNotSent() && failure.outcomeUnknown(),
                "the request line may already be on the wire, so this is unknown");
        check(failure.bytesWritten == 0, "no body byte was accepted by the transport");
        check(failure.reason.startsWith("headers_may_be_queued:"), "reason: " + failure.reason);
        check(wire.link.requests == 1 && wire.link.statusReads == 0, "the status must never be read after this");
        check(wire.link.received.size() == 0, "the fixture must not have received a body");
        check(wire.link.closed, "the link must still be released");
    }

    private static void aNullRequestStreamIsUnknown() throws Exception {
        Wire wire = new Wire(Mode.NULL_REQUEST);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.reason.equals("no_request_stream"), "reason: " + failure.reason);
        check(failure.stage == CONNECTED, "a broken transport contract is not a local 'not sent', got " + failure.stage);
        check(failure.outcomeUnknown() && !failure.definitelyNotSent(), "the safe answer is unknown");
    }

    private static void aNullLinkIsNotSent() throws Exception {
        QoderAuth.RefreshSendFailure failure = null;
        try {
            QoderAuth.refreshCandidateSingleShot(signIn(), new QoderAuth.RefreshTransport() {
                @Override public QoderAuth.RefreshLink open(URI target, int contentLength, int timeoutMs) {
                    return null;
                }
            }, BASE);
        } catch (QoderAuth.RefreshSendFailure e) {
            failure = e;
        }
        check(failure != null, "a null link must be reported as a refresh failure");
        check(failure.reason.equals("no_transport"), "reason: " + failure.reason);
        check(failure.stage == NOT_CONNECTED && failure.definitelyNotSent(), "there is no link, so nothing can be sent");
    }

    private static void zeroByteWriteIsUnknown() throws Exception {
        Wire wire = new Wire(Mode.ZERO_WRITE);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == WRITING, "a broken pipe during the write is a write attempt, got " + failure.stage);
        check(failure.outcomeUnknown() && !failure.definitelyNotSent(), "a failed zero-byte write must never be replayed");
        check(failure.reason.startsWith("write_incomplete:"), "reason: " + failure.reason);
        check(wire.link.received.size() == 0 && wire.link.statusReads == 0, "nothing may reach the fixture body");
    }

    /** 部分写：传输只吃下第一个切片就断了，记账记下它收下的字节，判定仍然是「未知」。 */
    private static void partialWriteIsUnknownAndSpent() throws Exception {
        QoderAuth.SignIn si = signIn();
        si.refresh = repeat('s', 2000);
        Wire wire = new Wire(Mode.PARTIAL_WRITE);
        QoderAuth.RefreshSendFailure failure = refusal(si, wire);
        check(failure.stage == WRITING, "stage should be WRITING, got " + failure.stage);
        check(failure.outcomeUnknown() && !failure.definitelyNotSent(), "a half-written request must not be replayed");
        check(wire.link.received.size() > 0, "the transport must have taken the first slice");
        check(wire.link.received.size() < failure.bytesTotal, "the request must really be truncated");
        // 记账只数「传输明确收下的那几次 write」：写调用自己抛异常时它收了几个字节本机看不到，
        // 所以这个数字只会偏小、绝不偏大；判定只看阶段，不看字节数。
        check(failure.bytesWritten == 0,
                "the ledger only counts writes the transport returned: " + failure.bytesWritten + "/" + failure.bytesTotal);
        check(failure.bytesTotal == wire.lastContentLength, "the declared length must match the body: " + failure.bytesTotal);
        check(wire.link.statusReads == 0, "the status must not be read after a truncated write");
        check(wire.opens == 1, "the refresh itself must never retry, got " + wire.opens + " opens");
    }

    private static void aFlushFailureIsAWriteFailure() throws Exception {
        Wire wire = new Wire(Mode.FLUSH_FAIL);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == WRITING,
                "a flush that never completed is not a written request, got " + failure.stage);
        check(failure.outcomeUnknown() && !failure.definitelyNotSent(), "a failed flush must not be replayed");
        check(wire.link.received.size() == failure.bytesTotal, "the whole body reached the transport buffer");
        check(wire.link.statusReads == 0, "the status must not be read when the flush failed");
    }

    private static void aLostStatusLineIsUnknown() throws Exception {
        Wire wire = new Wire(Mode.STATUS_FAIL);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == READING_STATUS, "stage should be READING_STATUS, got " + failure.stage);
        check(!failure.answered(), "a lost status line is not an answer");
        check(failure.outcomeUnknown() && !failure.definitelyNotSent(), "a reset after the body is unknown");
        check(failure.reason.startsWith("status_unreadable:"), "reason: " + failure.reason);
        check(wire.link.received.size() == failure.bytesTotal, "the whole body went out before the reset");
    }

    /** 重定向不跟随：状态原样作为「上游答过」交出，绝不把一次性 token 再发一次到别处。 */
    private static void aRedirectIsNeverFollowed() throws Exception {
        Wire wire = new Wire(Mode.STATUS_302);
        Exception thrown = null;
        try { QoderAuth.refreshCandidateSingleShot(signIn(), wire, BASE); }
        catch (Exception e) { thrown = e; }
        check(thrown instanceof QoderAuth.QoderHttpException, "a redirect must surface as the HTTP answer, got " + thrown);
        check(((QoderAuth.QoderHttpException) thrown).status == 302, "status must be preserved");
        QoderAuth.RefreshOutcome outcome = QoderAuth.refreshOutcome(thrown);
        check(outcome != null && outcome.answered() && outcome.reason().equals("answered_302"),
                "a redirect counts as answered: " + (outcome == null ? "null" : outcome.reason()));
        check(!outcome.definitelyNotSent() && !outcome.mayReplay(), "an answered redirect must never be replayed");
        check(wire.opens == 1 && wire.link.requests == 1,
                "a redirect must never be followed: " + wire.opens + "/" + wire.link.requests);
        check(wire.link.received.toString("UTF-8").indexOf(SECRET) ==
                wire.link.received.toString("UTF-8").lastIndexOf(SECRET),
                "the one-use token must appear exactly once");
        check(wire.link.closed, "the link must be released");
    }

    /**
     * 解析答复失败时，<b>解析器的异常不能带走答复正文</b>——那正是新签发的一对 token。
     *
     * <p>两条传输都必须满足：异常自身、它的 message、diagnostic、toString，以及整条 cause 链里
     * 都不出现 token 原文；同时结论是「已答复、已花掉」（200 已经拿到），而不是「未知」或「没发出去」。
     */
    private static void jsonParseFailuresNeverCarryTheAnswer() throws Exception {
        String newToken = "NEW-ACCESS-TOKEN-do-not-print";
        String malformed = "{\"token\":\"" + newToken + "\",\"refresh_token\":";

        Wire wire = new Wire(Mode.OK);
        wire.body = malformed;
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.stage == READING_BODY, "stage: " + failure.stage);
        check(failure.answered(), "a 200 was received, so the exchange happened");
        check(!failure.definitelyNotSent() && !failure.outcomeUnknown(), "an answered exchange is spent, not unknown");
        check(failure.reason().startsWith("body_unreadable:"), "reason: " + failure.reason());
        check(failure.getMessage().startsWith("Qoder refresh failed: "), "legacy message shape: " + failure.getMessage());
        checkNoLeak(failure, newToken, "the single-shot transport");
        checkNoLeak(failure, SECRET, "the single-shot transport (one-use token)");
        // 结构上的负对照：解析器的异常本身（可能夹带原文的那种）不许出现在 cause 链里。
        // 这条在「直接用 JSONException 当 cause」的旧写法上会失败。
        checkNoParserCause(failure, "the single-shot transport");

        Wire notJson = new Wire(Mode.OK);
        notJson.body = newToken;
        QoderAuth.RefreshSendFailure plain = refusal(signIn(), notJson);
        check(plain.answered(), "a non-JSON 200 is still an answer");
        checkNoLeak(plain, newToken, "a non-JSON answer");
        check(plain.reason().startsWith("body_unreadable:"), "reason: " + plain.reason());

        // 兼容路径（注入的 HttpURLConnection 工厂）必须是同一套规则。
        Legacy legacy = new Legacy(Mode.OK, malformed);
        QoderAuth.RefreshSendFailure legacyFailure = null;
        try { QoderAuth.refreshCandidate(signIn(), legacy); }
        catch (QoderAuth.RefreshSendFailure e) { legacyFailure = e; }
        check(legacyFailure != null, "the legacy path must fail on a malformed answer");
        check(legacyFailure.answered(), "the legacy path also received a 200");
        checkNoLeak(legacyFailure, newToken, "the legacy transport");
        checkNoParserCause(legacyFailure, "the legacy transport");
        check(legacy.openings == 1, "exactly one exchange on the legacy path, got " + legacy.openings);
    }

    /** 冲突 framing 一律拒绝（负对照）：这些正是「没法安全判断边界」的情形。 */
    private static void conflictingFramingIsStillRefused() throws Exception {
        expectRefusal("two transfer-encoding headers are ambiguous", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"));
            }
        });
        expectRefusal("conflicting content-encoding is ambiguous", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Encoding: identity\r\n"
                        + "Content-Length: 2\r\n\r\nxx");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
        expectRefusal("a comma-joined content-length is not a length", new Frame() {
            @Override public void run() throws Exception {
                QoderAuth.readHead(stream("HTTP/1.1 200 OK\r\nContent-Length: 5, 5\r\n\r\n"));
            }
        });
        expectRefusal("a chunk terminator with padding is refused", new Frame() {
            @Override public void run() throws Exception {
                InputStream in = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhi x\r\n");
                QoderAuth.readBody(in, QoderAuth.readHead(in), 64);
            }
        });
    }

    private static void aRejectionStaysAQoderHttpException() throws Exception {
        Wire wire = new Wire(Mode.STATUS_401);
        Exception thrown = null;
        try { QoderAuth.refreshCandidateSingleShot(signIn(), wire, BASE); }
        catch (Exception e) { thrown = e; }
        check(thrown instanceof QoderAuth.QoderHttpException, "existing callers catch QoderHttpException, got " + thrown);
        check(!(thrown instanceof QoderAuth.RefreshSendFailure), "a rejection is an answer, not a transport stage");
        check(((QoderAuth.QoderHttpException) thrown).status == 401, "status must be preserved");
        QoderAuth.RefreshOutcome outcome = QoderAuth.refreshOutcome(thrown);
        check(outcome != null && outcome.answered(), "a rejection counts as answered");
        check(!outcome.definitelyNotSent() && !outcome.mayReplay() && !outcome.outcomeUnknown(),
                "an answered rejection is known and spent");
        check(outcome.reason().equals("answered_401"), "reason: " + outcome.reason());
        check(!(outcome instanceof Throwable), "a verdict must not be a throwable: " + outcome);
        check(wire.link.bodyCalls == 0, "a non-200 answer must not be parsed as a token pair");
    }

    private static void aTruncatedPairKeepsTheLegacyReason() throws Exception {
        Wire wire = new Wire(Mode.TRUNCATED);
        wire.body = TRUNCATED_BODY;
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.getMessage().equals("Qoder refresh failed: incomplete_token_pair"),
                "legacy token must survive: " + failure.getMessage());
        check(failure.reason.equals("incomplete_token_pair"), "reason: " + failure.reason);
        check(failure.stage == READING_BODY, "the pair is parsed after the status, got " + failure.stage);
        check(failure.answered(), "a 200 with a broken pair was still answered");
        check(!failure.outcomeUnknown() && !failure.definitelyNotSent(), "an incomplete pair may already have spent the token");
        check(!failure.getMessage().contains("leak-me-not") && !failure.diagnostic().contains("leak-me-not"),
                "the response body must not be echoed");
    }

    private static void aLostBodyAfter200IsAnsweredButSpent() throws Exception {
        Wire wire = new Wire(Mode.BODY_FAIL);
        QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
        check(failure.answered(), "a 200 was received, so the exchange definitely happened");
        check(!failure.definitelyNotSent() && !failure.mayReplay(), "the token was spent, replay is forbidden");
        check(!failure.outcomeUnknown(), "the execution is known even though the new pair was lost");
        check(failure.reason.startsWith("body_unreadable:"), "reason: " + failure.reason);
        check(failure.stage == READING_BODY, "stage: " + failure.stage);
    }

    private static void successRotatesExactlyOnceAndLeavesTheOldAccount() throws Exception {
        Wire wire = new Wire(Mode.OK);
        QoderAuth.SignIn si = signIn();
        QoderAuth.SignIn next = QoderAuth.refreshCandidateSingleShot(si, wire, BASE);

        check(next != null && next != si, "the candidate must be a new object");
        check("access-new".equals(next.access), "access: " + next.access);
        check("refresh-new".equals(next.refresh), "refresh: " + next.refresh);
        check(!next.deviceChat, "the device-chat mode must be carried over");
        check("uid-fixture".equals(next.uid) && "machine-fixture".equals(next.machineId), "identity must be carried over");
        check("old-access".equals(si.access) && SECRET.equals(si.refresh), "the old account must stay untouched");
        check(next.expiresAt > System.currentTimeMillis(), "the new expiry must be in the future: " + next.expiresAt);

        check(wire.opens == 1 && wire.link.requests == 1 && wire.link.statusReads == 1 && wire.link.bodyCalls == 1,
                "exactly one open, one request and one answer expected, got "
                        + wire.opens + "/" + wire.link.requests + "/" + wire.link.statusReads);
        check(wire.link.closed, "the link must be released");
        String sent = wire.link.received.toString("UTF-8");
        JSONObject json = new JSONObject(sent);
        check(SECRET.equals(json.getString("refresh_token")), "the transport must receive the one-use token: " + sent);
        check(json.length() == 1, "the body must be only {refresh_token}: " + sent);
        check(sent.indexOf(SECRET) == sent.lastIndexOf(SECRET), "the one-use token may appear exactly once");
        check(wire.lastTarget.equals(BASE + "/api/v1/jobToken/refresh"), "target: " + wire.lastTarget);
    }

    private static void theDevicePathKeepsItsOwnEndpointAndFields() throws Exception {
        Wire wire = new Wire(Mode.OK);
        QoderAuth.SignIn si = signIn();
        si.deviceChat = true;
        si.deviceToken = "old-device";
        si.deviceRefresh = SECRET;
        QoderAuth.SignIn next = QoderAuth.refreshCandidateSingleShot(si, wire, BASE);
        check(wire.lastTarget.endsWith("/api/v1/deviceToken/refresh"), "device endpoint expected: " + wire.lastTarget);
        check(next.deviceChat, "device chat mode must survive");
        check("access-new".equals(next.deviceToken) && "refresh-new".equals(next.deviceRefresh),
                "device credentials follow the rotation: " + next.deviceToken + "/" + next.deviceRefresh);
        check("old-access".equals(si.access) && SECRET.equals(si.refresh), "the old account must stay untouched");
        JSONObject sent = new JSONObject(wire.link.received.toString("UTF-8"));
        check(sent.length() == 1 && SECRET.equals(sent.getString("refresh_token")),
                "the CN device refresh body stays {refresh_token}: " + sent);
    }

    private static void secretsNeverReachMessagesOrDiagnostics() throws Exception {
        Mode[] modes = {Mode.OPEN_FAIL, Mode.OPEN_TIMEOUT, Mode.REQUEST_FAIL, Mode.NULL_REQUEST, Mode.ZERO_WRITE,
                Mode.PARTIAL_WRITE, Mode.FLUSH_FAIL, Mode.STATUS_FAIL, Mode.BODY_FAIL, Mode.TRUNCATED};
        for (Mode mode : modes) {
            Wire wire = new Wire(mode);
            wire.body = TRUNCATED_BODY;
            QoderAuth.RefreshSendFailure failure = refusal(signIn(), wire);
            String surface = String.valueOf(failure.getMessage()) + " | " + failure.diagnostic() + " | " + failure;
            check(!surface.contains(SECRET), mode + " leaked the token: " + surface);
            check(!surface.contains("leak-me-not"), mode + " leaked the response body: " + surface);
        }
        try {
            QoderAuth.refreshCandidateSingleShot(signIn(), new Wire(Mode.STATUS_401), BASE);
            throw new AssertionError("a 401 must fail");
        } catch (QoderAuth.QoderHttpException expected) {
            String surface = expected.getMessage() + " | " + expected;
            check(!surface.contains(SECRET), "the rejection must stay sanitized: " + surface);
        }
        Wire redirect = new Wire(Mode.STATUS_302);
        try {
            QoderAuth.refreshCandidateSingleShot(signIn(), redirect, BASE);
            throw new AssertionError("a 302 must fail");
        } catch (QoderAuth.QoderHttpException expected) {
            check(!expected.getMessage().contains(SECRET), "a redirect must stay sanitized: " + expected.getMessage());
        }
    }

    private static void unclassifiedFailuresAreConservativelyUnknown() {
        QoderAuth.RefreshOutcome unclassified = QoderAuth.refreshOutcome(new RuntimeException("fixture"));
        check(unclassified != null, "an unknown throwable must still produce a verdict");
        check(!unclassified.definitelyNotSent(), "an unclassifiable failure must never be treated as not sent");
        check(unclassified.outcomeUnknown(), "an unclassifiable failure must be treated as possibly executed");
        check(!unclassified.mayReplay(), "an unclassifiable failure must not be replayed");
        check(unclassified.reason().startsWith("unclassified:"), "reason: " + unclassified.reason());
        check(!(unclassified instanceof Throwable), "a verdict must not be a throwable: " + unclassified);

        check(QoderAuth.refreshOutcome(null) == null, "a null failure has no verdict");
        check(!QoderAuth.refreshDefinitelyNotSent(null), "a null failure is never 'not sent'");
        check(!QoderAuth.refreshDefinitelyNotSent(new IOException("fixture")), "a bare IOException stays unknown");
        check(QoderAuth.refreshDefinitelyNotSent(verdict(NOT_CONNECTED, 0, 0, false, false)),
                "the helper must read the stage verdict");
    }

    // ------------------------------------------------------------------ 兼容路径

    /**
     * 注入 {@link QoderAuth.ConnectionFactory} 的旧重载语义不变：工厂 open 失败 → NOT_CONNECTED；
     * 拿到连接对象、请求流开不起来 → CONNECTED（真实断网也在这里）。GatewayReleaseHardeningOfflineTest
     * 依赖的正是这两条判定。
     */
    private static void theLegacyFactoryPathStillBehavesAsBefore() throws Exception {
        Legacy legacy = new Legacy(Mode.OK, OK_BODY);
        QoderAuth.SignIn next = QoderAuth.refreshCandidate(signIn(), legacy);
        check("access-new".equals(next.access) && "refresh-new".equals(next.refresh), "the legacy path must still rotate");
        check(legacy.openings == 1 && legacy.fake.streams == 1 && legacy.fake.statusReads == 1,
                "the legacy path makes exactly one handoff, got "
                        + legacy.openings + "/" + legacy.fake.streams + "/" + legacy.fake.statusReads);
        check("POST".equals(legacy.fake.method), "the legacy refresh is still a POST");
        check(legacy.fake.received.toString("UTF-8").contains(SECRET), "the legacy body still carries the token");
        check(legacy.fake.disconnected, "the legacy connection is still released");

        Legacy refused = new Legacy(Mode.CONNECT_FAIL, OK_BODY);
        QoderAuth.RefreshSendFailure openFailure = null;
        try { QoderAuth.refreshCandidate(signIn(), refused); }
        catch (QoderAuth.RefreshSendFailure e) { openFailure = e; }
        check(openFailure != null && openFailure.stage == NOT_CONNECTED && openFailure.definitelyNotSent(),
                "a factory refusal still releases the fence");
        check(refused.openings == 1 && refused.fake.streams == 0, "no stream may be opened for a refused factory");

        Legacy broken = new Legacy(Mode.STREAM_FAIL, OK_BODY);
        QoderAuth.RefreshSendFailure streamFailure = null;
        try { QoderAuth.refreshCandidate(signIn(), broken); }
        catch (QoderAuth.RefreshSendFailure e) { streamFailure = e; }
        check(streamFailure != null && streamFailure.stage == CONNECTED,
                "a getOutputStream failure still stays CONNECTED, got " + (streamFailure == null ? "null" : streamFailure.stage));
        check(!streamFailure.definitelyNotSent(), "a lazy connect failure must keep the fence");
    }

    private static void aCallerFollowingTheContractStopsAfterOneHandoff() throws Exception {
        Wire wire = new Wire(Mode.PARTIAL_WRITE);
        QoderAuth.SignIn si = signIn();
        int attempts = 0;
        QoderAuth.RefreshSendFailure last = null;
        while (attempts < 3) {
            attempts++;
            try {
                QoderAuth.refreshCandidateSingleShot(si, wire, BASE);
                break;
            } catch (QoderAuth.RefreshSendFailure failure) {
                last = failure;
                if (!failure.definitelyNotSent()) break;
            }
        }
        check(attempts == 1, "the contract must stop after the first handoff, got " + attempts + " attempts");
        check(wire.link.requests == 1, "exactly one request may reach the transport, got " + wire.link.requests);
        check(last != null && last.outcomeUnknown(), "the stop must be explained as unknown");
    }

    /**
     * 负对照：复现修复前那种 catch-all 重试（任何 IOException 都再试一次），同一个一次性 token
     * 会真的被送出两次——这正是阶段判定要挡住的重复执行。
     */
    private static void legacyCatchAllRetryWouldSpendTheTokenTwice() throws Exception {
        Wire wire = new Wire(Mode.PARTIAL_WRITE);
        QoderAuth.SignIn si = signIn();
        int handoffs = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            int before = wire.link.requests;
            try { QoderAuth.refreshCandidateSingleShot(si, wire, BASE); break; }
            catch (IOException ignored) { if (wire.link.requests > before) handoffs++; }
        }
        check(handoffs == 2, "the legacy catch-all retry sends twice, got " + handoffs);
        check(wire.opens == 2, "the legacy loop opens twice, got " + wire.opens);
        check(!QoderAuth.refreshDefinitelyNotSent(verdict(WRITING, 10, 100, false, false)),
                "the verdict is what makes the legacy loop avoidable");
    }

    // ------------------------------------------------------------------ 假传输

    private enum Mode {
        /** open 就失败：链路都没拿到。 */
        OPEN_FAIL,
        /** 兼容路径的工厂 open 失败（真实断网／DNS 失败的等价位置）。 */
        CONNECT_FAIL,
        /** 兼容路径拿到了连接对象，但请求流开不起来（HttpURLConnection 在这里才建连）。 */
        STREAM_FAIL,
        /** 建连超时。 */
        OPEN_TIMEOUT,
        /** 链路建立了，但请求行写不出去（请求头可能已部分写出）。 */
        REQUEST_FAIL,
        /** 传输违约：返回了一个 null 请求流。 */
        NULL_REQUEST,
        /** 请求流开了，第一个字节就失败。 */
        ZERO_WRITE,
        /** 写出去一部分后失败。 */
        PARTIAL_WRITE,
        /** 整个请求体写进了传输，flush 失败。 */
        FLUSH_FAIL,
        /** 请求体写完，状态行读不出来。 */
        STATUS_FAIL,
        /** 上游明确拒绝。 */
        STATUS_401,
        /** 上游要求重定向（不跟随，按状态处理）。 */
        STATUS_302,
        /** 200 但响应体丢了。 */
        BODY_FAIL,
        /** 200 但只有 token，没有 refresh_token。 */
        TRUNCATED,
        /** 200 + 完整的一对 token。 */
        OK
    }

    /** 纯内存假传输：不开 socket、不解析 DNS，只把每一次交接都记下来。 */
    private static final class Wire implements QoderAuth.RefreshTransport {
        final Mode mode;
        String body = OK_BODY;
        int opens;
        String lastTarget = "";
        int lastContentLength;
        int lastTimeoutMs;
        final Fixture link;

        Wire(Mode mode) {
            this.mode = mode;
            this.link = new Fixture(this);
        }

        @Override public QoderAuth.RefreshLink open(URI target, int contentLength, int timeoutMs) throws Exception {
            opens++;
            lastTarget = String.valueOf(target);
            lastContentLength = contentLength;
            lastTimeoutMs = timeoutMs;
            if (mode == Mode.OPEN_FAIL) throw new ConnectException("fixture: no route to the fake upstream");
            if (mode == Mode.OPEN_TIMEOUT) throw new java.net.SocketTimeoutException("fixture: connect timed out");
            return link;
        }
    }

    private static final class Fixture implements QoderAuth.RefreshLink {
        private final Wire wire;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        int requests;
        int statusReads;
        int bodyCalls;
        boolean closed;

        Fixture(Wire wire) { this.wire = wire; }

        @Override public OutputStream request() throws Exception {
            requests++;
            if (wire.mode == Mode.REQUEST_FAIL)
                throw new SocketException("fixture: the request line could not be written");
            if (wire.mode == Mode.NULL_REQUEST) return null;
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (wire.mode == Mode.ZERO_WRITE)
                        throw new SocketException("fixture: broken pipe before any byte");
                    if (wire.mode == Mode.PARTIAL_WRITE) {
                        int room = 512 - received.size();
                        if (room > 0) received.write(b, off, Math.min(room, len));
                        throw new SocketException("fixture: broken pipe mid body");
                    }
                    received.write(b, off, len);
                }
                @Override public void flush() throws IOException {
                    if (wire.mode == Mode.FLUSH_FAIL)
                        throw new SocketException("fixture: flush failed after the whole body");
                }
            };
        }

        @Override public int statusLine() throws Exception {
            statusReads++;
            if (wire.mode == Mode.STATUS_FAIL)
                throw new SocketException("fixture: connection reset before the status line");
            if (wire.mode == Mode.STATUS_401) return 401;
            if (wire.mode == Mode.STATUS_302) return 302;
            return 200;
        }

        @Override public byte[] body(int maxBytes) throws Exception {
            bodyCalls++;
            if (wire.mode == Mode.BODY_FAIL) throw new IOException("fixture: body lost after 200");
            return wire.body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void close() { closed = true; }
    }

    // ------------------------------------------------------------------ 兼容路径的假连接

    /** 兼容路径的假仓库：一个纯内存的 HttpURLConnection。 */
    private static final class Legacy implements QoderAuth.ConnectionFactory {
        final Mode mode;
        final FakeQoder fake;
        int openings;
        Legacy(Mode mode, String body) throws Exception {
            this.mode = mode;
            this.fake = new FakeQoder(body);
            this.fake.streamFail = mode == Mode.STREAM_FAIL;
        }
        @Override public HttpURLConnection open(String url) throws Exception {
            openings++;
            if (mode == Mode.CONNECT_FAIL) throw new UnknownHostException("fixture: no route to the fake upstream");
            return fake;
        }
    }

    private static final class FakeQoder extends HttpURLConnection {
        final String body;
        boolean streamFail;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        int streams;
        int statusReads;
        String method;
        boolean disconnected;

        FakeQoder(String body) throws Exception {
            super(new URL("https://openapi.qoder.com.cn/api/v1/jobToken/refresh"));
            this.body = body;
        }

        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestMethod(String m) { method = m; }
        @Override public void setDoOutput(boolean doOutput) { }
        @Override public void setFixedLengthStreamingMode(int length) { }
        @Override public void setFixedLengthStreamingMode(long length) { }
        @Override public void setConnectTimeout(int timeout) { }
        @Override public void setReadTimeout(int timeout) { }
        @Override public void setRequestProperty(String key, String value) { }
        @Override public void setInstanceFollowRedirects(boolean follow) { }

        @Override public OutputStream getOutputStream() throws IOException {
            streams++;
            if (streamFail) throw new SocketException("fixture: the request stream could not be started");
            return new OutputStream() {
                @Override public void write(int b) throws IOException { received.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { received.write(b, off, len); }
                @Override public void flush() { }
            };
        }

        @Override public int getResponseCode() throws IOException {
            statusReads++;
            return 200;
        }

        @Override public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ 工具

    private static QoderAuth.SignIn signIn() {
        QoderAuth.SignIn si = new QoderAuth.SignIn();
        si.access = "old-access";
        si.refresh = SECRET;
        si.uid = "uid-fixture";
        si.email = "fixture@example.invalid";
        si.name = "fixture";
        si.machineId = "machine-fixture";
        si.expiresAt = 1L;
        return si;
    }

    private static QoderAuth.RefreshSendFailure verdict(QoderAuth.RefreshSendStage stage, long written,
                                                       long total, boolean cancelled, boolean answered) {
        return new QoderAuth.RefreshSendFailure("Qoder refresh failed: fixture", "fixture", stage,
                written, total, cancelled, answered, null);
    }

    private static QoderAuth.RefreshSendFailure refusal(QoderAuth.SignIn si, Wire wire) {
        try {
            QoderAuth.refreshCandidateSingleShot(si, wire, BASE);
        } catch (QoderAuth.RefreshSendFailure failure) {
            return failure;
        } catch (Exception unexpected) {
            throw new AssertionError("expected a RefreshSendFailure, got " + unexpected, unexpected);
        }
        throw new AssertionError("the refresh should have failed");
    }

    private static QoderAuth.RefreshSendFailure realRefusal(QoderAuth.SignIn si, String base) {
        try {
            QoderAuth.refreshCandidateSingleShot(si, QoderAuth.tlsTransport(), base);
        } catch (QoderAuth.RefreshSendFailure failure) {
            return failure;
        } catch (Exception unexpected) {
            throw new AssertionError("expected a RefreshSendFailure, got " + unexpected, unexpected);
        }
        throw new AssertionError("the refresh should have failed");
    }

    private static void refusalText(QoderAuth.RefreshTransport transport, String target, String expected) {
        try {
            transport.open(new URI(target), 8, 1000);
            throw new AssertionError("the target must be refused: " + target);
        } catch (Exception expectedFailure) {
            String message = String.valueOf(expectedFailure.getMessage());
            check(message.contains(expected), "the refusal must name the reason (" + expected + "): " + message);
        }
    }

    private interface Frame { void run() throws Exception; }

    private static void expectRefusal(String what, Frame frame) throws Exception {
        try {
            frame.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(what);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static InputStream stream(String text, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(head, 0, head.length);
        out.write(body, 0, body.length);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /** 把若干段字节拼成一个假响应流：用来构造真正的 UTF-8 非 ASCII 头。 */
    private static InputStream rawStream(byte[]... chunks) {
        return new ByteArrayInputStream(raw(chunks));
    }

    private static byte[] raw(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk, 0, chunk.length);
        return out.toByteArray();
    }

    /** 假响应里的非 ASCII 头值按 UTF-8 落成字节（真实链路就是这么发的）。 */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out);
        gz.write(text.getBytes(StandardCharsets.UTF_8));
        gz.close();
        return out.toByteArray();
    }

    private static String chunk(byte[] data, int size) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += size) {
            int n = Math.min(size, data.length - off);
            sb.append(Integer.toHexString(n)).append("\r\n");
            sb.append(new String(data, off, n, StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        return sb.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** 断言这个异常（含整条 cause 链）的任何可见文本都不含给定原文。 */
    private static void checkNoLeak(Throwable failure, String needle, String what) {
        String surface = String.valueOf(failure.getMessage()) + " | " + failure;
        check(!surface.contains(needle), what + " leaked through the message: " + surface);
        int depth = 0;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (++depth > 8) break;
            String causeSurface = cause.getClass().getName() + ": " + cause.getMessage() + " | " + cause;
            check(!causeSurface.contains(needle), what + " leaked through a cause: " + causeSurface);
        }
        if (failure instanceof QoderAuth.RefreshOutcome) {
            String diagnostic = ((QoderAuth.RefreshOutcome) failure).diagnostic();
            check(!diagnostic.contains(needle), what + " leaked through the diagnostic: " + diagnostic);
        }
    }

    /** 断言 cause 链里没有 JSON 解析器自己的异常（它可能把被解析的正文写进 message）。 */
    private static void checkNoParserCause(Throwable failure, String what) {
        int depth = 0;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (++depth > 8) break;
            boolean parser = cause instanceof com.gaeul.allin.json.JSONException
                    || cause.getClass().getName().startsWith("com.gaeul.allin.json.");
            check(!parser, what + " carried a parser exception as its cause: " + cause);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }

    /**
     * 只监听 127.0.0.1 的最小服务端：接受一个连接，读请求行+头+Content-Length 指定的体，
     * 然后答 {@code answer}（null 表示只接受连接就结束）。线程是 daemon，最多接一个连接。
     */
    private static final class Loopback implements AutoCloseable {
        private final java.net.ServerSocket socket;
        private final byte[] answer;
        private final boolean garbage;
        private final Thread thread;
        private volatile long holdMs;
        private volatile boolean closing;
        volatile int accepts;
        private volatile String request = "";
        private volatile boolean done;

        Loopback(byte[] answer, int acceptTimeoutMs) throws Exception {
            this(answer, acceptTimeoutMs, false);
        }

        /** 一个不会说 TLS 的服务端：收到握手就回一段垃圾再关掉，用来制造真实的握手失败。 */
        static Loopback tlsGarbage(byte[] garbage) throws Exception {
            return new Loopback(garbage, 4000, true);
        }

        /** 只说一半的服务端：写出 prefix 就挂着不动，用来验证读预算与「同一次交换共享 deadline」。 */
        static Loopback rawStream(byte[] prefix, long holdMs) throws Exception {
            Loopback server = new Loopback(prefix, 4000, true);
            server.holdMs = holdMs;
            return server;
        }

        private Loopback(byte[] answer, int acceptTimeoutMs, boolean garbage) throws Exception {
            this.answer = answer;
            this.garbage = garbage;
            this.socket = new java.net.ServerSocket();
            this.socket.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0));
            this.socket.setSoTimeout(acceptTimeoutMs);
            this.thread = new Thread(this::serve, "qoder-loopback");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() { return socket.getLocalPort(); }

        boolean await(long millis) throws Exception {
            thread.join(millis);
            return done;
        }

        String request() { return request; }

        boolean sawRequestLine() {
            String seen = request;
            return seen.startsWith("POST ") || seen.startsWith("GET ") || seen.contains("HTTP/1.");
        }

        private void serve() {
            try {
                Socket client = socket.accept();
                accepts++;
                client.setSoTimeout(4000);
                InputStream in = client.getInputStream();
                if (garbage) {
                    byte[] probe = new byte[512];
                    int n = in.read(probe);
                    request = n > 0 ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
                    OutputStream part = client.getOutputStream();
                    part.write(answer, 0, answer.length);
                    part.flush();
                    // holdMs > 0：写出 prefix 之后保持连接不动（不补完响应头），让客户端撞上读超时。
                    for (long waited = 0; waited < holdMs && !closing; waited += 50) Thread.sleep(50);
                    client.close();
                    return;
                }
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int matched = 0;
                int b;
                while ((b = in.read()) != -1) {
                    head.write(b);
                    matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
                    if (matched >= 4) break;
                }
                String headers = head.toString("UTF-8");
                request = headers;
                if (answer == null) return;
                int length = 0;
                for (String line : headers.split("\r\n")) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                        length = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
                byte[] body = new byte[Math.max(0, length)];
                int off = 0;
                while (off < body.length) {
                    int n = in.read(body, off, body.length - off);
                    if (n < 0) break;
                    off += n;
                }
                request = headers + new String(body, 0, off, StandardCharsets.UTF_8);
                OutputStream out = client.getOutputStream();
                out.write(answer, 0, answer.length);
                out.flush();
                client.close();
            } catch (Exception ignored) {
                // 夹具自己的 IO 失败不改变被测代码的判定
            } finally {
                done = true;
            }
        }

        @Override public void close() throws Exception {
            closing = true;
            socket.close();
            thread.join(3000);
        }
    }
}
