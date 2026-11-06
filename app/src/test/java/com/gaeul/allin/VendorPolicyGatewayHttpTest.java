package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Gateway and upstream both use loopback HTTP; all credentials and payloads are synthetic. */
public final class VendorPolicyGatewayHttpTest {
    static final String KEY="ck_synthetic_12345678", ZERO="00000000-0000-0000-0000-000000000000";
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static final class Request { String path; Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER); JSONObject body; }
    static final class Reply { int status; String type,body; Reply(int s,String t,String b){status=s;type=t;body=b;} }
    interface Script { Reply reply(Request r,int n); }
    static final class Upstream implements AutoCloseable {
        final ServerSocket server; final Thread thread; final List<Request> requests=Collections.synchronizedList(new ArrayList<>()); final Script script; volatile Throwable failure;
        Upstream(Script s)throws Exception {script=s;server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(500);thread=new Thread(this::run);thread.setDaemon(true);thread.start();}
        void run(){while(!server.isClosed()) try(Socket c=server.accept()) { c.setSoTimeout(10000); InputStream in=c.getInputStream(); ByteArrayOutputStream h=new ByteArrayOutputStream(); int st=0,x;
            while(st<4&&(x=in.read())!=-1){h.write(x);st=st==0&&x==13?1:st==1&&x==10?2:st==2&&x==13?3:st==3&&x==10?4:0; if(h.size()>65536)throw new IOException("headers too large");}
            String[] lines=h.toString("UTF-8").split("\r\n"); Request r=new Request();r.path=lines[0].split(" ")[1];int len=0;
            for(String line:lines){int p=line.indexOf(':');if(p>0){String k=line.substring(0,p),v=line.substring(p+1).trim();r.headers.put(k,v);if(k.equalsIgnoreCase("content-length"))len=Integer.parseInt(v);}}
            check(!r.headers.containsKey("Transfer-Encoding"),"fixture expects content-length"); byte[] b=new byte[len];for(int i=0;i<len;){int n=in.read(b,i,len-i);if(n<0)throw new EOFException();i+=n;}r.body=new JSONObject(new String(b,StandardCharsets.UTF_8));requests.add(r);
            Reply reply=script.reply(r,requests.size());byte[] data=reply.body.getBytes(StandardCharsets.UTF_8);OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 "+reply.status+" Test\r\nContent-Type: "+reply.type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(data);out.flush();
        }catch(SocketTimeoutException ignored){}catch(Throwable e){if(!server.isClosed())failure=e;}}
        public void close()throws Exception{server.close();thread.join(11000);check(!thread.isAlive(),"fixture stopped");if(failure!=null)throw new AssertionError("fixture failed",failure);}
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway; final Path dir;
        Gateway(JSONObject cfg,Upstream upstream,boolean legacy)throws Exception {super("127.0.0.1",0);dir=Files.createTempDirectory("vendor-http-");Path file=dir.resolve("config.json");Files.write(file,cfg.toString().getBytes(StandardCharsets.UTF_8));gateway=RoutingSetupOfflineTest.server(cfg);java.lang.reflect.Field rests=GatewayServer.class.getDeclaredField("RESTS");rests.setAccessible(true);((java.util.Map<?,?>)rests.get(null)).clear();ResponsesGatewayOfflineTest.set(gateway,"configFile",file.toFile());
            gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:"+upstream.server.getLocalPort()+new URL(url).getFile()));
            if(legacy){Path asset=Paths.get(Files.exists(Paths.get("app/src/main/assets/default_quirks.json"))?"app/src/main/assets/default_quirks.json":"src/main/assets/default_quirks.json");String q=new String(Files.readAllBytes(asset),StandardCharsets.UTF_8).replace("1.0.2","1.0.1").replace("\"X-Agent-Type\": \"main\"","\"X-Conversation-ID\": \""+ZERO+"\", \"X-Agent-Type\": \"main\"");ProviderQuirks.Snapshot snapshot=ProviderQuirks.parse(q.getBytes(StandardCharsets.UTF_8));check(snapshot.headers("workbuddy","openai","https://copilot.tencent.com/v2/chat/completions").containsKey("X-Conversation-ID"),"legacy fixture includes zero conversation");ResponsesGatewayOfflineTest.set(gateway,"quirksSnapshot",snapshot);}
            start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        public Response serve(IHTTPSession s){return gateway.serve(s);} protected boolean useGzipWhenAccepted(Response r){return gateway.useGzipWhenAccepted(r);}
        public void close()throws Exception{stop();try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
    static JSONObject provider(String id,String protocol,String base,String model){return new JSONObject().put("id",id).put("protocol",protocol).put("baseUrl",base).put("keys",new JSONArray().put(KEY)).put("models",new JSONArray().put(model));}
    static JSONObject config(JSONObject p){return new JSONObject().put("server",new JSONObject().put("authKey","fixture-auth")).put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(p)).put("routing",new JSONObject().put("groupsEnabled",true)).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4));}
    static JSONObject body(String model,boolean stream){return new JSONObject().put("model",model).put("stream",stream).put("max_tokens",100).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));}
    static final class Result{int status;String body;}
    static Result post(Gateway g,String path,JSONObject body)throws Exception{return post(g,path,body,null);}
    static Result post(Gateway g,String path,JSONObject body,String session)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("anthropic-version","2023-06-01");if(session!=null)c.setRequestProperty("x-session-id",session);try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}Result r=new Result();r.status=c.getResponseCode();InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null)try(InputStream i=in){byte[] b=new byte[2048];int n;while((n=i.read(b))!=-1)out.write(b,0,n);}r.body=out.toString("UTF-8");c.disconnect();return r;}
    static final String SSE="data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\",\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\ndata: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
    static final String CHAT="{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
    static final String ANTH="{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    static void workbuddy()throws Exception{for(String id:new String[]{"workbuddy","workbuddy-ai"})try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",SSE))){String host=id.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2";JSONObject cfg=config(provider(id,"openai",host,"model"));try(Gateway g=new Gateway(cfg,u,true)){
        Result r=post(g,"/v1/chat/completions",body(id+"/model",false));check(r.status==200,"workbuddy aggregated status "+id+" "+r.body);check(u.requests.size()==1,"one upstream "+id);Request sent=u.requests.get(0);check(sent.body.optBoolean("stream"),"forced upstream stream "+id);check(sent.path.equals("/v2/chat/completions"),"official path");check(sent.headers.get("User-Agent").contains("5.9.3"),"policy overrides old user-agent");check(KEY.equals(sent.headers.get("X-API-Key")),"workbuddy api key");check(sent.headers.get("X-Conversation-ID")!=null&&!ZERO.equals(sent.headers.get("X-Conversation-ID")),"policy overrides legacy static conversation id");check(r.body.contains("hello")&&r.body.contains("lookup"),"aggregate text and tool calls");
        r=post(g,"/v1/chat/completions",body(id+"/model",true));check(r.status==200&&r.body.contains("[DONE]")&&r.body.contains("hello"),"live SSE "+id);check(u.requests.size()==2&&u.requests.get(1).body.optBoolean("stream"),"live stream upstream");
    }}}
    static void workbuddyTurns()throws Exception {
        for(String id:new String[]{"workbuddy","workbuddy-ai"}) {
            String host=id.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2";
            try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",SSE));
                Gateway g=new Gateway(config(provider(id,"openai",host,"model")),u,true)) {
                String session="workbuddy-test-session";
                JSONObject first=body(id+"/model",false);
                JSONObject roundtrip=new JSONObject(first.toString());
                roundtrip.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL)
                        .put("tool_calls",new JSONArray().put(new JSONObject().put("id","call1").put("type","function")
                                .put("function",new JSONObject().put("name","lookup").put("arguments","{}")))))
                        .put(new JSONObject().put("role","tool").put("tool_call_id","call1").put("content","synthetic result"));
                JSONObject next=new JSONObject(roundtrip.toString());
                next.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","done"))
                        .put(new JSONObject().put("role","user").put("content","hello"));
                JSONObject[] bodies={first,roundtrip,next};
                String firstTurn=null;
                Set<String> messageIds=new HashSet<>();
                for(int i=0;i<bodies.length;i++) {
                    String original=bodies[i].toString();
                    Result result=post(g,"/v1/chat/completions",bodies[i],session);
                    check(result.status==200,"workbuddy turn HTTP "+id+" step "+i+" "+result.body);
                    check(original.equals(bodies[i].toString()),"client payload unchanged "+id+" step "+i);
                    check(u.requests.size()==i+1,"one upstream per turn step "+id+" step "+i);
                    Request sent=u.requests.get(i);
                    check(sent.path.equals("/v2/chat/completions"),"workbuddy turn endpoint "+id);
                    check(sent.body.getJSONArray("messages").toString().equals(bodies[i].getJSONArray("messages").toString()),
                            "upstream messages preserved "+id+" step "+i);
                    String turn=sent.headers.get("X-Conversation-Request-ID");
                    String message=sent.headers.get("X-Conversation-Message-ID");
                    String request=sent.headers.get("X-Request-ID");
                    check(turn!=null&&!turn.isEmpty(),"turn id present "+id+" step "+i);
                    check(message!=null&&!message.isEmpty()&&message.equals(request),"message and request id match "+id+" step "+i);
                    check(messageIds.add(message),"new message id per request "+id+" step "+i);
                    if(i==0)firstTurn=turn;
                    else if(i==1)check(firstTurn.equals(turn),"tool roundtrip keeps turn id "+id);
                    else check(!firstTurn.equals(turn),"repeated text new user turn changes id "+id);
                }
            }
        }
    }
    static void cline()throws Exception{try(Upstream u=new Upstream((r,n)->new Reply(200,"application/json",CHAT))){String model="cline-pass/deepseek-v4.1-flash";JSONObject cfg=config(provider("cline","openai","https://api.cline.bot/api/v1",model));try(Gateway g=new Gateway(cfg,u,false)){
        JSONObject b=body("cline/"+model,false);String image="data:image/png;base64,AQIDBA==";b.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",image))));Result r=post(g,"/v1/chat/completions",b);check(r.status==200,"cline image status "+r.body);JSONObject sent=u.requests.get(0).body;JSONObject opts=sent.getJSONObject("providerOptions").getJSONObject("gateway");check(opts.getJSONArray("only").getString(0).equals("deepseek"),"cline pin");check(opts.getJSONArray("has").toString().contains("vision")&&opts.getJSONArray("has").toString().contains("implicit-caching"),"cline image capabilities");check(image.equals(sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url")),"image bytes preserved");
        JSONObject explicit=new JSONObject().put("gateway",new JSONObject().put("only",new JSONArray().put("synthetic")).put("off",false));b.put("providerOptions",explicit);r=post(g,"/v1/chat/completions",b);check(r.status==200,"cline explicit status");check(u.requests.get(1).body.getJSONObject("providerOptions").toString().equals(explicit.toString()),"explicit providerOptions preserved");
    }}}
    static JSONObject zconfig(){return config(provider("zcode","anthropic","https://open.bigmodel.cn/api/anthropic/v1","model"));}
    static void zcode()throws Exception{
        try(Upstream u=new Upstream((r,n)->new Reply(200,"application/json",ANTH));Gateway g=new Gateway(zconfig(),u,false)){JSONObject b=body("zcode/model",false);b.put("metadata",new JSONObject().put("user_id","opaque-custom"));Result r=post(g,"/v1/messages",b);check(r.status==200,"zcode messages status "+r.body);Request q=u.requests.get(0);check(q.path.equals("/api/anthropic/v1/messages"),"zcode official messages path");check(q.headers.get("x-session-id")!=null&&!q.headers.get("x-session-id").isEmpty(),"zcode session fingerprint header");check(q.body.getJSONObject("metadata").getString("user_id").equals("opaque-custom"),"opaque metadata preserved");}
        for(int code:new int[]{1113,1000})try(Upstream u=new Upstream((r,n)->new Reply(200,"application/json","{\"error\":{\"code\":\""+code+"\",\"message\":\"synthetic\"}}"));Gateway g=new Gateway(zconfig(),u,false)){Result r=post(g,"/v1/messages",body("zcode/model",false));check(r.status==(code==1113?429:401),"zcode envelope "+code+" got "+r.status+" "+r.body);check(u.requests.size()==1,"envelope no replay");}
        try(Upstream u=new Upstream((r,n)->new Reply(405,"application/json","{\"error\":{\"code\":\"3012\",\"message\":\"synthetic refusal\"}}"));Gateway g=new Gateway(zconfig(),u,false)){Result first=post(g,"/v1/messages",body("zcode/model",false));check(first.status==405,"explicit refusal status");check(u.requests.size()==1,"refusal not immediately repeated");Result second=post(g,"/v1/messages",body("zcode/model",false));check(second.status==429,"local refusal cooldown");check(u.requests.size()==1,"cooldown skips upstream");}
    }
    static void zeroStatusRefusal()throws Exception {
        try(Upstream u=new Upstream((r,n)->new Reply(200,"application/json","{\"success\":false,\"code\":3012,\"msg\":\"synthetic\"}"));Gateway g=new Gateway(zconfig(),u,false)) {
            check(post(g,"/v1/messages",body("zcode/model",false)).status==405,"200 3012 mapped");
            check(post(g,"/v1/messages",body("zcode/model",false)).status==429,"200 3012 held");check(u.requests.size()==1,"200 3012 not repeated");
        }
    }
    static JSONObject groupConfig(){JSONObject c=zconfig();c.getJSONArray("providers").put(provider("backup","anthropic","https://fixture.invalid/backup","model"));c.put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put("zcode/model").put("backup/model"))));return c;}
    static void groups()throws Exception{
        try(Upstream u=new Upstream((r,n)->r.path.startsWith("/backup/")?new Reply(200,"application/json",ANTH):new Reply(405,"application/json","{\"error\":{\"code\":\"3012\",\"message\":\"synthetic\"}}"));Gateway g=new Gateway(groupConfig(),u,false)){
            Result r=post(g,"/v1/messages",body("group/g",false));check(r.status==200,"explicit known refusal group fallback "+r.body);check(u.requests.size()==2&&u.requests.get(1).path.startsWith("/backup/"),"known refusal reaches backup once");
            r=post(g,"/v1/messages",body("group/g",false));check(r.status==200,"local hold allows group fallback");check(u.requests.size()==3&&u.requests.get(2).path.startsWith("/backup/"),"local hold skips refused upstream");
        }
        try(Upstream u=new Upstream((r,n)->new Reply(405,"application/json","{\"error\":{\"message\":\"generic refusal\"}}"));Gateway g=new Gateway(groupConfig(),u,false)){Result r=post(g,"/v1/messages",body("group/g",false));check(r.status==405,"bare 405 not safe fallback");check(u.requests.size()==1,"bare 405 not replayed");}
        try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream","event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"synthetic stream error\"}}\n\n"));Gateway g=new Gateway(groupConfig(),u,false)){Result r=post(g,"/v1/messages",body("group/g",true));check(r.status==200&&r.body.contains("error"),"stream error forwarded");check(u.requests.size()==1,"stream error never replayed");}
    }
    public static void main(String[] args)throws Exception{UsageLedger.init(Files.createTempDirectory("vendor-policy-ledger-").toFile());workbuddy();workbuddyTurns();cline();zcode();groups();zeroStatusRefusal();System.out.println("VendorPolicyGatewayHttpTest OK");}
}
