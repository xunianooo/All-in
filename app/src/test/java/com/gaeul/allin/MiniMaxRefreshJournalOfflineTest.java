package com.gaeul.allin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offline proof for the MiniMax refresh fence: crash windows, fail-closed errors, capacity,
 * identifier-free observability and concurrency. No device, network or Gradle involvement. */
public final class MiniMaxRefreshJournalOfflineTest {
 static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
 interface Run { void run() throws Exception; }
 static void blocked(Run f) throws Exception { try { f.run(); throw new AssertionError("not blocked"); } catch(MiniMaxRefreshJournal.Blocked expected) {} }
 /** Expects a plain failure: neither a fence hit nor a capacity rejection. */
 static void refused(Run f,String why) throws Exception {
  try { f.run(); throw new AssertionError(why); }
  catch(MiniMaxRefreshJournal.Blocked e) { throw new AssertionError(why+": reported a fence hit"); }
  catch(MiniMaxRefreshJournal.CapacityExceeded e) { throw new AssertionError(why+": reported capacity"); }
  catch(IOException expected) {}
 }
 static void full(Run f) throws Exception { try { f.run(); throw new AssertionError("capacity accepted"); } catch(MiniMaxRefreshJournal.CapacityExceeded expected) {} catch(MiniMaxRefreshJournal.Blocked e) { throw new AssertionError("capacity reported as an uncertain outcome"); } }
 static Path temp(String prefix) throws IOException { return Files.createTempDirectory(prefix); }
 static Path journalFile(Path dir) { return dir.resolve("minimax-refresh.journal"); }
 static String journalText(Path dir) throws IOException { return new String(Files.readAllBytes(journalFile(dir)),"US-ASCII"); }
 /** Injects a directory-sync failure at the Nth sync, i.e. exactly inside the durability window. */
 static final class Flaky extends MiniMaxRefreshJournal {
  private final int failOn; private int calls;
  Flaky(File directory,int failOn) throws IOException { super(directory); this.failOn=failOn; }
  @Override void syncDirectory() throws IOException { if(++calls==failOn) throw new IOException("injected directory sync failure"); super.syncDirectory(); }
 }
 public static void main(String[] args) throws Exception {
  plaintextOwnershipAndRotation();
  legacyMigration();
  identityRejection();
  corruptionIsFailClosed();
  crashWindows();
  capacity();
  observability();
  concurrency();
  System.out.println("MiniMaxRefreshJournalOfflineTest passed");
 }

 static void plaintextOwnershipAndRotation() throws Exception {
  Path dir=temp("minimax-journal"); MiniMaxRefreshJournal j=MiniMaxTestJournal.fixture(dir.toFile());
  MiniMaxRefreshJournal.Ticket old=j.begin("account-a","secret-old");
  String data=journalText(dir);
  check(!data.contains("account-a") && !data.contains("secret-old"),"no plaintext");
  MiniMaxRefreshJournal reopened=MiniMaxTestJournal.fixture(dir.toFile()); blocked(()->reopened.begin("account-b","secret-old"));
  MiniMaxRefreshJournal.Ticket newer=reopened.begin("account-a","secret-new");
  check(j.complete(old),"old ticket transitions own fingerprint"); check(!j.complete(old),"old owner cannot complete twice");
  blocked(()->j.begin("account-b","secret-new")); blocked(()->j.begin("account-b","secret-old"));
  check(j.complete(newer),"new owner finishes"); blocked(()->j.begin("account-c","secret-new"));
  // A ticket belongs to journal content, not to a process instance; a foreign journal cannot use it.
  MiniMaxRefreshJournal.Ticket restart=MiniMaxTestJournal.fixture(dir.toFile()).begin("account-a","secret-restart");
  check(MiniMaxTestJournal.fixture(dir.toFile()).complete(restart),"restart completes its own fence");
  Path foreign=temp("minimax-foreign"); MiniMaxRefreshJournal.Ticket stranger=MiniMaxTestJournal.fixture(foreign.toFile()).begin("account-a","secret-restart");
  check(!j.complete(stranger),"another journal cannot complete this fence");
  blocked(()->MiniMaxTestJournal.fixture(dir.toFile()).begin("account-a","secret-restart"));
  // A ticket is bound to its exact fence: a same-fingerprint record with a foreign owner is refused.
  Path staleDir=temp("minimax-stale"); MiniMaxRefreshJournal stale=MiniMaxTestJournal.fixture(staleDir.toFile());
  MiniMaxRefreshJournal.Ticket staleTicket=stale.begin("account","token");
  String[] fields=journalText(staleDir).split("\n")[1].split(" ");
  char[] otherOwner=new char[64]; Arrays.fill(otherOwner,'a');
  Files.write(journalFile(staleDir),("MINIMAX-JOURNAL-2\n"+fields[0]+" "+fields[1]+" "+new String(otherOwner)+" "+fields[3]+"\n").getBytes("US-ASCII"));
  check(!stale.complete(staleTicket),"a ticket with a stale owner completed a foreign fence");
  blocked(()->stale.begin("account","token"));
 }

