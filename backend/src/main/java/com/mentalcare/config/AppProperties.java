package com.mentalcare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Application-level settings (prefix {@code mentalcare}). All values can be overridden in
 * application.yml or by environment variables; none of them are secrets.
 */
@ConfigurationProperties(prefix = "mentalcare")
public record AppProperties(
        String dataDir,
        String markdownDir,
        String credentialsDir,
        int recentSessionCount,
        int recommendedIntervalDays,
        Context context,
        Memory memory,
        Safety safety,
        Security security
) {
    public record Context(int maxActiveMemories, int maxListItemsPerSummary, int importedContextMaxChars) {
        public int importedContextMaxCharsOrDefault() {
            return importedContextMaxChars <= 0 ? 6000 : importedContextMaxChars;
        }
    }

    public record Memory(int autoActivateUserStated, int autoActivateAiObserved) {}

    public record Safety(double moderationThreshold, int assessmentContextMessages, List<String> emergencyResources) {}

    /** @param allowedHosts Host header values (without port) accepted by the local request guard. */
    public record Security(List<String> allowedHosts) {}

    public String credentialsDirOrDefault() {
        return credentialsDir == null || credentialsDir.isBlank() ? dataDir + "/credentials" : credentialsDir;
    }

    public List<String> allowedHostsOrDefault() {
        if (security == null || security.allowedHosts() == null || security.allowedHosts().isEmpty()) {
            return List.of("127.0.0.1", "localhost", "[::1]");
        }
        return security.allowedHosts();
    }
}
