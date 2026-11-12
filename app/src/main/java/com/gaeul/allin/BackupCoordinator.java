package com.gaeul.allin;

import java.security.MessageDigest;
import java.util.Arrays;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/**
 * Restore backend owns quiescing, CAS and the complete atomic commit/rollback transaction.
 *
 * <p>Damaged durable configuration. A {@code config.json} that no longer parses used to abort
 * both the preview and the restore of an authentic backup with the correct password, so a
 * damaged install could not be repaired from a backup at all. Two independent causes existed:
 * the preview validated the current bytes with the same strict schema as the backup, and the
 * restore had to wrap them in the v1 encrypted envelope, which only accepts valid
 * configurations. The exact on-disk bytes are still a sound CAS anchor, so:
 *
 * <ul>
 *   <li>the preview reports the damaged state ({@code currentDamaged}) instead of failing;</li>
 *   <li>the restore preserves those bytes in an immutable, content-addressed rescue copy
 *       before anything is stopped or replaced, and aborts if the backend cannot prove that
 *       the copy exists. A damaged configuration is never silently overwritten.</li>
 * </ul>
 *
 * <p>A damaged configuration cannot become a decryptable rollback snapshot, so the restore
 * passes no rollback ciphertext in that case and the rescue copy is the durable copy of the
 * pre-restore state. For a readable configuration nothing changes: the rollback snapshot keeps
 * its documented password semantics.
 *
 * <p>Missing versus damaged. An absent {@code config.json} is a different state from a damaged
 * one: the gateway bootstraps a missing file from the shipped template, while an empty or
 * unparseable file makes it enter its read-only recovery mode. Restoring an authentic backup over
 * an absent file is therefore <em>refused</em> today: {@link Backend#readConfig()} reports the
 * absence (a thrown {@code IOException} or {@code null}), and {@link #preview} aborts before the
 * gateway is stopped or anything is written. That state is reachable — a failed first-run save
 * keeps the configuration in memory only, and a device whose file was deleted or never written has
 * none — so the refusal is a documented gap, not a guarantee that the file exists:
 *
 * <ul>
 *   <li>absence is not damage, so the damaged path cannot cover it: there are no bytes to rescue
 *       and no bytes to snapshot, and the two states recover differently;<\/li>
 *   <li>absence must not be emulated as zero-length bytes: the v1 envelope rejects them, and a
 *       rollback that wrote them would leave the recovery state instead of "no file";<\/li>
 *   <li>{@link BackupRestoreTransaction} cannot publish a configuration that never existed — its
 *       post-stop CAS re-reads the configuration file — so absence cannot be routed through it
 *       without changing that contract;<\/li>
 *   <li>the confirmation dialog only distinguishes "damaged" from "comparable", and both of its
 *       texts promise guarantees (rescue copy, rollback snapshot) that do not hold when no
 *       configuration existed.<\/li>
 * </ul>
 *
 * <p>Enabling the missing-configuration restore therefore needs, in one coordinated change: a CAS
 * anchor for "still absent" that the transaction can honor (or a separate create-only path), a
 * publish step that refuses to replace a file that appeared meanwhile, a rollback that restores
 * absence without deleting a file this restore did not write, and a dialog state that promises
 * neither a rescue copy nor a rollback snapshot. {@code BackupCoordinatorMissingConfigOfflineTest}
 * pins the current fail-closed invariants.
 */
