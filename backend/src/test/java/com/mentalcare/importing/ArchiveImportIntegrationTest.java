package com.mentalcare.importing;

import com.mentalcare.context.CounselingContext;
import com.mentalcare.context.CounselingContextBuilder;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.DataDeletionService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Enums.SessionKind;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class ArchiveImportIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "import");
    }

    static class Listener implements TurnListener {
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) {}
        @Override public void onError(String m, String k) {}
    }

    @Autowired ArchiveImportService imports;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired CounselingContextBuilder contextBuilder;
    @Autowired MemoryService memories;
    @Autowired DataDeletionService deletion;
    @Autowired FakeLlm llm;
    @Autowired MockMvc mvc;

    private byte[] sample() throws Exception {
        return new ClassPathResource("sample-archive.md").getContentAsByteArray();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void importCreatesSessionSummaryMemoriesAndFeedsTheContext() throws Exception {
        ArchiveImportService.Preview preview = imports.preview(sample());
        assertThat(preview.memoryCandidateCount()).isEqualTo(2);
        assertThat(preview.factCount()).isEqualTo(3);
        assertThat(preview.counseledAt()).isEqualTo("2026-07-01");

        ArchiveImportService.Result r = imports.importArchive("chatgpt-export.md", sample());

        // the same file again is refused (it would count as a second, independent observation)
        assertThatThrownBy(() -> imports.importArchive("chatgpt-export.md", sample()))
                .isInstanceOf(com.mentalcare.common.ConflictException.class);
        assertThat(imports.preview(sample()).alreadyImportedSessionId()).isEqualTo(r.sessionId());
        assertThat(memories.list(Set.of(MemoryStatus.ACTIVE))).isEmpty();

        Session s = sessions.get(r.sessionId());
        assertThat(s.kind()).isEqualTo(SessionKind.IMPORTED);
        assertThat(s.title()).startsWith("이관: 직장 스트레스");
        SessionService.SessionDetail d = sessions.detail(s.id());
        assertThat(d.messages()).isEmpty();
        assertThat(d.importedMarkdown()).contains("# ChatGPT 상담 이관 기록");
        assertThat(d.tags()).containsExactly("imported");
        assertThat(d.summary().output().keyEvents()).contains("2026년 6월에 팀을 옮겼다.", "관계: 상사 A: 업무 지시가 잦고 피드백이 직접적이다.");
        assertThat(d.summary().output().observedPatterns()).containsExactly("결정을 미루는 동안 자기 비난이 커지는 경향이 있어 보임.");
        assertThat(d.summary().output().importedContext()).contains("야근 시간 기록");
        // candidates become CANDIDATE memories with the right kind (never ACTIVE automatically)
        assertThat(memories.list(Set.of(MemoryStatus.CANDIDATE)))
                .anySatisfy(m -> {
                    assertThat(m.content()).isEqualTo("2026년 하반기 이직 여부를 두고 오래 고민했다.");
                    assertThat(m.kind().name()).isEqualTo("USER_STATED");
                    assertThat(m.observationCount()).isEqualTo(1);
                })
                .anySatisfy(m -> assertThat(m.kind().name()).isEqualTo("AI_OBSERVED"));
        assertThat(memories.list(Set.of(MemoryStatus.ACTIVE))).isEmpty();

        // the next counseling session sees the archive's core context inside a data block, not as instructions
        llm.reset();
        Session next = sessions.start(null);
        counseling.opening(next.id(), new Listener());
        CounselingContext ctx = llm.contexts.get(0);
        String instructions = ctx.instructions();
        int dataStart = instructions.indexOf(CounselingContextBuilder.DATA_OPEN + " 이관된 과거 기록");
        assertThat(dataStart).isPositive();
        assertThat(instructions.substring(dataStart)).contains("2026-07-01 — 이관: 직장 스트레스").contains("야근 시간 기록");
        assertThat(instructions.substring(0, dataStart)).doesNotContain("야근 시간 기록");
        // imported sessions are not counted as "recent counseling sessions"
        assertThat(instructions).contains("(첫 상담입니다");
        completion.complete(next.id());

        // deleting the imported session removes archive + its sole memories
        deletion.deleteSession(s.id());
        assertThatThrownBy(() -> sessions.get(s.id())).hasMessageContaining("not found");
        assertThat(imports.importedContexts()).isEmpty();
        assertThat(memories.list(Set.of(MemoryStatus.CANDIDATE))).noneMatch(m -> m.content().contains("이직 여부"));
    }

    @Autowired com.mentalcare.repository.ImportedArchiveRepository archiveRepository;
    @Autowired com.mentalcare.config.SchemaMigrations migrations;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    @org.junit.jupiter.api.Order(3)
    void archivesImportedBeforeHashingAreBackfilledAndThenDeduplicated() throws Exception {
        byte[] file = ("# ChatGPT 상담 이관 기록\n## Metadata\n- 상담 시점: 2025-01-01\n- 주요 주제: 옛 기록\n"
                + "## 다음 상담 AI에게 전달할 핵심 맥락\n예전 도구에서 가져온 맥락\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // simulate a row written by the previous version: no content hash
        ArchiveImportService.Result r = imports.importArchive("old.md", file);
        jdbc.sql("UPDATE imported_archives SET content_hash = NULL WHERE session_id = :id").param("id", r.sessionId()).update();
        assertThat(imports.preview(file).alreadyImportedSessionId()).isNull(); // undetectable before the migration

        migrations.migrate(); // idempotent: backfills hashes and (re)creates the unique index

        assertThat(archiveRepository.findSessionByHash(ArchiveImportService.sha256(file))).contains(r.sessionId());
        assertThat(imports.preview(file).alreadyImportedSessionId()).isEqualTo(r.sessionId());
        assertThatThrownBy(() -> imports.importArchive("old.md", file)).isInstanceOf(com.mentalcare.common.ConflictException.class);
        deletion.deleteSession(r.sessionId());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void concurrentImportsOfTheSameFileStoreItOnce() throws Exception {
        byte[] file = ("# ChatGPT 상담 이관 기록\n## Metadata\n- 상담 시점: 2025-02-01\n- 주요 주제: 동시 가져오기\n"
                + "## 다음 상담 AI에게 전달할 핵심 맥락\n같은 파일을 두 탭에서\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Object> task = () -> {
            go.await();
            try {
                return imports.importArchive("same.md", file);
            } catch (RuntimeException e) {
                return e;
            }
        };
        java.util.List<java.util.concurrent.Future<Object>> results = java.util.List.of(pool.submit(task), pool.submit(task));
        go.countDown();
        java.util.List<Object> outcomes = new java.util.ArrayList<>();
        for (var f : results) outcomes.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        long successes = outcomes.stream().filter(o -> o instanceof ArchiveImportService.Result).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof com.mentalcare.common.ConflictException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        long stored = jdbc.sql("SELECT COUNT(*) FROM imported_archives WHERE content_hash = :h")
                .param("h", ArchiveImportService.sha256(file)).query(Long.class).single();
        assertThat(stored).isEqualTo(1);
        // the losing transaction rolled back completely: one IMPORTED session for this file
        long sessionsForFile = jdbc.sql("SELECT COUNT(*) FROM sessions WHERE title = :t").param("t", "이관: 동시 가져오기").query(Long.class).single();
        assertThat(sessionsForFile).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void uploadEndpointsValidateAndImport() throws Exception {
        MockMultipartFile ok = new MockMultipartFile("file", "export.md", "text/markdown", sample());
        mvc.perform(multipart("/api/imports/preview").file(ok).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryCandidateCount").value(2));

        MockMultipartFile wrongType = new MockMultipartFile("file", "export.exe", "application/octet-stream", sample());
        mvc.perform(multipart("/api/imports").file(wrongType).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest());

        MockMultipartFile empty = new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0]);
        mvc.perform(multipart("/api/imports").file(empty).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest());

        mvc.perform(multipart("/api/imports").file(ok).header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/api/imports").file(ok).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoriesCreated").value(2))
                .andExpect(jsonPath("$.date").value("2026-07-01"));
    }
}
