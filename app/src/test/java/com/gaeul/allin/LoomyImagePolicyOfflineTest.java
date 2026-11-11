package com.gaeul.allin;

import org.json.JSONObject;

public final class LoomyImagePolicyOfflineTest {
    static void ok(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject input = new JSONObject("{\"model\":\"deepseek-v4-flash-0731\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,QUJD\"}},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.test/image.png\"}}]}]}");
        String original = input.toString();
        JSONObject output = LoomyImagePolicy.prepare(input, "deepseek-v4-flash-0731");
        String content = output.getJSONArray("messages").getJSONObject(0).getJSONArray("content").toString();
        ok(original.equals(input.toString()));
        ok(output.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).optString("text").equals("look\n<image_base64>data:image/png;base64,QUJD</image_base64>"));
        ok(content.contains("https://example.test/image.png") && !content.contains("<image_base64>https://"));
        JSONObject inputImage = new JSONObject("{\"messages\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/jpeg;base64,AA==\"}]}]}");
        ok(LoomyImagePolicy.prepare(inputImage, "deepseek-chat").toString().contains("<image_base64>"));
        for (String model : new String[]{"DeepSeek-V4.1-Flash", "deepseek-v4.1-flash", "other-model"}) {
            JSONObject unchanged = LoomyImagePolicy.prepare(inputImage, model);
            ok(!unchanged.toString().contains("<image_base64>") && unchanged.toString().contains("input_image"));
        }
        multiImageOrderSurvives();
        System.out.println("LoomyImagePolicyOfflineTest passed");
    }

    private static void multiImageOrderSurvives() throws Exception {
        // First image has no text neighbour: indexed replacement used to erase the remote image.
        org.json.JSONArray blocks = new org.json.JSONArray()
                .put(new JSONObject().put("type", "image_url").put("image_url", "data:image/png;base64,ONE="))
                .put(new JSONObject().put("type", "image_url").put("image_url", "https://example.test/remote.png"))
                .put(new JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,TWO="))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/png;base64,THREE=")))
                .put(new JSONObject().put("type", "text").put("text", "tail"))
                .put(new JSONObject().put("type", "opaque").put("value", "keep"));
        JSONObject input = new JSONObject().put("messages", new org.json.JSONArray().put(new JSONObject().put("content", blocks)));
        String original = input.toString();
        org.json.JSONArray actual = LoomyImagePolicy.prepare(input, "deepseek-chat")
                .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        ok(original.equals(input.toString()));
        ok(actual.length() == 4);
        ok(actual.getJSONObject(0).getString("text").equals("<image_base64>data:image/png;base64,ONE=</image_base64>"));
        ok(actual.getJSONObject(1).getString("image_url").equals("https://example.test/remote.png"));
        ok(actual.getJSONObject(2).getString("text").equals("<image_base64>data:image/png;base64,TWO=</image_base64>\n"
                + "<image_base64>data:image/png;base64,THREE=</image_base64>\ntail"));
        ok(actual.getJSONObject(3).getString("value").equals("keep"));
        org.json.JSONArray allImages = new org.json.JSONArray()
                .put(blocks.getJSONObject(0)).put(blocks.getJSONObject(2)).put(blocks.getJSONObject(3));
        JSONObject imagesOnly = new JSONObject().put("messages", new org.json.JSONArray().put(new JSONObject().put("content", allImages)));
        org.json.JSONArray converted = LoomyImagePolicy.prepare(imagesOnly, "deepseek-chat")
                .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        String text = converted.getJSONObject(0).getString("text");
        ok(converted.length() == 1 && text.indexOf("ONE=") < text.indexOf("TWO=") && text.indexOf("TWO=") < text.indexOf("THREE="));
    }
}
