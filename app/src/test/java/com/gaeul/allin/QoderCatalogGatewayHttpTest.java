package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only Qoder catalog and refresh integration. */
public final class QoderCatalogGatewayHttpTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static JSONObject account(String id,boolean expired){return new JSONObject().put("id",id).put("token","old-"+id).put("refresh","refresh-"+id).put("deviceToken","old-"+id).put("deviceRefresh","refresh-"+id).put("deviceChat",true).put("uid",id).put("machineId",id).put("expiresAt",expired?1:System.currentTimeMillis()+600000);}
    static final class Fixture implements AutoCloseable {
        final AtomicInteger reads=new AtomicInteger(),refreshes=new AtomicInteger();
        volatile boolean failRefresh,failCatalog,free,perAccount;
        volatile String failUser, emptyUser; volatile boolean requirePersisted;
        final File file; final GatewayServer gateway; final com.sun.net.httpserver.HttpServer upstream; final NanoHTTPD proxy;
        Fixture(JSONArray accounts, boolean broken) throws Exception {
            file=broken?Files.createTempDirectory("qoder-http-broken").resolve("missing/config.json").toFile():Files.createTempDirectory("qoder-http").resolve("config.json").toFile();
            JSONObject p=new JSONObject().put("id","qoder_cn").put("protocol","qoder").put("enabled",true).put("accounts",accounts).put("models",new JSONArray().put("old-model")).put("modelMeta",new JSONObject().put("old-model",new JSONObject().put("key","old-model")));
            JSONObject config=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture")).put("providers",new JSONArray().put(p));
            gateway=RoutingSetupOfflineTest.server(config);
            Field f=GatewayServer.class.getDeclaredField("configFile");f.setAccessible(true);f.set(gateway,file);
            f=GatewayServer.class.getDeclaredField("qoderSyncLock");f.setAccessible(true);f.set(gateway,new Object());
            if(!broken) AtomicConfigFile.write(file,config.toString());
            upstream=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/", exchange -> {
                boolean refresh=exchange.getRequestURI().getPath().contains("refresh");
                if(refresh) refreshes.incrementAndGet(); else reads.incrementAndGet();
                int status=(refresh?failRefresh:failCatalog)?500:200;
                String wire; String who=exchange.getRequestHeaders().getFirst("Cosy-User");
                if (!refresh && who != null && who.equals(failUser)) status=500;
                if (!refresh && requirePersisted) {
                    try {
                        JSONObject disk=new JSONObject(new String(Files.readAllBytes(file.toPath()),"UTF-8"));
                        if (!"new-token".equals(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).optString("token"))) status=500;
                    } catch(Exception e) { status=500; }
                }
                if(refresh) wire="{\"token\":\"new-token\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}";
                else { JSONArray items=new JSONArray(); for(int i=0;i<(who != null && who.equals(emptyUser)?0:12);i++)items.put(new JSONObject().put("key",(perAccount ? who+"-m"+i : "m"+i)).put("enable",!free || i<2).put("price_factor",i<2?0:1)); wire=new JSONObject().put("app",items).toString(); }
                byte[] bytes=wire.getBytes("UTF-8"); exchange.sendResponseHeaders(status,bytes.length); try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}
            }); upstream.start();
            gateway.setQoderConnectionsForTests(url -> (HttpURLConnection)new URL("http://127.0.0.1:"+upstream.getAddress().getPort()+(url.contains("deviceToken/refresh")?"/refresh":"/models")).openConnection());
            proxy=new NanoHTTPD("127.0.0.1",0){public Response serve(IHTTPSession s){return gateway.serve(s);}};proxy.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        int sync() throws Exception {HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+proxy.getListeningPort()+"/api/auth/qoder_cn/sync-models").openConnection();c.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());c.setConnectTimeout(3000);c.setReadTimeout(10000);try{int status=c.getResponseCode(); if(status!=200){InputStream in=c.getErrorStream(); if(in!=null)System.err.println("sync status "+status+" "+new String(in.readAllBytes(),"UTF-8"));} return status;}finally{c.disconnect();}}
        JSONObject provider() throws Exception{return ResponsesGatewayOfflineTest.config(gateway).getJSONArray("providers").getJSONObject(0);}
        public void close(){proxy.stop();upstream.stop(0);}
    }
    public static void main(String[] args)throws Exception {
        try(Fixture f=new Fixture(new JSONArray().put(account("a",true)),false)){f.requirePersisted=true;check(f.sync()==200,"sync");check(f.refreshes.get()==1 && f.reads.get()==1,"refresh before catalog");check(f.provider().getJSONArray("models").length()==12,"12 models");check(new JSONObject(new String(Files.readAllBytes(f.file.toPath()),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token").equals("new-token"),"persist token");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",true)),false)){f.failRefresh=true;check(f.sync()!=200,"refresh failure");check(f.provider().getJSONArray("models").getString(0).equals("old-model"),"old catalog");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",true)),false)){f.failCatalog=true;check(f.sync()!=200,"catalog failure");check(f.provider().getJSONArray("accounts").getJSONObject(0).getString("token").equals("new-token"),"rotated token retained");check(f.provider().getJSONArray("models").getString(0).equals("old-model"),"catalog retained");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",true)),true)){check(f.sync()!=200,"write failure");check(f.reads.get()==0,"no use unpersisted token");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false)),false)){f.free=true;check(f.sync()==200 && f.sync()==200,"free sync");check(f.refreshes.get()==1,"free throttle");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",true)),false)){ExecutorService x=Executors.newFixedThreadPool(2);try{Future<Integer> a=x.submit(f::sync),b=x.submit(f::sync);check(a.get()==200 && b.get()==200,"concurrent sync");check(f.refreshes.get()==1,"single refresh");}finally{x.shutdownNow();}}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false)).put(account("off",false).put("off",true)),false)){check(f.sync()==200 && f.reads.get()==1,"off excluded");}
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false)).put(account("b",false)),false)) {
            f.perAccount=true; check(f.sync()==200,"multiple accounts");
            JSONObject p=f.provider(); check(p.getJSONArray("models").length()==24,"account union");
            for(int i=0;i<2;i++){JSONObject a=p.getJSONArray("accounts").getJSONObject(i); String m=a.getString("id")+"-m0"; check(a.getJSONObject("modelMeta").getJSONObject(m).getString("key").equals(m),"account metadata");}
            java.util.List<?> routes=RoutingSetupOfflineTest.candidates(f.gateway,"qoder_cn/b-m0");
            check(!routes.isEmpty(),"account model route");
            Field selected=routes.get(0).getClass().getDeclaredField("account"); selected.setAccessible(true);
            check("b".equals(((JSONObject)selected.get(routes.get(0))).optString("id")),"route chooses owning account");
            f.failUser="b"; check(f.sync()==200 && f.provider().getJSONArray("models").length()==24,"partial failure preserves account directory");
            f.failUser=null; f.emptyUser="b"; check(f.sync()==200 && f.provider().getJSONArray("models").length()==24,"empty account response preserves prior directory");
        }
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false)),false)) {
            check(!RoutingSetupOfflineTest.candidates(f.gateway,"qoder_cn/old-model").isEmpty(),"legacy route");
        }
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false).put("off",true)).put(account("b",false)),false)) {
            check(RoutingSetupOfflineTest.candidates(f.gateway,"qoder_cn/old-model").isEmpty(),"ambiguous legacy catalog cannot transfer to another account");
        }
        try(Fixture f=new Fixture(new JSONArray().put(account("a",false).put("enabled",false).put("customSetting","keep").put("models",new JSONArray().put("old-model"))),false)) {
            Method m=GatewayServer.class.getDeclaredMethod("replaceQoderAuthorization",JSONObject.class,QoderAuth.SignIn.class); m.setAccessible(true);
            JSONObject before=new JSONObject(f.provider().getJSONArray("accounts").getJSONObject(0).toString());
            QoderAuth.SignIn si=new QoderAuth.SignIn(); si.uid="other"; si.access="fresh"; si.refresh="fresh-refresh";
            String disk=new String(Files.readAllBytes(f.file.toPath()),"UTF-8");
            check(!(Boolean)m.invoke(f.gateway,before,si),"different uid rejected");
            check(disk.equals(new String(Files.readAllBytes(f.file.toPath()),"UTF-8")),"different uid leaves disk unchanged");
            si.uid="a"; si.deviceChat=true; si.deviceToken="fresh"; si.deviceRefresh="fresh-refresh"; si.machineId="new-machine"; si.expiresAt=9999999999999L;
            check((Boolean)m.invoke(f.gateway,before,si),"same uid reauthorized");
            JSONObject a=f.provider().getJSONArray("accounts").getJSONObject(0);
            check(a.getString("id").equals("a") && !a.getBoolean("enabled") && a.getString("customSetting").equals("keep"),"settings preserved");
            check(a.getString("token").equals("fresh") && a.getJSONArray("models").getString(0).equals("old-model"),"credentials updated, catalog retained");
            check(new JSONObject(new String(Files.readAllBytes(f.file.toPath()),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("token").equals("fresh"),"reauthorization persisted");
        }
        System.out.println("QoderCatalogGatewayHttpTest PASS");
    }
}
