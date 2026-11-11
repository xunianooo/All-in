package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GatewayMissingProviderOfflineTest {
    private static void set(GatewayServer gateway, String name, Object value) throws Exception {
        Field field = GatewayServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(gateway, value);
    }

    private static GatewayServer gateway(boolean fallback) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        GatewayServer gateway = (GatewayServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(GatewayServer.class);
        set(gateway, "totalRequests", new AtomicInteger());
        set(gateway, "totalTokens", new AtomicLong());
        set(gateway, "totalMasked", new AtomicInteger());
        set(gateway, "totalContextDropped", new AtomicInteger());
        set(gateway, "wbRoundRobin", new AtomicInteger());
        JSONObject provider = new JSONObject().put("id", "default").put("protocol", "openai")
                .put("baseUrl", "http://127.0.0.1:1/v1")
                .put("models", new JSONArray().put("configured-model"))
                .put("keys", new JSONArray());
        set(gateway, "configJson", new JSONObject()
                .put("setup",new JSONObject().put("done",true))
                .put("routing",new JSONObject().put("bareModelFallback",fallback))
                .put("server", new JSONObject().put("authKey", "secret"))
                .put("providers", new JSONArray().put(provider))
                .put("privacy", new JSONObject().put("enabled", false)));
        return gateway;
    }

    private static NanoHTTPD.IHTTPSession session(String uri, String model) throws Exception {
        String body = new JSONObject().put("model", model).put("max_tokens", 32)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"))).toString();
        if ("/v1/responses".equals(uri)) body = new JSONObject().put("model", model).put("input", "hello").put("max_output_tokens",32).toString();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(bytes);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("content-length", String.valueOf(bytes.length));
        headers.put("content-type", "application/json");
        headers.put("host", "127.0.0.1:3425");
        headers.put("authorization", "Bearer secret");
        return (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(GatewayMissingProviderOfflineTest.class.getClassLoader(),
                new Class<?>[]{NanoHTTPD.IHTTPSession.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUri": return uri;
                        case "getMethod": return NanoHTTPD.Method.POST;
                        case "getHeaders": return headers;
                        case "getInputStream": return input;
                        case "getRemoteIpAddress": return "192.0.2.1";
                        case "getParameters":
                        case "getParms": return Collections.emptyMap();
                        default: return null;
                    }
                });
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (boolean fallback : new boolean[]{false,true}) {
        for (String uri : new String[]{"/v1/chat/completions", "/v1/messages", "/v1/responses"}) {
            for (String model : new String[]{"unknown-bare-model", "unknown-provider/model"}) {
                NanoHTTPD.Response response = gateway(fallback).serve(session(uri, model));
                int status = response.getStatus().getRequestStatus();
                String body = read(response.getData());
                System.out.println(uri + " " + model + " => " + status + " " + body);
                JSONObject error = new JSONObject(body).optJSONObject("error");
                int expected = model.indexOf('/') < 0 ? 400 : 404;
                if (status != expected || error == null || !"gateway_error".equals(error.optString("type"))
                        || error.optString("message").trim().isEmpty() || !error.optString("message").contains(model.indexOf('/') < 0 ? model : "unknown-provider")) {
                    failures++;
                }
            }
        }
        }
        if (failures != 0) throw new AssertionError(failures + " missing-provider endpoint cases failed");
        System.out.println("GatewayMissingProviderOfflineTest OK");
    }
}
