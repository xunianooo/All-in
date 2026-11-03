package com.gaeul.allin;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class DeepSeekWebChatTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static DeepSeekWeb.Response response(int code,String type,String s){return new DeepSeekWeb.Response(code,type,new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));}
 static class Mock implements DeepSeekWeb.Transport {
  int calls; boolean fail;
  public DeepSeekWeb.Response request(String method,String url,Map<String,String> headers,byte[] body) throws IOException {
   check(url.startsWith(DeepSeekWeb.ORIGIN+"/"),"fixed origin"); check("Bearer secret".equals(headers.get("authorization")),"token"); calls++;
   if(url.endsWith("/create"))return response(200,"application/json","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"chat_session\":{\"id\":\"s\"}}}}");
   if(url.endsWith("/delete"))return response(200,"application/json","{\"code\":0,\"data\":{\"biz_code\":0}} ");
   if(url.endsWith("/create_pow_challenge"))return response(200,"application/json","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"challenge\":{\"algorithm\":\"DeepSeekHashV1\",\"challenge\":\"5b56633fd742cf27d4510c0b213c40f673d3b7089bb6b7ec5a1b72b16fc79310\",\"salt\":\"salt\",\"expire_at\":1791638192759,\"difficulty\":1,\"signature\":\"sig\"}}}}");
   if(url.endsWith("/completion")) {if(fail)return response(500,"application/json","secret upstream-body"); return response(200,"text/event-stream","data: {\"v\":{\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"hello\"}]}}}\n\ndata: {\"p\":\"response/status\",\"v\":\"FINISHED\"}\n\nevent: close\n\n");}
   throw new IOException("unexpected");
  }
 }
 static String read(InputStream in) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8"); }
 public static void main(String[] args) throws Exception {
  JSONObject req=new JSONObject("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
  Mock fake=new Mock();DeepSeekWebChat web=new DeepSeekWebChat("secret",fake); check("hello".equals(web.run(req,null).text),"non-stream");
  CountDownLatch done=new CountDownLatch(1); final CountDownLatch completed=done; boolean[] success={false}; InputStream in=new DeepSeekWebChat("secret",fake).stream(req,"alias",(ok,status,err)->{success[0]=ok;completed.countDown();});String data=read(in);check(done.await(2,TimeUnit.SECONDS)&&success[0]&&data.contains("hello")&&data.contains("[DONE]")&&!data.contains("secret"),"stream success");in.close();
  fake.fail=true;done=new CountDownLatch(1);final CountDownLatch failed=done;final boolean[] ok={true};in=new DeepSeekWebChat("secret",fake).stream(req,"alias",(v,status,e)->{ok[0]=v;failed.countDown();});data=read(in);check(done.await(2,TimeUnit.SECONDS)&&!ok[0]&&data.contains("upstream_error")&&!data.contains("[DONE]")&&!data.contains("secret")&&!data.contains("upstream-body"),"stream error");in.close();
  for(int upstream:new int[]{401,403,429,500}) {
   final int code=upstream; final int expected=(code==401||code==403)?401:code==429?429:502;
   Mock failing=new Mock(){public DeepSeekWeb.Response request(String method,String url,Map<String,String> h,byte[] body) throws IOException {if(url.endsWith("/completion"))return response(code,"application/json","secret upstream-body");return super.request(method,url,h,body);}};
   CountDownLatch recorded=new CountDownLatch(1);int[] status={0};String[] error={null};
   in=new DeepSeekWebChat("secret",failing).stream(req,"alias",(v,st,e)->{status[0]=st;error[0]=e;recorded.countDown();});data=read(in);
   check(recorded.await(2,TimeUnit.SECONDS)&&status[0]==expected&&data.contains("\"code\":"+expected)&&!data.contains("[DONE]")&&!data.contains("secret")&&!data.contains("upstream-body")&&!error[0].contains("secret"),"safe SSE status "+code); in.close();
  }
  fake.fail=false;
  for(String field:new String[]{"max_tokens","max_completion_tokens","maxTokens"}) {JSONObject limit=new JSONObject(req.toString()).put(field,1); int calls=fake.calls; web.validate(limit); check(calls==fake.calls,"limit accepted without network: "+field); check("hello".equals(new DeepSeekWebChat("secret",fake).run(limit,null).text),"limit request works: "+field);}
  JSONObject toolReq=new JSONObject("{\"model\":\"deepseek-chat\",\"tool_choice\":\"auto\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"parameters\":{\"type\":\"object\"}}}],\"messages\":[{\"role\":\"user\",\"content\":\"find it\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_old\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call_old\",\"content\":\"prior result\"}]}");
  Mock toolMock=new Mock(){public DeepSeekWeb.Response request(String method,String url,Map<String,String> headers,byte[] body) throws IOException {
   if(!url.endsWith("/completion"))return super.request(method,url,headers,body);
   try {String prompt=new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("prompt");check(prompt.contains("lookup")&&prompt.contains("prior result")&&prompt.contains("<tool_call>"),"tool prompt and history");}catch(JSONException e){throw new IOException(e);}
   return response(200,"text/event-stream","data: {\"v\":{\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"<tool_call>{\\\"name\\\":\\\"lookup\\\",\\\"arguments\\\":{\\\"x\\\":1}}</tool_call>\"}]}}}\n\ndata: {\"p\":\"response/status\",\"v\":\"FINISHED\"}\n\nevent: close\n\n");
  }};
  DeepSeekWebChat.Result tr=new DeepSeekWebChat("secret",toolMock).run(toolReq,null);check(tr.toolCalls.length()==1&&tr.text.isEmpty()&&"lookup".equals(tr.toolCalls.getJSONObject(0).getJSONObject("function").getString("name")),"non-stream tool call");
  CountDownLatch toolDone=new CountDownLatch(1);in=new DeepSeekWebChat("secret",toolMock).stream(toolReq,"alias",(v,st,e)->toolDone.countDown());data=read(in);check(toolDone.await(2,TimeUnit.SECONDS)&&data.contains("tool_calls")&&data.contains("lookup")&&data.contains("[DONE]")&&!data.contains("<tool_call>"),"stream tool call");in.close();
  final CountDownLatch entered=new CountDownLatch(1), closed=new CountDownLatch(1), finished=new CountDownLatch(1);
  Mock blocking=new Mock() { public DeepSeekWeb.Response request(String method,String url,Map<String,String> headers,byte[] body) throws IOException {
   if(!url.endsWith("/completion"))return super.request(method,url,headers,body);
   InputStream source=new InputStream() { volatile boolean stop; public int read() throws IOException { entered.countDown(); while(!stop) {try {Thread.sleep(10);} catch(InterruptedException e){throw new IOException(e);} } throw new IOException("closed"); } public void close(){stop=true;closed.countDown();} };
   return new DeepSeekWeb.Response(200,"text/event-stream",source);
  }};
  in=new DeepSeekWebChat("secret",blocking).stream(req,"alias",(v,status,e)->finished.countDown());
  check(entered.await(3,TimeUnit.SECONDS),"completion opened"); in.close();
  check(closed.await(3,TimeUnit.SECONDS)&&finished.await(3,TimeUnit.SECONDS),"cancel closes upstream");
  System.out.println("DeepSeekWebChat mock tests passed");
 }
}
