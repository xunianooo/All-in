package com.gaeul.allin;

public class RecoveryPolicyTest {
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RecoveryPolicy p = new RecoveryPolicy();
        p.probeFailed(); p.probeFailed();
        expect(!p.mayRecover(1000), "two failures cannot recover");
        p.probeSucceeded(); p.probeFailed(); p.probeFailed();
        expect(!p.mayRecover(1000), "success resets failures");
        p.probeFailed();
        expect(p.mayRecover(1000), "third consecutive failure permits recovery");
        // A busy gate does not call recoveryStarted: it must remain eligible when idle.
        expect(p.mayRecover(2000), "busy gate must not start cooldown");
        p.recoveryStarted(2000);
        expect(!p.mayRecover(121999), "cooldown enforced");
        expect(p.mayRecover(122000), "cooldown expires");
        long now = 500000;
        for (long delay : new long[] {2000, 10000, 30000, 120000, 120000}) {
            expect(p.mayStart(now), "start initially eligible");
            expect(p.startFailed(now) == delay, "failure backoff");
            expect(!p.mayStart(now + delay - 1), "early retry blocked");
            now += delay;
        }
        p.startSucceeded();
        expect(p.mayStart(0), "success clears backoff");
        expect(p.startFailed(0) == 2000, "success resets failure count");
        System.out.println("RecoveryPolicyTest passed");
    }
}
