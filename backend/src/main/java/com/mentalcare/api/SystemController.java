package com.mentalcare.api;

import com.mentalcare.common.ConflictException;
import com.mentalcare.config.AppProperties;
import com.mentalcare.counseling.SessionLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Process-level endpoints used by the Windows launcher scripts and the in-app status card.
 * All of them are reachable only from this PC (see LocalRequestGuardFilter).
 */
@RestController
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    public record StatusDto(String status, String app, String version, long pid, Instant startedAt, long uptimeSeconds,
                            int port, String dataDir, int busySessions, boolean docker) {}


    private final ConfigurableApplicationContext context;
    private final Environment env;
    private final AppProperties app;
    private final SessionLocks locks;
    private final String version;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final Instant startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    public SystemController(ConfigurableApplicationContext context, Environment env, AppProperties app, SessionLocks locks,
                            @Value("${mentalcare.version:dev}") String version,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
        this.context = context;
        this.env = env;
        this.app = app;
        this.locks = locks;
        this.version = version;
    }

    /** Cheap readiness probe: the context is up and the HTTP layer answers. No data access. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "app", "mentalcare");
    }

    /** Runtime facts for the status card: when the server started, where it listens, whether a turn is running. */
    @GetMapping("/api/system/status")
    public StatusDto status() {
        int port = env.getProperty("local.server.port", Integer.class, env.getProperty("server.port", Integer.class, 8080));
        boolean docker = Path.of("/.dockerenv").toFile().exists();
        return new StatusDto("UP", "mentalcare", version, ProcessHandle.current().pid(), startedAt,
                Duration.between(startedAt, Instant.now()).getSeconds(), port,
                Path.of(app.dataDir()).toAbsolutePath().normalize().toString(), locks.busyCount(), docker);
    }

    /** {@code {"ifIdle":true}} → only quit when idle; anything else (no body, other JSON) → quit unconditionally. */
    static boolean wantsIdleOnly(String body, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (body == null || body.isBlank()) return false;
        try {
            return mapper.readTree(body).path("ifIdle").asBoolean(false);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Graceful shutdown: closes the datasource (SQLite WAL checkpoint) and exits. Used by the "stop" script
     * (no body) and by the in-app button, which sends {@code {"ifIdle":true}} so a reply that is still being
     * generated is never cut off. The response is sent first; the JVM exits ~300 ms later.
     */
    @PostMapping("/api/system/shutdown")
    public Map<String, String> shutdown(@RequestBody(required = false) String body) {
        // read as text: the stop script posts an empty body with a form content type, which must keep working
        if (wantsIdleOnly(body, mapper)) {
            // exclusive with every kind of work, and no new work is admitted afterwards
            if (!locks.closeIfIdle()) {
                throw new ConflictException("응답 생성이나 데이터 작업이 진행 중입니다. 끝난 뒤 종료하세요.", null);
            }
        } else {
            locks.close();
        }
        log.info("shutdown requested via /api/system/shutdown");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context, () -> 0));
        }, "mentalcare-shutdown");
        t.setDaemon(false);
        t.start();
        return Map.of("status", "shutting-down");
    }
}
