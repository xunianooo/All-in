package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read-only account meter. Fixed origins; no chat, check-in, key guessing or redirects. */
final class WorkBuddyAllowance {
    static final String SUMMARY="/billing/meter/get-user-resource-summary";
    static final String ENTERPRISE="/billing/meter/get-enterprise-user-usage";
    static final class Failure extends IOException {
        final String kind; final boolean invalidToken;
        Failure(String kind, boolean invalidToken) { super("WorkBuddy allowance "+kind); this.kind=kind; this.invalidToken=invalidToken; }
    }
    private WorkBuddyAllowance() {}
    static boolean eligible(JSONObject a) {
        String key=a.optString("access",a.optString("token",""));
        return !"api".equals(a.optString("type")) && !key.startsWith("ck_") && !key.startsWith("pt_");
    }
    static JSONObject fetch(String provider, JSONObject a, WorkBuddyLogin.ConnectionFactory factory) throws Exception {
        if(!eligible(a)) throw new Failure("key_not_supported",false);
        String path=WorkBuddyLogin.enterpriseValue(a).isEmpty()?SUMMARY:ENTERPRISE;
        String url=WorkBuddyAuth.origin(provider)+path;
        String key=a.optString("access",a.optString("token",""));
        if(key.isEmpty())throw new Failure("sign_in_required",false);
        // Catalog header policy already validates account values and enterprise dual headers.
        Map<String,String> headers=WorkBuddyPolicy.catalogHeaders(provider,WorkBuddyAuth.origin(provider)+"/v3/config",key,a);
        if(headers.isEmpty())throw new Failure("sign_in_required",false);
        HttpURLConnection c=null;
        try {
            c=factory.open(url); c.setInstanceFollowRedirects(false); c.setRequestMethod("POST");
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            c.setRequestProperty("Content-Type","application/json"); c.setDoOutput(true); c.setFixedLengthStreamingMode(2);
            try(OutputStream out=c.getOutputStream()){out.write(new byte[]{'{','}'});}
            int status=c.getResponseCode();
            if(status<200 || status>=300) {
                String challenge=c.getHeaderField("WWW-Authenticate");
                throw new Failure(status==401?"sign_in_required":"unavailable",status==401 && challenge!=null && challenge.toLowerCase(java.util.Locale.ROOT).contains("invalid_token"));
            }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); long deadline=System.nanoTime()+5000000000L;
            try(InputStream in=c.getInputStream()) {
                byte[] buffer=new byte[4096]; int n;
                while((n=in.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Interrupted");
                    if(bytes.size()+n>1048576 || System.nanoTime()>deadline)throw new Failure("unavailable",false);
                    bytes.write(buffer,0,n);
                }
            }
            JSONObject env=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            if(env.optInt("code",-1)!=0)throw new Failure("unavailable",false);
            JSONObject data=env.optJSONObject("data");
            return ENTERPRISE.equals(path)?enterprise(data):summary(data);
        } finally { if(c!=null)c.disconnect(); }
    }
    private static Double number(Object v) {
        if(!(v instanceof Number) && !(v instanceof String))return null;
        try { double n=Double.parseDouble(String.valueOf(v)); return Double.isFinite(n) && n>=0?n:null; }
        catch(Exception ignored){return null;}
    }
    private static JSONObject base() {
        return new JSONObject().put("kind","allowance").put("scope","account").put("unit","credits")
            .put("observedAt",System.currentTimeMillis()).put("windows",new JSONArray());
    }
    private static JSONObject window(String name,double total,double left) {
        return new JSONObject().put("name",name).put("remaining",left).put("total",total).put("unit","credits")
            .put("remainingPercent",100*left/total);
    }
    static JSONObject summary(JSONObject sum) {
        if(sum==null)return null;
        JSONArray groups=sum.optJSONArray("CreditGroups"), packages=sum.optJSONArray("Packages");
        JSONArray rows=groups!=null && groups.length()>0?groups:packages;
        double total=0,left=0,balance=0; boolean known=false; JSONArray windows=new JSONArray();
        for(int i=0;rows!=null && i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)continue;
            Double t=number(row.opt("CycleTotalCapacity")), u=number(row.opt("CycleUsedCapacity")), r=number(row.opt("CycleRemainCapacity"));
            if(t==null || t<=0)continue;
            if(r==null && u!=null && u<=t)r=t-u;
            if(r==null || r>t || (u!=null && u>t))return null; // unknown is never zero
            if(total+t==Double.POSITIVE_INFINITY || left+r==Double.POSITIVE_INFINITY)return null;
            total+=t; left+=r; known=true;
            if(groups==rows)windows.put(window("plan_base".equals(row.optString("Kind"))?"Plan credits":"Extra credits",t,r));
        }
        // Prepaid balances have no denominator; do not double-count groups + packages.
        for(int i=0;packages!=null && i<packages.length();i++) {
            JSONObject row=packages.optJSONObject(i); if(row==null)continue;
            Object raw=row.opt("CycleTotalCapacity");
            if(raw!=null && raw!=JSONObject.NULL && !"".equals(raw))continue;
            Double r=number(row.opt("CycleRemainCapacity"));
            if(r!=null){balance+=r;known=true;}
        }
        if(!known || !Double.isFinite(left+balance))return null;
        JSONObject out=base().put("remaining",left+balance);
        if(windows.length()>0)out.put("windows",windows);
        else if(total>0)out.optJSONArray("windows").put(window("Credits",total,left));
        if(balance>0 || total==0)out.optJSONArray("windows").put(new JSONObject().put("name","Prepaid credits").put("remaining",balance).put("unit","credits"));
        return out;
    }
    static JSONObject enterprise(JSONObject data) {
        if(data==null)return null;
        Double total=number(data.opt("limitNum")), used=number(data.opt("credit"));
        if(total==null || total<=0 || used==null || used>total)return null;
        double left=total-used; JSONObject out=base().put("remaining",left);
        out.optJSONArray("windows").put(window("Team credits",total,left)); return out;
    }
}
