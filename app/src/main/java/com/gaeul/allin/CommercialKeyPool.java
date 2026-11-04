package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** In-memory scheduling only. Never log Credential or its secret. */
public final class CommercialKeyPool {
    public interface Clock { long now(); }
    public enum Scope { NONE, KEY, KEY_MODEL, PROVIDER }
    public static final class Credential {
        public final String credentialId;
        private final String secret;
        private Credential(String value) { secret = value; credentialId = id(value); }
        public String secret() { return secret; }
        @Override public String toString() { return "Credential(" + credentialId + ")"; }
    }
    public static final class Decision {
        public final boolean retryKey, routeFallback;
        public final Scope scope;
        private Decision(boolean key, boolean route, Scope scope) {
            this.retryKey = key; this.routeFallback = route; this.scope = scope;
        }
    }
    public static final class Budget {
        private final Clock clock;
        private final long deadline;
        private final int maxAttempts;
        private int attempts;
        public Budget(Clock clock) { this(clock, 8, 60000); }
        public Budget(Clock clock, int maxAttempts, long durationMs) {
            if (clock == null || maxAttempts < 0 || durationMs < 0) throw new IllegalArgumentException();
            this.clock = clock; this.maxAttempts = maxAttempts;
            this.deadline = add(clock.now(), durationMs);
        }
        public synchronized boolean tryStart() {
            if (attempts >= maxAttempts || clock.now() >= deadline) return false;
            attempts++; return true;
        }
        public synchronized int attempts() { return attempts; }
        public long deadline() { return deadline; }
    }
    private static final class RestKey {
        final Scope scope; final String provider, credential, model;
        RestKey(Scope s, String p, String c, String m) { scope=s; provider=p; credential=c; model=m; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RestKey)) return false;
            RestKey k=(RestKey)o;
            return scope==k.scope && Objects.equals(provider,k.provider) && Objects.equals(credential,k.credential) && Objects.equals(model,k.model);
        }
        @Override public int hashCode() { return Objects.hash(scope,provider,credential,model); }
    }
    private final Clock clock;
    private final Map<RestKey,Long> rests = new HashMap<>();
    private final Map<String,Long> positions = new HashMap<>();
    public CommercialKeyPool() { this(System::currentTimeMillis); }
    public CommercialKeyPool(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    /** All-in currently saves strings. Object compatibility: {key, off}, optionally enabled. */
    public static List<Credential> list(JSONArray keys) {
        LinkedHashMap<String,Credential> result = new LinkedHashMap<>();
        for (int i=0; keys != null && i<keys.length(); i++) {
            Object item=keys.opt(i); String value=null;
            if (item instanceof String) value=(String)item;
            else if (item instanceof JSONObject) {
                JSONObject obj=(JSONObject)item;
                if (obj.optBoolean("off", false) || !obj.optBoolean("enabled", true)) continue;
                Object raw=obj.opt("key"); if (raw instanceof String) value=(String)raw;
            }
            if (value == null || (value=value.trim()).isEmpty()) continue;
            Credential c=new Credential(value); result.putIfAbsent(c.credentialId,c);
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }
    private static String id(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out=new char[digest.length*2], hex="0123456789abcdef".toCharArray();
            for (int i=0;i<digest.length;i++) { int b=digest[i]&255; out[i*2]=hex[b>>>4]; out[i*2+1]=hex[b&15]; }
            return new String(out);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static RestKey restKey(Scope scope, String provider, String credentialId, String model) {
        Objects.requireNonNull(provider);
        if (scope==Scope.KEY) return new RestKey(scope,provider,Objects.requireNonNull(credentialId),null);
        if (scope==Scope.KEY_MODEL) return new RestKey(scope,provider,Objects.requireNonNull(credentialId),Objects.requireNonNull(model));
        if (scope==Scope.PROVIDER) return new RestKey(scope,provider,null,null);
        throw new IllegalArgumentException("no cooldown scope");
    }
    public synchronized void cool(Scope scope, String provider, String credentialId, String model, long deadline) {
        RestKey k=restKey(scope,provider,credentialId,model);
        Long old=rests.get(k); if (old==null || deadline>old) rests.put(k,deadline);
    }
    private boolean active(RestKey k, long now) {
        Long deadline=rests.get(k);
        if (deadline==null) return false;
        if (deadline>now) return true;
        // Under the pool lock: expiry and removal cannot race with an extension.
        rests.remove(k); return false;
    }
    public synchronized boolean available(String provider, String model, Credential credential) {
        long now=clock.now();
        return !active(restKey(Scope.PROVIDER,provider,null,null),now)
            && !active(restKey(Scope.KEY,provider,credential.credentialId,null),now)
            && !active(restKey(Scope.KEY_MODEL,provider,credential.credentialId,model),now);
    }
    /** Attempted set is per logical request; selection marks it and advances only on selection. */
    public synchronized Credential choose(String provider, String model, List<Credential> candidates, Set<String> attempted) {
        Objects.requireNonNull(attempted); Objects.requireNonNull(candidates);
        int size=candidates.size(); if (size==0) return null;
        long position=positions.containsKey(provider) ? positions.get(provider) : 0L;
        int start=(int)Math.floorMod(position,(long)size);
        for (int offset=0;offset<size;offset++) {
            int i=(int)(((long)start+offset)%size); Credential c=candidates.get(i);
            if (!attempted.contains(c.credentialId) && available(provider,model,c)) {
                attempted.add(c.credentialId); positions.put(provider,(long)i+1); return c;
            }
        }
        return null;
    }
    public synchronized long availableAt(String provider, String model, Credential c) {
        long now=clock.now(), result=now;
        for (Scope s : new Scope[]{Scope.PROVIDER,Scope.KEY,Scope.KEY_MODEL}) {
            RestKey k=restKey(s,provider,c.credentialId,model);
            if (active(k,now)) result=Math.max(result,rests.get(k));
        }
        return result;
    }
    private static long add(long a,long b) { return b>0 && a>Long.MAX_VALUE-b ? Long.MAX_VALUE : a+b; }
    /** Invalid or negative headers use fallbackMs. Valid enormous values saturate, never wrap. */
    public static long retryAfterDeadline(String header, long now, long fallbackMs) {
        if (fallbackMs<0) throw new IllegalArgumentException();
        if (header != null) {
            String s=header.trim();
            if (s.matches("[0-9]+")) {
                BigInteger ms=new BigInteger(s).multiply(BigInteger.valueOf(1000));
                BigInteger deadline=BigInteger.valueOf(now).add(ms);
                return deadline.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>=0 ? Long.MAX_VALUE : deadline.longValue();
            }
            String[] formats={"EEE, dd MMM yyyy HH:mm:ss 'GMT'", "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", "EEE MMM d HH:mm:ss yyyy"};
            for (String format:formats) {
                SimpleDateFormat parser=new SimpleDateFormat(format,Locale.US);
                parser.setLenient(false); parser.setTimeZone(TimeZone.getTimeZone("GMT"));
                if (format.contains("yy HH")) {
                    Calendar start=Calendar.getInstance(TimeZone.getTimeZone("GMT"),Locale.US);
                    start.setTimeInMillis(now); start.add(Calendar.YEAR,-50);
                    parser.set2DigitYearStart(start.getTime());
                }
                ParsePosition p=new ParsePosition(0); Date date=parser.parse(s,p);
                if (date!=null && p.getIndex()==s.length()) return Math.max(now,date.getTime());
            }
        }
        return add(now,fallbackMs);
    }
    /** Input is normalized code/message, not logged; handoff and unknown execution always block replay. */
    public static Decision classify(int status, String code, String message, boolean handedOff) {
        if (handedOff || status<=0) return new Decision(false,false,Scope.NONE);
        String text=((code==null?"":code)+" "+(message==null?"":message)).toLowerCase(Locale.ROOT);
        boolean invalid=contains(text,"invalid_request","invalid request","context_length","context length","context window","model_not_found","model not found","invalid model","invalid parameter");
        if (invalid) return new Decision(false,false,Scope.NONE);
        boolean shared=contains(text,"organization","organisation","project","shared quota","provider outage","service unavailable","capacity","overloaded");
        boolean rate=status==429;
        boolean quota=status==402 && contains(text,"quota","balance","credit","insufficient_quota","insufficient balance","1113");
        boolean auth=status==401 || (status==403 && contains(text,"auth","api key","api_key","credential","invalid key"));
        if (shared && (rate || quota || status==503 || status==502)) return new Decision(false,true,Scope.PROVIDER);
        if (rate || quota || auth) {
            boolean model=rate && contains(text,"model_rate","model rate","model rate limit","per-model","per model");
            return new Decision(true,true,model?Scope.KEY_MODEL:Scope.KEY);
        }
        return new Decision(false,false,Scope.NONE);
    }
    private static boolean contains(String value,String... terms) { for (String term:terms) if (value.contains(term)) return true; return false; }
}
