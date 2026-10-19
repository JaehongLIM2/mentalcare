package com.mentalcare.counseling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side draft revisions for callers that do not send one. Same scale as the browser's
 * ({@code Date.now() * 1000} plus a counter): the browser and the server run on the same PC.
 */
public final class DraftRevisions {
    private static final AtomicLong last = new AtomicLong();

    private DraftRevisions() {}

    public static long next() {
        long now = System.currentTimeMillis() * 1000;
        return last.updateAndGet(prev -> Math.max(now, prev + 1));
    }
}
