package com.gaeul.allin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browser authorization-code flow with a loopback callback and strict per-login state validation. */
public final class RaccoonAuthorizationFlow implements AutoCloseable {
    public static final String AUTHORIZE_URL = "https://xiaohuanxiong.com/code/authorize";
    public static final String CALLBACK_PATH = "/auth/callback";
    public static final long DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_REQUEST_LINE = 8192;
    private static final int MAX_HEADER_BYTES = 16384;

    public static final class Callback {
        public final String code;
        public final boolean denied;
        public final boolean timedOut;
        private Callback(String code, boolean denied, boolean timedOut) {
            this.code = code; this.denied = denied; this.timedOut = timedOut;
        }
    }

    public interface Listener { void onCallback(Callback callback); }

    private final ServerSocket server;
    private final String state;
    private final long deadlineNanos;
    private final Listener listener;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Thread acceptThread;

    private RaccoonAuthorizationFlow(long timeoutMillis, Listener listener) throws IOException {
        if (timeoutMillis <= 0 || timeoutMillis > DEFAULT_TIMEOUT_MILLIS || listener == null)
            throw new IllegalArgumentException("invalid Raccoon authorization flow arguments");
        this.listener = listener;
        this.state = randomState();
        this.server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        this.server.setSoTimeout(1000);
        this.deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.acceptThread = new Thread(this::acceptLoop, "raccoon-oauth-callback");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public static RaccoonAuthorizationFlow start(Listener listener) throws IOException {
        return new RaccoonAuthorizationFlow(DEFAULT_TIMEOUT_MILLIS, listener);
    }

    static RaccoonAuthorizationFlow startForTest(long timeoutMillis, Listener listener) throws IOException {
        return new RaccoonAuthorizationFlow(timeoutMillis, listener);
    }

    public String authorizeUrl() {
        int port = server.getLocalPort();
        String redirect = "http://127.0.0.1:" + port + CALLBACK_PATH;
        return AUTHORIZE_URL + "?login_source=desktop&appname=" + encode("办公小浣熊客户端")
                + "&state=" + state + "&desktop_callback=loopback&redirect=" + encode(redirect);
    }

    public String stateForTest() { return state; }
    public int portForTest() { return server.getLocalPort(); }

    /** True only for the exact loopback callback endpoint belonging to this pending flow. */
    public boolean ownsCallbackUrl(String value) {
        try {
            URI uri = new URI(value);
            return "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == server.getLocalPort() && uri.getRawUserInfo() == null
                    && CALLBACK_PATH.equals(uri.getPath()) && uri.getRawFragment() == null;
        } catch (Exception ignored) { return false; }
    }

    /** Handle a WebView navigation to the registered redirect without allowing it to load. */
    public boolean acceptCallbackNavigation(String value) {
        if (!ownsCallbackUrl(value) || finished.get()) return false;
        try {
            URI uri = new URI(value);
            Callback callback = validateQuery(uri.getRawQuery());
            if (callback == null) return false;
            complete(callback);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void acceptLoop() {
        while (!finished.get() && System.nanoTime() < deadlineNanos) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(3000);
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    reply(socket, 403, "Forbidden");
                    continue;
                }
                Request request = readRequest(socket);
                if (request == null || !validHost(request.host) || !"GET".equals(request.method)) {
                    reply(socket, 400, "Invalid callback");
                    continue;
                }
                URI uri = new URI(request.target);
                if (!uri.getPath().equals(CALLBACK_PATH)) {
                    reply(socket, 404, "Not found");
                    continue;
                }
                Callback callback = validateQuery(uri.getRawQuery());
                if (callback == null) {
                    reply(socket, 400, "Callback validation failed");
                    continue;
                }
                reply(socket, 200, "Authorization received. Return to All In.");
                complete(callback);
            } catch (SocketTimeoutException ignored) {
                // Poll the bounded flow deadline and cancellation flag.
            } catch (Exception ignored) {
                if (socket != null) try { reply(socket, 400, "Invalid callback"); } catch (Exception ignoredAgain) { }
            } finally {
                if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            }
        }
        if (!finished.get()) complete(new Callback("", false, true));
    }

    private boolean validHost(String host) {
        return host != null && host.equals("127.0.0.1:" + server.getLocalPort());
    }

    private Callback validateQuery(String query) {
        if (System.nanoTime() >= deadlineNanos || finished.get()) return null;
        Map<String, List<String>> values = parseQuery(query);
        if (values == null || values.get("state") == null || values.get("state").size() != 1
                || !constantTimeEquals(state, values.get("state").get(0))) return null;
        List<String> errors = values.get("error");
        if (errors != null) {
            if (values.containsKey("code") || errors.size() != 1 || errors.get(0).isEmpty()) return null;
            return new Callback("", true, false);
        }
        if (values.containsKey("code_verifier") || values.containsKey("redirect_uri")) return null;
        List<String> codes = values.get("code");
        if (codes == null || codes.size() != 1) return null;
        String code = codes.get(0);
        if (code.isEmpty() || code.length() > 4096) return null;
        return new Callback(code, false, false);
    }

    private static Map<String, List<String>> parseQuery(String query) {
        if (query == null || query.length() > 12288) return null;
        Map<String, List<String>> values = new HashMap<>();
        try {
            if (query.isEmpty()) return values;
            for (String pair : query.split("&", -1)) {
                int equals = pair.indexOf('=');
                String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), "UTF-8");
                String value = URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), "UTF-8");
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
            return values;
        } catch (Exception ignored) { return null; }
    }

    private Request readRequest(Socket socket) throws IOException {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        int[] budget = new int[]{0};
        String first = readLine(in, budget, MAX_REQUEST_LINE);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ", -1);
        if (parts.length != 3 || !parts[2].matches("HTTP/1\\.[01]") || !parts[1].startsWith("/")) return null;
        String host = null;
        while (true) {
            String header = readLine(in, budget, MAX_HEADER_BYTES);
            if (header == null) return null;
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon <= 0) return null;
            String key = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if ("host".equalsIgnoreCase(key)) {
                if (host != null) return null;
                host = value;
            }
        }
        return new Request(parts[0], parts[1], host);
    }

    private static String readLine(BufferedInputStream in, int[] budget, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean carriage = false;
        while (budget[0] < max) {
            int b = in.read();
            if (b < 0) return null;
            budget[0]++;
            if (carriage) {
                if (b == '\n') return new String(out.toByteArray(), StandardCharsets.US_ASCII);
                out.write('\r');
                carriage = false;
            }
            if (b == '\r') carriage = true;
            else out.write(b);
        }
        throw new IOException("callback headers too large");
    }

    private void reply(Socket socket, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Bad Request";
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Pragma: no-cache\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void complete(Callback callback) {
        if (!finished.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) { }
        listener.onCallback(callback);
    }

    @Override public void close() {
        if (finished.compareAndSet(false, true)) {
            try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) { out.append(hex[(b >>> 4) & 15]); out.append(hex[b & 15]); }
        return out.toString();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Request {
        final String method, target, host;
        Request(String method, String target, String host) { this.method = method; this.target = target; this.host = host; }
    }
}
