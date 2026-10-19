package com.mentalcare.importing;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveMarkdownParserTest {

    static String sample() throws Exception {
        return new ClassPathResource("sample-archive.md").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesTemplateSectionsMetadataAndCandidates() throws Exception {
        ArchiveMarkdownParser.Parsed p = ArchiveMarkdownParser.parse(sample());

        assertThat(p.title()).isEqualTo("ChatGPT 상담 이관 기록");
        assertThat(p.counseledAt()).isEqualTo(LocalDate.of(2026, 7, 1)); // "2026년 7월 ~ ..." → first month
        assertThat(p.mainTopics()).isEqualTo("직장 스트레스, 이직 고민");
        assertThat(p.sections().get(ArchiveMarkdownParser.FACTS)).containsExactly(
                "2026년 6월에 팀을 옮겼다.", "주 2회 이상 야근을 했다.", "상사와 1:1 면담을 두 번 했다.");
        assertThat(p.sections().get(ArchiveMarkdownParser.SITUATION)).singleElement().asString().startsWith("새 팀으로");
        assertThat(p.sections().get(ArchiveMarkdownParser.AI_PATTERNS)).contains("결정을 미루는 동안 자기 비난이 커지는 경향이 있어 보임.");
        assertThat(p.coreContext()).startsWith("2026년 6월 팀 이동 이후").contains("야근 시간 기록");

        assertThat(p.memoryCandidates()).hasSize(2);
        ArchiveMarkdownParser.MemoryCandidate c1 = p.memoryCandidates().get(0);
        assertThat(c1.type()).isEqualTo("CONCERN");
        assertThat(c1.evidence()).isEqualTo("USER_STATED");
        assertThat(c1.content()).isEqualTo("2026년 하반기 이직 여부를 두고 오래 고민했다.");
        assertThat(c1.mayBecomeOutdated()).isTrue();
        assertThat(p.memoryCandidates().get(1).evidence()).isEqualTo("AI_OBSERVED");
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    void toleratesMissingSectionsAndOddDates() {
        ArchiveMarkdownParser.Parsed p = ArchiveMarkdownParser.parse("""
                # 뭔가 다른 제목
                ## Metadata
                - 상담 시점: 작년 가을쯤
                ## 주요 고민
                이직
                ## 처음 보는 섹션
                무시
                """);
        assertThat(p.counseledAt()).isNull();
        assertThat(p.sections().get(ArchiveMarkdownParser.CONCERNS)).containsExactly("이직");
        assertThat(p.sections().get(ArchiveMarkdownParser.FACTS)).isEmpty();
        assertThat(p.memoryCandidates()).isEmpty();
        assertThat(p.warnings()).anyMatch(w -> w.contains("날짜로 해석"))
                .anyMatch(w -> w.contains("알 수 없는 섹션"))
                .anyMatch(w -> w.contains("Memory Candidate"))
                .anyMatch(w -> w.contains("핵심 맥락"));
        assertThat(ArchiveMarkdownParser.parseDate("2025-03-14")).isEqualTo(LocalDate.of(2025, 3, 14));
        assertThat(ArchiveMarkdownParser.parseDate("2025.3")).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(ArchiveMarkdownParser.parseDate("2024년")).isEqualTo(LocalDate.of(2024, 1, 1));
    }
}
