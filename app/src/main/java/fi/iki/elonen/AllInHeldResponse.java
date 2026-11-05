package fi.iki.elonen;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Holds a response while its supplier runs on the caller's bounded executor.
 * The supplier worker also pumps successful SSE bodies into a bounded queue.
 * Heartbeats during body stalls are emitted only at SSE event boundaries; a partial
 * event cannot safely contain an injected comment. Non-SSE bodies stream directly.
 * The owner must call close even when send is never called. No executor is owned.
 * Once the first heartbeat is sent, upstream status and headers cannot be forwarded.
 * The cancellation callback must interrupt/close underlying network operations and must
 * not wait for this response's send thread. close() may close a blocked source.
 */
public final class AllInHeldResponse extends NanoHTTPD.Response {
    private final Object lock = new Object();
    private final AtomicBoolean sourceClosed = new AtomicBoolean();
    private final java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();
    private final Future<?> task;
    private final ExecutorService executor;
    private final long firstMs, intervalMs;
    private final Runnable cancel, businessWrite;
    private final BooleanSupplier cancelled;
    private final String protocol;
    private final AtomicBoolean cancelOnce = new AtomicBoolean();
    private volatile boolean closed;
    private boolean ready, sending, completed;
    private volatile boolean signalled;
    private NanoHTTPD.Response upstream;
    private Throwable error;
    private volatile InputStream body;
    private AllInTrackedResponse.WriteCompletion completion;
    private NanoHTTPD.Method method;
    private boolean keepAlive;
    private volatile boolean readFailed;
    private final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(8);
    private boolean pumpDecision, pumpEnabled;
    private volatile boolean pumpDone;
    private volatile Throwable pumpFailure;
    private static final byte[] HEARTBEAT = bytes(": heartbeat\n\n");

    /** cancelled must also return true when the caller's remaining budget is exhausted. */
    public AllInHeldResponse(Callable<NanoHTTPD.Response> supplier, ExecutorService executor,
            long firstHeartbeatMillis, long heartbeatIntervalMillis, Runnable cancel,
            BooleanSupplier cancelled, String protocol, Runnable onBusinessWrite) {
        super(NanoHTTPD.Response.Status.OK, "text/event-stream", null, 0);
        if (supplier == null || executor == null || cancel == null || cancelled == null || onBusinessWrite == null)
            throw new NullPointerException();
        if (firstHeartbeatMillis < 0 || heartbeatIntervalMillis <= 0) throw new IllegalArgumentException("heartbeat interval");
        if (!"chat".equals(protocol) && !"anthropic".equals(protocol) && !"responses".equals(protocol))
            throw new IllegalArgumentException("protocol");
        this.executor = executor;
        this.firstMs = firstHeartbeatMillis; this.intervalMs = heartbeatIntervalMillis;
        this.cancel = cancel; this.cancelled = cancelled; this.protocol = protocol; this.businessWrite = onBusinessWrite;
        task = executor.submit(() -> {
            NanoHTTPD.Response result = null; Throwable failure = null;
            try { result = supplier.call(); if (result == null) throw new IOException("null response"); }
            catch (Throwable t) { failure = t; }
            boolean dispose;
            synchronized (lock) {
                dispose = closed;
                if (!dispose) { upstream = result; error = failure; ready = true; lock.notifyAll(); }
            }
            if (dispose && result != null) { disposeUnsent(result); return; }
            if (result == null) return;
            try {
                synchronized (lock) {
                    while (!pumpDecision && !closed) lock.wait();
                    if (!pumpEnabled || closed) return;
                }
                InputStream source = body;
                byte[] buffer = new byte[8192];
                int n;
                while (!closed && (n = source.read(buffer)) != -1) {
                    if (n == 0) continue;
                    byte[] chunk = java.util.Arrays.copyOf(buffer, n);
                    while (!closed && !chunks.offer(chunk, 100, TimeUnit.MILLISECONDS)) { }
                }
            } catch (Throwable t) { pumpFailure = t; }
            finally { pumpDone = true; }
        });
    }

