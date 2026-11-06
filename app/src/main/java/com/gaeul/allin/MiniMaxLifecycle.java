package com.gaeul.allin;

import java.util.HashMap;
import java.util.Map;

/** In-memory coordination for one gateway generation. Storage callbacks must perform durable,
 * identity-and-generation-checked replacement under the same configuration transaction lock
 * used for account edits and restore. Never log credential objects. */
final class MiniMaxLifecycle {
    static final class Credentials {
        final String access, refresh;
        final long expires;
        Credentials(String access, String refresh, long expires) {
            if (access == null || access.isEmpty() || refresh == null || refresh.isEmpty())
                throw new IllegalArgumentException("incomplete credentials");
            this.access = access; this.refresh = refresh; this.expires = expires;
        }
    }
    interface Refresh { Credentials run(Credentials old) throws Exception; }
    interface Persist { boolean replace(String id, long generation, Credentials expected, Credentials next) throws Exception; }
    interface Clock { long now(); }
    enum Outcome { SUCCESS, REJECTED, UNKNOWN, CANCELLED_BEFORE_SEND, CANCELLED_AFTER_SEND }
    static final class Unavailable extends Exception { Unavailable(String message) { super(message); } }
    private static final class Entry {
        final long generation;
        Credentials current;
        boolean valid = true, refreshing, poisoned;
        long cooldownUntil;
        Entry(long generation, Credentials credentials) { this.generation = generation; current = credentials; }
    }
    static final class Lease {
        private final Entry entry;
        final String id;
        final long generation;
        final Credentials credentials;
        private boolean finished;
        private Lease(String id, Entry e) { this.id=id; entry=e; generation=e.generation; credentials=e.current; }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;
    private long serial;
    private boolean retired;
    MiniMaxLifecycle(Clock clock) { this.clock = clock; }

    /** Call under config transaction lock when introducing an enabled persisted account.
     * The id must be a stable persisted account id, never a token or array position. */
    synchronized long register(String id, Credentials persisted) {
        if (retired || id == null || id.isEmpty() || persisted == null) throw new IllegalStateException("invalid registration");
        invalidate(id);
        Entry e = new Entry(++serial, persisted); entries.put(id, e); return e.generation;
    }
    synchronized void invalidate(String id) {
        Entry e = entries.remove(id);
        if (e != null) e.valid = false;
        notifyAll();
    }
    synchronized void retire() {
        retired = true;
        for (Entry e : entries.values()) e.valid = false;
        entries.clear(); notifyAll();
    }
    private boolean live(String id, Entry e) { return !retired && e.valid && entries.get(id) == e; }
    synchronized Lease acquire(String id) throws Unavailable {
        Entry e = entries.get(id);
        if (e == null || !live(id,e) || e.poisoned || e.cooldownUntil > clock.now()) throw new Unavailable("account unavailable");
        return new Lease(id,e);
    }
    /** Force refresh only when the failed request used the current credential snapshot. */
    Lease refresh(Lease observed, boolean force, long leadMillis, Refresh operation, Persist persist) throws Exception {
        Entry e = observed.entry;
        Credentials old;
        synchronized (this) {
            for (;;) {
                if (!live(observed.id,e) || e.poisoned || e.cooldownUntil > clock.now()) throw new Unavailable("account invalidated or cooling");
                if (e.current != observed.credentials) return new Lease(observed.id,e);
                if (!force && e.current.expires > clock.now() + leadMillis) return new Lease(observed.id,e);
                if (!e.refreshing) { e.refreshing=true; old=e.current; break; }
                wait();
            }
        }
        Credentials next = null;
        boolean completed = false;
        try {
            next = operation.run(old);
            if (next == null) throw new Unavailable("refresh outcome unknown");
            synchronized (this) {
                if (!live(observed.id,e)) throw new Unavailable("account invalidated during refresh");
            }
            // External transaction must atomically validate generation and current persisted
            // credentials under the config lock. Never hold this monitor while taking that lock.
            if (!persist.replace(observed.id,e.generation,old,next)) throw new Unavailable("persist rejected");
            synchronized (this) {
                if (!live(observed.id,e)) throw new Unavailable("account invalidated during persist");
                e.current=next; completed=true;
                return new Lease(observed.id,e);
            }
        } finally {
            synchronized (this) {
                // A refresh request may have consumed its one-use token even on exception.
                // Preserve old durable state but quarantine it; never silently replay refresh.
                if (!completed && live(observed.id,e)) e.poisoned=true;
                e.refreshing=false; notifyAll();
            }
        }
    }
    synchronized boolean isCurrent(Lease lease) {
        return lease != null && live(lease.id,lease.entry) && !lease.entry.poisoned
                && lease.entry.current == lease.credentials;
    }
    /** A logical request may be finished exactly once. No retry is authorized here. */
    synchronized boolean finish(Lease lease, Outcome outcome, long cooldownMillis) {
        if (lease == null || lease.finished) return false;
        lease.finished=true;
        if (live(lease.id,lease.entry) && cooldownMillis > 0 && outcome != Outcome.SUCCESS
                && outcome != Outcome.CANCELLED_BEFORE_SEND) {
            long now=clock.now();
            lease.entry.cooldownUntil = Math.max(lease.entry.cooldownUntil,
                    now > Long.MAX_VALUE-cooldownMillis ? Long.MAX_VALUE : now+cooldownMillis);
        }
        return true;
    }
}
