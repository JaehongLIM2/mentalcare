package com.mentalcare.export;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.AppProperties;
import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.WeeklyCheckIn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownExporterTest {

    @TempDir
    Path tmp;

    private AppProperties props() {
        return new AppProperties(tmp.toString(), tmp.resolve("sessions").toString(), null, 3, 7,
                new AppProperties.Context(30, 5, 0), new AppProperties.Memory(2, 3),
                new AppProperties.Safety(0.4, 6, List.of()), null);
    }

    private static Session session(long id, LocalDate date) {
        Instant started = date.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant();
        return new Session(id, SessionStatus.COMPLETED, "제목", started, started, 1L, false, started, started);
    }

    @Test
    void rendersTemplateWithFactsAndInterpretationSeparated() {
        MarkdownExporter exporter = new MarkdownExporter(props());
        WeeklyCheckIn checkIn = new WeeklyCheckIn(1, 1, 6, 7, 5, 5, 6, null, Instant.now());
        SessionSummaryOutput summary = new SessionSummaryOutput("회사 스트레스", List.of("회사", "상사"),
                List.of("상사와 회의에서 갈등"), List.of("불안"), List.of("상사가 나를 싫어한다고 느낌"),
                List.of("평가에 대한 걱정이 앞서는 경향 가능성"), List.of("상사의 실제 행동과 해석을 구분함"),
                List.of("상사의 행동을 사실 위주로 메모하기"), List.of("면담 결과"), List.of(), List.of("work", "stress"));

        String md = exporter.render(session(1, LocalDate.of(2026, 10, 19)), checkIn, summary, List.of("work", "stress"));

        assertThat(md).startsWith("# 상담 기록\n\n날짜: 2026-10-19\n");
        assertThat(md).contains("## Check-in\n\n기분: 6/10\n스트레스: 7/10\n불안: 5/10\n에너지: 5/10\n수면: 6/10\n");
        assertThat(md).contains("## 이번 상담의 주요 주제\n\n- 회사\n- 상사\n");
        assertThat(md).contains("## 상담에서 관찰된 패턴\n\n이 영역은 AI의 해석이며 사실 또는 의학적 진단이 아니다.\n\n- 평가에 대한 걱정이 앞서는 경향 가능성\n");
        assertThat(md).contains("## 이번 주에 해볼 것\n\n- 상사의 행동을 사실 위주로 메모하기\n");
        assertThat(md).endsWith("## Tags\n\nwork\nstress\n");
    }

    @Test
    void skippedCheckInAndEmptySectionsAreExplicit() {
        MarkdownExporter exporter = new MarkdownExporter(props());
        SessionSummaryOutput summary = new SessionSummaryOutput("t", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String md = exporter.render(session(1, LocalDate.of(2026, 1, 5)), null, summary, List.of());

        assertThat(md).contains("## Check-in\n\n건너뜀\n");
        assertThat(md).contains("## 주요 감정\n\n(없음)\n");
        assertThat(md).endsWith("## Tags\n\n(없음)\n");
    }

    @Test
    void writesYearFolderAndAvoidsOverwritingSameDay() throws IOException {
        MarkdownExporter exporter = new MarkdownExporter(props());
        SessionSummaryOutput summary = new SessionSummaryOutput("t", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        LocalDate date = LocalDate.of(2026, 10, 19);

        Path first = exporter.export(session(7, date), null, summary, List.of());
        Path second = exporter.export(session(8, date), null, summary, List.of());

        assertThat(first).isEqualTo(tmp.resolve("sessions").resolve("2026").resolve("2026-10-19.md").toAbsolutePath().normalize());
        assertThat(second.getFileName().toString()).isEqualTo("2026-10-19-8.md");
        assertThat(Files.readString(first)).contains("# 상담 기록");
    }
}
