package com.mentalcare.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrisisKeywordDetectorTest {

    private final CrisisKeywordDetector detector = new CrisisKeywordDetector();

    @Test
    void idiomsDoNotMatch() {
        assertThat(detector.matches("회사 가기 싫어 죽겠다")).isFalse();
        assertThat(detector.matches("오늘 진짜 죽을 만큼 피곤하다")).isFalse();
        assertThat(detector.matches("")).isFalse();
        assertThat(detector.matches(null)).isFalse();
    }

    @Test
    void explicitPhrasesMatch() {
        assertThat(detector.matches("요즘 죽고 싶다는 생각이 들어요")).isTrue();
        assertThat(detector.matches("자해를 했어요")).isTrue();
        assertThat(detector.matches("死にたいです")).isTrue();
        assertThat(detector.matches("I want to die")).isTrue();
    }
}
