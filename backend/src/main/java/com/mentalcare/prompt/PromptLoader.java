package com.mentalcare.prompt;

import com.mentalcare.domain.PromptVersion;
import com.mentalcare.repository.PromptVersionRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prompt files from {@code classpath:prompts/<name>-v<N>.md}. The highest version wins.
 * Each loaded prompt is registered in prompt_versions (name, version, content hash) so a session can
 * record exactly which prompt text it was run with.
 */
@Component
public class PromptLoader {

    public static final String COUNSELOR_SYSTEM = "counselor-system";
    public static final String OPENING_CUE = "opening-cue";
    public static final String SESSION_SUMMARY = "session-summary";
    public static final String CRISIS_ASSESSMENT = "crisis-assessment";
    public static final String CRISIS_RESPONSE = "crisis-response";
    public static final String SAFETY_ADDENDUM = "safety-addendum";

    /** A prompt text with its registered version row. */
    public record LoadedPrompt(String name, String version, String content, PromptVersion registered) {
        public long versionId() { return registered.id(); }
    }

    private static final Pattern FILE = Pattern.compile("^(.+)-v(\\d+)\\.md$");

    private final PromptVersionRepository repository;
    private final Map<String, LoadedPrompt> cache = new ConcurrentHashMap<>();

    public PromptLoader(PromptVersionRepository repository) {
        this.repository = repository;
    }

    public LoadedPrompt load(String name) {
        return cache.computeIfAbsent(name, this::loadFromClasspath);
    }

    private LoadedPrompt loadFromClasspath(String name) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:prompts/" + name + "-v*.md");
            Resource best = null;
            int bestVersion = -1;
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) continue;
                Matcher m = FILE.matcher(filename);
                if (!m.matches() || !m.group(1).equals(name)) continue;
                int version = Integer.parseInt(m.group(2));
                if (version > bestVersion) {
                    bestVersion = version;
                    best = r;
                }
            }
            if (best == null) {
                throw new IllegalStateException("Prompt not found: prompts/" + name + "-v*.md");
            }
            String content = best.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
            String version = "v" + bestVersion;
            PromptVersion registered = repository.findOrCreate(name, version, sha256(content));
            return new LoadedPrompt(name, version, content, registered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
