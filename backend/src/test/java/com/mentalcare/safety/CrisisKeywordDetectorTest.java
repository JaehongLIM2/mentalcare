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
        // conjugated forms: the syllable after the stem changes (내리 -> 내릴), so the stem alone is not enough
        assertThat(detector.matches("오늘 밤에 뛰어내릴 생각이야")).isTrue();
        assertThat(detector.matches("옥상에서 뛰어내렸으면 좋겠다")).isTrue();
        assertThat(detector.matches("그냥 죽어버릴까")).isTrue();
        assertThat(detector.matches("목을 맬 끈을 샀어")).isTrue();
        assertThat(detector.matches("버스에서 내릴 때 뛰어서 넘어졌다")).isFalse();
    }
}
