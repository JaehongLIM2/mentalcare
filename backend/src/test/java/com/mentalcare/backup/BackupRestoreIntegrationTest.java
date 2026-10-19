package com.mentalcare.backup;

import com.mentalcare.auth.CredentialStore;
import com.mentalcare.common.ConflictException;
import com.mentalcare.config.SettingsService;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.DataDeletionService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.SummaryRepository;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupRestoreIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "backup");
    }

    static class Listener implements TurnListener {
        final List<String> errors = new ArrayList<>();
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) {}
        @Override public void onError(String m, String k) { errors.add(k); }
    }

    @Autowired BackupService backups;
    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired DataDeletionService deletion;
    @Autowired MemoryService memories;
    @Autowired SummaryRepository summaries;
    @Autowired SettingsService settings;
    @Autowired CredentialStore credentials;
    @Autowired MockMvc mvc;

    static Path backupZip;
    static long originalSessionId;
    static String originalMarkdown;

    @Test
    @Order(1)
    void backupContainsDatabaseMarkdownAndNoCredentialsByDefault() throws Exception {
        llm.reset();
        settings.updateAll(new SettingsService.SettingsUpdate(null, null, "백업 테스트 프로필", null, null, null, null));
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "백업 전에 남긴 이야기", new Listener());
        var result = completion.complete(s.id());
        originalSessionId = s.id();
        originalMarkdown = result.summary().markdownPath();
        assertThat(originalMarkdown).isNotNull();
        credentials.hostId(); // creates the non-secret host file

        assertThat(settings.lastBackupAt()).isNull();
        Path zip = backups.createForDownload(false);
        backupZip = Files.createTempFile("mentalcare-backup-test", ".zip");
        Files.copy(zip, backupZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(zip);

        try (ZipFile z = new ZipFile(backupZip.toFile())) {
            List<String> names = z.stream().map(ZipEntry::getName).toList();
            assertThat(names).contains("manifest.json", "mentalcare.db");
            assertThat(names).anyMatch(n -> n.startsWith("sessions/") && n.endsWith(".md"));
            assertThat(names).noneMatch(n -> n.startsWith("credentials/"));
            String manifest = new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"format\" : \"mentalcare-backup\"").contains("\"includesCredentials\" : false");
        }
        assertThat(settings.lastBackupAt()).isNotNull();
        assertThat(backups.inspect(backupZip).sessions()).isEqualTo(1);

        // opt-in: the (encrypted / non-secret) credential files travel with the backup
        Path withCreds = backups.createForDownload(true);
        try (ZipFile z = new ZipFile(withCreds.toFile())) {
            assertThat(z.stream().map(ZipEntry::getName).toList()).contains("credentials/chatgpt-host.json");
        }
        Files.deleteIfExists(withCreds);
    }

    @Test
    @Order(2)
    void restoreReplacesCurrentDataAndLeavesASafetySnapshot() throws Exception {
        // change the world after the backup
        deletion.deleteAll("DELETE");
        settings.updateAll(new SettingsService.SettingsUpdate(null, null, "복원 전 바뀐 프로필", null, null, null, null));
        Session later = sessions.start(null);
        counseling.opening(later.id(), new Listener());
        counseling.reply(later.id(), "백업 이후의 상담", new Listener());
        completion.complete(later.id());
        assertThat(sessions.count()).isEqualTo(1);
        Instant lastBackup = settings.lastBackupAt();

        BackupService.RestoreResult r = backups.restore(backupZip, false);

        assertThat(r.sessionsRestored()).isEqualTo(1);
        assertThat(r.markdownFilesRestored()).isGreaterThanOrEqualTo(1);
        assertThat(r.credentialsRestored()).isFalse();
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(sessions.detail(originalSessionId).messages()).extracting(Message::content).contains("백업 전에 남긴 이야기");
        assertThat(sessions.detail(originalSessionId).summary()).isNotNull();
        assertThatThrownBy(() -> sessions.detail(later.id())).hasMessageContaining("not found");
        assertThat(settings.profileSummary()).isEqualTo("백업 테스트 프로필");
        assertThat(settings.lastBackupAt()).isEqualTo(lastBackup); // describes this PC, not the backup
        assertThat(memories.memoriesForDeduplication()).isNotEmpty();

        // Markdown is back and the summary points at the restored file
        String path = summaries.findBySession(originalSessionId).orElseThrow().markdownPath();
        assertThat(path).isNotNull();
        assertThat(Files.isRegularFile(Path.of(path))).isTrue();
        assertThat(Files.readString(Path.of(path))).contains("# 상담 기록");

        // the state before the restore can itself be restored
        try (Stream<Path> files = Files.list(backups.backupsDir())) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("before-restore-")).count()).isEqualTo(1);
        }
        try (Stream<Path> files = Files.list(backups.backupsDir())) {
            assertThat(files.map(p -> p.getFileName().toString()).noneMatch(n -> n.startsWith(".work-"))).isTrue();
        }

        // the app keeps working on the restored rows (prompt version ids, new sessions, memory counts)
        Session next = sessions.start(null);
        Listener l = new Listener();
        counseling.opening(next.id(), l);
        assertThat(l.errors).isEmpty();
        completion.complete(next.id());
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    @Order(3)
    void restoreIsRefusedWhileATurnIsRunningAndRejectsBadArchives() throws Exception {
        llm.reset();
        Session s = sessions.start(null);
        CountDownLatch gate = new CountDownLatch(1);
        llm.gate = gate;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> turn = pool.submit(() -> counseling.opening(s.id(), new Listener()));
            Thread.sleep(300);
            assertThatThrownBy(() -> backups.restore(backupZip, false)).isInstanceOf(ConflictException.class);
            Files.deleteIfExists(backups.createForDownload(false)); // backups may run beside turns
            gate.countDown();
            turn.get(5, TimeUnit.SECONDS);
        } finally {
            llm.gate = null;
            pool.shutdownNow();
            completion.complete(s.id());
        }
        long before = sessions.count();

        // zip-slip entry
        Path slip = zipOf("sessions/../../evil.md", "x", "manifest.json", "{}");
        assertThatThrownBy(() -> backups.restore(slip, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("경로");
        // foreign zip
        Path foreign = zipOf("readme.txt", "hello");
        assertThatThrownBy(() -> backups.inspect(foreign)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest");
        // right manifest, broken database
        Path broken = zipOf("manifest.json", "{\"format\":\"mentalcare-backup\",\"app\":\"mentalcare\",\"version\":1}", "mentalcare.db", "not a database");
        assertThatThrownBy(() -> backups.restore(broken, false)).isInstanceOf(IllegalArgumentException.class);
        // newer format
        Path newer = zipOf("manifest.json", "{\"format\":\"mentalcare-backup\",\"app\":\"mentalcare\",\"version\":99}", "mentalcare.db", "x");
        assertThatThrownBy(() -> backups.inspect(newer)).hasMessageContaining("새로운 버전");
        // not a zip at all
        Path text = Files.createTempFile("notzip", ".zip");
        Files.writeString(text, "plain text");
        assertThatThrownBy(() -> backups.inspect(text)).isInstanceOf(IllegalArgumentException.class);

        assertThat(sessions.count()).isEqualTo(before); // nothing was touched
        assertThat(backups.restore(backupZip, false).sessionsRestored()).isEqualTo(1); // still restorable afterwards
    }

    @Test
    @Order(4)
    void httpEndpointsStreamTheZipAndDemandConfirmation() throws Exception {
        MvcResult started = mvc.perform(get("/api/backup").header("Host", "127.0.0.1:8080"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mentalcare-backup-")))
                .andReturn();
        byte[] body = done.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(1000);
        assertThat(new String(body, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        try (Stream<Path> files = Files.list(backups.backupsDir())) {
            assertThat(files.map(p -> p.getFileName().toString()).noneMatch(n -> n.startsWith(".download-"))).isTrue();
        }

        MockMultipartFile upload = new MockMultipartFile("file", "b.zip", "application/zip", body);
        mvc.perform(multipart("/api/backup/inspect").file(upload).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions").value(1))
                .andExpect(jsonPath("$.includesCredentials").value(false));
        mvc.perform(multipart("/api/backup/restore").file(upload).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/backup/restore").file(upload).param("confirmation", "RESTORE").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionsRestored").value(1));
        mvc.perform(multipart("/api/backup/restore").file(upload).param("confirmation", "RESTORE")
                        .header("Host", "127.0.0.1:8080").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void aFailureAfterTheDatabaseCopyRollsBackRowsAndFiles() throws Exception {
        // current state: the two sessions restored by the previous test plus their Markdown files
        Session extra = sessions.start(null);
        counseling.opening(extra.id(), new Listener());
        counseling.reply(extra.id(), "복원 실패 전에 있던 상담", new Listener());
        completion.complete(extra.id());
        long before = sessions.count();
        Path markdownDir = Path.of(summaries.findBySession(extra.id()).orElseThrow().markdownPath()).getParent().getParent();
        List<String> filesBefore = listMd(markdownDir);
        assertThat(filesBefore).isNotEmpty();
        String profileBefore = settings.profileSummary();

        backups.beforeSwapHook = () -> { throw new IllegalStateException("simulated failure during restore"); };
        try {
            assertThatThrownBy(() -> backups.restore(backupZip, false)).hasMessageContaining("simulated failure");
        } finally {
            backups.beforeSwapHook = () -> { };
        }

        assertThat(sessions.count()).isEqualTo(before);                       // rows rolled back
        assertThat(sessions.detail(extra.id()).messages()).extracting(Message::content).contains("복원 실패 전에 있던 상담");
        assertThat(settings.profileSummary()).isEqualTo(profileBefore);
        assertThat(listMd(markdownDir)).containsExactlyElementsOf(filesBefore); // files untouched
        assertThat(Files.isRegularFile(Path.of(summaries.findBySession(extra.id()).orElseThrow().markdownPath()))).isTrue();
        try (Stream<Path> siblings = Files.list(markdownDir.getParent())) {    // no staging/displaced folders left
            assertThat(siblings.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("." + markdownDir.getFileName()))).isEmpty();
        }
        // the lock was released: normal work and a real restore still go through
        Session after = sessions.start(null);
        completion.complete(after.id());
        assertThat(backups.restore(backupZip, false).sessionsRestored()).isEqualTo(1);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @Order(6)
    void deleteAllAlsoRemovesTheAppsOwnSnapshotsUnlessAskedToKeepThem() throws Exception {
        assertThat(backups.safetySnapshots()).isNotEmpty(); // written by the restores above, they hold the old data

        var kept = deletion.deleteAll("DELETE", false);
        assertThat(kept.backupsDeleted()).isZero();
        assertThat(kept.backupsRemaining()).isEqualTo(backups.safetySnapshots().size()).isPositive();

        var wiped = deletion.deleteAll("DELETE", true);
        assertThat(wiped.backupsDeleted()).isPositive();
        assertThat(wiped.backupsRemaining()).isZero();
        assertThat(backups.safetySnapshots()).isEmpty();
        try (Stream<Path> files = Files.list(backups.backupsDir())) {
            assertThat(files.toList()).isEmpty();
        }
        // the downloaded copy outside data/backups is the user's and stays
        assertThat(Files.isRegularFile(backupZip)).isTrue();
    }

    @Autowired com.mentalcare.config.AppProperties app;

    @Test
    @Order(7)
    void aFailedStagingLeavesNoCounselingFilesBehindAndDeleteAllSweepsOldLeftovers() throws Exception {
        Path markdownDir = Path.of(app.markdownDir()).toAbsolutePath().normalize();
        Files.createDirectories(markdownDir);
        // a non-Markdown file in the live folder is carried over to the staging folder first …
        Files.writeString(markdownDir.resolve("collide"), "user file");
        // … so a backup with a directory of the same name makes staging fail half-way
        Path colliding = withExtraEntry(backupZip, "sessions/collide/a.md", "# 상담 기록\n평문 상담 내용");
        long before = sessions.count();

        assertThatThrownBy(() -> backups.restore(colliding, false)).isInstanceOf(RuntimeException.class);

        assertThat(sessions.count()).isEqualTo(before);
        assertThat(leftovers(markdownDir)).isEmpty();          // the half-built staging copy is gone
        assertThat(Files.readString(markdownDir.resolve("collide"))).isEqualTo("user file");
        assertThat(locksFree()).isTrue();

        // a folder left by a crashed restore (e.g. power loss) is reported and removed by delete-all
        Path crashed = markdownDir.resolveSibling("." + markdownDir.getFileName() + "-restore-crashed");
        Files.createDirectories(crashed.resolve("2026"));
        Files.writeString(crashed.resolve("2026/x.md"), "평문 상담 내용");
        assertThat(backups.safetySnapshots()).contains(crashed);
        var r = deletion.deleteAll("DELETE", true);
        assertThat(r.backupsDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(r.backupsRemaining()).isZero();
        assertThat(Files.exists(crashed)).isFalse();
        Files.deleteIfExists(markdownDir.resolve("collide"));
    }

    @Autowired com.mentalcare.counseling.SessionLocks locks;

    private boolean locksFree() {
        return locks.busyCount() == 0;
    }

    private static List<Path> leftovers(Path markdownDir) throws Exception {
        try (Stream<Path> s = Files.list(markdownDir.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith("." + markdownDir.getFileName())).toList();
        }
    }

    private static Path withExtraEntry(Path zip, String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipFile in = new ZipFile(zip.toFile()); ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (ZipEntry e : java.util.Collections.list(in.entries())) {
                out.putNextEntry(new ZipEntry(e.getName()));
                in.getInputStream(e).transferTo(out);
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path file = Files.createTempFile("mentalcare-colliding-backup", ".zip");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static List<String> listMd(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    private static Path zipOf(String... nameAndContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndContent[i]));
                zip.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path file = Files.createTempFile("mentalcare-bad-backup", ".zip");
        Files.write(file, bytes.toByteArray());
        return file;
    }
}
