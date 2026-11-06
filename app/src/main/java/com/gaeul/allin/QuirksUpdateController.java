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
    private long generation; private boolean closed; private volatile boolean activeWork;
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
    Status check() {
        if(!store.enabled()) return status;
        FutureTask<Status> task;
        synchronized(this) {
            if(closed) return new Status(false,"retired",store.snapshot());
            if(flight==null && activeWork) return failed("busy");
            if(flight==null) {
                final NetworkPolicy.Snapshot policy=NetworkPolicy.capture();
                activeWork=true; final long id=++generation; final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                flight=new FutureTask<>(()->download(policy,id,deadline)); final FutureTask<Status> submitted=flight; executor.execute(()->{try{submitted.run();}finally{activeWork=false;}});
            }
            task=flight;
        }
        try { return task.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) { synchronized(this) { if(flight==task) { generation++; task.cancel(true); Transport c=active; if(c!=null)try{c.close();}catch(IOException ignored){} return failed("timeout"); } return status; } }
        catch(CancellationException e) { return status; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return new Status(true,"interrupted",store.snapshot()); }
        catch(ExecutionException e) { return failed("failed"); }
        finally { synchronized(this) { if(flight==task && task.isDone()) flight=null; } }
    }
    void close() {
        Transport connection;
        synchronized(this) { if(closed)return; closed=true; generation++; if(flight!=null) flight.cancel(true); connection=active; executor.shutdownNow(); status=new Status(false,"retired",store.snapshot()); }
        if(connection!=null) try{connection.close();}catch(IOException ignored){}
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
