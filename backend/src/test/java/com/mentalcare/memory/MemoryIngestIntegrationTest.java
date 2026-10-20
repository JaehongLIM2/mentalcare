package com.mentalcare.memory;

import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.Session;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summary candidates against existing memories: only a repeat of the same fact (relation SAME) is an
 * observation; new information and corrections are kept as their own candidates, however similar the wording.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class MemoryIngestIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "memory-ingest");
    }

    @Autowired MemoryService memories;
    @Autowired SessionService sessions;
    @Autowired SessionCompletionService completion;
    @Autowired FakeLlm llm;

    @BeforeEach
    void finishOpenSession() {
        llm.reset();
        sessions.active().ifPresent(s -> {
            llm.summary = FakeLlm.emptySummary();
            completion.complete(s.id());
            llm.reset();
        });
    }

    private static MemoryCandidateOutput candidate(String content, Long linkedId, String relation) {
        return new MemoryCandidateOutput(content, "USER_STATED", "event", "r", linkedId, relation);
    }

    /** A memory first seen in an earlier, finished session. */
    private Memory existingMemory(String content) {
        Session earlier = sessions.start(null);
        assertThat(memories.ingest(earlier.id(), List.of(candidate(content, null, "NEW"))).created()).isEqualTo(1);
        llm.summary = FakeLlm.emptySummary();
        completion.complete(earlier.id());
        return memories.list(EnumSet.allOf(MemoryStatus.class)).stream()
                .filter(m -> m.content().equals(content)).findFirst().orElseThrow();
    }

    private List<String> allContents() {
        return memories.list(EnumSet.allOf(MemoryStatus.class)).stream().map(Memory::content).toList();
    }

    @Test
    void aRepeatIsOneObservationAndANewFactLinkedToTheSameMemoryIsKept() {
        Memory existing = existingMemory("이직을 고민하고 있다.");
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(
                candidate("요즘도 회사를 옮길지 계속 생각한다", existing.id(), "SAME"),
                candidate("다음 달에 두 회사 면접을 보기로 했다.", existing.id(), "UPDATE"),
                candidate("이직을 고민하고 있다.", existing.id(), "SAME")));

        assertThat(r.merged()).as("the repeat is one observation").isEqualTo(1);
        assertThat(r.created()).as("the new fact is kept, the second repeat is skipped").isEqualTo(1);
        assertThat(allContents()).contains("다음 달에 두 회사 면접을 보기로 했다.")
                .doesNotContain("요즘도 회사를 옮길지 계속 생각한다")
                .filteredOn(c -> c.startsWith("이직을 고민")).hasSize(1);
        assertThat(memories.get(existing.id()).content()).as("existing text is not rewritten").isEqualTo("이직을 고민하고 있다.");
        assertThat(memories.get(existing.id()).observationCount()).isEqualTo(2);
    }

    @Test
    void twoNewFactsLinkedToTheSameMemoryAreBothKeptAndDoNotCountAsObservations() {
        Memory existing = existingMemory("봄에 다른 부서로 옮길 예정이다.");
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(
                candidate("부서 이동을 이야기하던 상사가 바뀌어 진행 여부가 불확실해졌다.", existing.id(), "UPDATE"),
                candidate("부서 이동은 확정이 아니라 상사의 구상이었다.", existing.id(), "UPDATE")));

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.merged()).isZero();
        assertThat(allContents()).contains("부서 이동을 이야기하던 상사가 바뀌어 진행 여부가 불확실해졌다.",
                "부서 이동은 확정이 아니라 상사의 구상이었다.");
        Memory after = memories.get(existing.id());
        assertThat(after.observationCount()).isEqualTo(1);
        assertThat(after.status()).isEqualTo(MemoryStatus.CANDIDATE);
    }

    @Test
    void aCorrectionWithSimilarWordingIsKeptAndDoesNotActivateTheOldMemory() {
        Memory existing = existingMemory("평일 저녁에는 집에서 요리를 하고 설거지는 직접 하지 않는다");
        assertThat(MemoryService.findSimilar(List.of(existing), "평일 저녁에는 집에서 요리를 하고 설거지는 직접 한다"))
                .as("text similarity alone would call this a repeat").isPresent();
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(
                candidate("평일 저녁에는 집에서 요리를 하고 설거지는 직접 한다", null, "NEW"),
                candidate("평일 저녁에는 집에서 요리를 하고 설거지는 직접 한다", existing.id(), "UPDATE")));

        assertThat(r.created()).as("the same correction twice is one candidate").isEqualTo(1);
        assertThat(r.merged()).isZero();
        assertThat(allContents()).contains("평일 저녁에는 집에서 요리를 하고 설거지는 직접 한다");
        Memory after = memories.get(existing.id());
        assertThat(after.observationCount()).isEqualTo(1);
        assertThat(after.status()).as("the corrected memory is not confirmed by its correction").isEqualTo(MemoryStatus.CANDIDATE);
    }

    @Test
    void aCorrectedNumberIsNotTheSameText() {
        Memory existing = existingMemory("매주 운동 목표는 2.5시간이다.");
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(
                candidate("매주 운동 목표는 25시간이다.", existing.id(), "UPDATE"),
                candidate("매주 운동 목표는 25시간이다", null, "NEW")));

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.merged()).isZero();
        assertThat(allContents()).contains("매주 운동 목표는 25시간이다.");
        Memory after = memories.get(existing.id());
        assertThat(after.observationCount()).isEqualTo(1);
        assertThat(after.status()).isEqualTo(MemoryStatus.CANDIDATE);
    }

    @Test
    void aChangedSignIsNotTheSameText() {
        Memory existing = existingMemory("이번 달 생활비 잔액은 -10만 원이다.");
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(
                candidate("이번 달 생활비 잔액은 +10만 원이다.", existing.id(), "UPDATE")));

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.merged()).isZero();
        assertThat(allContents()).contains("이번 달 생활비 잔액은 +10만 원이다.");
        Memory after = memories.get(existing.id());
        assertThat(after.observationCount()).isEqualTo(1);
        assertThat(after.status()).isEqualTo(MemoryStatus.CANDIDATE);
    }

    @Test
    void anUnlinkedCandidateWithTheSameTextIsStillARepeat() {
        Memory existing = existingMemory("고양이와 가끔 산책한다.");
        Session now = sessions.start(null);
        MemoryService.IngestResult r = memories.ingest(now.id(), List.of(candidate("고양이와 가끔 산책한다", null, "NEW")));

        assertThat(r.merged()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(memories.get(existing.id()).observationCount()).isEqualTo(2);
    }
}
