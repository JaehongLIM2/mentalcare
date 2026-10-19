package com.mentalcare.backup;

import com.mentalcare.context.ContextMessage;
import com.mentalcare.context.ContextOutline;
import com.mentalcare.context.CounselingContextBuilder;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Message;
import com.mentalcare.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure helpers: zip entry safety, Markdown path relinking, LIKE escaping, context budget accounting. */
class BackupServiceHelpersTest {

    @Test
    void zipEntryNamesMustStayInsideTheArchive() {
        assertThat(BackupService.isSafeEntryName("manifest.json")).isTrue();
        assertThat(BackupService.isSafeEntryName("sessions/2026/2026-10-19.md")).isTrue();
        assertThat(BackupService.isSafeEntryName("sessions/../x.md")).isFalse();
        assertThat(BackupService.isSafeEntryName("../x")).isFalse();
        assertThat(BackupService.isSafeEntryName("/etc/passwd")).isFalse();
        assertThat(BackupService.isSafeEntryName("C:/Windows/x")).isFalse();
        assertThat(BackupService.isSafeEntryName("sessions\\2026\\x.md")).isFalse();
        assertThat(BackupService.isSafeEntryName("a//b")).isFalse();
        assertThat(BackupService.isSafeEntryName("a/./b")).isFalse();
        assertThat(BackupService.isSafeEntryName("")).isFalse();
        assertThat(BackupService.isSafeEntryName("x\0y")).isFalse();
    }

    @Test
    void markdownPathsAreRelinkedToTheLocalFolder() {
        assertThat(BackupService.relativeMarkdownPath("C:\\old\\data\\sessions\\2026\\2026-10-19.md", "C:\\old\\data\\sessions"))
                .isEqualTo("2026/2026-10-19.md");
        assertThat(BackupService.relativeMarkdownPath("/srv/data/sessions/2026/2026-10-19-7.md", "/srv/data/sessions"))
                .isEqualTo("2026/2026-10-19-7.md");
        // case differences on Windows paths, and an unknown original layout, both fall back sensibly
        assertThat(BackupService.relativeMarkdownPath("c:\\OLD\\Data\\Sessions\\2026\\a.md", "C:\\old\\data\\sessions")).isEqualTo("2026/a.md");
        assertThat(BackupService.relativeMarkdownPath("/elsewhere/2025/b.md", null)).isEqualTo("2025/b.md");
        assertThat(BackupService.relativeMarkdownPath("b.md", "/x")).isEqualTo("b.md");
        assertThat(BackupService.relativeMarkdownPath(null, "/x")).isNull();
    }

    @Test
    void likeWildcardsAreEscaped() throws Exception {
        Method m = SessionRepository.class.getDeclaredMethod("escapeLike", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, "100%_done!")).isEqualTo("100!%!_done!!");
        assertThat(m.invoke(null, "plain")).isEqualTo("plain");
    }

    @Test
    void budgetCountsIncludedAndOmittedMessagesLikeTheContextBuilder() throws Exception {
        Message a = msg(1, MessageRole.ASSISTANT, "a".repeat(40), MessageStatus.COMPLETE);
        Message b = msg(2, MessageRole.USER, "b".repeat(40), MessageStatus.COMPLETE);
        Message failed = msg(3, MessageRole.ASSISTANT, "partial", MessageStatus.FAILED);
        Message c = msg(4, MessageRole.USER, "c".repeat(40), MessageStatus.COMPLETE);
        Method budget = CounselingContextBuilder.class.getDeclaredMethod("budget", List.class, int.class);
        budget.setAccessible(true);
        ContextOutline.Budget result = (ContextOutline.Budget) budget.invoke(null, List.of(a, b, failed, c), 90);
        assertThat(result.included()).isEqualTo(2);  // c and b fit, a does not, failed is skipped silently
        assertThat(result.omitted()).isEqualTo(1);
        assertThat(result.chars()).isEqualTo(80);
        assertThat(result.messages()).extracting(ContextMessage::role).containsExactly("developer", "user", "user");
        ContextOutline.Budget all = (ContextOutline.Budget) budget.invoke(null, List.of(a, b, c), 1000);
        assertThat(all.omitted()).isZero();
        assertThat(all.messages()).hasSize(3);
    }

    private static Message msg(long id, MessageRole role, String content, MessageStatus status) {
        return new Message(id, 1L, role, content, status, null, Instant.now());
    }
}
