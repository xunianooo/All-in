package com.gaeul.allin;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class PrivacyMaskerOfflineTest {
    static void check(boolean x) { if (!x) throw new AssertionError(); }
    static void fails(Runnable r) { try { r.run(); throw new AssertionError(); } catch (PrivacyMasker.PrivacyException expected) {} }
    static String token(PrivacyMasker.Session s, String value, String type) { return PrivacyMasker.getOrCreatePlaceholder(s, value, type); }
    static void checkPrivate(File file) throws Exception {
        java.nio.file.Path path = file.toPath();
        if (File.separatorChar == '\\') {
            java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(
                    path, java.nio.file.attribute.AclFileAttributeView.class);
            check(view != null);
            List<java.nio.file.attribute.AclEntry> acl = view.getAcl();
            check(acl.size() == 1);
            java.nio.file.attribute.AclEntry entry = acl.get(0);
            check(entry.type() == java.nio.file.attribute.AclEntryType.ALLOW
                    && entry.principal().equals(view.getOwner()) && entry.flags().isEmpty()
                    && entry.permissions().equals(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)));
        } else {
            check(Files.getPosixFilePermissions(path).equals(EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--child")) {
            PrivacyMasker.initialize(new File(args[1]));
            System.out.print(token(new PrivacyMasker.Session(), "child-value", "TEST"));
            return;
        }
        File a = Files.createTempDirectory("privacy-a").toFile(), b = Files.createTempDirectory("privacy-b").toFile();
        PrivacyMasker.setProviderForTest(null);
        PrivacyMasker.Session unset = new PrivacyMasker.Session();
        check(PrivacyMasker.maskText("nothing", unset).equals("nothing"));
        fails(() -> PrivacyMasker.maskText("a@example.com", unset));
        PrivacyMasker.Config cfg = new PrivacyMasker.Config(); cfg.customPatterns.add("secret");
        fails(() -> PrivacyMasker.maskText("secret", unset, cfg));
        PrivacyMasker.initialize(a);
        PrivacyMasker.Session one = new PrivacyMasker.Session();
        String t = token(one, "abc", "A");
        checkPrivate(new File(a, "privacy-mask.key"));
        checkPrivate(new File(a, "privacy-mask.lock"));
        check(t.matches("\\{\\{MASK_A_[0-9a-f]{24}\\}\\}"));
        check(!t.equals(token(one, "abc", "B")));
        PrivacyMasker.initialize(a);
        check(t.equals(token(new PrivacyMasker.Session(), "abc", "A")));
        PrivacyMasker.initialize(b);
        check(!t.equals(token(new PrivacyMasker.Session(), "abc", "A")));
        check(t.equals(token(one, "abc", "A")));
        PrivacyMasker.initialize(a);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        for (int i=0; i<32; i++) futures.add(pool.submit(() -> token(new PrivacyMasker.Session(), "abc", "A")));
        for (Future<String> f : futures) check(t.equals(f.get()));
        pool.shutdown();
        PrivacyMasker.KeyProvider key = () -> new byte[32];
        PrivacyMasker.DigestProvider collide = (k, type, value) -> { byte[] d = new byte[32]; d[12] = (byte) value.charAt(0); return d; };
        PrivacyMasker.Session cs = new PrivacyMasker.Session(key, collide);
        String c1 = token(cs, "a", "A"), c2 = token(cs, "b", "A");
        check(!c1.equals(c2) && c2.length() == c1.length() + 2 && cs.placeholderToReal.get(c1).equals("a"));
        PrivacyMasker.Session full = new PrivacyMasker.Session(key, (k, type, value) -> new byte[32]);
        token(full, "a", "A"); fails(() -> token(full, "b", "A"));
        fails(() -> token(new PrivacyMasker.Session(key, (k, type, v) -> { throw new Exception(); }), "x", "A"));
        fails(() -> token(new PrivacyMasker.Session(() -> { throw new IllegalStateException(); }, collide), "x", "A"));
        File bad = Files.createTempDirectory("privacy-bad").toFile();
        Files.write(new File(bad, "privacy-mask.key").toPath(), new byte[1]);
        fails(() -> new PrivacyKeyStore(bad).key());
        File randomFail = Files.createTempDirectory("privacy-random").toFile();
        fails(() -> new PrivacyKeyStore(randomFail, bytes -> { throw new IllegalStateException(); }).key());
        fails(() -> new PrivacyKeyStore(new File(bad, "missing")).key());
        PrivacyMasker.Session legacy = new PrivacyMasker.Session();
        legacy.placeholderToReal.put("{{MASK_X_ab}}", "{{MASK_Y_cd}}");
        legacy.placeholderToReal.put("{{MASK_Y_cd}}", "secret");
        check(PrivacyMasker.unmaskText("{{MASK_X_ab}} {{MASK_Z_ab}}", legacy).equals("{{MASK_Y_cd}} {{MASK_Z_ab}}"));
        PrivacyMasker.Session reserved = new PrivacyMasker.Session(key, (k, type, v) -> new byte[32]);
        String existing = "{{MASK_CUSTOM_000000000000000000000000}}";
        PrivacyMasker.Config custom = new PrivacyMasker.Config(); custom.customPatterns.add("sensitive");
        String masked = PrivacyMasker.maskText(existing + " sensitive", reserved, custom);
        check(masked.startsWith(existing) && !reserved.placeholderToReal.containsKey(existing));
        check(PrivacyMasker.checkMatch("x@example.com", new PrivacyMasker.Config()));
        check(!PrivacyMasker.checkMatch(existing, custom));
        PrivacyMasker.Config empty = new PrivacyMasker.Config();
        empty.maskApiKeys = empty.maskEmails = empty.maskPhones = false;
        empty.customPatterns.add(""); empty.customPatterns.add("(?=x)");
        check(!PrivacyMasker.checkMatch("x", empty));
        check(PrivacyMasker.maskText("x", unset, empty).equals("x"));
        PrivacyMasker.Config invalid = new PrivacyMasker.Config();
        invalid.customPatterns.add("[");
        fails(() -> PrivacyMasker.checkMatch("sensitive plaintext", invalid));
        fails(() -> PrivacyMasker.maskText("sensitive plaintext", unset, invalid));
        fails(() -> PrivacyMasker.checkMatch("a@example.com", invalid));
        invalid.enabled = false;
        check(!PrivacyMasker.checkMatch("a@example.com", invalid));
        check(PrivacyMasker.maskText("a@example.com", unset, invalid).equals("a@example.com"));
        PrivacyMasker.Config broad = new PrivacyMasker.Config();
        broad.customPatterns.add("MASK|[0-9a-f]{24}|sensitive");
        String protectedResult = PrivacyMasker.maskText(existing + " sensitive", new PrivacyMasker.Session(key, collide), broad);
        check(protectedResult.startsWith(existing + " "));
        check(protectedResult.endsWith("}}"));
        PrivacyMasker.Session conflict = new PrivacyMasker.Session(key, collide);
        String live = token(conflict, "a", "A");
        fails(() -> PrivacyMasker.maskText(live, conflict));
        PrivacyMasker.Session fork = cs.fork();
        check(fork.interceptedCount == cs.interceptedCount && fork.placeholderToReal.equals(cs.placeholderToReal));
        PrivacyMasker.Session copied = new PrivacyMasker.Session(); copied.replaceFrom(fork);
        check(token(copied, "a", "A").equals(c1));
        PrivacyMasker.Session fc = full.fork();
        fails(() -> token(fc, "c", "A"));
        PrivacyMasker.Session reserveOnly = new PrivacyMasker.Session(key, (k, type, v) -> new byte[32]);
        PrivacyMasker.maskText(existing, reserveOnly, custom);
        PrivacyMasker.Session rc = reserveOnly.fork();
        String reservedToken = token(rc, "other", "CUSTOM");
        check(!reservedToken.equals(existing));
        PrivacyMasker.Session raw = new PrivacyMasker.Session();
        raw.placeholderToReal.put("{{MASK_X}}", "A");
        raw.placeholderToReal.put("{{MASK_X}}suffix", "B");
        check(PrivacyMasker.unmaskText("{{MASK_X}}suffix {{MASK_X}}", raw).equals("B A"));
        File processDir = Files.createTempDirectory("privacy-process").toFile();
        String javaBin = new File(System.getProperty("java.home"), "bin/java").getPath();
        String cp = System.getProperty("java.class.path");
        Process p1 = new ProcessBuilder(javaBin, "-cp", cp, PrivacyMaskerOfflineTest.class.getName(), "--child", processDir.toString()).start();
        Process p2 = new ProcessBuilder(javaBin, "-cp", cp, PrivacyMaskerOfflineTest.class.getName(), "--child", processDir.toString()).start();
        String out1 = new String(p1.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String out2 = new String(p2.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(p1.waitFor() == 0 && p2.waitFor() == 0 && out1.equals(out2) && out1.matches("\\{\\{MASK_TEST_[0-9a-f]{24}\\}\\}"));
        Process p3 = new ProcessBuilder(javaBin, "-cp", cp, PrivacyMaskerOfflineTest.class.getName(), "--child", processDir.toString()).start();
        String out3 = new String(p3.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(p3.waitFor() == 0 && out1.equals(out3));
        System.out.println("PrivacyMaskerOfflineTest passed");
    }
}
