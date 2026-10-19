package com.mentalcare.api;

import com.mentalcare.api.dto.ApiDtos.*;
import com.mentalcare.context.ContextOutline;
import com.mentalcare.context.CounselingContextBuilder;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.SessionService.CheckInInput;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.TagRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final SessionCompletionService completion;
    private final CounselingContextBuilder contextBuilder;
    private final TagRepository tags;

    public SessionController(SessionService sessions, SessionCompletionService completion,
                             CounselingContextBuilder contextBuilder, TagRepository tags) {
        this.sessions = sessions;
        this.completion = completion;
        this.contextBuilder = contextBuilder;
        this.tags = tags;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        CheckInRequest c = request == null ? null : request.checkIn();
        CheckInInput input = c == null ? null
                : new CheckInInput(c.mood(), c.stress(), c.anxiety(), c.energy(), c.sleep(), c.note());
        Session session = sessions.start(input);
        return SessionDto.of(session, null);
    }

    /**
     * History list, newest first. Optional filters: {@code q} (keyword in title, conversation, summary,
     * check-in note, imported text), {@code from}/{@code to} (local dates, inclusive), {@code tag}.
     * The total number of matches is returned in the {@code X-Total-Count} header.
     */
    @GetMapping
    public ResponseEntity<List<SessionListItemDto>> list(@RequestParam(defaultValue = "50") int limit,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to,
                                                         @RequestParam(required = false) String tag) {
        if (q != null && q.length() > 200) throw new IllegalArgumentException("검색어가 너무 깁니다 (최대 200자).");
        if (tag != null && tag.length() > 100) throw new IllegalArgumentException("태그가 너무 깁니다.");
        SessionRepository.Filter filter = new SessionRepository.Filter(q, date(from), date(to), tag);
        SessionService.SessionPage page = sessions.search(filter, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
        List<SessionListItemDto> items = page.items().stream()
                .map(item -> new SessionListItemDto(
                        SessionDto.of(item.session(), null),
                        item.summary() == null ? List.of() : item.summary().output().topics(),
                        item.tags(),
                        oneLine(item),
                        CheckInDto.of(item.checkIn())))
                .toList();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(page.total())).body(items);
    }

    @GetMapping("/tags")
    public List<TagCountDto> tagList() {
        return tags.allWithCounts().stream().map(t -> new TagCountDto(t.name(), t.count())).toList();
    }

    @GetMapping("/active")
    public ResponseEntity<SessionDto> active() {
        return sessions.active()
                .map(s -> ResponseEntity.ok(SessionDto.of(s, null)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public SessionDetailDto detail(@PathVariable long id) {
        SessionService.SessionDetail d = sessions.detail(id);
        return new SessionDetailDto(
                SessionDto.of(d.session(), d.promptVersion()),
                CheckInDto.of(d.checkIn()),
                d.messages().stream().map(MessageDto::of).toList(),
                SummaryDto.of(d.summary()),
                d.tags(),
                d.importedMarkdown(),
                DraftDto.of(d.draft()));
    }

    /** What the counselor will refer to on the next turn: memories, summaries, imported archives, message budget. */
    @GetMapping("/{id}/context")
    public ContextOutline context(@PathVariable long id) {
        SessionService.SessionDetail d = sessions.detail(id);
        return contextBuilder.outline(d.session(), d.messages());
    }

    /** Unsent composer text. POST (not PUT) so the browser can send it with {@code keepalive} on tab close. */
    @PostMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveDraft(@PathVariable long id, @Valid @RequestBody(required = false) SaveDraftRequest request) {
        sessions.saveDraft(id, request == null ? null : request.content());
    }

    @PostMapping("/{id}/complete")
    public CompletionDto complete(@PathVariable long id) {
        SessionCompletionService.CompletionResult r = completion.complete(id);
        return new CompletionDto(SessionDto.of(r.session(), null), SummaryDto.of(r.summary()), r.tags(),
                r.memory().created(), r.memory().merged(), r.memory().activated(), r.warning());
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜 형식은 YYYY-MM-DD 입니다.");
        }
    }

    private static String oneLine(SessionService.SessionListItem item) {
        if (item.summary() == null) return null;
        var o = item.summary().output();
        if (!o.keyEvents().isEmpty()) return o.keyEvents().get(0);
        if (!o.topics().isEmpty()) return String.join(", ", o.topics());
        return null;
    }
}
