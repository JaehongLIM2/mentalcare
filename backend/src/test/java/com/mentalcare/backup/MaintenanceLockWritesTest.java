package com.mentalcare.backup;

import com.mentalcare.common.ConflictException;
import com.mentalcare.config.SettingsService;
import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.domain.Memory;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.MemoryRepository;
import com.mentalcare.repository.SettingsRepository;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Editing a memory and saving settings are writes started by a request, so they take part in the global
 * maintenance lock like turns, imports and drafts: a restore refuses them, they count as running work for the
 * idle shutdown, and the lock is released only after their transaction committed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class MaintenanceLockWritesTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "maintenance-writes");
    }

    @Autowired BackupService backups;
    @Autowired MemoryService memories;
    @Autowired SettingsService settings;
    @Autowired SessionLocks locks;
    @MockitoSpyBean MemoryRepository memoryRepository;
    @MockitoSpyBean SettingsRepository settingsRepository;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        backups.beforeSwapHook = () -> { };
        pool.shutdownNow();
    }

    private Memory someMemory() {
        return memories.addManual("잠금 테스트용 기억 " + System.nanoTime(), "other");
    }

    private SettingsService.SettingsUpdate profile(String text) {
        return new SettingsService.SettingsUpdate(null, null, text, null, null, null, null);
    }

    /** Whether a maintenance task (restore, delete-all, idle shutdown) could start right now, asked from another thread. */
    private boolean maintenanceCouldStart() throws Exception {
        return pool.submit(() -> {
            boolean ok = locks.tryLockAll();
            if (ok) locks.unlockAll();
            return ok;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void memoryAndSettingsEditsAreRefusedWhileARestoreIsRunning() throws Exception {
        Memory m = someMemory();
        settings.updateAll(profile("복원 전 프로필"));
        Path download = backups.createForDownload(false);
        Path zip = Files.createTempFile("mentalcare-maintenance-writes", ".zip");
        Files.copy(download, zip, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(download);

        CountDownLatch inRestore = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        backups.beforeSwapHook = () -> {
            inRestore.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<BackupService.RestoreResult> restore = pool.submit(() -> backups.restore(zip, false));
        try {
            assertThat(inRestore.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> memories.update(m.id(), "복원 중에 고친 기억", null, null))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> settings.updateAll(profile("복원 중에 바꾼 프로필")))
                    .isInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
        }
        restore.get(10, TimeUnit.SECONDS);

        assertThat(memories.get(m.id()).content()).isEqualTo(m.content());
        assertThat(settings.profileSummary()).isEqualTo("복원 전 프로필");
        // the restore released the lock: both writes go through again
        assertThat(memories.update(m.id(), "복원 뒤에 고친 기억", null, null).content()).isEqualTo("복원 뒤에 고친 기억");
        settings.updateAll(profile("복원 뒤 프로필"));
        assertThat(settings.profileSummary()).isEqualTo("복원 뒤 프로필");
        Files.deleteIfExists(zip);
    }

    @Test
    void aMemoryEditInProgressIsBusyWorkThatKeepsMaintenanceAndShutdownOut() throws Exception {
        Memory m = someMemory();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            writing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(memoryRepository).update(anyLong(), any(), any(), any());

        Future<Memory> edit = pool.submit(() -> memories.update(m.id(), "편집 중인 기억", null, null));
        try {
            assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(locks.busyCount()).isEqualTo(1);
            assertThat(maintenanceCouldStart()).isFalse();
            assertThat(locks.closeIfIdle()).isFalse();
        } finally {
            release.countDown();
        }
        assertThat(edit.get(10, TimeUnit.SECONDS).content()).isEqualTo("편집 중인 기억");
        assertThat(locks.busyCount()).isZero();
        assertThat(maintenanceCouldStart()).isTrue();
    }

    @Test
    void aSettingsSaveInProgressIsBusyWorkThatKeepsMaintenanceAndShutdownOut() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            writing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(settingsRepository).set(anyString(), any());

        Future<?> save = pool.submit(() -> settings.updateAll(profile("저장 중인 프로필")));
        try {
            assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(locks.busyCount()).isEqualTo(1);
            assertThat(maintenanceCouldStart()).isFalse();
            assertThat(locks.closeIfIdle()).isFalse();
        } finally {
            release.countDown();
        }
        save.get(10, TimeUnit.SECONDS);
        assertThat(settings.profileSummary()).isEqualTo("저장 중인 프로필");
        assertThat(locks.busyCount()).isZero();
        assertThat(maintenanceCouldStart()).isTrue();
    }

    /** Records, at the moment the surrounding transaction commits, whether the write still holds its lock. */
    private Object recordLockAtCommit(AtomicInteger busyAtCommit, AtomicReference<Boolean> maintenanceAtCommit,
                                      org.mockito.invocation.InvocationOnMock inv) throws Throwable {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                busyAtCommit.set(locks.busyCount());
                try {
                    maintenanceAtCommit.set(maintenanceCouldStart());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        return inv.callRealMethod();
    }

    @Test
    void aMemoryEditKeepsTheLockUntilItsTransactionHasCommitted() throws Exception {
        Memory m = someMemory();
        AtomicInteger busyAtCommit = new AtomicInteger(-1);
        AtomicReference<Boolean> maintenanceAtCommit = new AtomicReference<>();
        doAnswer(inv -> recordLockAtCommit(busyAtCommit, maintenanceAtCommit, inv))
                .when(memoryRepository).update(anyLong(), any(), any(), any());

        memories.update(m.id(), "커밋 순서 확인", null, null);

        assertThat(busyAtCommit.get()).isEqualTo(1);
        assertThat(maintenanceAtCommit.get()).isFalse();
        assertThat(locks.busyCount()).isZero();
    }

    @Test
    void aSettingsSaveKeepsTheLockUntilItsTransactionHasCommitted() throws Exception {
        AtomicInteger busyAtCommit = new AtomicInteger(-1);
        AtomicReference<Boolean> maintenanceAtCommit = new AtomicReference<>();
        doAnswer(inv -> recordLockAtCommit(busyAtCommit, maintenanceAtCommit, inv))
                .when(settingsRepository).set(anyString(), any());

        settings.updateAll(profile("커밋 순서 확인"));

        assertThat(busyAtCommit.get()).isEqualTo(1);
        assertThat(maintenanceAtCommit.get()).isFalse();
        assertThat(locks.busyCount()).isZero();
    }
}
