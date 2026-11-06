package com.gaeul.allin;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Durable uncertainty fence for one-use MiniMax refresh credentials.
 * All calls must also be serialized with the authoritative configuration transaction.
 * Never put credentials or account identifiers in logs or filenames. */
final class MiniMaxRefreshJournal {
    private static final Object PROCESS_LOCK = new Object();
    private static final int MAX_RECORDS=512, MAX_BYTES=128*1024;
    private final File directory, keyFile, stateFile, lockFile;
    static final class Ticket {
        private final String account, fingerprint, owner;
        private Ticket(String a,String f,String o) { account=a; fingerprint=f; owner=o; }
    }
    static final class Blocked extends IOException { Blocked() { super("refresh outcome uncertain; sign in again"); } }
    private static final class Record {
        final String account,owner;
        boolean completed;
        Record(String a,String o,boolean done) { account=a; owner=o; completed=done; }
    }
    interface Work<T> { T run(byte[] key,Map<String,Record> records) throws IOException; }
    MiniMaxRefreshJournal(File privateDirectory) {
        if(privateDirectory==null) throw new IllegalArgumentException("private directory required");
        directory=privateDirectory; keyFile=new File(directory,"minimax-refresh.key");
        stateFile=new File(directory,"minimax-refresh.journal"); lockFile=new File(directory,"minimax-refresh.lock");
    }
    private <T> T locked(Work<T> work) throws IOException {
        synchronized(PROCESS_LOCK) {
            if(!directory.isDirectory()) throw new IOException("journal directory unavailable");
            try(RandomAccessFile lock=new RandomAccessFile(lockFile,"rw"); FileChannel channel=lock.getChannel(); FileLock ignored=channel.lock()) {
                byte[] key=key(); Map<String,Record> records=read();
                return work.run(key,records);
            }
        }
    }
    private byte[] key() throws IOException {
        if(!keyFile.exists()) {
            if(stateFile.exists()) throw new IOException("journal key missing");
            byte[] generated=new byte[32]; new SecureRandom().nextBytes(generated);
            AtomicConfigFile.write(keyFile,hex(generated));
            syncDirectory();
        }
        String text=readFile(keyFile,64);
        if(!text.matches("[0-9a-f]{64}")) throw new IOException("invalid journal key");
        byte[] result=new byte[32]; for(int i=0;i<32;i++) result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return result;
    }
    private Map<String,Record> read() throws IOException {
        Map<String,Record> out=new HashMap<>(); if(!stateFile.exists()) return out;
        String text=readFile(stateFile,MAX_BYTES);
        String[] lines=text.split("\n",-1);
        if(lines.length<2 || !lines[lines.length-1].isEmpty()) throw new IOException("invalid journal");
        boolean v1="MINIMAX-JOURNAL-1".equals(lines[0]);
        if(!v1 && !"MINIMAX-JOURNAL-2".equals(lines[0])) throw new IOException("invalid journal version");
        for(int i=1;i<lines.length-1;i++) {
            String[] parts=lines[i].split(" ",-1);
            if(parts.length!=(v1?3:4) || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("[0-9a-f]{64}") || !parts[2].matches("[0-9a-f]{64}") || (!v1 && !parts[3].equals("pending") && !parts[3].equals("spent"))) throw new IOException("invalid journal entry");
            String account=v1?parts[0]:parts[1], fp=v1?parts[1]:parts[0], owner=parts[2];
            Record previous=out.put(fp,new Record(account,owner,!v1 && parts[3].equals("spent")));
            // Multiple legacy accounts can share a token. Preserve one fence, never discard it.
            if(previous!=null && !v1) throw new IOException("duplicate journal fingerprint");
            if(out.size()>MAX_RECORDS) throw new IOException("journal capacity exceeded");
        }
        return out;
    }
    private static String readFile(File f,int max) throws IOException {
        if(!f.isFile() || f.length()>max) throw new IOException("invalid journal file");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(f)) { byte[] buf=new byte[1024]; int n,total=0; while((n=in.read(buf))!=-1) { total+=n; if(total>max) throw new IOException("journal too large"); out.write(buf,0,n); } }
        return new String(out.toByteArray(),StandardCharsets.US_ASCII);
    }
    private void write(Map<String,Record> records) throws IOException {
        if(records.size()>MAX_RECORDS) throw new IOException("journal capacity exceeded");
        StringBuilder text=new StringBuilder("MINIMAX-JOURNAL-2\n");
        for(String fp:new TreeSet<>(records.keySet())) { Record r=records.get(fp); text.append(fp).append(' ').append(r.account).append(' ').append(r.owner).append(' ').append(r.completed?"spent":"pending").append('\n'); }
        AtomicConfigFile.write(stateFile,text.toString());
        syncDirectory();
    }
    void syncDirectory() throws IOException {
        if (AtomicConfigFile.isAndroidRuntime(System.getProperty("java.vm.name", ""))) {
            try {
                java.io.FileDescriptor fd=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
                try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
            } catch (Exception e) { throw new IOException("journal directory sync failed",e); }
        } else DesktopDirectorySync.sync(directory);
    }
    @android.annotation.TargetApi(26)
    private static final class DesktopDirectorySync {
        static void sync(File directory) throws IOException {
            try (FileChannel channel=FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ)) { channel.force(true); }
        }
    }
    private static String hex(byte[] bytes) { char[] digits="0123456789abcdef".toCharArray(), result=new char[bytes.length*2]; for(int i=0;i<bytes.length;i++) { result[2*i]=digits[(bytes[i]>>>4)&15];result[2*i+1]=digits[bytes[i]&15]; } return new String(result); }
    private static String digest(byte[] key,String domain,String value) throws IOException {
        if(value==null || value.isEmpty()) throw new IOException("missing journal identity");
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); mac.update(domain.getBytes(StandardCharsets.UTF_8)); mac.update((byte)0); return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch(GeneralSecurityException e) { throw new IOException("journal HMAC unavailable",e); }
    }
    /** Before the upstream refresh begins. A returned ticket means the fence is persisted. */
    Ticket begin(String accountId,String refresh) throws IOException {
        return locked((key,records)-> {
            String id=digest(key,"account",accountId), fp=digest(key,"refresh",refresh);
            if(records.containsKey(fp)) throw new Blocked();
            if(records.size()>=MAX_RECORDS) throw new IOException("journal capacity exceeded");
            byte[] random=new byte[32]; new SecureRandom().nextBytes(random); String owner=hex(random);
            records.put(fp,new Record(id,owner,false)); write(records); return new Ticket(id,fp,owner);
        });
    }
    /** Mark a durable rotation; the spent fingerprint remains blocked forever. */
    boolean complete(Ticket ticket) throws IOException {
        if(ticket==null) throw new IllegalArgumentException("ticket required");
        return locked((key,records)-> { Record r=records.get(ticket.fingerprint);
            if(r==null || r.completed || !r.owner.equals(ticket.owner) || !r.account.equals(ticket.account)) return false;
            r.completed=true; write(records); return true;
        });
    }
}
