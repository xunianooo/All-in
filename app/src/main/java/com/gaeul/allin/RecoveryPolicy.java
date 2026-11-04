package com.gaeul.allin;

/** State is owned by the service's single worker; times use elapsed realtime. */
final class RecoveryPolicy {
    private int healthFailures, startFailures;
    private long lastRecovery = -1, nextStart;
    void probeSucceeded() { healthFailures = 0; }
    void probeFailed() { if (healthFailures < Integer.MAX_VALUE) healthFailures++; }
    int failures() { return healthFailures; }
    boolean mayRecover(long now) {
        return healthFailures >= 3 && (lastRecovery < 0 || now - lastRecovery >= 120000L);
    }
    void recoveryStarted(long now) { lastRecovery = now; }
    boolean mayStart(long now) { return now >= nextStart; }
    long startFailed(long now) {
        if (startFailures < 4) startFailures++;
        long delay = new long[] {2000L, 10000L, 30000L, 120000L}[startFailures - 1];
        nextStart = now + delay;
        return delay;
    }
    void startSucceeded() { startFailures = 0; nextStart = 0; }
}
