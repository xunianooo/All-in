package com.gaeul.allin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Process-local policy for ZCode Start Plan. Never apply to other plans. */
public final class ZCodeGiftBusyPolicy {
    private ZCodeGiftBusyPolicy() {}
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }

    public static String accountKey(String jwt, String device, String stableAccount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            for (String s : (stableAccount != null && !stableAccount.trim().isEmpty()
                    ? new String[]{"stable-account", stableAccount.trim()}
                    : new String[]{"credentials", jwt, device})) {
                if (s == null) { mac.update(new byte[]{-1,-1,-1,-1}); continue; }
                byte[] b = s.getBytes(StandardCharsets.UTF_8); int n=b.length;
                mac.update(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n}); mac.update(b);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean busy(int status, String json) { return busyCode(status,json) != null; }
    /** Returns 3008/3009/3010, or empty string for a codeless message match; null otherwise. */
    public static String busyCode(int status, String json) {
        if (status != 429 || json == null) return null;
        try {
            Object v = new Parser(json).parse();
            if (!(v instanceof Map)) return null;
            Map<?,?> root=(Map<?,?>)v;
            Object err=root.get("error"); Map<?,?> nested=err instanceof Map ? (Map<?,?>)err : null;
            Object a=root.get("code"), b=nested == null ? null : nested.get("code");
            boolean hasA=root.containsKey("code") && a != null, hasB=nested != null && nested.containsKey("code") && b != null;
            String ac=code(a), bc=code(b);
            if (hasA || hasB) {
                if ((hasA && ac == null) || (hasB && bc == null) || (hasA && hasB && !ac.equals(bc))) return null;
                String c=hasA ? ac : bc;
                return c.equals("3008") || c.equals("3009") || c.equals("3010") ? c : null;
            }
            for (Object m : new Object[]{root.get("msg"),root.get("message"),nested == null ? null : nested.get("message"),err instanceof String ? err : null})
                if (m instanceof String && ((String)m).toLowerCase(Locale.ROOT).contains("concurrency limit exceeded")) return "";
        } catch (RuntimeException ignored) { }
        return null;
    }
    private static String code(Object o) { return o instanceof String || o instanceof Number ? o.toString() : null; }

    public interface Clock { long millis(); }
    private static final Clock MONOTONIC = () -> System.nanoTime()/1000000L;
    public static final class BusyHold {
        private final Clock clock; private final ArrayDeque<Long> hits=new ArrayDeque<>(); private long until; private boolean armed;
        public BusyHold() { this(MONOTONIC); }
        public BusyHold(Clock clock) { this.clock=Objects.requireNonNull(clock); }
        /** Make one tracker per logical request; call busyResponse for each explicit upstream busy. */
        public LogicalRequest logicalRequest() { return new LogicalRequest(this); }
        /** Low-level hook: caller must invoke at most once per logical request after its second busy. */
        public synchronized void sustainedCap() {
            long now=clock.millis(); if (armed && now < until) return; armed=false;
            while (!hits.isEmpty() && now-hits.peekFirst()>60000) hits.removeFirst();
            hits.addLast(now); if(hits.size()>=5) { hits.clear(); until=now+180000; armed=true; }
        }
        public synchronized long remainingMillis() { return armed ? Math.max(0,until-clock.millis()) : 0; }
        public synchronized void reset() { hits.clear(); until=0; armed=false; }
    }

    public static final class LogicalRequest {
        private final BusyHold hold; private int busyCount;
        private LogicalRequest(BusyHold hold) { this.hold=hold; }
        public synchronized void busyResponse() { if (busyCount < 2 && ++busyCount == 2) hold.sustainedCap(); }
    }

    public static final class Gate {
        private final int limit, perQueue, globalQueue, maxLanes; private final long waitMillis;
        private final Clock clock; private final Map<String,Lane> lanes=new HashMap<>(); private int queued;
        private static final class Lane { int active; final ArrayDeque<Object> queue=new ArrayDeque<>(); }
        public Gate() { this(3,120000,32,256,256,MONOTONIC); }
        public Gate(int limit,long waitMillis,int perQueue,int globalQueue,int maxLanes,Clock clock) {
            if(limit<1 || waitMillis<0 || perQueue<0 || globalQueue<0 || maxLanes<1) throw new IllegalArgumentException();
            this.limit=limit; this.waitMillis=waitMillis; this.perQueue=perQueue; this.globalQueue=globalQueue; this.maxLanes=maxLanes; this.clock=Objects.requireNonNull(clock);
        }
        /** Null means local capacity/queue timeout. Cancellation/deadline throws IOException; interruption throws InterruptedException. */
        public Slot acquire(String opaqueKey, RequestContext ctx) throws IOException, InterruptedException {
            Objects.requireNonNull(opaqueKey); Objects.requireNonNull(ctx);
            final Runnable wake=() -> { synchronized(Gate.this) { Gate.this.notifyAll(); } };
            ctx.onClose(wake);
            try { synchronized(this) {
                ctx.check(); Lane lane=lanes.get(opaqueKey);
                if(lane==null) { if(lanes.size()>=maxLanes) return null; lane=new Lane(); lanes.put(opaqueKey,lane); }
                if(lane.active<limit && lane.queue.isEmpty()) { lane.active++; return new Slot(this,opaqueKey,lane); }
                if(lane.queue.size()>=perQueue || queued>=globalQueue || waitMillis==0) { cleanup(opaqueKey,lane); return null; }
                Object ticket=new Object(); lane.queue.addLast(ticket); queued++; long start=clock.millis();
                try {
                    while(true) {
                        ctx.check(); long left=waitMillis-(clock.millis()-start);
                        if(left<=0) return null;
                        if(lane.queue.peekFirst()==ticket && lane.active<limit) {
                            lane.queue.removeFirst(); queued--; ticket=null; lane.active++; notifyAll(); return new Slot(this,opaqueKey,lane);
                        }
                        wait(Math.max(1,Math.min(left,ctx.remainingMillis())));
                    }
                } finally { if(ticket!=null) { lane.queue.remove(ticket); queued--; cleanup(opaqueKey,lane); notifyAll(); } }
            }} finally { ctx.removeCleanup(wake); }
        }
        private void cleanup(String key,Lane lane) { if(lane.active==0 && lane.queue.isEmpty() && lanes.get(key)==lane) lanes.remove(key); }
        private synchronized void release(String key,Lane lane) { lane.active--; cleanup(key,lane); notifyAll(); }
    }
    public static final class Slot implements AutoCloseable {
        private final Gate gate; private final String key; private final Gate.Lane lane; private boolean closed;
        private Slot(Gate gate,String key,Gate.Lane lane) { this.gate=gate;this.key=key;this.lane=lane; }
        @Override public synchronized void close() { if(!closed) { closed=true; gate.release(key,lane); } }
    }

    /** Strict JSON grammar with duplicate-key rejection and bounded nesting. */
    private static final class Parser {
        final String s; int i; Parser(String s){this.s=s;}
        Object parse(){ Object v=value(0); ws(); if(i!=s.length()) throw bad(); return v; }
        RuntimeException bad(){return new IllegalArgumentException("invalid JSON");}
        void ws(){while(i<s.length() && (s.charAt(i)==' ' || s.charAt(i)=='\n' || s.charAt(i)=='\r' || s.charAt(i)=='\t')) i++;}
        Object value(int depth){
            if(depth>64) throw bad(); ws(); if(i>=s.length()) throw bad(); char c=s.charAt(i);
            if(c=='"') return string();
            if(c=='{'){ i++; Map<String,Object> m=new HashMap<>(); ws(); if(take('}')) return m; do { ws(); if(i>=s.length() || s.charAt(i)!='"') throw bad(); String k=string(); ws(); if(!take(':') || m.containsKey(k)) throw bad(); m.put(k,value(depth+1)); ws(); if(take('}')) return m; } while(take(',')); throw bad(); }
            if(c=='['){ i++; List<Object> a=new ArrayList<>(); ws(); if(take(']')) return a; do { a.add(value(depth+1)); ws(); if(take(']')) return a; } while(take(',')); throw bad(); }
            if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;} if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;} if(s.startsWith("null",i)){i+=4;return null;}
            int start=i; if(take('-') && i>=s.length()) throw bad(); if(take('0')) { } else { if(i>=s.length() || s.charAt(i)<'1' || s.charAt(i)>'9') throw bad(); while(i<s.length() && digit(s.charAt(i))) i++; }
            if(take('.')){if(i>=s.length() || !digit(s.charAt(i))) throw bad(); while(i<s.length() && digit(s.charAt(i))) i++;}
            if(i<s.length() && (s.charAt(i)=='e'||s.charAt(i)=='E')){i++; if(i<s.length() && (s.charAt(i)=='+'||s.charAt(i)=='-')) i++; if(i>=s.length()||!digit(s.charAt(i))) throw bad(); while(i<s.length()&&digit(s.charAt(i)))i++;}
            return new java.math.BigDecimal(s.substring(start,i));
        }
        boolean digit(char c){return c>='0'&&c<='9';} boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        String string(){ i++; StringBuilder b=new StringBuilder(); while(i<s.length()){char c=s.charAt(i++); if(c=='"')return b.toString(); if(c<32)throw bad(); if(c=='\\'){if(i==s.length())throw bad(); c=s.charAt(i++); switch(c){case '"':case '\\':case '/':b.append(c);break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'u':if(i+4>s.length())throw bad(); try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException e){throw bad();} i+=4;break;default:throw bad();}}else b.append(c);} throw bad(); }
    }
}
