package com.gaeul.allin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.gaeul.allin.json.JSONObject;

public final class QuirksHttpsTransportOfflineTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static byte[] parse(String s)throws Exception{return QuirksHttpsTransport.readResponse(new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)),64);}
 static void rejects(String s)throws Exception{try{parse(s);throw new AssertionError();}catch(IOException expected){}}
 static String repeat(char c,int n){char[] b=new char[n];java.util.Arrays.fill(b,c);return new String(b);}
 public static void main(String[] args)throws Exception {
  String head="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n";
  check(Arrays.equals(parse(head+"Content-Length: 3\r\n\r\nabc"),new byte[]{97,98,99}));
  check(Arrays.equals(parse(head+"Transfer-Encoding: chunked\r\n\r\n2\r\nab\r\n1\r\nc\r\n0\r\n\r\n"),new byte[]{97,98,99}));
  rejects("HTTP/1.1 302 Found\r\nLocation: https://evil.invalid/\r\n\r\n");rejects("HTTP/1.1 401 Unauthorized\r\n\r\n");
  rejects(head+"Content-Encoding: gzip\r\nContent-Length: 3\r\n\r\nabc");rejects(head+"Content-Length: 1\r\nContent-Length: 1\r\n\r\na");
  rejects(head+"Content-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");rejects(head+"Transfer-Encoding: chunked\r\n\r\n1;ext=x\r\na\r\n0\r\n\r\n");rejects(head+"Content-Length: 65\r\n\r\n");rejects(head+"Content-Length: 3\r\n\r\nab");
  /* ---- status, media type, and framing grammar: the verifier sees nothing lenient ---- */
  String st="HTTP/1.1 200 OK\r\n";
  check(Arrays.equals(parse("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\na"),new byte[]{97}));
  check(Arrays.equals(parse("HTTP/1.1 200\r\nContent-Type: application/octet-stream\r\nContent-Length: 0\r\n\r\n"),new byte[0]));
  check(Arrays.equals(parse(st+"Content-Type: application/octet-stream\r\n\r\nabcdef"),"abcdef".getBytes(StandardCharsets.US_ASCII)));
  rejects("HTTP/1.1 2000 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\na");
  rejects("HTTP/2 200\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\na");
  rejects("HTTP/1.1 200 OK\nContent-Type: application/octet-stream\n\na");
  rejects(st+"Content-Type: application/octet-stream; charset=utf-8\r\nContent-Length: 1\r\n\r\na");
  rejects(st+"Content-Type: text/plain\r\nContent-Length: 1\r\n\r\na");
  rejects(st+"Transfer-Encoding: identity\r\n\r\na");
  rejects(st+"Content-Length: 1\r\n\r\nab");
  /* header count, single-line length, and the shared header budget are all bounded */
  StringBuilder many=new StringBuilder(st+"Content-Type: application/octet-stream\r\n");
  for(int i=0;i<64;i++)many.append("X-Pad-").append(i).append(": v\r\n");
  many.append("Content-Length: 0\r\n\r\n");rejects(many.toString());
  rejects(st+"Content-Type: application/octet-stream\r\nX-Long: "+repeat('a',5000)+"\r\nContent-Length: 0\r\n\r\n");
  StringBuilder budget=new StringBuilder(st+"Content-Type: application/octet-stream\r\n");
  for(int i=0;i<40;i++)budget.append("X-B").append(i).append(": ").append(repeat('b',400)).append("\r\n");
  budget.append("Content-Length: 0\r\n\r\n");rejects(budget.toString());
  /* chunked: terminator, chunk size line, and trailer rules */
  check(Arrays.equals(parse(head+"Transfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n\r\n"),new byte[]{97}));
  rejects(head+"Transfer-Encoding: chunked\r\n\r\n1\r\na\rX0\r\n\r\n");
  rejects(head+"Transfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: v\r\n\r\n");
  rejects(head+"Transfer-Encoding: chunked\r\n\r\nzz\r\na\r\n0\r\n\r\n");
  /* EOF-delimited bodies stay inside the same ceiling as declared ones */
  rejects(st+"Content-Type: application/octet-stream\r\n\r\n"+repeat('c',65));
  // Policy CONNECT is plain socket I/O: verify global cookie and authenticator never run, and no auth is sent.
  CookieHandler oldCookie=CookieHandler.getDefault(); final AtomicInteger called=new AtomicInteger();
  CookieHandler.setDefault(new CookieHandler(){public Map<String,List<String>> get(URI u,Map<String,List<String>> h){called.incrementAndGet();return Collections.emptyMap();}public void put(URI u,Map<String,List<String>> h){called.incrementAndGet();}});
  Authenticator.setDefault(new Authenticator(){protected PasswordAuthentication getPasswordAuthentication(){called.incrementAndGet();return new PasswordAuthentication("secret","secret".toCharArray());}});
  ServerSocket proxy=new ServerSocket(0); AtomicReference<String> wire=new AtomicReference<>();Thread t=new Thread(()->{try(Socket s=proxy.accept()){s.setSoTimeout(3000);ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=s.getInputStream().read())!=-1){b.write(c);if(b.toString("US-ASCII").endsWith("\r\n\r\n"))break;}wire.set(b.toString("US-ASCII"));s.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes(StandardCharsets.US_ASCII));}catch(Exception e){throw new RuntimeException(e);}});t.start();
  try{NetworkPolicy.Snapshot p=NetworkPolicy.parse(new JSONObject().put("mode","http_proxy").put("host","127.0.0.1").put("port",proxy.getLocalPort()));QuirksHttpsTransport transport=new QuirksHttpsTransport(System.nanoTime()+TimeUnit.SECONDS.toNanos(3),1000);try{transport.fetch(p,"https://example.org/rules");throw new AssertionError();}catch(IOException expected){}finally{transport.close();}t.join(4000);check(wire.get()!=null && wire.get().startsWith("CONNECT example.org:443 HTTP/1.1\r\n"));check(!wire.get().toLowerCase(Locale.ROOT).contains("authorization")&&!wire.get().toLowerCase(Locale.ROOT).contains("cookie"));check(called.get()==0);}finally{proxy.close();CookieHandler.setDefault(oldCookie);Authenticator.setDefault(null);}
  System.out.println("QuirksHttpsTransportOfflineTest PASS");
 }
}
