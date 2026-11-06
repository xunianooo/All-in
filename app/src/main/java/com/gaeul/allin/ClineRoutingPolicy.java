package com.gaeul.allin;

import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/** Default request routing constraints for the official Cline endpoint. */
final class ClineRoutingPolicy {
    private ClineRoutingPolicy() {}

    static void apply(JSONObject payload, String model, String exactUrl) throws Exception {
        if (payload == null || payload.has("providerOptions") || payload.has("provider")) return;
        // Mirrors upstream /^cline-pass\/deepseek/i, including its prefix semantics.
        String prefix = "cline-pass/deepseek";
        if (model == null || !model.regionMatches(true, 0, prefix, 0, prefix.length()) || !officialEndpoint(exactUrl)) return;
        JSONArray capabilities = new JSONArray();
        if (hasImages(payload.optJSONArray("messages"))) capabilities.put("vision");
        capabilities.put("implicit-caching");
        JSONObject gateway = new JSONObject().put("only", new JSONArray().put("deepseek"))
                .put("has", capabilities);
        payload.put("providerOptions", new JSONObject().put("gateway", gateway));
    }

    private static boolean officialEndpoint(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url);
            return !u.isOpaque() && "https".equalsIgnoreCase(u.getScheme())
                    && "api.cline.bot".equalsIgnoreCase(u.getHost())
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
                    && u.getRawAuthority() != null && !u.getRawAuthority().endsWith(":")
                    && "/api/v1/chat/completions".equals(u.getRawPath());
        } catch (Exception invalid) { return false; }
    }

    private static boolean hasImages(JSONArray messages) {
        if (messages == null) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && imageParts(message.opt("content"))) return true;
        }
        return false;
    }

    private static boolean imageParts(Object content) {
        if (!(content instanceof JSONArray)) return false;
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type", "");
            if ("image_url".equals(type) || "input_image".equals(type)) return true;
            if ("tool_result".equals(type) && imageParts(part.opt("content"))) return true;
        }
        return false;
    }
}
