package com.gaeul.allin;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
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
    public static final class Failure extends IOException { public final String code; Failure(String code) { super(code); this.code=code; } }
    private static final class Response { int status; JSONObject json; }
    private static Response http(String url,String method,JSONObject body,Map<String,String> headers,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        if(network==null)network=NetworkPolicy.capture(); if(ctx!=null)ctx.check(); String fixture=fixtureBase; if(fixture!=null) { URL original=new URL(url); url=fixture+original.getFile(); }
        OutboundLimiter.Result permit=LIMITER.await(Collections.singleton("trae-cn"),ctx==null?System.nanoTime()+20000000000L:ctx.deadlineNanos,ctx);
        if(permit.ticket==null)throw new Failure("outbound_"+permit.status.name().toLowerCase(Locale.ROOT));
        HttpURLConnection c=null; boolean sent=false; ScheduledFuture<?> timeout=null;
        long deadlineNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        if(ctx!=null)deadlineNanos=Math.min(deadlineNanos,ctx.deadlineNanos);
        try {
            c=network.openHttp(url); if(ctx!=null)ctx.attach(c); final HttpURLConnection timedConnection=c;
            timeout=DEADLINES.schedule(new Runnable(){public void run(){timedConnection.disconnect();}},Math.max(1,deadlineNanos-System.nanoTime()),TimeUnit.NANOSECONDS); c.setInstanceFollowRedirects(false); c.setConnectTimeout((int)Math.min(20000,ctx==null?20000:Math.max(1,ctx.remainingMillis()))); c.setReadTimeout((int)Math.min(20000,ctx==null?20000:Math.max(1,ctx.remainingMillis()))); c.setRequestMethod(method); c.setRequestProperty("Accept-Encoding","gzip"); c.setRequestProperty("Connection","close");
            for(Map.Entry<String,String> e:headers.entrySet())c.setRequestProperty(e.getKey(),e.getValue());
            if(body!=null) { byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8); if(bytes.length>MAX)throw new Failure("request_too_large"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length); sent=true; permit.ticket.markSent(); try(OutputStream out=c.getOutputStream()){out.write(bytes);} }
            if(body==null){sent=true;permit.ticket.markSent();} int status=c.getResponseCode(); if(timeout!=null){timeout.cancel(false);timeout=null;} InputStream in=status>=400?c.getErrorStream():c.getInputStream(); byte[] data=new byte[0];
            if(in!=null) {
                // Limit each underlying network read so a drip-fed body cannot hold a bulk
                // read (including gzip's internal reads) beyond the absolute deadline.
                final long bodyDeadline=deadlineNanos;
                InputStream raw=in;
                in=new FilterInputStream(raw) {
                    @Override public int read() throws IOException {
                        if(System.nanoTime()>=bodyDeadline)throw new SocketTimeoutException("deadline");
                        return super.read();
                    }
                    @Override public int read(byte[] b,int off,int len) throws IOException {
                        if(len==0)return 0;
                        if(System.nanoTime()>=bodyDeadline)throw new SocketTimeoutException("deadline");
                        return super.read(b,off,Math.min(len,Math.max(1,available())));
                    }
                };
                if("gzip".equalsIgnoreCase(c.getContentEncoding()))in=new GZIPInputStream(in);
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n,total=0;
                while(true) { if(ctx!=null)ctx.check(); long left=deadlineNanos-System.nanoTime(); if(left<=0)throw new SocketTimeoutException("deadline"); c.setReadTimeout((int)Math.max(1,Math.min(20000,TimeUnit.NANOSECONDS.toMillis(left)+1))); n=in.read(buf,0,Math.min(buf.length,Math.max(1,in.available()))); if(n==-1)break; total+=n; if(total>MAX)throw new Failure("response_too_large"); out.write(buf,0,n); }
                data=out.toByteArray();
                // Only close a fully consumed body; closing a partial HTTP body may drain it.
                in.close();
            }
            Response r=new Response(); r.status=status; try {r.json=new JSONObject(new String(data,StandardCharsets.UTF_8));}catch(Exception e){r.json=null;} return r;
        } catch(Failure e){throw e;} catch(Exception e){throw new Failure(sent?"outcome_unknown":"network_error");} finally {if(timeout!=null)timeout.cancel(false);if(c!=null){if(ctx!=null)ctx.detach(c);c.disconnect();}permit.ticket.close();}
    }
    private static JSONObject deviceInfo(JSONObject a,String platform,String version) throws Exception {
        JSONObject d=new JSONObject(); d.put("DeviceID",a.optString("deviceId")); d.put("MachineID",a.optString("machineId")); d.put("PlatformCode",platform); d.put("DeviceType","PC"); d.put("DeviceName",""); d.put("DeviceModel",""); d.put("ClientVersion",version); d.put("DevicePublicKey",a.optString("devicePublicKey")); d.put("DeviceBrand",BRAND); d.put("DeviceCPU","AMD"); d.put("OSInfo","Windows"); d.put("OSVersion","10.0.22631"); return d;
    }
    public static JSONObject refresh(JSONObject account,NetworkPolicy.Snapshot network,RequestContext ctx) throws Exception {
        TraeDevice.validate(account); String refresh=account.optString("refresh",""); if(refresh.isEmpty())throw new Failure("requires_relogin");
        String[] clients={"en1oxy7wnw8j9n","ono9krqynydwx5"}, platforms={"SOLO_PC","IDE_PC"}, versions={"0.1.64","3.3.99"};
        for(int i=0;i<2;i++) { JSONObject b=new JSONObject(); b.put("ClientID",clients[i]); b.put("ClientSecret",""); b.put("RefreshToken",refresh); b.put("DeviceInfo",deviceInfo(account,platforms[i],versions[i])); b.put("DeviceProof",TraeDevice.proofOf(PATH,clients[i],refresh,account.optString("devicePrivateKey"))); b.put("IDEVersion",versions[i]);
            Map<String,String> h=new LinkedHashMap<>(); h.put("Content-Type","application/json"); if(!account.optString("token","").isEmpty())h.put("Authorization","Cloud-IDE-JWT "+account.optString("token"));
            Response r; try { r=http(AUTH_HOST+PATH,"POST",b,h,network,ctx); } catch(Failure failure) { if(failure.code.equals("response_too_large"))throw new Failure("outcome_unknown"); throw failure; } JSONObject result=r.json==null?null:r.json.optJSONObject("Result"); if(result==null && r.json!=null)result=r.json.optJSONObject("result"); String token=result==null?"":text(result,"Token","token");
            if(r.status>=200 && r.status<300 && !token.isEmpty()) { JSONObject out=copy(account); out.put("token",token); String next=text(result,"RefreshToken","refreshToken"); out.put("refresh",next.isEmpty()?refresh:next); long expiry=tokenExpiry(result,token); if(expiry>0)out.put("expires",expiry); out.put("bound",true); return out; }
            // Only a concrete 4xx response or an explicit nonzero business code permits trying the other client.
            String code=businessCode(r.json); boolean reject=(r.status==400||r.status==401||r.status==403||r.status>=200&&r.status<300) && !code.isEmpty() && !code.equals("0");
            if(!reject)throw new Failure("outcome_unknown");
        } throw new Failure("requires_relogin");
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
        public volatile String status="pending",error=""; private final ServerSocket server; private final JSONObject device; private final NetworkPolicy.Snapshot network; private JSONObject result; private boolean released; private RequestContext bindingContext; private Socket activeSocket;
        private Flow(NetworkPolicy.Snapshot n) throws Exception { network=n==null?NetworkPolicy.capture():n; device=TraeDevice.create(); server=new ServerSocket(); server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)); server.setSoTimeout(1000);
            // The official authorization page only accepts an exact callback without a query.
            // It reflects login_trace_id as loginTraceID; require that unpredictable value on callback.
            String cb="http://127.0.0.1:"+server.getLocalPort()+"/authorize"; String[][] q={{"login_version","1"},{"auth_from","solo"},{"login_channel","native_ide"},{"plugin_version","2.3.24254"},{"auth_type","local"},{"client_id","ono9krqynydwx5"},{"redirect","0"},{"login_trace_id",flowId},{"auth_callback_url",cb},{"machine_id",device.optString("machineId")},{"device_id",device.optString("deviceId")},{"x_device_id",device.optString("deviceId")},{"x_machine_id",device.optString("machineId")},{"x_device_brand",BRAND},{"x_device_type","windows"},{"x_os_version","Windows 10 Pro"},{"x_env",""},{"x_app_version","3.3.65"},{"x_app_type","stable"},{"hide_saas_login","true"}}; StringBuilder s=new StringBuilder("https://www.trae.cn/authorization?"); for(String[] p:q){if(s.charAt(s.length()-1)!='?')s.append('&');s.append(enc(p[0])).append('=').append(enc(p[1]));} url=s.toString();
            Thread t=new Thread(new Runnable(){public void run(){serve();}},"trae-login"); t.setDaemon(true); t.start(); }
        public synchronized JSONObject view() { JSONObject o=new JSONObject(); try{o.put("flowId",flowId);o.put("url",url);o.put("deadline",deadline);o.put("status",status);o.put("error",error);}catch(Exception ignored){} return o; }
        public synchronized JSONObject takeResult() { JSONObject r=result; result=null; return r; }
        public void close() { RequestContext c; Socket socket; synchronized(this){if(status.equals("pending")||status.equals("binding")){status="closed";error="登录已关闭";}c=bindingContext;socket=activeSocket;} if(c!=null)c.cancel("flow closed"); try{if(socket!=null)socket.close();}catch(Exception ignored){} try{server.close();}catch(Exception ignored){} }
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
                JSONObject a=copy(device);a.put("token",text(jwt,"Token","token"));a.put("refresh",text(jwt,"RefreshToken","refreshToken"));a.put("uid",text(info,"UserID","userId"));a.put("name",text(info,"ScreenName","screenName"));long exp=tokenExpiry(jwt,a.optString("token"));a.put("expires",exp);a.put("bound",false); if(a.optString("token").isEmpty()||a.optString("refresh").isEmpty())throw new Failure("missing_credentials");
                try{RequestContext binding; synchronized(this){if(!status.equals("binding"))throw new Failure("closed");bindingContext=new RequestContext("trae-login",network,Math.max(1,deadline-System.currentTimeMillis()));binding=bindingContext;} a=refresh(a,network,binding); synchronized(this){if(status.equals("binding")){if(binding.isCancelled()){result=null;status="failed";error="设备绑定未完成，请重新授权；结果未知不会自动重试";}else{result=a;status="success";}}}reply(s,200,origin,"登录已处理，请返回应用查看");}catch(Exception e){synchronized(this){if(status.equals("binding")){result=null;status="failed";error="设备绑定未完成，请重新授权；结果未知不会自动重试";}}reply(s,200,origin,"设备绑定失败，请返回应用查看");}
            } catch(Exception e){synchronized(this){if(status.equals("binding")){status="failed";error=callbackError(e);}}reply(s,400,origin,callbackError(e));} finally {synchronized(this){if(bindingContext!=null)bindingContext.close();} server.close();}
        }
        private void reply(Socket s,int code,String origin,String body)throws Exception {byte[] data=body.getBytes(StandardCharsets.UTF_8);String h="HTTP/1.1 "+code+" Result\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+data.length+"\r\n"+(origin.isEmpty()?"":"Access-Control-Allow-Origin: "+origin+"\r\nVary: Origin\r\n")+"Access-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n";s.getOutputStream().write(h.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(data);s.getOutputStream().flush();}
    }
}
