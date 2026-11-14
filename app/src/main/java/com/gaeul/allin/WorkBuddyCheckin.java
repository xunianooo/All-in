package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** WorkBuddy China 0.2.34 meter contract. No AI endpoint, refresh replay or alternate claim path. */
final class WorkBuddyCheckin {
    static final String STATUS="/v2/billing/meter/checkin-activity-status", CLAIM="/v2/billing/meter/daily-checkin";
    private WorkBuddyCheckin() { }
    private static final class Reply { int http, code; JSONObject data; }
    private static Reply request(JSONObject a,String path,WorkBuddyLogin.ConnectionFactory factory) throws Exception {
        if(STATUS.equals(path))CheckinEvidence.query();
        String origin=WorkBuddyAuth.origin("workbuddy");
        Map<String,String> headers=WorkBuddyPolicy.catalogHeaders("workbuddy",origin+"/v3/config",a.optString("access",a.optString("token","")),a);
        if(headers.isEmpty())throw new IOException("sign in required");
        HttpURLConnection c=factory.open(origin+path);
        try {
            c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(5000);c.setReadTimeout(5000);
            for(String h:new String[]{"Authorization","X-User-Id","X-Domain","X-Enterprise-Id","X-Tenant-Id","X-Product","X-IDE-Type"})if(headers.containsKey(h))c.setRequestProperty(h,headers.get(h));
            c.setRequestProperty("User-Agent","WorkBuddy/5.9.3");c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);c.setFixedLengthStreamingMode(2);
            try(OutputStream o=c.getOutputStream()){if(CLAIM.equals(path))CheckinEvidence.claim();o.write(new byte[]{'{','}'});}
            Reply out=new Reply();out.http=c.getResponseCode();CheckinEvidence.reply(out.http,"");InputStream input=out.http>=200&&out.http<300?c.getInputStream():c.getErrorStream();
            if(input==null){out.code=-1;return out;}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();long deadline=System.nanoTime()+5000000000L;
            try(InputStream in=input){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(bytes.size()+n>65536||System.nanoTime()>deadline)throw new IOException("checkin response bound");bytes.write(b,0,n);}}
            JSONObject json=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));Object code=json.opt("code");if(code instanceof Number){double n=((Number)code).doubleValue();if(!Double.isFinite(n)||n!=((Number)code).longValue()||n<0||n>Integer.MAX_VALUE)throw new IOException("invalid checkin code");}else if(!(code instanceof String)||!((String)code).matches("[0-9]{1,9}"))throw new IOException("invalid checkin code");out.code=json.optInt("code",-1);CheckinEvidence.reply(out.http,Integer.toString(out.code));out.data=json.optJSONObject("data");return out;
        } finally {c.disconnect();}
    }
    static JSONObject checkin(String provider,JSONObject a,WorkBuddyLogin.ConnectionFactory factory) {
        CheckinEvidence.prepare();
        if(!"workbuddy".equals(provider)||!WorkBuddyAllowance.eligible(a))return new JSONObject().put("outcome","ineligible").put("message","此 WorkBuddy 版本或 API Key 不支持签到");
        Reply status;
        try {status=request(a,STATUS,factory);}catch(Exception e){return CheckinEvidence.failure(e);}
        if(status.http>=200&&status.http<300&&status.code==1001)return new JSONObject().put("outcome","done").put("verified",true);
        if(status.http<200||status.http>=300||status.code!=0)return new JSONObject().put("outcome",status.http==401?"ineligible":"failed").put("safeToRetry",true).put("message","签到状态查询被上游拒绝，未发送领取请求");
        JSONObject s=status.data;if(s==null||!(s.opt("active") instanceof Boolean))return new JSONObject().put("outcome","failed").put("safeToRetry",true).put("message","签到活动状态不完整，未发送领取请求");
        if(!s.optBoolean("active"))return new JSONObject().put("outcome","inactive");
        Object checked=s.has("today_checked_in")?s.opt("today_checked_in"):s.opt("todayCheckedIn");
        if(!(checked instanceof Boolean))return new JSONObject().put("outcome","failed").put("safeToRetry",true).put("message","今日签到状态不完整，未发送领取请求");
        if(Boolean.TRUE.equals(checked)){JSONObject r=new JSONObject().put("outcome","done").put("verified",true);number(r,"credit",s.has("today_credit")?s.opt("today_credit"):s.opt("daily_credit"));return r;}
        // One mutation only. A lost answer is not permission to send the claim again.
        Reply claim;try{claim=request(a,CLAIM,factory);}catch(Exception lost){return CheckinEvidence.failure(lost);}
        if(claim.http==401)return new JSONObject().put("outcome","ineligible").put("message","WorkBuddy 拒绝当前客户端领取，请在官方客户端签到");
        if(claim.http<200||claim.http>=300)return new JSONObject().put("outcome","unknown").put("message","领取未得到可确认答复，今天不重复发送");
        if(claim.code==1001)return new JSONObject().put("outcome","done").put("verified",true);
        if(claim.code==1002)return new JSONObject().put("outcome","ineligible");
        if(claim.code==1003)return new JSONObject().put("outcome","inactive");
        if(claim.code!=0)return new JSONObject().put("outcome","unknown").put("message","领取未得到成功确认，今天不重复发送");
        JSONObject result=new JSONObject().put("outcome","claimed").put("verified",true).put("evidenceType","official_claim");if(claim.data!=null){number(result,"credit",claim.data.opt("credit"));number(result,"streak",claim.data.opt("streak_days"));String message=claim.data.optString("message","");if(!message.isEmpty())result.put("message",message);}return result;
    }
    static JSONObject status(String provider,JSONObject a,WorkBuddyLogin.ConnectionFactory factory) {
        CheckinEvidence.prepare();
        if(!"workbuddy".equals(provider)||!WorkBuddyAllowance.eligible(a))return new JSONObject().put("outcome","ineligible").put("message","此版本或凭据不支持签到");
        try {
            Reply r=request(a,STATUS,factory);
            if(r.http>=200&&r.http<300&&r.code==1001)return new JSONObject().put("outcome","done").put("verified",true).put("message","今日已签到，无需重复签到");
            if(r.http<200||r.http>=300||r.code!=0)return CheckinEvidence.failure(new IOException("status refused"));
            JSONObject data=r.data;
            if(data==null||!(data.opt("active") instanceof Boolean))return new JSONObject().put("outcome","failed").put("safeToRetry",true).put("claimPossible",false).put("message","上游签到状态缺少 active 字段，未发送领取请求");
            if(!data.optBoolean("active"))return new JSONObject().put("outcome","inactive").put("verified",true).put("message","签到活动未开放");
            Object checked=data.has("today_checked_in")?data.opt("today_checked_in"):data.opt("todayCheckedIn");
            if(!(checked instanceof Boolean))return new JSONObject().put("outcome","failed").put("safeToRetry",true).put("claimPossible",false).put("message","上游没有明确的今日签到标记，未发送领取请求");
            JSONObject out=new JSONObject().put("outcome",Boolean.TRUE.equals(checked)?"done":"not_checked").put("verified",true).put("message",Boolean.TRUE.equals(checked)?"今日已签到，无需重复签到":"今日尚未签到");
            if(Boolean.TRUE.equals(checked)){number(out,"credit",data.has("today_credit")?data.opt("today_credit"):data.opt("daily_credit"));number(out,"streak",data.opt("streak_days"));}return out;
        }catch(Exception e){return CheckinEvidence.failure(e);}
    }
    private static void number(JSONObject out,String key,Object raw){if(!(raw instanceof Number)&&!(raw instanceof String))return;try{double n=Double.parseDouble(raw.toString());if(Double.isFinite(n)&&n>=0)out.put(key,n);}catch(Exception ignored){}}
}
