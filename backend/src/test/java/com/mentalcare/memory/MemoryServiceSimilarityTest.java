package com.mentalcare.memory;

import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceSimilarityTest {

    private static Memory mem(long id, String content) {
        return new Memory(id, content, MemoryKind.USER_STATED, "concern", MemoryStatus.CANDIDATE, 1L, 1L, 1,
                Instant.now(), Instant.now());
    }

    @Test
    void nearDuplicateIsMatchedAndDifferentContentIsNot() {
        List<Memory> existing = List.of(
                mem(1, "상사와의 관계가 반복적인 스트레스의 원인이다."),
                mem(2, "주말에 운동을 다시 시작하고 싶어 한다."));

        assertThat(MemoryService.findSimilar(existing, "상사와의 관계가 반복적인 스트레스의 원인이다"))
                .map(Memory::id).contains(1L);
        assertThat(MemoryService.findSimilar(existing, "상사와의 관계가 반복적인 스트레스 원인"))
                .map(Memory::id).contains(1L);
        assertThat(MemoryService.findSimilar(existing, "여자친구와의 갈등으로 잠을 잘 못 잔다")).isEmpty();
    }
}
