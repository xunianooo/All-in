package com.gaeul.allin;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.SecureRandom;

/** Installation-scoped key. A missing key is created only while holding the lock. */
final class PrivacyKeyStore implements PrivacyMasker.KeyProvider {
    interface RandomSource { void nextBytes(byte[] bytes); }
    private static final Object PROCESS_LOCK = new Object();
    private final File dir;
    private final RandomSource random;
    private byte[] cached;

    PrivacyKeyStore(File dir) { this(dir, bytes -> new SecureRandom().nextBytes(bytes)); }
    PrivacyKeyStore(File dir, RandomSource random) {
        if (dir == null || random == null) throw new IllegalArgumentException("Invalid privacy key configuration");
        this.dir = dir; this.random = random;
    }
    public byte[] key() {
        synchronized (PROCESS_LOCK) {
            if (cached != null) return cached.clone();
            try {
                if (!dir.isDirectory()) throw new IOException();
                File lockPath = new File(dir, "privacy-mask.lock");
                try (RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw");
                     FileChannel channel = lockFile.getChannel(); FileLock lock = channel.lock()) {
                    privateFile(lockPath);
                    File target = new File(dir, "privacy-mask.key");
                    if (!target.exists()) {
                        byte[] generated = new byte[32];
                        random.nextBytes(generated);
                        File temp = File.createTempFile("privacy-mask-", ".tmp", dir);
                        try {
                            privateFile(temp);
                            try (FileOutputStream out = new FileOutputStream(temp)) {
                                out.write(generated); out.getFD().sync();
                            }
                            if (!temp.renameTo(target)) throw new IOException();
                        } finally { temp.delete(); java.util.Arrays.fill(generated, (byte) 0); }
                    }
                    if (!target.isFile() || target.length() != 32) throw new IOException();
                    privateFile(target);
                    byte[] key = new byte[32];
                    try (FileInputStream in = new FileInputStream(target)) {
                        int n = 0, read;
                        while (n < key.length && (read = in.read(key, n, key.length - n)) != -1) n += read;
                        if (n != key.length || in.read() != -1) throw new IOException();
                    }
                    cached = key;
                    return key.clone();
                }
            } catch (Exception e) { throw new PrivacyMasker.PrivacyException(); }
        }
    }
    private static void privateFile(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setExecutable(false, false) || !file.setReadable(true, true)
                || !file.setWritable(true, true)) throw new IOException();
    }
}