 static void legacyMigration() throws Exception {
  // Synthesize legacy v1 from an existing valid v2 record, preserving HMAC values.
  Path legacyDir=temp("minimax-v1"); MiniMaxRefreshJournal legacy=MiniMaxTestJournal.fixture(legacyDir.toFile());legacy.begin("a","old");
  String[] lines=journalText(legacyDir).split("\n"); String[] record=lines[1].split(" ");
  String v1="MINIMAX-JOURNAL-1\n"+record[1]+" "+record[0]+" "+record[2]+"\n"; Files.write(journalFile(legacyDir),v1.getBytes("US-ASCII"));
  blocked(()->legacy.begin("different","old"));check(journalText(legacyDir).equals(v1),"blocked v1 read does not rewrite");
  legacy.begin("a","fresh");check(journalText(legacyDir).startsWith("MINIMAX-JOURNAL-2\n"),"v1 migrated");blocked(()->legacy.begin("third","old"));
  check(MiniMaxTestJournal.fixture(legacyDir.toFile()).stats().fences==2,"migration keeps both fences");
 }

 static void identityRejection() throws Exception {
  Path dir=temp("minimax-identity"); MiniMaxRefreshJournal j=MiniMaxTestJournal.fixture(dir.toFile());
  refused(()->j.begin(null,"token"),"null account accepted");
  refused(()->j.begin("account",null),"null refresh accepted");
  refused(()->j.begin("","token"),"empty account accepted");
  refused(()->j.begin("account",""),"empty refresh accepted");
  check(j.stats().fences==0,"a rejected begin wrote a fence");
  try { j.complete(null); throw new AssertionError("null ticket accepted"); } catch(IllegalArgumentException expected) {}
 }

