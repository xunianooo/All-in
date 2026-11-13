package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.*;

/** One-shot Trae JSON transport: no automatic authentication retry or redirect. */
final class TraeHttpTransport implements Closeable {
    private static final String CRLF = new String(new char[]{13,10});
    private final NetworkPolicy.Snapshot network;
    private final RequestContext context;
    private final long deadline;
    private volatile Socket socket;
    private volatile boolean closed;
    boolean sent;
    int status;

    TraeHttpTransport(NetworkPolicy.Snapshot network,RequestContext context,long deadline) {
        this.network=network;this.context=context;this.deadline=deadline;
    }
    private int remaining() throws IOException {
        if(context!=null)context.check();
        long left=deadline-System.nanoTime();
        if(closed || left<=0 || Thread.currentThread().isInterrupted())throw new SocketTimeoutException("Trae request ended");
        return (int)Math.max(1,Math.min(20000,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)+1));
    }
    private synchronized void own(Socket value)throws IOException {
        if(closed){value.close();throw new IOException("Trae request closed");}
        socket=value;
    }
    private static void header(String name,String value)throws IOException {
        if(name==null || !name.matches("[A-Za-z0-9-]+") || value==null)throw new IOException("invalid request header");
        for(char c:value.toCharArray())if(c<32 || c==127)throw new IOException("invalid request header");
        if(name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding") || name.equalsIgnoreCase("Connection"))throw new IOException("reserved request header");
    }
    byte[] exchange(String target,String method,byte[] body,Map<String,String> headers,int maxBytes,Runnable beforeWrite)throws Exception {
        URI uri=new URI(target);String host=uri.getHost();
        boolean tls="https".equalsIgnoreCase(uri.getScheme());
        if(host==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || (!tls && !("http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(host))))throw new IOException("invalid Trae endpoint");
        if(!"POST".equals(method)&&!"GET".equals(method))throw new IOException("invalid Trae method");
        String path=uri.getRawPath();if(path==null || !path.startsWith("/"))throw new IOException("invalid Trae path");
        int port=uri.getPort()<0?(tls?443:80):uri.getPort();
        String authority=host+((tls&&port==443)||(!tls&&port==80)?"":":"+port);
        byte[] payload=body==null?new byte[0]:body;
        if(payload.length>maxBytes)throw new IOException("request body too large");
        StringBuilder head=new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
                .append("Host: ").append(authority).append(CRLF);
        boolean encoding=false;
        for(Map.Entry<String,String> entry:headers.entrySet()) {
            header(entry.getKey(),entry.getValue());
            if(entry.getKey().equalsIgnoreCase("Accept-Encoding"))encoding=true;
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }
        if(!encoding)head.append("Accept-Encoding: gzip").append(CRLF);
        head.append("Content-Length: ").append(payload.length).append(CRLF).append("Connection: close").append(CRLF).append(CRLF);
        byte[] requestHead=head.toString().getBytes(StandardCharsets.UTF_8);
        if(requestHead.length>131072)throw new IOException("request headers too large");

        Socket plain=network.connectTunnel(host,port,remaining(),remaining());own(plain);
        if(tls) {
            SSLSocket secure=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,host,port,true);
            own(secure);SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);
            if(!"HTTPS".equals(secure.getSSLParameters().getEndpointIdentificationAlgorithm()))throw new SSLException("hostname verification unavailable");
            secure.setSoTimeout(remaining());secure.startHandshake();
        }
        remaining();final Socket connected=socket;
        OutputStream out=connected.getOutputStream();
        // Before the first application write, failures are provably unsent. After this point,
        // failures are outcome-unknown unless a complete explicit rejection is received.
        sent=true;beforeWrite.run();out.write(requestHead);out.write(payload);out.flush();
        InputStream input=new FilterInputStream(connected.getInputStream()) {
            @Override public int read()throws IOException {connected.setSoTimeout(remaining());return in.read();}
            @Override public int read(byte[] b,int offset,int length)throws IOException {
                if(length==0)return 0;connected.setSoTimeout(remaining());
                return in.read(b,offset,Math.min(length,Math.max(1,in.available())));
            }
        };
        // Reuse the project's strict, bounded framing/gzip parser; all statuses retain bodies,
        // including 401, unlike fixed-length HttpURLConnection on some Java runtimes.
        QoderAuth.Head response=QoderAuth.readHead(input);status=response.status;
        if(status<100 || status>599)throw new IOException("invalid response status");
        byte[] bytes=QoderAuth.readBody(input,response,maxBytes);
        remaining();return bytes;
    }
    @Override public void close() {
        Socket active;
        synchronized(this){if(closed)return;closed=true;active=socket;}
        if(active!=null)try{active.close();}catch(IOException ignored){}
    }
}
