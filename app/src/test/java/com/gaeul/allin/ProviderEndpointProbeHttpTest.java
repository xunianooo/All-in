package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic header parsing plus a local, non-TLS proxy for cancellation and policy checks. */
public final class ProviderEndpointProbeHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)); }
    static void rejects(String s) throws Exception {
        try { ProviderEndpointProbe.readStatus(input(s)); throw new AssertionError("accepted invalid response"); }
        catch (IOException rejectedRequest) {}
    }
    public static void main(String[] args) throws Exception {
        check(ProviderEndpointProbe.supports("workbuddy") && ProviderEndpointProbe.supports("raccoon"),"supported");
        check(!ProviderEndpointProbe.supports("https://example.com") && !ProviderEndpointProbe.probe("https://example.com",NetworkPolicy.snapshot()).getBoolean("ok"),"allowlist");
        Map<String, String[]> expected=new LinkedHashMap<>();
        expected.put("workbuddy", new String[]{"GET https://copilot.tencent.com/login?platform=CLI", "OPTIONS https://copilot.tencent.com/v2/plugin/auth/state?platform=CLI", "OPTIONS https://copilot.tencent.com/v2/chat/completions"});
        expected.put("raccoon", new String[]{"GET https://xiaohuanxiong.com/", "GET https://xiaohuanxiong.com/api/web/llm/v2/model_catalog", "OPTIONS https://xiaohuanxiong.com/api/web/llm/v2/chat/completions"});
        expected.put("workbuddy-ai", new String[]{"GET https://www.workbuddy.ai/profile/keys", "OPTIONS https://www.workbuddy.ai/v2/chat/completions"});
        expected.put("trae-cn", new String[]{"GET https://www.trae.cn/", "OPTIONS https://api.trae.cn/trae/api/v3/oauth/ExchangeToken", "OPTIONS https://trae-api-cn.mchost.guru/api/ide/v1/get_detail_param"});
        expected.put("catpaw", new String[]{"GET https://catx.nocode.cn/", "OPTIONS https://catx.nocode.cn/api/gateway/passport/login-config", "OPTIONS https://ai.catpaw.meituan.com/api/agent/conversation/turn"});
        expected.put("deepseek_web", new String[]{"GET https://chat.deepseek.com/sign_in", "OPTIONS https://chat.deepseek.com/api/v0/users/current", "OPTIONS https://chat.deepseek.com/api/v0/chat/completion"});
        expected.put("zcode", new String[]{"GET https://zcode.z.ai/", "OPTIONS https://zcode.z.ai/api/v1/oauth/cli/init", "OPTIONS https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages", "OPTIONS https://open.bigmodel.cn/api/coding/paas/v4/models", "OPTIONS https://api.z.ai/api/coding/paas/v4/models"});
        expected.put("qoder_cn", new String[]{"GET https://qoder.cn/device/selectAccounts", "OPTIONS https://openapi.qoder.com.cn/api/v1/deviceToken/poll", "OPTIONS https://gateway.qoder.com.cn/algo/api/v2/model/list?Encode=1"});
        expected.put("cline", new String[]{"GET https://app.cline.bot/", "OPTIONS https://api.workos.com/user_management/authorize/device", "OPTIONS https://api.cline.bot/api/v1/ai/cline/models"});
        expected.put("codex", new String[]{"GET https://chatgpt.com/codex", "OPTIONS https://auth.openai.com/oauth/authorize", "OPTIONS https://chatgpt.com/backend-api/codex/models"});
        expected.put("github-copilot", new String[]{"GET https://github.com/login/device", "OPTIONS https://github.com/login/device/code", "OPTIONS https://api.github.com/copilot_internal/v2/token", "OPTIONS https://api.githubcopilot.com/chat/completions"});
        expected.put("minimax-code", new String[]{"GET https://agent.minimax.cn/", "OPTIONS https://account.minimax.cn/oauth2/device/code", "OPTIONS https://agent.minimax.cn/mavis/api/v1/llm/v1/messages"});
        expected.put("antigravity", new String[]{"GET https://antigravity.google/", "OPTIONS https://accounts.google.com/o/oauth2/v2/auth", "OPTIONS https://oauth2.googleapis.com/token", "OPTIONS https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", "OPTIONS https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"});
        expected.put("deepseek", new String[]{"OPTIONS https://api.deepseek.com/v1/models", "OPTIONS https://api.deepseek.com/v1/chat/completions"});
        expected.put("siliconflow", new String[]{"OPTIONS https://api.siliconflow.cn/v1/models", "OPTIONS https://api.siliconflow.cn/v1/chat/completions"});
        expected.put("openai", new String[]{"OPTIONS https://api.openai.com/v1/models", "OPTIONS https://api.openai.com/v1/chat/completions"});
        expected.put("anthropic", new String[]{"OPTIONS https://api.anthropic.com/v1/models", "OPTIONS https://api.anthropic.com/v1/messages"});
        expected.put("openrouter", new String[]{"OPTIONS https://openrouter.ai/api/v1/models", "OPTIONS https://openrouter.ai/api/v1/chat/completions"});
        expected.put("tencent", new String[]{"OPTIONS https://api.hunyuan.cloud.tencent.com/v1/models", "OPTIONS https://api.hunyuan.cloud.tencent.com/v1/chat/completions"});
        expected.put("loomy", new String[]{"GET https://loomyad.xunfei.cn/", "OPTIONS https://loomyad.xunfei.cn/api/v1/chat/completions"});
        String[] supported=ProviderEndpointProbe.supportedProviders();
        check(supported.length==20 && new HashSet<>(Arrays.asList(supported)).equals(expected.keySet()), "fixed provider catalog");
        supported[0]="changed";
        check(ProviderEndpointProbe.supports("workbuddy") && !ProviderEndpointProbe.supports("changed"), "catalog defensive copy");
        check(!ProviderEndpointProbe.supports(null) && !ProviderEndpointProbe.supports("placeholder"), "no placeholder");
        NetworkPolicy.block();
        NetworkPolicy.Snapshot prior=NetworkPolicy.snapshot();
        NetworkPolicy.Snapshot blockedSnapshot=prior;
        for (Map.Entry<String,String[]> entry : expected.entrySet()) {
            check(ProviderEndpointProbe.supports(entry.getKey()), "supported " + entry.getKey());
            JSONObject probe=ProviderEndpointProbe.probe(entry.getKey(),blockedSnapshot);
            check(probe.getBoolean("ok") && "official-defaults".equals(probe.getString("scope")) &&
                    probe.getString("scopeNote").contains("自定义地址") && probe.getString("scopeNote").contains("动态项目"), "scope");
            check(probe.getJSONArray("results").length()==entry.getValue().length, "count " + entry.getKey());
            check(probe.getString("summary").startsWith(entry.getValue().length + " 个端点"), "summary count");
            for (int i=0; i<entry.getValue().length; i++) {
                JSONObject row=probe.getJSONArray("results").getJSONObject(i);
                String method=row.getString("method"), url=row.getString("url");
                check((method+" "+url).equals(entry.getValue()[i]), "fixed endpoint " + entry.getKey());
                URI uri=new URI(url);
                check("https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null &&
                        ("GET".equals(method) || "OPTIONS".equals(method)), "safe endpoint");
                ProviderEndpointProbe.requestBytes(uri,method);
                check("policy".equals(row.getString("kind")) && !row.getBoolean("reachable"), "blocked");
            }
        }
        check(NetworkPolicy.snapshot()==prior, "probe did not alter global network policy");
        int[] codes={200,302,401,403,404,405,429,503};
        String[] kinds={"response","redirect","auth_required","restricted","unverified","unverified","rate_limited","server_error"};
        for (int i=0;i<codes.length;i++) {
            int code=ProviderEndpointProbe.readStatus(input("HTTP/1.1 "+codes[i]+" result\r\nLocation: secret\r\n\r\nBODY"));
            check(code==codes[i] && kinds[i].equals(ProviderEndpointProbe.statusKind(code)),"classification");
        }
        ByteArrayInputStream body=(ByteArrayInputStream)input("HTTP/1.0 100 Continue\r\n\r\nHTTP/1.1 204\r\nX: y\r\n\r\nBODY");
        check(ProviderEndpointProbe.readStatus(body)==204 && body.available()==4,"informational/body");
        byte[] high="HTTP/1.1 200 OK\r\nX-Text: caf\u00e9\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        check(ProviderEndpointProbe.readStatus(new ByteArrayInputStream(high))==200,"valid high byte in header value");
        rejects("HTTP/1.1 101 Switching\r\n\r\n");
        rejects("HTTP/1.1 100\r\n\r\nHTTP/1.1 100\r\n\r\nHTTP/1.1 100\r\n\r\nHTTP/1.1 100\r\n\r\nHTTP/1.1 200\r\n\r\n");
        rejects("HTTP/1.1 200\n\n"); rejects("HTTP/1.1 600 nope\r\n\r\n"); rejects("HTTP/1.1 200\r\nBad header\r\n\r\n");
        StringBuilder huge=new StringBuilder("HTTP/1.1 200\r\nX: "); for(int i=0;i<4100;i++) huge.append('a'); huge.append("\r\n\r\n"); rejects(huge.toString());
        String request=new String(ProviderEndpointProbe.requestBytes(new URI("https://copilot.tencent.com/login?platform=CLI"),"GET"),StandardCharsets.US_ASCII);
        check(request.equals("GET /login?platform=CLI HTTP/1.1\r\nHost: copilot.tencent.com\r\nConnection: close\r\n\r\n"),"request");
        check(new String(ProviderEndpointProbe.requestBytes(new URI("https://xiaohuanxiong.com/api/web/llm/v2/chat/completions"),"OPTIONS"),StandardCharsets.US_ASCII).startsWith("OPTIONS /api/"),"options");
        try { ProviderEndpointProbe.requestBytes(new URI("https://example.com/"),"POST"); throw new AssertionError("POST accepted"); } catch (IOException rejectedRequest) {}
        NetworkPolicy.block(); JSONObject blocked=ProviderEndpointProbe.probe("raccoon",NetworkPolicy.snapshot());
        check(blocked.getBoolean("ok") && "policy".equals(blocked.getJSONArray("results").getJSONObject(0).getString("kind")),"policy");
        // The proxy deliberately holds CONNECT headers to exercise single-flight and total deadline cancellation.
        ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        CountDownLatch accepted=new CountDownLatch(3); Socket[] clients=new Socket[3];
        Thread listener=new Thread(() -> { try { for(int i=0;i<3;i++){ clients[i]=server.accept(); accepted.countDown(); } } catch(IOException ignored) {} }); listener.setDaemon(true); listener.start();
        NetworkPolicy.Snapshot proxy=NetworkPolicy.parse(new JSONObject().put("mode","http_proxy").put("host","127.0.0.1").put("port",server.getLocalPort()));
        ExecutorService executor=Executors.newSingleThreadExecutor(); long start=System.nanoTime();
        Future<JSONObject> future=executor.submit(() -> ProviderEndpointProbe.probe("workbuddy",proxy));
        check(accepted.await(3,TimeUnit.SECONDS),"proxy connections");
        check(ProviderEndpointProbe.probe("workbuddy",proxy).optBoolean("busy"),"busy");
        check(ProviderEndpointProbe.probe("zcode",proxy).optBoolean("busy"),"global batch busy");
        JSONObject result=future.get(12,TimeUnit.SECONDS); long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        check(result.getBoolean("ok") && elapsed<11000 && result.getString("networkMode").equals("http_proxy"),"deadline and snapshot: "+elapsed);
        for (Socket c : clients) if(c!=null) c.close(); server.close(); executor.shutdownNow();
        JSONObject after=ProviderEndpointProbe.probe("workbuddy",NetworkPolicy.snapshot()); check(after.getBoolean("ok"),"busy cleaned");
        System.out.println("ProviderEndpointProbeHttpTest OK");
    }
}
