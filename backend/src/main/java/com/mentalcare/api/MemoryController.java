package com.mentalcare.api;

import com.mentalcare.api.dto.ApiDtos.MemoryDto;
import com.mentalcare.api.dto.ApiDtos.ObservationDto;
import com.mentalcare.api.dto.ApiDtos.UpdateMemoryRequest;
import com.mentalcare.common.Dates;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.MemoryObservation;
import com.mentalcare.domain.Session;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.SessionRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memories;
    private final SessionRepository sessions;

    public MemoryController(MemoryService memories, SessionRepository sessions) {
        this.memories = memories;
        this.sessions = sessions;
    }

    /** @param status comma-separated statuses; default CANDIDATE,ACTIVE; "ALL" for everything */
    @GetMapping
    public List<MemoryDto> list(@RequestParam(required = false) String status) {
        Set<MemoryStatus> statuses = parseStatuses(status);
        List<Memory> list = memories.list(statuses);
        return toDtos(list);
    }

    @PatchMapping("/{id}")
    public MemoryDto update(@PathVariable long id, @Valid @RequestBody UpdateMemoryRequest request) {
        Memory updated = memories.update(id, request.content(), request.category(), request.status());
        return toDtos(List.of(updated)).get(0);
    }

    private List<MemoryDto> toDtos(List<Memory> list) {
        List<Long> ids = list.stream().map(Memory::id).toList();
        List<MemoryObservation> observations = memories.observations(ids);
        Map<Long, String> dates = sessions.findByIds(observations.stream().map(MemoryObservation::sessionId).distinct().toList())
                .stream().collect(Collectors.toMap(Session::id, s -> Dates.isoDate(s.startedAt())));
        Map<Long, List<ObservationDto>> byMemory = new HashMap<>();
        for (MemoryObservation o : observations) {
            byMemory.computeIfAbsent(o.memoryId(), k -> new ArrayList<>())
                    .add(ObservationDto.of(o, dates.getOrDefault(o.sessionId(), "")));
        }
        return list.stream().map(m -> MemoryDto.of(m, byMemory.getOrDefault(m.id(), List.of()))).toList();
    }

    private static Set<MemoryStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.of(MemoryStatus.CANDIDATE, MemoryStatus.ACTIVE);
        if ("ALL".equalsIgnoreCase(raw.trim())) return EnumSet.noneOf(MemoryStatus.class);
        Set<MemoryStatus> out = EnumSet.noneOf(MemoryStatus.class);
        for (String s : raw.split(",")) {
            try {
                out.add(MemoryStatus.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown memory status: " + s);
            }
        }
        return out;
    }
}
