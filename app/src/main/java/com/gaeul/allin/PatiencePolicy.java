package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.*;

/** Explicit rejection-only retry policy. No inference replay on transport errors or arbitrary 5xx. */
public final class PatiencePolicy {
    public final int patienceMillis;
    public final boolean heartbeat;
    public PatiencePolicy(int patienceMillis,boolean heartbeat) {
        if(patienceMillis<0 || patienceMillis>60000) throw new IllegalArgumentException("patienceMillis must be 0..60000");
        this.patienceMillis=patienceMillis; this.heartbeat=heartbeat;
    }
    public static PatiencePolicy parse(JSONObject json) {
        if(json==null) return new PatiencePolicy(0,false);
        Iterator<String> fields=json.keys();
        while(fields.hasNext()) if(!Arrays.asList("patienceMillis","heartbeat").contains(fields.next())) throw new IllegalArgumentException("unknown resilience field");
        Object ms=json.opt("patienceMillis"), beat=json.opt("heartbeat");
        if(ms!=null && (!(ms instanceof Integer || ms instanceof Long) || ((Number)ms).longValue()<0 || ((Number)ms).longValue()>60000)) throw new IllegalArgumentException("invalid patienceMillis");
        if(beat!=null && !(beat instanceof Boolean)) throw new IllegalArgumentException("invalid heartbeat");
        return new PatiencePolicy(ms==null?0:((Number)ms).intValue(),Boolean.TRUE.equals(beat));
    }
    public JSONObject json() { return new JSONObject().put("patienceMillis",patienceMillis).put("heartbeat",heartbeat); }
    /** Explicit structured overload codes are evidence of rejection, unlike generic HTTP 503. */
    public static boolean rejection(String provider,int status,String body) {
        UpstreamFailure failure=UpstreamFailure.classify(provider,status,body,true,false);
        if(failure.kind!=UpstreamFailure.Kind.RATE && failure.kind!=UpstreamFailure.Kind.OVERLOAD) return false;
        if(status!=429 && status!=503) return false;
        String code="",type="";
        try { JSONObject j=new JSONObject(body),e=j.optJSONObject("error"); if(e==null)return false; code=e.optString("code","");type=e.optString("type",""); } catch(Exception invalid) { return false; }
        // Both structured fields must agree with the rejection family. An unknown or
        // contradictory nonempty field is not evidence that replay is safe.
        if(status==429) return (code.isEmpty() || Arrays.asList("rate_limit","rate_limit_error","rate_limit_exceeded","requests_rate_limited").contains(code))
                && (type.isEmpty() || "rate_limit_error".equals(type))
                && (!code.isEmpty() || !type.isEmpty());
        return (code.isEmpty() || "overloaded_error".equals(code))
                && (type.isEmpty() || "overloaded_error".equals(type))
                && (!code.isEmpty() || !type.isEmpty());
    }
    /** Invalid/missing Retry-After retains the existing conservative fallback, never 2s bypass. */
    public static long retryDeadline(String header,long wallNow) {
        return CommercialKeyPool.retryAfterDeadline(header,wallNow,60000);
    }
    public static long delayMillis(int completedRounds,long retryRemaining,long jitterMillis) {
        long base=completedRounds<=0?2000:completedRounds==1?4000:8000;
        return Math.max(Math.max(0,retryRemaining),base+Math.max(0,Math.min(250,jitterMillis)));
    }
    /** Measured from first exhausted pass, bounded by request's original absolute monotonic deadline. */
    public static final class Window {
        private final long durationNanos;
        private long start; private boolean started;
        public Window(int durationMillis) { durationNanos=Math.max(0,durationMillis)*1000000L; }
        public long remainingMillis(long now,long requestDeadline) {
            if(!started) { started=true;start=now; }
            long left=Math.min(durationNanos-Math.max(0,now-start),requestDeadline-now);
            return Math.max(0,left/1000000L);
        }
    }
}
