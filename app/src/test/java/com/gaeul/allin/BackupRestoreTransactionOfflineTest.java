package com.gaeul.allin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

public class BackupRestoreTransactionOfflineTest {
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static final byte[] OLD = bytes("{\"server\":{\"port\":1},\"providers\":[]}");
    static final byte[] NEW = bytes("{\"server\":{\"port\":2},\"providers\":[]}");
    static class Engine implements BackupRestoreTransaction.Engine {
        File file; int stops, starts; boolean failStart;
        Engine(File file) { this.file = file; }
        public byte[] readCurrent() throws Exception { return BackupFiles.readFile(file, EncryptedConfigBackup.MAX_CONFIG_BYTES); }
        public void retireAndStop() { stops++; }
        public void restart() throws Exception { starts++; if (failStart) { failStart = false; throw new Exception("injected restart"); } }
    }
    static void eq(byte[] a, byte[] b) { if (!Arrays.equals(a,b)) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("backup-transaction").toFile();
        File config = new File(dir,"config.json"), snapshot = new File(dir,"rollback.aibk");
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            File staged = BackupFiles.stage(snapshot, bytes("private"));
            try {
                java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(staged.toPath(), java.nio.file.attribute.AclFileAttributeView.class);
                if (view == null) throw new AssertionError("ACL unavailable");
                java.util.List<java.nio.file.attribute.AclEntry> acl = view.getAcl();
                if (acl.size() != 1 || acl.get(0).type() != java.nio.file.attribute.AclEntryType.ALLOW || !acl.get(0).principal().equals(view.getOwner()) || !acl.get(0).permissions().containsAll(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)))
                    throw new AssertionError("staged backup ACL is not owner-only: " + acl);
            } finally { Files.delete(staged.toPath()); }
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(OLD), encrypted = bytes("encrypted fixture");
        for (String stage : new String[]{"stopped","snapshot","replaced","restarted","restart","commitSnapshot"}) {
            Files.write(config.toPath(), OLD); Files.write(snapshot.toPath(),bytes("old snapshot")); Engine engine = new Engine(config); engine.failStart = stage.equals("restart");
            BackupRestoreTransaction tx = new BackupRestoreTransaction(config,snapshot,engine,point -> { if (point.equals(stage)) throw new Exception("injected"); });
            try { tx.apply(hash,NEW,encrypted); throw new AssertionError("accepted " + stage); } catch (AssertionError e) { throw e; } catch (Exception expected) { }
            eq(OLD,Files.readAllBytes(config.toPath())); eq(bytes("old snapshot"),Files.readAllBytes(snapshot.toPath()));
            if (engine.starts < 1) throw new AssertionError("old engine not restarted");
        }
        Files.write(config.toPath(),OLD); Engine engine = new Engine(config);
        BackupRestoreTransaction tx = new BackupRestoreTransaction(config,snapshot,engine,point -> {});
        tx.apply(hash,NEW,encrypted); eq(NEW,Files.readAllBytes(config.toPath())); eq(encrypted,Files.readAllBytes(snapshot.toPath()));
        try { tx.apply(hash,OLD,encrypted); throw new AssertionError("CAS"); } catch (IllegalStateException expected) { }
        eq(NEW,Files.readAllBytes(config.toPath()));
        Files.write(config.toPath(),OLD); Engine changed = new Engine(config) { @Override public void retireAndStop() throws RuntimeException { super.retireAndStop(); try { Files.write(file.toPath(), NEW); } catch(Exception e) { throw new RuntimeException(e); } } };
        try { new BackupRestoreTransaction(config,snapshot,changed,point -> {}).apply(hash,NEW,encrypted); throw new AssertionError("second CAS"); } catch (IllegalStateException expected) { }
        eq(NEW,Files.readAllBytes(config.toPath()));
        Files.write(config.toPath(), OLD); Files.write(snapshot.toPath(), bytes("old snapshot"));
        Engine stopFailure = new Engine(config) { @Override public void retireAndStop() { super.retireAndStop(); if(stops==2) throw new IllegalStateException("stop failure"); } };
        try { new BackupRestoreTransaction(config,snapshot,stopFailure,point -> { if(point.equals("replaced")) throw new Exception("fail"); }).apply(hash,NEW,encrypted); throw new AssertionError("failure missing"); } catch (IllegalStateException expected) { }
        eq(OLD,Files.readAllBytes(config.toPath())); eq(bytes("old snapshot"),Files.readAllBytes(snapshot.toPath()));
        if(stopFailure.starts!=0) throw new AssertionError("must not restart after stop failure");
        System.out.println("BackupRestoreTransactionOfflineTest OK");
    }
}
