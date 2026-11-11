package fi.iki.elonen;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthetic coverage for the AllInRawHeaderInputStream absolute header budget.
 *
 * <p>Covers: slow-drip headers aborted by the deadline instead of by the 8 KiB size guard, a
 * negative control with a generous budget, a long streaming (SSE-shaped) response that must outlive
 * the budget, keep-alive reuse after a pause, connection availability after an abort, and the
 * scheduler/handler-thread bookkeeping.</p>
 *
 * <p>It also covers the per-request deadline token directly, without sockets: a deadline task that
 * lost the race for a finished request must not close the stream under the next keep-alive request,
 * a live deadline must still abort only its own header, and the "header completed"/"budget expired"
 * claim must be exclusive over many races. Those three drive a scripted {@link BlockingStream} so the
 * interleaving is chosen by the test instead of by timing luck. The socket tests are loopback
 * fixtures, not a substitute for device measurements or a real client.</p>
 */
public final class AllInRawHeaderDeadlineTest {
    /** Streaming body: one chunk per interval, so a whole response outlives the header budget. */
    static final class SlowStream extends InputStream {
        private final int chunks;
        private final long pauseMillis;
        private int index;
        private int offset;
        private byte[] current = new byte[0];

        SlowStream(int chunks, long pauseMillis) { this.chunks = chunks; this.pauseMillis = pauseMillis; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (current != null && this.offset < current.length) {
                int count = Math.min(length, current.length - this.offset);
                System.arraycopy(current, this.offset, bytes, offset, count);
                this.offset += count;
                return count;
            }
            if (index >= chunks) return -1;
            if (index > 0) {
                try { Thread.sleep(pauseMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return -1; }
            }
            current = ("data: chunk-" + index + "\n\n").getBytes(StandardCharsets.UTF_8);
            this.offset = 0;
            index++;
            return read(bytes, offset, length);
        }
    }

    /**
     * Scripted byte source: {@link #feed} appends bytes, reads return whatever is pending and block
     * while nothing is pending. That lets a test hold one header read open, decide exactly when the
     * deadline task runs, and observe whether the guard closed the stream.
     */
    static final class BlockingStream extends InputStream {
        private final Object lock = new Object();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final long nanosPerByte;
        private boolean closed;

        BlockingStream() { this(0L); }

        /** {@code nanosPerByte} widens the window between "budget armed" and "header finished". */
        BlockingStream(long nanosPerByte) { this.nanosPerByte = nanosPerByte; }

        void feed(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            synchronized (lock) {
                pending.write(bytes, 0, bytes.length);
                lock.notifyAll();
            }
        }

        boolean wasClosed() { synchronized (lock) { return closed; } }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            synchronized (lock) {
                while (pending.size() == 0 && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while waiting for scripted bytes");
                    }
                }
                if (closed) throw new IOException("scripted stream closed");
                if (pending.size() == 0) return -1;
                byte[] scripted = pending.toByteArray();
                int count = Math.min(length, scripted.length);
                System.arraycopy(scripted, 0, bytes, offset, count);
                pending.reset();
                pending.write(scripted, count, scripted.length - count);
                if (nanosPerByte > 0L) {
                    try { Thread.sleep(nanosPerByte / 1000000L, (int) (nanosPerByte % 1000000L)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted between scripted bytes");
                    }
                }
                return count;
            }
        }

