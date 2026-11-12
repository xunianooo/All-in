package com.gaeul.allin;

import com.gaeul.allin.json.*;
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
    static void harness() throws Exception {
        Path dir=Files.createTempDirectory("harness-diagnostics-"); UsageLedger.init(dir.toFile());
        UsageLedger.Row r=row("harness",1,true); r.err="";
        RequestDiagnostics.begin(r,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.workbuddyHarness(r,2,3); UsageLedger.record(r);
        RequestDiagnostics.workbuddyHarness(r,8,9);
        JSONObject h=RequestDiagnostics.json(r).getJSONObject("workbuddyHarness");
        check(h.keySet().equals(new HashSet<>(Arrays.asList("enabled","messages","descriptions","policyVersion"))),"harness whitelist");
        check(h.getBoolean("enabled") && h.getInt("messages")==2 && h.getInt("descriptions")==3 && "1".equals(h.getString("policyVersion")),"counts frozen");
        h.put("messages",77); check(RequestDiagnostics.json(r).getJSONObject("workbuddyHarness").getInt("messages")==2,"projection deep copy");
        UsageLedger.init(dir.toFile()); JSONObject saved=UsageLedger.recentRequests(10).getJSONObject(0).getJSONObject("diagnostics");
        check(saved.getJSONObject("workbuddyHarness").getInt("messages")==2,"ledger roundtrip");
        JSONObject old=new JSONObject(saved.toString()); old.remove("workbuddyHarness");
        check(!RequestDiagnostics.Snapshot.parse(old).json().has("workbuddyHarness"),"old schema remains absent");
        JSONObject injection=new JSONObject(saved.toString()); injection.getJSONObject("workbuddyHarness").put("prompt","prompt-sentinel").put("credentialId","secret-sentinel");
        String safe=RequestDiagnostics.Snapshot.parse(injection).json().toString();
        check(!safe.contains("prompt-sentinel") && !safe.contains("secret-sentinel"),"nested privacy whitelist");
        for(Object bad:new Object[]{-1,1000001,1.5,"2",JSONObject.NULL}) {
            JSONObject invalid=new JSONObject(saved.toString()); invalid.getJSONObject("workbuddyHarness").put("messages",bad);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("workbuddyHarness"),"reject invalid count: "+bad);
        }
        for(String key:new String[]{"enabled","policyVersion","descriptions"}) {
            JSONObject invalid=new JSONObject(saved.toString()); invalid.getJSONObject("workbuddyHarness").remove(key);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("workbuddyHarness"),"reject missing "+key);
        }
        UsageLedger.Row bad=row("bounds",1,true);RequestDiagnostics.begin(bad,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.workbuddyHarness(bad,1000001,0);RequestDiagnostics.freeze(bad);
        check(!RequestDiagnostics.json(bad).has("workbuddyHarness"),"bounds checked on capture");
        Method close=UsageLedger.class.getDeclaredMethod("closeWriter");close.setAccessible(true);close.invoke(null);
    }
    static void compatibility() throws Exception {
        Path dir=Files.createTempDirectory("compatibility-diagnostics-"); UsageLedger.init(dir.toFile());
        UsageLedger.Row r=row("compatibility",1,true); r.err="";
        RequestDiagnostics.begin(r,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.workbuddyCompatibility(r,1,"original");
        RequestDiagnostics.workbuddyCompatibility(r,2,"comma");
        RequestDiagnostics.workbuddyCompatibility(r,3,"system_as_user");
        RequestDiagnostics.workbuddyCompatibility(r,2,"original");
        UsageLedger.record(r);
        RequestDiagnostics.workbuddyCompatibility(r,3,"learned_comma");
        JSONObject saved=RequestDiagnostics.json(r);
        check(!saved.has("workbuddyHarness"),"compatibility independent of harness");
        JSONObject facts=saved.getJSONObject("workbuddyCompatibility");
        check(facts.keySet().equals(new HashSet<>(Arrays.asList("sends","stage"))),"compatibility whitelist");
        check(facts.getInt("sends")==3 && facts.getString("stage").equals("system_as_user"),"physical sends frozen and monotonic");
        facts.put("sends",1); check(RequestDiagnostics.json(r).getJSONObject("workbuddyCompatibility").getInt("sends")==3,"compatibility projection copy");
        UsageLedger.init(dir.toFile());
        JSONObject reloaded=UsageLedger.recentRequests(10).getJSONObject(0).getJSONObject("diagnostics");
        check(reloaded.getJSONObject("workbuddyCompatibility").getInt("sends")==3,"compatibility ledger roundtrip");
        JSONObject totals=UsageLedger.summary(1,"model").getJSONObject("totals");
        check(totals.getLong("requests")==1 && totals.getLong("attempts")==1,"three sends remain one logical attempt");
        JSONObject old=new JSONObject(reloaded.toString()); old.remove("workbuddyCompatibility");
        old.put("workbuddyHarness",new JSONObject().put("enabled",true).put("messages",0).put("descriptions",0).put("policyVersion","1"));
        JSONObject oldParsed=RequestDiagnostics.Snapshot.parse(old).json();
        check(oldParsed.has("workbuddyHarness") && !oldParsed.has("workbuddyCompatibility"),"old harness leaves physical sends unknown");
        for(Object bad:new Object[]{0,4,-1,1.5,"2",JSONObject.NULL}) {
            JSONObject invalid=new JSONObject(reloaded.toString()); invalid.getJSONObject("workbuddyCompatibility").put("sends",bad);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("workbuddyCompatibility"),"invalid physical sends "+bad);
        }
        for(Object bad:new Object[]{"prompt-sentinel",JSONObject.NULL,2}) {
            JSONObject invalid=new JSONObject(reloaded.toString()); invalid.getJSONObject("workbuddyCompatibility").put("stage",bad);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("workbuddyCompatibility"),"invalid stage "+bad);
        }
        for(String key:new String[]{"sends","stage"}) {
            JSONObject invalid=new JSONObject(reloaded.toString()); invalid.getJSONObject("workbuddyCompatibility").remove(key);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("workbuddyCompatibility"),"missing "+key);
        }
        JSONObject injected=new JSONObject(reloaded.toString()); injected.getJSONObject("workbuddyCompatibility").put("prompt","prompt-sentinel").put("secret","secret-sentinel");
        String clean=RequestDiagnostics.Snapshot.parse(injected).json().toString();
        check(!clean.contains("prompt-sentinel") && !clean.contains("secret-sentinel"),"compatibility nested privacy");
        for(String stage:new String[]{"original","comma","system_as_user","learned_comma","learned_system_as_user"}) {
            UsageLedger.Row sample=row(stage,1,true);RequestDiagnostics.begin(sample,new RequestDiagnostics.Logical(new Clock()),null);
            RequestDiagnostics.workbuddyCompatibility(sample,1,stage);RequestDiagnostics.freeze(sample);
            check(RequestDiagnostics.json(sample).getJSONObject("workbuddyCompatibility").getString("stage").equals(stage),"allowed stage "+stage);
        }
        UsageLedger.Row invalid=row("invalid",1,true);RequestDiagnostics.begin(invalid,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.workbuddyCompatibility(invalid,0,"original");RequestDiagnostics.workbuddyCompatibility(invalid,4,"original");
        RequestDiagnostics.workbuddyCompatibility(invalid,1,"secret-sentinel");RequestDiagnostics.freeze(invalid);
        check(!RequestDiagnostics.json(invalid).has("workbuddyCompatibility"),"invalid capture remains unknown");
        Method close=UsageLedger.class.getDeclaredMethod("closeWriter");close.setAccessible(true);close.invoke(null);
    }
    static void gift() throws Exception {
        UsageLedger.Row local=row("gift-local",1,true); RequestDiagnostics.begin(local,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.zcodeGift(local,0,null,0,"local_busy_hold"); RequestDiagnostics.freeze(local);
        JSONObject held=RequestDiagnostics.json(local).getJSONObject("zcodeGift");
        check(held.getInt("sends")==0 && held.isNull("busyCode") && held.getString("source").equals("local_busy_hold"),"zero-send local hold");
        UsageLedger.Row r=row("gift",1,true); RequestDiagnostics.begin(r,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.zcodeGift(r,1,"3008",0,"upstream");
        RequestDiagnostics.zcodeGift(r,2,null,120,"upstream");
        RequestDiagnostics.zcodeGift(r,3,null,300000,"upstream");
        RequestDiagnostics.freeze(r); RequestDiagnostics.zcodeGift(r,3,"3009",300000,"unknown");
        JSONObject saved=RequestDiagnostics.json(r); JSONObject facts=saved.getJSONObject("zcodeGift");
        check(facts.keySet().equals(new HashSet<>(Arrays.asList("sends","busyCode","retryWaitMs","source"))),"gift whitelist");
        check(facts.getInt("sends")==3 && facts.getString("busyCode").equals("3008") && facts.getLong("retryWaitMs")==300000,"success preserves prior busy code and freezes");
        facts.put("sends",0); check(RequestDiagnostics.json(r).getJSONObject("zcodeGift").getInt("sends")==3,"gift projection copy");
        saved=RequestDiagnostics.json(r); JSONObject old=new JSONObject(saved.toString());old.remove("zcodeGift");
        check(!RequestDiagnostics.Snapshot.parse(old).json().has("zcodeGift"),"old schema leaves gift unknown");
        JSONObject injected=new JSONObject(saved.toString());injected.getJSONObject("zcodeGift").put("prompt","prompt-sentinel").put("uid","secret-sentinel").put("token","token-sentinel");
        String clean=RequestDiagnostics.Snapshot.parse(injected).json().toString();
        check(!clean.contains("sentinel"),"gift privacy filter");
        for(String key:new String[]{"sends","busyCode","retryWaitMs","source"}) {
            JSONObject invalid=new JSONObject(saved.toString());invalid.getJSONObject("zcodeGift").remove(key);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("zcodeGift"),"missing gift "+key);
        }
        for(Object bad:new Object[]{-1,4,1.5,"1",JSONObject.NULL}) {
            JSONObject invalid=new JSONObject(saved.toString());invalid.getJSONObject("zcodeGift").put("sends",bad);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("zcodeGift"),"invalid sends "+bad);
        }
        for(Object bad:new Object[]{-1,300001,1.5,"1",JSONObject.NULL}) {
            JSONObject invalid=new JSONObject(saved.toString());invalid.getJSONObject("zcodeGift").put("retryWaitMs",bad);
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("zcodeGift"),"invalid wait "+bad);
        }
        for(String key:new String[]{"busyCode","source"}) {
            JSONObject invalid=new JSONObject(saved.toString());invalid.getJSONObject("zcodeGift").put(key,"secret-sentinel");
            check(!RequestDiagnostics.Snapshot.parse(invalid).json().has("zcodeGift"),"invalid "+key);
        }
        for(String source:new String[]{"upstream","local_admission","local_cooldown","local_busy_hold","unknown"}) {
            UsageLedger.Row sample=row(source,1,true);RequestDiagnostics.begin(sample,new RequestDiagnostics.Logical(new Clock()),null);
            RequestDiagnostics.zcodeGift(sample,0,"3010",0,source);RequestDiagnostics.freeze(sample);
            check(RequestDiagnostics.json(sample).getJSONObject("zcodeGift").getString("source").equals(source),"valid source");
        }
        UsageLedger.Row invalid=row("bad-gift",1,true);RequestDiagnostics.begin(invalid,new RequestDiagnostics.Logical(new Clock()),null);
        RequestDiagnostics.zcodeGift(invalid,4,null,0,"unknown");RequestDiagnostics.zcodeGift(invalid,0,"secret",0,"unknown");
        RequestDiagnostics.zcodeGift(invalid,0,null,300001,"unknown");RequestDiagnostics.zcodeGift(invalid,0,null,0,null);
        RequestDiagnostics.freeze(invalid);check(!RequestDiagnostics.json(invalid).has("zcodeGift"),"invalid capture rejected");
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);observations();ledger();harness();compatibility();gift();System.out.println("RequestDiagnosticsOfflineTest OK");
    }
}
