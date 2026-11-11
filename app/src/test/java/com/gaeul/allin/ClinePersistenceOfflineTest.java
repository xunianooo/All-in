package com.gaeul.allin;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class ClinePersistenceOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static GatewayServer setup(Path file) throws Exception {
        GatewayServer g = ResponsesGatewayOfflineTest.gateway(1, "cline");
        JSONObject c = ResponsesGatewayOfflineTest.config(g), p = c.getJSONArray("providers").getJSONObject(0);
        p.put("id", "cline").put("enabled", true).put("accounts", new JSONArray().put(
            new JSONObject().put("id", "a").put("type", "oauth").put("token", "old")
                .put("access", "old").put("refresh", "one").put("expires", 1)));
        ResponsesGatewayOfflineTest.set(g, "configFile", file.toFile());
        Files.write(file, c.toString().getBytes("UTF-8")); return g;
    }
    static JSONObject account(GatewayServer g) throws Exception {
        return ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").optJSONObject(0);
    }
    static JSONObject refresh(GatewayServer g, JSONObject a) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod("refreshClineAccount", JSONObject.class); m.setAccessible(true);
        try { return (JSONObject)m.invoke(g,a); } catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    static ClineAuth.SignIn next() {
        ClineAuth.SignIn n = new ClineAuth.SignIn(); n.access = "new"; n.refresh = "two";
        n.expires = System.currentTimeMillis()+3600000; return n;
    }
    static void await(CountDownLatch latch) throws Exception { check(latch.await(5,TimeUnit.SECONDS), "timeout"); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("cline-persist"), file = dir.resolve("config.json");
        GatewayServer g = setup(file); AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        g.setClineRefreshForTests(token -> { calls.incrementAndGet(); entered.countDown(); await(release); return next(); });
        JSONObject old = account(g); AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable job = () -> { try { refresh(g,old); } catch (Throwable e) { error.set(e); } };
        Thread a = new Thread(job), b = new Thread(job); a.start(); await(entered); b.start(); release.countDown(); a.join(5000); b.join(5000);
        check(!a.isAlive() && !b.isAlive() && error.get()==null && calls.get()==1, "single flight");
        check("two".equals(account(g).optString("refresh")), "memory commit");
        check("two".equals(new JSONObject(new String(Files.readAllBytes(file),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).optString("refresh")), "disk commit");
        for (String change : new String[]{"delete", "disable", "replace"}) {
            // A consumed refresh value stays fenced for as long as the fence beside that
            // configuration lives, so this phase gets its own directory: the previous phase
            // already spent "one", and reusing its directory would be blocked before the
            // exchange instead of exercising the cancellation below.
            Path phase = Files.createTempDirectory("cline-persist-phase-");
            Path config = phase.resolve("config.json");
            GatewayServer r = setup(config); CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            r.setClineRefreshForTests(token -> { in.countDown(); await(out); return next(); });
            JSONObject selected = account(r); AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread t = new Thread(() -> { try { refresh(r,selected); } catch (Throwable e) { failed.set(e); } });
            t.start(); await(in);
            synchronized(r) {
                JSONObject current = account(r);
                if (change.equals("delete")) ResponsesGatewayOfflineTest.config(r).getJSONArray("providers").getJSONObject(0).put("accounts",new JSONArray());
                else if (change.equals("disable")) current.put("enabled",false);
                else current.put("refresh","replacement");
            }
            out.countDown(); t.join(5000);
            check(!t.isAlive() && failed.get()!=null, "cancel " + change);
            check(!new String(Files.readAllBytes(config),"UTF-8").contains("\"two\""), "no stale write " + change);
        }
        // The exchange really happens and only then the configuration cannot be replaced: an
        // existing directory full of a marker file stands in for the configuration, so the fence
        // beside it stays writable while the atomic replacement must fail.
        Path disk = Files.createTempDirectory("cline-persist-disk-");
        Path blocked = Files.createDirectories(disk.resolve("blocked"));
        Files.write(blocked.resolve("keep"), new byte[]{'x'});
        GatewayServer bad = setup(disk.resolve("config.json")); JSONObject original = account(bad);
        ResponsesGatewayOfflineTest.set(bad,"configFile",blocked.toFile());
        AtomicInteger exchanges = new AtomicInteger();
        bad.setClineRefreshForTests(token -> { exchanges.incrementAndGet(); return next(); });
        try { refresh(bad,original); throw new AssertionError("disk failure accepted"); } catch (IOException expected) {}
        check(exchanges.get()==1, "disk failure without an exchange");
        check("one".equals(account(bad).optString("refresh")), "disk failure changed memory");
        System.out.println("ClinePersistenceOfflineTest passed");
    }
}