 static void corruptionIsFailClosed() throws Exception {
  Path missing=temp("minimax-missing"); File missingDir=missing.resolve("missing").toFile(); MiniMaxRefreshJournal bad=new MiniMaxRefreshJournal(missingDir);
  refused(()->bad.begin("a","b"),"write failure accepted");
  check(!missingDir.exists(),"a failed begin created a missing directory");

  Path emptyDir=temp("minimax-empty"); MiniMaxRefreshJournal empty=MiniMaxTestJournal.fixture(emptyDir.toFile()); empty.begin("a","b");
  Files.write(journalFile(emptyDir),new byte[0]);
  refused(()->empty.begin("a","c"),"an empty journal was treated as an empty fence set");
  try { empty.stats(); throw new AssertionError("stats hid an empty journal"); } catch(IOException expected) {}

  Path corruptDir=temp("minimax-corrupt"); MiniMaxRefreshJournal corrupt=MiniMaxTestJournal.fixture(corruptDir.toFile()); corrupt.begin("a","b");
  Files.write(journalFile(corruptDir),new byte[]{1,2,3});
  refused(()->corrupt.begin("a","b"),"corruption accepted");
  try { corrupt.stats(); throw new AssertionError("stats hid corruption"); } catch(IOException expected) {}

  Path dupDir=temp("minimax-duplicate"); MiniMaxRefreshJournal duplicate=MiniMaxTestJournal.fixture(dupDir.toFile()); duplicate.begin("a","b");
  String line=journalText(dupDir).split("\n")[1];
  Files.write(journalFile(dupDir),("MINIMAX-JOURNAL-2\n"+line+"\n"+line+"\n").getBytes("US-ASCII"));
  refused(()->duplicate.begin("a","c"),"duplicate fingerprint accepted");
  check(!MiniMaxRefreshJournal.Blocked.class.isAssignableFrom(MiniMaxRefreshJournal.CapacityExceeded.class),"capacity must not be reported as an uncertain outcome");

  Path bigDir=temp("minimax-oversized"); MiniMaxRefreshJournal big=MiniMaxTestJournal.fixture(bigDir.toFile()); big.begin("a","b");
  byte[] huge=new byte[128*1024+1]; Arrays.fill(huge,(byte)'a'); Files.write(journalFile(bigDir),huge);
  refused(()->big.begin("a","c"),"an oversized journal accepted");

  // Losing the key must never re-key the journal: a fresh key would silently unblock every fence.
  Path keyDir=temp("minimax-keylost"); MiniMaxRefreshJournal keyed=MiniMaxTestJournal.fixture(keyDir.toFile());
  MiniMaxRefreshJournal.Ticket kept=keyed.begin("a","b");
  Files.delete(keyDir.resolve("minimax-refresh.key"));
  refused(()->keyed.begin("a","c"),"a missing key accepted a new fence");
  refused(()->keyed.complete(kept),"a missing key accepted a completion");
  check(!keyDir.resolve("minimax-refresh.key").toFile().exists(),"a missing key was regenerated while fences exist");
  MiniMaxRefreshJournal.Stats stuck=MiniMaxTestJournal.fixture(keyDir.toFile()).stats();
  check(stuck.journalPresent && !stuck.keyPresent && stuck.fences==1 && stuck.pending==1,"diagnostics hides a keyless fence");
 }

 static void crashWindows() throws Exception {
  // The process died after the fence became durable and before the provider was contacted.
  Path dir=temp("minimax-crash"); MiniMaxRefreshJournal first=MiniMaxTestJournal.fixture(dir.toFile());
  first.begin("account","old-refresh");
  MiniMaxRefreshJournal after=MiniMaxTestJournal.fixture(dir.toFile());
  blocked(()->after.begin("account","old-refresh"));
  MiniMaxRefreshJournal.Ticket rotated=after.begin("account","new-refresh");
  check(after.complete(rotated),"the rotated token still works after a crash");

  // Injected failure after the fence rename: begin() throws, so the caller must not assume
  // "nothing happened"; the token stays blocked across a restart. The injection needs real
  // directory fsync, so filesystems that cannot sync a directory skip only these two cases.
  Path beginFail=temp("minimax-beginfail");
  Path completeFail=temp("minimax-completefail");
  if(MiniMaxTestJournal.unsupported(beginFail.toFile()) || MiniMaxTestJournal.unsupported(completeFail.toFile())) {
   System.out.println("durability-window injection skipped: directory sync unsupported here");
   return;
  }
  Flaky flaky=new Flaky(beginFail.toFile(),2);
  refused(()->flaky.begin("account","unsent"),"an injected begin failure was swallowed");
  check(journalText(beginFail).split("\n",-1).length==3,"a failed begin must leave exactly one fence");
  blocked(()->MiniMaxTestJournal.fixture(beginFail.toFile()).begin("account","unsent"));
  MiniMaxRefreshJournal.Stats visible=MiniMaxTestJournal.fixture(beginFail.toFile()).stats();
  check(visible.fences==1 && visible.pending==1,"a failed begin is not observable as a fence");
  blocked(()->flaky.begin("account","unsent"));

  // A failing completion may not unblock the token either.
  MiniMaxRefreshJournal owner=MiniMaxTestJournal.fixture(completeFail.toFile());
  MiniMaxRefreshJournal.Ticket ticket=owner.begin("account","old-refresh");
  refused(()->new Flaky(completeFail.toFile(),1).complete(ticket),"an injected completion failure was swallowed");
  MiniMaxRefreshJournal.Stats afterFailure=MiniMaxTestJournal.fixture(completeFail.toFile()).stats();
  check(afterFailure.fences==1,"a failed completion dropped the fence");
  blocked(()->MiniMaxTestJournal.fixture(completeFail.toFile()).begin("account","old-refresh"));
 }

