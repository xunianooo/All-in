package com.gaeul.allin;

import java.util.concurrent.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ZCodeGiftBusyPolicyOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void awaitQueued(ZCodeGiftBusyPolicy.Gate gate, int n) throws Exception {
        Field field=gate.getClass().getDeclaredField("queued"); field.setAccessible(true);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end) { synchronized(gate) { if(field.getInt(gate)==n) return; } Thread.yield(); }
        throw new AssertionError("queue size did not reach "+n);
    }
    static void gateTests() throws Exception {
        ZCodeGiftBusyPolicy.Gate gate=new ZCodeGiftBusyPolicy.Gate(3,1000,32,256,1,()->System.nanoTime()/1000000);
        RequestContext ctx=new RequestContext("test",null,5000);
        ZCodeGiftBusyPolicy.Slot[] slots=new ZCodeGiftBusyPolicy.Slot[3];
        for(int i=0;i<3;i++) slots[i]=gate.acquire("a",ctx);
        check(gate.acquire("other",ctx)==null);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        List<Integer> order=Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<3;i++) { final int id=i; jobs.add(pool.submit(()->{try(ZCodeGiftBusyPolicy.Slot slot=gate.acquire("a",new RequestContext("test",null,4000))) {check(slot!=null); order.add(id);}catch(Exception e){throw new RuntimeException(e);}})); awaitQueued(gate,i+1); }
        slots[0].close(); for(Future<?> f:jobs) f.get(2,TimeUnit.SECONDS);
        check(order.equals(Arrays.asList(0,1,2))); slots[1].close(); slots[2].close();
        try(ZCodeGiftBusyPolicy.Slot other=gate.acquire("other",ctx)) {check(other!=null);} // idle lane reclaimed
        ZCodeGiftBusyPolicy.Gate limited=new ZCodeGiftBusyPolicy.Gate(1,150,2,2,1,()->System.nanoTime()/1000000);
        try(ZCodeGiftBusyPolicy.Slot occupied=limited.acquire("a",ctx)) {
            RequestContext cancelled=new RequestContext("test",null,4000);
            Future<Boolean> cf=pool.submit(()->{try {limited.acquire("a",cancelled); return false;}catch(java.io.IOException e){return true;}});
            awaitQueued(limited,1); cancelled.cancel("test"); check(cf.get(1,TimeUnit.SECONDS)); awaitQueued(limited,0);
            check(limited.acquire("a",ctx)==null); // local queue timeout
            RequestContext deadline=new RequestContext("test",null,30);
            try { limited.acquire("a",deadline); throw new AssertionError("deadline"); } catch(java.io.IOException expected) { }
            awaitQueued(limited,0);
        }
        try(ZCodeGiftBusyPolicy.Slot other=limited.acquire("b",ctx)){check(other!=null);}
        pool.shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        check(ZCodeGiftBusyPolicy.busy(429,"{\"code\":3008}"));
        check(ZCodeGiftBusyPolicy.busy(429,"{\"error\":{\"code\":\"3009\"}}"));
        check(ZCodeGiftBusyPolicy.busy(429,"{\"message\":\"USER CONCURRENCY LIMIT EXCEEDED\"}"));
        for(String s:new String[]{"{\"code\":1302,\"message\":\"concurrency limit exceeded\"}","{\"code\":3008,\"error\":{\"code\":3009}}","{\"other\":{\"code\":3008}}","{\"code\":3008} trailing","{\"code\":3008,\"code\":3008}","{\"code\":03008}"}) check(!ZCodeGiftBusyPolicy.busy(429,s));
        check(!ZCodeGiftBusyPolicy.busy(403,"{\"code\":3008}"));
        check(ZCodeGiftBusyPolicy.accountKey("a","bc",null).equals(ZCodeGiftBusyPolicy.accountKey("a","bc",null)));
        check(!ZCodeGiftBusyPolicy.accountKey("a","bc",null).equals(ZCodeGiftBusyPolicy.accountKey("ab","c",null)));
        check(ZCodeGiftBusyPolicy.accountKey("old","device1","uid1").equals(ZCodeGiftBusyPolicy.accountKey("new","device2","uid1")));
        check(!ZCodeGiftBusyPolicy.accountKey("old","device1","uid1").equals(ZCodeGiftBusyPolicy.accountKey("old","device1","uid2")));
        check(!ZCodeGiftBusyPolicy.accountKey("old","device1",null).equals(ZCodeGiftBusyPolicy.accountKey("new","device1",null)));
        AtomicLong negative=new AtomicLong(-100000); ZCodeGiftBusyPolicy.BusyHold negHold=new ZCodeGiftBusyPolicy.BusyHold(negative::get);
        check(negHold.remainingMillis()==0); for(int i=0;i<5;i++) negHold.sustainedCap(); check(negHold.remainingMillis()==180000); negHold.reset(); check(negHold.remainingMillis()==0);
        gateTests();
        AtomicLong time=new AtomicLong(100); ZCodeGiftBusyPolicy.BusyHold hold=new ZCodeGiftBusyPolicy.BusyHold(time::get);
        for(int i=0;i<4;i++) { ZCodeGiftBusyPolicy.LogicalRequest r=hold.logicalRequest(); r.busyResponse(); r.busyResponse(); r.busyResponse(); } check(hold.remainingMillis()==0); ZCodeGiftBusyPolicy.LogicalRequest fifth=hold.logicalRequest(); fifth.busyResponse(); check(hold.remainingMillis()==0); fifth.busyResponse(); check(hold.remainingMillis()==180000); time.addAndGet(1000); hold.sustainedCap(); check(hold.remainingMillis()==179000); time.addAndGet(179000); check(hold.remainingMillis()==0);
        ZCodeGiftBusyPolicy.Gate gate=new ZCodeGiftBusyPolicy.Gate(1,1000,2,2,2,()->System.nanoTime()/1000000);
        RequestContext c=new RequestContext("test",null,5000); ZCodeGiftBusyPolicy.Slot s=gate.acquire("a",c); check(s!=null); check(gate.acquire("b",c)!=null); check(gate.acquire("c",c)==null);
        ExecutorService executor=Executors.newSingleThreadExecutor(); RequestContext waiting=new RequestContext("test",null,5000);
        Future<Boolean> f=executor.submit(()->{try {gate.acquire("a",waiting); return false;} catch(java.io.IOException expected){return true;}});
        Thread.sleep(50); waiting.cancel("test"); check(f.get(1,TimeUnit.SECONDS)); s.close(); s.close(); check(gate.acquire("a",c)!=null); executor.shutdownNow();
        System.out.println("ZCodeGiftBusyPolicyOfflineTest OK");
    }
}
