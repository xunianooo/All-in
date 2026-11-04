package com.gaeul.allin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Same-directory, fsynced configuration replacement; never truncates the live file. */
final class AtomicConfigFile {
    private AtomicConfigFile() {}

    static void write(File target, String json) throws IOException {
        File absolute = target.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Configuration directory missing");
        File temporary = File.createTempFile("config-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.getBytes("UTF-8"));
                output.flush();
                output.getFD().sync();
            }
            replace(temporary, absolute);
        } finally {
            // A failed replacement leaves the previous configuration untouched.
            if (temporary.exists()) temporary.delete();
        }
    }

    static void replace(File source, File target) throws IOException {
        if (!source.getCanonicalFile().getParentFile().equals(target.getCanonicalFile().getParentFile()))
            throw new IOException("Configuration replacement must stay in the same directory");
        // Android's Linux rename supports replacing an existing file, including API 24/25.
        if (source.renameTo(target)) return;
        // java.nio.file is unavailable on Android 7.x. Keep all such references in a
        // separate desktop-only class and fail closed on Android if rename failed.
        if (isAndroidRuntime(System.getProperty("java.vm.name", "")))
            throw new IOException("Atomic configuration rename failed");
        DesktopMove.replace(source, target);
    }

    static boolean isAndroidRuntime(String vmName) {
        return "Dalvik".equals(vmName) || "ART".equals(vmName);
    }

    @android.annotation.TargetApi(26)
    private static final class DesktopMove {
        static void replace(File source, File target) throws IOException {
            // On Windows File.renameTo refuses an existing destination. OpenJDK's
            // atomic move uses replacement semantics. Unsupported filesystems fail
            // without falling back to deleting or copying over the live file.
            for (int attempt = 0; ; attempt++) {
                try {
                    java.nio.file.Files.move(source.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    return;
                } catch (java.nio.file.AccessDeniedException denied) {
                    // Windows readers/virus scanners may briefly hold a handle without
                    // delete sharing. Retry only while both files remain ordinary files;
                    // a permanent denial still fails without altering the destination.
                    if (attempt >= 49 || !source.isFile() || !target.isFile()) throw denied;
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while replacing configuration", interrupted);
                    }
                }
            }
        }
    }
}