 static void capacity() throws Exception {
  Path dir=temp("minimax-capacity"); MiniMaxRefreshJournal full=MiniMaxTestJournal.fixture(dir.toFile());
  for(int i=0;i<512;i++) { MiniMaxRefreshJournal.Ticket t=full.begin("a","token"+i);check(full.complete(t),"completion"); }
  MiniMaxRefreshJournal.Stats at=full.stats();
  check(at.fences==512 && at.spent==512 && at.pending==0 && at.capacity==512,"capacity accounting");
  long size=Files.size(journalFile(dir)); check(size<=128*1024,"a full journal exceeds its byte bound: "+size);
  byte[] before=Files.readAllBytes(journalFile(dir));
  full(()->full.begin("a","another"));
  check(Arrays.equals(before,Files.readAllBytes(journalFile(dir))),"a refused begin rewrote the journal");
  blocked(()->full.begin("other","token0"));
  blocked(()->full.begin("other","token511"));
  MiniMaxRefreshJournal.Stats restarted=MiniMaxTestJournal.fixture(dir.toFile()).stats();
  check(restarted.fences==512 && restarted.spent==512,"a full journal is unreadable after restart");
  check(!journalText(dir).contains("token511"),"no plaintext at capacity");
 }

 static void observability() throws Exception {
  Path dir=temp("minimax-stats"); MiniMaxRefreshJournal j=MiniMaxTestJournal.fixture(dir.toFile());
  MiniMaxRefreshJournal.Stats empty=j.stats();
  check(!empty.journalPresent && !empty.keyPresent && empty.fences==0 && empty.bytes==0 && empty.capacity==512,"fresh diagnostics");
  check(!dir.resolve("minimax-refresh.key").toFile().exists() && !journalFile(dir).toFile().exists() && !dir.resolve("minimax-refresh.lock").toFile().exists(),"stats has side effects");
  MiniMaxRefreshJournal.Ticket t=j.begin("secret-account","secret-refresh");
  MiniMaxRefreshJournal.Stats pending=j.stats();
  check(pending.journalPresent && pending.keyPresent && pending.fences==1 && pending.pending==1 && pending.spent==0 && pending.bytes==journalText(dir).length(),"pending diagnostics");
  j.complete(t); MiniMaxRefreshJournal.Stats spent=j.stats();
  check(spent.fences==1 && spent.pending==0 && spent.spent==1,"spent diagnostics");
  String text=spent.toString();
  check(!text.contains("secret-account") && !text.contains("secret-refresh"),"diagnostics leaked an identifier");
  check(!text.matches("(?s).*[0-9a-f]{32,}.*"),"diagnostics leaked a fingerprint: "+text);
  for(java.lang.reflect.Field f:MiniMaxRefreshJournal.Stats.class.getDeclaredFields()) check(f.getType().isPrimitive(),"diagnostics can carry a secret: "+f);
  boolean sealed=false;
  for(java.lang.reflect.Constructor<?> c:MiniMaxRefreshJournal.Stats.class.getDeclaredConstructors()) sealed|=java.lang.reflect.Modifier.isPrivate(c.getModifiers());
  check(sealed,"diagnostics can be fabricated");
  Files.write(journalFile(dir),new byte[]{1,2,3});
  try { j.stats(); throw new AssertionError("stats reported on a corrupt journal"); } catch(IOException expected) {}
 }

 static void concurrency() throws Exception {
  Path parallel=temp("minimax-concurrency"); AtomicInteger success=new AtomicInteger(), blocked=new AtomicInteger(); CountDownLatch start=new CountDownLatch(1); Thread[] threads=new Thread[8];
  for(int i=0;i<threads.length;i++) { final int n=i;threads[i]=new Thread(()->{try {start.await();MiniMaxTestJournal.fixture(parallel.toFile()).begin("a"+n,"b");success.incrementAndGet();}catch(MiniMaxRefreshJournal.Blocked e){blocked.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}});threads[i].start(); }
  start.countDown();for(Thread t:threads)t.join();check(success.get()==1 && blocked.get()==7,"global single intent");
 }
}
