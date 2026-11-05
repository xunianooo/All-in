package fi.iki.elonen;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HeldResponseOfflineTest {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    static class Body extends ByteArrayInputStream implements AllInTrackedResponse.WriteCompletion {
        int starts, finishes, closes; boolean success;
        Body(String s) throws Exception { super(s.getBytes("UTF-8")); }
        public void onWriteStart() { starts++; }
        public void onWriteComplete(boolean ok) { finishes++; success = ok; }
        public void close() { closes++; }
    }
    static AllInHeldResponse held(Callable<NanoHTTPD.Response> c, ExecutorService e, long first, AtomicInteger cancel, AtomicInteger business, String protocol) {
        AllInHeldResponse r = new AllInHeldResponse(c, e, first, 20, cancel::incrementAndGet, () -> false, protocol, business::incrementAndGet);
        r.setRequestMethod(NanoHTTPD.Method.GET); r.setKeepAlive(false); return r;
    }
    static NanoHTTPD.Response response(NanoHTTPD.Response.IStatus status, Body body) {
        NanoHTTPD.Response r = NanoHTTPD.newChunkedResponse(status, "text/event-stream", body); r.addHeader("X-Test", "retained"); return r;
    }
    static void partialEofRegression() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Body body = new Body("data: incomplete"); AtomicInteger cancel = new AtomicInteger();
            AllInHeldResponse response = held(() -> response(NanoHTTPD.Response.Status.OK, body), executor, 1000, cancel, new AtomicInteger(), "chat");
            ByteArrayOutputStream sink = new ByteArrayOutputStream(); response.send(sink);
            String wire = sink.toString("UTF-8");
            check(body.finishes == 1 && !body.success && cancel.get() == 1, "partial SSE EOF must fail delivery");
            check(wire.contains("data: incomplete") && !wire.contains("[DONE]") && !wire.contains("event: error") && !wire.contains("upstream_error") && !wire.endsWith("0\r\n\r\n"), "partial EOF must abort without synthetic event or final chunk");
        } finally { executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }
    static void queueCancellationRegression() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            executor.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
            check(entered.await(1, TimeUnit.SECONDS), "queue blocker started");
            AtomicInteger ran = new AtomicInteger();
            AllInHeldResponse queued = held(() -> { ran.incrementAndGet(); return null; }, executor, 10, new AtomicInteger(), new AtomicInteger(), "chat");
            queued.signalCancellation();
            check(executor.getQueue().isEmpty(), "cancelled future must free queue immediately");
            Future<?> replacement = executor.submit(() -> {});
            queued.close(); release.countDown(); replacement.get(2, TimeUnit.SECONDS);
            check(ran.get() == 0, "cancelled queued supplier never ran");
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }
    static void genericExecutorCancellationRegression() throws Exception {
        ExecutorService underlying = Executors.newSingleThreadExecutor();
        ExecutorService generic = Executors.unconfigurableExecutorService(underlying);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            generic.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
            check(entered.await(1, TimeUnit.SECONDS), "generic blocker started");
            AtomicInteger ran = new AtomicInteger(), cancels = new AtomicInteger();
            AllInHeldResponse queued = held(() -> { ran.incrementAndGet(); return null; }, generic, 10, cancels, new AtomicInteger(), "chat");
            queued.close(); release.countDown();
            generic.submit(() -> {}).get(2, TimeUnit.SECONDS);
            check(ran.get() == 0 && cancels.get() == 1, "generic executor cancellation remains supported");
        } finally { release.countDown(); generic.shutdownNow(); generic.awaitTermination(2, TimeUnit.SECONDS); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("partial-eof")) { partialEofRegression(); return; }
        if (args.length > 0 && args[0].equals("queue-cancel")) { queueCancellationRegression(); return; }
        partialEofRegression(); queueCancellationRegression(); genericExecutorCancellationRegression();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger cancel = new AtomicInteger(), business = new AtomicInteger(); Body body = new Body("data: ok\n\n");
            AllInHeldResponse early = held(() -> response(NanoHTTPD.Response.Status.BAD_REQUEST, body), executor, 1000, cancel, business, "chat");
            early.addHeader("Access-Control-Allow-Origin", "https://example.test");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); early.send(out); String wire = out.toString("UTF-8");
            check(wire.contains("400 Bad Request") && wire.contains("X-Test: retained") && wire.contains("Access-Control-Allow-Origin: https://example.test") && wire.contains("data: ok"), "early status headers body");
            check(body.starts == 1 && body.finishes == 1 && body.success && body.closes == 1 && business.get() > 0 && cancel.get() == 0, "early delivery");
            early.close(); check(body.finishes == 1, "idempotent close");
            for (String protocol : new String[]{"chat", "anthropic", "responses"}) {
                CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
                cancel.set(0); business.set(0);
                AllInHeldResponse late = held(() -> { started.countDown(); release.await(2, TimeUnit.SECONDS); throw new IOException("failed"); }, executor, 10, cancel, business, protocol);
                late.addHeader("Access-Control-Allow-Origin", "https://example.test");
                ByteArrayOutputStream sink = new ByteArrayOutputStream() {
                    public void flush() { if (toString().contains("heartbeat")) release.countDown(); }
                };
                check(started.await(1, TimeUnit.SECONDS), "supplier started"); late.send(sink); wire = sink.toString("UTF-8");
                check(wire.contains("200 OK") && wire.contains(": heartbeat") && wire.contains("\"error\"") && !wire.contains("[DONE]"), "late failure " + protocol);
                check(wire.contains("Access-Control-Allow-Origin: https://example.test") && wire.contains("no-transform") && wire.contains("X-Accel-Buffering: no"), "late headers");
                if (protocol.equals("responses")) check(wire.contains("event: response.failed") && wire.contains("\"status\":\"failed\"") && wire.contains("\"id\":\"resp_"), "responses failure shape");
                check(business.get() == 0 && cancel.get() == 1, "no business for heartbeat " + protocol);
            }
            Body lateBody = new Body("data: delivered\n\n"); CountDownLatch release = new CountDownLatch(1);
            business.set(0); cancel.set(0);
            AllInHeldResponse lateOk = held(() -> { release.await(2, TimeUnit.SECONDS); return response(NanoHTTPD.Response.Status.OK, lateBody); }, executor, 5, cancel, business, "chat");
            ByteArrayOutputStream sink = new ByteArrayOutputStream() { public void flush() { if (toString().contains("heartbeat")) release.countDown(); } };
            lateOk.send(sink);
            check(sink.toString("UTF-8").contains("data: delivered") && lateBody.finishes == 1 && lateBody.success && business.get() > 0, "late success delivery");
            Body broken = new Body("data: broken\n\n"); cancel.set(0);
            AllInHeldResponse bad = held(() -> response(NanoHTTPD.Response.Status.OK, broken), executor, 1000, cancel, business, "chat");
            bad.send(new OutputStream() { int writes; public void write(int b) throws IOException { throw new IOException("broken"); } public void write(byte[] b,int o,int n) throws IOException { if (++writes > 1) throw new IOException("broken"); } });
            check(broken.finishes == 1 && !broken.success && cancel.get() == 1, "write failure delivery");
            CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1); Body abandoned = new Body("x"); cancel.set(0);
            AllInHeldResponse closed = held(() -> { entered.countDown(); while (true) { try { unblock.await(); break; } catch (InterruptedException ignored) {} } return response(NanoHTTPD.Response.Status.OK, abandoned); }, executor, 100, cancel, business, "chat");
            check(entered.await(1, TimeUnit.SECONDS), "entered"); closed.close(); closed.close(); unblock.countDown();
            for (int i=0; i<100 && abandoned.finishes == 0; i++) Thread.sleep(10);
            check(abandoned.finishes == 1 && !abandoned.success && abandoned.closes == 1 && cancel.get() == 1, "late disposal after unsent close");
            AtomicInteger budgetCancel = new AtomicInteger();
            CountDownLatch budgetEntered = new CountDownLatch(1); AtomicBoolean expired = new AtomicBoolean();
            AllInHeldResponse budget = new AllInHeldResponse(() -> { budgetEntered.countDown(); Thread.sleep(3000); return null; }, executor, 1000, 20, budgetCancel::incrementAndGet, expired::get, "chat", () -> {});
            check(budgetEntered.await(1, TimeUnit.SECONDS), "budget supplier entered"); expired.set(true);
            ByteArrayOutputStream budgetOut = new ByteArrayOutputStream(); budget.send(budgetOut); budget.close(); check(budgetCancel.get() == 1 && budgetOut.toString("UTF-8").contains("503 Service Unavailable"), "budget cancellation before heartbeat");
            AtomicBoolean lateExpired = new AtomicBoolean(); CountDownLatch lateEntered = new CountDownLatch(1);
            AtomicInteger lateBusiness = new AtomicInteger(), lateCancel = new AtomicInteger(), flushCount = new AtomicInteger();
            AllInHeldResponse deadlineLate = new AllInHeldResponse(() -> { lateEntered.countDown(); Thread.sleep(3000); return null; }, executor, 5, 20, lateCancel::incrementAndGet, lateExpired::get, "responses", lateBusiness::incrementAndGet);
            deadlineLate.setRequestMethod(NanoHTTPD.Method.GET);
            check(lateEntered.await(1, TimeUnit.SECONDS), "late deadline supplier started");
            ByteArrayOutputStream deadlineSink = new ByteArrayOutputStream() { public void flush() { flushCount.incrementAndGet(); if (toString().contains("heartbeat")) { check(lateBusiness.get() == 0, "heartbeat is not business"); lateExpired.set(true); } } };
            deadlineLate.send(deadlineSink);
            check(deadlineSink.toString("UTF-8").contains("event: response.failed") && flushCount.get() >= 2 && lateBusiness.get() == 0 && lateCancel.get() == 1, "deadline after heartbeat flushes failure");
            Body partial = new Body("short"); cancel.set(0);
            AllInHeldResponse shortFixed = held(() -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", partial, 20), executor, 1000, cancel, business, "chat");
            shortFixed.send(new ByteArrayOutputStream()); check(partial.finishes == 1 && !partial.success, "short fixed body failure");
            Body flushBody = new Body("data: final flush\n\n"); cancel.set(0);
            AllInHeldResponse flushFail = held(() -> response(NanoHTTPD.Response.Status.OK, flushBody), executor, 1000, cancel, business, "chat");
            AtomicBoolean finalFlushReached = new AtomicBoolean();
            flushFail.send(new ByteArrayOutputStream() { boolean finished; public synchronized void write(byte[] b,int o,int n) { super.write(b,o,n); if (n == 5 && b[o] == '0') finished = true; } public void flush() throws IOException { if (finished) { finalFlushReached.set(true); throw new IOException("final flush"); } } });
            check(finalFlushReached.get() && flushBody.finishes == 1 && !flushBody.success && cancel.get() == 1, "final flush failure");
            Body failedRead = new Body("unused") { @Override public synchronized int read(byte[] b, int o, int n) { throw new RuntimeException("read failed"); } };
            cancel.set(0);
            AllInHeldResponse readError = held(() -> response(NanoHTTPD.Response.Status.OK, failedRead), executor, 1000, cancel, business, "chat");
            readError.send(new ByteArrayOutputStream());
            check(failedRead.finishes == 1 && !failedRead.success && cancel.get() == 1, "read failure callback");
            CountDownLatch streamFirst = new CountDownLatch(1), streamRelease = new CountDownLatch(1); AtomicInteger stallBusiness = new AtomicInteger();
            InputStream stalled = new InputStream() {
                int stage;
                public int read() throws IOException { byte[] one = new byte[1]; int n = read(one,0,1); return n < 0 ? -1 : one[0] & 255; }
                public int read(byte[] b,int o,int n) throws IOException {
                    try {
                        if (stage++ == 0) { byte[] first = "data: first\n\n".getBytes("UTF-8"); System.arraycopy(first,0,b,o,first.length); streamFirst.countDown(); return first.length; }
                        if (stage == 2) { if (!streamRelease.await(2, TimeUnit.SECONDS)) throw new IOException("heartbeat absent"); byte[] second = "data: second\n\n".getBytes("UTF-8"); System.arraycopy(second,0,b,o,second.length); return second.length; }
                        return -1;
                    } catch (InterruptedException e) { throw new IOException(e); }
                }
            };
            AllInHeldResponse stallResponse = held(() -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", stalled), executor, 1000, new AtomicInteger(), stallBusiness, "chat");
            ByteArrayOutputStream stallSink = new ByteArrayOutputStream() { public void flush() { String text = toString(); if (text.contains("data: first") && text.contains(": heartbeat")) streamRelease.countDown(); } };
            stallResponse.send(stallSink); String stalledWire = stallSink.toString("UTF-8");
            check(streamFirst.getCount() == 0 && stalledWire.contains("data: first") && stalledWire.contains(": heartbeat") && stalledWire.contains("data: second") && stallBusiness.get() == 2, "body stall heartbeat and business writes");
            CountDownLatch partialStarted = new CountDownLatch(1), partialRelease = new CountDownLatch(1);
            InputStream partialEvent = new InputStream() {
                int stage;
                public int read() throws IOException { return -1; }
                public int read(byte[] b,int o,int n) throws IOException { try {
                    if (stage++ == 0) { byte[] v = "data: partial".getBytes("UTF-8"); System.arraycopy(v,0,b,o,v.length); partialStarted.countDown(); return v.length; }
                    if (stage == 2) { partialRelease.await(2,TimeUnit.SECONDS); byte[] v = "\n\n".getBytes("UTF-8"); System.arraycopy(v,0,b,o,v.length); return v.length; } return -1;
                } catch (InterruptedException e) { throw new IOException(e); } }
            };
            AllInHeldResponse partialStream = held(() -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,"text/event-stream",partialEvent), executor, 1000, new AtomicInteger(), new AtomicInteger(), "chat");
            ByteArrayOutputStream partialSink = new ByteArrayOutputStream(); Thread partialSender = new Thread(() -> partialStream.send(partialSink)); partialSender.start();
            check(partialStarted.await(1,TimeUnit.SECONDS), "partial started"); Thread.sleep(100); check(!partialSink.toString("UTF-8").contains("heartbeat"), "no heartbeat in partial event"); partialRelease.countDown(); partialSender.join(2000); check(!partialSender.isAlive(), "partial sender finished");
            CountDownLatch blockedSink = new CountDownLatch(1), unblockSink = new CountDownLatch(1); AtomicInteger produced = new AtomicInteger(), pressureCancel = new AtomicInteger();
            InputStream prolific = new InputStream() {
                public int read() { return -1; }
                public int read(byte[] b,int o,int n) { produced.incrementAndGet(); java.util.Arrays.fill(b,o,o+n,(byte)'x'); return n; }
            };
            AllInHeldResponse pressure = held(() -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,"text/event-stream",prolific), executor, 1000, pressureCancel, new AtomicInteger(), "chat");
            OutputStream slow = new OutputStream() { int writes; public void write(int b) {} public void write(byte[] b,int o,int n) throws IOException { if (++writes == 3) { blockedSink.countDown(); try { unblockSink.await(2,TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); } } } };
            Thread pressureSender = new Thread(() -> pressure.send(slow)); pressureSender.start();
            check(blockedSink.await(1,TimeUnit.SECONDS), "slow sink blocked"); Thread.sleep(150);
            check(produced.get() <= 11, "bounded producer queue " + produced.get()); pressure.close(); unblockSink.countDown(); pressureSender.join(2000);
            check(!pressureSender.isAlive() && pressureCancel.get() == 1, "cancel blocked producer");
            CountDownLatch signalEntered = new CountDownLatch(1); AtomicInteger signalCancel = new AtomicInteger();
            AllInHeldResponse signalledResponse = held(() -> { signalEntered.countDown(); Thread.sleep(3000); return null; }, executor, 1000, signalCancel, new AtomicInteger(), "chat");
            check(signalEntered.await(1,TimeUnit.SECONDS), "signal supplier entered"); signalledResponse.signalCancellation();
            check(signalCancel.get() == 0, "signal does not invoke callback");
            ByteArrayOutputStream signalledOut = new ByteArrayOutputStream(); signalledResponse.send(signalledOut);
            check(signalledOut.toString("UTF-8").contains("503 Service Unavailable") && signalCancel.get() == 1, "signal preserves response and final cleanup");
            CountDownLatch liveSignalEntered = new CountDownLatch(1); AtomicInteger liveCancel = new AtomicInteger();
            AllInHeldResponse liveSignal = held(() -> { liveSignalEntered.countDown(); Thread.sleep(3000); return null; }, executor, 5, liveCancel, new AtomicInteger(), "responses");
            ByteArrayOutputStream liveSink = new ByteArrayOutputStream() { boolean triggered; public void flush() { if (!triggered && toString().contains("heartbeat")) { triggered = true; liveSignal.signalCancellation(); check(liveCancel.get() == 0, "live signal no callback"); } } };
            check(liveSignalEntered.await(1,TimeUnit.SECONDS), "live supplier entered"); liveSignal.send(liveSink);
            check(liveSink.toString("UTF-8").contains("event: response.failed") && liveCancel.get() == 1, "live signal emits failure");
            ExecutorService single = Executors.newSingleThreadExecutor(); CountDownLatch occupied = new CountDownLatch(1), free = new CountDownLatch(1); AtomicInteger ran = new AtomicInteger(), queuedCancel = new AtomicInteger();
            single.submit(() -> { occupied.countDown(); try { free.await(); } catch (InterruptedException ignored) {} });
            check(occupied.await(1, TimeUnit.SECONDS), "executor occupied");
            AllInHeldResponse queued = held(() -> { ran.incrementAndGet(); return null; }, single, 10, queuedCancel, business, "chat");
            queued.signalCancellation(); check(queuedCancel.get() == 0, "queued signal no callback"); queued.close(); queued.close(); free.countDown(); single.shutdown(); check(single.awaitTermination(1, TimeUnit.SECONDS), "executor terminates");
            check(ran.get() == 0 && queuedCancel.get() == 1, "queued task cancelled without executing");
            ExecutorService rejected = Executors.newSingleThreadExecutor(); rejected.shutdown();
            try { held(() -> null, rejected, 1, new AtomicInteger(), new AtomicInteger(), "chat"); throw new AssertionError("rejection expected"); }
            catch (RejectedExecutionException expected) {}
            System.out.println("HeldResponseOfflineTest passed");
        } finally { executor.shutdownNow(); }
    }
}
