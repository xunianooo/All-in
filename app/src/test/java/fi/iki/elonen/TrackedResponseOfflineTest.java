package fi.iki.elonen;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public final class TrackedResponseOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static int bodyOffset(byte[] wire) {
        for (int i = 0; i + 3 < wire.length; i++) {
            if (wire[i] == '\r' && wire[i + 1] == '\n' && wire[i + 2] == '\r' && wire[i + 3] == '\n') return i + 4;
        }
        throw new AssertionError("HTTP header terminator missing");
    }

    static byte[] unchunk(byte[] wire, int offset) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = offset;
        while (true) {
            int end = pos;
            while (end + 1 < wire.length && !(wire[end] == '\r' && wire[end + 1] == '\n')) end++;
            check(end + 1 < wire.length, "chunk size line complete");
            String line = new String(wire, pos, end - pos, "US-ASCII");
            int extension = line.indexOf(';');
            int size = Integer.parseInt(extension < 0 ? line : line.substring(0, extension), 16);
            pos = end + 2;
            check(size >= 0 && size <= wire.length - pos - 2, "chunk fits in wire");
            if (size == 0) {
                check(wire[pos] == '\r' && wire[pos + 1] == '\n' && pos + 2 == wire.length,
                        "complete final chunk and no trailing bytes");
                return body.toByteArray();
            }
            body.write(wire, pos, size);
            pos += size;
            check(wire[pos] == '\r' && wire[pos + 1] == '\n', "chunk data terminator");
            pos += 2;
        }
    }

    static final class CompletionBody extends ByteArrayInputStream implements AllInTrackedResponse.WriteCompletion {
        final AtomicInteger gate;
        int callbacks;
        int closes;
        boolean eof;
        boolean result;

        CompletionBody(AtomicInteger gate) throws IOException {
            super("completion body".getBytes("UTF-8"));
            this.gate = gate;
        }
        @Override public synchronized int read(byte[] b, int off, int len) {
            int n = super.read(b, off, len);
            if (n == -1) eof = true;
            return n;
        }
        @Override public synchronized int read() {
            int n = super.read();
            if (n == -1) eof = true;
            return n;
        }
        @Override public void onWriteComplete(boolean success) {
            check(gate.get() == 1, "completion callback holds gate");
            check(eof, "body fully read before callback");
            callbacks++;
            result = success;
        }
        @Override public void close() throws IOException {
            if (closes == 0) check(gate.get() == 1, "body closes before gate release");
            closes++;
            super.close();
        }
    }

    static void checkWriteCompletion(final String failure) throws Exception {
        AtomicInteger gate = new AtomicInteger(1);
        CompletionBody body = new CompletionBody(gate);
        final boolean[] finalChunkAttempted = {false};
        final boolean[] finalFlushAttempted = {false};
        AllInTrackedResponse response = new AllInTrackedResponse(NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK, "text/plain", body), () -> {
                    check(body.callbacks == 1, "callback precedes gate release");
                    check(body.closes > 0, "body closes before gate release");
                    gate.decrementAndGet();
                });
        response.setRequestMethod(NanoHTTPD.Method.GET);
        response.setKeepAlive(false);
        response.setGzipEncoding(false);
        OutputStream sink = new OutputStream() {
            @Override public void write(int b) throws IOException { }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                byte[] terminator = { '0', '\r', '\n', '\r', '\n' };
                if (body.eof && len == terminator.length &&
                        Arrays.equals(Arrays.copyOfRange(b, off, off + len), terminator)) {
                    finalChunkAttempted[0] = true;
                    if ("chunk".equals(failure)) throw new IOException("injected final chunk failure");
                }
            }
            @Override public void flush() throws IOException {
                if (finalChunkAttempted[0]) {
                    finalFlushAttempted[0] = true;
                    if ("flush".equals(failure)) throw new IOException("injected final flush failure");
                }
            }
        };
        response.send(sink);
        check(body.eof, "body reached EOF");
        check(finalChunkAttempted[0], "final chunk attempted after body read");
        check("chunk".equals(failure) || finalFlushAttempted[0], "final flush attempted after final chunk");
        check(body.callbacks == 1, "completion callback exactly once");
        check(body.result == "none".equals(failure), "completion reports write outcome");
        check(body.closes > 0 && gate.get() == 0, "body closed and gate released");
        response.close();
        check(body.callbacks == 1 && gate.get() == 0, "close does not repeat callback or release");
    }

    public static void main(String[] args) throws Exception {
        final AtomicInteger active = new AtomicInteger(1);
        NanoHTTPD.Response source = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,
                "text/event-stream", new ByteArrayInputStream("data: hi\n\n".getBytes("UTF-8")));
        AllInTrackedResponse response = new AllInTrackedResponse(source, () -> active.decrementAndGet());
        response.setRequestMethod(NanoHTTPD.Method.GET);
        response.setKeepAlive(false);
        response.setGzipEncoding(false);
        ByteArrayOutputStream sink = new ByteArrayOutputStream() {
            @Override public void flush() { check(active.get() == 1, "held through final framing and flush"); }
        };
        response.send(sink);
        String wire = sink.toString("UTF-8");
        check(wire.contains("Transfer-Encoding: chunked") && wire.endsWith("0\r\n\r\n"), "complete HTTP chunk terminator");
        check(active.get() == 0, "send completion releases");
        response.close(); response.close(); check(active.get() == 0, "close idempotent release");

        byte[] fixedBody = "fixed response: 你好".getBytes("UTF-8");
        AtomicInteger fixedActive = new AtomicInteger(1);
        AllInTrackedResponse fixed = new AllInTrackedResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/plain; charset=UTF-8", new ByteArrayInputStream(fixedBody), fixedBody.length),
                () -> fixedActive.decrementAndGet());
        fixed.setRequestMethod(NanoHTTPD.Method.GET);
        fixed.setKeepAlive(false);
        fixed.setGzipEncoding(false);
        ByteArrayOutputStream fixedSink = new ByteArrayOutputStream() {
            @Override public void flush() { check(fixedActive.get() == 1, "fixed response held through flush"); }
        };
        fixed.send(fixedSink);
        byte[] fixedWire = fixedSink.toByteArray();
        int fixedOffset = bodyOffset(fixedWire);
        String fixedHeaders = new String(fixedWire, 0, fixedOffset, "US-ASCII");
        check(fixedHeaders.contains("Content-Length: " + fixedBody.length + "\r\n"), "fixed Content-Length is exact");
        check(Arrays.equals(fixedBody, Arrays.copyOfRange(fixedWire, fixedOffset, fixedWire.length)), "fixed body is exact");
        check(fixedActive.get() == 0, "fixed send releases");

        byte[] gzipBody = "compressed response: 你好\ndata: hello\n\n".getBytes("UTF-8");
        AtomicInteger gzipActive = new AtomicInteger(1);
        AtomicInteger flushes = new AtomicInteger();
        final boolean[] lastOperationWasFlush = {false};
        ByteArrayOutputStream gzipSink = new ByteArrayOutputStream() {
            @Override public synchronized void write(int b) { lastOperationWasFlush[0] = false; super.write(b); }
            @Override public synchronized void write(byte[] b, int off, int len) { lastOperationWasFlush[0] = false; super.write(b, off, len); }
            @Override public void flush() {
                check(gzipActive.get() == 1, "gzip held during flush");
                flushes.incrementAndGet();
                lastOperationWasFlush[0] = true;
            }
        };
        AllInTrackedResponse gzip = new AllInTrackedResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/plain; charset=UTF-8", new ByteArrayInputStream(gzipBody), gzipBody.length),
                () -> {
                    check(flushes.get() > 0 && lastOperationWasFlush[0], "gzip completion follows final flush");
                    gzipActive.decrementAndGet();
                });
        gzip.setRequestMethod(NanoHTTPD.Method.GET);
        gzip.setKeepAlive(false);
        gzip.setGzipEncoding(true);
        gzip.send(gzipSink);
        byte[] gzipWire = gzipSink.toByteArray();
        int gzipOffset = bodyOffset(gzipWire);
        String gzipHeaders = new String(gzipWire, 0, gzipOffset, "US-ASCII");
        check(gzipHeaders.contains("Content-Encoding: gzip\r\n"), "gzip content encoding");
        check(gzipHeaders.contains("Transfer-Encoding: chunked\r\n"), "gzip is chunked");
        GZIPInputStream decoded = new GZIPInputStream(new ByteArrayInputStream(unchunk(gzipWire, gzipOffset)));
        ByteArrayOutputStream decodedBody = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int count;
        while ((count = decoded.read(buffer)) != -1) decodedBody.write(buffer, 0, count);
        decoded.close();
        check(Arrays.equals(gzipBody, decodedBody.toByteArray()), "gzip decoded body is exact");
        check(gzipActive.get() == 0, "gzip send releases");

        AtomicInteger failed = new AtomicInteger(1);
        AllInTrackedResponse broken = new AllInTrackedResponse(NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,
                "text/event-stream", new InputStream() { public int read() throws IOException { throw new IOException("injected"); } }), () -> failed.decrementAndGet());
        broken.setRequestMethod(NanoHTTPD.Method.GET);
        broken.send(new ByteArrayOutputStream());
        check(failed.get() == 0, "failed send releases");
        broken.close(); check(failed.get() == 0, "failed send close idempotent");

        AtomicInteger disconnectedActive = new AtomicInteger(1);
        AtomicInteger upstreamClosed = new AtomicInteger();
        AtomicInteger writeAttempts = new AtomicInteger();
        InputStream upstream = new ByteArrayInputStream("upstream data".getBytes("UTF-8")) {
            @Override public void close() throws IOException { upstreamClosed.incrementAndGet(); super.close(); }
        };
        AllInTrackedResponse disconnected = new AllInTrackedResponse(NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK, "text/plain", upstream), () -> disconnectedActive.decrementAndGet());
        disconnected.setRequestMethod(NanoHTTPD.Method.GET);
        disconnected.send(new OutputStream() {
            @Override public void write(int b) throws IOException {
                writeAttempts.incrementAndGet();
                throw new IOException("client disconnected");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                writeAttempts.incrementAndGet();
                throw new IOException("client disconnected");
            }
        });
        check(writeAttempts.get() > 0, "client write failure was exercised");
        check(disconnectedActive.get() == 0, "client write failure releases gate");
        check(upstreamClosed.get() >= 1, "client write failure closes upstream");
        disconnected.close();
        check(disconnectedActive.get() == 0, "disconnect close idempotent");
        checkWriteCompletion("none");
        checkWriteCompletion("chunk");
        checkWriteCompletion("flush");
        System.out.println("TrackedResponseOfflineTest OK");
    }
}
