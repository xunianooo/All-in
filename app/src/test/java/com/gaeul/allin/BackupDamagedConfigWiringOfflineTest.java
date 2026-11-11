package com.gaeul.allin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wiring fixture for restoring an authentic encrypted backup over a damaged configuration.
 *
 * <p>Covers the two halves that {@code BackupCoordinator} delegates to: the content-addressed
 * rescue copy ({@link BackupRestoreTransaction#preserveDamaged}) and the restore transaction when
 * no rollback snapshot can exist ({@link BackupRestoreTransaction#apply} with a null ciphertext).
 * It also composes them exactly the way {@code GatewayService.backupBackend()} does, so the real
 * call order (preserve, then CAS-anchored replace) is exercised against real files.
 *
 * <p>No real backup, account, gateway, device or user data is touched: every file lives in a
 * throw-away directory, no network is used, and Gradle is not required.
 */
public class BackupDamagedConfigWiringOfflineTest {
    interface Checked { void run() throws Exception; }

    static void fails(Checked body) throws Exception {
        try { body.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) { return; }
        throw new AssertionError("expected failure");
    }
    static void failsWith(Class<?> type, Checked body) throws Exception {
        try { body.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) {
            if (!type.isInstance(expected))
                throw new AssertionError("unexpected failure type " + expected.getClass().getName(), expected);
            return;
        }
        throw new AssertionError("expected failure");
    }
    static void check(boolean condition, String what) { if (!condition) throw new AssertionError(what); }
    static void checkEquals(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + " (expected " + expected + ", actual " + actual + ")");
    }

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static char[] pass() { return "wiring fixture passphrase".toCharArray(); }
    static byte[] sha(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }

    static final String BACKUP_CONFIG =
            "{\"server\":{},\"providers\":[{\"id\":\"test\",\"accounts\":[{},{}],\"keys\":[\"one\",\"two\",\"three\"]}]}";
    static final String OTHER_CONFIG = "{\"server\":{},\"providers\":[]}";
    /** Truncated write: the shape the encrypted install could never repair before. */
    static final String TRUNCATED = "{\"server\":{},\"providers\":[{\"id\":\"test\",\"acc";
    static final String NOT_JSON = "not json at all";

    static File tmp(String name) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir", "."), "allin-damaged-wiring-" + name + "-"
                + Long.toHexString(System.nanoTime()));
        if (!dir.mkdirs()) throw new IOException("temp dir");
        return dir;
    }
    static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); out.flush(); out.getFD().sync(); }
    }
    static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    static String[] names(File dir) { String[] all = dir.list(); return all == null ? new String[0] : all; }
    static List<String> matching(File dir, String prefix, String suffix) {
        List<String> out = new ArrayList<>();
        for (String name : names(dir)) if (name.startsWith(prefix) && name.endsWith(suffix)) out.add(name);
        return out;
    }
    static List<String> residue(File dir) {
        List<String> out = new ArrayList<>();
        out.addAll(matching(dir, "backup-", ".tmp"));
        out.addAll(matching(dir, "config-", ".tmp"));
        return out;
    }
    static List<String> rescues(File dir) { return matching(dir, "config.corrupt-", ".json"); }
    static List<String> salvage(File dir) { return matching(dir, "config-rollback-", ".aibk"); }
    static File staleMarker(File dir) { return new File(dir, BackupRestoreTransaction.STALE_MARKER); }
    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    static String fingerprint(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        byte[] digest = sha(data);
        for (int i = 0; i < 8; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
        return hex.toString();
    }

    /** Reads and writes the real config file, like the production engine does. */
    static final class Engine implements BackupRestoreTransaction.Engine {
        final File config;
        int retireCalls, restartCalls;
        boolean stopped;
        Engine(File config) { this.config = config; }
        public byte[] readCurrent() throws Exception {
            return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        }
        public void retireAndStop() { retireCalls++; stopped = true; }
        public void restart() { restartCalls++; }
    }

    /** Mirrors GatewayService.backupBackend(): same call order, real files, no Android. */
    static final class Wired implements BackupCoordinator.Backend {
        final File config, rollback;
        final BackupRestoreTransaction.Fault fault;
        Engine engine;
        Wired(File config, File rollback, BackupRestoreTransaction.Fault fault) {
            this.config = config; this.rollback = rollback; this.fault = fault;
        }
        public byte[] readConfig() throws Exception {
            if (!config.isFile()) throw new IOException("config missing");
            return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        }
        public String preserveDamagedConfig(byte[] hash, byte[] damaged) throws Exception {
            return BackupRestoreTransaction.preserveDamaged(config, hash, damaged);
        }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollbackCiphertext) throws Exception {
            engine = new Engine(config);
            new BackupRestoreTransaction(config, rollback, engine, fault).apply(hash, replacement, rollbackCiphertext);
        }
    }

    static final BackupRestoreTransaction.Fault NO_FAULT = new BackupRestoreTransaction.Fault() {
        public void at(String stage) { }
    };

    public static void main(String[] args) throws Exception {
        sectionsShapeAndCopy();
        sectionsFailClosed();
        sectionsWriterFaultInjection();
        sectionsTransactionWithoutSnapshot();
        sectionsTransactionWithSnapshot();
        sectionsRollbackFailureKeepsSnapshot();
        sectionsSalvageDuplicateReuse();
        sectionsSnapshotGeneration();
        sectionsWiring();
        System.out.println("BackupDamagedConfigWiringOfflineTest OK");
    }

    /** 1. Content-addressed rescue copy of the exact damaged bytes, never overwriting anything. */
    static void sectionsShapeAndCopy() throws Exception {
        File dir = tmp("copy");
        try {
            byte[] damaged = bytes(TRUNCATED);
            File config = new File(dir, "config.json");
            write(config, damaged);
            byte[] hash = sha(damaged);

            String name = BackupRestoreTransaction.preserveDamaged(config, hash, damaged);
            checkEquals("config.corrupt-" + fingerprint(damaged) + ".json", name, "rescue name must be content addressed");
            checkEquals(name, BackupRestoreTransaction.rescueNameFor(damaged), "rescueNameFor must agree with the copy name");
            File rescue = new File(dir, name);
            check(rescue.isFile(), "rescue copy must exist");
            check(Arrays.equals(damaged, read(rescue)), "rescue copy must hold the exact damaged bytes");
            check(Arrays.equals(damaged, read(config)), "preserving must not touch the damaged configuration");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");
            check(rescues(dir).size() == 1, "exactly one rescue copy");

            // Preserving the same bytes again reuses the file instead of adding a copy.
            String again = BackupRestoreTransaction.preserveDamaged(config, hash, damaged);
            checkEquals(name, again, "identical bytes must map to the same copy");
            check(rescues(dir).size() == 1, "re-preserving must not add a second copy");
            check(Arrays.equals(damaged, read(rescue)), "reused copy must stay byte identical");

            // A different damaged state gets its own copy and leaves the first one alone.
            byte[] other = bytes(NOT_JSON);
            File config2 = new File(dir, "config.json");
            write(config2, other);
            String otherName = BackupRestoreTransaction.preserveDamaged(config2, sha(other), other);
            check(!name.equals(otherName), "different bytes must map to a different copy");
            check(rescues(dir).size() == 2, "both copies must exist");
            check(Arrays.equals(damaged, read(rescue)), "the first copy must stay untouched");

            // An empty damaged file is still preserved under its content-addressed name.
            File emptyDir = tmp("empty");
            try {
                File emptyConfig = new File(emptyDir, "config.json");
                write(emptyConfig, new byte[0]);
                String emptyName = BackupRestoreTransaction.preserveDamaged(emptyConfig, sha(new byte[0]), new byte[0]);
                checkEquals("config.corrupt-empty.json", emptyName, "empty damaged bytes have a named copy");
                File emptyCopy = new File(emptyDir, emptyName);
                check(emptyCopy.isFile() && emptyCopy.length() == 0, "the empty copy must exist and be empty");
            } finally { deleteTree(emptyDir); }
        } finally { deleteTree(dir); }
    }

    /** 2. Preservation never guesses: conflicts, changed bytes and missing files all fail closed. */
    static void sectionsFailClosed() throws Exception {
        File dir = tmp("closed");
        try {
            byte[] damaged = bytes(TRUNCATED);
            File config = new File(dir, "config.json");
            write(config, damaged);
            byte[] hash = sha(damaged);
            String name = BackupRestoreTransaction.rescueNameFor(damaged);

            // Foreign content under the content-addressed name is a conflict, not an overwrite.
            File rescue = new File(dir, name);
            byte[] foreign = bytes("some unrelated rescue content");
            write(rescue, foreign);
            failsWith(IllegalStateException.class, () -> BackupRestoreTransaction.preserveDamaged(config, hash, damaged));
            check(Arrays.equals(foreign, read(rescue)), "a conflicting copy must never be overwritten");
            check(Arrays.equals(damaged, read(config)), "a conflict must not touch the configuration");

            // The configuration changed after the preview: the anchor no longer matches.
            byte[] repaired = bytes(OTHER_CONFIG);
            write(config, repaired);
            failsWith(IllegalStateException.class, () -> BackupRestoreTransaction.preserveDamaged(config, hash, damaged));
            check(Arrays.equals(repaired, read(config)), "a changed configuration must not be copied or replaced");
            check(Arrays.equals(foreign, read(rescue)), "a rejected preserve must not write either");

            // Correct digest but the caller's bytes are not the ones on disk: refuse.
            write(config, damaged);
            failsWith(IllegalStateException.class,
                    () -> BackupRestoreTransaction.preserveDamaged(config, hash, bytes(NOT_JSON)));
            check(!new File(dir, BackupRestoreTransaction.rescueNameFor(bytes(NOT_JSON))).exists(),
                    "mismatched caller bytes must not produce a copy");

            // A missing configuration cannot be anchored at all.
            File missing = new File(dir, "absent.json");
            fails(() -> BackupRestoreTransaction.preserveDamaged(missing, hash, damaged));

            // Invalid arguments are rejected instead of producing a half-understood copy.
            failsWith(IllegalArgumentException.class,
                    () -> BackupRestoreTransaction.preserveDamaged(config, new byte[31], damaged));
            failsWith(IllegalArgumentException.class,
                    () -> BackupRestoreTransaction.preserveDamaged(config, hash, null));

            // Cancellation is not damage and must not leave a copy behind.
            String pendingName = BackupRestoreTransaction.rescueNameFor(bytes(NOT_JSON));
            File pending = new File(dir, pendingName);
            check(!pending.exists(), "no copy before the interrupted attempt");
            byte[] other = bytes(NOT_JSON);
            write(config, other);
            Thread.currentThread().interrupt();
            try { fails(() -> BackupRestoreTransaction.preserveDamaged(config, sha(other), other)); }
            finally { Thread.interrupted(); }
            check(!pending.exists(), "an interrupted preserve must not publish a copy");
            check(Arrays.equals(other, read(config)), "an interrupted preserve must not touch the configuration");
        } finally { deleteTree(dir); }
    }

    /** 3. Fault injection in the copy writer: a wrong or failed write is detected and removed. */
    static void sectionsWriterFaultInjection() throws Exception {
        File dir = tmp("writer");
        try {
            byte[] damaged = bytes(TRUNCATED);
            File config = new File(dir, "config.json");
            write(config, damaged);
            byte[] hash = sha(damaged);
            String name = BackupRestoreTransaction.rescueNameFor(damaged);

            // The writer silently writes different bytes: the read-back check must reject them
            // and the lying copy must not stay where a later restore would trust it.
            failsWith(IllegalStateException.class,
                    () -> BackupRestoreTransaction.preserveDamaged(config, hash, damaged,
                            (target, data) -> BackupFiles.atomicWrite(target, bytes(NOT_JSON))));
            check(!new File(dir, name).exists(), "a copy that does not match its bytes must be removed");
            check(Arrays.equals(damaged, read(config)), "a rejected copy must not touch the configuration");

            // The writer fails outright.
            fails(() -> BackupRestoreTransaction.preserveDamaged(config, hash, damaged,
                    (target, data) -> { throw new IOException("disk full"); }));
            check(!new File(dir, name).exists(), "a failed write must not leave a copy");
            check(residue(dir).isEmpty(), "a failed write must not leave a temporary file");

            // The healthy writer still works afterwards.
            checkEquals(name, BackupRestoreTransaction.preserveDamaged(config, hash, damaged), "healthy write must succeed");
            check(Arrays.equals(damaged, read(new File(dir, name))), "healthy copy must hold the damaged bytes");
        } finally { deleteTree(dir); }
    }

    /** 4. The transaction with no rollback snapshot: replace, roll back, and never publish. */
    static void sectionsTransactionWithoutSnapshot() throws Exception {
        // 4a. A damaged pre-restore state is replaced and no snapshot appears.
        File dir = tmp("tx-null");
        try {
            byte[] damaged = bytes(TRUNCATED);
            byte[] replacement = bytes(BACKUP_CONFIG);
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");
            write(config, damaged);
            Engine engine = new Engine(config);
            new BackupRestoreTransaction(config, rollback, engine, NO_FAULT).apply(sha(damaged), replacement, null);
            check(Arrays.equals(replacement, read(config)), "the replacement must be written");
            EncryptedConfigBackup.validateConfig(read(config));
            check(!rollback.exists(), "a damaged pre-restore state must not publish a rollback snapshot");
            check(!staleMarker(dir).exists(), "no snapshot exists, so nothing may be marked stale");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");
            check(engine.retireCalls == 1 && engine.restartCalls == 1, "the engine must be stopped and restarted");
            check(!engine.stopped || engine.restartCalls >= 1, "the engine must be running again");

            // 4b. A stale snapshot from an older readable restore stays byte identical, and the
            // configuration that no longer has a snapshot of its own is reported as stale.
            File staleDir = tmp("tx-stale");
            try {
                File staleConfig = new File(staleDir, "config.json");
                File staleRollback = new File(staleDir, "config-rollback.aibk");
                write(staleConfig, damaged);
                byte[] olderSnapshot = bytes("older rollback snapshot marker");
                write(staleRollback, olderSnapshot);
                new BackupRestoreTransaction(staleConfig, staleRollback, new Engine(staleConfig), NO_FAULT)
                        .apply(sha(damaged), replacement, null);
                check(Arrays.equals(replacement, read(staleConfig)), "the replacement must be written");
                check(Arrays.equals(olderSnapshot, read(staleRollback)), "an older snapshot must not be overwritten");
                check(staleMarker(staleDir).isFile(), "an older snapshot must be marked so the UI can warn");
                String note = new String(read(staleMarker(staleDir)), StandardCharsets.UTF_8);
                check(!note.contains("providers") && !note.contains("authKey"), "the marker must carry no configuration");
                check(residue(staleDir).isEmpty(), "no temporary file may be left behind");
            } finally { deleteTree(staleDir); }

            // 4c. The commit stage is never reached, so an injected commit fault cannot fire.
            File noCommitDir = tmp("tx-nocommit");
            try {
                File noCommitConfig = new File(noCommitDir, "config.json");
                File noCommitRollback = new File(noCommitDir, "config-rollback.aibk");
                write(noCommitConfig, damaged);
                new BackupRestoreTransaction(noCommitConfig, noCommitRollback, new Engine(noCommitConfig),
                        stage -> { if ("commitSnapshot".equals(stage)) throw new IllegalStateException("must not fire"); })
                        .apply(sha(damaged), replacement, null);
                check(Arrays.equals(replacement, read(noCommitConfig)), "no snapshot commit may be attempted");
                check(!noCommitRollback.exists(), "no snapshot may be published");
            } finally { deleteTree(noCommitDir); }

            // 4d. A failure after the swap rolls the damaged bytes back exactly.
            File faultDir = tmp("tx-fault");
            try {
                File faultConfig = new File(faultDir, "config.json");
                File faultRollback = new File(faultDir, "config-rollback.aibk");
                write(faultConfig, damaged);
                String rescueName = BackupRestoreTransaction.preserveDamaged(faultConfig, sha(damaged), damaged);
                Engine faultEngine = new Engine(faultConfig);
                fails(() -> new BackupRestoreTransaction(faultConfig, faultRollback, faultEngine,
                        stage -> { if ("replaced".equals(stage)) throw new IllegalStateException("injected"); })
                        .apply(sha(damaged), replacement, null));
                check(Arrays.equals(damaged, read(faultConfig)), "a failed restore must roll the damaged bytes back");
                check(Arrays.equals(damaged, read(new File(faultDir, rescueName))), "the rescue copy must survive");
                check(!faultRollback.exists(), "a failed restore must not publish a snapshot");
                check(residue(faultDir).isEmpty(), "a failed restore must not leave a temporary file");
                check(faultEngine.restartCalls >= 1, "the engine must be restarted after the rollback");
            } finally { deleteTree(faultDir); }

            // 4e. CAS still rejects a configuration that changed between preview and restore.
            File casDir = tmp("tx-cas");
            try {
                byte[] repaired = bytes(OTHER_CONFIG);
                File casConfig = new File(casDir, "config.json");
                File casRollback = new File(casDir, "config-rollback.aibk");
                write(casConfig, repaired);
                Engine casEngine = new Engine(casConfig);
                failsWith(IllegalStateException.class, () -> new BackupRestoreTransaction(casConfig, casRollback,
                        casEngine, NO_FAULT).apply(sha(damaged), replacement, null));
                check(Arrays.equals(repaired, read(casConfig)), "a changed configuration must not be replaced");
                check(casEngine.retireCalls == 0, "CAS must reject before the engine is touched");
                check(!casRollback.exists(), "CAS must reject before any snapshot work");
            } finally { deleteTree(casDir); }
        } finally { deleteTree(dir); }
    }

    /** 5. The readable path is unchanged: the rollback snapshot is still published and usable. */
    static void sectionsTransactionWithSnapshot() throws Exception {
        File dir = tmp("tx-snapshot");
        try {
            byte[] old = bytes(OTHER_CONFIG);
            byte[] replacement = bytes(BACKUP_CONFIG);
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");
            write(config, old);
            char[] password = pass();
            byte[] snapshot = EncryptedConfigBackup.encrypt(old, password);
            check(isCleared(password), "encrypt must clear the password it was given");
            new BackupRestoreTransaction(config, rollback, new Engine(config), NO_FAULT)
                    .apply(sha(old), replacement, snapshot);
            check(Arrays.equals(replacement, read(config)), "the replacement must be written");
            check(rollback.isFile(), "a readable configuration must still publish a rollback snapshot");
            check(Arrays.equals(snapshot, read(rollback)), "the snapshot must be published unchanged");
            check(Arrays.equals(old, EncryptedConfigBackup.decrypt(read(rollback), pass())),
                    "the rollback snapshot must decrypt to the pre-restore bytes");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");

            // A fault before the swap must not publish a snapshot at all.
            File earlyDir = tmp("tx-early");
            try {
                File earlyConfig = new File(earlyDir, "config.json");
                File earlyRollback = new File(earlyDir, "config-rollback.aibk");
                write(earlyConfig, old);
                char[] second = pass();
                byte[] secondSnapshot = EncryptedConfigBackup.encrypt(old, second);
                fails(() -> new BackupRestoreTransaction(earlyConfig, earlyRollback, new Engine(earlyConfig),
                        stage -> { if ("snapshot".equals(stage)) throw new IllegalStateException("injected"); })
                        .apply(sha(old), replacement, secondSnapshot));
                check(Arrays.equals(old, read(earlyConfig)), "an early failure must keep the configuration");
                check(!earlyRollback.exists(), "an early failure must not publish a snapshot");
                check(residue(earlyDir).isEmpty(), "an early failure must not leave a temporary file");
            } finally { deleteTree(earlyDir); }
        } finally { deleteTree(dir); }
    }

    /** 5b. A failed rollback must never delete the staged snapshot that holds the only copy. */
    static void sectionsRollbackFailureKeepsSnapshot() throws Exception {
        // The rollback write is made impossible by turning the config path into a non-empty
        // directory after the swap: the replacement is already on disk, so putting the original
        // back fails and the staged ciphertext becomes the only copy of the previous state.
        File dir = tmp("salvage");
        try {
            byte[] old = bytes(OTHER_CONFIG);
            byte[] replacement = bytes(BACKUP_CONFIG);
            byte[] damaged = bytes(TRUNCATED);
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");
            write(config, old);
            byte[] snapshot = EncryptedConfigBackup.encrypt(old, pass());
            BackupRestoreTransaction.Fault sabotage = stage -> {
                if ("restarted".equals(stage)) {
                    if (!config.delete() || !config.mkdirs() || !new File(config, "occupied").createNewFile())
                        throw new IOException("cannot block the config path");
                    throw new IOException("injected post-swap failure");
                }
            };
            Exception thrown = null;
            try {
                new BackupRestoreTransaction(config, rollback, new Engine(config), sabotage)
                        .apply(sha(old), replacement, snapshot);
            } catch (Exception failure) { thrown = failure; }
            check(thrown != null, "the restore must fail");
            String salvageName = "config-rollback-" + fingerprint(snapshot) + ".aibk";
            File salvaged = new File(dir, salvageName);
            check(salvaged.isFile(), "the only copy of the previous configuration must be kept");
            check(Arrays.equals(snapshot, read(salvaged)), "the kept file must be the exact staged ciphertext");
            check(Arrays.equals(old, EncryptedConfigBackup.decrypt(read(salvaged), pass())),
                    "the kept snapshot must decrypt to the previous configuration");
            check(!rollback.exists(), "a failed restore must not publish the documented snapshot slot");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");
            check(thrown.getMessage() != null && thrown.getMessage().contains(salvageName),
                    "the failure must name the kept snapshot: " + thrown.getMessage());

            // Salvaging the same ciphertext twice reuses the file instead of adding a copy.
            checkEquals(salvageName, BackupRestoreTransaction.salvageNameFor(snapshot), "salvage name must be content addressed");
            checkEquals(salvageName, BackupRestoreTransaction.salvageNameFor(snapshot), "salvage name must be stable");
            check(BackupRestoreTransaction.salvageNameFor(null) == null, "no ciphertext, no salvage name");
        } finally { deleteTree(dir); }

        // When the salvage name already holds different content the staged file is kept as is:
        // a conflict must never overwrite a copy and never drop the only remaining one.
        File conflictDir = tmp("salvage-conflict");
        try {
            byte[] old = bytes(OTHER_CONFIG);
            byte[] replacement = bytes(BACKUP_CONFIG);
            File config = new File(conflictDir, "config.json"), rollback = new File(conflictDir, "config-rollback.aibk");
            write(config, old);
            byte[] snapshot = EncryptedConfigBackup.encrypt(old, pass());
            File conflicting = new File(conflictDir, "config-rollback-" + fingerprint(snapshot) + ".aibk");
            byte[] foreign = bytes("unrelated salvage content");
            write(conflicting, foreign);
            Exception thrown = null;
            try {
                new BackupRestoreTransaction(config, rollback, new Engine(config), stage -> {
                    if ("restarted".equals(stage)) {
                        if (!config.delete() || !config.mkdirs() || !new File(config, "occupied").createNewFile())
                            throw new IOException("cannot block the config path");
                        throw new IOException("injected post-swap failure");
                    }
                }).apply(sha(old), replacement, snapshot);
            } catch (Exception failure) { thrown = failure; }
            check(thrown != null, "the restore must fail");
            check(Arrays.equals(foreign, read(conflicting)), "a conflicting salvage file must never be overwritten");
            List<String> kept = residue(conflictDir);
            check(kept.size() == 1, "exactly the staged snapshot must be kept, actual " + kept);
            byte[] keptBytes = read(new File(conflictDir, kept.get(0)));
            check(Arrays.equals(snapshot, keptBytes), "the kept temporary file must hold the exact ciphertext");
            check(Arrays.equals(old, EncryptedConfigBackup.decrypt(keptBytes, pass())),
                    "the kept temporary file must decrypt to the previous configuration");
            check(thrown.getMessage() != null && thrown.getMessage().contains(kept.get(0)),
                    "the failure must name the kept file: " + thrown.getMessage());
        } finally { deleteTree(conflictDir); }
    }

    /** 5c. The rollback slot keeps its newest generation, and an older one is marked as such. */
    static void sectionsSnapshotGeneration() throws Exception {
        File dir = tmp("generation");
        try {
            byte[] old = bytes(OTHER_CONFIG);
            byte[] replacement = bytes(BACKUP_CONFIG);
            byte[] damaged = bytes(TRUNCATED);
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");

            // A readable restore publishes a snapshot and leaves nothing marked stale.
            write(config, old);
            new BackupRestoreTransaction(config, rollback, new Engine(config), NO_FAULT)
                    .apply(sha(old), replacement, EncryptedConfigBackup.encrypt(old, pass()));
            byte[] firstSnapshot = read(rollback);
            check(rollback.isFile(), "a readable restore must publish a snapshot");
            check(!staleMarker(dir).exists(), "a freshly published snapshot is current");

            // A damaged restore cannot publish one, so the previous generation is marked.
            write(config, damaged);
            new BackupRestoreTransaction(config, rollback, new Engine(config), NO_FAULT)
                    .apply(sha(damaged), replacement, null);
            check(Arrays.equals(firstSnapshot, read(rollback)), "the older snapshot must stay byte identical");
            check(staleMarker(dir).exists(), "an older snapshot must be marked so the UI can warn");

            // The next readable restore refreshes the slot, which removes the warning.
            write(config, bytes(OTHER_CONFIG));
            new BackupRestoreTransaction(config, rollback, new Engine(config), NO_FAULT)
                    .apply(sha(bytes(OTHER_CONFIG)), replacement, EncryptedConfigBackup.encrypt(bytes(OTHER_CONFIG), pass()));
            check(!staleMarker(dir).exists(), "a refreshed snapshot must clear the stale marker");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");
        } finally { deleteTree(dir); }

        // With no snapshot at all there is nothing to warn about.
        File emptyDir = tmp("generation-empty");
        try {
            File config = new File(emptyDir, "config.json"), rollback = new File(emptyDir, "config-rollback.aibk");
            byte[] damaged = bytes(TRUNCATED);
            write(config, damaged);
            new BackupRestoreTransaction(config, rollback, new Engine(config), NO_FAULT)
                    .apply(sha(damaged), bytes(BACKUP_CONFIG), null);
            check(!rollback.exists(), "no snapshot is published");
            check(!staleMarker(emptyDir).exists(), "no snapshot, no stale warning");
        } finally { deleteTree(emptyDir); }
    }

    static boolean isCleared(char[] password) { for (char c : password) if (c != 0) return false; return true; }

    /**
     * Makes the rollback write impossible: the configuration path is turned into a non-empty
     * directory after the swap, so putting the previous configuration back must fail and the
     * staged snapshot becomes the only remaining copy of it.
     */
    static BackupRestoreTransaction.Fault blockRollback(final File config) {
        return stage -> {
            if ("restarted".equals(stage)) {
                if (!config.delete() || !config.mkdirs() || !new File(config, "occupied").createNewFile())
                    throw new IOException("cannot block the config path");
                throw new IOException("injected post-swap failure");
            }
        };
    }

    /**
     * 5d. Fault injection around the salvage step itself: the staged snapshot is only cleaned up
     * once an identical copy is verified under the content-addressed salvage name, and an
     * unverified name conflict still keeps the staged file as the only copy.
     */
    static void sectionsSalvageDuplicateReuse() throws Exception {
        byte[] old = bytes(OTHER_CONFIG);
        byte[] replacement = bytes(BACKUP_CONFIG);
        byte[] snapshot = EncryptedConfigBackup.encrypt(old, pass());
        String salvageName = BackupRestoreTransaction.salvageNameFor(snapshot);
        checkEquals("config-rollback-" + fingerprint(snapshot) + ".aibk", salvageName,
                "salvage name must be content addressed");

        // 5d-1. The salvage name already holds the identical ciphertext (an earlier failed attempt
        // published it and then crashed before removing its staged duplicate). Keeping the staged
        // duplicate would leave an orphaned temporary file next to the copy, so it must be removed
        // - but only because the bytes were verified, and the verified copy must survive.
        File dir = tmp("salvage-duplicate");
        try {
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");
            write(config, old);
            File salvageFile = new File(dir, salvageName);
            write(salvageFile, snapshot);
            Exception thrown = null;
            try {
                new BackupRestoreTransaction(config, rollback, new Engine(config), blockRollback(config))
                        .apply(sha(old), replacement, snapshot);
            } catch (Exception failure) { thrown = failure; }
            check(thrown != null, "the restore must fail");
            check(salvageFile.isFile(), "the verified copy must be kept");
            check(Arrays.equals(snapshot, read(salvageFile)), "the verified copy must stay byte identical");
            check(Arrays.equals(old, EncryptedConfigBackup.decrypt(read(salvageFile), pass())),
                    "the verified copy must decrypt to the previous configuration");
            check(!rollback.exists(), "a failed restore must not publish the documented snapshot slot");
            check(residue(dir).isEmpty(),
                    "a verified identical copy must not leave the staged duplicate behind, actual " + residue(dir));
            check(names(dir).length == 2,
                    "only the blocked configuration path and the verified copy may remain, actual "
                            + Arrays.toString(names(dir)));
            check(thrown.getMessage() != null && thrown.getMessage().contains(salvageName),
                    "the failure must name the verified copy: " + thrown.getMessage());

            // 5d-2. Negative control: the same salvage name holding the same number of bytes but
            // not the same bytes is a conflict, never a duplicate. Nothing may be deleted, and the
            // staged snapshot has to stay as the only copy of the previous configuration.
            File tamperedDir = tmp("salvage-tampered");
            try {
                File tamperedConfig = new File(tamperedDir, "config.json");
                File tamperedRollback = new File(tamperedDir, "config-rollback.aibk");
                write(tamperedConfig, old);
                byte[] tampered = snapshot.clone();
                tampered[tampered.length - 1] ^= 0x5a;
                check(tampered.length == snapshot.length && !Arrays.equals(tampered, snapshot),
                        "the tampered fixture must keep the length and differ");
                File tamperedSalvage = new File(tamperedDir, salvageName);
                write(tamperedSalvage, tampered);
                Exception tamperedThrown = null;
                try {
                    new BackupRestoreTransaction(tamperedConfig, tamperedRollback, new Engine(tamperedConfig),
                            blockRollback(tamperedConfig)).apply(sha(old), replacement, snapshot);
                } catch (Exception failure) { tamperedThrown = failure; }
                check(tamperedThrown != null, "the restore must fail");
                check(Arrays.equals(tampered, read(tamperedSalvage)),
                        "a non-identical salvage file must never be overwritten");
                List<String> kept = residue(tamperedDir);
                check(kept.size() == 1, "exactly the staged snapshot must be kept, actual " + kept);
                byte[] keptBytes = read(new File(tamperedDir, kept.get(0)));
                check(Arrays.equals(snapshot, keptBytes), "the kept temporary file must hold the exact ciphertext");
                check(Arrays.equals(old, EncryptedConfigBackup.decrypt(keptBytes, pass())),
                        "the kept temporary file must decrypt to the previous configuration");
                check(tamperedThrown.getMessage() != null && tamperedThrown.getMessage().contains(kept.get(0)),
                        "the failure must name the kept file: " + tamperedThrown.getMessage());
            } finally { deleteTree(tamperedDir); }

            // 5d-3. Negative control: the salvage name occupied by a directory is not a copy
            // either, so the staged file must be kept instead of being deleted beside it.
            File occupiedDir = tmp("salvage-dir");
            try {
                File occupiedConfig = new File(occupiedDir, "config.json");
                File occupiedRollback = new File(occupiedDir, "config-rollback.aibk");
                write(occupiedConfig, old);
                File occupiedSalvage = new File(occupiedDir, salvageName);
                check(occupiedSalvage.mkdirs(), "the salvage path fixture must be a directory");
                Exception occupiedThrown = null;
                try {
                    new BackupRestoreTransaction(occupiedConfig, occupiedRollback, new Engine(occupiedConfig),
                            blockRollback(occupiedConfig)).apply(sha(old), replacement, snapshot);
                } catch (Exception failure) { occupiedThrown = failure; }
                check(occupiedThrown != null, "the restore must fail");
                check(occupiedSalvage.isDirectory() && names(occupiedSalvage).length == 0,
                        "the directory squatting the salvage name must stay untouched");
                List<String> occupiedKept = residue(occupiedDir);
                check(occupiedKept.size() == 1, "exactly the staged snapshot must be kept, actual " + occupiedKept);
                check(Arrays.equals(snapshot, read(new File(occupiedDir, occupiedKept.get(0)))),
                        "the kept temporary file must hold the exact ciphertext");
                check(occupiedThrown.getMessage() != null
                                && occupiedThrown.getMessage().contains(occupiedKept.get(0)),
                        "the failure must name the kept file: " + occupiedThrown.getMessage());
            } finally { deleteTree(occupiedDir); }
        } finally { deleteTree(dir); }
    }

    /** 6. Full composition: preview, preserve, CAS-anchored replace, and a readable regression. */
    static void sectionsWiring() throws Exception {
        // 6a. Damaged configuration + authentic backup + correct password.
        File dir = tmp("wired");
        try {
            byte[] damaged = bytes(TRUNCATED);
            byte[] backupConfig = bytes(BACKUP_CONFIG);
            File config = new File(dir, "config.json"), rollback = new File(dir, "config-rollback.aibk");
            write(config, damaged);
            Wired backend = new Wired(config, rollback, NO_FAULT);
            byte[] encrypted = EncryptedConfigBackup.encrypt(backupConfig, pass());

            BackupCoordinator.Preview preview = BackupCoordinator.preview(backend, encrypted, pass());
            check(preview.currentDamaged, "the damaged state must be reported, not refused");
            check(preview.summary.getBoolean("currentDamaged"), "the summary must carry the damaged flag");
            check(preview.rescuedAs.isEmpty(), "nothing is preserved before the restore");
            check(rescues(dir).isEmpty(), "preview must not create a copy");

            BackupCoordinator.apply(backend, preview, pass());
            check(Arrays.equals(backupConfig, read(config)), "the backup must be restored");
            checkEquals("config.corrupt-" + fingerprint(damaged) + ".json", preview.rescuedAs,
                    "the caller must learn where the damaged bytes went");
            check(Arrays.equals(damaged, read(new File(dir, preview.rescuedAs))),
                    "the rescue copy must hold the exact damaged bytes");
            check(!rollback.exists(), "a damaged restore must not publish a rollback snapshot");
            check(residue(dir).isEmpty(), "no temporary file may be left behind");
            check(backend.engine != null && backend.engine.retireCalls == 1, "the gateway must be stopped once");
            check(backend.engine.restartCalls == 1, "the gateway must be restarted");

            // 6b. A wrong password is still rejected and nothing is written.
            File wrongDir = tmp("wired-wrong");
            try {
                File wrongConfig = new File(wrongDir, "config.json");
                write(wrongConfig, damaged);
                Wired wrong = new Wired(wrongConfig, new File(wrongDir, "config-rollback.aibk"), NO_FAULT);
                fails(() -> BackupCoordinator.preview(wrong, encrypted, "wrong passphrase".toCharArray()));
                check(Arrays.equals(damaged, read(wrongConfig)), "a rejected password must not touch the file");
                check(rescues(wrongDir).isEmpty(), "a rejected password must not preserve anything");
            } finally { deleteTree(wrongDir); }

            // 6c. A failed swap keeps the damaged bytes and the rescue copy, and the UI can say where.
            File failDir = tmp("wired-fail");
            try {
                File failConfig = new File(failDir, "config.json");
                File failRollback = new File(failDir, "config-rollback.aibk");
                write(failConfig, damaged);
                Wired failing = new Wired(failConfig, failRollback,
                        stage -> { if ("replaced".equals(stage)) throw new IllegalStateException("injected"); });
                BackupCoordinator.Preview failingPreview = BackupCoordinator.preview(failing, encrypted, pass());
                fails(() -> BackupCoordinator.apply(failing, failingPreview, pass()));
                check(Arrays.equals(damaged, read(failConfig)), "a failed restore must keep the damaged bytes");
                check(!failingPreview.rescuedAs.isEmpty(), "the rescue copy must be reported even on failure");
                check(rescues(failDir).size() == 1, "exactly one rescue copy must exist");
                check(Arrays.equals(damaged, read(new File(failDir, failingPreview.rescuedAs))),
                        "the rescue copy must survive a failed restore");
                check(!failRollback.exists(), "a failed restore must not publish a snapshot");
                check(residue(failDir).isEmpty(), "a failed restore must not leave a temporary file");
            } finally { deleteTree(failDir); }

            // 6d. Regression: a readable configuration keeps the decryptable rollback snapshot.
            File readableDir = tmp("wired-readable");
            try {
                byte[] old = bytes(OTHER_CONFIG);
                File readableConfig = new File(readableDir, "config.json");
                File readableRollback = new File(readableDir, "config-rollback.aibk");
                write(readableConfig, old);
                Wired readable = new Wired(readableConfig, readableRollback, NO_FAULT);
                BackupCoordinator.Preview readablePreview = BackupCoordinator.preview(readable, encrypted, pass());
                check(!readablePreview.currentDamaged, "a readable configuration is not damaged");
                BackupCoordinator.apply(readable, readablePreview, pass());
                check(Arrays.equals(backupConfig, read(readableConfig)), "the backup must be restored");
                check(readableRollback.isFile(), "the rollback snapshot must still be created");
                check(Arrays.equals(old, EncryptedConfigBackup.decrypt(read(readableRollback), pass())),
                        "the rollback snapshot must be decryptable with the same password");
                check(readablePreview.rescuedAs.isEmpty(), "a readable configuration needs no rescue copy");
                check(rescues(readableDir).isEmpty(), "no rescue copy for a readable configuration");
                check(residue(readableDir).isEmpty(), "no temporary file may be left behind");
            } finally { deleteTree(readableDir); }
        } finally { deleteTree(dir); }
    }
}
