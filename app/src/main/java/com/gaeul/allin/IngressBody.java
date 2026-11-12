package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded request-body reader. Header occurrences lost by the HTTP parser cannot be checked here. */
final class IngressBody {
    static final int DEFAULT_LIMIT = 8 * 1024 * 1024;
    static final int MAX_LIMIT = 64 * 1024 * 1024;

    enum Classification { OK, INVALID_FRAMING, UNSUPPORTED_TRANSFER_ENCODING, UNSUPPORTED_CONTENT_ENCODING,
        TOO_LARGE, INCOMPLETE, TIMEOUT, INVALID_UTF8, IO_ERROR }

    static final class Result implements AutoCloseable {
        private IngressMemoryBudget.Lease memory;
        final String text;
        final int status;
        final String message;
        final Classification classification;
        final long bytesRead;
        final boolean mustClose;
        private Result(String text, int status, String message, Classification classification, long bytesRead) {
            this.text = text; this.status = status; this.message = message;
            this.classification = classification; this.bytesRead = bytesRead; this.mustClose = status != 200;
        }
        boolean ok() { return status == 200; }
        public void close() { if (memory != null) memory.close(); }
    }

    /** Transfers a prepared body to a held-response worker without freeing a running worker's lease. */
    static final class Handoff {
        private Result result;
        private boolean started;
        Handoff(Result result) { this.result = result; }
        synchronized Result begin() {
            if (result == null) return null;
            started = true;
            return result;
        }
        synchronized void cancelPending() {
            if (!started && result != null) { result.close(); result = null; }
        }
        synchronized void finish() {
            if (result != null) { result.close(); result = null; }
        }
    }

    private IngressBody() { }

    /** Invalid or absent configuration uses the default; oversized values are capped. */
    static int effectiveLimit(long configured) {
        if (configured < 65536) return DEFAULT_LIMIT;
        return (int) Math.min(configured, (long) MAX_LIMIT);
    }

    /** limit is an already selected byte limit; clamp it again to the absolute cap. */
    static Result read(InputStream in, Map<String, String> headers, String method, int limit) {
        return read(in, headers, method, limit, null);
    }
    static Result read(InputStream in, Map<String, String> headers, String method, int limit, OutboundLimiter.Cancellation cancellation) {
        return read(in,headers,method,limit,cancellation,30000L);
    }
    static Result read(InputStream in, Map<String, String> headers, String method, int limit, OutboundLimiter.Cancellation cancellation, long timeoutMillis) {
        if (cancellation != null && cancellation.isCancelled())
            return fail(408, "Request body cancelled or deadline exceeded", Classification.TIMEOUT, 0);
        int bound = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        String cl = null, te = null, ce = null;
        boolean hasCl = false, hasTe = false, hasCe = false;
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            if (key.equalsIgnoreCase("content-length")) {
                if (hasCl) return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
                hasCl = true; cl = e.getValue();
            } else if (key.equalsIgnoreCase("transfer-encoding")) {
                if (hasTe) return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
                hasTe = true; te = e.getValue();
            } else if (key.equalsIgnoreCase("content-encoding")) {
                if (hasCe) return fail(415, "Unsupported content encoding", Classification.UNSUPPORTED_CONTENT_ENCODING, 0);
                hasCe = true; ce = e.getValue();
            }
        }
        if (hasCl && hasTe) return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
        if (hasTe) {
            if (te != null && trimOws(te).equalsIgnoreCase("chunked"))
                return fail(501, "Transfer encoding is not supported", Classification.UNSUPPORTED_TRANSFER_ENCODING, 0);
            return fail(400, "Invalid or unsupported transfer encoding", Classification.INVALID_FRAMING, 0);
        }
        if (hasCe && (ce == null || !trimOws(ce).equalsIgnoreCase("identity")))
            return fail(415, "Unsupported content encoding", Classification.UNSUPPORTED_CONTENT_ENCODING, 0);
        long length = 0;
        if (hasCl) {
            if (cl == null) return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
            String value = trimOws(cl);
            if (value.isEmpty()) return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9' || length > (Long.MAX_VALUE - (c - '0')) / 10)
                    return fail(400, "Invalid request framing", Classification.INVALID_FRAMING, 0);
                length = length * 10 + c - '0';
            }
        }
        if (length > bound) return fail(413, "Request body too large", Classification.TOO_LARGE, 0);
        if (length == 0) return success("", 0);
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method))
            return fail(400, "Request body not supported for method", Classification.INVALID_FRAMING, 0);
        if (in == null) return fail(400, "Incomplete request body", Classification.INCOMPLETE, 0);
        long allocation = IngressMemoryBudget.allocationBytes(length);
        if (allocation > IngressMemoryBudget.MAX_BYTES) return fail(413,"Request body exceeds device memory budget",Classification.TOO_LARGE,0);
        IngressMemoryBudget.Lease memory=IngressMemoryBudget.acquire(allocation);
        if(memory==null) return fail(503,"Concurrent request body capacity exhausted",Classification.IO_ERROR,0);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1,Math.min(30000L,timeoutMillis)));
        long count = 0;
        boolean retained = false;
        try {
            // Content-Length is already validated: one exact allocation avoids BAOS growth/copies.
            byte[] buffer = new byte[(int) length];
            while (count < length) {
                if(System.nanoTime()>=deadline) return fail(408,"Request body deadline exceeded",Classification.TIMEOUT,count);
                if (cancellation != null && cancellation.isCancelled())
                    return fail(408, "Request body cancelled or deadline exceeded", Classification.TIMEOUT, count);
                int n = in.read(buffer, (int) count, (int) Math.min(8192L, length - count));
                if(System.nanoTime()>=deadline) return fail(408,"Request body deadline exceeded",Classification.TIMEOUT,count+Math.max(0,n));
                if (cancellation != null && cancellation.isCancelled())
                    return fail(408, "Request body cancelled or deadline exceeded", Classification.TIMEOUT, count);
                if (n < 0) return fail(400, "Incomplete request body", Classification.INCOMPLETE, count);
                if (n == 0) return fail(400, "Unable to read request body", Classification.IO_ERROR, count);
                count += n;
            }
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer)).toString();
                Result result = success(text, count);
                result.memory = memory;
                retained = true;
                return result;
            } catch (CharacterCodingException e) {
                return fail(400, "Invalid UTF-8 request body", Classification.INVALID_UTF8, count);
            }
        } catch (SocketTimeoutException e) {
            return fail(408, "Request body read timed out", Classification.TIMEOUT, count);
        } catch (IOException e) {
            if (cancellation != null && cancellation.isCancelled())
                return fail(408, "Request body cancelled or deadline exceeded", Classification.TIMEOUT, count);
            return fail(400, "Unable to read request body", Classification.IO_ERROR, count);
        } finally {
            memory.readComplete();
            if (!retained) memory.close();
        }
    }
    private static String trimOws(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }
    private static Result success(String text, long count) { return new Result(text, 200, "", Classification.OK, count); }
    private static Result fail(int status, String message, Classification c, long count) { return new Result("", status, message, c, count); }
}
