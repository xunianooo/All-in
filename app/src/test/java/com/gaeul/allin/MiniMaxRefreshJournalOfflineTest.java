package com.gaeul.allin;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MiniMaxRefreshJournalOfflineTest {
 static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
 interface Run { void run() throws Exception; }
 static void blocked(Run f) throws Exception { try { f.run(); throw new AssertionError("not blocked"); } catch(MiniMaxRefreshJournal.Blocked expected) {} }
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("minimax-journal"); MiniMaxRefreshJournal j=new MiniMaxRefreshJournal(dir.toFile());
  MiniMaxRefreshJournal.Ticket old=j.begin("account-a","secret-old");
  String data=new String(Files.readAllBytes(dir.resolve("minimax-refresh.journal")),"US-ASCII");
  check(!data.contains("account-a") && !data.contains("secret-old"),"no plaintext");
  MiniMaxRefreshJournal reopened=new MiniMaxRefreshJournal(dir.toFile()); blocked(()->reopened.begin("account-b","secret-old"));
  MiniMaxRefreshJournal.Ticket newer=reopened.begin("account-a","secret-new");
  check(j.complete(old),"old ticket transitions own fingerprint"); check(!j.complete(old),"old owner cannot complete twice");
  blocked(()->j.begin("account-b","secret-new")); blocked(()->j.begin("account-b","secret-old"));
  check(j.complete(newer),"new owner finishes"); blocked(()->j.begin("account-c","secret-new"));
  // Synthesize legacy v1 from an existing valid v2 record, preserving HMAC values.
  Path legacyDir=Files.createTempDirectory("minimax-v1"); MiniMaxRefreshJournal legacy=new MiniMaxRefreshJournal(legacyDir.toFile());legacy.begin("a","old");
  String[] lines=new String(Files.readAllBytes(legacyDir.resolve("minimax-refresh.journal")),"US-ASCII").split("\n"); String[] record=lines[1].split(" ");
  String v1="MINIMAX-JOURNAL-1\n"+record[1]+" "+record[0]+" "+record[2]+"\n"; Files.write(legacyDir.resolve("minimax-refresh.journal"),v1.getBytes("US-ASCII"));
  blocked(()->legacy.begin("different","old"));check(new String(Files.readAllBytes(legacyDir.resolve("minimax-refresh.journal")),"US-ASCII").equals(v1),"blocked v1 read does not rewrite");
  legacy.begin("a","fresh");check(new String(Files.readAllBytes(legacyDir.resolve("minimax-refresh.journal")),"US-ASCII").startsWith("MINIMAX-JOURNAL-2\n"),"v1 migrated");blocked(()->legacy.begin("third","old"));
  MiniMaxRefreshJournal bad=new MiniMaxRefreshJournal(dir.resolve("missing").toFile());try {bad.begin("a","b");throw new AssertionError("write failure accepted");} catch(IOException expected) {}
  Path badDir=Files.createTempDirectory("minimax-corrupt"); MiniMaxRefreshJournal corrupt=new MiniMaxRefreshJournal(badDir.toFile()); corrupt.begin("a","b");
  Files.write(badDir.resolve("minimax-refresh.journal"),new byte[]{1,2,3});try {corrupt.begin("a","b");throw new AssertionError("corruption accepted");} catch(IOException expected) {}
  Path parallel=Files.createTempDirectory("minimax-concurrency"); AtomicInteger success=new AtomicInteger(), blocked=new AtomicInteger(); CountDownLatch start=new CountDownLatch(1); Thread[] threads=new Thread[8];
  for(int i=0;i<threads.length;i++) { final int n=i;threads[i]=new Thread(()->{try {start.await();new MiniMaxRefreshJournal(parallel.toFile()).begin("a"+n,"b");success.incrementAndGet();}catch(MiniMaxRefreshJournal.Blocked e){blocked.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}});threads[i].start(); }
  start.countDown();for(Thread t:threads)t.join();check(success.get()==1 && blocked.get()==7,"global single intent");
  Path fullDir=Files.createTempDirectory("minimax-capacity");MiniMaxRefreshJournal full=new MiniMaxRefreshJournal(fullDir.toFile());
  for(int i=0;i<512;i++) { MiniMaxRefreshJournal.Ticket t=full.begin("a","token"+i);check(full.complete(t),"completion"); }
  try {full.begin("a","another");throw new AssertionError("capacity accepted");} catch(IOException expected) {}
  blocked(()->full.begin("other","token0"));
  System.out.println("MiniMaxRefreshJournalOfflineTest passed");
 }
}
