package com.gaeul.allin;

import android.annotation.TargetApi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One controller per process. check() is synchronous and must run off the UI thread. */
final class QuirksUpdateController {
    private static final int DESCRIPTOR_MAX=8192;
    interface Transport extends Closeable { byte[] fetch(NetworkPolicy.Snapshot policy,String url) throws IOException; }
    interface TransportFactory { Transport open(long deadline,int timeout) throws IOException; }
    static final class TrustConfig {
        final String url; final PublicKey key;
        private TrustConfig(String url,PublicKey key) { this.url=url; this.key=key; }
        static TrustConfig disabled() { return new TrustConfig(null,null); }
        /** Only pass bytes read by application code from an APK asset, never from UI or network. */
        @TargetApi(26)
        static TrustConfig fromApkAsset(byte[] data) throws Exception {
            if(data==null) return disabled();
            if(data.length==0 || data.length>DESCRIPTOR_MAX) throw new IllegalArgumentException("descriptor size");
            String text=new String(data,StandardCharsets.UTF_8);
            if(!Arrays.equals(data,text.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("descriptor UTF8");
            new BoundedStrictJson(text).run(); JSONObject obj=new JSONObject(text);
            if(obj.length()!=3 || !obj.has("schemaVersion") || !obj.has("url") || !obj.has("rsaPublicKeySpkiBase64") || !Integer.valueOf(1).equals(obj.get("schemaVersion"))) throw new IllegalArgumentException("descriptor schema");
            Object u=obj.get("url"), k=obj.get("rsaPublicKeySpkiBase64");
            if(!(u instanceof String) || !(k instanceof String)) throw new IllegalArgumentException("descriptor types");
            URI uri=new URI((String)u);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getPort()!=-1 || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawPath()==null || uri.getRawPath().isEmpty() || !uri.getRawPath().startsWith("/") || !uri.normalize().equals(uri) || !uri.toASCIIString().equals(u)) throw new IllegalArgumentException("descriptor URL");
            byte[] der=Base64.getDecoder().decode((String)k);
            PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if(!(key instanceof java.security.interfaces.RSAPublicKey) || ((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()<2048 || ((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()>4096) throw new IllegalArgumentException("descriptor key");
            return new TrustConfig((String)u,key);
        }
    }
    static final class Status {
        final boolean enabled; final String result,version,source;
        Status(boolean e,String r,ProviderQuirks.Snapshot s){enabled=e;result=r;version=s.ruleVersion;source=s.source;}
        JSONObject toJson(){ JSONObject o=new JSONObject(); try {o.put("enabled",enabled);o.put("result",result);o.put("version",version);o.put("source",source);}catch(JSONException e){throw new IllegalStateException(e);}return o; }
    }
    private final TrustConfig config; private final SignedQuirksStore store; private final TransportFactory factory;
    private final int timeoutMs; private final ExecutorService executor;
    private FutureTask<Status> flight; private volatile Status status;
    private volatile Transport active;
    private long generation; private boolean closed;
    /**
     * A flight some check already gave up on: it was cancelled at the deadline and its worker has
     * not left {@link #download} yet. Until it does, no new download may start — the abandoned
     * worker is still talking to the network, and letting a second one run beside it is exactly the
     * double-flight the "busy" answer exists to prevent. Cleared by that worker, under the lock, in
     * {@link #finishWorker}; never by a waiter.
     */
    private FutureTask<Status> abandoned;
    QuirksUpdateController(TrustConfig cfg,File cache,byte[] builtin,int androidApi) throws Exception {
        this(cfg,cache,builtin,androidApi,(deadline,timeout)-> { QuirksHttpsTransport t=new QuirksHttpsTransport(deadline,timeout); return new Transport() { public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{return t.fetch(p,u);} public void close(){t.close();} }; },15000);
    }
    // Package-private for isolated offline tests; production uses only the constructor above.
    QuirksUpdateController(TrustConfig cfg,File cache,byte[] builtin,int androidApi,TransportFactory f,int timeout) throws Exception {
        if(cfg==null || f==null || timeout<1) throw new IllegalArgumentException("arguments");
        config=androidApi>=26 ? cfg : TrustConfig.disabled(); factory=f;timeoutMs=timeout;
        store=new SignedQuirksStore(cache,config.key,builtin);
        String initial=store.enabled()?"ready":"disabled";
        try { if(store.restore()) initial="cache_restored"; } catch(SignedQuirksStore.OlderRulesException e) { initial="builtin_newer"; } catch(Exception e) { initial="cache_invalid"; }
        status=new Status(store.enabled(),initial,store.snapshot());
        executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"quirks-update");t.setDaemon(true);return t;});
    }
    ProviderQuirks.Snapshot snapshot(){return store.snapshot();}
    Status status(){return status;}
    /**
     * Answer the caller without ever inventing state. "busy", "interrupted" and "retired" describe
     * this call only, so they are returned as throwaway values; a reply that mutates {@link #status}
     * is reserved for an outcome of the work itself. Otherwise a request that merely found the
     * updater busy would leave that word in the panel after the real check had already finished.
     *
     * <p>Concurrent callers join the one download in progress. A caller that arrives after that
     * download finished — the flight's worker still returning, a few instructions behind — starts
     * the next download rather than being told the updater is busy: the previous check left no work
     * running, only bookkeeping. {@link #finishWorker} is what used to create that false window,
     * because a waiter retired the flight while the worker cleared its own flag afterwards; the
     * worker now retires the flight itself, so its own completion and the handle disappearing are
     * one step and half-finished state is not observable.
     *
     * <p>"busy" remains for the one case that is not bookkeeping: a flight that timed out. Its
     * worker can still be inside the network call, and a second download started next to it could
     * be overtaken by work the caller was already told had failed.
     */
    Status check() {
        if(!store.enabled()) return status;
        FutureTask<Status> task;
        synchronized(this) {
            if(closed) return new Status(false,"retired",store.snapshot());
            if(abandoned!=null) return new Status(true,"busy",store.snapshot());
            task=flight;
            if(task==null || task.isDone()) {
                /* Nothing joinable is left: start this call's own download. Cancelled handles only
                   exist while abandoned is set, so a done handle here is a completed check whose
                   worker is merely tidying up. */
                if(task!=null && task.isCancelled()) return new Status(true,"busy",store.snapshot());
                final NetworkPolicy.Snapshot policy=NetworkPolicy.capture();
                final long id=++generation;
                final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                final FutureTask<Status> created=new FutureTask<>(()->download(policy,id,deadline));
                flight=created;
                try { executor.execute(()->{ try { created.run(); } finally { finishWorker(created); } }); }
                catch(RuntimeException rejected) {   // no worker will ever run: undo, and say so
                    if(flight==created) flight=null;
                    generation++;
                    return failed("failed");
                }
                task=created;
            }
        }
        try { return task.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) {
            synchronized(this) {
                /* The deadline and the completion can land together; a finished flight's own answer
                   is the truth and must not be overwritten with "timeout". */
                if(task.isDone()) return answerOf(task);
                if(flight!=task) return status;             // superseded while this call waited
                generation++;
                task.cancel(true);
                /* From here until the worker leaves download(), this controller has work running
                   that no caller can join, so every other caller is told "busy". */
                abandoned=task;
                Transport c=active; if(c!=null)try{c.close();}catch(IOException ignored){}
                return failed("timeout");
            }
        }
        catch(CancellationException e) {
            /* Only a cancelled flight ends here, which means some other call gave up on it and its
               worker may still be running. Report that instead of starting a second download or
               handing this caller the result of a call it did not make. */
            synchronized(this) { return closed ? new Status(false,"retired",store.snapshot()) : new Status(true,"busy",store.snapshot()); }
        }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return new Status(true,"interrupted",store.snapshot()); }
        catch(ExecutionException e) { return failed("failed"); }
    }

