package com.gaeul.allin;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;

/** Immutable diagnostics, null coverage, clock/phase boundaries and unchanged accounting. */
public final class RequestDiagnosticsOfflineTest {
    static void check(boolean value,String why) { if(!value) throw new AssertionError(why); }
    static final class Clock implements RequestDiagnostics.Clock {
        long now=-9000000000L;
        public long nanoTime() { return now; }
        void ms(long millis) { now+=millis*1000000L; }
    }
    static UsageLedger.Row row(String id,int attempt,boolean terminal) {
        UsageLedger.Row row=new UsageLedger.Row();row.requestId=id;row.attempt=attempt;row.terminal=terminal;
        row.req="group/fixture";row.prov="fixture";row.model="route-model";row.status=200;
        row.err="prompt-sentinel";row.session="full-session-sentinel";row.credentialId="secret-sentinel";
        return row;
    }
    static void whitelist(JSONObject json) {
        Set<String> keys=new HashSet<>(Arrays.asList("schemaVersion","requestedModel","target","logicalDurationMs","queuedMs",
                "patienceWaitMs","failureKind","responsePhase","executionState","deliveryConfirmed","rulesVersion"));
        check(json.keySet().equals(keys),"exact diagnostics whitelist");
        String text=json.toString();
        for(String secret:new String[]{"prompt-sentinel","full-session-sentinel","secret-sentinel"}) check(!text.contains(secret),"sensitive material not copied");
        check(json.isNull("rulesVersion"),"no fictitious rules version");
    }
    static void observations() throws Exception {
        UsageLedger.Row old=row("legacy-primitives",1,true);old.queuedMs=123;old.outboundAt=System.currentTimeMillis();
        JSONObject unknown=RequestDiagnostics.json(old);whitelist(unknown);
        for(String key:new String[]{"logicalDurationMs","queuedMs","patienceWaitMs","failureKind","responsePhase","executionState","deliveryConfirmed"}) check(unknown.isNull(key),"unobserved "+key);
        Clock clock=new Clock();RequestDiagnostics.Logical logical=new RequestDiagnostics.Logical(clock);logical.patienceCovered();
        RequestContext context=new RequestContext("/v1/chat/completions",NetworkPolicy.capture(),60000);
        UsageLedger.Row attempt=row("observed",1,false);RequestDiagnostics.begin(attempt,logical,context);RequestDiagnostics.coverTransport(attempt);
        RequestDiagnostics.admissionRejected(attempt);RequestDiagnostics.outcome(attempt,false,true,"overload",false);
        RequestDiagnostics.freeze(attempt);JSONObject frozen=RequestDiagnostics.json(attempt);whitelist(frozen);
        check("not_sent".equals(frozen.getString("executionState")),"observed before-handoff failure is not sent");
        check("admission".equals(frozen.getString("failureKind")),"admission source not upstream overload");
        check(frozen.isNull("queuedMs"),"unentered admission not a synthetic zero");
        context.headersWritten();context.businessWritten();context.close();
        check("headers".equals(context.diagnosticResponsePhase()),"highest trustworthy phase survives terminal and never trusts framing");
        check(frozen.toString().equals(RequestDiagnostics.json(attempt).toString()),"freeze survives later context/callback changes");
        RequestDiagnostics.handoff(attempt);RequestDiagnostics.nativeCompletion(attempt,true);
        check(frozen.toString().equals(RequestDiagnostics.json(attempt).toString()),"late callbacks do not mutate recorded snapshot");
        clock.ms(10);long waiting=clock.nanoTime();clock.ms(30);logical.waited(waiting);clock.ms(5);
        UsageLedger.Row marker=row("observed",0,true);RequestDiagnostics.marker(marker,logical,context,1);RequestDiagnostics.freeze(marker);
        JSONObject terminal=RequestDiagnostics.json(marker);whitelist(terminal);
        check(terminal.getLong("logicalDurationMs")==45 && terminal.getLong("patienceWaitMs")==30,"negative monotonic origin and actual interrupted-style wait delta");
        check("not_sent".equals(terminal.getString("executionState")),"logical terminal aggregates known attempts");
        check("headers".equals(terminal.getString("responsePhase")),"header milestone retained without business claim");
        RequestDiagnostics.Logical mixed=new RequestDiagnostics.Logical(clock);
        UsageLedger.Row sent=row("mixed",1,false);RequestDiagnostics.begin(sent,mixed,null);RequestDiagnostics.handoff(sent);
        RequestDiagnostics.outcome(sent,false,false,"unknown_execution",false);RequestDiagnostics.freeze(sent);
        UsageLedger.Row blocked=row("mixed",2,false);RequestDiagnostics.begin(blocked,mixed,null);RequestDiagnostics.coverTransport(blocked);
        RequestDiagnostics.admissionRejected(blocked);RequestDiagnostics.outcome(blocked,false,true,"overload",false);RequestDiagnostics.freeze(blocked);
        UsageLedger.Row mixMarker=row("mixed",0,true);RequestDiagnostics.marker(mixMarker,mixed,null,2);RequestDiagnostics.freeze(mixMarker);
        check("unknown_execution".equals(RequestDiagnostics.json(mixMarker).getString("executionState")),"last local rejection cannot erase earlier unknown execution");
        RequestDiagnostics.Logical special=new RequestDiagnostics.Logical(clock);
        UsageLedger.Row uncovered=row("special",1,true);RequestDiagnostics.begin(uncovered,special,null);
        RequestDiagnostics.outcome(uncovered,false,false,"network",false);RequestDiagnostics.freeze(uncovered);
        check(RequestDiagnostics.json(uncovered).isNull("executionState"),"special false-sent not evidence of not sent");
        UsageLedger.Row early=row("early-success",1,true);RequestDiagnostics.begin(early,new RequestDiagnostics.Logical(clock),null);RequestDiagnostics.handoff(early);
        RequestDiagnostics.outcome(early,true,false,"other",false);RequestDiagnostics.freeze(early);
        check(RequestDiagnostics.json(early).isNull("executionState") && RequestDiagnostics.json(early).isNull("deliveryConfirmed"),"early successful status is not delivered");
        JSONObject injected=new JSONObject(terminal.toString()).put("prompt","prompt-sentinel").put("credentialId","secret-sentinel")
                .put("failureKind","arbitrary raw error").put("rulesVersion","made-up").put("queuedMs",-1).put("deliveryConfirmed","true");
        JSONObject parsed=RequestDiagnostics.Snapshot.parse(injected).json();whitelist(parsed);
        check(parsed.isNull("failureKind") && parsed.isNull("queuedMs") && parsed.isNull("deliveryConfirmed"),"parse rejects unsupported values and raw fields");
    }
    static void ledger() throws Exception {
        Path dir=Files.createTempDirectory("diagnostics-ledger-");UsageLedger.init(dir.toFile());
        Clock clock=new Clock();RequestDiagnostics.Logical logical=new RequestDiagnostics.Logical(clock);
        UsageLedger.Row failed=row("accounting",1,false);failed.status=429;failed.err="";failed.usageSource="known";failed.in=3;failed.out=1;
        RequestDiagnostics.begin(failed,logical,null);RequestDiagnostics.handoff(failed);RequestDiagnostics.outcome(failed,false,false,"rate",true);
        UsageLedger.record(failed);
        UsageLedger.Row ok=row("accounting",2,true);ok.tries=2;ok.err="";ok.usageSource="unknown";
        RequestDiagnostics.begin(ok,logical,null);RequestDiagnostics.handoff(ok);UsageLedger.record(ok);
        JSONObject totals=UsageLedger.summary(1,"model").getJSONObject("totals");
        check(totals.getLong("requests")==1 && totals.getLong("attempts")==2 && totals.getLong("attemptFailed")==1 && totals.getLong("tokens")==4,"unchanged logical/attempt/token accounting");
        Map<Integer,String> snapshots=new HashMap<>();JSONArray recent=UsageLedger.recentRequests(10);
        for(int i=0;i<recent.length();i++) {JSONObject row=recent.getJSONObject(i);JSONObject diagnostic=row.getJSONObject("diagnostics");whitelist(diagnostic);snapshots.put(row.getInt("attempt"),diagnostic.toString());}
        String persisted="";try(java.util.stream.Stream<Path> files=Files.list(dir.resolve("usage"))) {
            for(Path file:(Iterable<Path>)files::iterator) persisted+=Files.readString(file);
        }
        check(persisted.contains("\"diagnostics\""),"snapshot stored in the same JSONL ledger");
        UsageLedger.init(dir.toFile());JSONArray reloaded=UsageLedger.recentRequests(10);
        for(int i=0;i<reloaded.length();i++) {JSONObject row=reloaded.getJSONObject(i);check(row.getJSONObject("diagnostics").toString().equals(snapshots.get(row.getInt("attempt"))),"restart retains immutable snapshot");}
        JSONArray usageRecent=UsageLedger.summary(1,"model").getJSONArray("recent");
        for(int i=0;i<usageRecent.length();i++) {JSONObject row=usageRecent.getJSONObject(i);check(row.getJSONObject("diagnostics").toString().equals(snapshots.get(row.getInt("attempt"))),"sessions/usage recent share projection");}
        Method close=UsageLedger.class.getDeclaredMethod("closeWriter");close.setAccessible(true);close.invoke(null);
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);observations();ledger();System.out.println("RequestDiagnosticsOfflineTest OK");
    }
}
