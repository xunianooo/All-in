package com.gaeul.allin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProviderCatalogCommitOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) out.append(canonical(array.get(i))).append(',');
            return out.append(']').toString();
        }
        return JSONObject.valueToString(value);
    }
    static final class Fixture implements AutoCloseable {
        final Path dir;
        final File file;
        final GatewayServer server;
        final JSONObject expected;
        final JSONArray models = new JSONArray().put("new-model");
        final JSONObject metadata = new JSONObject().put("new-model", new JSONObject().put("context", 1234));
        Fixture() throws Exception {
            JSONObject provider = new JSONObject().put("id", "alpha").put("enabled", true)
                    .put("protocol", "openai").put("baseUrl", "https://example.invalid")
                    .put("accounts", new JSONArray().put(new JSONObject().put("id", "one").put("access", "old")))
                    .put("keys", new JSONArray().put("old-key"))
                    .put("models", new JSONArray().put("old-model"))
                    .put("modelMeta", new JSONObject().put("old-model", new JSONObject().put("context", 42)));
            JSONObject config = new JSONObject().put("providers", new JSONArray().put(provider))
                    .put("unrelated", new JSONObject().put("keep", true));
            expected = new JSONObject(provider.toString());
            server = RoutingSetupOfflineTest.server(config);
            dir = Files.createTempDirectory("catalog-commit");
            file = dir.resolve("config.json").toFile();
            Files.write(file.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
            set("configFile", file);
        }
        void set(String name, Object value) throws Exception {
            Field field = GatewayServer.class.getDeclaredField(name); field.setAccessible(true); field.set(server, value);
        }
        JSONObject config() throws Exception {
            Field field = GatewayServer.class.getDeclaredField("configJson"); field.setAccessible(true); return (JSONObject) field.get(server);
        }
        JSONObject provider() throws Exception { return config().getJSONArray("providers").getJSONObject(0); }
        void persistCurrent() throws Exception { Files.write(file.toPath(), config().toString().getBytes(StandardCharsets.UTF_8)); }
        void commit() throws Exception {
            Method method = GatewayServer.class.getDeclaredMethod("persistProviderCatalog", String.class, JSONObject.class, JSONArray.class, JSONObject.class);
            method.setAccessible(true);
            try { method.invoke(server, "alpha", expected, models, metadata); }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new AssertionError(cause);
            }
        }
        void assertRejected() throws Exception {
            JSONObject before = config();
            String memory = canonical(before);
            byte[] disk = Files.readAllBytes(file.toPath());
            boolean rejected = false;
            try { commit(); } catch (Exception expectedFailure) { rejected = true; }
            check(rejected, "stale or unavailable catalog commit accepted");
            check(config() == before && canonical(config()).equals(memory), "rejected commit changed memory");
            check(java.util.Arrays.equals(disk, Files.readAllBytes(file.toPath())), "rejected commit changed disk");
        }
        public void close() throws Exception {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
            }
        }
    }
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            // Simulate a credential commit that published a fresh config while catalog I/O was in flight.
            JSONObject refreshed = new JSONObject(f.config().toString());
            JSONObject provider = refreshed.getJSONArray("providers").getJSONObject(0);
            provider.getJSONArray("accounts").getJSONObject(0).put("access", "new-token");
            provider.put("keys", new JSONArray().put("new-key"));
            refreshed.getJSONObject("unrelated").put("concurrent", "preserved");
            f.set("configJson", refreshed); f.persistCurrent();
            f.commit();
            check(f.provider().getJSONArray("accounts").getJSONObject(0).getString("access").equals("new-token"), "lost refreshed token");
            check(f.provider().getJSONArray("keys").getString(0).equals("new-key"), "lost refreshed key");
            check(canonical(f.provider().getJSONArray("models")).equals(canonical(f.models)), "models not committed");
            JSONObject committedMeta=new JSONObject(f.provider().getJSONObject("modelMeta").toString());
            long observed=committedMeta.getJSONObject("new-model").getLong("catalogObservedAt");
            check(observed>0 && observed<=System.currentTimeMillis(),"catalog timestamp missing");
            committedMeta.getJSONObject("new-model").remove("catalogObservedAt");
            check(canonical(committedMeta).equals(canonical(f.metadata)), "metadata not committed");
            check(f.config().getJSONObject("unrelated").getString("concurrent").equals("preserved"), "lost unrelated update");
            JSONObject onDisk = new JSONObject(new String(Files.readAllBytes(f.file.toPath()), StandardCharsets.UTF_8));
            check(canonical(onDisk).equals(canonical(f.config())), "actual file differs from published config");
            check(f.expected.getJSONArray("accounts").getJSONObject(0).getString("access").equals("old"), "snapshot was mutated");
        }
        for (String change : new String[]{"models", "modelMeta", "disabled", "deleted", "baseUrl", "protocol", "retired", "disk"}) {
            try (Fixture f = new Fixture()) {
                switch (change) {
                    case "models": f.provider().put("models", new JSONArray().put("other")); break;
                    case "modelMeta": f.provider().put("modelMeta", new JSONObject().put("other", true)); break;
                    case "disabled": f.provider().put("enabled", false); break;
                    case "deleted": f.config().put("providers", new JSONArray()); break;
                    case "baseUrl": f.provider().put("baseUrl", "https://changed.invalid"); break;
                    case "protocol": f.provider().put("protocol", "anthropic"); break;
                    case "retired": f.set("backupRetired", true); break;
                    case "disk": f.set("configFile", f.dir.resolve("missing/config.json").toFile()); break;
                }
                f.persistCurrent();
                f.assertRejected();
            }
        }
        System.out.println("ProviderCatalogCommitOfflineTest passed");
    }
}
