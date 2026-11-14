package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CheckinCenterRegressionTest {
    static int checks;
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("name","same nickname").put("uid","u-"+id).put("access","secret-"+id).put("token","secret-"+id).put("refresh","refresh-"+id).put("jwt","jwt-"+id).put("enabled",true).put("type","oauth").put("domain","copilot.tencent.com");}
    static JSONObject config(String provider,JSONObject... accounts){JSONArray aa=new JSONArray();for(JSONObject a:accounts)aa.put(a);return new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id",provider).put("enabled",true).put("accounts",aa)));}
    static final class Host implements CheckinCenter.Host {
        JSONObject value;int saves,failAt=-1;boolean retired;
        Host(JSONObject config){value=config;}
        public Object lock(){return this;}public JSONObject config(){return value;}
        public boolean save(JSONObject next){saves++;if(saves==failAt)return false;value=new JSONObject(next.toString());return true;}
        public boolean retired(){return retired;}
    }
    static void daily() throws Exception {
        Host h=new Host(config("workbuddy",account("a"),account("b")));long[] clock={1794564000000L};AtomicInteger calls=new AtomicInteger();
        try(CheckinCenter c=new CheckinCenter(h,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","claimed").put("verified",true).put("credit",30).put("streak",3);},()->clock[0])) {
            check(c.snapshot().getBoolean("autoEnabled"),"automatic default true");check(c.snapshot().getInt("supported")==2,"both nickname-equal account IDs stay distinct");
            check(c.runOne("workbuddy","a",true).getString("outcome").equals("claimed"),"first claim");
            c.runOne("workbuddy","a",true);c.runOne("workbuddy","a",false);check(calls.get()==1,"manual and automatic share daily fence");
            c.runOne("workbuddy","b",true);check(calls.get()==2,"second account independent");check(c.snapshot().getInt("completed")==2,"only confirmed completions counted");
            String publicView=c.snapshot().toString();check(!publicView.contains("secret-")&&!publicView.contains("refresh-")&&!publicView.contains("jwt-"),"snapshot has no credentials");
            clock[0]=CheckinCenter.nextDay(clock[0])+1000;c.runOne("workbuddy","a",true);check(calls.get()==3,"new UTC8 day gets one new attempt");
            c.settings(new JSONObject().put("autoEnabled",false));clock[0]=CheckinCenter.nextDay(clock[0])+1000;c.runOne("workbuddy","a",true);check(calls.get()==3,"off blocks automatic claim");c.runOne("workbuddy","a",false);check(calls.get()==4,"off does not prohibit explicit manual claim");
            int saves=h.saves;try{c.settings(new JSONObject().put("autoEnabled","true"));throw new AssertionError("invalid flag accepted");}catch(IllegalArgumentException expected){check(h.saves==saves,"invalid settings do not write");}
        }
    }
    static void unknownAndFailure() throws Exception {
        Host h=new Host(config("workbuddy",account("a")));AtomicInteger calls=new AtomicInteger();long[] clock={1794564000000L};
        try(CheckinCenter c=new CheckinCenter(h,(p,a)->{calls.incrementAndGet();throw new java.io.IOException("lost claim answer");},()->clock[0])) {
            check(c.runOne("workbuddy","a",false).getString("outcome").equals("unknown"),"lost answer is unknown");c.runOne("workbuddy","a",true);check(calls.get()==1,"no same-day replay after unknown");check(c.snapshot().getInt("completed")==0,"unknown never counted completed");
        }
        try(CheckinCenter restarted=new CheckinCenter(h,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","claimed").put("verified",true);},()->clock[0])) {restarted.runOne("workbuddy","a",true);check(calls.get()==1,"durable outcome survives restart");}
        Host pending=new Host(config("workbuddy",account("a")));pending.failAt=2;calls.set(0);
        try(CheckinCenter c=new CheckinCenter(pending,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","claimed").put("verified",true);},()->clock[0])) {check(c.runOne("workbuddy","a",true).getString("outcome").equals("unknown"),"result save failure honest");check(c.snapshot().getJSONArray("accounts").getJSONObject(0).getString("outcome").equals("unknown"),"abandoned intent shown unknown");}
        pending.failAt=-1;
        try(CheckinCenter c=new CheckinCenter(pending,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","claimed").put("verified",true);},()->clock[0])) {c.runOne("workbuddy","a",false);check(calls.get()==1,"write-ahead intent prevents restart duplication");}
        Host noIntent=new Host(config("workbuddy",account("a")));noIntent.failAt=1;calls.set(0);
        try(CheckinCenter c=new CheckinCenter(noIntent,(p,a)->{calls.incrementAndGet();return new JSONObject();},()->clock[0])) {try{c.runOne("workbuddy","a",true);throw new AssertionError("intent failure ignored");}catch(java.io.IOException expected){check(calls.get()==0,"no mutation before durable intent");}}
        Host safeRead=new Host(config("workbuddy",account("a")));calls.set(0);
        try(CheckinCenter c=new CheckinCenter(safeRead,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","failed").put("safeToRetry",true);},()->clock[0])) {c.runOne("workbuddy","a",true);c.runOne("workbuddy","a",false);check(calls.get()==1,"read failure backs off");clock[0]+=31*60000L;c.runOne("workbuddy","a",true);check(calls.get()==2,"known read-only failure can recheck later");}
    }
    static void currentAccountAndSafety() throws Exception {
        Host changed=new Host(config("workbuddy",account("a")));
        try(CheckinCenter c=new CheckinCenter(changed,(p,a)->{changed.value.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("uid","new-owner");return new JSONObject().put("outcome","claimed").put("verified",true);})) {check(c.runOne("workbuddy","a",false).getString("outcome").equals("account_changed"),"late result never attached to another owner");check(changed.value.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("uid").equals("new-owner"),"new owner not reverted");}
        Host rotated=new Host(config("workbuddy",account("a")));
        try(CheckinCenter c=new CheckinCenter(rotated,(p,a)->{rotated.value.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("access","new-access");return new JSONObject().put("outcome","claimed").put("verified",true).put("message","secret-a refresh-a jwt-a");})) {c.runOne("workbuddy","a",true);check(rotated.value.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("access").equals("new-access"),"result merge preserves renewed access");check(!c.snapshot().toString().contains("secret-a")&&!c.snapshot().toString().contains("jwt-a"),"upstream message secrets removed");}
        for(String provider:CheckinCenter.SUPPORTED){check(CheckinCenter.eligible(provider,account("x")),"eligible supported provider "+provider);}
        check(!CheckinCenter.enabled(config("zcode",account("x")),"zcode"),"gift claims not default automatic checkin");
        check(!CheckinCenter.eligible("workbuddy-ai",account("x")),"AI build no checkin");check(!CheckinCenter.eligible("workbuddy",account("x").put("type","api")),"API key has no daily claim");
        Host inactive=new Host(config("workbuddy",account("a")));AtomicInteger calls=new AtomicInteger();
        try(CheckinCenter c=new CheckinCenter(inactive,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","inactive");})) {c.runOne("workbuddy","a",true);c.runOne("workbuddy","a",false);check(calls.get()==1,"inactive activity is not hammered");check(c.snapshot().getInt("completed")==0,"inactive is not success");}
    }
    static void concurrency() throws Exception {
        Host h=new Host(config("workbuddy",account("a")));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        try(CheckinCenter c=new CheckinCenter(h,(p,a)->{calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS),"test release timely");return new JSONObject().put("outcome","claimed").put("verified",true);})) {
            JSONObject accepted=c.enqueue("workbuddy","a",false);check(accepted.getBoolean("accepted"),"async accepted");check(entered.await(3,TimeUnit.SECONDS),"async worker started");
            check(!c.enqueue("workbuddy","a",false).getBoolean("accepted"),"only one queued batch");check(c.runOne("workbuddy","a",false).getString("outcome").equals("running"),"same account concurrent call excluded");check(calls.get()==1,"one actual mutation while concurrent");release.countDown();
        }finally{release.countDown();}
    }
    public static void main(String[] args)throws Exception {daily();unknownAndFailure();currentAccountAndSafety();concurrency();System.out.println("CheckinCenterRegressionTest PASS: "+checks+" checks");}
}
