package com.gaeul.allin;

import java.io.File;
import java.security.MessageDigest;

/** Caller serializes this complete transaction against engine lifecycle operations. */
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
        File staged = null;
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
            staged = BackupFiles.stage(snapshot, rollbackCiphertext);
            fault.at("snapshot");
            EncryptedConfigBackup.checkInterrupted();
            BackupFiles.atomicWrite(config, replacement); replaced = true;
            fault.at("replaced");
            EncryptedConfigBackup.checkInterrupted();
            engine.restart();
            fault.at("restarted");
            EncryptedConfigBackup.checkInterrupted();
            fault.at("commitSnapshot");
            AtomicConfigFile.replace(staged, snapshot);
            staged = null;
        } catch (Exception failure) {
            // Rollback I/O must run even if cancellation interrupted the worker.
            boolean interrupted = Thread.interrupted();
            if (retired) {
                Exception rollbackFailure = null;
                boolean stopped = false, restored = !replaced;
                try { engine.retireAndStop(); stopped = true; }
                catch (Exception e) { rollbackFailure = e; }
                // Retire must precede stop; a failed stop still blocks old writers.
                if (replaced) try { BackupFiles.atomicWrite(config, original); restored = true; }
                catch (Exception e) { if (rollbackFailure == null) rollbackFailure = e; else rollbackFailure.addSuppressed(e); }
                if (stopped && restored) try { engine.restart(); }
                catch (Exception e) { if (rollbackFailure == null) rollbackFailure = e; else rollbackFailure.addSuppressed(e); }
                if (rollbackFailure != null) {
                    failure.addSuppressed(rollbackFailure);
                    if (interrupted) Thread.currentThread().interrupt();
                    throw new IllegalStateException("restore and rollback failed", failure);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw failure;
        } finally { if (staged != null) staged.delete(); EncryptedConfigBackup.clear(original); EncryptedConfigBackup.clear(disk); }
    }
    private static void compare(byte[] expected, byte[] data) throws Exception {
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(data);
        try { if (expected == null || !MessageDigest.isEqual(expected, actual)) throw new IllegalStateException("configuration changed"); }
        finally { EncryptedConfigBackup.clear(actual); }
    }
}