        @Override public void close() {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    /** One background {@code read} of a header, so a test can expire a budget while it is in flight. */
    static final class HeaderRead {
        final Thread thread;
        volatile String header;
        volatile Throwable failure;

        HeaderRead(AllInRawHeaderInputStream guard) {
            thread = new Thread(() -> {
                byte[] sink = new byte[512];
                try {
                    int count = guard.read(sink, 0, sink.length);
                    header = count < 0 ? null : new String(sink, 0, count, StandardCharsets.ISO_8859_1);
                } catch (Throwable thrown) {
                    failure = thrown;
                }
            }, "header-read");
            thread.setDaemon(true);
            thread.start();
        }

        HeaderRead await() throws Exception {
            thread.join(6000L);
            if (thread.isAlive()) throw new AssertionError("header read never returned");
            return this;
        }
    }

    static long awaitArmedGeneration(AllInRawHeaderInputStream guard, Thread owner) throws Exception {
        long limit = System.nanoTime() + 5000000000L;
        while (System.nanoTime() < limit) {
            long generation = guard.armedHeaderGeneration();
            if (generation != 0L) return generation;
            if (!owner.isAlive()) break;
            Thread.sleep(1L);
        }
        throw new AssertionError("header budget was never armed for the read in flight");
    }

    static final class Server extends NanoHTTPD {
        final long deadline;
        final AtomicInteger served = new AtomicInteger();

        Server(long deadline) throws IOException {
            super("127.0.0.1", 0);
            this.deadline = deadline;
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        }

        @Override protected ClientHandler createClientHandler(Socket socket, InputStream input) {
            try {
                InputStream guarded = deadline <= 0
                        ? new AllInRawHeaderInputStream(input, socket.getOutputStream())
                        : new AllInRawHeaderInputStream(input, socket.getOutputStream(), deadline);
                return super.createClientHandler(socket, guarded);
            } catch (IOException failure) { throw new RuntimeException(failure); }
        }

        @Override public Response serve(IHTTPSession session) {
            served.incrementAndGet();
            if ("/stream".equals(session.getUri()))
                return newChunkedResponse(Response.Status.OK, "text/event-stream", new SlowStream(6, 250));
            try {
                long remaining = Long.parseLong(session.getHeaders().getOrDefault("content-length", "0"));
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[512];
                while (remaining > 0) {
                    int count = session.getInputStream().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    body.write(buffer, 0, count);
                    remaining -= count;
                }
                return newFixedLengthResponse(new String(body.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception failure) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", failure.toString());
            }
        }
    }

    static Socket connect(Server server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getListeningPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(4000);
        return socket;
    }

    static Thread drip(Socket socket, AtomicInteger sent) {
        Thread thread = new Thread(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                while (!Thread.currentThread().isInterrupted()) {
                    out.write('a');
                    out.flush();
                    sent.incrementAndGet();
                    Thread.sleep(30L);
                }
            } catch (Exception stopped) { }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Returns the elapsed millis when the server closed the connection, or -1 if it stayed open. */
    static long awaitClose(Socket socket, long maxMillis) throws IOException {
        socket.setSoTimeout(50);
        InputStream in = socket.getInputStream();
        long start = System.nanoTime();
        while (true) {
            long elapsed = (System.nanoTime() - start) / 1000000L;
            if (elapsed > maxMillis) return -1;
            try {
                int value = in.read();
                if (value < 0) return elapsed;
            } catch (SocketTimeoutException waiting) { }
            catch (SocketException closed) { return elapsed; }
        }
    }

    static byte[] readAll(Socket socket) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = socket.getInputStream().read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        byte[] window = new byte[4];
        while (true) {
            int value = in.read();
            if (value < 0) throw new IOException("unexpected eof before response header");
            head.write(value);
            System.arraycopy(window, 1, window, 0, 3);
            window[3] = (byte) value;
            if (window[0] == '\r' && window[1] == '\n' && window[2] == '\r' && window[3] == '\n') break;
        }
        String wire = head.toString("ISO-8859-1");
        if (!wire.startsWith("HTTP/1.1 ")) throw new IOException("malformed response: " + wire);
        int status = Integer.parseInt(wire.substring(9, 12));
        int length = 0;
        for (String line : wire.split("\r\n"))
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(body, read, length - read);
            if (count < 0) throw new IOException("short body");
            read += count;
        }
        return status + " " + new String(body, StandardCharsets.UTF_8);
    }

    static String post(int port, String body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            return readResponse(socket.getInputStream());
        }
    }

    static void verifyBudgetInvariant() {
        if (AllInRawHeaderInputStream.HEADER_DEADLINE_MILLIS != NanoHTTPD.SOCKET_READ_TIMEOUT * 2L)
            throw new AssertionError("header budget drifted from the socket read timeout: "
                    + AllInRawHeaderInputStream.HEADER_DEADLINE_MILLIS);
        if (AllInRawHeaderInputStream.HEADER_DEADLINE_MILLIS <= NanoHTTPD.SOCKET_READ_TIMEOUT)
            throw new AssertionError("header budget must exceed the per-read socket timeout");
    }

