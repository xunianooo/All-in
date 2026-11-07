package com.gaeul.allin;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public final class ClineAuthPolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject a = new JSONObject().put("token", "old").put("refresh", "r").put("expires", 1);
        AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        ClineAuth.RefreshTransport transport = r -> {
            calls.incrementAndGet(); Thread.sleep(40);
            ClineAuth.SignIn n = new ClineAuth.SignIn(); n.access = "new"; n.refresh = "rotated";
            n.expires = System.currentTimeMillis() + 600000; return n;
        };
        Runnable task = () -> { try { start.await(); if (!ClineAuth.refreshAccount(a, transport)) failures.incrementAndGet(); } catch (Exception e) { failures.incrementAndGet(); } };
        Thread x = new Thread(task), y = new Thread(task); x.start(); y.start(); start.countDown(); x.join(); y.join();
        check(failures.get() == 0 && calls.get() == 1 && "new".equals(a.optString("token")));
        JSONObject b = new JSONObject().put("token", "old").put("refresh", "r").put("expires", 1);
        ClineAuth.RefreshCandidate candidate = ClineAuth.refreshCandidate(b, false, transport);
        check(candidate != null && candidate.matches(b) && "old".equals(b.optString("token")));
        b.put("refresh", "replacement"); check(!candidate.matches(b));
        check(ClineAuth.refreshCandidate(a, false, transport) == null);
        check(ClineAuth.refreshCandidate(a, true, transport) != null);
        check(ClineAuth.refreshCandidate(b, false, r -> { throw new Exception("SECRET_MARKER"); }) == null);
        System.out.println("ClineAuthPolicyOfflineTest passed");
    }
}
