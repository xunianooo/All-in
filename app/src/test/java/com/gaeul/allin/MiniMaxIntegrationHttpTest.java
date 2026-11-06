package com.gaeul.allin;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import fi.iki.elonen.NanoHTTPD;

public class MiniMaxIntegrationHttpTest {
 static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
 static GatewayServer setup(int port,Path path,long expires) throws Exception {
  GatewayServer g=ResponsesGatewayOfflineTest.gateway(port,"minimax");
  JSONObject config=ResponsesGatewayOfflineTest.config(g), p=config.getJSONArray("providers").getJSONObject(0);
  p.put("id","minimax-code").put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("access","old").put("token","old").put("refresh","one").put("expires",expires)));
  ResponsesGatewayOfflineTest.set(g,"configFile",path.toFile()); Files.write(path,config.toString().getBytes("UTF-8")); return g;
 }
 static JSONObject account(GatewayServer g) throws Exception {return ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0);}
 static Object lease(GatewayServer g,JSONObject a) throws Exception {Method m=GatewayServer.class.getDeclaredMethod("miniLease",JSONObject.class,boolean.class,MiniMaxLifecycle.Lease.class);m.setAccessible(true);return m.invoke(g,a,false,null);}
 static String request="{\"model\":\"minimax-code/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("mini-fixture"), file=dir.resolve("config.json"); UsageLedger.init(dir.toFile());
  String response="{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"m\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
  try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock(response,false)) {
   GatewayServer g=setup(mock.server.getLocalPort(),file,1); AtomicInteger calls=new AtomicInteger();
   g.setMiniMaxRefreshForTests(rt -> {calls.incrementAndGet(); MiniMaxAuth.SignIn x=new MiniMaxAuth.SignIn();x.access="new";x.refresh="two";x.expires=System.currentTimeMillis()+3600000;return x;});
   NanoHTTPD.Response result=g.serve(ResponsesGatewayOfflineTest.session(request,"secret","/v1/chat/completions"));
   String body=ResponsesGatewayOfflineTest.read(result.getData()); check(result.getStatus().getRequestStatus()==200,"forward "+body);
   check(calls.get()==1 && account(g).getString("refresh").equals("two") && new JSONObject(new String(Files.readAllBytes(file),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("refresh").equals("two"),"durable rotation");
  }
  GatewayServer failed=setup(1,file,1); ResponsesGatewayOfflineTest.set(failed,"configFile",dir.resolve("missing").resolve("config.json").toFile());
  AtomicInteger calls=new AtomicInteger();failed.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();MiniMaxAuth.SignIn x=new MiniMaxAuth.SignIn();x.access="new";x.refresh="two";x.expires=9;return x;});
  try {lease(failed,account(failed));throw new AssertionError("expected failure");} catch(InvocationTargetException expected) {}
  check(account(failed).getString("refresh").equals("one"),"old in memory");
  try {lease(failed,account(failed));throw new AssertionError("expected quarantine");} catch(InvocationTargetException expected) {}
  check(calls.get()==0,"journal write failure prevents refresh");
  Path raceFile=Files.createTempDirectory("mini-race").resolve("config.json"); GatewayServer race=setup(1,raceFile,1); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();
  race.setMiniMaxRefreshForTests(rt->{entered.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}MiniMaxAuth.SignIn x=new MiniMaxAuth.SignIn();x.access="new";x.refresh="two";x.expires=9;return x;});
  Thread t=new Thread(()->{try{lease(race,account(race));err.set(new AssertionError("late refresh accepted"));}catch(InvocationTargetException expected){}catch(Throwable e){err.set(e);}});t.start();entered.await();race.retireForRestore();release.countDown();t.join();check(err.get()==null,"retired refresh");
  check(new JSONObject(new String(Files.readAllBytes(raceFile),"UTF-8")).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("refresh").equals("one"),"retired writer");
  try(ResponsesGatewayOfflineTest.Mock mock=new ResponsesGatewayOfflineTest.Mock("{\"error\":{\"type\":\"authentication_error\"}}",false,401)) {
   GatewayServer g=setup(mock.server.getLocalPort(),file,System.currentTimeMillis()+3600000);AtomicInteger refresh=new AtomicInteger();g.setMiniMaxRefreshForTests(rt->{refresh.incrementAndGet();return null;});
   NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(request,"secret","/v1/chat/completions"));ResponsesGatewayOfflineTest.read(r.getData());check(r.getStatus().getRequestStatus()==401 && refresh.get()==0,"401 fail closed");
  }
  System.out.println("MiniMaxIntegrationHttpTest passed");
 }
}