    private static void disposeUnsent(NanoHTTPD.Response response) {
        InputStream data = response.getData();
        try { if (data instanceof AllInTrackedResponse.WriteCompletion)
            ((AllInTrackedResponse.WriteCompletion) data).onWriteComplete(false); }
        finally { try { response.close(); } catch (IOException ignored) {} }
    }
    private void cancelWork() {
        if (cancelOnce.compareAndSet(false, true)) {
            try { cancel.run(); } catch (RuntimeException ignored) {}
        }
        cancelTask();
    }
    private void cancelTask() {
        task.cancel(true);
        if (executor instanceof ThreadPoolExecutor && task instanceof Runnable)
            ((ThreadPoolExecutor) executor).remove((Runnable) task);
    }
    /** Interrupt pending supplier/pump work and wake the sender without closing it.
     * A pending or active sender emits the appropriate cancellation error.
     * This method never invokes the cancellation callback. */
    public void signalCancellation() {
        signalled = true;
        cancelTask();
        synchronized (lock) { lock.notifyAll(); }
    }
    @Override public void addHeader(String name, String value) {
        synchronized (lock) { extraHeaders.put(name, value); }
        super.addHeader(name, value);
    }
    private boolean stopped() { return closed || signalled || cancelled.getAsBoolean(); }
    private boolean await(long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (lock) {
            while (!ready && !stopped()) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
            return ready;
        }
    }
    private void selectPump(boolean enabled) {
        synchronized (lock) { pumpEnabled = enabled; pumpDecision = true; lock.notifyAll(); }
    }
    private static boolean isSse(NanoHTTPD.Response response) {
        return response != null && response.getStatus() != null &&
                response.getStatus().getRequestStatus() >= 200 && response.getStatus().getRequestStatus() < 300 &&
                response.getMimeType() != null && response.getMimeType().toLowerCase(java.util.Locale.US).contains("text/event-stream") &&
                response.getHeader("content-length") == null && response.getData() != null;
    }
    private void startBody(NanoHTTPD.Response response) {
        body = response.getData();
        if (body instanceof AllInTrackedResponse.WriteCompletion) {
            completion = (AllInTrackedResponse.WriteCompletion) body;
            completion.onWriteStart();
        }
    }
    private void closeSource() {
        InputStream source = body;
        if (source != null && sourceClosed.compareAndSet(false, true))
            try { source.close(); } catch (IOException ignored) {}
    }
    private void finish(boolean success) {
        AllInTrackedResponse.WriteCompletion cb;
        NanoHTTPD.Response response;
        synchronized (lock) {
            if (completed) return;
            completed = true; closed = true; cb = completion;
            response = upstream;
            if (cb == null && body == null && response != null && response.getData() instanceof AllInTrackedResponse.WriteCompletion)
                cb = (AllInTrackedResponse.WriteCompletion) response.getData();
            lock.notifyAll();
        }
        if (!success) cancelWork();
        try { if (cb != null) cb.onWriteComplete(success); }
        finally {
            if (body != null) closeSource();
            // A delegated wrapper does not own the source. When the original response
            // still points at it, closeSource already performed its only close.
            if (response != null && response.getData() != body) try { response.close(); } catch (IOException ignored) {}
            else if (response != null && body == null) try { response.close(); } catch (IOException ignored) {}
        }
    }
    @Override public void close() {
        NanoHTTPD.Response response;
        boolean active;
        synchronized (lock) { if (completed || closed) return; closed = true; active = sending; response = upstream; lock.notifyAll(); }
        cancelWork();
        // Closing the source may unblock a direct streaming read.
        if (active && body != null) closeSource();
        if (!active) finish(false);
    }
    @Override public void setRequestMethod(NanoHTTPD.Method value) { method = value; super.setRequestMethod(value); }
    @Override public void setKeepAlive(boolean value) { keepAlive = value; super.setKeepAlive(value); }
    @Override public void setGzipEncoding(boolean ignored) { super.setGzipEncoding(false); }
    @Override public void closeConnection(boolean value) { super.closeConnection(value); }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private byte[] lateError() {
        if ("anthropic".equals(protocol)) return bytes("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Upstream request failed\"}}\n\n");
        if ("responses".equals(protocol)) return bytes("event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_" + java.util.UUID.randomUUID().toString().replace("-", "") + "\",\"object\":\"response\",\"status\":\"failed\",\"error\":{\"code\":\"upstream_error\",\"message\":\"Upstream request failed\"}}}\n\n");
        return bytes("data: {\"error\":{\"message\":\"Upstream request failed\",\"type\":\"api_error\"}}\n\n");
    }
    /** Reads only on send thread; producer never writes to the socket. */
    private final class DeferredBody extends InputStream {
        byte[] pending; int pos; boolean terminal, attached;
        boolean boundary = true, lineEmpty = true, previousCR;
        DeferredBody(boolean initialHeartbeat, boolean attached) {
            this.attached = attached;
            if (initialHeartbeat) pending = HEARTBEAT;
        }
        private void updateBoundary(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 255;
                if (c == '\n') {
                    boundary = lineEmpty;
                    lineEmpty = true; previousCR = false;
                } else if (c == '\r') {
                    if (previousCR) lineEmpty = false;
                    previousCR = true; boundary = false;
                } else {
                    lineEmpty = false; previousCR = false; boundary = false;
                }
            }
        }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            try {
                while (true) {
                    if (pending != null) {
                        int n = Math.min(len, pending.length - pos);
                        System.arraycopy(pending, pos, b, off, n); pos += n;
                        if (pending != HEARTBEAT && !terminal) {
                            updateBoundary(b, off, n); businessPending = true; businessBuffer = b;
                        }
                        if (pos == pending.length) { if (pending == HEARTBEAT) lastHeartbeatNanos = System.nanoTime(); pending = null; pos = 0; }
                        return n;
                    }
                    if (terminal) return -1;
                    if (closed) { readFailed = true; throw new IOException("closed"); }
                    if (signalled || cancelled.getAsBoolean()) {
                        readFailed = true;
                        if (!boundary) throw new IOException("cancelled in partial SSE event");
                        pending = lateError(); terminal = true; continue;
                    }
                    if (!attached) {
                        if (!await(intervalMs)) { if (!closed && !signalled && !cancelled.getAsBoolean()) pending = HEARTBEAT; continue; }
                        if (closed) continue;
                        if (signalled || cancelled.getAsBoolean()) continue;
                        if (!isSse(upstream) || error != null) {
                            readFailed = true; pending = lateError(); terminal = true; continue;
                        }
                        startBody(upstream); attached = true; selectPump(true);
                    }
                    byte[] next = chunks.poll(Math.min(intervalMs, 100), TimeUnit.MILLISECONDS);
                    if (next != null) { pending = next; continue; }
                    if (pumpDone && chunks.isEmpty()) {
                        if (pumpFailure != null) {
                            readFailed = true;
                            if (!boundary) throw new IOException("upstream failed in partial SSE event", pumpFailure);
                            pending = lateError(); terminal = true; continue;
                        }
                        if (!boundary) { readFailed = true; throw new IOException("upstream ended in partial SSE event"); }
                        return -1;
                    }
                    // Poll in short intervals for cancellation, but do not send extra heartbeats
                    // faster than the configured interval.
                    long now = System.nanoTime();
                    if (boundary && now - lastHeartbeatNanos >= TimeUnit.MILLISECONDS.toNanos(intervalMs)) {
                        lastHeartbeatNanos = now; pending = HEARTBEAT;
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); readFailed = true; throw new IOException(e); }
        }
        private long lastHeartbeatNanos = System.nanoTime();
        @Override public void close() { /* source is owned by response */ }
    }
    // Set on the send thread by body.read, consumed after actual output succeeds.
    private boolean businessPending;
    private byte[] businessBuffer;
    private final class CheckedOutput extends OutputStream {
        final OutputStream out; boolean failed; boolean chunked; boolean headersDone;
        int match, chunkState; long chunkSize, chunkRemaining; long expectedLength = -1, bodyBytes; final ByteArrayOutputStream headers = new ByteArrayOutputStream();
        CheckedOutput(OutputStream out, boolean chunked) { this.out = out; this.chunked = chunked; }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
                if (!headersDone) {
                    for (int i = off; i < off + len; i++) {
                        if (headers.size() < 65536) headers.write(b[i]);
                        int c = b[i] & 255;
                        match = (match == 0 && c == 13) ? 1 : (match == 1 && c == 10) ? 2 :
                                (match == 2 && c == 13) ? 3 : (match == 3 && c == 10) ? 4 : 0;
                        if (match == 4) { headersDone = true; String text = new String(headers.toByteArray(), StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.US); chunked = text.contains("transfer-encoding: chunked");
                            for (String line : text.split("\r\n")) if (line.startsWith("content-length:")) try { expectedLength = Long.parseLong(line.substring(15).trim()); } catch (NumberFormatException ignored) {}
                            break; }
                    }
                } else if (chunked) {
                    if (businessPending && b == businessBuffer) { businessPending = false; businessWrite.run(); }
                    // Parse chunk bytes, independent of how writes are split. Flush after
                    // the terminating CRLF of each chunk, including the final chunk.
                    for (int i = off; i < off + len; i++) {
                        int c = b[i] & 255;
                        if (chunkState == 0) {
                            if (c == '\r') chunkState = 1;
                            else { int digit = Character.digit((char)c, 16); if (digit < 0) throw new IOException("invalid chunk size"); chunkSize = (chunkSize << 4) | digit; }
                        } else if (chunkState == 1) {
                            if (c != '\n') throw new IOException("invalid chunk header");
                            chunkRemaining = chunkSize; chunkState = chunkSize == 0 ? 3 : 2;
                        } else if (chunkState == 2) {
                            if (--chunkRemaining == 0) chunkState = 3;
                        } else if (chunkState == 3) {
                            if (c != '\r') throw new IOException("invalid chunk terminator"); chunkState = 4;
                        } else {
                            if (c != '\n') throw new IOException("invalid chunk terminator");
                            out.flush(); chunkState = 0; chunkSize = 0;
                        }
                    }
                } else { bodyBytes += len; if (businessPending && b == businessBuffer) { businessPending = false; businessWrite.run(); } }
            } catch (IOException e) { failed = true; throw e; }
        }
        @Override public void flush() throws IOException { try { out.flush(); } catch (IOException e) { failed = true; throw e; } }
    }
    @Override protected void send(OutputStream output) {
        synchronized (lock) { if (closed || sending) return; sending = true; }
        boolean success = false;
        try {
            boolean before = await(firstMs);
            if (closed) return;
            boolean deadline = signalled || cancelled.getAsBoolean();
            NanoHTTPD.Response chosen;
            boolean chunked = false;
            if (deadline) {
                chosen = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Request deadline exceeded");
                readFailed = true;
            } else if (before) {
                if (error != null || upstream == null) {
                    chosen = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Upstream request failed");
                    readFailed = true;
                } else {
                    chosen = upstream; startBody(chosen);
                    if (isSse(chosen) && method != NanoHTTPD.Method.HEAD) {
                        chosen.setChunkedTransfer(true);
                        chosen.setData(new DeferredBody(false, true));
                        selectPump(true);
                    } else {
                    selectPump(false);
                    if (body != null) chosen.setData(new FilterInputStream(body) {
                        @Override public int read(byte[] b, int o, int l) throws IOException {
                            try { int n = in.read(b, o, l); if (n > 0) { businessPending = true; businessBuffer = b; } return n; }
                            catch (IOException e) { readFailed = true; throw e; }
                        }
                        @Override public void close() {} // response owns original body
                    });
                    }
                }
            } else {
                chunked = true;
                chosen = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream; charset=utf-8", new DeferredBody(true, false));
                chosen.addHeader("Cache-Control", "no-cache, no-transform");
                chosen.addHeader("X-Accel-Buffering", "no");
            }
            synchronized (lock) {
                for (java.util.Map.Entry<String, String> header : extraHeaders.entrySet())
                    chosen.addHeader(header.getKey(), header.getValue());
            }
            chosen.setRequestMethod(method); chosen.setKeepAlive(keepAlive); chosen.closeConnection(isCloseConnection()); chosen.setGzipEncoding(false);
            CheckedOutput checked = new CheckedOutput(output, chunked);
            chosen.send(checked);
            success = !checked.failed && !readFailed && !stopped() &&
                    (method == NanoHTTPD.Method.HEAD || checked.chunked || checked.expectedLength < 0 || checked.bodyBytes == checked.expectedLength);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { finish(success); }
    }
}
