package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loopback contracts for the provider response wiring that has no pure-function home:
 * Raccoon's sign-in header and model rewrite (JSON and SSE), WorkBuddy's filtered refusal,
 * and Cline's refresh grace. Both sides are synthetic; nothing leaves 127.0.0.1.
 */
public final class ProviderParityWiringHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static final class Reply { int status; String body; Map<String,String> headers=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER); }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int n;
            while ((n = i.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    static Reply post(VendorPolicyGatewayHttpTest.Gateway g, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer fixture-auth");
        c.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        Reply r = new Reply();
        r.status = c.getResponseCode();
        r.body = read(r.status >= 400 ? c.getErrorStream() : c.getInputStream());
        for (Map.Entry<String,List<String>> e : c.getHeaderFields().entrySet())
            if (e.getKey() != null) for (String v : e.getValue()) r.headers.put(e.getKey(), v);
        c.disconnect();
        return r;
    }

    static Reply request(VendorPolicyGatewayHttpTest.Gateway g, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(20000); c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer fixture-auth");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        Reply r = new Reply();
        r.status = c.getResponseCode();
        r.body = read(r.status >= 400 ? c.getErrorStream() : c.getInputStream());
        for (Map.Entry<String,List<String>> e : c.getHeaderFields().entrySet())
            if (e.getKey() != null) for (String v : e.getValue()) r.headers.put(e.getKey(), v);
        c.disconnect();
        return r;
    }

    static JSONObject chat(String model) {
        return new JSONObject().put("model", model).put("stream", false).put("max_tokens", 100)
                .put("messages", new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
    }

    // ---------------- Raccoon ----------------

    static JSONObject raccoonProvider(String model, JSONObject account) {
        return new JSONObject().put("id","raccoon").put("name","Raccoon").put("protocol","openai")
                .put("baseUrl","https://xiaohuanxiong.com")
                .put("accounts", account == null ? new JSONArray() : new JSONArray().put(account))
                .put("models", new JSONArray().put(model));
    }

    static JSONObject raccoonAccount(String access, String refresh) {
        return new JSONObject().put("id","acc").put("enabled",true).put("type","oauth").put("source","browser")
                .put("name","fixture").put("access",access).put("token",access).put("refresh",refresh)
                .put("expires", System.currentTimeMillis()+3600000L);
    }

    static void raccoonJson(String upstreamModel,String requestedModel,String clientModel) throws Exception {
        String reply = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\""+upstreamModel+"\",\"choices\":[{\"index\":0,"
                +"\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",reply))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(raccoonProvider(upstreamModel, raccoonAccount("fixture-access","r1")));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = post(g, "/v1/chat/completions", chat(clientModel));
                check(r.status == 200, "raccoon JSON status " + r.body);
                check("kept".equals(r.headers.get("X-Magpie-Sign-In")), "raccoon kept: " + r.headers.get("X-Magpie-Sign-In"));
                JSONObject body = new JSONObject(r.body);
                check(requestedModel.equals(body.optString("model")), "raccoon reports the client's model: " + body.optString("model"));
                check("ok".equals(body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")),
                        "raccoon content untouched");
                check("glm-upstream".equals(up.requests.get(0).body.optString("model")), "upstream still gets its own model");
                check(up.requests.get(0).path.equals("/api/web/llm/v2/chat/completions"), "raccoon chat path");
            }
        }
    }

    static void raccoonJsonWithoutModel() throws Exception {
        String reply = "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                +"\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",reply))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(raccoonProvider("raccoon-model", null));
            // A key-only provider: no account, so a 401 cannot be replayed.
            cfg.getJSONArray("providers").getJSONObject(0).put("keys", new JSONArray().put(VendorPolicyGatewayHttpTest.KEY));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = post(g, "/v1/chat/completions", chat("raccoon/raccoon-model"));
                check(r.status == 200, "raccoon key-only status " + r.body);
                check(!new JSONObject(r.body).has("model"), "no model key means no invented model");
                check("kept".equals(r.headers.get("X-Magpie-Sign-In")), "key-only kept");
            }
        }
    }

    static void raccoonStream() throws Exception {
        String sse = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"glm-upstream\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"he\"},\"finish_reason\":null}]}\n\n"
                + ": comment line passes through\n\n"
                + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"glm-upstream\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"glm-upstream\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",sse))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(raccoonProvider("glm-upstream", raccoonAccount("fixture-access","r1")));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                JSONObject body = chat("raccoon/glm-upstream").put("stream", true);
                Reply r = post(g, "/v1/chat/completions", body);
                check(r.status == 200, "raccoon SSE status " + r.body);
                check("kept".equals(r.headers.get("X-Magpie-Sign-In")), "raccoon SSE kept");
                check(!r.body.contains("\"model\":\"glm-upstream\""), "every frame names the client's model: " + r.body);
                check(r.body.contains("\"model\":\"raccoon/glm-upstream\""), "client model inside frames: " + r.body);
                check(r.body.contains("\"content\":\"he\"") && r.body.contains("\"content\":\"llo\""), "content streamed through");
                check(r.body.contains("[DONE]"), "done frame retained");
            }
        }
    }

    static void raccoonExpiredThenRenewed() throws Exception {
        // 1) The first exchange is rejected and no account can rotate the token: expired.
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(401,"application/json","{\"error\":{\"message\":\"nope\"}}"))) {
            JSONObject provider = raccoonProvider("glm-upstream", null);
            provider.put("keys", new JSONArray().put(VendorPolicyGatewayHttpTest.KEY));
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(provider);
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = post(g, "/v1/chat/completions", chat("raccoon/glm-upstream"));
                check(r.status == 401, "raccoon 401 passthrough " + r.status + " " + r.body);
                check("expired".equals(r.headers.get("X-Magpie-Sign-In")), "401 is expired: " + r.headers.get("X-Magpie-Sign-In"));
            }
        }
        // 2) The account holds a refresh credential: 401 rotates once, the replay is renewed.
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream((request,n) ->
                n == 1 ? new VendorPolicyGatewayHttpTest.Reply(401,"application/json","{\"error\":{\"message\":\"expired\"}}")
                        : new VendorPolicyGatewayHttpTest.Reply(200,"application/json",
                        "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"glm-upstream\",\"choices\":[{\"index\":0,"
                        +"\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(raccoonProvider("glm-upstream", raccoonAccount("old-access","r1")));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                g.gateway.setRaccoonRefreshTransportForTests((path, body) -> {
                    check(RaccoonRefreshService.REFRESH_PATH.equals(path), "refresh path " + path);
                    check("r1".equals(body.optString("refresh_token")), "refresh token sent");
                    return new RaccoonRefreshService.Response(200, new JSONObject().put("code",0)
                            .put("data", new JSONObject().put("access_token","new-access")
                                    .put("refresh_token","r2").put("expires_in",3600)));
                });
                Reply r = post(g, "/v1/chat/completions", chat("raccoon/glm-upstream"));
                check(r.status == 200, "renewed replay status " + r.status + " " + r.body);
                check("renewed".equals(r.headers.get("X-Magpie-Sign-In")), "renewed header: " + r.headers.get("X-Magpie-Sign-In"));
                check(up.requests.size() == 2, "exactly one replay " + up.requests.size());
                check("Bearer old-access".equals(up.requests.get(0).headers.get("Authorization")), "first send uses the expired token");
                check("Bearer new-access".equals(up.requests.get(1).headers.get("Authorization")), "replay uses the rotated token");
                check("raccoon/glm-upstream".equals(new JSONObject(r.body).optString("model")), "renewed body names the client's model");
            }
        }
    }

    // ---------------- WorkBuddy ----------------

    static String chunk(String delta,String finish) {
        return "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"+delta
                +",\"finish_reason\":"+(finish==null?"null":"\""+finish+"\"")+"}]}\n\n";
    }

    static void workbuddy(String providerId,String content,String finish,boolean expectEmpty) throws Exception {
        String sse = chunk("{\"role\":\"assistant\",\"content\":"+JSONObject.quote(content)+"}",null)
                + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":0,\"total_tokens\":12}}\n\n"
                + "data: [DONE]\n\n";
        String host = "workbuddy".equals(providerId) ? "https://copilot.tencent.com/v2" : "https://www.workbuddy.ai/v2";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",sse))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(
                    VendorPolicyGatewayHttpTest.provider(providerId,"openai",host,"model"));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, true)) {
                Reply r = post(g, "/v1/chat/completions", chat(providerId+"/model"));
                check(r.status == 200, providerId+" aggregate status " + r.body);
                JSONObject choice = new JSONObject(r.body).getJSONArray("choices").getJSONObject(0);
                String shown = choice.getJSONObject("message").optString("content","");
                if (expectEmpty) {
                    check(shown.isEmpty(), providerId+" refusal text is not an answer: "+shown);
                    check("content_filter".equals(choice.optString("finish_reason")), "finish stays content_filter");
                } else {
                    check(shown.equals(content), providerId+" ordinary reply kept: "+shown);
                }
                check(new JSONObject(r.body).getJSONObject("usage").getInt("prompt_tokens") == 12,
                        providerId+" upstream usage reaches the client: "+r.body);
            }
        }
    }

    static void workbuddyRefusal() throws Exception {
        String says = "Sorry, I can't respond to this question.";
        for (String id : new String[]{"workbuddy","workbuddy-ai"}) {
            workbuddy(id, says, "content_filter", true);
            // Padded exactly as copilot.tencent.com sends it, and with a curly apostrophe.
            workbuddy(id, "\n"+says.replace('\'','\u2019')+"\n", "content_filter", true);
            // The same words are an ordinary answer when the filter did not end the reply.
            workbuddy(id, says, "stop", false);
            // Any other text is never touched.
            workbuddy(id, "here is the answer", "content_filter", false);
        }
    }

    // ---------------- Cline ----------------

    static void cline(String model, long expiresIn, IOException refreshFailure, boolean expectDispatch) throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",VendorPolicyGatewayHttpTest.CHAT))) {
            JSONObject account = new JSONObject().put("id","acc").put("enabled",true).put("type","oauth")
                    .put("token","old-token").put("access","old-token").put("refresh","r1")
                    .put("expires", System.currentTimeMillis()+expiresIn);
            JSONObject provider = new JSONObject().put("id","cline").put("protocol","openai")
                    .put("baseUrl","https://api.cline.bot/api/v1").put("models",new JSONArray().put(model))
                    .put("accounts", new JSONArray().put(account));
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(provider);
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                if (refreshFailure != null) g.gateway.setClineRefreshForTests(token -> { throw refreshFailure; });
                else throw new AssertionError("this fixture always fails its refresh");
                Reply r = post(g, "/v1/chat/completions", chat("cline/"+model));
                if (!expectDispatch) {
                    check(r.status >= 400, "no usable credential is a failure, got "+r.status+" "+r.body);
                    check(up.requests.isEmpty(), "an unusable route never reaches the upstream");
                    return;
                }
                check(r.status == 200, "grace keeps the token: "+r.status+" "+r.body);
                check(up.requests.size() == 1, "one upstream attempt "+up.requests.size());
                check("Bearer workos:old-token".equals(up.requests.get(0).headers.get("Authorization")),
                        "held token used: "+up.requests.get(0).headers.get("Authorization"));
            }
        }
    }

    static void clineGrace() throws Exception {
        // Within the 5 minute refresh margin, but more than 30 seconds of life left.
        cline("cline-pass/deepseek-v4.1-flash", 120000L, new IOException("Cline token refresh failed (HTTP 503)"), true);
        // Under 30 seconds left the plugin stops using it too.
        cline("cline-pass/deepseek-v4.1-flash", 20000L, new IOException("Cline token refresh failed (HTTP 503)"), false);
    }

    // ---------------- Loomy draws ----------------

    static JSONObject loomyProvider(String token) {
        JSONArray accounts = token == null ? new JSONArray() : new JSONArray().put(new JSONObject()
                .put("id","loomy-account").put("enabled",true).put("token",token));
        return new JSONObject().put("id","loomy").put("name","Loomy").put("protocol","openai")
                .put("baseUrl","https://loomyad.xunfei.cn/api/v1")
                .put("accounts",accounts)
                .put("models",new JSONArray().put("loomy-chat").put("draw-1"));
    }

    static JSONObject draw(String model) {
        return new JSONObject().put("model",model).put("prompt","a red circle").put("n",1);
    }

    static void loomyDrawPassThrough() throws Exception {
        String answer = "{\"created\":1700000000,\"data\":[{\"url\":\"https://cdn.example/a.png\"}],\"points_consumed\":110}";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",answer))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(loomyProvider("loomy-session"));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                JSONObject body = draw("loomy/draw-1");
                Reply r = request(g, "POST", "/v1/images/generations", body);
                check(r.status == 200, "draw status " + r.status + " " + r.body);
                check(answer.equals(r.body), "draw answer passes through verbatim: " + r.body);
                check(up.requests.size() == 1, "one draw request");
                VendorPolicyGatewayHttpTest.Request sent = up.requests.get(0);
                check("/api/v1/images/generations".equals(sent.path), "draw path " + sent.path);
                check("loomy-session".equals(sent.headers.get("token")), "session header");
                check("Bearer loomy-session".equals(sent.headers.get("Authorization")), "bearer header");
                check("application/json;charset=utf-8".equals(sent.headers.get("Content-Type")), "draw content type");
                check("draw-1".equals(sent.body.optString("model")), "route prefix stripped for the upstream: " + sent.body);
                check("a red circle".equals(sent.body.optString("prompt")), "prompt verbatim");
            }
        }
    }

    static void loomyDrawGuards() throws Exception {
        String answer = "{\"created\":1,\"data\":[]}";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",answer))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(loomyProvider("loomy-session"));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = request(g, "GET", "/v1/images/generations", null);
                check(r.status == 405, "draw needs POST: " + r.status);
                r = request(g, "POST", "/v1/images/generations", draw("openai/gpt-image-1"));
                check(r.status == 400, "another provider's model is refused: " + r.status + " " + r.body);
                check(up.requests.isEmpty(), "a refused draw never reaches Loomy");
            }
        }
        // No sign-in at all.
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",answer))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(loomyProvider(null));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = request(g, "POST", "/v1/images/generations", draw("draw-1"));
                check(r.status == 401, "missing sign-in is 401: " + r.status + " " + r.body);
                check(up.requests.isEmpty(), "no upstream without a session");
            }
        }
        // The upstream's own refusal: a dead session becomes 401, and a 5xx never leaks the token.
        for (int status : new int[]{401,403}) {
            try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                    (request,n) -> new VendorPolicyGatewayHttpTest.Reply(status,"application/json","{\"error\":\"dead\"}"))) {
                JSONObject cfg = VendorPolicyGatewayHttpTest.config(loomyProvider("loomy-session"));
                try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                    Reply r = request(g, "POST", "/v1/images/generations", draw("draw-1"));
                    check(r.status == 401, "expired sign-in maps to 401: " + r.status + " " + r.body);
                    check(r.body.contains("sign in again"), "message asks for a new sign-in: " + r.body);
                }
            }
        }
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(500,"application/json",
                "{\"error\":\"upstream failed for loomy-session\"}"))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(loomyProvider("loomy-session"));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = request(g, "POST", "/v1/images/generations", draw("draw-1"));
                check(r.status == 500, "upstream failure keeps its status: " + r.status + " " + r.body);
                check(!r.body.contains("loomy-session"), "the session never leaks: " + r.body);
                check(r.body.contains("[REDACTED]"), "the reason survives redacted: " + r.body);
            }
        }
        // A disabled provider is not silently answered by someone else.
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",answer))) {
            JSONObject provider = loomyProvider("loomy-session").put("enabled", false);
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(provider);
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = request(g, "POST", "/v1/images/generations", draw("draw-1"));
                check(r.status == 400, "disabled Loomy: " + r.status + " " + r.body);
                check(up.requests.isEmpty(), "disabled provider never dispatched");
            }
        }
    }

    // ---------------- a non-Raccoon provider keeps its shape ----------------

    static void untouchedProvider() throws Exception {
        String reply = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"upstream-name\",\"choices\":[{\"index\":0,"
                +"\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream(
                (request,n) -> new VendorPolicyGatewayHttpTest.Reply(200,"application/json",reply))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(
                    VendorPolicyGatewayHttpTest.provider("deepseek","openai","https://api.deepseek.com/v1","deepseek-chat"));
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                Reply r = post(g, "/v1/chat/completions", chat("deepseek/deepseek-chat"));
                check(r.status == 200, "control status "+r.body);
                check("upstream-name".equals(new JSONObject(r.body).optString("model")), "other providers still report upstream");
                check(r.headers.get("X-Magpie-Sign-In") == null, "sign-in header stays Raccoon-only");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        raccoonJson("glm-upstream","raccoon/glm-upstream","raccoon/glm-upstream");
        raccoonJsonWithoutModel();
        raccoonStream();
        raccoonExpiredThenRenewed();
        workbuddyRefusal();
        clineGrace();
        loomyDrawPassThrough();
        loomyDrawGuards();
        untouchedProvider();
        System.out.println("ProviderParityWiringHttpTest passed");
    }
}
