package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AccountTokenCoordinatorOfflineTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static JSONObject copy(JSONObject x) throws Exception { return x == null ? null : new JSONObject(x.toString()); }
    static JSONObject account(String token, long expires) throws Exception {
        return new JSONObject().put("enabled", true).put("access", token).put("refresh", "secret")
                .put("expires", expires).put("nested", new JSONObject().put("v", 1));
    }
    static final class FakeStore implements AccountTokenCoordinator.Store {
        JSONObject value; boolean providerEnabled = true, fail; int generation, readGeneration;
        FakeStore(JSONObject x) throws Exception { value = copy(x); }
        public synchronized JSONObject read(String p, String id) throws Exception {
            readGeneration = generation;
            return providerEnabled ? copy(value) : null;
        }
        public synchronized JSONObject commit(String p, String id, JSONObject expected, JSONObject candidate) throws Exception {
            if (!providerEnabled || value == null || !value.optBoolean("enabled", true) ||
                    generation != readGeneration || !value.toString().equals(expected.toString()))
                throw new Exception("conflict");
            if (fail) throw new Exception("disk failure");
            value = copy(candidate); return copy(value);
        }
        synchronized void change(int mode) throws Exception {
            if (mode == 0) value = null;
            if (mode == 1) value.put("enabled", false);
            if (mode == 2) { value = copy(value); generation++; }
            if (mode == 3) providerEnabled = false;
            if (mode == 4) value.getJSONObject("nested").put("v", 2);
        }
    }
    static void await(CountDownLatch l) throws Exception { check(l.await(5, TimeUnit.SECONDS)); }
    static AccountTokenCoordinator.Refresher refreshing(AtomicInteger calls) {
        return (snapshot, force) -> { calls.incrementAndGet(); snapshot.put("access", "new");
            snapshot.put("expires", System.currentTimeMillis() + 600000); return snapshot; };
    }
    static void concurrent() throws Exception {
        AccountTokenCoordinator c = new AccountTokenCoordinator(); FakeStore s = new FakeStore(account("old", 1));
        JSONObject firstCopy = s.read("p", "a"), secondCopy = s.read("p", "a");
        check(firstCopy != secondCopy && firstCopy.getJSONObject("nested") != secondCopy.getJSONObject("nested"));
        AtomicInteger calls = new AtomicInteger(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AccountTokenCoordinator.Refresher r = (x, f) -> { started.countDown(); await(release); return refreshing(calls).refresh(x, f); };
        Runnable task = () -> { try { check("new".equals(c.ensure("p", "a", 1000, null, s, r).optString("access"))); }
            catch (Throwable t) { error.compareAndSet(null, t); } };
        Thread a = new Thread(task), b = new Thread(task); a.start(); await(started); b.start(); release.countDown();
        a.join(5000); b.join(5000); check(!a.isAlive() && !b.isAlive() && error.get() == null && calls.get() == 1);
        check("new".equals(c.ensure("p", "a", 1000, "old", s, r).optString("access")) && calls.get() == 1);
        c.ensure("p", "a", 1000, "new", s, r); check(calls.get() == 2);
    }
    static void conflicts() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            final int m = mode; AccountTokenCoordinator c = new AccountTokenCoordinator();
            FakeStore s = new FakeStore(account("old", 1));
            try { c.ensure("p", "a", 0, null, s, (x, f) -> { x.put("access", "new"); s.change(m); return x; });
                throw new AssertionError("expected conflict"); } catch (Exception expected) { check("conflict".equals(expected.getMessage())); }
            if (s.value != null) check("old".equals(s.value.optString("access")));
        }
    }
    static void persistenceAndReuse() throws Exception {
        AccountTokenCoordinator c = new AccountTokenCoordinator(); FakeStore s = new FakeStore(account("old", 1));
        s.fail = true;
        try { c.ensure("p", "a", 0, null, s, (x, f) -> { x.put("access", "new"); x.getJSONObject("nested").put("v", 9); return x; });
            throw new AssertionError(); } catch (Exception expected) { check("disk failure".equals(expected.getMessage())); }
        check("old".equals(s.value.optString("access")) && s.value.getJSONObject("nested").getInt("v") == 1);
        s.fail = false; s.value.put("expires", 0); AtomicInteger calls = new AtomicInteger();
        JSONObject result = c.ensure("p", "a", 100, null, s, refreshing(calls));
        result.getJSONObject("nested").put("v", 5); check(calls.get() == 0 && s.value.getJSONObject("nested").getInt("v") == 1);
        s.value.put("enabled", false); check(c.ensure("p", "a", 0, null, s, refreshing(calls)) == null);
        s.value.put("enabled", true).put("access", ""); check(c.ensure("p", "a", 0, null, s, (x, f) -> null) == null);
    }
    public static void main(String[] args) throws Exception {
        concurrent(); conflicts(); persistenceAndReuse(); System.out.println("AccountTokenCoordinatorOfflineTest passed");
    }
}
