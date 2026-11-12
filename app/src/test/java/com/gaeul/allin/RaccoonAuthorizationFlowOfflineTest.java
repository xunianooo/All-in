package com.gaeul.allin;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Tests redirect, state and local listener behavior without contacting the vendor. */
public final class RaccoonAuthorizationFlowOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static String callback(RaccoonAuthorizationFlow flow, String query) {
        return "http://127.0.0.1:" + flow.portForTest() + RaccoonAuthorizationFlow.CALLBACK_PATH + "?" + query;
    }

    static void urlContractAndWebViewCallback() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<RaccoonAuthorizationFlow.Callback> result = new AtomicReference<>();
        RaccoonAuthorizationFlow flow = RaccoonAuthorizationFlow.startForTest(5000, callback -> {
            result.set(callback); called.countDown();
        });
        try {
            URI authorize = new URI(flow.authorizeUrl());
            check(authorize.getScheme().equals("https") && authorize.getHost().equals("xiaohuanxiong.com")
                    && authorize.getPath().equals("/code/authorize"), "official authorize origin/path");
            String query = authorize.getRawQuery();
            check(query.contains("login_source=desktop") && query.contains("appname=")
                    && query.contains("desktop_callback=loopback"), "reference authorize params");
            String state = flow.stateForTest();
            check(state.matches("[0-9a-f]{64}") && query.contains("state=" + state), "64 hex state");
            String redirect = "http://127.0.0.1:" + flow.portForTest() + "/auth/callback";
            check(query.contains("redirect=" + java.net.URLEncoder.encode(redirect, "UTF-8")), "dynamic loopback redirect");
            check(flow.acceptCallbackNavigation(callback(flow, "code=good&state=wrong")) == false, "wrong state rejected");
            check(flow.acceptCallbackNavigation(callback(flow, "code=good&state=" + state + "&state=duplicate")) == false,
                    "duplicate state rejected");
            check(flow.acceptCallbackNavigation(callback(flow, "error=access_denied&code=bad&state=" + state)) == false,
                    "mixed denial and code rejected");
            check(flow.acceptCallbackNavigation(callback(flow, "code_verifier=attacker&code=bad&state=" + state)) == false,
                    "unexpected verifier parameter rejected");
            check(flow.acceptCallbackNavigation(callback(flow, "code=good&state=" + state)), "matching WebView callback accepted");
            check(called.await(1, TimeUnit.SECONDS), "listener completed");
            check(result.get() != null && result.get().code.equals("good") && !result.get().denied, "validated code delivered");
            check(flow.acceptCallbackNavigation(callback(flow, "code=again&state=" + state)) == false, "callback one-shot");
        } finally { flow.close(); }
    }

    static void localHttpListenerAndDenial() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<RaccoonAuthorizationFlow.Callback> result = new AtomicReference<>();
        RaccoonAuthorizationFlow flow = RaccoonAuthorizationFlow.startForTest(5000, callback -> {
            result.set(callback); called.countDown();
        });
        try {
            String state = flow.stateForTest();
            String request = "GET /auth/callback?code=http-code&state=" + state + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + flow.portForTest() + "\r\nConnection: close\r\n\r\n";
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + flow.portForTest()
                    + "/auth/callback?code=http-code&state=" + state).openConnection();
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            check(connection.getResponseCode() == 200, "loopback listener callback accepted");
            connection.disconnect();
            check(called.await(1, TimeUnit.SECONDS), "loopback listener delivered callback");
            check(result.get().code.equals("http-code"), "listener code");
        } finally { flow.close(); }

        CountDownLatch deniedLatch = new CountDownLatch(1);
        AtomicReference<RaccoonAuthorizationFlow.Callback> denied = new AtomicReference<>();
        flow = RaccoonAuthorizationFlow.startForTest(5000, callback -> { denied.set(callback); deniedLatch.countDown(); });
        try {
            String state = flow.stateForTest();
            check(flow.acceptCallbackNavigation(callback(flow, "error=access_denied&state=" + state)), "denial callback accepted");
            check(deniedLatch.await(1, TimeUnit.SECONDS) && denied.get().denied, "authorization refusal reported");
        } finally { flow.close(); }
    }

    static void noRealTransportUsed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RaccoonOAuthClient client = new RaccoonOAuthClient((url, body) -> {
            calls.incrementAndGet();
            check(url.equals(RaccoonOAuthClient.AUTH_BASE + "/login_with_authorization_code"), "exact HTTPS exchange URL");
            check(body.length() == 1 && body.optString("authorization_code").equals("synthetic-code"), "exact exchange body");
            return new RaccoonOAuthClient.Response(200, new com.gaeul.allin.json.JSONObject().put("code", 0).put("data",
                    new com.gaeul.allin.json.JSONObject().put("access_token", "opaque-access").put("refresh_token", "fixture-refresh")));
        });
        RaccoonLoginProtocol.Credentials c = client.exchangeAuthorizationCode("synthetic-code");
        check(c.access.equals("opaque-access") && c.refresh.equals("fixture-refresh"), "fixture exchange result");
        check(c.expires == 0L, "opaque access has unknown expiry");
        check(calls.get() == 1, "only injected fixture transport called");
    }

    public static void main(String[] args) throws Exception {
        urlContractAndWebViewCallback();
        localHttpListenerAndDenial();
        noRealTransportUsed();
        System.out.println("RaccoonAuthorizationFlowOfflineTest passed");
    }
}
