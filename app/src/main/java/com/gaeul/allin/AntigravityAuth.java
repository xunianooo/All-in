package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Antigravity（Google 的 agentic IDE）Google 账号登录 + Code Assist 后端。
 *
 * 对照 upstream yetone/magpie：
 *   internal/provider/google.go        —— OAuth、loadCodeAssist/onboardUser、fetchAvailableModels
 *   internal/provider/antigravity_*.go —— 模型家族 / 思考档位
 *   internal/gateway/codeassist.go     —— {model, project, request} 信封与 SSE 解码
 *
 * 关键事实：
 * 1. Antigravity 用的是 Google Code Assist（cloudcode-pa），不是 Gemini 公开 API。
 *    请求走 https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse，
 *    拿项目走 https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist。
 * 2. 登录取的是 Antigravity 自己的 OAuth client（installed app，loopback 任意端口），
 *    回调必须是 http://localhost:<port>/oauth-callback。
 * 3. 凭据 = access_token + refresh_token（Google refresh token 不过期，只需刷 access）。
 * 4. 请求要带 project、userAgent=antigravity、requestType=agent、requestId=agent-<hex>、
 *    request.sessionId = "-" + sha256(首条用户消息) 的低 63 位。
 * 5. Antigravity 对「Claude Code / Claude Agent SDK / Codex GPT-5 / Hermes 旧人设」的
 *    system prompt 会回假 429（不是额度）。上游默认在发给 Antigravity 的 system 里插入
 *    U+200B 打散这些词（antigravityCloak），否则 Claude Code 连不上。
 */
public class AntigravityAuth {

    public static final String PROVIDER = "antigravity";
    public static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    public static final String CLIENT_SECRET = "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";
    public static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json";

    /** 聊天/建模走 daily，取项目走 prod（upstream 就是这么分的） */
    public static final String REQ_BASE = "https://daily-cloudcode-pa.googleapis.com";
    public static final String LOAD_BASE = "https://cloudcode-pa.googleapis.com";

    /**
     * 实际在用的两个 base。两个域是同一个 Code Assist 后端，谁通就用谁：
     * 探测（probeAll）拿到结论后会改这里并落盘，真实请求立刻跟着走，
     * 不再是「探完就扔、该挂还是挂」。
     */
    public static volatile String chatBase = REQ_BASE;
    public static volatile String loadBase = LOAD_BASE;

    /** 两个域是同一后端，任何一侧不可达时换另一侧 */
    public static String altBase() {
        return REQ_BASE.equals(chatBase) ? LOAD_BASE : REQ_BASE;
    }

    /** 转发路径自适应：换过的 base 记住，下次直接用 */
    public static void switchChatBase(String base) {
        if (base != null && base.startsWith("https://")) chatBase = base.replaceAll("/+$", "");
    }

    /** 从配置里恢复上次探测选出的 base */
    public static void applyBases(String chat, String load) {
        if (chat != null && chat.startsWith("https://")) chatBase = chat.replaceAll("/+$", "");
        if (load != null && load.startsWith("https://")) loadBase = load.replaceAll("/+$", "");
    }
    public static final String STREAM_PATH = "/v1internal:streamGenerateContent?alt=sse";

    public static final int CALLBACK_PORT = 1456;
    public static final String CALLBACK_PATH = "/oauth-callback";

    public static final String SCOPES = "https://www.googleapis.com/auth/cloud-platform "
            + "https://www.googleapis.com/auth/userinfo.email "
            + "https://www.googleapis.com/auth/userinfo.profile "
            + "https://www.googleapis.com/auth/cclog "
            + "https://www.googleapis.com/auth/experimentsandconfigs";

    public static final String APP_VERSION = "2.9.1";
    public static final String USER_AGENT = "antigravity/hub/" + APP_VERSION + " linux/x64";

    /** Antigravity 不说有哪些模型时的兜底（upstream antigravityModels） */
    public static final String[][] DEFAULT_MODELS = new String[][]{
            {"gemini-pro-agent", "Gemini 3.1 Pro (High)"},
            {"gemini-3.1-pro-low", "Gemini 3.1 Pro (Low)"},
            {"gemini-3-flash", "Gemini 3 Flash"},
            {"gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite"},
            {"claude-opus-4-6-thinking", "Claude Opus 4.6 (Thinking)"},
            {"claude-sonnet-4-6", "Claude Sonnet 4.6"},
            {"gpt-oss-120b-medium", "GPT-OSS 120B (Medium)"}
    };

    /** Antigravity 列出来但不是聊天用的 */
    private static final String[] HIDDEN = {
            "chat_20706", "chat_23310", "tab_flash_lite_preview", "tab_jump_flash_lite_preview",
            "gemini-2.5-flash-thinking", "gemini-2.5-pro"
    };

    /** Antigravity 据以回假 429 的 system prompt 片段（小写） */
    private static final String[] TURNED_AWAY = {
            "x-anthropic-billing-header:",
            "built on anthropic's claude agent sdk",
            "you are codex, an agent based on gpt-5",
            "you are codex, a coding agent based on gpt-5",
            "hermes agent, an intelligent ai assistant created by nous research"
    };

    private static final String ZERO_WIDTH = "\u200b";

    /** 连接建立阶段的尝试次数（本机网络对 Google 的握手成功率约五成） */
    public static volatile int HANDSHAKE_TRIES = 8;

    // ---------------------------------------------------------------- 极小 HTTPS 客户端
    //
    // 为什么不用 HttpURLConnection：Android 的 OkHttp 会自己把 ALPN 重新设回
    // "h2, http/1.1"，只要对上 h2，cloudcode-pa 直接 reset（实测 h2 一律失败、
    // HTTP/1.1 + TLSv1.2 才有响应）。所以这里自己开 SSLSocket，把 ALPN 钉死
    // http/1.1，手写请求行与头。

    public static class Resp implements java.io.Closeable {
        public int status;
        public InputStream body;
        public java.net.Socket socket;
        public String statusLine = "";
        public java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        /** 出这个响应的 base，诊断用（不含凭据）。 */
        public String base = "";
        /** 拿到这个响应时请求已经写到哪一步；Resp 一旦返回，上游一定收到过请求。 */
        public SendStage stage = SendStage.NOT_CONNECTED;
        /** 注入的链路；真实 socket 时与 socket 同时存在，关闭走这里。 */
        Link link;
        /** 该请求的取消信号，响应体读取阶段也认它。 */
        Cancellation cancel;

        public String text() {
            try {
                return readAll(body);
            } catch (Exception e) {
                return "";
            }
        }

