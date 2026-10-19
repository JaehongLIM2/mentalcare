package com.mentalcare.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.auth.CredentialStore;
import com.mentalcare.common.ConflictException;
import com.mentalcare.config.AppProperties;
import com.mentalcare.config.SettingsService;
import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.prompt.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup = one zip file: {@code manifest.json}, a consistent snapshot of the SQLite database
 * ({@code VACUUM INTO}), every Markdown archive under {@code sessions/}, and – only when the user asks –
 * the encrypted credential files under {@code credentials/}. Settings (profile, goals, model choice) are
 * inside the database and therefore always included.
 *
 * <p>Restore replaces the counseling data <b>row by row inside one transaction</b> (the live database file
 * is never swapped under the connection pool), then replaces the Markdown folder. Before touching anything
 * it writes a safety snapshot to {@code data/backups/}, so a mistaken restore can itself be undone.
 * It runs under the global maintenance lock: no turn, completion or import can interleave.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    public static final String FORMAT = "mentalcare-backup";
    public static final int FORMAT_VERSION = 1;
    static final String MANIFEST = "manifest.json";
    static final String DB_ENTRY = "mentalcare.db";
    static final String MARKDOWN_PREFIX = "sessions/";
    static final String CREDENTIALS_PREFIX = "credentials/";
    static final Set<String> CREDENTIAL_FILES = Set.of("chatgpt-auth.json", "chatgpt-host.json", ".master.key");
    static final Set<String> REQUIRED_TABLES = Set.of("sessions", "messages", "memories", "session_summaries", "app_settings");
    static final long MAX_ZIP_BYTES = 1024L * 1024 * 1024;
    static final long MAX_TOTAL_UNPACKED = 2L * 1024 * 1024 * 1024;
    static final long MAX_SMALL_ENTRY = 4L * 1024 * 1024;
    static final int MAX_ENTRIES = 20_000;
    static final int KEEP_PRE_RESTORE = 5;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public record Manifest(String format, int version, String app, String appVersion, Instant createdAt,
                           String markdownDir, boolean includesCredentials,
                           long sessions, long messages, long memories, int markdownFiles) {}

    public record Inspection(Instant createdAt, String appVersion, long sessions, long messages, long memories,
                             int markdownFiles, boolean includesCredentials, List<String> warnings) {}

    public record RestoreResult(long sessionsRestored, int markdownFilesRestored, String preRestoreBackup,
                                boolean credentialsRestored, List<String> warnings) {}

    /** Contents of an extracted, validated backup. */
    record Extracted(Manifest manifest, Path db, List<String> markdownFiles, Map<String, byte[]> credentialFiles,
                     List<String> warnings) {}

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final SettingsService settings;
    private final SessionLocks locks;
    private final CredentialStore credentials;
    private final PromptLoader prompts;
    private final Path markdownDir;
    private final Path backupsDir;
    private final String appVersion;

    public BackupService(JdbcClient jdbc, DataSource dataSource, TransactionTemplate tx, ObjectMapper mapper,
                         SettingsService settings, SessionLocks locks, CredentialStore credentials, PromptLoader prompts,
                         AppProperties app,
                         @Value("${mentalcare.backups-dir:${mentalcare.data-dir}/backups}") String backupsDir,
                         @Value("${mentalcare.version:dev}") String appVersion) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = tx;
        this.mapper = mapper;
        this.settings = settings;
        this.locks = locks;
        this.credentials = credentials;
        this.prompts = prompts;
        this.markdownDir = Path.of(app.markdownDir()).toAbsolutePath().normalize();
        this.backupsDir = Path.of(backupsDir).toAbsolutePath().normalize();
        this.appVersion = appVersion;
    }

    public Path backupsDir() {
        return backupsDir;
    }

    /**
     * Copies of counseling data the app keeps on its own: pre-restore snapshots under data/backups and any
     * folder left behind by an interrupted restore. Shown in settings and removed by delete-all.
     */
    public List<Path> safetySnapshots() {
        List<Path> out = new ArrayList<>(leftoverRestoreFolders());
        if (Files.isDirectory(backupsDir)) {
            try (Stream<Path> s = Files.list(backupsDir)) {
                s.filter(p -> p.getFileName().toString().startsWith("before-restore-")).sorted().forEach(out::add);
            } catch (IOException e) {
                // listing failed: report what we have
            }
        }
        return out;
    }

    /**
     * Deletes everything this app wrote under data/backups (safety snapshots and leftover temp files).
     * Downloads the user saved elsewhere are not touched. Caller holds the global maintenance lock.
     * Returns {deleted, failed}.
     */
    public int[] deleteAppBackups() {
        int ok = 0, failed = 0;
        for (Path p : leftoverRestoreFolders()) {
            deleteTree(p);
            if (Files.exists(p)) failed++; else ok++;
        }
        if (!Files.isDirectory(backupsDir)) return new int[]{ok, failed};
        try (Stream<Path> s = Files.list(backupsDir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (!(name.startsWith("before-restore-") || name.startsWith(".download-") || name.startsWith(".snapshot-")
                        || name.startsWith(".upload-") || name.startsWith(".work-"))) continue;
                deleteTree(p);
                if (Files.exists(p)) failed++; else ok++;
            }
        } catch (IOException e) {
            failed++;
        }
        return new int[]{ok, failed};
    }

    // ------------------------------------------------------------------ backup

    /**
     * Writes a backup zip to a temporary file for download and records the backup time. The caller streams
     * and deletes the file. Refused while a delete-all or restore is running.
     */
    public Path createForDownload(boolean includeCredentials) {
        if (!locks.tryLockGlobalRead()) {
            throw new ConflictException("데이터 삭제 또는 복원이 진행 중입니다. 끝난 뒤 백업하세요.", null);
        }
        try {
            Files.createDirectories(backupsDir);
            Path target = backupsDir.resolve(".download-" + UUID.randomUUID() + ".zip");
            writeZip(target, includeCredentials);
            settings.markBackedUp(Instant.now());
            log.info("backup created credentials={} bytes={}", includeCredentials, Files.size(target));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            locks.unlockGlobalRead();
        }
    }

    public String downloadFileName(boolean includeCredentials) {
        return "mentalcare-backup-" + STAMP.format(Instant.now()) + (includeCredentials ? "-with-credentials" : "") + ".zip";
    }

    /** Snapshot + zip. Caller holds a global lock (read for backup, write for restore). */
    void writeZip(Path target, boolean includeCredentials) throws IOException {
        Files.createDirectories(target.getParent());
        Path snapshot = target.resolveSibling(".snapshot-" + UUID.randomUUID() + ".db");
        try {
            // VACUUM INTO writes a transactionally consistent copy even while WAL readers/writers are active.
            jdbc.sql("VACUUM INTO :path").param("path", snapshot.toString()).update();
            List<Path> markdown = markdownFiles();
            Manifest manifest = new Manifest(FORMAT, FORMAT_VERSION, "mentalcare", appVersion, Instant.now(),
                    markdownDir.toString(), includeCredentials, count("sessions"), count("messages"), count("memories"),
                    markdown.size());
            try (OutputStream fileOut = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(fileOut)) {
                put(zip, MANIFEST, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.putNextEntry(new ZipEntry(DB_ENTRY));
                Files.copy(snapshot, zip);
                zip.closeEntry();
                for (Path file : markdown) {
                    String rel = markdownDir.relativize(file).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(MARKDOWN_PREFIX + rel));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
                if (includeCredentials) {
                    for (String name : CREDENTIAL_FILES) {
                        Path f = credentials.directory().resolve(name);
                        if (Files.isRegularFile(f)) put(zip, CREDENTIALS_PREFIX + name, Files.readAllBytes(f));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private List<Path> markdownFiles() throws IOException {
        if (!Files.isDirectory(markdownDir)) return List.of();
        try (Stream<Path> s = Files.walk(markdownDir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    // ------------------------------------------------------------------ inspect

    /** Validates an uploaded backup without changing anything, so the UI can show what a restore would do. */
    public Inspection inspect(Path zip) {
        Path work = workDir("inspect");
        try {
            Extracted e = extract(zip, work);
            return new Inspection(e.manifest().createdAt(), e.manifest().appVersion(), e.manifest().sessions(),
                    e.manifest().messages(), e.manifest().memories(), e.markdownFiles().size(),
                    !e.credentialFiles().isEmpty(), e.warnings());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Replaces all counseling data with the backup's. Requires {@code confirmation == "RESTORE"} from the
     * caller (checked by the controller). Credentials are restored only on request and only on the same
     * kind of machine they were written on (DPAPI blobs do not travel between Windows accounts).
     */
    public RestoreResult restore(Path zip, boolean restoreCredentials) {
        if (!locks.tryLockAll()) {
            throw new ConflictException("진행 중인 상담 작업(응답 생성·종료·가져오기)이 있습니다. 끝난 뒤 다시 시도하세요.", null);
        }
        Path work = null;
        Path staged = null;
        try {
            work = workDir("restore");
            Extracted e = extract(zip, work);
            List<String> warnings = new ArrayList<>(e.warnings());
            if (restoreCredentials && !e.credentialFiles().isEmpty()) {
                credentials.validateRestoreFiles(e.credentialFiles()); // reject before anything changes
            }

            // 1. safety snapshot of the current state
            Files.createDirectories(backupsDir);
            Path safety = backupsDir.resolve("before-restore-" + STAMP.format(Instant.now()) + ".zip");
            writeZip(safety, false);
            pruneSafetySnapshots();

            // 2. the new Markdown folder is fully prepared next to the live one before the database changes
            for (Path stale : leftoverRestoreFolders()) {
                if (stale.getFileName().toString().startsWith(stagingPrefix())) deleteTree(stale);
            }
            staged = stageMarkdown(work, e.markdownFiles());
            final Path stagedDir = staged;

            // 3. database rows and the folder swap commit together: a failure anywhere rolls both back
            Instant lastBackup = settings.lastBackupAt();
            MarkdownSwap swap = new MarkdownSwap(markdownDir, stagedDir);
            long copiedSessions;
            int unresolved;
            try (Connection src = openReadOnly(e.db())) {
                record Copied(long sessions, int unresolved) {}
                Copied copied = tx.execute(status -> {
                    long n = copyAll(src);
                    int missing = relinkMarkdownPaths(e.manifest().markdownDir(), stagedDir);
                    if (lastBackup != null) settings.markBackedUp(lastBackup); // describes this PC, not the backup
                    beforeSwapHook.run();
                    swap.apply(); // throws → transaction rolls back
                    return new Copied(n, missing);
                });
                copiedSessions = copied.sessions();
                unresolved = copied.unresolved();
            } catch (RuntimeException | SQLException ex) {
                swap.undo(); // commit failed after the swap, or the swap itself failed half-way
                throw ex;
            } finally {
                prompts.clearCache();
            }
            staged = null; // now the live folder
            swap.discardPrevious();
            if (unresolved > 0) {
                warnings.add("요약 " + unresolved + "건의 Markdown 파일을 찾지 못해 파일 연결을 비웠습니다 (요약 자체는 복원됨).");
            }

            // 4. credentials (opt-in, validated above). Counseling data is already restored, so a failure here is a warning.
            boolean credentialsRestored = false;
            if (restoreCredentials) {
                if (e.credentialFiles().isEmpty()) {
                    warnings.add("이 백업에는 로그인 자격증명이 들어 있지 않습니다. 설정에서 ChatGPT를 다시 연결하세요.");
                } else {
                    try {
                        credentials.restoreFiles(e.credentialFiles());
                        credentialsRestored = true;
                        warnings.add("자격증명은 백업을 만든 같은 PC·같은 사용자 계정에서만 풀립니다. 연결 상태가 오류로 보이면 다시 로그인하세요.");
                    } catch (com.mentalcare.auth.AuthException ex) {
                        warnings.add("상담 데이터는 복원했지만 로그인 자격증명은 쓰지 못했습니다. 설정에서 ChatGPT를 다시 연결하세요.");
                    }
                }
            }
            int files = e.markdownFiles().size();
            log.info("backup restored sessions={} markdownFiles={} credentials={}", copiedSessions, files, credentialsRestored);
            return new RestoreResult(copiedSessions, files, safety.getFileName().toString(), credentialsRestored, warnings);
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("restore failed: " + ex.getClass().getSimpleName(), ex);
        } finally {
            deleteTree(staged);
            deleteTree(work);
            locks.unlockAll();
        }
    }

    /** Test seam: runs inside the restore transaction right before the Markdown folder is swapped. */
    Runnable beforeSwapHook = () -> { };

    /**
     * Swaps the live Markdown folder with a prepared one by renaming (same parent, same volume), and can put
     * the old folder back. The old folder is deleted only after the database commit succeeded.
     */
    static final class MarkdownSwap {
        private final Path live;
        private final Path staged;
        private Path previous;
        private boolean liveReplaced;

        MarkdownSwap(Path live, Path staged) {
            this.live = live;
            this.staged = staged;
        }

        void apply() {
            try {
                if (Files.exists(live)) {
                    previous = live.resolveSibling("." + live.getFileName() + "-before-restore-" + UUID.randomUUID());
                    Files.move(live, previous);
                }
                Files.move(staged, live);
                liveReplaced = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Restores the folder as it was before {@link #apply()}; safe to call when nothing was moved. */
        void undo() {
            try {
                if (liveReplaced) {
                    Files.move(live, staged); // keep the restored copy aside; it is deleted with the staging dir
                    liveReplaced = false;
                }
                if (previous != null && Files.exists(previous) && !Files.exists(live)) {
                    Files.move(previous, live);
                    previous = null;
                }
            } catch (IOException e) {
                log.error("restore rollback could not move the Markdown folder back: {}", e.getClass().getSimpleName());
            }
        }

        void discardPrevious() {
            deleteTree(previous);
            previous = null;
        }
    }

    /** Copies every table the live schema knows from the backup. Returns the number of sessions restored. */
    private long copyAll(Connection src) {
        try {
            Connection live = DataSourceUtils.getConnection(dataSource);
            try (Statement st = live.createStatement()) {
                st.execute("PRAGMA defer_foreign_keys = ON");
            }
            List<String> liveTables = tables(live);
            Set<String> srcTables = new HashSet<>(tables(src));
            for (int i = liveTables.size() - 1; i >= 0; i--) {
                try (Statement st = live.createStatement()) {
                    st.executeUpdate("DELETE FROM \"" + liveTables.get(i) + "\"");
                }
            }
            long sessions = 0;
            for (String table : liveTables) {
                if (!srcTables.contains(table)) continue;
                long n = copyTable(src, live, table);
                if (table.equals("sessions")) sessions = n;
            }
            return sessions;
        } catch (SQLException e) {
            throw new IllegalStateException("copy failed", e);
        }
    }

    private long copyTable(Connection src, Connection live, String table) throws SQLException {
        List<String> cols = new ArrayList<>(columns(live, table));
        cols.retainAll(columns(src, table));
        if (cols.isEmpty()) return 0;
        String quoted = String.join(", ", cols.stream().map(c -> "\"" + c + "\"").toList());
        String insert = "INSERT INTO \"" + table + "\" (" + quoted + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        long n = 0;
        try (Statement sel = src.createStatement();
             ResultSet rs = sel.executeQuery("SELECT " + quoted + " FROM \"" + table + "\"");
             PreparedStatement ins = live.prepareStatement(insert)) {
            int batch = 0;
            while (rs.next()) {
                for (int i = 1; i <= cols.size(); i++) ins.setObject(i, rs.getObject(i));
                ins.addBatch();
                n++;
                if (++batch == 500) { ins.executeBatch(); batch = 0; }
            }
            if (batch > 0) ins.executeBatch();
        }
        return n;
    }

    /** User tables in creation order (schema.sql lists parents before children). */
    static List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }

    /**
     * Builds the future Markdown folder beside the live one (same parent so the swap is a rename):
     * the backup's archives plus any non-Markdown files the user keeps in the live folder.
     */
    private Path stageMarkdown(Path work, List<String> files) throws IOException {
        Path parent = markdownDir.getParent();
        Files.createDirectories(parent);
        Path staged = parent.resolve(stagingPrefix() + UUID.randomUUID());
        Files.createDirectories(staged);
        try {
            copyIntoStaging(work, files, staged);
        } catch (IOException | RuntimeException e) {
            deleteTree(staged); // never leave a half-built copy of counseling files behind
            if (Files.exists(staged)) log.warn("restore staging folder could not be removed: {}", staged.getFileName());
            throw e;
        }
        return staged;
    }

    private String stagingPrefix() {
        return "." + markdownDir.getFileName() + "-restore-";
    }

    private String displacedPrefix() {
        return "." + markdownDir.getFileName() + "-before-restore-";
    }

    /** Leftover folders from a restore that crashed half-way (they contain counseling Markdown). */
    List<Path> leftoverRestoreFolders() {
        Path parent = markdownDir.getParent();
        if (parent == null || !Files.isDirectory(parent)) return List.of();
        try (Stream<Path> s = Files.list(parent)) {
            return s.filter(Files::isDirectory).filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(stagingPrefix()) || n.startsWith(displacedPrefix());
            }).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void copyIntoStaging(Path work, List<String> files, Path staged) throws IOException {
        if (Files.isDirectory(markdownDir)) {
            try (Stream<Path> s = Files.walk(markdownDir)) {
                for (Path p : s.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().endsWith(".md")).toList()) {
                    Path to = staged.resolve(markdownDir.relativize(p).toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(p, to);
                }
            }
        }
        for (String rel : files) {
            Path from = work.resolve(MARKDOWN_PREFIX).resolve(rel).normalize();
            Path to = staged.resolve(rel).normalize();
            if (!to.startsWith(staged)) continue; // already rejected at extraction; belt and braces
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Summary rows carry the absolute Markdown path of the machine that wrote them. Re-points them to this
     * machine's Markdown folder; rows whose file is missing lose the link. Returns the number unresolved.
     */
    private int relinkMarkdownPaths(String originalDir, Path lookupDir) {
        List<Object[]> rows = jdbc.sql("SELECT session_id, markdown_path FROM session_summaries WHERE markdown_path IS NOT NULL")
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}).list();
        int unresolved = 0;
        for (Object[] row : rows) {
            String rel = relativeMarkdownPath((String) row[1], originalDir);
            // the files are still in the staging folder; the stored path is where they will live after the swap
            Path candidate = rel == null ? null : markdownDir.resolve(rel).normalize();
            Path staged = rel == null ? null : lookupDir.resolve(rel).normalize();
            String resolved = candidate != null && candidate.startsWith(markdownDir)
                    && staged.startsWith(lookupDir) && Files.isRegularFile(staged) ? candidate.toString() : null;
            if (resolved == null) unresolved++;
            jdbc.sql("UPDATE session_summaries SET markdown_path = :p WHERE session_id = :id")
                    .param("p", resolved).param("id", row[0]).update();
        }
        return unresolved;
    }

    /** "C:\...\sessions\2026\2026-10-19.md" with original dir "C:\...\sessions" → "2026/2026-10-19.md". */
    static String relativeMarkdownPath(String path, String originalDir) {
        if (path == null || path.isBlank()) return null;
        List<String> p = segments(path);
        if (originalDir != null && !originalDir.isBlank()) {
            List<String> base = segments(originalDir);
            if (p.size() > base.size() && startsWithIgnoreCase(p, base)) {
                return String.join("/", p.subList(base.size(), p.size()));
            }
        }
        // unknown layout: keep year/file
        return p.size() >= 2 ? p.get(p.size() - 2) + "/" + p.get(p.size() - 1) : p.get(p.size() - 1);
    }

    private static List<String> segments(String path) {
        return Stream.of(path.split("[/\\\\]+")).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean startsWithIgnoreCase(List<String> list, List<String> prefix) {
        for (int i = 0; i < prefix.size(); i++) {
            if (!list.get(i).equalsIgnoreCase(prefix.get(i))) return false;
        }
        return true;
    }

    private void pruneSafetySnapshots() throws IOException {
        try (Stream<Path> s = Files.list(backupsDir)) {
            List<Path> snapshots = s.filter(p -> p.getFileName().toString().startsWith("before-restore-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            for (int i = KEEP_PRE_RESTORE; i < snapshots.size(); i++) Files.deleteIfExists(snapshots.get(i));
        }
    }

    // ------------------------------------------------------------------ extraction & validation

    /**
     * Unpacks only the entries a backup may contain, into {@code work}: no absolute paths, no {@code ..},
     * no unexpected names, bounded sizes. Then validates the manifest and the database file.
     */
    Extracted extract(Path zip, Path work) throws IOException {
        if (Files.size(zip) > MAX_ZIP_BYTES) throw new IllegalArgumentException("백업 파일이 너무 큽니다.");
        Files.createDirectories(work);
        Manifest manifest = null;
        Path db = null;
        List<String> markdown = new ArrayList<>();
        Map<String, byte[]> credentialFiles = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        long total = 0;
        int entries = 0;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("백업 파일의 항목이 너무 많습니다.");
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!isSafeEntryName(name)) throw new IllegalArgumentException("백업 파일에 허용되지 않는 경로가 있습니다.");
                if (name.equals(MANIFEST)) {
                    byte[] bytes = readBounded(zin, MAX_SMALL_ENTRY);
                    total += bytes.length;
                    manifest = parseManifest(bytes);
                } else if (name.equals(DB_ENTRY)) {
                    db = work.resolve(DB_ENTRY);
                    total += copyBounded(zin, db, MAX_ZIP_BYTES);
                } else if (name.startsWith(MARKDOWN_PREFIX) && name.endsWith(".md")) {
                    String rel = name.substring(MARKDOWN_PREFIX.length());
                    Path target = work.resolve(MARKDOWN_PREFIX).resolve(rel).normalize();
                    if (!target.startsWith(work.resolve(MARKDOWN_PREFIX).normalize())) {
                        throw new IllegalArgumentException("백업 파일에 허용되지 않는 경로가 있습니다.");
                    }
                    Files.createDirectories(target.getParent());
                    total += copyBounded(zin, target, MAX_SMALL_ENTRY);
                    markdown.add(rel);
                } else if (name.startsWith(CREDENTIALS_PREFIX) && CREDENTIAL_FILES.contains(name.substring(CREDENTIALS_PREFIX.length()))) {
                    byte[] bytes = readBounded(zin, MAX_SMALL_ENTRY);
                    total += bytes.length;
                    credentialFiles.put(name.substring(CREDENTIALS_PREFIX.length()), bytes);
                } else {
                    warnings.add("알 수 없는 항목을 건너뛰었습니다: " + name);
                    readBounded(zin, MAX_SMALL_ENTRY);
                }
                if (total > MAX_TOTAL_UNPACKED) throw new IllegalArgumentException("백업 파일의 내용이 너무 큽니다.");
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("zip 파일이 아니거나 손상되었습니다.");
        }
        if (manifest == null) throw new IllegalArgumentException("manifest.json이 없습니다. MentalCare 백업 파일이 아닙니다.");
        if (db == null) throw new IllegalArgumentException("백업에 데이터베이스가 없습니다.");
        validateDatabase(db);
        return new Extracted(manifest, db, markdown, credentialFiles, warnings);
    }

    static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.length() > 512) return false;
        if (name.startsWith("/") || name.contains("\\") || name.contains("\0") || name.contains("//")) return false;
        if (name.length() > 1 && name.charAt(1) == ':') return false; // C:...
        for (String seg : name.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
        }
        return true;
    }

    private Manifest parseManifest(byte[] bytes) throws IOException {
        JsonNode n = mapper.readTree(bytes);
        if (!FORMAT.equals(n.path("format").asText()) || !"mentalcare".equals(n.path("app").asText())) {
            throw new IllegalArgumentException("MentalCare 백업 파일이 아닙니다.");
        }
        if (n.path("version").asInt(0) > FORMAT_VERSION) {
            throw new IllegalArgumentException("더 새로운 버전의 앱으로 만든 백업입니다. 앱을 업데이트한 뒤 복원하세요.");
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(n.path("createdAt").asText(""));
        } catch (java.time.format.DateTimeParseException e) {
            createdAt = null;
        }
        return new Manifest(FORMAT, n.path("version").asInt(), "mentalcare", n.path("appVersion").asText(null), createdAt,
                n.path("markdownDir").asText(null), n.path("includesCredentials").asBoolean(false),
                n.path("sessions").asLong(), n.path("messages").asLong(), n.path("memories").asLong(),
                n.path("markdownFiles").asInt());
    }

    private void validateDatabase(Path db) throws IOException {
        try (Connection c = openReadOnly(db)) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new IllegalArgumentException("백업의 데이터베이스가 손상되었습니다.");
                }
            }
            Set<String> present = new HashSet<>(tables(c));
            if (!present.containsAll(REQUIRED_TABLES)) {
                throw new IllegalArgumentException("백업의 데이터베이스에 필요한 테이블이 없습니다.");
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("백업의 데이터베이스를 열 수 없습니다.");
        }
    }

    static Connection openReadOnly(Path db) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath(), config.toProperties());
    }

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
        if (bytes.length > max) throw new IllegalArgumentException("백업 파일의 항목이 너무 큽니다.");
        return bytes;
    }

    private static long copyBounded(InputStream in, Path target, long max) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IllegalArgumentException("백업 파일의 항목이 너무 큽니다.");
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private Path workDir(String label) {
        try {
            Files.createDirectories(backupsDir);
            return Files.createDirectories(backupsDir.resolve(".work-" + label + "-" + UUID.randomUUID()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // temp dir; a leftover is harmless and sits under data/backups
        }
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
