package com.mentalcare.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Process-level endpoints used by the Windows launcher scripts.
 * Both are reachable only from this PC (see LocalRequestGuardFilter).
 */
@RestController
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final ConfigurableApplicationContext context;

    public SystemController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Cheap readiness probe: the context is up and the HTTP layer answers. No data access. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "app", "mentalcare");
    }

    /**
     * Graceful shutdown for the "stop" script: closes the datasource (SQLite WAL checkpoint) and exits.
     * The response is sent first; the JVM exits ~300 ms later.
     */
    @PostMapping("/api/system/shutdown")
    public Map<String, String> shutdown() {
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
