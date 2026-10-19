package com.mentalcare.ai.openai;

import java.util.function.BiConsumer;

/**
 * Minimal Server-Sent Events line parser. Feed raw lines; complete events are dispatched as
 * (eventName, data). Handles multi-line data and ignores comments.
 */
public final class SseEventReader {

    private final BiConsumer<String, String> onEvent;
    private String eventName = "message";
    private final StringBuilder data = new StringBuilder();
    private boolean hasData = false;

    public SseEventReader(BiConsumer<String, String> onEvent) {
        this.onEvent = onEvent;
    }

    public void line(String line) {
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.startsWith(":")) {
            return; // comment / keep-alive
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> eventName = value;
            case "data" -> {
                if (hasData) data.append('\n');
                data.append(value);
                hasData = true;
            }
            default -> { /* id, retry: ignored */ }
        }
    }

    /** Dispatches a trailing event that was not terminated by a blank line. */
    public void finish() {
        dispatch();
    }

    private void dispatch() {
        if (!hasData) {
            eventName = "message";
            return;
        }
        String payload = data.toString();
        String name = eventName;
        data.setLength(0);
        hasData = false;
        eventName = "message";
        onEvent.accept(name, payload);
    }
}
