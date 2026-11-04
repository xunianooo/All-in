package com.gaeul.allin;

/** Admission and recovery share one lock so an idle observation cannot race a new stream. */
final class ForwardRequestGate {
    private int active;
    private boolean draining;
    synchronized boolean enter() {
        if (draining) return false;
        active++;
        return true;
    }
    synchronized void leave() { if (active > 0) active--; }
    synchronized int active() { return active; }
    synchronized boolean beginRecovery() {
        if (draining || active != 0) return false;
        draining = true;
        return true;
    }
}
