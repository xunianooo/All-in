package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.*;

/** Process-local outbound admission. Domain identifiers must be stable, non-secret opaque IDs. */
public final class OutboundLimiter {
    public interface Clock { long nanoTime(); }
    public interface Cancellation { boolean isCancelled(); }
    public static final Clock SYSTEM_CLOCK = new Clock() { public long nanoTime() { return System.nanoTime(); } };
    private static final long SECOND = 1000000000L, WINDOW = 60L * SECOND;
    private static final Cancellation NEVER = new Cancellation() { public boolean isCancelled() { return false; } };

    public static final class Policy {
        public final long minIntervalMillis, maxWaitMillis;
        public final int rpm, maxInFlight, maxQueue;
        public Policy(long minIntervalMillis, int rpm, int maxInFlight, int maxQueue, long maxWaitMillis) {
            if (minIntervalMillis < 0 || rpm < 0 || maxInFlight < 0 || maxQueue < 0 || maxWaitMillis < 0
                    || minIntervalMillis > 300000L || maxWaitMillis > 300000L || rpm>10000 || maxInFlight>64 || maxQueue>128)
                throw new IllegalArgumentException("invalid limit");
            this.minIntervalMillis = minIntervalMillis; this.rpm = rpm; this.maxInFlight = maxInFlight;
            this.maxQueue = maxQueue; this.maxWaitMillis = maxWaitMillis;
        }
        /** All fields are optional; zero means unlimited. Unknown, fractional and wrongly typed fields fail closed. */
        public static Policy fromJson(JSONObject json) {
            if (json == null) throw new IllegalArgumentException("settings required");
            Set<String> allowed = new HashSet<String>(Arrays.asList("minIntervalMillis", "rpm", "maxInFlight", "maxQueue", "maxWaitMillis"));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("unknown limit field");
            return new Policy(number(json, "minIntervalMillis", Long.MAX_VALUE / 1000000L),
                    (int) number(json, "rpm", Integer.MAX_VALUE), (int) number(json, "maxInFlight", Integer.MAX_VALUE),
                    (int) number(json, "maxQueue", Integer.MAX_VALUE), number(json, "maxWaitMillis", Long.MAX_VALUE / 1000000L));
        }
        private static long number(JSONObject json, String key, long max) {
            if (!json.has(key)) return 0;
            Object value = json.opt(key);
            if (!(value instanceof Number)) throw new IllegalArgumentException("invalid " + key);
            double d = ((Number) value).doubleValue();
            long n = ((Number) value).longValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > max || d != (double)n || n < 0 || n > max)
                throw new IllegalArgumentException("invalid " + key);
            return n;
        }
    }
    public enum Status { ACQUIRED, WOULD_BLOCK, QUEUE_FULL, WAIT_TIMEOUT, DEADLINE, CANCELLED, STOPPED, CAPACITY }
    public static final class Result {
        public final Status status; public final Ticket ticket; public final long retryAfterMillis;
        private Result(Status s, Ticket t, long retry) { status = s; ticket = t; retryAfterMillis = retry; }
    }
    private static final class State {
        Policy policy; int active, reserved, queued; boolean sent, overflow; long lastSent, overflowAt;
        final ArrayDeque<Long> sends = new ArrayDeque<Long>();
        State(Policy p) { policy = p; }
    }
    private static final class Waiter { final List<String> domains; Waiter(List<String> d) { domains = d; } }
    public final class Ticket implements AutoCloseable {
        private final List<String> domains; private boolean sent, closed;
        private Ticket(List<String> domains) { this.domains = domains; }
        /** Call immediately before handing the request to the transport. Idempotent. */
        public void markSent() { synchronized (OutboundLimiter.this) {
            if (closed) throw new IllegalStateException("closed ticket");
            if (sent) return;
            long now = clock.nanoTime();
            for (String id : domains) { State s = states.get(id); prune(s,now); s.reserved--; s.sent = true; s.lastSent = now; s.sends.addLast(now); if (s.sends.size() > 10000) { s.overflowAt = s.sends.removeFirst(); s.overflow = true; } }
            sent = true; OutboundLimiter.this.notifyAll();
        } }
        public void close() { synchronized (OutboundLimiter.this) {
            if (closed) return;
            closed = true;
            for (String id : domains) { State s = states.get(id); s.active--; if (!sent) s.reserved--; }
            OutboundLimiter.this.notifyAll();
        } }
    }
    private final Clock clock; private final int maxDomains, maxWaiters;
    private final Map<String, State> states = new HashMap<String, State>();
    private final ArrayDeque<Waiter> queue = new ArrayDeque<Waiter>();
    private boolean stopped;
    public OutboundLimiter(int maxDomains, int maxWaiters) { this(SYSTEM_CLOCK, maxDomains, maxWaiters); }
    public OutboundLimiter(Clock clock, int maxDomains, int maxWaiters) {
        if (clock == null || maxDomains <= 0 || maxWaiters <= 0) throw new IllegalArgumentException();
        this.clock = clock; this.maxDomains = maxDomains; this.maxWaiters = maxWaiters;
    }
    /** Atomically configure all supplied domains. IDs must never contain credentials or model names. */
    public synchronized boolean configure(Map<String, Policy> policies) {
        if (policies == null || policies.isEmpty()) throw new IllegalArgumentException();
        for (Map.Entry<String, Policy> e : policies.entrySet())
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null) throw new IllegalArgumentException();
        int added = 0; for (String id : policies.keySet()) if (!states.containsKey(id)) added++;
        if (states.size() + added > maxDomains) {
            for(String id:new ArrayList<String>(states.keySet())) if(!policies.containsKey(id)) removeDomain(id);
            if(states.size()+added>maxDomains) return false;
        }
        for (Map.Entry<String, Policy> e : policies.entrySet()) {
            State s = states.get(e.getKey()); if (s == null) states.put(e.getKey(), new State(e.getValue())); else s.policy = e.getValue();
        }
        notifyAll(); return true;
    }
    /** Remove only an idle domain without live rate history. */
    public synchronized boolean removeDomain(String id) {
        State s = states.get(id); if (s == null) return true;
        long now = clock.nanoTime(); prune(s, now);
        if (s.active != 0 || s.queued != 0 || !s.sends.isEmpty() || s.overflow || (s.sent && remaining(s.lastSent, s.policy.minIntervalMillis * 1000000L, now) > 0)) return false;
        states.remove(id); return true;
    }
    public synchronized void stop() { stopped = true; notifyAll(); }
    /** Wake waiters after external cancellation or advancing an injected clock. */
    public synchronized void signalChange() { notifyAll(); }
    private List<String> domains(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("domains required");
        LinkedHashSet<String> unique = new LinkedHashSet<String>(ids);
        for (String id : unique) if (id == null || !states.containsKey(id)) throw new IllegalArgumentException("unconfigured domain");
        return new ArrayList<String>(unique);
    }
    private static long remaining(long start, long duration, long now) {
        long elapsed = now - start; if (elapsed < 0) return duration; return elapsed >= duration ? 0 : duration - elapsed;
    }
    private static void prune(State s, long now) { while (!s.sends.isEmpty() && remaining(s.sends.peekFirst(), WINDOW, now) == 0) s.sends.removeFirst(); if (s.overflow && remaining(s.overflowAt, WINDOW, now) == 0) s.overflow = false; }
    private long delay(List<String> ids, long now) {
        long delay = 0;
        for (String id : ids) {
            State s = states.get(id); Policy p = s.policy; prune(s, now);
            if (p.maxInFlight > 0 && s.active >= p.maxInFlight) delay = Long.MAX_VALUE;
            if (p.minIntervalMillis > 0) {
                if (s.reserved > 0) delay = Long.MAX_VALUE;
                else if (s.sent) delay = Math.max(delay, remaining(s.lastSent, p.minIntervalMillis * 1000000L, now));
            }
            if (p.rpm > 0 && s.overflow) delay = Math.max(delay, remaining(s.overflowAt, WINDOW, now));
            if (p.rpm > 0 && (long)s.sends.size() + s.reserved >= p.rpm) {
                if (s.sends.isEmpty() || s.reserved >= p.rpm) delay = Long.MAX_VALUE;
                else {
                    int index = (int)((long)s.sends.size() + s.reserved - p.rpm); Iterator<Long> it = s.sends.iterator(); long t = 0;
                    for (int i = 0; i <= index && it.hasNext(); i++) t = it.next();
                    delay = Math.max(delay, remaining(t, WINDOW, now));
                }
            }
        }
        return delay;
    }
    private Result acquired(List<String> ids) {
        for (String id : ids) { State s = states.get(id); s.active++; s.reserved++; }
        return new Result(Status.ACQUIRED, new Ticket(ids), 0);
    }
    private static long retry(long nanos) { return nanos == Long.MAX_VALUE ? 0 : nanos / 1000000L + (nanos % 1000000L == 0 ? 0 : 1); }
    private Result result(Status status, long delay) { return new Result(status, null, retry(delay)); }
    private boolean blockedByEarlier(List<String> ds,Waiter self) {
        for(Waiter w:queue) {
            if(w==self) break;
            for(String id:ds) if(w.domains.contains(id)) {
                Policy p=states.get(id).policy;
                if(p.minIntervalMillis>0 || p.rpm>0 || p.maxInFlight>0) return true;
            }
        }
        return false;
    }
    public synchronized boolean ready(Collection<String> ids) { List<String> ds=domains(ids); return !stopped && !blockedByEarlier(ds,null) && delay(ds,clock.nanoTime())==0; }
    public synchronized int queued() { return queue.size(); }
    public synchronized int active() { int n=0; for(State s:states.values()) n+=s.active; return n; }
    /** Never queues; existing waiters for overlapping domains have priority. */
    public synchronized Result tryAcquire(Collection<String> ids) {
        List<String> ds = domains(ids); if (stopped) return result(Status.STOPPED, 0);
        long d = delay(ds, clock.nanoTime());
        return !blockedByEarlier(ds,null) && d == 0 ? acquired(ds) : result(Status.WOULD_BLOCK, d);
    }
    /** deadlineNanos is an absolute clock.nanoTime deadline; zero means no caller deadline.
     * Cancellation implementations should call signalChange on cancellation for immediate wakeup. */
    public synchronized Result await(Collection<String> ids, long deadlineNanos, Cancellation cancel) throws InterruptedException {
        List<String> ds = domains(ids); if (cancel == null) cancel = NEVER;
        long start = clock.nanoTime();
        if (stopped) return result(Status.STOPPED, 0);
        if (cancel.isCancelled()) return result(Status.CANCELLED, 0);
        if (deadlineNanos != 0 && start - deadlineNanos >= 0) return result(Status.DEADLINE, 0);
        long d = delay(ds, start); if (!blockedByEarlier(ds,null) && d == 0) return acquired(ds);
        if (queue.size() >= maxWaiters) return result(Status.QUEUE_FULL, d);
        for (String id : ds) { State s = states.get(id); if (s.policy.maxQueue > 0 && s.queued >= s.policy.maxQueue) return result(Status.QUEUE_FULL, d); }
        Waiter w = new Waiter(ds); queue.addLast(w); for (String id : ds) states.get(id).queued++;
        try {
            while (true) {
                long now = clock.nanoTime();
                if (stopped) return result(Status.STOPPED, 0);
                if (cancel.isCancelled()) return result(Status.CANCELLED, 0);
                if (deadlineNanos != 0 && now - deadlineNanos >= 0) return result(Status.DEADLINE, 0);
                long waitLimit = Long.MAX_VALUE;
                for (String id : ds) { long ms = states.get(id).policy.maxWaitMillis; if (ms > 0) waitLimit = Math.min(waitLimit, remaining(start, ms * 1000000L, now)); }
                if (waitLimit == 0) return result(Status.WAIT_TIMEOUT, 0);
                d = delay(ds, now);
                if (!blockedByEarlier(ds,w) && d == 0) return acquired(ds);
                long sleep = Math.min(waitLimit, d == 0 ? Long.MAX_VALUE : d);
                if (deadlineNanos != 0) { long left = deadlineNanos - now; if (left > 0) sleep = Math.min(sleep, left); }
                // Poll cancellation even if a caller cannot install a callback; no worker threads.
                if(cancel!=NEVER) sleep = Math.min(sleep, 100000000L);
                long millis = sleep / 1000000L; int nanos = (int)(sleep % 1000000L); wait(millis, nanos);
            }
        } finally { queue.remove(w); for (String id : ds) states.get(id).queued--; notifyAll(); }
    }
}
