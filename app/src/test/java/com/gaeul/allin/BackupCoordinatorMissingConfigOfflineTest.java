package com.gaeul.allin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Fixture for restoring an .aibk backup when {@code config.json} does not exist at all.
 *
 * <p>Absence is a real state: a first-run save can fail (the gateway then keeps its configuration
 * only in memory), a factory-reset device has not written the file yet, and an external deletion
 * removes it. It is also <em>not</em> the same state as a damaged file, because an empty or
 * unparseable {@code config.json} makes the gateway enter its read-only recovery mode while a
 * missing file is simply bootstrapped from the shipped template.
 *
 * <p>A restore of that state is refused fail-closed today. This fixture pins the invariants that
 * refusal must keep, so that a future implementation of the missing-configuration restore changes
 * them deliberately instead of silently weakening them:
 *
 * <ul>
 *   <li>nothing is created, replaced, stopped or snapshotted while the restore is refused;<\/li>
 *   <li>the password buffer is zeroed even though the preview fails;<\/li>
 *   <li>absence is never emulated as zero-length bytes: the v1 envelope rejects them, and a
 *       rollback that wrote them would leave the recovery state instead of "no file";<\/li>
 *   <li>{@link BackupRestoreTransaction} cannot express absence either (its post-stop CAS reads the
 *       configuration file), so an implementation must not smuggle an empty anchor into it.<\/li>
 * <\/ul>
 *
 * <p>No real backup, account, gateway or configuration is used: the backend doubles below are
 * in-memory fakes and every file lives in a temporary directory.
 */
public class BackupCoordinatorMissingConfigOfflineTest {
    static void check(boolean condition, String what) { if (!condition) throw new AssertionError(what); }
    static void fails(Checked c, String what) throws Exception {
        try { c.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) { return; }
        throw new AssertionError(what);
    }
    static void failsWith(Class<?> type, Checked c, String what) throws Exception {
        try { c.run(); } catch (AssertionError e) { throw e; } catch (Exception expected) {
            if (!type.isInstance(expected))
                throw new AssertionError(what + ": unexpected " + expected.getClass().getName() + " (" + expected.getMessage() + ")", expected);
            return;
        }
        throw new AssertionError(what + ": expected failure");
    }
    interface Checked { void run() throws Exception; }

    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static char[] pass() { return "fixture passphrase".toCharArray(); }
    static boolean zeroed(char[] value) { for (char c : value) if (c != 0) return false; return true; }
    static byte[] sha(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    static String config() {
        return "{\"server\":{},\"providers\":[{\"id\":\"test\",\"accounts\":[{}],\"keys\":[\"one\"]}]}";
    }

    /** Same failure shape as {@code GatewayServer.backupDurableConfig()} for an absent file. */
    static class AbsentBackend implements BackupCoordinator.Backend {
        int reads, restores, rescues;
        boolean wouldWrite;
        public byte[] readConfig() throws IOException { reads++; throw new IOException("config missing"); }
        public String preserveDamagedConfig(byte[] hash, byte[] damaged) { rescues++; wouldWrite = true; return "config.corrupt-deadbeef.json"; }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) { restores++; wouldWrite = true; }
    }

