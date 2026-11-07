package com.gaeul.allin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline synthetic accounts and injected refresh; all disk writes are to temporary files. */
public final class TokenPersistenceGatewayOfflineTest {
    private static final String[] PROVIDERS = {"github-copilot", "antigravity"};
    private static final Method ENSURE;
    static {
        try {
            ENSURE = GatewayServer.class.getDeclaredMethod("ensureStoredToken", String.class, JSONObject.class, String.class);
            ENSURE.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void field(GatewayServer s, String name, Object value) throws Exception {
        Field f = GatewayServer.class.getDeclaredField(name); f.setAccessible(true); f.set(s, value);
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static JSONObject account(String tag) {
        return new JSONObject().put("id", "same-id").put("name", tag).put("enabled", true)
            .put("access", tag + "-old").put("token", tag + "-old")
            .put("refresh", tag + "-refresh").put("expires", 1L);
    }
    private static JSONObject provider(String id) {
        return new JSONObject().put("id", id).put("enabled", true)
            .put("accounts", new JSONArray().put(account(id)));
    }
    private static JSONObject lookup(JSONObject root, String id) {
        JSONArray ps = root.getJSONArray("providers");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.getJSONObject(i);
            if (id.equals(p.optString("id"))) return p;
        }
        throw new AssertionError("provider missing: " + id);
    }
    private static JSONObject current(GatewayServer s, String id) throws Exception {
        Field f = GatewayServer.class.getDeclaredField("configJson"); f.setAccessible(true);
        return lookup((JSONObject) f.get(s), id).getJSONArray("accounts").getJSONObject(0);
    }
    private static JSONObject disk(File file, String provider) throws Exception {
        return lookup(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)), provider)
            .getJSONArray("accounts").getJSONObject(0);
    }
    private static final class Fixture implements AutoCloseable {
        final Path dir = Files.createTempDirectory("allin-token-offline-");
        final File file = dir.resolve("config.json").toFile();
        final JSONObject config = new JSONObject().put("providers", new JSONArray()
            .put(provider(PROVIDERS[0])).put(provider(PROVIDERS[1])));
        final GatewayServer server;
        Fixture() throws Exception {
            Files.write(file.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
            server = RoutingSetupOfflineTest.server(config);
            field(server, "configFile", file);
        }
        public void close() throws Exception {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.resolve("missing"));
            Files.deleteIfExists(dir);
        }
    }
    private static JSONObject ensure(GatewayServer s, String p, JSONObject a, String failed) throws Exception {
        try { return (JSONObject) ENSURE.invoke(s, p, a, failed); }
        catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        }
    }
    private static JSONObject candidate(JSONObject a, int number) {
        String prefix = a.optString("name");
        return copy(a).put("access", prefix + "-new-" + number).put("token", prefix + "-new-" + number)
            .put("refresh", prefix + "-rotated-" + number).put("expires", System.currentTimeMillis() + 3600000L);
    }
    private static void await(CountDownLatch latch, String why) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout: " + why);
    }
    private static void join(Thread t) throws Exception {
        t.join(10000); if (t.isAlive()) { t.interrupt(); throw new AssertionError("thread did not finish"); }
    }
    private static void concurrency(String provider) throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.server.setTokenRefreshForTests((snapshot, force) -> {
                int n = calls.incrementAndGet();
                if (n == 1) { entered.countDown(); await(release, "release refresh"); }
                return candidate(snapshot, n);
            });
            JSONObject stale1 = copy(current(f.server, provider)), stale2 = copy(stale1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicReference<JSONObject> a = new AtomicReference<>(), b = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread one = new Thread(() -> {
                try {
                    JSONObject result = ensure(f.server, provider, stale1, null);
                    check(disk(f.file, provider).optString("access").equals(result.optString("access")), "persist before first return");
                    a.set(result);
                } catch (Throwable t) { error.compareAndSet(null, t); }
            });
            Thread two = new Thread(() -> {
                try {
                    started.countDown();
                    JSONObject result = ensure(f.server, provider, stale2, null);
                    check(disk(f.file, provider).optString("access").equals(result.optString("access")), "persist before second return");
                    b.set(result);
                } catch (Throwable t) { error.compareAndSet(null, t); }
            });
            one.start(); await(entered, "first refresh"); two.start(); await(started, "second thread");
            release.countDown(); join(one); join(two);
            if (error.get() != null) throw new AssertionError("concurrent ensure", error.get());
            check(calls.get() == 1, provider + " single flight");
            check(a.get().optString("access").equals(b.get().optString("access")), "both see committed token");
            String now = provider + "-new-1";
            check(now.equals(ensure(f.server, provider, stale1, null).optString("access")), "stale object rereads current");
            check(now.equals(ensure(f.server, provider, stale2, provider + "-old").optString("access")), "old failed access reuses token");
            check(calls.get() == 1, "stale failed token did not exchange");
            JSONObject forced = ensure(f.server, provider, stale1, now);
            check(calls.get() == 2 && (provider + "-new-2").equals(forced.optString("access")), "current failed token forces one refresh");
            check(disk(f.file, provider).optString("refresh").equals(provider + "-rotated-2"), "rotation durable");
            String other = provider.equals(PROVIDERS[0]) ? PROVIDERS[1] : PROVIDERS[0];
            check(current(f.server, other).optString("access").equals(other + "-old"), "same id other provider unchanged");
            JSONObject otherResult = ensure(f.server, other, copy(current(f.server, other)), null);
            check(otherResult.optString("access").equals(other + "-new-3") && calls.get() == 3, "same id refresh remains provider scoped");
            check(disk(f.file, provider).optString("access").equals(provider + "-new-2"), "other refresh preserves first provider");
        }
    }
    private static void rejection(String provider, String change) throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject stale = copy(current(f.server, provider));
            String beforeDisk = new String(Files.readAllBytes(f.file.toPath()), StandardCharsets.UTF_8);
            AtomicInteger calls = new AtomicInteger();
            f.server.setTokenRefreshForTests((snapshot, force) -> {
                calls.incrementAndGet();
                synchronized (f.server) {
                    JSONObject p = lookup(f.config, provider);
                    JSONObject live = p.getJSONArray("accounts").getJSONObject(0);
                    switch (change) {
                        case "delete": p.put("accounts", new JSONArray()); break;
                        case "disableAccount": live.put("enabled", false); break;
                        case "disableProvider": p.put("enabled", false); break;
                        case "replace": p.getJSONArray("accounts").put(0, account(provider).put("name", "replacement")); break;
                        case "replaceIdentical": p.getJSONArray("accounts").put(0, copy(live)); break;
                        case "changedRefresh": live.put("refresh", "changed"); break;
                        case "epoch": field(f.server, "affinityEpoch", 1L); break;
                        case "retired": field(f.server, "backupRetired", true); break;
                        case "disk": field(f.server, "configFile", f.dir.resolve("missing").resolve("config.json").toFile()); break;
                        default: break;
                    }
                }
                JSONObject result = candidate(snapshot, 1);
                if ("candidateId".equals(change)) result.put("id", "injected-id");
                if ("candidateName".equals(change)) result.put("name", "injected-name");
                return result;
            });
            boolean rejected = false;
            try { JSONObject result = ensure(f.server, provider, stale, null); rejected = result == null; }
            catch (Exception expected) { rejected = true; }
            check(rejected && calls.get() == 1, provider + " rejects " + change);
            check(beforeDisk.equals(new String(Files.readAllBytes(f.file.toPath()), StandardCharsets.UTF_8)), change + " leaves disk unchanged");
            if (!"delete".equals(change)) {
                JSONObject a = current(f.server, provider);
                check(!a.optString("access").contains("-new-"), change + " does not publish candidate");
            }
            if ("disk".equals(change)) check(current(f.server, provider).toString().equals(stale.toString()), "disk failure leaves memory old");
        }
    }
    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (String provider : PROVIDERS) {
            try { concurrency(provider); }
            catch (Throwable t) { failures++; System.err.println(provider + " concurrency: " + t); }
            for (String change : new String[]{"delete", "disableAccount", "disableProvider", "replace", "replaceIdentical", "changedRefresh", "epoch", "retired", "disk", "candidateId", "candidateName"}) {
                try { rejection(provider, change); }
                catch (Throwable t) { failures++; System.err.println(provider + " " + change + ": " + t); }
            }
        }
        if (failures != 0) throw new AssertionError(failures + " token persistence checks failed");
        System.out.println("TokenPersistenceGatewayOfflineTest PASS");
    }
}
