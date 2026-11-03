package com.gaeul.allin;
import com.gaeul.allin.DeepSeekWeb;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class DeepSeekWebTest {
 static void check(boolean x,String why){if(!x)throw new AssertionError(why);}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
 static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
 static void fails(RunnableEx r,String why) throws Exception {try{r.run();throw new AssertionError(why);}catch(IOException expected){}}
 interface RunnableEx {void run() throws Exception;}
 static DeepSeekWeb.Response response(String type,String value){return new DeepSeekWeb.Response(200,type,new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));}
 static final class Fake implements DeepSeekWeb.Transport {
  List<String> calls=new ArrayList<>();
  public DeepSeekWeb.Response request(String method,String url,Map<String,String> h,byte[] body) throws IOException {
   String path=url.substring(DeepSeekWeb.ORIGIN.length());calls.add(method+" "+path);check("Bearer fake-token".equals(h.get("authorization")),"authorization");
   if(path.endsWith("/users/current"))return response("application/json","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"user\":{\"id\":\"user-1\",\"name\":\"tester\"}}}}");
   if(path.endsWith("/chat_session/create"))return response("application/json","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"chat_session\":{\"id\":\"sid\"}}}}");
   if(path.endsWith("/create_pow_challenge"))return response("application/json","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"challenge\":{\"algorithm\":\"DeepSeekHashV1\",\"challenge\":\"5b56633fd742cf27d4510c0b213c40f673d3b7089bb6b7ec5a1b72b16fc79310\",\"salt\":\"salt\",\"expire_at\":1791638192759,\"difficulty\":1,\"signature\":\"sig\"}}}}");
   if(path.endsWith("/chat/completion")){check(h.containsKey("x-ds-pow-response"),"pow header");JSONObject payload=new JSONObject(new String(body,StandardCharsets.UTF_8));check(payload.optString("model_type").equals("expert"),"wire model");return response("text/event-stream","event: ready\ndata: {\"v\":{\"response\":{\"message_id\":42,\"fragments\":[{\"type\":\"THINK\",\"content\":\"why\"}]}}}\n\ndata: {\"p\":\"response/fragments\",\"v\":[{\"type\":\"RESPONSE\",\"content\":\"hello\"}]}\n\ndata: {\"p\":\"response/status\",\"v\":\"INCOMPLETE\"}\n\nevent: close\n\n");}
   if(path.endsWith("/chat/continue")){check(!h.containsKey("x-ds-pow-response"),"continue no pow");return response("text/event-stream","data: {\"v\":\" world\"}\n\ndata: {\"p\":\"response/status\",\"v\":\"FINISHED\"}\n\nevent: close\n\n");}
   throw new IOException("unexpected path "+path);
  }
 }
 public static void main(String[] args) throws Exception {
  if(args.length>0 && args[0].equals("hash")){for(int n:new int[]{0,1,135,136,137,300})System.out.println(hex(DeepSeekWeb.hash(repeat('x',n),23)));return;}
  String[] inputs={"","abc",repeat('a',135),repeat('a',136),repeat('a',137),repeat('x',300)};
  String[] expected={"e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3","f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506","1e3eb415bb0b3ecec08a6f71cf7c8d0591b5594752ba52802a2e60357834f998","680364b336f77918ed390287a581f96f1371599825acd1e348fa7649fcecbbab","dfeb7768d4d48053c083d849570e84cc8120520790f2d09cb56e40038081fab4","31e036e2f0c5e1761b18fba0c2907cc7abf9b40a58cf015e69704e3deb87fc96"};
  for(int i=0;i<inputs.length;i++){check(hex(DeepSeekWeb.hash(inputs[i],23)).equals(expected[i]),"wasm fixture "+i);try { check(Arrays.equals(DeepSeekWeb.hash(inputs[i],24),MessageDigest.getInstance("SHA3-256").digest(inputs[i].getBytes(StandardCharsets.UTF_8))),"sha3 "+i); } catch(java.security.NoSuchAlgorithmException e) { if(args.length==0 || !args[0].equals("art")) throw e; }}
  if(args.length>0 && args[0].equals("art"))System.out.println("ART hash passed");
  JSONObject challenge=new JSONObject("{\"algorithm\":\"DeepSeekHashV1\",\"challenge\":\"fecb1cc19748bf476ece2fc22bcfb94d974f3b630cbeb8d1b3ff1550dbd711eb\",\"salt\":\"salt\",\"expire_at\":1791638192759,\"difficulty\":12346,\"signature\":\"sig\"}");
  check(DeepSeekWeb.solvePow(challenge,30000)==12345,"solve");
  for(int length:new int[]{1,115,116,117,120,121,122,123,124,125,126,127,128,129,130,131,132,133,134,135,136,137,270}){String salt=repeat('s',length);int nonce=12;JSONObject v=new JSONObject().put("algorithm","DeepSeekHashV1").put("challenge",hex(DeepSeekWeb.hash(salt+"_123_"+nonce,23))).put("salt",salt).put("expire_at",123).put("difficulty",nonce+1);check(DeepSeekWeb.solvePow(v,5000)==nonce,"prefix boundary "+length);}
  JSONObject timeout=new JSONObject().put("algorithm","DeepSeekHashV1").put("challenge",repeat('0',64)).put("salt","synthetic").put("expire_at",123).put("difficulty",DeepSeekWeb.MAX_DIFFICULTY);long started=System.nanoTime();fails(()->DeepSeekWeb.solvePow(timeout,10),"deadline");check((System.nanoTime()-started)/1000000<2000,"bounded deadline");
  DeepSeekWeb.CancelToken during=new DeepSeekWeb.CancelToken();final boolean[] stopped={false};Thread worker=new Thread(()->{try{DeepSeekWeb.solvePow(timeout,10000,during);}catch(IOException cancelled){stopped[0]=true;}});worker.start();Thread.sleep(20);during.cancel();worker.join(2000);check(!worker.isAlive()&&stopped[0],"cancel during solve");
  Thread.currentThread().interrupt();try{fails(()->DeepSeekWeb.solvePow(timeout,1000),"interrupted");}finally{Thread.interrupted();}challenge.put("difficulty",5000001);fails(()->DeepSeekWeb.solvePow(challenge,1000),"bound");
  if(args.length>0 && args[0].equals("art"))System.out.println("ART PoW bounds passed");
  JSONObject req=new JSONObject("{\"model\":\"deepseek-reasoner\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
  Fake fake=new Fake();DeepSeekWeb web=new DeepSeekWeb(fake,"fake-token");check(web.validateToken().optString("name").equals("tester"),"validate");DeepSeekWeb.TurnResult result=web.run(req,null,null,null,30000);
  check(result.sessionId.equals("sid")&&result.stream.messageId==42&&result.stream.text.toString().equals("hello world")&&result.stream.reasoning.toString().equals("why"),"stream/continue");check(fake.calls.size()==5,"calls");
  JSONObject tools=new JSONObject(req.toString());tools.put("tools",new JSONArray("[{\"type\":\"function\"}]"));fails(()->DeepSeekWeb.prepare(tools,"x",null),"tools rejected");
  JSONObject image=new JSONObject("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com\"}}]}]}");fails(()->DeepSeekWeb.prepare(image,"x",null),"images rejected");
  check(DeepSeekWeb.prepare(new JSONObject(req.toString()).put("model","deepseek-chat"),"s",null).optString("model_type").equals("default"),"default alias");
  for(String k:new String[]{"max_tokens","max_completion_tokens","maxTokens"}) {JSONObject limited=new JSONObject(req.toString());limited.put(k,1);check(!DeepSeekWeb.prepare(limited,"s",null).has(k),"accept but do not forward unsupported limit");}
  DeepSeekWeb.StreamState st=new DeepSeekWeb.StreamState(null);
  st.apply(new JSONObject("{\"v\":{\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"Hi\"}]}}}"));
  st.apply(new JSONObject("{\"p\":\"response/fragments/0/content\",\"o\":\"SET\",\"v\":\"Hi there\"}"));
  st.apply(new JSONObject("{\"p\":\"response\",\"o\":\"BATCH\",\"v\":[{\"p\":\"accumulated_token_usage\",\"v\":9},{\"p\":\"status\",\"v\":\"FINISHED\"}]}"));
  check(st.text.toString().equals("Hi there")&&st.usage==9&&st.status.equals("FINISHED"),"snapshot and batch");
  fails(()->st.apply(new JSONObject("{\"p\":\"response/fragments/0/content\",\"o\":\"SET\",\"v\":\"changed\"}")),"replacement cannot retract streamed text");
  DeepSeekWeb.CancelToken cancel=new DeepSeekWeb.CancelToken();cancel.cancel();fails(()->web.run(req,"s",null,null,1000,cancel),"cancel preflight");
  for(int code:new int[]{401,403,429,500}) {final int status=code;DeepSeekWeb broken=new DeepSeekWeb((m,u,h,b)->new DeepSeekWeb.Response(status,"application/json",new ByteArrayInputStream(new byte[0])),"test");fails(()->broken.run(req,"s",null,null,1000),"HTTP error "+code);}
  // Real upstream envelope: identity in biz_data or biz_data.user, never a bare envelope.
  for(String bad:new String[]{"{\"code\":0,\"data\":{\"biz_code\":0}}","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{}}}","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"user\":{\"name\":\"only name\"}}}}","{\"code\":0,\"data\":{\"biz_code\":3,\"biz_data\":{\"id\":\"x\"}}}"}) {
   DeepSeekWeb w=new DeepSeekWeb((m,u,h,b)->response("application/json",bad),"test");fails(()->w.validateToken(),"invalid user envelope");
  }
  for(String good:new String[]{"{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"id\":\"user-1\"}}}","{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{\"user\":{\"mobile_number\":\"176****\"}}}}"}) {
   DeepSeekWeb w=new DeepSeekWeb((m,u,h,b)->response("application/json",good),"test");check(w.validateToken()!=null,"valid user envelope");
  }
  for(int code:new int[]{401,403,429,500}) {final int status=code; final String body="{\"error\":\"upstream detail\"}";
   DeepSeekWeb broken=new DeepSeekWeb((m,u,h,b)->new DeepSeekWeb.Response(status,"application/json",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),"test");
   try{broken.validateToken();throw new AssertionError("status exception missing");}catch(DeepSeekWeb.UpstreamException e){check(e.upstreamStatus==status&&e.gatewayStatus==(status==401||status==403?401:status==429?429:502)&&e.responseBody.equals(body),"HTTP status and body");}
  }
  for(String error:new String[]{"data: {\"error\":{\"message\":\"denied\"}}\n\n","data: {\"code\":7,\"msg\":\"denied\"}\n\n","data: {\"data\":{\"biz_code\":8}}\n\n","event: error\ndata: bad news\n\n","event: close\ndata: {\"error\":\"failed\"}\n\n","event: other\ndata: {\"error\":\"failed\"}\n\n"}) {
   DeepSeekWeb.StreamState bad=new DeepSeekWeb.StreamState(null);bad.status="FINISHED";
   try{DeepSeekWeb.readSse(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)),bad);throw new AssertionError("stream error accepted");}catch(DeepSeekWeb.UpstreamException e){check(e.gatewayStatus==502&&!e.responseBody.isEmpty(),"stream error detail");}
  }
  DeepSeekWeb.StreamState eof=new DeepSeekWeb.StreamState(null);DeepSeekWeb.readSse(new ByteArrayInputStream(new byte[0]),eof);check(!eof.closed&&eof.status==null,"empty EOF");
  DeepSeekWeb.StreamState tail=new DeepSeekWeb.StreamState(null);DeepSeekWeb.readSse(new ByteArrayInputStream("data: {\"p\":\"response/status\",\"v\":\"FINISHED\"}".getBytes(StandardCharsets.UTF_8)),tail);check("FINISHED".equals(tail.status)&&!tail.closed,"EOF dispatches last data");
  for(int status:new int[]{401,403,429,500}) {
   Fake base=new Fake();final boolean[] closed={false}; final String body="http failure body";
   DeepSeekWeb w=new DeepSeekWeb((m,u,h,b)->{
    if(!u.endsWith("/chat/completion"))return base.request(m,u,h,b);
    return new DeepSeekWeb.Response(status,"text/plain",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {public void close() throws IOException {closed[0]=true;super.close();}});
   },"fake-token");
   try{w.run(req,"s",null,null,30000);throw new AssertionError("completion error accepted");}catch(DeepSeekWeb.UpstreamException e){check(e.responseBody.equals(body)&&e.upstreamStatus==status&&e.gatewayStatus==(status==401||status==403?401:status==429?429:502)&&closed[0],"completion error close and mapping");}
  }
  for(String stream:new String[]{"", "data: {\"p\":\"response/status\",\"v\":\"FINISHED\"}\n\ndata: {\"error\":\"late failure\"}\n\n"}) {
   Fake base=new Fake();final boolean[] closed={false};
   DeepSeekWeb w=new DeepSeekWeb((m,u,h,b)->{
    if(!u.endsWith("/chat/completion"))return base.request(m,u,h,b);
    return new DeepSeekWeb.Response(200,"text/event-stream",new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)){public void close() throws IOException {closed[0]=true;super.close();}});
   },"fake-token");
   fails(()->w.run(req,"s",null,null,30000),"empty EOF and late error");check(closed[0],"failure closes stream");
  }
  { Fake base=new Fake();final boolean[] closed={false};DeepSeekWeb.CancelToken ct=new DeepSeekWeb.CancelToken();
   String stream="data: {\"v\":{\"response\":{\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"partial\"}]}}}\n\ndata: {\"p\":\"response/status\",\"v\":\"FINISHED\"}\n\n";
   DeepSeekWeb w=new DeepSeekWeb((m,u,h,b)->{if(!u.endsWith("/chat/completion"))return base.request(m,u,h,b);return new DeepSeekWeb.Response(200,"text/event-stream",new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)){public void close() throws IOException {closed[0]=true;super.close();}});},"fake-token");
   DeepSeekWeb.Listener listener=new DeepSeekWeb.Listener(){public void onText(String delta){ct.cancel();}public void onReasoning(String delta){}};
   fails(()->w.run(req,"s",null,listener,30000,ct),"cancel during stream");check(closed[0],"cancel closes stream");
  }
  System.out.println("DeepSeek offline tests passed");
 }
}
