package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

public final class RoutingSetupOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static GatewayServer server(JSONObject config) throws Exception {
        TestPrivacyScope.initialize();
        Field u = Unsafe.class.getDeclaredField("theUnsafe"); u.setAccessible(true);
        GatewayServer s = (GatewayServer)((Unsafe)u.get(null)).allocateInstance(GatewayServer.class);
        Field f = GatewayServer.class.getDeclaredField("configJson"); f.setAccessible(true); f.set(s, config);
        f = GatewayServer.class.getDeclaredField("wbRoundRobin"); f.setAccessible(true); f.set(s, new AtomicInteger());
        f = GatewayServer.class.getDeclaredField("totalRequests"); f.setAccessible(true); f.set(s, new AtomicInteger());
        f = GatewayServer.class.getDeclaredField("totalMasked"); f.setAccessible(true); f.set(s, new AtomicInteger());
        f = GatewayServer.class.getDeclaredField("totalContextDropped"); f.setAccessible(true); f.set(s, new AtomicInteger());
        return s;
    }
    static List<?> candidates(GatewayServer s, String model) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod("resolveCandidates", String.class); m.setAccessible(true);
        return (List<?>)m.invoke(s, model);
    }
    static NanoHTTPD.IHTTPSession request(String ip, String path, String method, String body, String key) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return (NanoHTTPD.IHTTPSession)Proxy.newProxyInstance(RoutingSetupOfflineTest.class.getClassLoader(), new Class[]{NanoHTTPD.IHTTPSession.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getRemoteIpAddress": return ip;
                case "getUri": return path;
                case "getMethod": return NanoHTTPD.Method.valueOf(method);
                case "getHeaders": { Map<String,String> h = new HashMap<>(); h.put("content-length", ""+bytes.length); if (key != null) h.put("authorization", "Bearer " + key); return h; }
                case "getParameters": return Collections.emptyMap();
                case "getInputStream": return new ByteArrayInputStream(bytes);
                default: return null;
            }
        });
    }
    static int status(GatewayServer s, String ip, String path, String method, String body, String key) {
        return s.serve(request(ip,path,method,body,key)).getStatus().getRequestStatus();
    }
    public static void main(String[] args) throws Exception {
        JSONObject cfg = new JSONObject("{\"server\":{\"authKey\":\"magpie\"},\"providers\":[{\"id\":\"alpha\",\"protocol\":\"openai\",\"keys\":[\"fake\"],\"models\":[\"known\"]}],\"groups\":[{\"id\":\"g\",\"targets\":[\"alpha/known\"]}]}");
        GatewayServer s = server(cfg);
        check(candidates(s,"unknown").isEmpty(), "missing setting defaults off");
        check(candidates(s,"known").size()==1, "known bare model still resolves");
        check(candidates(s,"alpha/anything").size()==1, "explicit provider unchanged");
        check(candidates(s,"other/anything").isEmpty(), "unknown explicit provider not redirected");
        cfg.put("routing", new JSONObject("{\"bareModelFallback\":true,\"groupsEnabled\":true}"));
        check(candidates(s,"unknown").size()==1, "opt-in bare fallback");
        check(candidates(s,"other/anything").isEmpty(), "opt-in does not affect explicit routes");
        check(candidates(s,"group/g").size()==1, "group still resolves");
        cfg.getJSONObject("routing").put("bareModelFallback", false);
        check(candidates(s,"unknown").isEmpty(), "switch can be disabled");
        cfg.getJSONArray("providers").getJSONObject(0).put("keys",new JSONArray());
        check(candidates(s,"unknown").isEmpty(), "no key no candidate");
        check(status(s,"192.168.1.2","/api/routing","GET","","magpie")==403, "remote blocked despite default password");
        check(status(s,"192.168.1.2","/","OPTIONS","",null)==403, "remote preflight blocked");
        for (String ip : new String[]{"127.0.0.1","::1","0:0:0:0:0:0:0:1","::ffff:127.0.0.1"})
            check(status(s,ip,"/api/routing","GET","",null)==200, "local setup available " + ip);
        cfg.put("setup",new JSONObject("{\"done\":true}"));
        check(status(s,"192.168.1.2","/api/routing","GET","","magpie")==200, "completed user retains LAN access");
        check(status(s,"192.168.1.2","/api/routing","GET","",null)==401, "completed user still requires key");
        int unknownStatus = status(s, "127.0.0.1", "/v1/chat/completions", "POST", "{\"model\":\"unknown\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}", null);
        check(unknownStatus >= 400 && unknownStatus < 500, "unknown bare model without key must be 4xx, got " + unknownStatus);
        System.out.println("RoutingSetupOfflineTest OK");
    }
}
