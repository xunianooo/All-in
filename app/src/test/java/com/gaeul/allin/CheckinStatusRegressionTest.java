package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.util.concurrent.atomic.AtomicInteger;

public final class CheckinStatusRegressionTest {
    static int checks;static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        CheckinEvidence.begin();CheckinEvidence.prepare();CheckinEvidence.query();JSONObject r=CheckinEvidence.failure(new SSLException("credential should never echo"));check(r.getString("outcome").equals("failed")&&r.getString("reasonCode").equals("tls"),"failed TLS query is not a lost claim");check(!r.toString().contains("credential should never echo"),"exception raw text not echoed");
        CheckinEvidence.claim();r=CheckinEvidence.failure(new SocketTimeoutException("lost"));check(r.getString("outcome").equals("unknown")&&r.getBoolean("claimPossible"),"timeout after possible claim remains unknown");CheckinEvidence.clear();
        CheckinCenterRegressionTest.Host h=new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("a")));AtomicInteger claims=new AtomicInteger(),reads=new AtomicInteger();
        CheckinCenter.Transport t=new CheckinCenter.Transport(){public JSONObject checkin(String p,JSONObject a)throws Exception{claims.incrementAndGet();throw new SocketTimeoutException("lost claim");}public JSONObject status(String p,JSONObject a){reads.incrementAndGet();return new JSONObject().put("outcome","done").put("verified",true).put("credit",30).put("message","今日已签到，无需重复签到");}};
        try(CheckinCenter c=new CheckinCenter(h,t)){check(c.runOne("workbuddy","a",false).getString("outcome").equals("unknown"),"uncertain original claim");JSONObject view=c.reconcile("workbuddy","a");check(view.getInt("completed")==1,"official positive state reconciles old uncertainty");check(reads.get()==1&&claims.get()==1,"reconcile never resends claim");JSONObject cached=c.runOne("workbuddy","a",false);check(cached.getString("message").contains("无需重复")&&claims.get()==1,"duplicate request has normal completed feedback");}
        CheckinCenterRegressionTest.Host failed=new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("b")));
        CheckinCenter.Transport q=new CheckinCenter.Transport(){public JSONObject checkin(String p,JSONObject a)throws Exception{throw new SocketTimeoutException("lost claim");}public JSONObject status(String p,JSONObject a)throws Exception{throw new SocketTimeoutException("status timeout");}};
        try(CheckinCenter c=new CheckinCenter(failed,q)){c.runOne("workbuddy","b",false);JSONObject v=c.reconcile("workbuddy","b");JSONObject row=v.getJSONArray("accounts").getJSONObject(0);check(row.getBoolean("retryBlocked"),"failed re-read cannot unlock uncertain mutation");check(row.getString("message").contains("超时"),"status error has concrete reason");}
        CheckinCenterRegressionTest.Host unused=new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("c")));
        try(CheckinCenter c=new CheckinCenter(unused,(p,a)->{CheckinEvidence.prepare();CheckinEvidence.query();throw new SocketTimeoutException("query");})){JSONObject f=c.runOne("workbuddy","c",true);check(f.getString("outcome").equals("failed")&&!f.getBoolean("claimPossible"),"explicit pre-claim failure can be retried safely");}
        AtomicInteger statusCalls=new AtomicInteger();
        RaccoonAccountService raccoon=new RaccoonAccountService((method,path,desktop)->{
            statusCalls.incrementAndGet();check("GET".equals(method)&&path.startsWith("/api/web/points/v1/bills?")&&!desktop,"status read never requests a grant");
            return new JSONObject().put("data",new JSONObject().put("items",new JSONArray()));
        });
        JSONObject noRecord=raccoon.checkinStatus(System.currentTimeMillis());
        check("unknown".equals(noRecord.getString("outcome"))&&"no_today_record".equals(noRecord.getString("reasonCode")),"no current bill keeps uncertainty with a specific display reason");
        check(!noRecord.optBoolean("verified")&&!noRecord.optBoolean("claimPossible")&&!noRecord.has("credit"),"friendly status never invents verification or reward");
        check(noRecord.getString("message").contains("签到奖励记录")&&!noRecord.getString("message").contains("正证"),"readable feedback avoids internal evidence jargon");
        CheckinCenterRegressionTest.Host unresolved=new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("d")));
        AtomicInteger noGrants=new AtomicInteger();
        try(CheckinCenter c=new CheckinCenter(unresolved,new CheckinCenter.Transport(){public JSONObject checkin(String p,JSONObject a){noGrants.incrementAndGet();return new JSONObject().put("outcome","claimed");}public JSONObject status(String p,JSONObject a){return noRecord;}})){
            JSONObject view=c.reconcile("workbuddy","d");JSONObject row=view.getJSONArray("accounts").getJSONObject(0);
            check(view.getInt("completed")==0&&"unknown".equals(row.getString("outcome")),"copy changes do not increase the completed count");
            check("no_today_record".equals(row.getString("reasonCode")),"display reason survives persistence and snapshot");
            check(noGrants.get()==0,"readable reconciliation sends no new claim");
        }
        CheckinEvidence.clear();
        System.out.println("CheckinStatusRegressionTest PASS: "+checks+" checks");
    }
}
