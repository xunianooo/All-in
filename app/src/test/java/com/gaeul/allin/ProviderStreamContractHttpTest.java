package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import fi.iki.elonen.NanoHTTPD;

/** Live HTTP gates and a pinned npm event oracle, not completed responses split into chunks. */
public final class ProviderStreamContractHttpTest {
    static int checks;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static final String FIRST="data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"FIRST\"},\"finish_reason\":null}]}\n\n";
    static final String LAST="data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" LAST\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    static final class DelayedHttp implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1),seen=new CountDownLatch(1);
        final Thread worker;final String first,last;volatile String head;volatile JSONObject body;volatile Throwable failure;
        DelayedHttp(String first,String last)throws Exception{this.first=first;this.last=last;worker=new Thread(()->{try(Socket socket=server.accept()){
            socket.setSoTimeout(10000);InputStream in=socket.getInputStream();head=MiniMaxIntegrationHttpTest.Upstream.head(in);int n=Integer.parseInt(MiniMaxIntegrationHttpTest.Upstream.field(head,"content-length"));byte[] bytes=new byte[n];new DataInputStream(in).readFully(bytes);
            if("gzip".equalsIgnoreCase(MiniMaxIntegrationHttpTest.Upstream.field(head,"content-encoding"))){try(InputStream gz=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int count;while((count=gz.read(b))!=-1)out.write(b,0,count);bytes=out.toByteArray();}}
            String decoded=new String(bytes,StandardCharsets.UTF_8);if(head.startsWith("POST /qoder "))decoded=QoderAuth.decodeBody(decoded);body=new JSONObject(decoded);seen.countDown();OutputStream out=socket.getOutputStream();out.write("HTTP/1.1 200 Test\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));chunk(out,first);out.flush();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("first delta never reached client");chunk(out,last);out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
        }catch(Throwable t){if(!server.isClosed())failure=t;}finally{seen.countDown();done.countDown();}},"provider-delayed-http");worker.setDaemon(true);worker.start();}
        static void chunk(OutputStream out,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);out.write((Integer.toHexString(b.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));}
        int port(){return server.getLocalPort();}
        public void close()throws Exception{release.countDown();server.close();worker.join(3000);check(!worker.isAlive(),"owned producer stopped");if(failure!=null)throw new AssertionError(failure);}
    }
    static String remaining(BufferedReader reader)throws Exception{StringBuilder b=new StringBuilder();String s;while((s=reader.readLine())!=null)b.append(s).append('\n');return b.toString();}
    static JSONObject genericConfig(String id){
        JSONObject provider=VendorPolicyGatewayHttpTest.provider(id,"openai","https://example.invalid/v1","model");
        if(id.equals("loomy")||id.equals("raccoon"))provider.put("keys",new JSONArray()).put("accounts",new JSONArray().put(new JSONObject().put("id","synthetic-account").put("token","synthetic-session").put("access","synthetic-session").put("enabled",true).put("expires",System.currentTimeMillis()+3600000)));
        return VendorPolicyGatewayHttpTest.config(provider).put("setup",new JSONObject().put("done",true)).put("guard",new JSONObject().put("enabled",false));
    }
    static void generic(String id)throws Exception {
        try(DelayedHttp source=new DelayedHttp(FIRST,LAST);VendorPolicyGatewayHttpTest.Upstream unused=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(500,"application/json","{}"));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(genericConfig(id),unused,false)){
            gateway.gateway.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+source.port()+"/chat"));
            HttpURLConnection client=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+"/v1/chat/completions").openConnection();client.setRequestMethod("POST");client.setDoOutput(true);client.setReadTimeout(4000);client.setRequestProperty("Content-Type","application/json");client.setRequestProperty("Authorization","Bearer fixture-auth");client.setRequestProperty("Accept-Encoding","identity");
            try{try(OutputStream out=client.getOutputStream()){out.write(VendorPolicyGatewayHttpTest.body(id+"/model",true).toString().getBytes(StandardCharsets.UTF_8));}check(client.getResponseCode()==200,id+" HTTP");BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));String s;boolean first=false;while((s=reader.readLine())!=null)if(s.contains("FIRST")){first=true;break;}check(first,id+" first delta");check(source.done.getCount()==1,id+" first delta before completed upstream");check(source.body.optBoolean("stream"),id+" stream request preserved");source.release.countDown();String tail=remaining(reader);check(tail.contains(" LAST")&&tail.contains("[DONE]"),id+" remaining/terminal");}finally{client.disconnect();source.release.countDown();}
        }
    }
    static void minimax(JSONObject oracle)throws Exception {
        String first="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"usage\":{\"input_tokens\":1}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"FIRST\"}}\n\n";
        String last="event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" LAST\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        try(DelayedHttp source=new DelayedHttp(first,last)){
            Path dir=Files.createTempDirectory("minimax-stream-contract-");GatewayServer gateway=MiniMaxIntegrationHttpTest.setup(source.port(),dir.resolve("config.json"),System.currentTimeMillis()+3600000);ResponsesGatewayOfflineTest.config(gateway).put("guard",new JSONObject().put("enabled",false));Files.write(dir.resolve("config.json"),ResponsesGatewayOfflineTest.config(gateway).toString().getBytes(StandardCharsets.UTF_8));String body=new JSONObject().put("model","minimax-code/m").put("stream",true).put("max_tokens",128).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi"))).toString();NanoHTTPD.Response response=gateway.serve(MiniMaxIntegrationHttpTest.namedSession(body,"synthetic-stream"));
            try{check(response.getStatus().getRequestStatus()==200,"MiniMax live HTTP");check(source.seen.await(2,TimeUnit.SECONDS),"MiniMax request received");check(oracle.getString("miniMaxAcceptEncoding").equals(MiniMaxIntegrationHttpTest.Upstream.field(source.head,"accept-encoding")),"actual outbound MiniMax header equals pinned npm identity");check(source.body.getInt("max_tokens")==128,"MiniMax caller budget not changed");BufferedReader reader=new BufferedReader(new InputStreamReader(response.getData(),StandardCharsets.UTF_8));boolean firstSeen=false;String s;while((s=reader.readLine())!=null)if(s.contains("FIRST")){firstSeen=true;break;}check(firstSeen&&source.done.getCount()==1,"MiniMax first delta before upstream completed");source.release.countDown();check(remaining(reader).contains("[DONE]"),"MiniMax terminal");}finally{response.getData().close();source.release.countDown();}
        }
    }
    static void qoder(JSONObject oracle)throws Exception {
        String finish="data: {\"statusCodeValue\":200,\"body\":\"{\\\"choices\\\":[{\\\"delta\\\":{},\\\"finish_reason\\\":\\\"tool_calls\\\"}]}\"}\n\ndata: {\"body\":\"[DONE]\"}\n\n";
        try(DelayedHttp source=new DelayedHttp(oracle.getString("qoderFirstWire"),oracle.getString("qoderNextWire")+finish)){
            QoderAuth.SignIn sign=new QoderAuth.SignIn();sign.access="synthetic";sign.uid="synthetic";sign.machineId="synthetic";
            JSONObject original=new JSONObject().put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","add_numbers"))));JSONObject body=new JSONObject().put("model_config",new JSONObject().put("key","m"));
            try(QoderStream.Stream stream=QoderStream.open(sign,body,original,"m",url->NetworkPolicy.openHttp("http://127.0.0.1:"+source.port()+"/qoder"),null,null)){
                BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));String first=reader.readLine();JSONObject frame=new JSONObject(first.substring(6));JSONObject call=frame.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0),expected=oracle.getJSONObject("qoderFirstEvent").getJSONObject("tool");
                check(call.getInt("index")==expected.getInt("index")&&call.getString("id").equals(expected.getString("id")),"Qoder first tool identity equals upstream oracle");check(call.getJSONObject("function").getString("name").equals(expected.getString("name")),"Qoder declared name");check(call.getJSONObject("function").getString("arguments").equals(expected.getString("args")),"Qoder partial args match npm, not delayed whole JSON");check(source.done.getCount()==1,"Qoder tool header before unfinished upstream");check(frame.getJSONArray("choices").getJSONObject(0).isNull("finish_reason"),"partial tool not an executable successful finish");source.release.countDown();String tail=remaining(reader);String wanted=oracle.getJSONObject("qoderNextEvent").getJSONObject("args").getString("text");boolean found=false;for(String l:tail.split("\n"))if(l.startsWith("data: ")&&!l.contains("[DONE]")){JSONObject c=new JSONObject(l.substring(6));JSONArray a=c.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optJSONArray("tool_calls");if(a!=null&&a.length()>0){JSONObject d=a.getJSONObject(0);found|=d.getJSONObject("function").optString("arguments").equals(wanted)&&!d.has("id");}}check(found,"Qoder later args-only event matches npm");check(tail.contains("[DONE]"),"Qoder final checks permit complete terminal");
            }finally{source.release.countDown();}
        }
    }
    static void antigravityCancel()throws Exception {
        PipedInputStream raw=new PipedInputStream(16384);PipedOutputStream writer=new PipedOutputStream(raw);AtomicBoolean closed=new AtomicBoolean();InputStream tracked=new FilterInputStream(raw){public void close()throws IOException{closed.set(true);super.close();}};
        String first="data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"FIRST\"}]}}]}}\n\n";writer.write(first.getBytes(StandardCharsets.UTF_8));writer.flush();InputStream result=AntigravityClient.toOpenAiStream(tracked,"synthetic-model");BufferedReader reader=new BufferedReader(new InputStreamReader(result,StandardCharsets.UTF_8));boolean saw=false;String s;while((s=reader.readLine())!=null)if(s.contains("FIRST")){saw=true;break;}check(saw,"Antigravity incremental first reply");result.close();check(closed.get(),"Antigravity downstream close propagates to original source, not pipe only");writer.close();result.close();
    }
    public static void main(String[] args)throws Exception {
        JSONObject oracle=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        for(String id:new String[]{"workbuddy","workbuddy-ai","loomy","raccoon","cline","github-copilot","openai","deepseek","siliconflow","openrouter","tencent"})generic(id);
        minimax(oracle);qoder(oracle);antigravityCancel();System.out.println("ProviderStreamContractHttpTest PASS: "+checks+" checks, controlled first-before-end HTTP matrix and npm tool oracle");
    }
}
