package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.Calendar;
import java.util.TimeZone;

public class UsageLedgerHeatmapOfflineTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static long at(TimeZone zone, int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(zone); c.clear(); c.set(y, m-1, d, h, min); return c.getTimeInMillis();
    }
    static JSONObject find(JSONArray a, String key, String date) {
        for (int i=0;i<a.length();i++) { JSONObject o=a.getJSONObject(i); if(date.equals(o.optString(key))) return o; }
        throw new AssertionError("missing " + date);
    }
    static void equal(long a, long b, String what) { check(a == b, what + ": " + a + " != " + b); }
    static void row(long t, int in, int out, int status, int tries) {
        UsageLedger.Row r = new UsageLedger.Row(); r.t=t; r.in=in; r.out=out; r.status=status; r.tries=tries; UsageLedger.record(r);
    }
    public static void main(String[] args) throws Exception {
        TimeZone old=TimeZone.getDefault(), utc=TimeZone.getTimeZone("UTC"), sh=TimeZone.getTimeZone("Asia/Shanghai"), ny=TimeZone.getTimeZone("America/New_York");
        File dir=Files.createTempDirectory("heat-ledger").toFile();
        try {
            TimeZone.setDefault(utc); UsageLedger.init(dir);
            // Use future dates so deterministic as-of can exclude later events even on a real clock.
            long now=at(utc, 2030,3,11,12,0);
            row(at(utc,2030,3,5,0,1), 10, 1,200,1);
            row(at(utc,2030,3,10,23,59), 20, 2,200,3);
            row(at(utc,2030,3,11,0,1), Integer.MAX_VALUE, Integer.MAX_VALUE,200,1);
            row(at(utc,2030,3,11,1,0), 700, 800,500,1);
            row(at(utc,2030,3,11,13,0), 123, 0,200,1);
            JSONObject summary=UsageLedger.summary(7,"model",now,utc), heat=UsageLedger.heatmap(now,utc);
            equal(summary.getJSONObject("totals").getLong("tokens"), 4294967327L,"long totals");
            equal(summary.getJSONObject("totals").getLong("requests"),4,"request total");
            equal(summary.getJSONObject("totals").getLong("failed"),1,"failed total");
            equal(summary.getJSONObject("totals").getLong("retried"),1,"retry is one request");
            long sum=0; JSONArray daily=summary.getJSONArray("daily");
            for(int i=0;i<daily.length();i++) { JSONObject d=daily.getJSONObject(i); sum+=d.getLong("tokens"); JSONObject hd=find(heat.getJSONArray("days"),"date",d.getString("day")); equal(d.getLong("tokens"),hd.getLong("tokens"),"heat and summary"); }
            equal(sum,summary.getJSONObject("totals").getLong("tokens"),"midnight daily sum");
            check("2029-03-12".equals(heat.getString("start")),"monday start " + heat.getString("start"));
            check("2030-03-11".equals(heat.getString("end")),"today end");
            equal(find(heat.getJSONArray("days"),"date","2030-03-11").getLong("requests"),2,"future exclusion");
            JSONObject hs=UsageLedger.heatmap(now,sh);
            equal(find(hs.getJSONArray("days"),"date","2030-03-11").getLong("requests"),3,"timezone day");
            // Spring DST: 7 calendar days including midnight on both sides, no duplicate date.
            long dst=at(ny,2030,3,12,12,0); JSONObject ds=UsageLedger.summary(7,"model",dst,ny);
            JSONArray dd=ds.getJSONArray("daily"); check("2030-03-06".equals(dd.getJSONObject(0).getString("day")),"DST start");
            check("2030-03-12".equals(dd.getJSONObject(6).getString("day")),"DST end");
            // Old on-disk row is outside the 90-day row cache but in heatmap range.
            File usage=new File(dir,"usage"); File fixture=new File(usage,"ledger-fixture.jsonl");
            long actual=System.currentTimeMillis(), historical=actual-150L*86400000L;
            Files.write(fixture.toPath(), ("{\"t\":"+historical+",\"in\":42,\"out\":3,\"st\":200}\nnot-json\n{}\n").getBytes(StandardCharsets.UTF_8));
            UsageLedger.init(dir); JSONObject reloaded=UsageLedger.heatmap(actual,utc);
            Calendar hc=Calendar.getInstance(utc); hc.setTimeInMillis(historical);
            java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd"); fmt.setTimeZone(utc);
            equal(find(reloaded.getJSONArray("days"),"date",fmt.format(hc.getTime())).getLong("tokens"),45,"old disk history");
            equal(reloaded.getLong("invalidLines"),2,"invalid lines");
            equal(reloaded.getLong("readErrors"),0,"read errors");
            UsageLedger.init(dir); JSONObject twice=UsageLedger.heatmap(actual,utc);
            equal(find(twice.getJSONArray("days"),"date",fmt.format(hc.getTime())).getLong("tokens"),45,"repeat init no duplicate");
            Files.createDirectory(new File(usage,"ledger-unreadable.jsonl").toPath());
            UsageLedger.init(dir); check(UsageLedger.heatmap(actual,utc).getLong("readErrors") > 0,"read failure reported");
            File badDir=Files.createTempDirectory("heat-write-failure").toFile();
            Files.write(new File(badDir,"usage").toPath(), new byte[]{1});
            UsageLedger.init(badDir); row(actual, 2, 3, 200, 1);
            JSONObject failedWrite=UsageLedger.heatmap(actual,sh);
            check(failedWrite.getLong("persistenceErrors") > 0,"write failure reported");
            equal(failedWrite.getJSONObject("totals").getLong("tokens"),5,"unpersisted row survives timezone rebuild");
            // A failed flush can have persisted a whole line; rollback must prevent duplicate replay.
            File writeDir=Files.createTempDirectory("heat-flush-failure").toFile(); UsageLedger.init(writeDir);
            java.lang.reflect.Field wf=UsageLedger.class.getDeclaredField("writer"); wf.setAccessible(true);
            java.io.OutputStreamWriter original=(java.io.OutputStreamWriter)wf.get(null);
            original.close();
            java.lang.reflect.Field cf=UsageLedger.class.getDeclaredField("currentFile"); cf.setAccessible(true);
            File file=(File)cf.get(null);
            final java.io.FileOutputStream sink=new java.io.FileOutputStream(file,true);
            java.io.OutputStream injected=new java.io.FilterOutputStream(sink) {
                public void flush() throws java.io.IOException { super.flush(); throw new java.io.IOException("injected flush"); }
            };
            wf.set(null,new java.io.OutputStreamWriter(injected,StandardCharsets.UTF_8));
            row(actual, 9, 1, 200, 1);
            JSONObject flushed=UsageLedger.heatmap(actual,utc);
            equal(flushed.getJSONObject("totals").getLong("tokens"),10,"failed flush counted once");
            equal(flushed.getLong("uncertainWrites"),0,"rollback confirmed");
            equal(file.length(),0,"failed flush rolled back");
            equal(UsageLedger.heatmap(actual,sh).getJSONObject("totals").getLong("tokens"),10,"rollback replay once");
            // A partial write is also truncated to the exact previous file length.
            File partialDir=Files.createTempDirectory("heat-partial").toFile(); UsageLedger.init(partialDir);
            ((java.io.OutputStreamWriter)wf.get(null)).close(); file=(File)cf.get(null);
            final java.io.FileOutputStream partialSink=new java.io.FileOutputStream(file,true);
            wf.set(null,new java.io.OutputStreamWriter(new java.io.OutputStream() {
                public void write(int b) throws java.io.IOException { partialSink.write(b); throw new java.io.IOException("partial"); }
                public void write(byte[] bytes,int off,int len) throws java.io.IOException { partialSink.write(bytes,off,Math.min(4,len)); throw new java.io.IOException("partial"); }
                public void close() throws java.io.IOException { partialSink.close(); }
            },StandardCharsets.UTF_8));
            row(actual,4,0,200,1); equal(file.length(),0,"partial write rolled back");
            equal(UsageLedger.heatmap(actual,sh).getJSONObject("totals").getLong("tokens"),4,"partial row replayed once");
            // An event already in the current day must be excluded after an intra-day clock rollback.
            File clockDir=Files.createTempDirectory("heat-clock").toFile(); UsageLedger.init(clockDir);
            row(actual-1000, 8, 0, 200, 1);
            JSONObject earlier=UsageLedger.heatmap(actual-2000,utc);
            equal(earlier.getJSONObject("totals").getLong("requests"),0,"clock rollback excludes later event");
            check(earlier.isNull("firstRecordedDate"),"future-only first date null");
            equal(UsageLedger.heatmap(actual,utc).getJSONObject("totals").getLong("requests"),1,"clock advance includes event");
            // Simulate a full write/flush followed by a failed rollback. Rebuild takes
            // the raw disk row only and reports that its durability was uncertain.
            File uncertainDir=Files.createTempDirectory("heat-uncertain").toFile(); UsageLedger.init(uncertainDir);
            ((java.io.OutputStreamWriter)wf.get(null)).close(); file=(File)cf.get(null);
            final java.io.FileOutputStream uncertainSink=new java.io.FileOutputStream(file,true);
            wf.set(null,new java.io.OutputStreamWriter(new java.io.FilterOutputStream(uncertainSink) {
                public void flush() throws java.io.IOException { super.flush(); throw new java.io.IOException("flush"); }
            },StandardCharsets.UTF_8));
            UsageLedger.rollbackHook=(f,n)->false;
            row(actual, 7, 0, 200, 1); UsageLedger.rollbackHook=null;
            JSONObject uncertain=UsageLedger.heatmap(actual,sh);
            equal(uncertain.getLong("uncertainWrites"),1,"uncertain failure visible");
            equal(uncertain.getJSONObject("totals").getLong("tokens"),7,"uncertain disk row not duplicated");
            // A failed partial append with no rollback must not corrupt the next row.
            File damagedDir=Files.createTempDirectory("heat-damaged-tail").toFile(); UsageLedger.init(damagedDir);
            ((java.io.OutputStreamWriter)wf.get(null)).close(); file=(File)cf.get(null);
            final java.io.FileOutputStream damagedSink=new java.io.FileOutputStream(file,true);
            wf.set(null,new java.io.OutputStreamWriter(new java.io.OutputStream() {
                public void write(int b) throws java.io.IOException { damagedSink.write(b); throw new java.io.IOException("partial"); }
                public void write(byte[] bytes,int off,int len) throws java.io.IOException { damagedSink.write(bytes,off,Math.min(4,len)); throw new java.io.IOException("partial"); }
                public void close() throws java.io.IOException { damagedSink.close(); }
            },StandardCharsets.UTF_8));
            UsageLedger.rollbackHook=(f,n)->false;
            row(actual,21,0,200,1); UsageLedger.rollbackHook=null;
            row(actual,13,0,200,1);
            JSONObject recovered=UsageLedger.heatmap(actual,sh);
            equal(recovered.getLong("uncertainWrites"),1,"partial uncertainty reported");
            equal(recovered.getLong("invalidLines"),1,"partial tail reported");
            equal(recovered.getJSONObject("totals").getLong("tokens"),13,"subsequent row survives rebuild");
            UsageLedger.init(damagedDir);
            JSONObject restarted=UsageLedger.heatmap(actual,utc);
            equal(restarted.getLong("invalidLines"),1,"partial tail reported after restart");
            equal(restarted.getJSONObject("totals").getLong("tokens"),13,"subsequent row survives restart");
            // Pause after init snapshot: record must join delta, not snapshot and delta.
            File concurrentDir=Files.createTempDirectory("heat-concurrent").toFile();
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1), proceed=new java.util.concurrent.CountDownLatch(1);
            UsageLedger.rebuildSnapshotHook=()-> { entered.countDown(); try { proceed.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } };
            Thread init=new Thread(()->UsageLedger.init(concurrentDir)); init.start();
            check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"snapshot reached");
            row(actual,11,0,200,1); proceed.countDown(); init.join(5000); UsageLedger.rebuildSnapshotHook=null;
            check(!init.isAlive(),"init completes");
            equal(UsageLedger.heatmap(actual,utc).getJSONObject("totals").getLong("requests"),1,"concurrent init heat count");
            equal(UsageLedger.summary(1,"model",actual,utc).getJSONObject("totals").getLong("requests"),1,"concurrent init summary count");
            System.out.println("UsageLedgerHeatmapOfflineTest OK");
        } finally { TimeZone.setDefault(old); }
    }
}
