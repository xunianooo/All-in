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
            if (!AtomicConfigFile.isAndroidRuntime(System.getProperty("java.vm.name", ""))
                    && System.getProperty("os.name", "").startsWith("Windows")) {
                WindowsPrivateFile.restrict(temp);
            } else if (!temp.setReadable(false, false) || !temp.setWritable(false, false)
                    || !temp.setReadable(true, true) || !temp.setWritable(true, true)) throw new IOException("permissions");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); out.flush(); out.getFD().sync(); }
            ready = true; return temp;
        } finally { if (!ready) temp.delete(); }
    }
    // Desktop-only: Windows File.setWritable toggles the DOS readonly attribute and
    // cannot implement POSIX owner-only permissions. Fail closed if ACLs are absent.
    @android.annotation.TargetApi(26)
    private static final class WindowsPrivateFile {
        static void restrict(File file) throws IOException {
            java.nio.file.Path path = file.toPath();
            java.nio.file.attribute.AclFileAttributeView view = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
            if (view == null) throw new IOException("private ACL unavailable");
            java.nio.file.attribute.UserPrincipal owner = view.getOwner();
            java.nio.file.attribute.AclEntry entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();
            view.setAcl(java.util.Collections.singletonList(entry));
            java.util.List<java.nio.file.attribute.AclEntry> actual = view.getAcl();
            if (actual.size() != 1 || actual.get(0).type() != java.nio.file.attribute.AclEntryType.ALLOW
                    || !owner.equals(actual.get(0).principal())
                    || !actual.get(0).permissions().containsAll(entry.permissions())) throw new IOException("private ACL verification failed");
        }
    }
}