    static void deadlineAbortsSlowHeader(Server server, long expectedMillis) throws Exception {
        try (Socket socket = connect(server)) {
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nX-Drip: ".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            AtomicInteger sent = new AtomicInteger();
            Thread dripper = drip(socket, sent);
            try {
                long elapsed = awaitClose(socket, expectedMillis + 6000L);
                if (elapsed < 0)
                    throw new AssertionError("header budget not enforced; dripped bytes=" + sent.get());
                if (sent.get() >= 8190)
                    throw new AssertionError("connection ended by the size guard, not the budget: " + sent.get());
                if (elapsed + 120L < expectedMillis)
                    throw new AssertionError("closed before the budget elapsed: " + elapsed + " ms, sent=" + sent.get());
                System.out.println("slow header aborted after " + elapsed + " ms (budget " + expectedMillis
                        + " ms), dripped bytes=" + sent.get());
            } finally { dripper.interrupt(); }
        }
    }

    static void stillServesAfterAbort(Server server) throws Exception {
        String response = post(server.getListeningPort(), "after-abort");
        if (!response.equals("200 after-abort"))
            throw new AssertionError("server unusable after a deadline abort: " + response);
    }

    static void fastHeaderAndBodyServed(Server server) throws Exception {
        String response = post(server.getListeningPort(), "你好，网关");
        if (!response.equals("200 你好，网关"))
            throw new AssertionError("fast request broken by the budget: " + response);
    }

    static void keepAliveAcrossPause(Server server) throws Exception {
        try (Socket socket = connect(server)) {
            socket.setSoTimeout(4000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\none".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = readResponse(in);
            if (!first.equals("200 one")) throw new AssertionError("first keep-alive request: " + first);
            Thread.sleep(120L);
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nConnection: close\r\n\r\ntwo".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String second = readResponse(in);
            if (!second.equals("200 two")) throw new AssertionError("reused keep-alive connection: " + second);
            System.out.println("keep-alive reuse across a pause still served both requests");
        }
    }

    static void streamingResponseOutlivesBudget(Server server, long budgetMillis) throws Exception {
        try (Socket socket = connect(server)) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(
                    "GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            long start = System.nanoTime();
            String wire = new String(readAll(socket), StandardCharsets.UTF_8);
            long elapsed = (System.nanoTime() - start) / 1000000L;
            for (int i = 0; i < 6; i++)
                if (!wire.contains("chunk-" + i)) throw new AssertionError("stream truncated at chunk-" + i
                        + " after " + elapsed + " ms: " + wire);
            if (!wire.contains("\r\n0\r\n\r\n")) throw new AssertionError("chunked terminator missing: " + wire);
            if (elapsed <= budgetMillis)
                throw new AssertionError("stream did not outlive the header budget: " + elapsed + " ms");
            System.out.println("streamed response ran " + elapsed + " ms past a " + budgetMillis + " ms header budget");
        }
    }

    static void noAbortWithGenerousBudget(Server server) throws Exception {
        try (Socket socket = connect(server)) {
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nX-Drip: ".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            AtomicInteger sent = new AtomicInteger();
            Thread dripper = drip(socket, sent);
            try {
                long elapsed = awaitClose(socket, 1200L);
                if (elapsed >= 0)
                    throw new AssertionError("connection closed before any budget expired: " + elapsed
                            + " ms, sent=" + sent.get());
                if (sent.get() < 10)
                    throw new AssertionError("slow-drip control did not run: sent=" + sent.get());
                System.out.println("generous budget control kept the connection open (dripped " + sent.get() + " bytes)");
            } finally { dripper.interrupt(); }
        }
    }

    static void bookkeepingDrains(Server server) throws Exception {
        long limit = System.nanoTime() + 12000000000L;
        while (System.nanoTime() < limit) {
            int pending = AllInRawHeaderInputStream.pendingHeaderDeadlines();
            int handlers = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.isAlive() && thread.getName().startsWith("NanoHttpd Request Processor")) handlers++;
            if (pending == 0 && handlers == 0) {
                System.out.println("deadline queue and handler threads drained to zero");
                return;
            }
            Thread.sleep(100L);
        }
        int pending = AllInRawHeaderInputStream.pendingHeaderDeadlines();
        int handlers = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().startsWith("NanoHttpd Request Processor")) handlers++;
        throw new AssertionError("leaked bookkeeping: pending deadlines=" + pending + ", handler threads=" + handlers);
    }

