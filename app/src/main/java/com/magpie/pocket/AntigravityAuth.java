package com.magpie.pocket;

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

        public String text() {
            try {
                return readAll(body);
            } catch (Exception e) {
                return "";
            }
        }

        public void close() {
            try { if (body != null) body.close(); } catch (Throwable ignored) {}
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
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

    /** 发出一个 HTTPS 请求，返回状态与（尚未读完的）响应流 */
    public static Resp request(String urlStr, String method, String bearer, String accept,
                               String contentType, byte[] body, int connectMs, int readMs) throws Exception {
        java.net.URL u = new java.net.URL(urlStr);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        String path = u.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (u.getQuery() != null) path = path + "?" + u.getQuery();

        // 这台机到 Google 的 TLS 握手会被随机 reset（实测成功率约五成），
        // 所以连接建立阶段要重试。此时一个字节都还没发，重试是安全的。
        java.net.Socket plain = null;
        Exception lastErr = null;
        Resp r = new Resp();
        try {
            for (int attempt = 1; attempt <= HANDSHAKE_TRIES; attempt++) {
                try {
                    plain = new java.net.Socket();
                    plain.connect(new java.net.InetSocketAddress(host, port), connectMs);
                    plain.setSoTimeout(readMs);
            // 关键：不设 ALPN。Conscrypt 默认不送 ALPN，服务端按 HTTP/1.1 处理；
            // 一旦协商到 h2，cloudcode-pa 会直接 reset（实测）。
                    javax.net.ssl.SSLSocketFactory sf =
                            (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                    java.net.Socket s = sf.createSocket(plain, host, port, true);
                    ((javax.net.ssl.SSLSocket) s).startHandshake();
                    s.setSoTimeout(readMs);
                    r.socket = s;
                    lastErr = null;
                    break;
                } catch (Exception he) {
                    lastErr = he;
                    try { if (plain != null) plain.close(); } catch (Throwable ignored) {}
                    try { if (r.socket != null) r.socket.close(); } catch (Throwable ignored) {}
                    plain = null;
                    r.socket = null;
                    if (attempt < HANDSHAKE_TRIES) {
                        try {
                            Thread.sleep(200L * attempt);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            if (lastErr != null) throw lastErr;
            java.net.Socket s = r.socket;

            StringBuilder h = new StringBuilder(512);
            h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            h.append("Host: ").append(host).append("\r\n");
            h.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            if (accept != null) h.append("Accept: ").append(accept).append("\r\n");
            if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
            if (bearer != null && !bearer.isEmpty()) h.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (body != null) h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            OutputStream os = s.getOutputStream();
            os.write(h.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) os.write(body);
            os.flush();

            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 16384);
            String line = readLine(in);
            r.statusLine = line == null ? "" : line;
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    try {
                        r.status = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {
                    }
                }
            }
            while ((line = readLine(in)) != null) {
                if (line.isEmpty()) break;
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
            r.body = decoded;
            return r;
        } catch (Exception e) {
            try { plain.close(); } catch (Throwable ignored) {}
            try { if (r.socket != null) r.socket.close(); } catch (Throwable ignored) {}
            throw e;
        }
    }

    /** 诊断：同一台机上 TLS 协议 / ALPN 组合到底哪个能握手（只用于 /api/antigravity/tlslab） */
    public static JSONObject tlsLab(String host, boolean tls12, String alpn, int connectMs) {
        JSONObject o = new JSONObject();
        java.net.Socket plain = null;
        java.net.Socket tls = null;
        try {
            plain = new java.net.Socket();
            plain.connect(new java.net.InetSocketAddress(host, 443), connectMs);
            javax.net.ssl.SSLSocketFactory f =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            tls = f.createSocket(plain, host, 443, true);
            javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) tls;
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
                java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
                for (java.net.InetAddress a : all) {
                    boolean v4 = a instanceof java.net.Inet4Address;
                    if ("ipv4".equals(mode) && !v4) continue;
                    if ("ipv6".equals(mode) && v4) continue;
                    addr = a;
                    break;
                }
                if (addr == null) throw new java.net.UnknownHostException("没有匹配 " + mode + " 的地址");
                plain = new java.net.Socket();
                plain.connect(new java.net.InetSocketAddress(addr, 443), 6000);
                javax.net.ssl.SSLSocketFactory sf =
                        (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                tls = sf.createSocket(plain, host, 443, true);
                ((javax.net.ssl.SSLSocket) tls).startHandshake();
                long ms = System.currentTimeMillis() - t0;
                one.put("i", i + 1);
                one.put("ok", true);
                one.put("ms", ms);
                one.put("ip", addr.getHostAddress());
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
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
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

    /** 刷 access_token（Google refresh token 不轮换，不用写回 refresh） */
    public static synchronized boolean refresh(JSONObject acc) {
        try {
            String refresh = acc.optString("refresh", "");
            if (refresh.isEmpty()) return false;
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + enc(refresh)
                    + "&client_id=" + enc(CLIENT_ID)
                    + "&client_secret=" + enc(CLIENT_SECRET);
            JSONObject tok = postForm(TOKEN_URL, form);
            String access = tok.optString("access_token", "");
            if (access.isEmpty()) return false;
            long expiresIn = tok.optLong("expires_in", 3600);
            acc.put("access", access);
            acc.put("token", access);
            acc.put("expires", System.currentTimeMillis() + expiresIn * 1000L);
            if (tok.has("refresh_token") && !tok.optString("refresh_token", "").isEmpty()) {
                acc.put("refresh", tok.optString("refresh_token"));
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 保证 access 至少还有 3 分钟，必要时刷新；失败抛异常 */
    public static String ensureToken(JSONObject acc) throws Exception {
        String access = acc.optString("access", acc.optString("token", ""));
        long expires = acc.optLong("expires", 0L);
        boolean stale = access.isEmpty() || (expires > 0 && expires - System.currentTimeMillis() < 3 * 60 * 1000L);
        if (stale) {
            if (!refresh(acc)) throw new Exception("Antigravity 账号已掉线：refresh_token 刷新失败，请重新登录");
            access = acc.optString("access", "");
        }
        if (access.isEmpty()) throw new Exception("Antigravity 账号没有可用的 access_token");
        return access;
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
                throw new Exception("Code Assist " + r.status + ": " + shortErr(text));
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

    public static JSONArray fetchModels(JSONObject acc, String access, String project) throws Exception {
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
                res = postJson(chatBase + "/v1internal:fetchAvailableModels", access, b, USER_AGENT);
                if (!t.isEmpty()) acc.put("tier", t);
                break;
            } catch (Exception e) {
                lastErr = e;
                if (!t.isEmpty() && String.valueOf(e.getMessage()).contains("400")) {
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
        if (body == null) return false;
        String low = body.toLowerCase();
        if (status == 429 && (low.contains("resource has been exhausted") || low.contains("resource_exhausted"))) {
            return true;
        }
        return low.contains("resource has been exhausted") && low.contains("check quota");
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
