package com.mentalcare.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Gives each Spring test context its own fresh data directory (SQLite, Markdown, credentials). */
public final class TestDirs {
    private TestDirs() {}

    public static void register(DynamicPropertyRegistry registry, String label) {
        String dir = System.getProperty("java.io.tmpdir") + "/mentalcare-test-" + label + "-" + System.nanoTime();
        registry.add("mentalcare.data-dir", () -> dir);
        registry.add("mentalcare.markdown-dir", () -> dir + "/sessions");
        registry.add("mentalcare.credentials-dir", () -> dir + "/credentials");
    }
}
