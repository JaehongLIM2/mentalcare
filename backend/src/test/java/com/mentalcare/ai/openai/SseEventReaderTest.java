package com.mentalcare.ai.openai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventReaderTest {

    @Test
    void parsesNamedEventsAndMultilineData() {
        List<String> events = new ArrayList<>();
        SseEventReader reader = new SseEventReader((name, data) -> events.add(name + "|" + data));

        reader.line(": keep-alive");
        reader.line("event: response.output_text.delta");
        reader.line("data: {\"delta\":\"안\"}");
        reader.line("");
        reader.line("event: custom");
        reader.line("data: line1");
        reader.line("data: line2");
        reader.line("");
        reader.line("data: trailing");
        reader.finish();

        assertThat(events).containsExactly(
                "response.output_text.delta|{\"delta\":\"안\"}",
                "custom|line1\nline2",
                "message|trailing");
    }
}
