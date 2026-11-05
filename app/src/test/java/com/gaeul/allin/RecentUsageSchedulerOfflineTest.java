package com.gaeul.allin;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class RecentUsageSchedulerOfflineTest {
    static class FakeClock implements RecentUsageScheduler.Clock { long t; public long now() { return t; } }
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static void eq(double a, double b) { check(Math.abs(a-b) < 1e-8, a + " != " + b); }
    static UsageLedger.Row row(String source, int in, int out) {
        UsageLedger.Row r = new UsageLedger.Row(); r.status=200; r.usageSource=source;
        r.in=in; r.out=out; r.inputKnown=true; r.outputKnown=true; return r;
    }
    static void add(RecentUsageScheduler s, String ref, UsageLedger.Row r) { check(s.reserve(ref).complete(r), "settle"); }
    public static void main(String[] args) throws Exception {
        FakeClock clock = new FakeClock(); RecentUsageScheduler s = new RecentUsageScheduler(clock);
        add(s,"a",row("known",600,400)); eq(s.score("a"),1000);
        clock.t=3600000; eq(s.score("a"),500);
        clock.t=0; eq(s.score("a"),500);
        clock.t=7200000; eq(s.score("a"),250);
        RecentUsageScheduler.Lease held=s.reserve("a"); clock.t=14400000;
        eq(s.score("a"),62.5); check(s.inFlight("a")==1,"inflight decay"); held.cancel(); held.cancel();
        RecentUsageScheduler t = new RecentUsageScheduler(clock);
        check(t.pickAndReserve(Arrays.asList("x","x","y")).target.equals("x"),"tie");
        check(t.pickAndReserve(Arrays.asList("x","y")).target.equals("y"),"reservation");
        final RecentUsageScheduler concurrent = new RecentUsageScheduler(clock);
        final CountDownLatch start = new CountDownLatch(1); final List<RecentUsageScheduler.Lease> leases = new ArrayList<RecentUsageScheduler.Lease>();
        List<Thread> threads = new ArrayList<Thread>();
        for(int i=0;i<20;i++) { Thread th=new Thread(new Runnable(){ public void run(){ try { start.await(); RecentUsageScheduler.Lease l=concurrent.pickAndReserve(Arrays.asList("a","b")); synchronized(leases){leases.add(l);} } catch(Exception e){throw new RuntimeException(e);} }}); threads.add(th); th.start(); }
        start.countDown(); for(Thread th:threads) th.join();
        check(leases.size()==20 && concurrent.inFlight("a")==10 && concurrent.inFlight("b")==10,"parallel");
        for(RecentUsageScheduler.Lease l:leases) l.close();
        RecentUsageScheduler u=new RecentUsageScheduler(clock);
        UsageLedger.Row r=row("partial",10,20); r.outputKnown=false; add(u,"p",r); eq(u.score("p"),10);
        add(u,"est",row("estimated",300,400)); eq(u.score("est"),1);
        add(u,"unknown",row("unknown",300,400)); eq(u.score("unknown"),1);
        add(u,"zero",row("known",0,0)); eq(u.score("zero"),1);
        r=row("known",100,20); r.cacheRead=30; r.cacheWrite=40; r.cacheSource="known"; r.reasoning=1000; add(u,"openai",r); eq(u.score("openai"),120);
        r.cacheInputExcludesCache=true; add(u,"anthropic",r); eq(u.score("anthropic"),190);
        r=row("known",Integer.MAX_VALUE,Integer.MAX_VALUE); r.cacheInputExcludesCache=true; r.cacheSource="known"; r.cacheRead=Integer.MAX_VALUE; r.cacheWrite=Integer.MAX_VALUE; add(u,"large",r); eq(u.score("large"),4.0*Integer.MAX_VALUE);
        for (int status : new int[]{0,199,300,399,400,500}) { r=row("known",9,9); r.status=status; add(u,"failure",r); }
        r=row("known",9,9); r.err="error"; add(u,"failure",r); eq(u.score("failure"),0);
        RecentUsageScheduler.Lease l=u.reserve("once"); check(l.complete(row("known",2,3)),"first"); check(!l.complete(row("known",2,3)),"second"); l.cancel(); eq(u.score("once"),5); check(u.inFlight("once")==0,"settled");
        // Known cache counters remain independently trustworthy even when other usage is estimated/unknown.
        for (String source : new String[]{"estimated", "unknown"}) {
            r=row(source,300,400); r.cacheInputExcludesCache=true; r.cacheSource="known"; r.cacheRead=30; r.cacheWrite=40;
            add(u,"cache-"+source,r); eq(u.score("cache-"+source),70);
            r.cacheSource="estimated"; add(u,"uncertain-cache-"+source,r); eq(u.score("uncertain-cache-"+source),1);
        }
        final RecentUsageScheduler gate = new RecentUsageScheduler(clock);
        RecentUsageScheduler.Lease first=gate.reserve("row-first"); first.requireDelivery();
        check(first.complete(row("known",7,3)),"row accepted");
        check(!first.complete(row("known",100,100)),"duplicate row rejected");
        eq(gate.score("row-first"),0); check(gate.inFlight("row-first")==1,"read complete retains reservation");
        first.deliveryComplete(true); first.deliveryComplete(false); eq(gate.score("row-first"),10); check(gate.inFlight("row-first")==0,"delivery settles");
        RecentUsageScheduler.Lease second=gate.reserve("delivery-first"); second.requireDelivery(); second.deliveryComplete(true); second.deliveryComplete(false);
        check(gate.inFlight("delivery-first")==1,"delivery awaits row"); eq(gate.score("delivery-first"),0);
        check(second.complete(row("known",4,6)),"row after delivery"); eq(gate.score("delivery-first"),10);
        RecentUsageScheduler.Lease failed=gate.reserve("row-fail"); failed.requireDelivery(); r=row("known",50,50); r.status=500;
        check(failed.complete(r),"failure accepted"); check(gate.inFlight("row-fail")==0,"failure releases"); failed.deliveryComplete(true); eq(gate.score("row-fail"),0);
        RecentUsageScheduler.Lease dropped=gate.reserve("dropped"); dropped.requireDelivery(); dropped.complete(row("known",9,9)); dropped.deliveryComplete(false);
        check(gate.inFlight("dropped")==0,"delivery failure releases"); eq(gate.score("dropped"),0);
        RecentUsageScheduler.Lease droppedFirst=gate.reserve("dropped-first"); droppedFirst.requireDelivery(); droppedFirst.deliveryComplete(false);
        check(!droppedFirst.complete(row("known",9,9)) && gate.inFlight("dropped-first")==0,"failure before row");
        RecentUsageScheduler.Lease cancelled=gate.reserve("cancelled"); cancelled.requireDelivery(); cancelled.complete(row("known",9,9)); cancelled.cancel(); cancelled.cancel(); cancelled.deliveryComplete(true);
        eq(gate.score("cancelled"),0); check(gate.inFlight("cancelled")==0,"cancel gate");
        RecentUsageScheduler.Lease late=gate.reserve("late"); late.complete(row("known",1,1));
        boolean threw=false; try { late.requireDelivery(); } catch(IllegalStateException expected) { threw=true; } check(threw,"late gate rejected");
        for (int i=0;i<100;i++) {
            final RecentUsageScheduler.Lease race=gate.reserve("race"+i); race.requireDelivery();
            final CountDownLatch go=new CountDownLatch(1);
            Thread rowThread=new Thread(new Runnable(){ public void run(){ try { go.await(); race.complete(row("known",10,20)); } catch(InterruptedException e){ throw new AssertionError(e); } }});
            Thread deliveryThread=new Thread(new Runnable(){ public void run(){ try { go.await(); race.deliveryComplete(true); } catch(InterruptedException e){ throw new AssertionError(e); } }});
            rowThread.start(); deliveryThread.start(); go.countDown(); rowThread.join(5000); deliveryThread.join(5000);
            check(!rowThread.isAlive() && !deliveryThread.isAlive(),"gate deadlock"); eq(gate.score("race"+i),30); check(gate.inFlight("race"+i)==0,"gate race release");
        }
        RecentUsageScheduler cap=new RecentUsageScheduler(clock); List<RecentUsageScheduler.Lease> active=new ArrayList<RecentUsageScheduler.Lease>();
        for(int i=0;i<4096;i++) active.add(cap.reserve("ref"+i));
        check(cap.size()==4096 && cap.reserve("overflow")==null && cap.pickAndReserve(Arrays.asList("overflow"))==null,"capacity");
        active.get(0).cancel(); check(cap.reserve("overflow")!=null && cap.size()==4096 && cap.inFlight("ref1")==1,"eviction");
        final java.lang.reflect.Field initializing=UsageLedger.class.getDeclaredField("initializing"); initializing.setAccessible(true);
        final java.lang.reflect.Field lockField=UsageLedger.class.getDeclaredField("LOCK"); lockField.setAccessible(true);
        final Object ledgerLock=lockField.get(null);
        final UsageLedger.Row pending=row("known",11,12); pending.recentUsageLease=u.reserve("record");
        synchronized(ledgerLock) { initializing.setBoolean(null,true); }
        Thread interrupted=new Thread(new Runnable(){ public void run(){ Thread.currentThread().interrupt(); UsageLedger.record(pending); } });
        interrupted.start(); interrupted.join(5000);
        synchronized(ledgerLock) { initializing.setBoolean(null,false); ledgerLock.notifyAll(); }
        check(!interrupted.isAlive(),"interrupted wait"); eq(u.score("record"),23); check(u.inFlight("record")==0,"record settlement");
        java.lang.reflect.Method json=UsageLedger.class.getDeclaredMethod("toJsonLine",UsageLedger.Row.class); json.setAccessible(true);
        check(!((String)json.invoke(null,pending)).contains("recentUsageLease"),"transient serialization");
        System.out.println("RecentUsageSchedulerOfflineTest passed");
    }
}
