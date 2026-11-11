package fi.iki.elonen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Validates framing before NanoHTTPD can collapse duplicate header fields, and bounds how long one
 * request header may take.
 *
 * <p>NanoHTTPD 2.3.1 only bounds each individual socket read ({@code SOCKET_READ_TIMEOUT}, applied in
 * {@code ServerRunnable}). A client that drips one byte per read interval therefore never trips a
 * read timeout: it keeps a {@code NanoHttpd Request Processor} thread (one thread per accepted
 * connection, unbounded) busy for up to {@code MAX_HEADER} read intervals before this guard rejects
 * the size. The absolute header budget below turns that into one bounded wait per header.</p>
 *
 * <p>This class deliberately does not hold the {@link java.net.Socket}: the existing constructor is
 * used by {@code GatewayServer.validateRawHeaders}. The budget is honoured by a shared daemon
 * deadline thread that closes the underlying stream when the budget expires, which unblocks the read
 * that the guard is waiting on even if the socket read timeout is infinite. It therefore aborts the
 * connection (no 408 is written, because NanoHTTPD may be mid-response and its writer must stay
 * single-threaded) and never touches the accept thread. The budget covers only the header phase:
 * request bodies and long streaming responses are never bounded here.</p>
 *
 * <p>Each header arms its own deadline generation token, and the token is claimed exactly once,
 * under a short lock, by whichever side gets there first: the reader that finished the header or the
 * deadline task that fired. A task that loses that race is inert: it can never mark the stream
 * closed, never set the "budget exceeded" flag and never close the socket, because its token is
 * already retired. This is what keeps a keep-alive connection alive: request N's budget can no
 * longer abort request N+1's header, which the previous "is a header in progress" flag could not
 * express. The lock never spans a read or a close, so a blocked reader cannot hold up the deadline
 * thread and the deadline thread cannot hold up a reader for longer than one flag write.</p>
 */
