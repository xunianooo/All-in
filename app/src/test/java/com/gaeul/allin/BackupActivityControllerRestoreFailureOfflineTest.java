package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fault injection for the text shown when a restore fails.
 *
 * <p>The damaged-configuration restore once reported "当前配置未被替换" whenever a rescue copy
 * existed. That claim is false for exactly the case the transaction labels "restore and rollback
 * failed": the incoming configuration may already be on disk and the rollback write may be the
 * step that failed. This fixture therefore drives the real {@link BackupRestoreTransaction}
 * through real files and real failures, then feeds the state the app can actually observe
 * ({@link BackupActivityController#outcomeAfterFailure} and
 * {@link BackupActivityController#restoreFailureMessage}) and pins that the report never claims
 * more than the bytes prove:
 *
 * <ul>
 *   <li>rollback write fails after the replacement → the backup bytes stay on disk → the report
 *       must not say the configuration was left alone;<\/li>
 *   <li>the same exception label with a successful rollback → the previous bytes are back → the
 *       verified wording is allowed again;<\/li>
 *   <li>a readable pre-restore configuration leaves nothing comparable → no claim either way;<\/li>
 *   <li>a rescue copy or configuration that cannot be read → no claim either way.<\/li>
 * </ul>
 *
 * <p>Nothing here touches a device, the network, a real backup, Gradle or user data: every file
 * lives in a throw-away directory.
 */
public class BackupActivityControllerRestoreFailureOfflineTest {
    interface Checked { void run() throws Exception; }
    static int checks;
    static void check(boolean condition, String what) { checks++; if (!condition) throw new AssertionError(what); }
    static void checkEquals(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + " (expected " + expected + ", actual " + actual + ")");
    }
    static void checkText(String text, String required, String what) {
        checks++;
        if (text == null || text.indexOf(required) < 0) throw new AssertionError(what + " (missing \"" + required + "\" in \"" + text + "\")");
    }
    /** The assertion that would have caught the original report. */
    static void checkNoClaim(String text, String forbidden, String what) {
        checks++;
        if (text != null && text.indexOf(forbidden) >= 0) throw new AssertionError(what + " (claimed \"" + forbidden + "\" in \"" + text + "\")");
    }
    static Exception failureOf(Checked body) throws Exception {
        try { body.run(); }
        catch (AssertionError e) { throw e; }
        catch (Exception expected) { return expected; }
        throw new AssertionError("expected a failure");
    }

    /** Valid incoming configuration, the bytes a restore writes. */
    static final String BACKUP_CONFIG =
            "{\"server\":{},\"providers\":[{\"id\":\"test\",\"accounts\":[{},{}],\"keys\":[\"one\",\"two\",\"three\"]}]}";
    /** Different but still valid configuration, used as a readable pre-restore state. */
    static final String OTHER_CONFIG = "{\"server\":{},\"providers\":[]}";
    /** Truncated write: the damaged state the rescue copy has to preserve. */
    static final String DAMAGED = "{\"server\":{},\"providers\":[{\"id\":\"test\",\"acc";
    static final String OTHER_DAMAGED = "not json at all";

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static char[] pass() { return "restore failure fixture passphrase".toCharArray(); }

    static File tmp(String name) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir", "."), "allin-restore-report-" + name + "-"
                + Long.toHexString(System.nanoTime()));
        if (!dir.mkdirs()) throw new IOException("temp dir");
        return dir;
    }
    static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); out.flush(); out.getFD().sync(); }
    }
    static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    /**
     * The configuration path itself, with a switch that makes every later write resolve to a
     * directory that does not exist. That is the same observable behaviour as a rollback write
     * failing on a full or revoked private directory, and it keeps the bytes the restore already
     * wrote in place.
     */
    static final class SwitchableStorage extends File {
        private static final long serialVersionUID = 1L;
        boolean refuseWrites;
        SwitchableStorage(File parent, String name) { super(parent, name); }
        @Override public File getAbsoluteFile() {
            if (!refuseWrites) return super.getAbsoluteFile();
            return new File(new File(super.getAbsoluteFile().getParentFile(), "unwritable"), getName());
        }
    }

    /** Reads and writes the real config file, like the production engine does. */
    static final class Engine implements BackupRestoreTransaction.Engine {
        final File config;
        final boolean failRestart;
        int restarts;
        Engine(File config, boolean failRestart) { this.config = config; this.failRestart = failRestart; }
        public byte[] readCurrent() throws Exception {
            return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        }
        public void retireAndStop() { }
        public void restart() throws Exception {
            restarts++;
            if (failRestart) throw new IOException("injected engine restart failure");
        }
    }

    /** Mirrors GatewayService.backupBackend(): same call order, real files, no Android. */
    static final class Wired implements BackupCoordinator.Backend {
        final File config, rollback;
        final BackupRestoreTransaction.Fault fault;
        final boolean failRestart;
        Wired(File config, File rollback, BackupRestoreTransaction.Fault fault, boolean failRestart) {
            this.config = config; this.rollback = rollback; this.fault = fault; this.failRestart = failRestart;
        }
        public byte[] readConfig() throws Exception {
            if (!config.isFile()) throw new IOException("config missing");
            return BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        }
        public String preserveDamagedConfig(byte[] hash, byte[] damaged) throws Exception {
            return BackupRestoreTransaction.preserveDamaged(config, hash, damaged);
        }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] rollbackCiphertext) throws Exception {
            new BackupRestoreTransaction(config, rollback, new Engine(config, failRestart), fault)
                    .apply(hash, replacement, rollbackCiphertext);
        }
    }

    static final BackupRestoreTransaction.Fault NO_FAULT = new BackupRestoreTransaction.Fault() {
        public void at(String stage) { }
    };

    public static void main(String[] args) throws Exception {
        comparisonTruthTable();
        messageTruthTable();
        failedRollbackLeavesTheBackupOnDisk();
        failedRestartWithSuccessfulRollback();
        readableConfigurationReportsNoOutcome();
        unreadableStateReportsNoOutcome();
        System.out.println("BackupActivityControllerRestoreFailureOfflineTest OK: " + checks + " checks");
    }

    /** The comparison itself must never turn missing evidence into a verdict. */
    static void comparisonTruthTable() throws Exception {
        byte[] preserved = bytes(DAMAGED);
        checkEquals(BackupActivityController.DurableOutcome.UNCHANGED,
                BackupActivityController.durableOutcome(preserved, preserved.clone()), "identical bytes are unchanged");
        checkEquals(BackupActivityController.DurableOutcome.REPLACED,
                BackupActivityController.durableOutcome(preserved, bytes(BACKUP_CONFIG)), "different bytes are replaced");
        checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                BackupActivityController.durableOutcome(null, bytes(BACKUP_CONFIG)), "no preserved copy is unknown");
        checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                BackupActivityController.durableOutcome(new byte[0], bytes(BACKUP_CONFIG)), "an empty preserved copy is unknown");
        checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                BackupActivityController.durableOutcome(preserved, null), "no durable bytes is unknown");
    }

    /** Every wording, including the one the report used unconditionally before this fix. */
    static void messageTruthTable() throws Exception {
        String name = "config.corrupt-0123456789abcdef.json";
        String unchanged = BackupActivityController.restoreFailureMessage(
                true, BackupActivityController.DurableOutcome.UNCHANGED, name);
        checkText(unchanged, "未被替换", "identical bytes may be reported as unchanged");
        checkText(unchanged, name, "the rescue copy must be named");

        String replaced = BackupActivityController.restoreFailureMessage(
                true, BackupActivityController.DurableOutcome.REPLACED, name);
        checkNoClaim(replaced, "未被替换", "different bytes must never be reported as unchanged");
        checkText(replaced, "已不是恢复前的内容", "different bytes must say the file changed");
        checkText(replaced, name, "the rescue copy must be named");

        String unknown = BackupActivityController.restoreFailureMessage(
                true, BackupActivityController.DurableOutcome.UNKNOWN, name);
        checkNoClaim(unknown, "未被替换", "an unverified state must not claim anything");
        checkNoClaim(unknown, "已不是恢复前的内容", "an unverified state must not claim a replacement either");
        checkText(unknown, "无法确认", "an unverified state must say so");
        checkText(unknown, name, "the rescue copy must be named");

        String noCopy = BackupActivityController.restoreFailureMessage(
                true, BackupActivityController.DurableOutcome.UNKNOWN, "");
        checkNoClaim(noCopy, "损坏的原配置已完整保留为", "no rescue copy must not be promised");

        String readable = BackupActivityController.restoreFailureMessage(
                false, BackupActivityController.DurableOutcome.UNKNOWN, "");
        checkNoClaim(readable, "未被替换", "a readable configuration has no preserved copy to prove it");
        checkNoClaim(readable, "已不是恢复前的内容", "a readable configuration cannot prove a replacement");
        checkText(readable, "config-rollback", "the decryptable snapshot must still be mentioned");
    }

    /**
     * The case that motivated the fix: the replacement is already on disk, the rollback write is
     * the step that fails, and the transaction reports "restore and rollback failed".
     */
    static void failedRollbackLeavesTheBackupOnDisk() throws Exception {
        File dir = tmp("failed-rollback");
        try {
            byte[] damaged = bytes(DAMAGED);
            byte[] backup = bytes(BACKUP_CONFIG);
            SwitchableStorage config = new SwitchableStorage(dir, "config.json");
            write(config, damaged);
            File rollback = new File(dir, "config-rollback.aibk");
            Wired wired = new Wired(config, rollback, stage -> {
                if ("replaced".equals(stage)) config.refuseWrites = true;
            }, true);
            byte[] encrypted = EncryptedConfigBackup.encrypt(backup, pass());
            try {
                BackupCoordinator.Preview preview = BackupCoordinator.preview(wired, encrypted, pass());
                check(preview.currentDamaged, "a truncated configuration must preview as damaged");
                Exception failure = failureOf(() -> BackupCoordinator.apply(wired, preview, pass()));
                check(config.refuseWrites, "the injected write failure must have fired");
                check(failure instanceof IllegalStateException,
                        "a restore and rollback failure must surface as IllegalStateException, got " + failure);
                checkText(String.valueOf(failure.getMessage()), "restore and rollback failed",
                        "the failure must be the restore and rollback case");
                config.refuseWrites = false;

                byte[] durable = read(new File(dir, "config.json"));
                check(Arrays.equals(backup, durable),
                        "the incoming backup is what a failed rollback write leaves on disk");
                byte[] rescue = read(new File(dir, preview.rescuedAs));
                check(Arrays.equals(damaged, rescue), "the rescue copy must still hold the damaged bytes");

                BackupActivityController.DurableOutcome outcome =
                        BackupActivityController.outcomeAfterFailure(preview.currentDamaged, preview.rescuedAs, dir);
                checkEquals(BackupActivityController.DurableOutcome.REPLACED, outcome,
                        "the durable bytes are the backup, so the outcome is a replacement");
                String message = BackupActivityController.restoreFailureMessage(
                        preview.currentDamaged, outcome, preview.rescuedAs);
                checkNoClaim(message, "未被替换", "restore and rollback failed must not claim the original was kept");
                checkNoClaim(message, "当前配置未被替换", "the pre-fix wording must not reach the user");
                checkText(message, preview.rescuedAs, "the rescue copy must be named so the bytes can be found");
                checkText(message, "已不是恢复前的内容", "the user must be told the configuration changed");
            } finally {
                config.refuseWrites = false;
                EncryptedConfigBackup.clear(encrypted);
                deleteTree(dir);
            }
        } finally { deleteTree(dir); }
    }

    /**
     * The same "restore and rollback failed" label with a rollback that worked: the pre-restore
     * bytes are back, so the unchanged wording is provable and therefore allowed.
     */
    static void failedRestartWithSuccessfulRollback() throws Exception {
        File dir = tmp("restored");
        try {
            byte[] damaged = bytes(OTHER_DAMAGED);
            File config = new File(dir, "config.json");
            write(config, damaged);
            File rollback = new File(dir, "config-rollback.aibk");
            Wired wired = new Wired(config, rollback, NO_FAULT, true);
            byte[] encrypted = EncryptedConfigBackup.encrypt(bytes(BACKUP_CONFIG), pass());
            try {
                BackupCoordinator.Preview preview = BackupCoordinator.preview(wired, encrypted, pass());
                check(preview.currentDamaged, "an unparseable configuration must preview as damaged");
                Exception failure = failureOf(() -> BackupCoordinator.apply(wired, preview, pass()));
                checkText(String.valueOf(failure.getMessage()), "restore and rollback failed",
                        "the restart failure must still be reported as the restore and rollback case");
                check(Arrays.equals(damaged, read(config)), "the rollback must have put the damaged bytes back");
                check(Arrays.equals(damaged, read(new File(dir, preview.rescuedAs))),
                        "the rescue copy must still hold the damaged bytes");
                check(!rollback.exists(), "a damaged-configuration restore never publishes a rollback snapshot");

                BackupActivityController.DurableOutcome outcome =
                        BackupActivityController.outcomeAfterFailure(preview.currentDamaged, preview.rescuedAs, dir);
                checkEquals(BackupActivityController.DurableOutcome.UNCHANGED, outcome,
                        "restored bytes must be recognised as unchanged");
                String message = BackupActivityController.restoreFailureMessage(
                        preview.currentDamaged, outcome, preview.rescuedAs);
                checkText(message, "未被替换", "a verified rollback may be reported as unchanged");
                checkText(message, preview.rescuedAs, "the rescue copy must be named");
            } finally {
                EncryptedConfigBackup.clear(encrypted);
                deleteTree(dir);
            }
        } finally { deleteTree(dir); }
    }

    /** A readable pre-restore configuration leaves nothing to compare, so nothing is claimed. */
    static void readableConfigurationReportsNoOutcome() throws Exception {
        File dir = tmp("readable");
        try {
            byte[] previous = bytes(OTHER_CONFIG);
            File config = new File(dir, "config.json");
            write(config, previous);
            File rollback = new File(dir, "config-rollback.aibk");
            Wired wired = new Wired(config, rollback, NO_FAULT, true);
            byte[] encrypted = EncryptedConfigBackup.encrypt(bytes(BACKUP_CONFIG), pass());
            try {
                BackupCoordinator.Preview preview = BackupCoordinator.preview(wired, encrypted, pass());
                check(!preview.currentDamaged, "a readable configuration is not damaged");
                Exception failure = failureOf(() -> BackupCoordinator.apply(wired, preview, pass()));
                check(failure != null, "a restart failure must surface");
                check(Arrays.equals(previous, read(config)), "the rollback must have restored the previous bytes");
                check(preview.rescuedAs == null || preview.rescuedAs.isEmpty(),
                        "a readable configuration needs no rescue copy");

                BackupActivityController.DurableOutcome outcome =
                        BackupActivityController.outcomeAfterFailure(preview.currentDamaged, preview.rescuedAs, dir);
                checkEquals(BackupActivityController.DurableOutcome.UNKNOWN, outcome,
                        "an encrypted snapshot gives the app nothing to compare");
                String message = BackupActivityController.restoreFailureMessage(
                        preview.currentDamaged, outcome, preview.rescuedAs);
                checkNoClaim(message, "未被替换", "an unverified state must not claim the original was kept");
                checkNoClaim(message, "已不是恢复前的内容", "an unverified state must not claim a replacement");
                checkText(message, "请检查", "the user must be told to check the gateway state");
            } finally {
                EncryptedConfigBackup.clear(encrypted);
                deleteTree(dir);
            }
        } finally { deleteTree(dir); }
    }

    /** Missing or unreadable evidence stays unverified instead of becoming a verdict. */
    static void unreadableStateReportsNoOutcome() throws Exception {
        File dir = tmp("unreadable");
        try {
            String missing = "config.corrupt-ffffffffffffffff.json";
            write(new File(dir, "config.json"), bytes(BACKUP_CONFIG));
            checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                    BackupActivityController.outcomeAfterFailure(true, missing, dir),
                    "a missing rescue copy proves nothing");
            checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                    BackupActivityController.outcomeAfterFailure(true, "", dir),
                    "no rescue name proves nothing");
            checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                    BackupActivityController.outcomeAfterFailure(false, missing, dir),
                    "a readable pre-restore configuration proves nothing");
            checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                    BackupActivityController.outcomeAfterFailure(true, missing, null),
                    "a missing directory proves nothing");

            File unreadable = tmp("unreadable-config");
            try {
                String rescueName = "config.corrupt-0011223344556677.json";
                write(new File(unreadable, rescueName), bytes(DAMAGED));
                if (!new File(unreadable, "config.json").mkdirs()) throw new IOException("fixture directory");
                checkEquals(BackupActivityController.DurableOutcome.UNKNOWN,
                        BackupActivityController.outcomeAfterFailure(true, rescueName, unreadable),
                        "a durable configuration that cannot be read proves nothing");
                String message = BackupActivityController.restoreFailureMessage(
                        true, BackupActivityController.DurableOutcome.UNKNOWN, rescueName);
                checkNoClaim(message, "未被替换", "an unreadable configuration must not be reported as kept");
                checkText(message, "无法确认", "the user must be told the state is unknown");
            } finally { deleteTree(unreadable); }
        } finally { deleteTree(dir); }
    }
}
