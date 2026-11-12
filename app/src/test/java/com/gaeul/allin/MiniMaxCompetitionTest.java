package com.gaeul.allin;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.gaeul.allin.json.*;

public final class MiniMaxCompetitionTest {
 static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
 static Object lease(GatewayServer g) throws Exception {return MiniMaxIntegrationHttpTest.lease(g,MiniMaxIntegrationHttpTest.account(g));}
 static void expectFailure(GatewayServer g) throws Exception {try {lease(g);throw new AssertionError("expected blocked refresh");}catch(InvocationTargetException expected) {}}
 static void mutate(GatewayServer g,String operation) throws Exception {
  if(operation.equals("restore")) {g.retireForRestore();return;}
  JSONObject cfg=ResponsesGatewayOfflineTest.config(g), p=cfg.getJSONArray("providers").getJSONObject(0);
  if(operation.equals("disable")) {
   JSONObject copy=new JSONObject(cfg.toString());copy.getJSONArray("providers").getJSONObject(0).put("enabled",false);
   Method save=GatewayServer.class.getDeclaredMethod("persistConfigStrict",JSONObject.class);save.setAccessible(true);save.invoke(g,copy);
  } else { Method remove=GatewayServer.class.getDeclaredMethod("removeMiniMaxAccount",String.class);remove.setAccessible(true);remove.invoke(g,"a"); }
 }
 static void pendingMutation(String op) throws Exception {
  Path dir=Files.createTempDirectory("mini-race"); GatewayServer g=MiniMaxIntegrationHttpTest.setup(1,dir.resolve("config.json"),1);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
  g.setMiniMaxRefreshForTests(rt->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}MiniMaxAuth.SignIn n=new MiniMaxAuth.SignIn();n.access="new";n.refresh="rotated";n.expires=9;return n;});
  Thread t=new Thread(()->{try{lease(g);error.set(new AssertionError("late commit"));}catch(InvocationTargetException expected){}catch(Throwable e){error.set(e);}});t.start();check(entered.await(5,TimeUnit.SECONDS),"refresh entered");mutate(g,op);release.countDown();t.join(5000);
  check(!t.isAlive() && error.get()==null,"mutation "+op+" "+error.get());
  JSONObject disk=new JSONObject(new String(Files.readAllBytes(dir.resolve("config.json")),"UTF-8"));JSONArray accounts=disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
  check(op.equals("delete")?accounts.length()==0:accounts.getJSONObject(0).getString("refresh").equals("one"),"no stale writer "+op);
 }
 static void restartAndFailures() throws Exception {
  Path dir=Files.createTempDirectory("mini-restart"), file=dir.resolve("config.json");
  if (MiniMaxTestJournal.unsupported(dir.toFile())) {
   GatewayServer closed=MiniMaxIntegrationHttpTest.setup(1,file,1); ResponsesGatewayOfflineTest.set(closed,"miniMaxJournal",null);
   byte[] before=Files.readAllBytes(file); AtomicInteger attempts=new AtomicInteger();
   closed.setMiniMaxRefreshForTests(rt->{attempts.incrementAndGet();return null;}); expectFailure(closed);
   GatewayServer reopened=MiniMaxIntegrationHttpTest.setup(1,file,1); ResponsesGatewayOfflineTest.set(reopened,"miniMaxJournal",null);
   reopened.setMiniMaxRefreshForTests(rt->{attempts.incrementAndGet();return null;}); expectFailure(reopened);
   check(attempts.get()==0 && Arrays.equals(before,Files.readAllBytes(file)),"unsupported directory sync must fail closed before refresh and after restart");
   System.out.println("Windows directory sync AccessDeniedException: fail-closed verified; isolated lifecycle fixture follows");
   dir=Files.createTempDirectory("mini-restart-fixture"); file=dir.resolve("config.json");
  }
  GatewayServer g=MiniMaxIntegrationHttpTest.setup(1,file,1);AtomicInteger calls=new AtomicInteger();
  g.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return null;});expectFailure(g);
  GatewayServer restart=MiniMaxIntegrationHttpTest.setup(1,file,1);MiniMaxIntegrationHttpTest.account(restart).put("id","renamed");restart.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return null;});expectFailure(restart);check(calls.get()==1,"restarted renamed credential replayed");
  Files.write(dir.resolve("minimax-refresh.journal"),new byte[]{1});GatewayServer corrupt=MiniMaxIntegrationHttpTest.setup(1,file,1);corrupt.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return null;});expectFailure(corrupt);check(calls.get()==1,"corrupt marker sent refresh");
  Path missing=Files.createTempDirectory("mini-no-dir").resolve("missing").resolve("config.json"); GatewayServer noDir=MiniMaxIntegrationHttpTest.setup(1,dir.resolve("other.json"),1);ResponsesGatewayOfflineTest.set(noDir,"configFile",missing.toFile());ResponsesGatewayOfflineTest.set(noDir,"miniMaxJournal",null);noDir.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return null;});expectFailure(noDir);check(calls.get()==1,"write failure sent refresh");
 }
 static void persistenceFailure() throws Exception {
  Path dir=Files.createTempDirectory("mini-save-fail"), file=dir.resolve("config.json"); GatewayServer g=MiniMaxIntegrationHttpTest.setup(1,file,1);AtomicInteger calls=new AtomicInteger();
  g.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();try {Files.delete(file);Files.createDirectory(file);}catch(IOException e){throw new RuntimeException(e);}MiniMaxAuth.SignIn n=new MiniMaxAuth.SignIn();n.access="new";n.refresh="two";n.expires=9;return n;});
  expectFailure(g);check(calls.get()==1 && MiniMaxIntegrationHttpTest.account(g).getString("refresh").equals("one"),"save failure changed memory");
  Files.delete(file); Files.write(file,ResponsesGatewayOfflineTest.config(g).toString().getBytes("UTF-8"));
  GatewayServer restart=MiniMaxIntegrationHttpTest.setup(1,file,1);restart.setMiniMaxRefreshForTests(rt->{calls.incrementAndGet();return null;});expectFailure(restart);check(calls.get()==1,"save failure replay after restart");
 }
 static void staleAux(boolean models) throws Exception {
  Path dir=Files.createTempDirectory("mini-aux");GatewayServer g=MiniMaxIntegrationHttpTest.setup(1,dir.resolve("config.json"),System.currentTimeMillis()+3600000);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
  g.setMiniMaxAuxForTests(new GatewayServer.MiniMaxAuxTransport(){public List<String> models(String token)throws Exception {entered.countDown();release.await();return Arrays.asList("late-model");}public JSONObject checkin(String token,String uid)throws Exception {entered.countDown();release.await();return new JSONObject().put("ok",true).put("outcome","done");}});
  Thread t=new Thread(()->{try {
   if(models) {fi.iki.elonen.NanoHTTPD.Response r=g.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/minimax-code/sync-models","POST","{}",null)); if(r.getStatus().getRequestStatus()==200) throw new AssertionError("stale models accepted");}
   else {Method m=GatewayServer.class.getDeclaredMethod("minimaxCheckinAccount",JSONObject.class);m.setAccessible(true);JSONObject r=(JSONObject)m.invoke(g,MiniMaxIntegrationHttpTest.account(g));if(r.optBoolean("ok",false))throw new AssertionError("stale checkin accepted");}
  }catch(Throwable e){error.set(e);}});t.start();check(entered.await(5,TimeUnit.SECONDS),"aux entered "+models);mutate(g,"delete");release.countDown();t.join(5000);check(!t.isAlive() && error.get()==null,"aux stale "+error.get());
  JSONObject disk=new JSONObject(new String(Files.readAllBytes(dir.resolve("config.json")),"UTF-8"));check(disk.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").length()==0 && !disk.toString().contains("late-model"),"stale aux wrote");
 }
 public static void main(String[] args) throws Exception { UsageLedger.init(Files.createTempDirectory("mini-ledger").toFile()); restartAndFailures();persistenceFailure();for(String op:Arrays.asList("disable","delete","restore"))pendingMutation(op);staleAux(false);staleAux(true);System.out.println("MiniMaxCompetitionTest passed"); }
}
