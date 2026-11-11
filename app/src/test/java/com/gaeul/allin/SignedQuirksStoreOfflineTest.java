package com.gaeul.allin;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone JVM offline test; all keys are ephemeral test fixtures. */
public final class SignedQuirksStoreOfflineTest {
 static final byte[] DOMAIN={'A','l','l','I','n','-','Q','2',0,1};
 static byte[] envelope(byte[] p, PrivateKey k) throws Exception {
  Signature s=Signature.getInstance("SHA256withRSA");s.initSign(k);s.update(DOMAIN);s.update(p);byte[] sig=s.sign();
  ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt  (0x51524b31);d.writeInt(p.length);d.write(p);d.writeShort(sig.length);d.write(sig);return b.toByteArray();
 }
 static void check(boolean v) {if(!v)throw new AssertionError();}
 static byte[] sign(byte[] p, PrivateKey k) throws Exception {
  Signature s=Signature.getInstance("SHA256withRSA");s.initSign(k);s.update(DOMAIN);s.update(p);return s.sign();
 }
 /** Same payload, but signed over an arbitrary (or empty) domain prefix. */
 static byte[] envelopeWith(byte[] p, PrivateKey k, byte[] domain) throws Exception {
  Signature s=Signature.getInstance("SHA256withRSA");s.initSign(k);s.update(domain);s.update(p);
  return raw(0x51524b31,p.length,p,s.sign());
 }
 /** Structurally arbitrary envelope, so each length field can be attacked on its own. */
 static byte[] raw(int magic, int len, byte[] payload, byte[] signature) throws Exception {
  ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
  d.writeInt(magic);d.writeInt(len);d.write(payload);d.writeShort(signature.length);d.write(signature);
  d.flush();return b.toByteArray();
 }
 interface Action {void run() throws Exception;}
 static void fails(Action a) throws Exception {try{a.run();throw new AssertionError("accepted invalid input");}catch(AssertionError e){throw e;}catch(Exception expected){}}
 public static void main(String[] args) throws Exception {
  byte[] base=Files.readAllBytes(Paths.get(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"));
  byte[] latest=base;
  String text=new String(base,StandardCharsets.UTF_8).replace("1.0.2","1.0.0"); base=text.getBytes(StandardCharsets.UTF_8); ProviderQuirks.Snapshot builtin=ProviderQuirks.parse(base);
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair a=g.generateKeyPair(),b=g.generateKeyPair();
  Path dir=Files.createTempDirectory("q2-test");File cache=dir.resolve("rules.bin").toFile();
  SignedQuirksStore st=new SignedQuirksStore(cache,a.getPublic(),base);
  byte[] p2=text.replace("1.0.0","1.0.1").getBytes(StandardCharsets.UTF_8), p3=text.replace("1.0.0","1.0.2").getBytes(StandardCharsets.UTF_8);
  fails(()->st.install(envelope(text.replace("  "," ").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  byte[] e2=envelope(p2,a.getPrivate());check(st.install(e2));ProviderQuirks.Snapshot held=st.snapshot();check(held.ruleVersion.equals("1.0.1"));check(held.source.equals("verified-update"));
  check(!st.install(e2));byte[] bad=e2.clone();bad[15]^=1;fails(()->st.install(bad));fails(()->st.install(envelope(p3,b.getPrivate())));
  fails(()->st.install(envelope(text.replace("\"schemaVersion\": 1","\"schemaVersion\": 2").replace("1.0.0","2.0.0").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  fails(()->st.install(envelope(new byte[]{(byte)0xff},a.getPrivate())));fails(()->st.install(new byte[SignedQuirksStore.maxEnvelope()+1]));
  byte[] cachedBefore=Files.readAllBytes(cache.toPath());
  SignedQuirksStore upgraded=new SignedQuirksStore(cache,a.getPublic(),latest);
  try { upgraded.restore(); throw new AssertionError("older cache accepted"); } catch(SignedQuirksStore.OlderRulesException expected) {}check(upgraded.snapshot().ruleVersion.equals("1.0.2"));check(upgraded.snapshot().source.equals("builtin"));
  check(java.util.Arrays.equals(cachedBefore,Files.readAllBytes(cache.toPath())));
  try { upgraded.install(e2); throw new AssertionError("older update accepted"); } catch(SignedQuirksStore.OlderRulesException expected) {}check(upgraded.snapshot().ruleVersion.equals("1.0.2"));
  check(st.install(envelope(p3,a.getPrivate())));fails(()->st.install(e2));check(held.ruleVersion.equals("1.0.1"));
  SignedQuirksStore restart=new SignedQuirksStore(cache,a.getPublic(),base);check(restart.restore());check(restart.snapshot().ruleVersion.equals("1.0.2"));check(restart.snapshot().source.equals("verified-cache"));
  SignedQuirksStore disabled=new SignedQuirksStore(cache,null,base);check(!disabled.enabled());fails(()->disabled.install(e2));
  File missing=dir.resolve("missing/cache").toFile();SignedQuirksStore broken=new SignedQuirksStore(missing,a.getPublic(),base);fails(()->broken.install(e2));check(broken.snapshot().ruleVersion.equals(builtin.ruleVersion));
  /* ---- the signature covers the domain prefix, not just the payload ---- */
  byte[] fresh=text.replace("1.0.0","1.0.7").getBytes(StandardCharsets.UTF_8);
  SignedQuirksStore sig=new SignedQuirksStore(dir.resolve("sig.bin").toFile(),a.getPublic(),base);
  fails(()->sig.install(envelopeWith(fresh,a.getPrivate(),new byte[0])));                 // payload only
  fails(()->sig.install(envelopeWith(fresh,a.getPrivate(),new byte[]{'A','l','l','I','n'}))); // other domain
  byte[] swapped=new byte[DOMAIN.length+fresh.length];
  System.arraycopy(fresh,0,swapped,0,fresh.length); System.arraycopy(DOMAIN,0,swapped,fresh.length,DOMAIN.length);
  fails(()->sig.install(envelopeWith(swapped,a.getPrivate(),new byte[0])));               // payload before domain
  check(sig.snapshot().source.equals("builtin") && sig.snapshot().ruleVersion.equals(builtin.ruleVersion));
  check(!dir.resolve("sig.bin").toFile().isFile());
  check(sig.install(envelope(fresh,a.getPrivate())));check(sig.snapshot().ruleVersion.equals("1.0.7"));
  /* ---- every length in the envelope is checked before it is trusted ---- */
  byte[] goodSig=sign(fresh,a.getPrivate());
  fails(()->sig.install(raw(0x51524b32,fresh.length,fresh,goodSig)));                      // wrong magic
  fails(()->sig.install(raw(0x51524b31,0,new byte[0],goodSig)));                           // empty payload
  fails(()->sig.install(raw(0x51524b31,fresh.length+1,fresh,goodSig)));                    // claimed length past the data
  fails(()->sig.install(raw(0x51524b31,ProviderQuirks.MAX_BYTES+1,fresh,goodSig)));        // payload over the cap
  fails(()->sig.install(raw(0x51524b31,fresh.length,fresh,new byte[0])));                  // empty signature
  fails(()->sig.install(raw(0x51524b31,fresh.length,fresh,new byte[513])));                // signature over the cap
  byte[] e7=envelope(fresh,a.getPrivate()),trailing=java.util.Arrays.copyOf(e7,e7.length+1);
  fails(()->sig.install(trailing));                                                        // one byte past the envelope
  check(sig.snapshot().ruleVersion.equals("1.0.7"));check(java.util.Arrays.equals(e7,Files.readAllBytes(dir.resolve("sig.bin"))));
  /* ---- one version grammar: a document the store cannot order is not a document ---- */
  fails(()->sig.install(envelope(text.replace("1.0.0","1.0.8-rc.1").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  fails(()->sig.install(envelope(text.replace("1.0.0","1.0.8.1").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  check(sig.snapshot().ruleVersion.equals("1.0.7"));
  /* ---- cache edge cases: oversize, not a file, and a no-op install that must not rewrite ---- */
  Path oversize=dir.resolve("oversize.bin");Files.write(oversize,new byte[SignedQuirksStore.maxEnvelope()+1]);
  SignedQuirksStore tooBig=new SignedQuirksStore(oversize.toFile(),a.getPublic(),base);fails(()->tooBig.restore());
  check(tooBig.snapshot().source.equals("builtin")&&tooBig.snapshot().ruleVersion.equals(builtin.ruleVersion));
  Path asDirectory=dir.resolve("cache-dir");Files.createDirectories(asDirectory);
  SignedQuirksStore dirCache=new SignedQuirksStore(asDirectory.toFile(),a.getPublic(),base);check(!dirCache.restore());
  check(dirCache.snapshot().source.equals("builtin"));
  byte[] beforeNoop=Files.readAllBytes(cache.toPath());
  check(!restart.install(envelope(p3,a.getPrivate())));
  check(java.util.Arrays.equals(beforeNoop,Files.readAllBytes(cache.toPath())));
  check(restart.snapshot().ruleVersion.equals("1.0.2")&&restart.snapshot().source.equals("verified-cache"));
  check(!restart.restore());check(restart.snapshot().ruleVersion.equals("1.0.2"));
  /* a restored cache is still upgradeable in place: the hot-update path over last-known-good */
  byte[] p4=text.replace("1.0.0","1.0.3").getBytes(StandardCharsets.UTF_8);
  check(restart.install(envelope(p4,a.getPrivate())));
  check(restart.snapshot().ruleVersion.equals("1.0.3")&&restart.snapshot().source.equals("verified-update"));
  check(restart.restore()==false&&restart.snapshot().ruleVersion.equals("1.0.3"));
  /* ---- engine 2: the shipped rule source signs schema 2 documents, so the store must be
     exercised with the vocabulary that actually carries rules and reply limits ---- */
  engineTwoInstall(latest,a.getPrivate(),a.getPublic());
  engineTwoUpgrade(latest,a.getPrivate(),a.getPublic());
  engineTwoTamper(latest,a.getPrivate(),a.getPublic());
  engineTwoCollision(latest,a.getPrivate(),a.getPublic());
  engineTwoConcurrency(latest,a.getPrivate(),a.getPublic());
  System.out.println("SignedQuirksStoreOfflineTest OK");
 }

 /* ================= engine 2 / schema 2 documents through the signed store ================= */

 static final String Q2_CHAT_URL="https://copilot.tencent.com/v2/chat/completions";
 static final String Q2_NATIVE_URL="https://copilot.tencent.com/v1/messages";

 static String q2Out(String provider,String model,int maxOutput){
  return "{\"provider\":\""+provider+"\",\"model\":\""+model+"\",\"maxOutput\":"+maxOutput+"}";
 }
 static String q2Defaults(int workbuddy,int zcode,int minimax){
  return q2Out("workbuddy","deepseek-v3",workbuddy)+","+q2Out("zcode","glm-5.3",zcode)
          +","+q2Out("minimax-code","MiniMax-M2.7",minimax);
 }
 /**
  * Schema 2 document: two engine 2 header subsets (the chat tuple the gateway reads, and the native
  * Messages tuple that only engine 2 can describe) plus explicit reply limits.
  */
 static String q2Doc(String version,String ua,String defaults){
  return "{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\""+version+"\",\"rules\":["
   +"{\"id\":\"chat-sub\",\"provider\":\"workbuddy\",\"protocol\":\"openai\","
   +"\"origin\":\"https://copilot.tencent.com\",\"path\":\"/v2/chat/completions\","
   +"\"headers\":{\"User-Agent\":\""+ua+"\"}},"
   +"{\"id\":\"native-sub\",\"provider\":\"workbuddy\",\"protocol\":\"anthropic\","
   +"\"origin\":\"https://copilot.tencent.com\",\"path\":\"/v1/messages\","
   +"\"headers\":{\"User-Agent\":\""+ua+"\",\"Accept\":\"text/event-stream\"}}"
   +"],\"outputDefaults\":["+defaults+"]}";
 }
 static byte[] q2Bytes(String version,String ua,String defaults){
  return q2Doc(version,ua,defaults).getBytes(StandardCharsets.UTF_8);
 }
 /** Refuse-and-return, so a case can assert the exception class it expects. */
 static Exception refusal(Action a) throws Exception {
  try{a.run();}catch(AssertionError e){throw e;}catch(Exception e){return e;}
  throw new AssertionError("accepted invalid input");
 }
 static String uaOf(ProviderQuirks.Snapshot s,String protocol,String url){
  return s.headers("workbuddy",protocol,url).get("User-Agent");
 }

 /**
  * A schema 2 document is the only shape that can carry reply limits, and the whole engine 2 path
  * is what the live rule source uses. This case pins install with explicit values, then the restart
  * that has to recover the same values from the cache.
  */
 static void engineTwoInstall(byte[] builtin,PrivateKey key,PublicKey pub) throws Exception {
  Path dir=Files.createTempDirectory("q2-e2-install");
  File cache=dir.resolve("rules.bin").toFile();
  SignedQuirksStore st=new SignedQuirksStore(cache,pub,builtin);
  check(st.snapshot().ruleVersion.equals("1.0.2")&&st.snapshot().source.equals("builtin"));
  check(st.snapshot().schemaVersion==1&&st.snapshot().outputDefaultCount()==0);
  byte[] env=envelope(q2Bytes("2.2.0","CLI/9.9",q2Defaults(8192,64000,1)),key);
  check(st.install(env));
  ProviderQuirks.Snapshot s=st.snapshot();
  check(s.ruleVersion.equals("2.2.0")&&s.source.equals("verified-update"));
  check(s.schemaVersion==2&&s.minimumEngineVersion==2&&s.engineVersion==2);
  /* header rules keep their engine 2 subset semantics after a signed install */
  Map<String,String> chat=s.headers("workbuddy","openai",Q2_CHAT_URL);
  check(chat.size()==1&&"CLI/9.9".equals(chat.get("User-Agent")));
  Map<String,String> nat=s.headers("workbuddy","anthropic",Q2_NATIVE_URL);
  check(nat.size()==2&&"CLI/9.9".equals(nat.get("User-Agent"))&&"text/event-stream".equals(nat.get("Accept")));
  check(s.headers("workbuddy","openai",Q2_NATIVE_URL).isEmpty());
  check(s.headers("workbuddy","anthropic",Q2_CHAT_URL).isEmpty());
  check(s.headers("workbuddy-ai","openai",Q2_CHAT_URL).isEmpty());
  /* the signed limits are the values, exactly, and nothing else is answered */
  check(s.outputDefaultCount()==3);
  check(s.outputDefault("workbuddy","deepseek-v3")==8192);
  check(s.outputDefault("zcode","glm-5.3")==64000);
  check(s.outputDefault("minimax-code","MiniMax-M2.7")==1);
  check(s.outputDefault("workbuddy","glm-5.3")==0);
  check(s.outputDefault("zcode","deepseek-v3")==0);
  check(s.outputDefault("workbuddy","deepseek-v3-extra")==0);
  check(java.util.Arrays.equals(env,Files.readAllBytes(cache.toPath())));
  /* restart on the same build: the cache is the same document, so it is a no-op, not a collision */
  SignedQuirksStore restart=new SignedQuirksStore(cache,pub,builtin);
  check(restart.restore());
  ProviderQuirks.Snapshot r=restart.snapshot();
  check(r.ruleVersion.equals("2.2.0")&&r.source.equals("verified-cache"));
  check(r.schemaVersion==2&&r.minimumEngineVersion==2&&r.engineVersion==2&&r.outputDefaultCount()==3);
  check("CLI/9.9".equals(uaOf(r,"openai",Q2_CHAT_URL)));
  check(r.headers("workbuddy","anthropic",Q2_NATIVE_URL).size()==2);
  check(r.outputDefault("workbuddy","deepseek-v3")==8192);
  check(r.outputDefault("zcode","glm-5.3")==64000);
  check(r.outputDefault("minimax-code","MiniMax-M2.7")==1);
  byte[] cachedAtRestart=Files.readAllBytes(cache.toPath());
  check(!restart.restore());
  check(java.util.Arrays.equals(cachedAtRestart,Files.readAllBytes(cache.toPath())));
  /* and an older engine 2 cache is refused outright instead of being served */
  Files.write(cache.toPath(),envelope(q2Bytes("2.1.5","STALE-UA",q2Defaults(7,7,7)),key));
  byte[] stale=Files.readAllBytes(cache.toPath());
  SignedQuirksStore backward=new SignedQuirksStore(cache,pub,builtin);
  backward.install(envelope(q2Bytes("2.2.0","NEWER-UA",q2Defaults(8192,64000,1)),key));
  check(backward.snapshot().ruleVersion.equals("2.2.0"));
  Files.write(cache.toPath(),stale);
  check(refusal(()->backward.restore()) instanceof SignedQuirksStore.OlderRulesException);
  check(backward.snapshot().ruleVersion.equals("2.2.0")&&backward.snapshot().source.equals("verified-update"));
  check("NEWER-UA".equals(uaOf(backward.snapshot(),"openai",Q2_CHAT_URL)));
  check(backward.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  check(java.util.Arrays.equals(stale,Files.readAllBytes(cache.toPath())));
 }

 /**
  * Upgrade path: the APK starts shipping a schema 2 builtin at 2.1.0 while the cache holds a signed
  * 2.2.0. A restart must keep the newer signed document — rules and limits — rather than the
  * values the new build happens to ship, and it must do so before any network call happens.
  */
 static void engineTwoUpgrade(byte[] builtin,PrivateKey key,PublicKey pub) throws Exception {
  Path dir=Files.createTempDirectory("q2-e2-upgrade");
  File cache=dir.resolve("rules.bin").toFile();
  byte[] shipped=q2Bytes("2.1.0","BUILTIN-UA",q2Defaults(4096,4096,4096));
  SignedQuirksStore old=new SignedQuirksStore(cache,pub,builtin);
  check(old.install(envelope(q2Bytes("2.2.0","CACHE-UA",q2Defaults(8192,64000,1)),key)));
  SignedQuirksStore upgraded=new SignedQuirksStore(cache,pub,shipped);
  ProviderQuirks.Snapshot before=upgraded.snapshot();
  check(before.ruleVersion.equals("2.1.0")&&before.source.equals("builtin")&&before.schemaVersion==2);
  check("BUILTIN-UA".equals(uaOf(before,"openai",Q2_CHAT_URL)));
  check(before.outputDefault("workbuddy","deepseek-v3")==4096);
  check(upgraded.restore());
  ProviderQuirks.Snapshot after=upgraded.snapshot();
  check(after.ruleVersion.equals("2.2.0")&&after.source.equals("verified-cache"));
  check("CACHE-UA".equals(uaOf(after,"openai",Q2_CHAT_URL)));
  check(after.outputDefault("workbuddy","deepseek-v3")==8192);
  check(after.outputDefault("zcode","glm-5.3")==64000);
  check(after.outputDefault("minimax-code","MiniMax-M2.7")==1);
  /* the same restart is idempotent: a second boot reads the same document and changes nothing */
  byte[] settled=Files.readAllBytes(cache.toPath());
  SignedQuirksStore secondBoot=new SignedQuirksStore(cache,pub,shipped);
  check(secondBoot.restore());
  check(secondBoot.snapshot().ruleVersion.equals("2.2.0")&&"CACHE-UA".equals(uaOf(secondBoot.snapshot(),"openai",Q2_CHAT_URL)));
  check(java.util.Arrays.equals(settled,Files.readAllBytes(cache.toPath())));
  /* a cache older than the newly shipped builtin is not restored over it */
  Path dir2=Files.createTempDirectory("q2-e2-stale");
  File staleCache=dir2.resolve("rules.bin").toFile();
  byte[] newest=q2Bytes("2.3.0","SHIPPED-UA",q2Defaults(2048,2048,2048));
  Files.write(staleCache.toPath(),envelope(q2Bytes("2.2.9","OLD-CACHE-UA",q2Defaults(9,9,9)),key));
  byte[] staleBytes=Files.readAllBytes(staleCache.toPath());
  SignedQuirksStore forward=new SignedQuirksStore(staleCache,pub,newest);
  check(refusal(()->forward.restore()) instanceof SignedQuirksStore.OlderRulesException);
  check(forward.snapshot().ruleVersion.equals("2.3.0")&&forward.snapshot().source.equals("builtin"));
  check("SHIPPED-UA".equals(uaOf(forward.snapshot(),"openai",Q2_CHAT_URL)));
  check(forward.snapshot().outputDefault("workbuddy","deepseek-v3")==2048);
  check(java.util.Arrays.equals(staleBytes,Files.readAllBytes(staleCache.toPath())));
 }

 /**
  * The signature covers the engine 2 payload exactly, on both the install path and the restart
  * path, and a refused document leaves no snapshot and no cache entry behind.
  */
 static void engineTwoTamper(byte[] builtin,PrivateKey key,PublicKey pub) throws Exception {
  Path dir=Files.createTempDirectory("q2-e2-tamper");
  File cache=dir.resolve("rules.bin").toFile();
  SignedQuirksStore st=new SignedQuirksStore(cache,pub,builtin);
  byte[] env=envelope(q2Bytes("2.3.0","TAMPER-UA",q2Defaults(8192,64000,1)),key);
  byte[] flippedPayload=env.clone(); flippedPayload[8+12]^=1;   /* 8 = magic+length header */
  check(refusal(()->st.install(flippedPayload)) instanceof SecurityException);
  byte[] flippedSignature=env.clone(); flippedSignature[flippedSignature.length-1]^=1;
  check(refusal(()->st.install(flippedSignature)) instanceof SecurityException);
  check(st.snapshot().ruleVersion.equals("1.0.2")&&st.snapshot().source.equals("builtin"));
  check(st.snapshot().outputDefaultCount()==0&&st.snapshot().headers("workbuddy","openai",Q2_CHAT_URL).size()==7);
  check(!cache.isFile());
  /* the single flipped content byte is the difference between a trusted and an untrusted rule set */
  check(st.install(env));
  check(st.snapshot().ruleVersion.equals("2.3.0")&&st.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  /* restart path: a damaged cache installs nothing and is left exactly as it was */
  Path dir2=Files.createTempDirectory("q2-e2-tamper-cache");
  File damaged=dir2.resolve("rules.bin").toFile();
  Files.write(damaged.toPath(),flippedPayload);
  SignedQuirksStore restart=new SignedQuirksStore(damaged,pub,builtin);
  check(refusal(()->restart.restore()) instanceof SecurityException);
  check(restart.snapshot().source.equals("builtin")&&restart.snapshot().outputDefaultCount()==0);
  check(java.util.Arrays.equals(flippedPayload,Files.readAllBytes(damaged.toPath())));
 }

 /**
  * Version collision: the same rule version signed twice with different content may never be
  * silently chosen between. It is not "older rules", and it changes neither the live snapshot nor
  * the cache.
  */
 static void engineTwoCollision(byte[] builtin,PrivateKey key,PublicKey pub) throws Exception {
  Path dir=Files.createTempDirectory("q2-e2-collision");
  File cache=dir.resolve("rules.bin").toFile();
  SignedQuirksStore st=new SignedQuirksStore(cache,pub,builtin);
  byte[] first=envelope(q2Bytes("2.4.0","FIRST-UA",q2Defaults(8192,64000,1)),key);
  check(st.install(first));
  byte[] cacheBefore=Files.readAllBytes(cache.toPath());
  byte[] second=envelope(q2Bytes("2.4.0","SECOND-UA",q2Defaults(4096,4096,4096)),key);
  Exception e=refusal(()->st.install(second));
  check(e instanceof IllegalArgumentException&&!(e instanceof SignedQuirksStore.OlderRulesException));
  check(st.snapshot().ruleVersion.equals("2.4.0")&&st.snapshot().source.equals("verified-update"));
  check("FIRST-UA".equals(uaOf(st.snapshot(),"openai",Q2_CHAT_URL)));
  check(st.snapshot().outputDefault("workbuddy","deepseek-v3")==8192);
  check(st.snapshot().outputDefault("zcode","glm-5.3")==64000);
  check(java.util.Arrays.equals(cacheBefore,Files.readAllBytes(cache.toPath())));
  /* the restart path has the same rule: cache and builtin share a version but not the document */
  Path dir2=Files.createTempDirectory("q2-e2-collision-boot");
  File colliding=dir2.resolve("rules.bin").toFile();
  byte[] shipped=q2Bytes("2.4.0","SHIPPED-UA",q2Defaults(2048,2048,2048));
  Files.write(colliding.toPath(),second);
  SignedQuirksStore restart=new SignedQuirksStore(colliding,pub,shipped);
  Exception boot=refusal(()->restart.restore());
  check(boot instanceof IllegalArgumentException&&!(boot instanceof SignedQuirksStore.OlderRulesException));
  check(restart.snapshot().ruleVersion.equals("2.4.0")&&restart.snapshot().source.equals("builtin"));
  check("SHIPPED-UA".equals(uaOf(restart.snapshot(),"openai",Q2_CHAT_URL)));
  check(restart.snapshot().outputDefault("workbuddy","deepseek-v3")==2048);
  check(java.util.Arrays.equals(second,Files.readAllBytes(colliding.toPath())));
  /* the identical document at the same version is still a no-op, not a collision */
  Path dir3=Files.createTempDirectory("q2-e2-same");
  File same=dir3.resolve("rules.bin").toFile();
  byte[] sameEnvelope=envelope(shipped,key);
  Files.write(same.toPath(),sameEnvelope);
  SignedQuirksStore noop=new SignedQuirksStore(same,pub,shipped);
  check(!noop.restore());
  check(noop.snapshot().source.equals("builtin"));
  check("SHIPPED-UA".equals(uaOf(noop.snapshot(),"openai",Q2_CHAT_URL)));
  check(noop.snapshot().outputDefault("workbuddy","deepseek-v3")==2048);
  check(!noop.install(sameEnvelope));
  check(java.util.Arrays.equals(sameEnvelope,Files.readAllBytes(same.toPath())));
 }

 /**
  * Snapshot isolation: a request decides with one snapshot reference, so an install that lands
  * mid-request may never expose a rule from one document together with a limit from another, and
  * it may never move the live version backwards. Every version here carries a paired
  * {@code User-Agent} and reply limit, so any mixing would be a visible inconsistency.
  */
 static void engineTwoConcurrency(byte[] builtin,final PrivateKey key,final PublicKey pub) throws Exception {
  final Path dir=Files.createTempDirectory("q2-e2-race");
  final File cache=dir.resolve("rules.bin").toFile();
  final SignedQuirksStore st=new SignedQuirksStore(cache,pub,builtin);
  final ProviderQuirks.Snapshot held=st.snapshot();
  check(held.ruleVersion.equals("1.0.2")&&held.source.equals("builtin")&&held.outputDefaultCount()==0);
  final String builtinUa=uaOf(held,"openai",Q2_CHAT_URL);
  check(builtinUa!=null);
  final int rounds=20, readers=3;
  final AtomicBoolean done=new AtomicBoolean();
  final AtomicReference<Throwable> failure=new AtomicReference<>();
  final AtomicInteger raced=new AtomicInteger();
  final CountDownLatch ready=new CountDownLatch(readers);
  List<Thread> pool=new ArrayList<>();
  for(int i=0;i<readers;i++){
   Thread t=new Thread(()->{
    int last=-1; boolean sawChange=false;
    try{
     ready.countDown();
     while(!done.get()){
      ProviderQuirks.Snapshot s=st.snapshot();
      String version=s.ruleVersion, ua=uaOf(s,"openai",Q2_CHAT_URL);
      int limit=s.outputDefault("workbuddy","deepseek-v3"), index;
      check(s.engineVersion==2);
      if("1.0.2".equals(version)){
       /* the document this build shipped: seven v1 headers and no limit at all */
       index=-1;
       check(s.schemaVersion==1&&s.minimumEngineVersion==1&&s.outputDefaultCount()==0);
       check(builtinUa.equals(ua)&&limit==0);
       check(s.headers("workbuddy","openai",Q2_CHAT_URL).size()==7);
      }else{
       /* every installed document pairs one User-Agent with one limit under one version */
       check(version.startsWith("2.2."));
       index=Integer.parseInt(version.substring(4));
       check(s.schemaVersion==2&&s.minimumEngineVersion==2&&s.outputDefaultCount()==3);
       check(("UA-"+index).equals(ua));
       check(limit==1000+index);
       check(uaOf(s,"anthropic",Q2_NATIVE_URL).equals("UA-"+index));
       check("text/event-stream".equals(s.headers("workbuddy","anthropic",Q2_NATIVE_URL).get("Accept")));
      }
      if(index!=last){ if(last!=-1)sawChange=true; check(index>last); last=index; }
     }
    }catch(Throwable t2){ failure.compareAndSet(null,t2); }
    if(sawChange)raced.incrementAndGet();
   },"q2-reader-"+i);
   t.setDaemon(true); pool.add(t);
  }
  for(Thread t:pool)t.start();
  check(ready.await(5,TimeUnit.SECONDS));
  Thread writer=new Thread(()->{
   try{
    for(int i=0;i<rounds;i++){
     byte[] env=envelope(q2Bytes("2.2."+i,"UA-"+i,q2Defaults(1000+i,2000+i,1)),key);
     if(!st.install(env))throw new AssertionError("install refused at round "+i);
     Thread.sleep(2);
    }
   }catch(Throwable t2){ failure.compareAndSet(null,t2); }
  },"q2-writer");
  writer.setDaemon(true); writer.start();
  writer.join(60000);
  done.set(true);
  for(Thread t:pool)t.join(10000);
  check(failure.get()==null);
  check(raced.get()>0);
  /* the final state is the last document, whole */
  ProviderQuirks.Snapshot end=st.snapshot();
  check(end.ruleVersion.equals("2.2."+(rounds-1))&&end.source.equals("verified-update"));
  check(("UA-"+(rounds-1)).equals(uaOf(end,"openai",Q2_CHAT_URL)));
  check(end.outputDefault("workbuddy","deepseek-v3")==1000+rounds-1);
  check(end.outputDefault("zcode","glm-5.3")==2000+rounds-1);
  /* publication replaces the reference: the snapshot a request already holds is untouched */
  check(held!=end);
  check(held.ruleVersion.equals("1.0.2")&&held.schemaVersion==1&&held.source.equals("builtin"));
  check(builtinUa.equals(uaOf(held,"openai",Q2_CHAT_URL)));
  check(held.headers("workbuddy","openai",Q2_CHAT_URL).size()==7);
  check(held.outputDefaultCount()==0&&held.outputDefault("workbuddy","deepseek-v3")==0);
  /* what the last install published is also what a restart recovers from the cache */
  check(java.util.Arrays.equals(envelope(q2Bytes("2.2."+(rounds-1),"UA-"+(rounds-1),
          q2Defaults(1000+rounds-1,2000+rounds-1,1)),key),Files.readAllBytes(cache.toPath())));
  SignedQuirksStore restart=new SignedQuirksStore(cache,pub,builtin);
  check(restart.restore());
  check(restart.snapshot().ruleVersion.equals("2.2."+(rounds-1)));
  check(restart.snapshot().source.equals("verified-cache"));
  check(("UA-"+(rounds-1)).equals(uaOf(restart.snapshot(),"openai",Q2_CHAT_URL)));
  check(restart.snapshot().outputDefault("workbuddy","deepseek-v3")==1000+rounds-1);
 }
}
