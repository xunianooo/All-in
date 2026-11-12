package com.gaeul.allin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import com.gaeul.allin.json.JSONObject;

/** Local, in-memory duplicate-refusal protection. 180 seconds is our conservative
 * policy, not an upstream duration or a claim that an IP/account is banned.
 * Callers must provide a stable nonempty account identifier and network-scope ID;
 * identifiers are SHA-256 hashed before storage. Check before every attempt,
 * including metadata requests. This gate does not establish whether a request
 * executed or authorize fallback/retry after an ambiguous failure. */
final class ZCodeRefusalPolicy {
    enum Kind { NONE, ACCOUNT, EDGE, UNKNOWN }
    interface Clock { long nowMillis(); }
    static final long HOLD_MILLIS = 180_000L;
    static final int MAX_ENTRIES = 512;
    static final class Hold {
        final Kind kind;
        /** Monotonic clock domain, not wall-clock timestamp. */
        final long until;
        final long retryAfterMillis;
        final String message;
        Hold(Kind kind, long until, long now, String message) {
            this.kind = kind; this.until = until;
            this.retryAfterMillis = Math.max(0L, until - now); this.message = message;
        }
    }
    private static final class Entry {
        final Kind kind; final long until;
        Entry(Kind kind, long until) { this.kind = kind; this.until = until; }
    }
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private long lastNow = Long.MIN_VALUE;
    private long overflowUntil = Long.MIN_VALUE;
    ZCodeRefusalPolicy() { this(() -> System.nanoTime() / 1_000_000L); }
    ZCodeRefusalPolicy(Clock clock) { if (clock == null) throw new IllegalArgumentException("clock"); this.clock = clock; }
    private long now() { long n = clock.nowMillis(); if (n < lastNow) return lastNow; return lastNow = n; }
    private static long deadline(long now) { return now > Long.MAX_VALUE - HOLD_MILLIS ? Long.MAX_VALUE : now + HOLD_MILLIS; }
    private void prune(long now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) if (it.next().until <= now) it.remove();
    }
    static String trustedOrigin(String url) {
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null
                    || (u.getPort() != -1 && u.getPort() != 443) || u.getFragment() != null || u.getRawQuery()!=null) return null;
            String host = u.getHost(); if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            String path = u.getRawPath();
            if (host.equals("zcode.z.ai") && "/api/v1/zcode-plan/anthropic/v1/messages".equals(path)) return "https://" + host;
            if ((host.equals("api.z.ai") || host.equals("open.bigmodel.cn")) &&
                    ("/api/anthropic/v1/messages".equals(path) || "/api/coding/paas/v4/messages".equals(path)
                    || "/api/coding/paas/v4/chat/completions".equals(path))) return "https://" + host;
        } catch (Exception ignored) { }
        return null;
    }
    static Kind classify(int status, String body) {
        if (status >= 200 && status < 300) return Kind.NONE;
        if (body != null) {
            String text = body.trim();
            if (text.startsWith("{")) {
                try {
                    JSONObject json = new JSONObject(text);
                    Object code = json.opt("code");
                    Object err = json.opt("error");
                    if (is3012(code) || (err instanceof JSONObject && is3012(((JSONObject) err).opt("code")))) return Kind.ACCOUNT;
                } catch (Exception ignored) { }
            }
            String lower = text.toLowerCase(Locale.ROOT);
            boolean html = lower.contains("<!doctype html") || lower.contains("<html");
            boolean block = lower.contains("errors.aliyun.com") || (lower.contains("cloudflare") &&
                    (lower.contains("error 1020") || lower.contains("error code: 1020") || lower.contains("access denied") || lower.contains("blocked")));
            if ((status == 403 || status == 405 || status == 429) && html && block) return Kind.EDGE;
        }
        return status == 405 ? Kind.UNKNOWN : Kind.NONE;
    }
    private static boolean is3012(Object code) { return code instanceof Number ? ((Number) code).doubleValue() == 3012.0 : code instanceof String && "3012".equals(code); }
    private static String hash(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte v : b) out.append(String.format(Locale.ROOT, "%02x", v & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static boolean valid(String s) { return s != null && !s.trim().isEmpty(); }
    private static String key(String type, String origin, String network, String account) {
        return type + hash(origin) + hash(network) + (account == null ? "" : hash(account));
    }
    private static String message(Kind kind) {
        return kind == Kind.ACCOUNT ? "ZCode returned code 3012; local account cooldown is active."
                : kind == Kind.EDGE ? "ZCode edge refusal detected; local network cooldown is active."
                : "ZCode protection requires valid account and network scopes.";
    }
    synchronized Hold before(String url, String accountScope, String networkScope) {
        String origin = trustedOrigin(url); if (origin == null) return null;
        long n = now(); prune(n);
        if (!valid(accountScope) || !valid(networkScope)) return new Hold(Kind.UNKNOWN, deadline(n), n, message(Kind.UNKNOWN));
        if (overflowUntil > n) return new Hold(Kind.EDGE, overflowUntil, n, "Local protection capacity reached; temporary shared cooldown is active.");
        Entry edge = entries.get(key("E", origin, networkScope, null));
        Entry account = entries.get(key("A", origin, networkScope, accountScope));
        Entry e = edge == null ? account : account == null || edge.until >= account.until ? edge : account;
        return e == null ? null : new Hold(e.kind, e.until, n, message(e.kind));
    }
    synchronized Kind observe(String url, String accountScope, String networkScope, int status, String body) {
        String origin = trustedOrigin(url); if (origin == null) return Kind.NONE;
        Kind kind = classify(status, body);
        if (kind != Kind.ACCOUNT && kind != Kind.EDGE) return kind;
        long n = now(); prune(n);
        if (!valid(accountScope) || !valid(networkScope)) { overflowUntil = Math.max(overflowUntil, deadline(n)); return kind; }
        String k = key(kind == Kind.EDGE ? "E" : "A", origin, networkScope, kind == Kind.EDGE ? null : accountScope);
        if (!entries.containsKey(k) && entries.size() >= MAX_ENTRIES) overflowUntil = Math.max(overflowUntil, deadline(n));
        else entries.put(k, new Entry(kind, deadline(n)));
        return kind;
    }
    synchronized void clear() { entries.clear(); overflowUntil = Long.MIN_VALUE; }
}
