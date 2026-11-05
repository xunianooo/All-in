package com.gaeul.allin;


import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;


public class OutboundLimiterOfflineTest {
    static void assertEquals(Object a,Object b) { if(a instanceof Number && b instanceof Number) { if(((Number)a).longValue()==((Number)b).longValue()) return; } else if(Objects.equals(a,b)) return; throw new AssertionError(a+" != "+b); }
    static void assertTrue(boolean b) { if(!b) throw new AssertionError(); }
    static void assertFalse(boolean b) { assertTrue(!b); }
    static void fail() { throw new AssertionError(); }
    public static void main(String[] args) throws Exception { OutboundLimiterOfflineTest t=new OutboundLimiterOfflineTest(); t.reservationsAndRollingWindow(); t.multipleDomainsCapacityAndStrictJson(); t.queueFairnessCancellationReconfigureAndStop(); System.out.println("OutboundLimiterOfflineTest passed"); }

    static final class FakeClock implements OutboundLimiter.Clock { volatile long now = 1; public long nanoTime() { return now; } void ms(long v) { now += v * 1000000L; } }
    static OutboundLimiter.Policy p(long interval, int rpm, int flight, int queue, long wait) { return new OutboundLimiter.Policy(interval, rpm, flight, queue, wait); }
    static Map<String, OutboundLimiter.Policy> config(String id, OutboundLimiter.Policy p) { return Collections.singletonMap(id, p); }
    static List<String> ids(String... s) { return Arrays.asList(s); }
    public void reservationsAndRollingWindow() {
        FakeClock c = new FakeClock(); OutboundLimiter l = new OutboundLimiter(c, 4, 4);
        l.configure(config("account", p(1000, 2, 0, 0, 0)));
        OutboundLimiter.Ticket a = l.tryAcquire(ids("account")).ticket;
        assertEquals(OutboundLimiter.Status.WOULD_BLOCK, l.tryAcquire(ids("account")).status);
        a.close(); a = l.tryAcquire(ids("account")).ticket; a.markSent(); a.close();
        assertEquals(1000, l.tryAcquire(ids("account")).retryAfterMillis);
        c.ms(1000); a = l.tryAcquire(ids("account")).ticket; a.markSent(); a.close();
        c.ms(1000); assertEquals(OutboundLimiter.Status.WOULD_BLOCK, l.tryAcquire(ids("account")).status);
        c.ms(58000); assertEquals(OutboundLimiter.Status.ACQUIRED, l.tryAcquire(ids("account")).status);
    }
    public void multipleDomainsCapacityAndStrictJson() throws Exception {
        OutboundLimiter l = new OutboundLimiter(2, 2);
        Map<String, OutboundLimiter.Policy> m = new HashMap<String, OutboundLimiter.Policy>();
        m.put("provider", p(0,0,1,0,0)); m.put("account", p(0,0,1,0,0)); assertTrue(l.configure(m));
        OutboundLimiter.Ticket t = l.tryAcquire(ids("provider", "account")).ticket;
        assertEquals(OutboundLimiter.Status.WOULD_BLOCK, l.tryAcquire(ids("account")).status);
        assertFalse(l.configure(config("third", p(0,0,0,0,0)))); t.close(); assertTrue(l.removeDomain("account"));
        assertTrue(l.configure(config("third", p(0,0,0,0,0))));
        assertEquals(3, OutboundLimiter.Policy.fromJson(new JSONObject("{\"rpm\":3}")).rpm);
        try { OutboundLimiter.Policy.fromJson(new JSONObject("{\"rpm\":\"3\"}")); fail(); } catch (IllegalArgumentException expected) { }
    }
    public void queueFairnessCancellationReconfigureAndStop() throws Exception {
        final OutboundLimiter l = new OutboundLimiter(4, 4); l.configure(config("a", p(0,0,1,3,0)));
        OutboundLimiter.Ticket held = l.tryAcquire(ids("a")).ticket;
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        final CountDownLatch firstStarted = new CountDownLatch(1);
        Thread first = new Thread(new Runnable() { public void run() { try { firstStarted.countDown(); OutboundLimiter.Result r = l.await(ids("a"), 0, null); if (r.ticket != null) { order.add(1); r.ticket.close(); } } catch (InterruptedException e) { throw new AssertionError(e); } } });
        first.start(); firstStarted.await();
        // Synchronize by observing the bounded per-domain queue, not a timing assumption.
        l.configure(config("a", p(0,0,1,1,0)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (l.await(ids("a"), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1), null).status != OutboundLimiter.Status.QUEUE_FULL && System.nanoTime() < until) Thread.yield();
        assertEquals(OutboundLimiter.Status.QUEUE_FULL, l.await(ids("a"), 0, null).status);
        held.close(); first.join(2000); assertEquals(Arrays.asList(1), order);
        final boolean[] cancelled = {true}; assertEquals(OutboundLimiter.Status.CANCELLED, l.await(ids("a"), 0, new OutboundLimiter.Cancellation() { public boolean isCancelled() { return cancelled[0]; } }).status);
        l.configure(config("a", p(0,0,2,1,0))); assertEquals(OutboundLimiter.Status.ACQUIRED, l.tryAcquire(ids("a")).status);
        l.stop(); assertEquals(OutboundLimiter.Status.STOPPED, l.tryAcquire(ids("a")).status);
    }
}
