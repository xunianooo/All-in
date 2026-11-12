package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Version 1: PBKDF2-HMAC-SHA256, AES-256-GCM; header is authenticated AAD. */
final class EncryptedConfigBackup {
    static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    static final int HEADER_BYTES = 40, TAG_BYTES = 16, ITERATIONS = 600000;
    static final int MAX_BACKUP_BYTES = HEADER_BYTES + MAX_CONFIG_BYTES + TAG_BYTES;
    static final int MAX_DEPTH = 48, MAX_NODES = 100000;
    private static final int MAGIC = 0x4149424b; // AIBK
    private EncryptedConfigBackup() {}

    static byte[] encrypt(byte[] config, char[] password) throws Exception {
        byte[] key = null;
        try {
            validateConfig(config);
            requirePassword(password);
            checkInterrupted();
            byte[] salt = new byte[16], nonce = new byte[12];
            SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(nonce);
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).put((byte)1)
                    .put((byte)1).putShort((short)0).putInt(ITERATIONS).put(salt).put(nonce).array();
            key = derive(password, salt, ITERATIONS);
            checkInterrupted();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(config);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } finally { clear(key); clear(password); }
    }

    static byte[] decrypt(byte[] backup, char[] password) throws Exception {
        byte[] key = null, plain = null;
        boolean success = false;
        try {
            requirePassword(password);
            checkInterrupted();
            if (backup == null || backup.length < HEADER_BYTES + TAG_BYTES || backup.length > MAX_BACKUP_BYTES)
                throw new IllegalArgumentException("backup size");
            ByteBuffer b = ByteBuffer.wrap(backup);
            if (b.getInt() != MAGIC || b.get() != 1 || b.get() != 1 || b.getShort() != 0)
                throw new IllegalArgumentException("unsupported backup format");
            int iterations = b.getInt();
            // v1 has exactly one work factor; reject attacker controlled CPU amplification and silent weakening.
            if (iterations != ITERATIONS) throw new IllegalArgumentException("unsupported KDF parameters");
            byte[] salt = new byte[16], nonce = new byte[12]; b.get(salt); b.get(nonce);
            key = derive(password, salt, iterations);
            checkInterrupted();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(backup, 0, HEADER_BYTES);
            plain = cipher.doFinal(backup, HEADER_BYTES, backup.length - HEADER_BYTES);
            checkInterrupted();
            validateConfig(plain);
            success = true;
            return plain;
        } finally { clear(key); clear(password); if (!success) clear(plain); }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static void requirePassword(char[] p) {
        if (p == null || p.length == 0 || p.length > 1024) throw new IllegalArgumentException("password length");
    }
    static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("backup interrupted");
    }
    static void clear(byte[] v) { if (v != null) Arrays.fill(v, (byte)0); }
    static void clear(char[] v) { if (v != null) Arrays.fill(v, '\0'); }

    static JSONObject validateConfig(byte[] data) throws Exception {
        if (data == null || data.length == 0 || data.length > MAX_CONFIG_BYTES)
            throw new IllegalArgumentException("config size");
        String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        new BoundedStrictJson(s).run();
        com.gaeul.allin.json.JSONTokener tokener = new com.gaeul.allin.json.JSONTokener(s);
        Object parsed = tokener.nextValue();
        if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("config root");
        JSONObject root = (JSONObject) parsed;
        Object schema = root.opt("schemaVersion");
        if (schema != null && schema != JSONObject.NULL && (!(schema instanceof Number)
                || ((Number)schema).doubleValue() != ((Number)schema).intValue()
                || ((Number)schema).intValue() < 1 || ((Number)schema).intValue() > 4))
            throw new IllegalArgumentException("unsupported config schema");
        int[] count = {0}; countNodes(root, count);
        if (!(root.opt("providers") instanceof JSONArray) || !(root.opt("server") instanceof JSONObject))
            throw new IllegalArgumentException("config schema");
        BackupConfigSchema.validate(root);
        return root;
    }
    private static void countNodes(Object value, int[] count) {
        if (++count[0] > MAX_NODES) throw new IllegalArgumentException("config count");
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value; Iterator<String> it = o.keys();
            while (it.hasNext()) countNodes(o.opt(it.next()), count);
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray)value; for (int i = 0; i < a.length(); i++) countNodes(a.opt(i), count);
        }
    }
}
