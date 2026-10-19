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
 *   <li>a global maintenance lock for operations that touch every session (delete-all): it is
 *       granted only when no session work is running, and while held no session work can start.</li>
 * </ul>
 * Callers use try-lock semantics and report "busy" instead of queueing work.
 */
@Component
public class SessionLocks {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** read = per-session work in progress, write = global maintenance (delete-all). */
    private final ReentrantReadWriteLock global = new ReentrantReadWriteLock();

    public ReentrantLock of(long sessionId) {
        return locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    }

    /** Acquires the session lock without waiting; false when another turn or a delete-all is in progress. */
    public boolean tryLock(long sessionId) {
        // isWriteLocked() also covers the maintenance holder's own thread (read-write locks allow downgrading)
        if (global.isWriteLocked() || !global.readLock().tryLock()) {
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

    /** Acquires the global maintenance lock; false while any session work is running. */
    public boolean tryLockAll() {
        return global.writeLock().tryLock();
    }

    public void unlockAll() {
        if (global.writeLock().isHeldByCurrentThread()) {
            global.writeLock().unlock();
        }
    }

    public boolean isBusy(long sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        return lock != null && lock.isLocked();
    }
}
