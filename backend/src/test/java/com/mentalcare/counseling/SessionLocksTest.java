package com.mentalcare.counseling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Busy accounting and the shutdown gate. */
class SessionLocksTest {

    @Test
    void busyCountIncludesGlobalReadWork() {
        SessionLocks locks = new SessionLocks();
        assertThat(locks.busyCount()).isZero();
        assertThat(locks.tryLockGlobalRead()).isTrue(); // new session / import / backup / draft
        assertThat(locks.busyCount()).isEqualTo(1);
        assertThat(locks.tryLock(7L)).isTrue();          // a turn
        assertThat(locks.busyCount()).isEqualTo(2);
        locks.unlock(7L);
        locks.unlockGlobalRead();
        assertThat(locks.busyCount()).isZero();
    }

    @Test
    void idleShutdownWaitsForEveryKindOfWorkAndThenAdmitsNothing() throws Exception {
        SessionLocks locks = new SessionLocks();
        assertThat(locks.tryLockGlobalRead()).isTrue(); // e.g. a backup download in progress
        // the read lock is held by this thread; the shutdown request arrives on another one
        assertThat(CompletableFuture.supplyAsync(locks::closeIfIdle).get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(locks.isClosed()).isFalse();
        locks.unlockGlobalRead();

        assertThat(CompletableFuture.supplyAsync(locks::closeIfIdle).get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(locks.isClosed()).isTrue();
        assertThat(locks.tryLock(1L)).isFalse();
        assertThat(locks.tryLockGlobalRead()).isFalse();
        assertThat(locks.tryLockAll()).isFalse();
        assertThat(locks.busyCount()).isZero();
    }

    @Test
    void scriptShutdownClosesImmediately() {
        SessionLocks locks = new SessionLocks();
        assertThat(locks.tryLock(3L)).isTrue();
        locks.close();                       // running work is not interrupted …
        assertThat(locks.isBusy(3L)).isTrue();
        assertThat(locks.tryLock(4L)).isFalse(); // … but nothing new starts
        locks.unlock(3L);
    }
}
