package com.gaeul.allin;

import org.json.JSONObject;

public final class ClineRoutingPolicyOfflineTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        String[] models = {"cline-pass/deepseek-v4.1-flash", "cline-pass/deepseekish", "deepseek/deepseek-v4", "other/model"};
        String[] urls = {
                "https://api.cline.bot/api/v1/chat/completions",
                "https://api.cline.bot/api/v1/chat/completions?x=1",
                "https://example.org/api/v1/chat/completions"
        };
        String[] bodies = {
                "{}",
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AA==\"}}]}]}",
                "{\"providerOptions\":{\"gateway\":{\"only\":[\"caller-choice\"]}}}",
                "{\"provider\":{\"only\":[\"caller-choice\"]}}"
        };
        for (String model : models) for (String url : urls) for (String body : bodies) {
            JSONObject payload = new JSONObject(body);
            String before = payload.toString();
            ClineRoutingPolicy.apply(payload, model, url);
            check(before.equals(payload.toString()), "Cline routing must not inject providerOptions: " + model + " " + url);
        }
        System.out.println("ClineRoutingPolicyOfflineTest passed");
    }
}