    /**
     * Regression for the keep-alive race: a deadline task of request N that lost its cancellation
     * race used to consult only a shared "header complete" flag, so it could close the stream while
     * request N+1's header was being read. With per-request tokens that task must be inert.
     */
    static void staleDeadlineFromFinishedRequestIsIgnored() throws Exception {
        BlockingStream stream = new BlockingStream();
        AllInRawHeaderInputStream guard = new AllInRawHeaderInputStream(
                stream, new ByteArrayOutputStream(), 60000L);
        stream.feed("POST /one HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n");
        HeaderRead first = new HeaderRead(guard);
        long firstGeneration = awaitArmedGeneration(guard, first.thread);
        stream.feed("\r\n");
        first.await();
        if (first.failure != null) throw new AssertionError("first header failed: " + first.failure);
        if (first.header == null || !first.header.startsWith("POST /one HTTP/1.1"))
            throw new AssertionError("first header not served: " + first.header);

        // Second keep-alive request on the same stream, held open in the middle of its header.
        HeaderRead second = new HeaderRead(guard);
        long secondGeneration = awaitArmedGeneration(guard, second.thread);
        if (secondGeneration == firstGeneration)
            throw new AssertionError("a retired generation token was reissued: " + secondGeneration);

        // The stale task of request one runs now: exactly the moment the old design closed the
        // connection out from under request two. That design only knew "a header is being read",
        // which is true for request two right now; the token knows this task belongs to request one.
        guard.headerDeadlineExpired(firstGeneration);
        if (guard.armedHeaderGeneration() != secondGeneration)
            throw new AssertionError("a retired task disturbed request 2's armed generation");
        if (stream.wasClosed())
            throw new AssertionError("a retired deadline closed the keep-alive connection");

        stream.feed("GET /two HTTP/1.1\r\nHost: localhost\r\n\r\n");
        second.await();
        if (second.failure != null)
            throw new AssertionError("second header killed by a stale deadline: " + second.failure);
        if (second.header == null || !second.header.startsWith("GET /two HTTP/1.1"))
            throw new AssertionError("second header not served: " + second.header);
        if (stream.wasClosed()) throw new AssertionError("stream closed although both headers completed");
        System.out.println("stale deadline of request 1 left request 2's header and connection intact");
    }

    /** The token must not make the guard toothless: a live deadline still aborts its own header. */
    static void liveDeadlineStillAbortsItsOwnHeader() throws Exception {
        BlockingStream stream = new BlockingStream();
        AllInRawHeaderInputStream guard = new AllInRawHeaderInputStream(
                stream, new ByteArrayOutputStream(), 60000L);
        stream.feed("POST /slow HTTP/1.1\r\nHost: local");
        HeaderRead read = new HeaderRead(guard);
        long generation = awaitArmedGeneration(guard, read.thread);
        guard.headerDeadlineExpired(generation);
        read.await();
        if (!(read.failure instanceof SocketTimeoutException))
            throw new AssertionError("live deadline did not abort its own header: " + read.failure);
        if (!stream.wasClosed()) throw new AssertionError("live deadline did not close the stream");
        if (guard.read(new byte[8], 0, 8) >= 0) throw new AssertionError("guard still readable after abort");
        if (guard.armedHeaderGeneration() != 0L)
            throw new AssertionError("consumed generation left armed: " + guard.armedHeaderGeneration());
        System.out.println("live deadline aborted its own header: " + read.failure.getMessage());
    }

