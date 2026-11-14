package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorkBuddyCheckinRegressionTest {
    static int checks;static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static class Conn extends HttpURLConnection {
        JSONObject payload;int http=200;boolean disconnected;ByteArrayOutputStream sent=new ByteArrayOutputStream();String text;
        Conn(String url,JSONObject json)throws Exception{super(new URL(url));payload=json;}
        public void connect(){throw new AssertionError("real network prohibited");}public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return sent;}public int getResponseCode(){return http;}
        public InputStream getInputStream(){return new ByteArrayInputStream((text==null?payload.toString():text).getBytes(StandardCharsets.UTF_8));}public InputStream getErrorStream(){return getInputStream();}
    }
    static JSONObject env(int code,JSONObject data){return new JSONObject().put("code",code).put("data",data);}
    static JSONObject status(boolean active,boolean checked){return env(0,new JSONObject().put("active",active).put("todayCheckedIn",checked));}
    static JSONObject ask(JSONObject status,int code,JSONObject result,List<Conn> calls)throws Exception {
        JSONObject a=WorkBuddyAllowanceRegressionTest.account("a");
        return WorkBuddyCheckin.checkin("workbuddy",a,url->{Conn c=new Conn(url,url.endsWith(WorkBuddyCheckin.STATUS)?status:env(code,result));calls.add(c);return c;});
    }
    public static void main(String[] args)throws Exception {
        List<Conn> calls=new ArrayList<>();JSONObject r=ask(status(true,false),0,new JSONObject().put("credit",30).put("streak_days",3).put("message","连续签到3天"),calls);
        check(r.getString("outcome").equals("claimed")&&r.getDouble("credit")==30&&r.getDouble("streak")==3,"official reward and streak");
        check(calls.size()==2,"one status then one claim");
        check(calls.get(0).getURL().toString().equals("https://copilot.tencent.com"+WorkBuddyCheckin.STATUS)&&calls.get(1).getURL().toString().equals("https://copilot.tencent.com"+WorkBuddyCheckin.CLAIM),"fixed official paths");
        for(Conn c:calls){check(c.getRequestMethod().equals("POST")&&c.sent.toString("UTF-8").equals("{}"),"post empty object");check(!c.getInstanceFollowRedirects()&&c.disconnected,"no redirects, always disconnected");check(c.getRequestProperty("Authorization").equals("Bearer fixture-a")&&c.getRequestProperty("X-User-Id").equals("uid-a"),"selected account identity headers");}
        calls.clear();r=ask(status(false,false),0,null,calls);check(r.getString("outcome").equals("inactive")&&calls.size()==1,"inactive never claims");
        calls.clear();r=ask(env(0,new JSONObject().put("active",true).put("today_checked_in",true).put("today_credit",30).put("daily_credit",100)),0,null,calls);check(r.getString("outcome").equals("done")&&r.getDouble("credit")==30&&calls.size()==1,"actual today credit wins over daily forecast");
        calls.clear();r=ask(env(1001,null),0,null,calls);check(r.getString("outcome").equals("done")&&calls.size()==1,"status already checked");
        for(int code:new int[]{1001,1002,1003,10000}) {calls.clear();r=ask(status(true,false),code,null,calls);String expected=code==1001?"done":code==1002?"ineligible":code==1003?"inactive":"unknown";check(r.getString("outcome").equals(expected)&&calls.size()==2,"business outcome no claim replay "+code);}
        calls.clear();r=ask(env(0,new JSONObject().put("active",true)),0,null,calls);check(r.getString("outcome").equals("failed")&&calls.size()==1,"missing checked flag cannot authorize claim");
        calls.clear();r=WorkBuddyCheckin.checkin("workbuddy",WorkBuddyAllowanceRegressionTest.account("a"),url->{Conn c=new Conn(url,status(true,false));if(url.endsWith(WorkBuddyCheckin.CLAIM))c.http=401;calls.add(c);return c;});check(r.getString("outcome").equals("ineligible")&&calls.size()==2,"client eligibility refusal no refresh/replay");
        calls.clear();r=WorkBuddyCheckin.checkin("workbuddy",WorkBuddyAllowanceRegressionTest.account("a"),url->{Conn c=new Conn(url,status(true,false));if(url.endsWith(WorkBuddyCheckin.CLAIM))c.text="<html>";calls.add(c);return c;});check(r.getString("outcome").equals("unknown")&&calls.size()==2,"unreadable claim unknown not success");
        calls.clear();r=WorkBuddyCheckin.checkin("workbuddy",WorkBuddyAllowanceRegressionTest.account("a"),url->{if(url.endsWith(WorkBuddyCheckin.CLAIM))throw new IOException("lost answer");Conn c=new Conn(url,status(true,false));calls.add(c);return c;});check(r.getString("outcome").equals("failed")&&!r.getBoolean("claimPossible"),"factory failure before claim send is a query/preflight failure");
        AtomicCounter count=new AtomicCounter();for(String provider:new String[]{"workbuddy-ai","unsupported"})check(WorkBuddyCheckin.checkin(provider,WorkBuddyAllowanceRegressionTest.account("a"),url->{count.n++;throw new AssertionError();}).getString("outcome").equals("ineligible"),"no unsupported origin request");
        check(WorkBuddyCheckin.checkin("workbuddy",WorkBuddyAllowanceRegressionTest.account("a").put("type","api"),url->{count.n++;throw new AssertionError();}).getString("outcome").equals("ineligible")&&count.n==0,"no static-key claim");
        r=WorkBuddyCheckin.checkin("workbuddy",WorkBuddyAllowanceRegressionTest.account("a"),url->new Conn(url,new JSONObject().put("code",0.5).put("data",new JSONObject().put("active",true).put("todayCheckedIn",false))));check(r.getString("outcome").equals("failed"),"fractional business code rejected");
        System.out.println("WorkBuddyCheckinRegressionTest PASS: "+checks+" checks");
    }
    static final class AtomicCounter {int n;}
}
