package com.gaeul.allin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Private management credential, deliberately independent of the inference configuration. */
final class GatewayManagementCredential {
    private static final String NAME = "gateway-management-credential";
    private static String credential = "";
    private static File initializedDirectory;
    private static String error = "not_initialized";

    private GatewayManagementCredential() {}

    static synchronized void init(File filesDir) {
        if(filesDir!=null && filesDir.getAbsoluteFile().equals(initializedDirectory) && !credential.isEmpty()) return;
        credential = ""; initializedDirectory = null; error = "management_credential_unavailable";
        if (filesDir == null) return;
        File temporary = null;
        try {
            File dir = filesDir.getCanonicalFile();
            if (!dir.isDirectory()) return;
            File target = new File(dir, NAME);
            File lock = new File(dir, NAME + ".lock");
            if (!safePath(lock)) return;
            try (RandomAccessFile lockFile = new RandomAccessFile(lock, "rw")) {
                if (!privatePermissions(lock)) return;
                try (FileLock ignored = lockFile.getChannel().lock()) {
                    if (!safePath(target)) return;
                    if (target.exists()) {
                        credential = read(target);
                        if(!credential.isEmpty()) { initializedDirectory=filesDir.getAbsoluteFile(); error=""; }
                        return;
                    }
                    byte[] random = new byte[32];
                    new SecureRandom().nextBytes(random);
                    char[] hex = new char[64];
                    final char[] alphabet = "0123456789abcdef".toCharArray();
                    for (int i = 0; i < random.length; i++) {
                        hex[i * 2] = alphabet[(random[i] & 255) >>> 4];
                        hex[i * 2 + 1] = alphabet[random[i] & 15];
                    }
                    temporary = File.createTempFile(".gateway-management-", ".tmp", dir);
                    if (!privatePermissions(temporary)) return;
                    try (FileOutputStream out = new FileOutputStream(temporary)) {
                        out.write(new String(hex).getBytes("UTF-8"));
                        out.flush();
                        out.getFD().sync();
                    }
                    // All cooperating processes hold the lock; never replace an existing file.
                    if (!safePath(target) || target.exists() || !temporary.renameTo(target)) return;
                    temporary = null;
                    credential = read(target);
                    if(!credential.isEmpty()) { initializedDirectory=filesDir.getAbsoluteFile(); error=""; }
                }
            }
        } catch (Exception ignored) {
            credential = "";
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    static synchronized String get() { return credential; }
    static synchronized String error() { return error; }

    static synchronized boolean matches(String presented) {
        if (credential.isEmpty() || presented == null || presented.isEmpty()) return false;
        try {
            return MessageDigest.isEqual(credential.getBytes("UTF-8"), presented.getBytes("UTF-8"));
        } catch (Exception ignored) { return false; }
    }

    private static boolean safePath(File file) throws Exception {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) return false;
        // Canonical paths alone do not detect dangling links.
        if(System.getProperty("java.vm.name", "").toLowerCase(java.util.Locale.ROOT).contains("dalvik")) {
            Class<?> os = Class.forName("android.system.Os");
            try { os.getMethod("readlink", String.class).invoke(null, file.getPath()); return false; }
            catch (java.lang.reflect.InvocationTargetException notLinkOrMissing) { return true; }
        }
        Class<?> path = Class.forName("java.nio.file.Path");
        Object value = File.class.getMethod("toPath").invoke(file);
        return !((Boolean) Class.forName("java.nio.file.Files")
                .getMethod("isSymbolicLink", path).invoke(null, value));
    }

    private static boolean privatePermissions(File file) {
        try { PrivacyKeyStore.privateFile(file); return true; }
        catch (java.io.IOException denied) { return false; }
    }

    private static String read(File file) throws Exception {
        if (!safePath(file) || !file.isFile() || file.length() != 64 || !privatePermissions(file)) return "";
        byte[] bytes = new byte[65];
        int length = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while (length < bytes.length && (n = in.read(bytes, length, bytes.length - length)) != -1) length += n;
        }
        if (length != 64) return "";
        for (int i = 0; i < length; i++) {
            int c = bytes[i] & 255;
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return "";
        }
        return new String(bytes, 0, length, "UTF-8");
    }
}
