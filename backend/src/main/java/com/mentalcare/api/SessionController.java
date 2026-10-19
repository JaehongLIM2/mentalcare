package com.mentalcare.api;

import com.mentalcare.api.dto.ApiDtos.*;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.SessionService.CheckInInput;
import com.mentalcare.domain.Session;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final SessionCompletionService completion;

    public SessionController(SessionService sessions, SessionCompletionService completion) {
        this.sessions = sessions;
        this.completion = completion;
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

    @GetMapping
    public List<SessionListItemDto> list(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return sessions.list(Math.min(Math.max(limit, 1), 200), Math.max(offset, 0)).stream()
                .map(item -> new SessionListItemDto(
                        SessionDto.of(item.session(), null),
                        item.summary() == null ? List.of() : item.summary().output().topics(),
                        item.tags(),
                        oneLine(item),
                        CheckInDto.of(item.checkIn())))
                .toList();
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
                d.tags());
    }

    @PostMapping("/{id}/complete")
    public CompletionDto complete(@PathVariable long id) {
        SessionCompletionService.CompletionResult r = completion.complete(id);
        return new CompletionDto(SessionDto.of(r.session(), null), SummaryDto.of(r.summary()), r.tags(),
                r.memory().created(), r.memory().merged(), r.memory().activated(), r.warning());
    }

    private static String oneLine(SessionService.SessionListItem item) {
        if (item.summary() == null) return null;
        var o = item.summary().output();
        if (!o.keyEvents().isEmpty()) return o.keyEvents().get(0);
        if (!o.topics().isEmpty()) return String.join(", ", o.topics());
        return null;
    }
}
