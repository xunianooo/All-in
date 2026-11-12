package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic responses only: exercises the production parsers and fallback boundary. */
public final class CatalogReadIntegrityHttpTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final class Fake extends HttpURLConnection {
        final int status; final byte[] body;
        Fake(int code, String text) throws Exception { super(new URL("https://fixture.invalid/models")); status=code; body=text.getBytes(StandardCharsets.UTF_8); }
        public int getResponseCode() { return status; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        public void disconnect() {} public boolean usingProxy() { return false; } public void connect() {}
    }
    static List<String> codex(int code, String body) throws Exception {
        CodexAuth.SignInInfo info = new CodexAuth.SignInInfo();
        info.authMode="chatgpt"; info.accessToken="fixture"; info.expiresAt=0;
        return CodexAuth.syncModelsStrict(info, url -> new Fake(code, body));
    }
    static Object json(String text) throws Exception { return new JSONTokener(text).nextValue(); }
    public static void main(String[] args) throws Exception {
        for (String body : new String[]{"{}", "not-json", "{\"models\":[]}", "{\"models\":[{\"slug\":\"valid\"},{}]}"})
            check(codex(200, body).isEmpty(), "codex rejects malformed/empty");
        check(codex(401,"{}").isEmpty(), "codex HTTP failure");
        check(codex(200,"{\"models\":[{\"slug\":\"real\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}").equals(Arrays.asList("real")), "codex live");
        CodexAuth.SignInInfo key = new CodexAuth.SignInInfo(); key.authMode="apikey";
        check(CodexAuth.syncModelsStrict(key).isEmpty(), "apikey no synthetic live");
        check(!CodexAuth.syncModels(key).isEmpty(), "apikey compatibility defaults");
        final Object rec=json("{\"recommended\":[{\"id\":\"a/free\",\"free\":true},{\"id\":\"a/paid\"}]}");
        final Object cloud=json("{\"data\":[{\"id\":\"a/paid\",\"context_length\":1000},{\"id\":\"cloud-only\",\"display_name\":\"Cloud Only\"}]}");
        JSONObject meta=new JSONObject();
        List<String> models=ClineAuth.fetchFeedStrict(meta, url -> url.endsWith("recommended-models") ? rec : cloud);
        check(models.equals(Arrays.asList("a/free","a/paid","cloud-only")), "cloud union");
        check(meta.getJSONObject("a/free").optBoolean("free"), "free flag");
        check(!meta.getJSONObject("a/paid").optBoolean("free"), "free flag does not leak");
        check(meta.getJSONObject("a/paid").getInt("context")==1000, "cloud context");
        for (Object bad : new Object[]{null, json("{}"), json("{\"data\":[]}"), json("{\"data\":[{\"id\":\"ok\"},{}]}")}) {
            JSONObject unchanged=new JSONObject().put("old",new JSONObject().put("free",true));
            check(ClineAuth.fetchFeedStrict(unchanged,url -> url.endsWith("recommended-models") ? rec : bad).isEmpty(), "bad cloud must fail");
            check(unchanged.length()==1 && unchanged.has("old"), "failure leaves metadata unchanged");
        }
        check(ClineAuth.fetchFeedStrict(new JSONObject(),url -> { throw new IOException("fixture"); }).isEmpty(), "transport failure");
        check(ClineAuth.fetchFeedStrict(new JSONObject(),url -> url.endsWith("recommended-models") ? json("{}") : cloud).isEmpty(), "recommended schema failure");
        System.out.println("CatalogReadIntegrityHttpTest passed");
    }
}
