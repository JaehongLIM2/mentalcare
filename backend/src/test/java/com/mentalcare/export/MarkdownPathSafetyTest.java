package com.mentalcare.export;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.AppProperties;
import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Archive paths are derived from server-side date + id only; user text can never influence them. */
class MarkdownPathSafetyTest {

    @TempDir Path tmp;

    @Test
    void archivePathStaysInsideMarkdownDirWhateverTheContentSays() throws Exception {
        Path markdownDir = tmp.resolve("sessions");
        AppProperties props = new AppProperties(tmp.toString(), markdownDir.toString(), null, 3, 7,
                new AppProperties.Context(30, 5), new AppProperties.Memory(2, 3),
                new AppProperties.Safety(0.4, 6, List.of()), null);
        MarkdownExporter exporter = new MarkdownExporter(props);
        String traversal = "../../../etc/passwd";
        Session session = new Session(42, SessionStatus.COMPLETED, traversal, Instant.now(), Instant.now(), 1L, false, Instant.now(), Instant.now());
        SessionSummaryOutput summary = new SessionSummaryOutput(traversal, List.of(traversal), List.of("C:\\Windows\\win.ini"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(traversal));

        Path written = exporter.export(session, null, summary, List.of(traversal));

        assertThat(written.normalize()).startsWith(markdownDir.toAbsolutePath().normalize());
        assertThat(written.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}(-42)?\\.md");
    }
}
