package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Synthetic in-memory HTTP transport; never contacts Qoder. */
public final class QoderCatalogIntegrityHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Fake extends HttpURLConnection {
        final int status; final String response; final ByteArrayOutputStream sent = new ByteArrayOutputStream(); boolean disconnected;
        Fake(String url, int status, String response) throws Exception { super(new URL(url)); this.status=status; this.response=response; }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public int getResponseCode() { return status; }
        public OutputStream getOutputStream() { return sent; }
        public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
    }
    static class Transport implements QoderAuth.ConnectionFactory {
        final int status; final String body; Fake last;
        Transport(int status, String body) { this.status=status; this.body=body; }
        public HttpURLConnection open(String url) throws Exception { return last = new Fake(url,status,body); }
    }
    static JSONObject entry(String key, boolean enable, double price) throws Exception {
        return new JSONObject().put("key", key).put("enable", enable).put("price_factor",price).put("display_name", "name-"+key).put("source","system");
    }
    static String listing(boolean paidEnabled) throws Exception {
        JSONArray app=new JSONArray().put(entry("auto",false,1));
        for(int i=0;i<12;i++) app.put(entry("synthetic"+i, i<2 || paidEnabled, i<2 ? 0 : 1));
        return new JSONObject().put("app",app).put("chat",new JSONArray().put(entry("synthetic0",true,5))).toString();
    }
    public static void main(String[] args) throws Exception {
        QoderAuth.SignIn old = new QoderAuth.SignIn(); old.uid="uid"; old.machineId="machine"; old.access="old-access"; old.refresh="old-refresh"; old.deviceToken="old-access"; old.deviceRefresh="old-refresh"; old.deviceChat=true; old.expiresAt=1;
        Transport first=new Transport(200,listing(false));
        QoderAuth.Catalog initial=QoderAuth.readCatalog(old,first);
        check(initial.freeView && initial.models.length()==2 && first.last.disconnected,"free view");
        Transport rotation=new Transport(200,"{\"token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}");
        long now=System.currentTimeMillis(); QoderAuth.SignIn next=QoderAuth.refreshCandidate(old,rotation);
        check(rotation.last.getURL().getPath().endsWith("/deviceToken/refresh"),"device endpoint");
        check(new JSONObject(rotation.last.sent.toString("UTF-8")).getString("refresh_token").equals("old-refresh"),"refresh request");
        check(next!=old && next.access.equals("new-access") && next.deviceToken.equals(next.access) && next.deviceRefresh.equals(next.refresh),"rotated candidate");
        check(old.access.equals("old-access") && old.refresh.equals("old-refresh") && old.expiresAt==1,"old untouched");
        check(next.expiresAt>=now+3599000 && next.expiresAt<=System.currentTimeMillis()+3601000,"device seconds expiry");
        QoderAuth.Catalog refreshed=QoderAuth.readCatalog(next,new Transport(200,listing(true)));
        check(!refreshed.freeView && refreshed.models.length()==12 && refreshed.meta.getJSONObject("synthetic0").getString("display_name").equals("name-synthetic0"),"full catalog");
        QoderAuth.Catalog still=QoderAuth.readCatalog(next,new Transport(200,listing(false)));
        check(still.freeView && still.models.length()==2,"still free stays free");
        Transport bad=new Transport(401,"secret-response");
        try { QoderAuth.refreshCandidate(old,bad); throw new AssertionError("status accepted"); } catch(QoderAuth.QoderHttpException e) { check(e.status==401 && !e.toString().contains("secret"),"sanitized status"); }
        check(bad.last.disconnected && old.access.equals("old-access"),"failure isolated");
        try { QoderAuth.refreshCandidate(old,new Transport(200,"{\"token\":\"secret-incomplete\"}")); throw new AssertionError("incomplete accepted"); } catch(IOException e) { check(!e.toString().contains("secret"),"sanitized pair"); }
        try { QoderAuth.readCatalog(old,new Transport(200,"{\"app\":[{\"key\":\"a\",\"enable\":true},{\"key\":\"bad\"}]}")); throw new AssertionError("invalid accepted"); } catch(IOException expected) { }
        JSONObject source=new JSONObject().put("app",new JSONArray().put(entry("a",true,1)).put(entry("b",true,1))).put("chat",new JSONArray().put(entry("a",false,1)).put(entry("c",true,1)));
        QoderAuth.Catalog ordered=QoderAuth.readCatalog(old,new Transport(200,source.toString()));
        check(ordered.models.toString().equals("[\"a\",\"c\",\"b\"]") && ordered.meta.getJSONObject("a").getBoolean("enable"),"order dedupe app wins");
        QoderAuth.SignIn job=new QoderAuth.SignIn(); job.refresh="job-old"; job.deviceToken="unchanged"; job.deviceRefresh="unchanged-refresh";
        Transport jt=new Transport(200,"{\"token\":\"job-new\",\"refresh_token\":\"job-new-refresh\",\"expires_in\":3600000}");
        now=System.currentTimeMillis(); QoderAuth.SignIn jn=QoderAuth.refreshCandidate(job,jt);
        check(jt.last.getURL().getPath().endsWith("/jobToken/refresh") && jn.deviceToken.equals("unchanged") && jn.expiresAt>=now+3599000 && jn.expiresAt<=System.currentTimeMillis()+3601000,"job refresh and milliseconds");
        System.out.println("QoderCatalogIntegrityHttpTest OK");
    }
}
