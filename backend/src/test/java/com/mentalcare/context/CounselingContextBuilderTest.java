package com.mentalcare.context;

import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CounselingContextBuilderTest {

    private static Memory mem(long id, String content) {
        return new Memory(id, content, MemoryKind.AI_OBSERVED, "pattern", MemoryStatus.ACTIVE, 1L, 2L, 3,
                Instant.now(), Instant.now());
    }

    @Test
    void dedupeRemovesTextualDuplicatesAndCapsCount() {
        List<Memory> input = List.of(
                mem(1, "평가에 대한 걱정이 앞서는 경향"),
                mem(2, "평가에 대한 걱정이 앞서는 경향."),
                mem(3, "수면 부족이 반복됨"),
                mem(4, "주말마다 가족과 갈등"));

        List<Memory> out = CounselingContextBuilder.dedupe(input, 2);

        assertThat(out).extracting(Memory::id).containsExactly(1L, 3L);
    }
}
