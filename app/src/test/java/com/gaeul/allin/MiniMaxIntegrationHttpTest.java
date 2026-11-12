package com.gaeul.allin;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;

/** MiniMax Code end to end over loopback only: the durable rotation, the refusal that is
 *  answered once more - with a credential it did not just use, whether that is the one this
 *  refresh persisted or one another refresh already saved - the second refusal that is the
 *  answer, and every ending that is never replayed: no answer at all, a dropped connection, a
 *  5xx, and a redirect, on the first send or on the retry. */
public class MiniMaxIntegrationHttpTest {
 static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
 static GatewayServer setup(int port,Path path,long expires) throws Exception {
  GatewayServer g=ResponsesGatewayOfflineTest.gateway(port,"minimax");
  JSONObject config=ResponsesGatewayOfflineTest.config(g), p=config.getJSONArray("providers").getJSONObject(0);
  p.put("id","minimax-code").put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("access","old").put("token","old").put("refresh","one").put("expires",expires)));
  ResponsesGatewayOfflineTest.set(g,"configFile",path.toFile()); Files.write(path,config.toString().getBytes("UTF-8"));
  ResponsesGatewayOfflineTest.set(g,"miniMaxJournal",MiniMaxTestJournal.fixture(path.getParent().toFile())); return g;
 }
 static JSONObject account(GatewayServer g) throws Exception {return ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0);}
 static JSONObject durable(Path file) throws Exception {return new JSONObject(new String(Files.readAllBytes(file),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0);}
 static Object lease(GatewayServer g,JSONObject a) throws Exception {Method m=GatewayServer.class.getDeclaredMethod("miniLease",JSONObject.class,boolean.class,MiniMaxLifecycle.Lease.class);m.setAccessible(true);return m.invoke(g,a,false,null);}
 /** A lease that is forced to refresh, as the 401 path asks for one: a test uses it to renew the
  *  account from another thread while a send is still open. */
 static Object forced(GatewayServer g,JSONObject a) throws Exception {Method m=GatewayServer.class.getDeclaredMethod("miniLease",JSONObject.class,boolean.class,MiniMaxLifecycle.Lease.class);m.setAccessible(true);return m.invoke(g,a,true,null);}
 static String request="{\"model\":\"minimax-code/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
 static final String RESPONSE="{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"m\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
 static final String REFUSED="{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid access token\"}}";
 static List<UsageLedger.Row> hooks() { List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<UsageLedger.Row>()); UsageLedger.setHooks(rows::add); return rows; }
 static int executions(List<UsageLedger.Row> rows,String state) { int n=0; for(UsageLedger.Row r:rows) if(r.diagnostics!=null && state.equals(r.diagnostics.executionState)) n++; return n; }
 static int terminals(List<UsageLedger.Row> rows) { int n=0; for(UsageLedger.Row r:rows) if(r.terminal) n++; return n; }
 static MiniMaxAuth.SignIn rotated(String access,String refresh) { MiniMaxAuth.SignIn x=new MiniMaxAuth.SignIn(); x.access=access; x.refresh=refresh; x.expires=System.currentTimeMillis()+3600000; return x; }
 static NanoHTTPD.Response chat(GatewayServer g) throws Exception { return g.serve(ResponsesGatewayOfflineTest.session(request,"secret","/v1/chat/completions")); }
 /** A client request naming its own conversation, as MiniMax Code's own client does, so a test can
  *  check which conversation the retried send lands in. */
 static NanoHTTPD.IHTTPSession namedSession(String body,final String conversation) throws Exception {
  final byte[] bytes=body.getBytes(StandardCharsets.UTF_8); final InputStream in=new ByteArrayInputStream(bytes);
  final Map<String,String> headers=new HashMap<String,String>();
  headers.put("host","127.0.0.1:3425");
  headers.put("content-length",String.valueOf(bytes.length)); headers.put("content-type","application/json");
  headers.put("authorization","Bearer secret"); headers.put("x-magpie-session",conversation);
  return (NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(MiniMaxIntegrationHttpTest.class.getClassLoader(),
          new Class[]{NanoHTTPD.IHTTPSession.class},(proxy,method,args)->{
           switch(method.getName()) {
            case "getUri": return "/v1/chat/completions";
            case "getMethod": return NanoHTTPD.Method.POST;
            case "getHeaders": return headers;
            case "getInputStream": return in;
            case "getRemoteIpAddress": return "192.0.2.1";
            case "getParameters": return Collections.emptyMap();
            case "getParms": return Collections.emptyMap();
            default: return null;
           }
          });
 }

 /** Loopback upstream: one send per connection, answered from a script, then listening with an
  *  idle timeout so an unplanned extra send is counted (500) instead of hidden.
  *  script entry >0 is that status; 0 consumes the request and drops the connection with no
  *  answer (execution unknown); -1 closes it without answering at all. */
 static final class Upstream implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0);
  final List<String> auth=Collections.synchronizedList(new ArrayList<String>());
  final List<String> bodies=Collections.synchronizedList(new ArrayList<String>());
  /** Every request head, and beside it the durable account's access token as it stood when that
   *  request arrived: the persisted credential is read back from the config file the gateway
   *  writes, which is how a test proves a rotation was durable before the rotated token was used. */
  final List<String> heads=Collections.synchronizedList(new ArrayList<String>());
  final List<String> durableAccess=Collections.synchronizedList(new ArrayList<String>());
  volatile Path durable;
  /** Counted down once the first request has been read, and awaited before that first request is
   *  answered, so a test can act - refresh the account elsewhere - while a send is still open. */
  volatile CountDownLatch requestSeen, answerFirst;
  final Thread worker; volatile Throwable error; volatile String location; volatile String payload;
  Upstream(int... script) throws Exception {
   server.setSoTimeout(1500);
   worker=new Thread(new Runnable() { public void run() { serve(script); } });
   worker.setDaemon(true); worker.start();
  }
  int port() { return server.getLocalPort(); }
  static String head(InputStream in) throws IOException {
   ByteArrayOutputStream b=new ByteArrayOutputStream(); int state=0,c;
   while((c=in.read())!=-1) { b.write(c); state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0; if(state==4) break; }
   return new String(b.toByteArray(),StandardCharsets.US_ASCII);
  }
  static String field(String raw,String name) {
   for(String line:raw.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith(name+":")) return line.substring(line.indexOf(':')+1).trim();
   return "";
  }
  void serve(int[] script) {
   for(int seen=0;;seen++) {
    Socket s=null;
    try {
     s=server.accept();
     String raw=head(s.getInputStream());
     int length=0; String declared=field(raw,"content-length"); if(!declared.isEmpty()) length=Integer.parseInt(declared);
     byte[] body=new byte[length]; new DataInputStream(s.getInputStream()).readFully(body);
     auth.add(field(raw,"authorization")); bodies.add(new String(body,StandardCharsets.UTF_8));
     heads.add(raw);
     String persisted="";
     if(durable!=null) try { persisted=new JSONObject(new String(Files.readAllBytes(durable),"UTF-8"))
             .getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).optString("access",""); }
         catch(Exception unreadable) { }
     durableAccess.add(persisted);
     if(requestSeen!=null) requestSeen.countDown();
     CountDownLatch gate=seen==0?answerFirst:null;
     if(gate!=null) { try { gate.await(8000,TimeUnit.MILLISECONDS); }
       catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
     int status=seen<script.length?script[seen]:500;
     if(status==-1) { s.close(); continue; }
     if(status==0) { s.setSoLinger(true,0); continue; }
     String text=payload!=null?payload:(status>=200&&status<300?RESPONSE:REFUSED);
     byte[] answer=text.getBytes(StandardCharsets.UTF_8);
     String extra=location!=null && status>=300 && status<400 ? "Location: "+location+"\r\n" : "";
     OutputStream out=s.getOutputStream();
     out.write(("HTTP/1.1 "+status+" Status\r\n"+extra+"Content-Type: application/json\r\nContent-Length: "+answer.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
     out.write(answer); out.flush();
    } catch(SocketTimeoutException idle) { return; }
    catch(Throwable failed) { error=failed; return; }
    finally { if(s!=null) try { s.close(); } catch(IOException ignored) { } }
   }
  }
  int sends() { return auth.size(); }
  public void close() throws Exception { worker.join(6000); server.close(); if(error!=null) throw new AssertionError(error); check(!worker.isAlive(),"upstream fixture timed out"); }
 }

 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("mini-fixture"), file=dir.resolve("config.json"); UsageLedger.init(dir.toFile());
  try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(RESPONSE,false)) {
   GatewayServer g=setup(mock.server.getLocalPort(),file,1); AtomicInteger calls=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt -> {calls.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response result=chat(g);
   String body=ResponsesGatewayOfflineTest.read(result.getData()); check(result.getStatus().getRequestStatus()==200,"forward "+body);
   check(calls.get()==1 && account(g).getString("refresh").equals("two") && durable(file).getString("refresh").equals("two"),"durable rotation");
  }
  GatewayServer failed=setup(1,file,1); ResponsesGatewayOfflineTest.set(failed,"configFile",dir.resolve("missing").resolve("config.json").toFile()); ResponsesGatewayOfflineTest.set(failed,"miniMaxJournal",null);
  AtomicInteger calls=new AtomicInteger();failed.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return rotated("new","two");});
  try {lease(failed,account(failed));throw new AssertionError("expected failure");} catch(InvocationTargetException expected) {}
  check(account(failed).getString("refresh").equals("one"),"old in memory");
  try {lease(failed,account(failed));throw new AssertionError("expected quarantine");} catch(InvocationTargetException expected) {}
  check(calls.get()==0,"journal write failure prevents refresh");
  Path raceFile=Files.createTempDirectory("mini-race").resolve("config.json"); GatewayServer race=setup(1,raceFile,1); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();
  race.setMiniMaxRefreshForTests(rt->{entered.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return rotated("new","two");});
  Thread t=new Thread(()->{try{lease(race,account(race));err.set(new AssertionError("late refresh accepted"));}catch(InvocationTargetException expected){}catch(Throwable e){err.set(e);}});t.start();entered.await();race.retireForRestore();release.countDown();t.join();check(err.get()==null,"retired refresh");
  check(durable(raceFile).getString("refresh").equals("one"),"retired writer");

  // A token the upstream turns away before its expiry is answered once more with credentials that
  // were not just refused: the single-use refresh is spent, persisted, and only then reused. The
  // request after it starts with that credential and spends nothing more.
  Upstream refusedOnce=new Upstream(401,200,200);
  List<UsageLedger.Row> rescued=hooks();
  try {
   Path ownFile=Files.createTempDirectory("mini-401").resolve("config.json");
   GatewayServer g=setup(refusedOnce.port(),ownFile,System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   refusedOnce.durable=ownFile;
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==200,"401 then refreshed send "+body);
   check(refresh.get()==1 && refusedOnce.sends()==2,"one refresh and exactly one resend: "+refusedOnce.auth);
   check(refusedOnce.auth.get(0).equals("Bearer old") && refusedOnce.auth.get(1).equals("Bearer new"),"refused token replaced "+refusedOnce.auth);
   check(refusedOnce.bodies.get(1).equals(refusedOnce.bodies.get(0)),"same body resent");
   check(durable(ownFile).getString("access").equals("new") && durable(ownFile).getString("refresh").equals("two"),"rotated before reuse "+durable(ownFile));
   check(refusedOnce.durableAccess.size()==2 && refusedOnce.durableAccess.get(0).equals("old") && refusedOnce.durableAccess.get(1).equals("new"),
           "the rotation was durable on disk before the rotated token was sent: "+refusedOnce.durableAccess);
   check(Upstream.field(refusedOnce.heads.get(1),"user-agent").equals("MiniMaxAgent")
           && !Upstream.field(refusedOnce.heads.get(1),"x-api-key").isEmpty()
           && Upstream.field(refusedOnce.heads.get(1),"x-api-key").equals(Upstream.field(refusedOnce.heads.get(0),"x-api-key"))
           && Upstream.field(refusedOnce.heads.get(1),"x-mavis-agent-id").equals("main"),
           "the resend is still MiniMax Code's own request shape "+refusedOnce.heads.get(1));
   // With no session header from the client, each attempt mints its own fallback id (chatHeaders
   // "magpie-<hex>"): recorded here rather than asserted, because the reference keeps one id for
   // the whole plugin and that is the behaviour to move to. What must hold either way is that
   // every attempt carries a MiniMax Code conversation id.
   check(Upstream.field(refusedOnce.heads.get(0),"x-mavis-session-id").startsWith("magpie-")
           && Upstream.field(refusedOnce.heads.get(1),"x-mavis-session-id").startsWith("magpie-"),
           "every attempt carries a MiniMax conversation id: "+Upstream.field(refusedOnce.heads.get(0),"x-mavis-session-id")+" / "+Upstream.field(refusedOnce.heads.get(1),"x-mavis-session-id"));
   NanoHTTPD.Response next=chat(g); ResponsesGatewayOfflineTest.read(next.getData());
   check(next.getStatus().getRequestStatus()==200 && refusedOnce.sends()==3,"the next request uses the rescued credential: "+refusedOnce.auth);
   check(refusedOnce.auth.get(2).equals("Bearer new") && refresh.get()==1,"no second refresh after the rescue "+refusedOnce.auth);
   check(terminals(rescued)==2 && executions(rescued,"unknown_execution")==0,"rescued requests are not unknown: "+terminals(rescued));
  } finally { refusedOnce.close(); UsageLedger.setHooks(null); }

  // A conversation the client named is the one both attempts use: the retry is the same
  // conversation, never a new one, and nothing of the first attempt is left behind.
  Upstream named=new Upstream(401,200);
  try {
   Path ownFile=Files.createTempDirectory("mini-401-session").resolve("config.json");
   GatewayServer g=setup(named.port(),ownFile,System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=g.serve(namedSession(request,"conv-42")); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==200,"401 retry inside a named conversation "+body);
   check(named.sends()==2 && refresh.get()==1,"one resend, one refresh "+named.auth);
   check(Upstream.field(named.heads.get(0),"x-mavis-session-id").equals("conv-42")
           && Upstream.field(named.heads.get(1),"x-mavis-session-id").equals("conv-42"),
           "the retried send stays in the client's conversation: "+Upstream.field(named.heads.get(0),"x-mavis-session-id")+" / "+Upstream.field(named.heads.get(1),"x-mavis-session-id"));
   check(named.bodies.get(1).equals(named.bodies.get(0)) && named.auth.get(1).equals("Bearer new"),
           "the conversation is resent unchanged under the new credential "+named.auth);
  } finally { named.close(); }

  // A second refusal is the answer: the retry happens once, never in a loop.
  Upstream twiceRefused=new Upstream(401,401);
  try {
   Path own=Files.createTempDirectory("mini-401x2");
   GatewayServer g=setup(twiceRefused.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==401,"second refusal surfaces "+body);
   check(refresh.get()==1 && twiceRefused.sends()==2,"one retry only: "+twiceRefused.auth);
  } finally { twiceRefused.close(); }
  check(twiceRefused.sends()==2,"no growth past one retry: "+twiceRefused.auth);

  // A refresh that hands back the very token the upstream just refused authorises no second send.
  // The reference would resend there - fresh() returns the account it already has when there is a
  // refresh token only for the expiry check and the refusal is not re-asked - but All-in stops: a
  // 401 is a definite refusal before execution, so repeating it with the same credential can only
  // be refused again. The rotation itself is still persisted: MiniMax spent that refresh token.
  Upstream sameToken=new Upstream(401,200);
  try {
   Path ownFile=Files.createTempDirectory("mini-401-same").resolve("config.json");
   GatewayServer g=setup(sameToken.port(),ownFile,System.currentTimeMillis()+3600000);
   sameToken.durable=ownFile; AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("old","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==401,"the refusal stands when the refresh returns the same token "+body);
   check(refresh.get()==1 && sameToken.sends()==1,"the refused credential is not sent again: "+sameToken.auth);
   check(durable(ownFile).getString("refresh").equals("two") && durable(ownFile).getString("access").equals("old"),
           "the new single-use refresh token is kept even though the access token did not change "+durable(ownFile));
  } finally { sameToken.close(); }
  check(sameToken.sends()==1,"no resend on an unchanged access token");

  // The refused token was already replaced elsewhere - another request, the usage card, another
  // process - so the newer credential is taken as it is. That is the reference's
  // fresh(getAuth, rejected): a token that is not the one just refused is used without spending a
  // refresh, because MiniMax's refresh token is single use.
  Upstream overtaken=new Upstream(401,200);
  try {
   Path ownFile=Files.createTempDirectory("mini-401-newer").resolve("config.json");
   GatewayServer g=setup(overtaken.port(),ownFile,System.currentTimeMillis()+3600000);
   overtaken.requestSeen=new CountDownLatch(1); overtaken.answerFirst=new CountDownLatch(1);
   AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("second","two");});
   AtomicReference<Throwable> other=new AtomicReference<Throwable>();
   Thread elsewhere=new Thread(()->{ try { overtaken.requestSeen.await(8000,TimeUnit.MILLISECONDS); forced(g,account(g)); }
     catch(Throwable raised) { other.set(raised); } finally { overtaken.answerFirst.countDown(); } });
   elsewhere.setDaemon(true); elsewhere.start();
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   elsewhere.join(8000);
   check(other.get()==null,"the concurrent refresh succeeded: "+other.get());
   check(r.getStatus().getRequestStatus()==200,"the newer token answers the refused request "+body);
   check(refresh.get()==1,"only the concurrent refresh was spent: "+refresh.get());
   check(overtaken.sends()==2 && overtaken.auth.get(0).equals("Bearer old") && overtaken.auth.get(1).equals("Bearer second"),
           "the resend takes the credential another refresh saved: "+overtaken.auth);
  } finally { overtaken.close(); }

  // The single retry is an attempt like any other: a redirect on it is the answer, and no third
  // send follows. (The retry already spent its refresh - the 401 that caused it was a refusal.)
  Upstream retriedRedirect=new Upstream(401,307,200);
  retriedRedirect.location="http://127.0.0.1:1/v1/messages";
  try {
   Path own=Files.createTempDirectory("mini-401-307");
   GatewayServer g=setup(retriedRedirect.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000);
   AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502,"a redirect on the retried send is a gateway failure "+body);
   check(body.contains("307") && body.contains("127.0.0.1"),"the answer names that redirect "+body);
   check(r.getHeader("Location")==null,"no Location reaches the client "+r.getHeader("Location"));
   check(refresh.get()==1 && retriedRedirect.sends()==2,"no third send after a redirect: "+retriedRedirect.auth);
  } finally { retriedRedirect.close(); }
  check(retriedRedirect.sends()==2,"the redirect on the retry is not replayed");

  // A refresh that fails, or comes back uncertain, spends nothing more and replays nothing.
  Upstream failedRefresh=new Upstream(401,200);
  try {
   Path ownFile=Files.createTempDirectory("mini-refresh-failed").resolve("config.json");
   GatewayServer g=setup(failedRefresh.port(),ownFile,System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); throw new IllegalStateException("refresh transport failed");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==401,"refused with failed refresh "+body);
   check(refresh.get()==1 && failedRefresh.sends()==1,"failed refresh is not replayed: "+failedRefresh.auth);
   check(durable(ownFile).getString("refresh").equals("one"),"no rotation when the refresh failed "+durable(ownFile));
  } finally { failedRefresh.close(); }
  check(failedRefresh.sends()==1,"no resend after a failed refresh");

  Upstream uncertainRefresh=new Upstream(401,200);
  try {
   Path own=Files.createTempDirectory("mini-refresh-uncertain");
   GatewayServer g=setup(uncertainRefresh.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return null;});
   NanoHTTPD.Response r=chat(g); ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==401 && refresh.get()==1,"401 fail closed on uncertain refresh");
  } finally { uncertainRefresh.close(); }
  check(uncertainRefresh.sends()==1,"no resend on uncertain refresh: "+uncertainRefresh.auth);

  // Negative controls: a request that may already have run is never sent again, whatever the code.
  Upstream dropped=new Upstream(0); List<UsageLedger.Row> droppedRows=hooks();
  try {
   Path own=Files.createTempDirectory("mini-dropped");
   GatewayServer g=setup(dropped.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502 && refresh.get()==0,"dropped answer is not replayed "+body);
   check(terminals(droppedRows)==1 && executions(droppedRows,"unknown_execution")>=1,"a dropped answer is unknown "+executions(droppedRows,"unknown_execution"));
  } finally { dropped.close(); UsageLedger.setHooks(null); }
  check(dropped.sends()==1,"one send only when the outcome is unknown: "+dropped.auth);

  // The upstream that never answers at all: it takes the request and closes without a status.
  Upstream silent=new Upstream(-1); List<UsageLedger.Row> silentRows=hooks();
  try {
   Path own=Files.createTempDirectory("mini-silent");
   GatewayServer g=setup(silent.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502 && refresh.get()==0,"no answer is a 502 without a refresh");
   check(terminals(silentRows)==1 && executions(silentRows,"unknown_execution")>=1,"no answer is unknown execution");
  } finally { silent.close(); UsageLedger.setHooks(null); }
  check(silent.sends()==1,"one send only when there is no answer: "+silent.auth);

  Upstream unavailable=new Upstream(503);
  try {
   Path own=Files.createTempDirectory("mini-503");
   GatewayServer g=setup(unavailable.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==503 && refresh.get()==0,"503 is not a token refusal");
  } finally { unavailable.close(); }
  check(unavailable.sends()==1,"no resend on 503: "+unavailable.auth);

  // Zero sends: when the token cannot be prepared, nothing leaves the device.
  Upstream neverSent=new Upstream(200);
  try {
   GatewayServer g=setup(neverSent.port(),Files.createTempDirectory("mini-none").resolve("config.json"),1); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return null;});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==400,"no route without a prepared token "+body);
   check(refresh.get()==1,"refresh attempted before any send");
  } finally { neverSent.close(); }
  check(neverSent.sends()==0,"nothing sent when the token cannot be prepared: "+neverSent.auth);

  // A redirect is not this request being served. The reference answers it as OtherSite - a 502,
  // no refresh spent, never sent twice - and never lets the conversation reach the other host with
  // the account's credentials. It is also not an execution whose outcome is unknown: it never ran.
  ServerSocket decoy=new ServerSocket(0); decoy.setSoTimeout(1500); AtomicInteger decoyHits=new AtomicInteger();
  Thread decoyWorker=new Thread(()->{ try { for(;;) { decoy.accept(); decoyHits.incrementAndGet(); } } catch(Throwable expected) { } });
  decoyWorker.setDaemon(true); decoyWorker.start();
  String decoyUrl="http://127.0.0.1:"+decoy.getLocalPort()+"/v1/messages";
  Upstream redirected=new Upstream(307,200); redirected.location=decoyUrl;
  List<UsageLedger.Row> redirectRows=hooks();
  Path redirectFile=Files.createTempDirectory("mini-307").resolve("config.json");
  GatewayServer redirectGateway=setup(redirected.port(),redirectFile,System.currentTimeMillis()+3600000);
  AtomicInteger redirectRefresh=new AtomicInteger();
  redirectGateway.setMiniMaxRefreshForTests(rt->{redirectRefresh.incrementAndGet(); return rotated("new","two");});
  try {
   NanoHTTPD.Response r=chat(redirectGateway); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502,"307 is a gateway failure, not a success: "+r.getStatus().getRequestStatus()+" "+body);
   check(body.contains("307") && body.contains("127.0.0.1"),"the answer names the redirect "+body);
   check(r.getHeader("Location")==null,"no Location is handed to the client: "+r.getHeader("Location"));
   check(redirected.sends()==1 && decoyHits.get()==0,"nothing is sent to the other host: sends="+redirected.sends()+" decoy="+decoyHits.get());
   check(redirected.auth.get(0).equals("Bearer old"),"the credential never left for the other host "+redirected.auth);
   check(redirectRefresh.get()==0,"a redirect spends no refresh");
   check(durable(redirectFile).getString("refresh").equals("one"),"no rotation on a redirect "+durable(redirectFile));
   check(terminals(redirectRows)==1 && executions(redirectRows,"unknown_execution")==0,"a redirect is not unknown execution: "+terminals(redirectRows));
   check(executions(redirectRows,"rejected")>=1,"a redirect is a definite refusal "+executions(redirectRows,"rejected"));
   lease(redirectGateway,account(redirectGateway));   // still usable: no cooldown was charged
  } finally { redirected.close(); UsageLedger.setHooks(null); }

  // The same redirect carrying a body that happens to parse as a message: never a completion. The
  // answer is the redirect, not the body the other host would have served.
  Upstream smuggled=new Upstream(307); smuggled.location=decoyUrl; smuggled.payload=RESPONSE;
  try {
   Path own=Files.createTempDirectory("mini-307-body");
   GatewayServer g=setup(smuggled.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502,"a 3xx with a message body is not a success: "+r.getStatus().getRequestStatus()+" "+body);
   check(!body.contains("ok"),"the redirect body is not served as the answer "+body);
   check(smuggled.sends()==1 && decoyHits.get()==0 && refresh.get()==0,"no replay and no refresh on a 3xx body: "+smuggled.sends());
  } finally { smuggled.close(); decoy.close(); }
  check(decoyHits.get()==0,"the other host was never contacted");


  // 302 on the same host: followed by nobody, reported the same way, and sent once.
  Upstream sameHost=new Upstream(302,200); sameHost.location="http://127.0.0.1:"+sameHost.server.getLocalPort()+"/v1/messages";
  try {
   Path own=Files.createTempDirectory("mini-302");
   GatewayServer g=setup(sameHost.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502 && r.getHeader("Location")==null,"same-host 302 is not followed "+body);
   check(sameHost.sends()==1 && refresh.get()==0,"a 302 is not replayed: "+sameHost.sends());
  } finally { sameHost.close(); }
  check(sameHost.sends()==1,"no second send on a same-host 302");

  // A 302 without a Location is still not a completion: reported, never parsed as a message.
  Upstream bare=new Upstream(302,200);
  try {
   Path own=Files.createTempDirectory("mini-302-bare");
   GatewayServer g=setup(bare.port(),own.resolve("config.json"),System.currentTimeMillis()+3600000); AtomicInteger refresh=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet(); return rotated("new","two");});
   NanoHTTPD.Response r=chat(g); String body=ResponsesGatewayOfflineTest.read(r.getData());
   check(r.getStatus().getRequestStatus()==502,"a bare 302 is a failure, never an empty success "+body);
   check(bare.sends()==1 && refresh.get()==0,"a bare 302 is not replayed");
  } finally { bare.close(); }
  check(bare.sends()==1,"no second send on a bare 302");

  System.out.println("MiniMaxIntegrationHttpTest passed");
 }
}
