package com.gaeul.allin;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class QuirksUpdateControllerOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static QuirksUpdateController.Transport transport(byte[] bytes,int code,boolean slow) {
  return new QuirksUpdateController.Transport(){ public byte[] fetch(NetworkPolicy.Snapshot p,String u)throws IOException {
   if(slow)try{Thread.sleep(500);}catch(InterruptedException ignored){} if(code!=200)throw new IOException("status");return bytes;
  } public void close(){} };
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
  byte[] incompatible=SignedQuirksStoreOfflineTest.envelope(new String(oldPayload,StandardCharsets.UTF_8).replace("\"minimumEngineVersion\": 1","\"minimumEngineVersion\": 2").getBytes(StandardCharsets.UTF_8),kp.getPrivate());
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
  ExecutorService ex=Executors.newFixedThreadPool(2);Future<QuirksUpdateController.Status> a=ex.submit(c::check);entered.await();Future<QuirksUpdateController.Status> b=ex.submit(c::check);Thread.sleep(100);release.countDown();check(a.get().result.equals("updated"));check(b.get().result.equals("updated"));check(count.get()==1);check(c.snapshot().source.equals("verified-update"));
  QuirksUpdateController redir=new QuirksUpdateController(cfg,dir.resolve("cache").toFile(),base,26,(deadline,timeout)->transport(envelope,302,false),1000);check(redir.check().result.equals("failed"));check(redir.snapshot().source.equals("verified-cache"));
  byte[] damaged=envelope.clone();damaged[10]^=1;QuirksUpdateController bad=new QuirksUpdateController(cfg,dir.resolve("bad").toFile(),base,26,(deadline,timeout)->transport(damaged,200,false),1000);check(bad.check().result.equals("failed"));check(bad.snapshot().source.equals("builtin"));
  QuirksUpdateController slow=new QuirksUpdateController(cfg,dir.resolve("slow").toFile(),base,26,(deadline,timeout)->transport(envelope,200,true),100);check(slow.check().result.equals("timeout"));Thread.sleep(700);check(slow.snapshot().source.equals("builtin"));
  QuirksUpdateController off=new QuirksUpdateController(QuirksUpdateController.TrustConfig.disabled(),dir.resolve("off").toFile(),base,26);check(off.check().result.equals("disabled"));
  QuirksUpdateController old=new QuirksUpdateController(cfg,dir.resolve("old").toFile(),base,25);check(old.check().result.equals("disabled"));
  Files.write(dir.resolve("corrupt"),new byte[]{1,2,3});QuirksUpdateController corrupt=new QuirksUpdateController(cfg,dir.resolve("corrupt").toFile(),base,26);check(corrupt.status().result.equals("cache_invalid"));check(corrupt.snapshot().source.equals("builtin"));
  QuirksUpdateController unauthorized=new QuirksUpdateController(cfg,dir.resolve("unauthorized").toFile(),base,26,(deadline,timeout)->transport(envelope,401,false),1000);check(unauthorized.check().result.equals("failed"));
  QuirksUpdateController writeFail=new QuirksUpdateController(cfg,dir.resolve("missing/cache").toFile(),base,26,(deadline,timeout)->transport(envelope,200,false),1000);check(writeFail.check().result.equals("failed"));check(writeFail.snapshot().source.equals("builtin"));
  ex.shutdownNow();System.out.println("QuirksUpdateControllerOfflineTest OK");
 }
}
