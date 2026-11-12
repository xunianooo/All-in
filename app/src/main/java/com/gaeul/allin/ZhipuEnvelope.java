package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;

/** Normalizes the documented Zhipu platform refusal envelope on its coding endpoints. */
final class ZhipuEnvelope {
    private static final int LIMIT = 64 * 1024;
    private static final int FIELD_LIMIT = 4096;

    static final class Result {
        final InputStream body;
        final int status;
        final String errorBody;
        final String code;
        final boolean adapted;
        Result(InputStream body, int status, String errorBody, String code, boolean adapted) {
            this.body = body; this.status = status; this.errorBody = errorBody;
            this.code = code; this.adapted = adapted;
        }
    }

    static Result inspect(HttpURLConnection conn, InputStream stream, String actualUrl) throws IOException {
        return inspect(conn, stream, actualUrl, false);
    }

    // Test hook only: production must use the three-argument overload.
    static Result inspect(HttpURLConnection conn, InputStream stream, String actualUrl,
                          boolean trustedOriginForTest) throws IOException {
        int status = conn.getResponseCode();
        if (stream == null || !applicable(actualUrl, status, conn.getContentType(),
                conn.getContentEncoding(), trustedOriginForTest)) {
            return new Result(stream, status, null, null, false);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        IOException failure = null;
        try {
            while (buffer.size() <= LIMIT) {
                int n = stream.read(chunk, 0, Math.min(chunk.length, LIMIT + 1 - buffer.size()));
                if (n < 0) break;
                if (n == 0) {
                    int one = stream.read();
                    if (one < 0) break;
                    buffer.write(one);
                } else buffer.write(chunk, 0, n);
            }
        } catch (IOException e) { failure = e; }
        byte[] prefix = buffer.toByteArray();
        if (failure != null) {
            return new Result(new ReplayFailure(prefix, stream, failure), status, null, null, false);
        }
        InputStream replay = new SequenceInputStream(new ByteArrayInputStream(prefix), stream);
        if (prefix.length > LIMIT) return new Result(replay, status, null, null, false);
        try {
            JSONTokener tokener = new JSONTokener(new String(prefix, StandardCharsets.UTF_8));
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
                return new Result(replay, status, null, null, false);
            JSONObject obj = (JSONObject) parsed;
            JSONObject nested=obj.optJSONObject("error");
            if (!Boolean.FALSE.equals(obj.opt("success")) && nested==null)
                return new Result(replay, status, null, null, false);
            // An error envelope must never replace a normal model message or choices.
            if(obj.has("choices") || obj.has("content") || obj.has("output"))return new Result(replay,status,null,null,false);
            Object rawCode = nested==null?obj.opt("code"):nested.opt("code");
            int number = 0;
            if (rawCode instanceof String || rawCode instanceof Number) {
                try { number = Integer.parseInt(rawCode.toString()); } catch (NumberFormatException ignored) { }
            }
            Object rawMsg = nested==null?obj.opt("msg"):nested.opt("message");
            String message = rawMsg instanceof String ? ((String) rawMsg).trim() : "";
            if (number == 0 && message.isEmpty()) return new Result(replay, status, null, null, false);
            if (message.isEmpty()) message = "request failed";
            if (message.length() > FIELD_LIMIT) message = message.substring(0, FIELD_LIMIT);
            String code = number == 0 ? null : Integer.toString(number);
            JSONObject error = new JSONObject().put("message", message);
            if (code != null) error.put("code", code);
            String json = new JSONObject().put("error", error).toString();
            InputStream adapted = new SequenceInputStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), stream);
            return new Result(adapted, codeStatus(number), json, code, true);
        } catch (Exception ignored) {
            return new Result(replay, status, null, null, false);
        }
    }

    static boolean applicable(String url, int status, String ct, String encoding, boolean trusted) {
        if (status < 200 || status >= 300 || ct == null) return false;
        String type = ct.toLowerCase(Locale.ROOT);
        if (!type.contains("json") || type.contains("event-stream")) return false;
        if (encoding != null && !encoding.trim().isEmpty() && !encoding.trim().equalsIgnoreCase("identity")) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || (!trusted && !"https".equalsIgnoreCase(uri.getScheme())) || (trusted && !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (!trusted && uri.getPort()!=-1 && uri.getPort()!=443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (!trusted && !host.equals("open.bigmodel.cn") && !host.equals("api.z.ai")) return false;
            String path = uri.getRawPath();
            return path != null && (path.equals("/api/v1") || path.equals("/api/v1/responses")
                    || path.equals("/api/monitor") || path.startsWith("/api/monitor/")
                    || path.equals("/api/anthropic/v1/messages") || path.equals("/api/coding/paas/v4/messages") || path.equals("/api/coding/paas/v4/chat/completions"));
        } catch (Exception e) { return false; }
    }

    static int codeStatus(int code) {
        if (code >= 1000 && code <= 1005) return 401;
        if (code == 1113 || code >= 1302 && code <= 1399) return 429;
        if (code == 1220) return 403;
        if (code == 3012) return 405;
        if (code >= 1210 && code <= 1229 || code == 1261 || code == 1301) return 400;
        return 502;
    }

    private static final class ReplayFailure extends InputStream {
        private final ByteArrayInputStream prefix;
        private final InputStream original;
        private IOException failure;
        ReplayFailure(byte[] prefix, InputStream original, IOException failure) {
            this.prefix = new ByteArrayInputStream(prefix); this.original = original; this.failure = failure;
        }
        @Override public int read() throws IOException {
            int b = prefix.read();
            if (b >= 0) return b;
            if (failure != null) { IOException e = failure; failure = null; throw e; }
            return original.read();
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int n = prefix.read(b, off, len);
            if (n >= 0) return n;
            if (failure != null) { IOException e = failure; failure = null; throw e; }
            return original.read(b, off, len);
        }
        @Override public void close() throws IOException { original.close(); }
    }
}
