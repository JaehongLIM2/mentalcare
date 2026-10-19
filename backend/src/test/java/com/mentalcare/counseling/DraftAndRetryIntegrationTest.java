package com.mentalcare.counseling;

import com.mentalcare.ai.LlmException;
import com.mentalcare.common.ConflictException;
import com.mentalcare.context.ContextMessage;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.MessageRepository;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Composer drafts survive until the text is sent; a reply can be requested again without a duplicate user message. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class DraftAndRetryIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "draft-retry");
    }

    static class Listener implements TurnListener {
        final StringBuilder text = new StringBuilder();
        final List<String> errorKinds = new ArrayList<>();
        Message done;
        Integer included, omitted;
        RiskLevel risk;
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) { risk = r; }
        @Override public void onContext(int i, int o) { included = i; omitted = o; }
        @Override public void onDelta(String t) { text.append(t); }
        @Override public void onDone(Message m) { done = m; }
        @Override public void onError(String m, String k) { errorKinds.add(k); }
    }

    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired MessageRepository messageRepository;

    @BeforeEach
    void reset() {
        llm.reset();
        sessions.active().ifPresent(s -> {
            llm.summary = FakeLlm.emptySummary();
            completion.complete(s.id());
            llm.reset();
        });
    }

    @Test
    void draftIsStoredWithTheSessionAndClearedWhenSentOrCompleted() {
        Session s = sessions.start(null);
        sessions.saveDraft(s.id(), "아직 보내지 않은 긴 글 ");
        assertThat(sessions.detail(s.id()).draft()).isNotNull();
        assertThat(sessions.detail(s.id()).draft().content()).isEqualTo("아직 보내지 않은 긴 글 ");

        // overwriting and blanking
        sessions.saveDraft(s.id(), "수정된 초안");
        assertThat(sessions.detail(s.id()).draft().content()).isEqualTo("수정된 초안");
        sessions.saveDraft(s.id(), "   ");
        assertThat(sessions.detail(s.id()).draft()).isNull();

        // sending the text removes the draft
        sessions.saveDraft(s.id(), "보낼 글");
        counseling.reply(s.id(), "보낼 글", new Listener());
        assertThat(sessions.detail(s.id()).draft()).isNull();
        assertThat(messageRepository.findBySession(s.id())).filteredOn(m -> m.role() == MessageRole.USER).hasSize(1);

        // completion clears whatever is left, and a completed session takes no drafts
        sessions.saveDraft(s.id(), "남은 초안");
        completion.complete(s.id());
        assertThat(sessions.detail(s.id()).draft()).isNull();
        assertThatThrownBy(() -> sessions.saveDraft(s.id(), "x")).isInstanceOf(ConflictException.class);
    }

    @Autowired SessionLocks locks;

    @Test
    void draftSavesTakePartInTheMaintenanceLock() {
        Session s = sessions.start(null);
        assertThat(locks.tryLockAll()).isTrue(); // a restore / delete-all in another tab
        try {
            assertThatThrownBy(() -> sessions.saveDraft(s.id(), "복원 중에 들어온 자동 저장")).isInstanceOf(ConflictException.class);
        } finally {
            locks.unlockAll();
        }
        assertThat(sessions.detail(s.id()).draft()).isNull();
        // a draft can be saved while a reply is streaming (only the session lock is busy then)
        assertThat(locks.tryLock(s.id())).isTrue();
        try {
            sessions.saveDraft(s.id(), "응답을 기다리며 쓰는 글");
        } finally {
            locks.unlock(s.id());
        }
        assertThat(sessions.detail(s.id()).draft().content()).isEqualTo("응답을 기다리며 쓰는 글");
        assertThat(locks.busyCount()).isZero(); // the read lock was released after each save
        assertThatThrownBy(() -> sessions.saveDraft(999_999L, "x")).isInstanceOf(com.mentalcare.common.NotFoundException.class);
    }

    @Test
    void outOfOrderAutosavesNeverBringBackOlderText() {
        Session s = sessions.start(null);
        long base = DraftRevisions.next();
        assertThat(sessions.saveDraft(s.id(), "최신 초안", base + 20)).isTrue();
        // a slower request issued earlier arrives afterwards: ignored, not an error
        assertThat(sessions.saveDraft(s.id(), "이전 초안", base + 10)).isFalse();
        assertThat(sessions.detail(s.id()).draft().content()).isEqualTo("최신 초안");

        // sending at revision base+30 leaves a tombstone: an autosave issued before the send cannot resurrect the text
        counseling.reply(s.id(), "최신 초안", base + 30, new Listener());
        assertThat(sessions.saveDraft(s.id(), "최신 초안", base + 25)).isFalse();
        assertThat(sessions.detail(s.id()).draft()).isNull();
        // typing after the send works again
        assertThat(sessions.saveDraft(s.id(), "다음 이야기", base + 40)).isTrue();
        assertThat(sessions.detail(s.id()).draft().content()).isEqualTo("다음 이야기");
    }

    @Test
    void retryAfterAnInterruptedReplyReusesTheSavedUserMessage() {
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());

        llm.streamFailure = new LlmException(LlmException.Kind.SERVER, "boom");
        Listener failed = new Listener();
        counseling.reply(s.id(), "질문입니다", failed);
        assertThat(failed.errorKinds).containsExactly("SERVER");
        assertThat(failed.done.status()).isEqualTo(MessageStatus.INTERRUPTED); // partial text was kept
        assertThat(failed.included).isEqualTo(2);
        assertThat(failed.omitted).isZero();

        llm.streamFailure = null;
        llm.reply = "다시 드리는 답변";
        Listener retried = new Listener();
        counseling.retry(s.id(), retried);
        assertThat(retried.errorKinds).isEmpty();
        assertThat(retried.done.status()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(retried.text.toString()).isEqualTo("다시 드리는 답변");

        List<Message> all = messageRepository.findBySession(s.id());
        assertThat(all).filteredOn(m -> m.role() == MessageRole.USER).hasSize(1); // not saved twice
        assertThat(all).filteredOn(m -> m.status() == MessageStatus.FAILED).hasSize(1); // the partial reply is retired
        assertThat(all.get(all.size() - 1).content()).isEqualTo("다시 드리는 답변");
        // the retired partial text is not shown to the model; the user's message is
        List<ContextMessage> ctx = llm.contexts.get(llm.contexts.size() - 1).messages();
        assertThat(ctx).extracting(ContextMessage::content).contains("질문입니다").doesNotContain("부분 응답");

        // nothing to retry once the reply is in place
        Listener again = new Listener();
        counseling.retry(s.id(), again);
        assertThat(again.errorKinds).containsExactly("ALREADY_ANSWERED");
        assertThat(messageRepository.findBySession(s.id())).hasSize(all.size());
    }

    @Test
    void retryRegeneratesAFailedGreetingAndKeepsTheStoredSafetyLevel() {
        Session s = sessions.start(null);
        llm.streamFailure = new LlmException(LlmException.Kind.TIMEOUT, "slow");
        counseling.opening(s.id(), new Listener());
        llm.streamFailure = null;

        Listener retried = new Listener();
        counseling.retry(s.id(), retried);
        assertThat(retried.done).isNotNull();
        // the opening cue is used again, nothing else is in the conversation
        assertThat(llm.contexts.get(llm.contexts.size() - 1).messages())
                .singleElement().extracting(ContextMessage::role).isEqualTo("developer");

        // an elevated user message keeps its safety mode on retry (safety is not re-evaluated, the stored level is reused)
        llm.crisisLevel = "ELEVATED";
        llm.streamFailure = new LlmException(LlmException.Kind.SERVER, "boom");
        Listener elevated = new Listener();
        counseling.reply(s.id(), "요즘 정말 죽고 싶다는 생각이 자주 들어", elevated);
        assertThat(elevated.risk).isEqualTo(RiskLevel.ELEVATED);
        llm.streamFailure = null;
        Listener after = new Listener();
        counseling.retry(s.id(), after);
        assertThat(after.risk).isEqualTo(RiskLevel.ELEVATED);
        assertThat(llm.contexts.get(llm.contexts.size() - 1).instructions()).contains("안전"); // safety addendum present
        assertThat(after.done.status()).isEqualTo(MessageStatus.COMPLETE);
    }
}
