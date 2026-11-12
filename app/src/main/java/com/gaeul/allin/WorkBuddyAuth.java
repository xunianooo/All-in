package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** WorkBuddy OAuth contract from community 0.2.26; never retry a refresh POST. */
final class WorkBuddyAuth {
    static final String[] ROTATING_FIELDS={"access","token","refresh","expires","refreshExpiresAt","domain","tokenType"};
    private static final String CRLF=new String(new char[]{13,10});
    static final class Failure extends IOException {
        final boolean notSent;
        final int status;
        Failure(String kind,boolean notSent,int status){super("WorkBuddy "+kind);this.notSent=notSent;this.status=status;}
    }
    interface Exchange { JSONObject refresh(String provider,JSONObject account) throws Exception; }
    private WorkBuddyAuth() {}
    static String origin(String provider) {
        if("workbuddy".equals(provider))return "https://copilot.tencent.com";
        if("workbuddy-ai".equals(provider))return "https://www.workbuddy.ai";
        throw new IllegalArgumentException("Unsupported WorkBuddy provider");
    }
    static String providerOfOrigin(String url) {
        if("https://copilot.tencent.com".equals(url))return "workbuddy";
        if("https://www.workbuddy.ai".equals(url))return "workbuddy-ai";
        throw new IllegalArgumentException("Untrusted WorkBuddy login origin");
    }
    private static String text(JSONObject o,String key) {
        Object v=o.opt(key); String value=v instanceof String?(String)v:v instanceof Number?v.toString():"";
        if(!safe(value))throw new IllegalArgumentException("Invalid WorkBuddy response field");
        return value.trim();
    }
    private static boolean safe(String s){if(s.length()>16384)return false;for(int i=0;i<s.length();i++)if(s.charAt(i)<32||s.charAt(i)==127)return false;return true;}
    static JSONObject merge(JSONObject previous,JSONObject answer) throws IOException {
        JSONObject out=previous==null?new JSONObject():new JSONObject(previous.toString());
        String access=text(answer,"accessToken");if(access.isEmpty())access=text(answer,"access_token");if(access.isEmpty())access=text(answer,"token");
        if(access.isEmpty() || !safe(access))throw new Failure("invalid token response",false,0);
        out.put("access",access).put("token",access).put("type","oauth");
        String refresh=text(answer,"refreshToken");if(refresh.isEmpty())refresh=text(answer,"refresh_token");
        if(!refresh.isEmpty()){if(!safe(refresh))throw new Failure("invalid refresh response",false,0);out.put("refresh",refresh);}
        long now=System.currentTimeMillis();
        for(String[] fields:new String[][]{{"expiresAt","expiresIn","expires"},{"refreshExpiresAt","refreshExpiresIn","refreshExpiresAt"}}){
            long at=answer.optLong(fields[0],0),seconds=answer.optLong(fields[1],0);
            if(at>0)out.put(fields[2],at);else if(seconds>0 && seconds<(Long.MAX_VALUE-now)/1000)out.put(fields[2],now+seconds*1000);
        }
        for(String field:new String[]{"domain","tokenType"}){String v=text(answer,field);if(!v.isEmpty()){if(!safe(v))throw new Failure("invalid account response",false,0);out.put(field,v);}}
        if(!out.has("expires")){
            try{String[] parts=access.split("[.]");if(parts.length>1){String p=parts[1].replace('-','+').replace('_','/');while(p.length()%4!=0)p+="=";
                byte[] decoded=decode64(p);long exp=new JSONObject(new String(decoded,StandardCharsets.UTF_8)).optLong("exp",0);if(exp>0&&exp<Long.MAX_VALUE/1000)out.put("expires",exp*1000);}}
            catch(Exception ignored){}
        }
        return out;
    }
    private static byte[] decode64(String value)throws Exception {
        // Existing Android-compatible decoder; no java.util.Base64 API26 dependency.
        Class<?> base=Class.forName("android.util.Base64");
        try{return (byte[])base.getMethod("decode",String.class,int.class).invoke(null,value,0);}catch(Exception stub){
            return (byte[])Class.forName("java.util.Base64$Decoder").getMethod("decode",String.class)
                    .invoke(Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null),value);
        }
    }
    static JSONObject loginAccount(String provider,JSONObject answer,String name) throws IOException {
        JSONObject out=merge(null,answer);out.put("name",name).put("enabled",true);
        if(!out.has("domain"))out.put("domain",URI.create(origin(provider)).getHost());
        applyIdentity(out,answer);
        return out;
    }
    static void applyIdentity(JSONObject account,JSONObject identity)throws IOException {
        WorkBuddyLogin.enterpriseValue(identity);
        for(String key:new String[]{"uid","enterpriseId","enterpriseName","email"}){
            String value=text(identity,key);if(!value.isEmpty()){if(!safe(value))throw new IOException("Invalid WorkBuddy identity");account.put(key,value);}
        }
        if(account.optString("uid","").isEmpty()){String id=text(identity,"userId");if(!id.isEmpty()&&safe(id))account.put("uid",id);}
    }
    static JSONObject refreshCandidate(String provider,JSONObject account)throws Exception {
        return refreshCandidate(provider,account,WorkBuddyAuth::exchange);
    }
    static JSONObject refreshCandidate(String provider,JSONObject account,Exchange exchange)throws Exception {
        JSONObject snapshot=new JSONObject(account.toString());
        String refresh=snapshot.optString("refresh","");
        if(refresh.isEmpty() || !safe(refresh) || (snapshot.optLong("refreshExpiresAt",0)>0 && System.currentTimeMillis()>=snapshot.optLong("refreshExpiresAt",0)))
            throw new Failure("refresh unavailable; sign in again",true,401);
        JSONObject candidate=merge(snapshot,exchange.refresh(provider,snapshot));
        // A token answer must never silently change the selected owner or slot.
        return candidate;
    }
    static boolean definitelyNotSent(Throwable failure){return failure instanceof Failure&&((Failure)failure).notSent;}
    private static JSONObject exchange(String provider,JSONObject account)throws Exception {
        URI u=URI.create(origin(provider));Socket raw=null;SSLSocket tls=null;boolean sent=false;int status=0;
        long deadline=System.nanoTime()+30000000000L;
        try{
            raw=NetworkPolicy.capture().connectTunnel(u.getHost(),443,10000,15000);
            tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,u.getHost(),443,true);
            SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.setSoTimeout(15000);tls.startHandshake();
            String path="/v2/plugin/auth/token/refresh";
            Map<String,String> headers=new LinkedHashMap<>();headers.put("Host",u.getHost());headers.put("Connection","close");headers.put("Content-Type","application/json");headers.put("Accept","application/json");headers.put("Accept-Encoding","identity");headers.put("Content-Length","2");
            headers.put("X-Refresh-Token",account.optString("refresh"));headers.put("X-Auth-Refresh-Source","plugin");
            headers.put("X-Domain",account.optString("domain",u.getHost()));
            String enterprise=WorkBuddyLogin.enterpriseValue(account);if(!enterprise.isEmpty()){headers.put("X-Enterprise-Id",enterprise);headers.put("X-Tenant-Id",enterprise);}
            StringBuilder request=new StringBuilder("POST ").append(path).append(" HTTP/1.1").append(CRLF);
            for(Map.Entry<String,String> h:headers.entrySet()){if(!safe(h.getValue()))throw new Failure("invalid refresh headers",true,0);request.append(h.getKey()).append(": ").append(h.getValue()).append(CRLF);}
            request.append(CRLF).append("{}");if(request.length()>32768)throw new Failure("refresh headers too large",true,0);
            // The refresh credential is in the headers, so mark before the first write.
            sent=true;OutputStream out=tls.getOutputStream();out.write(request.toString().getBytes(StandardCharsets.UTF_8));out.flush();
            InputStream in=new BufferedInputStream(tls.getInputStream());String first=line(in,tls,deadline);String[] parts=first.split(" ",3);if(parts.length<2)throw new IOException("Invalid status");status=Integer.parseInt(parts[1]);
            Map<String,String> response=new HashMap<>();int total=first.length();
            while(true){String l=line(in,tls,deadline);total+=l.length();if(total>32768)throw new IOException("Headers too large");if(l.isEmpty())break;int colon=l.indexOf(':');if(colon>0)response.put(l.substring(0,colon).toLowerCase(Locale.ROOT),l.substring(colon+1).trim());}
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            if("chunked".equalsIgnoreCase(response.get("transfer-encoding"))){
                while(true){String n=line(in,tls,deadline);int semi=n.indexOf(';');int size=Integer.parseInt(semi<0?n:n.substring(0,semi),16);if(size<0||size>1048576-body.size())throw new IOException("Body too large");if(size==0){while(!line(in,tls,deadline).isEmpty()){}break;}readExact(in,tls,deadline,body,size);if(!line(in,tls,deadline).isEmpty())throw new IOException("Invalid chunk");}
            }else if(response.containsKey("content-length")){
                long length=Long.parseLong(response.get("content-length"));if(length<0||length>1048576)throw new IOException("Body too large");readExact(in,tls,deadline,body,(int)length);
            }else{byte[] b=new byte[4096];int n;while(true){timeout(tls,deadline);n=in.read(b);if(n<0)break;if(body.size()+n>1048576)throw new IOException("Body too large");body.write(b,0,n);}}
            if(status<200||status>=300)throw new Failure("refresh HTTP "+status,false,status);
            JSONObject envelope=new JSONObject(new String(body.toByteArray(),StandardCharsets.UTF_8));Object code=envelope.opt("code");if(code!=null&&code!=JSONObject.NULL&&!"0".equals(String.valueOf(code)))throw new Failure("refresh refused",false,status);
            JSONObject data=envelope.optJSONObject("data");return data==null?envelope:data;
        }catch(Exception failure){if(failure instanceof Failure)throw failure;throw new Failure(sent?"refresh result unknown; sign in again":"refresh not sent",!sent,status);}
        finally{if(tls!=null)try{tls.close();}catch(Exception ignored){}else if(raw!=null)try{raw.close();}catch(Exception ignored){}}
    }
    private static void timeout(Socket socket,long deadline)throws IOException {long remaining=(deadline-System.nanoTime())/1000000;if(remaining<=0)throw new SocketTimeoutException("Refresh deadline");socket.setSoTimeout((int)Math.max(1,Math.min(15000,remaining)));}
    private static String line(InputStream in,Socket socket,long deadline)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int previous=-1;
        while(b.size()<32768){timeout(socket,deadline);int c=in.read();if(c<0)throw new EOFException();if(previous==13&&c==10){byte[] bytes=b.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}b.write(c);previous=c;}
        throw new IOException("Line too large");
    }
    private static void readExact(InputStream in,Socket socket,long deadline,ByteArrayOutputStream out,int n)throws IOException {byte[] b=new byte[4096];while(n>0){timeout(socket,deadline);int got=in.read(b,0,Math.min(b.length,n));if(got<=0)throw new EOFException();out.write(b,0,got);n-=got;}}
}
