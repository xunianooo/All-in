package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class GatewayAddressOfflineTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static JSONObject info(int port, String ip, String bind) throws Exception {
        return GatewayServer.gatewayAddressInfo(port, ip, bind);
    }
    static final class Fixture extends NanoHTTPD {
        final GatewayServer gateway;
        Fixture() throws Exception {
            super("127.0.0.1", 0);
            gateway = RoutingSetupOfflineTest.server(new JSONObject("{\"providers\":[],\"server\":{\"authKey\":\"fixture-key\"}}"));
        }
        void attach() throws Exception {
            Field socket = NanoHTTPD.class.getDeclaredField("myServerSocket");
            socket.setAccessible(true);
            socket.set(gateway, socket.get(this));
        }
        @Override public Response serve(IHTTPSession session) { return gateway.serve(session); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject address = info(12345, "192.168.1.22", null);
        check(address.getString("localBaseUrl").equals("http://127.0.0.1:12345/v1"), "actual port in local URL");
        check(address.getString("lanBaseUrl").equals("http://192.168.1.22:12345/v1"), "valid LAN IPv4");
        check(address.getString("listenAddress").equals("0.0.0.0"), "wildcard hostname");
        for (String invalid : new String[]{"", "0.0.0.0", "127.1.2.3", "169.254.1.1", "224.1.2.3", "255.255.255.255", "256.1.2.3", "192.168.01.2", "::1", "example.com"})
            check(info(12345, invalid, null).getString("lanBaseUrl").isEmpty(), "invalid IP: " + invalid);
        check(info(12345, "100.64.1.2", null).getString("lanBaseUrl").isEmpty(), "cellular CGNAT is not a LAN endpoint");
        check(info(12345, "8.8.8.8", null).getString("lanBaseUrl").isEmpty(), "public address is not a LAN endpoint");
        check(info(12345, "192.168.1.22", "192.168.1.22").getString("localBaseUrl").isEmpty(), "specific LAN bind does not advertise loopback");
        check(info(12345, "192.168.1.22", "127.0.0.1").getString("lanBaseUrl").isEmpty(), "loopback bind does not advertise LAN");
        for (int port : new int[]{-1, 0, 65536}) {
            JSONObject unavailable = info(port, "192.168.1.22", null);
            check(unavailable.getString("localBaseUrl").isEmpty() && unavailable.getString("lanBaseUrl").isEmpty(), "invalid port: " + port);
        }
        Fixture fixture = new Fixture();
        try {
            fixture.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            fixture.attach();
            int port = fixture.getListeningPort();
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/eta/import").openConnection();
            conn.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            check(conn.getResponseCode() == 200, "ETA import response");
            StringBuilder body = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = in.readLine()) != null) body.append(line);
            }
            JSONObject result = new JSONObject(body.toString());
            String expected = "http://127.0.0.1:" + port + "/v1";
            check(result.getString("baseUrl").equals(expected), "ETA import uses real listening port");
            check(result.getString("snippet").contains("Base URL: " + expected), "snippet agrees with baseUrl");
            conn.disconnect();
        } finally { fixture.stop(); }
        System.out.println("GatewayAddressOfflineTest OK");
    }
}
