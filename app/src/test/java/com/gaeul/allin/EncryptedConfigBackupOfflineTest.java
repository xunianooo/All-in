package com.gaeul.allin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class EncryptedConfigBackupOfflineTest {
    interface Checked { void run() throws Exception; }
    static void fails(Checked c) throws Exception { try { c.run(); throw new AssertionError("expected failure"); } catch (AssertionError e) { throw e; } catch (Exception expected) {} }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static char[] pass() { return "test passphrase".toCharArray(); }
    static class Fake implements BackupCoordinator.Backend {
        byte[] state; boolean fail;
        Fake(byte[] state) { this.state = state.clone(); }
        public byte[] readConfig() { return state.clone(); }
        public void restoreIfUnchanged(byte[] hash, byte[] replacement, byte[] snapshot) throws Exception {
            if (!MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(state))) throw new IllegalStateException("CAS");
            byte[] old = state.clone();
            try { state = replacement.clone(); if (fail) throw new Exception("injected"); }
            catch (Exception e) { state = old; throw e; }
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] config = bytes("{\"server\":{},\"providers\":[{\"id\":\"test\",\"accounts\":[{}],\"keys\":[\"secret\"]}]}");
        char[] password = pass(); byte[] encrypted = EncryptedConfigBackup.encrypt(config, password);
        for (char c : password) if (c != 0) throw new AssertionError("password uncleared");
        if (!Arrays.equals(config, EncryptedConfigBackup.decrypt(encrypted, pass()))) throw new AssertionError("roundtrip");
        fails(() -> EncryptedConfigBackup.decrypt(encrypted, "wrong".toCharArray()));
        byte[] tampered = encrypted.clone(); tampered[tampered.length - 1] ^= 1; fails(() -> EncryptedConfigBackup.decrypt(tampered, pass()));
        byte[] version = encrypted.clone(); version[4] = 2; fails(() -> EncryptedConfigBackup.decrypt(version, pass()));
        fails(() -> EncryptedConfigBackup.decrypt(new byte[EncryptedConfigBackup.MAX_BACKUP_BYTES + 1], pass()));
        StringBuilder deep = new StringBuilder("{\"server\":{},\"providers\":[],\"x\":");
        for (int i=0;i<50;i++) deep.append('['); for (int i=0;i<50;i++) deep.append(']'); deep.append('}');
        fails(() -> EncryptedConfigBackup.validateConfig(bytes(deep.toString())));
        StringBuilder many = new StringBuilder("{\"server\":{},\"providers\":[],\"x\":[");
        for (int i=0;i<100001;i++) { if(i>0) many.append(','); many.append('0'); } many.append("]}");
        fails(() -> EncryptedConfigBackup.validateConfig(bytes(many.toString())));
        for (String invalid : new String[]{"{providers:[{}],server:{}}", "{\"server\":{\"authKey\":[]},\"providers\":[]}",
            "{\"server\":{},\"providers\":[{\"id\":\"p\",\"accounts\":[1]}]}",
            "{\"server\":{},\"providers\":[{\"id\":\"p\",\"keys\":[true]}]}",
            "{\"server\":{},\"providers\":[{\"id\":\"p\"},{\"id\":\"p\"}]}",
            "{\"server\":{},\"providers\":[],}", "{\"server\":{},\"providers\":[],\"schemaVersion\":99}",
            "{\"server\":{},\"providers\":[],\"affinity\":{\"enabled\":1}}",
            "{\"server\":{},\"providers\":[],\"outbound\":{\"p\":{\"shared\":{\"rpm\":-1}}}}"})
            fails(() -> EncryptedConfigBackup.validateConfig(bytes(invalid)));
        EncryptedConfigBackup.validateConfig(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(java.nio.file.Files.exists(java.nio.file.Paths.get("src/main/assets/default_config.json"))?"src/main/assets/default_config.json":"app/src/main/assets/default_config.json")));
        Fake f = new Fake(config); BackupCoordinator.Preview p = BackupCoordinator.preview(f, encrypted, pass());
        f.state = bytes("{\"server\":{\"port\":1},\"providers\":[]}"); byte[] changed = f.state.clone();
        fails(() -> BackupCoordinator.apply(f, p, pass())); if (!Arrays.equals(changed, f.state)) throw new AssertionError("CAS");
        final Fake failing = new Fake(config); failing.fail = true;
        BackupCoordinator.Preview p2 = BackupCoordinator.preview(failing, encrypted, pass());
        fails(() -> BackupCoordinator.apply(failing, p2, pass())); if (!Arrays.equals(config, failing.state)) throw new AssertionError("rollback");
        Thread.currentThread().interrupt(); fails(() -> EncryptedConfigBackup.decrypt(encrypted, pass())); Thread.interrupted();
        System.out.println("EncryptedConfigBackupOfflineTest OK");
    }
}