    void close() {
        Transport connection;
        synchronized(this) { if(closed)return; closed=true; generation++; connection=active;
            /* Publish the retired state before cancelling: cancel() wakes a waiting check() at once,
               and that caller must not be able to observe the pre-close status. */
            status=new Status(false,"retired",store.snapshot());
            if(flight!=null) flight.cancel(true); executor.shutdownNow(); }
        if(connection!=null) try{connection.close();}catch(IOException ignored){}
    }
    /**
     * The worker's own exit, and the only place a flight handle is retired. Doing it here rather
     * than in a waiter means "the flight completed" and "the handle is gone" happen in one step
     * under the lock, so no caller can observe the gap between them.
     */
    private void finishWorker(FutureTask<Status> task) {
        synchronized(this) {
            if(abandoned==task) abandoned=null;
            if(flight==task) flight=null;
        }
    }
    /** Read a finished flight's own result, for a call that raced its completion. */
    private Status answerOf(FutureTask<Status> task) {
        try { return task.get(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return new Status(true,"interrupted",store.snapshot()); }
        catch(CancellationException e) { return new Status(true,"busy",store.snapshot()); }
        catch(ExecutionException e) { return failed("failed"); }
    }
    private Status failed(String result) { Status s=new Status(true,result,store.snapshot()); status=s;return s; }
    private synchronized Status failureFor(long id,String result) {return !closed && id==generation ? failed(result) : new Status(true,"timeout",store.snapshot());}
    private Status download(NetworkPolicy.Snapshot policy,long id,long deadline) {
        Transport c=null;
        try {
            c=factory.open(deadline,timeoutMs);
            synchronized(this) { if(closed || id!=generation) { c.close(); return new Status(true,"timeout",store.snapshot()); } active=c; }
            byte[] body=c.fetch(policy,config.url);
            synchronized(this) {
                if(closed || id!=generation || Thread.currentThread().isInterrupted() || System.nanoTime()-deadline>=0) return new Status(true,"timeout",store.snapshot());
                boolean changed=store.install(body);
                Status s=new Status(true,changed?"updated":"unchanged",store.snapshot());status=s;return s;
            }
        } catch(SignedQuirksStore.OlderRulesException e) { synchronized(this) {return !closed && id==generation ? failed("newer_local") : new Status(true,"timeout",store.snapshot());} }
        catch(Exception e) { synchronized(this) {return !closed && id==generation ? failed("failed") : new Status(true,"timeout",store.snapshot());} } finally {if(c!=null)try{c.close();}catch(IOException ignored){} if(active==c)active=null;}
    }
}
