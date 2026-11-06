package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ClineRoutingPolicyOfflineTest {
    private static final String URL = "https://api.cline.bot/api/v1/chat/completions";
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static JSONObject apply(String json, String model, String url) throws Exception {
        JSONObject p = new JSONObject(json); ClineRoutingPolicy.apply(p, model, url); return p;
    }
    private static JSONArray caps(JSONObject p) { return p.getJSONObject("providerOptions").getJSONObject("gateway").getJSONArray("has"); }
    public static void main(String[] args) throws Exception {
        String model = "cline-pass/deepseek-v4.1-flash";
        JSONObject text = apply("{\"messages\":[{\"role\":\"user\",\"content\":\"image_url\"}]}", model, URL);
        check(text.getJSONObject("providerOptions").getJSONObject("gateway").getJSONArray("only").getString(0).equals("deepseek"), "only");
        check(caps(text).length() == 1 && caps(text).getString(0).equals("implicit-caching"), "text");
        for (String content : new String[] {
            "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.org/a\"}}]",
            "[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AA==\"}]",
            "[{\"type\":\"tool_result\",\"content\":[{\"type\":\"image_url\",\"image_url\":\"https://example.org/a\"}]}]" }) {
            JSONObject p = apply("{\"messages\":[{\"role\":\"tool\",\"content\":" + content + "}]}", model, URL);
            check(caps(p).length() == 2 && caps(p).getString(0).equals("vision") && caps(p).getString(1).equals("implicit-caching"), "image");
        }
        for (String routing : new String[] {"\"providerOptions\":null", "\"provider\":null", "\"providerOptions\":false", "\"provider\":{\"off\":false}", "\"providerOptions\":{\"gateway\":{\"only\":[\"raw\"],\"off\":false}}"}) {
            JSONObject p = new JSONObject("{" + routing + ",\"extra\":false}"); String before = p.toString();
            ClineRoutingPolicy.apply(p, model, URL); check(before.equals(p.toString()), "user routing " + routing);
        }
        for (String url : new String[] {"http://api.cline.bot/api/v1/chat/completions", "https://api.cline.bot:444/api/v1/chat/completions", "https://api.cline.bot.evil/api/v1/chat/completions", "https://api.cline.bot/api/v1/chat/completions?x=1", "https://api.cline.bot/api/v1/chat/completions#x", "https://evil@api.cline.bot/api/v1/chat/completions", "https://api.cline.bot/api/v1/chat/completions/", "https://api.cline.bot:/api/v1/chat/completions"})
            check(!apply("{}", model, url).has("providerOptions"), "url " + url);
        check(apply("{}", model, "https://API.CLINE.BOT:443/api/v1/chat/completions").has("providerOptions"), "default port");
        for (String other : new String[] {"deepseek/deepseek-v4", "xcline-pass/deepseek-v4", "cline-pass/other", "cline-pass/deepsee"})
            check(!apply("{}", other, URL).has("providerOptions"), "model " + other);
        check(apply("{}", "CLINE-PASS/DEEPSEEK-v4", URL).has("providerOptions"), "case");
        check(apply("{}", "cline-pass/deepseekish", URL).has("providerOptions"), "upstream prefix semantics");
        System.out.println("ClineRoutingPolicyOfflineTest passed");
    }
}
