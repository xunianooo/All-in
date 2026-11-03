package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class ResponsesGatewayOfflineTest {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static String read(InputStream in) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[1024]; int n; while((n=in.read(buf))!=-1)b.write(buf,0,n); return new String(b.toByteArray(),StandardCharsets.UTF_8); }
    static void set(Object o,String name,Object v) throws Exception {Field f=GatewayServer.class.getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static GatewayServer gateway(int port) throws Exception { return gateway(port,"openai"); }
    static GatewayServer gateway(int port,String protocol) throws Exception {
        Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        GatewayServer g=(GatewayServer)((sun.misc.Unsafe)field.get(null)).allocateInstance(GatewayServer.class);
        set(g,"totalRequests",new AtomicInteger()); set(g,"totalTokens",new AtomicLong()); set(g,"totalMasked",new AtomicInteger()); set(g,"totalContextDropped",new AtomicInteger()); set(g,"wbRoundRobin",new AtomicInteger());
        JSONObject p=new JSONObject().put("id","mock").put("baseUrl","http://127.0.0.1:"+port+"/v1").put("protocol",protocol).put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("test-key"));
        set(g,"configJson",new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","secret")).put("providers",new JSONArray().put(p)).put("privacy",new JSONObject().put("enabled",false)).put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",12)));
        return g;
    }
    static NanoHTTPD.IHTTPSession session(String body,String key) throws Exception {
        final byte[] bytes=body.getBytes(StandardCharsets.UTF_8); final InputStream in=new ByteArrayInputStream(bytes); final Map<String,String> headers=new HashMap<String,String>();
        headers.put("content-length",String.valueOf(bytes.length));headers.put("content-type","application/json");if(key!=null)headers.put("authorization","Bearer "+key);
        return (NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(ResponsesGatewayOfflineTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->{
            switch(m.getName()) {case "getUri":return "/v1/responses";case "getMethod":return NanoHTTPD.Method.POST;case "getHeaders":return headers;case "getInputStream":return in;case "getRemoteIpAddress":return "192.0.2.1";case "getParameters":return Collections.emptyMap();case "getParms":return Collections.emptyMap();default:return null;}
        });
    }
    static final class Mock implements AutoCloseable {
        ServerSocket server=new ServerSocket(0); Thread worker; volatile JSONObject received; volatile Throwable error; volatile String requestLine;
        Mock(String body,boolean stream) throws Exception {
            worker=new Thread(()->{try(Socket s=server.accept()) {
                InputStream in=s.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream(); int state=0,c;
                while((c=in.read())!=-1) {headers.write(c);state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0;if(state==4)break;}
                String h=new String(headers.toByteArray(),StandardCharsets.US_ASCII); requestLine=h.split("\\r\\n")[0]; int len=0;for(String line:h.split("\\r\\n"))if(line.toLowerCase().startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                byte[] b=new byte[len];int n=0;while(n<len){int x=in.read(b,n,len-n);if(x<0)throw new IOException("truncated request");n+=x;} received=new JSONObject(new String(b,StandardCharsets.UTF_8));
                byte[] response=body.getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(stream?"text/event-stream":"application/json")+"\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
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
        GatewayServer chat=gateway(1,"openai"); NanoHTTPD.Response unsupported=chat.serve(session(request,"secret"));check(unsupported.getStatus().getRequestStatus()==400 && read(unsupported.getData()).contains("include"),"cross protocol rejection");
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
        String codex="data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call3\",\"name\":\"f\",\"arguments\":\"\"}}\n\ndata: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{}\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":4,\"output_tokens\":3,\"total_tokens\":7}}}\n\n";
        String converted=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(codex.getBytes(StandardCharsets.UTF_8)),"m"));check(converted.contains("tool_calls")&&converted.contains("call3")&&converted.contains("total_tokens\":7")&&converted.contains("[DONE]"),"Codex stream "+converted);
        JSONObject back=ResponsesBridge.chatToResponses(ResponsesBridge.toChat(new JSONObject("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,eA==\"}]},{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":\"ok\"}],\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"parameters\":{}}]}")),"m");check(back.toString().contains("input_image")&&back.toString().contains("function_call_output")&&back.getJSONArray("tools").getJSONObject(0).optString("name").equals("f"),"Codex request "+back);
        nativeCases();
        System.out.println("ResponsesGatewayOfflineTest OK");
    }
}
