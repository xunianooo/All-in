package com.gaeul.allin;
import com.gaeul.allin.json.*;

/** Synthetic loopback upstream exercises the actual gateway transport and cache. */
public final class WorkBuddyCompatibilityGatewayHttpTest {
 static final String REF="{\"code\":11128,\"msg\":\"Illegal API invocation from an unapproved channel\",\"retry_policy\":{\"retry\":false}}";
 static void check(boolean x,String s){if(!x)throw new AssertionError(s);}
 static JSONObject config(){return VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("workbuddy","openai","https://copilot.tencent.com/v2","m")).put("setup",new JSONObject().put("done",true));}
 static JSONObject request(String path,boolean stream){
  JSONObject p=path.endsWith("/messages")?DeepSeekHarnessGatewayHttpTest.messages("workbuddy",stream):path.endsWith("/responses")?DeepSeekHarnessGatewayHttpTest.responses("workbuddy",stream):DeepSeekHarnessGatewayHttpTest.chat("workbuddy",stream);
  if(path.endsWith("/completions")) p.getJSONArray("messages").getJSONObject(0).put("content","You are an assistant powered by test model");
  else if(path.endsWith("/messages"))p.getJSONArray("system").getJSONObject(0).put("text","You are an assistant powered by test model");
  else p.getJSONArray("input").getJSONObject(0).put("content","You are an assistant powered by test model");
  return p;
 }
 static void stages(String path, boolean stream,int refusals) throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->n<=refusals?new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF):new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   JSONObject input=request(path,stream);String before=input.toString();
   DeepSeekHarnessGatewayHttpTest.Result res=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),path,input);
   check(res.status==200,"status "+path+" "+stream+" "+res.body);check(u.requests.size()==refusals+1,"send count "+path+" got "+u.requests.size());
   check(input.toString().equals(before),"input unchanged");
   JSONObject first=u.requests.get(0).body, second=u.requests.get(1).body;
   check(first.toString().contains("assistant powered by"),"original opener");
   check(second.toString().contains("assistant, powered by"),"comma opener");
   if(refusals==2)check(u.requests.get(2).body.toString().contains("<instructions>"),"system moved");
   if(path.endsWith("/completions")) for(VendorPolicyGatewayHttpTest.Request sent:u.requests){
    JSONArray m=sent.body.getJSONArray("messages");check(m.toString().contains("call-1") && m.toString().contains("DeepSeek Harness"),"tool arguments and result preserved");
   }
   int beforeNext=u.requests.size();
   check(DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),path,input).status==200,"learned response");
   check(u.requests.size()==beforeNext+1,"learned first send");
   String learned=u.requests.get(beforeNext).body.toString();
   check(learned.contains(refusals==2?"<instructions>":"assistant, powered by"),"learned stage");
  }
 }
 static void terminal(int status,String error,int expected) throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(status,"application/json",error));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)){
   DeepSeekHarnessGatewayHttpTest.Result res=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",false));
   check(res.status==status,"terminal status "+res.status);check(u.requests.size()==expected,"terminal sends "+u.requests.size());
  }
 }
 static void incomplete() throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF).truncated(40));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   DeepSeekHarnessGatewayHttpTest.Result res=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",false));
   check(res.status==400 && u.requests.size()==1,"short content-length no replay");
  }
 }
 static void scope() throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->n==1?new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF):new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   JSONObject input=request("/v1/chat/completions",false);
   check(DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input).status==200,"learn source");
   input.getJSONArray("messages").getJSONObject(0).put("content","You are an assistant powered by other model");
   check(DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input).status==200,"other prompt");
   check(u.requests.get(2).body.toString().contains("assistant powered by other model"),"prompt scope isolated");
  }
 }
 static void sharedCacheScope() throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->n==1?new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF):new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway a=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false);
      VendorPolicyGatewayHttpTest.Gateway b=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   WorkBuddyRefusalPolicy.Cache cache=new WorkBuddyRefusalPolicy.Cache();
   ResponsesGatewayOfflineTest.set(a.gateway,"workBuddyRefusalCache",cache); ResponsesGatewayOfflineTest.set(b.gateway,"workBuddyRefusalCache",cache);
   JSONObject input=request("/v1/chat/completions",false);
   check(DeepSeekHarnessGatewayHttpTest.post(a.getListeningPort(),"/v1/chat/completions",input).status==200,"scope training");
   check(DeepSeekHarnessGatewayHttpTest.post(b.getListeningPort(),"/v1/chat/completions",input).status==200,"shared same scope");
   check(u.requests.get(2).body.toString().contains("assistant, powered by"),"shared cache actually used");
   ResponsesGatewayOfflineTest.config(b.gateway).getJSONArray("providers").getJSONObject(0).getJSONArray("keys").put(0,"ck_different_87654321");
   check(DeepSeekHarnessGatewayHttpTest.post(b.getListeningPort(),"/v1/chat/completions",input).status==200,"different key response");
   check(u.requests.get(3).body.toString().contains("assistant powered by"),"key scope isolated");
  }
 }
 static void networkFailure() throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
   g.gateway.setVendorConnectionsForTests(url->{attempts.incrementAndGet();throw new java.io.IOException("synthetic factory failure");});
   DeepSeekHarnessGatewayHttpTest.Result r=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",false));
   check(r.status>=400 && attempts.get()==1 && u.requests.isEmpty(),"factory failure has one attempt: "+attempts.get());
  }
 }
 static void opaqueSystem() throws Exception {
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   JSONObject input=request("/v1/chat/completions",false);
   input.getJSONArray("messages").getJSONObject(0).put("signature","opaque-signature");
   DeepSeekHarnessGatewayHttpTest.Result r=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input);
   check(r.status==400 && u.requests.size()==1,"signed system cannot be rewritten, sends="+u.requests.size());
  }
 }
 static void usageAggregation() throws Exception {
  java.lang.reflect.Method absorb=GatewayServer.class.getDeclaredMethod("absorbUsage",UsageLedger.Row.class,JSONObject.class); absorb.setAccessible(true);
  UsageLedger.Row row=new UsageLedger.Row(); row.requestId="fixture";
  row.beginCompatUsageSend(); absorb.invoke(null,row,new JSONObject("{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":3,\"cache_write_tokens\":4}}"));
  row.beginCompatUsageSend(); absorb.invoke(null,row,new JSONObject("{\"prompt_tokens\":20,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":7}}"));
  absorb.invoke(null,row,new JSONObject("{\"prompt_tokens_details\":{\"cached_tokens\":7}}"));
  check(row.in==30 && row.out==7 && row.cacheRead==10 && row.cacheWrite==4,"two send cumulative and repeated partial cache");
  row.beginCompatUsageSend(); absorb.invoke(null,row,new JSONObject("{\"completion_tokens\":9}"));
  absorb.invoke(null,row,new JSONObject("{\"completion_tokens\":9}"));
  check(row.in==30 && row.out==16 && row.inputKnown && row.outputKnown && "partial".equals(row.usageSource),"missing input retains billed partial");
  check(row.countedInput()==30 && row.countedOutput()==16,"partial counted");
  UsageLedger.Row regular=new UsageLedger.Row(); absorb.invoke(null,regular,new JSONObject("{\"prompt_tokens\":4,\"completion_tokens\":2}"));
  check(regular.in==4 && regular.out==2 && "known".equals(regular.usageSource),"ordinary row unchanged");
 }
 static String usageSse(int input,int output) {
  JSONObject usage=new JSONObject().put("prompt_tokens",input).put("completion_tokens",output);
  return DeepSeekHarnessGatewayHttpTest.sse().replace("data: [DONE]", "data: "+new JSONObject().put("choices",new JSONArray()).put("usage",usage)+"\n\ndata: [DONE]");
 }
 static String refusalUsage(int input,int output) {
  return new JSONObject(REF).put("usage",new JSONObject().put("prompt_tokens",input).put("completion_tokens",output)).toString();
 }
 static void usageHttp(int sends,boolean missingFirst) throws Exception {
  java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("wb-usage-http-");
  java.util.List<UsageLedger.Row> rows=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  UsageLedger.init(dir.toFile()); UsageLedger.setHooks(rows::add);
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->{
       if(n<sends) return new VendorPolicyGatewayHttpTest.Reply(400,"application/json",missingFirst && n==1?REF:refusalUsage(n==1?2:3,n==1?1:2));
       return new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",usageSse(5,3));
      }); VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",false));
   check(result.status==200 && u.requests.size()==sends,"usage physical sends "+sends+" status="+result.status);
   check(rows.size()==1,"one ledger row per logical request, rows="+rows.size());
   UsageLedger.Row row=rows.get(0);
   int expectedIn=missingFirst?5:sends==2?7:10, expectedOut=missingFirst?3:sends==2?4:6;
   check(row.in==expectedIn && row.out==expectedOut,"usage totals "+row.in+"/"+row.out+" expected "+expectedIn+"/"+expectedOut);
   check((missingFirst?"partial":"known").equals(row.effectiveUsageSource()),"usage completeness "+row.effectiveUsageSource());
   JSONArray recent=UsageLedger.recentRequests(10); check(recent.length()==1,"one recent request "+recent);
   JSONObject recentRow=recent.getJSONObject(0);
   check(recentRow.getLong("totalTokens")==expectedIn+expectedOut,"recent total "+recentRow);
   check(recentRow.getJSONObject("diagnostics").getJSONObject("workbuddyCompatibility").getInt("sends")==sends,"diagnostic physical sends "+recentRow);
  } finally {
   UsageLedger.setHooks(null);
   try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(dir)) {
    files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{java.nio.file.Files.deleteIfExists(p);}catch(java.io.IOException ignored){}});
   }
  }
 }
 static void guardCandidate() throws Exception {
  JSONObject input=request("/v1/chat/completions",false);
  final String[] prepared=new String[2];
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(n==1?400:200,n==1?"application/json":"text/event-stream",n==1?REF:DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),u,false)) {
   check(DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input).status==200,"guard calibration");
   prepared[0]=u.requests.get(0).body.toString(); prepared[1]=u.requests.get(1).body.toString();
  }
  int budget=Math.max(input.toString().getBytes("UTF-8").length,Math.max(prepared[0].getBytes("UTF-8").length,prepared[1].getBytes("UTF-8").length));
  JSONObject candidate=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",new JSONObject(prepared[1]),true).body;
  check(candidate.toString().getBytes("UTF-8").length>budget,"guard fixture must reject second candidate");
  JSONObject cfg=config().put("guard",new JSONObject().put("enabled",true).put("maxBodyBytes",budget));
  WorkBuddyRefusalPolicy.Cache cache=new WorkBuddyRefusalPolicy.Cache();
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
   ResponsesGatewayOfflineTest.set(g.gateway,"workBuddyRefusalCache",cache);
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input);
   check(result.status==400 && u.requests.size()==2,"over-budget candidate not sent: "+u.requests.size());
   check(cache.size()==0,"rejected candidate not learned");
   DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input);
   check(u.requests.size()==4 && u.requests.get(2).body.toString().contains("assistant powered by"),"next request starts original after rejection");
  }
 }
 static void disconnectedUpstream() throws Exception {
  java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
  try(java.net.ServerSocket socket=new java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));
      VendorPolicyGatewayHttpTest.Upstream unused=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(config(),unused,false)) {
   java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
   Thread server=new Thread(()->{try(java.net.Socket c=socket.accept()) {
    c.setSoTimeout(5000); java.io.InputStream in=c.getInputStream(); java.io.ByteArrayOutputStream header=new java.io.ByteArrayOutputStream();
    int state=0,x; while(state<4 && (x=in.read())!=-1){header.write(x);state=state==0&&x==13?1:state==1&&x==10?2:state==2&&x==13?3:state==3&&x==10?4:0;}
    int len=0; for(String line:header.toString("UTF-8").split("\r\n")) if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
    check(state==4 && len>0,"disconnect fixture request headers");
    for(int i=0;i<len;i++) if(in.read()<0)throw new java.io.EOFException("disconnect fixture request body");
    c.setSoLinger(true,0);
   } catch(Throwable t){failure.set(t);}});
   server.setDaemon(true); server.start();
   g.gateway.setVendorConnectionsForTests(url->{attempts.incrementAndGet();return NetworkPolicy.openHttp("http://127.0.0.1:"+socket.getLocalPort()+new java.net.URL(url).getFile());});
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",false));
   server.join(6000); check(!server.isAlive() && failure.get()==null,"disconnect fixture completed: "+failure.get());
   check(result.status>=400 && attempts.get()==1 && unused.requests.isEmpty(),"disconnect no replay attempts="+attempts.get());
  }
 }
 static void privateError() throws Exception {
  JSONObject cfg=config().put("privacy",new JSONObject().put("enabled",true));
  String secret=VendorPolicyGatewayHttpTest.KEY, email="person@example.com";
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(400,"application/json",new JSONObject().put("error",new JSONObject().put("message","quota exhausted key "+secret)).toString()));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
   JSONObject input=request("/v1/chat/completions",false); input.getJSONArray("messages").getJSONObject(2).put("content","email "+email);
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",input);
   check(result.status==400 && u.requests.size()==1,"privacy fixture response");
   check(!u.requests.get(0).body.toString().contains(email),"email masked in actual upstream body");
   check(!result.body.contains(secret),"API key redacted from upstream error");
  }
 }
 static void delayedRefusalAfterHeartbeat() throws Exception {
  JSONObject cfg=config().put("resilience",new JSONObject().put("heartbeat",true));
  try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->{
       if(n==1) {try{Thread.sleep(3400);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new VendorPolicyGatewayHttpTest.Reply(400,"application/json",REF);}
       return new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse());
      });VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,u,false)) {
   DeepSeekHarnessGatewayHttpTest.Result res=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",request("/v1/chat/completions",true));
   check(res.status==200 && u.requests.size()==2,"explicit rejection may continue after waiting headers: "+res.status+" sends="+u.requests.size());
   check(res.body.contains(": heartbeat") && res.body.contains("hello") && res.body.contains("[DONE]"),"heartbeat then successful business stream");
  }
  RequestContext context=new RequestContext("/v1/chat/completions",NetworkPolicy.capture(),30000);
  context.headersWritten();check(!context.mayReplay() && context.mayContinueAfterRejection(),"dedicated explicit-rejection rule only");
  context.businessWritten();check(!context.mayContinueAfterRejection(),"business data prohibits continuation");context.close();
  RequestContext cancelled=new RequestContext("/v1/chat/completions",NetworkPolicy.capture(),30000);cancelled.cancel("synthetic");
  check(!cancelled.mayContinueAfterRejection(),"cancelled prohibits continuation");
 }
 public static void main(String[] a)throws Exception{
  delayedRefusalAfterHeartbeat();
  usageAggregation();
  usageHttp(2,false); usageHttp(3,false); usageHttp(2,true); guardCandidate(); disconnectedUpstream(); privateError();
  for(String p:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"}) for(boolean stream:new boolean[]{false,true})for(int n:new int[]{1,2})stages(p,stream,n);
  terminal(400,REF,3);terminal(403,REF,1);terminal(400,"{\"error\":\"other\"}",1);terminal(400,"{\"msg\":\"unapproved channel\"",1);
  incomplete(); scope(); sharedCacheScope(); networkFailure(); opaqueSystem();
  terminal(400,"{\"error\":{\"message\":\"quota exhausted\",\"code\":\"quota\"}}",1);
  terminal(400,"{\"error\":{\"message\":\"quota exhausted\"},\"unrelated\":\"unapproved channel\"}",1);
  System.out.println("WorkBuddyCompatibilityGatewayHttpTest passed");
 }
}
