package com.mentalcare.api.dto;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.Dates;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.MemoryObservation;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.PromptVersion;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.domain.WeeklyCheckIn;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response records of the REST API. Kept separate from domain records on purpose. */
public final class ApiDtos {
    private ApiDtos() {}

    // ------------------------------------------------------------------ requests

    public record CheckInRequest(
            @Min(1) @Max(10) Integer mood,
            @Min(1) @Max(10) Integer stress,
            @Min(1) @Max(10) Integer anxiety,
            @Min(1) @Max(10) Integer energy,
            @Min(1) @Max(10) Integer sleep,
            @Size(max = 2000) String note) {}

    public record CreateSessionRequest(CheckInRequest checkIn) {}

    public record SendMessageRequest(@NotBlank @Size(max = 20000) String content) {}

    public record UpdateMemoryRequest(@Size(max = 2000) String content, @Size(max = 50) String category, MemoryStatus status) {}

    public record UpdateSettingsRequest(String counselingReasoningEffort, Integer recentSessionCount,
                                        @Size(max = 4000) String profileSummary, @Size(max = 4000) String goals,
                                        @Size(max = 200) String counselingModel, @Size(max = 200) String utilityModel,
                                        Boolean welcomeShown) {}

    // ------------------------------------------------------------------ responses

    public record SessionDto(long id, String status, String kind, String title, Instant startedAt, Instant endedAt,
                             String date, boolean crisisFlag, String promptVersion) {
        public static SessionDto of(Session s, PromptVersion pv) {
            return new SessionDto(s.id(), s.status().name(), s.kind().name(), s.title(), s.startedAt(), s.endedAt(),
                    Dates.isoDate(s.startedAt()), s.crisisFlag(),
                    pv == null ? null : pv.name() + " " + pv.version() + " (" + pv.contentHash() + ")");
        }
    }

    public record CheckInDto(Integer mood, Integer stress, Integer anxiety, Integer energy, Integer sleep, String note) {
        public static CheckInDto of(WeeklyCheckIn c) {
            return c == null ? null : new CheckInDto(c.mood(), c.stress(), c.anxiety(), c.energy(), c.sleep(), c.note());
        }
    }

    public record MessageDto(long id, String role, String content, String status, String riskLevel, Instant createdAt) {
        public static MessageDto of(Message m) {
            return new MessageDto(m.id(), m.role().name(), m.content(), m.status().name(),
                    m.riskLevel() == null ? null : m.riskLevel().name(), m.createdAt());
        }
    }

    public record SummaryDto(SessionSummaryOutput output, String model, String markdownPath, Instant createdAt) {
        public static SummaryDto of(SessionSummary s) {
            return s == null ? null : new SummaryDto(s.output(), s.model(), s.markdownPath(), s.createdAt());
        }
    }

    public record SessionDetailDto(SessionDto session, CheckInDto checkIn, List<MessageDto> messages,
                                   SummaryDto summary, List<String> tags, String importedMarkdown) {}

    public record SessionListItemDto(SessionDto session, List<String> topics, List<String> tags,
                                     String oneLine, CheckInDto checkIn) {}

    public record CompletionDto(SessionDto session, SummaryDto summary, List<String> tags,
                                int memoriesCreated, int memoriesMerged, int memoriesActivated, String warning) {}

    public record MemoryDto(long id, String content, String kind, String category, String status,
                            Long firstObservedSessionId, Long lastObservedSessionId, int observationCount,
                            Instant createdAt, Instant updatedAt, List<ObservationDto> observations) {
        public static MemoryDto of(Memory m, List<ObservationDto> observations) {
            return new MemoryDto(m.id(), m.content(), m.kind().name(), m.category(), m.status().name(),
                    m.firstObservedSessionId(), m.lastObservedSessionId(), m.observationCount(),
                    m.createdAt(), m.updatedAt(), observations);
        }
    }

    public record ObservationDto(long sessionId, String sessionDate, String note) {
        public static ObservationDto of(MemoryObservation o, String sessionDate) {
            return new ObservationDto(o.sessionId(), sessionDate, o.note());
        }
    }

    public record SettingsDto(String authMode, boolean aiReady, String aiUnavailableReason,
                              String counselingModel, String utilityModel, String moderationModel,
                              String defaultCounselingModel, String defaultUtilityModel,
                              String counselingReasoningEffort, String defaultReasoningEffort,
                              int recentSessionCount, String profileSummary, String goals,
                              String dataDir, String markdownDir, String credentialsDir, String credentialProvider,
                              boolean apiKeyConfigured, boolean welcomeShown,
                              List<UsageDto> usageLast30Days) {}

    public record UsageDto(String model, String requestType, long requests, long inputTokens, long outputTokens) {}

    public record ErrorDto(String message, String kind, Long relatedId, Map<String, String> fields) {}
}
