package com.gaeul.allin;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class BackupOwnedTaskOfflineTest {
    public static void main(String[] args) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicBoolean activeCleared=new AtomicBoolean(), queuedCleared=new AtomicBoolean();
        BackupOwnedTask active=new BackupOwnedTask() {
            protected void execute() { started.countDown(); try { release.await(); } catch(InterruptedException e) { try { release.await(); } catch(InterruptedException ignored) {} } }
            protected void clear() { activeCleared.set(true); }
        };
        BackupOwnedTask queued=new BackupOwnedTask() { protected void execute() { throw new AssertionError(); } protected void clear() { queuedCleared.set(true); } };
        executor.execute(active); started.await(); executor.execute(queued);
        for(Runnable r:executor.shutdownNow()) ((BackupOwnedTask)r).close();
        active.close();
        if(activeCleared.get() || !queuedCleared.get()) throw new AssertionError("ownership");
        release.countDown(); if(!executor.awaitTermination(5,TimeUnit.SECONDS) || !activeCleared.get()) throw new AssertionError("active cleanup");
        System.out.println("BackupOwnedTaskOfflineTest OK");
    }
}
