package com.gaeul.allin;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class QuirksUpdateControllerOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
 static QuirksUpdateController.Transport transport(byte[] bytes,int code,boolean slow) {
  return new QuirksUpdateController.Transport(){ public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException {
   if(slow)try{Thread.sleep(500);}catch(InterruptedException ignored){} if(code!=200)throw new IOException("status");return bytes;
  } public void close(){} };
 }
 /**
  * One call per iteration, back to back, so every call starts inside the window the previous call
  * left behind: right after its flight completed and before its worker returned. Nothing else is
  * in flight, so "busy" here would be a false answer.
  */
 static void noSpuriousBusyUnderSequentialLoad(QuirksUpdateController.TrustConfig cfg,Path dir,
   byte[] base,byte[] envelope)throws Exception{
  final AtomicInteger fetches=new AtomicInteger();
  QuirksUpdateController c=new QuirksUpdateController(cfg,dir.resolve("seq-load").toFile(),base,26,
    (deadline,timeout)->new QuirksUpdateController.Transport(){
      public byte[] fetch(NetworkPolicy.Snapshot p,String u){fetches.incrementAndGet();return envelope;}
      public void close(){}
    },2000);
  int busy=0,other=0;
  for(int i=0;i<2000;i++){
    String result=c.check().result;
    if("busy".equals(result))busy++;
    else if(!"unchanged".equals(result)&&!"updated".equals(result))other++;
  }
  check(busy==0);check(other==0);
  /* Nothing was swallowed either: every call after the first ran its own download and saw the
     document already in force, which is what "unchanged" reports. */
  check(fetches.get()>=2000);
  check(c.snapshot().ruleVersion.equals("1.0.3")&&c.snapshot().source.equals("verified-update"));
  check("unchanged".equals(c.status().result)||"updated".equals(c.status().result));
 }
 /** The same window, entered from many threads at once, with every caller joining one answer. */
 static void noSpuriousBusyUnderConcurrentLoad(QuirksUpdateController.TrustConfig cfg,Path dir,
   byte[] base,byte[] envelope)throws Exception{
  final AtomicInteger fetches=new AtomicInteger();
  final QuirksUpdateController c=new QuirksUpdateController(cfg,dir.resolve("par-load").toFile(),base,26,
    (deadline,timeout)->new QuirksUpdateController.Transport(){
      public byte[] fetch(NetworkPolicy.Snapshot p,String u){fetches.incrementAndGet();return envelope;}
      public void close(){}
    },2000);
  final Map<String,AtomicInteger> tally=new ConcurrentHashMap<>();
  final ExecutorService pool=Executors.newFixedThreadPool(8,r->{Thread t=new Thread(r,"q2-par");t.setDaemon(true);return t;});
  List<Future<?>> all=new ArrayList<>();
  for(int t=0;t<8;t++)all.add(pool.submit(()->{
    for(int i=0;i<250;i++){
      String result=c.check().result;
      tally.computeIfAbsent(result,k->new AtomicInteger()).incrementAndGet();
    }
  }));
  for(Future<?> f:all)f.get();
  pool.shutdownNow();
  check(!tally.containsKey("busy"));
  for(String key:tally.keySet())check("unchanged".equals(key)||"updated".equals(key));
  check(fetches.get()>=1);
  check(c.snapshot().ruleVersion.equals("1.0.3")&&c.snapshot().source.equals("verified-update"));
 }
 /**
  * The refusal that must survive the fix: a check that timed out leaves a cancelled worker behind,
  * and while that worker is still inside its download a second download must not start. Exactly one
  * fetch may have been handed out, and the answer must say why.
  */
 static void busyStillGuardsAnAbandonedWorker(QuirksUpdateController.TrustConfig cfg,Path dir,
   byte[] base,byte[] envelope)throws Exception{
  final AtomicInteger fetches=new AtomicInteger();
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
  QuirksUpdateController c=new QuirksUpdateController(cfg,dir.resolve("guard").toFile(),base,26,
    (deadline,timeout)->new QuirksUpdateController.Transport(){
      public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{
        fetches.incrementAndGet();entered.countDown();
        /* A cancelled flight interrupts this wait; ignoring interrupts keeps "the worker is still
           running" a property of the latch, not of how fast the machine is. */
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()-until<0){
          try{ if(release.await(until-System.nanoTime(),TimeUnit.NANOSECONDS))break; }catch(InterruptedException ignored){}
        }
        return envelope;
      }
      public void close(){closed.countDown();}
    },200);
  check(c.check().result.equals("timeout"));
  check(c.status().result.equals("timeout"));
  /* Held open: no second download may start, and nothing may be installed. */
  for(int i=0;i<5;i++){
    check(c.check().result.equals("busy"));
    check(fetches.get()==1);
  }
  check(c.snapshot().ruleVersion.equals("1.0.2")&&c.snapshot().source.equals("builtin"));
  check(!dir.resolve("guard").toFile().isFile());
  release.countDown();
  check(closed.await(5,TimeUnit.SECONDS));
  /* The abandoned worker never published anything, and once it is gone a fresh check works. */
  check("timeout".equals(c.status().result));
  check(c.snapshot().source.equals("builtin"));
  check(!dir.resolve("guard").toFile().isFile());
  String after=awaitSettled(c);
  check("updated".equals(after)||"unchanged".equals(after));
  check(c.snapshot().ruleVersion.equals("1.0.3")&&c.snapshot().source.equals("verified-update"));
 }
 /**
  * A worker that outlives its deadline and then produces a perfectly good document must not install
  * it: its check already answered "timeout", so publishing that answer later would report work the
  * caller was told had not happened.
  */
 static void timeoutNeverPublishesTheAbandonedAnswer(QuirksUpdateController.TrustConfig cfg,Path dir,
   byte[] base,byte[] envelope)throws Exception{
  final AtomicInteger fetches=new AtomicInteger();
  final CountDownLatch closed=new CountDownLatch(1);
  Path cache=dir.resolve("abandon");
  QuirksUpdateController c=new QuirksUpdateController(cfg,cache.toFile(),base,26,
    (deadline,timeout)->new QuirksUpdateController.Transport(){
      public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{
        if(fetches.incrementAndGet()==1)sleep(400);   /* past the 150ms deadline */
        return envelope;
      }
      public void close(){closed.countDown();}
    },150);
  check(c.check().result.equals("timeout"));
  check(c.snapshot().source.equals("builtin")&&!cache.toFile().isFile());
  check(closed.await(5,TimeUnit.SECONDS));          /* the worker has left download() */
  check("timeout".equals(c.status().result));
  check(c.snapshot().ruleVersion.equals("1.0.2")&&c.snapshot().source.equals("builtin"));
  check(!cache.toFile().isFile());
  /* A new check is a new flight and may install; that is the only way these rules can arrive. */
  check("updated".equals(awaitSettled(c)));
  check(cache.toFile().isFile()&&c.snapshot().ruleVersion.equals("1.0.3"));
 }
 /**
  * Timeouts and ordinary checks at the same time. Three things must hold under that mix: every
  * answer is one the controller can explain, "busy" never becomes the published status, and a
  * cancelled worker never leaves the guard latched — if the abandoned handle outlived its worker,
  * the updater would answer "busy" for ever and the panel could never check again.
  */
 static void timeoutsAndChecksMixedStayLive(QuirksUpdateController.TrustConfig cfg,Path dir,
   byte[] base,byte[] envelope)throws Exception{
  final AtomicInteger fetches=new AtomicInteger();
  /* Only the first two downloads are slower than the deadline; everything after them is instant,
     so the run has to recover on its own rather than stop as soon as it has timed out. */
  final QuirksUpdateController c=new QuirksUpdateController(cfg,dir.resolve("mixed").toFile(),base,26,
    (deadline,timeout)->new QuirksUpdateController.Transport(){
      public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{
        if(fetches.incrementAndGet()<=2)sleep(300);
        return envelope;
      }
      public void close(){}
    },120);
  final Map<String,AtomicInteger> tally=new ConcurrentHashMap<>();
  final ExecutorService pool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"q2-mix");t.setDaemon(true);return t;});
  List<Future<?>> all=new ArrayList<>();
  final long started=System.nanoTime();
  for(int t=0;t<2;t++)all.add(pool.submit(()->{
    for(int i=0;i<15;i++){
      String result=c.check().result;
      tally.computeIfAbsent(result,k->new AtomicInteger()).incrementAndGet();
    }
  }));
  for(Future<?> f:all)f.get(30,TimeUnit.SECONDS);
  pool.shutdownNow();
  check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)<30);
  /* Every answer is one the controller can explain, and at least one timeout really happened. */
  for(String key:tally.keySet())check(Arrays.asList("updated","unchanged","timeout","busy").contains(key));
  check(tally.containsKey("timeout"));
  /* A transient answer never becomes the published one. */
  check(!"busy".equals(c.status().result));
  /* And the guard is not latched: the updater settles on a real outcome by itself. */
  String settled=awaitSettled(c);
  check("updated".equals(settled)||"unchanged".equals(settled));
  check(c.snapshot().ruleVersion.equals("1.0.3")&&c.snapshot().source.equals("verified-update"));
  check(c.status().result.equals(settled));
 }

 /**
  * Polls until a call gets a real outcome. "busy" is legal only while a worker is still running and
  * "timeout" only while a shared flight's own deadline has passed, so both must clear on their own;
  * the bound is what proves the guard is not latched, and it is long enough that a slow machine
  * cannot make this a timing test.
  */
 static String awaitSettled(QuirksUpdateController c)throws Exception{
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  String last=c.check().result;
  while(System.nanoTime()-until<0){
    if(!"busy".equals(last)&&!"timeout".equals(last))return last;
    Thread.yield();
    last=c.check().result;
  }
  throw new AssertionError("never settled, last="+last+" status="+c.status().result);
 }

 public static void main(String[] args)throws Exception {
  byte[] base=Files.readAllBytes(Paths.get(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"));
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair kp=g.generateKeyPair();
  String json="{\"schemaVersion\":1,\"url\":\"https://example.org/rules\",\"rsaPublicKeySpkiBase64\":\""+Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())+"\"}";
  QuirksUpdateController.TrustConfig cfg=QuirksUpdateController.TrustConfig.fromApkAsset(json.getBytes(StandardCharsets.UTF_8));
  for(String u:new String[]{"http://example.org/rules","https://u@example.org/rules","https://example.org:443/rules","https://example.org/rules?x=y","https://example.org/rules#x"}) {
   try{QuirksUpdateController.TrustConfig.fromApkAsset(json.replace("https://example.org/rules",u).getBytes(StandardCharsets.UTF_8));throw new AssertionError();}catch(IllegalArgumentException expected){}
  }
  byte[] p=new String(base,StandardCharsets.UTF_8).replace("1.0.2","1.0.3").getBytes(StandardCharsets.UTF_8);byte[] envelope=SignedQuirksStoreOfflineTest.envelope(p,kp.getPrivate());
  Path dir=Files.createTempDirectory("q2-controller");
  byte[] oldPayload=new String(base,StandardCharsets.UTF_8).replace("1.0.2","1.0.1").getBytes(StandardCharsets.UTF_8);
  byte[] oldEnvelope=SignedQuirksStoreOfflineTest.envelope(oldPayload,kp.getPrivate());
  Path oldCache=dir.resolve("old-cache");Files.write(oldCache,oldEnvelope);
  QuirksUpdateController upgrade=new QuirksUpdateController(cfg,oldCache.toFile(),base,26,(deadline,timeout)->transport(oldEnvelope,200,false),1000);
  check(upgrade.status().result.equals("builtin_newer"));check(upgrade.snapshot().ruleVersion.equals("1.0.2"));check(upgrade.snapshot().source.equals("builtin"));
  check(upgrade.check().result.equals("newer_local"));check(upgrade.snapshot().ruleVersion.equals("1.0.2"));check(java.util.Arrays.equals(oldEnvelope,Files.readAllBytes(oldCache)));
  byte[] badOld=oldEnvelope.clone();badOld[badOld.length-1]^=1;
  Path badOldCache=dir.resolve("bad-old-cache");Files.write(badOldCache,badOld);
  QuirksUpdateController badOldController=new QuirksUpdateController(cfg,badOldCache.toFile(),base,26,(deadline,timeout)->transport(badOld,200,false),1000);
  check(badOldController.status().result.equals("cache_invalid"));check(badOldController.check().result.equals("failed"));
  byte[] incompatible=SignedQuirksStoreOfflineTest.envelope(new String(oldPayload,StandardCharsets.UTF_8).replace("\"minimumEngineVersion\": 1","\"minimumEngineVersion\": 3").getBytes(StandardCharsets.UTF_8),kp.getPrivate());
  Path incompatibleCache=dir.resolve("incompatible-cache");Files.write(incompatibleCache,incompatible);
  QuirksUpdateController incompatibleController=new QuirksUpdateController(cfg,incompatibleCache.toFile(),base,26,(deadline,timeout)->transport(incompatible,200,false),1000);
  check(incompatibleController.status().result.equals("cache_invalid"));check(incompatibleController.check().result.equals("failed"));
  byte[] collision=SignedQuirksStoreOfflineTest.envelope(new String(base,StandardCharsets.UTF_8).replace("  "," ").getBytes(StandardCharsets.UTF_8),kp.getPrivate());
  Path collisionCache=dir.resolve("collision-cache");Files.write(collisionCache,collision);
  QuirksUpdateController collisionController=new QuirksUpdateController(cfg,collisionCache.toFile(),base,26,(deadline,timeout)->transport(collision,200,false),1000);
  check(collisionController.status().result.equals("cache_invalid"));check(collisionController.check().result.equals("failed"));
  QuirksUpdateController lateOld=new QuirksUpdateController(cfg,dir.resolve("late-old").toFile(),base,26,(deadline,timeout)->transport(oldEnvelope,200,true),100);
  check(lateOld.check().result.equals("timeout"));Thread.sleep(700);check(lateOld.status().result.equals("timeout"));check(lateOld.snapshot().ruleVersion.equals("1.0.2"));
  AtomicInteger count=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  QuirksUpdateController c=new QuirksUpdateController(cfg,dir.resolve("cache").toFile(),base,26,(deadline,timeout)->new QuirksUpdateController.Transport(){public byte[] fetch(NetworkPolicy.Snapshot policy,String url)throws IOException{count.incrementAndGet();check(url.equals("https://example.org/rules"));entered.countDown();try{release.await();return envelope;}catch(InterruptedException e){throw new IOException(e);}}public void close(){}},2000);
  ExecutorService ex=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"q2-test");t.setDaemon(true);return t;});Future<QuirksUpdateController.Status> a=ex.submit(c::check);entered.await();Future<QuirksUpdateController.Status> b=ex.submit(c::check);Thread.sleep(100);release.countDown();check(a.get().result.equals("updated"));check(b.get().result.equals("updated"));check(count.get()==1);check(c.snapshot().source.equals("verified-update"));
  QuirksUpdateController redir=new QuirksUpdateController(cfg,dir.resolve("cache").toFile(),base,26,(deadline,timeout)->transport(envelope,302,false),1000);check(redir.check().result.equals("failed"));check(redir.snapshot().source.equals("verified-cache"));
  byte[] damaged=envelope.clone();damaged[10]^=1;QuirksUpdateController bad=new QuirksUpdateController(cfg,dir.resolve("bad").toFile(),base,26,(deadline,timeout)->transport(damaged,200,false),1000);check(bad.check().result.equals("failed"));check(bad.snapshot().source.equals("builtin"));
  QuirksUpdateController slow=new QuirksUpdateController(cfg,dir.resolve("slow").toFile(),base,26,(deadline,timeout)->transport(envelope,200,true),100);check(slow.check().result.equals("timeout"));Thread.sleep(700);check(slow.snapshot().source.equals("builtin"));
  QuirksUpdateController off=new QuirksUpdateController(QuirksUpdateController.TrustConfig.disabled(),dir.resolve("off").toFile(),base,26);check(off.check().result.equals("disabled"));
  QuirksUpdateController old=new QuirksUpdateController(cfg,dir.resolve("old").toFile(),base,25);check(old.check().result.equals("disabled"));
  Files.write(dir.resolve("corrupt"),new byte[]{1,2,3});QuirksUpdateController corrupt=new QuirksUpdateController(cfg,dir.resolve("corrupt").toFile(),base,26);check(corrupt.status().result.equals("cache_invalid"));check(corrupt.snapshot().source.equals("builtin"));
  QuirksUpdateController unauthorized=new QuirksUpdateController(cfg,dir.resolve("unauthorized").toFile(),base,26,(deadline,timeout)->transport(envelope,401,false),1000);check(unauthorized.check().result.equals("failed"));
  QuirksUpdateController writeFail=new QuirksUpdateController(cfg,dir.resolve("missing/cache").toFile(),base,26,(deadline,timeout)->transport(envelope,200,false),1000);check(writeFail.check().result.equals("failed"));check(writeFail.snapshot().source.equals("builtin"));
  /* ---- a transient answer is never allowed to become the persisted status ---- */
  final CountDownLatch busyEntered=new CountDownLatch(1),busyHold=new CountDownLatch(1);
  QuirksUpdateController busy=new QuirksUpdateController(cfg,dir.resolve("busy").toFile(),base,26,(deadline,timeout)->new QuirksUpdateController.Transport(){
   public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{ busyEntered.countDown();
    /* Hold the worker until the test releases it; a cancelled flight interrupts this await, so the
       wait ignores interrupts and only gives up on its own deadline. That keeps the "work is still
       in flight" state from depending on how fast the machine is. */
    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while(System.nanoTime()-until<0){ try{ if(busyHold.await(until-System.nanoTime(),java.util.concurrent.TimeUnit.NANOSECONDS)) break; }
      catch(InterruptedException ignored){} }
    return envelope; }
   public void close(){}
  },150);
  Future<QuirksUpdateController.Status> timedOut=ex.submit(busy::check);
  check(busyEntered.await(2,TimeUnit.SECONDS));check(timedOut.get().result.equals("timeout"));check(busy.status().result.equals("timeout"));
  check(busy.check().result.equals("busy"));check(!dir.resolve("busy").toFile().isFile());
  check(busy.status().result.equals("timeout"));check(busy.snapshot().ruleVersion.equals("1.0.2"));
  busyHold.countDown();Thread.sleep(200);
  check(busy.snapshot().source.equals("builtin"));check(!dir.resolve("busy").toFile().isFile());
  /* ---- the same signed document twice is one install, and the second one says so ---- */
  QuirksUpdateController once=new QuirksUpdateController(cfg,dir.resolve("once").toFile(),base,26,(deadline,timeout)->transport(envelope,200,false),1000);
  check(once.check().result.equals("updated"));check(once.snapshot().ruleVersion.equals("1.0.3"));
  check(once.check().result.equals("unchanged"));check(once.snapshot().source.equals("verified-update"));
  /* ---- a document signed by a key this build does not trust installs nothing ---- */
  KeyPairGenerator g2=KeyPairGenerator.getInstance("RSA");g2.initialize(2048);KeyPair other=g2.generateKeyPair();
  byte[] foreignEnvelope=SignedQuirksStoreOfflineTest.envelope(p,other.getPrivate());
  QuirksUpdateController foreign=new QuirksUpdateController(cfg,dir.resolve("foreign").toFile(),base,26,(deadline,timeout)->transport(foreignEnvelope,200,false),1000);
  check(foreign.check().result.equals("failed"));check(foreign.snapshot().source.equals("builtin"));check(!dir.resolve("foreign").toFile().isFile());
  /* ---- lifecycle: retired means retired, and it never re-enters the network ---- */
  AtomicInteger afterClose=new AtomicInteger();
  QuirksUpdateController retired=new QuirksUpdateController(cfg,dir.resolve("retired").toFile(),base,26,(deadline,timeout)->new QuirksUpdateController.Transport(){
   public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{afterClose.incrementAndGet();return envelope;}public void close(){}
  },1000);
  retired.close();QuirksUpdateController.Status afterRetire=retired.check();
  check(afterRetire.result.equals("retired")&&!afterRetire.enabled);check(afterClose.get()==0);
  /* ---- close while a check is in flight: cancelled, nothing installed, nothing cached ---- */
  final CountDownLatch inFlight=new CountDownLatch(1);final AtomicInteger allowClose=new AtomicInteger();
  QuirksUpdateController closing=new QuirksUpdateController(cfg,dir.resolve("closing").toFile(),base,26,(deadline,timeout)->new QuirksUpdateController.Transport(){
   public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException{ inFlight.countDown();
    for(int i=0;i<400&&allowClose.get()==0;i++){try{Thread.sleep(5);}catch(InterruptedException ignored){}} return envelope; }
   public void close(){}
  },5000);
  Future<QuirksUpdateController.Status> during=ex.submit(closing::check);
  check(inFlight.await(2,TimeUnit.SECONDS));closing.close();
  check(during.get(5,TimeUnit.SECONDS).result.equals("retired"));
  allowClose.set(1);Thread.sleep(300);
  check(closing.snapshot().source.equals("builtin"));check(!dir.resolve("closing").toFile().isFile());
  /* ---- engine 2 over the live update path: what a schema 2 document signs is what gets served,
     both straight after the update and after the restart that follows it ---- */
  byte[] engineTwo=SignedQuirksStoreOfflineTest.envelope(
          SignedQuirksStoreOfflineTest.q2Doc("2.5.0","E2-UA",SignedQuirksStoreOfflineTest.q2Defaults(8192,64000,1)).getBytes(StandardCharsets.UTF_8),
          kp.getPrivate());
  Path e2Cache=dir.resolve("engine-two");
  QuirksUpdateController e2=new QuirksUpdateController(cfg,e2Cache.toFile(),base,26,(deadline,timeout)->transport(engineTwo,200,false),1000);
  check(e2.status().result.equals("ready")&&e2.snapshot().schemaVersion==1);
  check(e2.snapshot().outputDefaultCount()==0&&e2.snapshot().outputDefault("workbuddy","deepseek-v3")==0);
  check(e2.check().result.equals("updated"));
  check(e2.snapshot().schemaVersion==2&&e2.snapshot().minimumEngineVersion==2&&e2.snapshot().engineVersion==2);
  check(e2.snapshot().ruleVersion.equals("2.5.0")&&e2.status().source.equals("verified-update"));
  check(e2.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  check(e2.snapshot().outputDefault("zcode","glm-5.3")==64000);
  check(e2.snapshot().outputDefault("minimax-code","MiniMax-M2.7")==1);
  check(e2.snapshot().outputDefault("workbuddy","glm-5.3")==0);
  check("E2-UA".equals(e2.snapshot().headers("workbuddy","openai","https://copilot.tencent.com/v2/chat/completions").get("User-Agent")));
  check(e2.snapshot().headers("workbuddy","anthropic","https://copilot.tencent.com/v1/messages").size()==2);
  /* the same signed document twice is still one install at engine 2 */
  check(e2.check().result.equals("unchanged"));
  /* restart: the cache is the newer document, and the limits survive the restore */
  QuirksUpdateController reboot=new QuirksUpdateController(cfg,e2Cache.toFile(),base,26,(deadline,timeout)->transport(engineTwo,200,false),1000);
  check(reboot.status().result.equals("cache_restored"));
  check(reboot.status().version.equals("2.5.0")&&reboot.status().source.equals("verified-cache"));
  check(reboot.snapshot().schemaVersion==2&&reboot.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  check("E2-UA".equals(reboot.snapshot().headers("workbuddy","openai","https://copilot.tencent.com/v2/chat/completions").get("User-Agent")));
  /* the same rule version arriving again with different content: refused, and the signed values
     already in force are neither replaced nor re-cached */
  byte[] collideTwo=SignedQuirksStoreOfflineTest.envelope(
          SignedQuirksStoreOfflineTest.q2Doc("2.5.0","OTHER-UA",SignedQuirksStoreOfflineTest.q2Defaults(4096,4096,4096)).getBytes(StandardCharsets.UTF_8),
          kp.getPrivate());
  byte[] cacheBeforeCollision=Files.readAllBytes(e2Cache);
  QuirksUpdateController collisionTwo=new QuirksUpdateController(cfg,e2Cache.toFile(),base,26,(deadline,timeout)->transport(collideTwo,200,false),1000);
  check(collisionTwo.status().result.equals("cache_restored"));
  check(collisionTwo.check().result.equals("failed"));
  check(collisionTwo.snapshot().ruleVersion.equals("2.5.0")&&collisionTwo.snapshot().source.equals("verified-cache"));
  check("E2-UA".equals(collisionTwo.snapshot().headers("workbuddy","openai","https://copilot.tencent.com/v2/chat/completions").get("User-Agent")));
  check(collisionTwo.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  check(java.util.Arrays.equals(cacheBeforeCollision,Files.readAllBytes(e2Cache)));
  /* ---- the handover between two checks: no false "busy", but the abandoned-worker refusal kept ---- */
  noSpuriousBusyUnderSequentialLoad(cfg,dir,base,envelope);
  noSpuriousBusyUnderConcurrentLoad(cfg,dir,base,envelope);
  busyStillGuardsAnAbandonedWorker(cfg,dir,base,envelope);
  timeoutNeverPublishesTheAbandonedAnswer(cfg,dir,base,envelope);
  timeoutsAndChecksMixedStayLive(cfg,dir,base,envelope);
  ex.shutdownNow();System.out.println("QuirksUpdateControllerOfflineTest OK");
 }
}
