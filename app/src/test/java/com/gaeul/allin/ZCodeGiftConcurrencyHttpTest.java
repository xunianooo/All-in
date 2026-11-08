package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Real loopback requests only; all identities and bodies are synthetic. */
public final class ZCodeGiftConcurrencyHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class Server implements AutoCloseable {
        final ServerSocket listener; final ExecutorService workers=Executors.newCachedThreadPool();
        final AtomicInteger calls=new AtomicInteger();
        final List<byte[]> bodies=Collections.synchronizedList(new ArrayList<byte[]>());
        final List<String> ids=Collections.synchronizedList(new ArrayList<String>());
        volatile String mode="stream"; volatile CountDownLatch release=new CountDownLatch(1);
        Server() throws Exception {
            listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
            workers.submit(() -> { while (!listener.isClosed()) try { final Socket s=listener.accept(); workers.submit(() -> handle(s)); } catch (IOException ignored) {} });
        }
        void handle(Socket s) {
            try (Socket socket=s) {
                socket.setSoTimeout(5000); InputStream in=socket.getInputStream(); ByteArrayOutputStream h=new ByteArrayOutputStream(); int state=0,v;
                while(state<4 && (v=in.read())!=-1) { h.write(v); state=state==0&&v==13?1:state==1&&v==10?2:state==2&&v==13?3:state==3&&v==10?4:0; if(h.size()>65536) throw new IOException("headers"); }
                int len=0; String id=""; for(String line:h.toString("UTF-8").split("\r\n")) { String l=line.toLowerCase(Locale.ROOT); if(l.startsWith("content-length:")) len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim()); if(l.startsWith("authorization:")) id=line.substring(line.indexOf(':')+1).trim(); }
                byte[] body=new byte[len]; for(int off=0;off<len;) { int n=in.read(body,off,len-off); if(n<0) throw new EOFException(); off+=n; }
                bodies.add(body); ids.add(id); int number=calls.incrementAndGet(); String m=mode;
                if(m.equals("disconnect") && number==2) return;
                boolean busy=m.startsWith("busy") && (!m.endsWith("success") || number==1) || m.equals("disconnect") && number==1;
                String code=m.contains("3009")?"3009":m.contains("3010")?"3010":"3008";
                byte[] data=(busy?"{\"code\":"+code+"}":"ok").getBytes("UTF-8");
                OutputStream out=socket.getOutputStream();
                String headers="HTTP/1.1 "+(busy?"429 Busy":"200 OK")+"\r\nContent-Type: "+(busy?"application/json":"text/event-stream")+"\r\n"+(m.equals("busy-retryafter")?"Retry-After: 30\r\n":"")+"Content-Length: "+data.length+"\r\nConnection: close\r\n\r\n";
                out.write(headers.getBytes("UTF-8")); out.flush();
                if(m.equals("stream")) { CountDownLatch latch=release; latch.await(15,TimeUnit.SECONDS); }
                out.write(data); out.flush();
            } catch(Exception ignored) { }
        }
        void reset(String m) { calls.set(0); bodies.clear(); ids.clear(); mode=m; }
        public void close() throws Exception { release.countDown(); listener.close(); workers.shutdownNow(); workers.awaitTermination(3,TimeUnit.SECONDS); }
    }
    static RequestContext ctx(long ms) { return new RequestContext("fixture",NetworkPolicy.capture(),ms); }
    static ZCodeGift.Stream open(String token,String device,String account,RequestContext c) throws Exception {
        return ZCodeGift.openStream(token,device,"zai","model",new JSONObject("{\"messages\":[],\"stream\":true}"),"session",5000,true,c,null,account);
    }
    static void awaitCalls(Server s,int count) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(s.calls.get()<count && System.nanoTime()<end) Thread.yield(); check(s.calls.get()==count,"expected calls "+count+" got "+s.calls.get()); }
    static void queued(int count) throws Exception {
        Field g=ZCodeGift.class.getDeclaredField("giftGate"); g.setAccessible(true); Object gate=g.get(null); Field q=gate.getClass().getDeclaredField("queued"); q.setAccessible(true);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(System.nanoTime()<end) { synchronized(gate) { if(q.getInt(gate)==count) return; } Thread.yield(); } throw new AssertionError("queue "+count);
    }
    static void resetBusy() throws Exception { ZCodeGift.class.getDeclaredMethod("resetBusyForTests").invoke(null); ZCodeGift.resetCooldownForTests(); }
    static void close(ZCodeGift.Stream s) throws Exception { if(s.is!=null) s.is.close(); }
    static void retries(Server s,String mode,int expected,String code,long budget) throws Exception {
        resetBusy(); s.reset(mode); RequestContext c=ctx(budget); long start=System.nanoTime(); ZCodeGift.Stream r=open("retry","device","retry-account",c);
        check(r.sends==expected && s.calls.get()==expected,mode+" sends "+r.sends+"/"+s.calls.get());
        check("upstream".equals(r.source) && code.equals(r.busyCode),mode+" source/code");
        for(byte[] b:s.bodies) check(Arrays.equals(s.bodies.get(0),b),mode+" retry body differs");
        if(mode.endsWith("success")) { check(r.status==200,mode+" status"); check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>=900,mode+" backoff"); }
        else check(r.status==429,mode+" status"); close(r); c.close();
    }
    public static void main(String[] args) throws Exception {
        ExecutorService pool=Executors.newCachedThreadPool();
        try(Server s=new Server()) {
            ZCodeGift.setFixtureBase("http://127.0.0.1:"+s.listener.getLocalPort()); resetBusy();
            RequestContext a=ctx(12000),b=ctx(12000),c=ctx(12000),d=ctx(12000),e=ctx(12000),other=ctx(12000);
            ZCodeGift.Stream x=open("old1","dev1","same",a), y=open("old2","dev2","same",b), z=open("new","dev3","same",c);
            check(x.status==200&&y.status==200&&z.status==200,"three streams"); awaitCalls(s,3);
            Future<ZCodeGift.Stream> fourth=pool.submit(() -> open("fourth","refreshed","same",d)); queued(1);
            Future<ZCodeGift.Stream> fifth=pool.submit(() -> open("fifth","refreshed","same",e)); queued(2);
            ZCodeGift.Stream independent=open("other","dev","other-account",other); check(independent.status==200,"different account"); awaitCalls(s,4);
            close(x); ZCodeGift.Stream f=fourth.get(3,TimeUnit.SECONDS); check(f.status==200,"fourth admitted"); awaitCalls(s,5); check(s.ids.get(4).contains("fourth"),"FIFO first");
            close(y); ZCodeGift.Stream g=fifth.get(3,TimeUnit.SECONDS); check(g.status==200,"fifth admitted"); awaitCalls(s,6); check(s.ids.get(5).contains("fifth"),"FIFO second");
            close(z); close(f); close(g); close(independent); a.close();b.close();c.close();d.close();e.close();other.close();
            // With three occupied slots, both cancelled and expired waiters must never send.
            RequestContext p=ctx(8000),q=ctx(8000),r=ctx(8000),cancel=ctx(8000),shortCtx=ctx(180);
            ZCodeGift.Stream p1=open("p1","d","waiting",p),p2=open("p2","d","waiting",q),p3=open("p3","d","waiting",r); int before=s.calls.get();
            Future<ZCodeGift.Stream> cancelled=pool.submit(() -> open("cancel","d","waiting",cancel)); queued(1); cancel.cancel("test"); ZCodeGift.Stream cr=cancelled.get(3,TimeUnit.SECONDS); check(!cr.sent,"cancel sent"); queued(0);
            ZCodeGift.Stream expired=open("expired","d","waiting",shortCtx); check(!expired.sent && s.calls.get()==before,"budget sent");
            // Cancel an active stream and verify a waiting request obtains its slot.
            RequestContext next=ctx(5000); Future<ZCodeGift.Stream> wait=pool.submit(() -> open("after-cancel","d","waiting",next)); queued(1); p.cancel("test"); ZCodeGift.Stream after=wait.get(3,TimeUnit.SECONDS); check(after.status==200,"cancel leaked slot");
            close(p1);close(p2);close(p3);close(after); p.close();q.close();r.close();next.close();shortCtx.close();
            // EOF and read exceptions each release ownership; following streams must enter.
            RequestContext eofCtx=ctx(5000), eof2=ctx(5000), eof3=ctx(5000), eof4=ctx(5000);
            ZCodeGift.Stream eof=open("eof","d","eof",eofCtx), es2=open("eof2","d","eof",eof2), es3=open("eof3","d","eof",eof3);
            Future<ZCodeGift.Stream> eofWait=pool.submit(() -> open("eof4","d","eof",eof4)); queued(1);
            s.release.countDown(); while(eof.is.read()!=-1) {} ZCodeGift.Stream eofNext=eofWait.get(3,TimeUnit.SECONDS);
            check(eofNext.status==200,"EOF leaked slot"); close(eof);close(es2);close(es3);close(eofNext);eofCtx.close();eof2.close();eof3.close();eof4.close();
            s.release=new CountDownLatch(1); RequestContext ex=ctx(600), ex2=ctx(5000),ex3=ctx(5000),ex4=ctx(5000);
            ZCodeGift.Stream exs=open("exception","d","exception",ex), exs2=open("exception2","d","exception",ex2), exs3=open("exception3","d","exception",ex3);
            Future<ZCodeGift.Stream> exWait=pool.submit(() -> open("exception4","d","exception",ex4)); queued(1); try { exs.is.read(); throw new AssertionError("expected read exception"); } catch(IOException expected) { }
            ZCodeGift.Stream exNext=exWait.get(3,TimeUnit.SECONDS); check(exNext.status==200,"read exception leaked slot"); close(exs2);close(exs3);close(exNext);ex.close();ex2.close();ex3.close();ex4.close(); s.release.countDown();
            retries(s,"busy-3008-success",2,"3008",5000); retries(s,"busy-3009-success",2,"3009",5000); retries(s,"busy-3010-success",2,"3010",5000);
            retries(s,"busy-3008",3,"3008",6000); retries(s,"busy-retryafter",1,"3008",1500);
            resetBusy(); s.reset("disconnect"); RequestContext net=ctx(5000); ZCodeGift.Stream failed=open("network","device","network-account",net);
            check(failed.sends==2 && s.calls.get()==2,"disconnect sends "+failed.sends+"/"+s.calls.get()); check(failed.status!=429 && failed.transportKind!=null,"disconnect must report transport failure, got "+failed.status); net.close();
            System.out.println("ZCodeGiftConcurrencyHttpTest PASS");
        } finally { ZCodeGift.setFixtureBase(null); pool.shutdownNow(); }
    }
}
