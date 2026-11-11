package com.gaeul.allin;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

public final class MiniMaxModelMetadataOfflineTest {
    public static void main(String[] args) throws Exception {
        JSONObject catalog = new JSONObject("{\"providers\":[{\"providerId\":\"minimax\",\"config\":{\"model_order\":[\"MiniMax-M3.1-Flash\",\"MiniMax-M3\",\"MiniMax-M3\",\"unknown\"],\"models\":{\"MiniMax-M3\":{\"name\":\"M3 official\",\"thinking_config\":{\"mode\":\"switchable\"},\"limit\":{\"context\":524288,\"output\":8192}},\"MiniMax-M3.1-Flash\":{\"name\":\"Flash official\",\"thinking_config\":{\"mode\":\"forced_on\"},\"thinking\":{\"effortOptions\":[\"low\",\"max\",\"none\",\"default\"]},\"limit\":{\"context\":524288,\"output\":8192}},\"other\":{}}}}]}");
        JSONObject meta = new JSONObject();
        List<String> ids = MiniMaxAuth.parseModelCatalog(catalog, meta);
        if (!ids.subList(0, 2).equals(Arrays.asList("MiniMax-M3.1-Flash", "MiniMax-M3")) || ids.size() != 3) throw new AssertionError(ids);
        for (String id : ids.subList(0, 2)) {
            JSONObject m = meta.getJSONObject(id);
            if (m.getInt("contextWindow") != 1048576 || m.getJSONObject("limit").getInt("context") != 1048576 || m.has("multiplier") || m.has("price") || m.has("billing")) throw new AssertionError(m);
        }
        if (!"M3 official".equals(meta.getJSONObject("MiniMax-M3").getString("name")) || meta.getJSONObject("MiniMax-M3").getInt("maxOutput") != 524288) throw new AssertionError(meta);
        if (meta.getJSONObject("other").has("contextWindow") || meta.getJSONObject("MiniMax-M3.1-Flash").getInt("maxOutput") != 524288) throw new AssertionError(meta);
        if (!"switchable".equals(meta.getJSONObject("MiniMax-M3").getString("thinkingMode"))) throw new AssertionError(meta);
        JSONObject preview = meta.getJSONObject("MiniMax-M3.1-Flash");
        if (!"forced_on".equals(preview.getString("thinkingMode"))
                || !preview.getJSONArray("efforts").toString().equals("[\"low\",\"max\"]")) throw new AssertionError(preview);
        catalog.getJSONArray("providers").getJSONObject(0).getJSONObject("config").getJSONObject("models").getJSONObject("MiniMax-M3").getJSONObject("limit").put("context", 1);
        if (meta.getJSONObject("MiniMax-M3").getJSONObject("limit").getInt("context") != 1048576) throw new AssertionError("not copied");
        JSONObject unchanged = new JSONObject("{\"keep\":true}");
        try {
            MiniMaxAuth.parseModelCatalog(new JSONObject("{\"providers\":[{\"providerId\":\"minimax\",\"config\":{\"models\":{\"good\":{},\"bad\":{\"limit\":\"bad\"}}}}]}"), unchanged);
            throw new AssertionError("accepted bad schema");
        } catch (java.io.IOException expected) { }
        if (unchanged.length() != 1 || !unchanged.getBoolean("keep")) throw new AssertionError(unchanged);
        System.out.println("MiniMaxModelMetadataOfflineTest OK");
    }
}
