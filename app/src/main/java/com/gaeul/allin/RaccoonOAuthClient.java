package com.gaeul.allin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** HTTPS authorization-code exchange with bounded IO and no redirect credential forwarding. */
public final class RaccoonOAuthClient {
    public static final String AUTH_BASE = "https://xiaohuanxiong.com/api/web/auth/v1";
    public static final int MAX_RESPONSE_BYTES = 128 * 1024;
    public static final int TIMEOUT_MILLIS = 20000;

    public interface Transport {
        Response postJson(String url, JSONObject body) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final JSONObject body;
        public Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }

    private final Transport transport;

    public RaccoonOAuthClient(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("transport required");
        this.transport = transport;
    }

    public RaccoonLoginProtocol.Credentials exchangeAuthorizationCode(String code) throws IOException {
        JSONObject request;
        try { request = RaccoonLoginProtocol.exchangeBody(code); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid Raccoon authorization code", invalid); }
        Response response = transport.postJson(AUTH_BASE + "/login_with_authorization_code", request);
        if (response == null) throw new IOException("Raccoon authorization exchange failed");
        return RaccoonLoginProtocol.parseExchange(response.status, response.body);
    }

    public static RaccoonOAuthClient production() {
        return new RaccoonOAuthClient(RaccoonOAuthClient::postJson);
    }

    private static Response postJson(String url, JSONObject body) throws IOException {
        if (!(AUTH_BASE + "/login_with_authorization_code").equals(url))
            throw new IOException("Unexpected Raccoon authorization endpoint");
        HttpURLConnection connection = NetworkPolicy.openHttp(url);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Cache-Control", "no-store");
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String text = input == null ? "" : readBounded(input, MAX_RESPONSE_BYTES);
            JSONObject payload;
            try { payload = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
            catch (Exception invalidJson) { payload = new JSONObject(); }
            return new Response(status, payload);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count, total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("Raccoon authorization response too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
