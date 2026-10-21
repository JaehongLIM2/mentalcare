package com.mentalcare.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lock itself, inside one JVM; the cross-process cases are in {@link DataDirectorySingleInstanceTest}. */
class DataDirectoryLockTest {

    @TempDir Path tmp;

    @Test
    void secondHolderOfTheSameDirectoryIsRefusedEvenWhenSpelledDifferently() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("data"));
        DataDirectoryLock first = DataDirectoryLock.acquire(a);
        try {
            assertThat(first.isHeld()).isTrue();
            assertThatThrownBy(() -> DataDirectoryLock.acquire(tmp.resolve("x/../data")))
                    .isInstanceOf(DataDirectoryLock.DataDirectoryInUseException.class)
                    .hasMessageContaining(a.toAbsolutePath().normalize().toString());
        } finally {
            first.destroy();
        }
    }

    @Test
    void releasedLockCanBeTakenAgainAndTheFileStaysEmpty() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("data"));
        DataDirectoryLock.acquire(dir).destroy();

        DataDirectoryLock again = DataDirectoryLock.acquire(dir);
        again.destroy();

        assertThat(again.isHeld()).isFalse();
        Path file = dir.resolve(DataDirectoryLock.FILE_NAME);
        assertThat(file).exists();
        assertThat(Files.size(file)).isZero(); // nothing personal, nothing at all
    }

    @Test
    void differentDirectoriesDoNotBlockEachOther() throws Exception {
        DataDirectoryLock a = DataDirectoryLock.acquire(Files.createDirectories(tmp.resolve("a")));
        DataDirectoryLock b = DataDirectoryLock.acquire(Files.createDirectories(tmp.resolve("b")));
        assertThat(a.isHeld()).isTrue();
        assertThat(b.isHeld()).isTrue();
        a.destroy();
        b.destroy();
    }
}
