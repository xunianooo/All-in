package com.gaeul.allin;

import java.net.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** Synthetic loopback coverage, Java 8 and Android compatible. */
public final class ZCodeGiftPolicyHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger calls=new AtomicInteger(); final String[] mode={"3012"}, received={""}, sid={""}; final long[] now={0};
        Thread worker=new Thread(new Runnable() { public void run() {
            while (!server.isClosed()) try {
                Socket socket=server.accept(); socket.setSoTimeout(5000);
                try {
                    InputStream input=socket.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream();
                    int state=0, v;
                    while (state<4 && (v=input.read())!=-1) {
                        headers.write(v); state=state==0&&v==13?1:state==1&&v==10?2:state==2&&v==13?3:state==3&&v==10?4:0;
                        if (headers.size()>65536) throw new IOException("headers too large");
                    }
                    int length=0; for (String line:headers.toString("UTF-8").split("\r\n")) {
                        String lower=line.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                        if (lower.startsWith("x-session-id:")) sid[0]=line.substring(line.indexOf(':')+1).trim();
                    }
                    ByteArrayOutputStream request=new ByteArrayOutputStream();
                    while (length-->0) { v=input.read(); if(v<0) throw new EOFException(); request.write(v); }
                    received[0]=request.toString("UTF-8"); calls.incrementAndGet(); String m=mode[0];
                    int status=m.equals("redirect")?302:m.equals("ok") || m.equals("slow")?200:405;
                    String text=m.equals("3012")?"{\"code\":3012}":m.equals("edge")?"<html>errors.aliyun.com</html>":m.equals("huge")?new String(new char[70000]).replace('\0','x'):m.equals("ok")?"{\"content\":[]}":"plain";
                    byte[] data=text.getBytes("UTF-8"); String extra="";
                    if (m.equals("gzip")) { ByteArrayOutputStream b=new ByteArrayOutputStream(); GZIPOutputStream z=new GZIPOutputStream(b); z.write("{\"code\":3012}".getBytes("UTF-8")); z.close(); data=b.toByteArray(); extra="Content-Encoding: gzip\r\n"; }
                    if (m.equals("redirect")) extra+="Location: /follow\r\n";
                    if (m.equals("slow")) { data="ab".getBytes("UTF-8"); extra+="Content-Type: text/event-stream\r\n"; }
                    else extra+="Content-Type: application/json\r\n";
                    OutputStream out=socket.getOutputStream(); out.write(("HTTP/1.1 "+status+" Test\r\n"+extra+"Content-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    if (m.equals("slow")) { out.write(data,0,1); out.flush(); try { Thread.sleep(350); } catch (InterruptedException ignored) {} out.write(data,1,1); }
                    else out.write(data);
                    out.flush();
                } finally { socket.close(); }
            } catch (IOException ignored) { }
        }}); worker.setDaemon(true); worker.start();
        try {
            ZCodeGift.setFixtureBase("http://127.0.0.1:"+server.getLocalPort());
            ZCodeGift.setCooldownClockForTests(() -> now[0]);
            JSONObject request=new JSONObject("{\"messages\":[],\"metadata\":{\"user_id\":\"existing\"}}");
            ZCodeGift.Stream first=ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false);
            check(first.refused && first.status==405 && first.sent,"3012 classification");
            check(new JSONObject(received[0]).getJSONObject("metadata").getString("user_id").equals("existing"),"preserve metadata");
            check(sid[0].equals(ZCodeGift.sessionOf("sess","device")),"session header");
            ZCodeGift.Stream held=ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false);
            check(held.held && held.status==429 && calls.get()==1 && !held.sent,"hold before network");
            check(ZCodeGift.hold("token","device",null)!=null,"hold helper reflects cooldown");
            now[0]+=ZCodeRefusalPolicy.HOLD_MILLIS; mode[0]="plain";
            ZCodeGift.Stream bare=ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false);
            check(!bare.refused && bare.classification==ZCodeRefusalPolicy.Kind.UNKNOWN,"bare 405");
            ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false); check(calls.get()==3,"bare no hold");
            mode[0]="gzip"; check(ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false).refused,"gzip");
            ZCodeGift.resetCooldownForTests(); mode[0]="huge"; check(!ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false).refused,"truncated");
            mode[0]="redirect"; int before=calls.get(); check(ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false).status==302 && calls.get()==before+1,"redirect");
            mode[0]="edge"; check(ZCodeGift.openStream("token","device","zai","m",request,"sess",3000,false).edge,"edge");
            check(ZCodeGift.openStream("other","device","zai","m",request,"sess",3000,false).held,"edge network scope");
            ZCodeGift.resetCooldownForTests(); mode[0]="ok"; JSONObject empty=new JSONObject("{\"messages\":[]}");
            ZCodeGift.Stream ok=ZCodeGift.openStream("token","device","zai","m",empty,"sess",3000,false);
            JSONObject uid=new JSONObject(new JSONObject(received[0]).getJSONObject("metadata").getString("user_id"));
            check(uid.getString("session_id").equals(sid[0]),"generated session matches"); ok.is.close();
            mode[0]="slow"; RequestContext deadline=new RequestContext("gift",NetworkPolicy.capture(),150);
            ZCodeGift.Stream slow=ZCodeGift.openStream("token","device","zai","m",empty,"sess",3000,false,deadline);
            check(slow.status==200,"slow response opened"); boolean expired=false;
            try { while(slow.is.read()!=-1) {} } catch(IOException expected) { expired=true; }
            check(expired && deadline.remainingMillis()==0,"stream enforces deadline"); deadline.close();
            RequestContext cancelled=new RequestContext("gift",NetworkPolicy.capture(),3000); cancelled.cancel("test");
            int countBeforeCancel=calls.get(); ZCodeGift.Stream stopped=ZCodeGift.openStream("token","device","zai","m",empty,"sess",3000,false,cancelled);
            check(stopped.status==-1 && !stopped.sent && calls.get()==countBeforeCancel,"cancel before send");
            check(ZCodeGift.hold("token","device",null)==null,"hold helper clear");
            System.out.println("ZCodeGiftPolicyHttpTest PASS");
        } finally { ZCodeGift.setFixtureBase(null); ZCodeGift.resetCooldownForTests(); server.close(); }
    }
}