final class BackupCoordinator {
    interface Backend {
        /** Immutable snapshot taken under the same lock used by every config mutation.
         * Returns null or throws when no durable configuration exists; either way the caller
         * refuses the absent state instead of treating it as an empty document. */
        byte[] readConfig() throws Exception;
        /** Under the mutation/lifecycle lock, reject changed digest, stop/drain requests,
         * persist rollbackCiphertext privately, atomically replace config and restart.
         * On failure restore prior config and restart before returning; never log arguments.
         * A null rollbackCiphertext means the pre-restore configuration was unparseable and is
         * already preserved as a rescue copy; the caller has verified nothing else will
         * overwrite that copy. */
        void restoreIfUnchanged(byte[] expectedSha256, byte[] replacement, byte[] rollbackCiphertext) throws Exception;
        /**
         * Preserve an unparseable current configuration before it is replaced and return the
         * rescue copy's name.
         *
         * <p>Contract: the copy is content addressed (identical bytes always map to the same
         * name), an existing copy is never overwritten with different content, regular saves and
         * later restores never touch it, and the bytes are written durably and read back
         * compared before returning. Return null (or throw) when preservation is impossible; the
         * restore then aborts without stopping the gateway or touching the file. The default
         * keeps backends that cannot preserve a damaged configuration fail-closed.
         */
        default String preserveDamagedConfig(byte[] expectedSha256, byte[] damaged) throws Exception { return null; }
    }
    static final class Preview implements AutoCloseable {
        final JSONObject summary;
        /** True when the durable configuration was not a readable configuration at preview time. */
        final boolean currentDamaged;
        private byte[] replacement, expectedHash;
        /** Rescue copy that holds the preserved pre-restore bytes; empty unless restored. */
        String rescuedAs = "";
        Preview(JSONObject summary, byte[] replacement, byte[] expectedHash, boolean currentDamaged) {
            this.summary = summary; this.replacement = replacement; this.expectedHash = expectedHash;
            this.currentDamaged = currentDamaged;
        }
        public void close() { EncryptedConfigBackup.clear(replacement); EncryptedConfigBackup.clear(expectedHash); replacement = expectedHash = null; }
    }
    static Preview preview(Backend backend, byte[] ciphertext, char[] password) throws Exception {
        byte[] restored = null, current = null;
        try {
            restored = EncryptedConfigBackup.decrypt(ciphertext, password);
            current = backend.readConfig();
            if (current == null) throw new IllegalStateException("configuration unavailable");
            JSONObject incoming = EncryptedConfigBackup.validateConfig(restored);
            // Unreadable durable bytes are a repairable state, not a reason to refuse the backup.
            JSONObject original = readable(current);
            JSONObject summary = new JSONObject();
            JSONArray providers = incoming.getJSONArray("providers");
            int accounts = 0, keys = 0;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i); if (p == null) throw new IllegalArgumentException("provider schema");
                JSONArray a = p.optJSONArray("accounts"), k = p.optJSONArray("keys");
                accounts += a == null ? 0 : a.length(); keys += k == null ? 0 : k.length();
            }
            summary.put("providers", providers.length()); summary.put("accounts", accounts); summary.put("keys", keys);
            boolean damaged = original == null;
            summary.put("currentDamaged", damaged);
            // Damaged bytes can never be confirmed identical to the backup.
            summary.put("serverChanged", damaged
                    || !incoming.getJSONObject("server").toString().equals(original.getJSONObject("server").toString()));
            Preview result = new Preview(summary, restored, digest(current), damaged);
            restored = null; return result;
        } finally { EncryptedConfigBackup.clear(restored); EncryptedConfigBackup.clear(current); EncryptedConfigBackup.clear(password); }
    }
    static void apply(Backend backend, Preview preview, char[] rollbackPassword) throws Exception {
        byte[] current = null, encryptedSnapshot = null;
        try {
            if (preview == null || preview.replacement == null) throw new IllegalStateException("preview expired");
            current = backend.readConfig();
            if (current == null) throw new IllegalStateException("configuration unavailable");
            byte[] actual = digest(current);
            try { if (!MessageDigest.isEqual(actual, preview.expectedHash)) throw new IllegalStateException("configuration changed"); }
            finally { Arrays.fill(actual, (byte)0); }
            EncryptedConfigBackup.checkInterrupted();
            if (preview.currentDamaged) {
                // The v1 envelope only wraps valid configurations, so a damaged pre-restore
                // state cannot become a decryptable rollback snapshot. Preserve the exact bytes
                // first and refuse before any stop/replace when that cannot be proven.
                String rescued = backend.preserveDamagedConfig(preview.expectedHash, current);
                if (rescued == null || rescued.isEmpty())
                    throw new IllegalStateException("damaged configuration cannot be preserved");
                preview.rescuedAs = rescued;
                EncryptedConfigBackup.checkInterrupted();
                backend.restoreIfUnchanged(preview.expectedHash, preview.replacement, null);
            } else {
                encryptedSnapshot = EncryptedConfigBackup.encrypt(current, rollbackPassword);
                EncryptedConfigBackup.checkInterrupted();
                backend.restoreIfUnchanged(preview.expectedHash, preview.replacement, encryptedSnapshot);
            }
        } finally { EncryptedConfigBackup.clear(rollbackPassword); EncryptedConfigBackup.clear(current); EncryptedConfigBackup.clear(encryptedSnapshot); if (preview != null) preview.close(); }
    }
    /** null when the durable bytes are unreadable; that state is recoverable, not fatal. */
    private static JSONObject readable(byte[] current) throws Exception {
        try { return EncryptedConfigBackup.validateConfig(current); }
        catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception damaged) { return null; }
    }
    private static byte[] digest(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
