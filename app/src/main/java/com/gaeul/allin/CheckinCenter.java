package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Daily per-account check-ins. Durable intent precedes a claim; unknown claims are never replayed that day. */
final class CheckinCenter implements AutoCloseable {
    static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList("workbuddy", "minimax-code", "raccoon", "loomy", "trae-cn", "zcode"));
    interface Host { Object lock(); JSONObject config(); boolean save(JSONObject next); boolean retired(); }
    interface Transport { JSONObject checkin(String provider, JSONObject account) throws Exception; default JSONObject status(String provider,JSONObject account)throws Exception{return new JSONObject().put("outcome","unknown").put("message","此供应商没有独立签到状态接口，请在官方客户端核对");} }
    interface Clock { long now(); }
    private final Host host; private final Transport transport; private final Clock clock;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean batch = new AtomicBoolean();
    private final Set<String> active = new HashSet<>();
    private final Map<String,JSONObject> localFailures = new ConcurrentHashMap<>();
    private volatile JSONObject manualOperation;
    private volatile boolean closed, started;
    CheckinCenter(Host host, Transport transport) { this(host, transport, System::currentTimeMillis); }
    CheckinCenter(Host host, Transport transport, Clock clock) {
        this.host=host; this.transport=transport; this.clock=clock;
        worker=Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"allin-checkin");t.setDaemon(true);return t; });
    }
    private void live() throws IOException { if(closed || host.retired()) throw new IOException("checkin retired"); }
    private static JSONObject copy(JSONObject j) { return new JSONObject(j.toString()); }
    private static String key(String provider,String id) { return provider+":"+id; }
    static String day(long now) { SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));return f.format(new Date(now)); }
    static long nextDay(long now) { Calendar c=Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));c.setTimeInMillis(now);c.add(Calendar.DATE,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis(); }
    private static JSONObject options(JSONObject config) { JSONObject o=config.optJSONObject("checkin");return o==null?new JSONObject():o; }
    private static JSONObject records(JSONObject config) { JSONObject r=options(config).optJSONObject("records");return r==null?new JSONObject():r; }
    static boolean enabled(JSONObject config,String provider) { JSONObject o=options(config),p=o.optJSONObject("providers");return o.optBoolean("autoEnabled",true) && (p==null ? !"zcode".equals(provider) : p.optBoolean(provider,!"zcode".equals(provider))); }
    private static String fingerprint(JSONObject a) {
        try {
            String s=a.optString("id")+"\n"+a.optString("uid")+"\n"+a.optString("domain")+"\n"+a.optString("enterpriseId")+"\n"+a.optString("addedAt");
            byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte x:b)h.append(String.format(Locale.US,"%02x",x&255));return h.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static JSONObject account(JSONObject config,String provider,String id) {
        JSONArray ps=config.optJSONArray("providers");JSONObject found=null;
        for(int i=0;ps!=null&&i<ps.length();i++) { JSONObject p=ps.optJSONObject(i);if(p==null||!provider.equals(p.optString("id")))continue;if(found!=null)return null;found=p; }
        if(found==null||!found.optBoolean("enabled",true))return null;
        JSONArray aa=found.optJSONArray("accounts");JSONObject a=null;
        for(int i=0;aa!=null&&i<aa.length();i++) { JSONObject x=aa.optJSONObject(i);if(x!=null&&id.equals(x.optString("id"))) { if(a!=null)return null;a=x; } }
        return a!=null&&a.optBoolean("enabled",true)?a:null;
    }
    static boolean eligible(String provider,JSONObject a) {
        if(!SUPPORTED.contains(provider)||a==null||a.optString("id").isEmpty())return false;
        if("api".equals(a.optString("type")))return false;
        if("workbuddy".equals(provider))return WorkBuddyAllowance.eligible(a) && !a.optString("access",a.optString("token","")).isEmpty();
        if("minimax-code".equals(provider))return !a.optString("access",a.optString("token","")).isEmpty();
        if("zcode".equals(provider))return !a.optString("jwt",a.optString("giftJwt","")).isEmpty();
        return !a.optString("token",a.optString("access","")).isEmpty();
    }
    private static void putRecord(JSONObject config,String k,JSONObject result) {
        JSONObject o=options(config);config.put("checkin",o);JSONObject r=o.optJSONObject("records");if(r==null){r=new JSONObject();o.put("records",r);}r.put(k,result);
    }
    private static boolean confirmed(JSONObject r) {
        return r!=null&&Boolean.TRUE.equals(r.opt("verified"))&&Arrays.asList("claimed","done","refreshed").contains(r.optString("outcome"));
    }
    private static JSONObject normalized(String provider,JSONObject raw) {
        JSONObject r=copy(raw);String state=r.optString("outcome","unknown");
        if(Arrays.asList("claimed","done","refreshed").contains(state)&&"loomy".equals(provider)) {
            r.put("outcome","available").put("verified",false).put("reasonCode","no_daily_status").put("evidenceType","wallet_only").put("retryBlocked",true).put("message","每日额度已处理；余额可用不代表本次新增了签到奖励，可查询当前额度。");
        } else if(Arrays.asList("claimed","done","refreshed").contains(state)&&!confirmed(r)) {
            r.put("outcome","unknown").put("verified",false).put("reasonCode","confirmation_required").put("claimPossible",true).put("retryBlocked",true).put("message","已有处理记录，需核对官方状态；本次不会重复领取。");
        }
        if(!r.has("actionAt")&&r.has("automatic"))r.put("actionAt",r.optLong("at")).put("actionAutomatic",r.optBoolean("automatic"));
        if(!confirmed(r)){r.remove("credit");r.remove("unit");r.remove("streak");}
        return r;
    }
    private static String label(String outcome) {
        switch(outcome) {case "available":return "每日额度已查询，不代表领取成功";case "claimed":return "签到成功";case "done":return "今日已签到";case "refreshed":return "每日额度已刷新";case "inactive":return "签到活动未开放";case "ineligible":return "当前账号不具备领取资格";case "captcha":return "需要在供应商客户端完成验证码后领取";case "auth_required":return "登录失效或接口权限不足，请重新登录后查询";case "not_checked":return "今日尚未签到";case "failed":return "签到查询失败，请稍后重试";case "running":return "正在签到";case "unknown":return "领取结果尚未确认，今天不重复发送领取";case "account_changed":return "账号已变化，旧结果未写入新账号";default:return "等待签到";}
    }
    private static JSONObject safeResult(JSONObject raw,JSONObject a) {
        if(raw==null)raw=new JSONObject();if(!raw.optBoolean("ok",true)&&!raw.has("outcome"))raw=CheckinEvidence.resultFailure(raw);String outcome=raw.optString("outcome","unknown");
        if("already".equals(outcome))outcome="done";
        if(!Arrays.asList("claimed","done","refreshed","available","inactive","ineligible","captcha","failed","unknown","auth_required","not_checked").contains(outcome))outcome="unknown";
        if("failed".equals(outcome)&&!raw.optBoolean("safeToRetry",false)&&raw.optBoolean("claimPossible",true))outcome="unknown";
        boolean denied=Boolean.FALSE.equals(raw.opt("ok"));if(denied&&Arrays.asList("claimed","done","refreshed").contains(outcome))outcome="unknown";
        JSONObject r=new JSONObject().put("outcome",outcome).put("message",label(outcome));
        for(String field:new String[]{"verified","phase","claimPossible","reasonCode","exceptionType","httpStatus","businessCode","safeToRetry","evidenceType","pagesRead","billsRead","rowsRead","coverageComplete","refreshAcknowledged"})if(raw.has(field))r.put(field,raw.opt(field));
        if(denied)r.put("verified",false);
        String message=raw.optString("message","");
        if(!message.isEmpty()) {
            for(String secret:new String[]{"access","token","refresh","key","apiKey","cookie","deviceToken","jwt","idToken"}) { String value=a.optString(secret,"");if(!value.isEmpty())message=message.replace(value,"[REDACTED]"); }
            message=message.replaceAll("[\\x00-\\x1f\\x7f]"," ");if(message.length()>300)message=message.substring(0,300);r.put("message",message);
        }
        Object credit=raw.opt("credit");if(credit==null)credit=raw.opt("credits");
        if(credit instanceof Number) { double n=((Number)credit).doubleValue();if(Double.isFinite(n)&&n>=0)r.put("credit",credit).put("unit","积分"); }
        Object quota=raw.opt("dailyQuota");if(quota instanceof Number){double n=((Number)quota).doubleValue();if(Double.isFinite(n)&&n>=0)r.put("dailyQuota",quota);}
        Object streak=raw.opt("streak");if(streak instanceof Number && ((Number)streak).longValue()>0)r.put("streak",Math.min(100000,((Number)streak).longValue()));
        return r;
    }
    JSONObject runOne(String provider,String id,boolean automatic) throws Exception {
        if(!SUPPORTED.contains(provider)||id==null||id.isEmpty())throw new IllegalArgumentException("unsupported checkin account");String k=key(provider,id);
        synchronized(active) { if(!active.add(k))return new JSONObject().put("outcome","running").put("message","此账号正在签到"); }
        JSONObject snapshot=null;String fp="";long now=clock.now();
        try {
            synchronized(host.lock()) {
                live();JSONObject config=host.config(),a=account(config,provider,id);if(a==null)throw new IOException("account unavailable");snapshot=copy(a);fp=fingerprint(a);
                if(!eligible(provider,a))return new JSONObject().put("outcome","ineligible").put("message","当前凭据不支持签到");
                if(automatic&&!enabled(config,provider))return new JSONObject().put("outcome","disabled");
                JSONObject previous=records(config).optJSONObject(k);
                if(previous!=null&&fp.equals(previous.optString("fingerprint"))&&day(now).equals(previous.optString("day"))) {
                    JSONObject normalizedPrevious=normalized(provider,previous);String state=normalizedPrevious.optString("outcome","unknown");
                    if("running".equals(state))state="unknown";
                    if(previous.optBoolean("retryBlocked",false)||!java.util.Arrays.asList("failed","inactive","idle","not_checked").contains(state) || previous.optLong("nextAt",Long.MAX_VALUE)>now) {
                        JSONObject cached=normalizedPrevious.put("outcome",state).put("reused",true);
                        if(confirmed(cached))cached.put("message","今日已签到，无需重复签到").put("repeated",true);
                        return cached;
                    }
                }
                JSONObject next=copy(config);JSONObject intent=new JSONObject().put("fingerprint",fp).put("day",day(now)).put("outcome","running").put("message",label("running")).put("at",now).put("actionAt",now).put("actionAutomatic",automatic).put("nextAt",nextDay(now));
                putRecord(next,k,intent);if(!host.save(next))throw new IOException("cannot persist checkin intent");
            }
            JSONObject result;CheckinEvidence.begin();
            try { live(); if(Thread.currentThread().isInterrupted())throw new IOException("interrupted");result=normalized(provider,safeResult(transport.checkin(provider,copy(snapshot)),snapshot)); }
            catch(Exception failed) { result=CheckinEvidence.failure(failed); }
            long finished=clock.now();String state=result.optString("outcome");result.put("at",finished).put("day",day(now)).put("fingerprint",fp).put("automatic",automatic).put("actionAt",now).put("actionAutomatic",automatic);
            result.put("nextAt",Arrays.asList("failed","inactive").contains(state)?Math.min(nextDay(now),finished+30*60000L):nextDay(now));
            synchronized(host.lock()) {
                live();JSONObject current=account(host.config(),provider,id);
                if(current==null||!fp.equals(fingerprint(current)))return new JSONObject().put("outcome","account_changed").put("message",label("account_changed"));
                JSONObject next=copy(host.config());putRecord(next,k,result);if(!host.save(next))return new JSONObject().put("outcome","unknown").put("message","签到结果保存失败，今天不会重复领取");
            }
            localFailures.remove(k);
            return copy(result);
        } finally { CheckinEvidence.clear();synchronized(active) { active.remove(k); } }
    }
    JSONObject snapshot() throws Exception {
        synchronized(host.lock()) {
            live();JSONObject config=host.config();JSONArray ps=config.optJSONArray("providers"),rows=new JSONArray();long now=clock.now();int ready=0,complete=0,available=0;
            for(int i=0;ps!=null&&i<ps.length();i++) {
                JSONObject p=ps.optJSONObject(i);if(p==null||!p.optBoolean("enabled",true))continue;String provider=p.optString("id");JSONArray aa=p.optJSONArray("accounts");
                for(int j=0;aa!=null&&j<aa.length();j++) {
                    JSONObject a=aa.optJSONObject(j);if(a==null||!a.optBoolean("enabled",true)||a.optString("id").isEmpty())continue;
                    String id=a.optString("id");boolean supports=eligible(provider,a);JSONObject row=new JSONObject().put("provider",provider).put("providerName",p.optString("name",provider)).put("accountId",id).put("name",a.optString("name","账号 "+(j+1))).put("supported",supports).put("autoEnabled",supports&&enabled(config,provider));
                    JSONObject r=records(config).optJSONObject(key(provider,id));
                    JSONObject local=localFailures.get(key(provider,id));if(local!=null)r=local;
                    String outcome="idle";
                    if(r!=null&&fingerprint(a).equals(r.optString("fingerprint"))&&day(now).equals(r.optString("day"))) {
                        r=normalized(provider,r);outcome=r.optString("outcome","unknown");boolean isActive; synchronized(active){isActive=active.contains(key(provider,id));}if("running".equals(outcome)&&!isActive)outcome="unknown";
                        for(String field:new String[]{"message","credit","unit","streak","at","nextAt","automatic","phase","claimPossible","reasonCode","exceptionType","httpStatus","businessCode","retryBlocked","verified","dailyQuota","evidenceType","pagesRead","billsRead","rowsRead","coverageComplete","actionAt","actionAutomatic","confirmedAt"})if(r.has(field))row.put(field,r.opt(field));
                    }
                    if(!supports){outcome="unsupported";row.put("message",SUPPORTED.contains(provider)?"此账号凭据不支持签到":"供应商没有已接入的签到接口");}
                    row.put("outcome",outcome);if(!row.has("message"))row.put("message",label(outcome));if(!"zcode".equals(provider)){if(supports)ready++;if(confirmed(row))complete++;else if("available".equals(outcome)&&supports)available++;}rows.put(row);
                }
            }
            return new JSONObject().put("ok",true).put("autoEnabled",options(config).optBoolean("autoEnabled",true)).put("day",day(now)).put("timezone","UTC+8").put("running",batch.get()).put("supported",ready).put("completed",complete).put("available",available).put("pending",Math.max(0,ready-complete-available)).put("accounts",rows).put("operation",manualSnapshot(config));
        }
    }
    JSONObject reconcile(String provider,String id)throws Exception {
        String k=key(provider,id);boolean acquired;synchronized(active){acquired=active.add(k);}if(!acquired)return snapshot().put("message","此账号正在处理中");
        try{
            JSONObject a,previous;String fp;
            synchronized(host.lock()){live();a=account(host.config(),provider,id);if(!eligible(provider,a))throw new IllegalArgumentException("account unavailable");a=copy(a);fp=fingerprint(a);previous=records(host.config()).optJSONObject(k);if(previous!=null&&(!fp.equals(previous.optString("fingerprint"))||!day(clock.now()).equals(previous.optString("day"))))previous=null;if(previous!=null)previous=normalized(provider,previous);}
            CheckinEvidence.begin();CheckinEvidence.prepare();JSONObject answer;
            try{answer=normalized(provider,safeResult(transport.status(provider,a),a));}catch(Exception failure){answer=CheckinEvidence.failure(failure);}
            boolean verified=Boolean.TRUE.equals(answer.opt("verified"));String outcome=answer.optString("outcome","unknown");
            if(verified&&"not_checked".equals(outcome))answer.put("outcome","idle").put("message","官方状态显示今日尚未签到，可以点击签到");
            boolean uncertain=previous!=null&&java.util.Arrays.asList("unknown","running").contains(previous.optString("outcome"))&&!Boolean.FALSE.equals(previous.opt("claimPossible"));
            if(uncertain&&!verified)answer.put("retryBlocked",true).put("claimPossible",true);
            if(confirmed(previous)&&!verified)answer=copy(previous).put("message","今日已签到；当前状态查询暂不可用，保留已确认结果");
            if(previous!=null){if(previous.has("actionAt"))answer.put("actionAt",previous.opt("actionAt")).put("actionAutomatic",previous.optBoolean("actionAutomatic",previous.optBoolean("automatic",false)));else if(previous.has("automatic"))answer.put("actionAt",previous.optLong("at")).put("actionAutomatic",previous.optBoolean("automatic"));}
            if(verified)answer.put("confirmedAt",clock.now());
            answer.put("day",day(clock.now())).put("at",clock.now()).put("fingerprint",fp).put("nextAt",verified&&"not_checked".equals(outcome)?clock.now():nextDay(clock.now()));
            synchronized(host.lock()){live();JSONObject current=account(host.config(),provider,id);if(current==null||!fp.equals(fingerprint(current)))throw new IOException("account changed");JSONObject next=copy(host.config());putRecord(next,k,answer);if(!host.save(next))throw new IOException("status save failed");}
            localFailures.remove(k);return snapshot().put("result",answer);
        }finally{CheckinEvidence.clear();synchronized(active){active.remove(k);}}
    }
    JSONObject settings(JSONObject patch) throws Exception {
        if(patch==null||!(patch.opt("autoEnabled") instanceof Boolean)||patch.length()!=1)throw new IllegalArgumentException("autoEnabled boolean required");
        synchronized(host.lock()) { live();JSONObject next=copy(host.config());JSONObject o=options(next);next.put("checkin",o);o.put("autoEnabled",patch.getBoolean("autoEnabled"));if(!host.save(next))throw new IOException("settings save failed"); }
        return snapshot();
    }
    private JSONObject manualSnapshot(JSONObject config) {
        JSONObject op=manualOperation;if(op==null)return new JSONObject().put("visible",false);
        JSONObject view=new JSONObject().put("visible",true).put("id",op.optString("id")).put("day",op.optString("day")).put("startedAt",op.optLong("startedAt")).put("finishedAt",op.optLong("finishedAt")).put("running",op.optBoolean("running"));
        JSONArray rows=new JSONArray(),source=op.optJSONArray("results");int rewards=0,reused=0,confirmedOnly=0,quota=0,pending=0,failed=0;
        for(int i=0;source!=null&&i<source.length();i++){
            JSONObject r=source.optJSONObject(i);if(r==null)continue;JSONObject current=account(config,r.optString("provider"),r.optString("accountId"));
            if(current==null||!fingerprint(current).equals(r.optString("fingerprint")))continue;
            JSONObject safe=copy(r);safe.remove("fingerprint");rows.put(safe);String state=r.optString("outcome");
            if(confirmed(r)){if(r.optBoolean("reused"))reused++;else if("claimed".equals(state))rewards++;else confirmedOnly++;}
            else if("available".equals(state))quota++;else if(Arrays.asList("failed","auth_required").contains(state))failed++;else pending++;
        }
        return view.put("results",rows).put("newRewards",rewards).put("reusedConfirmed",reused).put("confirmedWithoutNewReward",confirmedOnly).put("available",quota).put("pending",pending).put("failed",failed).put("error",op.optBoolean("error",false));
    }
    private void publishManual(String id,long started,List<JSONObject> results,boolean running,boolean error) {
        JSONObject next=new JSONObject().put("id",id).put("day",day(started)).put("startedAt",started).put("finishedAt",running?0:clock.now()).put("running",running).put("error",error).put("results",new JSONArray());
        JSONArray rows=next.getJSONArray("results");for(JSONObject r:results)rows.put(copy(r));manualOperation=next;
    }
    private JSONObject operationResult(String provider,String id,String fp,JSONObject result) {
        JSONObject r=new JSONObject().put("provider",provider).put("accountId",id).put("fingerprint",fp);
        for(String field:new String[]{"outcome","verified","reused","actionAt","actionAutomatic","at"})if(result.has(field))r.put(field,result.opt(field));
        String note;
        if(confirmed(result))note=result.optBoolean("reused")?"本次无需重复领取，沿用此前已确认结果。":"claimed".equals(result.optString("outcome"))?("raccoon".equals(provider)?"本次核验发现新增奖励账单，以官方记录为准。":"本次官方已确认新增领取。"):"本次已确认今日状态；未确认新增奖励数额。";
        else if("available".equals(result.optString("outcome")))note="本次仅确认每日额度信息，不计作新签到奖励。";
        else note=result.optBoolean("reused")?"本次未重复领取，仍需核对官方状态。":"本次尚未取得成功确认，请查看账号反馈。";
        return r.put("message",note);
    }
    private List<String[]> candidates() throws Exception {
        JSONArray rows=snapshot().getJSONArray("accounts");List<String[]> result=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject a=rows.getJSONObject(i);if(a.optBoolean("supported")&&!"zcode".equals(a.optString("provider")))result.add(new String[]{a.getString("provider"),a.getString("accountId")});}return result;
    }
    JSONObject enqueue(String provider,String id,boolean automatic) throws Exception {
        live();if((provider==null||provider.isEmpty())!=(id==null||id.isEmpty()))throw new IllegalArgumentException("provider and accountId required together");
        if(provider!=null&&!provider.isEmpty()) { synchronized(host.lock()){if(!eligible(provider,account(host.config(),provider,id)))throw new IllegalArgumentException("account does not support checkin");} }
        if(!batch.compareAndSet(false,true))return snapshot().put("accepted",false).put("message","已有签到任务正在运行");
        final String operationId=UUID.randomUUID().toString();final long operationStarted=clock.now();
        if(!automatic)publishManual(operationId,operationStarted,Collections.emptyList(),true,false);
        try { worker.execute(() -> {
            List<JSONObject> receipts=new ArrayList<>();boolean error=false;
            try {
                List<String[]> ids=provider==null||provider.isEmpty()?candidates():Collections.singletonList(new String[]{provider,id});
                for(String[] target:ids){
                    if(closed||Thread.currentThread().isInterrupted()){error=true;break;}
                    String fp="";synchronized(host.lock()){JSONObject current=account(host.config(),target[0],target[1]);if(current!=null)fp=fingerprint(current);}
                    JSONObject result;
                    try{result=runOne(target[0],target[1],automatic);}
                    catch(Exception failed){
                        result=new JSONObject().put("outcome","unknown").put("claimPossible",true).put("message","本次处理未完整返回，不能确认领取结果，请查询状态。");
                        synchronized(host.lock()){JSONObject current=account(host.config(),target[0],target[1]);if(current!=null)localFailures.put(key(target[0],target[1]),copy(result).put("fingerprint",fingerprint(current)).put("day",day(clock.now())).put("at",clock.now()));}
                    }
                    receipts.add(operationResult(target[0],target[1],fp,result));
                    if(!automatic)publishManual(operationId,operationStarted,receipts,true,false);
                }
            }catch(Exception failed){error=true;}
            finally{if(!automatic)publishManual(operationId,operationStarted,receipts,false,error);batch.set(false);}
        }); }catch(RejectedExecutionException retired){batch.set(false);if(!automatic)publishManual(operationId,operationStarted,Collections.emptyList(),false,true);throw new IOException("checkin stopped");}
        return snapshot().put("accepted",true);
    }
    synchronized void start() {
        if(started||closed)return;started=true;
        worker.scheduleWithFixedDelay(() -> {try { synchronized(host.lock()){if(!options(host.config()).optBoolean("autoEnabled",true))return;}enqueue("","",true);}catch(Exception ignored){}},2,300,TimeUnit.SECONDS);
    }
    @Override public void close(){closed=true;worker.shutdownNow();}
}
