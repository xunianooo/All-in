package com.gaeul.allin;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Standalone JVM offline test; all keys are ephemeral test fixtures. */
public final class SignedQuirksStoreOfflineTest {
 static final byte[] DOMAIN={'A','l','l','I','n','-','Q','2',0,1};
 static byte[] envelope(byte[] p, PrivateKey k) throws Exception {
  Signature s=Signature.getInstance("SHA256withRSA");s.initSign(k);s.update(DOMAIN);s.update(p);byte[] sig=s.sign();
  ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt  (0x51524b31);d.writeInt(p.length);d.write(p);d.writeShort(sig.length);d.write(sig);return b.toByteArray();
 }
 static void check(boolean v) {if(!v)throw new AssertionError();}
 interface Action {void run() throws Exception;}
 static void fails(Action a) throws Exception {try{a.run();throw new AssertionError("accepted invalid input");}catch(AssertionError e){throw e;}catch(Exception expected){}}
 public static void main(String[] args) throws Exception {
  byte[] base=Files.readAllBytes(Paths.get(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"));
  String text=new String(base,StandardCharsets.UTF_8); ProviderQuirks.Snapshot builtin=ProviderQuirks.parse(base);
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair a=g.generateKeyPair(),b=g.generateKeyPair();
  Path dir=Files.createTempDirectory("q2-test");File cache=dir.resolve("rules.bin").toFile();
  SignedQuirksStore st=new SignedQuirksStore(cache,a.getPublic(),base);
  byte[] p2=text.replace("1.0.0","1.0.1").getBytes(StandardCharsets.UTF_8), p3=text.replace("1.0.0","1.0.2").getBytes(StandardCharsets.UTF_8);
  fails(()->st.install(envelope(text.replace("  "," ").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  byte[] e2=envelope(p2,a.getPrivate());check(st.install(e2));ProviderQuirks.Snapshot held=st.snapshot();check(held.ruleVersion.equals("1.0.1"));check(held.source.equals("verified-update"));
  check(!st.install(e2));byte[] bad=e2.clone();bad[15]^=1;fails(()->st.install(bad));fails(()->st.install(envelope(p3,b.getPrivate())));
  fails(()->st.install(envelope(text.replace("\"schemaVersion\": 1","\"schemaVersion\": 2").replace("1.0.0","2.0.0").getBytes(StandardCharsets.UTF_8),a.getPrivate())));
  fails(()->st.install(envelope(new byte[]{(byte)0xff},a.getPrivate())));fails(()->st.install(new byte[SignedQuirksStore.maxEnvelope()+1]));
  check(st.install(envelope(p3,a.getPrivate())));fails(()->st.install(e2));check(held.ruleVersion.equals("1.0.1"));
  SignedQuirksStore restart=new SignedQuirksStore(cache,a.getPublic(),base);check(restart.restore());check(restart.snapshot().ruleVersion.equals("1.0.2"));check(restart.snapshot().source.equals("verified-cache"));
  SignedQuirksStore disabled=new SignedQuirksStore(cache,null,base);check(!disabled.enabled());fails(()->disabled.install(e2));
  File missing=dir.resolve("missing/cache").toFile();SignedQuirksStore broken=new SignedQuirksStore(missing,a.getPublic(),base);fails(()->broken.install(e2));check(broken.snapshot().ruleVersion.equals(builtin.ruleVersion));
  System.out.println("SignedQuirksStoreOfflineTest OK");
 }
}
