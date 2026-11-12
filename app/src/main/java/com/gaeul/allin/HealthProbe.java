package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class HealthProbe {
    static final class Result {
        final boolean ok; final int code; final String error;
        Result(boolean ok, int code, String error) { this.ok = ok; this.code = code; this.error = error; }
    }
    static Result probe(String expectedInstance) { return probe(3425, expectedInstance); }
    static Result probe(int port, String expectedInstance) {
        HttpURLConnection c = null;
        int code = 0;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/health").openConnection(java.net.Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(1000);
            c.setReadTimeout(1500);
            c.setRequestProperty("Connection", "close");
            code = c.getResponseCode();
            if (code != 200) return new Result(false, code, "HttpStatus");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int n;
            long deadline = System.nanoTime() + 3000000000L;
            while ((n = in.read(buffer)) != -1) {
                if (System.nanoTime() > deadline) return new Result(false, code, "ProbeDeadline");
                if (out.size() + n > 4096) return new Result(false, code, "ResponseTooLarge");
                out.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            boolean ok = json.optBoolean("ok", false)
                    && "com.gaeul.allin".equals(json.optString("service"))
                    && expectedInstance != null && !expectedInstance.isEmpty()
                    && expectedInstance.equals(json.optString("instance"));
            return new Result(ok, code, ok ? "" : "IdentityMismatch");
        } catch (Throwable t) {
            return new Result(false, code, t.getClass().getSimpleName());
        } finally { if (c != null) c.disconnect(); }
    }
}
