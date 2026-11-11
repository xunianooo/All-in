package com.gaeul.allin;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class GiftRefreshConcurrencyOfflineTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  AtomicInteger calls=new AtomicInteger(); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean limited=new AtomicBoolean(false);
  ZCodeGiftControl.setTestHooks(()->1000,new ZCodeGiftControl.Transport(){
   public JSONObject summary(String j,String d)throws Exception { int n=calls.incrementAndGet(); if(n==2){entered.countDown();release.await();} if(limited.get())return new JSONObject().put("ok",false).put("status",429).put("retryAfterMs",10000); return new JSONObject().put("ok",true).put("claimable",new JSONArray()).put("buckets",new JSONArray()); }
   public JSONObject claim(String j,String d,String p){throw new AssertionError("claim");}
  });
  try {
   check(ZCodeGiftControl.summary("j","d").optBoolean("ok"),"initial");
   limited.set(true); ExecutorService ex=Executors.newFixedThreadPool(2);
   Future<JSONObject> a=ex.submit(()->ZCodeGiftControl.summary("j","d",true)); entered.await();
   Future<JSONObject> b=ex.submit(()->ZCodeGiftControl.summary("j","d",true));
   Thread.sleep(100); release.countDown();
   check(a.get().optInt("status")==429 && b.get().optInt("status")==429,"both limited");
   check(calls.get()==2,"single flight"); check(ZCodeGiftControl.peekSummary("j","d")==null,"failed refresh clears old snapshot");
   check(ZCodeGiftControl.summary("j","d",true).optInt("status")==429 && calls.get()==2,"refresh held"); ex.shutdownNow();
  } finally { release.countDown(); ZCodeGiftControl.setTestHooks(null,null); }
  System.out.println("GiftRefreshConcurrencyOfflineTest PASS");
 }
}