        public void close() {
            try { if (body != null) body.close(); } catch (Throwable ignored) {}
            try { if (link != null) link.close(); } catch (Throwable ignored) {}
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
            link = null;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    /** Chunked 用的行读取：把 IOException 收敛掉，让 InputStream 契约好写 */
    private static String readLineQuiet(InputStream in) {
        try {
            return readLine(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transfer-Encoding: chunked 解码器。
     *
     * 自己开 socket 就没有 HttpClient 帮忙拆 chunk：Google 的响应体是
     * "<hex长度>\r\n<数据>\r\n ... 0\r\n\r\n"，直接当 JSON 解会把 "73a"
     * 当成第一个 token，报 "Value 73a ... cannot be converted to JSONObject"。
     */
    public static class Chunked extends InputStream {
        private final InputStream in;
        private long remaining = 0;
        private boolean done = false;
        private boolean started = false;

        public Chunked(InputStream in) {
            this.in = in;
        }

        private boolean nextChunk() throws java.io.IOException {
            if (done) return false;
            if (started) {
                // 上一个 chunk 数据后面还有一个 CRLF
                readLineQuiet(in);
            }
            started = true;
            String line = readLineQuiet(in);
            if (line == null) {
                done = true;
                return false;
            }
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            line = line.trim();
            if (line.isEmpty()) {
                // 有些服务端在结尾多给一个空行
                line = readLineQuiet(in);
                if (line == null) {
                    done = true;
                    return false;
                }
                semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                line = line.trim();
            }
            long size;
            try {
                size = Long.parseLong(line, 16);
            } catch (Exception e) {
                done = true;
                return false;
            }
            if (size <= 0) {
                done = true;
                return false;
            }
            remaining = size;
            return true;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            while (remaining <= 0) {
                if (!nextChunk()) return -1;
            }
            int want = (int) Math.min(len, remaining);
            int n = in.read(b, off, want);
            if (n < 0) {
                done = true;
                return -1;
            }
            remaining -= n;
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }

    // ------------------------------------------------------------ 出站结果分类
    //
    // 手写 socket 客户端没法靠异常类型区分「一个字节都没发出去」和「写了之后连接被掐」。
    // 这两件事对上层完全不同：前者换 base / 换候选重发是安全的，后者上游可能已经执行
    // 甚至计费，重放就是重复请求。所以这里把阶段显式记下来，异常统一升级成 SendFailure。
    //
    // 判定一律保守：只要进入写阶段就按「可能已执行」处理，哪怕计数显示 0 字节 ——
    // write() 抛异常时内核缓冲区里可能已经有数据，客户端无法证明对端没收到。
    // 唯一可以证明「没执行」的情况是链路根本没建起来（NOT_CONNECTED），
    // 而 NOT_CONNECTED 之所以能当证据，是因为本文件的真实链路（SOCKET_TRANSPORT）在
    // 返回 Link 之前只发 DNS / TCP / 代理 CONNECT / TLS 握手的字节，一个请求字节都不发：
    //  - refresh_token 只在请求体里，握手阶段的 SNI、证书校验、代理 CONNECT 都带不到它；
    //  - 代理 CONNECT 里的 authority 只是目标主机名，重复一次 CONNECT 也无副作用；
    //  - 握手重试（HANDSHAKE_TRIES）只重放握手，不重放请求。
    // 所以「链路没建起来」=「token 端点没收到过这次交换」，这是可证明的，不是猜的。
    // 反过来说，write() 一旦被调用过（writeAttempted），就无法证明对端没收到。

    public enum SendStage {
        /** 链路没建起来（DNS / TCP / 代理 / TLS 握手）：一个请求字节都没写。 */
        NOT_CONNECTED,
        /** 已经开始往链路写请求；只写出去一部分也算。 */
        WRITING,
        /** 请求整体写完，正在等状态行。 */
        WRITTEN,
        /** 在读状态行 / 响应头，还没有可用的状态码。 */
        READING_STATUS,
        /** 已经拿到状态和头，在读响应体。 */
        READING_BODY,
        /** 没有传输层证据（本地参数错误、来源不明的异常）：既不能证明没发，也不能证明发了。 */
        UNKNOWN
    }

    /** 传输层失败：是 IOException，老调用点的 catch(Exception) 语义不变。 */
    public static class SendFailure extends java.io.IOException {
        public final SendStage stage;
        /** 已确认交给 socket 的请求字节数（不包含可能只进了内核缓冲的部分）。 */
        public final long bytesWritten;
        public final long bytesTotal;
        public final boolean cancelled;
        public final String base;
        public final int attempt;
        /**
         * 是否真的把请求字节交给过链路（即 OutputStream.write 被调用过）。
         *
         * 这是「没发出去」唯一可信的证据：只有从没调用过 write，才能断定请求字节
         * 一个都没进内核；一旦 write 被调用过，无论它抛出什么，都不能断言对端没收到
         * （TCP 缓冲、TLS 分片、写一半被 reset 都可能已经送到）。
         */
        public final boolean writeAttempted;

        SendFailure(SendStage stage, long bytesWritten, long bytesTotal, boolean cancelled,
                    String base, int attempt, String message, Throwable cause) {
            // 老签名的推断规则保持原样：进了写阶段就当可能已发送（保守、向后兼容）。
            this(stage, bytesWritten, bytesTotal, cancelled, base, attempt, message, cause,
                    stage != null && stage != SendStage.NOT_CONNECTED && stage != SendStage.UNKNOWN);
        }

        SendFailure(SendStage stage, long bytesWritten, long bytesTotal, boolean cancelled,
                    String base, int attempt, String message, Throwable cause, boolean writeAttempted) {
            super(message, cause);
            this.stage = stage == null ? SendStage.NOT_CONNECTED : stage;
            this.bytesWritten = bytesWritten;
            this.bytesTotal = bytesTotal;
            this.cancelled = cancelled;
            this.base = base == null ? "" : base;
            this.attempt = attempt;
            this.writeAttempted = writeAttempted;
        }

        /** 保守判定（旧语义，保留兼容）：只要进入了写阶段，就认为上游可能已经执行。 */
        public boolean handedOff() { return stage != SendStage.NOT_CONNECTED; }

        /**
         * 证据判定：只有「链路没建起来」且「从没调用过 write」才能证明请求没发出去。
         *
         * 两条都必须成立：NOT_CONNECTED 只说明建链失败；如果某个 transport 在这之前
         * 已经把字节交给了流（writeAttempted），那也已经是「可能已发送」。
         */
        public boolean provablyNotSent() {
            return stage == SendStage.NOT_CONNECTED && !writeAttempted;
        }

        /** 与之互补：不是可证明未发送，就按可能已发送处理（包括 UNKNOWN）。 */
        public boolean maybeSent() { return !provablyNotSent(); }

        /** 只有「可证明未发送且没被取消」才允许换 base / 换候选重发。 */
        public boolean mayTryAnotherBase() { return !cancelled && provablyNotSent(); }

        /** 脱敏诊断，不含 bearer / 请求体。 */
        public String diagnostic() {
            StringBuilder sb = new StringBuilder();
            if (cancelled) sb.append("Antigravity request cancelled");
            else if (provablyNotSent()) sb.append("Antigravity request was never sent");
            else sb.append("Antigravity request may already have been executed");
            sb.append(" (stage=").append(stage.name())
              .append(", bytes=").append(bytesWritten).append('/').append(bytesTotal);
            if (!base.isEmpty()) sb.append(", base=").append(hostOf(base));
            sb.append(", attempt=").append(attempt).append(')');
            String message = getMessage();
            if (message != null && !message.isEmpty()) sb.append(": ").append(message);
            return sb.toString();
        }
    }

    /** 取消信号：网关的 RequestContext 可以适配过来，测试可以自己造。 */
    public interface Cancellation {
        boolean isCancelled();
        /** 取消发生时用来打断阻塞中的读写；允许重复注册，允许立即执行。 */
        void onAbort(Runnable hook);

        Cancellation NONE = new Cancellation() {
            @Override public boolean isCancelled() { return false; }
            @Override public void onAbort(Runnable hook) { }
        };
    }

    /** 把网关的请求生命周期适配成取消信号；deadline 超时和客户端断开都算取消。 */
    public static Cancellation cancellation(final RequestContext context) {
        if (context == null) return Cancellation.NONE;
        return new Cancellation() {
            @Override public boolean isCancelled() { return context.isCancelled(); }
            @Override public void onAbort(Runnable hook) { context.onClose(hook); }
        };
    }

    /** 一条已经建好的双向字节链路。 */
    public interface Link extends java.io.Closeable {
        InputStream in() throws java.io.IOException;
        OutputStream out() throws java.io.IOException;
    }

    /**
     * 可注入的传输层。
     *
     * 契约（上层的「可证明未发送」判定完全依赖它）：
     * open() 抛出的任何异常都必须发生在请求字节写出之前 —— 建链阶段只允许有
     * DNS / TCP / 代理 CONNECT / TLS 握手的字节。真实实现（SOCKET_TRANSPORT）满足这一点：
     * 它只 connect 隧道、做 TLS 握手，请求行和请求体是在拿到 Link 之后才写的。
     * 一旦某个实现在 open() 里就写了请求字节，那它就没有「可证明未发送」这种结论。
     */
    public interface Transport {
        Link open(String url, String host, int port, int connectMs, int readMs) throws java.io.IOException;
    }

    /** 真实链路：自己开 SSLSocket 并钉死 HTTP/1.1（见文件顶部注释）。 */
    static final class SocketLink implements Link {
        final java.net.Socket socket;
        private final InputStream in;
        private final OutputStream out;

        SocketLink(java.net.Socket socket, int readMs) throws java.io.IOException {
            this.socket = socket;
            socket.setSoTimeout(readMs);
            this.in = new java.io.BufferedInputStream(socket.getInputStream(), 16384);
            this.out = socket.getOutputStream();
        }

        @Override public InputStream in() { return in; }
        @Override public OutputStream out() { return out; }
        @Override public void close() {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    public static final Transport SOCKET_TRANSPORT = new Transport() {
        @Override public Link open(String url, String host, int port, int connectMs, int readMs)
                throws java.io.IOException {
            // 一个逻辑请求和它的握手重试共用同一份网络策略快照。
            NetworkPolicy.Snapshot policy = NetworkPolicy.capture();
            java.net.Socket plain = null;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= HANDSHAKE_TRIES; attempt++) {
                try {
                    plain = policy.connectTunnel(host, port, connectMs, readMs);
                    // 关键：不设 ALPN。Conscrypt 默认不送 ALPN，服务端按 HTTP/1.1 处理；
                    // 一旦协商到 h2，cloudcode-pa 会直接 reset（实测）。
                    javax.net.ssl.SSLSocketFactory sf =
                            javax.net.ssl.SSLContext.getDefault().getSocketFactory();
                    java.net.Socket s = sf.createSocket(plain, host, port, true);
                    enableHostVerification((javax.net.ssl.SSLSocket) s);
                    ((javax.net.ssl.SSLSocket) s).startHandshake();
                    return new SocketLink(s, readMs);
                } catch (Exception he) {
                    lastErr = he;
                    try { if (plain != null) plain.close(); } catch (Throwable ignored) { }
                    plain = null;
                    if (attempt < HANDSHAKE_TRIES) {
                        try {
                            Thread.sleep(200L * attempt);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            if (lastErr instanceof java.io.IOException) throw (java.io.IOException) lastErr;
            throw new java.io.IOException(String.valueOf(lastErr));
        }
    };

    /** 一次发送走到了哪一步的记账本，异常统一由它转成 SendFailure。 */
    static final class Trace {
        SendStage stage = SendStage.NOT_CONNECTED;
        long written;
        long total;
        String base = "";
        int attempt = 1;
        /** 请求字节是否真的交给过流（write() 被调用过）；区分「可证明未发送」的唯一依据。 */
        boolean writeAttempted;

        void write(OutputStream os, byte[] data, Cancellation cancel) throws java.io.IOException {
            if (data == null || data.length == 0) return;
            int offset = 0;
            while (offset < data.length) {
                if (cancel.isCancelled()) throw new java.io.IOException("request cancelled while writing");
                int length = Math.min(65536, data.length - offset);
                // 先记账再写：write() 一旦被调用，就没法再证明对端没收到。
                writeAttempted = true;
                os.write(data, offset, length);
                offset += length;
                // 只有 write() 正常返回的字节才算「已交给 socket」；半截的那段记成未知，
                // 由 handedOff() 的保守规则覆盖。
                written += length;
            }
        }

        SendFailure failure(Exception cause, boolean cancelled) {
            if (cause instanceof SendFailure) return (SendFailure) cause;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return new SendFailure(stage, written, total, cancelled, base, attempt, message, cause,
                    writeAttempted);
        }
    }

    /** 响应体读取阶段的取消包装：取消后立刻失败，并且明确标记 handedOff。 */
    static final class CancelBody extends java.io.FilterInputStream {
        private final Cancellation cancel;
        private final Trace trace;

        CancelBody(InputStream in, Cancellation cancel, Trace trace) {
            super(in);
            this.cancel = cancel;
            this.trace = trace;
        }

        private void check() throws java.io.IOException {
            if (cancel.isCancelled()) {
                trace.stage = SendStage.READING_BODY;
                throw trace.failure(new java.io.IOException("request cancelled while reading response"), true);
            }
        }

        @Override public int read() throws java.io.IOException {
            check();
            return super.read();
        }

        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            check();
            return super.read(b, off, len);
        }
    }

    /** 发出一个 HTTPS 请求，返回状态与（尚未读完的）响应流 */
    public static Resp request(String urlStr, String method, String bearer, String accept,
                               String contentType, byte[] body, int connectMs, int readMs) throws Exception {
        return request(urlStr, method, bearer, accept, contentType, body, connectMs, readMs,
                SOCKET_TRANSPORT, Cancellation.NONE, 1);
    }

    /**
     * 可注入 transport、可取消的版本。
     *
     * 失败一律以 SendFailure 抛出，调用方据 handedOff() 决定能不能换端点/换候选重发；
     * 被打断（客户端断开、deadline）也走 SendFailure 并带 cancelled=true。
     */
    public static Resp request(String urlStr, String method, String bearer, String accept,
                               String contentType, byte[] body, int connectMs, int readMs,
                               Transport transport, Cancellation cancellation, int attempt) throws Exception {
        java.net.URL u = new java.net.URL(urlStr);
        if (!"https".equalsIgnoreCase(u.getProtocol())) throw new java.io.IOException("HTTPS required");
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        String path = u.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (u.getQuery() != null) path = path + "?" + u.getQuery();

        StringBuilder h = new StringBuilder(512);
        h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        h.append("Host: ").append(host).append("\r\n");
        h.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (accept != null) h.append("Accept: ").append(accept).append("\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        if (bearer != null && !bearer.isEmpty()) h.append("Authorization: Bearer ").append(bearer).append("\r\n");
        if (body != null) h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        byte[] headBytes = h.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body == null ? new byte[0] : body;

        final Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        final Trace trace = new Trace();
        trace.base = urlStr;
        trace.attempt = attempt;
        trace.total = (long) headBytes.length + bodyBytes.length;

        Link link = null;
        try {
            if (cancel.isCancelled()) throw new java.io.IOException("request cancelled before connect");
            link = transport.open(urlStr, host, port, connectMs, readMs);
            if (link == null) throw new java.io.IOException("transport returned no connection");
            final Link opened = link;
            cancel.onAbort(new Runnable() {
                @Override public void run() {
                    try { opened.close(); } catch (Throwable ignored) { }
                }
            });
            OutputStream os = link.out();
            if (os == null) throw new java.io.IOException("transport returned no output stream");
            if (cancel.isCancelled()) throw new java.io.IOException("request cancelled before write");
            trace.stage = SendStage.WRITING;
            trace.write(os, headBytes, cancel);
            trace.write(os, bodyBytes, cancel);
            os.flush();
            trace.stage = SendStage.WRITTEN;

            InputStream in = link.in();
            if (in == null) throw new java.io.IOException("transport returned no input stream");
            // 还没读到状态行之前都算 WRITTEN：请求已经整段写出去，结果未知。
            String line = readLine(in);
            if (line == null) throw new java.io.IOException("connection closed before the status line");
            Resp r = new Resp();
            r.base = urlStr;
            r.statusLine = line;
            String[] parts = line.split(" ");
            if (parts.length >= 2) {
                try {
                    r.status = Integer.parseInt(parts[1]);
                } catch (Exception ignored) {
                }
            }
            if (r.status == 0) throw new java.io.IOException("unreadable status line: " + line);
            trace.stage = SendStage.READING_STATUS;
            while ((line = readLine(in)) != null) {
                if (line.isEmpty()) break;
                if (cancel.isCancelled()) throw new java.io.IOException("request cancelled while reading headers");
                int c = line.indexOf(':');
                if (c > 0) {
                    r.headers.put(line.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT),
                            line.substring(c + 1).trim());
                }
            }
            String te = r.headers.get("transfer-encoding");
            boolean chunked = te != null && te.toLowerCase(java.util.Locale.ROOT).contains("chunked");
            String ce = r.headers.get("content-encoding");
            boolean gzip = ce != null && ce.toLowerCase(java.util.Locale.ROOT).contains("gzip");

            InputStream decoded = in;
            if (chunked) decoded = new Chunked(decoded);
            if (gzip) {
                try {
                    decoded = new java.util.zip.GZIPInputStream(decoded);
                } catch (Throwable ignored) {
                }
            }
            trace.stage = SendStage.READING_BODY;
            r.stage = SendStage.READING_BODY;
            r.body = cancel == Cancellation.NONE ? decoded : new CancelBody(decoded, cancel, trace);
            r.link = link;
            r.cancel = cancel;
            if (link instanceof SocketLink) r.socket = ((SocketLink) link).socket;
            link = null; // 关闭责任交给 Resp
            return r;
        } catch (Exception e) {
            if (link != null) {
                try { link.close(); } catch (Throwable ignored) { }
            }
            throw trace.failure(e, cancel.isCancelled());
        }
    }

    // ------------------------------------------------------- 刷新 token 交换的结果分类
    //
    // 刷新交换和聊天请求一样是「可能产生上游副作用的写操作」：Google 的 refresh_token 可能
    // 轮换，一旦轮换被消费而本地没存住，重放旧值就是重放废凭据。所以这里也不再靠异常类型
    // 判断，而是拿同一份 Trace 记账得出两种互斥结论：可证明未发送 / 可能已发送。

    /**
     * 一次 refresh_token 交换的结果。
     *
     * 两种结论互斥，没有中间地带：
     *  - answered()：读到了状态行 —— 上游一定收到过这次交换（哪怕随后读响应体时断掉）；
     *  - provablyNotSent()：链路没建起来，且从没调用过 write —— 请求字节一个都没进内核；
     *  - 其余一律 maybeSent()：写完等响应被 reset、读状态行超时、写了一半 broken pipe、
     *    来源不明的异常，全部按「可能已执行」处理。异常叫 IOException 不等于没发出去。
     */
    public static final class Exchange {
        public final int status;
        public final JSONObject body;
        /** 非空 = 这次交换没拿到响应，结论由它的传输层证据决定。 */
        public final SendFailure failure;

        Exchange(int status, JSONObject body, SendFailure failure) {
            this.status = status;
            this.body = body == null ? new JSONObject() : body;
            this.failure = failure;
        }

        static Exchange answered(int status, JSONObject body) { return new Exchange(status, body, null); }
        static Exchange failed(SendFailure failure) { return new Exchange(0, new JSONObject(), failure); }

        /** 没有传输层证据的失败：按「可能已发送」处理，绝不猜未发送。 */
        static Exchange unclassified(Exception cause) {
            String message;
            if (cause == null) message = "refresh exchange failed";
            else if (cause.getMessage() == null) message = cause.getClass().getSimpleName();
            else message = cause.getMessage();
            return new Exchange(0, new JSONObject(),
                    new SendFailure(SendStage.UNKNOWN, 0, 0, false, TOKEN_URL, 1, message, cause, false));
        }

        public boolean answered() { return failure == null; }

        public boolean provablyNotSent() { return failure != null && failure.provablyNotSent(); }

        /** 与之互补：只要不能证明没发出去，就按可能已发送处理。 */
        public boolean maybeSent() { return !provablyNotSent(); }

        /** 脱敏诊断，不含 refresh token / client_secret。 */
        public String diagnostic() {
            if (answered()) {
                return "Antigravity token endpoint answered HTTP " + status + " (" + hostOf(TOKEN_URL) + ")";
            }
            return failure.diagnostic();
        }
    }

    /**
     * 一次 refresh_token 交换。唯一的出站地址是 TOKEN_URL，没有第二个 base，所以这里
     * 不做端点轮换：一次逻辑交换只建一次链路、最多写一次请求，结论全部来自 Trace 记账。
     *
     * @param transport 可注入的传输层；null = 真实 SSLSocket 通路
     * @param cancel    可注入的取消信号；null = 不取消
     */
    static Exchange exchangeRefresh(String refresh, Transport transport, Cancellation cancel) {
        Transport wire = transport == null ? SOCKET_TRANSPORT : transport;
        Cancellation stop = cancel == null ? Cancellation.NONE : cancel;
        try {
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + enc(refresh)
                    + "&client_id=" + enc(CLIENT_ID)
                    + "&client_secret=" + enc(CLIENT_SECRET);
            Resp r = request(TOKEN_URL, "POST", null, "application/json",
                    "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8),
                    20000, 60000, wire, stop, 1);
            try {
                int status = r.status;
                String text;
                try {
                    // 不走 Resp.text()：它把读失败吞成空串，会把「读到一半断了」伪装成「响应为空」。
                    text = readAll(r.body);
                } catch (Exception readFailure) {
                    // 状态行都读到了，请求肯定发出去过；这里只能报「已发送、响应不可用」。
                    SendFailure known = readFailure instanceof SendFailure
                            ? (SendFailure) readFailure
                            : new SendFailure(SendStage.READING_BODY, 0, 0, stop.isCancelled(),
                                    TOKEN_URL, 1, "token response could not be read", readFailure, true);
                    return Exchange.failed(known);
                }
                JSONObject parsed;
                try { parsed = new JSONObject(text); } catch (Exception notJson) { parsed = new JSONObject(); }
                return Exchange.answered(status, parsed);
            } finally {
                r.close();
            }
        } catch (SendFailure failure) {
            return Exchange.failed(failure);
        } catch (Exception noEvidence) {
            // 没有任何传输层记账的失败（构造表单、URL 之类）：不猜「没发」，按可能已发送处理。
            return Exchange.unclassified(noEvidence);
        }
    }

    /**
     * 网关集成判定：这次刷新失败是否「可证明没发出去」，因此可以安全重试 / 释放刷新围栏。
     *
     * 只认本文件产出的证据（TypedAuthException / SendFailure）。其他任何异常都返回 false，
     * 也就是按「可能已发送」处理 —— 不允许外来异常冒充「未发送」。
     */
    public static boolean provablyNotSent(Throwable failure) {
        return provablyNotSent(failure, 0);
    }

    private static boolean provablyNotSent(Throwable failure, int depth) {
        if (failure == null || depth > 8) return false;
        if (failure instanceof TypedAuthException) return ((TypedAuthException) failure).provablyNotSent;
        if (failure instanceof SendFailure) return ((SendFailure) failure).provablyNotSent();
        Throwable cause = failure.getCause();
        return cause != null && cause != failure && provablyNotSent(cause, depth + 1);
    }

    /** 网关错误体 / 日志用的脱敏诊断；不含 refresh token、bearer、请求体。 */
    public static String sendDiagnostic(Throwable failure) {
        if (failure == null) return "";
        if (failure instanceof TypedAuthException) return ((TypedAuthException) failure).diagnostic();
        if (failure instanceof SendFailure) return ((SendFailure) failure).diagnostic();
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /** 一次「按候选 base 依次尝试、但绝不重放已写出请求」的结果。 */
    public static final class Dispatch {
        public Resp resp;
        public SendFailure failure;
        public String usedBase = "";
        /** 请求字节真正交给链路的次数：这就是会不会重复执行的唯一度量。 */
        public int sendCount;
        /** 尝试建链路的次数（含没写出任何字节的失败）。 */
        public int openCount;
        public boolean cancelled;

        public boolean ok() { return resp != null; }

        /** 是否可能已经被上游执行过（诊断/账本用）。 */
        public boolean handedOff() { return resp != null || (failure != null && failure.handedOff()); }

        /** 脱敏诊断文本，可直接进错误体或日志。 */
        public String diagnostic() {
            if (resp != null) return "Antigravity upstream answered from " + hostOf(usedBase)
                    + " after " + openCount + " attempt(s)";
            if (failure == null) {
                return cancelled ? "Antigravity 请求在发出前已取消，没有触达任何端点" : "Antigravity request was not sent";
            }
            StringBuilder sb = new StringBuilder(failure.diagnostic());
            if (failure.cancelled) {
                sb.append("; 请求已取消，没有重发");
            } else if (failure.handedOff()) {
                sb.append("; 请求可能已被上游执行，为避免重复执行没有换端点重发");
            } else {
                sb.append("; 未写出任何请求字节，换端点重发是安全的");
            }
            return sb.toString();
        }
    }

    /**
     * 依次尝试 base（同一条逻辑请求的备用出站端点）。
     *
     * 规则：
     *  - 只有「链路没建起来」（NOT_CONNECTED）才换下一个 base；
     *  - 一旦写出过任何字节，无论异常看起来多像网络抖动，都停在这里，结果是「执行未知」；
     *  - 取消后不再尝试任何 base。
     */
    public static Dispatch dispatch(String[] bases, String pathSuffix, String bearer, byte[] body,
                                    int connectMs, int readMs, Transport transport, Cancellation cancel) {
        Dispatch outcome = new Dispatch();
        if (bases == null || bases.length == 0) return outcome;
        final Cancellation cancellation = cancel == null ? Cancellation.NONE : cancel;
        for (int i = 0; i < bases.length; i++) {
            if (cancellation.isCancelled()) {
                outcome.cancelled = true;
                break;
            }
            String base = bases[i];
            outcome.openCount++;
            try {
                String url = base.replaceAll("/+$", "") + (pathSuffix == null ? "" : pathSuffix);
                outcome.resp = request(url, "POST", bearer, "text/event-stream", "application/json",
                        body, connectMs, readMs, transport, cancellation, outcome.openCount);
                outcome.usedBase = base;
                outcome.sendCount++;
                return outcome;
            } catch (SendFailure failure) {
                outcome.failure = failure;
                if (failure.handedOff()) outcome.sendCount++;
                if (failure.cancelled) {
                    outcome.cancelled = true;
                    break;
                }
                if (!failure.mayTryAnotherBase()) break;
            } catch (Exception other) {
                // 连 URL 都建不出来这类本地错误：一个字节都没写，可以换下一个 base。
                outcome.failure = new SendFailure(SendStage.NOT_CONNECTED, 0, 0, cancellation.isCancelled(),
                        base, outcome.openCount, other.getMessage(), other);
                if (outcome.failure.cancelled) {
                    outcome.cancelled = true;
                    break;
                }
            }
        }
        return outcome;
    }

    /** 只取 host，避免诊断里带出完整 URL / 查询串。 */
    static String hostOf(String url) {
        if (url == null) return "";
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            int scheme = url.indexOf("://");
            String rest = scheme < 0 ? url : url.substring(scheme + 3);
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }
    }

    private static void enableHostVerification(javax.net.ssl.SSLSocket socket) {
        javax.net.ssl.SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
    }

    /** 诊断：同一台机上 TLS 协议 / ALPN 组合到底哪个能握手（只用于 /api/antigravity/tlslab） */
    public static JSONObject tlsLab(String host, boolean tls12, String alpn, int connectMs) {
        JSONObject o = new JSONObject();
        java.net.Socket plain = null;
        java.net.Socket tls = null;
        try {
            plain = NetworkPolicy.capture().connectTunnel(host, 443, connectMs, connectMs);
            javax.net.ssl.SSLSocketFactory f =
                    javax.net.ssl.SSLContext.getDefault().getSocketFactory();
            tls = f.createSocket(plain, host, 443, true);
            javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) tls;
            enableHostVerification(ss);
            if (tls12) ss.setEnabledProtocols(new String[]{"TLSv1.2"});
            if (alpn != null) {
                java.lang.reflect.Method m = ss.getClass().getMethod("setApplicationProtocols", String[].class);
                m.invoke(ss, (Object) alpn.split(","));
            }
            long t0 = System.currentTimeMillis();
            ss.startHandshake();
            o.put("ok", true);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("protocol", ss.getSession().getProtocol());
            try {
                java.lang.reflect.Method g = ss.getClass().getMethod("getApplicationProtocol");
                o.put("alpn", String.valueOf(g.invoke(ss)));
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
            o.put("ok", false);
            o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { if (tls != null) tls.close(); } catch (Throwable ignored) {}
            try { if (plain != null) plain.close(); } catch (Throwable ignored) {}
        }
        return o;
    }

    // ---------------------------------------------------------------- 连接抖动测量
    //
    // 用来量化「TLS 握手被随机 reset」的比例：同一个域名连打 N 次，统计成功/失败，
    // 并记录每次耗时。有这个数据才能决定重试次数该给多少。

    public static JSONObject flap(String host, int n) {
        return flap(host, n, "direct");
    }

    /** mode: direct / ipv4 / ipv6 — 用来判断是不是 IPv6 路由不通 */
    public static JSONObject flap(String host, int n, String mode) {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        // Proxy mode cannot truthfully measure target IP families: the proxy resolves the target.
        NetworkPolicy.Snapshot policy = NetworkPolicy.capture();
        int ok = 0;
        long totalMs = 0;
        java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            JSONObject one = new JSONObject();
            java.net.Socket plain = null;
            java.net.Socket tls = null;
            java.net.InetAddress addr = null;
            long t0 = System.currentTimeMillis();
            try {
                if ("ipv4".equals(mode) || "ipv6".equals(mode)) {
                    if (!policy.isDirectFor(host, 443)) throw new java.io.IOException("address-family diagnostic unavailable through proxy");
                    java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
                    for (java.net.InetAddress a : all) {
                        boolean v4 = a instanceof java.net.Inet4Address;
                        if ("ipv4".equals(mode) != v4) continue;
                        addr = a;
                        break;
                    }
                    if (addr == null) throw new java.net.UnknownHostException("没有匹配 " + mode + " 的地址");
                    // Selection was DIRECT for the original name; connect to the chosen IP directly.
                    plain = new java.net.Socket(java.net.Proxy.NO_PROXY);
                    plain.connect(new java.net.InetSocketAddress(addr, 443), 6000);
                    plain.setSoTimeout(6000);
                } else {
                    plain = policy.connectTunnel(host, 443, 6000, 6000);
                }
                javax.net.ssl.SSLSocketFactory sf =
                        javax.net.ssl.SSLContext.getDefault().getSocketFactory();
                tls = sf.createSocket(plain, host, 443, true);
                enableHostVerification((javax.net.ssl.SSLSocket) tls);
                ((javax.net.ssl.SSLSocket) tls).startHandshake();
                long ms = System.currentTimeMillis() - t0;
                one.put("i", i + 1);
                one.put("ok", true);
                one.put("ms", ms);
                one.put("ip", addr == null ? "" : addr.getHostAddress());
                ok++;
                totalMs += ms;
            } catch (Throwable e) {
                String r = classify(e);
                one.put("i", i + 1);
                one.put("ok", false);
                one.put("reason", r);
                one.put("ip", addr == null ? "" : addr.getHostAddress());
                one.put("ms", System.currentTimeMillis() - t0);
                Integer c = reasons.get(r);
                reasons.put(r, c == null ? 1 : c + 1);
            } finally {
                try { if (tls != null) tls.close(); } catch (Throwable ignored) {}
                try { if (plain != null) plain.close(); } catch (Throwable ignored) {}
            }
            arr.put(one);
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            out.put("host", host);
            out.put("mode", mode);
            try {
                JSONArray ips = new JSONArray();
                for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                    ips.put(a.getHostAddress());
                }
                out.put("resolved", ips);
            } catch (Exception ignored) {
            }
            out.put("total", n);
            out.put("okCount", ok);
            out.put("failCount", n - ok);
            out.put("rate", n == 0 ? 0 : Math.round(ok * 1000.0 / n) / 10.0);
            out.put("avgMs", ok == 0 ? -1 : totalMs / ok);
            JSONObject rs = new JSONObject();
            for (java.util.Map.Entry<String, Integer> e : reasons.entrySet()) rs.put(e.getKey(), e.getValue());
            out.put("reasons", rs);
            out.put("attempts", arr);
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- 逐接口诊断
    //
    // probe 只证明「TLS 通了、接口认 401」。带凭据的真实调用失败时必须逐个接口打，
    // 才能分清是路径/请求体不对，还是带 Authorization 后被 reset。

    public static JSONObject diag(String base, String access, String project) {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            out.put("base", base);
            out.put("project", project == null ? "" : project);
            out.put("hasToken", access != null && !access.isEmpty());

            // 1) loadCodeAssist 不带凭据：基线
            arr.put(step("1 loadCodeAssist(no auth)", base + "/v1internal:loadCodeAssist",
                    null, "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}", USER_AGENT));
            // 2) loadCodeAssist 带凭据
            arr.put(step("2 loadCodeAssist(auth)", base + "/v1internal:loadCodeAssist",
                    access, "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}", USER_AGENT));
            // 3) fetchAvailableModels
            JSONObject fa = new JSONObject();
            try {
                fa.put("project", project == null ? "" : project);
                JSONObject ent = new JSONObject();
                ent.put("userTier", "g1-pro-tier");
                fa.put("entitlement", ent);
            } catch (Exception ignored) {
            }
            arr.put(step("3 fetchAvailableModels(auth)", base + "/v1internal:fetchAvailableModels",
                    access, fa.toString(), USER_AGENT));
            // 4) streamGenerateContent：最小合法信封
            JSONObject env = new JSONObject();
            try {
                env.put("model", "gemini-3-flash");
                env.put("project", project == null ? "" : project);
                env.put("userAgent", "antigravity");
                env.put("requestType", "agent");
                env.put("requestId", "agent-" + randomToken(12));
                JSONObject req = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject turn = new JSONObject();
                turn.put("role", "user");
                JSONArray parts = new JSONArray();
                JSONObject tp = new JSONObject();
                tp.put("text", "hi");
                parts.put(tp);
                turn.put("parts", parts);
                contents.put(turn);
                req.put("contents", contents);
                req.put("sessionId", "-1234567");
                env.put("request", req);
            } catch (Exception ignored) {
            }
            arr.put(step("4 streamGenerateContent(auth)", base + STREAM_PATH,
                    access, env.toString(), USER_AGENT));
            // 5) 统计模型列表接口（不带 entitlement）
            JSONObject fa2 = new JSONObject();
            try {
                fa2.put("project", project == null ? "" : project);
            } catch (Exception ignored) {
            }
            arr.put(step("5 fetchAvailableModels(no tier)", base + "/v1internal:fetchAvailableModels",
                    access, fa2.toString(), USER_AGENT));

            out.put("ok", true);
            out.put("results", arr);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
                out.put("results", arr);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject step(String name, String url, String bearer, String body, String ua) {
        JSONObject o = new JSONObject();
        Resp r = null;
        try {
            o.put("name", name);
            o.put("url", url);
            long t0 = System.currentTimeMillis();
            r = request(url, "POST", bearer, "application/json", "application/json",
                    body.getBytes(StandardCharsets.UTF_8), 12000, 30000);
            o.put("status", r.status);
            o.put("ms", System.currentTimeMillis() - t0);
            String t = r.text();
            String pv = t.replace('\n', ' ').replace('\r', ' ').trim();
            if (pv.length() > 260) pv = pv.substring(0, 260) + "…";
            o.put("body", pv);
            o.put("ok", r.status >= 200 && r.status < 400);
        } catch (Throwable e) {
            try {
                o.put("ok", false);
                o.put("error", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            if (r != null) r.close();
        }
        return o;
    }

    // ---------------------------------------------------------------- 端点探测
    //
    // 旧实现有三个硬伤（实测暴露）：
    //   1. 用 GET / 探，拿 404 就算 ✓ —— 那只证明 TLS 通了，证明不了 API 能用；
    //   2. 没有重试，这台机对 Google 的连接本来就时通时断，单次抖动就被判成「端点挂了」，
    //      于是同一域名会出现「base ✗ / 子路径 ✓」这种自相矛盾的结果；
    //   3. 探完结论不落地，真实请求照旧打默认 base，等于白探。
    // 现在改成：并发 + 每项重试 3 次 + 打真实 POST 业务端点（无凭据期望 401）+ 结论自动生效。

    /** 一个候选 base 的探测结果 */
    public static class Probe {
        public String role;      // chat / load
        public String name;      // daily-cloudcode-pa
        public String url;
        public boolean alive;
        public int status;
        public long ms = -1;
        public int tries;
        public String reason = "";
        public String tlsError = "";
        public String note = "";
        public String preview = "";
    }

    private static String classify(Throwable e) {
        String n = e.getClass().getSimpleName();
        String m = e.getMessage() == null ? "" : e.getMessage();
        String all = (n + " " + m).toLowerCase(java.util.Locale.ROOT);
        if (all.contains("unknownhost")) return "DNS 解析失败";
        if (all.contains("sockettimeout") || all.contains("timed out") || all.contains("timeout")) return "响应超时";
        if (all.contains("sslhandshake") || all.contains("connection closed")
                || all.contains("reset") || all.contains("eof")) return "TLS 握手被重置";
        if (all.contains("connectexception") || all.contains("econnrefused")) return "端口不可达";
        if (all.contains("certificate") || all.contains("cert")) return "证书校验失败";
        return "连接失败";
    }

    /** 探一个 base：先 TLS 握手，再打真实业务端点（POST loadCodeAssist，无凭据，期望 401/403/400） */
    public static Probe probeOne(String role, String url) {
        Probe p = new Probe();
        p.role = role;
        p.url = url;
        p.name = url.replace("https://", "").replaceAll("/+$", "");

        // TLS 层：单独握一次手，失败时能区分「网络不通」和「接口不认」
        JSONObject hs = tlsLab(p.name, false, null, 9000);
        if (!hs.optBoolean("ok", false)) {
            String raw = hs.optString("error", "").toLowerCase(java.util.Locale.ROOT);
            if (raw.contains("unknownhost")) p.tlsError = "DNS 解析失败";
            else if (raw.contains("timeout") || raw.contains("timed out")) p.tlsError = "响应超时";
            else if (raw.contains("sslhandshake") || raw.contains("connection closed")) p.tlsError = "TLS 握手被重置";
            else p.tlsError = "TLS 握手失败";
        }

        // 业务层：重试 3 次，任何一次拿到 HTTP 响应就算端点活着
        for (int i = 1; i <= 3; i++) {
            p.tries = i;
            long t0 = System.currentTimeMillis();
            Resp r = null;
            try {
                r = request(url + "/v1internal:loadCodeAssist", "POST", null,
                        "application/json", "application/json",
                        "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}".getBytes(StandardCharsets.UTF_8),
                        9000, 12000);
                p.status = r.status;
                p.ms = System.currentTimeMillis() - t0;
                try {
                    String t = r.text();
                    String pv = t.replace('\n', ' ').replace('\r', ' ').trim();
                    if (pv.length() > 90) pv = pv.substring(0, 90) + "…";
                    p.preview = pv;
                } catch (Throwable ignored) {
                }
                // 401/403 = 服务在跑、只是没带凭据；400 = 请求形态不合但它认了这个路由
                p.alive = (p.status == 401 || p.status == 403 || p.status == 400
                        || p.status == 404 || p.status == 405);
                if (p.alive) {
                    p.reason = "";
                    p.note = p.status == 401 || p.status == 403
                            ? "端点正常（" + p.status + " = 服务在跑，只是这次没带凭据）"
                            : "端点正常（" + p.status + "）";
                    p.tlsError = "";
                    return p;
                }
                p.reason = "HTTP " + p.status;
            } catch (Throwable e) {
                p.reason = classify(e);
            } finally {
                if (r != null) r.close();
            }
            try {
                Thread.sleep(400L * i);
            } catch (InterruptedException ignored) {
            }
        }
        p.alive = false;
        if (!p.tlsError.isEmpty() && p.reason.isEmpty()) p.reason = p.tlsError;
        return p;
    }

    /** 并发探两个候选 base，返回结论 + 自动择优（会改 chatBase/loadBase） */
    public static JSONObject probeAll() {
        final Probe[] out = new Probe[2];
        Thread t1 = new Thread(new Runnable() {
            public void run() { out[0] = probeOne("chat", REQ_BASE); }
        }, "ag-probe-daily");
        Thread t2 = new Thread(new Runnable() {
            public void run() { out[1] = probeOne("load", LOAD_BASE); }
        }, "ag-probe-prod");
        t1.start();
        t2.start();
        try {
            t1.join(45000);
            t2.join(45000);
        } catch (InterruptedException ignored) {
        }

        Probe daily = out[0] != null ? out[0] : failedProbe("chat", REQ_BASE);
        Probe prod = out[1] != null ? out[1] : failedProbe("load", LOAD_BASE);

        JSONObject ret = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            arr.put(toJson(daily));
            arr.put(toJson(prod));

            String oldChat = chatBase;
            String oldLoad = loadBase;
            String verdict;
            String summary;
            if (daily.alive && prod.alive) {
                verdict = "ok";
                chatBase = REQ_BASE;
                loadBase = LOAD_BASE;
                summary = "两个端点都通，用默认组合（聊天走 daily，取项目走 cloudcode）。";
            } else if (daily.alive) {
                verdict = "degraded";
                chatBase = REQ_BASE;
                loadBase = REQ_BASE;
                summary = "只有 daily 通，取项目也改走 daily（同一后端），登录/对话可用。";
            } else if (prod.alive) {
                verdict = "degraded";
                chatBase = LOAD_BASE;
                loadBase = LOAD_BASE;
                summary = "daily 这次不通，已切到 cloudcode-pa（同一后端），现在可以直接用；转发时还会自动兜底重试。";
            } else {
                verdict = "down";
                chatBase = REQ_BASE;
                loadBase = LOAD_BASE;
                summary = "本次探测两个端点都没拿到响应：先确认代理/VPN 规则放行了 *.googleapis.com，再点一次；这是网络问题，不是账号问题。";
            }
            ret.put("verdict", verdict);
            ret.put("summary", summary);
            ret.put("chatBase", chatBase);
            ret.put("loadBase", loadBase);
            ret.put("changed", !oldChat.equals(chatBase) || !oldLoad.equals(loadBase));
            ret.put("results", arr);
            ret.put("ok", true);
        } catch (Exception e) {
            try {
                ret.put("ok", false);
                ret.put("error", String.valueOf(e.getMessage()));
                ret.put("results", arr);
            } catch (Exception ignored) {
            }
        }
        return ret;
    }

    private static Probe failedProbe(String role, String url) {
        Probe p = new Probe();
        p.role = role;
        p.url = url;
        p.name = url.replace("https://", "");
        p.alive = false;
        p.reason = "探测超时未返回";
        return p;
    }

    private static JSONObject toJson(Probe p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", p.role);
        o.put("name", p.name);
        o.put("url", p.url);
        o.put("alive", p.alive);
        o.put("tries", p.tries);
        if (p.alive) {
            o.put("status", p.status);
            if (p.ms >= 0) o.put("ms", p.ms);
        }
        o.put("reason", p.reason == null ? "" : p.reason);
        o.put("note", p.note == null ? "" : p.note);
        o.put("preview", p.preview == null ? "" : p.preview);
        return o;
    }

    // ---------------------------------------------------------------- 登录流程

    public static class Flow {
        public String flowId;
        public String state;
        public String verifier;
        public String redirect;
        public String authUrl;
        public long createdAt = System.currentTimeMillis();
        public String status = "pending";   // pending / done / failed
        public String error = "";
        public JSONObject account;
    }

    /**
     * 实测：只有在**不协商 ALPN**（等于纯 HTTP/1.1）时 cloudcode-pa 才握手通过；
     * 只要 ALPN 出现 h2 就被 reset（OkHttp/HttpURLConnection 默认会协商 h2，
     * 这正是「配置正确却连接被断」的根因）。所以这里自己开 socket、不设 ALPN。
     */
    private static javax.net.ssl.SSLSocketFactory TLS12 = buildTls12();

    private static java.net.Socket tune(java.net.Socket s) {
        if (!(s instanceof javax.net.ssl.SSLSocket)) return s;
        javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) s;
        try {
            ss.setEnabledProtocols(new String[]{"TLSv1.2"});
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = ss.getClass().getMethod("setApplicationProtocols", String[].class);
            m.invoke(ss, (Object) new String[]{"http/1.1"});
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static javax.net.ssl.SSLSocketFactory buildTls12() {
        try {
            final javax.net.ssl.SSLSocketFactory base =
                    javax.net.ssl.SSLContext.getDefault().getSocketFactory();
            return new javax.net.ssl.SSLSocketFactory() {
                public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
                public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
                public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                        throws java.io.IOException {
                    return tune(base.createSocket(s, host, port, autoClose));
                }
                public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
                    return tune(base.createSocket(host, port));
                }
                public java.net.Socket createSocket(String host, int port, java.net.InetAddress la, int lp)
                        throws java.io.IOException {
                    return tune(base.createSocket(host, port, la, lp));
                }
                public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
                    return tune(base.createSocket(host, port));
                }
                public java.net.Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp)
                        throws java.io.IOException {
                    return tune(base.createSocket(a, p, la, lp));
                }
            };
        } catch (Throwable e) {
            return null;
        }
    }

    /** 到 Google 的连接统一入口：走 CodexAuth 的本地代理探测 + 强制 TLS1.2/HTTP1.1 */
    public static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = CodexAuth.openHttp(urlStr);
        if (c instanceof javax.net.ssl.HttpsURLConnection && TLS12 != null) {
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(TLS12);
        }
        return c;
    }

    private static String b64url(byte[] data) {
        return android.util.Base64.encodeToString(data,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 建一个 PKCE + state 的授权流程，回调固定落在 localhost:1456/oauth-callback */
    public static Flow createFlow() throws Exception {
        Flow f = new Flow();
        f.flowId = randomToken(8);
        f.state = randomToken(16);
        f.verifier = randomToken(32);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(f.verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = b64url(digest);
        f.redirect = "http://localhost:" + CALLBACK_PORT + CALLBACK_PATH;

        StringBuilder u = new StringBuilder(AUTHORIZE_URL);
        u.append("?client_id=").append(enc(CLIENT_ID));
        u.append("&redirect_uri=").append(enc(f.redirect));
        u.append("&response_type=code");
        u.append("&scope=").append(enc(SCOPES));
        u.append("&access_type=offline");
        u.append("&prompt=consent");
        u.append("&state=").append(enc(f.state));
        u.append("&code_challenge=").append(enc(challenge));
        u.append("&code_challenge_method=S256");
        f.authUrl = u.toString();
        return f;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /**
     * 用授权 code 换 token，并取回账号身份与 Cloud 项目。
     * 返回可直接落盘的账号 JSON。
     */
    public static JSONObject exchangeCode(String code, String verifier, String redirect) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + enc(CLIENT_ID)
                + "&client_secret=" + enc(CLIENT_SECRET)
                + "&code_verifier=" + enc(verifier);
        JSONObject tok = postForm(TOKEN_URL, form);
        String access = tok.optString("access_token", "");
        String refresh = tok.optString("refresh_token", "");
        long expiresIn = tok.optLong("expires_in", 3600);
        if (access.isEmpty()) throw new Exception("Google 没有返回 access_token");

        JSONObject acc = new JSONObject();
        acc.put("type", "google");
        acc.put("access", access);
        acc.put("token", access);
        acc.put("refresh", refresh);
        acc.put("expires", System.currentTimeMillis() + expiresIn * 1000L);
        String email = "";
        try {
            email = whoAmI(access);
        } catch (Exception ignored) {
        }
        acc.put("email", email);
        acc.put("name", email);
        acc.put("addedAt", System.currentTimeMillis());
        acc.put("enabled", true);

        String project = "";
        String plan = "";
        try {
            JSONObject load = loadCodeAssist(access, "");
            project = projectOf(load);
            plan = planOf(load);
            acc.put("tier", tierOf(load));
            acc.put("paidTier", paidTierOf(load));
            if (project.isEmpty()) project = onboard(access);
        } catch (Exception e) {
            acc.put("projectError", e.getMessage() == null ? "取项目失败" : e.getMessage());
        }
        acc.put("project", project);
        acc.put("plan", plan);
        return acc;
    }

    /** Google OAuth 的常见错误翻成人话（原样贴 JSON 用户看不懂） */
    private static String googleTokenError(int status, String text) {
        String low = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("invalid_grant")) {
            if (low.contains("malformed")) return "授权码格式不对（多半是复制时被截断），请重新点登录拿新链接";
            return "授权码已失效或被用过（同一个码只能用一次），请重新点「Google 账号登录」";
        }
        if (low.contains("invalid_client")) return "OAuth 客户端不被 Google 接受（client_id/secret 可能变了）";
        if (low.contains("redirect_uri_mismatch")) return "回调地址不匹配：Google 只接受 http://localhost:1456/oauth-callback";
        if (low.contains("access_denied")) return "你在 Google 授权页点了拒绝";
        if (low.contains("unsupported_country") || low.contains("region")) return "这个 Google 账号所在地区不支持 Antigravity";
        return "Google 返回 " + status + "：" + shortErr(text);
    }

    public static final class TypedAuthException extends Exception {
        public final int status;
        public final boolean needsSignIn;
        public final long retryAfterMs;
        /** 这次交换走到的传输阶段；UNKNOWN 表示没有传输层证据。 */
        public final SendStage stage;
        /** 可证明请求一个字节都没发出去 —— 唯一可信的「未发送」结论。 */
        public final boolean provablyNotSent;
        public final long bytesWritten;
        public final long bytesTotal;

        /** 兼容旧签名：没有传输层证据，所以按「可能已发送」处理（保守方向）。 */
        TypedAuthException(int status, boolean needsSignIn) {
            this(status, needsSignIn, SendStage.UNKNOWN, 0, 0, false);
        }

        /** 传输层失败：结论直接抄 SendFailure 的证据，这里不做二次猜测。 */
        TypedAuthException(int status, boolean needsSignIn, SendFailure failure) {
            this(status, needsSignIn,
                    failure == null ? SendStage.UNKNOWN : failure.stage,
                    failure == null ? 0 : failure.bytesWritten,
                    failure == null ? 0 : failure.bytesTotal,
                    failure != null && failure.provablyNotSent());
        }

        private TypedAuthException(int status, boolean needsSignIn, SendStage stage,
                                   long bytesWritten, long bytesTotal, boolean provablyNotSent) {
            super("Antigravity token refresh failed (HTTP " + status + ")");
            this.status = status; this.needsSignIn = needsSignIn; this.retryAfterMs = 0L;
            this.stage = stage == null ? SendStage.UNKNOWN : stage;
            this.bytesWritten = bytesWritten;
            this.bytesTotal = bytesTotal;
            this.provablyNotSent = provablyNotSent;
        }

        /** 与 provablyNotSent 互补：不是可证明未发送，就按可能已发送处理。 */
        public boolean maybeSent() { return !provablyNotSent; }

        /** 网关据此决定「释放刷新围栏、允许重试」还是「保留为结果未知」。 */
        public boolean retryIsSafe() { return provablyNotSent; }

        /** 脱敏诊断：阶段 + 字节数 + 目标主机 + 状态码，不含刷新凭据。 */
        public String diagnostic() {
            return (provablyNotSent
                    ? "Antigravity token exchange was never sent"
                    : "Antigravity token exchange may already have been executed")
                    + " (stage=" + stage.name() + ", bytes=" + bytesWritten + "/" + bytesTotal
                    + ", host=" + hostOf(TOKEN_URL) + ", http=" + status + ")";
        }
    }
    static final class CandidateResponse {
        final int status;
        final JSONObject body;
        /** 非空 = 这次交换没拿到响应；失败按传输层证据分类，不再统一压成「HTTP 0」。 */
        final SendFailure failure;
        CandidateResponse(int status, JSONObject body) { this(status, body, null); }
        CandidateResponse(int status, JSONObject body, SendFailure failure) {
            this.status = status; this.body = body; this.failure = failure;
        }
        static CandidateResponse failed(SendFailure failure) {
            return new CandidateResponse(0, new JSONObject(), failure);
        }
    }
    interface CandidateTransport { CandidateResponse exchange(String refresh) throws Exception; }

    /** 真实交换：走同一个可审计的 exchangeRefresh，失败时把传输层证据一起带回去。 */
    private static CandidateResponse exchangeCandidate(String refresh) {
        Exchange out = exchangeRefresh(refresh, SOCKET_TRANSPORT, Cancellation.NONE);
        return out.answered()
                ? new CandidateResponse(out.status, out.body)
                : CandidateResponse.failed(out.failure);
    }

    public static JSONObject refreshCandidate(JSONObject account) throws Exception {
        return refreshCandidate(account, AntigravityAuth::exchangeCandidate);
    }

    /**
     * 候选刷新。旧的注入缝（CandidateTransport）原样保留：
     * 返回 CandidateResponse 就是「拿到响应」，抛异常就是「没有传输层证据」——
     * 后者一律按可能已发送处理，绝不因为异常叫 IOException 就猜没发出去。
     */
    static JSONObject refreshCandidate(JSONObject account, CandidateTransport transport) throws Exception {
        if (account == null) throw new TypedAuthException(0, false);
        JSONObject copy;
        synchronized (account) { copy = new JSONObject(account.toString()); }
        String refresh = copy.optString("refresh", "");
        if (refresh.isEmpty()) throw new TypedAuthException(0, true);
        CandidateTransport seam = transport == null ? AntigravityAuth::exchangeCandidate : transport;
        Exchange out;
        try {
            CandidateResponse response = seam.exchange(refresh);
            if (response == null) throw new IllegalStateException("candidate transport returned no response");
            out = response.failure != null
                    ? Exchange.failed(response.failure)
                    : Exchange.answered(response.status, response.body);
        } catch (SendFailure failure) {
            out = Exchange.failed(failure);
        } catch (Exception noEvidence) {
            out = Exchange.unclassified(noEvidence);
        }
        if (!out.answered()) throw new TypedAuthException(0, false, out.failure);
        int status = out.status;
        JSONObject body = out.body;
        String access = body.optString("access_token", "");
        if (status < 200 || status >= 300 || access.isEmpty()) {
            boolean signIn = status == 401 || (status == 400 && "invalid_grant".equals(body.optString("error", "")));
            throw new TypedAuthException(status, signIn);
        }
        copy.put("access", access);
        copy.put("token", access);
        copy.put("expires", System.currentTimeMillis() + body.optLong("expires_in", 3600) * 1000L);
        String rotated = body.optString("refresh_token", "");
        if (!rotated.isEmpty()) copy.put("refresh", rotated);
        return copy;
    }

    /** 刷 access_token（Google refresh token 通常不轮换，只有上游给了新值才写回） */
    public static boolean refresh(JSONObject acc) {
        if (acc == null) return false;
        synchronized (acc) { return refreshLocked(acc); }
    }
    private static boolean refreshLocked(JSONObject acc) {
        String refresh = acc.optString("refresh", "");
        if (refresh.isEmpty()) return false;
        // 与 refreshCandidate 走同一条可审计通路：失败时能说清是「没发出去」还是「可能已发出」。
        return applyRefresh(acc, exchangeRefresh(refresh, SOCKET_TRANSPORT, Cancellation.NONE));
    }

    /**
     * 把一次成功交换写回账号。字段与旧 refresh() 的写回完全一致：
     * access / token 同步、expires = now + expires_in、上游给了新 refresh 才覆盖。
     * 失败或响应不完整一律返回 false，且账号一个字段都不动。
     */
    static boolean applyRefresh(JSONObject acc, Exchange out) {
        if (acc == null || out == null || !out.answered()) return false;
        // 与旧 postForm 的判定完全一致（只把 >=400 当失败），不额外收紧也不放宽。
        if (out.status < 200 || out.status >= 400) return false;
        String access = out.body.optString("access_token", "");
        if (access.isEmpty()) return false;
        long expiresIn = out.body.optLong("expires_in", 3600);
        try {
            acc.put("access", access);
            acc.put("token", access);
            acc.put("expires", System.currentTimeMillis() + expiresIn * 1000L);
            String rotated = out.body.optString("refresh_token", "");
            if (!rotated.isEmpty()) acc.put("refresh", rotated);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /** 保证 access 至少还有 3 分钟，必要时刷新；失败抛异常 */
    public static String ensureToken(JSONObject acc) throws Exception {
        synchronized (acc) { return ensureTokenLocked(acc); }
    }
    private static String ensureTokenLocked(JSONObject acc) throws Exception {
        String access = acc.optString("access", acc.optString("token", ""));
        long expires = acc.optLong("expires", 0L);
        boolean stale = access.isEmpty() || (expires > 0 && expires - System.currentTimeMillis() < 3 * 60 * 1000L);
        if (stale) {
            String refresh = acc.optString("refresh", "");
            if (refresh.isEmpty()) throw refreshFailure(null);
            Exchange out = exchangeRefresh(refresh, SOCKET_TRANSPORT, Cancellation.NONE);
            // 刷新失败不再一律说「已掉线」：没发出去的请求没有消费任何凭据。
            if (!applyRefresh(acc, out)) throw refreshFailure(out);
            access = acc.optString("access", "");
        }
        if (access.isEmpty()) throw new Exception("Antigravity 账号没有可用的 access_token");
        return access;
    }

    /**
     * 刷新失败的说法必须与证据一致：
     *  - 可证明没发出去 → 凭据没被消费，网络恢复后直接重试，不要求重新登录；
     *  - 可能已发送 → 结果未知、一次性凭据可能已经没了，只能重新登录；
     *  - 拿到了响应但被拒 → 沿用旧文案（兼容旧行为）。
     */
    private static Exception refreshFailure(Exchange out) {
        if (out != null && !out.answered()) {
            if (out.provablyNotSent()) {
                return new Exception("Antigravity 刷新请求没有发出去（" + out.diagnostic()
                        + "），账号凭据未被消费，网络恢复后可直接重试");
            }
            return new Exception("Antigravity 账号已掉线：refresh 结果未知（请求可能已被上游处理；"
                    + out.diagnostic() + "），请重新登录");
        }
        return new Exception("Antigravity 账号已掉线：refresh_token 刷新失败，请重新登录");
    }

    /** 账号没有项目时补一次 loadCodeAssist + onboardUser */
    public static String ensureProject(JSONObject acc, String access) throws Exception {
        String project = acc.optString("project", "");
        if (!project.isEmpty()) return project;
        JSONObject load = loadCodeAssist(access, "");
        project = projectOf(load);
        if (project.isEmpty()) project = onboard(access);
        if (project.isEmpty()) throw new Exception("Google 没有给这个账号分配 Cloud 项目（onboard 未完成）");
        acc.put("project", project);
        acc.put("tier", tierOf(load));
        acc.put("paidTier", paidTierOf(load));
        String plan = planOf(load);
        if (!plan.isEmpty()) acc.put("plan", plan);
        return project;
    }

    // ---------------------------------------------------------------- Code Assist 调用

    public static JSONObject postJson(String url, String bearer, JSONObject body, String ua) throws Exception {
        byte[] payload = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        Resp r = request(url, "POST", bearer, "application/json", "application/json", payload, 20000, 90000);
        try {
            String text = r.text();
            if (r.status < 200 || r.status >= 400) {
                throw codeAssistRefused(r.status, text);
            }
            if (text.trim().isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (org.json.JSONException je) {
                String preview = text.replace('\n', ' ').replace('\r', ' ').trim();
                if (preview.length() > 160) preview = preview.substring(0, 160) + "…";
                throw new Exception("响应不是 JSON（" + preview + "）", je);
            }
        } finally {
            r.close();
        }
    }

    /**
     * Code Assist 的非 2xx 应答：状态码与消息分开携带。
     *
     * 对齐 upstream yetone/magpie internal/provider/google.go 的 codeAssistError：
     * 调用方（tier 拒绝分类、日志）按状态码判断，不再去猜错误串里有没有 "400"
     * —— 猜字符串会让「正文里恰好出现 400」的 429 / 403 / 5xx 被误判成 tier 被拒。
     */
    public static final class CodeAssistException extends Exception {
        private final int status;

        CodeAssistException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** HTTP 状态码（Code Assist 应答的那个）。 */
        public int status() {
            return status;
        }
    }

    /** 造一条 Code Assist 非 2xx 应答的异常；消息格式与旧版逐字一致，日志不变。 */
    static CodeAssistException codeAssistRefused(int status, String text) {
        return new CodeAssistException(status, "Code Assist " + status + ": " + shortErr(text));
    }

    /**
     * err 携带的 Code Assist 状态码，0 表示它不是 Code Assist 的应答。
     * 对齐上游 codeAssistStatus：只认结构化状态，沿 cause 链找（上游用 errors.As）。
     */
    static int codeAssistStatus(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof CodeAssistException) return ((CodeAssistException) t).status();
        }
        return 0;
    }

    private static JSONObject postForm(String url, String form) throws Exception {
        Resp r = request(url, "POST", null, "application/json",
                "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8), 20000, 60000);
        try {
            String text = r.text();
            if (r.status < 200 || r.status >= 400) throw new Exception(googleTokenError(r.status, text));
            if (text.trim().isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (org.json.JSONException je) {
                String preview = text.replace('\n', ' ').replace('\r', ' ').trim();
                if (preview.length() > 160) preview = preview.substring(0, 160) + "…";
                throw new Exception("Google 响应不是 JSON（" + preview + "）", je);
            }
        } finally {
            r.close();
        }
    }

    private static String whoAmI(String access) throws Exception {
        Resp r = request(USERINFO_URL, "GET", access, "application/json", null, null, 20000, 30000);
        try {
            String text = r.text();
            if (r.status < 200 || r.status >= 400) throw new Exception("userinfo " + r.status + ": " + shortErr(text));
            JSONObject o = new JSONObject(text);
            return o.optString("email", "");
        } finally {
            r.close();
        }
    }

    /** Antigravity 用 metadata.ideType=ANTIGRAVITY 问账号的项目与 tier */
    public static JSONObject loadCodeAssist(String access, String project) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("ideType", "ANTIGRAVITY");
        JSONObject body = new JSONObject();
        body.put("metadata", meta);
        if (project != null && !project.isEmpty()) body.put("cloudaicompanionProject", project);
        // prod 优先（upstream 的 loadBase），本机网络不通时回落到 daily
        try {
            return postJson(loadBase + "/v1internal:loadCodeAssist", access, body,
                    USER_AGENT + " google-api-nodejs-client/10.3.0");
        } catch (Exception e) {
            return postJson(chatBase + "/v1internal:loadCodeAssist", access, body,
                    USER_AGENT + " google-api-nodejs-client/10.3.0");
        }
    }

    private static String projectOf(JSONObject load) {
        if (load == null) return "";
        Object p = load.opt("cloudaicompanionProject");
        if (p instanceof String) return (String) p;
        if (p instanceof JSONObject) return ((JSONObject) p).optString("id", "");
        return "";
    }

    private static String tierOf(JSONObject load) {
        JSONObject t = load == null ? null : load.optJSONObject("currentTier");
        return t == null ? "" : t.optString("id", "");
    }

    private static String paidTierOf(JSONObject load) {
        JSONObject t = load == null ? null : load.optJSONObject("paidTier");
        return t == null ? "" : t.optString("id", "");
    }

    private static String planOf(JSONObject load) {
        if (load == null) return "";
        JSONObject t = load.optJSONObject("paidTier");
        if (t == null || t.optString("name", "").isEmpty()) t = load.optJSONObject("currentTier");
        if (t == null) return "";
        String n = t.optString("name", "");
        return n.isEmpty() ? t.optString("id", "") : n;
    }

    /** onboardUser：没有项目的新账号要跑一次（LRO，最多等 5 轮） */
    public static String onboard(String access) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("ide_type", "ANTIGRAVITY");
        meta.put("ide_version", APP_VERSION);
        meta.put("ide_name", "antigravity");
        JSONObject body = new JSONObject();
        body.put("tier_id", "free-tier");
        body.put("metadata", meta);
        String ua = USER_AGENT + " google-api-nodejs-client/10.3.0";
        for (int i = 0; i < 5; i++) {
            JSONObject lro = postJson(chatBase + "/v1internal:onboardUser", access, body, ua);
            if (lro.optBoolean("done", false)) {
                Object resp = lro.opt("response");
                if (resp instanceof JSONObject) {
                    Object p = ((JSONObject) resp).opt("cloudaicompanionProject");
                    if (p instanceof String) return (String) p;
                    if (p instanceof JSONObject) return ((JSONObject) p).optString("id", "");
                }
                return "";
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return "";
    }

    /**
     * fetchAvailableModels：Antigravity 的模型清单（含配额）。
     * 返回 [{id,name,context,output,remaining}]，过滤掉非聊天模型与 disabled。
     */
    /**
     * Google 会用 400 INVALID_ARGUMENT 拒绝某些 tier（例如 g1-pro-tier 只对
     * 真正有该档位的账号有效）。被拒过的 tier 记 24 小时，别每次请求都先吃一个 400
     * —— 对齐上游 magpie 的 tierRefusedFor。
     */
    private static final java.util.Map<String, Long> TIER_REFUSED =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final long TIER_REFUSED_FOR = 24L * 60 * 60 * 1000;

    private static boolean tierRefused(String key) {
        Long at = TIER_REFUSED.get(key);
        if (at == null) return false;
        if (System.currentTimeMillis() - at > TIER_REFUSED_FOR) {
            TIER_REFUSED.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 这次失败是否说明「Google 拒了这个 tier」，该记进 TIER_REFUSED（24 小时）。
     *
     * 对齐上游 fetchAvailableModels：只有 400（INVALID_ARGUMENT，即 g1-pro-tier 这档
     * 账号其实没有）算 tier 被拒；429（额度 / 假 429）、401 / 403、404、5xx 一律不算。
     * 旧实现用 String.valueOf(e.getMessage()).contains("400") 判断，429 正文里出现
     * 「retry after 400 seconds」这类字样就会把账号的高档位错误静音一整天。
     */
    static boolean marksTierRefused(String tier, Throwable err) {
        return tier != null && !tier.isEmpty()
                && codeAssistStatus(err) == HttpURLConnection.HTTP_BAD_REQUEST;
    }

    /** fetchAvailableModels 的一次 Code Assist POST；生产走 postJson，测试可注入。 */
    interface ModelsPost {
        JSONObject post(String url, String access, JSONObject body) throws Exception;
    }

    private static final ModelsPost POST_MODELS = (url, access, body) -> postJson(url, access, body, USER_AGENT);

    public static JSONArray fetchModels(JSONObject acc, String access, String project) throws Exception {
        return fetchModels(acc, access, project, null);
    }

    static JSONArray fetchModels(JSONObject acc, String access, String project, ModelsPost post) throws Exception {
        ModelsPost seam = post == null ? POST_MODELS : post;
        JSONObject body = new JSONObject();
        body.put("project", project);
        String paid = acc.optString("paidTier", "");
        String tier = acc.optString("tier", "");
        String accKey = acc.optString("id", project);
        JSONArray tiers = new JSONArray();
        if (!paid.isEmpty() && !"free-tier".equals(paid) && !"legacy-tier".equals(paid)
                && !tierRefused(accKey + "\u0000" + paid)) {
            tiers.put(paid);
        }
        if (!tier.isEmpty() && !"free-tier".equals(tier) && !"legacy-tier".equals(tier)
                && !tier.equals(paid) && !tierRefused(accKey + "\u0000" + tier)) {
            tiers.put(tier);
        }
        tiers.put("");

        JSONObject res = null;
        Exception lastErr = null;
        for (int i = 0; i < tiers.length(); i++) {
            String t = tiers.optString(i, "");
            JSONObject b = new JSONObject(body.toString());
            if (!t.isEmpty()) {
                JSONObject ent = new JSONObject();
                ent.put("userTier", t);
                b.put("entitlement", ent);
            }
            try {
                res = seam.post(chatBase + "/v1internal:fetchAvailableModels", access, b);
                if (!t.isEmpty()) acc.put("tier", t);
                break;
            } catch (Exception e) {
                lastErr = e;
                if (marksTierRefused(t, e)) {
                    TIER_REFUSED.put(accKey + "\u0000" + t, System.currentTimeMillis());
                }
            }
        }
        if (res == null) throw lastErr == null ? new Exception("模型列表拉取失败") : lastErr;

        JSONObject models = res.optJSONObject("models");
        JSONArray out = new JSONArray();
        if (models == null) return out;
        Iterator<String> it = models.keys();
        while (it.hasNext()) {
            String id = it.next();
            if (isHidden(id)) continue;
            JSONObject m = models.optJSONObject(id);
            if (m == null) continue;
            if (m.optBoolean("disabled", false)) continue;
            JSONObject o = new JSONObject();
            o.put("id", id);
            String name = m.optString("displayName", "");
            o.put("name", name.isEmpty() ? id : name);
            int ctx = m.optInt("maxTokens", 0);
            int outTokens = m.optInt("maxOutputTokens", 0);
            if (ctx > 0) o.put("context", ctx);
            if (outTokens > 0) o.put("output", outTokens);
            JSONObject q = m.optJSONObject("quotaInfo");
            if (q != null) {
                if (q.has("remainingFraction")) o.put("remaining", q.optDouble("remainingFraction", 0));
                o.put("reset", q.optString("resetTime", ""));
            }
            out.put(o);
        }
        return out;
    }

    /** 思考档位后缀，长的在前（对齐上游 antigravityEffortWords） */
    private static final String[][] EFFORT_WORDS = {
            {"extra-high", "xhigh"}, {"extra-low", "minimal"}, {"xhigh", "xhigh"},
            {"minimal", "minimal"}, {"low", "low"}, {"medium", "medium"}, {"high", "high"}
    };

    /** 把 gemini-3.8-flash-high 拆成 family=gemini-3.8-flash + effort=high */
    private static String[] splitEffort(String id) {
        for (String[] w : EFFORT_WORDS) {
            String suffix = "-" + w[0];
            if (id.endsWith(suffix) && id.length() > suffix.length()) {
                return new String[]{id.substring(0, id.length() - suffix.length()), w[1]};
            }
        }
        return new String[]{id, ""};
    }

    /**
     * Antigravity 把同一个模型的思考档位当独立 id 列出来（gemini-3.8-flash-low /
     * -medium / -high），PC 端 magpie 把它们合并成一个模型 + 档位。这里照做：
     * 返回去重后的 family 列表，并在 modelMeta 里记 variants（档位 → 真实 id），
     * 请求时按 effort 选变体（见 pickVariant）。
     */
    public static JSONArray collapseFamilies(JSONArray rawModels, JSONObject modelMeta) {
        JSONArray out = new JSONArray();
        java.util.LinkedHashMap<String, JSONArray> groups = new java.util.LinkedHashMap<String, JSONArray>();
        java.util.LinkedHashMap<String, JSONObject> firstMeta = new java.util.LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < rawModels.length(); i++) {
            JSONObject m = rawModels.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;
            String[] fe = splitEffort(id);
            String family = fe[0];
            JSONArray g = groups.get(family);
            if (g == null) {
                g = new JSONArray();
                groups.put(family, g);
                firstMeta.put(family, m);
            }
            g.put(id);
        }
        for (java.util.Map.Entry<String, JSONArray> e : groups.entrySet()) {
            String family = e.getKey();
            JSONArray variants = e.getValue();
            JSONObject base = firstMeta.get(family);
            String def = family;
            if (!variants.toString().contains("\"" + family + "\"")) {
                // 没有无后缀的基名时，用 high 作默认（对齐上游）
                def = variants.optString(0, family);
                for (int i = 0; i < variants.length(); i++) {
                    String v = variants.optString(i, "");
                    if (v.endsWith("-high")) {
                        def = v;
                        break;
                    }
                }
            }
            out.put(family);
            if (modelMeta != null && base != null) {
                try {
                    JSONObject meta = new JSONObject(base.toString());
                    meta.put("id", family);
                    JSONObject vs = new JSONObject();
                    for (int i = 0; i < variants.length(); i++) {
                        String v = variants.optString(i, "");
                        String eff = splitEffort(v)[1];
                        if (!eff.isEmpty()) vs.put(eff, v);
                    }
                    if (vs.length() > 0) meta.put("variants", vs);
                    meta.put("defaultId", def);
                    // 显示名去掉「(Low)」这类括号后缀
                    String name = base.optString("name", family);
                    int paren = name.lastIndexOf(" (");
                    if (paren > 0 && name.endsWith(")")) name = name.substring(0, paren);
                    meta.put("name", name.isEmpty() ? family : name);
                    meta.put("effort", splitEffort(def)[1]);
                    modelMeta.put(family, meta);
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** 按 effort 从 variants 里选真实 id；选不到就用默认 id */
    public static String pickVariant(JSONObject meta, String effort) {
        if (meta == null) return null;
        JSONObject vs = meta.optJSONObject("variants");
        if (vs != null && effort != null && !effort.isEmpty()) {
            String want = effort;
            if ("xhigh".equals(want) && vs.has("high") && !vs.has("xhigh")) want = "high";
            if ("minimal".equals(want) && vs.has("low") && !vs.has("minimal")) want = "low";
            if (vs.has(want)) return vs.optString(want, null);
        }
        String def = meta.optString("defaultId", "");
        return def.isEmpty() ? null : def;
    }

    private static boolean isHidden(String id) {
        for (String h : HIDDEN) if (h.equals(id)) return true;
        return id.startsWith("chat_") || id.startsWith("tab_");
    }

    // ---------------------------------------------------------------- 提示词打散

    /** system prompt 里有没有 Antigravity 会拒绝的词 */
    public static boolean turnsAway(String system) {
        if (system == null || system.isEmpty()) return false;
        String low = system.toLowerCase();
        for (String line : TURNED_AWAY) {
            if (low.contains(line)) return true;
        }
        return false;
    }

    /**
     * 在每个命中片段的第一个字符后插一个 U+200B。Google 的检查过不去，
     * 模型照读（upstream antigravityCloak 同款做法）。
     */
    public static String cloak(String system) {
        if (!turnsAway(system)) return system;
        String low = system.toLowerCase();
        List<Integer> at = new ArrayList<Integer>();
        for (String line : TURNED_AWAY) {
            int i = 0;
            while (true) {
                int j = low.indexOf(line, i);
                if (j < 0) break;
                at.add(j + 1);
                i = j + line.length();
            }
        }
        if (at.isEmpty()) return system;
        Collections.sort(at);
        StringBuilder sb = new StringBuilder(system.length() + at.size() * 3);
        int prev = 0;
        int last = -1;
        for (Integer idx : at) {
            if (idx == last) continue;
            last = idx;
            sb.append(system, prev, idx);
            sb.append(ZERO_WIDTH);
            prev = idx;
        }
        sb.append(system.substring(prev));
        return sb.toString();
    }

    /** Antigravity 那个假 429 的识别（额度其实没动） */
    public static boolean looksTurnedAway(int status, String body) {
        // RESOURCE_EXHAUSTED alone also describes actual quota or rate limits.
        // No reliable distinguishing server marker is currently established.
        return false;
    }

    // ---------------------------------------------------------------- 小工具

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    static String shortErr(String text) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }
}
