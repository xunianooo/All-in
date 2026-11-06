package com.gaeul.allin;

import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restore backend owns quiescing, CAS and the complete atomic commit/rollback transaction. */
final class BackupCoordinator {
    interface Backend {
        /** Immutable snapshot taken under the same lock used by every config mutation. */
        byte[] readConfig() throws Exception;
        /** Under the mutation/lifecycle lock, reject changed digest, stop/drain requests,
         * persist rollbackCiphertext privately, atomically replace config and restart.
         * On failure restore prior config and restart before returning; never log arguments. */
        void restoreIfUnchanged(byte[] expectedSha256, byte[] replacement, byte[] rollbackCiphertext) throws Exception;
    }
    static final class Preview implements AutoCloseable {
        final JSONObject summary;
        private byte[] replacement, expectedHash;
        Preview(JSONObject summary, byte[] replacement, byte[] expectedHash) {
            this.summary = summary; this.replacement = replacement; this.expectedHash = expectedHash;
        }
        public void close() { EncryptedConfigBackup.clear(replacement); EncryptedConfigBackup.clear(expectedHash); replacement = expectedHash = null; }
    }
    static Preview preview(Backend backend, byte[] ciphertext, char[] password) throws Exception {
        byte[] restored = null, current = null;
        try {
            restored = EncryptedConfigBackup.decrypt(ciphertext, password);
            current = backend.readConfig();
            JSONObject incoming = EncryptedConfigBackup.validateConfig(restored);
            JSONObject original = EncryptedConfigBackup.validateConfig(current);
            JSONObject summary = new JSONObject();
            JSONArray providers = incoming.getJSONArray("providers");
            int accounts = 0, keys = 0;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i); if (p == null) throw new IllegalArgumentException("provider schema");
                JSONArray a = p.optJSONArray("accounts"), k = p.optJSONArray("keys");
                accounts += a == null ? 0 : a.length(); keys += k == null ? 0 : k.length();
            }
            summary.put("providers", providers.length()); summary.put("accounts", accounts); summary.put("keys", keys);
            summary.put("serverChanged", !incoming.getJSONObject("server").toString().equals(original.getJSONObject("server").toString()));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(current);
            Preview result = new Preview(summary, restored, hash); restored = null; return result;
        } finally { EncryptedConfigBackup.clear(restored); EncryptedConfigBackup.clear(current); EncryptedConfigBackup.clear(password); }
    }
    static void apply(Backend backend, Preview preview, char[] rollbackPassword) throws Exception {
        byte[] current = null, encryptedSnapshot = null;
        try {
            if (preview == null || preview.replacement == null) throw new IllegalStateException("preview expired");
            current = backend.readConfig();
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(current);
            try { if (!MessageDigest.isEqual(actual, preview.expectedHash)) throw new IllegalStateException("configuration changed"); }
            finally { Arrays.fill(actual, (byte)0); }
            encryptedSnapshot = EncryptedConfigBackup.encrypt(current, rollbackPassword);
            EncryptedConfigBackup.checkInterrupted();
            backend.restoreIfUnchanged(preview.expectedHash, preview.replacement, encryptedSnapshot);
        } finally { EncryptedConfigBackup.clear(rollbackPassword); EncryptedConfigBackup.clear(current); EncryptedConfigBackup.clear(encryptedSnapshot); if (preview != null) preview.close(); }
    }
}
