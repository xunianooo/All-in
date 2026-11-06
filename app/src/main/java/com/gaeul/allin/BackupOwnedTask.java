package com.gaeul.allin;

/** Executor tasks own their secrets from construction until completion or discard. */
abstract class BackupOwnedTask implements Runnable, AutoCloseable {
    private boolean started, closed;
    @Override public final void run() {
        synchronized(this) { if(closed) return; started=true; }
        try { execute(); } finally { synchronized(this) { if(!closed) { closed=true; clear(); } } }
    }
    @Override public final synchronized void close() { if(!started && !closed) { closed=true; clear(); } }
    protected abstract void execute();
    protected abstract void clear();
}
