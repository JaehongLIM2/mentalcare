package com.mentalcare.api;

import com.mentalcare.api.dto.ApiDtos.MessageDto;
import com.mentalcare.api.dto.ApiDtos.SendMessageRequest;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming endpoints. Events: user_saved, safety, safety_unavailable, context, delta, done, error.
 * The counseling turn keeps running (and is persisted) even if the browser disconnects.
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class MessageStreamController {

    private static final Logger log = LoggerFactory.getLogger(MessageStreamController.class);
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final CounselingService counseling;
    private final ExecutorService executor;

    public MessageStreamController(CounselingService counseling, ExecutorService streamExecutor) {
        this.counseling = counseling;
        this.executor = streamExecutor;
    }

    @PostMapping(value = "/opening", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter opening(@PathVariable long id) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        EmitterListener listener = new EmitterListener(emitter);
        executor.submit(() -> run(() -> counseling.opening(id, listener), listener));
        return emitter;
    }

    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable long id, @Valid @RequestBody SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        EmitterListener listener = new EmitterListener(emitter);
        executor.submit(() -> run(() -> counseling.reply(id, request.content(), request.draftRev(), attachments(request), listener), listener));
        return emitter;
    }

    private static List<com.mentalcare.counseling.Attachments.Input> attachments(SendMessageRequest request) {
        if (request.attachments() == null) return List.of();
        return request.attachments().stream()
                .map(a -> new com.mentalcare.counseling.Attachments.Input(a.name(), a.content())).toList();
    }

    /** Asks for the reply again after an interruption; the user's message is not saved twice. */
    @PostMapping(value = "/retry", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter retry(@PathVariable long id) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        EmitterListener listener = new EmitterListener(emitter);
        executor.submit(() -> run(() -> counseling.retry(id, listener), listener));
        return emitter;
    }

    private void run(Runnable turn, EmitterListener listener) {
        try {
            turn.run();
        } catch (RuntimeException e) {
            log.error("Streaming turn failed: {}", e.getClass().getName());
            listener.onError("상담 처리 중 오류가 발생했습니다.", "INTERNAL");
        } finally {
            listener.close();
        }
    }

    /** Bridges TurnListener callbacks to SSE events; becomes a no-op once the client is gone. */
    static final class EmitterListener implements TurnListener {
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        EmitterListener(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(() -> open.set(false));
            emitter.onTimeout(() -> open.set(false));
            emitter.onError(t -> open.set(false));
        }

        @Override public void onUserSaved(Message m) { send("user_saved", Map.of("messageId", m.id())); }
        @Override public void onSafety(RiskLevel level) { send("safety", Map.of("riskLevel", level.name())); }
        @Override public void onSafetyUnavailable() { send("safety_unavailable", Map.of("unavailable", true)); }
        @Override public void onContext(int included, int omitted) { send("context", Map.of("includedMessages", included, "omittedMessages", omitted)); }
        @Override public void onDelta(String text) { send("delta", Map.of("text", text)); }
        @Override public void onDone(Message m) { send("done", MessageDto.of(m)); }
        @Override public void onError(String message, String kind) { send("error", Map.of("message", message, "kind", kind)); }

        void close() {
            if (open.getAndSet(false)) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // client already gone
                }
            }
        }

        private void send(String name, Object data) {
            if (!open.get()) return;
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open.set(false); // client disconnected; keep processing so the reply is still saved
            }
        }
    }
}
