package com.mentalcare.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real backend processes ({@code java -cp ... MentalCareApplication}, the same start as {@code java -jar}),
 * each on its own port: the data directory, not the port, decides whether a second one may start.
 */
class DataDirectorySingleInstanceTest {

    private static final Duration START = Duration.ofSeconds(90);

    // Windows can keep a killed child's output file open for a moment; cleaned up below with retries instead
    @TempDir(cleanup = CleanupMode.NEVER) Path tmp;
    private final List<Process> started = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @AfterEach
    void killLeftovers() throws InterruptedException {
        for (Process p : started) {
            p.destroyForcibly();
            p.waitFor(30, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 20 && Files.exists(tmp); i++) {
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // retried
            }
            if (Files.exists(tmp)) Thread.sleep(250);
        }
    }

    @Test
    void sameDataOnAnotherPortIsRefused() throws Exception {
        Path data = tmp.resolve("data");
        Backend first = start(data, "first");
        assertThat(first.awaitHealthy()).as(first.output()).isTrue();

        Backend second = start(data, "second");

        assertThat(second.process.waitFor(START.toSeconds(), TimeUnit.SECONDS)).as("second process ends").isTrue();
        assertThat(second.process.exitValue()).isNotZero();
        assertThat(second.output()).contains("DataDirectoryInUseException", "already using this data directory");
        assertThat(first.isHealthy()).as("the first one is untouched").isTrue();
    }

    @Test
    void differentDataOnDifferentPortsRunSideBySide() throws Exception {
        Backend a = start(tmp.resolve("a"), "a");
        Backend b = start(tmp.resolve("b"), "b");

        assertThat(a.awaitHealthy()).as(a.output()).isTrue();
        assertThat(b.awaitHealthy()).as(b.output()).isTrue();
    }

    @Test
    void gracefulShutdownReleasesTheDirectory() throws Exception {
        Path data = tmp.resolve("data");
        Backend first = start(data, "first");
        assertThat(first.awaitHealthy()).as(first.output()).isTrue();

        http.send(HttpRequest.newBuilder(first.uri("/api/system/shutdown"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(first.process.waitFor(60, TimeUnit.SECONDS)).as("graceful exit").isTrue();

        Backend again = start(data, "again");
        assertThat(again.awaitHealthy()).as(again.output()).isTrue();
    }

    @Test
    void killedProcessLeavesNoStaleLock() throws Exception {
        Path data = tmp.resolve("data");
        Backend first = start(data, "first");
        assertThat(first.awaitHealthy()).as(first.output()).isTrue();

        first.process.destroyForcibly(); // TerminateProcess: no shutdown hook, no cleanup
        assertThat(first.process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(data.resolve(DataDirectoryLock.FILE_NAME)).exists(); // the file is left behind ...

        Backend again = start(data, "again");
        assertThat(again.awaitHealthy()).as(again.output()).isTrue(); // ... but its lock died with the process
    }

    // ---------- helpers ----------

    private record Backend(Process process, int port, Path log, HttpClient http) {
        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        boolean isHealthy() {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                return r.statusCode() == 200 && r.body().contains("mentalcare");
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean awaitHealthy() throws InterruptedException {
            long deadline = System.nanoTime() + START.toNanos();
            while (System.nanoTime() < deadline) {
                if (isHealthy()) return true;
                if (!process.isAlive()) return false;
                Thread.sleep(300);
            }
            return false;
        }

        String output() {
            try {
                return Files.readString(log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    }

    private Backend start(Path data, String label) throws IOException {
        int port = freePort();
        Path log = tmp.resolve(label + ".out");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "com.mentalcare.MentalCareApplication",
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--mentalcare.data-dir=" + data,
                "--openai.api-key=test-key",
                "--spring.main.banner-mode=off")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        pb.environment().remove("MENTALCARE_DATA_DIR");
        pb.environment().remove("MENTALCARE_CREDENTIALS_DIR");
        Process p = pb.start();
        started.add(p);
        return new Backend(p, port, log, http);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
