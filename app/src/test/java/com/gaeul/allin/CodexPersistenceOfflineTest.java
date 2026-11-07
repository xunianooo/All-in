package com.gaeul.allin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CodexPersistenceOfflineTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Fixture {
        GatewayServer gateway;
        JSONObject original;
        File file;
        Fixture() throws Exception {
            original = new JSONObject().put("id", "local-id").put("accountId", "account-id")
                    .put("access", "old-access").put("token", "old-access").put("refresh", "old-refresh")
                    .put("idToken", "old-id").put("expires", 100L).put("lastRefresh", 10L);
            JSONObject provider = new JSONObject().put("id", "codex").put("enabled", true)
                    .put("accounts", new JSONArray().put(original));
            JSONObject config = new JSONObject().put("providers", new JSONArray().put(provider));
            gateway = RoutingSetupOfflineTest.server(config);
            file = new File(Files.createTempDirectory("codex-persistence").toFile(), "config.json");
            Files.write(file.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
            field("configFile").set(gateway, file);
        }
        Field field(String name) throws Exception { Field f=GatewayServer.class.getDeclaredField(name); f.setAccessible(true); return f; }
        JSONObject config() throws Exception { return (JSONObject) field("configJson").get(gateway); }
        JSONObject provider() throws Exception { return config().getJSONArray("providers").getJSONObject(0); }
        JSONObject account() throws Exception { return provider().getJSONArray("accounts").getJSONObject(0); }
        CodexAuth.SignInInfo bind() throws Exception {
            CodexAuth.SignInInfo i=oldInfo();
            Method m=GatewayServer.class.getDeclaredMethod("bindCodexPersistence",JSONObject.class,CodexAuth.SignInInfo.class);
            m.setAccessible(true);
            try { m.invoke(gateway, original, i); } catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
            return i;
        }
        void failCommit(CodexAuth.SignInInfo bound) throws Exception {
            try { bound.refreshCommit.commit(oldInfo(),candidate()); throw new AssertionError("commit accepted stale or unavailable account"); }
            catch (IOException expected) { check(bound.accessToken.equals("old-access"), "failed commit changed info"); }
        }
    }
    static CodexAuth.SignInInfo oldInfo() {
        CodexAuth.SignInInfo i=new CodexAuth.SignInInfo(); i.accessToken="old-access"; i.refreshToken="old-refresh"; i.idToken="old-id"; i.expiresAt=100; i.lastRefresh=10; return i;
    }
    static CodexAuth.SignInInfo candidate() {
        CodexAuth.SignInInfo i=new CodexAuth.SignInInfo(); i.accessToken="new-access"; i.refreshToken="new-refresh"; i.idToken="new-id"; i.expiresAt=200; i.lastRefresh=20; return i;
    }
    static void checkNew(JSONObject a) {
        check(a.optString("access").equals("new-access"),"access"); check(a.optString("token").equals("new-access"),"token");
        check(a.optString("refresh").equals("new-refresh"),"refresh"); check(a.optString("idToken").equals("new-id"),"idToken");
        check(a.optLong("expires")==200 && a.optLong("lastRefresh")==20,"timestamps");
    }
    public static void main(String[] args) throws Exception {
        Fixture good=new Fixture(); CodexAuth.SignInInfo one=good.bind();
        one.refreshCommit.commit(oldInfo(),candidate());
        checkNew(good.account());
        checkNew(new JSONObject(new String(Files.readAllBytes(good.file.toPath()),StandardCharsets.UTF_8)).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0));
        check(good.original.optString("access").equals("old-access"),"old object mutated");
        Fixture stale=new Fixture(); CodexAuth.SignInInfo b=stale.bind(); stale.account().put("refresh","different"); stale.failCommit(b);
        Fixture disabled=new Fixture(); b=disabled.bind(); disabled.account().put("enabled",false); disabled.failCommit(b);
        Fixture deleted=new Fixture(); b=deleted.bind(); deleted.provider().put("accounts",new JSONArray()); deleted.failCommit(b);
        Fixture providerDisabled=new Fixture(); b=providerDisabled.bind(); providerDisabled.provider().put("enabled",false); providerDisabled.failCommit(b);
        Fixture replaced=new Fixture(); b=replaced.bind(); replaced.provider().getJSONArray("accounts").put(0,new JSONObject(replaced.original.toString()).put("id","other")); replaced.failCommit(b);
        Fixture retired=new Fixture(); b=retired.bind(); retired.field("backupRetired").setBoolean(retired.gateway,true); retired.failCommit(b);
        Fixture failed=new Fixture(); b=failed.bind(); JSONObject before=failed.config(); failed.field("configFile").set(failed.gateway,new File(failed.file.getParentFile(),"missing/config.json")); failed.failCommit(b);
        check(failed.config()==before && failed.account().optString("access").equals("old-access"),"failed write changed memory");
        Fixture concurrent=new Fixture(); CodexAuth.SignInInfo left=concurrent.bind(), right=concurrent.bind();
        CountDownLatch start=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> x=pool.submit(()->{ start.await(); left.refreshCommit.commit(oldInfo(),candidate()); return null; });
            Future<?> y=pool.submit(()->{ start.await(); right.refreshCommit.commit(oldInfo(),candidate()); return null; });
            start.countDown(); x.get(); y.get(); checkNew(concurrent.account());
        } finally { pool.shutdownNow(); }
        System.out.println("CodexPersistenceOfflineTest passed");
    }
}
