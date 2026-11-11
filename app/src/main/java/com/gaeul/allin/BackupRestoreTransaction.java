package com.gaeul.allin;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Caller serializes this complete transaction against engine lifecycle operations.
 *
 * <p>A damaged (unparseable) durable configuration cannot be sealed into the v1 rollback
 * envelope, so {@link #apply} accepts a null rollback ciphertext. In that case no snapshot is
 * staged or published and the caller is responsible for the rescue copy that holds the exact
 * pre-restore bytes; an older snapshot is left untouched rather than overwritten, and
 * {@link #STALE_MARKER} records that it now predates the current configuration.
 *
 * <p>The staged snapshot is only deleted once the pre-restore configuration is known to be back
 * on disk, or once an identical copy of it has been verified under the content-addressed salvage
 * name. If the rollback itself fails, that ciphertext is the only remaining copy of the previous
 * configuration, so it is published under that name and only the staged duplicate is removed
 * afterwards; when the name already holds different content, or the copy cannot be verified, the
 * staged file itself is kept instead of being removed.
 */
final class BackupRestoreTransaction {
    interface Engine {
        byte[] readCurrent() throws Exception;
        void retireAndStop() throws Exception;
        void restart() throws Exception;
    }
    interface Fault { void at(String stage) throws Exception; }
    private final File config, snapshot;
    private final Engine engine;
    private final Fault fault;
    BackupRestoreTransaction(File config, File snapshot, Engine engine, Fault fault) {
        this.config = config; this.snapshot = snapshot; this.engine = engine; this.fault = fault;
    }
    void apply(byte[] expectedHash, byte[] replacement, byte[] rollbackCiphertext) throws Exception {
        byte[] original = null, disk = null;
        boolean retired = false, replaced = false;
        // Set when the rollback could not write the previous configuration back, so the staged
        // snapshot holds its only remaining copy and must never be deleted.
        boolean lastCopy = false;
        File staged = null;
        String salvage = null;
        try {
            EncryptedConfigBackup.validateConfig(replacement);
            original = engine.readCurrent();
            compare(expectedHash, original);
            EncryptedConfigBackup.checkInterrupted();
            retired = true; // retireAndStop may partially succeed
            engine.retireAndStop();
            fault.at("stopped");
            disk = BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
            compare(expectedHash, disk);
            EncryptedConfigBackup.checkInterrupted();
            // A damaged pre-restore configuration has no rollback snapshot to publish, so an
            // older snapshot is left byte-identical instead of being overwritten with an
            // unrelated rollback state. The rescue copy holds the pre-restore bytes.
            if (rollbackCiphertext != null) {
                staged = BackupFiles.stage(snapshot, rollbackCiphertext);
                salvage = salvageNameFor(rollbackCiphertext);
            }
            fault.at("snapshot");
            EncryptedConfigBackup.checkInterrupted();
            BackupFiles.atomicWrite(config, replacement); replaced = true;
            fault.at("replaced");
            EncryptedConfigBackup.checkInterrupted();
            engine.restart();
            fault.at("restarted");
            EncryptedConfigBackup.checkInterrupted();
            if (staged != null) {
                fault.at("commitSnapshot");
                AtomicConfigFile.replace(staged, snapshot);
                staged = null;
                clearStaleMarker();
            } else {
                markSnapshotStale();
            }
        } catch (Exception failure) {
            // Rollback I/O must run even if cancellation interrupted the worker.
            boolean interrupted = Thread.interrupted();
            String kept = null;
            if (retired) {
                Exception rollbackFailure = null;
                boolean stopped = false, restored = !replaced;
                try { engine.retireAndStop(); stopped = true; }
                catch (Exception e) { rollbackFailure = e; }
                // Retire must precede stop; a failed stop still blocks old writers.
                if (replaced) try { BackupFiles.atomicWrite(config, original); restored = true; }
                catch (Exception e) { if (rollbackFailure == null) rollbackFailure = e; else rollbackFailure.addSuppressed(e); }
                if (replaced && !restored && staged != null) {
                    // The previous configuration now exists only inside the staged snapshot.
                    // Publish it under its content-addressed name; if that is impossible keep
                    // the staged file itself. Deleting it would destroy the only surviving copy.
                    lastCopy = true;
                    File published = keepStagedSnapshot(staged, salvage, rollbackFailure);
                    if (published != null) { staged = null; kept = published.getName(); }
                    else kept = staged.getName();
                }
                if (stopped && restored) try { engine.restart(); }
                catch (Exception e) { if (rollbackFailure == null) rollbackFailure = e; else rollbackFailure.addSuppressed(e); }
                if (rollbackFailure != null) {
                    failure.addSuppressed(rollbackFailure);
                    if (interrupted) Thread.currentThread().interrupt();
                    // The name is derived from a content hash, never from the configuration.
                    throw new IllegalStateException("restore and rollback failed"
                            + (kept == null ? "" : "; pre-restore snapshot kept as " + kept), failure);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw failure;
        } finally {
            if (staged != null && !lastCopy) staged.delete();
            EncryptedConfigBackup.clear(original); EncryptedConfigBackup.clear(disk);
        }
    }
    private static void compare(byte[] expected, byte[] data) throws Exception {
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(data);
        try { if (expected == null || !MessageDigest.isEqual(expected, actual)) throw new IllegalStateException("configuration changed"); }
        finally { EncryptedConfigBackup.clear(actual); }
    }

    /** Marker telling the UI that the rollback snapshot predates a damaged-configuration restore. */
    static final String STALE_MARKER = "config-rollback.stale";
    private static final byte[] STALE_MARKER_BYTES =
            "a damaged configuration was restored without a rollback snapshot\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Content-addressed name for a pre-restore snapshot that had to be kept because the rollback
     * could not restore the previous configuration.
     */
    static String salvageNameFor(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ciphertext);
            try {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
                return "config-rollback-" + hex + ".aibk";
            } finally { EncryptedConfigBackup.clear(digest); }
        } catch (Exception unavailableDigest) { return null; }
    }

    /**
     * Keep the staged snapshot under its content-addressed salvage name without ever replacing
     * different content. Returns the file that holds the copy, or null when the caller must keep
     * the staged file itself (a conflict, an unverifiable target or a failed publish).
     */
    private static File keepStagedSnapshot(File staged, String salvageName, Exception rollbackFailure) {
        File target = null;
        try {
            File directory = staged.getAbsoluteFile().getParentFile();
            if (directory == null || salvageName == null) return null;
            target = new File(directory, salvageName);
            if (target.exists()) {
                // Content addressed: only an identical copy is reused, anything else is a conflict.
                if (!sameBytes(target, staged)) return null;
                // The verified copy under the durable name now holds these bytes, so the staged
                // duplicate is pure residue. Remove it, but only trust the target once the
                // duplicate is really gone; otherwise the caller keeps the staged file.
                return discardStaged(staged) ? target : null;
            }
            AtomicConfigFile.replace(staged, target);
            return target.isFile() && !staged.exists() ? target : null;
        } catch (Exception publishFailure) {
            if (rollbackFailure != null) rollbackFailure.addSuppressed(publishFailure);
            // A move that failed after the rename still left the copy under its name.
            try { if (target != null && !staged.exists() && target.isFile()) return target; }
            catch (RuntimeException ignored) { }
            return null;
        }
    }

    /**
     * Delete a staged duplicate whose bytes are already verified in the durable copy, and report
     * whether it is verifiably gone. Anything unverifiable keeps the caller on the safe path.
     */
    private static boolean discardStaged(File staged) {
        try {
            if (!staged.delete() && staged.exists()) return false;
            // Only real absence counts: a path recreated by another writer is not a cleanup.
            return !staged.exists();
        } catch (SecurityException refused) { return false; }
    }

    private static boolean sameBytes(File first, File second) {
        try {
            if (!first.isFile() || !second.isFile() || first.length() != second.length()) return false;
            byte[] a = BackupFiles.readFile(first, EncryptedConfigBackup.MAX_BACKUP_BYTES);
            byte[] b = BackupFiles.readFile(second, EncryptedConfigBackup.MAX_BACKUP_BYTES);
            try { return Arrays.equals(a, b); } finally { EncryptedConfigBackup.clear(a); EncryptedConfigBackup.clear(b); }
        } catch (Exception unreadable) { return false; }
    }

    /** Best effort: a missing marker only drops a UI warning, it never changes user data. */
    private void markSnapshotStale() {
        try {
            File directory = snapshot.getAbsoluteFile().getParentFile();
            if (directory == null || !snapshot.isFile()) return;
            BackupFiles.atomicWrite(new File(directory, STALE_MARKER), STALE_MARKER_BYTES);
        } catch (Exception ignored) { }
    }

    /** A freshly published snapshot is current again, so the warning no longer applies. */
    private void clearStaleMarker() {
        try {
            File directory = snapshot.getAbsoluteFile().getParentFile();
            if (directory == null) return;
            File marker = new File(directory, STALE_MARKER);
            // Leaving the marker only keeps a warning visible; never fail the restore for it.
            if (marker.isFile()) marker.delete();
        } catch (Exception ignored) { }
    }

    /** Seam for the durable write of a rescue copy; production uses the atomic writer. */
    interface RescueWriter { void write(File target, byte[] data) throws Exception; }

    private static final RescueWriter ATOMIC_RESCUE = new RescueWriter() {
        public void write(File target, byte[] data) throws Exception { BackupFiles.atomicWrite(target, data); }
    };

    /**
     * Content-addressed name of the rescue copy that holds these exact bytes.
     *
     * <p>Deliberately identical to {@code GatewayServer.rescueNameFor}: the recovery state
     * already stores a damaged configuration under that name, so preserving the same bytes again
     * must reuse the existing file instead of adding a second copy.
     */
    static String rescueNameFor(byte[] original) {
        if (original == null || original.length == 0) return "config.corrupt-empty.json";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(original);
            try {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
                return "config.corrupt-" + hex + ".json";
            } finally { EncryptedConfigBackup.clear(digest); }
        } catch (Exception unavailableDigest) {
            // Unreachable from a restore, whose CAS anchor already required SHA-256.
            return "config.corrupt-" + System.currentTimeMillis() + ".json";
        }
    }

    static String preserveDamaged(File config, byte[] expectedSha256, byte[] damaged) throws Exception {
        return preserveDamaged(config, expectedSha256, damaged, ATOMIC_RESCUE);
    }

    /**
     * Preserve an unparseable durable configuration in an immutable, content-addressed copy and
     * return that copy's name.
     *
     * <p>Fails closed and writes nothing when the on-disk bytes are no longer the bytes the
     * caller verified, when the copy cannot be written, or when it cannot be read back identical
     * to those bytes. An existing copy is never replaced: identical content is reused, different
     * content under the same name is a conflict.
     */
    static String preserveDamaged(File config, byte[] expectedSha256, byte[] damaged, RescueWriter writer) throws Exception {
        if (config == null || writer == null || expectedSha256 == null || expectedSha256.length != 32 || damaged == null)
            throw new IllegalArgumentException("damaged configuration");
        if (damaged.length > EncryptedConfigBackup.MAX_CONFIG_BYTES) throw new IllegalArgumentException("damaged configuration size");
        File directory = config.getAbsoluteFile().getParentFile();
        if (directory == null || !directory.isDirectory()) throw new IOException("missing directory");
        byte[] disk = BackupFiles.readFile(config, EncryptedConfigBackup.MAX_CONFIG_BYTES);
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(disk);
            boolean unchanged;
            try { unchanged = MessageDigest.isEqual(expectedSha256, actual); }
            finally { EncryptedConfigBackup.clear(actual); }
            // The digest proves the file was not replaced since the preview; the byte comparison
            // proves the copy describes exactly the state that is about to be replaced.
            if (!unchanged || !Arrays.equals(disk, damaged)) throw new IllegalStateException("configuration changed");
        } finally { EncryptedConfigBackup.clear(disk); }
        EncryptedConfigBackup.checkInterrupted();
        String name = rescueNameFor(damaged);
        File rescue = new File(directory, name);
        if (rescue.exists()) {
            if (!rescue.isFile()) throw new IllegalStateException("rescue copy unavailable");
            byte[] existing = BackupFiles.readFile(rescue, EncryptedConfigBackup.MAX_CONFIG_BYTES);
            try { if (!Arrays.equals(existing, damaged)) throw new IllegalStateException("rescue copy conflict"); }
            finally { EncryptedConfigBackup.clear(existing); }
            return name;
        }
        boolean written = false;
        try {
            writer.write(rescue, damaged);
            written = true;
            byte[] back = BackupFiles.readFile(rescue, EncryptedConfigBackup.MAX_CONFIG_BYTES);
            try { if (!Arrays.equals(back, damaged)) throw new IllegalStateException("rescue copy verification failed"); }
            finally { EncryptedConfigBackup.clear(back); }
            return name;
        } catch (Exception failure) {
            // Never leave a file that claims to hold these bytes but does not.
            if (written && rescue.exists() && !rescue.delete())
                failure.addSuppressed(new IOException("rescue copy left behind"));
            throw failure;
        }
    }
}
