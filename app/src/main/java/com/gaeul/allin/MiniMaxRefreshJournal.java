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
 * Never put credentials or account identifiers in logs, filenames or exception messages.
 *
 * Crash and fence invariants (each one is exercised by the offline failure tests):
 * 1. begin() returns only after the pending fence is durable; the file data is fsynced before
 *    the rename and the directory entry is fsynced afterwards. The refresh transport therefore
 *    can only run for a token that already has a fence on disk.
 * 2. A fence is never removed, downgraded or reused: a pending record blocks exactly like a
 *    spent one. There is deliberately no abandon/rollback call, because a failed or crashed
 *    refresh can never prove the provider did not already receive the token.
 * 3. Consequently a crash at any instant leaves either no fence and no upstream use, or a
 *    fence that blocks that token forever. "The process died before the rotation was recorded"
 *    must never be read as "the token was not sent".
 * 4. Every failure stays fail-closed: a missing key, an unreadable journal, an empty journal, a
 *    duplicate fingerprint, an oversized file or a full journal all reject the refresh instead
 *    of forgetting fences. A failed write leaves the previous journal in place.
 * 5. stats() is a pure read: it creates no key, no journal and no lock file, and reports counts
 *    only - never fingerprints, account identifiers, tokens or paths. It never hides corruption
 *    behind an empty result, so a caller cannot mistake "unreadable" for "no fences".
 */
class MiniMaxRefreshJournal {
    private static final Object PROCESS_LOCK = new Object();
    private static final int MAX_RECORDS=512, MAX_BYTES=128*1024;
    private static final String HEADER="MINIMAX-JOURNAL-2", LEGACY_HEADER="MINIMAX-JOURNAL-1";
    private final File directory, keyFile, stateFile, lockFile;
    static final class Ticket {
        private final String account, fingerprint, owner;
        private Ticket(String a,String f,String o) { account=a; fingerprint=f; owner=o; }
    }
    static final class Blocked extends IOException { Blocked() { super("refresh outcome uncertain; sign in again"); } }
    /** The journal is full. No fence was written and no refresh attempt started for this token. */
    static final class CapacityExceeded extends IOException { CapacityExceeded(String message) { super(message); } }
    /** Identifier-free read-only diagnostics; primitives only, so it cannot carry a secret.
     * keyPresent reports file presence only - never key validity or any journal content. */
    static final class Stats {
        final boolean journalPresent, keyPresent;
        final int fences, pending, spent, capacity, bytes;
        private Stats(boolean journalPresent,boolean keyPresent,int fences,int pending,int spent,int capacity,int bytes) {
            this.journalPresent=journalPresent; this.keyPresent=keyPresent; this.fences=fences;
            this.pending=pending; this.spent=spent; this.capacity=capacity; this.bytes=bytes;
        }
        @Override public String toString() {
            return "journal{fences="+fences+" pending="+pending+" spent="+spent+" capacity="+capacity
                +" bytes="+bytes+" present="+journalPresent+" keyPresent="+keyPresent+"}";
        }
    }
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
    /** Read-only diagnostics. Deliberately lock-free and write-free: the journal is replaced by an
     * atomic rename, so this observes either the old or the new complete file and nothing else. */
    Stats stats() throws IOException {
        if(!directory.isDirectory()) throw new IOException("journal directory unavailable");
        boolean present=stateFile.exists();
        String text=present?readFile(stateFile,MAX_BYTES):"";
        Map<String,Record> records=present?parse(text):new HashMap<>();
        int pending=0,spent=0;
        for(Record record:records.values()) { if(record.completed) spent++; else pending++; }
        // ASCII fields only, so the character count is the on-disk byte count.
        return new Stats(present,keyFile.isFile(),records.size(),pending,spent,MAX_RECORDS,present?text.length():0);
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
        if(!stateFile.exists()) return new HashMap<>();
        return parse(readFile(stateFile,MAX_BYTES));
    }
    /** Strict parser. An empty journal is corruption, not an empty fence set: only an absent file
     * means "no attempt yet", so truncated content can never re-open a spent token. */
    private Map<String,Record> parse(String text) throws IOException {
        Map<String,Record> out=new HashMap<>();
        String[] lines=text.split("\n",-1);
        if(lines.length<2 || !lines[lines.length-1].isEmpty()) throw new IOException("invalid journal");
        boolean v1=LEGACY_HEADER.equals(lines[0]);
        if(!v1 && !HEADER.equals(lines[0])) throw new IOException("invalid journal version");
        for(int i=1;i<lines.length-1;i++) {
            String[] parts=lines[i].split(" ",-1);
            if(parts.length!=(v1?3:4) || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("[0-9a-f]{64}") || !parts[2].matches("[0-9a-f]{64}") || (!v1 && !parts[3].equals("pending") && !parts[3].equals("spent"))) throw new IOException("invalid journal entry");
            String account=v1?parts[0]:parts[1], fp=v1?parts[1]:parts[0], owner=parts[2];
            Record previous=out.put(fp,new Record(account,owner,!v1 && parts[3].equals("spent")));
            // Multiple legacy accounts can share a token. Preserve one fence, never discard it.
            if(previous!=null && !v1) throw new IOException("duplicate journal fingerprint");
            if(out.size()>MAX_RECORDS) throw new CapacityExceeded("refresh fence journal exceeds capacity");
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
        if(records.size()>MAX_RECORDS) throw new CapacityExceeded("refresh fence journal full");
        StringBuilder text=new StringBuilder(HEADER).append('\n');
        for(String fp:new TreeSet<>(records.keySet())) { Record r=records.get(fp); text.append(fp).append(' ').append(r.account).append(' ').append(r.owner).append(' ').append(r.completed?"spent":"pending").append('\n'); }
        // Every field is fixed-width hex or a fixed keyword, so characters equal bytes. Enforcing
        // the byte bound here keeps a full journal re-readable by readFile()/read() after a restart.
        if(text.length()>MAX_BYTES) throw new CapacityExceeded("refresh fence journal exceeds byte capacity");
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
    /** Before the upstream refresh begins. A returned ticket means the fence is persisted and
     * durable; a thrown exception may still have left a blocking fence behind (that is safe). */
    Ticket begin(String accountId,String refresh) throws IOException {
        return locked((key,records)-> {
            String id=digest(key,"account",accountId), fp=digest(key,"refresh",refresh);
            if(records.containsKey(fp)) throw new Blocked();
            if(records.size()>=MAX_RECORDS) throw new CapacityExceeded("refresh fence journal full");
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
