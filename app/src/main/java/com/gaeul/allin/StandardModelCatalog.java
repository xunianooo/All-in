package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded, all-or-nothing retrieval of standard vendor model catalogs. */
final class StandardModelCatalog {
    interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    private static final int MAX_PAGES = 20, MAX_MODELS = 10000, MAX_BODY = 2 * 1024 * 1024;

    static List<String> fetch(String provider, String baseUrl, String token, JSONObject metaOut,
                              ConnectionFactory factory) {
        try {
            URI base = new URI(baseUrl == null ? "" : baseUrl.trim());
            if (base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null
                    || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme())))
                throw new IOException("Invalid catalog base URL");
            String path = base.getRawPath();
            if (path == null) path = "";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.endsWith("/v1")) path = path.substring(0, path.length() - 3);
            String query = base.getRawQuery();
            // A configured cursor makes the catalog incomplete and must never be silently overwritten.
            if (query != null) for (String part : query.split("&")) {
                String key = part.split("=", 2)[0];
                if ("after_id".equals(java.net.URLDecoder.decode(key, "UTF-8")))
                    throw new IOException("Configured pagination cursor");
            }
            String endpoint = base.getScheme() + "://" + base.getRawAuthority() + path + "/v1/models"
                    + (query == null || query.isEmpty() ? "" : "?" + query);
            LinkedHashSet<String> models = new LinkedHashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            int total = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = endpoint + (cursor == null ? "" : (endpoint.contains("?") ? "&" : "?")
                        + "after_id=" + URLEncoder.encode(cursor, "UTF-8"));
                HttpURLConnection conn = null;
                try {
                    conn = factory.open(url);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("User-Agent", "MagpiePocket/1.0");
                    if ("anthropic".equals(provider)) {
                        conn.setRequestProperty("x-api-key", token);
                        conn.setRequestProperty("anthropic-version", "2023-06-01");
                    } else conn.setRequestProperty("Authorization", "Bearer " + token);
                    int status = conn.getResponseCode();
                    if (status < 200 || status >= 300) throw new IOException("Catalog HTTP " + status);
                    byte[] buffer = new byte[8192];
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (n > MAX_BODY - body.size()) throw new IOException("Catalog body limit");
                            body.write(buffer, 0, n);
                        }
                    }
                    JSONObject root = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
                    boolean hasData = root.has("data"), hasModels = root.has("models");
                    if (hasData == hasModels) throw new IOException("Ambiguous catalog schema");
                    Object raw = root.opt(hasData ? "data" : "models");
                    if (!(raw instanceof JSONArray)) throw new IOException("Invalid model array");
                    JSONArray data = (JSONArray) raw;
                    if (data.length() > MAX_MODELS - total) throw new IOException("Catalog model limit");
                    total += data.length();
                    for (int i = 0; i < data.length(); i++) {
                        Object item = data.opt(i);
                        Object id;
                        if (item instanceof JSONObject) {
                            JSONObject model = (JSONObject) item;
                            id = model.opt("id");
                            if (id == null || JSONObject.NULL.equals(id) || "".equals(id)) id = model.opt("name");
                        } else id = item;
                        if (!(id instanceof String) || ((String) id).trim().isEmpty())
                            throw new IOException("Invalid model id");
                        models.add((String) id);
                    }
                    Object more = root.opt("has_more");
                    if (more == null && !"anthropic".equals(provider)) return new ArrayList<>(models);
                    if (!(more instanceof Boolean)) throw new IOException("Invalid pagination flag");
                    if (!((Boolean) more)) return new ArrayList<>(models);
                    Object next = root.opt("last_id");
                    if (!(next instanceof String) || ((String) next).trim().isEmpty()
                            || !cursors.add((String) next)) throw new IOException("Invalid pagination cursor");
                    cursor = (String) next;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            throw new IOException("Catalog page limit");
        } catch (Exception failure) {
            return new ArrayList<>();
        }
    }
}
