package com.mentalcare.export;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.Dates;
import com.mentalcare.config.AppProperties;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.WeeklyCheckIn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Renders the human-readable Markdown archive of a completed session from the structured summary.
 * The template lives in Java on purpose: the model returns data, the app owns the format.
 */
@Component
public class MarkdownExporter {

    private final Path markdownDir;

    public MarkdownExporter(AppProperties app) {
        this.markdownDir = Path.of(app.markdownDir());
    }

    /** Writes the archive file and returns its path. */
    public Path export(Session session, WeeklyCheckIn checkIn, SessionSummaryOutput summary, List<String> tags)
            throws IOException {
        LocalDate date = Dates.localDate(session.startedAt());
        Path dir = markdownDir.resolve(String.valueOf(date.getYear()));
        Files.createDirectories(dir);
        Path file = dir.resolve(date + ".md");
        if (Files.exists(file)) {
            file = dir.resolve(date + "-" + session.id() + ".md"); // second session on the same day
        }
        Files.writeString(file, render(session, checkIn, summary, tags), StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize();
    }

    public String render(Session session, WeeklyCheckIn checkIn, SessionSummaryOutput s, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 상담 기록\n\n");
        sb.append("날짜: ").append(Dates.isoDate(session.startedAt())).append("\n");
        if (s.title() != null && !s.title().isBlank()) sb.append("제목: ").append(s.title()).append("\n");
        sb.append("\n");

        sb.append("## Check-in\n\n");
        if (checkIn == null || checkIn.isEmpty()) {
            sb.append("건너뜀\n\n");
        } else {
            score(sb, "기분", checkIn.mood());
            score(sb, "스트레스", checkIn.stress());
            score(sb, "불안", checkIn.anxiety());
            score(sb, "에너지", checkIn.energy());
            score(sb, "수면", checkIn.sleep());
            if (checkIn.note() != null && !checkIn.note().isBlank()) {
                sb.append("메모: ").append(checkIn.note().trim()).append("\n");
            }
            sb.append("\n");
        }

        section(sb, "이번 상담의 주요 주제", s.topics());
        section(sb, "이야기한 내용", s.keyEvents());
        section(sb, "주요 감정", s.emotions());
        section(sb, "사용자의 생각", s.userThoughts());

        sb.append("## 상담에서 관찰된 패턴\n\n");
        sb.append("이 영역은 AI의 해석이며 사실 또는 의학적 진단이 아니다.\n\n");
        bullets(sb, s.observedPatterns());

        section(sb, "새롭게 발견된 점", s.discoveries());
        section(sb, "이번 주에 해볼 것", s.actionItems());
        section(sb, "다음 상담에서 확인할 것", s.nextSessionTopics());

        sb.append("## Tags\n\n");
        if (tags == null || tags.isEmpty()) {
            sb.append("(없음)\n");
        } else {
            for (String t : tags) sb.append(t).append("\n");
        }
        return sb.toString();
    }

    private static void score(StringBuilder sb, String label, Integer value) {
        sb.append(label).append(": ").append(value == null ? "-" : value + "/10").append("\n");
    }

    private static void section(StringBuilder sb, String title, List<String> items) {
        sb.append("## ").append(title).append("\n\n");
        bullets(sb, items);
    }

    private static void bullets(StringBuilder sb, List<String> items) {
        if (items == null || items.isEmpty()) {
            sb.append("(없음)\n\n");
            return;
        }
        for (String item : items) sb.append("- ").append(item).append("\n");
        sb.append("\n");
    }
}
