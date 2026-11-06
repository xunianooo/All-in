package com.gaeul.allin;

import android.annotation.TargetApi;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

/** Offline signed rules and last-known-good store. No configured key means disabled. */
@TargetApi(26)
final class SignedQuirksStore {
    private static final int MAGIC = 0x51524b31, MAX_SIGNATURE = 512;
    private static final byte[] DOMAIN = new byte[]{'A','l','l','I','n','-','Q','2',0,1};
    private final File cache;
    private final PublicKey trust;
    private volatile ProviderQuirks.Snapshot current;
    private byte[] currentPayload;

    SignedQuirksStore(File cache, PublicKey trust, byte[] builtinBytes) throws Exception {
        ProviderQuirks.Snapshot builtin = ProviderQuirks.parse(builtinBytes);
        if (cache == null || builtin == null) throw new IllegalArgumentException("missing arguments");
        if (trust != null && (!(trust instanceof RSAPublicKey) || ((RSAPublicKey)trust).getModulus().bitLength() < 2048 || ((RSAPublicKey)trust).getModulus().bitLength() > 4096)) throw new IllegalArgumentException("RSA trust key required");
        this.cache=cache; this.trust=trust; this.current=builtin; this.currentPayload=builtinBytes.clone();
    }
    boolean enabled() { return trust != null; }
    ProviderQuirks.Snapshot snapshot() { return current; }

    /** Caller supplies exactly one bounded binary envelope; no network or credentials here. */
    synchronized boolean install(byte[] envelope) throws Exception {
        Verified v=verify(envelope);
        int cmp=compare(v.snapshot.ruleVersion,current.ruleVersion);
        if(cmp<0) throw new IllegalArgumentException("rollback");
        if(cmp==0) {
            if(!Arrays.equals(v.payload,currentPayload)) throw new IllegalArgumentException("version collision");
            return false;
        }
        writeAtomic(envelope);
        currentPayload=v.payload.clone(); current=v.snapshot.withSource("verified-update");
        return true;
    }
    synchronized boolean restore() throws Exception {
        if (!enabled() || !cache.isFile()) return false;
        byte[] bytes;
        try (InputStream in=new FileInputStream(cache)) { bytes=readBounded(in, maxEnvelope()); }
        Verified v=verify(bytes);
        int cmp=compare(v.snapshot.ruleVersion,current.ruleVersion);
        if(cmp<0) throw new IllegalArgumentException("cached rollback");
        if(cmp==0 && !Arrays.equals(currentPayload,v.payload)) throw new IllegalArgumentException("version collision");
        if(cmp==0) return false;
        currentPayload=v.payload.clone(); current=v.snapshot.withSource("verified-cache"); return true;
    }
    private Verified verify(byte[] bytes) throws Exception {
        if(!enabled()) throw new IllegalStateException("updates disabled");
        if(bytes==null || bytes.length>maxEnvelope()) throw new IllegalArgumentException("envelope size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC) throw new IllegalArgumentException("envelope format");
        int n=in.readInt(); if(n<1 || n>ProviderQuirks.MAX_BYTES) throw new IllegalArgumentException("payload size");
        byte[] payload=new byte[n]; in.readFully(payload);
        int s=in.readUnsignedShort(); if(s<1 || s>MAX_SIGNATURE) throw new IllegalArgumentException("signature size");
        byte[] sig=new byte[s]; in.readFully(sig); if(in.read()!=-1) throw new IllegalArgumentException("trailing bytes");
        Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(trust); verifier.update(DOMAIN); verifier.update(payload);
        if(!verifier.verify(sig)) throw new SecurityException("invalid signature");
        ProviderQuirks.Snapshot snapshot=ProviderQuirks.parse(payload);
        compare(snapshot.ruleVersion,snapshot.ruleVersion); // restrict remote versions to numeric triples
        return new Verified(payload,snapshot);
    }
    private static int compare(String a,String b) {
        String[] x=a.split("\\.",-1), y=b.split("\\.",-1);
        if(x.length!=3 || y.length!=3) throw new IllegalArgumentException("numeric version required");
        for(int i=0;i<3;i++) {
            if(!x[i].matches("0|[1-9][0-9]{0,8}") || !y[i].matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("numeric version required");
            
        }
        for(int i=0;i<3;i++) { int z=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i])); if(z!=0)return z; }
        return 0;
    }
    private void writeAtomic(byte[] bytes) throws IOException {
        File dir=cache.getAbsoluteFile().getParentFile();
        if(!dir.isDirectory()) throw new IOException("cache directory missing");
        File tmp=File.createTempFile(".q2-", ".tmp",dir);
        try {
            try(FileOutputStream out=new FileOutputStream(tmp)) { out.write(bytes); out.flush(); out.getFD().sync(); }
            Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if(tmp.exists()) tmp.delete(); }
    }
    static int maxEnvelope() { return 4+4+ProviderQuirks.MAX_BYTES+2+MAX_SIGNATURE; }
    static byte[] readBounded(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[2048]; int n;
        while((n=in.read(buf))!=-1) { if(n==0) continue; if(out.size()>max-n) throw new IOException("too large"); out.write(buf,0,n); }
        return out.toByteArray();
    }
    private static final class Verified {
        final byte[] payload; final ProviderQuirks.Snapshot snapshot;
        Verified(byte[] p,ProviderQuirks.Snapshot s){payload=p;snapshot=s;}
    }
}
