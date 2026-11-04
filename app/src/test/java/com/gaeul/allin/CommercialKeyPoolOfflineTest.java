package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class CommercialKeyPoolOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        AtomicLong now=new AtomicLong(1000);
        CommercialKeyPool pool=new CommercialKeyPool(now::get);
        JSONArray json=new JSONArray().put(" synthetic-one ").put("synthetic-one").put("")
                .put(new JSONObject().put("key","synthetic-two"))
                .put(new JSONObject().put("key","synthetic-three").put("off",true))
                .put(new JSONObject().put("key","synthetic-four").put("enabled",false));
        List<CommercialKeyPool.Credential> keys=CommercialKeyPool.list(json);
        check(keys.size()==2 && keys.get(0).credentialId.length()==64,"filter, deduplicate, identity");
        check(!keys.get(0).toString().contains("synthetic"),"safe display");
        Set<String> attempted=new HashSet<>();
        CommercialKeyPool.Credential a=pool.choose("p","m1",keys,attempted);
        CommercialKeyPool.Credential b=pool.choose("p","m1",keys,attempted);
        check(a!=b && pool.choose("p","m1",keys,attempted)==null,"attempt set");
        check(pool.choose("p","m2",keys,new HashSet<>()).credentialId.equals(a.credentialId),"round robin and cross model identity");
        pool.cool(CommercialKeyPool.Scope.KEY_MODEL,"p",a.credentialId,"m1",3000);
        check(!pool.available("p","m1",a) && pool.available("p","m2",a),"model scope");
        pool.cool(CommercialKeyPool.Scope.KEY,"p",a.credentialId,"m1",4000);
        check(!pool.available("p","m2",a) && pool.available("p","m1",b),"key scope");
        pool.cool(CommercialKeyPool.Scope.PROVIDER,"p",null,null,5000);
        check(!pool.available("p","m2",b) && pool.available("other","m2",b),"provider scope");
        check(pool.availableAt("p","m1",a)==5000,"combined deadline");
        now.set(5000); check(pool.available("p","m1",a),"expiry");
        ExecutorService threads=Executors.newFixedThreadPool(8);
        List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<1000;i++) { final int n=i; futures.add(threads.submit(() -> {
            pool.cool(CommercialKeyPool.Scope.KEY,"race",a.credentialId,null, 5001+n);
            pool.available("race","m",a);
        })); }
        for(Future<?> f:futures) f.get(); threads.shutdown();
        check(pool.availableAt("race","m",a)==6000,"concurrent maximum and expiry");
        pool.cool(CommercialKeyPool.Scope.KEY,"expiry",a.credentialId,null,5000);
        ExecutorService race=Executors.newFixedThreadPool(2);
        CountDownLatch gate=new CountDownLatch(1);
        Future<?> expiry=race.submit(() -> { try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } pool.available("expiry","m",a); });
        Future<?> extend=race.submit(() -> { try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } pool.cool(CommercialKeyPool.Scope.KEY,"expiry",a.credentialId,null,9000); });
        gate.countDown(); expiry.get(); extend.get(); race.shutdown();
        check(pool.availableAt("expiry","m",a)==9000,"expiry cannot remove extension");
        check(CommercialKeyPool.retryAfterDeadline("5",1000,123)==6000,"seconds");
        check(CommercialKeyPool.retryAfterDeadline("999999999999999999999999999",1000,123)==Long.MAX_VALUE,"huge");
        check(CommercialKeyPool.retryAfterDeadline("-5",1000,123)==1123,"negative invalid");
        check(CommercialKeyPool.retryAfterDeadline("nonsense",Long.MAX_VALUE-2,123)==Long.MAX_VALUE,"fallback overflow");
        check(CommercialKeyPool.retryAfterDeadline("Sun, 06 Nov 1994 08:49:37 GMT",0,1)==784111777000L,"rfc date");
        long now2026=1767225600000L;
        check(CommercialKeyPool.retryAfterDeadline("Sunday, 06-Nov-94 08:49:37 GMT",now2026,1)==now2026,"rfc850 past date");
        check(CommercialKeyPool.retryAfterDeadline("Wednesday, 01-Jan-70 00:00:00 GMT",now2026,1)==3155760000000L,"rfc850 future within 50 years");
        check(CommercialKeyPool.retryAfterDeadline("Saturday, 01-Jan-77 00:00:00 GMT",now2026,1)==now2026,"rfc850 over 50 years in future interpreted as past");
        check(CommercialKeyPool.retryAfterDeadline("Sun Nov  6 08:49:37 1994",0,1)==784111777000L,"asctime date");
        check(CommercialKeyPool.retryAfterDeadline("Sun, 06 Nov 1994 08:49:37 GMT",900000000000L,1)==900000000000L,"past date");
        CommercialKeyPool.Budget budget=new CommercialKeyPool.Budget(now::get,2,10);
        check(budget.tryStart() && budget.tryStart() && !budget.tryStart(),"attempt budget");
        budget=new CommercialKeyPool.Budget(now::get,8,10); now.addAndGet(10);
        check(!budget.tryStart(),"time budget");
        check(CommercialKeyPool.classify(429,"rate_limit","",false).retryKey,"429");
        check(CommercialKeyPool.classify(429,"model_rate_limit","",false).scope==CommercialKeyPool.Scope.KEY_MODEL,"model rate");
        check(CommercialKeyPool.classify(429,"organization_rate_limit","",false).scope==CommercialKeyPool.Scope.PROVIDER,"shared scope");
        check(CommercialKeyPool.classify(402,"1113","",false).retryKey,"quota code");
        check(!CommercialKeyPool.classify(402,"unknown","",false).retryKey,"unknown 402");
        check(!CommercialKeyPool.classify(503,"","",false).retryKey,"unknown 503");
        check(CommercialKeyPool.classify(503,"capacity","",false).routeFallback,"shared outage");
        check(!CommercialKeyPool.classify(0,"timeout","",false).routeFallback,"unknown result");
        check(!CommercialKeyPool.classify(429,"rate_limit","",true).retryKey,"response handed off");
        check(!CommercialKeyPool.classify(429,"context_length","",false).retryKey,"invalid request");
        check(CommercialKeyPool.classify(401,"","",false).retryKey,"auth");
        check(!CommercialKeyPool.classify(403,"model_not_found","",false).retryKey,"invalid model");
        System.out.println("CommercialKeyPoolOfflineTest passed");
    }
}
