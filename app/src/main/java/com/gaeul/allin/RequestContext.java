package com.gaeul.allin;

import java.net.HttpURLConnection;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit request lifetime. Only the gateway response writer advances delivery phases. */
public final class RequestContext implements OutboundLimiter.Cancellation, AutoCloseable {
    public enum Phase { UNCOMMITTED, HEADERS, BUSINESS, TERMINAL }
    public final String requestId = UUID.randomUUID().toString();
    public final String protocol;
    public final NetworkPolicy.Snapshot network;
    public final long deadlineNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final Set<Runnable> cleanups = new HashSet<>();
    private volatile Phase phase = Phase.UNCOMMITTED;
    private volatile String reason = "";
    public RequestContext(String protocol, NetworkPolicy.Snapshot network, long durationMillis) {
        this.protocol = protocol; this.network = network;
        this.deadlineNanos = System.nanoTime() + Math.max(1, Math.min(300000, durationMillis)) * 1000000L;
    }
    public long remainingMillis() { return Math.max(0, (deadlineNanos-System.nanoTime()+999999L)/1000000L); }
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted() || remainingMillis()==0; }
    public synchronized Phase phase() { return phase; }
    public synchronized void headersWritten() { if(phase==Phase.UNCOMMITTED) phase=Phase.HEADERS; }
    public synchronized void businessWritten() { if(phase!=Phase.TERMINAL) phase=Phase.BUSINESS; }
    public boolean mayReplay() { return phase==Phase.UNCOMMITTED && !isCancelled(); }
    public String cancelReason() { return reason; }
    public void check() throws IOException { if(isCancelled()) throw new IOException("Request cancelled or deadline exceeded"); }
    public void attach(HttpURLConnection connection) throws IOException {
        synchronized(this) { check(); connections.add(connection); }
    }
    public synchronized void detach(HttpURLConnection connection) { connections.remove(connection); }
    public void onClose(Runnable action) {
        synchronized(this) { if(!cancelled.get()) { cleanups.add(action); return; } }
        action.run();
    }
    public synchronized void removeCleanup(Runnable action) { cleanups.remove(action); }
    public void cancel(String why) {
        if(!cancelled.compareAndSet(false,true)) return;
        List<HttpURLConnection> conns; List<Runnable> actions;
        synchronized(this) { reason=why; phase=Phase.TERMINAL; conns=new ArrayList<>(connections); connections.clear(); actions=new ArrayList<>(cleanups); cleanups.clear(); }
        for(HttpURLConnection c:conns) try { c.disconnect(); } catch(Throwable ignored) { }
        for(Runnable r:actions) try { r.run(); } catch(Throwable ignored) { }
    }
    @Override public void close() { cancel("finished"); }
}
