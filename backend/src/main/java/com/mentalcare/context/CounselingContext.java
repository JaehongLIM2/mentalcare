package com.mentalcare.context;

import java.util.List;

/**
 * Everything the counseling model needs for one turn, assembled by {@link CounselingContextBuilder}.
 * Provider-neutral: it does not know about OpenAI request formats.
 *
 * @param model           model slug to use (resolved from the account's catalog / preferences)
 * @param instructions    system/developer instructions (system prompt + delimited user data blocks)
 * @param messages        the current session's conversation (plus an optional developer cue)
 * @param reasoningEffort effort hint for the model ("low" | "medium" | "high")
 */
public record CounselingContext(String model, String instructions, List<ContextMessage> messages, String reasoningEffort) {}
