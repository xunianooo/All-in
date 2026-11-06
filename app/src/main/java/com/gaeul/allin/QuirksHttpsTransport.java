package com.gaeul.allin;


import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Anonymous HTTP/1.1 over a policy tunnel; never uses URLConnection, cookies or authenticators. */
final class QuirksHttpsTransport implements Closeable {
    private volatile Socket socket; private volatile boolean closed;
    private final long deadline; private final int timeout;
    QuirksHttpsTransport(long deadline,int timeout){this.deadline=deadline;this.timeout=timeout;}
    private int remaining() throws IOException {
        long n=deadline-System.nanoTime(); if(closed || n<=0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException("deadline");
        return (int)Math.max(1,Math.min((long)timeout,TimeUnit.NANOSECONDS.toMillis(n)+1));
    }
    byte[] fetch(NetworkPolicy.Snapshot policy,String url) throws IOException {
        URI uri=URI.create(url); String host=uri.getHost();
        if(!"https".equals(uri.getScheme()) || host==null || uri.getPort()!=-1 || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawPath()==null || !uri.getRawPath().startsWith("/") || !uri.normalize().equals(uri) || !uri.toASCIIString().equals(url)) throw new IOException("invalid endpoint");
        Socket plain=policy.connectTunnel(host,443,remaining(),remaining());
        synchronized(this){if(closed){plain.close();throw new IOException("closed");}socket=plain;}
        SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,host,443,true);
        synchronized(this){if(closed){tls.close();throw new IOException("closed");}socket=tls;}
        SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);
        if(!"HTTPS".equals(tls.getSSLParameters().getEndpointIdentificationAlgorithm())) throw new SSLException("hostname verification unavailable");
        tls.setSoTimeout(remaining());tls.startHandshake();
        String authority=host.indexOf(':')>=0?"["+host+"]":host;
        String request="GET "+uri.getRawPath()+" HTTP/1.1\r\nHost: "+authority+"\r\nAccept: application/octet-stream\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
        tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
        return readResponse(new DeadlineInput(tls.getInputStream(),tls),SignedQuirksStore.maxEnvelope());
    }
    private final class DeadlineInput extends FilterInputStream {
        final Socket s; DeadlineInput(InputStream in,Socket s){super(in);this.s=s;}
        public int read() throws IOException{s.setSoTimeout(remaining());return in.read();}
        public int read(byte[] b,int o,int l)throws IOException{s.setSoTimeout(remaining());return in.read(b,o,l);}
    }
    static byte[] readResponse(InputStream in,int max) throws IOException {
        int[] budget={16384};String status=line(in,budget,1024);
        if(!status.matches("HTTP/1\\.[01] 200(?: [\\x20-\\x7e]*)?")) throw new IOException("HTTP status");
        Map<String,String> headers=new HashMap<>();int count=0;
        while(true){String line=line(in,budget,4096);if(line.isEmpty())break;if(++count>64)throw new IOException("headers");
            int colon=line.indexOf(':');if(colon<1)throw new IOException("header");String key=line.substring(0,colon).toLowerCase(Locale.ROOT);
            if(!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || headers.put(key,line.substring(colon+1).trim())!=null)throw new IOException("duplicate header");
        }
        if(!"application/octet-stream".equalsIgnoreCase(headers.get("content-type")))throw new IOException("content type");
        String encoding=headers.get("content-encoding");if(encoding!=null && !"identity".equalsIgnoreCase(encoding))throw new IOException("encoding");
        String length=headers.get("content-length"), transfer=headers.get("transfer-encoding");
        if(length!=null && transfer!=null)throw new IOException("ambiguous framing");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(transfer!=null){
            if(!"chunked".equalsIgnoreCase(transfer))throw new IOException("transfer encoding");
            while(true){String size=line(in,budget,32);if(!size.matches("[0-9a-fA-F]{1,8}"))throw new IOException("chunk size");long n=Long.parseLong(size,16);if(n>max-out.size())throw new IOException("size");
                if(n==0){if(!line(in,budget,1024).isEmpty())throw new IOException("trailers unsupported");break;}
                copy(in,out,(int)n);if(in.read()!='\r'||in.read()!='\n')throw new IOException("chunk terminator");
            }
        } else if(length!=null){
            if(!length.matches("0|[1-9][0-9]{0,9}"))throw new IOException("length");long n=Long.parseLong(length);if(n>max)throw new IOException("size");copy(in,out,(int)n);
        } else {byte[] buf=new byte[2048];int n;while((n=in.read(buf))!=-1){if(n==0)continue;if(out.size()>max-n)throw new IOException("size");out.write(buf,0,n);}}
        return out.toByteArray();
    }
    private static void copy(InputStream in,OutputStream out,int n)throws IOException{byte[] b=new byte[2048];while(n>0){int r=in.read(b,0,Math.min(n,b.length));if(r<0)throw new EOFException();if(r==0)continue;out.write(b,0,r);n-=r;}}
    private static String line(InputStream in,int[] budget,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int prev=-1;
        while(true){int b=in.read();if(b<0 || --budget[0]<0)throw new IOException("truncated headers");if(b=='\n'){if(prev!='\r')throw new IOException("line ending");byte[] v=out.toByteArray();return new String(v,0,v.length-1,StandardCharsets.US_ASCII);}
            if(b>126 || (b<32 && b!='\r' && b!='\t') || prev=='\r' || out.size()>=max)throw new IOException("header syntax");out.write(b);prev=b;}
    }
    public void close(){Socket s; synchronized(this){closed=true;s=socket;}if(s!=null)try{s.close();}catch(IOException ignored){}}
}
