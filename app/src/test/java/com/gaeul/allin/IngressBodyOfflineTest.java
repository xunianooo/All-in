package com.gaeul.allin;

import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class IngressBodyOfflineTest {
    static int checks;
    static void check(boolean b, String s) { checks++; if (!b) throw new AssertionError(s); }
    static Map<String,String> h(String... pairs) { Map<String,String> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]); return m; }
    static IngressBody.Result read(byte[] b, Map<String,String> h, int limit) { return IngressBody.read(new ByteArrayInputStream(b),h,"POST",limit); }
    static void status(int expected, IngressBody.Result r) { check(r.status==expected,"status expected "+expected+" got "+r.status+" "+r.classification); check(r.mustClose==(expected!=200),"close flag"); }
    public static void main(String[] args) throws Exception {
        byte[] empty=new byte[0];
        for(String s:new String[]{"-1","+1","1.0","1,1","1x","","9223372036854775808","１２"," 1 2 "}) status(400,read(empty,h("content-length",s),8));
        status(413,read(empty,h("content-length","9223372036854775807"),8));
        status(400,read(empty,h("content-length","1","Content-Length","1"),8));
        status(400,read(empty,h("content-length","0","transfer-encoding","chunked"),8));
        status(501,read(empty,h("transfer-encoding","ChUnKeD"),8));
        for(String te:new String[]{"gzip","xchunked","chunked, gzip","chunked;foo=bar",""}) status(400,read(empty,h("transfer-encoding",te),8));
        status(415,read(empty,h("content-encoding","gzip"),8));
        status(200,read(empty,h("content-encoding","identity"),8));
        InputStream forbidden=new InputStream(){public int read(){throw new AssertionError("must not read");} public int read(byte[] b,int o,int l){throw new AssertionError("must not read");}};
        status(200,IngressBody.read(forbidden,h(),"POST",8));
        status(413,IngressBody.read(forbidden,h("content-length","2147483647"),"POST",Integer.MAX_VALUE));
        byte[] bytes="12345678".getBytes(StandardCharsets.UTF_8);
        IngressBody.Result r=read(bytes,h("content-length","8"),8); status(200,r); check(r.bytesRead==8&&r.text.equals("12345678"),"exact limit");
        status(413,read(bytes,h("content-length","9"),8));
        r=read(bytes,h("content-length","9"),9); status(400,r);check(r.classification==IngressBody.Classification.INCOMPLETE&&r.bytesRead==8,"short count");
        byte[] cross=new byte[8195];Arrays.fill(cross,0,8191,(byte)'a');cross[8191]=(byte)0xf0;cross[8192]=(byte)0x9f;cross[8193]=(byte)0x98;cross[8194]=(byte)0x80;
        r=read(cross,h("content-length","8195"),8195);status(200,r);check(r.text.endsWith("😀"),"UTF-8 crossing buffer");
        for(byte[] b:new byte[][]{{(byte)0xc3},{(byte)0xc0,(byte)0x80},{(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80}}) status(400,read(b,h("content-length",Integer.toString(b.length)),8));
        InputStream timeout=new InputStream(){public int read() throws IOException {throw new SocketTimeoutException("secret");}};
        r=IngressBody.read(timeout,h("content-length","1"),"POST",8);status(408,r);check(!r.message.contains("secret"),"safe errors");
        check(IngressBody.effectiveLimit(0)==IngressBody.DEFAULT_LIMIT&&IngressBody.effectiveLimit(Long.MAX_VALUE)==IngressBody.MAX_LIMIT,"config cap");
        ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<Boolean>> fs=new ArrayList<>();for(int i=1;i<=40;i++){final int n=i;fs.add(pool.submit(()->{byte[] b=new byte[n];Arrays.fill(b,(byte)'x');IngressBody.Result q=read(b,h("content-length",Integer.toString(n)),100);return q.bytesRead==n&&q.status==200;}));}for(Future<Boolean> f:fs)check(f.get(),"per-request counters");}finally{pool.shutdownNow();}
        System.out.println("IngressBodyOfflineTest checks="+checks);
    }
}
