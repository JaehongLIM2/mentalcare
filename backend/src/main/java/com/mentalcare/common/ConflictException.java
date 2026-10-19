package com.mentalcare.common;

/** The request conflicts with current state (e.g. an ACTIVE session already exists). */
public class ConflictException extends RuntimeException {
    private final Long relatedId;

    public ConflictException(String message, Long relatedId) {
        super(message);
        this.relatedId = relatedId;
    }

    public Long relatedId() {
        return relatedId;
    }
}
