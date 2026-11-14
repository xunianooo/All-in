package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Measures public output and actual transport calls, not just intended branches. */
public final class CheckinProofRegressionTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final long NOW=RaccoonAccountService.billTime("2026-11-14T00:20:00+08:00");
    static JSONObject bill(String id,String at,String type,Object points){return new JSONObject().put("bill_id",id).put("created_at",at).put("biz_type",type).put("points",points);}
    static JSONObject response(JSONArray rows){return new JSONObject().put("code",0).put("data",new JSONObject().put("items",rows));}
    static JSONArray oldPage(int offset){JSONArray a=new JSONArray();for(int i=0;i<20;i++)a.put(bill("old-"+(offset+i),"2026-11-13T00:01:00+08:00","daily_grant",300));return a;}
    static void bills()throws Exception {
        check("2026-11-14".equals(CheckinCenter.day(RaccoonAccountService.billTime("2026-11-13T16:01:00Z"))),"explicit UTC date normalized to UTC8");
        check("2026-11-13".equals(CheckinCenter.day(RaccoonAccountService.billTime("2026-11-14T00:05:00+09:00"))),"date prefix alone is not local day");
        for(String bad:new String[]{"2026-11-14T00:01:00","2026-02-30T00:00:00+08:00","2026-11-14T25:00:00Z","2026-11-14T00:00:00+19:00","2026-11-14T00:00:00Z trailing"})check(RaccoonAccountService.billTime(bad)==null,"malformed/unqualified date rejected");
        check(RaccoonAccountService.billTime("2026-11-14T00:01:00.123456+08:00")!=null,"fractional timestamp supported");
        for(String bad:new String[]{"/api/web/points/v1/bills?paging.limit=20&paging.offset=-20","/api/web/points/v1/bills?paging.limit=20&paging.offset=1","/api/web/points/v1/bills?paging.limit=20&paging.offset=100","/api/web/points/v1/bills?paging.limit=20&paging.offset=20&evil=1"})check(!RaccoonAccountService.billsPathAllowed(bad),"exact bounded paths only");
        check(RaccoonAccountService.billsPathAllowed(RaccoonAccountService.billsPath(80)),"last permitted page allowed");
        AtomicInteger reads=new AtomicInteger(),claims=new AtomicInteger();
        RaccoonAccountService paged=new RaccoonAccountService((m,p,d)->{check("GET".equals(m)&&!d,"page lookup is read-only");reads.incrementAndGet();if(p.endsWith("=0"))return response(oldPage(0));return response(new JSONArray().put(bill("today","2026-11-13T16:01:00Z","daily_grant",300)).put(bill("today","2026-11-13T16:01:00Z","daily_grant",300)));});
        JSONObject r=paged.checkinStatus(NOW);check(r.getBoolean("verified")&&r.getLong("credit")==300,"second page positive grant deduplicated");check(reads.get()==2&&r.getInt("pagesRead")==2&&r.getBoolean("coverageComplete"),"measured page coverage");
        reads.set(0);RaccoonAccountService capped=new RaccoonAccountService((m,p,d)->{if(d){claims.incrementAndGet();return new JSONObject();}int n=reads.getAndIncrement();return response(oldPage(n*20));});
        r=capped.checkIn(NOW);check("unknown".equals(r.getString("outcome"))&&!r.optBoolean("verified"),"full bounded window cannot become success");check(reads.get()==5&&r.getInt("billsRead")==100&&!r.getBoolean("coverageComplete"),"100 rows are not exhaustive proof");check(claims.get()==0,"incomplete pre-read does not send claim");
        reads.set(0);RaccoonAccountService ignoredOffset=new RaccoonAccountService((m,p,d)->{reads.incrementAndGet();return response(oldPage(0));});r=ignoredOffset.checkinStatus(NOW);check(reads.get()==2&&!r.getBoolean("coverageComplete"),"repeated pages stop safely");check(r.getInt("billsRead")==20&&r.getInt("rowsRead")==40,"repeated rows are not advertised as unique bills");
        RaccoonAccountService noGrant=new RaccoonAccountService((m,p,d)->{if(d){claims.incrementAndGet();return new JSONObject();}return response(new JSONArray());});claims.set(0);r=noGrant.checkIn(NOW);check(claims.get()==1&&"unknown".equals(r.getString("outcome"))&&!r.optBoolean("verified")&&!r.has("credit"),"HTTP200 without a bill never means done");
        claims.set(0);RaccoonAccountService already=new RaccoonAccountService((m,p,d)->{if(d)claims.incrementAndGet();return response(new JSONArray().put(bill("today","2026-11-14T00:01:00+08:00","daily_grant",300)));});r=already.checkIn(NOW);check(claims.get()==0&&r.optBoolean("verified")&&"done".equals(r.optString("outcome")),"existing daily grant suppresses mutation");
        AtomicInteger stage=new AtomicInteger();claims.set(0);RaccoonAccountService added=new RaccoonAccountService((m,p,d)->{if(d){claims.incrementAndGet();return new JSONObject();}return response(stage.getAndIncrement()==0?new JSONArray():new JSONArray().put(bill("new","2026-11-14T00:02:00+08:00","daily_grant",300)));});r=added.checkIn(NOW);check("claimed".equals(r.getString("outcome"))&&r.getBoolean("verified")&&r.getLong("credit")==300&&claims.get()==1,"new attributable daily bill verifies one claim");
        RaccoonAccountService nonDaily=new RaccoonAccountService((m,p,d)->response(new JSONArray().put(bill("gift","2026-11-14T00:01:00+08:00","reward_grant",999)).put(bill("fraction","2026-11-14T00:01:00+08:00","daily_grant",0.5)).put(bill("ambiguous","2026-11-14T00:01:00","daily_grant",300))));r=nonDaily.checkinStatus(NOW);check(!r.optBoolean("verified")&&!r.has("credit"),"one-time gifts, fractional points and missing zones are not daily evidence");
        RaccoonAccountService badCode=new RaccoonAccountService((m,p,d)->response(new JSONArray().put(bill("x","2026-11-14T00:01:00+08:00","daily_grant",300))).put("code",0.5));try{badCode.checkinStatus(NOW);throw new AssertionError("fractional code accepted");}catch(IOException expected){checks++;}
        claims.set(0);RaccoonAccountService uncertainDate=new RaccoonAccountService((m,p,d)->{if(d)claims.incrementAndGet();return response(new JSONArray().put(bill("maybe","2026-11-14T00:01:00","daily_grant",300)));});r=uncertainDate.checkIn(NOW);check(claims.get()==0&&!r.optBoolean("verified"),"ambiguous date does not permit another grant");
        for(Object v:new Object[]{3.5,"3.5",true,null,-1,Double.POSITIVE_INFINITY})check(MiniMaxAuth.checkinInteger(v)==null,"non-integer MiniMax status rejected");check(MiniMaxAuth.checkinInteger(3)==3&&MiniMaxAuth.checkinInteger("3")==3,"known integer status accepted");
        CheckinEvidence.clear();
    }
    static void waitIdle(CheckinCenter c)throws Exception {long end=System.nanoTime()+3000000000L;while(c.snapshot().getBoolean("running")){if(System.nanoTime()>end)throw new AssertionError("batch did not finish");Thread.sleep(5);}}
    static void countsAndOperations()throws Exception {
        String[] providers={"workbuddy","trae-cn","minimax-code","raccoon","loomy"};JSONArray ps=new JSONArray();
        for(String p:providers){JSONArray aa=new JSONArray().put(CheckinCenterRegressionTest.account(p+"-a"));if(p.equals("workbuddy")||p.equals("raccoon"))aa.put(CheckinCenterRegressionTest.account(p+"-b"));ps.put(new JSONObject().put("id",p).put("accounts",aa).put("enabled",true));}
        CheckinCenterRegressionTest.Host h=new CheckinCenterRegressionTest.Host(new JSONObject().put("providers",ps));long[] time={NOW};AtomicInteger calls=new AtomicInteger();
        CheckinCenter.Transport t=new CheckinCenter.Transport(){public JSONObject checkin(String p,JSONObject a){calls.incrementAndGet();if(p.equals("loomy"))return new JSONObject().put("outcome","done").put("credit",999);if(p.equals("raccoon")&&a.getString("id").endsWith("-b"))return new JSONObject().put("outcome","unknown").put("claimPossible",true);return new JSONObject().put("outcome","done").put("verified",true).put("credit",100);}public JSONObject status(String p,JSONObject a)throws Exception{throw new java.net.SocketTimeoutException("read unavailable");}};
        try(CheckinCenter c=new CheckinCenter(h,t,()->time[0])){
            for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i);JSONArray aa=p.getJSONArray("accounts");for(int j=0;j<aa.length();j++)c.runOne(p.getString("id"),aa.getJSONObject(j).getString("id"),true);}
            JSONObject stored=h.value.getJSONObject("checkin").getJSONObject("records");stored.getJSONObject("loomy:loomy-a").put("outcome","done").put("credit",999).remove("verified");
            int naive=0;for(String k:stored.keySet())if("done".equals(stored.getJSONObject(k).optString("outcome")))naive++;
            JSONObject v=c.snapshot();check(naive==6&&v.getInt("completed")==5,"actual completed output drops six to five");check(v.getInt("available")==1&&v.getInt("pending")==1,"quota and unresolved counts are separate");
            for(int i=0;i<v.getJSONArray("accounts").length();i++){JSONObject row=v.getJSONArray("accounts").getJSONObject(i);if(row.getString("provider").equals("loomy"))check("available".equals(row.getString("outcome"))&&!row.has("credit"),"legacy Loomy reward removed from public output");}
            time[0]+=600000;check(c.enqueue("","",false).getBoolean("accepted"),"manual action accepted");waitIdle(c);v=c.snapshot();JSONObject op=v.getJSONObject("operation");check(op.getInt("newRewards")==0&&op.getInt("reusedConfirmed")==5&&op.getInt("available")==1&&op.getInt("pending")==1,"manual receipt distinguishes reuse, quota and unknown");check(calls.get()==7,"one-key action sends no repeated upstream calls");
            check(!op.toString().contains("fingerprint")&&!op.toString().contains("secret-")&&!op.toString().contains("refresh-"),"operation receipt carries no credentials/internal identity hash");
            JSONObject first=v.getJSONArray("accounts").getJSONObject(0);check(first.getLong("actionAt")==NOW&&first.getBoolean("actionAutomatic"),"original automatic action time survives later click");check(op.getLong("startedAt")==time[0],"manual click has its own timestamp");
            time[0]=CheckinCenter.nextDay(time[0])+1000;c.reconcile("workbuddy","workbuddy-a");check(c.snapshot().getInt("completed")==0,"failed query cannot carry yesterday success into today");check(calls.get()==7,"cross-day query is not a claim");
        }
        CheckinCenterRegressionTest.Host unsigned=new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("x")));
        try(CheckinCenter c=new CheckinCenter(unsigned,(p,a)->new JSONObject().put("outcome","claimed").put("verified","true").put("credit",100))){c.runOne("workbuddy","x",false);check(c.snapshot().getInt("completed")==0&&!c.snapshot().getJSONArray("accounts").getJSONObject(0).has("credit"),"string true is not official evidence");}
        try(CheckinCenter c=new CheckinCenter(new CheckinCenterRegressionTest.Host(CheckinCenterRegressionTest.config("workbuddy",CheckinCenterRegressionTest.account("denied"))),(p,a)->new JSONObject().put("ok",false).put("outcome","claimed").put("verified",true).put("credit",100))){c.runOne("workbuddy","denied",false);check(c.snapshot().getInt("completed")==0,"explicit failure beats a contradictory success marker");}
    }
    public static void main(String[] args)throws Exception {bills();countsAndOperations();System.out.println("CheckinProofRegressionTest PASS: "+checks+" checks");}
}
