package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Anonymous, header-only checks against the fixed pre-login endpoint list. */
public final class ProviderEndpointProbe {
    private ProviderEndpointProbe() {}
    private static final long BUDGET_MS = 10000;
    private static final AtomicBoolean GLOBAL_BUSY = new AtomicBoolean();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(6, 6, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(), r -> { Thread t = new Thread(r, "provider-endpoint-probe"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private static final class Endpoint {
        final String role, label, url, method;
        Endpoint(String role, String label, String url, String method) { this.role=role; this.label=label; this.url=url; this.method=method; }
    }
    private static final Endpoint[] WB = {
        new Endpoint("website", "网页入口", "https://copilot.tencent.com/login?platform=CLI", "GET"),
        new Endpoint("auth", "认证服务", "https://copilot.tencent.com/v2/plugin/auth/state?platform=CLI", "OPTIONS"),
        new Endpoint("api", "模型 API", "https://copilot.tencent.com/v2/chat/completions", "OPTIONS") };
    private static final Endpoint[] RACCOON = {
        new Endpoint("website", "网页入口", "https://xiaohuanxiong.com/", "GET"),
        new Endpoint("auth", "登录后服务（模型目录，非独立认证验证）", "https://xiaohuanxiong.com/api/web/llm/v2/model_catalog", "GET"),
        new Endpoint("api", "模型 API", "https://xiaohuanxiong.com/api/web/llm/v2/chat/completions", "OPTIONS") };
    private static final Map<String, Endpoint[]> CATALOG;
    private static final Map<String, AtomicBoolean> BUSY;
    static {
        Map<String, Endpoint[]> catalog = new LinkedHashMap<>();
        catalog.put("workbuddy", WB);
        catalog.put("raccoon", RACCOON);
        catalog.put("workbuddy-ai", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://www.workbuddy.ai/profile/keys", "GET"),
            new Endpoint("api", "模型 API", "https://www.workbuddy.ai/v2/chat/completions", "OPTIONS"),
        });
        catalog.put("trae-cn", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://www.trae.cn/", "GET"),
            new Endpoint("auth", "认证服务", "https://api.trae.cn/trae/api/v3/oauth/ExchangeToken", "OPTIONS"),
            new Endpoint("api", "模型参数服务", "https://trae-api-cn.mchost.guru/api/ide/v1/get_detail_param", "OPTIONS"),
        });
        catalog.put("catpaw", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://catx.nocode.cn/", "GET"),
            new Endpoint("auth", "登录配置", "https://catx.nocode.cn/api/gateway/passport/login-config", "OPTIONS"),
            new Endpoint("api", "对话服务", "https://ai.catpaw.meituan.com/api/agent/conversation/turn", "OPTIONS"),
        });
        catalog.put("deepseek_web", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://chat.deepseek.com/sign_in", "GET"),
            new Endpoint("auth", "账号服务", "https://chat.deepseek.com/api/v0/users/current", "OPTIONS"),
            new Endpoint("api", "对话服务", "https://chat.deepseek.com/api/v0/chat/completion", "OPTIONS"),
        });
        catalog.put("zcode", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://zcode.z.ai/", "GET"),
            new Endpoint("auth", "认证服务", "https://zcode.z.ai/api/v1/oauth/cli/init", "OPTIONS"),
            new Endpoint("api", "Gift 消息服务", "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages", "OPTIONS"),
            new Endpoint("api", "国内 Coding 目录", "https://open.bigmodel.cn/api/coding/paas/v4/models", "OPTIONS"),
            new Endpoint("api", "国际 Coding 目录", "https://api.z.ai/api/coding/paas/v4/models", "OPTIONS"),
        });
        catalog.put("qoder_cn", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://qoder.cn/device/selectAccounts", "GET"),
            new Endpoint("auth", "认证服务", "https://openapi.qoder.com.cn/api/v1/deviceToken/poll", "OPTIONS"),
            new Endpoint("api", "模型目录", "https://gateway.qoder.com.cn/algo/api/v2/model/list?Encode=1", "OPTIONS"),
        });
        catalog.put("cline", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://app.cline.bot/", "GET"),
            new Endpoint("auth", "设备授权", "https://api.workos.com/user_management/authorize/device", "OPTIONS"),
            new Endpoint("api", "模型目录", "https://api.cline.bot/api/v1/ai/cline/models", "OPTIONS"),
        });
        catalog.put("codex", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://chatgpt.com/codex", "GET"),
            new Endpoint("auth", "认证服务", "https://auth.openai.com/oauth/authorize", "OPTIONS"),
            new Endpoint("api", "模型目录", "https://chatgpt.com/backend-api/codex/models", "OPTIONS"),
        });
        catalog.put("github-copilot", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://github.com/login/device", "GET"),
            new Endpoint("auth", "设备授权", "https://github.com/login/device/code", "OPTIONS"),
            new Endpoint("auth", "令牌服务", "https://api.github.com/copilot_internal/v2/token", "OPTIONS"),
            new Endpoint("api", "模型 API", "https://api.githubcopilot.com/chat/completions", "OPTIONS"),
        });
        catalog.put("minimax-code", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://agent.minimax.cn/", "GET"),
            new Endpoint("auth", "设备授权", "https://account.minimax.cn/oauth2/device/code", "OPTIONS"),
            new Endpoint("api", "消息服务", "https://agent.minimax.cn/mavis/api/v1/llm/v1/messages", "OPTIONS"),
        });
        catalog.put("antigravity", new Endpoint[] {
            new Endpoint("website", "网页入口", "https://antigravity.google/", "GET"),
            new Endpoint("auth", "授权服务", "https://accounts.google.com/o/oauth2/v2/auth", "OPTIONS"),
            new Endpoint("auth", "令牌服务", "https://oauth2.googleapis.com/token", "OPTIONS"),
            new Endpoint("api", "项目服务", "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", "OPTIONS"),
            new Endpoint("api", "模型服务", "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", "OPTIONS"),
        });
        catalog.put("deepseek", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://api.deepseek.com/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://api.deepseek.com/v1/chat/completions", "OPTIONS"),
        });
        catalog.put("siliconflow", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://api.siliconflow.cn/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://api.siliconflow.cn/v1/chat/completions", "OPTIONS"),
        });
        catalog.put("openai", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://api.openai.com/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://api.openai.com/v1/chat/completions", "OPTIONS"),
        });
        catalog.put("anthropic", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://api.anthropic.com/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://api.anthropic.com/v1/messages", "OPTIONS"),
        });
        catalog.put("openrouter", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://openrouter.ai/api/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://openrouter.ai/api/v1/chat/completions", "OPTIONS"),
        });
        catalog.put("tencent", new Endpoint[] {
            new Endpoint("api", "模型目录", "https://api.hunyuan.cloud.tencent.com/v1/models", "OPTIONS"),
            new Endpoint("api", "推理服务", "https://api.hunyuan.cloud.tencent.com/v1/chat/completions", "OPTIONS"),
        });
        catalog.put("loomy", new Endpoint[] {
            new Endpoint("website", "网页入口（登录未支持，需手动 session）", "https://loomyad.xunfei.cn/", "GET"),
            new Endpoint("api", "模型 API", "https://loomyad.xunfei.cn/api/v1/chat/completions", "OPTIONS"),
        });
        CATALOG = Collections.unmodifiableMap(catalog);
        Map<String, AtomicBoolean> busy = new LinkedHashMap<>();
        for (String id : catalog.keySet()) busy.put(id, new AtomicBoolean());
        BUSY = Collections.unmodifiableMap(busy);
    }
    public static boolean supports(String provider) { return provider != null && CATALOG.containsKey(provider); }
    public static String[] supportedProviders() { return CATALOG.keySet().toArray(new String[0]); }

    public static JSONObject probe(String provider, NetworkPolicy.Snapshot network) {
        if (!supports(provider)) return error("不支持的供应商", false);
        if (network == null) return error("缺少网络配置快照", false);
        AtomicBoolean busy = BUSY.get(provider);
        if (!busy.compareAndSet(false, true)) return error("该供应商的网络诊断正在进行", true);
        boolean acquired = false;
        Task[] tasks = null;
        Future<?>[] futures = null;
        try {
            if (!GLOBAL_BUSY.compareAndSet(false, true)) return error("诊断资源繁忙，请稍后重试", true);
            acquired = true;
            Endpoint[] endpoints = CATALOG.get(provider);
            int count = endpoints.length;
            long checkedAt = System.currentTimeMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS);
            tasks = new Task[count];
            @SuppressWarnings("unchecked") Future<JSONObject>[] submitted = new Future[count];
            futures = submitted;
            JSONObject[] rows = new JSONObject[count];
            boolean blocked = "blocked".equals(network.mode);
            for (int i=0; i<count; i++) {
                if (blocked) {
                    rows[i] = row(endpoints[i], 0, 0, "policy", "网络策略阻止了连接");
                    continue;
                }
                tasks[i] = new Task(endpoints[i], network, deadline);
                try { submitted[i] = WORKERS.submit(tasks[i]); }
                catch (RejectedExecutionException e) { rows[i] = row(endpoints[i], 0, 0, "timeout", "诊断资源繁忙，请稍后重试"); }
            }
            for (int i=0; i<count; i++) {
                if (submitted[i] == null) continue;
                try {
                    long left = deadline - System.nanoTime();
                    if (left <= 0 && !submitted[i].isDone()) throw new TimeoutException();
                    rows[i] = submitted[i].get(Math.max(0, left), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rows[i] = row(endpoints[i], 0, BUDGET_MS, "timeout", "诊断已中断");
                } catch (TimeoutException e) {
                    rows[i] = row(endpoints[i], 0, BUDGET_MS, "timeout", "诊断超时");
                } catch (ExecutionException | CancellationException e) {
                    rows[i] = row(endpoints[i], 0, 0, "network", "网络连接失败");
                }
            }
            JSONArray results = new JSONArray(); int reached=0;
            for (JSONObject row : rows) { results.put(row); if (row.optBoolean("reachable")) reached++; }
            JSONObject result = new JSONObject();
            result.put("ok", true); result.put("provider", provider); result.put("networkMode", network.mode);
            result.put("scope", "official-defaults");
            result.put("scopeNote", "探测官方固定入口，不代表自定义地址、地区或动态项目的可用性");
            result.put("verdict", reached == count ? "reachable" : reached == 0 ? "unreachable" : "partial");
            result.put("summary", count + " 个端点中 " + reached + " 个收到 HTTP 响应");
            result.put("checkedAt", checkedAt); result.put("results", results);
            return result;
        } catch (Exception e) { return error("无法完成网络诊断", false); }
        finally {
            if (tasks != null) for (Task task : tasks) if (task != null) task.cancel();
            if (futures != null) for (Future<?> future : futures) if (future != null && !future.isDone()) future.cancel(true);
            if (acquired) GLOBAL_BUSY.set(false);
            busy.set(false);
        }
    }
    private static JSONObject error(String message, boolean busy) {
        JSONObject o = new JSONObject(); try { o.put("ok", false); if (busy) o.put("busy", true); o.put("error", message); } catch (Exception ignored) {} return o;
    }
    private static JSONObject row(Endpoint e, int status, long ms, String kind, String note) {
        JSONObject o = new JSONObject();
        try {
            o.put("role", e.role); o.put("label", e.label); o.put("url", e.url); o.put("method", e.method);
            o.put("httpStatus", status); o.put("ms", ms); o.put("reachable", status >= 100); o.put("kind", kind); o.put("note", note);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
        return o;
    }
    static String statusKind(int status) {
        if (status>=300 && status<400) return "redirect";
        if (status==401) return "auth_required";
        if (status==403) return "restricted";
        if (status==404 || status==405) return "unverified";
        if (status==429) return "rate_limited";
        if (status>=500 && status<600) return "server_error";
        return "response";
    }
    private static final class Task implements Callable<JSONObject> {
        final Endpoint endpoint; final NetworkPolicy.Snapshot network; final Transport transport;
        Task(Endpoint endpoint, NetworkPolicy.Snapshot network, long deadline) {
            this.endpoint=endpoint; this.network=network; this.transport=new Transport(deadline);
        }
        void cancel() { transport.close(); }
        public JSONObject call() {
            long start=System.nanoTime();
            try {
                int status=transport.request(network, endpoint.url, endpoint.method);
                String kind=statusKind(status), note;
                switch (kind) {
                    case "redirect": note="收到重定向，未跟随"; break;
                    case "auth_required": note="服务要求认证"; break;
                    case "restricted": note="服务拒绝访问"; break;
                    case "unverified": note="收到 HTTP 响应，端点或方法可用性未验证"; break;
                    case "rate_limited": note="服务限制请求频率"; break;
                    case "server_error": note="服务返回服务器错误"; break;
                    default: note="收到 HTTP 响应";
                }
                return row(endpoint, status, elapsed(start), kind, note);
            } catch (UnknownHostException e) { return row(endpoint, 0, elapsed(start), "dns", "域名解析失败"); }
            catch (SSLException e) { return row(endpoint, 0, elapsed(start), "tls", "TLS 连接失败"); }
            catch (SocketTimeoutException e) { return row(endpoint, 0, elapsed(start), "timeout", "网络连接超时"); }
            catch (ConnectException e) { return row(endpoint, 0, elapsed(start), "connection", "无法建立连接"); }
            catch (Exception e) { return row(endpoint, 0, elapsed(start), "network", "网络连接失败"); }
            finally { transport.close(); }
        }
        private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
    }

    /** Only private caller supplies fixed endpoints; no configurable destination or credential headers. */
    private static final class Transport implements Closeable {
        private final long deadline;
        private Socket socket;
        private boolean closed;
        Transport(long deadline) { this.deadline=deadline; }
        private int remaining() throws SocketTimeoutException {
            long n=deadline-System.nanoTime();
            if (n<=0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException("deadline");
            return (int)Math.max(1, Math.min(4000L, TimeUnit.NANOSECONDS.toMillis(n)+1));
        }
        private synchronized void register(Socket s) throws IOException {
            if (closed) { s.close(); throw new IOException("closed"); }
            socket=s;
        }
        int request(NetworkPolicy.Snapshot network, String url, String method) throws IOException {
            URI uri=URI.create(url);
            byte[] request=requestBytes(uri,method);
            Socket plain=network.connectTunnel(uri.getHost(),443,remaining(),remaining());
            register(plain);
            SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,uri.getHost(),443,true);
            register(tls);
            SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(params);
            if (!"HTTPS".equals(tls.getSSLParameters().getEndpointIdentificationAlgorithm())) throw new SSLException("hostname verification unavailable");
            tls.setSoTimeout(remaining()); tls.startHandshake();
            tls.getOutputStream().write(request); tls.getOutputStream().flush();
            InputStream raw=tls.getInputStream();
            return readStatus(new FilterInputStream(raw) {
                @Override public int read() throws IOException { tls.setSoTimeout(remaining()); return in.read(); }
            });
        }
        public void close() {
            Socket s; synchronized(this) { closed=true; s=socket; socket=null; }
            if (s!=null) try { s.close(); } catch (IOException ignored) {}
        }
    }
    // Package-private pure functions enable synthetic tests without a configurable production transport.
    static byte[] requestBytes(URI uri, String method) throws IOException {
        String host=uri.getHost(), path=uri.getRawPath(), query=uri.getRawQuery();
        if (!"https".equals(uri.getScheme()) || host==null || uri.getPort()!=-1 || uri.getRawUserInfo()!=null ||
                uri.getRawFragment()!=null || path==null || !path.startsWith("/") || !uri.normalize().equals(uri) ||
                !uri.toASCIIString().equals(uri.toString()) || !("GET".equals(method) || "OPTIONS".equals(method)))
            throw new IOException("invalid request");
        String target=path+(query == null ? "" : "?"+query);
        return (method+" "+target+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }
    static int readStatus(InputStream in) throws IOException {
        int[] budget={16384}; int informational=0;
        while (true) {
            String status=readLine(in,budget,1024);
            if (!status.matches("HTTP/1\\.[01] [1-5][0-9][0-9](?: [\\x20-\\x7e]*)?")) throw new IOException("invalid status");
            int code=Integer.parseInt(status.substring(9,12));
            while (true) {
                String header=readLine(in,budget,4096);
                if (header.isEmpty()) break;
                int colon=header.indexOf(':');
                if (colon<=0 || !header.substring(0,colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("invalid header");
            }
            if (code==101) throw new IOException("protocol upgrade");
            if (code>=200) return code;
            if (++informational>3) throw new IOException("too many informational responses");
        }
    }
    private static String readLine(InputStream in, int[] budget, int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while (true) {
            int b=in.read(); if (b<0 || --budget[0]<0) throw new IOException("incomplete headers");
            if (b=='\r') { int next=in.read(); if (next<0 || --budget[0]<0 || next!='\n') throw new IOException("invalid line ending"); return new String(out.toByteArray(),StandardCharsets.ISO_8859_1); }
            if (b=='\n' || b==127 || (b<32 && b!='\t') || out.size()>=max) throw new IOException("invalid line");
            out.write(b);
        }
    }
}
