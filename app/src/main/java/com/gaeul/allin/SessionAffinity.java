package com.gaeul.allin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Optional, process-local preference. Callers must revalidate every preferred target. */
public final class SessionAffinity {
    public interface Clock { long nowMillis(); } // monotonic
    public enum State { BYPASS, OWNER, WAIT, BOUND }
    public static final class Key {
        private final String digest;
        private Key(String digest) { this.digest = digest; }
        @Override public boolean equals(Object o) { return o instanceof Key && digest.equals(((Key)o).digest); }
        @Override public int hashCode() { return digest.hashCode(); }
        @Override public String toString() { return "SessionAffinity.Key"; }
    }
    /** Identity values only: never pass a credential secret. */
    public static final class Target {
        public final String provider, origin, model, credentialId;
        public Target(String provider, String origin, String model, String credentialId) {
            this.provider = required(provider); this.origin = required(origin);
            this.model = required(model); this.credentialId = required(credentialId);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Target)) return false;
            Target t = (Target)o;
            return provider.equals(t.provider) && origin.equals(t.origin) && model.equals(t.model) && credentialId.equals(t.credentialId);
        }
        @Override public int hashCode() { return Objects.hash(provider, origin, model, credentialId); }
        @Override public String toString() { return "SessionAffinity.Target"; }
    }
    private static final class Entry {
        final CountDownLatch done = new CountDownLatch(1);
        final long started;
        long boundAt;
        Target target;
        Entry(long now) { started = now; }
    }
    public final class Lease implements AutoCloseable {
        public final State state;
        public final Target preferred;
        private final Key key;
        private final Entry entry;
        private Lease(State s, Key k, Entry e, Target t) { state=s; key=k; entry=e; preferred=t; }
        /** WAIT only; outside the map lock. Reacquire after return, cancellation or timeout. */
        public boolean await(long millis) throws InterruptedException {
            if (state != State.WAIT || millis < 0) throw new IllegalStateException();
            return entry.done.await(millis, TimeUnit.MILLISECONDS);
        }
        /** OWNER only, after successful complete delivery; false if expired/cleared/replaced. */
        public boolean bind(Target target) {
            Objects.requireNonNull(target);
            if (state != State.OWNER) return false;
            synchronized (SessionAffinity.this) {
                long now=clock.nowMillis();
                if (entries.get(key)!=entry || entry.target!=null || expired(entry,now)) {
                    if (entries.get(key)==entry && entry.target==null) remove(key,entry);
                    return false;
                }
                entry.target=target; entry.boundAt=now; entry.done.countDown(); return true;
            }
        }
        /** Releasing an owner is idempotent and cannot erase its successful binding. */
        @Override public void close() {
            if (state != State.OWNER) return;
            synchronized (SessionAffinity.this) {
                if (entries.get(key)==entry && entry.target==null) remove(key,entry);
            }
        }
    }
    private final Clock clock;
    private final int capacity;
    private final long ttlMillis, reservationMillis;
    private final byte[] secret = new byte[32];
    private final LinkedHashMap<Key,Entry> entries = new LinkedHashMap<Key,Entry>(16,0.75f,true);
    public SessionAffinity() { this(() -> System.nanoTime()/1000000L, 1024, 30*60*1000L, 60000L); }
    public SessionAffinity(Clock clock, int capacity, long ttlMillis, long reservationMillis) {
        this.clock=Objects.requireNonNull(clock);
        if (capacity<1 || ttlMillis<1 || reservationMillis<1) throw new IllegalArgumentException();
        this.capacity=capacity; this.ttlMillis=ttlMillis; this.reservationMillis=reservationMillis;
        new SecureRandom().nextBytes(secret);
    }
    private static String required(String s) {
        if (s==null || s.trim().isEmpty()) throw new IllegalArgumentException("identity required");
        return s;
    }
    /** Process-keyed opaque fingerprint for short-lived request metadata. */
    public String fingerprint(String value) {
        if (value==null || value.trim().isEmpty() || value.length()>1024) return "";
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            byte[] digest=mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(64);
            for(byte b:digest) out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return out.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    /** Only pass an explicitly supplied trusted session header; disabled/missing means bypass. */
    public Key key(boolean enabled, String explicitSessionHeader, String scope, String protocol, String requestedModel) {
        if (!enabled || explicitSessionHeader==null || explicitSessionHeader.trim().isEmpty()) return null;
        String[] parts={explicitSessionHeader, required(scope), required(protocol), required(requestedModel)};
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            for (String part:parts) {
                byte[] b=part.getBytes(StandardCharsets.UTF_8);
                mac.update(new byte[]{(byte)(b.length>>>24),(byte)(b.length>>>16),(byte)(b.length>>>8),(byte)b.length});
                mac.update(b);
            }
            byte[] digest=mac.doFinal(); StringBuilder hex=new StringBuilder(64);
            for (byte b:digest) hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return new Key(hex.toString());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private boolean expired(Entry e,long now) {
        long age=now-(e.target==null ? e.started : e.boundAt);
        return age<0 || age >= (e.target==null ? reservationMillis : ttlMillis);
    }
    private void remove(Key key,Entry entry) { entries.remove(key); entry.done.countDown(); }
    private void purge(long now) {
        Iterator<Map.Entry<Key,Entry>> it=entries.entrySet().iterator();
        while (it.hasNext()) { Entry e=it.next().getValue(); if (expired(e,now)) { it.remove(); e.done.countDown(); } }
    }
    /** Nonblocking; WAIT caller may wait bounded by its own request deadline, or bypass. */
    public synchronized Lease acquire(Key key) {
        if (key==null) return new Lease(State.BYPASS,null,null,null);
        long now=clock.nowMillis(); purge(now);
        Entry e=entries.get(key);
        if (e!=null) return new Lease(e.target==null ? State.WAIT : State.BOUND,key,e,e.target);
        if (entries.size()>=capacity) {
            Iterator<Map.Entry<Key,Entry>> it=entries.entrySet().iterator(); boolean evicted=false;
            while (it.hasNext()) { Entry old=it.next().getValue(); if (old.target!=null) { it.remove(); old.done.countDown(); evicted=true; break; } }
            if (!evicted) return new Lease(State.BYPASS,null,null,null);
        }
        e=new Entry(now); entries.put(key,e); return new Lease(State.OWNER,key,e,null);
    }
    /** Remove a stale preferred target only if it still matches; then reacquire normally. */
    public synchronized void invalidate(Key key, Target target) {
        if (key==null || target==null) return;
        Entry e=entries.get(key); if (e!=null && target.equals(e.target)) remove(key,e);
    }
    /** Call on configuration epoch change, disabling affinity, or restore. */
    public synchronized void clear() { for (Entry e:entries.values()) e.done.countDown(); entries.clear(); }
}
