package com.gaeul.allin;

import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

/** Process policy. Loopback literals always bypass proxies to protect the local control plane. */
public final class NetworkPolicy {
    private static volatile Snapshot current = new Snapshot("default", null, 0);
    private static final ThreadLocal<Snapshot> scoped = new ThreadLocal<>();
    private NetworkPolicy() {}
    public static Snapshot current() { return current; }
    public static Snapshot snapshot() { return current; }
    /** Capture the logical request's policy, if a caller installed a scope. */
    public static Snapshot capture() { Snapshot s = scoped.get(); return s == null ? current : s; }
    public static Snapshot currentScope() { return scoped.get(); }
    public static Scope use(Snapshot s) { if (s == null) throw new IllegalArgumentException("null snapshot"); return new Scope(s); }
    public static final class Scope implements AutoCloseable {
        private final Snapshot previous;
        private boolean closed;
        private Scope(Snapshot s) { previous = scoped.get(); scoped.set(s); }
        public void close() { if (!closed) { closed = true; if (previous == null) scoped.remove(); else scoped.set(previous); } }
    }
    /** Invalid persisted policy fails closed until a valid configuration is published. */
    public static void block() { current = new Snapshot("blocked", null, 0); }
    public static Snapshot configure(JSONObject network) { Snapshot s = parse(network); current = s; return s; }
    public static Snapshot parse(JSONObject network) {
        if (network == null) return new Snapshot("default", null, 0);
        Object m = network.opt("mode");
        if (!(m instanceof String)) throw new IllegalArgumentException("mode required");
        String mode = (String)m;
        if (!mode.equals("default") && !mode.equals("direct") && !mode.equals("http_proxy")) throw new IllegalArgumentException("invalid mode");
        Iterator<String> keys = network.keys();
        while (keys.hasNext()) { String k = keys.next(); if (!k.equals("mode") && !(mode.equals("http_proxy") && (k.equals("host") || k.equals("port")))) throw new IllegalArgumentException("unknown field"); }
        if (!mode.equals("http_proxy")) return new Snapshot(mode, null, 0);
        Object h = network.opt("host"), p = network.opt("port");
        if (!(h instanceof String) || !(p instanceof Number)) throw new IllegalArgumentException("host and integer port required");
        String host = validHost((String)h);
        Number num = (Number)p;
        double d = num.doubleValue();
        if (d < 1 || d > 65535 || d != Math.floor(d)) throw new IllegalArgumentException("invalid port");
        // JSON fractional notation is not an integer, even when numerically integral.
        if (!(p instanceof Byte || p instanceof Short || p instanceof Integer || p instanceof Long || p instanceof java.math.BigInteger)) throw new IllegalArgumentException("port must be integer");
        return new Snapshot(mode, host, num.intValue());
    }
    private static String validHost(String host) {
        if (host == null || host.isEmpty() || !host.equals(host.trim())) throw new IllegalArgumentException("invalid host");
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length()-1);
        if (h.isEmpty() || h.length() > 253) throw new IllegalArgumentException("invalid host");
        for (int i=0;i<h.length();i++) { char c=h.charAt(i); if (c <= 32 || c >= 127 || "/\\@?#[]%".indexOf(c)>=0) throw new IllegalArgumentException("invalid host"); }
        if (h.indexOf(':') >= 0) {
            try { if (!(InetAddress.getByName(h) instanceof Inet6Address)) throw new IllegalArgumentException("invalid IPv6"); }
            catch (UnknownHostException e) { throw new IllegalArgumentException("invalid IPv6"); }
        } else if (!h.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("invalid host");
        return h;
    }
    private static boolean loopback(String host) {
        if (host == null) return false;
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1,h.length()-1);
        if (h.equalsIgnoreCase("localhost")) return true;
        if (h.indexOf(':') >= 0) { try { return InetAddress.getByName(h).isLoopbackAddress(); } catch (Exception e) { return false; } }
        // Dotted decimal only: never resolve a hostname, including names that resolve to loopback.
        if (!h.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] parts = h.split("\\.");
        try { for (String part : parts) if (part.length() > 3 || Integer.parseInt(part) > 255) return false; return Integer.parseInt(parts[0]) == 127; } catch (NumberFormatException e) { return false; }
    }
    public static HttpURLConnection openHttp(String url) throws IOException { return capture().openHttp(url); }
    public static final class Snapshot {
        public final String mode;
        public final String host;
        public final int port;
        private Snapshot(String mode, String host, int port) { this.mode=mode; this.host=host; this.port=port; }
        public JSONObject toJson() { JSONObject o = new JSONObject(); try { o.put("mode",mode); if (host != null) { o.put("host",host); o.put("port",port); } } catch (Exception e) { throw new IllegalStateException(e); } return o; }
        public HttpURLConnection openHttp(String value) throws IOException {
            URL url = new URL(value);
            if (!url.getProtocol().equalsIgnoreCase("http") && !url.getProtocol().equalsIgnoreCase("https")) throw new IOException("unsupported URL scheme");
            if (mode.equals("blocked") && !loopback(url.getHost())) throw new IOException("Outbound network configuration is invalid");
            URLConnection c;
            if (loopback(url.getHost()) || mode.equals("direct")) c=url.openConnection(Proxy.NO_PROXY);
            else if (mode.equals("http_proxy")) c=url.openConnection(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host,port)));
            else c=url.openConnection();
            return (HttpURLConnection)c;
        }
        private Proxy select(String target, int targetPort) throws IOException {
            if (loopback(target) || mode.equals("direct")) return Proxy.NO_PROXY;
            if (mode.equals("blocked")) throw new IOException("Outbound network configuration is invalid");
            if (mode.equals("http_proxy")) return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host,port));
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return Proxy.NO_PROXY;
            try {
                URI uri = new URI("https", null, target, targetPort, null, null, null);
                List<Proxy> proxies = selector.select(uri);
                if (proxies == null || proxies.isEmpty() || proxies.get(0) == null) throw new IOException("proxy selection failed");
                Proxy p=proxies.get(0);
                if (p.type() != Proxy.Type.DIRECT && p.type() != Proxy.Type.HTTP) throw new IOException("unsupported system proxy type");
                return p;
            } catch (URISyntaxException | IllegalArgumentException e) { throw new IOException("proxy selection failed", e); }
        }
        /** Address-family diagnostics are valid only when this target selects DIRECT. */
        public boolean isDirectFor(String target, int port) throws IOException { return select(validHost(target),port).type() == Proxy.Type.DIRECT; }
        public Socket connectTunnel(String target, int targetPort, int connectMs, int readMs) throws IOException {
            target=validHost(target);
            if (targetPort < 1 || targetPort > 65535 || connectMs <= 0 || readMs <= 0) throw new IllegalArgumentException("invalid connection parameters");
            Proxy proxy=select(target,targetPort);
            Socket s=new Socket(Proxy.NO_PROXY);
            try {
                if (proxy.type() == Proxy.Type.DIRECT) s.connect(new InetSocketAddress(target,targetPort),connectMs);
                else {
                    if (!(proxy.address() instanceof InetSocketAddress)) throw new IOException("invalid proxy address");
                    InetSocketAddress a=(InetSocketAddress)proxy.address();
                    s.connect(new InetSocketAddress(a.getHostString(),a.getPort()),connectMs);
                }
                s.setSoTimeout(readMs);
                if (proxy.type() == Proxy.Type.HTTP) {
                    String authority=(target.indexOf(':')>=0 ? "["+target+"]" : target)+":"+targetPort;
                    OutputStream out=s.getOutputStream();
                    out.write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                    InputStream in=s.getInputStream();
                    byte[] header=new byte[32768]; int n=0, b;
                    // A proxy cannot extend this budget indefinitely by sending one byte at a time.
                    long deadline=System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readMs);
                    while (n<header.length) {
                        long remaining=deadline-System.nanoTime();
                        if (remaining <= 0) throw new SocketTimeoutException("proxy header deadline exceeded");
                        long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining);
                        s.setSoTimeout((int)Math.max(1, Math.min((long)readMs, millis + 1)));
                        b=in.read();
                        if (b == -1) break;
                        header[n++]=(byte)b;
                        if (n>=4 && header[n-4]=='\r' && header[n-3]=='\n' && header[n-2]=='\r' && header[n-1]=='\n') break;
                    }
                    s.setSoTimeout(readMs);
                    if (n<4 || header[n-4]!='\r' || header[n-3]!='\n' || header[n-2]!='\r' || header[n-1]!='\n') throw new IOException("incomplete or oversized proxy response");
                    int end=0; while (end+1<n && !(header[end]=='\r' && header[end+1]=='\n')) end++;
                    String status=new String(header,0,end,StandardCharsets.US_ASCII);
                    if (!status.matches("HTTP/1\\.[01] 200(?: [^\\r\\n]*)?")) throw new IOException("proxy CONNECT rejected");
                }
                return s;
            } catch (IOException | RuntimeException e) { try { s.close(); } catch (IOException ignored) {} throw e; }
        }
    }
}
