package com.gaeul.allin;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded request facts, frozen into the existing usage row. No payload, error text or identity material. */
public final class RequestDiagnostics {
    interface Clock { long nanoTime(); }
    static final Clock SYSTEM = System::nanoTime;
    private static final Set<String> FAILURES = new HashSet<>(Arrays.asList("rate", "account_throughput", "quota", "auth",
            "request", "overload", "network", "proxy", "unknown_execution", "other", "admission", "deadline", "cancelled", "protocol", "downstream"));
    private static final Set<String> EXECUTIONS = new HashSet<>(Arrays.asList("not_sent", "rejected", "unknown_execution", "delivered"));
    private RequestDiagnostics() { }
    private static String identifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return null;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return null;
        return value;
    }
    private static String ruleVersion(String value) {
        return value != null && value.length()<=64 && value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.]+)?") ? value : null;
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static String enumValue(String value, Set<String> allowed) { return allowed.contains(value) ? value : null; }
    static long elapsedMillis(long start, long end) { return Math.max(0, end - start) / 1000000L; }
    private static Long number(JSONObject json, String key) {
        Object raw = json.opt(key);
        if (!(raw instanceof Number)) return null;
        double value = ((Number)raw).doubleValue(); long integer = ((Number)raw).longValue();
        return value >= 0 && value == integer ? integer : null;
    }

    /** Immutable after settlement; JSON projections always return a new whitelist object. */
    public static final class Snapshot {
        final String requestedModel, provider, model, failureKind, responsePhase, executionState;
        final String rulesVersion;
        final Long logicalDurationMs, queuedMs, patienceWaitMs;
        final Boolean deliveryConfirmed;
        Snapshot(String requested, String provider, String model, Long duration, Long queue, Long patience,
                 String failure, String phase, String execution, Boolean delivery) {
            this(requested, provider, model, duration, queue, patience, failure, phase, execution, delivery, null);
        }
        Snapshot(String requested, String provider, String model, Long duration, Long queue, Long patience,
                 String failure, String phase, String execution, Boolean delivery, String version) {
            rulesVersion = ruleVersion(version);
            requestedModel = identifier(requested); this.provider = identifier(provider); this.model = identifier(model);
            logicalDurationMs = duration; queuedMs = queue; patienceWaitMs = patience;
            failureKind = enumValue(failure, FAILURES);
            responsePhase = "uncommitted".equals(phase) || "headers".equals(phase) || "business".equals(phase) ? phase : null;
            executionState = enumValue(execution, EXECUTIONS); deliveryConfirmed = delivery;
        }
        public JSONObject json() {
            return new JSONObject().put("schemaVersion", 1).put("requestedModel", nullable(requestedModel))
                    .put("target", new JSONObject().put("provider", nullable(provider)).put("model", nullable(model)))
                    .put("logicalDurationMs", nullable(logicalDurationMs)).put("queuedMs", nullable(queuedMs))
                    .put("patienceWaitMs", nullable(patienceWaitMs)).put("failureKind", nullable(failureKind))
                    .put("responsePhase", nullable(responsePhase)).put("executionState", nullable(executionState))
                    .put("deliveryConfirmed", nullable(deliveryConfirmed)).put("rulesVersion", nullable(rulesVersion));
        }
        static Snapshot parse(JSONObject json) {
            if (json == null || json.optInt("schemaVersion", 0) != 1) return null;
            JSONObject target = json.optJSONObject("target"); Object delivered = json.opt("deliveryConfirmed");
            Snapshot parsed = new Snapshot(json.optString("requestedModel", null), target == null ? null : target.optString("provider", null),
                    target == null ? null : target.optString("model", null), number(json,"logicalDurationMs"), number(json,"queuedMs"),
                    number(json,"patienceWaitMs"), json.optString("failureKind",null), json.optString("responsePhase",null),
                    json.optString("executionState",null), delivered instanceof Boolean ? (Boolean)delivered : null, json.optString("rulesVersion",null));
            return parsed;
        }
    }

    /** Process-local, monotonic logical scope. Its values are never persisted directly. */
    static final class Logical {
        final Clock clock; final long started;
        private boolean patienceCovered, unknown, unobserved, rejected;
        private long patienceNanos;
        private int pending, observed;
        private String lastProvider, lastModel, lastFailure, unknownFailure;
        Logical() { this(SYSTEM); }
        Logical(Clock clock) { this.clock = clock; started = clock.nanoTime(); }
        synchronized void startAttempt(String provider, String model) { pending++; lastProvider=provider; lastModel=model; }
        synchronized void patienceCovered() { patienceCovered=true; }
        synchronized void waited(long started) { patienceNanos += Math.max(0, clock.nanoTime()-started); }
        synchronized Long patienceMillis() { return patienceCovered ? Math.max(0,patienceNanos)/1000000L : null; }
        synchronized void settleAttempt(String execution, String failure) {
            pending--; observed++; lastFailure=failure;
            if ("unknown_execution".equals(execution)) { unknown=true; unknownFailure=failure; }
            else if (execution == null) unobserved=true;
            else if ("rejected".equals(execution)) rejected=true;
        }
        synchronized String terminalExecution(int attempts) {
            if (pending > 0) return null;
            if (unknown) return "unknown_execution";
            if (unobserved) return null;
            if (rejected) return "rejected";
            return observed > 0 || attempts == 0 ? "not_sent" : null;
        }
        synchronized String terminalFailure() { return unknown ? unknownFailure : lastFailure; }
        synchronized String provider() { return lastProvider; }
        synchronized String model() { return lastModel; }
    }

    /** Mutable only until the owner's existing ledger settle. No diagnostic event log. */
    static final class Observation {
        final Logical logical; final RequestContext context; final boolean marker; final int attempts;
        boolean transportCovered, handedOff, frozen;
        Long queuedMs; String failureKind, executionState; Boolean deliveryConfirmed;
        Observation(Logical logical, RequestContext context, boolean marker, int attempts, String provider, String model) {
            this.logical=logical; this.context=context; this.marker=marker; this.attempts=attempts;
            if (!marker && logical != null) logical.startAttempt(provider,model);
        }
    }
    static void begin(UsageLedger.Row row, Logical logical, RequestContext context) {
        row.diagnosticObservation = new Observation(logical,context,false,row.attempt,row.prov,row.model);
    }
    static void marker(UsageLedger.Row row, Logical logical, RequestContext context, int attempts) {
        row.diagnosticObservation = new Observation(logical,context,true,attempts,null,null);
    }
    static void coverTransport(UsageLedger.Row row) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) o.transportCovered=true; }
    }
    static void handoff(UsageLedger.Row row) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) { o.transportCovered=true; o.handedOff=true; } }
    }
    static void admissionElapsed(UsageLedger.Row row, long start) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) {
            long value=elapsedMillis(start,SYSTEM.nanoTime()); o.queuedMs=(o.queuedMs==null?0:o.queuedMs)+value;
        } }
    }
    static void admissionRejected(UsageLedger.Row row) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) o.failureKind="admission"; }
    }
    static void protocolFailure(UsageLedger.Row row) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) { o.failureKind="protocol"; o.executionState="unknown_execution"; } }
    }
    static void outcome(UsageLedger.Row row, boolean ok, boolean local, String failure, boolean definiteRejection) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) {
            if(o.frozen || ok || "protocol".equals(o.failureKind)) return;
            if(o.transportCovered) {
                o.executionState=!o.handedOff?"not_sent":(!local && definiteRejection?"rejected":"unknown_execution");
                if(o.failureKind==null && o.handedOff) o.failureKind=enumValue(failure,FAILURES);
            }
        }
    }
    /** NativeAnthropicStream's success listener runs only after terminal validation AND writer confirmation. */
    static void nativeCompletion(UsageLedger.Row row, boolean success) {
        Observation o=row==null?null:row.diagnosticObservation;
        if(o!=null) synchronized(o) { if(!o.frozen) {
            if(success) { o.deliveryConfirmed=true; o.executionState="delivered"; }
            else if(o.transportCovered && o.handedOff) o.executionState="unknown_execution";
        } }
    }
    private static String cancellation(RequestContext context) {
        if(context==null) return null;
        String reason=context.cancelReason();
        if("deadline".equals(reason) || context.remainingMillis()==0) return "deadline";
        if("downstream write failed".equals(reason)) return "downstream";
        if(!reason.isEmpty() && !"finished".equals(reason)) return "cancelled";
        return null;
    }
    static Snapshot unobserved(UsageLedger.Row row) {
        // Legacy primitives (e.g. queuedMs=0 or status=200) do not establish observations.
        return new Snapshot(row.req,row.isAttempt()?row.prov:null,row.isAttempt()?row.model:null,null,null,null,null,null,null,null);
    }
    static void freeze(UsageLedger.Row row) {
        if(row==null || row.diagnostics!=null) return;
        Observation o=row.diagnosticObservation;
        if(o==null) { row.diagnostics=unobserved(row); return; }
        synchronized(o) {
            if(row.diagnostics!=null) return;
            o.frozen=true;
            String execution=o.marker && o.logical!=null?o.logical.terminalExecution(o.attempts):o.executionState;
            String failure=o.marker && o.logical!=null?o.logical.terminalFailure():o.failureKind;
            String cancel=cancellation(o.context);
            if(cancel!=null && !"delivered".equals(execution)) failure=cancel;
            if(!o.marker && o.logical!=null) o.logical.settleAttempt(execution,failure);
            String provider=o.marker && o.logical!=null?o.logical.provider():row.prov;
            String model=o.marker && o.logical!=null?o.logical.model():row.model;
            Long duration=row.isTerminal() && o.logical!=null?elapsedMillis(o.logical.started,o.logical.clock.nanoTime()):null;
            Long patience=row.isTerminal() && o.logical!=null?o.logical.patienceMillis():null;
            row.diagnostics=new Snapshot(row.req,provider,model,duration,o.queuedMs,patience,failure,
                    o.context==null?null:o.context.diagnosticResponsePhase(),execution,o.deliveryConfirmed,
                    o.context==null || o.context.quirks==null?null:o.context.quirks.ruleVersion);
        }
    }
    public static JSONObject json(UsageLedger.Row row) { return (row.diagnostics==null?unobserved(row):row.diagnostics).json(); }
}
