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

    public record Result(int sessionsDeleted, int filesDeleted, int filesFailed) {}

    private final DataDeletionRepository repository;
    private final SessionRepository sessions;
    private final TransactionTemplate tx;
    private final Path markdownDir;

    public DataDeletionService(DataDeletionRepository repository, SessionRepository sessions, TransactionTemplate tx,
                               AppProperties app) {
        this.repository = repository;
        this.sessions = sessions;
        this.tx = tx;
        this.markdownDir = Path.of(app.markdownDir()).toAbsolutePath().normalize();
    }

    public Result deleteSession(long sessionId) {
        sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Session " + sessionId + " not found"));
        List<String> paths = repository.markdownPathsOf(List.of(sessionId));
        tx.executeWithoutResult(status -> repository.deleteSession(sessionId));
        int[] files = deleteFiles(paths);
        log.info("session deleted id={} files={}", sessionId, files[0]);
        return new Result(1, files[0], files[1]);
    }

    /** Wipes everything counseling-related. {@code confirmation} must equal "DELETE". */
    public Result deleteAll(String confirmation) {
        if (!"DELETE".equals(confirmation)) {
            throw new IllegalArgumentException("confirmation must be \"DELETE\"");
        }
        long count = repository.countSessions();
        List<String> paths = repository.allMarkdownPaths();
        tx.executeWithoutResult(status -> repository.deleteAllCounselingData());
        int[] files = deleteFiles(paths);
        // archives written by sessions whose summary rows are already gone
        files = sweepMarkdownDir(files);
        log.info("all counseling data deleted sessions={} files={}", count, files[0]);
        return new Result((int) count, files[0], files[1]);
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
