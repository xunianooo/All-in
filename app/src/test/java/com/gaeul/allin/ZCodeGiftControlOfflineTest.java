package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ZCodeGiftControlOfflineTest {
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static JSONObject data(int remaining) throws Exception {
        return new JSONObject().put("ok", true).put("claimable", new JSONArray().put(new JSONObject().put("planId", "one")).put(new JSONObject().put("planId", "two")))
                .put("buckets", new JSONArray().put(new JSONObject().put("remaining", remaining).put("capabilities", new JSONArray().put("model:m"))));
    }
    public static void main(String[] args) throws Exception {
        final long[] now = {1000}; final AtomicInteger reads = new AtomicInteger(), claims = new AtomicInteger();
        final int[] mode = {0}; final CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        ZCodeGiftControl.Transport fake = new ZCodeGiftControl.Transport() {
            public JSONObject summary(String jwt, String device) throws Exception {
                reads.incrementAndGet();
                if (mode[0] == 1 && jwt.equals("A")) throw new ZCodeAuth.ServiceException(429, 3002, 5000);
                if (mode[0] == 2) { entered.countDown(); check(go.await(3, TimeUnit.SECONDS), "release read"); }
                return data(1);
            }
            public JSONObject claim(String jwt, String device, String id) throws Exception {
                claims.incrementAndGet();
                if (mode[0] == 3 && id.equals("two")) throw new ZCodeAuth.ServiceException(200, 3002, 10000);
                return new JSONObject();
            }
        };
        try {
            ZCodeGiftControl.setTestHooks(() -> now[0], fake);
            JSONObject first = ZCodeGiftControl.summary("A", "d"); check(first.optBoolean("complete"), "summary complete");
            first.getJSONArray("buckets").getJSONObject(0).put("remaining", 0);
            check(ZCodeGiftControl.summary("A", "d").getJSONArray("buckets").getJSONObject(0).getInt("remaining") == 1 && reads.get()==1, "deep cache snapshot");
            check(ZCodeGiftControl.claim("A", "d", "one").optBoolean("ok"), "claim success");
            ZCodeGiftControl.summary("A", "d"); check(reads.get()==2, "claim invalidates");
            ZCodeGiftControl.invalidate("A", "d"); mode[0]=1;
            JSONObject limited = ZCodeGiftControl.summary("A", "d"); check(limited.optInt("status")==429 && limited.optLong("retryAfterMs")==5000, "limit response");
            ZCodeGiftControl.summary("A", "d"); check(reads.get()==3, "held avoids read");
            check(ZCodeGiftControl.summary("B", "d").optBoolean("ok") && reads.get()==4, "account isolation");
            now[0]+=5000; ZCodeGiftControl.summary("A", "d"); check(reads.get()==5, "expires");
            now[0]+=5000; mode[0]=3; JSONObject batch=ZCodeGiftControl.claimAll("C", "d");
            check(!batch.optBoolean("ok") && !batch.optBoolean("complete") && batch.getJSONArray("claimed").length()==1 && batch.optInt("status")==429, "partial limited batch");
            int old=reads.get(); ZCodeGiftControl.summary("C", "d"); check(reads.get()==old, "claim hold");
            mode[0]=2; final JSONObject[] stale={null};
            Thread t=new Thread(() -> stale[0]=ZCodeGiftControl.summary("R", "d")); t.start();
            check(entered.await(3, TimeUnit.SECONDS), "in flight"); ZCodeGiftControl.invalidate("R", "d"); go.countDown(); t.join(3000);
            check(!t.isAlive() && !stale[0].optBoolean("ok"), "old generation rejected");
            mode[0]=0; old=reads.get(); check(ZCodeGiftControl.summary("R", "d").optBoolean("ok") && reads.get()==old+1, "old generation not cached");
            check(ZCodeAuth.parseRetryAfter("2", 0)==2000 && ZCodeAuth.parseRetryAfter("nonsense", 0)==0, "retry seconds and invalid");
            check(ZCodeAuth.parseRetryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 1000)==9000, "retry date");
            check(ZCodeAuth.parseRetryAfter("999999999", 0)==ZCodeAuth.MAX_RETRY_AFTER_MS, "retry cap");
            System.out.println("ZCodeGiftControlOfflineTest PASS");
        } finally { ZCodeGiftControl.setTestHooks(null, null); }
    }
}
