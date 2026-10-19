package com.mentalcare.ai;

/** Receives streamed text fragments of a counseling reply. */
@FunctionalInterface
public interface StreamHandler {
    void onDelta(String text);
}
