package com.gaeul.allin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import fi.iki.elonen.NanoHTTPD;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;

public final class RoutingAuditOfflineTest {
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void file(GatewayServer server, File f) throws Exception {
        Field field = GatewayServer.class.getDeclaredField("configFile"); field.setAccessible(true); field.set(server, f);
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("routing-audit").toFile();
        JSONObject cfg = new JSONObject("{\"server\":{\"authKey\":\"test-key\"},\"routing\":{\"groupsEnabled\":false,\"bareModelFallback\":false}}");
        GatewayServer s = RoutingSetupOfflineTest.server(cfg);
        file(s, new File(dir, "config.json"));
        check(RoutingSetupOfflineTest.status(s,"192.0.2.1","/api/routing","GET","","test-key")==403,"legacy config requires local setup");
        NanoHTTPD.IHTTPSession base = RoutingSetupOfflineTest.request("192.0.2.1", "/api/routing", "GET", "", null);
        NanoHTTPD.IHTTPSession spoof = (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(RoutingAuditOfflineTest.class.getClassLoader(), new Class[]{NanoHTTPD.IHTTPSession.class}, (p,m,a) -> {
            if (m.getName().equals("getHeaders")) { Map<String,String> h = new HashMap<>(); h.put("origin", "http://127.0.0.1:3425"); h.put("remote-addr", "127.0.0.1"); h.put("x-forwarded-for", "127.0.0.1"); return h; }
            return m.invoke(base,a);
        });
        check(s.serve(spoof).getStatus().getRequestStatus()==403,"spoofed headers cannot bypass setup");
        cfg.put("setup", new JSONObject("{\"done\":\"true\"}"));
        check(s.serve(spoof).getStatus().getRequestStatus()==401,"spoofed headers cannot bypass authentication");
        check(RoutingSetupOfflineTest.status(s,"192.0.2.1","/api/routing","GET","","test-key")==200,"string true accepted");
        cfg.put("setup", new JSONObject("{\"done\":null}"));
        check(RoutingSetupOfflineTest.status(s,"192.0.2.1","/api/routing","GET","","test-key")==403,"null blocked");
        cfg.put("setup", new JSONObject("{\"done\":true}"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i=0; i<40; i++) {
            cfg.getJSONObject("routing").put("groupsEnabled",false).put("bareModelFallback",false);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a=executor.submit(() -> { start.await(); return RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/routing","POST","{\"enabled\":true}",null); });
            Future<Integer> b=executor.submit(() -> { start.await(); return RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/routing","POST","{\"bareModelFallback\":true}",null); });
            start.countDown(); check(a.get()==200 && b.get()==200,"writes succeed");
            JSONObject persisted = new JSONObject(new String(Files.readAllBytes(new File(dir,"config.json").toPath()),"UTF-8")).getJSONObject("routing");
            check(persisted.optBoolean("groupsEnabled") && persisted.optBoolean("bareModelFallback"),"concurrent patches retained");
        }
        executor.shutdown();
        file(s, new File(new File(dir,"missing"),"config.json"));
        check(RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/routing","POST","{\"enabled\":false}",null)==500,"routing failure returned");
        check(cfg.getJSONObject("routing").optBoolean("groupsEnabled"),"routing rollback");
        cfg.remove("setup");
        check(RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/setup/complete","POST","{\"authKey\":\"new-key\"}",null)==500,"setup failure returned");
        check(!cfg.has("setup") && cfg.getJSONObject("server").getString("authKey").equals("test-key"),"setup rollback");
        check(RoutingSetupOfflineTest.status(s,"192.0.2.1","/api/routing","GET","","new-key")==403,"failed setup stays locked");
        System.out.println("RoutingAuditOfflineTest OK");
    }
}
