package com.gaeul.allin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Fixture for a damaged durable configuration: an authentic .aibk backup plus the correct
 * password must (a) preview, (b) preserve the corrupt bytes in an immutable rescue copy, and
 * (c) never replace anything when that preservation cannot be proven. No real backup, account,
 * gateway or config file is used; the backend here is a fault-injecting in-memory double.
 */
public class BackupCoordinatorDamagedConfigOfflineTest {
    interface Checked { void run() throws Exception; }
    static void fails(Checked c) throws Exception { try { c.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) { return; } throw new AssertionError("expected failure"); }
    static void failsWith(Class<?> type, Checked c) throws Exception {
        try { c.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) {
            if (!type.isInstance(expected)) throw new AssertionError("unexpected failure type " + expected.getClass().getName(), expected);
            return;
        }
        throw new AssertionError("expected failure");
    }
    static void check(boolean condition, String what) { if (!condition) throw new AssertionError(what); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static char[] pass() { return "fixture passphrase".toCharArray(); }
    static boolean cleared(char[] p) { for (char c : p) if (c != 0) return false; return true; }
    static byte[] sha(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    static String hex(byte[] data) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte v : sha(data)) b.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
        return b.toString();
    }
    static String validConfig() {
        return "{\"server\":{},\"providers\":[{\"id\":\"test\",\"accounts\":[{},{}],\"keys\":[\"one\",\"two\",\"three\"]}]}";
    }

