package com.mentalcare.backup;

import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The backups folder cannot be created (a file sits where it should be): restore fails and releases the global lock. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class BackupLockReleaseTest {

    static Path blocker;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) throws Exception {
        TestDirs.register(registry, "backup-lock");
        blocker = Files.createTempFile("mentalcare-backups-blocker", ".txt"); // a file, not a directory
        registry.add("mentalcare.backups-dir", () -> blocker.toString());
    }

    @Autowired BackupService backups;
    @Autowired SessionLocks locks;
    @Autowired SessionService sessions;
    @Autowired SessionCompletionService completion;

    @Test
    void workFolderFailureDoesNotLeaveTheMaintenanceLockHeld() throws Exception {
        Path zip = Files.createTempFile("mentalcare-any", ".zip");
        assertThatThrownBy(() -> backups.restore(zip, false)).isInstanceOf(RuntimeException.class);
        assertThat(locks.busyCount()).isZero();
        var s = sessions.start(null); // would be refused if the write lock were still held
        completion.complete(s.id());
        assertThatThrownBy(() -> backups.createForDownload(false)).isInstanceOf(RuntimeException.class);
        assertThat(locks.busyCount()).isZero();
    }
}
