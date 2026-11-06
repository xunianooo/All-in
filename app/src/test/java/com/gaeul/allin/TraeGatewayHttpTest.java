package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real loopback HTTP on both sides of the gateway; credentials and upstream are synthetic. */
public final class TraeGatewayHttpTest {
    static final String TOKEN="synthetic-trae-token", AUTH="fixture-auth";
    static final String END="event: token_usage\ndata: {\"input_tokens\":4,\"output_tokens\":2}\n\nevent: done\ndata: {}\n\n";
    static void check(boolean b,String msg) { if(!b) throw new AssertionError(msg); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static final class Upstream implements AutoCloseable {
        final ServerSocket server; final Thread thread; final AtomicInteger count=new AtomicInteger();
        volatile String mode="ok", body="", path=""; volatile Throwable failure;
        volatile CountDownLatch wrote=new CountDownLatch(1), release=new CountDownLatch(1);
        Upstream() throws Exception {
            server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(()->{while(!server.isClosed()) try { final Socket s=server.accept(); new Thread(()->handle(s)).start(); } catch(IOException e) {if(!server.isClosed()) failure=e;} });
            thread.setDaemon(true); thread.start();
        }
        void handle(Socket s) {
            try(Socket c=s) {
                c.setSoTimeout(10000); InputStream in=c.getInputStream(); ByteArrayOutputStream h=new ByteArrayOutputStream(); int state=0,x;
                while(state<4 && (x=in.read())!=-1) {h.write(x); state=state==0&&x==13?1:state==1&&x==10?2:state==2&&x==13?3:state==3&&x==10?4:0; if(h.size()>65536)throw new IOException("headers");}
                String header=h.toString("UTF-8"); path=header.split(" ")[1]; int len=0;
                for(String line:header.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(line.substring(15).trim());
                byte[] data=new byte[len]; int pos=0,n; while(pos<len && (n=in.read(data,pos,len-pos))!=-1)pos+=n; if(pos!=len)throw new EOFException();
                body=new String(data,StandardCharsets.UTF_8); count.incrementAndGet(); String m=mode;
                int status=m.equals("401")?401:m.equals("400")?400:200;
                boolean json=m.equals("json")||status!=200;
                String text=m.equals("rate")?"event: error\ndata: {\"code\":4011,\"message\":\"requests have exceeded the rate limit\"}\n\nevent: done\ndata: {}\n\n":json?"{\"error\":{\"message\":\"synthetic failure\"}}":m.equals("truncated")?"event: output\ndata: {\"content\":\"partial\"}\n\n":m.equals("error")?"event: output\ndata: {\"content\":\"partial\"}\n\nevent: error\ndata: {\"code\":1}\n\n":m.equals("tool")?"event: output\ndata: {\"tool_calls\":[{\"id\":\"call_fixture_123\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}\n\n"+END:"event: output\ndata: {\"content\":\"hello\"}\n\n";
                OutputStream out=c.getOutputStream(); out.write(bytes("HTTP/1.1 "+status+" Fixture\r\nContent-Type: "+(json?"application/json":"text/event-stream")+"\r\nConnection: close\r\n\r\n"));out.write(bytes(text));out.flush();wrote.countDown();
                if(m.equals("hold")) {release.await(10,TimeUnit.SECONDS); try {out.write(bytes(END));out.flush();}catch(IOException disconnected) {}}
                else if(m.equals("ok")) {out.write(bytes(END));out.flush();}
            } catch(Throwable e) {if(!server.isClosed())failure=e;}
        }
        public void close() throws Exception { release.countDown(); server.close();thread.join(3000);if(failure!=null)throw new AssertionError("upstream fixture",failure); }
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        Gateway(JSONObject config,Path dir,Upstream upstream) throws Exception {
            super("127.0.0.1",0);inner=RoutingSetupOfflineTest.server(config);
            ResponsesGatewayOfflineTest.set(inner,"configFile",dir.resolve("config.json").toFile());
            ResponsesGatewayOfflineTest.set(inner,"groupTurns",new ConcurrentHashMap<String,Integer>());
            Files.write(dir.resolve("config.json"),bytes(config.toString()));
            inner.setTraeOriginForTests("http://127.0.0.1:"+upstream.server.getLocalPort());start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        public Response serve(IHTTPSession s){return inner.serve(s);}
        protected boolean useGzipWhenAccepted(Response r){return inner.useGzipWhenAccepted(r);}
        public void close(){stop();inner.retireForRestore();}
    }
    static JSONObject config(Upstream fallback) throws Exception {
        JSONObject meta=new JSONObject().put("function","chat_v3").put("modelName","fixture__dev").put("multimodal",true).put("maxOutput",4096).put("levels",new JSONArray("[\"light\",\"high\"]"));
        JSONObject modelMeta=new JSONObject().put("fixture",meta);
        JSONObject account=TraeDevice.create().put("id","account-fixture").put("generation","generation-fixture").put("token",TOKEN).put("refresh","synthetic-refresh").put("expires",System.currentTimeMillis()+86400000L).put("uid","fixture-uid").put("name","fixture").put("enabled",true).put("models",new JSONArray().put("fixture")).put("modelMeta",modelMeta);
        JSONObject trae=new JSONObject().put("id","trae-cn").put("status","ported").put("protocol","trae").put("enabled",true).put("baseUrl","http://evil.invalid").put("keys",new JSONArray().put(TOKEN)).put("models",new JSONArray().put("fixture")).put("modelMeta",modelMeta).put("accounts",new JSONArray().put(account));
        JSONObject backup=new JSONObject().put("id","backup").put("protocol","openai").put("enabled",true).put("baseUrl","http://127.0.0.1:"+fallback.server.getLocalPort()+"/v1").put("keys",new JSONArray().put("synthetic-backup")).put("models",new JSONArray().put("fixture"));
        return new JSONObject().put("server",new JSONObject().put("authKey",AUTH)).put("setup",new JSONObject().put("done",true)).put("privacy",new JSONObject().put("enabled",false)).put("routing",new JSONObject().put("groupsEnabled",true)).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4)).put("providers",new JSONArray().put(trae).put(backup)).put("groups",new JSONArray().put(new JSONObject().put("id","fixture").put("strategy","order").put("targets",new JSONArray().put("trae-cn/fixture").put("backup/fixture"))));
    }
    static JSONObject chat(String model,boolean stream) {return new JSONObject().put("model",model).put("stream",stream).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));}
    static final class Result {int status;String body;}
    static Result http(Gateway g,String path,String method,JSONObject body,boolean auth) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection(); c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestMethod(method);c.setRequestProperty("Accept-Encoding","identity");if(auth)c.setRequestProperty("Authorization","Bearer "+AUTH);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(bytes(body.toString()));}}
        Result r=new Result();r.status=c.getResponseCode();InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null)try(InputStream i=in){byte[] b=new byte[4096];int n;while((n=i.read(b))!=-1)out.write(b,0,n);}r.body=out.toString("UTF-8");c.disconnect();return r;
    }
    static Result post(Gateway g,String path,JSONObject body) throws Exception{return http(g,path,"POST",body,true);}
    static void successes(Gateway g,Upstream u) throws Exception {
        Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));check(r.status==200&&r.body.contains("hello"),"chat: "+r.status+" "+r.body);check(u.path.equals("/api/agent/v3/llm_utils_chat"),"fixed origin path");
        JSONArray recent=UsageLedger.summary(1,"model").getJSONArray("recent");boolean found=false;for(int i=0;i<recent.length();i++){JSONObject row=recent.getJSONObject(i);if("trae-cn".equals(row.optString("prov"))&&"known".equals(row.optString("usageSource"))){check(row.optInt("in",-1)==4&&row.optInt("out",-1)==2,"usage values");check("unknown".equals(row.optString("cacheSource"))&&row.isNull("cacheRead"),"unknown cache not zero");found=true;break;}}check(found,"known ledger row");
        for(String key:new String[]{"reasoning_effort","reasoningEffort"}) for(boolean streaming:new boolean[]{false,true}) {
            for(String effort:new String[]{"medium","low","high","xhigh"}) {
                int before=u.count.get();Result effortResult=post(g,"/v1/chat/completions",chat("trae-cn/fixture",streaming).put(key,effort));
                check(effortResult.status==200&&u.count.get()==before+1,"effort reaches upstream once: "+key+" "+effort+" "+effortResult.body);
                JSONObject effortWire=new JSONObject(u.body);String expected=effort.equals("low")?"light":effort.equals("high")?"high":null;
                check(expected==null?!effortWire.has("reasoning_effort"):expected.equals(effortWire.optString("reasoning_effort")),"effort wire mapping "+effort);
                check(!effortWire.has("reasoningEffort"),"no camelCase effort on wire");
                check(!streaming||effortResult.body.contains("[DONE]"),"effort stream terminal");
            }
        }
        int invalidBefore=u.count.get();Result invalidEffort=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false).put("reasoning_effort",7));
        check(invalidEffort.status==400&&u.count.get()==invalidBefore,"invalid effort type stays local");
        u.mode="hold";u.wrote=new CountDownLatch(1);u.release=new CountDownLatch(1);
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+"/v1/chat/completions").openConnection();c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer "+AUTH);c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(bytes(chat("trae-cn/fixture",true).toString()));}check(c.getResponseCode()==200,"stream status");byte[] first=new byte[2048];int n;ByteArrayOutputStream early=new ByteArrayOutputStream();while(!early.toString("UTF-8").contains("hello")){n=c.getInputStream().read(first);check(n>0,"stream ended before content");early.write(first,0,n);check(early.size()<65536,"stream prefix bounded");}check(u.release.getCount()==1,"stream before upstream terminal");u.release.countDown();ByteArrayOutputStream rest=new ByteArrayOutputStream();while((n=c.getInputStream().read(first))!=-1)rest.write(first,0,n);check(rest.toString("UTF-8").contains("[DONE]"),"stream terminal");c.disconnect();u.mode="ok";
        JSONObject image=chat("trae-cn/fixture",false);image.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,YWJj"))));r=post(g,"/v1/chat/completions",image);check(r.status==200,"image status");String raw=u.body;check(raw.contains("YWJj"),"image bytes retained");JSONObject captured=new JSONObject(raw); String imageUrl=captured.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url");byte[] decoded=Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',')+1));check(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(decoded),MessageDigest.getInstance("SHA-256").digest(bytes("abc"))),"image hash");
        JSONObject unsupported=chat("trae-cn/fixture",false).put("top_p",0.5);int before=u.count.get();r=post(g,"/v1/chat/completions",unsupported);check(r.status>=400&&u.count.get()==before,"unsupported parameter local");
        JSONObject badImage=chat("trae-cn/fixture",false);badImage.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","file:///invalid.png"))));before=u.count.get();r=post(g,"/v1/chat/completions",badImage);check(r.status>=400&&u.count.get()==before,"unsupported image local");
        JSONObject etaHistory=chat("trae-cn/fixture",false);etaHistory.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","previous answer").put("reasoning_content","previous reasoning")).put(new JSONObject().put("role","user").put("content","next question"));
        r=post(g,"/v1/chat/completions",etaHistory);check(r.status==200,"Eta reasoning history accepted: "+r.body);
        u.mode="tool";JSONObject tools=new JSONObject("{\"model\":\"trae-cn/fixture\",\"messages\":[{\"role\":\"user\",\"content\":\"run\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}}]}");r=post(g,"/v1/chat/completions",tools);check(r.status==200&&r.body.contains("call_fixture_123"),"chat tool id");
        JSONObject an=new JSONObject("{\"model\":\"trae-cn/fixture\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"run\"}],\"tools\":[{\"name\":\"run\",\"input_schema\":{\"type\":\"object\"}}]}");r=post(g,"/v1/messages",an);check(r.status==200&&r.body.contains("call_fixture_123"),"anthropic tool id: "+r.status+" "+r.body);
        an.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","call_fixture_123").put("name","run").put("input",new JSONObject())))).put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","call_fixture_123").put("content","done"))));u.mode="ok";r=post(g,"/v1/messages",an);check(r.status==200&&u.body.contains("call_fixture_123"),"anthropic tool result roundtrip");u.mode="tool";
        JSONObject responses=new JSONObject("{\"model\":\"trae-cn/fixture\",\"input\":\"run\",\"tools\":[{\"type\":\"function\",\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}]}");r=post(g,"/v1/responses",responses);check(r.status==200&&r.body.contains("call_fixture_123"),"responses tool id: "+r.status+" "+r.body);
        responses.put("input",new JSONArray().put(new JSONObject().put("type","message").put("role","user").put("content","run")).put(new JSONObject().put("type","function_call").put("call_id","call_fixture_123").put("name","run").put("arguments","{}")).put(new JSONObject().put("type","function_call_output").put("call_id","call_fixture_123").put("output","done")));u.mode="ok";r=post(g,"/v1/responses",responses);check(r.status==200&&u.body.contains("call_fixture_123"),"responses tool result roundtrip");
        u.mode="ok";
    }
    static void failures(Gateway g,Upstream u,Upstream backup) throws Exception {
        for(String mode:new String[]{"401","400","json","truncated","error"}) {u.mode=mode;int before=u.count.get(),fallback=backup.count.get();Result r=post(g,"/v1/chat/completions",chat("group/fixture",false));check(r.status>=400,"failure status "+mode+" "+r.status);check(u.count.get()==before+1&&backup.count.get()==fallback,"no replay or fallback "+mode);}
        u.mode="ok";check(post(g,"/v1/chat/completions",chat("trae-cn/fixture",false)).status==200,"admission released after failures");
        u.mode="rate";int before=u.count.get();Result rate=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));check(rate.status==429 && rate.body.contains("4011"),"SSE first error mapped before HTTP success");check(u.count.get()==before+1,"single rate request");
        rate=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));check(rate.status==429 && u.count.get()==before+1,"account rate cooldown no network");
    }
    static void api(Gateway g,Upstream u,JSONObject config) throws Exception {
        check(RoutingSetupOfflineTest.status(g.inner,"192.168.1.2","/api/auth/trae-cn/init","GET","",null)==401,"init remote auth");Result r=http(g,"/api/auth/trae-cn/init","GET",null,true);check(r.status==200&&new JSONObject(r.body).getString("url").startsWith("https://"),"init URL");
        int before=u.count.get();r=post(g,"/api/trae/checkin",new JSONObject());check(r.status==400,"checkin missing account");
        config.getJSONArray("providers").getJSONObject(0).put("accounts",new JSONArray());r=post(g,"/api/auth/trae-cn/sync-models",new JSONObject());check(r.status>=400||!new JSONObject(r.body).optBoolean("ok",true),"zero account sync fails");check(u.count.get()==before,"control calls no upstream");
        check(post(g,"/api/auth/trae-cn/add",new JSONObject().put("token","synthetic")).status==400,"token-only import rejected");
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("trae-gateway-http");UsageLedger.init(dir.toFile());
        try(Upstream u=new Upstream();Upstream fallback=new Upstream()){JSONObject cfg=config(fallback);try(Gateway g=new Gateway(cfg,dir,u)){successes(g,u);failures(g,u,fallback);api(g,u,cfg);}}
        System.out.println("TraeGatewayHttpTest OK");
    }
}
