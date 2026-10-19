package com.mentalcare.context;

/** One conversational input item sent to the model. Role is "user", "assistant" or "developer". */
public record ContextMessage(String role, String content) {
    public static ContextMessage user(String content) { return new ContextMessage("user", content); }
    public static ContextMessage assistant(String content) { return new ContextMessage("assistant", content); }
    public static ContextMessage developer(String content) { return new ContextMessage("developer", content); }
}
