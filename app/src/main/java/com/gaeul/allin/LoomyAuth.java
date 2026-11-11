package com.gaeul.allin;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Loomy account and points protocol. Callers own credential persistence and UI. */
public final class LoomyAuth {
    private static final String ACCOUNT = "https://account.xfinfr.com";
    private static final String GATEWAY = "https://loomyad.xunfei.cn";
    // Public desktop-client fingerprint, not a per-user credential.
    private static final String AK = "2thryby66wxi53sk", SK = "zsak6eadrbawz683wf5r3m2snrwj868r";
    private static final String RECORDS = "/api/v2/points/records?pageNo=1&pageSize=1&direction=debit&granularity=chat";
    private final String account, gateway, ak, sk;
    public LoomyAuth() { this.account=ACCOUNT; this.gateway=GATEWAY; this.ak=AK; this.sk=SK; }
    // Injectable endpoints are for local fake servers; production callers use the default constructor.
    LoomyAuth(String account, String gateway, String ak, String sk) {
        if (!testOrigin(account) || !testOrigin(gateway)) throw new IllegalArgumentException("Test endpoints must use loopback HTTP");
        this.account=account; this.gateway=gateway; this.ak=ak; this.sk=sk;
    }
    private static boolean testOrigin(String value) {
        try {
            URI u = new URI(value);
            String host = u.getHost();
            return "http".equals(u.getScheme()) && ("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host))
                    && u.getPort() > 0 && (u.getRawPath() == null || u.getRawPath().isEmpty())
                    && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null;
        } catch (Exception e) { return false; }
    }
    public static final class Failure extends Exception {
        public final boolean expired;
        Failure(String message, boolean expired) { super(message); this.expired=expired; }
    }
    public static final class Wallets {
        public final Double permanent, daily, dailyLimit, dailyRemaining;
        Wallets(Double p, Double d, Double l, Double r) { permanent=p; daily=d; dailyLimit=l; dailyRemaining=r; }
        public Double dailyUsed() { return dailyLimit == null || dailyRemaining == null ? null : Math.max(0, dailyLimit-dailyRemaining); }
    }
    public static final class Refresh {
        public final String outcome; public final Double before, after;
        Refresh(String outcome, Double before, Double after) { this.outcome=outcome; this.before=before; this.after=after; }
    }
    public static String normalizePhone(String raw) throws Failure {
        String p=raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (p.startsWith("+86")) p=p.substring(3); else if (p.length()==13 && p.startsWith("86")) p=p.substring(2);
        if (!p.matches("1[3-9][0-9]{9}")) throw new Failure("手机号格式无效", false);
        return p;
    }
    private static JSONObject base() throws Exception {
        byte[] hash=MessageDigest.getInstance("MD5").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder trace=new StringBuilder(); for(byte b:hash) trace.append(String.format(Locale.US,"%02x",b & 255));
        return new JSONObject().put("appid","GM3LOOMY").put("modelid","Web").put("version","1.0.0").put("devid","web").put("ua","Loomy|Desktop|Electron|macOS").put("traceid",trace.toString());
    }
    private static String b64(byte[] b) {
        final char[] alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out=new StringBuilder(((b.length+2)/3)*4);
        for(int i=0;i<b.length;i+=3) {
            int n=(b[i]&255)<<16; if(i+1<b.length) n|=(b[i+1]&255)<<8; if(i+2<b.length) n|=b[i+2]&255;
            out.append(alphabet[(n>>>18)&63]).append(alphabet[(n>>>12)&63]);
            out.append(i+1<b.length?alphabet[(n>>>6)&63]:'=').append(i+2<b.length?alphabet[n&63]:'=');
        }
        return out.toString();
    }
    private JSONObject signed(String path, JSONObject param) throws Exception {
        String body=new JSONObject().put("base",base()).put("param",param).toString();
        String md5=b64(MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8)));
        SimpleDateFormat df=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US); df.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date=df.format(new Date()), nonce=UUID.randomUUID().toString();
        String canonical="POST\n"+path+"\n\n"+md5+"\napplication/json\n"+date+"\n"+nonce+"\n\n";
        Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(sk.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
        Map<String,String> headers=new HashMap<>(); headers.put("Authorization","account "+ak+":"+b64(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))); headers.put("Date",date); headers.put("Nonce",nonce); headers.put("Content-MD5",md5);
        return request(account+path,"POST",headers,body,false);
    }
    public String sendCode(String phone) throws Exception {
        JSONObject p=new JSONObject().put("ccode","86").put("phone",normalizePhone(phone)).put("expire",300);
        JSONObject result=signed("/login/phone/sendMsgCode",p); check(result);
        String id=result.optJSONObject("data") == null ? "" : result.optJSONObject("data").optString("msgid","").trim();
        if(id.isEmpty()) throw new Failure("上游未返回验证码标识",false); return id;
    }
    public String checkCode(String phone, String code, String msgid) throws Exception {
        if(code==null || !code.matches("[0-9]{6}") || msgid==null || msgid.trim().isEmpty()) throw new Failure("验证码或标识无效",false);
        JSONObject p=new JSONObject().put("ccode","86").put("phone",normalizePhone(phone)).put("mcode",code).put("msgid",msgid).put("expire",1209600);
        JSONObject result=signed("/login/phone/checkCode",p); check(result);
        String session=result.optJSONObject("data") == null ? "" : result.optJSONObject("data").optString("session","").trim();
        if(session.isEmpty()) throw new Failure("上游未返回 session",false); return session;
    }
    private JSONObject tokenRequest(String method,String path,String session,String body) throws Exception {
        if(session==null || session.trim().isEmpty() || session.indexOf('\n')>=0 || session.indexOf('\r')>=0) throw new Failure("session 无效",false);
        session=session.trim();
        for (int i=0;i<session.length();i++) if (session.charAt(i)<0x20 || session.charAt(i)==0x7f) throw new Failure("session 无效",false);
        Map<String,String> h=new HashMap<>(); h.put("token",session); h.put("Authorization","Bearer "+session);
        return request(gateway+path,method,h,body,true);
    }
    private JSONObject request(String url,String method,Map<String,String> headers,String body,boolean sessionRequest) throws Exception {
        HttpURLConnection c=NetworkPolicy.openHttp(url);
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(20000); c.setRequestMethod(method);
            for(Map.Entry<String,String> h:headers.entrySet()) c.setRequestProperty(h.getKey(),h.getValue());
            if(body!=null) { c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); byte[] bytes=body.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length); try(OutputStream out=c.getOutputStream()){out.write(bytes);} }
            int status=c.getResponseCode(); if(sessionRequest && (status==401 || status==403)) throw new Failure("登录态已失效",true);
            if(status<200 || status>=300) throw new Failure("上游 HTTP "+status,false);
            ByteArrayOutputStream out=new ByteArrayOutputStream(); try(InputStream in=c.getInputStream()) { byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1){ if(out.size()+n>1048576) throw new Failure("上游响应过大",false); out.write(buf,0,n); } }
            try { return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)); } catch(Exception e) { throw new Failure("上游响应格式无效",false); }
        } finally { c.disconnect(); }
    }
    private static void check(JSONObject o) throws Failure {
        String code=o.optString("code","").trim(); if("000000".equals(code)) return;
        if("020002".equals(code) || "100002".equals(code)) throw new Failure("登录态已失效",true);
        throw new Failure("上游业务请求失败",false);
    }
    private static JSONObject data(JSONObject o) { JSONObject d=o.optJSONObject("data"); if(d==null) return new JSONObject(); JSONObject nested=d.optJSONObject("data"); return nested==null?d:nested; }
    private static Double num(JSONObject o,String key) { Object v=o.opt(key); if(!(v instanceof Number) && !(v instanceof String)) return null; try { double x=Double.parseDouble(v.toString().trim()); return Double.isNaN(x)||Double.isInfinite(x)?null:x; } catch(Exception e){return null;} }
    /** Fetch only an authenticated, nonempty official catalog; malformed data is a failure. */
    public List<String> models(String session, JSONObject metadata) throws Exception {
        JSONObject result=tokenRequest("GET","/api/v1/models",session,null);
        if(result.has("code")) check(result);
        JSONArray entries=result.optJSONArray("data");
        if(entries==null || entries.length()==0) throw new Failure("模型目录为空或格式无效",false);
        List<String> ids=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.optJSONObject(i);
            if(entry==null || !(entry.opt("id") instanceof String)) throw new Failure("模型目录格式无效",false);
            String id=entry.optString("id","").trim();
            if(id.isEmpty() || !seen.add(id)) throw new Failure("模型目录标识无效",false);
            ids.add(id);
            JSONObject raw=new JSONObject(entry.toString());
            for(String key:new String[]{"name","display_name"}) {
                Object value=raw.opt(key);
                if(value instanceof String) {
                    String original=(String)value, normalized=original.replace('（','(').replace('）',')');
                    if(!original.equals(normalized)) { raw.put("original_"+key,original); raw.put(key,normalized); }
                }
            }
            metadata.put(id,raw);
        }
        return ids;
    }
    public Wallets usage(String session) throws Exception {
        JSONObject result=tokenRequest("GET",RECORDS,session,null); check(result); JSONObject d=data(result);
        Double p=num(d,"balance"), daily=num(d,"dailyBalance"), limit=num(d,"dailyLimitPoints"), remaining=num(d,"dailyRemainingPoints");
        if(p==null && daily==null && (limit==null || remaining==null)) throw new Failure("积分响应缺少余额",false);
        return new Wallets(p,daily,limit,remaining);
    }
    /** Validation performs a read-only points request; a successful result proves the session works for points. */
    public Wallets validateSession(String session) throws Exception { return usage(session); }
    public Refresh refreshDaily(String session) throws Exception {
        Wallets before=usage(session); // No mutation unless the pre-read succeeds.
        JSONObject result=tokenRequest("POST","/api/v1/points/first-login",session,"{}"); check(result);
        Double after=num(data(result),"dailyBalance");
        if(after==null) { try { after=usage(session).daily; } catch(Failure e) { if(e.expired) throw e; } catch(Exception ignored) {} }
        String outcome=before.daily==null || after==null ? "unknown" : after>before.daily ? "claimed" : "done";
        return new Refresh(outcome,before.daily,after);
    }
}
