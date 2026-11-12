package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.gaeul.allin.json.*;

public final class ResponsesGatewayOfflineTest {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static String read(InputStream in) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[1024]; int n; while((n=in.read(buf))!=-1)b.write(buf,0,n); return new String(b.toByteArray(),StandardCharsets.UTF_8); }
    static void set(Object o,String name,Object v) throws Exception {Field f=GatewayServer.class.getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static GatewayServer gateway(int port) throws Exception { return gateway(port,"openai"); }
    static GatewayServer gateway(int port,String protocol) throws Exception {
        TestPrivacyScope.initialize();
        Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        GatewayServer g=(GatewayServer)((sun.misc.Unsafe)field.get(null)).allocateInstance(GatewayServer.class);
        set(g,"totalRequests",new AtomicInteger()); set(g,"totalTokens",new AtomicLong()); set(g,"totalMasked",new AtomicInteger()); set(g,"totalContextDropped",new AtomicInteger()); set(g,"wbRoundRobin",new AtomicInteger());
        JSONObject p=new JSONObject().put("id","mock").put("baseUrl","http://127.0.0.1:"+port+"/v1").put("protocol",protocol).put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("test-key"));
        set(g,"configJson",new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","secret")).put("providers",new JSONArray().put(p)).put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",12)));
        return g;
    }
    static NanoHTTPD.IHTTPSession session(String body,String key) throws Exception { return session(body,key,"/v1/responses"); }
    static NanoHTTPD.IHTTPSession session(String body,String key,final String uri) throws Exception {
        final byte[] bytes=body.getBytes(StandardCharsets.UTF_8); final InputStream in=new ByteArrayInputStream(bytes); final Map<String,String> headers=new HashMap<String,String>();
        headers.put("host","127.0.0.1:3425");headers.put("content-length",String.valueOf(bytes.length));headers.put("content-type","application/json");if(key!=null)headers.put("authorization","Bearer "+key);
        return (NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(ResponsesGatewayOfflineTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->{
            switch(m.getName()) {case "getUri":return uri;case "getMethod":return NanoHTTPD.Method.POST;case "getHeaders":return headers;case "getInputStream":return in;case "getRemoteIpAddress":return "192.0.2.1";case "getParameters":return Collections.emptyMap();case "getParms":return Collections.emptyMap();default:return null;}
        });
    }
    static final class Mock implements AutoCloseable {
        ServerSocket server=new ServerSocket(0); Thread worker; volatile JSONObject received; volatile Throwable error; volatile String requestLine;
        Mock(String body,boolean stream) throws Exception { this(body,stream,200); }
        Mock(String body,boolean stream,int status) throws Exception { this(body,stream,status,null); }
        Mock(String body,boolean stream,int status,java.util.function.Predicate<JSONObject> accept) throws Exception {
            worker=new Thread(()->{try(Socket s=server.accept()) {
                InputStream in=s.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream(); int state=0,c;
                while((c=in.read())!=-1) {headers.write(c);state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0;if(state==4)break;}
                String h=new String(headers.toByteArray(),StandardCharsets.US_ASCII); requestLine=h.split("\\r\\n")[0]; int len=0;for(String line:h.split("\\r\\n"))if(line.toLowerCase().startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                byte[] b=new byte[len];int n=0;while(n<len){int x=in.read(b,n,len-n);if(x<0)throw new IOException("truncated request");n+=x;} received=new JSONObject(new String(b,StandardCharsets.UTF_8));
                int actualStatus=accept!=null && !accept.test(received)?400:status; String actualBody=actualStatus==400 && status==200?"{\"error\":\"unsupported request\"}":body;
                byte[] response=actualBody.getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+actualStatus+" "+(actualStatus==200?"OK":"Bad Request")+"\r\nContent-Type: "+(stream?"text/event-stream":"application/json")+"\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
            }catch(Throwable e){error=e;}});worker.start();
        }
        public void close() throws Exception {worker.join(5000);server.close();if(error!=null)throw new AssertionError(error);check(!worker.isAlive(),"mock timed out");}
    }
    static JSONObject config(GatewayServer gateway) throws Exception { Field f=GatewayServer.class.getDeclaredField("configJson");f.setAccessible(true);return (JSONObject)f.get(gateway); }
    static void nativeCases() throws Exception {
        String json="{\"id\":\"resp_native\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"m\",\"output\":[{\"type\":\"reasoning\",\"id\":\"r1\",\"encrypted_content\":\"cipher\",\"summary\":[]},{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}],\"usage\":{\"input_tokens\":7,\"output_tokens\":3,\"total_tokens\":10}}";
        String request="{\"model\":\"mock/m\",\"instructions\":\"rules\",\"input\":[{\"type\":\"reasoning\",\"id\":\"r1\",\"encrypted_content\":\"cipher\",\"summary\":[]},{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,eA==\"}]}],\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"store\":false,\"max_output_tokens\":100}";
        try(Mock mock=new Mock(json,false)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"responses");
            NanoHTTPD.Response r=g.serve(session(request,"secret"));String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==200 && new JSONObject(body).getJSONArray("output").getJSONObject(0).getString("encrypted_content").equals("cipher"),"native output "+body);
            check(mock.requestLine.startsWith("POST /v1/responses "),"native endpoint "+mock.requestLine);
            check(mock.received.getString("model").equals("m") && mock.received.getInt("max_output_tokens")==12 && mock.received.getJSONObject("reasoning").getString("summary").equals("auto") && mock.received.getJSONArray("include").getString(0).equals("reasoning.encrypted_content") && mock.received.toString().contains("cipher") && mock.received.toString().contains("input_image"),"native input "+mock.received);
        }
        try(Mock mock=new Mock(json,false)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"responses");
            config(g).put("middleware",new JSONObject().put("modelMap",new JSONObject().put("mock/alias","mock/m")));
            NanoHTTPD.Response r=g.serve(session(request.replace("mock/m","mock/alias"),"secret")); check(r.getStatus().getRequestStatus()==200,"mapped native");read(r.getData());check(mock.received.getString("model").equals("m"),"mapped model");
        }
        GatewayServer modified=gateway(1,"responses");config(modified).put("middleware",new JSONObject().put("systemPrompt","injected"));
        NanoHTTPD.Response rejection=modified.serve(session(request,"secret"));check(rejection.getStatus().getRequestStatus()==400 && read(rejection.getData()).contains("cannot be converted"),"native-only middleware rejection");
        GatewayServer chat=gateway(1,"openai"); NanoHTTPD.Response unsupported=chat.serve(session(request,"secret"));check(unsupported.getStatus().getRequestStatus()==400 && read(unsupported.getData()).contains("encrypted reasoning"),"cross protocol rejection");
        JSONObject orig=new JSONObject(request); JSONObject baseline=ResponsesBridge.projection(orig); JSONObject current=new JSONObject(baseline.toString()).put("model","m").put("max_tokens",12);
        ContextGuard.Config guard=ContextGuard.Config.fromJson(new JSONObject().put("enabled",true).put("maxOutputTokens",12));
        check(GatewayServer.nativeResponsesRequest(orig,baseline,current,"m",false,guard,null)!=null,"native guard cap");
        current.getJSONArray("messages").put(new JSONObject().put("role","system").put("content","new"));
        check(GatewayServer.nativeResponsesRequest(orig,baseline,current,"m",false,guard,null)==null,"no resurrect after semantic change");
        JSONObject tiny=new JSONObject().put("enabled",true).put("maxBodyBytes",80).put("maxOutputTokens",12);
        check(GatewayServer.nativeResponsesRequest(orig,baseline,baseline,"m",false,ContextGuard.Config.fromJson(tiny),null)==null,"native body exceeds guard");
        String simple="{\"model\":\"mock/m\",\"input\":\"hello\"}";
        try(Mock mock=new Mock(json,false)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"responses");config(g).put("middleware",new JSONObject().put("systemPrompt","injected"));
            NanoHTTPD.Response r=g.serve(session(simple,"secret"));String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==200 && mock.received.optString("instructions").contains("injected"),"middleware converted request "+body);
        }
        String plain="alice@example.com"; PrivacyMasker.Session mask=new PrivacyMasker.Session();String placeholder=PrivacyMasker.maskText(plain,mask,PrivacyMasker.Config.fromJson(new JSONObject()));
        String echo=new JSONObject().put("id","r").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",placeholder))))).toString();
        try(Mock mock=new Mock(echo,false)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"responses");config(g).put("privacy",new JSONObject().put("enabled",true));
            NanoHTTPD.Response r=g.serve(session(simple.replace("hello",plain),"secret"));String body=read(r.getData());
            check(body.contains(plain) && !mock.received.toString().contains(plain) && mock.received.toString().contains("MASK_EMAIL"),"native privacy roundtrip "+body);
        }
        GatewayServer blocked=gateway(1,"responses");config(blocked).put("middleware",new JSONObject().put("wordGuard",new JSONObject().put("enabled",true).put("mode","block").put("words",new JSONArray().put("hello"))));
        NanoHTTPD.Response blockedResponse=blocked.serve(session(simple,"secret"));check(blockedResponse.getStatus().getRequestStatus()==400,"word guard");
        String state="{\"model\":\"mock/m\",\"input\":\"hello\",\"previous_response_id\":\"r\"}";
        check(gateway(1,"responses").serve(session(state,"secret")).getStatus().getRequestStatus()==400,"server state rejected");
        try(Mock mock=new Mock(json,false)) { GatewayServer g=gateway(mock.server.getLocalPort(),"responses"); NanoHTTPD.Response r=g.serve(session(simple.replace("\"input\":\"hello\"","\"input\":\"hello\",\"store\":true"),"secret"));read(r.getData());check(mock.received.getBoolean("store"),"generic store preserved"); }
        String events="event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"r\",\"status\":\"in_progress\"}}\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"a\",\"output_index\":0,\"content_index\":0,\"delta\":\"ok\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}\n\n";
        try(Mock mock=new Mock(events,true)) { GatewayServer g=gateway(mock.server.getLocalPort(),"responses"); NanoHTTPD.Response r=g.serve(session(request.replace("\"max_output_tokens\":100","\"stream\":true,\"max_output_tokens\":100"),"secret"));String body=read(r.getData());check(body.contains("response.output_text.delta") && body.contains("response.completed") && body.contains("input_tokens"),"native SSE "+body); }
        try(Mock mock=new Mock(events.substring(0,events.indexOf("event: response.completed")),true)) { GatewayServer g=gateway(mock.server.getLocalPort(),"responses"); NanoHTTPD.Response r=g.serve(session(request.replace("\"max_output_tokens\":100","\"stream\":true,\"max_output_tokens\":100"),"secret"));String body=read(r.getData());check(body.contains("response.failed") && !body.contains("response.completed"),"truncated native SSE "+body); }
        String first=placeholder.substring(0,placeholder.length()/2), second=placeholder.substring(placeholder.length()/2);
        String split="event: response.output_text.delta\ndata: "+new JSONObject().put("type","response.output_text.delta").put("item_id","a").put("output_index",0).put("content_index",0).put("delta",first)+"\n\nevent: response.output_text.delta\ndata: "+new JSONObject().put("type","response.output_text.delta").put("item_id","a").put("output_index",0).put("content_index",0).put("delta",second)+"\n\nevent: response.output_text.done\ndata: "+new JSONObject().put("type","response.output_text.done").put("item_id","a").put("output_index",0).put("content_index",0).put("text",placeholder)+"\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"output\":[]}}\n\n";
        try(Mock mock=new Mock(split,true)) { GatewayServer g=gateway(mock.server.getLocalPort(),"responses");config(g).put("privacy",new JSONObject().put("enabled",true));NanoHTTPD.Response r=g.serve(session(simple.replace("hello",plain).replace("\"input\":","\"stream\":true,\"input\":"),"secret"));String body=read(r.getData());check(body.contains(plain) && !body.contains(placeholder) && body.contains("response.completed"),"split placeholder SSE "+body); }
    }
    static final class CopilotMock implements AutoCloseable {
        ServerSocket server=new ServerSocket(0); Thread worker; volatile Throwable error; volatile JSONObject request; volatile String first, second, secondHeaders;
        CopilotMock(String response) throws Exception {
            worker=new Thread(()->{try {
                for(int i=0;i<2;i++) try(Socket socket=server.accept()) {
                    InputStream in=socket.getInputStream();ByteArrayOutputStream raw=new ByteArrayOutputStream();int state=0,c;
                    while((c=in.read())!=-1) {raw.write(c);state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0;if(state==4)break;}
                    String headers=new String(raw.toByteArray(),StandardCharsets.US_ASCII);String path=headers.split("\\r\\n")[0]; int len=0;
                    for(String line:headers.split("\\r\\n")) if(line.toLowerCase().startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    byte[] bytes=new byte[len]; int n=0;while(n<len) {int count=in.read(bytes,n,len-n);if(count<0)throw new IOException("truncated");n+=count;}
                    String body;if(i==0) {first=path; body="{\"error\":{\"code\":\"unsupported_api_for_model\"}}";} else {second=path;secondHeaders=headers;request=new JSONObject(new String(bytes,StandardCharsets.UTF_8));body=response;}
                    byte[] result=body.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+(i==0?"400 Bad Request":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+result.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(result);out.flush();
                }
            }catch(Throwable e){error=e;}});worker.start();
        }
        public void close() throws Exception {worker.join(5000);server.close();if(error!=null)throw new AssertionError(error);check(!worker.isAlive(),"Copilot mock timed out");}
    }
    static void crossProtocolCases() throws Exception {
        String reply="{\"id\":\"r\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"think\"}]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]},{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":4,\"output_tokens\":3,\"total_tokens\":7}}";
        String chat="{\"model\":\"mock/m\",\"messages\":[{\"role\":\"system\",\"content\":\"rules\"},{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":100}";
        String anth="{\"model\":\"mock/m\",\"system\":\"rules\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":100}";
        String stream="event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"\"}}\n\nevent: response.reasoning_summary_text.delta\ndata: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"think\"}\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\nevent: response.function_call_arguments.delta\ndata: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{}\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":4,\"output_tokens\":3,\"total_tokens\":7}}}\n\n";
        for(boolean anthropic:new boolean[]{false,true}) for(boolean streaming:new boolean[]{false,true}) {
            try(Mock mock=new Mock(streaming?stream:reply,streaming)) {
                GatewayServer g=gateway(mock.server.getLocalPort(),"responses");
                String request=anthropic?anth:chat; if(streaming)request=request.replace("\"max_tokens\":100","\"stream\":true,\"max_tokens\":100");
                NanoHTTPD.Response r=g.serve(session(request,"secret",anthropic?"/v1/messages":"/v1/chat/completions")); String body=read(r.getData());
                check(r.getStatus().getRequestStatus()==200,"cross status "+body);
                check(mock.requestLine.startsWith("POST /v1/responses ") && mock.received.optInt("max_output_tokens")==12 && mock.received.optString("instructions").contains("rules") && mock.received.getJSONArray("input").length()>0,"cross request "+mock.received);
                if(streaming) check(body.contains(anthropic?"message_start":"chat.completion.chunk") && body.contains(anthropic?"message_stop":"[DONE]") && body.contains("hello") && body.contains(anthropic?"tool_use":"tool_calls"),"cross stream "+body);
                else { JSONObject parsed=new JSONObject(body); check(body.contains("hello") && body.contains("think") && body.contains(anthropic?"tool_use":"tool_calls") && body.contains(anthropic?"output_tokens":"completion_tokens"),"cross JSON "+body); check(anthropic?"message".equals(parsed.optString("type")):"chat.completion".equals(parsed.optString("object")),"cross type"); }
            }
        }
        try(Mock mock=new Mock(reply,false)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"openai");config(g).getJSONArray("providers").getJSONObject(0).put("id","github-copilot");
            String nativeRequest="{\"model\":\"github-copilot/m\",\"input\":\"hi\",\"reasoning\":{\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"]}";
            NanoHTTPD.Response r=g.serve(session(nativeRequest,"secret"));String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==200 && mock.requestLine.startsWith("POST /v1/responses ") && mock.received.getJSONObject("reasoning").getString("summary").equals("auto") && body.contains("response"),"Copilot native route "+body);
        }
        try(CopilotMock mock=new CopilotMock(reply)) {
            GatewayServer g=gateway(mock.server.getLocalPort(),"openai");
            config(g).getJSONArray("providers").getJSONObject(0).put("id","github-copilot");
            NanoHTTPD.Response r=g.serve(session(chat.replace("mock/m","github-copilot/m"),"secret","/v1/chat/completions"));String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==200 && body.contains("hello") && mock.first.startsWith("POST /v1/chat/completions ") && mock.second.startsWith("POST /v1/responses ") && mock.request.optInt("max_output_tokens")==12 && mock.secondHeaders.toLowerCase().contains("authorization: bearer test-key"),"Copilot fallback "+body);
        }
        GatewayServer unsupported=gateway(1,"responses");
        NanoHTTPD.Response unsupportedResponse=unsupported.serve(session(chat.replace("\"max_tokens\":100","\"stop\":[\"END\"],\"max_tokens\":100"),"secret","/v1/chat/completions"));
        check(unsupportedResponse.getStatus().getRequestStatus()==400 && read(unsupportedResponse.getData()).contains("stop"),"unsupported Chat field rejected");
        NanoHTTPD.Response unsupportedAnth=unsupported.serve(session(anth.replace("\"max_tokens\":100","\"stop_sequences\":[\"END\"],\"max_tokens\":100"),"secret","/v1/messages"));
        check(unsupportedAnth.getStatus().getRequestStatus()==400 && read(unsupportedAnth.getData()).contains("stop_sequences"),"unsupported Anthropic field rejected");
        String refusalReply="{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"No thanks\"}]}]}";
        try(Mock mock=new Mock(refusalReply,false)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session(anth,"secret","/v1/messages"));String body=read(r.getData());check(body.contains("No thanks") && body.contains("refusal"),"Anthropic refusal "+body);
        }
        String refusalEvents="data: {\"type\":\"response.refusal.delta\",\"delta\":\"No thanks\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        try(Mock mock=new Mock(refusalEvents,true)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session(anth.replace("\"max_tokens\":100","\"stream\":true,\"max_tokens\":100"),"secret","/v1/messages"));String body=read(r.getData());check(body.contains("No thanks") && body.contains("refusal") && body.contains("message_stop"),"Anthropic stream refusal "+body);
        }
        try(Mock mock=new Mock("bad test-key",false,400)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session(chat,"secret","/v1/chat/completions")); String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==400 && new JSONObject(body).getJSONObject("error").getString("type").equals("gateway_error") && !body.contains("test-key"),"safe translated error "+body);
        }
        try(Mock mock=new Mock("bad test-key",false,400)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session("{\"model\":\"mock/m\",\"input\":\"hi\"}","secret"));String body=read(r.getData());
            check(r.getStatus().getRequestStatus()==400 && new JSONObject(body).getJSONObject("error").getString("type").equals("gateway_error") && !body.contains("test-key"),"safe native error "+body);
        }
        try(Mock mock=new Mock("event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":7,\"response\":{\"status\":\"in_progress\"}}\n\n",true)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session("{\"model\":\"mock/m\",\"input\":\"hi\",\"stream\":true}","secret"));String body=read(r.getData());
            check(body.contains("response.failed") && body.contains("\"sequence_number\":8"),"failed sequence "+body);
        }
        try(Mock mock=new Mock("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n",true)) {
            NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"responses").serve(session(chat.replace("\"max_tokens\":100","\"stream\":true,\"max_tokens\":100"),"secret","/v1/chat/completions"));String body=read(r.getData());
            check(body.contains("error") && !body.contains("[DONE]"),"translated truncated stream "+body);
        }
    }
    public static void main(String[] args) throws Exception {
        UsageLedger.init(java.nio.file.Files.createTempDirectory("allin-responses-ledger").toFile());
        String request="{\"model\":\"mock/m\",\"input\":\"hello\",\"max_output_tokens\":100}";
        GatewayServer denied=gateway(1); NanoHTTPD.Response unauthorized=denied.serve(session(request,null)); check(unauthorized.getStatus().getRequestStatus()==401,"auth");
        NanoHTTPD.Response previous=denied.serve(session("{\"input\":\"x\",\"previous_response_id\":\"r\"}","secret"));check(previous.getStatus().getRequestStatus()==400 && read(previous.getData()).contains("previous_response_id"),"state rejected");
        set(denied,"configJson",new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","secret")).put("providers",new JSONArray()));
        NanoHTTPD.Response bare=denied.serve(session("{\"model\":\"unknown\",\"input\":\"x\"}","secret"));check(bare.getStatus().getRequestStatus()>=400 && read(bare.getData()).contains("error"),"bare model readable error");
        String chat="{\"id\":\"c\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}";
        try(Mock mock=new Mock(chat,false)) { NanoHTTPD.Response r=gateway(mock.server.getLocalPort()).serve(session(request,"secret"));String result=read(r.getData()); check(r.getStatus().getRequestStatus()==200,"status "+result);JSONObject o=new JSONObject(result);check(o.getJSONArray("output").length()==2 && o.getJSONObject("usage").getInt("total_tokens")==5,"json conversion "+result);check(mock.received.getInt("max_tokens")==12 && mock.received.getJSONArray("messages").getJSONObject(0).getString("content").equals("hello"),"guard and request"); }
        String chunks="data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n";
        try(Mock mock=new Mock(chunks,true)) { NanoHTTPD.Response r=gateway(mock.server.getLocalPort()).serve(session("{\"model\":\"mock/m\",\"input\":\"x\",\"stream\":true}","secret"));String result=read(r.getData());check(result.contains("response.created")&&result.contains("response.output_text.delta")&&result.contains("response.function_call_arguments.delta")&&result.contains("response.completed")&&result.contains("\\\"name\\\":\\\"f\\\"")==false,"SSE "+result);check(result.contains("\"name\":\"f\""),"tool name "+result); }
        String anth="{\"id\":\"msg1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"done\"},{\"type\":\"tool_use\",\"id\":\"call2\",\"name\":\"f\",\"input\":{\"x\":1}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":4,\"output_tokens\":6}}";
        try(Mock mock=new Mock(anth,false)) { NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"anthropic").serve(session("{\"model\":\"mock/m\",\"instructions\":\"rules\",\"input\":\"go\",\"max_output_tokens\":20}","secret"));String body=read(r.getData());JSONObject result=new JSONObject(body);check(r.getStatus().getRequestStatus()==200 && result.getJSONArray("output").length()==2 && result.getJSONObject("usage").getInt("output_tokens")==6,"Anthropic output "+body);check(mock.received.getInt("max_tokens")==12 && mock.received.optString("system").contains("rules"),"Anthropic input and guard"); }
        String anthStream="data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg1\",\"usage\":{\"input_tokens\":4}}}\n\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hey\"}}\n\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
        try(Mock mock=new Mock(anthStream,true)) { NanoHTTPD.Response r=gateway(mock.server.getLocalPort(),"anthropic").serve(session("{\"model\":\"mock/m\",\"input\":\"x\",\"stream\":true}","secret"));String result=read(r.getData());check(result.contains("response.output_text.delta")&&result.contains("hey")&&result.contains("response.completed")&&result.contains("output_tokens\":3"),"Anthropic stream "+result); }
        JSONObject codexReply=ResponsesBridge.responsesResponseToChat(new JSONObject("{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"think\"}]},{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":4,\"output_tokens\":3}}"),"m");check(codexReply.toString().contains("reasoning_content")&&codexReply.toString().contains("tool_calls")&&codexReply.getJSONObject("usage").getInt("prompt_tokens")==4,"Codex JSON");
        String failed=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8)),"m"));check(failed.contains("response.failed")&&!failed.contains("response.completed"),"truncated stream");
        String codex="data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call3\",\"name\":\"f\",\"arguments\":\"\"}}\n\ndata: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{}\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":4,\"output_tokens\":3,\"total_tokens\":7}}}\n\n";
        String converted=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(codex.getBytes(StandardCharsets.UTF_8)),"m"));check(converted.contains("tool_calls")&&converted.contains("call3")&&converted.contains("total_tokens\":7")&&converted.contains("[DONE]"),"Codex stream "+converted);
        JSONObject back=ResponsesBridge.chatToResponses(ResponsesBridge.toChat(new JSONObject("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,eA==\"}]},{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":\"ok\"}],\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"parameters\":{}}]}")),"m");check(back.toString().contains("input_image")&&back.toString().contains("function_call_output")&&back.getJSONArray("tools").getJSONObject(0).optString("name").equals("f"),"Codex request "+back);
        nativeCases(); crossProtocolCases();
        System.out.println("ResponsesGatewayOfflineTest OK");
    }
}
