package com.gaeul.allin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class AtomicConfigFileOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String read(Path p) throws IOException { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
    static final class NoRename extends File {
        NoRename(Path path) { super(path.toString()); }
        @Override public boolean renameTo(File target) { return false; }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("allin atomic config ");
        File target = dir.resolve("config.json").toFile();
        AtomicConfigFile.write(target, "{\"revision\":0}");
        for (int i = 1; i <= 20; i++) {
            AtomicConfigFile.write(target, "{\"revision\":" + i + ",\"text\":\"中文\"}");
            check(new JSONObject(read(target.toPath())).getInt("revision") == i, "replace existing config");
        }
        // Exercise the exact desktop fallback even on Linux.
        Path source = dir.resolve("forced move.tmp");
        Files.write(source, "replacement".getBytes(StandardCharsets.UTF_8));
        AtomicConfigFile.replace(new NoRename(source), target);
        check(read(target.toPath()).equals("replacement") && !Files.exists(source), "desktop atomic replacement");

        // API 24/25 must fail closed without touching java.nio.file on rename failure.
        String vmName = System.getProperty("java.vm.name");
        try {
            System.setProperty("java.vm.name", "Dalvik");
            Files.write(source, "new".getBytes(StandardCharsets.UTF_8));
            try { AtomicConfigFile.replace(new NoRename(source), target); throw new AssertionError("Android rename failure accepted"); }
            catch (IOException expected) {}
            check(read(target.toPath()).equals("replacement") && Files.exists(source), "Android preserves old config on failure");
        } finally {
            if (vmName == null) System.clearProperty("java.vm.name"); else System.setProperty("java.vm.name", vmName);
            Files.deleteIfExists(source);
        }
        check(AtomicConfigFile.isAndroidRuntime("Dalvik") && AtomicConfigFile.isAndroidRuntime("ART"), "Android runtime detection");
        check(!AtomicConfigFile.isAndroidRuntime("OpenJDK 64-Bit Server VM"), "desktop runtime detection");

        File blocked = dir.resolve("blocked.json").toFile();
        check(blocked.mkdir(), "blocked target directory");
        Files.write(blocked.toPath().resolve("keep"), "old".getBytes(StandardCharsets.UTF_8));
        try { AtomicConfigFile.write(blocked, "new"); throw new AssertionError("directory overwritten"); }
        catch (IOException expected) {}
        check(read(blocked.toPath().resolve("keep")).equals("old"), "failed replacement preserves destination");
        File[] temporary = dir.toFile().listFiles((d, name) -> name.startsWith("config-") && name.endsWith(".tmp"));
        check(temporary != null && temporary.length == 0, "failed write cleans temporary file");

        AtomicConfigFile.write(target, "{\"revision\":0}");
        ExecutorService readers = Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Future<?> observer = readers.submit(() -> {
            try { while (running.get()) new JSONObject(read(target.toPath())).getInt("revision"); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        try { for (int i = 1; i <= 60; i++) AtomicConfigFile.write(target, "{\"revision\":" + i + "}"); }
        finally { running.set(false); readers.shutdown(); }
        observer.get(10, TimeUnit.SECONDS);
        check(new JSONObject(read(target.toPath())).getInt("revision") == 60, "concurrent reader sees only complete configs");
        System.out.println("AtomicConfigFileOfflineTest OK");
    }
}
