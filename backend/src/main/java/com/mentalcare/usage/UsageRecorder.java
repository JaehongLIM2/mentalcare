package com.mentalcare.usage;

import com.mentalcare.ai.LlmUsage;
import com.mentalcare.repository.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Records token usage per request. Logs only counts, never content. */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    public static final String COUNSELING = "counseling";
    public static final String SUMMARY = "summary";
    public static final String CRISIS_ASSESSMENT = "crisis_assessment";

    private final UsageRepository repository;

    public UsageRecorder(UsageRepository repository) {
        this.repository = repository;
    }

    public void record(String requestType, LlmUsage usage, Long sessionId) {
        if (usage == null) return;
        try {
            repository.insert(requestType, usage.model(), usage.inputTokens(), usage.outputTokens(), sessionId);
            log.info("usage type={} model={} in={} out={} session={}", requestType, usage.model(),
                    usage.inputTokens(), usage.outputTokens(), sessionId);
        } catch (RuntimeException e) {
            log.warn("Failed to record usage: {}", e.getClass().getSimpleName());
        }
    }
}
