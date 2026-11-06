package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Real NanoHTTPD 2.3.1 loopback socket; no upstream or external network. */
public final class IngressBodyHttpTest {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static final class Server extends NanoHTTPD {
        Server(){super("127.0.0.1",0);}
        @Override public Response serve(IHTTPSession s){
            IngressBody.Result r=IngressBody.read(s.getInputStream(),s.getHeaders(),s.getMethod().name(),8);
            Response.IStatus st=new Response.IStatus(){ public String getDescription(){return r.status+" Fixture";} public int getRequestStatus(){return r.status;} };
            Response response=newFixedLengthResponse(st,"text/plain",r.classification+":"+r.bytesRead+":"+r.text);
            if(r.mustClose) response.closeConnection(true);
            return response;
        }
    }
    static String request(int port,String raw,boolean halfClose) throws Exception {
        try(Socket s=new Socket("127.0.0.1",port)){
            s.setSoTimeout(3000);s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();
            if(halfClose)s.shutdownOutput();
            ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
            while((n=s.getInputStream().read(b))!=-1)o.write(b,0,n);
            return o.toString("UTF-8");
        }
    }
    static String req(String header,String body){return "POST / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"+header+"\r\n"+body;}
    public static void main(String[] args)throws Exception{
        Server server=new Server(); server.start(400,false);
        try {
            int p=server.getListeningPort();String x;
            x=request(p,req("Content-Length: 2\r\n","{}"),false);check(x.contains("200 Fixture")&&x.contains("OK:2:{}"),"normal "+x);
            x=request(p,req("",""),false);check(x.contains("200 Fixture")&&x.contains("OK:0:"),"no body no EOF "+x);
            x=request(p,req("Content-Length: 8\r\n","{}"),true);check(x.contains("400 Fixture")&&x.contains("INCOMPLETE:2"),"short "+x);
            x=request(p,req("Content-Length: 8\r\n","{}"),false);check(x.contains("408 Fixture"),"timeout "+x);
            x=request(p,req("Transfer-Encoding: chunked\r\n","2\r\n{}\r\n0\r\n\r\n"),false);check(x.contains("501 Fixture"),"chunked rejected "+x);
            x=request(p,req("Transfer-Encoding: chunked\r\nContent-Length: 2\r\n","{}"),false);check(x.contains("400 Fixture"),"CL+TE "+x);
            x=request(p,req("Content-Length: 9\r\n",""),false);check(x.contains("413 Fixture"),"large no read "+x);
            x=request(p,req("Content-Length: 7\r\nContent-Length: 2\r\n","{}"),false);check(x.contains("200 Fixture")&&x.contains("OK:2:{}"),"raw duplicate limitation changed "+x);
            System.out.println("IngressBodyHttpTest passed; duplicate CL collapsed by NanoHTTPD");
        } finally { server.stop(); }
    }
}
