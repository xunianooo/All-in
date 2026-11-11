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
    final ProviderQuirks.Snapshot quirks;
    final String explicitSession, authScope;
    final long affinityEpoch;
    volatile AtomicBoolean affinitySemantic = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final Set<Runnable> cleanups = new HashSet<>();
    private volatile Phase phase = Phase.UNCOMMITTED;
    private volatile String reason = "";
    final RequestDiagnostics.Logical diagnosticScope = new RequestDiagnostics.Logical();
    // The existing generic body observer also sees HTTP framing. Do not promote it to diagnostic BUSINESS.
    private boolean diagnosticHeadersSeen;
    public synchronized String diagnosticResponsePhase() { return diagnosticHeadersSeen ? "headers" : "uncommitted"; }
    public RequestContext(String protocol, NetworkPolicy.Snapshot network, long durationMillis) {
        this(protocol, network, durationMillis, null, "", "", 0);
    }
    RequestContext(String protocol, NetworkPolicy.Snapshot network, long durationMillis, ProviderQuirks.Snapshot quirks, String explicitSession, String authScope, long affinityEpoch) {
        this.protocol = protocol; this.network = network;
        this.quirks=quirks; this.explicitSession=explicitSession; this.authScope=authScope; this.affinityEpoch=affinityEpoch;
        this.deadlineNanos = System.nanoTime() + Math.max(1, Math.min(RequestTiming.MAX_REQUEST_MS, durationMillis)) * 1000000L;
    }
    public long remainingMillis() { return Math.max(0, (deadlineNanos-System.nanoTime()+999999L)/1000000L); }
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted() || remainingMillis()==0; }
    public synchronized Phase phase() { return phase; }
    public synchronized void headersWritten() { diagnosticHeadersSeen=true; if(phase==Phase.UNCOMMITTED) phase=Phase.HEADERS; }
    public synchronized void businessWritten() { if(phase!=Phase.TERMINAL) phase=Phase.BUSINESS; }
    public boolean mayReplay() { return phase==Phase.UNCOMMITTED && !isCancelled(); }
    /** Only for a fully read, explicit upstream rejection. Waiting SSE comments
     * may commit headers; they are not model output or evidence of execution. */
    public synchronized boolean mayContinueAfterRejection() {
        return (phase==Phase.UNCOMMITTED || phase==Phase.HEADERS) && !isCancelled();
    }
    public String cancelReason() { return reason; }
    public synchronized void awaitMillis(long millis) throws IOException,InterruptedException {
        long end=System.nanoTime()+Math.max(0,millis)*1000000L;
        while(true) { check(); long left=Math.min(end-System.nanoTime(),deadlineNanos-System.nanoTime());
            if(left<=0) { check();return; }
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,left);
        }
    }
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
        synchronized(this) { reason=why; phase=Phase.TERMINAL; conns=new ArrayList<>(connections); connections.clear(); actions=new ArrayList<>(cleanups); cleanups.clear(); notifyAll(); }
        for(HttpURLConnection c:conns) try { c.disconnect(); } catch(Throwable ignored) { }
        for(Runnable r:actions) try { r.run(); } catch(Throwable ignored) { }
    }
    @Override public void close() { cancel("finished"); }
}
