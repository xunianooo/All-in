package com.gaeul.allin;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Process-wide ingress allocation budget. Acquisition happens before body buffers. */
final class IngressMemoryBudget {
    static final long MAX_BYTES = Math.max(8L * 1024 * 1024,
            Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4));
    // Exact input buffer + UTF-8 decoder chars + final String, with allocation headroom.
    static long allocationBytes(long bodyBytes) { return bodyBytes * 6L + 8192L; }
    private static final Semaphore READERS = new Semaphore(4);
    private static final AtomicLong USED = new AtomicLong();
    private IngressMemoryBudget() {}
    static Lease acquire(long bytes) {
        if (bytes <= 0 || bytes > MAX_BYTES || !READERS.tryAcquire()) return null;
        while (true) {
            long old = USED.get();
            if (bytes > MAX_BYTES - old) { READERS.release(); return null; }
            if (USED.compareAndSet(old, old + bytes)) return new Lease(bytes);
        }
    }
    static long usedBytes() { return USED.get(); }
    static final class Lease implements AutoCloseable {
        private final long bytes;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean(true);
        Lease(long bytes) { this.bytes = bytes; }
        void readComplete() { if (reading.compareAndSet(true, false)) READERS.release(); }
        public void close() {
            readComplete();
            if (closed.compareAndSet(false, true)) USED.addAndGet(-bytes);
        }
    }
}
