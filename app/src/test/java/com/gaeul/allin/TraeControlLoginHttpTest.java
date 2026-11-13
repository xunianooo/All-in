package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real control, auth and refresh over a loopback-only synthetic upstream. */
public final class TraeControlLoginHttpTest {
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 static JSONObject copy(JSONObject x){return new JSONObject(x.toString());}
 static final class Host implements TraeControl.Host {
  final Object lock=new Object(); final Path dir, file; JSONObject data; long epoch; boolean fail;
  Host() throws Exception {dir=Files.createTempDirectory("trae-control-test-");file=dir.resolve("config.json");data=new JSONObject("{\"providers\":[{\"id\":\"trae-cn\",\"enabled\":true,\"accounts\":[],\"keys\":[],\"models\":[],\"modelMeta\":{}}]}");write(data);}
  void write(JSONObject x)throws Exception {Path tmp=dir.resolve("config.tmp");Files.write(tmp,x.toString().getBytes(StandardCharsets.UTF_8));Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  public Object lock(){return lock;} public long epoch(){ok(Thread.holdsLock(lock),"epoch lock");return epoch;}
  public JSONObject config(){ok(Thread.holdsLock(lock),"config lock");return data;}
  public boolean retired(){return false;}
  public boolean save(JSONObject x){ok(Thread.holdsLock(lock),"save lock");if(fail)return false;try{write(x);data=copy(x);epoch++;return true;}catch(Exception e){throw new RuntimeException(e);}}
  JSONObject provider(){return data.getJSONArray("providers").getJSONObject(0);}
  JSONObject snapshot(){synchronized(lock){return copy(data);}}
  void expire()throws Exception{synchronized(lock){JSONObject x=copy(data);x.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("expires",1);ok(save(x),"expire save");}}
  void cleanup()throws Exception{Files.deleteIfExists(file);Files.deleteIfExists(dir.resolve("config.tmp"));Files.deleteIfExists(dir);}
 }
 static final class Fixture implements AutoCloseable {
  final ServerSocket server; final Thread thread; final Host host; final AtomicInteger exchange=new AtomicInteger(),models=new AtomicInteger(),status=new AtomicInteger(),claim=new AtomicInteger(); volatile boolean drop,claimed,pendingObserved; volatile boolean requirePending; volatile boolean blockBinding; final CountDownLatch bindStarted=new CountDownLatch(1),bindRelease=new CountDownLatch(1);
  Fixture(Host h)throws Exception{host=h;server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));TraeAuth.setFixtureBase("http://127.0.0.1:"+server.getLocalPort());thread=new Thread(()->{while(!server.isClosed())try{Socket s=server.accept();Thread t=new Thread(()->serve(s));t.setDaemon(true);t.start();}catch(IOException e){break;}});thread.setDaemon(true);thread.start();}
  void serve(Socket s){try(Socket c=s){c.setSoTimeout(5000);InputStream in=c.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int ch;while((ch=in.read())!=-1){head.write(ch);byte[] b=head.toByteArray();int n=b.length;if(n>=4&&b[n-4]==13&&b[n-3]==10&&b[n-2]==13&&b[n-1]==10)break;}String h=head.toString("UTF-8"),path=h.split(" ")[1];int len=0;for(String line:h.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(line.substring(15).trim());while(len-->0&&in.read()!=-1){} String body;
   if(path.contains("ExchangeToken")){int n=exchange.incrementAndGet();if(blockBinding){bindStarted.countDown();if(!bindRelease.await(10,TimeUnit.SECONDS))throw new IOException("fixture bind wait");}if(requirePending){JSONObject disk=new JSONObject(new String(Files.readAllBytes(host.file),StandardCharsets.UTF_8));pendingObserved="pending".equals(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).optString("refreshState"));}if(drop)return;body="{\"Result\":{\"Token\":\"synthetic-token-"+n+"\",\"RefreshToken\":\"synthetic-refresh-"+n+"\",\"TokenExpireAt\":\"2099-01-01T00:00:00Z\"}}";}
   else if(path.contains("batch_get_detail_param")){models.incrementAndGet();body="{\"function_configs\":[{\"function\":\"chat_v3\",\"config_info_list\":[{\"config_name\":\"fixture-model\",\"display_name\":\"Fixture Model\",\"context_window_tokens\":{\"dev\":4096}}]}]}";}
   else if(path.endsWith("/status")){status.incrementAndGet();body="{\"enable\":true,\"checked_in\":"+claimed+",\"credits\":7,\"secret\":\"synthetic-secret\"}";}
   else if(path.endsWith("/claim")){claim.incrementAndGet();claimed=true;body="{}";}
   else {body="{}";}
   byte[] b=body.getBytes(StandardCharsets.UTF_8);OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}catch(Exception ignored){}}
  public void close()throws Exception{server.close();TraeAuth.setFixtureBase(null);thread.join(1000);}
 }
 static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
 static JSONObject login(TraeControl c)throws Exception {
  JSONObject init=c.api("/api/auth/trae-cn/init",null,null);URL url=new URL(init.getString("url"));String callback=null;for(String p:url.getQuery().split("&")){String[] v=p.split("=",2);if(v[0].equals("auth_callback_url"))callback=URLDecoder.decode(v[1],"UTF-8");}ok(callback!=null,"callback url");URL cb=new URL(callback);ok(InetAddress.getByName(cb.getHost()).isLoopbackAddress(),"callback loopback");String path=cb.getFile()+"?loginTraceID="+init.getString("flowId")+"&userJwt="+enc("{\"Token\":\"synthetic-initial\",\"RefreshToken\":\"synthetic-initial-refresh\"}")+"&userInfo="+enc("{\"UserID\":\"synthetic-user\",\"ScreenName\":\"Synthetic\",\"Host\":\"https://api.trae.com.cn\",\"AuthHost\":\"https://api.trae.com.cn/\"}");
  try(Socket s=new Socket(cb.getHost(),cb.getPort())){s.setSoTimeout(10000);s.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+cb.getPort()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();ok(new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8)).readLine().contains(" 200 "),"callback accepted");}
  Map<String,String> p=Collections.singletonMap("flowId",init.getString("flowId"));JSONObject result=null;for(int i=0;i<100;i++){result=c.api("/api/auth/trae-cn/poll",p,null);if(!"pending".equals(result.optString("status")))return result;Thread.sleep(25);}throw new AssertionError("poll timeout: "+result);
 }
 static URL callbackUrl(JSONObject init)throws Exception {for(String p:new URL(init.getString("url")).getQuery().split("&")){String[] v=p.split("=",2);if(v[0].equals("auth_callback_url"))return new URL(URLDecoder.decode(v[1],"UTF-8"));}throw new AssertionError("callback missing");}
 static void recovery(TraeControl c)throws Exception {
  String base="/api/auth/trae-cn/";
  JSONObject gone=c.api(base+"init",Collections.singletonMap("flowId","synthetic-missing"),null);
  ok(!gone.optBoolean("ok")&&"missing".equals(gone.optString("status")),"unknown flow must not start another authorization");
  JSONObject a=c.api(base+"init",null,null), resumed=c.api(base+"init",null,null);
  ok(!a.optBoolean("resumed") && resumed.optBoolean("resumed"),"init resumed flags");
  ok(a.getString("flowId").equals(resumed.getString("flowId")) && a.getLong("deadline")==resumed.getLong("deadline") && a.getString("url").equals(resumed.getString("url")),"same pending flow and deadline");
  URL old=callbackUrl(a); JSONObject fresh=c.api(base+"init",Collections.singletonMap("fresh","1"),null);
  ok(!fresh.optBoolean("resumed") && !a.getString("flowId").equals(fresh.getString("flowId")),"fresh flow identity");
  JSONObject missing=c.api(base+"poll",Collections.singletonMap("flowId",a.getString("flowId")),null);
  ok(!missing.optBoolean("ok",true) && "missing".equals(missing.optString("status")),"old poll missing immediately");
  try(Socket late=new Socket()){late.connect(new InetSocketAddress("127.0.0.1",old.getPort()),1000);late.setSoTimeout(1500);late.getOutputStream().write(("GET /authorize?loginTraceID="+enc(a.getString("flowId"))+"&error=late HTTP/1.1\r\nHost: 127.0.0.1:"+old.getPort()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));late.getOutputStream().flush();ok(late.getInputStream().read()==-1,"old socket must close");}catch(IOException expected){}
  JSONObject pending=c.api(base+"poll",Collections.singletonMap("flowId",fresh.getString("flowId")),null);
  ok(pending.optBoolean("ok") && "pending".equals(pending.optString("status")),"late old callback cannot affect new flow");
 }
 static JSONObject bindingRecovery(TraeControl c,Fixture f)throws Exception {
  JSONObject before=c.api("/api/auth/trae-cn/init",null,null);String id=before.getString("flowId");
  f.blockBinding=true;ExecutorService worker=Executors.newSingleThreadExecutor();
  try{
   Future<JSONObject> ready=worker.submit(()->login(c));
   ok(f.bindStarted.await(5,TimeUnit.SECONDS),"fixture observed device binding");
   JSONObject resumed=c.api("/api/auth/trae-cn/init",Collections.singletonMap("flowId",id),null);
   ok(resumed.optBoolean("resumed")&&id.equals(resumed.optString("flowId"))&&"binding".equals(resumed.optString("status")),"Activity recreation resumes in-flight device binding");
   JSONObject implicit=c.api("/api/auth/trae-cn/init",null,null);
   ok(id.equals(implicit.optString("flowId")),"in-flight binding is not replaced by another init");
   f.bindRelease.countDown();return ready.get(10,TimeUnit.SECONDS);
  }finally{f.bindRelease.countDown();f.blockBinding=false;worker.shutdownNow();}
 }
 static void retainedResult(TraeControl c,JSONObject result)throws Exception {
  String id=result.getString("flowId");
  JSONObject resumed=c.api("/api/auth/trae-cn/init",Collections.singletonMap("flowId",id),null);
  ok(resumed.optBoolean("resumed") && id.equals(resumed.optString("flowId")),"terminal result must resume its original flow, not create another authorization");
  JSONObject checked=c.api("/api/auth/trae-cn/poll",Collections.singletonMap("flowId",id),null);
  ok(result.optString("status").equals(checked.optString("status")),"checking retained result preserves terminal status");
  ok(result.optString("error").equals(checked.optString("error")),"checking retained result preserves failure reason");
 }
 static JSONObject lease(TraeControl c,String id)throws Exception {try(RequestContext r=new RequestContext("fixture-lease",NetworkPolicy.capture(),10000)){return c.lease(id,r);}}
 public static void main(String[] args)throws Exception {
  Host h=new Host();TraeControl c=new TraeControl(h);
  try(Fixture f=new Fixture(h)){
   recovery(c); JSONObject ready=bindingRecovery(c,f);ok("ready".equals(ready.optString("status")),"login ready: "+ready);retainedResult(c,ready); String safe=ready.toString();ok(!safe.contains("synthetic-token")&&!safe.contains("PRIVATE KEY")&&!safe.contains("refresh"),"safe login response");String id=ready.getString("keyId");JSONObject p=h.snapshot().getJSONArray("providers").getJSONObject(0),a=p.getJSONArray("accounts").getJSONObject(0);String generation=a.getString("generation");ok("https://api.trae.com.cn".equals(a.optString("authHost")),"official callback auth host survives binding and account save");ok(a.getBoolean("bound")&&id.equals(a.getString("id"))&&!generation.isEmpty()&&a.getString("devicePrivateKey").contains("PRIVATE KEY"),"bound identity and key");ok(p.getJSONArray("models").toString().contains("fixture-model")&&p.getJSONObject("modelMeta").getJSONObject("fixture-model").getInt("contextWindow")==4096&&f.models.get()==1,"automatic model sync");ok(!a.has("_traeEpoch"),"epoch transient");
   JSONObject l=lease(c,id);l.put("token","changed-clone");ok(!h.snapshot().toString().contains("changed-clone"),"lease clone");int before=f.exchange.get();h.expire();f.requirePending=true;ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<JSONObject> x=pool.submit(()->lease(c,id)),y=pool.submit(()->lease(c,id));JSONObject one=x.get(15,TimeUnit.SECONDS),two=y.get(15,TimeUnit.SECONDS);ok(one.getString("token").equals(two.getString("token")),"concurrent lease token");}finally{pool.shutdownNow();}ok(f.exchange.get()==before+1&&f.pendingObserved,"single refresh and persisted intent");a=h.snapshot().getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0);ok(a.getString("token").equals("synthetic-token-"+f.exchange.get())&&!a.has("refreshState")&&!a.has("_traeEpoch"),"refresh published and transient epoch");f.requirePending=false;
   Map<String,String> aid=Collections.singletonMap("accountId",id);JSONObject st=c.api("/api/trae/checkin-status",aid,null);ok(st.optString("outcome").equals("status")&&st.optInt("credits")==7&&!st.toString().contains("synthetic-secret"),"safe checkin status");JSONObject claim=c.api("/api/trae/checkin",null,new JSONObject().put("accountId",id));ok(claim.optString("outcome").equals("done")&&f.claim.get()==1,"checkin claim");
   JSONObject stale=h.snapshot();ready=login(c);ok("ready".equals(ready.optString("status")),"relogin ready");p=h.snapshot().getJSONArray("providers").getJSONObject(0);a=p.getJSONArray("accounts").getJSONObject(0);ok(p.getJSONArray("accounts").length()==1&&id.equals(a.getString("id"))&&!generation.equals(a.getString("generation"))&&!a.has("refreshState"),"same uid replaces generation");GatewayServer.preserveTraeAccounts(h.snapshot(),stale);ok(stale.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("generation").equals(a.getString("generation")),"stale config preserves account");
   synchronized(h.lock){h.fail=true;}try{c.remove(id);throw new AssertionError("remove save failure");}catch(IOException expected){}synchronized(h.lock){h.fail=false;}ok(h.snapshot().getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==1,"failed removal intact");ok(c.remove(id).getBoolean("ok"),"remove");p=h.snapshot().getJSONArray("providers").getJSONObject(0);ok(p.getJSONArray("accounts").length()==0&&p.getJSONArray("keys").length()==0&&p.getJSONArray("models").length()==0,"remove clears provider");
   f.drop=true;JSONObject failed=login(c);ok("failed".equals(failed.optString("status")),"binding failure");retainedResult(c,failed);ok(failed.optString("error").contains("TRAE_BIND_"),"binding failure exposes a safe diagnostic rather than losing the reason");ok(h.snapshot().getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==0,"failed bind not added");
   System.out.println("TraeControlLoginHttpTest OK");
  }finally{c.close();TraeAuth.setFixtureBase(null);h.cleanup();}
 }
}
