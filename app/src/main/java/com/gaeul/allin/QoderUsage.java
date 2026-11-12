package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import com.gaeul.allin.json.JSONObject;

/**
 * Qoder's account usage envelope, {displayMode, qoderUsage}, read as the vendored plugin reads it
 * (opencode-qoder-auth 0.2.64, index.mjs:2525-2545): the account pages' usage, asked with the
 * device token rather than the job token the chat runs on.
 *
 * <p>Android has no such read today — GatewayServer's {@code fetchBillingAllowance} answers other
 * providers and QoderAuth reads the catalog only — which is why {@link QoderCredits} has nothing to
 * count in the gateway. This class is that read, and nothing else: it decodes, it does not decide.
 * What the envelope means ("spent" or not) belongs to {@link QoderCredits}, and what a spent
 * account means for one model belongs to {@link QoderRefusal}.
 *
 * <p><b>One address, and only that one.</b> The request is exactly {@link #url()}: scheme, host and
 * path come from constants below, redirects are never followed, and no other host is ever opened.
 * A caller that stands up a loopback double still passes through here, so the address a double sees
 * is the address the phone would dial.
 *
 * <p><b>A whole-read deadline, not a socket timeout.</b> {@code setReadTimeout} only bounds a single
 * {@code read}; a server dripping one byte every 100 ms would never trip it. The response read
 * timeout is therefore the whole budget left, and the body is read in a loop that checks one
 * deadline for the whole of it — the read's own {@link #TIMEOUT_MS} capped by whatever the request
 * has left ({@link Budget}) — and a caller's cancellation between reads. Past it the connection is
 * disconnected and the read fails: the caller sees "unreadable", never a partial body passed off as
 * an answer. No thread is started anywhere in here, so an aborted read leaves nothing running.
 *
 * <p><b>One request, asked once.</b> The status is read with a single {@code getResponseCode()}
 * bounded by the same deadline, never retried: a retry on a connection whose read timed out is not
 * a thing {@code HttpURLConnection} promises to survive, and re-asking would be a second request
 * nobody counted. A slow header leg is therefore allowed the whole budget, and a body that trickles
 * is stopped by the deadline in the loop rather than by a short socket timeout.
 *
 * <p>Every failure is an exception and none is a verdict: a read that fails says the usage is
 * unknown, which leaves a request on the answer it gives without one.
 */
public final class QoderUsage {
    private QoderUsage() {}

    /** Qoder's openapi origin, the one QoderAuth.OPENAPI holds, kept apart so this read stands alone. */
    public static final String ORIGIN = "https://openapi.qoder.com.cn";

    /** The usage page the plugin reads (index.mjs:2531). */
    public static final String PATH = "/sash/api/v2/me/usage";

    /** The read's ceiling: an envelope is a few kilobytes, and never unbounded. */
    public static final int MAX_BYTES = 512 * 1024;

    /** The longest a read may take, as the plugin allows it (20s). */
    public static final long TIMEOUT_MS = 20000L;

    /** The longest wait for the connection itself. */
    public static final long CONNECT_MS = 10000L;

    /** The one address this read ever opens. */
    public static String url() { return ORIGIN + PATH; }

    /** A transport for one GET: the gateway passes its own policy-bound opener. */
    public interface Transport {
        HttpURLConnection open(String url) throws Exception;
    }

    /**
     * What the surrounding request allows: how much time is left for the whole read, whether it
     * was cancelled, and the connection to cancel (so a cancelled request closes a socket the read
     * is blocked on, rather than waiting for the slice to lapse).
     */
    public interface Budget {
        /** Milliseconds left for the whole read; {@link Long#MAX_VALUE} for none. */
        long remainingMillis();
        /** Whether the request was cancelled. */
        boolean cancelled();
        /** Why, for the failure's message; never a token or an upstream body. */
        String reason();
        /** Register a connection the request may cancel, or do nothing. */
        void attach(HttpURLConnection connection) throws Exception;
        /** Forget an attached connection. */
        void detach(HttpURLConnection connection);

        /** A read inside no request at all: only its own timeout bounds it. */
        Budget NONE = new Budget() {
            public long remainingMillis() { return Long.MAX_VALUE; }
            public boolean cancelled() { return false; }
            public String reason() { return ""; }
            public void attach(HttpURLConnection connection) { }
            public void detach(HttpURLConnection connection) { }
        };
    }

    /** The account's usage envelope, under no request's budget. */
    public static JSONObject read(String token, long timeoutMillis, Transport transport) throws Exception {
        return read(token, timeoutMillis, transport, Budget.NONE);
    }

