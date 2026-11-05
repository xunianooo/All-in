package com.gaeul.allin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Process-local token load; refs must identify provider/model and never contain credentials.
 * No work is scheduled in the background. A lease must be completed or cancelled.
 * At capacity, the oldest idle entry is evicted. If all entries are in flight,
 * previously unseen refs cannot be reserved: reserve/pickAndReserve return null
 * if no eligible tracked ref can be selected. Active entries are never evicted.
 */
public final class RecentUsageScheduler {
    public interface Clock { long now(); }
    private static final int LIMIT = 4096;
    private static final double HALF_LIFE_MS = 3600000.0;
    private static final double RESERVATION = 1024.0;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private long sequence;

    private static final class Entry {
        double score;
        long time, touched;
        int inFlight;
        Entry(long now, long touched) { this.time = now; this.touched = touched; }
    }
    public RecentUsageScheduler() {
        this(new Clock() { public long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); } });
    }
    public RecentUsageScheduler(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock");
        this.clock = clock;
    }
    private void decay(Entry e, long now) {
        if (now > e.time) {
            // Double subtraction avoids overflow for deliberately extreme test clocks.
            e.score *= Math.pow(0.5, ((double) now - (double) e.time) / HALF_LIFE_MS);
            e.time = now;
        }
    }
    private boolean valid(String ref) { return ref != null && !ref.isEmpty(); }
    private Entry ensure(String ref, long now) {
        Entry e = entries.get(ref);
        if (e != null) return e;
        if (entries.size() >= LIMIT) {
            String oldest = null;
            long age = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                Entry candidate = item.getValue();
                if (candidate.inFlight == 0 && candidate.touched < age) {
                    age = candidate.touched; oldest = item.getKey();
                }
            }
            if (oldest == null) return null;
            entries.remove(oldest);
        }
        e = new Entry(now, ++sequence);
        entries.put(ref, e);
        return e;
    }
    public synchronized Lease reserve(String ref) {
        if (!valid(ref)) return null;
        long now = clock.now();
        Entry e = ensure(ref, now);
        if (e == null) return null;
        decay(e, now); e.inFlight++; e.touched = ++sequence;
        return new Lease(this, ref, e);
    }
    /** Atomic selection and reservation; equal loads retain input order. */
    public synchronized Lease pickAndReserve(List<String> orderedEligibleRefs) {
        if (orderedEligibleRefs == null) return null;
        long now = clock.now();
        String best = null;
        double bestLoad = Double.POSITIVE_INFINITY;
        boolean hasIdle = entries.size() < LIMIT;
        if (!hasIdle) for (Entry e : entries.values()) if (e.inFlight == 0) { hasIdle = true; break; }
        for (String ref : new LinkedHashSet<String>(orderedEligibleRefs)) {
            if (!valid(ref)) continue;
            Entry e = entries.get(ref);
            if (e == null && !hasIdle) continue;
            if (e != null) decay(e, now);
            double load = e == null ? 0 : e.score + e.inFlight * RESERVATION;
            if (best == null || load < bestLoad) { best = ref; bestLoad = load; }
        }
        if (best == null) return null;
        Entry e = ensure(best, now);
        if (e == null) return null;
        e.inFlight++; e.touched = ++sequence;
        return new Lease(this, best, e);
    }
    /** Diagnostic ordering only: does not reserve, insert, or evict. */
    public synchronized List<String> order(List<String> refs) {
        if (refs == null) return Collections.emptyList();
        final long now = clock.now();
        List<String> result = new ArrayList<String>();
        for (String ref : new LinkedHashSet<String>(refs)) if (valid(ref)) result.add(ref);
        Collections.sort(result, new Comparator<String>() {
            public int compare(String a, String b) { return Double.compare(load(a, now), load(b, now)); }
        });
        return result;
    }
    private double load(String ref, long now) {
        Entry e = entries.get(ref);
        if (e == null) return 0;
        decay(e, now);
        return e.score + e.inFlight * RESERVATION;
    }
    synchronized double score(String ref) { Entry e = entries.get(ref); if (e == null) return 0; decay(e, clock.now()); return e.score; }
    synchronized int inFlight(String ref) { Entry e = entries.get(ref); return e == null ? 0 : e.inFlight; }
    synchronized int size() { return entries.size(); }

    /** Capture only reported, trustworthy dimensions; reasoning is included in output. */
    private static double weight(UsageLedger.Row r) {
        if (r == null || r.status < 200 || r.status >= 300 || (r.err != null && !r.err.isEmpty())) return 0;
        String source = r.usageSource;
        boolean known = "known".equals(source), partial = "partial".equals(source);
        double n = 0;
        if (known || (partial && r.inputKnown)) n += Math.max(0, r.in);
        if (known || (partial && r.outputKnown)) n += Math.max(0, r.out);
        if (r.cacheInputExcludesCache && !r.cacheUnknown && "known".equals(r.cacheSource)) {
            n += Math.max(0, r.cacheRead);
            n += Math.max(0, r.cacheWrite);
        }
        return n > 0 ? n : 1;
    }
    public static final class Lease implements AutoCloseable {
        public final String target;
        private final RecentUsageScheduler owner;
        private final Entry entry;
        private boolean done, rowReceived, deliveryRequired, deliveryReceived;
        private double rowWeight;
        private Lease(RecentUsageScheduler owner, String target, Entry entry) {
            this.owner = owner; this.target = target; this.entry = entry;
        }
        /** Enable the delivery gate before the first complete or deliveryComplete call. */
        public boolean requiresDelivery() { synchronized(owner) { return deliveryRequired; } }
        public void requireDelivery() {
            synchronized (owner) {
                if (done || rowReceived || deliveryReceived)
                    throw new IllegalStateException("delivery gate must be enabled before completion");
                deliveryRequired = true;
            }
        }
        /** The first row result is accepted once, even while waiting for delivery. */
        public boolean complete(UsageLedger.Row row) {
            synchronized (owner) {
                if (done || rowReceived) return false;
                rowWeight = weight(row);
                rowReceived = true;
                if (rowWeight == 0 || !deliveryRequired || deliveryReceived) settle(rowWeight);
                return true;
            }
        }
        /** First delivery result wins. Failure cancels immediately; success waits for the row. */
        public void deliveryComplete(boolean success) {
            synchronized (owner) {
                if (done || deliveryReceived) return;
                deliveryReceived = true;
                if (!success) settle(0);
                else if (rowReceived) settle(rowWeight);
            }
        }
        // owner lock held; never call external code while holding it.
        private void settle(double amount) {
            done = true;
            owner.decay(entry, owner.clock.now());
            entry.inFlight--;
            entry.score += amount;
            entry.touched = ++owner.sequence;
        }
        public void cancel() {
            synchronized (owner) {
                if (!done) settle(0);
            }
        }
        public void close() { cancel(); }
    }
}
