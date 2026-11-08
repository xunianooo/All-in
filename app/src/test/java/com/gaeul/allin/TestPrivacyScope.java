package com.gaeul.allin;

import java.io.File;
import java.nio.file.Files;

/** Explicit isolated on-disk install scope for JVM fixtures. Never uses real app data. */
final class TestPrivacyScope {
    private static File directory;
    static synchronized void initialize() {
        try {
            if (directory == null) {
                String configured = System.getProperty("allin.test.privateDir");
                directory = configured == null ? Files.createTempDirectory("allin-test-privacy-").toFile() : new File(configured);
                if (!directory.isDirectory()) throw new IllegalStateException("Missing isolated privacy scope");
            }
            PrivacyMasker.initialize(directory);
            GatewayManagementCredential.init(directory);
            if(GatewayManagementCredential.get().isEmpty()) throw new IllegalStateException("Missing isolated management credential");
        } catch (Exception e) { throw new IllegalStateException("Test privacy initialization failed", e); }
    }
}
