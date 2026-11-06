package com.gaeul.allin;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MiniMaxLifecycleOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static MiniMaxLifecycle.Credentials c(String s) { return new MiniMaxLifecycle.Credentials("access"+s,"refresh"+s,1); }
    interface Task { void run() throws Exception; }
    static void unavailable(Task t) throws Exception { try { t.run(); throw new AssertionError(); } catch (MiniMaxLifecycle.Unavailable expected) {} }
    public static void main(String[] args) throws Exception {
        final long[] time={100};
        MiniMaxLifecycle l=new MiniMaxLifecycle(() -> time[0]);
        l.register("account-1",c("1"));
        MiniMaxLifecycle.Lease a=l.acquire("account-1"), b=l.acquire("account-1");
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger refreshes=new AtomicInteger(), writes=new AtomicInteger();
        AtomicReference<Throwable> error=new AtomicReference<>();
        MiniMaxLifecycle.Refresh refresh=old -> { refreshes.incrementAndGet(); entered.countDown(); release.await(); return c("2"); };
        MiniMaxLifecycle.Persist persist=(id,g,old,next)-> { writes.incrementAndGet(); return true; };
        Thread t1=new Thread(() -> { try { l.refresh(a,true,0,refresh,persist); } catch(Throwable x) { error.set(x); } });
        Thread t2=new Thread(() -> { try { l.refresh(b,true,0,refresh,persist); } catch(Throwable x) { error.set(x); } });
        t1.start(); entered.await(); t2.start(); release.countDown(); t1.join(); t2.join();
        check(error.get()==null && refreshes.get()==1 && writes.get()==1 && l.acquire("account-1").credentials.access.equals("access2"));
        check(!l.isCurrent(a));
        MiniMaxLifecycle.Lease curr=l.acquire("account-1");
        unavailable(() -> l.refresh(curr,true,0,old -> c("3"),(id,g,old,next)->false));
        unavailable(() -> l.acquire("account-1"));
        l.register("account-1",c("durable-old"));
        check(l.acquire("account-1").credentials.access.equals("accessdurable-old"));
        MiniMaxLifecycle.Lease inFlight=l.acquire("account-1");
        CountDownLatch entered2=new CountDownLatch(1), release2=new CountDownLatch(1);
        AtomicInteger lateWrites=new AtomicInteger();
        Thread t3=new Thread(() -> { try { l.refresh(inFlight,true,0,old -> { entered2.countDown(); release2.await(); return c("late"); },(id,g,old,next)-> { lateWrites.incrementAndGet(); return true; }); error.set(new AssertionError()); } catch(MiniMaxLifecycle.Unavailable expected) {} catch(Throwable x) { error.set(x); } });
        t3.start(); entered2.await(); l.invalidate("account-1"); l.register("account-1",c("replacement")); release2.countDown(); t3.join();
        check(error.get()==null && lateWrites.get()==0 && !l.isCurrent(inFlight));
        MiniMaxLifecycle.Lease req=l.acquire("account-1");
        check(l.finish(req,MiniMaxLifecycle.Outcome.UNKNOWN,500));
        check(!l.finish(req,MiniMaxLifecycle.Outcome.SUCCESS,0));
        unavailable(() -> l.acquire("account-1"));
        time[0]=600; req=l.acquire("account-1");
        check(l.finish(req,MiniMaxLifecycle.Outcome.CANCELLED_BEFORE_SEND,500));
        req=l.acquire("account-1"); check(l.finish(req,MiniMaxLifecycle.Outcome.CANCELLED_AFTER_SEND,500));
        unavailable(() -> l.acquire("account-1"));
        time[0]=1100; MiniMaxLifecycle.Lease old=l.acquire("account-1"); l.retire();
        check(!l.isCurrent(old)); unavailable(() -> l.acquire("account-1"));
        System.out.println("MiniMaxLifecycleOfflineTest passed");
    }
}
