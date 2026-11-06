package com.gaeul.allin;

import java.io.*;

/** Bounded reads and fsynced, same-directory atomic writes. */
final class BackupFiles {
    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n, total = 0;
        try { while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (n > limit - total) throw new IOException("size limit");
            total += n; out.write(buffer, 0, n);
        } return out.toByteArray(); }
        finally { EncryptedConfigBackup.clear(buffer); }
    }
    static byte[] readFile(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return read(in, limit); }
    }
    static void atomicWrite(File target, byte[] data) throws IOException {
        File temp = stage(target, data);
        try { AtomicConfigFile.replace(temp, target); }
        finally { if (temp.exists()) temp.delete(); }
    }
    static File stage(File target, byte[] data) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("missing directory");
        File temp = File.createTempFile("backup-", ".tmp", parent);
        boolean ready = false;
        try {
            if (!temp.setReadable(false, false) || !temp.setWritable(false, false)
                    || !temp.setReadable(true, true) || !temp.setWritable(true, true)) throw new IOException("permissions");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); out.flush(); out.getFD().sync(); }
            ready = true; return temp;
        } finally { if (!ready) temp.delete(); }
    }
}