    /** A backend that reports absence as a null snapshot. */
    static class NullStateBackend implements BackupCoordinator.Backend {
        int reads, restores;
        public byte[] readConfig() { reads++; return null; }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) { restores++; }
    }

    /** A backend that really reads the directory, like the production one does. */
    static class FileBackend implements BackupCoordinator.Backend {
        final File config; int restores;
        FileBackend(File config) { this.config = config; }
        public byte[] readConfig() throws Exception {
            if (!config.isFile()) throw new IOException("config missing");
            return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollback) throws IOException { restores++; BackupFiles.atomicWrite(config, replacement); }
    }

    public static void main(String[] args) throws Exception {
        check(restoringAnAbsentConfigurationIsRefused(), "absent restore is refused fail-closed");
        check(absenceIsNeverMaterialized(), "absence is never materialized");
        check(absenceCannotBeEmulatedAsEmptyBytes(), "absence cannot be emulated as empty bytes");
        check(transactionCannotExpressAbsence(), "transaction cannot express absence");
        check(presentConfigurationStillPreviews(), "a present configuration still previews");
        System.out.println("BackupCoordinatorMissingConfigOfflineTest OK");
    }

    /**
     * A restore attempt against an absent configuration fails during preview: the gateway is never
     * stopped, nothing is written, and the password buffer is still cleared.
     */
    static boolean restoringAnAbsentConfigurationIsRefused() throws Exception {
        byte[] ciphertext = EncryptedConfigBackup.encrypt(bytes(config()), pass());
        AbsentBackend backend = new AbsentBackend();
        char[] password = pass();
        fails(() -> {
            try (BackupCoordinator.Preview ignored = BackupCoordinator.preview(backend, ciphertext, password)) {
                throw new AssertionError("preview must not succeed without a durable configuration");
            }
        }, "preview with an absent configuration must fail");
        check(backend.reads == 1, "the durable configuration must be consulted once, was " + backend.reads);
        check(backend.restores == 0, "an absent configuration must never reach the restore step");
        check(backend.rescues == 0, "there is nothing to rescue, so no rescue copy may be attempted");
        check(!backend.wouldWrite, "a refused restore must not write");
        check(zeroed(password), "the password buffer must be zeroed even when the preview fails");

        NullStateBackend nullState = new NullStateBackend();
        char[] second = pass();
        failsWith(IllegalStateException.class, () ->
                        BackupCoordinator.preview(nullState, ciphertext, second).close(),
                "a null configuration must be reported, not restored");
        check(nullState.restores == 0, "a null configuration must never reach the restore step");
        check(zeroed(second), "the password buffer must be zeroed for a null configuration too");

        // A preview that does not exist can never be applied.
        FileBackend fileBackend = new FileBackend(new File(java.nio.file.Files.createTempDirectory("missing-preview").toFile(), "config.json"));
        failsWith(IllegalStateException.class, () -> BackupCoordinator.apply(fileBackend, null, pass()),
                "apply without a preview must be refused");
        check(fileBackend.restores == 0, "apply without a preview must not touch the backend");
        return true;
    }

    /** An absent configuration stays absent: no file, no rescue copy, no rollback snapshot. */
    static boolean absenceIsNeverMaterialized() throws Exception {
        File dir = Files.createTempDirectory("missing-materialize").toFile();
        try {
            File config = new File(dir, "config.json");
            byte[] ciphertext = EncryptedConfigBackup.encrypt(bytes(config()), pass());
            FileBackend backend = new FileBackend(config);
            fails(() -> BackupCoordinator.preview(backend, ciphertext, pass()).close(),
                    "preview must fail while the file is absent");
            check(!config.exists(), "a refused restore must not create config.json");
            check(dir.list().length == 0, "a refused restore must not leave files behind: " + Arrays.toString(dir.list()));
            check(backend.restores == 0, "a refused restore must not reach the restore step");
        } finally { clear(dir); }
        return true;
    }

    /**
     * Zero-length bytes are a damaged configuration, not an absent one, so a rollback must never
     * write them to express "there was no file". The v1 envelope rejects them outright.
     */
    static boolean absenceCannotBeEmulatedAsEmptyBytes() throws Exception {
        failsWith(IllegalArgumentException.class, () -> EncryptedConfigBackup.validateConfig(new byte[0]),
                "an empty configuration must not validate");
        failsWith(IllegalArgumentException.class, () -> EncryptedConfigBackup.validateConfig(null),
                "a null configuration must not validate");
        return true;
    }

    /**
     * The restore transaction cannot publish a configuration that never existed: it re-reads the
     * file after stopping the engine. Reusing it for absence would need an anchor it cannot honor.
     */
    static boolean transactionCannotExpressAbsence() throws Exception {
        File dir = Files.createTempDirectory("missing-transaction").toFile();
        try {
            File config = new File(dir, "config.json"), snapshot = new File(dir, "config-rollback.aibk");
            byte[] replacement = bytes(config());
            BackupRestoreTransaction.Engine engine = new BackupRestoreTransaction.Engine() {
                int stops;
                public byte[] readCurrent() throws Exception {
                    if (!config.isFile()) throw new IOException("config missing");
                    return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
                }
                public void retireAndStop() { stops++; }
                public void restart() { }
            };
            for (byte[] anchor : new byte[][] { sha(new byte[0]), null }) {
                try {
                    new BackupRestoreTransaction(config, snapshot, engine, stage -> { }).apply(anchor, replacement, null);
                } catch (AssertionError e) { throw e; } catch (Exception expected) { }
                // Whatever the outcome, an absent pre-restore state must never leave foreign bytes
                // behind and must never publish a rollback snapshot of a configuration that did not exist.
                check(!config.exists() || Arrays.equals(replacement, BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES)),
                        "the attempt may only leave the replacement configuration, never unrelated bytes");
                check(!snapshot.exists(), "no rollback snapshot may be published for a configuration that never existed");
            }
        } finally { clear(dir); }
        return true;
    }

    /** Differential control: the very same call succeeds once the file exists. */
    static boolean presentConfigurationStillPreviews() throws Exception {
        File dir = Files.createTempDirectory("missing-control").toFile();
        try {
            File config = new File(dir, "config.json");
            byte[] current = bytes("{\"server\":{\"authKey\":\"fixture\"},\"providers\":[]}");
            BackupFiles.atomicWrite(config, current);
            FileBackend backend = new FileBackend(config);
            byte[] ciphertext = EncryptedConfigBackup.encrypt(bytes(config()), pass());
            try (BackupCoordinator.Preview preview = BackupCoordinator.preview(backend, ciphertext, pass())) {
                check(preview.summary.optBoolean("currentDamaged") == false, "a readable configuration is not damaged");
                check(preview.summary.optBoolean("serverChanged"), "an empty server section differs from the fixture backup");
            }
            return true;
        } finally { clear(dir); }
    }

    static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
