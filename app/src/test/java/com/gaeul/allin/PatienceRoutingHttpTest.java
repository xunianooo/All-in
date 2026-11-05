package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Rejection replay and protocol keepalive on real loopback sockets, no external services. */
public final class PatienceRoutingHttpTest {
 static void check(boolean b,String m) { if(!b)throw new AssertionError(m); }
 static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
 static final class Upstream extends NanoHTTPD implements AutoCloseable {
  final AtomicInteger calls=new AtomicInteger();final List<Long> times=Collections.synchronizedList(new ArrayList<>());
  volatile String errorType=""; volatile int rejectCount;volatile int status=429;volatile String code="rate_limit_error",retry="1";volatile long initialDelay,bodyDelay;
  Upstream() throws Exception { super("127.0.0.1",0);start(SOCKET_READ_TIMEOUT,true); }
  public Response serve(IHTTPSession session) {
   try {
    Map<String,String> f=new HashMap<>();session.parseBody(f);JSONObject p=new JSONObject(f.get("postData"));int n=calls.incrementAndGet();times.add(System.nanoTime());
    if(initialDelay>0)Thread.sleep(initialDelay);
    if(n<=rejectCount) { Response r=newFixedLengthResponse(Response.Status.lookup(status),"application/json",new JSONObject().put("error",new JSONObject().put("code",code).put("type",errorType).put("message","synthetic rejection")).toString());if(retry!=null)r.addHeader("Retry-After",retry);return r; }
    boolean anthropic=session.getUri().endsWith("/messages"),responses=session.getUri().endsWith("/responses");
    if(!p.optBoolean("stream")) return newFixedLengthResponse(Response.Status.OK,"application/json","{\"id\":\"fixture\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");
    String first=anthropic?"event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"usage\":{\"input_tokens\":2}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n":responses?"event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"r\",\"status\":\"in_progress\"}}\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n":"data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n";
    String last=anthropic?"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n":responses?"event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}\n\n":"data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n";
    InputStream body=new SequenceInputStream(new ByteArrayInputStream(bytes(first)),new ByteArrayInputStream(bytes(last)) {
     boolean delayed;public synchronized int read(byte[] b,int o,int l) { if(!delayed){delayed=true;try{Thread.sleep(bodyDelay);}catch(InterruptedException e){Thread.currentThread().interrupt();return -1;}}return super.read(b,o,l); }
    });return newChunkedResponse(Response.Status.OK,"text/event-stream",body);
   } catch(Exception e) {return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.getClass().getSimpleName());}
  }
  public void close(){stop();}
 }
 static final class Gateway extends NanoHTTPD implements AutoCloseable {
  final GatewayServer inner;
  Gateway(JSONObject cfg)throws Exception {super("127.0.0.1",0);inner=RoutingSetupOfflineTest.server(cfg);RoutingAuditOfflineTest.file(inner,Files.createTempDirectory("r2-http").resolve("config.json").toFile());Field f=GatewayServer.class.getDeclaredField("groupTurns");f.setAccessible(true);f.set(inner,new ConcurrentHashMap<String,Integer>());start(SOCKET_READ_TIMEOUT,true);}
  public Response serve(IHTTPSession s){return inner.serve(s);}public void close(){inner.stop();stop();}
 }
 static JSONObject config(Upstream u,int patience,boolean heartbeat,String protocol) {
  return new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth"))
   .put("privacy",new JSONObject().put("enabled",false)).put("resilience",new JSONObject().put("patienceMillis",patience).put("heartbeat",heartbeat))
   .put("providers",new JSONArray().put(new JSONObject().put("id","r2fixture").put("enabled",true).put("protocol",protocol).put("baseUrl","http://127.0.0.1:"+u.getListeningPort()+"/v1").put("keys",new JSONArray().put("synthetic-key")).put("models",new JSONArray().put("m"))));
 }
 static HttpURLConnection request(Gateway g,String protocol,boolean stream)throws Exception {
  String path="anthropic".equals(protocol)?"/v1/messages":"responses".equals(protocol)?"/v1/responses":"/v1/chat/completions";
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+path).openConnection(java.net.Proxy.NO_PROXY);c.setDoOutput(true);c.setRequestMethod("POST");c.setReadTimeout(12000);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer fixture-auth");
  JSONObject p=new JSONObject().put("model","r2fixture/m").put("stream",stream);
  if(protocol.equals("responses"))p.put("input","hello");else p.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).put("max_tokens",32);
  try(OutputStream out=c.getOutputStream()){out.write(bytes(p.toString()));}return c;
 }
 static String read(HttpURLConnection c)throws Exception {int status=c.getResponseCode();try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){return in==null?"":new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}}
 public static void main(String[]args)throws Exception {
  NetworkPolicy.configure(null);
  Path ledger=Files.createTempDirectory("r2-ledger");UsageLedger.init(ledger.toFile());
  List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<>());UsageLedger.setHooks(rows::add);
  Field sf=GatewayServer.class.getDeclaredField("RECENT_USAGE");sf.setAccessible(true);RecentUsageScheduler scheduler=(RecentUsageScheduler)sf.get(null);
  try {
  if(args.length==0 || args[0].equals("conflict")) {
   try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,false,"openai"))) {u.rejectCount=1;u.status=503;u.code="authentication_error";u.errorType="overloaded_error";HttpURLConnection c=request(g,"chat",false);int status=c.getResponseCode();read(c);check(status==503 && u.calls.get()==1,"conflicting auth/overload must not replay; status="+status+" calls="+u.calls.get());}
   System.out.println("conflict fixture passed");if(args.length>0)return;
  }
  if(args.length==0 || args[0].equals("special")) {
   for(String retry:new String[]{"1","120","missing"}) try(Upstream u=new Upstream()) {JSONObject cfg=config(u,10000,false,"openai");cfg.getJSONArray("providers").getJSONObject(0).put("type","subscription");try(Gateway g=new Gateway(cfg)) {u.rejectCount=1;u.retry="missing".equals(retry)?null:retry;HttpURLConnection c=request(g,"chat",false);int status=c.getResponseCode();read(c);check(status==429 && u.calls.get()==1,"special 429 must not enter patience retry="+retry+" status="+status+" calls="+u.calls.get());}}
   System.out.println("special fixture passed");if(args.length>0)return;
  }
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,false,"openai"))) {u.rejectCount=1;HttpURLConnection c=request(g,"chat",false);check(c.getResponseCode()==200,"429 patience success");read(c);check(u.calls.get()==2 && TimeUnit.NANOSECONDS.toMillis(u.times.get(1)-u.times.get(0))>=1900,"backoff send timeline");}
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,false,"openai"))) {u.rejectCount=1;u.status=503;u.code="overloaded_error";HttpURLConnection c=request(g,"chat",false);check(c.getResponseCode()==200,"explicit overload retry");read(c);check(u.calls.get()==2,"503 attempts");}
  for(String code:new String[]{"insufficient_quota","user_model_rate_limited","authentication_error"})try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,true,"openai"))) {u.rejectCount=10;u.code=code;HttpURLConnection c=request(g,"chat",true);check(c.getResponseCode()==429,"hard error before heartbeat "+code);read(c);check(u.calls.get()==1,"no hard replay");}
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,5000,true,"openai"))) {u.rejectCount=10;u.retry="120";HttpURLConnection c=request(g,"chat",true);check(c.getResponseCode()==429,"retry outside budget preserves HTTP");read(c);check(u.calls.get()==1,"no premature retry");}
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,true,"openai"))) {u.rejectCount=2;HttpURLConnection c=request(g,"chat",true);check(c.getResponseCode()==200,"held header");String body=read(c);check(body.contains(": heartbeat")&&body.contains("[DONE]")&&u.calls.get()==3,"heartbeat followed real success");}
  for(String protocol:new String[]{"chat","anthropic","responses"})try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,true,protocol.equals("chat")?"openai":protocol))) {u.initialDelay=3500;u.rejectCount=1;u.code="insufficient_quota";HttpURLConnection c=request(g,protocol,true);check(c.getResponseCode()==200,"comment committed HTTP "+protocol);String body=read(c);check(body.contains(": heartbeat")&&body.contains(protocol.equals("responses")?"response.failed":"error")&&!body.contains("[DONE]"),"late protocol failure "+protocol);check(u.calls.get()==1,"late error not replayed");}
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,true,"openai"))) {u.bodyDelay=3600;HttpURLConnection c=request(g,"chat",true);String body=read(c);check(body.contains("hello")&&body.contains(": heartbeat")&&body.contains("[DONE]"),"body stall heartbeat");}
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,0,true,"openai"))) {
   u.bodyDelay=500;int before=rows.size();HttpURLConnection c=request(g,"chat",true);check(c.getResponseCode()==200,"held successful stream");read(c);
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(scheduler.inFlight("r2fixture/m")!=0 && System.nanoTime()<end)Thread.sleep(5);
   check(scheduler.inFlight("r2fixture/m")==0,"held C1 lease released");
   List<UsageLedger.Row> current=new ArrayList<>(rows.subList(before,rows.size()));
   check(current.stream().filter(UsageLedger.Row::isTerminal).count()==1 && current.stream().filter(UsageLedger.Row::isAttempt).count()==1,"held one attempt terminal");
  }
  try(Upstream u=new Upstream();Gateway g=new Gateway(config(u,10000,true,"openai"))) {
   u.rejectCount=10;HttpURLConnection c=request(g,"chat",true);check(c.getResponseCode()==200,"cancel after heartbeat");
   try(InputStream in=c.getInputStream()) { check(in.read()>0,"received comment"); } finally {c.disconnect();}
   int sent=u.calls.get();Thread.sleep(4500);check(u.calls.get()<=sent+1,"cancel stops retry after detected write failure");
  }
  System.out.println("PatienceRoutingHttpTest passed");
  } finally {
   UsageLedger.setHooks(null);java.lang.reflect.Method close=UsageLedger.class.getDeclaredMethod("closeWriter");close.setAccessible(true);close.invoke(null);
  }

 }
}
