package com.gaeul.allin;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionAffinityOfflineTest {
    static final class Clock implements SessionAffinity.Clock { volatile long now; public long nowMillis() { return now; } }
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static SessionAffinity.Key key(SessionAffinity a,String s,String scope,String protocol,String model) { return a.key(true,s,scope,protocol,model); }
    public static void main(String[] args) throws Exception {
        Clock clock=new Clock(); SessionAffinity a=new SessionAffinity(clock,2,100,10);
        SessionAffinity.Target t=new SessionAffinity.Target("p","origin","upstream","credential-id");
        check(a.acquire(a.key(false,"x","s","p","m")).state==SessionAffinity.State.BYPASS);
        check(a.key(true," ","s","p","m")==null);
        SessionAffinity.Key k=key(a,"session","scope","protocol","requested");
        check(!k.toString().contains("session"));
        for (SessionAffinity.Key other:new SessionAffinity.Key[]{key(a,"different","scope","protocol","requested"),key(a,"session","other","protocol","requested"),key(a,"session","scope","other","requested"),key(a,"session","scope","protocol","other")}) check(!k.equals(other));
        check(!t.equals(new SessionAffinity.Target("p","origin","upstream","other")));
        check(!t.equals(new SessionAffinity.Target("p","origin","other","credential-id")));
        check(!t.equals(new SessionAffinity.Target("p","other","upstream","credential-id")));
        final int n=20; ExecutorService executor=Executors.newFixedThreadPool(n);
        CountDownLatch start=new CountDownLatch(1), acquired=new CountDownLatch(n), finish=new CountDownLatch(1);
        AtomicInteger owners=new AtomicInteger(), waiters=new AtomicInteger();
        Future<?>[] futures=new Future<?>[n];
        for (int i=0;i<n;i++) futures[i]=executor.submit(() -> { try {
            start.await(); SessionAffinity.Lease l=a.acquire(k);
            if (l.state==SessionAffinity.State.OWNER) owners.incrementAndGet();
            if (l.state==SessionAffinity.State.WAIT) waiters.incrementAndGet();
            acquired.countDown(); finish.await();
            if (l.state==SessionAffinity.State.OWNER) check(l.bind(t));
            if (l.state==SessionAffinity.State.WAIT) check(l.await(1000));
            l.close();
        } catch (Exception e) { throw new RuntimeException(e); } });
        start.countDown(); check(acquired.await(5,TimeUnit.SECONDS)); check(owners.get()==1 && waiters.get()==n-1); finish.countDown();
        for (Future<?> f:futures) f.get(5,TimeUnit.SECONDS); executor.shutdownNow();
        check(t.equals(a.acquire(k).preferred));
        a.invalidate(k,new SessionAffinity.Target("p","origin","upstream","different")); check(a.acquire(k).state==SessionAffinity.State.BOUND);
        a.invalidate(k,t); SessionAffinity.Lease canceled=a.acquire(k); check(canceled.state==SessionAffinity.State.OWNER);
        SessionAffinity.Lease waiter=a.acquire(k); canceled.close(); check(waiter.await(1)); check(a.acquire(k).state==SessionAffinity.State.OWNER);
        a.clear(); SessionAffinity.Lease owner=a.acquire(k); check(owner.bind(t)); check(!owner.bind(new SessionAffinity.Target("x","x","x","x"))); owner.close(); check(t.equals(a.acquire(k).preferred));
        clock.now=100; check(a.acquire(k).state==SessionAffinity.State.OWNER);
        SessionAffinity.Lease old=a.acquire(key(a,"second","scope","protocol","requested"));
        check(a.acquire(key(a,"third","scope","protocol","requested")).state==SessionAffinity.State.BYPASS);
        clock.now=110; SessionAffinity.Lease replacement=a.acquire(k); check(replacement.state==SessionAffinity.State.OWNER); check(!old.bind(t));
        a.clear(); check(!replacement.bind(t));
        SessionAffinity.Lease bound=a.acquire(k); check(bound.bind(t));
        SessionAffinity.Key second=key(a,"second","scope","protocol","requested"); check(a.acquire(second).bind(t));
        check(a.acquire(key(a,"third","scope","protocol","requested")).state==SessionAffinity.State.OWNER);
        check(a.acquire(k).state==SessionAffinity.State.OWNER); // oldest bound evicted
        // Gateway integration: semantic gate, writer success/failure and restore retirement.
        Class<?> gateway=Class.forName("com.gaeul.allin.GatewayServer");
        java.lang.reflect.Method semantic=gateway.getDeclaredMethod("affinitySemanticComplete",String.class,String.class); semantic.setAccessible(true);
        check((Boolean)semantic.invoke(null,"{\"choices\":[{\"message\":{},\"finish_reason\":\"stop\"}]}","openai"));
        check(!(Boolean)semantic.invoke(null,"{\"choices\":[{\"message\":{}}]}","openai"));
        check(!(Boolean)semantic.invoke(null,"{\"error\":{}}","openai"));
        System.out.println("SessionAffinityOfflineTest PASS");
    }
}
