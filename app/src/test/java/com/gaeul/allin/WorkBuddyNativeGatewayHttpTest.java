package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local-only mock Gateway HTTP tests for domestic WorkBuddy Claude native Messages. */
public final class WorkBuddyNativeGatewayHttpTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static final String KEY="ck_fixture_workbuddy_token";
    static final String BASE="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_native\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5.5\",\"content\":[],\"usage\":{\"input_tokens\":17,\"cache_read_input_tokens\":3}}}\n\n"
      +"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}\n\n"
      +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private thought\"}}\n\n"
      +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"opaque-signature\"}}\n\n"
      +"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
      +"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
      +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"native answer\"}}\n\n"
      +"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
      +"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":6}}\n\n"
      +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    static final String TOOL_SSE="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_tool\",\"role\":\"assistant\",\"model\":\"claude-opus-5.5\",\"content\":[],\"usage\":{\"input_tokens\":4}}}\n\n"
      +"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_native\",\"name\":\"Read\",\"input\":{}}}\n\n"
      +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"src\\\"}\"}}\n\n"
      +"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
      +"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":3}}\n\n"
      +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    static final class Request { String path; Map<String,String> headers=new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER); JSONObject body; }
    static final class Reply { int status;String type,body;boolean disconnect;Reply(int s,String t,String b){status=s;type=t;body=b;} static Reply disconnect(){Reply r=new Reply(0,"","" );r.disconnect=true;return r;} }
    interface Script { Reply respond(Request request,int count); }
    static final class MockUpstream implements AutoCloseable {
        final ServerSocket server; final Thread worker; final Script script;
        final List<Request> requests=Collections.synchronizedList(new java.util.ArrayList<Request>());
        volatile Throwable failure;
        MockUpstream(Script script)throws Exception {this.script=script;server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(400);worker=new Thread(this::loop);worker.setDaemon(true);worker.start();}
        void loop(){while(!server.isClosed())try(Socket socket=server.accept()){
            socket.setSoTimeout(8000);InputStream in=socket.getInputStream();ByteArrayOutputStream raw=new ByteArrayOutputStream();int state=0,ch;
            while(state<4&&(ch=in.read())!=-1){raw.write(ch);state=state==0&&ch==13?1:state==1&&ch==10?2:state==2&&ch==13?3:state==3&&ch==10?4:0;}
            String[] lines=raw.toString("UTF-8").split("\\r\\n");Request req=new Request();req.path=lines[0].split(" ")[1];int length=0;
            for(String line:lines){int p=line.indexOf(':');if(p>0){String k=line.substring(0,p),v=line.substring(p+1).trim();req.headers.put(k,v);if("content-length".equalsIgnoreCase(k))length=Integer.parseInt(v);}}
            byte[] body=new byte[length];for(int n=0;n<length;){int got=in.read(body,n,length-n);if(got<0)throw new java.io.EOFException();n+=got;}
            if("gzip".equalsIgnoreCase(req.headers.get("Content-Encoding")))try(InputStream gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body));ByteArrayOutputStream decoded=new ByteArrayOutputStream()){byte[] chunk=new byte[4096];int got;while((got=gzip.read(chunk))!=-1)decoded.write(chunk,0,got);body=decoded.toByteArray();}
            try { req.body=new JSONObject(new String(body,StandardCharsets.UTF_8)); }
            catch(Exception malformed) { throw new java.io.IOException("malformed fixture request; headers="+raw.toString("UTF-8"),malformed); }
            requests.add(req);Reply reply=script.respond(req,requests.size());
            if(reply.disconnect)continue;
            byte[] payload=reply.body.getBytes(StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+reply.status+" Fixture\r\nContent-Type: "+reply.type+"\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(payload);out.flush();
        }catch(SocketTimeoutException ignore){}catch(Throwable t){if(!server.isClosed())failure=t;}}
        public void close()throws Exception{server.close();worker.join(9000);check(!worker.isAlive(),"upstream worker stopped");if(failure!=null)throw new AssertionError("mock upstream failure",failure);}
    }
    static final class GatewayHarness implements AutoCloseable {
        final GatewayServer gateway; final NanoHTTPD http; final Path dir; final Object previousFactory;
        GatewayHarness(JSONObject config,MockUpstream upstream)throws Exception {
            dir=Files.createTempDirectory("workbuddy-native-gw-");Path file=dir.resolve("config.json");Files.write(file,config.toString().getBytes(StandardCharsets.UTF_8));
            gateway=RoutingSetupOfflineTest.server(config);ResponsesGatewayOfflineTest.set(gateway,"configFile",file.toFile());
            java.lang.reflect.Field factoryField=GatewayServer.class.getDeclaredField("vendorConnectionFactory");factoryField.setAccessible(true);
            previousFactory=factoryField.get(gateway);
            gateway.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+upstream.server.getLocalPort()+new URL(url).getFile()));

            http=new NanoHTTPD("127.0.0.1",0){@Override public Response serve(IHTTPSession session){return gateway.serve(session);} @Override protected boolean useGzipWhenAccepted(Response response){return gateway.useGzipWhenAccepted(response);}};
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);
        }
        int port(){return http.getListeningPort();}
        public void close()throws Exception{http.stop();java.lang.reflect.Field factoryField=GatewayServer.class.getDeclaredField("vendorConnectionFactory");factoryField.setAccessible(true);factoryField.set(gateway,previousFactory);try(java.util.stream.Stream<Path> files=Files.walk(dir)){for(Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    static JSONObject config(String provider,String protocol,String base,String model){
        JSONObject p=new JSONObject().put("id",provider).put("enabled",true).put("protocol",protocol).put("baseUrl",base)
                .put("keys",new JSONArray().put(KEY)).put("models",new JSONArray().put(model));
        if("workbuddy".equals(provider))p.put("modelMeta",new JSONObject().put(model,new JSONObject().put("maxOutput",8192)
                .put("reasoning",new JSONObject().put("defaultEffort","high").put("supportedEfforts",new JSONArray().put("low").put("high")))));
        return new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","gateway-fixture"))
                .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(p))
                .put("routing",new JSONObject().put("groupsEnabled",true)).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4));
    }
    static JSONObject chat(boolean stream){
        return new JSONObject().put("model","workbuddy/claude-opus-5.5").put("stream",stream).put("max_tokens",2048)
                .put("thinking",new JSONObject().put("type","adaptive")).put("output_config",new JSONObject().put("format",new JSONObject().put("type","json_schema")))
                .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","system prompt"))
                    .put(new JSONObject().put("role","user").put("content","hello")))
                .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","Read").put("description","read file")
                    .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")))))));
    }
    static JSONObject anthropic(boolean stream){
        return new JSONObject().put("model","workbuddy/claude-opus-5.5").put("max_tokens",2048).put("stream",stream)
            .put("thinking",new JSONObject().put("type","adaptive")).put("output_config",new JSONObject().put("format",new JSONObject().put("type","json_schema")))
            .put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","system prompt")))
            .put("tools",new JSONArray().put(new JSONObject().put("name","Read").put("description","read file").put("input_schema",new JSONObject().put("type","object"))))
            .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
    }
    static final class Result { int status;String body;Map<String,List<String>> headers; }
    static Result post(int port,String path,JSONObject body,String beta)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer gateway-fixture");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept-Encoding","identity");
        if(beta!=null)c.setRequestProperty("anthropic-beta",beta);
        try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        Result result=new Result();result.status=c.getResponseCode();result.headers=c.getHeaderFields();InputStream in=result.status>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null)try(InputStream bodyStream=in){byte[] buffer=new byte[4096];int n;while((n=bodyStream.read(buffer))!=-1)out.write(buffer,0,n);}result.body=out.toString("UTF-8");c.disconnect();return result;
    }
    static String header(Result result,String key){for(Map.Entry<String,List<String>> e:result.headers.entrySet())if(key.equalsIgnoreCase(e.getKey()))return e.getValue()==null||e.getValue().isEmpty()?null:e.getValue().get(0);return null;}
    static void verifyRequest(Request request,boolean stream,boolean expectReasoning){
        check("/v1/messages".equals(request.path),"native domestic endpoint only: "+request.path);
        check(("Bearer "+KEY).equals(request.headers.get("Authorization")),"WorkBuddy token header");
        check("2023-06-01".equals(request.headers.get("anthropic-version")),"anthropic version header");
        check(KEY.equals(request.headers.get("X-API-Key")),"workbuddy key classification");
        check("text/event-stream".equalsIgnoreCase(request.headers.get("Accept")),"SSE Accept header");
        check("claude-opus-5.5".equals(request.body.optString("model")),"resolved upstream model");
        check(stream==request.body.optBoolean("stream"),"native stream mode");
        // A Messages relay is not a chat: the model catalog's default effort is not injected
        // (opencode-workbuddy-auth index.mjs:3837-3838 applies it to chats only), so only a level
        // and a thinking block the client named may appear.
        JSONObject outConfig=request.body.optJSONObject("output_config");
        check(outConfig==null||!outConfig.has("effort"),"no catalog default effort is invented on the native route");
        if(expectReasoning)check(request.body.has("thinking")&&request.body.has("output_config"),"native reasoning and output_config retained");
        else check(!request.body.has("thinking")&&!request.body.has("output_config"),"a Responses client that asked for no reasoning gets none: "+request.body);
        JSONArray tools=request.body.optJSONArray("tools");
        if(tools!=null)check(tools.getJSONObject(0).has("input_schema"),"Anthropic tool schema retained");
    }
    static void routeAndProtocol()throws Exception{
        for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})for(boolean stream:new boolean[]{false,true}){
            try(MockUpstream upstream=new MockUpstream((req,n)->new Reply(200,"text/event-stream",BASE));
                GatewayHarness gateway=new GatewayHarness(config("workbuddy","openai","https://copilot.tencent.com/v2","claude-opus-5.5"),upstream)){
                JSONObject input=path.endsWith("/messages")?anthropic(stream):path.endsWith("/responses")?new JSONObject().put("model","workbuddy/claude-opus-5.5").put("stream",stream).put("max_output_tokens",2048).put("input",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).put("tools",new JSONArray().put(new JSONObject().put("type","function").put("name","Read").put("description","read file").put("parameters",new JSONObject().put("type","object")))):chat(stream);
                input.put("model","workbuddy/claude-opus-5.5");if(path.endsWith("/responses")&& !stream) input.put("max_output_tokens",2048);
                Result response=post(gateway.port(),path,input,null);
                check(response.status==200,"route/protocol "+path+" stream="+stream+" got "+response.status+" requests="+upstream.requests.size()+" failure="+upstream.failure+" body="+response.body);
                check(upstream.requests.size()==1,"single native request "+path+" "+stream);
                verifyRequest(upstream.requests.get(0),true,!path.endsWith("/responses"));
                if(path.endsWith("/messages")){
                    if(stream)check(response.body.contains("thinking_delta")&&response.body.contains("signature_delta")&&response.body.contains("opaque-signature"),"Anthropic SSE native signature preserved: "+response.body);
                    else check(response.body.contains("private thought")&&response.body.contains("opaque-signature"),"Anthropic JSON signed thinking preserved");
                    if(!stream)check(response.body.contains("input_tokens")&&response.body.contains("output_tokens"),"Anthropic JSON aggregate retains real usage");
                }else if(stream){
                    if(path.endsWith("/responses"))check(response.body.contains("response.completed")&&response.body.contains("native answer"),"Responses stream conversion: "+response.body);
                    else check(response.body.contains("reasoning_content")&&response.body.contains("[DONE]"),"Chat stream conversion: "+response.body);
                }
                else {
                    JSONObject reply=new JSONObject(response.body);
                    if(path.endsWith("/responses"))check(reply.optString("status").length()>0&&reply.toString().contains("native answer"),"Responses complete aggregate conversion");
                    else check(reply.getJSONObject("usage").optInt("prompt_tokens")==20&&reply.getJSONObject("usage").optInt("completion_tokens")==6,"Chat real usage returned");
                }
            }
        }
    }
    static void nativeToolAndSignedHistory()throws Exception{
        for(boolean stream:new boolean[]{false,true})try(MockUpstream upstream=new MockUpstream((req,n)->new Reply(200,"text/event-stream",TOOL_SSE));
            GatewayHarness gateway=new GatewayHarness(config("workbuddy","openai","https://copilot.tencent.com/v2","claude-opus-5.5"),upstream)){
            JSONArray nativeMessages=new JSONArray();
            nativeMessages.put(new JSONObject().put("role","user").put("content","hello"));
            JSONArray assistantBlocks=new JSONArray();
            assistantBlocks.put(new JSONObject().put("type","thinking").put("thinking","signed prior").put("signature","sig-prior"));
            assistantBlocks.put(new JSONObject().put("type","tool_use").put("id","toolu-prior").put("name","Read").put("input",new JSONObject().put("path","src")));
            nativeMessages.put(new JSONObject().put("role","assistant").put("content",assistantBlocks));
            JSONArray toolResult=new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","toolu-prior").put("content","result"));
            nativeMessages.put(new JSONObject().put("role","user").put("content",toolResult));
            nativeMessages.put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","continue"))));
            JSONObject input=anthropic(stream).put("messages",nativeMessages);
            Result response=post(gateway.port(),"/v1/messages",input,null);
            check(response.status==200,"signed request preserved "+stream+" "+response.body);
            JSONObject sent=upstream.requests.get(0).body;
            check(sent.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).optString("signature").equals("sig-prior"),"historical signature unchanged upstream");
            check(response.body.contains("toolu_native")||response.body.contains("opaque-signature"),"tool response retained");
        }
    }
    static void unknownNoReplayAndDsh()throws Exception{
        for(String scenario:new String[]{"status-unknown","reject","dsh"}) {
            Script script=(req,n)->{
                if("dsh".equals(scenario))check(!req.body.toString().contains("DeepSeek Harness"),"DSH removed from first outbound request");
                if("reject".equals(scenario))return new Reply(400,"application/json","{\"error\":{\"message\":\"thinking signature invalid\"}}");
                if("status-unknown".equals(scenario))return Reply.disconnect();
                return new Reply(200,"text/event-stream",BASE);
            };
            try(MockUpstream upstream=new MockUpstream(script);GatewayHarness gateway=new GatewayHarness(config("workbuddy","openai","https://copilot.tencent.com/v2","claude-opus-5.5"),upstream)){
                JSONObject input=anthropic(false);if("dsh".equals(scenario))input.put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","You are an AI agent powered by DeepSeek Harness.")));
                Result response=post(gateway.port(),"/v1/messages",input,null);
                check(upstream.requests.size()==1,"single attempt "+scenario);
                if("reject".equals(scenario))check(response.status==400,"provider rejection returned");
                if("dsh".equals(scenario))check(response.status==200,"DSH request success");
                if("status-unknown".equals(scenario))check(response.status>=500,"unknown execution is terminal error");
            }
        }
    }
    static void internationalRemainsOnChat()throws Exception{
        String chatSse="data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"intl\"},\"finish_reason\":null}]}\n\n"
                +"data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                +"data: [DONE]\n\n";
        try(MockUpstream upstream=new MockUpstream((req,n)->new Reply(200,"text/event-stream",chatSse));
            GatewayHarness gateway=new GatewayHarness(config("workbuddy-ai","openai","https://www.workbuddy.ai/v2","model"),upstream)) {
            JSONObject input=new JSONObject().put("model","workbuddy-ai/model").put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")));
            Result response=post(gateway.port(),"/v1/chat/completions",input,null);
            check(response.status==200&&response.body.contains("intl"),"international WorkBuddy still follows chat path: "+response.status+" "+response.body);
            check(upstream.requests.size()==1&&upstream.requests.get(0).path.equals("/v2/chat/completions"),"international endpoint remains chat");
        }
    }
    static void upstreamAggregateUsageForChat()throws Exception{
        String chatSse="data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":null}]}\n\n"
                +"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":7,\"total_tokens\":38}}\n\n"
                +"data: [DONE]\n\n";
        try(MockUpstream upstream=new MockUpstream((req,n)->new Reply(200,"text/event-stream",chatSse));GatewayHarness gateway=new GatewayHarness(config("workbuddy","openai","https://copilot.tencent.com/v2","glm-5.3"),upstream)){
            Result response=post(gateway.port(),"/v1/chat/completions",new JSONObject().put("model","workbuddy/glm-5.3").put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi"))),null);
            JSONObject usage=new JSONObject(response.body).getJSONObject("usage");check(usage.optInt("prompt_tokens")==31&&usage.optInt("completion_tokens")==7&&usage.optInt("total_tokens")==38,"WorkBuddy chat aggregate exposes genuine upstream usage");
        }
    }
    static void nativeAttemptText()throws Exception{
        try(MockUpstream upstream=new MockUpstream((req,n)->new Reply(200,"text/event-stream",BASE));
            GatewayHarness gateway=new GatewayHarness(config("workbuddy","openai","https://copilot.tencent.com/v2","claude-opus-5.5"),upstream)){
            Class<?> routeClass=Class.forName("com.gaeul.allin.GatewayServer$RouteTarget");
            java.lang.reflect.Constructor<?> ctor=routeClass.getDeclaredConstructor();ctor.setAccessible(true);Object target=ctor.newInstance();
            for(String[] pair:new String[][]{{"providerId","workbuddy"},{"baseUrl","https://copilot.tencent.com/v2"},{"apiKey",KEY},{"upstreamModel","claude-opus-5.5"},{"protocol","openai"}}){java.lang.reflect.Field f=routeClass.getDeclaredField(pair[0]);f.setAccessible(true);f.set(target,pair[1]);}
            java.lang.reflect.Method forward=GatewayServer.class.getDeclaredMethod("forwardWorkBuddyNative",routeClass,JSONObject.class,boolean.class,String.class,PrivacyMasker.Session.class,ContextGuard.Result.class,ContextGuard.Config.class);
            forward.setAccessible(true);
            Object result=forward.invoke(gateway.gateway,target,chat(false),false,"workbuddy/claude-opus-5.5",null,null,new ContextGuard.Config());
            java.lang.reflect.Field attemptField=result.getClass().getDeclaredField("attempt");attemptField.setAccessible(true);Object attempt=attemptField.get(result);
            java.lang.reflect.Field text=attempt.getClass().getDeclaredField("textOut"),reason=attempt.getClass().getDeclaredField("reasoningOut"),ok=attempt.getClass().getDeclaredField("ok");text.setAccessible(true);reason.setAccessible(true);ok.setAccessible(true);
            check(ok.getBoolean(attempt),"direct native attempt succeeds");
            check("native answer".equals(text.get(attempt)),"native nonstream Attempt.textOut contains completion");
            check("private thought".equals(reason.get(attempt)),"native nonstream Attempt.reasoningOut contains reasoning");
            check(upstream.requests.size()==1,"direct attempt has one physical send");
        }
    }
    public static void main(String[] args)throws Exception{
        nativeAttemptText();routeAndProtocol();nativeToolAndSignedHistory();unknownNoReplayAndDsh();internationalRemainsOnChat();upstreamAggregateUsageForChat();
        System.out.println("WorkBuddyNativeGatewayHttpTest OK (route, protocols, signatures, tools, usage, DSH, no replay)");
    }
}
