package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.gaeul.allin.json.*;

public class CatPawCatalogHttpTest {
    static class Fixture extends HttpURLConnection {
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        final String body; final int status;
        Fixture(String body, int status) throws Exception { super(new URL("https://fixture.invalid")); this.body=body; this.status=status; }
        public void disconnect() {} public boolean usingProxy() { return false; } public void connect() {}
        public OutputStream getOutputStream() { return request; }
        public int getResponseCode() { return status; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
    }
    static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        String body="{\"code\":0,\"data\":[{\"modelTypeId\":77,\"modelTypeName\":\"LongCat\",\"extendedInfo\":{\"rateMultiplier\":\"1.5x\",\"marketingCopy\":\"限时免费\"}},{\"modelTypeId\":123,\"modelTypeName\":\"New Model\",\"description\":\"Official\",\"extendedInfo\":\"{\\\"rateMultiplier\\\":0}\"},{\"modelTypeId\":-1,\"provider\":\"USER_CUSTOM\"}]}";
        Fixture ok = new Fixture(body,200); JSONObject meta = new JSONObject();
        java.util.List<String> ids = CatPawAuth.fetchCatalog("test-token",meta,url -> { check(url.equals("https://ai.catpaw.meituan.com/api/agent/maas/model-types"),"url"); return ok; });
        check(ids.size()==2 && ids.contains("new-model"),"ids");
        check(meta.getJSONObject("longcat-2.0").getDouble("multiplier")==1.5 && !meta.getJSONObject("longcat-2.0").getBoolean("free"),"positive rate");
        check(meta.getJSONObject("new-model").getDouble("multiplier")==0 && meta.getJSONObject("new-model").getString("name").equals("Official"),"new model");
        check(ok.getRequestMethod().equals("POST") && ok.getRequestProperty("Cookie").equals("X-Passport-Token=test-token"),"method and cookie");
        check(new JSONObject(ok.request.toString("UTF-8")).getString("tenant").equals("CatDesk"),"body");
        check(meta.getJSONObject("new-model").getInt("modelTypeId")==123 && meta.getJSONObject("new-model").getInt("modelType")==123,"persisted type");
        JSONArray messages = new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
        final java.util.List<Integer> sent = new java.util.ArrayList<>();
        CatPawAuth.ChatTransport transport = (url, request, headers, timeout, accept) -> {
            if (url.endsWith("/round") || url.endsWith("/turn")) sent.add(new JSONObject(request).getInt("modelType"));
            String response = url.endsWith("/turn") ? "data: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}\n\n" : "{\"code\":0}";
            return new CatPawAuth.ChatResponse(200, response.getBytes(StandardCharsets.UTF_8));
        };
        // Metadata is passed independently of any prior catalog read or token.
        CatPawAuth.chatWithCatalogMetadata("different-token", "new-model", messages, 123, transport);
        check(sent.equals(java.util.Arrays.asList(123,123)),"dynamic round and turn");
        sent.clear(); CatPawAuth.chat("different-token", "new-model", messages, transport);
        check(sent.equals(java.util.Arrays.asList(77,77)),"legacy static fallback without cache");
        sent.clear(); CatPawAuth.chatWithCatalogMetadata("different-token", "kimi-k3", messages, 123, transport);
        check(sent.equals(java.util.Arrays.asList(83,83)),"known static type wins");
        for (int invalid : new int[]{0,-1}) {
            sent.clear();
            try { CatPawAuth.chatWithCatalogMetadata("different-token", "new-model", messages, invalid, transport); throw new AssertionError("invalid type accepted"); }
            catch (CatPawAuth.Failure expected) { check(expected.httpStatus==400 && !expected.sentOrUnknown,"invalid type failure"); }
            check(sent.isEmpty(),"invalid type sent request");
        }
        for (String bad : new String[]{"{}", "{\"data\":[]}", "{\"data\":[{\"modelTypeId\":123}]}", "{\"data\":[{\"modelTypeId\":123,\"modelTypeName\":\"valid\"},{\"modelTypeId\":124}]}"}) {
            JSONObject retained = new JSONObject().put("old",new JSONObject().put("multiplier",2));
            try { CatPawAuth.fetchCatalog("test-token",retained,url -> new Fixture(bad,200)); throw new AssertionError("accepted bad catalog"); } catch (IOException expected) { }
            check(retained.length()==1 && retained.has("old"),"preserve metadata");

        }
        try { CatPawAuth.fetchCatalog("test-token",new JSONObject(),url -> new Fixture("{}",401)); throw new AssertionError("accepted 401"); } catch (IOException expected) { }
        System.out.println("CatPawCatalogHttpTest passed");
    }
}
