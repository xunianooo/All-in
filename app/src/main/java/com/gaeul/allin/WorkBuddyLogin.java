package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official browser login contract from opencode-workbuddy-auth 0.2.26, without desktop discovery. */
final class WorkBuddyLogin {
    interface ConnectionFactory { HttpURLConnection open(String url) throws Exception; }
    interface Api {
        JSONObject call(String provider, String method, String path, Map<String,String> headers, JSONObject body) throws Exception;
    }
    static final class ApiFailure extends IOException {
        final int status, code;
        ApiFailure(int status, int code) { super("WorkBuddy account API refused"); this.status=status; this.code=code; }
        boolean pending() { return code==11217 || code==12151 || status==408 || status==429; }
    }
    static final class Flow {
        final String provider, state, authUrl;
        final long deadline = System.currentTimeMillis() + 300000L;
        JSONObject token, account;
        String savedId;
        Flow(String provider, String state, String authUrl) { this.provider=provider; this.state=state; this.authUrl=authUrl; }
    }
    private WorkBuddyLogin() {}
    private static String text(JSONObject object, String key) {
        Object value=object==null?null:object.opt(key);
        return value instanceof String?(String)value:value instanceof Number?value.toString():"";
    }
    private static boolean safe(String value) {
        if(value==null || value.length()>16384)return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32 || value.charAt(i)==127)return false;
        return true;
    }
    static String enterpriseValue(JSONObject account) throws IOException {
        Object raw=account==null?null:account.opt("enterpriseId");
        if(raw==null || raw==JSONObject.NULL)return "";
        // Validate before trimming: CR/LF-only identities must not silently become personal accounts.
        if(!(raw instanceof String) || !safe((String)raw))throw new IOException("Invalid WorkBuddy enterprise identity");
        return ((String)raw).trim();
    }
    static Flow begin(String provider, Api api) throws Exception {
        WorkBuddyAuth.origin(provider);
        Map<String,String> headers=new LinkedHashMap<>();
        for(String name:new String[]{"X-No-Authorization","X-No-User-Id","X-No-Enterprise-Id","X-No-Department-Info"})headers.put(name,"true");
        JSONObject data=api.call(provider,"POST","/v2/plugin/auth/state?platform="+provider,headers,new JSONObject());
        String state=text(data,"state"), url=text(data,"authUrl");
        if(state.isEmpty() || !safe(state) || state.length()>4096)throw new IOException("Invalid WorkBuddy login state");
        URI uri=URI.create(url);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || !safe(url))
            throw new IOException("Invalid WorkBuddy login URL");
        String fragment=uri.getRawFragment()==null?"":"#"+uri.getRawFragment();
        String base=url.split("#",2)[0].split("[?]",2)[0];
        List<String> query=new ArrayList<>();
        if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&")) {
            String key=URLDecoder.decode(part.split("=",2)[0],"UTF-8");
            if(!"version".equals(key) && !"loginSessionId".equals(key))query.add(part);
        }
        query.add("version=5.9.3"); query.add("loginSessionId="+UUID.randomUUID().toString().replace("-",""));
        StringBuilder encoded=new StringBuilder();
        for(String part:query){if(encoded.length()>0)encoded.append('&');encoded.append(part);}
        return new Flow(provider,state,base+"?"+encoded+fragment);
    }
    /** Reads the account selected on the official page, never guesses identity from a nickname. */
    static JSONObject poll(Flow flow, Api api) throws Exception {
        if(System.currentTimeMillis()>=flow.deadline)throw new IOException("WorkBuddy sign-in expired");
        if(flow.account!=null)return new JSONObject(flow.account.toString());
        String query="?state="+URLEncoder.encode(flow.state,"UTF-8");
        if(flow.token==null)flow.token=api.call(flow.provider,"GET","/v2/plugin/auth/token"+query,Collections.emptyMap(),null);
        JSONObject account=WorkBuddyAuth.loginAccount(flow.provider,flow.token,"");
        Map<String,String> headers=new LinkedHashMap<>();
        headers.put("Authorization","Bearer "+account.optString("access"));
        headers.put("X-Domain",account.optString("domain"));
        headers.put("X-No-User-Id","true"); headers.put("X-No-Enterprise-Id","true");
        JSONObject who=api.call(flow.provider,"GET","/v2/plugin/login/account"+query,headers,null);
        // Only /login/account, not display names or the token reply, owns the selected identity.
        for(String field:new String[]{"uid","enterpriseId","enterpriseName","email"})account.remove(field);
        WorkBuddyAuth.applyIdentity(account,who);
        if(account.optString("uid","").trim().isEmpty())throw new IOException("WorkBuddy returned no verified account identity");
        String kind=text(who,"type").trim().toLowerCase(Locale.ROOT);
        String enterprise=enterpriseValue(who), enterpriseName=text(who,"enterpriseName");
        if((!enterprise.isEmpty() && enterpriseName.isEmpty()) || (enterprise.isEmpty() && !kind.isEmpty() && !"personal".equals(kind))) {
            Map<String,String> lookup=new LinkedHashMap<>();
            lookup.put("Authorization","Bearer "+account.optString("access")); lookup.put("X-Domain",account.optString("domain"));
            JSONObject listing;
            try { listing=api.call(flow.provider,"GET","/v2/plugin/accounts",lookup,null); }
            catch(Exception unavailable) {
                // A known enterprise id is sufficient; a missing name is display-only.
                if(enterprise.isEmpty())throw new IOException("WorkBuddy team identity unavailable");
                listing=new JSONObject();
            }
            JSONArray entries=listing.optJSONArray("accounts");
            List<JSONObject> mine=new ArrayList<>(), same=new ArrayList<>(), last=new ArrayList<>();
            for(int i=0;entries!=null && i<entries.length();i++) {
                JSONObject entry=entries.optJSONObject(i);
                if(entry==null || !account.optString("uid").equals(text(entry,"uid")))continue;
                String id=enterpriseValue(entry); if(id.isEmpty())continue;
                mine.add(entry);
                if(kind.equals(text(entry,"type").trim().toLowerCase(Locale.ROOT)))same.add(entry);
                if(entry.optBoolean("lastLogin",false))last.add(entry);
                if(id.equals(enterprise))enterpriseName=text(entry,"enterpriseName");
            }
            if(enterprise.isEmpty()) {
                JSONObject picked=same.size()==1?same.get(0):last.size()==1?last.get(0):mine.size()==1?mine.get(0):null;
                // Do not silently send a known but ambiguous enterprise account as personal.
                if(picked==null)throw new IOException("WorkBuddy team identity is ambiguous; select an account again");
                enterprise=enterpriseValue(picked); enterpriseName=text(picked,"enterpriseName");
            }
        }
        account.remove("enterpriseId"); account.remove("enterpriseName");
        if(!enterprise.isEmpty())account.put("enterpriseId",enterprise).put("enterpriseName",enterpriseName);
        String name=text(who,"nickname");if(name.isEmpty())name=text(who,"phoneNumber");if(name.isEmpty())name=account.optString("uid");
        if(!enterprise.isEmpty())name+=" · "+(enterpriseName.isEmpty()?"团队 "+enterprise.substring(Math.max(0,enterprise.length()-6)):enterpriseName);
        account.put("name",name);
        flow.account=new JSONObject(account.toString());
        return account;
    }
    /** Fixed origins and redirect refusal prevent account credentials crossing an origin boundary. */
    static JSONObject call(String provider,String method,String path,Map<String,String> headers,JSONObject body,ConnectionFactory factory) throws Exception {
        URI endpoint=URI.create(WorkBuddyAuth.origin(provider)+path);
        if(!path.startsWith("/v2/plugin/") || endpoint.getRawFragment()!=null || endpoint.getRawUserInfo()!=null
                || !endpoint.normalize().equals(endpoint))throw new IOException("Unsupported WorkBuddy account path");
        HttpURLConnection conn=null;
        try {
            conn=factory.open(endpoint.toASCIIString());
            conn.setInstanceFollowRedirects(false);conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept","application/json");conn.setRequestProperty("Accept-Encoding","identity");
            for(Map.Entry<String,String> header:headers.entrySet()) {
                if(!safe(header.getValue()))throw new IOException("Invalid WorkBuddy account header");
                conn.setRequestProperty(header.getKey(),header.getValue());
            }
            if(body!=null) {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            }
            int status=conn.getResponseCode();
            InputStream stream=status>=200 && status<300?conn.getInputStream():conn.getErrorStream();
            if(stream==null)throw new ApiFailure(status,-1);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();long deadline=System.nanoTime()+15000000000L;
            try(InputStream in=stream) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1) {
                    if(bytes.size()+n>2*1024*1024 || System.nanoTime()>deadline)throw new IOException("WorkBuddy account response limit");
                    bytes.write(buffer,0,n);
                }
            }
            JSONObject envelope=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            int code=envelope.optInt("code",-1);
            if(status<200 || status>=300 || code!=0)throw new ApiFailure(status,code);
            JSONObject data=envelope.optJSONObject("data");
            if(data==null || data.length()==0)throw new ApiFailure(status,11217);
            return data;
        } finally {if(conn!=null)conn.disconnect();}
    }
}
