package com.gaeul.allin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;

/** Synthetic credentials and fake gift transport only; no provider network. */
public final class AccountSelectionGatewayOfflineTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static Object target(GatewayServer s, String model) throws Exception {
        Method m = GatewayServer.class.getDeclaredMethod("buildTarget", String.class);
        m.setAccessible(true); return m.invoke(s, model);
    }
    static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    static JSONObject account(String token, boolean enabled) {
        return new JSONObject().put("token", token).put("enabled", enabled);
    }
    static JSONObject config(JSONObject provider) {
        return new JSONObject().put("server", new JSONObject().put("authKey", "fixture"))
            .put("providers", new JSONArray().put(provider));
    }
    static void routeTests(String protocol) throws Exception {
        JSONObject first = account("disabled-token", false), second = account("enabled-token", true);
        JSONObject p = new JSONObject().put("id", protocol).put("protocol", protocol)
            .put("accounts", new JSONArray().put(first).put(second));
        GatewayServer s = RoutingSetupOfflineTest.server(config(p));
        Object route = target(s, protocol + "/synthetic-model");
        check(route != null && field(route, "account") == second && "enabled-token".equals(field(route, "apiKey")), protocol + " skips disabled account");
        second.put("enabled", false);
        check(target(s, protocol + "/synthetic-model") == null, protocol + " all accounts disabled");
        first.remove("enabled");
        check(target(s, protocol + "/synthetic-model") != null, protocol + " missing enabled defaults true");
        p.put("enabled", false);
        check(target(s, protocol + "/synthetic-model") == null, protocol + " disabled provider");
    }
    static final class GiftFake implements ZCodeGiftControl.Transport {
        int calls; String jwt;
        public JSONObject summary(String token, String device) { calls++; jwt=token; return new JSONObject().put("ok", true).put("claimable",new JSONArray()).put("buckets",new JSONArray()); }
        public JSONObject claim(String token, String device, String id) { calls++; jwt=token; return new JSONObject().put("ok", true); }
    }
    static JSONObject panel(GatewayServer s) throws Exception {
        NanoHTTPD.Response r = s.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/api/zcode/gift", "GET", "", null));
        return new JSONObject(new String(r.getData().readAllBytes(), "UTF-8"));
    }
    static void giftTests() throws Exception {
        JSONObject first = new JSONObject().put("jwt", "disabled-jwt").put("enabled", false);
        JSONObject second = new JSONObject().put("jwt", "enabled-jwt").put("enabled", true);
        JSONObject p = new JSONObject().put("id", "zcode").put("accounts", new JSONArray().put(first).put(second));
        GatewayServer s = RoutingSetupOfflineTest.server(config(p));
        GiftFake fake = new GiftFake();
        ZCodeGiftControl.setTestHooks(() -> 1000L, fake);
        try {
            JSONObject result = panel(s);
            check(result.optBoolean("ok") && fake.calls == 1 && "enabled-jwt".equals(fake.jwt), "gift panel selects enabled JWT");
            second.put("enabled", false);
            result = panel(s);
            check(!result.optBoolean("ok") && fake.calls == 1, "all disabled means no gift transport");
            first.remove("enabled");
            result = panel(s);
            check(result.optBoolean("ok") && fake.calls == 2 && "disabled-jwt".equals(fake.jwt), "legacy enabled default");
        } finally { ZCodeGiftControl.setTestHooks(null, null); }
    }
    public static void main(String[] args) throws Exception {
        for (String p : new String[]{"qoder", "catpaw", "antigravity"}) routeTests(p);
        giftTests();
        System.out.println("AccountSelectionGatewayOfflineTest PASS");
    }
}