    /**
     * The account's usage envelope.
     *
     * @param token   the account's device token; the job token only when it has no device token, as
     *                the plugin reads a PAT account's usage
     * @param timeoutMillis the longest this read may take; 0 or less means {@link #TIMEOUT_MS}
     * @param transport where to open {@link #url()}
     * @param budget  the surrounding request's time and cancellation, or null for none
     * @return the envelope as Qoder sent it
     * @throws Exception when the token is missing, the call fails, Qoder answers other than 200,
     *                   the body is not an envelope, the envelope names another display mode, the
     *                   read runs past its deadline, or the request was cancelled
     */
    public static JSONObject read(String token, long timeoutMillis, Transport transport, Budget budget)
            throws Exception {
        if (token == null || token.trim().isEmpty())
            throw new IllegalArgumentException("Qoder usage: missing device token");
        if (transport == null) throw new IllegalArgumentException("Qoder usage: missing transport");
        Budget limit = budget == null ? Budget.NONE : budget;
        if (limit.cancelled()) throw new IOException("Qoder usage: canceled");
        long allowed = timeoutMillis > 0 ? Math.min(timeoutMillis, TIMEOUT_MS) : TIMEOUT_MS;
        long left = limit.remainingMillis();
        if (left <= 0) throw new IOException("Qoder usage: no time left for this read");
        long total = Math.min(allowed, left);
        final long deadlineNanos = System.nanoTime() + total * 1000000L;

        HttpURLConnection conn = null;
        boolean attached = false;
        try {
            conn = transport.open(url());
            if (conn == null) throw new IOException("Qoder usage: no transport");
            limit.attach(conn);
            attached = true;
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setDoOutput(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Cosy-ClientType", "10");
            conn.setRequestProperty("User-Agent", "Qoder");
            int status = answer(conn, limit, deadlineNanos);
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body;
            try {
                body = text(stream, limit, deadlineNanos);
            } finally {
                closeQuietly(stream);
            }
            if (status != 200) throw new IOException("Qoder usage: upstream HTTP " + status);
            return decode(body);
        } finally {
            if (conn != null) {
                if (attached) { try { limit.detach(conn); } catch (Throwable ignored) { } }
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * The response status, asked once and bounded by the whole-read deadline: read timeout is all
     * the time this read has, so a slow header leg is allowed to take it and a refused socket is
     * the deadline arriving, not a slice to retry.
     */
    private static int answer(HttpURLConnection conn, Budget budget, long deadlineNanos) throws Exception {
        if (budget.cancelled()) throw new IOException("Qoder usage: canceled");
        long remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) throw timeout(budget, null);
        conn.setConnectTimeout((int) Math.max(1L, Math.min(CONNECT_MS, remaining)));
        conn.setReadTimeout((int) Math.max(1L, Math.min(Integer.MAX_VALUE, remaining)));
        return conn.getResponseCode();
    }

    /**
     * The body, read under the one deadline. Each read may block up to the read timeout (the whole
     * remaining budget), so a read that stalls cannot outlast the deadline; a body that trickles
     * returns often, and the loop stops it at the deadline instead of letting every single read
     * timeout reset the clock.
     */
    private static String text(InputStream stream, Budget budget, long deadlineNanos) throws Exception {
        if (stream == null) throw new IOException("Qoder usage: empty response");
        try (InputStream source = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            for (;;) {
                if (budget.cancelled()) throw new IOException("Qoder usage: canceled");
                if (remainingMillis(deadlineNanos) <= 0) throw timeout(budget, null);
                int n;
                try {
                    n = source.read(buf);
                } catch (SocketTimeoutException stalled) {
                    // The read timeout is the deadline itself: a socket timeout here means the
                    // deadline arrived, not that another slice should be tried.
                    throw timeout(budget, stalled);
                }
                if (n < 0) break;
                total += n;
                if (total > MAX_BYTES) throw new IOException("Qoder usage: response too large");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static IOException timeout(Budget budget, Throwable cause) {
        String why = budget.cancelled() && budget.reason() != null && !budget.reason().isEmpty()
                ? "Qoder usage: canceled (" + budget.reason() + ")" : "Qoder usage: timed out";
        IOException failure = new IOException(why);
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private static long remainingMillis(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime() + 999999L) / 1000000L;
    }

    /**
     * The envelope a body carries, checked as the plugin checks it: "qoder" or "enterprise", and a
     * "qoder" envelope must name its quota data. Anything else is not an envelope.
     */
    static JSONObject decode(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) throw new IOException("Qoder usage: empty response");
        JSONObject envelope;
        try {
            envelope = new JSONObject(body.trim());
        } catch (Exception e) {
            throw new IOException("Qoder usage: decode response");
        }
        String mode = envelope.optString("displayMode", "");
        if (!"qoder".equals(mode) && !"enterprise".equals(mode))
            throw new IOException("Qoder usage: unknown display mode");
        if ("qoder".equals(mode) && (envelope.opt("qoderUsage") == null || envelope.isNull("qoderUsage")))
            throw new IOException("Qoder usage: missing quota data");
        return envelope;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Exception ignored) { }
    }
}
