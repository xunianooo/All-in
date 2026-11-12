package com.gaeul.allin;

import java.io.File;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONArray;

public class UsageLedgerAttemptsOfflineTest {
    static void eq(long got,long want,String what) { if(got!=want) throw new AssertionError(what+": "+got+" != "+want); }
    static void check(boolean b,String what) { if(!b) throw new AssertionError(what); }
    static JSONObject day(JSONArray days,String key,String date) { for(int i=0;i<days.length();i++) { JSONObject d=days.getJSONObject(i); if(date.equals(d.optString(key))) return d; } throw new AssertionError(date); }
    static UsageLedger.Row row(long t,String id,int attempt,boolean terminal,int status,String source,int in,int out) {
        UsageLedger.Row r=new UsageLedger.Row(); r.t=t; r.requestId=id; r.attempt=attempt; r.terminal=terminal; r.status=status; r.usageSource=source; r.in=in; r.out=out; return r;
    }
    public static void main(String[] args) throws Exception {
        TimeZone old=TimeZone.getDefault(), utc=TimeZone.getTimeZone("UTC"); TimeZone.setDefault(utc);
        try {
            File dir=Files.createTempDirectory("attempt-ledger").toFile(); UsageLedger.init(dir);
            Calendar c=Calendar.getInstance(utc); c.setTimeInMillis(System.currentTimeMillis()); c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0);
            long today=c.getTimeInMillis(), now=today+3600000;
            UsageLedger.Row legacy=row(today-1000,"",1,true,500,"",99,99); UsageLedger.record(legacy);
            legacy=row(today-1000,"",1,true,200,"",10,1); legacy.cacheUnknown=true; UsageLedger.record(legacy);
            UsageLedger.Row fail=row(today-1000,"abc",1,false,429,"known",20,2); fail.credentialId="cred1"; fail.cacheSource="known"; fail.cacheRead=5; UsageLedger.record(fail);
            UsageLedger.Row success=row(today+1000,"abc",2,true,200,"known",30,3); success.tries=2; success.cacheSource="known"; success.cacheRead=6; UsageLedger.record(success);
            UsageLedger.Row unknown=row(today+2000,"xyz",1,false,500,"unknown",999,999); UsageLedger.record(unknown);
            UsageLedger.Row marker=row(today+3000,"xyz",0,true,503,"unknown",0,0); marker.tries=1; UsageLedger.record(marker);
            UsageLedger.Row estimate=row(today+4000,"est",1,true,200,"estimated",Integer.MAX_VALUE,Integer.MAX_VALUE); estimate.inputKnown=true; estimate.outputKnown=true; UsageLedger.record(estimate);
            JSONObject s=UsageLedger.summary(2,"model",now,utc), totals=s.getJSONObject("totals");
            eq(totals.getLong("requests"),5,"logical requests"); eq(totals.getLong("failed"),2,"logical failures");
            eq(totals.getLong("attempts"),6,"attempts"); eq(totals.getLong("attemptFailed"),3,"attempt failures");
            eq(totals.getLong("tokens"),4294967360L,"tokens and long arithmetic"); eq(totals.getLong("usageUnknown"),1,"unknown attempts"); eq(totals.getLong("usageEstimated"),1,"estimated attempts");
            check(Math.abs(totals.getDouble("hitRate")-22.0)<0.01,"cache denominator and numerator");
            JSONArray daily=s.getJSONArray("daily"), heat=UsageLedger.heatmap(now,utc).getJSONArray("days");
            for(int i=0;i<daily.length();i++) { JSONObject d=daily.getJSONObject(i), h=day(heat,"date",d.getString("day")); for(String k:new String[]{"requests","failed","attempts","attemptFailed","tokens"}) eq(d.getLong(k),h.getLong(k),"daily heat "+k); }
            eq(daily.getJSONObject(0).getLong("requests"),2,"first day logical"); eq(daily.getJSONObject(0).getLong("tokens"),33,"first day tokens");
            String csv=UsageLedger.csv(2); check(csv.contains("request_id,credential_id,attempt,terminal,usage_source"),"csv metadata"); check(csv.contains("abc,cred1,1,false,known"),"csv attempt");
            UsageLedger.init(dir); JSONObject restart=UsageLedger.summary(2,"model",now,utc).getJSONObject("totals");
            for(String k:new String[]{"requests","failed","attempts","attemptFailed","tokens","usageUnknown","usageEstimated"}) eq(restart.getLong(k),totals.getLong(k),"restart "+k);
            // Different provider/protocol attempts across midnight: terminal attribution,
            // failed reported usage, unknown success timing and terminal-only marker.
            File more=Files.createTempDirectory("attempt-protocols").toFile(); UsageLedger.init(more);
            long clock=System.currentTimeMillis();
            Calendar midnight=Calendar.getInstance(utc); midnight.setTimeInMillis(clock);
            midnight.set(Calendar.HOUR_OF_DAY,0); midnight.set(Calendar.MINUTE,0); midnight.set(Calendar.SECOND,0); midnight.set(Calendar.MILLISECOND,0);
            long boundary=midnight.getTimeInMillis();
            UsageLedger.Row a=row(boundary-1000,"cross",1,false,429,"known",100,10);
            a.prov="openai"; a.ep="chat"; a.session="session"; a.cacheSource="known"; a.cacheRead=20; UsageLedger.record(a);
            UsageLedger.Row b=row(boundary+1,"cross",2,true,200,"unknown",0,0);
            b.prov="anthropic"; b.ep="messages"; b.session="session"; b.tries=2; b.ttft=123; b.ms=500; UsageLedger.record(b);
            UsageLedger.Row f=row(boundary+2,"failed",1,false,500,"known",7,3);
            f.prov="gemini"; f.ep="generateContent"; f.session="session"; UsageLedger.record(f);
            UsageLedger.Row m=row(boundary+3,"failed",0,true,503,"unknown",0,0);
            m.prov="gemini"; m.session="session"; m.ms=700; UsageLedger.record(m);
            // Direct Totals exercise synthetic successful terminal marker timing.
            UsageLedger.Totals timing=new UsageLedger.Totals();
            UsageLedger.Row successMarker=row(clock,"marker",0,true,200,"unknown",0,0); successMarker.ttft=42; successMarker.ms=200;
            timing.add(successMarker); eq(timing.timed,1,"marker timed"); eq(timing.ttftSum,42,"marker ttft"); eq(timing.msSum,200,"marker duration"); eq(timing.attempts,0,"marker not attempt");
            JSONObject protocol=UsageLedger.summary(2,"provider",boundary+1000,utc), pt=protocol.getJSONObject("totals");
            eq(pt.getLong("requests"),2,"protocol requests"); eq(pt.getLong("failed"),1,"protocol failed");
            eq(pt.getLong("attempts"),3,"protocol attempts"); eq(pt.getLong("tokens"),120,"protocol reported failed usage");
            eq(pt.getLong("timed"),1,"unknown usage timed"); eq(pt.getLong("meanTtft"),123,"unknown usage ttft"); eq(pt.getLong("avgMs"),600,"marker latency");
            check(Math.abs(pt.getDouble("cacheCoverage")-33.3)<0.01,"cache coverage");
            JSONObject session=protocol.getJSONArray("sessions").getJSONObject(0); eq(session.getLong("requests"),2,"session requests"); eq(session.getLong("attempts"),3,"session attempts");
            long[] win=UsageLedger.windowTotals(3); eq(win[0],2,"window requests"); eq(win[1],120,"window tokens"); eq(win[2],1,"window failed");
            JSONArray providers=UsageLedger.providerStats(3); JSONObject openai=day(providers,"providerId","openai"), anthropic=day(providers,"providerId","anthropic"), gemini=day(providers,"providerId","gemini");
            eq(openai.getLong("requests"),0,"failed nonterminal provider requests"); eq(openai.getLong("tokens"),110,"failed provider tokens");
            eq(anthropic.getLong("requests"),1,"success provider requests"); eq(gemini.getLong("requests"),1,"marker provider requests"); eq(gemini.getLong("failures"),1,"marker provider failure");
            long hourlyReq=0,hourlyTokens=0; JSONArray hours=UsageLedger.hourlyStats(3);
            for(int i=0;i<hours.length();i++) { hourlyReq+=hours.getJSONObject(i).getLong("requests"); hourlyTokens+=hours.getJSONObject(i).getLong("tokens"); }
            eq(hourlyReq,2,"hourly requests"); eq(hourlyTokens,120,"hourly tokens");
            JSONArray pd=protocol.getJSONArray("daily"), ph=UsageLedger.heatmap(boundary+1000,utc).getJSONArray("days");
            for(int i=0;i<pd.length();i++) { JSONObject d=pd.getJSONObject(i), h=day(ph,"date",d.getString("day")); for(String k:new String[]{"requests","failed","attempts","attemptFailed","tokens"}) eq(d.getLong(k),h.getLong(k),"protocol daily "+k); }
            String[] lines=UsageLedger.csv(3).split("\n");
            boolean foundUnknown=false;
            for(String line:lines) if(line.contains(",cross,,2,true,unknown,")) {
                foundUnknown=true;
                String[] fields=line.split(",",-1); check(fields[4].isEmpty() && fields[5].isEmpty() && fields[21].isEmpty() && fields[22].isEmpty(),"CSV unknown stays blank");
            }
            check(foundUnknown,"CSV unknown row present");
            UsageLedger.init(more); JSONObject again=UsageLedger.summary(2,"provider",boundary+1000,utc).getJSONObject("totals");
            for(String k:new String[]{"requests","failed","attempts","tokens","timed","meanTtft","avgMs"}) eq(again.getLong(k),pt.getLong(k),"protocol restart "+k);
            File partialDir=Files.createTempDirectory("partial-ledger").toFile(); UsageLedger.init(partialDir);
            long t=System.currentTimeMillis()-10000;
            UsageLedger.Row input=row(t,"partial-in",1,false,429,"partial",40,9999); input.inputKnown=true; input.cacheSource="known"; input.cacheRead=10; UsageLedger.record(input);
            UsageLedger.Row output=row(t+1,"partial-out",1,true,200,"partial",9999,15); output.outputKnown=true; output.cacheSource="known"; output.cacheRead=777; UsageLedger.record(output);
            UsageLedger.Row estimated=row(t+2,"estimated-partial",1,true,200,"estimated",8,8888); estimated.inputKnown=true; UsageLedger.record(estimated);
            UsageLedger.Row full=row(t+3,"known-compat",1,true,200,"known",2,3); UsageLedger.record(full);
            JSONObject partial=UsageLedger.summary(2,"provider",t+100,utc).getJSONObject("totals");
            eq(partial.getLong("input"),50,"partial input"); eq(partial.getLong("output"),18,"partial output");
            eq(partial.getLong("tokens"),68,"partial total"); eq(partial.getLong("usagePartial"),2,"partial attempts");
            eq(partial.getLong("inputKnown"),3,"input dimension coverage count"); eq(partial.getLong("outputKnown"),2,"output dimension coverage count");
            check(Math.abs(partial.getDouble("inputCoverage")-75.0)<0.01,"input coverage");
            check(Math.abs(partial.getDouble("outputCoverage")-50.0)<0.01,"output coverage");
            check(Math.abs(partial.getDouble("hitRate")-25.0)<0.01,"cache rate excludes unknown input");
            eq(UsageLedger.heatmap(t+100,utc).getJSONObject("totals").getLong("tokens"),68,"partial heatmap");
            eq(UsageLedger.windowTotals(2)[1],68,"partial window");
            boolean partialInputCsv=false,partialOutputCsv=false,estimatedCsv=false;
            for(String line:UsageLedger.csv(2).split("\n")) {
                String[] fields=line.split(",",-1); if(fields.length<27) continue;
                if("partial-in".equals(fields[15])) { partialInputCsv=true; check("40".equals(fields[4]) && fields[5].isEmpty() && "40".equals(fields[22]) && fields[23].isEmpty(),"partial input CSV"); }
                if("partial-out".equals(fields[15])) { partialOutputCsv=true; check(fields[4].isEmpty() && "15".equals(fields[5]) && fields[22].isEmpty() && "15".equals(fields[23]),"partial output CSV"); }
                if("estimated-partial".equals(fields[15])) { estimatedCsv=true; check("8".equals(fields[4]) && fields[5].isEmpty(),"estimated partial CSV"); }
            }
            check(partialInputCsv && partialOutputCsv && estimatedCsv,"partial CSV rows present");
            UsageLedger.init(partialDir);
            JSONObject partialReload=UsageLedger.summary(2,"provider",t+100,utc).getJSONObject("totals");
            for(String k:new String[]{"input","output","tokens","usagePartial","inputKnown","outputKnown"}) eq(partialReload.getLong(k),partial.getLong(k),"partial reload "+k);
            eq(UsageLedger.heatmap(t+100,utc).getJSONObject("totals").getLong("tokens"),68,"partial heatmap reload");
            File cacheDir=Files.createTempDirectory("protocol-cache-ledger").toFile(); UsageLedger.init(cacheDir);
            UsageLedger.Row anthropicCache=row(t,"anthropic-cache",1,true,200,"partial",11,0);
            anthropicCache.inputKnown=true; anthropicCache.prov="anthropic"; anthropicCache.cacheSource="known";
            anthropicCache.cacheRead=3; anthropicCache.cacheWrite=4; anthropicCache.cacheInputTokens=18; UsageLedger.record(anthropicCache);
            UsageLedger.Row openaiCache=row(t+1,"openai-cache",1,true,200,"partial",11,0);
            openaiCache.inputKnown=true; openaiCache.prov="openai"; openaiCache.cacheSource="known";
            openaiCache.cacheRead=3; openaiCache.cacheInputTokens=11; UsageLedger.record(openaiCache);
            UsageLedger.Row noInput=row(t+2,"independent-cache",1,true,200,"partial",9999,5);
            noInput.outputKnown=true; noInput.prov="independent"; noInput.cacheSource="known";
            noInput.cacheRead=2; noInput.cacheInputTokens=10; UsageLedger.record(noInput);
            UsageLedger.Row unknownCache=row(t+3,"unknown-cache",1,true,200,"unknown",9999,9999);
            unknownCache.prov="unknown"; unknownCache.cacheSource="known"; unknownCache.cacheRead=999; unknownCache.cacheInputTokens=1000; UsageLedger.record(unknownCache);
            JSONObject cs=UsageLedger.summary(2,"provider",t+100,utc);
            JSONObject ca=day(cs.getJSONArray("groups"),"name","anthropic"), co=day(cs.getJSONArray("groups"),"name","openai");
            check(Math.abs(ca.getDouble("hitRate")-16.7)<0.01,"Anthropic cache 3/18");
            check(Math.abs(co.getDouble("hitRate")-27.3)<0.01,"OpenAI cache 3/11");
            eq(cs.getJSONObject("totals").getLong("tokens"),27,"normalized cache denominator excluded from tokens");
            eq(cs.getJSONObject("totals").getLong("cacheKnownInputTokens"),39,"normalized denominator with independent input");
            eq(cs.getJSONObject("totals").getLong("cacheKnown"),3,"unknown usage not cache known");
            UsageLedger.init(cacheDir); JSONObject cs2=UsageLedger.summary(2,"provider",t+100,utc);
            for(String provider:new String[]{"anthropic","openai","independent","unknown"}) {
                JSONObject before=day(cs.getJSONArray("groups"),"name",provider), after=day(cs2.getJSONArray("groups"),"name",provider);
                eq(after.getLong("tokens"),before.getLong("tokens"),"cache restart tokens");
                eq(after.getLong("cacheKnownInputTokens"),before.getLong("cacheKnownInputTokens"),"cache restart denominator");
                check(after.isNull("hitRate") == before.isNull("hitRate"),"cache restart null hit rate");
                if(!before.isNull("hitRate")) check(Math.abs(before.getDouble("hitRate")-after.getDouble("hitRate"))<0.01,"cache restart hit rate");
            }
            System.out.println("UsageLedgerAttemptsOfflineTest passed");
        } finally { TimeZone.setDefault(old); }
    }
}
