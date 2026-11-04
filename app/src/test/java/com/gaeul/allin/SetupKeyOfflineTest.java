package com.gaeul.allin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import fi.iki.elonen.NanoHTTPD;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

public final class SetupKeyOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JSONObject disk(File dir) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(dir, "config.json").toPath()), StandardCharsets.UTF_8));
    }
    static JSONObject current(GatewayServer s) throws Exception {
        Field f = GatewayServer.class.getDeclaredField("configJson"); f.setAccessible(true); return (JSONObject) f.get(s);
    }
    static int post(GatewayServer s, String path, String body) {
        return RoutingSetupOfflineTest.status(s,"127.0.0.1",path,"POST",body,null);
    }
    static int remote(GatewayServer s, String key) {
        return RoutingSetupOfflineTest.status(s,"192.0.2.1","/api/routing","GET","",key);
    }
    // The test-only server shares the listening socket with the Unsafe gateway so
    // NanoHTTPD's final getListeningPort() reports the real wrapper port.
    static final class HttpFixture extends NanoHTTPD {
        final File dir;
        volatile GatewayServer gateway;
        HttpFixture(File dir) { super("127.0.0.1", 0); this.dir = dir; }
        void reload() throws Exception {
            GatewayServer next = RoutingSetupOfflineTest.server(disk(dir));
            RoutingAuditOfflineTest.file(next, new File(dir, "config.json"));
            Field socket = NanoHTTPD.class.getDeclaredField("myServerSocket");
            socket.setAccessible(true);
            socket.set(next, socket.get(this));
            gateway = next;
        }
        @Override public Response serve(IHTTPSession session) {
            if ("/__test/reload".equals(session.getUri()) && session.getMethod() == Method.POST) {
                try {
                    int remaining = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
                    while (remaining-- > 0) if (session.getInputStream().read() < 0) throw new java.io.EOFException();
                    reload(); return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
                }
                catch (Exception e) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"ok\":false}"); }
            }
            IHTTPSession effective = session;
            if ("1".equals(session.getHeaders().get("x-fixture-remote"))) {
                effective = (IHTTPSession) Proxy.newProxyInstance(IHTTPSession.class.getClassLoader(), new Class[]{IHTTPSession.class},
                    (proxy, method, args) -> method.getName().equals("getRemoteIpAddress") ? "192.0.2.1" : method.invoke(session, args));
            }
            return gateway.serve(effective);
        }
    }
    static HttpFixture startFixture(File dir) throws Exception {
        // Never replace existing files, even if the caller accidentally supplies a real directory.
        if (!dir.isDirectory() || dir.list() == null || dir.list().length != 0)
            throw new IllegalArgumentException("fixture directory must exist and be empty");
        File config = new File(dir, "config.json");
        Files.write(config.toPath(), "{\"providers\":[],\"server\":{\"authKey\":\"magpie\"}}".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        GatewayAuthKeyHolder.init(dir);
        HttpFixture fixture = new HttpFixture(dir);
        try {
            fixture.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            fixture.reload();
            return fixture;
        } catch (Exception e) {
            fixture.stop();
            throw e;
        }
    }
    static void httpFixture(File dir) throws Exception {
        HttpFixture fixture = startFixture(dir);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
            System.out.println("{\"port\":" + fixture.getListeningPort() + "}");
            System.out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                String line = input.readLine();
                if (line == null) { new java.util.concurrent.CountDownLatch(1).await(); break; }
                if (line.trim().equals("stop")) break;
            }
        } finally { fixture.stop(); }
    }
    static void httpTest(File repoRoot) throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("setup-key-http-");
        HttpFixture fixture = null;
        try {
            fixture = startFixture(dir.toFile());
            File script = new File(repoRoot, "tests/dashboard-setup-key-http.cjs");
            String nodeCommand = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                    ? "node.exe" : "node";
            Process node = new ProcessBuilder(nodeCommand, script.getAbsolutePath(),
                    "http://127.0.0.1:" + fixture.getListeningPort(), dir.toString())
                    .directory(repoRoot).inheritIO().start();
            int status = node.waitFor();
            if (status != 0) throw new AssertionError("Node HTTP test exited with status " + status);
        } finally {
            if (fixture != null) fixture.stop();
            java.util.List<java.nio.file.Path> pathsToDelete;
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir)) {
                pathsToDelete = paths.sorted(java.util.Comparator.reverseOrder())
                        .collect(java.util.stream.Collectors.toList());
            }
            for (java.nio.file.Path path : pathsToDelete) Files.deleteIfExists(path);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--http-fixture".equals(args[0])) { httpFixture(new File(args[1])); return; }
        if (args.length == 2 && "--http-test".equals(args[0])) { httpTest(new File(args[1])); return; }
        if (args.length != 0) throw new IllegalArgumentException("usage: --http-fixture <empty-dir> | --http-test <repoRoot>");
        File dir = Files.createTempDirectory("setup-key").toFile();
        File config = new File(dir,"config.json");
        String stale = "{\"server\":{\"authKey\":\"magpie\",\"maxBodyBytes\":65536},\"setup\":{\"done\":false},\"providers\":[]}";
        Files.write(config.toPath(),stale.getBytes(StandardCharsets.UTF_8));
        GatewayAuthKeyHolder.init(dir);
        check(GatewayAuthKeyHolder.get().equals("magpie"),"initial disk key");
        File unreadable = Files.createTempDirectory("setup-key-empty").toFile();
        GatewayAuthKeyHolder.init(unreadable);
        check(GatewayAuthKeyHolder.get().isEmpty(),"missing file does not invent default");
        Files.write(new File(unreadable,"config.json").toPath(), "{\"server\":{\"authKey\":\"fixture-retry\"}}".getBytes(StandardCharsets.UTF_8));
        check(GatewayAuthKeyHolder.get().equals("fixture-retry"),"missing file can be retried");
        GatewayAuthKeyHolder.init(dir);
        GatewayServer s = RoutingSetupOfflineTest.server(new JSONObject(stale));
        RoutingAuditOfflineTest.file(s,config);
        check(post(s,"/api/config","{\"server\":{\"authKey\":\"fixture-attacker\"},\"setup\":{\"done\":true},\"providers\":[]}")==200,"security fields ignored on config");
        check(!disk(dir).getJSONObject("setup").optBoolean("done"),"config cannot complete setup");
        check(GatewayAuthKeyHolder.get().equals("magpie"),"config cannot change key");
        check(post(s,"/api/server-key","{\"authKey\":\"fixture-new-key\"}")==200,"first step");
        check(disk(dir).getJSONObject("server").getString("authKey").equals("fixture-new-key"),"first step persisted");
        check(!disk(dir).getJSONObject("setup").optBoolean("done"),"setup remains pending");
        check(GatewayAuthKeyHolder.get().equals("fixture-new-key"),"holder invalidated");
        check(post(s,"/api/setup/complete","{\"keepCurrent\":true}")==200,"setup complete");
        check(remote(s,"magpie")==401 && remote(s,"fixture-new-key")==200,"LAN authorization");
        GatewayServer restarted = RoutingSetupOfflineTest.server(disk(dir));
        RoutingAuditOfflineTest.file(restarted,config);
        check(remote(restarted,"magpie")==401 && remote(restarted,"fixture-new-key")==200,"restart authorization");
        check(post(restarted,"/api/config",stale)==200,"stale config accepted");
        check(disk(dir).getJSONObject("setup").optBoolean("done"),"setup protected");
        check(disk(dir).getJSONObject("server").getString("authKey").equals("fixture-new-key"),"key protected");
        check(disk(dir).getJSONObject("server").getInt("maxBodyBytes")==65536,"ordinary server field retained");
        check(GatewayAuthKeyHolder.get().equals("fixture-new-key"),"holder after config write");
        java.lang.reflect.Method save = GatewayServer.class.getDeclaredMethod("saveConfig", JSONObject.class);
        save.setAccessible(true);
        JSONObject replacement = new JSONObject(current(restarted).toString());
        replacement.getJSONObject("server").put("authKey","fixture-legacy-save");
        check((Boolean)save.invoke(restarted,replacement),"legacy save succeeds");
        check(GatewayAuthKeyHolder.get().equals("fixture-legacy-save"),"legacy save invalidates holder");
        check(post(restarted,"/api/server-key","{\"authKey\":\"fixture-new-key\"}")==200,"restore fixture key");
        check(GatewayAuthKeyHolder.get().equals("fixture-new-key"),"holder refreshed after restore");
        File other = Files.createTempDirectory("setup-key-other").toFile();
        Files.write(new File(other,"config.json").toPath(),"{\"server\":{\"authKey\":\"fixture-other\"}}".getBytes(StandardCharsets.UTF_8));
        GatewayAuthKeyHolder.init(other);
        check(GatewayAuthKeyHolder.get().equals("fixture-other"),"directory switch");
        GatewayAuthKeyHolder.init(dir);
        check(GatewayAuthKeyHolder.get().equals("fixture-new-key"),"switch back");
        File broken = new File(dir,"missing/config.json");
        RoutingAuditOfflineTest.file(restarted,broken);
        check(post(restarted,"/api/server-key","{\"authKey\":\"fixture-failed\"}")==500,"failed key write");
        check(current(restarted).getJSONObject("server").getString("authKey").equals("fixture-new-key"),"failed key retains memory");
        check(disk(dir).getJSONObject("server").getString("authKey").equals("fixture-new-key"),"failed key retains disk");
        check(post(restarted,"/api/setup/complete","{\"authKey\":\"fixture-failed\"}")==500,"failed setup write");
        check(current(restarted).getJSONObject("server").getString("authKey").equals("fixture-new-key"),"failed setup retains key");
        check(current(restarted).getJSONObject("setup").optBoolean("done"),"failed setup retains state");
        check(GatewayAuthKeyHolder.get().equals("fixture-new-key"),"failed writes retain holder");
        System.out.println("SetupKeyOfflineTest OK");
    }
}
