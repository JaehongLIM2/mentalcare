package com.mentalcare.config;

import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One MentalCare process per data directory, whatever the port or the way it was started (EXE, CMD, java -jar).
 * An OS-level exclusive lock on {@code <data-dir>/mentalcare.lock} is held for the life of the process; the OS
 * drops it when the process ends, even when it is killed, so a leftover file never blocks the next start.
 * The file stays empty and is never deleted (deleting it would let two processes lock two different files).
 */
public final class DataDirectoryLock implements DisposableBean {

    public static final String FILE_NAME = "mentalcare.lock";

    private final Path dataDir;
    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path dataDir, FileChannel channel, FileLock lock) {
        this.dataDir = dataDir;
        this.channel = channel;
        this.lock = lock;
    }

    /** @throws DataDirectoryInUseException when another process (or context in this JVM) holds the directory */
    public static DataDirectoryLock acquire(Path dataDir) {
        Path dir = dataDir.toAbsolutePath().normalize();
        FileChannel ch = null;
        try {
            ch = FileChannel.open(dir.resolve(FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null; // this JVM already holds it
            }
            if (l == null) {
                ch.close();
                throw new DataDirectoryInUseException(dir);
            }
            return new DataDirectoryLock(dir, ch, l);
        } catch (IOException e) {
            if (ch != null) {
                try { ch.close(); } catch (IOException ignored) { }
            }
            throw new IllegalStateException("Cannot lock data directory: " + dir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public boolean isHeld() {
        return lock.isValid();
    }

    @Override
    public void destroy() throws IOException {
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }

    public static final class DataDirectoryInUseException extends IllegalStateException {
        public DataDirectoryInUseException(Path dataDir) {
            super("Another MentalCare process is already using this data directory: " + dataDir
                    + " (stop it first; a different port does not help)");
        }
    }
}
