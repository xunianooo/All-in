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
import com.gaeul.allin.json.*;

/** Real loopback HTTP on both sides of the gateway; credentials and upstream are synthetic. */
public final class TraeGatewayHttpTest {
    static final String TOKEN="synthetic-trae-token", AUTH="fixture-auth";
    static final String END="event: token_usage\ndata: {\"input_tokens\":4,\"output_tokens\":2}\n\nevent: done\ndata: {}\n\n";
    static void check(boolean b,String msg) { if(!b) throw new AssertionError(msg); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static final class Upstream implements AutoCloseable {
        final ServerSocket server; final Thread thread; final AtomicInteger count=new AtomicInteger();
        /** Answers this fixture saw the gateway close while it was still writing them. */
        final AtomicInteger closedByGateway=new AtomicInteger();
        volatile String mode="ok", body="", path=""; volatile Throwable failure;
        volatile CountDownLatch wrote=new CountDownLatch(1), release=new CountDownLatch(1);
        volatile int failFirst=0;
        private synchronized boolean failing() { if(failFirst>0){failFirst--;return true;} return false; }
        final java.util.List<String> seen=java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        final java.util.List<String> seenHeaders=java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
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
                body=new String(data,StandardCharsets.UTF_8); int index=count.incrementAndGet(); seen.add(body); seenHeaders.add(header); String m=mode;
                boolean failing=failing(), wrong=m.equals("wrong4001")||m.equals("wrong4008"), sseWrong=m.equals("sse4001")||m.equals("sse1005")||m.equals("sseusage4001");
                // A failure the plugin reads by the code Trae named rather than by the status that carried
                // it: jw = the answer asks another candidate, jn = it is final. The status is in the name.
                int named=0; Integer namedStatus=null;
                if(m.startsWith("jw")||m.startsWith("jn")) {
                    String[] pieces=m.split("_");
                    namedStatus=Integer.valueOf(Integer.parseInt(pieces[0].substring(2)));
                    named="quota".equals(pieces[1])?0:Integer.parseInt(pieces[1]);
                }
                boolean namedReply=namedStatus!=null && (m.startsWith("jn") || failing);
                boolean answered=m.startsWith("jw") && !failing;
                int status=namedReply?namedStatus.intValue():wrong&&failing?400:m.equals("401")?401:m.equals("400")?400:200;
                boolean json=namedReply||(wrong&&failing)||m.equals("json")||status!=200;
                String text=namedReply?(named>0?"{\"error\":{\"code\":"+named+",\"message\":\"synthetic function\"}}":"{\"error\":{\"message\":\"quota exceeded\"}}")
                        :wrong&&failing?"{\"error\":{\"code\":"+(m.equals("wrong4001")?4001:4008)+",\"message\":\"synthetic function\"}}"
                        :m.equals("sseusage4001")?(failing?"event: token_usage\ndata: {\"input_tokens\":111,\"output_tokens\":7}\n\nevent: error\ndata: {\"code\":4001,\"message\":\"synthetic function\"}\n\n":"event: output\ndata: {\"content\":\"hello\"}\n\nevent: done\ndata: {}\n\n")
                        :sseWrong&&failing?"event: error\ndata: {\"code\":"+(m.equals("sse4001")?4001:1005)+",\"message\":\"synthetic function\"}\n\n"
                        :m.equals("late4001")?"event: output\ndata: {\"content\":\"partial\"}\n\nevent: error\ndata: {\"code\":4001,\"message\":\"synthetic function\"}\n\n"
                        :m.equals("rate")?"event: error\ndata: {\"code\":4011,\"message\":\"requests have exceeded the rate limit\"}\n\nevent: done\ndata: {}\n\n"
                        :json?"{\"error\":{\"message\":\"synthetic failure\"}}"
                        :m.equals("truncated")?"event: output\ndata: {\"content\":\"partial\"}\n\n"
                        :m.equals("error")?"event: output\ndata: {\"content\":\"partial\"}\n\nevent: error\ndata: {\"code\":1}\n\n"
                        :m.equals("tool")?"event: output\ndata: {\"tool_calls\":[{\"id\":\"call_fixture_123\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}\n\n"+END
                        :"event: output\ndata: {\"content\":\"hello\"}\n\n";
                OutputStream out=c.getOutputStream(); out.write(bytes("HTTP/1.1 "+status+" Fixture\r\nContent-Type: "+(json?"application/json":"text/event-stream")+"\r\nConnection: close\r\n\r\n"));out.write(bytes(text));out.flush();wrote.countDown();
                if(m.equals("hold")) {release.await(10,TimeUnit.SECONDS); try {out.write(bytes(END));out.flush();}catch(IOException disconnected) {}}
                else if(m.equals("cancel")) {
                    // The answer keeps arriving until the client's own cancellation closes this socket.
                    release.await(15,TimeUnit.SECONDS);
                    try { for(int i=0;i<400;i++){out.write(bytes("event: output\ndata: {\"content\":\"drip\"}\n\n"));out.flush();Thread.sleep(10);} }
                    catch(IOException gatewayClosed) { }
                    boolean closed=false;
                    try { c.setSoTimeout(5000); while(in.read()!=-1) { } closed=true; }
                    catch(SocketTimeoutException stillOpen) { }
                    catch(IOException reset) { closed=true; }
                    if(closed) closedByGateway.incrementAndGet();
                }
                else if(m.equals("ok")||answered||((wrong||sseWrong)&&!failing&&!m.equals("sseusage4001"))) {out.write(bytes(END));out.flush();}
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
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection(); c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestMethod(method);c.setRequestProperty("Accept-Encoding","identity");if(auth)c.setRequestProperty("Authorization","Bearer "+(path.startsWith("/api/")?GatewayManagementCredential.get():AUTH));
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
        JSONObject unsupported=chat("trae-cn/fixture",false).put("response_format",new JSONObject().put("type","json_schema"));int before=u.count.get();r=post(g,"/v1/chat/completions",unsupported);check(r.status>=400&&u.count.get()==before,"unsupported parameter local");
        JSONObject badImage=chat("trae-cn/fixture",false);badImage.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","file:///invalid.png"))));before=u.count.get();r=post(g,"/v1/chat/completions",badImage);check(r.status>=400&&u.count.get()==before,"unsupported image local");
        JSONObject etaHistory=chat("trae-cn/fixture",false);etaHistory.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","previous answer").put("reasoning_content","previous reasoning")).put(new JSONObject().put("role","user").put("content","next question"));
        r=post(g,"/v1/chat/completions",etaHistory);check(r.status==200,"Eta reasoning history accepted: "+r.body);
        u.mode="tool";JSONObject tools=new JSONObject("{\"model\":\"trae-cn/fixture\",\"messages\":[{\"role\":\"user\",\"content\":\"run\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}}]}");r=post(g,"/v1/chat/completions",tools);check(r.status==200&&r.body.contains("call_fixture_123"),"chat tool id");
        JSONObject an=new JSONObject("{\"model\":\"trae-cn/fixture\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"run\"}],\"tools\":[{\"name\":\"run\",\"input_schema\":{\"type\":\"object\"}}]}");r=post(g,"/v1/messages",an);check(r.status==200&&r.body.contains("call_fixture_123"),"anthropic tool id: "+r.status+" "+r.body);
        an.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","call_fixture_123").put("name","run").put("input",new JSONObject())))).put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","call_fixture_123").put("content","done"))));u.mode="ok";r=post(g,"/v1/messages",an);check(r.status==200&&u.body.contains("call_fixture_123"),"anthropic tool result roundtrip");u.mode="tool";
        JSONObject responses=new JSONObject("{\"model\":\"trae-cn/fixture\",\"input\":\"run\",\"tools\":[{\"type\":\"function\",\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}]}");r=post(g,"/v1/responses",responses);check(r.status==200&&r.body.contains("call_fixture_123"),"responses tool id: "+r.status+" "+r.body);
        responses.put("input",new JSONArray().put(new JSONObject().put("type","message").put("role","user").put("content","run")).put(new JSONObject().put("type","function_call").put("call_id","call_fixture_123").put("name","run").put("arguments","{}")).put(new JSONObject().put("type","function_call_output").put("call_id","call_fixture_123").put("output","done")));u.mode="ok";r=post(g,"/v1/responses",responses);check(r.status==200&&u.body.contains("call_fixture_123"),"responses tool result roundtrip");
        // A summary turn drops the tools: the past call still goes out, as Trae's text form.
        JSONObject summary=new JSONObject("{\"model\":\"trae-cn/fixture\",\"messages\":[{\"role\":\"user\",\"content\":\"run\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_fixture_123\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call_fixture_123\",\"content\":\"done\"}]}");
        r=post(g,"/v1/chat/completions",summary);check(r.status==200,"tool history without tools: "+r.status+" "+r.body);
        check(u.body.contains("<tool_call>")&&u.body.contains("Result of run (call call_fixture_123):"),"text form went upstream: "+u.body);
        u.mode="ok";
    }
    static String sessionRequest(Gateway g, Upstream u, JSONObject request, String session) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+"/v1/chat/completions").openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestProperty("Authorization","Bearer "+AUTH);
        c.setRequestProperty("Content-Type","application/json"); if(session!=null)c.setRequestProperty("x-session-id",session);
        c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(bytes(request.toString()));}
        int status=c.getResponseCode();try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){while(in.read()!=-1){}}
        c.disconnect();check(status==200,"session request status "+status);return new JSONObject(u.body).getString("session_id");
    }
    static void sessions(Path dir, Upstream u, Upstream fallback) throws Exception {
        JSONObject cfg=config(fallback), other=config(fallback);
        other.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("id","other-account");
        Path d1=Files.createDirectory(dir.resolve("session-a")), d2=Files.createDirectory(dir.resolve("session-b"));
        try(Gateway a=new Gateway(cfg,d1,u); Gateway b=new Gateway(other,d2,u)) {
            JSONObject first=chat("trae-cn/fixture",false), next=chat("trae-cn/fixture",false);
            next.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","answer")).put(new JSONObject().put("role","user").put("content","next"));
            String named=sessionRequest(a,u,first,"conversation-a");
            check(named.equals(sessionRequest(a,u,next,"conversation-a")),"named session continues across turns");
            check(!named.equals(sessionRequest(a,u,first,"conversation-b")),"named conversations separated");
            check(!named.equals(sessionRequest(b,u,first,"conversation-a")),"accounts separated");
            String fallbackId=sessionRequest(a,u,first,null);
            check(fallbackId.equals(sessionRequest(a,u,next,null)),"first user fallback continues across turns");
            check(!fallbackId.equals(sessionRequest(b,u,first,null)),"fallback accounts separated");
            JSONObject empty=chat("trae-cn/fixture",false).put("messages",new JSONArray());
            String one=sessionRequest(a,u,empty,null), two=sessionRequest(a,u,empty,null);
            check(!one.equals(two),"unnamed empty history must get a fresh session per request");
            JSONObject blank=chat("trae-cn/fixture",false);blank.getJSONArray("messages").getJSONObject(0).put("content","   ");
            check(!sessionRequest(a,u,blank,null).equals(sessionRequest(a,u,blank,null)),"blank text is random");
            JSONObject imageOnly=chat("trae-cn/fixture",false);imageOnly.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,YWJj"))));
            check(!sessionRequest(a,u,imageOnly,null).equals(sessionRequest(a,u,imageOnly,null)),"image-only is random");
            try(Gateway restarted=new Gateway(cfg,d1,u)) {
                check(named.equals(sessionRequest(restarted,u,first,"conversation-a")),"named session survives gateway restart");
                check(fallbackId.equals(sessionRequest(restarted,u,first,null)),"fallback survives gateway restart");
            }
        }
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
    /** The code an error names, in a body or in a status: what moves on, what is final, and what is 502. */
    static void verdicts(Path dir,Upstream u,Upstream backup) throws Exception {
        try(Gateway g=new Gateway(config(backup),Files.createDirectory(dir.resolve("verdicts")),u)) {
            // Positive: a wrong-function code in a 200 body asks the next candidate, exactly once.
            for(String mode:new String[]{"jw200_4001","jw200_1005"}) {
                u.mode=mode;u.failFirst=1;int before=u.count.get();int seenStart=u.seen.size();
                Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
                check(r.status==200&&r.body.contains("hello"),"a named wrong function in a 200 body is retried: "+mode+" "+r.status+" "+r.body);
                check(u.count.get()==before+2,"exactly one next candidate: "+mode+" "+(u.count.get()-before));
                check("chat_v3".equals(wire(u,seenStart).optString("function"))&&"solo_work_lite".equals(wire(u,seenStart+1).optString("function")),"the plugin's candidate order after a named 200: "+mode);
            }
            // …and a stream after it answers too.
            u.mode="jw200_4001";u.failFirst=1;int before=u.count.get();
            Result streamed=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));
            check(streamed.status==200&&streamed.body.contains("[DONE]")&&u.count.get()==before+2,"a named 200 retries before HTTP success: "+streamed.status+" "+streamed.body);
            // Negative: nothing else a body names is replayed, re-asked of another provider, or answered
            // under a different code's status.
            String[][] final_={{"jn200_4008","429"},{"jn200_1001","401"},{"jn400_4008","429"},{"jn400_1001","401"},{"jn500_4001","500"},{"jn403_quota","429"},{"json","502"}};
            for(String[] c:final_) {
                u.mode=c[0];u.failFirst=0;int attempts=u.count.get(),fallback=backup.count.get();
                Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
                check(String.valueOf(r.status).equals(c[1]),"a named "+c[0]+" answers "+c[1]+": "+r.status+" "+r.body);
                check(u.count.get()==attempts+1,"no replay for "+c[0]+": "+(u.count.get()-attempts));
                check(backup.count.get()==fallback,"no other provider was asked for "+c[0]);
                check(!r.body.contains("计费")&&!r.body.contains("billed"),"no billing claim for "+c[0]+": "+r.body);
            }
            // Every candidate once, then the code's own status: 4001 is not a quota answer.
            u.mode="jw200_4001";u.failFirst=5;before=u.count.get();
            Result bounded=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(bounded.status==502&&u.count.get()==before+5,"a named 200 is bounded to one pass: "+bounded.status+" "+(u.count.get()-before));
            u.mode="ok";u.failFirst=0;
            check(post(g,"/v1/chat/completions",chat("trae-cn/fixture",false)).status==200,"a named failure leaves no cooldown");
        }
    }
    /** A client that goes away mid-answer: Trae's connection is closed and no other candidate is asked. */
    static void cancel(Path dir,Upstream u,Upstream backup) throws Exception {
        try(Gateway g=new Gateway(config(backup),Files.createDirectory(dir.resolve("cancel")),u)) {
            u.mode="cancel";u.release=new CountDownLatch(1);u.wrote=new CountDownLatch(1);u.closedByGateway.set(0);
            int before=u.count.get(),fallback=backup.count.get();
            Socket client=new Socket("127.0.0.1",g.getListeningPort());
            client.setSoLinger(true,0); client.setSoTimeout(10000);
            try {
                String body=chat("trae-cn/fixture",true).toString();
                byte[] raw=bytes(body);
                OutputStream out=client.getOutputStream();
                out.write(bytes("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:"+g.getListeningPort()+"\r\nAuthorization: Bearer "+AUTH+"\r\nContent-Type: application/json\r\nAccept-Encoding: identity\r\nContent-Length: "+raw.length+"\r\n\r\n"));
                out.write(raw); out.flush();
                InputStream in=client.getInputStream(); ByteArrayOutputStream seen=new ByteArrayOutputStream(); byte[] b=new byte[1024]; int n;
                while(!seen.toString("UTF-8").contains("hello")) { n=in.read(b); check(n>0,"stream ended before content"); seen.write(b,0,n); check(seen.size()<65536,"stream prefix bounded"); }
            } finally { client.close(); }// linger 0: a reset, so the gateway's next write fails
            u.release.countDown();
            long end=System.currentTimeMillis()+15000L;
            while(u.closedByGateway.get()==0 && System.currentTimeMillis()<end) Thread.sleep(20);
            check(u.closedByGateway.get()==1,"the cancelled answer's Trae connection is closed: "+u.closedByGateway.get());
            check(u.count.get()==before+1,"a cancelled answer asks no other candidate: "+(u.count.get()-before));
            check(backup.count.get()==fallback,"a cancelled answer fails over to nobody");
            u.mode="ok";u.release.countDown();
            check(post(g,"/v1/chat/completions",chat("trae-cn/fixture",false)).status==200,"the gateway serves after a cancelled stream");
        }
    }
    static JSONObject wire(Upstream u,int index) throws Exception { return new JSONObject(u.seen.get(index)); }
    static String identity(String header) {
        StringBuilder out=new StringBuilder();
        for(String line:header.split("\r\n")) { String l=line.toLowerCase(Locale.ROOT);
            if(l.startsWith("authorization:")||l.startsWith("x-cloudide-token:")||l.startsWith("x-ide-token:")||l.startsWith("x-uid:")) out.append(line).append('\n'); }
        return out.toString();
    }
    static String rawStream(Gateway g,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+"/v1/chat/completions").openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("Authorization","Bearer "+AUTH);
        c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);
        try(OutputStream o=c.getOutputStream()){o.write(bytes(body.toString()));}
        check(c.getResponseCode()==200,"stream status after content");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=c.getInputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        catch(IOException ended) { }
        finally { c.disconnect(); }
        return out.toString("UTF-8");
    }
    /** 4001/4023/1005 before the answer begins: the next function, in the plugin's order, once. */
    static void functions(Path dir,Upstream u,Upstream fallback) throws Exception {
        JSONObject cfg=config(fallback); Path d=Files.createDirectory(dir.resolve("functions"));
        try(Gateway g=new Gateway(cfg,d,u)) {
            u.mode="wrong4001";u.failFirst=1;int before=u.count.get();int seenStart=u.seen.size();
            Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==200&&r.body.contains("hello"),"4001 retried to the next function: "+r.status+" "+r.body);
            check(u.count.get()==before+2,"one retry, no more: "+(u.count.get()-before));
            check(u.seen.size()==seenStart+2,"two attempts recorded");
            JSONObject first=wire(u,seenStart), second=wire(u,seenStart+1);
            check("chat_v3".equals(first.optString("function")),"the function whose list names the model goes first: "+first.optString("function"));
            check("solo_work_lite".equals(second.optString("function")),"then the site's own order: "+second.optString("function"));
            check(!first.optString("session_id").isEmpty()&&first.optString("session_id").equals(second.optString("session_id")),"the same conversation across candidates");
            check(first.optString("config_name").equals(second.optString("config_name"))&&first.optString("model").equals(second.optString("model")),"the same model across candidates");
            check(first.getJSONArray("messages").toString().equals(second.getJSONArray("messages").toString()),"the same messages across candidates");
            String auth=identity(u.seenHeaders.get(seenStart));
            String auth2=identity(u.seenHeaders.get(seenStart+1));
            String lower=auth.toLowerCase(Locale.ROOT);
            check(lower.contains("authorization:")&&lower.contains("x-uid:")&&lower.contains("x-ide-token:")&&auth.equals(auth2),"the same account across candidates");
            u.failFirst=4;before=u.count.get();seenStart=u.seen.size();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==200&&u.count.get()==before+5,"every candidate once, then the answer: "+(u.count.get()-before));
            String[] order={"chat_v3","solo_work_lite","solo_agent","solo_agent_lite","solo_coder"};
            for(int i=0;i<order.length;i++) check(order[i].equals(wire(u,seenStart+i).optString("function")),"candidate order "+i+": "+wire(u,seenStart+i).optString("function"));
            u.failFirst=1;before=u.count.get();seenStart=u.seen.size();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==200&&u.count.get()==before+2,"the remembered function is retried once");
            check("chat_v3".equals(wire(u,seenStart).optString("function")),"the list's function still goes first");
            check("solo_coder".equals(wire(u,seenStart+1).optString("function")),"the function that answered goes before the rest: "+wire(u,seenStart+1).optString("function"));
            u.failFirst=5;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==400&&u.count.get()==before+5,"candidates bounded to one pass: "+r.status+" "+(u.count.get()-before));
            u.mode="ok";u.failFirst=0;
            check(post(g,"/v1/chat/completions",chat("trae-cn/fixture",false)).status==200,"a rejected function leaves no cooldown");
            u.mode="sse4001";u.failFirst=1;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));
            check(r.status==200&&r.body.contains("hello")&&r.body.contains("[DONE]"),"a first event that is 4001 is retried before HTTP 200: "+r.status+" "+r.body);
            check(u.count.get()==before+2,"first-event 4001 retried once");
            u.mode="sse1005";u.failFirst=1;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));
            check(r.status==200&&r.body.contains("hello")&&r.body.contains("[DONE]"),"a first event that is 1005 is retried before HTTP 200");
            check(u.count.get()==before+2,"first-event 1005 retried once");
            u.mode="ok";u.failFirst=0;before=u.count.get();
            check(post(g,"/v1/chat/completions",chat("trae-cn/fixture",false)).status==200&&u.count.get()==before+1,"a rejected first event leaves no cooldown");
            // A rejected candidate's tokens are never counted: only the function that answered books usage.
            u.mode="sseusage4001";u.failFirst=1;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));
            check(r.status==200&&r.body.contains("[DONE]")&&u.count.get()==before+2,"a used candidate is retried once");
            JSONArray recent=UsageLedger.summary(1,"model").getJSONArray("recent");
            for(int i=0;i<recent.length();i++) { JSONObject row=recent.getJSONObject(i); check(!(row.optInt("in",-1)==111||row.optInt("out",-1)==7),"a rejected candidate's tokens are not booked: "+row); }
            JSONObject booked=recent.getJSONObject(0);
            check("trae-cn".equals(booked.optString("prov"))&&booked.isNull("in")&&booked.isNull("out"),"an answer without usage is not given the rejected candidate's: "+booked);
            u.mode="late4001";u.failFirst=0;before=u.count.get();
            String partial=rawStream(g,chat("trae-cn/fixture",true));
            check(u.count.get()==before+1,"no retry once content was produced: "+(u.count.get()-before));
            check(partial.contains("partial")&&!partial.contains("[DONE]"),"the delivered text is not retracted");
            u.mode="wrong4008";u.failFirst=1;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==429&&u.count.get()==before+1,"4008 is a quota answer, not a wrong function: "+r.status+" "+(u.count.get()-before));
            u.mode="sse4001";u.failFirst=5;before=u.count.get();
            r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",true));
            check(r.status==400&&u.count.get()==before+5,"every candidate tried once, then 400: "+r.status+" "+(u.count.get()-before));
            u.mode="ok";u.failFirst=0;
        }
        // A catalog function this build doesn't know is asked as the catalog said, never replaced.
        JSONObject unknown=config(fallback);
        unknown.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("fixture").put("function","future_fn");
        try(Gateway g=new Gateway(unknown,Files.createDirectory(dir.resolve("functions-unknown")),u)) {
            u.mode="ok";u.failFirst=0;int before=u.count.get();int seenStart=u.seen.size();
            Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==200&&u.count.get()==before+1,"an unknown catalog function is still served: "+r.status);
            check("future_fn".equals(wire(u,seenStart).optString("function")),"the catalog's function is never replaced: "+wire(u,seenStart).optString("function"));
        }
        // A blank one is the protocol's own local rejection: nothing is sent for it.
        JSONObject blank=config(fallback);
        blank.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("fixture").put("function","");
        try(Gateway g=new Gateway(blank,Files.createDirectory(dir.resolve("functions-blank")),u)) {
            u.mode="ok";u.failFirst=0;int before=u.count.get();
            Result r=post(g,"/v1/chat/completions",chat("trae-cn/fixture",false));
            check(r.status==400&&u.count.get()==before,"a blank catalog function stays a local rejection: "+r.status+" "+r.body);
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("trae-gateway-http");UsageLedger.init(dir.toFile());GatewayManagementCredential.init(dir.toFile());
        try(Upstream u=new Upstream();Upstream fallback=new Upstream()){if(args.length>0 && args[0].equals("--sessions")) sessions(dir,u,fallback); else if(args.length>0 && args[0].equals("--functions")) functions(dir,u,fallback); else if(args.length>0 && args[0].equals("--verdicts")) verdicts(dir,u,fallback); else if(args.length>0 && args[0].equals("--cancel")) cancel(dir,u,fallback); else {JSONObject cfg=config(fallback);try(Gateway g=new Gateway(cfg,dir,u)){successes(g,u);failures(g,u,fallback);api(g,u,cfg);}u.mode="ok";sessions(dir,u,fallback);functions(dir,u,fallback);verdicts(dir,u,fallback);cancel(dir,u,fallback);}}
        System.out.println("TraeGatewayHttpTest OK");
    }
}
