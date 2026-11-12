package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache, hold and claim-classification coverage for {@link ZCodeGiftControl} with a synthetic
 * transport: no network, no account.
 *
 * <p>The claim outcomes are pinned to what the reference plugin answers for the same replies
 * (packages/zcode claimDaily/claimOne):
 *
 * <ul>
 *   <li>code 0 — claimed, and the summary cache is dropped;</li>
 *   <li>code 1003 ("target already exists") — done, not a failure, and it is not a new grant
 *       so the cache stays;</li>
 *   <li>code 1004 — ineligible for now, a settled answer: no rate hold, read again later;</li>
 *   <li>code 3007, or a claim refused before it was even posted — captcha, handed to the ZCode
 *       app, never retried;</li>
 *   <li>code 3002 / HTTP 429 — rate limited, held until the wait ends;</li>
 *   <li>anything unreadable — failed (unknown), never claimed.</li>
 * </ul>
 */
public final class ZCodeGiftControlOfflineTest {
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static JSONObject data(int remaining) throws Exception {
        return new JSONObject().put("ok", true).put("claimable", new JSONArray().put(new JSONObject().put("planId", "one")).put(new JSONObject().put("planId", "two")))
                .put("buckets", new JSONArray().put(new JSONObject().put("remaining", remaining).put("capabilities", new JSONArray().put("model:m"))));
    }

    // the fake transport's answers, one per reply the reference plugin classifies
    static final int OK = 0, LIMITED_SUMMARY = 1, IN_FLIGHT = 2, LIMITED_CLAIM = 3,
            ALREADY_CLAIMED = 4, NOT_ELIGIBLE = 5, CAPTCHA_REPLY = 6, CAPTCHA_GATE = 7,
            CLAIM_UNKNOWN = 8, TRANSPORT_CRASH = 9, UNREADABLE = 10, UNKNOWN_LIST = 11,
            CLAIM_ON_HTTP_ERROR = 12, LIMITED_SUMMARY_ANY = 13;

