package com.gaeul.allin;

import org.json.JSONObject;


import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class StandardModelCatalogHttpTest {
    static void assertTrue(boolean v) { if(!v)throw new AssertionError(); }
    static void assertFalse(boolean v) { assertTrue(!v); }
    static void assertEquals(Object a,Object b) { if(!java.util.Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final Thread thread;
        volatile Throwable failure;
        Server(String... replies) throws Exception {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(3000);
            thread = new Thread(() -> {
                try {
                    for (String reply : replies) {
                        try (Socket client = socket.accept()) {
                            client.setSoTimeout(3000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                            StringBuilder request = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                            requests.add(request.toString());
                            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                            client.getOutputStream().write(bytes);
                            client.getOutputStream().flush();
                        }
                    }
                } catch (Throwable t) { if (!socket.isClosed()) failure = t; }
            });
            thread.start();
        }
        String base() { return "http://127.0.0.1:" + socket.getLocalPort() + "/v1?existing=yes"; }
        public void close() throws Exception { socket.close(); thread.join(4000); if (failure != null) throw new AssertionError(failure); }
    }
    private static String response(int code, String body, String headers) {
        return "HTTP/1.1 " + code + " Test\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n" + headers + "\r\n" + body;
    }
    private static String ok(String body) { return response(200, body, ""); }
    private static List<String> fetch(Server server, JSONObject meta) {
        return StandardModelCatalog.fetch("anthropic", server.base(), "secret", meta,
                url -> (HttpURLConnection) new URL(url).openConnection());
    }
     public void twoPagesDedupeAndEncodeCursor() throws Exception {
        try (Server server = new Server(ok("{\"data\":[{\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a +/\"}"),
                ok("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"has_more\":false}"))) {
            assertEquals(Arrays.asList("a", "b"), fetch(server, new JSONObject()));
            assertTrue(server.requests.get(1).startsWith("GET /v1/models?existing=yes&after_id=a+%2B%2F "));
            assertTrue(server.requests.get(0).toLowerCase(Locale.ROOT).contains("x-api-key: secret"));
            assertTrue(server.requests.get(0).toLowerCase(Locale.ROOT).contains("anthropic-version: 2023-06-01"));
        }
    }
     public void secondPageFailureKeepsMeta() throws Exception {
        try (Server server = new Server(ok("{\"data\":[\"a\"],\"has_more\":true,\"last_id\":\"a\"}"), response(500, "", ""))) {
            JSONObject meta = new JSONObject().put("existing", "value");
            assertTrue(fetch(server, meta).isEmpty());
            assertEquals("value", meta.getString("existing"));
            assertEquals(1, meta.length());
        }
    }
     public void rejectsCursorLoopAndBadSchema() throws Exception {
        String page = ok("{\"data\":[\"a\"],\"has_more\":true,\"last_id\":\"a\"}");
        try (Server server = new Server(page, page)) { assertTrue(fetch(server, new JSONObject()).isEmpty()); }
        try (Server server = new Server(ok("{\"data\":[{\"id\":123}],\"has_more\":false}"))) {
            assertTrue(fetch(server, new JSONObject()).isEmpty());
        }
    }
     public void doesNotFollowRedirect() throws Exception {
        try (Server target = new Server(); Server server = new Server(response(302, "", "Location: " + target.base() + "\r\n"))) {
            assertTrue(fetch(server, new JSONObject()).isEmpty());
            assertTrue(target.requests.isEmpty());
        }
    }
    public static void main(String[] args) throws Exception {
        StandardModelCatalogHttpTest t = new StandardModelCatalogHttpTest();
        t.twoPagesDedupeAndEncodeCursor(); t.secondPageFailureKeepsMeta();
        t.rejectsCursorLoopAndBadSchema(); t.doesNotFollowRedirect();
        System.out.println("StandardModelCatalogHttpTest PASS");
    }
}
