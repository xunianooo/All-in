package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
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
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Commercial two-target tool-contract tests, using only 127.0.0.1 and synthetic data.
 * Reuses the existing RoutingSetupOfflineTest gateway bootstrap, not real App config.
 * No production bridge is used to generate the expected native fixtures.
 */
public final class ToolContractFailoverHttpTest {
    private static final String AUTH="synthetic-tool-contract-client";
    private static final String KEY_FIRST="synthetic-tool-contract-first", KEY_SECOND="synthetic-tool-contract-second";
    private static final String GROUP="group/tool-contract";
    private static final String SECOND_MARKER="second-target-only-marker", REFUSAL_MARKER="first-target-explicit-refusal-marker", LATE_MARKER="first-target-late-error-marker";
    private static final String DELIVERED_ID="delivered-tool-id-keep", DELIVERED_ARGS="{ \"signature\":\"delivered-arg-signature\", \"command\":\"synthetic\" }";
    private static final AtomicInteger IDS=new AtomicInteger();
    private static final List<UsageLedger.Row> ROWS=Collections.synchronizedList(new ArrayList<UsageLedger.Row>());
    private static final JSONArray OBSERVATIONS=new JSONArray();
    private static final class Tool {
        final String id,name,args,result;
        Tool(String i,String n,String a,String r) { id=i;name=n;args=a;result=r; }
        JSONObject chat() { return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args)); }
        JSONObject messages() { return new JSONObject().put("type","tool_use").put("id",id).put("name",name).put("input",new JSONObject(args)); }
        JSONObject responses() { return new JSONObject().put("type","function_call").put("id","fc_"+id).put("call_id",id).put("name",name).put("arguments",args); }
    }
    private static final Tool A=new Tool("history-kept-A","lookup_history_a","{ \"q\":\"中文\\n雪\", \"signature\":\"history-argument-signature-A\", \"nested\":{\"id\":\"argument-id-A\",\"n\":1234567890123456789} }","result-for-history-kept-A: 中文 / A");
    private static final Tool B=new Tool("history-kept-B","lookup_history_b","{\"items\":[1,true,null,\"\\\"quoted\\\"\"],\"signature\":\"history-argument-signature-B\",\"nested\":{\"id\":\"argument-id-B\"}}","result-for-history-kept-B: 中文 / B");
    private static final Tool NEW_A=new Tool("new-kept-A","new_lookup_a","{ \"signature\":\"new-argument-signature-A\", \"nested\":{\"id\":\"new-argument-id-A\"}, \"q\":\"中文\" }","");
    private static final Tool NEW_B=new Tool("new-kept-B","new_lookup_b","{\"signature\":\"new-argument-signature-B\",\"items\":[1,true,null]}","");
    private static final Tool[] HISTORY={A,B}, NEW_TOOLS={NEW_A,NEW_B};
    private enum Action { REFUSE, SUCCESS, UNKNOWN_AFTER_REQUEST, DELIVER_THEN_FAIL }
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    private static String path(String protocol) { return "anthropic".equals(protocol)?"/v1/messages":"responses".equals(protocol)?"/v1/responses":"/v1/chat/completions"; }
    private static String canonical(Object value) {
        if(value instanceof JSONObject) { JSONObject o=(JSONObject)value; TreeMap<String,String> sorted=new TreeMap<String,String>(); for(String k:o.keySet())sorted.put(k,canonical(o.get(k))); StringBuilder b=new StringBuilder("{"); for(Map.Entry<String,String> e:sorted.entrySet()) { if(b.length()>1)b.append(',');b.append(JSONObject.quote(e.getKey())).append(':').append(e.getValue()); }return b.append('}').toString(); }
        if(value instanceof JSONArray) { JSONArray a=(JSONArray)value; StringBuilder b=new StringBuilder("["); for(int i=0;i<a.length();i++) { if(i>0)b.append(',');b.append(canonical(a.get(i))); }return b.append(']').toString(); }
        return value instanceof String?JSONObject.quote((String)value):value==null?"null":value.toString();
    }
    private static String sha(String value) throws Exception { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString(); }
    private static JSONObject definition(Tool t,String protocol) {
        JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("signature",new JSONObject().put("type","string")).put("nested",new JSONObject().put("type","object")));
        if("anthropic".equals(protocol))return new JSONObject().put("name",t.name).put("input_schema",schema);
        if("responses".equals(protocol))return new JSONObject().put("type","function").put("name",t.name).put("parameters",schema);
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",t.name).put("parameters",schema));
    }
    private static JSONObject request(String entry,boolean stream) {
        JSONObject body=new JSONObject().put("model",GROUP).put("stream",stream); JSONArray tools=new JSONArray();for(Tool t:HISTORY)tools.put(definition(t,entry));body.put("tools",tools);
        if("responses".equals(entry)) {
            body.put("input",new JSONArray().put(new JSONObject().put("type","message").put("role","user").put("content","synthetic tool history"))
                .put(A.responses()).put(B.responses()).put(new JSONObject().put("type","function_call_output").put("call_id",B.id).put("output",B.result))
                .put(new JSONObject().put("type","function_call_output").put("call_id",A.id).put("output",A.result))
                .put(new JSONObject().put("type","message").put("role","user").put("content","continue synthetic history")));
        } else if("anthropic".equals(entry)) {
            body.put("max_tokens",256).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic tool history"))
                .put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(A.messages()).put(B.messages())))
                .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id",B.id).put("content",B.result))
                    .put(new JSONObject().put("type","tool_result").put("tool_use_id",A.id).put("content",A.result))))
                .put(new JSONObject().put("role","user").put("content","continue synthetic history")));
        } else {
            body.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic tool history"))
                .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(A.chat()).put(B.chat())))
                .put(new JSONObject().put("role","tool").put("tool_call_id",B.id).put("content",B.result))
                .put(new JSONObject().put("role","tool").put("tool_call_id",A.id).put("content",A.result))
                .put(new JSONObject().put("role","user").put("content","continue synthetic history")));
        }
        return body;
    }
    private static final class Snapshot {
        final Map<String,Tool> calls=new LinkedHashMap<String,Tool>();
        final Map<String,String> results=new LinkedHashMap<String,String>();
        void call(String id,String name,String args) { check(!calls.containsKey(id),"duplicate history call ID");calls.put(id,new Tool(id,name,args,"")); }
        void result(String id,String content) { check(!results.containsKey(id),"duplicate history result ID");results.put(id,content); }
        JSONObject trace() throws Exception { JSONObject obj=new JSONObject(); for(Tool t:calls.values())obj.put(t.id,new JSONObject().put("name",t.name).put("args",new JSONObject(t.args)).put("result",results.get(t.id)));return new JSONObject().put("call_ids",new JSONArray(calls.keySet())).put("result_ids",new JSONArray(results.keySet())).put("mapping_sha256",sha(canonical(obj))); }
    }
    private static Snapshot snapshot(JSONObject payload,String protocol) {
        Snapshot s=new Snapshot();
        if("responses".equals(protocol)) {
            JSONArray items=payload.getJSONArray("input");for(int i=0;i<items.length();i++) { JSONObject item=items.getJSONObject(i); if("function_call".equals(item.optString("type")))s.call(item.getString("call_id"),item.getString("name"),item.getString("arguments"));if("function_call_output".equals(item.optString("type")))s.result(item.getString("call_id"),item.getString("output")); }
        } else {
            JSONArray messages=payload.getJSONArray("messages");for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.getJSONObject(i);
                if("anthropic".equals(protocol)) { JSONArray content=m.optJSONArray("content");for(int j=0;content!=null && j<content.length();j++) { JSONObject b=content.getJSONObject(j);if("tool_use".equals(b.optString("type")))s.call(b.getString("id"),b.getString("name"),b.getJSONObject("input").toString());if("tool_result".equals(b.optString("type")))s.result(b.getString("tool_use_id"),b.getString("content")); } }
                else { JSONArray calls=m.optJSONArray("tool_calls");for(int j=0;calls!=null && j<calls.length();j++) { JSONObject c=calls.getJSONObject(j),f=c.getJSONObject("function");s.call(c.getString("id"),f.getString("name"),f.getString("arguments")); }if("tool".equals(m.optString("role")))s.result(m.getString("tool_call_id"),m.getString("content")); }
            }
        }
        return s;
    }
    private static void history(Snapshot snapshot,boolean rawArgs) {
        check(snapshot.calls.keySet().equals(new java.util.LinkedHashSet<String>(Arrays.asList(A.id,B.id))),"history call IDs changed");
        check(snapshot.results.keySet().equals(snapshot.calls.keySet()),"history result references changed");
        for(Tool expected:HISTORY) { Tool actual=snapshot.calls.get(expected.id);check(actual.name.equals(expected.name),"history name changed");check(canonical(new JSONObject(expected.args)).equals(canonical(new JSONObject(actual.args))),"history args/signature values changed");if(rawArgs)check(expected.args.equals(actual.args),"history raw argument string changed");check(expected.result.equals(snapshot.results.get(expected.id)),"reverse result paired to wrong ID"); }
    }
    private static JSONObject response(String protocol) {
        if("anthropic".equals(protocol))return new JSONObject().put("id","message-second-target").put("type","message").put("role","assistant").put("model","m2")
            .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",SECOND_MARKER)).put(NEW_A.messages()).put(NEW_B.messages())).put("stop_reason","tool_use").put("usage",new JSONObject().put("input_tokens",11).put("output_tokens",7));
        if("responses".equals(protocol))return new JSONObject().put("id","response-second-target").put("object","response").put("status","completed").put("model","m2")
            .put("output",new JSONArray().put(new JSONObject().put("id","msg_second").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",SECOND_MARKER)))).put(NEW_A.responses()).put(NEW_B.responses())).put("usage",new JSONObject().put("input_tokens",11).put("output_tokens",7));
        return new JSONObject().put("id","chat-second-target").put("object","chat.completion").put("model","m2").put("choices",new JSONArray().put(new JSONObject().put("index",0)
            .put("message",new JSONObject().put("role","assistant").put("content",SECOND_MARKER).put("tool_calls",new JSONArray().put(NEW_A.chat()).put(NEW_B.chat()))).put("finish_reason","tool_calls")))
            .put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",7));
    }
    private static JSONObject toolDelta(int index,String id,String name,String args) {
        JSONObject c=new JSONObject().put("index",index),fn=new JSONObject();if(id!=null)c.put("id",id);if(name!=null)fn.put("name",name);if(args!=null)fn.put("arguments",args);return c.put("function",fn);
    }
    private static JSONObject chunk(JSONArray calls,String text,String finish) {
        JSONObject d=new JSONObject();if(calls!=null)d.put("tool_calls",calls);if(text!=null)d.put("content",text);
        return new JSONObject().put("id","chat-stream-second-target").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",d).put("finish_reason",finish==null?JSONObject.NULL:finish)));
    }
    private static String data(JSONObject e) { return "data: "+e.toString()+"\n\n"; }
    private static String successStream(String protocol) {
        StringBuilder b=new StringBuilder();int splitA=NEW_A.args.length()/2,splitB=NEW_B.args.length()/2;
        if("anthropic".equals(protocol)) {
            b.append(data(new JSONObject().put("type","message_start").put("message",new JSONObject().put("id","message-stream-second").put("type","message").put("role","assistant").put("model","m2").put("usage",new JSONObject().put("input_tokens",11).put("output_tokens",0)))));
            b.append(data(new JSONObject().put("type","content_block_start").put("index",0).put("content_block",new JSONObject().put("type","text").put("text",""))))
             .append(data(new JSONObject().put("type","content_block_delta").put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text",SECOND_MARKER))))
             .append(data(new JSONObject().put("type","content_block_stop").put("index",0)));
            int i=1;for(Tool t:NEW_TOOLS) {int split=t.args.length()/2;b.append(data(new JSONObject().put("type","content_block_start").put("index",i).put("content_block",new JSONObject().put("type","tool_use").put("id",t.id).put("name",t.name).put("input",new JSONObject()))))
                .append(data(new JSONObject().put("type","content_block_delta").put("index",i).put("delta",new JSONObject().put("type","input_json_delta").put("partial_json",t.args.substring(0,split)))))
                .append(data(new JSONObject().put("type","content_block_delta").put("index",i).put("delta",new JSONObject().put("type","input_json_delta").put("partial_json",t.args.substring(split)))))
                .append(data(new JSONObject().put("type","content_block_stop").put("index",i++))); }
            return b.append(data(new JSONObject().put("type","message_delta").put("delta",new JSONObject().put("stop_reason","tool_use")).put("usage",new JSONObject().put("output_tokens",7))))
                .append(data(new JSONObject().put("type","message_stop"))).toString();
        }
        if("responses".equals(protocol)) {
            JSONObject text=response(protocol).getJSONArray("output").getJSONObject(0);
            b.append(data(new JSONObject().put("type","response.output_item.added").put("output_index",0).put("item",new JSONObject(text.toString()).put("content",new JSONArray()))))
             .append(data(new JSONObject().put("type","response.output_text.delta").put("output_index",0).put("content_index",0).put("delta",SECOND_MARKER)));
            b.append(data(new JSONObject().put("type","response.output_item.added").put("output_index",1).put("item",NEW_A.responses().put("arguments",""))))
             .append(data(new JSONObject().put("type","response.output_item.added").put("output_index",2).put("item",NEW_B.responses().put("arguments",""))))
             .append(data(new JSONObject().put("type","response.function_call_arguments.delta").put("output_index",2).put("delta",NEW_B.args.substring(0,splitB))))
             .append(data(new JSONObject().put("type","response.function_call_arguments.delta").put("output_index",1).put("delta",NEW_A.args.substring(0,splitA))))
             .append(data(new JSONObject().put("type","response.function_call_arguments.delta").put("output_index",2).put("delta",NEW_B.args.substring(splitB))))
             .append(data(new JSONObject().put("type","response.function_call_arguments.done").put("output_index",1).put("arguments",NEW_A.args)))
             .append(data(new JSONObject().put("type","response.output_item.done").put("output_index",2).put("item",NEW_B.responses())))
             .append(data(new JSONObject().put("type","response.output_item.done").put("output_index",1).put("item",NEW_A.responses())))
             .append(data(new JSONObject().put("type","response.completed").put("response",response(protocol))));return b.toString();
        }
        b.append(data(chunk(new JSONArray().put(toolDelta(0,null,null,NEW_A.args.substring(0,splitA))).put(toolDelta(1,null,null,NEW_B.args.substring(0,splitB))),SECOND_MARKER,null)))
         .append(data(chunk(new JSONArray().put(toolDelta(1,"new-kept-","new_lookup_",NEW_B.args.substring(splitB))).put(toolDelta(0,"new-kept-","new_lookup_",NEW_A.args.substring(splitA))),null,null)))
         .append(data(chunk(new JSONArray().put(toolDelta(0,"A","a","")).put(toolDelta(1,"B","b","")),null,null)))
         .append(data(chunk(null,null,"tool_calls"))).append("data: [DONE]\n\n");return b.toString();
    }
    private static final class Upstream implements AutoCloseable {
        final String protocol;final Action action;final ServerSocket socket;final Thread thread;
        final List<JSONObject> payloads=Collections.synchronizedList(new ArrayList<JSONObject>());
        final List<String> lines=Collections.synchronizedList(new ArrayList<String>()), keys=Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch firstFrameWritten=new CountDownLatch(1), allowLateFailure=new CountDownLatch(1);
        volatile Socket active;volatile boolean closing;volatile Throwable failure;
        Upstream(String p,Action a) throws Exception { protocol=p;action=a;socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));socket.setSoTimeout(250);thread=new Thread(this::run,"tool-contract-loopback-"+p);thread.setDaemon(true);thread.start(); }
        void run() {
            while(!closing)try(Socket client=socket.accept()) {
                active=client;client.setSoTimeout(10000);InputStream in=client.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int state=0,c;
                while(state<4 && (c=in.read())!=-1) {h.write(c);state=state==0&&c=='\r'?1:state==1&&c=='\n'?2:state==2&&c=='\r'?3:state==3&&c=='\n'?4:0;if(h.size()>65536)throw new IOException("synthetic header bound");}
                String headers=new String(h.toByteArray(),StandardCharsets.UTF_8);int length=-1;String key="";
                for(String line:headers.split("\r\n")) {int colon=line.indexOf(':');if(colon<0)continue;String name=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();if("content-length".equals(name))length=Integer.parseInt(value);if("authorization".equals(name))key=value.startsWith("Bearer ")?value.substring(7):value;if("x-api-key".equals(name))key=value;}
                if(length<0 || length>1024*1024)throw new IOException("synthetic body bound");byte[] bytes=new byte[length];int off=0;while(off<length) {int n=in.read(bytes,off,length-off);if(n<0)throw new EOFException();off+=n;}
                JSONObject payload=new JSONObject(new String(bytes,StandardCharsets.UTF_8));payloads.add(payload);lines.add(headers.split("\r\n")[0]);keys.add(key);
                if(action==Action.UNKNOWN_AFTER_REQUEST) {client.setSoLinger(true,0);continue;}
                OutputStream out=client.getOutputStream();
                if(action==Action.REFUSE) {write(out,429,"application/json",new JSONObject().put("error",new JSONObject().put("type","rate_limit_error").put("message",REFUSAL_MARKER)).toString());continue;}
                if(action==Action.DELIVER_THEN_FAIL) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));char[] pad=new char[20000];Arrays.fill(pad,'x');String prefix;
                    if("responses".equals(protocol))prefix=data(new JSONObject().put("type","response.output_item.added").put("output_index",0).put("item",new JSONObject().put("type","function_call").put("id","fc_delivered").put("call_id",DELIVERED_ID).put("name","delivered_once").put("arguments",DELIVERED_ARGS)))+data(new JSONObject().put("type","response.output_text.delta").put("output_index",1).put("content_index",0).put("delta",new String(pad)));
                    else prefix=data(chunk(new JSONArray().put(toolDelta(0,DELIVERED_ID,"delivered_once",DELIVERED_ARGS)),new String(pad),null));
                    out.write(prefix.getBytes(StandardCharsets.UTF_8));out.flush();firstFrameWritten.countDown();
                    if(!allowLateFailure.await(10,TimeUnit.SECONDS))throw new IOException("client never observed delivered tool");
                    JSONObject error=new JSONObject().put("type","rate_limit_error").put("message",LATE_MARKER);
                    String late="responses".equals(protocol)?data(new JSONObject().put("type","response.failed").put("response",new JSONObject().put("status","failed").put("error",error))):data(new JSONObject().put("error",error));out.write(late.getBytes(StandardCharsets.UTF_8));out.flush();continue;
                }
                boolean stream=payload.optBoolean("stream",false);write(out,200,stream?"text/event-stream":"application/json",stream?successStream(protocol):response(protocol).toString());
            }catch(SocketTimeoutException ignored) { }catch(Throwable e) {if(!closing)failure=e;}finally {active=null;}
        }
        void write(OutputStream out,int status,String mime,String body) throws Exception {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);out.write(("HTTP/1.1 "+status+" "+(status==200?"OK":"Refused")+"\r\nContent-Type: "+mime+"\r\nRetry-After: 120\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();}
        public void close() throws Exception {closing=true;allowLateFailure.countDown();socket.close();Socket current=active;if(current!=null)current.close();thread.join(5000);check(!thread.isAlive(),"upstream worker did not stop");if(failure!=null)throw new AssertionError("synthetic upstream failure",failure);}
    }
    private static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway;
        Gateway(JSONObject cfg) throws Exception {super("127.0.0.1",0);gateway=RoutingSetupOfflineTest.server(cfg);start(NanoHTTPD.SOCKET_READ_TIMEOUT,true);}
        public Response serve(IHTTPSession session) {return gateway.serve(session);}
        protected boolean useGzipWhenAccepted(Response response) {return gateway.useGzipWhenAccepted(response);}
        public void close() {stop();}
    }
    private static JSONObject config(Upstream first,Upstream second) {
        int n=IDS.incrementAndGet();String p1="contract-first-"+n,p2="contract-second-"+n;
        JSONObject a=new JSONObject().put("id",p1).put("protocol",first.protocol).put("baseUrl","http://127.0.0.1:"+first.socket.getLocalPort()+"/v1").put("keys",new JSONArray().put(KEY_FIRST)).put("models",new JSONArray().put("m1"));
        JSONObject b=new JSONObject().put("id",p2).put("protocol",second.protocol).put("baseUrl","http://127.0.0.1:"+second.socket.getLocalPort()+"/v1").put("keys",new JSONArray().put(KEY_SECOND)).put("models",new JSONArray().put("m2"));
        return new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey",AUTH))
            .put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",false)).put("routing",new JSONObject().put("groupsEnabled",true))
            .put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4))
            .put("providers",new JSONArray().put(a).put(b)).put("groups",new JSONArray().put(new JSONObject().put("id","tool-contract").put("strategy","order").put("targets",new JSONArray().put(p1+"/m1").put(p2+"/m2"))));
    }
    private static final class Result {int status;String body="";boolean toolObservedBeforeFailure;}
    private static Result post(Gateway gateway,String entry,JSONObject body,Upstream gated) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path(entry)).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(15000);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Accept-Encoding","identity");conn.setRequestProperty("Authorization","Bearer "+AUTH);conn.setRequestProperty("anthropic-version","2023-06-01");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(bytes.length);
        Result result=new Result();
        try {
            try(OutputStream out=conn.getOutputStream()) {out.write(bytes);}result.status=conn.getResponseCode();InputStream raw=result.status>=400?conn.getErrorStream():conn.getInputStream();StringBuilder all=new StringBuilder();
            if(raw!=null)try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(raw,StandardCharsets.UTF_8))) {
                String line;while((line=reader.readLine())!=null) {
                    all.append(line).append('\n');if(gated!=null && line.startsWith("data:")) {
                        String data=line.substring(5).trim();if(data.isEmpty() || "[DONE]".equals(data))continue;JSONObject e=new JSONObject(data);JSONArray choices=e.optJSONArray("choices");
                        for(int i=0;choices!=null && i<choices.length();i++) {JSONObject d=choices.getJSONObject(i).optJSONObject("delta");JSONArray calls=d==null?null:d.optJSONArray("tool_calls");for(int j=0;calls!=null && j<calls.length();j++) {JSONObject c=calls.getJSONObject(j);if(DELIVERED_ID.equals(c.optString("id"))) {JSONObject f=c.getJSONObject("function");check("delivered_once".equals(f.getString("name")) && DELIVERED_ARGS.equals(f.getString("arguments")),"delivered tool was mutated");result.toolObservedBeforeFailure=true;check(gated.firstFrameWritten.await(2,TimeUnit.SECONDS),"upstream frame gate missing");gated.allowLateFailure.countDown();}}}
                    }
                }
            }
            result.body=all.toString();return result;
        }finally {if(gated!=null)gated.allowLateFailure.countDown();conn.disconnect();}
    }
    private static void idle(Gateway gateway) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(gateway.gateway.activeForwardRequests()!=0 && System.nanoTime()<until)Thread.sleep(5);check(gateway.gateway.activeForwardRequests()==0,"forward request leaked");}
    private static JSONObject attempts(int expected) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until) {boolean terminal=false;synchronized(ROWS) {for(UsageLedger.Row r:ROWS)terminal|=r.terminal;}if(terminal)break;Thread.sleep(5);}
        int attempts=0,terminals=0;String logical=null;JSONArray targets=new JSONArray(),ordinals=new JSONArray(),statuses=new JSONArray(),failureKinds=new JSONArray();
        synchronized(ROWS) {for(UsageLedger.Row row:ROWS) {if(logical==null)logical=row.requestId;check(logical.equals(row.requestId) && !logical.isEmpty(),"logical request identity changed");if(row.attempt>0) {attempts++;targets.put(row.prov+"/"+row.model);ordinals.put(row.attempt);statuses.put(row.status);failureKinds.put(row.failureKind);}if(row.terminal)terminals++;}}
        check(attempts==expected && terminals==1,"unexpected attempt/terminal count: "+attempts+"/"+terminals);return new JSONObject().put("attempts",attempts).put("terminals",terminals).put("attempt_ordinals",ordinals).put("actual_targets",targets).put("attempt_statuses",statuses).put("failure_kinds",failureKinds);
    }
    private static JSONObject forwarded(Upstream upstream,String key,String model,String entry,boolean stream) throws Exception {
        check(upstream.payloads.size()==1,"target request count differs from one");JSONObject payload=upstream.payloads.get(0);check(upstream.keys.get(0).equals(key),"target credential attribution changed");check(upstream.lines.get(0).startsWith("POST "+path(upstream.protocol)+" "),"target endpoint changed");check(model.equals(payload.getString("model")),"actual target model wrong");check(payload.optBoolean("stream",false)==stream,"upstream stream mode changed");Snapshot s=snapshot(payload,upstream.protocol);history(s,!"anthropic".equals(entry) && !"anthropic".equals(upstream.protocol));return s.trace().put("actual_model",model).put("upstream_protocol",upstream.protocol);
    }
    private static Map<String,Tool> responseCalls(String entry,boolean stream,String body) {
        Map<String,Tool> out=new LinkedHashMap<String,Tool>();
        if(!stream) {
            JSONObject response=new JSONObject(body);if("responses".equals(entry)) {JSONArray items=response.getJSONArray("output");for(int i=0;i<items.length();i++) {JSONObject item=items.getJSONObject(i);if("function_call".equals(item.optString("type")))put(out,item.getString("call_id"),item.getString("name"),item.getString("arguments"));}}
            else if("anthropic".equals(entry)) {JSONArray blocks=response.getJSONArray("content");for(int i=0;i<blocks.length();i++) {JSONObject block=blocks.getJSONObject(i);if("tool_use".equals(block.optString("type")))put(out,block.getString("id"),block.getString("name"),block.getJSONObject("input").toString());}}
            else {JSONArray calls=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls");for(int i=0;i<calls.length();i++) {JSONObject call=calls.getJSONObject(i),f=call.getJSONObject("function");put(out,call.getString("id"),f.getString("name"),f.getString("arguments"));}}
            return out;
        }
        List<JSONObject> events=new ArrayList<JSONObject>();for(String line:body.split("\n"))if(line.startsWith("data:")) {String data=line.substring(5).trim();if(!data.isEmpty() && !"[DONE]".equals(data))events.add(new JSONObject(data));}
        if("responses".equals(entry)) {JSONObject terminal=null;for(JSONObject e:events) {check(!"response.failed".equals(e.optString("type")),"Responses stream failed");if("response.completed".equals(e.optString("type")))terminal=e.getJSONObject("response");}check(terminal!=null,"Responses stream has no success terminal");return responseCalls(entry,false,terminal.toString());}
        if("anthropic".equals(entry)) {
            Map<Integer,JSONObject> blocks=new LinkedHashMap<Integer,JSONObject>();Map<Integer,StringBuilder> args=new LinkedHashMap<Integer,StringBuilder>();boolean stop=false;
            for(JSONObject e:events) {String type=e.optString("type");check(!"error".equals(type),"Messages stream failed");if("content_block_start".equals(type)) {int idx=e.getInt("index");blocks.put(idx,e.getJSONObject("content_block"));args.put(idx,new StringBuilder());}if("content_block_delta".equals(type)) {JSONObject d=e.getJSONObject("delta");if("input_json_delta".equals(d.optString("type")))args.get(e.getInt("index")).append(d.getString("partial_json"));}if("message_stop".equals(type))stop=true;}
            check(stop,"Messages stream has no success terminal");for(Map.Entry<Integer,JSONObject> entryBlock:blocks.entrySet()) {JSONObject block=entryBlock.getValue();if("tool_use".equals(block.optString("type"))) {String a=args.get(entryBlock.getKey()).toString();put(out,block.getString("id"),block.getString("name"),a.isEmpty()?block.getJSONObject("input").toString():a);}}return out;
        }
        check(body.contains("data: [DONE]"),"Chat stream has no success terminal");Map<Integer,StringBuilder[]> pieces=new TreeMap<Integer,StringBuilder[]>();
        for(JSONObject e:events) {check(!e.has("error"),"Chat stream failed");JSONArray choices=e.optJSONArray("choices");for(int i=0;choices!=null && i<choices.length();i++) {JSONObject d=choices.getJSONObject(i).optJSONObject("delta");JSONArray calls=d==null?null:d.optJSONArray("tool_calls");for(int j=0;calls!=null && j<calls.length();j++) {JSONObject call=calls.getJSONObject(j);int index=call.getInt("index");if(!pieces.containsKey(index))pieces.put(index,new StringBuilder[]{new StringBuilder(),new StringBuilder(),new StringBuilder()});StringBuilder[] p=pieces.get(index);if(call.opt("id") instanceof String)p[0].append(call.getString("id"));JSONObject f=call.optJSONObject("function");if(f!=null) {if(f.opt("name") instanceof String)p[1].append(f.getString("name"));if(f.opt("arguments") instanceof String)p[2].append(f.getString("arguments"));}}}}
        for(StringBuilder[] p:pieces.values())put(out,p[0].toString(),p[1].toString(),p[2].toString());return out;
    }
    private static void put(Map<String,Tool> out,String id,String name,String args) {check(!id.isEmpty() && !out.containsKey(id),"response IDs missing/duplicate");out.put(id,new Tool(id,name,args,""));}
    private static void finalTools(Map<String,Tool> calls,boolean rawArgs) {
        check(calls.keySet().equals(new java.util.LinkedHashSet<String>(Arrays.asList(NEW_A.id,NEW_B.id))),"second-target response IDs changed or mixed");for(Tool expected:NEW_TOOLS) {Tool actual=calls.get(expected.id);check(expected.name.equals(actual.name),"response tool name changed");check(canonical(new JSONObject(expected.args)).equals(canonical(new JSONObject(actual.args))),"response arguments/signature values changed");if(rawArgs)check(expected.args.equals(actual.args),"response raw args changed");}
    }
    private static void fallback(String entry,String secondProtocol,boolean stream) throws Exception {
        ROWS.clear();JSONObject original=request(entry,stream);String originalText=original.toString();Snapshot input=snapshot(original,entry);history(input,false);check(new ArrayList<String>(input.results.keySet()).equals(Arrays.asList(B.id,A.id)),"fixture did not send inverse tool results");
        try(Upstream first=new Upstream("openai",Action.REFUSE);Upstream second=new Upstream(secondProtocol,Action.SUCCESS)) {
            JSONObject cfg=config(first,second);try(Gateway gateway=new Gateway(cfg)) {
                Result result=post(gateway,entry,original,null);idle(gateway);check(result.status==200,"explicit rejection did not fall back: HTTP "+result.status);check(result.body.contains(SECOND_MARKER) && !result.body.contains(REFUSAL_MARKER),"rejected and successful responses concatenated");
                JSONObject one=forwarded(first,KEY_FIRST,"m1",entry,stream),two=forwarded(second,KEY_SECOND,"m2",entry,stream);check(one.getString("mapping_sha256").equals(two.getString("mapping_sha256")),"target switch changed ID/name/args/result mapping");
                Map<String,Tool> calls=responseCalls(entry,stream,result.body);finalTools(calls,!"anthropic".equals(entry) && (stream || !"anthropic".equals(secondProtocol)));check(originalText.equals(original.toString()),"client fixture mutated");
                JSONObject trace=attempts(2).put("case","fallback_"+entry+"_to_"+secondProtocol+"_"+(stream?"stream":"nonstream")).put("entry",entry).put("stream",stream).put("http_status",result.status).put("input_reverse_result_ids",new JSONArray(input.results.keySet())).put("first",one).put("second",two).put("response_tool_ids",new JSONArray(calls.keySet())).put("first_http",429).put("response_not_spliced",true);OBSERVATIONS.put(trace);
            }
        }
    }
    private static void deliveredNoReplay(String firstProtocol) throws Exception {
        ROWS.clear();try(Upstream first=new Upstream(firstProtocol,Action.DELIVER_THEN_FAIL);Upstream second=new Upstream("openai",Action.SUCCESS)) {
            try(Gateway gateway=new Gateway(config(first,second))) {
                Result result=post(gateway,"openai",request("openai",true),first);idle(gateway);check(result.status==200 && result.toolObservedBeforeFailure,"tool was not actually delivered before injected error");check(first.payloads.size()==1 && second.payloads.isEmpty(),"already delivered tool caused target replay");check(!result.body.contains(SECOND_MARKER) && !result.body.contains(NEW_A.id),"second target appended to delivered response");check(result.body.contains("\"error\"") && !result.body.contains("data: [DONE]"),"late failure reported successful stream completion");forwarded(first,KEY_FIRST,"m1","openai",true);
                OBSERVATIONS.put(attempts(1).put("case","delivered_then_error_"+firstProtocol).put("entry","openai").put("stream",true).put("http_status",result.status).put("downstream_tool_observed_before_failure",true).put("delivered_id",DELIVERED_ID).put("first_requests",first.payloads.size()).put("second_requests",second.payloads.size()).put("response_not_spliced",true));
            }
        }
    }
    private static void unknownNoReplay(String entry,boolean stream) throws Exception {
        ROWS.clear();try(Upstream first=new Upstream("openai",Action.UNKNOWN_AFTER_REQUEST);Upstream second=new Upstream("openai",Action.SUCCESS)) {
            try(Gateway gateway=new Gateway(config(first,second))) {
                Result result=post(gateway,entry,request(entry,stream),null);idle(gateway);check(first.payloads.size()==1 && second.payloads.isEmpty(),"unknown execution replayed another target/credential");check(result.status>=400 && result.body.contains("error"),"unknown execution delivered success");check(!result.body.contains(SECOND_MARKER) && !result.body.contains(NEW_A.id),"unknown and fallback responses spliced");JSONObject firstTrace=forwarded(first,KEY_FIRST,"m1",entry,stream);
                OBSERVATIONS.put(attempts(1).put("case","unknown_execution_"+entry+"_"+(stream?"stream":"nonstream")).put("entry",entry).put("stream",stream).put("http_status",result.status).put("upstream_full_request_observed",true).put("response_headers_never_sent",true).put("first",firstTrace).put("first_requests",first.payloads.size()).put("second_requests",second.payloads.size()).put("response_not_spliced",true));
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path ledger=Files.createTempDirectory("tool-contract-failover-ledger-");Path evidence=args.length>0?Paths.get(args[0]):null;
        Map<String,Checked> cases=new LinkedHashMap<String,Checked>();for(String entry:new String[]{"openai","responses","anthropic"})for(String target:new String[]{"openai","responses","anthropic"})for(boolean stream:new boolean[]{false,true})cases.put("fallback "+entry+" → "+target+" stream="+stream,()->fallback(entry,target,stream));
        cases.put("delivered Chat tool then late rejection no replay",()->deliveredNoReplay("openai"));cases.put("delivered Responses tool then late rejection no replay",()->deliveredNoReplay("responses"));
        for(String entry:new String[]{"openai","responses","anthropic"})for(boolean stream:new boolean[]{false,true})cases.put("unknown execution "+entry+" stream="+stream,()->unknownNoReplay(entry,stream));
        int failures=0;JSONArray results=new JSONArray();
        try {
            UsageLedger.init(ledger.toFile());UsageLedger.setHooks(ROWS::add);
            for(Map.Entry<String,Checked> entry:cases.entrySet()) {
                try {entry.getValue().run();System.out.println("PASS "+entry.getKey());results.put(new JSONObject().put("case",entry.getKey()).put("pass",true));}
                catch(Exception | AssertionError e) {failures++;System.out.println("FAIL "+entry.getKey()+" : "+e.getClass().getSimpleName()+" : "+e.getMessage());results.put(new JSONObject().put("case",entry.getKey()).put("pass",false).put("error",e.toString()));}
            }
            System.out.println("ToolContractFailoverHttpTest: "+(cases.size()-failures)+" passed, "+failures+" failed, "+cases.size()+" synthetic cases");
            if(evidence!=null) {Files.createDirectories(evidence);Files.write(evidence.resolve("failover-http-observations.json"),OBSERVATIONS.toString(2).getBytes(StandardCharsets.UTF_8));Files.write(evidence.resolve("failover-http-results.json"),new JSONObject().put("cases",cases.size()).put("passed",cases.size()-failures).put("failed",failures).put("results",results).toString(2).getBytes(StandardCharsets.UTF_8));Files.write(evidence.resolve("failover-two-tool-history-fixture.json"),new JSONObject().put("synthetic_only",true).put("chat",request("openai",false)).put("responses",request("responses",false)).put("messages",request("anthropic",false)).toString(2).getBytes(StandardCharsets.UTF_8));}
        }finally {
            UsageLedger.setHooks(null);java.lang.reflect.Method close=UsageLedger.class.getDeclaredMethod("closeWriter");close.setAccessible(true);close.invoke(null);
            try(Stream<Path> paths=Files.walk(ledger)) {paths.sorted(Comparator.reverseOrder()).forEach(p->{try {Files.deleteIfExists(p);}catch(IOException e) {throw new java.io.UncheckedIOException(e);}});}
        }
        if(failures>0)throw new AssertionError(failures+" synthetic failover cases failed");
    }
}