    public static void main(String[] args) throws Exception {
        final long[] now = {1000}; final AtomicInteger reads = new AtomicInteger(), claims = new AtomicInteger();
        final int[] mode = {0}; final CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        ZCodeGiftControl.Transport fake = new ZCodeGiftControl.Transport() {
            public JSONObject summary(String jwt, String device) throws Exception {
                reads.incrementAndGet();
                if (mode[0] == LIMITED_SUMMARY && jwt.equals("A")) throw new ZCodeAuth.ServiceException(429, 3002, 5000);
                if (mode[0] == LIMITED_SUMMARY_ANY) throw new ZCodeAuth.ServiceException(429, 3002, 5000);
                if (mode[0] == IN_FLIGHT) { entered.countDown(); check(go.await(3, TimeUnit.SECONDS), "release read"); }
                if (mode[0] == UNKNOWN_LIST) return new JSONObject().put("ok", true).put("claimable", new JSONArray())
                        .put("buckets", new JSONArray()).put("claimableKnown", false)
                        .put("claimableError", "gift 可领取计划数据不完整");
                return data(1);
            }
            public JSONObject claim(String jwt, String device, String id) throws Exception {
                claims.incrementAndGet();
                if (mode[0] == LIMITED_CLAIM && id.equals("two")) throw new ZCodeAuth.ServiceException(200, 3002, 10000);
                if (mode[0] == ALREADY_CLAIMED) throw new ZCodeAuth.ServiceException(200, 1003, 0);
                if (mode[0] == NOT_ELIGIBLE) throw new ZCodeAuth.ServiceException(400, 1004, 0);
                if (mode[0] == CAPTCHA_REPLY) throw new ZCodeAuth.ServiceException(400, 3007, 0);
                if (mode[0] == CAPTCHA_GATE) throw new ZCodeAuth.CaptchaRequiredException("configs says captcha");
                if (mode[0] == CLAIM_UNKNOWN) throw new ZCodeAuth.ClaimUnknownException("configs unreadable");
                if (mode[0] == TRANSPORT_CRASH) throw new Exception("socket hang up");
                if (mode[0] == UNREADABLE) throw new ZCodeAuth.ServiceException(200, 0, 0);
                if (mode[0] == CLAIM_ON_HTTP_ERROR) throw new ZCodeAuth.ServiceException(500, 1003, 0);
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
            ZCodeGiftControl.invalidate("A", "d"); mode[0]=LIMITED_SUMMARY;
            JSONObject limited = ZCodeGiftControl.summary("A", "d"); check(limited.optInt("status")==429 && limited.optLong("retryAfterMs")==5000, "limit response");
            ZCodeGiftControl.summary("A", "d"); check(reads.get()==3, "held avoids read");
            check(ZCodeGiftControl.summary("B", "d").optBoolean("ok") && reads.get()==4, "account isolation");
            now[0]+=5000; ZCodeGiftControl.summary("A", "d"); check(reads.get()==5, "expires");
            now[0]+=5000; mode[0]=LIMITED_CLAIM; JSONObject batch=ZCodeGiftControl.claimAll("C", "d");
            check(!batch.optBoolean("ok") && !batch.optBoolean("complete") && batch.getJSONArray("claimed").length()==1 && batch.optInt("status")==429, "partial limited batch");
            // a claim that was rate limited holds the claim alone: the balance read still goes
            // (the reference's rateWait is read by claimDaily only, never by giftOf/plansOf)
            int old=reads.get();
            check(ZCodeGiftControl.summary("C", "d").optBoolean("ok") && reads.get()==old+1,
                    "a limited claim does not blind the balance read");
            mode[0]=IN_FLIGHT; final JSONObject[] stale={null};
            Thread t=new Thread(() -> stale[0]=ZCodeGiftControl.summary("R", "d")); t.start();
            check(entered.await(3, TimeUnit.SECONDS), "in flight"); ZCodeGiftControl.invalidate("R", "d"); go.countDown(); t.join(3000);
            check(!t.isAlive() && !stale[0].optBoolean("ok"), "old generation rejected");
            mode[0]=OK; old=reads.get(); check(ZCodeGiftControl.summary("R", "d").optBoolean("ok") && reads.get()==old+1, "old generation not cached");
            check(ZCodeAuth.parseRetryAfter("2", 0)==2000 && ZCodeAuth.parseRetryAfter("nonsense", 0)==0, "retry seconds and invalid");
            check(ZCodeAuth.parseRetryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 1000)==9000, "retry date");
            check(ZCodeAuth.parseRetryAfter("999999999", 0)==ZCodeAuth.MAX_RETRY_AFTER_MS, "retry cap");

            // ---- 1003: claimed before, not a failure, and not a new grant --------------------
            mode[0]=OK;
            ZCodeGiftControl.summary("D", "d");                   // prime the cache for this account
            int cachedReads = reads.get();
            mode[0]=ALREADY_CLAIMED;
            JSONObject done = ZCodeGiftControl.claim("D", "d", "one");
            check(done.optBoolean("ok") && "done".equals(done.optString("outcome")) && done.optBoolean("alreadyClaimed"),
                    "1003 is done, not a failure: " + done);
            check(!done.has("status") && !done.has("retryAfterMs"), "1003 is not an error with a status: " + done);
            check(ZCodeGiftControl.summary("D", "d").optBoolean("complete") && reads.get()==cachedReads,
                    "a 1003 is no new grant, so the cached summary stays");
            JSONObject allDone = ZCodeGiftControl.claimAll("D2", "d");
            check(allDone.optBoolean("ok") && allDone.getJSONArray("done").length()==2 && allDone.getJSONArray("claimed").length()==0,
                    "a batch of 1003 answers is settled, claiming nothing: " + allDone);

            // ---- 1004: not eligible now — settled, and no rate hold -------------------------
            mode[0]=NOT_ELIGIBLE;
            JSONObject late = ZCodeGiftControl.claim("E", "d", "one");
            check(!late.optBoolean("ok") && "ineligible".equals(late.optString("outcome")) && late.optInt("businessCode")==1004,
                    "1004 is ineligible: " + late);
            check(late.optLong("recheckMs")==30*60_000L && !late.has("until"), "1004 asks again in half an hour, holds nothing: " + late);
            check(!late.has("status") || late.optInt("status")!=429, "1004 is no rate limit");
            JSONObject lateBatch = ZCodeGiftControl.claimAll("E2", "d");
            check(lateBatch.optBoolean("ok") && lateBatch.getJSONArray("ineligible").length()==2,
                    "a batch of 1004 answers claims nothing and is settled: " + lateBatch);

            // ---- 3007 / the captcha gate: handed to the ZCode app, never retried -------------
            mode[0]=CAPTCHA_REPLY;
            JSONObject captcha = ZCodeGiftControl.claim("F", "d", "one");
            check(!captcha.optBoolean("ok") && "captcha".equals(captcha.optString("outcome")) && captcha.optInt("businessCode")==3007,
                    "3007 is captcha: " + captcha);
            check(captcha.optString("error").contains("ZCode"), "the captcha answer names where to claim it: " + captcha);
            mode[0]=CAPTCHA_GATE;
            JSONObject gated = ZCodeGiftControl.claim("F2", "d", "one");
            check(!gated.optBoolean("ok") && "captcha".equals(gated.optString("outcome")), "a claim that was never posted is captcha too: " + gated);
            int said = claims.get();
            JSONObject captchaBatch = ZCodeGiftControl.claimAll("F3", "d");
            check(!captchaBatch.optBoolean("ok") && captchaBatch.getJSONArray("captcha").length()==1
                    && captchaBatch.optString("error").contains("ZCode"), "a captcha batch stops and names it: " + captchaBatch);
            check(claims.get()==said+1, "the batch stops at the first captcha, one claim attempt only");

            // ---- unknown is never a claim ---------------------------------------------------
            mode[0]=CLAIM_UNKNOWN;
            JSONObject unknown = ZCodeGiftControl.claim("G", "d", "one");
            check(!unknown.optBoolean("ok") && "unknown".equals(unknown.optString("outcome"))
                    && !unknown.has("claimed"), "an unposted claim is unknown: " + unknown);
            mode[0]=UNREADABLE;
            JSONObject unread = ZCodeGiftControl.claim("G2", "d", "one");
            check(!unread.optBoolean("ok") && !unread.has("claimed"), "an unreadable reply is not a claim: " + unread);
            mode[0]=TRANSPORT_CRASH;
            JSONObject crashed = ZCodeGiftControl.claim("G3", "d", "one");
            check(!crashed.optBoolean("ok") && "failed".equals(crashed.optString("outcome")), "a transport crash is failed: " + crashed);
            check(ZCodeGiftControl.summary("G3", "d").optBoolean("ok"), "a failed claim leaves the account readable");

            // ---- a claim's rate limit is the claim's, not the balance read's ---------------
            // The reference holds rateWait only for claimDaily; plansOf/giftOf keep reading the
            // balance. A limited claim must therefore not blind the panel.
            now[0]=100000;
            mode[0]=LIMITED_CLAIM;                       // claim("one") succeeds, claim("two") -> 3002
            JSONObject limitedClaim = ZCodeGiftControl.claim("I", "d", "two");
            check(!limitedClaim.optBoolean("ok") && limitedClaim.optInt("status")==429, "the claim is limited: " + limitedClaim);
            mode[0]=OK;
            int balanceReads = reads.get();
            JSONObject stillRead = ZCodeGiftControl.summary("I", "d");
            check(stillRead.optBoolean("ok") && stillRead.optBoolean("complete"),
                    "the balance is still read while a claim is held: " + stillRead);
            check(reads.get()==balanceReads+1, "and that read actually went out");
            // the claim itself is still held: no second request, and the answer is the same one
            mode[0]=LIMITED_CLAIM;
            int claimsBeforeHold = claims.get();
            JSONObject heldAgain = ZCodeGiftControl.claim("I", "d", "one");
            check(!heldAgain.optBoolean("ok") && heldAgain.optInt("status")==429 && claims.get()==claimsBeforeHold,
                    "a held claim asks ZCode nothing: " + heldAgain);
            // and a limited balance read still does hold the reads
            mode[0]=LIMITED_SUMMARY_ANY;
            ZCodeGiftControl.invalidate("I", "d");
            JSONObject heldBalance = ZCodeGiftControl.summary("I", "d");
            check(!heldBalance.optBoolean("ok") && heldBalance.optInt("status")==429, "a limited balance is held: " + heldBalance);
            mode[0]=OK;

            // ---- a 1003 body under an HTTP failure is unknown, not "already claimed" -------
            mode[0]=CLAIM_ON_HTTP_ERROR;
            JSONObject errorDone = ZCodeGiftControl.claim("G4", "d", "one");
            check(!errorDone.optBoolean("ok") && !"done".equals(errorDone.optString("outcome"))
                    && !errorDone.optBoolean("alreadyClaimed"),
                    "an HTTP 500 with a 1003 body is not reported as already claimed: " + errorDone);
            check(errorDone.optInt("status")==500 && errorDone.optInt("businessCode")==1003,
                    "it is carried as unknown with what the reply said: " + errorDone);

            // ---- an unknown claim list claims nothing -------------------------------------
            mode[0]=UNKNOWN_LIST;
            int posted = claims.get();
            JSONObject unknownList = ZCodeGiftControl.claimAll("H", "d");
            check(!unknownList.optBoolean("ok") && !unknownList.optBoolean("complete")
                    && unknownList.getJSONArray("claimed").length()==0
                    && unknownList.optString("error").contains("可领取计划"),
                    "an unknown claim list claims nothing and says so: " + unknownList);
            check(claims.get()==posted, "and posts no claim at all");
            System.out.println("ZCodeGiftControlOfflineTest PASS");
        } finally { ZCodeGiftControl.setTestHooks(null, null); }
    }
}
