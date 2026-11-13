package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.concurrent.*;

/** Consumer Trae CN only. Results contain credentials; store them atomically and never log them. */
public final class TraeAuth {
    public static final String AUTH_HOST="https://api.trae.cn", MODEL_HOST="https://trae-api-cn.mchost.guru";
    private static final String PATH="/trae/api/v3/oauth/ExchangeToken", BRAND="ASUS TUF Gaming A15 FA507RM_FA507RM";
    private static final int MAX=2*1024*1024;
    private static int flows;
    private static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,new ThreadFactory(){public Thread newThread(Runnable r){Thread t=new Thread(r,"trae-http-deadline");t.setDaemon(true);return t;}});
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private static volatile String fixtureBase;
    // Test-only hook: no arbitrary host or TLS downgrade beyond loopback fixtures.
    static void setFixtureBase(String base) throws Exception {
        if(base!=null) { URL u=new URL(base); if(!"http".equals(u.getProtocol()) || !"127.0.0.1".equals(u.getHost()) || u.getPort()<1 || !u.getPath().isEmpty() || u.getUserInfo()!=null || u.getQuery()!=null || u.getRef()!=null) throw new IllegalArgumentException("loopback fixture only"); }
        fixtureBase=base;
    }
    private static final OutboundLimiter LIMITER=new OutboundLimiter(2,32);
    static { LIMITER.configure(Collections.singletonMap("trae-cn",new OutboundLimiter.Policy(0,0,8,32,20000))); }
    static long expiry(Object value) {
        if(value==null || value==JSONObject.NULL)return 0;
        String x=String.valueOf(value).trim(); if(x.matches("[0-9]+"))try {long n=Long.parseLong(x); if(n<=0)return 0; return n<100000000000L?Math.multiplyExact(n,1000):n;}catch(Exception ignored){return 0;}
        // SimpleDateFormat supports Android 24; normalize ISO offsets and fractional seconds.
        if(!x.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})"))return 0;
        x=x.replaceFirst("(\\.[0-9]{3})[0-9]+", "$1"); if(x.endsWith("Z"))x=x.substring(0,x.length()-1)+"+0000"; else x=x.substring(0,x.length()-3)+x.substring(x.length()-2);
        for(String pattern:new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSSZ","yyyy-MM-dd'T'HH:mm:ssZ"}) { SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US); f.setLenient(false); ParsePosition pos=new ParsePosition(0); Date d=f.parse(x,pos); if(d!=null && pos.getIndex()==x.length())return d.getTime(); } return 0;
    }
    static long tokenExpiry(JSONObject data,String token) { long n=expiry(data.opt("TokenExpireAt")); if(n==0)n=expiry(data.opt("tokenExpireAt")); if(n!=0)return n; try{String[] parts=token.split("\\.");String b=parts[1].replace('-','+').replace('_','/');while(b.length()%4!=0)b+="=";return expiry(new JSONObject(new String(TraeDevice.unb64(b),StandardCharsets.UTF_8)).opt("exp"));}catch(Exception ignored){return 0;} }
    private static String businessCode(JSONObject o) { if(o==null)return ""; JSONObject m=o.optJSONObject("ResponseMetadata"); if(m==null)m=o.optJSONObject("responseMetadata"); JSONObject e=m==null?null:m.optJSONObject("Error"); if(e==null&&m!=null)e=m.optJSONObject("error"); if(e!=null){String c=text(e,"Code","code");if(!c.isEmpty())return c;} String c=text(o,"Code","code"); if(!c.isEmpty())return c; JSONObject r=o.optJSONObject("Result");if(r==null)r=o.optJSONObject("result");return r==null?"":text(r,"Code","code"); }

    private TraeAuth() {}
    private static String enc(String s) throws Exception { return URLEncoder.encode(s,"UTF-8"); }
    private static String text(JSONObject o,String a,String b) { String s=o.optString(a,""); return s.isEmpty()?o.optString(b,""):s; }
    private static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    public static Map<String,String> headers(JSONObject a) {
        Map<String,String> h=new LinkedHashMap<>(); String token=a.optString("token","");
        h.put("Content-Type","application/json"); h.put("Authorization","Cloud-IDE-JWT "+token); h.put("X-Cloudide-Token",token); h.put("x-ide-token",token);
        h.put("x-uid",a.optString("uid","")); h.put("x-app-id","6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"); h.put("x-device-id",a.optString("deviceId","")); h.put("x-machine-id",a.optString("machineId","")); h.put("x-request-id",UUID.randomUUID().toString());
        h.put("x-app-version","0.1.69"); h.put("x-app-version-code","20260917"); h.put("x-ide-version","0.1.69"); h.put("x-ide-version-code","20260917"); h.put("x-ide-version-type","stable"); h.put("x-device-cpu","AMD"); h.put("x-device-brand",BRAND); h.put("x-device-type","windows"); h.put("x-os-version","Windows 10"); h.put("x-system-type","Windows"); return h;
    }
    public static final class Failure extends IOException {
        public final String code, diagnostic;
        final boolean bindingRejected;
        Failure(String code) { this(code, ""); }
        Failure(String code,String diagnostic) { this(code,diagnostic,false); }
        Failure(String code,String diagnostic,boolean bindingRejected) { super(code); this.code=code; this.diagnostic=diagnostic; this.bindingRejected=bindingRejected; }
    }
    private static String networkDiagnostic(Exception e) {
        if(e instanceof javax.net.ssl.SSLException)return "TLS";
        if(e instanceof UnknownHostException)return "DNS";
        if(e instanceof SocketTimeoutException)return "TIMEOUT";
        if(e instanceof ConnectException || e instanceof NoRouteToHostException)return "CONNECT";
        return "NETWORK_IO";
    }
    // No exception messages, URLs, headers, credential values or response bodies in diagnostics.
    static String bindingDiagnostic(Exception e) {
        String detail="LOCAL_ERROR";
        if(e instanceof Failure) {
            Failure f=(Failure)e;
            if(!f.diagnostic.isEmpty())detail=f.diagnostic;
            else if(Arrays.asList("requires_relogin","invalid_device","device_proof_failed","outcome_unknown","network_error","closed").contains(f.code))detail=f.code.toUpperCase(Locale.ROOT);
            else if(f.code.startsWith("outbound_"))detail="OUTBOUND_LIMIT";
        }
        if(!detail.matches("[A-Z0-9_]{1,160}"))detail="LOCAL_ERROR";
        return (e instanceof Failure && ((Failure)e).code.startsWith("browser_")?"TRAE_LOGIN_":"TRAE_BIND_")+detail;
    }
    static String bindingFailureMessage(Exception e) {
        String code=e instanceof Failure?((Failure)e).code:"";
        String message;
        if(code.startsWith("browser_"))message="网页登录凭据未通过官方核验，账号尚未入池，请重新发起授权";
        else if("requires_relogin".equals(code))message="Trae 拒绝本次设备绑定，账号尚未入池，请重新发起授权";
        else if("invalid_device".equals(code)||"device_proof_failed".equals(code))message="本机设备签名未完成，账号尚未入池，请反馈诊断码";
        else if("network_error".equals(code)||code.startsWith("outbound_"))message="设备绑定请求未能发出，请检查 All In 的网络设置后重新授权";
        else message="设备绑定未完成，结果未知不会自动重试；账号尚未入池，请重新发起授权";
        return message+" ["+bindingDiagnostic(e)+"]";
    }
    private static String responseDiagnostic(Response response) {
        String code=businessCode(response.json);
        return "HTTP_"+response.status+(response.json==null?"_NON_JSON":code.isEmpty()?"_NO_CODE":code.matches("[0-9]{1,6}")?"_CODE_"+code:"_CODE_OTHER");
    }
    private static final class Response { int status; JSONObject json; }
    private static Response http(String url,String method,JSONObject body,Map<String,String> headers,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(network==null)network=NetworkPolicy.capture();if(ctx!=null)ctx.check();
        String fixture=fixtureBase;if(fixture!=null){URL original=new URL(url);url=fixture+original.getFile();}
        OutboundLimiter.Result permit=LIMITER.await(Collections.singleton("trae-cn"),ctx==null?System.nanoTime()+20000000000L:ctx.deadlineNanos,ctx);
        if(permit.ticket==null)throw new Failure("outbound_"+permit.status.name().toLowerCase(Locale.ROOT));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);if(ctx!=null)deadline=Math.min(deadline,ctx.deadlineNanos);
        final TraeHttpTransport wire=new TraeHttpTransport(network,ctx,deadline);
        Runnable close=wire::close;if(ctx!=null)ctx.onClose(close);
        ScheduledFuture<?> timeout=DEADLINES.schedule(close,Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        try {
            byte[] request=body==null?null:body.toString().getBytes(StandardCharsets.UTF_8);
            if(request!=null && request.length>MAX)throw new Failure("request_too_large");
            byte[] data=wire.exchange(url,method,request,headers,MAX,()->permit.ticket.markSent());
            Response r=new Response();r.status=wire.status;
            try {com.gaeul.allin.json.JSONTokener parser=new com.gaeul.allin.json.JSONTokener(new String(data,StandardCharsets.UTF_8));Object value=parser.nextValue();r.json=value instanceof JSONObject && parser.nextClean()==0?(JSONObject)value:null;}
            catch(Exception malformed){r.json=null;}
            return r;
        } catch(Failure failure){throw failure;}
        catch(Exception failure) {
            if("Qoder refresh: response body too large".equals(failure.getMessage()))throw new Failure("response_too_large");
            throw new Failure(wire.sent?"outcome_unknown":"network_error",networkDiagnostic(failure));
        } finally {
            timeout.cancel(false);wire.close();if(ctx!=null)ctx.removeCleanup(close);permit.ticket.close();
        }
    }
    private static JSONObject deviceInfo(JSONObject a,String platform,String version) throws Exception {
        JSONObject d=new JSONObject(); d.put("DeviceID",a.optString("deviceId")); d.put("MachineID",a.optString("machineId")); d.put("PlatformCode",platform); d.put("DeviceType","PC"); d.put("DeviceName",""); d.put("DeviceModel",""); d.put("ClientVersion",version); d.put("DevicePublicKey",a.optString("devicePublicKey")); d.put("DeviceBrand",BRAND); d.put("DeviceCPU","AMD"); d.put("OSInfo","Windows"); d.put("OSVersion","10.0.22631"); return d;
    }
    private static JSONObject bindDevice(JSONObject account,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        try { TraeDevice.validate(account); } catch(Exception invalid) { throw new Failure("invalid_device"); }
        String authHost=consumerAuthHost(account.optString("authHost",""));
        String refresh=account.optString("refresh",""); if(refresh.isEmpty())throw new Failure("requires_relogin");
        String rejected="";
        String[] clients={"en1oxy7wnw8j9n","ono9krqynydwx5"}, platforms={"SOLO_PC","IDE_PC"}, versions={"0.1.64","3.3.99"};
        for(int i=0;i<2;i++) { JSONObject b=new JSONObject(); b.put("ClientID",clients[i]); b.put("ClientSecret",""); b.put("RefreshToken",refresh); b.put("DeviceInfo",deviceInfo(account,platforms[i],versions[i])); try { b.put("DeviceProof",TraeDevice.proofOf(PATH,clients[i],refresh,account.optString("devicePrivateKey"))); } catch(Exception invalid) { throw new Failure("device_proof_failed"); } b.put("IDEVersion",versions[i]);
            Map<String,String> h=new LinkedHashMap<>(); h.put("Content-Type","application/json"); if(!account.optString("token","").isEmpty())h.put("Authorization","Cloud-IDE-JWT "+account.optString("token"));
            Response r; try { r=http(authHost+PATH,"POST",b,h,network,ctx); } catch(Failure failure) { if(failure.code.equals("response_too_large"))throw new Failure("outcome_unknown","RESPONSE_TOO_LARGE"); throw failure; } JSONObject result=r.json==null?null:r.json.optJSONObject("Result"); if(result==null && r.json!=null)result=r.json.optJSONObject("result"); String token=result==null?"":text(result,"Token","token");
            if(r.status>=200 && r.status<300 && !token.isEmpty() && (businessCode(r.json).isEmpty() || "0".equals(businessCode(r.json)))) { JSONObject out=copy(account); out.put("token",token); String next=text(result,"RefreshToken","refreshToken"); out.put("refresh",next.isEmpty()?refresh:next); long expiry=tokenExpiry(result,token); if(expiry>0)out.put("expires",expiry); out.put("bound",true); out.put("authMode","device"); out.remove("browserSessionVerified"); return out; }
            // Only a concrete 4xx response or an explicit nonzero business code permits trying the other client.
            if(!token.isEmpty())throw new Failure("outcome_unknown","CONFLICTING_TOKEN_RESPONSE");
            String code=businessCode(r.json); boolean reject=(r.status==400||r.status==401||r.status==403||r.status>=200&&r.status<300) && !code.isEmpty() && !code.equals("0");
            String diagnostic=(i==0?"SOLO_":"IDE_")+responseDiagnostic(r);
            if(!reject)throw new Failure("outcome_unknown",diagnostic);
            rejected=rejected.isEmpty()?diagnostic:rejected+"_THEN_"+diagnostic;
        } throw new Failure("requires_relogin",rejected,true);
    }
    private static boolean safeCredential(JSONObject a,String key) {
        Object value=a.opt(key);
        if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>32768)return false;
        for(char c:((String)value).toCharArray())if(c<=32 || c==127)return false;
        return true;
    }
    private static String sessionClientId(String value) throws Failure {
        if(value==null || value.isEmpty())return "ono9krqynydwx5";
        if(!value.equals("ono9krqynydwx5") && !value.equals("en1oxy7wnw8j9n"))throw new Failure("requires_relogin","UNSUPPORTED_CLIENT");
        return value;
    }
    static boolean verifiedBrowserAccount(JSONObject account) {
        return account!=null && Boolean.TRUE.equals(account.opt("browserSessionVerified"))
                && "browser".equals(account.optString("authMode")) && !account.optBoolean("bound",false)
                && safeCredential(account,"token") && safeCredential(account,"refresh") && safeCredential(account,"uid")
                && ("ono9krqynydwx5".equals(account.optString("clientId")) || "en1oxy7wnw8j9n".equals(account.optString("clientId")));
    }
    // A read-only authenticated account endpoint confirms the original browser access token.
    // This never exchanges/spends the browser's refresh token merely to finish logging in.
    private static JSONObject verifyBrowserSession(JSONObject account,Failure rejection,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(!rejection.bindingRejected)throw rejection;
        if(!safeCredential(account,"token") || !safeCredential(account,"refresh") || !safeCredential(account,"uid"))
            throw new Failure("browser_auth_rejected","INCOMPLETE_BROWSER_IDENTITY");
        long expiry=account.optLong("expires",0);
        if(expiry>0 && expiry<=System.currentTimeMillis())throw new Failure("browser_auth_rejected","EXPIRED_BROWSER_TOKEN");
        if(ctx!=null)ctx.check();
        String host=consumerAuthHost(account.optString("authHost",""));
        Response r=http(host+"/trae/api/v2/pay/ide_user_ent_usage","POST",new JSONObject().put("require_usage",true).put("req_source",0),headers(account),network,ctx);
        String code=businessCode(r.json);
        if(r.status<200 || r.status>=300 || r.json==null || (!code.isEmpty()&&!code.equals("0")) || r.json.optJSONArray("user_entitlement_pack_list")==null)
            throw new Failure("browser_auth_rejected","CHECK_"+responseDiagnostic(r));
        if(ctx!=null)ctx.check();
        JSONObject out=copy(account);
        out.put("bound",false);out.put("browserSessionVerified",true);out.put("authMode","browser");
        out.put("clientId",sessionClientId(account.optString("clientId","")));
        out.put("deviceBindingState","rejected");out.put("bindingDiagnostic",bindingDiagnostic(rejection));
        return out;
    }

    private static JSONObject ordinaryRefresh(JSONObject account,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(!safeCredential(account,"refresh"))throw new Failure("requires_relogin","MISSING_REFRESH");
        String client=sessionClientId(account.optString("clientId",""));
        JSONObject body=new JSONObject().put("ClientID",client).put("ClientSecret","-")
                .put("RefreshToken",account.optString("refresh")).put("UserID","");
        Map<String,String> h=new LinkedHashMap<>();h.put("Content-Type","application/json");
        Response r;
        try { r=http(consumerAuthHost(account.optString("authHost",""))+"/cloudide/api/v3/trae/oauth/ExchangeToken","POST",body,h,network,ctx); }
        catch(Failure failure) { if(failure.code.equals("response_too_large"))throw new Failure("outcome_unknown","BROWSER_RESPONSE_TOO_LARGE");throw failure; }
        JSONObject result=r.json==null?null:r.json.optJSONObject("Result");
        if(result==null && r.json!=null)result=r.json.optJSONObject("result");
        String token=result==null?"":text(result,"Token","token");
        String next=result==null?"":text(result,"RefreshToken","refreshToken");
        String code=businessCode(r.json);
        JSONObject credentials=new JSONObject().put("token",token).put("refresh",next);
        if(r.status>=200 && r.status<300 && (code.isEmpty()||code.equals("0"))
                && safeCredential(credentials,"token") && safeCredential(credentials,"refresh")) {
            if(ctx!=null)ctx.check();
            JSONObject out=copy(account);out.put("token",token);out.put("refresh",next);
            out.put("expires",tokenExpiry(result,token));out.put("clientId",client);
            out.put("bound",false);out.put("authMode","browser");out.put("browserSessionVerified",true);
            return out;
        }
        // A successful-looking but incomplete/contradictory exchange may already have spent
        // the refresh credential: never replay it or publish the old refresh token as new.
        boolean rejected=token.isEmpty() && r.json!=null && !code.isEmpty() && !code.equals("0")
                && (r.status==400 || r.status==401 || r.status==403 || r.status>=200&&r.status<300);
        throw new Failure(rejected?"requires_relogin":"outcome_unknown","BROWSER_"+responseDiagnostic(r));
    }
    public static JSONObject refresh(JSONObject account,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(verifiedBrowserAccount(account)) {
            try { TraeDevice.validate(account); } catch(Exception invalid) { throw new Failure("invalid_device"); }
            return ordinaryRefresh(account,network,ctx);
        }
        try { return bindDevice(account,network,ctx); }
        catch(Failure failure) {
            if(!failure.bindingRejected)throw failure;
            return ordinaryRefresh(account,network,ctx);
        }
    }

    public static JSONObject request(JSONObject account,String path,String method,JSONObject body,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(path==null || !path.startsWith("/trae/api/") || path.contains("..") || path.contains("?") || path.contains("#") || path.contains("\\") || path.contains("%") || !("GET".equals(method)||"POST".equals(method)))throw new Failure("invalid_request");
        Response r=http(AUTH_HOST+path,method,body,headers(account),network,ctx); if(r.status<200||r.status>=300)throw new Failure("http_"+r.status); if(r.json==null)throw new Failure("invalid_response"); return r.json;
    }
    public static JSONObject modelRequest(JSONObject account,String path,JSONObject body,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(!"/api/ide/v1/get_detail_param".equals(path)&&!"/api/ide/v1/batch_get_detail_param".equals(path))throw new Failure("invalid_request");
        Response r=http(MODEL_HOST+path,"POST",body==null?new JSONObject():body,headers(account),network,ctx); if(r.status<200||r.status>=300)throw new Failure("http_"+r.status); if(r.json==null)throw new Failure("invalid_response"); return r.json;
    }
    static String ugDevice(JSONObject a) throws Exception { String id=a.optString("deviceId",""); if(id.matches("[0-9]{15}"))return id; String uid=a.optString("uid",""); if(id.isEmpty()&&uid.isEmpty())throw new Failure("invalid_device"); byte[] d=MessageDigest.getInstance("SHA-256").digest(("trae-cn-device\0"+id+"\0"+uid).getBytes(StandardCharsets.UTF_8)); byte[] first=Arrays.copyOf(d,15); return new BigInteger(1,first).mod(new BigInteger("900000000000000")).add(new BigInteger("100000000000000")).toString(); }
    private static Map<String,String> ugHeaders(JSONObject a) throws Exception { Map<String,String> h=headers(a);h.put("Accept","application/json");h.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) TRAE-SOLO-CN/0.1.69 Chrome/120.0.6099.291 Electron/28.2.10 Safari/537.36");h.put("x-device-id",ugDevice(a));h.put("x-device-brand",BRAND);h.put("x-device-type","Windows");h.put("x-os-version","10.0.22631");h.put("x-app-version","0.1.69");return h; }
    private static JSONObject checkinCall(JSONObject a,String path,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception { JSONObject body=new JSONObject();body.put("req_source",1);Response r=http(AUTH_HOST+path,"POST",body,ugHeaders(a),network,ctx);if(r.status<200||r.status>=300)throw new Failure("http_"+r.status);if(r.json==null)throw new Failure("invalid_response");return r.json; }
    public static JSONObject checkin(JSONObject a,NetworkPolicy.Snapshot network,RequestContext ctx,boolean claim) throws Exception {
        String base="/trae/api/v2/ug/checkin_credits/"; JSONObject first=checkinCall(a,base+"status",network,ctx);String code=businessCode(first);if(!code.isEmpty()&&!code.equals("0"))throw new Failure("checkin_status_rejected");JSONObject out=new JSONObject();out.put("status",first);
        if(!claim){out.put("outcome","status");return out;}if(first.optBoolean("checked_in",false)){out.put("outcome","done");return out;}if(!first.has("enable")||!first.optBoolean("enable",false)){out.put("outcome",first.has("enable")?"inactive":"unknown");return out;}
        JSONObject response=checkinCall(a,base+"claim",network,ctx);out.put("claim",response);code=businessCode(response);if("9074".equals(code)){out.put("outcome","ineligible");out.put("message","9074：上游拒绝签到；此版本不执行备用请求");return out;}if("9095".equals(code)){out.put("outcome","ineligible");return out;}if(!code.isEmpty()&&!code.equals("0")){out.put("outcome","rejected");return out;}
        try {JSONObject after=checkinCall(a,base+"status",network,ctx);out.put("status",after);String c=businessCode(after);out.put("outcome",(c.isEmpty()||c.equals("0"))&&after.optBoolean("checked_in",false)?"done":"unknown");}catch(Exception e){out.put("outcome","unknown");}return out;
    }
    // Consumer login may report either registered China domain. Never accept suffix matches.
    static boolean consumerCallbackHost(String value,boolean authOnly) {
        if(value==null || value.isEmpty())return true;
        try {java.net.URI u=new java.net.URI(value.contains("://")?value:"https://"+value);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo()!=null || (u.getPort()!=-1&&u.getPort()!=443)
                    || u.getRawQuery()!=null || u.getRawFragment()!=null || (u.getRawPath()!=null&&!u.getRawPath().isEmpty()&&!u.getRawPath().equals("/")))return false;
            String h=u.getHost();return "api.trae.cn".equalsIgnoreCase(h)||"api.trae.com.cn".equalsIgnoreCase(h)
                    || (!authOnly && ("www.trae.cn".equalsIgnoreCase(h)||"www.trae.com.cn".equalsIgnoreCase(h)||"trae-api-cn.mchost.guru".equalsIgnoreCase(h)));
        }catch(Exception e){return false;}
    }
    static String consumerAuthHost(String value) throws Failure {
        if(value==null || value.isEmpty())return AUTH_HOST;
        if(!consumerCallbackHost(value,true))throw new Failure("enterprise_unsupported");
        try { URI u=new URI(value.contains("://")?value:"https://"+value); return "https://"+u.getHost().toLowerCase(Locale.ROOT); }
        catch(Exception invalid) { throw new Failure("enterprise_unsupported"); }
    }
    private static String callbackError(Exception error) {
        if(error instanceof Failure) {String c=((Failure)error).code;
            if(c.equals("enterprise_unsupported"))return "授权来自尚未支持的站点，请使用 Trae CN 个人账号";
            if(c.equals("missing_credentials"))return "授权回调缺少令牌字段，请重新发起授权";
        }
        return "授权回调字段解析失败，请重新发起授权";
    }
    public static Flow startLogin() throws Exception { return startLogin(NetworkPolicy.capture()); }
    public static Flow startLogin(NetworkPolicy.Snapshot network) throws Exception { synchronized(TraeAuth.class) { if(flows>=4)throw new Failure("too_many_flows"); flows++; } try { return new Flow(network); } catch(Exception e){synchronized(TraeAuth.class){flows--;} throw e;} }
    public static final class Flow implements AutoCloseable {
        public final String flowId=UUID.randomUUID().toString(),url; public final long deadline=System.currentTimeMillis()+5*60*1000L;
        public volatile String status="pending",error="",errorCode=""; private final ServerSocket server; private final JSONObject device; private final NetworkPolicy.Snapshot network; private JSONObject result; private boolean released; private RequestContext bindingContext; private Socket activeSocket;
        private Flow(NetworkPolicy.Snapshot n) throws Exception { network=n==null?NetworkPolicy.capture():n; device=TraeDevice.create(); server=new ServerSocket(); server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)); server.setSoTimeout(1000);
            // The official authorization page only accepts an exact callback without a query.
            // It reflects login_trace_id as loginTraceID; require that unpredictable value on callback.
            String cb="http://127.0.0.1:"+server.getLocalPort()+"/authorize"; String[][] q={{"login_version","1"},{"auth_from","solo"},{"login_channel","native_ide"},{"plugin_version","2.3.24254"},{"auth_type","local"},{"client_id","ono9krqynydwx5"},{"redirect","0"},{"login_trace_id",flowId},{"auth_callback_url",cb},{"machine_id",device.optString("machineId")},{"device_id",device.optString("deviceId")},{"x_device_id",device.optString("deviceId")},{"x_machine_id",device.optString("machineId")},{"x_device_brand",BRAND},{"x_device_type","windows"},{"x_os_version","Windows 10 Pro"},{"x_env",""},{"x_app_version","3.3.65"},{"x_app_type","stable"},{"hide_saas_login","true"}}; StringBuilder s=new StringBuilder("https://www.trae.cn/authorization?"); for(String[] p:q){if(s.charAt(s.length()-1)!='?')s.append('&');s.append(enc(p[0])).append('=').append(enc(p[1]));} url=s.toString();
            Thread t=new Thread(new Runnable(){public void run(){serve();}},"trae-login"); t.setDaemon(true); t.start(); }
        public synchronized JSONObject view() { JSONObject o=new JSONObject(); try{o.put("flowId",flowId);o.put("url",url);o.put("deadline",deadline);o.put("status",status);o.put("error",error);o.put("errorCode",errorCode);}catch(Exception ignored){} return o; }
        public synchronized JSONObject takeResult() { JSONObject r=result; result=null; return r; }
        public void close() { RequestContext c; Socket socket; synchronized(this){if(status.equals("pending")||status.equals("binding")||status.equals("validating")){status="closed";error="登录已关闭";}c=bindingContext;socket=activeSocket;} if(c!=null)c.cancel("flow closed"); try{if(socket!=null)socket.close();}catch(Exception ignored){} try{server.close();}catch(Exception ignored){} }
        private synchronized void release(){if(!released){released=true;synchronized(TraeAuth.class){flows--;}}}
        private void serve(){ try {while(System.currentTimeMillis()<deadline && !server.isClosed()) { try(Socket s=server.accept()){synchronized(this){if(server.isClosed())break;activeSocket=s;} s.setSoTimeout(3000);try{handle(s);}finally{synchronized(this){activeSocket=null;}}} catch(SocketTimeoutException ignored){} catch(Exception ignored){} } synchronized(this){if(status.equals("pending")){status="expired";error="登录超时";}} } finally {try{server.close();}catch(Exception ignored){} release();} }
        private void handle(Socket s) throws Exception {
            InputStream in=s.getInputStream(); ByteArrayOutputStream b=new ByteArrayOutputStream(); int v, match=0; long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); final int[] terminator={13,10,13,10};
            while(b.size()<32768) { long left=end-System.nanoTime(); if(left<=0)break; s.setSoTimeout((int)Math.max(1,Math.min(3000,TimeUnit.NANOSECONDS.toMillis(left)+1))); v=in.read(); if(v<0)break; b.write(v); match=(v==terminator[match])?match+1:(v==13?1:0); if(match==4)break; }
            String raw=b.toString("UTF-8"); if(!raw.endsWith("\r\n\r\n")){reply(s,400,"","");return;} String[] lines=raw.split("\r\n"); String[] req=lines[0].split(" "); if(req.length!=3 || req[1].length()>24576 || !req[1].startsWith("/authorize") || !(req[1].equals("/authorize")||req[1].startsWith("/authorize?"))){reply(s,404,"","");return;}
            String origin="",host=""; for(int i=1;i<lines.length;i++){int k=lines[i].indexOf(':');if(k<1){reply(s,400,"","");return;}String name=lines[i].substring(0,k).trim(),val=lines[i].substring(k+1).trim();if(name.equalsIgnoreCase("Origin")){if(!origin.isEmpty()){reply(s,400,"","");return;}origin=val;} if(name.equalsIgnoreCase("Host")){if(!host.isEmpty()){reply(s,400,"","");return;}host=val;}}
            if(!host.equals("127.0.0.1:"+server.getLocalPort()) || (!origin.isEmpty()&&!origin.equals("https://www.trae.cn")&&!origin.equals("https://trae.cn"))){reply(s,403,"","");return;}
            if(req[0].equals("OPTIONS")){reply(s,204,origin,"");return;} if(!req[0].equals("GET")){reply(s,405,origin,"");return;}
            Map<String,String> q=new HashMap<>(); if(req[1].contains("?"))for(String pair:req[1].substring(req[1].indexOf('?')+1).split("&")){int k=pair.indexOf('=');String key=URLDecoder.decode(k<0?pair:pair.substring(0,k),"UTF-8"),val=URLDecoder.decode(k<0?"":pair.substring(k+1),"UTF-8");if(q.put(key,val)!=null){reply(s,400,origin,"");return;}}
            String returnedTrace=q.containsKey("loginTraceID")?q.get("loginTraceID"):q.get("login_trace_id");
            if(returnedTrace==null || (q.containsKey("loginTraceID") && q.containsKey("login_trace_id")) || !MessageDigest.isEqual(flowId.getBytes(StandardCharsets.US_ASCII),returnedTrace.getBytes(StandardCharsets.UTF_8))){reply(s,403,origin,"授权状态无效");return;}
            if(q.containsKey("error")){synchronized(this){if(status.equals("pending")){status="failed";error="授权未完成";}}reply(s,200,origin,"授权未完成");server.close();return;}
            if(!q.containsKey("userJwt")||!q.containsKey("userInfo")){reply(s,400,origin,"缺少授权信息");return;}
            synchronized(this){if(!status.equals("pending")){reply(s,409,origin,"登录已处理");return;}status="binding";}
            try { JSONObject jwt=new JSONObject(q.get("userJwt")),info=new JSONObject(q.get("userInfo")); String hostValue=q.containsKey("host")?q.get("host"):info.optString("Host",""); String auth=q.containsKey("authHost")?q.get("authHost"):q.containsKey("auth_host")?q.get("auth_host"):text(info,"AuthHost","authHost");
                if(!consumerCallbackHost(hostValue,false) || !consumerCallbackHost(auth,true))throw new Failure("enterprise_unsupported");
                JSONObject a=copy(device);a.put("authHost",consumerAuthHost(auth));String client=text(jwt,"ClientID","clientId");if(client.isEmpty())client=q.get("clientId");a.put("clientId",sessionClientId(client));a.put("token",text(jwt,"Token","token"));a.put("refresh",text(jwt,"RefreshToken","refreshToken"));a.put("uid",text(info,"UserID","userId"));a.put("name",text(info,"ScreenName","screenName"));long exp=tokenExpiry(jwt,a.optString("token"));a.put("expires",exp);a.put("bound",false); if(!safeCredential(a,"token")||!safeCredential(a,"refresh"))throw new Failure("missing_credentials");
                try {
                    RequestContext binding;
                    synchronized(this) {
                        if(!status.equals("binding"))throw new Failure("closed");
                        bindingContext=new RequestContext("trae-login",network,Math.max(1,deadline-System.currentTimeMillis()));
                        binding=bindingContext;
                    }
                    JSONObject authenticated;
                    try { authenticated=bindDevice(a,network,binding); }
                    catch(Failure rejection) {
                        if(!rejection.bindingRejected)throw rejection;
                        synchronized(this) { if(!status.equals("binding"))throw new Failure("closed");status="validating"; }
                        authenticated=verifyBrowserSession(a,rejection,network,binding);
                    }
                    binding.check();
                    synchronized(this) {
                        if(!(status.equals("binding")||status.equals("validating")) || binding.isCancelled())throw new Failure("closed");
                        result=authenticated;status="success";
                    }
                    reply(s,200,origin,"授权结果已确认，请返回 All In 查看入池结果");
                } catch(Exception e) {
                    synchronized(this) {
                        if(status.equals("binding")||status.equals("validating")) {
                            result=null;status="failed";errorCode=bindingDiagnostic(e);error=bindingFailureMessage(e);
                        }
                    }
                    reply(s,200,origin,error.isEmpty()?"登录未完成，请返回应用查看":error);
                }
            } catch(Exception e){synchronized(this){if(status.equals("binding")){status="failed";error=callbackError(e);}}reply(s,400,origin,callbackError(e));} finally {synchronized(this){if(bindingContext!=null)bindingContext.close();} server.close();}
        }
        private void reply(Socket s,int code,String origin,String body)throws Exception {
            String escaped=body.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
            String page=code==204?"":"<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Trae 国内版授权</title>"
                    +"<style>body{margin:0;padding:28px 22px;background:#111318;color:#f1f3f5;font:16px/1.7 system-ui,sans-serif}main{max-width:540px;margin:12vh auto}h1{font-size:22px}p{overflow-wrap:anywhere}.hint{color:#adb5bd;font-size:14px}</style>"
                    +"<main><h1>Trae 国内版授权</h1><p>"+escaped+"</p><p class=\"hint\">请返回 All In 检查状态。反馈时只截取提示和诊断码，不要复制地址栏的授权链接。</p></main>";
            byte[] data=page.getBytes(StandardCharsets.UTF_8);
            String h="HTTP/1.1 "+code+" Result\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: "+data.length+"\r\n"+(origin.isEmpty()?"":"Access-Control-Allow-Origin: "+origin+"\r\nVary: Origin\r\n")+"Access-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n";
            s.getOutputStream().write(h.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(data);s.getOutputStream().flush();
        }
    }
}
