package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

/** Client-visible evidence of a received rate rejection, never a guess from a broken socket. */
final class CooldownFeedback {
    static final long DEFAULT_MS = 60000L, MAX_MS = 21600000L;
    private CooldownFeedback() { }
    static boolean limited(int status, String body) {
        try {
            JSONObject root = new JSONObject(body), error = root.optJSONObject("error");
            if (error == null) return status == 429;
            String code = error.optString("code", error.optString("type", ""));
            if(java.util.Arrays.asList("insufficient_quota","quota_exceeded","credits_exhausted","authentication_error","permission_error").contains(code))return false;
            return status == 429 || java.util.Arrays.asList("user_model_rate_limited", "rate_limit", "rate_limit_error", "rate_limit_exceeded", "requests_rate_limited").contains(code);
        } catch (Exception invalid) { return status == 429; }
    }
    static long waitMillis(String header, long remaining, long now) {
        String safe = ProviderRetryPolicy.safeRetryAfter(header, now);
        long upstream = safe == null ? (remaining > 0 ? remaining : DEFAULT_MS) : Long.parseLong(safe) * 1000L;
        return Math.min(MAX_MS, Math.max(Math.max(0, remaining), upstream));
    }
    static JSONObject body(String provider, String original, String header, long remaining, long now) {
        String name = provider != null && provider.matches("[a-zA-Z0-9_-]{1,64}") ? provider : "upstream";
        long wait = waitMillis(header, remaining, now), seconds = (wait + 999) / 1000;
        String code = "rate_limit_error";
        try {
            JSONObject e = new JSONObject(original).optJSONObject("error");
            if (e != null) {String originalCode=e.optString("code","");if("user_model_rate_limited".equals(originalCode)||originalCode.matches("[0-9]{1,9}"))code=originalCode;}
        } catch (Exception ignored) {
            if("trae-cn".equals(name)&&original!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("code ([0-9]{1,9})").matcher(original);if(m.find())code=m.group(1);}
        }
        boolean said = ProviderRetryPolicy.safeRetryAfter(header, now) != null;
        String message = name + " 已触发限流，" + (seconds > 0 ? "请在 " + seconds + " 秒后重试" : "可稍后重试")
                + (said ? "（参考上游等待提示，恢复时间以供应商为准）" : "（网关保护性冷却时间，非上游恢复保证）");
        long until = now > Long.MAX_VALUE - wait ? Long.MAX_VALUE : now + wait;
        JSONObject detail = new JSONObject().put("type", "rate_limit_error").put("code", code).put("message", message)
                .put("provider", name).put("retry_after", seconds).put("retry_after_ms", wait)
                .put("cooldown_until", until).put("cooldown_source", said ? "upstream_hint" : "gateway");
        return new JSONObject().put("type", "error").put("error", detail);
    }
    /** Rewrite explicit SSE rate errors before protocol projection; ordinary frames stay intact. */
    static final class Stream extends InputStream {
        private static final int MAX_FRAME = 1024 * 1024;
        private final BufferedReader source;
        private final String provider, retry;
        private final LongConsumer observe;
        private byte[] pending = new byte[0]; private int offset;
        private boolean ended;
        Stream(InputStream source, String provider, String retry, LongConsumer observe) {
            this.source = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
            this.provider = provider; this.retry = retry; this.observe = observe;
        }
        private String line() throws IOException {
            StringBuilder b = new StringBuilder(); int c;
            while ((c = source.read()) != -1) {
                if (c == '\n') break;
                if (b.length() >= MAX_FRAME) throw new IOException("SSE frame exceeds limit");
                b.append((char)c);
            }
            if (c == -1 && b.length() == 0) return null;
            if (b.length() > 0 && b.charAt(b.length()-1) == '\r') b.setLength(b.length()-1);
            return b.toString();
        }
        private boolean fill() throws IOException {
            while (offset >= pending.length) {
                if (ended) return false;
                StringBuilder frame = new StringBuilder(), data = new StringBuilder(); String line;
                while ((line = line()) != null) {
                    if (frame.length() + line.length() + 1 > MAX_FRAME) throw new IOException("SSE frame exceeds limit");
                    frame.append(line).append('\n');
                    if (line.startsWith("data:")) { if (data.length()>0) data.append('\n'); data.append(line.substring(5).trim()); }
                    if (line.isEmpty()) break;
                }
                if (line == null) ended = true;
                if (frame.length() == 0) return false;
                String text = data.toString();
                if (limited(0, text)) {
                    String hint = retry;
                    try {
                        JSONObject e = new JSONObject(text).optJSONObject("error");
                        Object seconds = e == null ? null : e.opt("retry_after");
                        if (seconds instanceof Number && ((Number)seconds).doubleValue() == ((Number)seconds).longValue() && ((Number)seconds).longValue() >= 0)
                            hint = String.valueOf(((Number)seconds).longValue());
                    } catch (Exception ignored) { }
                    long now = System.currentTimeMillis(), wait = waitMillis(hint, 0, now);
                    if (observe != null) observe.accept(wait);
                    JSONObject error = body(provider, text, hint, wait, now);
                    // This is a terminal error, not a normal finish or a synthetic successful [DONE].
                    frame = new StringBuilder("event: error\ndata: ").append(error).append("\n\n");
                    ended = true;
                }
                pending = frame.toString().getBytes(StandardCharsets.UTF_8); offset = 0;
            }
            return true;
        }
        @Override public int read() throws IOException { return fill() ? pending[offset++] & 255 : -1; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || len > b.length-off) throw new IndexOutOfBoundsException();
            if (len == 0) return 0; if (!fill()) return -1;
            int n = Math.min(len, pending.length-offset); System.arraycopy(pending,offset,b,off,n); offset += n; return n;
        }
        @Override public void close() throws IOException { ended = true; source.close(); }
    }
}