    /** Fault-injecting backend double: enforces the rescue-copy contract like a real one must. */
    static class Fake implements BackupCoordinator.Backend {
        byte[] state;
        boolean rescueSupported = true;
        boolean replaceFails;
        RuntimeException rescueFailure;
        byte[] rescueCopy;
        String rescueName = "";
        int rescueCalls, restoreCalls;
        boolean stopped;
        byte[] snapshot;
        boolean nullSnapshot;
        Fake(byte[] state) { this.state = state.clone(); }
        public byte[] readConfig() { return state.clone(); }
        public String preserveDamagedConfig(byte[] hash, byte[] damaged) throws Exception {
            rescueCalls++;
            if (!MessageDigest.isEqual(hash, sha(state))) throw new IllegalStateException("configuration changed");
            if (rescueFailure != null) throw rescueFailure;
            if (!rescueSupported) return null;
            String name = "config.corrupt-" + hex(damaged).substring(0, 16) + ".json";
            if (rescueCopy != null && !Arrays.equals(rescueCopy, damaged)) throw new IllegalStateException("rescue copy conflict");
            rescueCopy = damaged.clone(); rescueName = name;
            if (!Arrays.equals(rescueCopy, damaged)) throw new IllegalStateException("rescue verify");
            return name;
        }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) throws Exception {
            restoreCalls++;
            if (!MessageDigest.isEqual(hash, sha(state))) throw new IllegalStateException("configuration changed");
            nullSnapshot = rollback == null;
            snapshot = rollback == null ? null : rollback.clone();
            stopped = true;
            byte[] old = state.clone();
            try { state = replacement.clone(); if (replaceFails) throw new Exception("injected replacement failure"); }
            catch (Exception e) { state = old; throw e; }
        }
    }

    /** Backend that only implements the two historical methods: the damaged path must fail closed. */
    static class Legacy implements BackupCoordinator.Backend {
        byte[] state; int restoreCalls;
        Legacy(byte[] state) { this.state = state.clone(); }
        public byte[] readConfig() { return state.clone(); }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) { restoreCalls++; state = replacement.clone(); }
    }

    public static void main(String[] args) throws Exception {
        byte[] authentic = bytes(validConfig());
        byte[] encrypted = EncryptedConfigBackup.encrypt(authentic, pass());
        String[] damagedShapes = {
                "{\"server\":{},\"providers\":[{\"id\":\"test\",\"acc",          // truncated write
                "",                                                             // empty file
                "{\"server\":[]}",                                              // wrong shape
                "{\"server\":{},\"providers\":[],\"schemaVersion\":99}",        // future schema
                "{\"providers\":[{\"id\":\"p\"},{\"id\":\"p\"}],\"server\":{}}",// duplicate provider
                "not json at all",
        };

        // 1. Damaged durable bytes no longer block the preview: report the state instead.
        for (String shape : damagedShapes) {
            byte[] damaged = bytes(shape);
            Fake fake = new Fake(damaged);
            char[] password = pass();
            BackupCoordinator.Preview preview = BackupCoordinator.preview(fake, encrypted, password);
            try {
                check(cleared(password), "preview must clear the password");
                check(preview.currentDamaged, "damaged current config must be reported: " + shape);
                check(preview.summary.getBoolean("currentDamaged"), "summary flag: " + shape);
                check(preview.summary.getBoolean("serverChanged"), "damaged server state cannot be confirmed equal: " + shape);
                check(preview.summary.getInt("providers") == 1, "providers: " + shape);
                check(preview.summary.getInt("accounts") == 2, "accounts: " + shape);
                check(preview.summary.getInt("keys") == 3, "keys: " + shape);
                check(preview.rescuedAs.isEmpty(), "no rescue before apply");
                check(Arrays.equals(damaged, fake.state), "preview must not touch the durable bytes");
                check(fake.rescueCalls == 0 && fake.restoreCalls == 0, "preview must not write");
            } finally { preview.close(); }
            // A wrong password is still rejected while the configuration is damaged.
            byte[] untouched = fake.state.clone();
            fails(() -> { BackupCoordinator.Preview p = BackupCoordinator.preview(fake, encrypted, "wrong passphrase".toCharArray()); p.close(); });
            fails(() -> BackupCoordinator.preview(fake, new byte[]{1, 2, 3}, pass()));
            check(Arrays.equals(untouched, fake.state) && fake.rescueCalls == 0 && fake.restoreCalls == 0, "rejected preview must not write");
        }

        // 2. Correct password + rescue-capable backend: recover, with the corrupt bytes preserved.
        for (String shape : damagedShapes) {
            byte[] damaged = bytes(shape);
            Fake fake = new Fake(damaged);
            BackupCoordinator.Preview preview = BackupCoordinator.preview(fake, encrypted, pass());
            check(preview.currentDamaged, "damaged flag");
            char[] rollbackPassword = pass();
            BackupCoordinator.apply(fake, preview, rollbackPassword);
            check(cleared(rollbackPassword), "apply must clear the caller password");
            check(fake.stopped, "gateway must be stopped for the restore");
            check(fake.restoreCalls == 1 && fake.rescueCalls == 1, "exactly one rescue and one restore");
            check(Arrays.equals(authentic, fake.state), "restored configuration must be the backup content: " + shape);
            check(Arrays.equals(damaged, fake.rescueCopy), "rescue copy must hold the exact damaged bytes: " + shape);
            check(fake.rescueName.endsWith(".json") && fake.rescueName.startsWith("config.corrupt-"), "content addressed rescue name");
            check(fake.nullSnapshot, "a damaged configuration cannot become a decryptable rollback snapshot");
            check(preview.rescuedAs.equals(fake.rescueName), "caller can report where the damaged bytes went");
            check(preview.summary != null, "summary survives apply");
            // The replacement must be loadable afterwards; the rescue copy survives the new state.
            EncryptedConfigBackup.validateConfig(fake.state);
            check(Arrays.equals(damaged, fake.rescueCopy), "rescue copy must not be rewritten by the restore");
            fails(() -> BackupCoordinator.apply(fake, preview, pass())); // closed preview cannot be replayed
        }

        // 3. Same bytes must map to the same rescue name, and a foreign copy is never overwritten.
        byte[] damaged = bytes(damagedShapes[0]);
        Fake reusable = new Fake(damaged);
        BackupCoordinator.Preview once = BackupCoordinator.preview(reusable, encrypted, pass());
        BackupCoordinator.apply(reusable, once, pass());
        String firstName = reusable.rescueName;
        byte[] firstCopy = reusable.rescueCopy.clone();
        Fake again = new Fake(damaged);
        BackupCoordinator.Preview twice = BackupCoordinator.preview(again, encrypted, pass());
        BackupCoordinator.apply(again, twice, pass());
        check(firstName.equals(again.rescueName), "rescue name must be content addressed");
        check(Arrays.equals(firstCopy, again.rescueCopy), "same bytes must produce the same copy");
        Fake conflict = new Fake(damaged);
        conflict.rescueCopy = bytes("some other damaged content");
        BackupCoordinator.Preview conflicted = BackupCoordinator.preview(conflict, encrypted, pass());
        fails(() -> BackupCoordinator.apply(conflict, conflicted, pass()));
        check(Arrays.equals(damaged, conflict.state), "a rescue conflict must not replace the configuration");
        check(conflict.restoreCalls == 0, "a rescue conflict must abort before stopping the gateway");

        // 4. Preservation impossible: fail closed, nothing stopped, nothing written, no rescue file.
        byte[] saved = damaged.clone();
        Fake unsupported = new Fake(damaged);
        unsupported.rescueSupported = false;
        BackupCoordinator.Preview p1 = BackupCoordinator.preview(unsupported, encrypted, pass());
        failsWith(IllegalStateException.class, () -> BackupCoordinator.apply(unsupported, p1, pass()));
        check(Arrays.equals(saved, unsupported.state), "unsupported backend must keep the damaged bytes");
        check(!unsupported.stopped, "unsupported backend must not stop the gateway");
        check(unsupported.restoreCalls == 0, "unsupported backend must not reach the replace step");
        check(unsupported.rescueCopy == null, "no rescue copy is fabricated");
        // The historical two-method backend keeps the same fail-closed behaviour.
        Legacy legacy = new Legacy(damaged);
        BackupCoordinator.Preview p2 = BackupCoordinator.preview(legacy, encrypted, pass());
        check(p2.currentDamaged, "legacy backend still previews the damaged state");
        failsWith(IllegalStateException.class, () -> BackupCoordinator.apply(legacy, p2, pass()));
        check(legacy.restoreCalls == 0 && Arrays.equals(saved, legacy.state), "legacy backend must not overwrite");
        // A throwing preservation hook is reported, not swallowed, and still writes nothing.
        Fake throwing = new Fake(damaged);
        throwing.rescueFailure = new IllegalStateException("disk full");
        BackupCoordinator.Preview p3 = BackupCoordinator.preview(throwing, encrypted, pass());
        fails(() -> BackupCoordinator.apply(throwing, p3, pass()));
        check(Arrays.equals(saved, throwing.state) && !throwing.stopped && throwing.restoreCalls == 0, "hook failure must not overwrite");

        // 5. Fault injection: the replace step fails after the swap; the damaged bytes come back.
        Fake midFailure = new Fake(damaged);
        midFailure.replaceFails = true;
        BackupCoordinator.Preview p4 = BackupCoordinator.preview(midFailure, encrypted, pass());
        failsWith(Exception.class, () -> BackupCoordinator.apply(midFailure, p4, pass()));
        check(Arrays.equals(saved, midFailure.state), "failed restore must roll the damaged bytes back");
        check(Arrays.equals(saved, midFailure.rescueCopy), "rescue copy must survive a failed restore");

        // 6. CAS still protects a configuration that was repaired between preview and apply.
        Fake repaired = new Fake(damaged);
        BackupCoordinator.Preview p5 = BackupCoordinator.preview(repaired, encrypted, pass());
        repaired.state = bytes("{\"server\":{},\"providers\":[]}");
        byte[] repairedBytes = repaired.state.clone();
        failsWith(IllegalStateException.class, () -> BackupCoordinator.apply(repaired, p5, pass()));
        check(Arrays.equals(repairedBytes, repaired.state), "a repaired configuration must not be replaced");
        check(repaired.rescueCalls == 0 && repaired.restoreCalls == 0, "CAS must reject before any write");

        // 7. Cancellation and closed previews never write.
        Fake interrupted = new Fake(damaged);
        BackupCoordinator.Preview p6 = BackupCoordinator.preview(interrupted, encrypted, pass());
        Thread.currentThread().interrupt();
        failsWith(InterruptedException.class, () -> BackupCoordinator.apply(interrupted, p6, pass()));
        Thread.interrupted();
        check(Arrays.equals(saved, interrupted.state) && !interrupted.stopped && interrupted.rescueCalls == 0, "cancelled restore must not write");
        Fake expired = new Fake(damaged);
        BackupCoordinator.Preview p7 = BackupCoordinator.preview(expired, encrypted, pass());
        p7.close();
        failsWith(IllegalStateException.class, () -> BackupCoordinator.apply(expired, p7, pass()));
        check(Arrays.equals(saved, expired.state), "an expired preview must not write");

        // 8. A readable configuration keeps the documented rollback-snapshot semantics.
        byte[] current = bytes("{\"server\":{\"port\":1},\"providers\":[]}");
        Fake healthy = new Fake(current);
        BackupCoordinator.Preview p8 = BackupCoordinator.preview(healthy, encrypted, pass());
        check(!p8.currentDamaged, "a valid configuration is not damaged");
        check(p8.summary.getBoolean("serverChanged"), "server diff detection");
        BackupCoordinator.apply(healthy, p8, pass());
        check(!healthy.nullSnapshot && healthy.snapshot != null, "readable current config keeps its rollback snapshot");
        check(healthy.rescueCalls == 0, "no rescue copy for a readable configuration");
        check(Arrays.equals(current, EncryptedConfigBackup.decrypt(healthy.snapshot, pass())),
                "the rollback snapshot must stay decryptable with the same password");
        check(Arrays.equals(authentic, healthy.state), "restored content");

        // 9. Real atomic transaction interop: no rollback snapshot is passed for damaged bytes, so
        // the durable file may only ever hold the old or the new configuration afterwards.
        java.io.File dir = java.nio.file.Files.createTempDirectory("allin-damaged-config-").toFile();
        java.io.File file = new java.io.File(dir, "config.json"), snapshot = new java.io.File(dir, "config-rollback.aibk");
        byte[] realDamaged = bytes(damagedShapes[0]);
        java.nio.file.Files.write(file.toPath(), realDamaged);
        try {
            BackupRestoreTransaction.Engine engine = new BackupRestoreTransaction.Engine() {
                public byte[] readCurrent() throws Exception { return BackupFiles.readFile(file, EncryptedConfigBackup.MAX_CONFIG_BYTES); }
                public void retireAndStop() { }
                public void restart() { }
            };
            try {
                new BackupRestoreTransaction(file, snapshot, engine, stage -> { })
                        .apply(sha(realDamaged), authentic, null);
            } catch (Exception expected) { }
            byte[] durable = java.nio.file.Files.readAllBytes(file.toPath());
            check(Arrays.equals(realDamaged, durable) || Arrays.equals(authentic, durable),
                    "a missing rollback snapshot may only leave the old or the new configuration");
            check(!snapshot.exists(), "no half-written rollback snapshot may be published");
            String[] leftovers = dir.list();
            for (String name : leftovers)
                check("config.json".equals(name) || "config-rollback.aibk".equals(name), "stray file " + name);
        } finally {
            java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            dir.delete();
        }

        System.out.println("BackupCoordinatorDamagedConfigOfflineTest OK");
    }
}
