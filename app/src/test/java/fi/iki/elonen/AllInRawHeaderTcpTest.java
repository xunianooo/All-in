package fi.iki.elonen;

import fi.iki.elonen.AllInRawHeaderInputStream;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AllInRawHeaderTcpTest {
    static class Server extends NanoHTTPD {
        Server() throws IOException { super("127.0.0.1", 0); start(3000, false); }
        @Override protected ClientHandler createClientHandler(Socket socket, InputStream input) {
            try { return super.createClientHandler(socket, new AllInRawHeaderInputStream(input, socket.getOutputStream())); }
            catch (IOException ex) { throw new RuntimeException(ex); }
        }
        @Override public Response serve(IHTTPSession session) {
            try {
                long remaining = Long.parseLong(session.getHeaders().getOrDefault("content-length", "0"));
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                while (remaining > 0) {
                    int n = session.getInputStream().read(buf, 0, (int)Math.min(buf.length, remaining));
                    if (n < 0) break;
                    body.write(buf, 0, n); remaining -= n;
                }
                return newFixedLengthResponse(new String(body.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception ex) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", ex.toString()); }
        }
    }
    static String exchange(int port, byte[] payload) throws Exception {
        return exchange(port, payload, null);
    }
    static String exchange(int port, byte[] payload, String expectedReject) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000); s.getOutputStream().write(payload); s.shutdownOutput();
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = s.getInputStream().read(buf)) != -1) out.write(buf, 0, n);
            } catch (SocketException ex) {
                String partial = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
                if (expectedReject == null || !ex.getMessage().toLowerCase(Locale.ROOT).contains("reset")) throw ex;
                verifyReject(partial, expectedReject);
                System.out.println("RESET after complete " + expectedReject + " response, requestBytes=" + payload.length + ", responseBytes=" + out.size() + ", wire=" + partial.replace("\r", "\\r").replace("\n", "\\n"));
                return partial;
            }
            String result = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (expectedReject != null) verifyReject(result, expectedReject);
            return result;
        }
    }
    static void verifyReject(String wire, String expected) {
        int end = wire.indexOf("\r\n\r\n");
        if (end < 0 || end + 4 != wire.length()) throw new AssertionError("incomplete response or extra bytes: " + wire);
        String[] lines = wire.substring(0, end).split("\r\n");
        if (!lines[0].equals("HTTP/1.1 " + expected)) throw new AssertionError("wrong status: " + wire);
        int lengths = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].regionMatches(true, 0, "Content-Length:", 0, 15)) {
                lengths++;
                if (!lines[i].substring(15).trim().equals("0")) throw new AssertionError("nonzero length: " + wire);
            }
        }
        if (lengths != 1) throw new AssertionError("missing or duplicate length: " + wire);
    }
    static void check(int port, String wire, String expected) throws Exception {
        String reject = expected.equals("400 Bad Request") || expected.equals("501 Not Implemented") ? expected : null;
        String got = exchange(port, wire.getBytes(StandardCharsets.UTF_8), reject);
        if (!got.contains(expected)) throw new AssertionError(expected + " absent: " + got);
    }
    public static void main(String[] args) throws Exception {
        Server server = new Server();
        try {
            int p = server.getListeningPort();
            String prefix = "POST / HTTP/1.1\r\nHost: localhost\r\n";
            for (String fields : Arrays.asList("Content-Length: 2\r\nContent-Length: 2", "Content-Length: 2\r\ncontent-length: 3", "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked", "Content-Length: 2\r\nTransfer-Encoding: chunked", "Content-Length: +2", "Content-Length: 2, 2", "Content-Length: 9223372036854775808", "X: a\r\n Content-Length: 2", "Content-Length : 2"))
                check(p, prefix + fields + "\r\n\r\n", "400 Bad Request");
            check(p, prefix + "Transfer-Encoding: chunked\r\n\r\n", "501 Not Implemented");
            for (String v : Arrays.asList("gzip", "chunked, gzip", "xchunked", ""))
                check(p, prefix + "Transfer-Encoding: " + v + "\r\n\r\n", "400 Bad Request");
            for (String request : Arrays.asList("POST / HTTP/2.0", "POST  / HTTP/1.1", "POST / HTTP/1.1 EXTRA", "POST\t/ HTTP/1.1"))
                check(p, request + "\r\nHost: x\r\n\r\n", "400 Bad Request");
            String bad = exchange(p, (prefix + "Content-Length: 0\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII), "400 Bad Request");
            if (bad.indexOf("HTTP/1.1") != bad.lastIndexOf("HTTP/1.1")) throw new AssertionError("multiple responses " + bad);
            check(p, prefix + "Authorization: Bearer " + new String(new char[7000]).replace('\0','a') + "\r\nConnection: close\r\n\r\n", "200 OK");
            check(p, prefix + "X: " + new String(new char[8200]).replace('\0','a') + "\r\n\r\n", "400 Bad Request");
            check(p, prefix + "Content-Length: 6\r\n\r\n你好", "你好");
            String requests = "GET / HTTP/1.1\r\nHost: x\r\n\r\n" + prefix + "Content-Length: 3\r\nConnection: close\r\n\r\nabc";
            String result = exchange(p, requests.getBytes(StandardCharsets.UTF_8));
            if (result.indexOf("200 OK") == result.lastIndexOf("200 OK") || !result.contains("abc")) throw new AssertionError(result);
            try (Socket fragmented = new Socket("127.0.0.1", p)) {
                fragmented.setSoTimeout(3000); OutputStream out=fragmented.getOutputStream();
                String wire="POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 6\r\n\r\nabcdef" +
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n" +
                    "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nConnection: close\r\n\r\nxyz";
                byte[] all=wire.getBytes(StandardCharsets.US_ASCII);for(int i=0;i<all.length;i+=7){out.write(all,i,Math.min(7,all.length-i));out.flush();}
                ByteArrayOutputStream response=new ByteArrayOutputStream();int c;while((c=fragmented.getInputStream().read())!=-1)response.write(c);
                String result2=response.toString("UTF-8");int count=0,at=0;while((at=result2.indexOf("HTTP/1.1 200",at))>=0){count++;at+=8;}
                if(count!=3||!result2.contains("abcdef")||!result2.contains("xyz"))throw new AssertionError("fragmented pipeline "+result2);
            }
            System.out.println("raw TCP checks passed");
        } finally { server.stop(); }
    }
}