public final class AllInRawHeaderInputStream extends InputStream {
    private static final int MAX_HEADER = 8192;
    /**
     * Absolute budget for one complete request header, measured on a monotonic clock from the first
     * header byte this guard reads (each header of a keep-alive connection gets a fresh budget).
     * Twice {@link NanoHTTPD#SOCKET_READ_TIMEOUT} is the smallest value that leaves every connection
     * which already survives the per-read socket timeout untouched: an idle keep-alive connection or
     * a stalled header is still ended by the socket read timeout first, so only a dribbling client
     * sees the new limit.
     */
    static final long HEADER_DEADLINE_MILLIS = NanoHTTPD.SOCKET_READ_TIMEOUT * 2L;
    /**
     * One daemon thread for all connections. A task is cancelled as soon as its header finishes; the
     * generation token below makes a task that outlived its cancellation harmless on its own.
     */
    private static final ScheduledThreadPoolExecutor HEADER_DEADLINES = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "allin-header-deadline");
        thread.setDaemon(true);
        return thread;
    });
    static {
        HEADER_DEADLINES.setRemoveOnCancelPolicy(true);
        HEADER_DEADLINES.setKeepAliveTime(30L, TimeUnit.SECONDS);
        HEADER_DEADLINES.allowCoreThreadTimeOut(true);
    }
    private final InputStream source;
    private final OutputStream response;
    private final long headerDeadlineMillis;
    /**
     * Serialises the single state transition that decides which of the two header outcomes wins:
     * "this header completed" or "this header's budget expired". Held only for that constant-time
     * flip, never across a read or a close.
     */
    private final Object deadlineGuard = new Object();
    /** Token of the budget armed for the header currently being read, or 0 when nothing is armed. */
    private long armedGeneration;
    /** Last token handed out; monotonic so a retired token can never be reissued. */
    private long generationCounter;
    private byte[] header;
    private int headerPosition;
    private long bodyRemaining;
    private volatile boolean closed;
    /** Set only by the task that claimed its own token; read outside the guard by the reader. */
    private volatile boolean headerDeadlineExceeded;

    public AllInRawHeaderInputStream(InputStream source, OutputStream response) {
        this(source, response, HEADER_DEADLINE_MILLIS);
    }

    /** Same contract with an explicit header budget; production uses {@link #HEADER_DEADLINE_MILLIS}. */
    AllInRawHeaderInputStream(InputStream source, OutputStream response, long headerDeadlineMillis) {
        this.source = source;
        this.response = response;
        this.headerDeadlineMillis = headerDeadlineMillis;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (closed) return -1;
        if (header != null && headerPosition < header.length) {
            int count = Math.min(length, header.length - headerPosition);
            System.arraycopy(header, headerPosition, bytes, offset, count);
            headerPosition += count;
            return count;
        }
        if (bodyRemaining > 0) {
            int count = source.read(bytes, offset, (int) Math.min((long) length, bodyRemaining));
            if (count < 0) { closed = true; return -1; }
            bodyRemaining -= count;
            return count;
        }
        if (!loadHeader()) return -1;
        return read(bytes, offset, length);
    }

    private boolean loadHeader() throws IOException {
        long generation = armHeaderDeadline();
        ScheduledFuture<?> deadline = scheduleHeaderDeadline(generation);
        try {
            boolean complete = readHeader();
            if (!disarmHeaderDeadline(generation)) {
                // The budget claimed this generation while the header was being assembled, so the
                // stream is already being closed for this very request: the bytes just read must not
                // be handed out as a valid header.
                throw headerDeadlineFailure(null);
            }
            return complete;
        } catch (IOException | RuntimeException | Error failure) {
            disarmHeaderDeadline(generation);
            throw failure;
        } finally {
            if (deadline != null) deadline.cancel(false);
        }
    }

    /** Arms one budget for exactly one header and returns its generation token. */
    private long armHeaderDeadline() {
        synchronized (deadlineGuard) {
            headerDeadlineExceeded = false;
            armedGeneration = ++generationCounter;
            return armedGeneration;
        }
    }

    /**
     * Retires the token of a header that finished. Returns false when the deadline claimed the
     * generation first, which means this same request is being aborted.
     */
    private boolean disarmHeaderDeadline(long generation) {
        synchronized (deadlineGuard) {
            if (armedGeneration != generation) return false;
            armedGeneration = 0L;
            return true;
        }
    }

    private ScheduledFuture<?> scheduleHeaderDeadline(long generation) {
        try {
            return HEADER_DEADLINES.schedule(() -> headerDeadlineExpired(generation),
                    headerDeadlineMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException unavailable) {
            // Executor gone: fail open. The token stays armed so the reader retires it normally.
            return null;
        }
    }

    /**
     * Deadline task body. Claiming the token and deciding to abort are one guarded step, so the
     * outcome is all-or-nothing: either this task owns the request it was scheduled for and closes
     * the stream, or it observes that its token is no longer armed (its header finished, or a later
     * keep-alive request already owns the guard) and touches nothing at all.
     *
     * <p>Comparing the token rather than a shared "is a header in progress" flag is what makes
     * keep-alive safe: a task that lost its race only ever matches its own retired token, never the
     * token of the next request on the same connection.</p>
     *
     * <p>Closing happens outside the guard because {@code close()} may block on the socket; the
     * token is already retired at that point, so no reader or later task waits behind it.</p>
     */
    void headerDeadlineExpired(long generation) {
        synchronized (deadlineGuard) {
            if (armedGeneration != generation) return;
            armedGeneration = 0L;
            headerDeadlineExceeded = true;
            closed = true;
        }
        try { source.close(); } catch (IOException ignored) { }
    }

    /** Visible for tests: token armed for the header in flight, 0 when no header is being read. */
    long armedHeaderGeneration() {
        synchronized (deadlineGuard) { return armedGeneration; }
    }

    /**
     * Reads one header byte. The blocking read is bounded by the deadline thread closing the stream,
     * which surfaces here as a timeout rather than as an unhandled transport failure.
     */
    private int readHeaderByte() throws IOException {
        if (headerDeadlineExceeded) throw headerDeadlineFailure(null);
        try {
            return source.read();
        } catch (IOException failure) {
            if (headerDeadlineExceeded) throw headerDeadlineFailure(failure);
            throw failure;
        }
    }

    private SocketTimeoutException headerDeadlineFailure(Throwable cause) {
        SocketTimeoutException failure =
                new SocketTimeoutException("Request header not complete within " + headerDeadlineMillis + " ms");
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private boolean readHeader() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int c = readHeaderByte();
            if (c < 0) {
                if (buffer.size() != 0) reject(400);
                closed = true;
                return false;
            }
            buffer.write(c);
            if (buffer.size() > MAX_HEADER) reject(400);
            if (state == 0) state = c == '\r' ? 1 : 0;
            else if (state == 1) state = c == '\n' ? 2 : -1;
            else if (state == 2) state = c == '\r' ? 3 : 0;
            else if (state == 3) { if (c == '\n') break; state = -1; }
            if (state == -1 || (c == '\n' && state != 2)) reject(400);
        }
        byte[] candidate = buffer.toByteArray();
        String text = new String(candidate, StandardCharsets.ISO_8859_1);
        String[] lines = text.substring(0, text.length() - 4).split("\\r\\n", -1);
        if (lines.length == 0 || !validRequestLine(lines[0])) reject(400);
        boolean hasLength = false, hasTransfer = false;
        String transfer = null;
        long parsedLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) reject(400);
            for (int j = 0; j < colon; j++) if (!token(line.charAt(j))) reject(400);
            for (int j = colon + 1; j < line.length(); j++) {
                char ch = line.charAt(j);
                if ((ch < 32 && ch != '\t') || ch == 127) reject(400);
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("content-length")) {
                if (hasLength || value.isEmpty()) reject(400);
                hasLength = true;
                for (int j = 0; j < value.length(); j++) {
                    char c = value.charAt(j);
                    if (c < '0' || c > '9' || parsedLength > (Long.MAX_VALUE - (c - '0')) / 10) reject(400);
                    parsedLength = parsedLength * 10 + c - '0';
                }
            } else if (name.equalsIgnoreCase("transfer-encoding")) {
                if (hasTransfer) reject(400);
                hasTransfer = true;
                transfer = value;
            }
        }
        if (hasTransfer && hasLength) reject(400);
        if (hasTransfer) reject("chunked".equalsIgnoreCase(transfer) ? 501 : 400);
        header = candidate;
        headerPosition = 0;
        bodyRemaining = parsedLength;
        return true;
    }

    private static boolean validRequestLine(String line) {
        int first = line.indexOf(' '), second = first < 0 ? -1 : line.indexOf(' ', first + 1);
        if (first <= 0 || second <= first + 1 || line.indexOf(' ', second + 1) >= 0) return false;
        for (int i=0; i<first; i++) if (!token(line.charAt(i))) return false;
        for (int i=first+1; i<second; i++) {
            char c=line.charAt(i); if (c<=32 || c>=127) return false;
        }
        String version=line.substring(second+1);
        return "HTTP/1.1".equals(version) || "HTTP/1.0".equals(version);
    }

    private static boolean token(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private void reject(int status) throws IOException {
        closed = true;
        String reason = status == 501 ? "Not Implemented" : "Bad Request";
        byte[] message = ("HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        response.write(message);
        response.flush();
        throw new IOException("Invalid HTTP request framing");
    }

    /** Visible for tests: header budgets still scheduled (must return to zero once headers finish). */
    static int pendingHeaderDeadlines() { return HEADER_DEADLINES.getQueue().size(); }

    @Override public int available() { return 0; }
    @Override public void close() throws IOException { closed = true; source.close(); }
}
