package com.gaeul.allin;

import java.io.File;
import java.lang.reflect.Field;
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
    public static void main(String[] args) throws Exception {
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
