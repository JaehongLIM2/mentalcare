package com.mentalcare.counseling;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Serializes work on counseling data:
 * <ul>
 *   <li>one lock per session so that opening, reply, completion and deletion never interleave
 *       (two tabs, double clicks, "complete" while a reply is still streaming);</li>
 *   <li>a global maintenance lock for operations that touch every session (delete-all, restore): it is
 *       granted only when no other work is running, and while held no work can start. Every write path
 *       (turns, completion, deletion, new session, import, backup, draft save) takes the read side.</li>
 *   <li>a "closed" state for shutdown: once set, no new work is admitted at all.</li>
 * </ul>
 * Callers use try-lock semantics and report "busy" instead of queueing work.
 */
@Component
public class SessionLocks {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** read = work in progress, write = global maintenance (delete-all, restore, idle shutdown check). */
    private final ReentrantReadWriteLock global = new ReentrantReadWriteLock();
    /** Set while the write lock is held, so every reader acquired afterwards observes it. */
    private volatile boolean closed;

    public ReentrantLock of(long sessionId) {
        return locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    }

    /** Acquires the session lock without waiting; false when another turn, a maintenance task or shutdown is in progress. */
    public boolean tryLock(long sessionId) {
        if (!tryLockGlobalRead()) {
            return false;
        }
        if (!of(sessionId).tryLock()) {
            global.readLock().unlock();
            return false;
        }
        return true;
    }

    public void unlock(long sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            global.readLock().unlock();
        }
    }

    /**
     * Participation in the global lock for work that does not own a session lock (new session, archive
     * import, backup, draft save). Hold it until the transaction has committed.
     */
    public boolean tryLockGlobalRead() {
        // isWriteLocked() also covers the maintenance holder's own thread (read-write locks allow downgrading)
        if (closed || global.isWriteLocked() || !global.readLock().tryLock()) {
            return false;
        }
        if (closed) { // shutdown was decided between the first check and the acquisition
            global.readLock().unlock();
            return false;
        }
        return true;
    }

    public void unlockGlobalRead() {
        global.readLock().unlock();
    }

    /** Acquires the global maintenance lock; false while any other work is running or after shutdown. */
    public boolean tryLockAll() {
        if (closed || !global.writeLock().tryLock()) return false;
        if (closed) {
            global.writeLock().unlock();
            return false;
        }
        return true;
    }

    public void unlockAll() {
        if (global.writeLock().isHeldByCurrentThread()) {
            global.writeLock().unlock();
        }
    }

    /**
     * For the in-app quit button: succeeds only when nothing at all is running, and from then on refuses
     * every new piece of work, so the process can exit without cutting a write in half.
     */
    public boolean closeIfIdle() {
        if (closed) return true;
        if (!global.writeLock().tryLock()) return false;
        try {
            closed = true;
            return true;
        } finally {
            global.writeLock().unlock();
        }
    }

    /** For the stop script: refuse new work immediately; running work finishes while the context closes. */
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Work in progress: session turns/completions/deletions, creations, imports, backups, drafts (+1 for maintenance). */
    public int busyCount() {
        return global.getReadLockCount() + (global.isWriteLocked() ? 1 : 0);
    }

    public boolean isBusy(long sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        return lock != null && lock.isLocked();
    }
}
