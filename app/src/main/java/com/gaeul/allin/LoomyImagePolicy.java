package com.gaeul.allin;

import java.util.Locale;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Loomy chat image compatibility. Pure transformation; callers decide whether to send. */
public final class LoomyImagePolicy {
    private LoomyImagePolicy() {}

    public static boolean usesTaggedImages(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.contains("deepseek") && !java.util.regex.Pattern.compile("v4[.-]1(?![0-9])", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(m).find();
    }

    /** Returns a deep copy; only data-URI image_url/input_image blocks for legacy DeepSeek change. */
    public static JSONObject prepare(JSONObject input, String model) throws Exception {
        if (input == null) throw new IllegalArgumentException("missing request");
        JSONObject out = new JSONObject(input.toString());
        if (!usesTaggedImages(model)) return out;
        JSONArray messages = out.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            JSONArray content = message == null ? null : message.optJSONArray("content");
            if (content == null) continue;
            for (int j = content.length() - 1; j >= 0; j--) {
                JSONObject part = content.optJSONObject(j);
                String type = part == null ? "" : part.optString("type", "").trim().toLowerCase(Locale.ROOT);
                if (!("image_url".equals(type) || "input_image".equals(type))) continue;
                Object image = part.opt("image_url");
                String uri = image instanceof String ? (String) image
                        : image instanceof JSONObject ? ((JSONObject) image).optString("url", "") : "";
                if (!uri.startsWith("data:")) continue;
                content.remove(j);
                String tag = "<image_base64>" + uri + "</image_base64>";
                JSONObject previous = j > 0 ? content.optJSONObject(j - 1) : null;
                if (previous != null && "text".equals(previous.optString("type"))) {
                    previous.put("text", previous.optString("text", "") + "\n" + tag);
                } else {
                    JSONObject next = content.optJSONObject(j);
                    if (next != null && "text".equals(next.optString("type"))) {
                        next.put("text", tag + "\n" + next.optString("text", ""));
                    } else {
                        // JSONArray.put(index, value) replaces; shift the remaining blocks to insert.
                        for (int k = content.length(); k > j; k--) content.put(k, content.opt(k - 1));
                        content.put(j, new JSONObject().put("type", "text").put("text", tag));
                    }
                }
            }
        }
        return out;
    }
}
