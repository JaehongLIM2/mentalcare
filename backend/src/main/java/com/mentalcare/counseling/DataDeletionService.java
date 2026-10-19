package com.mentalcare.counseling;

import com.mentalcare.common.NotFoundException;
import com.mentalcare.config.AppProperties;
import com.mentalcare.repository.DataDeletionRepository;
import com.mentalcare.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Irreversible deletion of counseling data (sessions, messages, check-ins, summaries, memories, tags,
 * safety events, Markdown archives). Settings and OAuth credentials are left alone; signing out is a
 * separate action. Database rows go first (transaction), files afterwards (best effort, confined to the
 * Markdown directory).
 */
@Service
public class DataDeletionService {

    private static final Logger log = LoggerFactory.getLogger(DataDeletionService.class);

    /**
     * @param backupsDeleted   safety snapshots / temp files under data/backups removed (delete-all only)
     * @param backupsRemaining safety snapshots still present afterwards (they contain the deleted data)
     */
    public record Result(int sessionsDeleted, int filesDeleted, int filesFailed, int backupsDeleted, int backupsRemaining) {}

    private final DataDeletionRepository repository;
    private final SessionRepository sessions;
    private final TransactionTemplate tx;
    private final Path markdownDir;
    private final SessionLocks locks;
    private final org.springframework.beans.factory.ObjectProvider<com.mentalcare.backup.BackupService> backups;

    public DataDeletionService(DataDeletionRepository repository, SessionRepository sessions, TransactionTemplate tx,
                               AppProperties app, SessionLocks locks,
                               org.springframework.beans.factory.ObjectProvider<com.mentalcare.backup.BackupService> backups) {
        this.backups = backups;
        this.locks = locks;
        this.repository = repository;
        this.sessions = sessions;
        this.tx = tx;
        this.markdownDir = Path.of(app.markdownDir()).toAbsolutePath().normalize();
    }

    public Result deleteSession(long sessionId) {
        sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Session " + sessionId + " not found"));
        if (!locks.tryLock(sessionId)) {
            throw new com.mentalcare.common.ConflictException("응답 생성이 진행 중입니다. 끝난 뒤 삭제하세요.", sessionId);
        }
        try {
            List<String> paths = repository.markdownPathsOf(List.of(sessionId));
            tx.executeWithoutResult(status -> repository.deleteSession(sessionId));
            int[] files = deleteFiles(paths);
            log.info("session deleted id={} files={}", sessionId, files[0]);
            return new Result(1, files[0], files[1], 0, backups.getObject().safetySnapshots().size());
        } finally {
            locks.unlock(sessionId);
        }
    }

    public Result deleteAll(String confirmation) {
        return deleteAll(confirmation, true);
    }

    /**
     * Wipes everything counseling-related. {@code confirmation} must equal "DELETE". With
     * {@code includeAppBackups} the safety snapshots the app wrote under data/backups go too; backups the
     * user downloaded elsewhere are out of reach and stay.
     */
    public Result deleteAll(String confirmation, boolean includeAppBackups) {
        if (!"DELETE".equals(confirmation)) {
            throw new IllegalArgumentException("confirmation must be \"DELETE\"");
        }
        if (!locks.tryLockAll()) {
            throw new com.mentalcare.common.ConflictException("진행 중인 상담 작업(응답 생성·종료)이 있습니다. 끝난 뒤 다시 시도하세요.", null);
        }
        try {
            return doDeleteAll(includeAppBackups);
        } finally {
            locks.unlockAll();
        }
    }

    private Result doDeleteAll(boolean includeAppBackups) {
        long count = repository.countSessions();
        List<String> paths = repository.allMarkdownPaths();
        tx.executeWithoutResult(status -> repository.deleteAllCounselingData());
        int[] files = deleteFiles(paths);
        // archives written by sessions whose summary rows are already gone
        files = sweepMarkdownDir(files);
        int[] removed = includeAppBackups ? backups.getObject().deleteAppBackups() : new int[]{0, 0};
        int remaining = backups.getObject().safetySnapshots().size();
        log.info("all counseling data deleted sessions={} files={} appBackups={} remaining={}", count, files[0], removed[0], remaining);
        return new Result((int) count, files[0], files[1] + removed[1], removed[0], remaining);
    }

    private int[] deleteFiles(List<String> paths) {
        int ok = 0, failed = 0;
        for (String p : paths) {
            if (p == null) continue;
            try {
                Path file = Path.of(p).toAbsolutePath().normalize();
                if (!file.startsWith(markdownDir)) { // never delete outside the archive folder
                    failed++;
                    continue;
                }
                if (Files.deleteIfExists(file)) ok++;
            } catch (IOException | RuntimeException e) {
                failed++;
            }
        }
        return new int[]{ok, failed};
    }

    private int[] sweepMarkdownDir(int[] counts) {
        if (!Files.isDirectory(markdownDir)) return counts;
        try (Stream<Path> stream = Files.walk(markdownDir)) {
            for (Path p : stream.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                try {
                    if (Files.deleteIfExists(p)) counts[0]++;
                } catch (IOException e) {
                    counts[1]++;
                }
            }
        } catch (IOException e) {
            counts[1]++;
        }
        return counts;
    }
}
