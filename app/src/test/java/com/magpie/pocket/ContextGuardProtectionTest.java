package com.magpie.pocket;
import org.json.*;
public final class ContextGuardProtectionTest {
    static void ok(boolean b) { if (!b) throw new AssertionError(); }
    static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); while(n-- > 0) b.append(s); return b.toString(); }
    public static void main(String[] args) {
        ContextGuard.Config c = new ContextGuard.Config(); c.maxBodyBytes = 0; c.maxContextTokens = 100; c.reserveTokens = 0;
        ContextGuard.Result invalid = ContextGuard.fit(new JSONObject("{\"messages\":[null]}"), c);
        ok("invalid_message".equals(invalid.error));
        ContextGuard.Result impossible = ContextGuard.fit(new JSONObject().put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", repeat("界", 300)))), c);
        ok(impossible.budgetTokens == 100 && impossible.budgetExceeded && "budget_exceeded".equals(impossible.error));
        c.maxContextTokens = 0; c.maxBodyBytes = 1200; c.keepRecent = 10;
        JSONObject uncut = new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "image_url").put("image_url", repeat("x", 900))));
        JSONObject text = new JSONObject().put("role", "user").put("content", repeat("文", 500));
        ContextGuard.Result shrink = ContextGuard.fit(new JSONObject().put("messages", new JSONArray().put(uncut).put(text)), c);
        ok(shrink.truncated > 0);
        c.maxBodyBytes = 500; c.keepRecent = 1;
        JSONArray pair = new JSONArray().put(new JSONObject().put("role", "user").put("content", repeat("x", 1500)))
          .put(new JSONObject("{\"role\":\"assistant\",\"content\":\"preserve\",\"tool_calls\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"))
          .put(new JSONObject("{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"one\"}"))
          .put(new JSONObject("{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"two\"}"));
        ContextGuard.Result paired = ContextGuard.fit(new JSONObject().put("messages", pair), c);
        ok(paired.payload.getJSONArray("messages").length() == 3);
        ok(paired.payload.getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").length() == 2);
        System.out.println("ContextGuard protection checks passed");
    }
}