    /**
     * Atomicity: the "header completed" and "budget expired" claims are exclusive. First with both
     * interleavings picked by the test (header first, then the late task for the same generation;
     * deadline fired while the header is still in flight), then under a real race where a deadline
     * thread fires while the header is in flight. Whatever the schedule, a served header never comes
     * with a closed stream and an aborted header always leaves the stream closed.
     */
    static void deadlineClaimIsExclusiveWithCompletion() throws Exception {
        String header = "POST /race HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
        int served = 0, aborted = 0;
        for (int round = 0; round < 50; round++) {
            // 200 us per byte keeps the header in flight long enough for the poll to see the token.
            BlockingStream stream = new BlockingStream(200000L);
            AllInRawHeaderInputStream guard = new AllInRawHeaderInputStream(
                    stream, new ByteArrayOutputStream(), 60000L);
            stream.feed(header);
            HeaderRead read = new HeaderRead(guard);
            long generation = awaitArmedGeneration(guard, read.thread);
            if ((round & 1) == 0) {
                // Header wins; the late task for that same generation must be inert.
                read.await();
                guard.headerDeadlineExpired(generation);
                if (read.failure != null)
                    throw new AssertionError("header lost a claim it had already won: " + read.failure);
                if (read.header == null || !read.header.startsWith("POST /race HTTP/1.1"))
                    throw new AssertionError("header mangled: " + read.header);
                if (stream.wasClosed())
                    throw new AssertionError("a retired generation still closed the stream");
                served++;
            } else {
                // Deadline wins while the header is still in flight.
                guard.headerDeadlineExpired(generation);
                read.await();
                if (!(read.failure instanceof SocketTimeoutException))
                    throw new AssertionError("deadline claim did not abort the header: " + read.failure);
                if (!stream.wasClosed())
                    throw new AssertionError("deadline claimed the generation without closing the stream");
                aborted++;
            }
        }

        int raced = 0, racedServed = 0, racedAborted = 0;
        for (int round = 0; round < 50; round++) {
            BlockingStream stream = new BlockingStream(200000L);
            AllInRawHeaderInputStream guard = new AllInRawHeaderInputStream(
                    stream, new ByteArrayOutputStream(), 60000L);
            stream.feed(header);
            HeaderRead read = new HeaderRead(guard);
            long generation = awaitArmedGeneration(guard, read.thread);
            int jitter = round % 16;
            Thread racer = new Thread(() -> {
                try { Thread.sleep(jitter); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                guard.headerDeadlineExpired(generation);
            }, "deadline-racer");
            racer.setDaemon(true);
            racer.start();
            racer.join(4000L);
            if (racer.isAlive()) throw new AssertionError("deadline claim blocked on the reader");
            read.await();
            if (read.failure != null) {
                if (!(read.failure instanceof SocketTimeoutException))
                    throw new AssertionError("unexpected header failure: " + read.failure);
                if (!stream.wasClosed()) throw new AssertionError("header aborted without closing the stream");
                racedAborted++;
            } else {
                if (read.header == null) throw new AssertionError("header read returned EOF without an abort");
                if (!read.header.startsWith("POST /race HTTP/1.1"))
                    throw new AssertionError("header mangled by the race: " + read.header);
                if (stream.wasClosed()) throw new AssertionError("served header on an aborted stream");
                racedServed++;
            }
            raced++;
        }
        if (served != 25 || aborted != 25)
            throw new AssertionError("interleaving coverage lost: served=" + served + ", aborted=" + aborted);
        System.out.println("claim interleavings: served=" + served + ", aborted=" + aborted
                + "; concurrent races=" + raced + " (served=" + racedServed + ", aborted=" + racedAborted
                + "), never both outcomes at once");
    }

    public static void main(String[] args) throws Exception {
        verifyBudgetInvariant();
        staleDeadlineFromFinishedRequestIsIgnored();
        liveDeadlineStillAbortsItsOwnHeader();
        deadlineClaimIsExclusiveWithCompletion();
        Server tight = new Server(400L);
        try {
            deadlineAbortsSlowHeader(tight, 400L);
            stillServesAfterAbort(tight);
            fastHeaderAndBodyServed(tight);
            keepAliveAcrossPause(tight);
            streamingResponseOutlivesBudget(tight, 400L);
        } finally { tight.stop(); }
        Server generous = new Server(60000L);
        try { noAbortWithGenerousBudget(generous); } finally { generous.stop(); }
        bookkeepingDrains(tight);
        Server production = new Server(0L);
        try { deadlineAbortsSlowHeader(production, AllInRawHeaderInputStream.HEADER_DEADLINE_MILLIS); }
        finally { production.stop(); }
        System.out.println("header deadline checks passed");
    }
}
