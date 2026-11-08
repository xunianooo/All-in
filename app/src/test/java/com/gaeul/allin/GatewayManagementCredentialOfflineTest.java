package com.gaeul.allin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class GatewayManagementCredentialOfflineTest {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("management-test-");
        try {
            File a = Files.createDirectory(root.resolve("a")).toFile();
            File b = Files.createDirectory(root.resolve("b")).toFile();
            GatewayManagementCredential.init(a);
            String first = GatewayManagementCredential.get();
            check(first.matches("[0-9a-f]{64}"), "random format");
            check(GatewayManagementCredential.matches(first), "matching credential");
            check(!GatewayManagementCredential.matches("") && !GatewayManagementCredential.matches(null)
                    && !GatewayManagementCredential.matches(first + "0"), "reject invalid credentials");
            GatewayManagementCredential.init(a);
            check(first.equals(GatewayManagementCredential.get()), "persistence across init");
            GatewayManagementCredential.init(b);
            check(!first.equals(GatewayManagementCredential.get()) && !GatewayManagementCredential.get().isEmpty(), "directory isolation");
            Path target = a.toPath().resolve("gateway-management-credential");
            byte[] broken = "broken".getBytes(StandardCharsets.UTF_8);
            Files.write(target, broken);
            GatewayManagementCredential.init(b);
            GatewayManagementCredential.init(a);
            check(GatewayManagementCredential.get().isEmpty() && !GatewayManagementCredential.matches("broken"), "corruption fails closed");
            check(java.util.Arrays.equals(broken, Files.readAllBytes(target)), "corruption preserved");
            Files.write(target, new byte[1024]);
            GatewayManagementCredential.init(b);
            GatewayManagementCredential.init(a);
            check(GatewayManagementCredential.get().isEmpty() && Files.size(target) == 1024, "oversize preserved");
            GatewayManagementCredential.init(root.resolve("missing").toFile());
            check(GatewayManagementCredential.get().isEmpty(), "failed write never exposes key");
            Files.delete(target);
            Files.createSymbolicLink(target, root.resolve("missing-target"));
            GatewayManagementCredential.init(b);
            GatewayManagementCredential.init(a);
            check(GatewayManagementCredential.get().isEmpty() && Files.isSymbolicLink(target), "symlink rejected");
            System.out.println("GatewayManagementCredentialOfflineTest passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }
}
