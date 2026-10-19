package com.mentalcare.counseling;

import com.mentalcare.common.ConflictException;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.MessageRepository;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Turns, completion and deletion on one session must never interleave (two tabs, double clicks). */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class SessionConcurrencyTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "concurrency");
    }

    static class Listener implements TurnListener {
        final List<String> errorKinds = new ArrayList<>();
        Message done;
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) { done = m; }
        @Override public synchronized void onError(String m, String k) { errorKinds.add(k); }
    }

    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired DataDeletionService deletion;
    @Autowired MessageRepository messageRepository;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        llm.reset();
        pool.shutdownNow();
        sessions.active().ifPresent(s -> {
            try { completion.complete(s.id()); } catch (RuntimeException ignored) { }
        });
    }

    @Test
    void simultaneousOpeningsProduceExactlyOneGreeting() throws Exception {
        llm.reset();
        Session s = sessions.start(null);
        CountDownLatch gate = new CountDownLatch(1);
        llm.gate = gate;
        Listener a = new Listener(), b = new Listener();

        Future<?> first = pool.submit(() -> counseling.opening(s.id(), a));
        Thread.sleep(300); // first turn is now blocked inside the fake stream, holding the lock
        Future<?> second = pool.submit(() -> counseling.opening(s.id(), b));
        second.get(5, TimeUnit.SECONDS);
        gate.countDown();
        first.get(5, TimeUnit.SECONDS);

        List<Message> msgs = messageRepository.findBySession(s.id());
        assertThat(msgs).filteredOn(m -> m.role() == MessageRole.ASSISTANT).hasSize(1);
        assertThat(List.of(a.errorKinds, b.errorKinds)).anySatisfy(k -> assertThat(k).containsExactly("BUSY"));
        assertThat(llm.contexts).hasSize(1);
    }

    @Test
    void turnsAreRefusedWhileDeleteAllHoldsTheGlobalLock() throws Exception {
        llm.reset();
        Session s = sessions.start(null);
        assertThat(locks.tryLockAll()).isTrue();
        try {
            Listener l = new Listener();
            counseling.opening(s.id(), l);
            assertThat(l.errorKinds).containsExactly("BUSY");
            assertThatThrownBy(() -> completion.complete(s.id())).isInstanceOf(ConflictException.class);
            // creating a new session and importing an archive participate in the global lock too
            assertThatThrownBy(() -> sessions.start(null)).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> imports.importArchive("x.md", "# ChatGPT 상담 이관 기록\n## 다음 상담 AI에게 전달할 핵심 맥락\nx\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isInstanceOf(ConflictException.class);
        } finally {
            locks.unlockAll();
        }
        counseling.opening(s.id(), new Listener());
        assertThat(messageRepository.findBySession(s.id())).hasSize(1);
    }

    @Autowired SessionLocks locks;
    @Autowired com.mentalcare.importing.ArchiveImportService imports;

    @Test
    void completionAndDeletionAreRefusedWhileAReplyIsStreaming() throws Exception {
        llm.reset();
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        CountDownLatch gate = new CountDownLatch(1);
        llm.gate = gate;
        Listener turn = new Listener();

        Future<?> reply = pool.submit(() -> counseling.reply(s.id(), "진행 중인 메시지", turn));
        Thread.sleep(300);
        assertThatThrownBy(() -> completion.complete(s.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> deletion.deleteSession(s.id())).isInstanceOf(ConflictException.class);
        // delete-all is global maintenance: refused while any turn is running, and no turn may start while it runs
        assertThatThrownBy(() -> deletion.deleteAll("DELETE")).isInstanceOf(ConflictException.class);
        gate.countDown();
        reply.get(5, TimeUnit.SECONDS);

        assertThat(turn.done).isNotNull();
        assertThat(completion.complete(s.id()).session().status().name()).isEqualTo("COMPLETED");
        // once completed, no further turn can append messages
        Listener late = new Listener();
        counseling.reply(s.id(), "늦은 메시지", late);
        assertThat(late.errorKinds).containsExactly("CONFLICT");
        assertThat(messageRepository.findBySession(s.id())).noneMatch(m -> m.content().equals("늦은 메시지"));
    }
}
