package com.mentalcare.launcher;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.awt.Desktop;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point of the jpackage app-image ({@code MentalCare.exe}). Plays the role of
 * {@code scripts\start-background.ps1}: reuse a running instance, refuse a foreign program on the port,
 * otherwise start the Spring Boot fat jar <b>in this same process</b> and open the browser once it is ready.
 * <p>
 * Uses only the JDK (no Spring on its own classpath): it ships as {@code mentalcare-launcher.jar} next to
 * {@code mentalcare.jar} and starts the latter through Spring Boot's own {@code JarLauncher}, so the backend
 * runs exactly as with {@code java -jar}. Never reads counseling data or credentials; it only decides the
 * data directory and passes it on.
 */
public final class MentalCareLauncher {

    static final int PORT = 8080;
    static final String URL = "http://127.0.0.1:" + PORT;
    static final String PORTABLE_HOME = "MentalCare-data";
    private static final String TITLE = "MentalCare";
    private static final Duration START_WARNING = Duration.ofSeconds(120);

    enum PortState { MENTALCARE, OTHER, FREE }

    /** Where data, logs and the optional .env live for this launch. */
    record Layout(String mode, Path home, Path dataDir, Path runDir) {}

    private static Path launcherLog;

    private MentalCareLauncher() {}

    public static void main(String[] args) {
        // Spring Boot defaults java.awt.headless to true unless it is already set; the browser and the
        // error dialogs need AWT, and the backend itself never uses it.
        System.setProperty("java.awt.headless", "false");
        List<String> forwarded = new ArrayList<>(List.of(args));
        boolean openBrowser = !forwarded.remove("--no-browser");

        Path image = appImageDir();
        Layout layout;
        try {
            layout = resolveLayout(image, System.getenv("LOCALAPPDATA"));
        } catch (IllegalStateException e) {
            fail(e.getMessage(), 10);
            return;
        }
        launcherLog = layout.runDir().resolve("launcher.log");

        Map<String, String> dotEnv = readDotEnv(layout.home().resolve(".env"));
        Path dataDir = dataDirOverride(System.getenv("MENTALCARE_DATA_DIR"), dotEnv.get("MENTALCARE_DATA_DIR"))
                .orElse(layout.dataDir());
        if (image != null && insideImage(dataDir, image)) {
            fail("데이터 폴더가 앱 폴더 안을 가리킵니다. 앱을 다시 빌드하면 지워지므로 사용할 수 없습니다.\n" + dataDir, 11);
            return;
        }
        if (!writable(dataDir)) {
            fail("데이터 폴더를 만들거나 쓸 수 없습니다.\n" + dataDir, 12);
            return;
        }
        log("launch mode=" + layout.mode() + " home=" + layout.home() + " data=" + dataDir);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        FileLock lock = acquireStartLock(layout.runDir());
        try {
            PortState state = probe(http);
            if (state == PortState.MENTALCARE) {
                release(lock);
                warnIfOtherDataDir(http, dataDir);
                log("already running; no new backend");
                if (openBrowser) browse();
                return;
            }
            if (state == PortState.OTHER) {
                release(lock);
                fail("포트 " + PORT + "을(를) 다른 프로그램이 사용 중입니다.\n"
                        + "그 프로그램을 종료한 뒤 다시 실행하세요. (MentalCare는 다른 프로그램을 종료하지 않습니다.)", 2);
                return;
            }

            // Dotenv values win over real environment variables, as in start-background.ps1; system properties
            // resolve the same ${...} placeholders in application.yml.
            dotEnv.forEach((k, v) -> { if (System.getProperty(k) == null) System.setProperty(k, v); });
            List<String> springArgs = new ArrayList<>(List.of(
                    "--server.address=127.0.0.1",
                    "--server.port=" + PORT,
                    "--mentalcare.data-dir=" + dataDir,
                    "--logging.file.name=" + layout.runDir().resolve("mentalcare.log")));
            springArgs.addAll(forwarded);

            Thread watchdog = startWatchdog(layout.runDir());
            try {
                startSpringBoot(springArgs.toArray(String[]::new));
            } catch (Throwable t) {
                watchdog.interrupt();
                release(lock);
                handleStartFailure(t, http, layout.runDir(), openBrowser);
                return;
            }
            watchdog.interrupt();
            if (!waitForHealth(http, Duration.ofSeconds(15))) {
                release(lock);
                showMessage("MentalCare가 시작되었지만 " + URL + " 에서 응답하지 않습니다.\n로그: "
                        + layout.runDir().resolve("mentalcare.log"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            log("started pid=" + ProcessHandle.current().pid());
            release(lock);
            if (openBrowser) browse();
            // main returns; Tomcat's non-daemon threads keep this process (MentalCare.exe) alive until
            // POST /api/system/shutdown (in-app button or scripts\stop.ps1).
        } finally {
            release(lock);
        }
    }

    // ---------- layout ----------

    /** {@code dist\MentalCare} when started from the exe, or the directory above {@code app\} for the jar. */
    static Path appImageDir() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isBlank()) return Path.of(exe).toAbsolutePath().getParent();
        try {
            Path jar = Path.of(MentalCareLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            return dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("app")
                    ? dir.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Picks the home directory (data, .run, .env), in this order:
     * <ol>
     *   <li>the image is {@code <repo>\dist\MentalCare} of a mentalcare checkout → the repository itself, so the
     *       exe shares {@code <repo>\data} and {@code <repo>\.run} with {@code MentalCare 시작.cmd}</li>
     *   <li>an existing {@code MentalCare-data} folder next to the image (portable copy)</li>
     *   <li>an existing {@code %LOCALAPPDATA%\MentalCare} (used before because the image folder was read-only)</li>
     *   <li>a new {@code MentalCare-data} next to the image, if that location is writable</li>
     *   <li>a new {@code %LOCALAPPDATA%\MentalCare}</li>
     * </ol>
     * Never inside the image: rebuilding replaces {@code dist\MentalCare} as a whole.
     */
    static Layout resolveLayout(Path image, String localAppData) {
        Path parent = image == null ? null : image.getParent();
        if (parent != null && parent.getParent() != null && isRepository(parent.getParent())) {
            Path repo = parent.getParent();
            return layout("repository", repo);
        }
        Path portable = parent == null ? null : parent.resolve(PORTABLE_HOME);
        Path local = localAppData == null || localAppData.isBlank() ? null : Path.of(localAppData, "MentalCare");
        if (portable != null && Files.isDirectory(portable.resolve("data"))) return layout("portable", portable);
        if (local != null && Files.isDirectory(local.resolve("data"))) return layout("localappdata", local);
        if (portable != null && writable(portable.resolve("data")) && writable(portable.resolve(".run"))) {
            return layout("portable", portable);
        }
        if (local != null && writable(local.resolve("data")) && writable(local.resolve(".run"))) {
            return layout("localappdata", local);
        }
        throw new IllegalStateException("데이터 폴더를 만들 수 있는 위치가 없습니다.\n"
                + (portable != null ? portable + "\n" : "") + (local != null ? local : "%LOCALAPPDATA% 없음"));
    }

    private static Layout layout(String mode, Path home) {
        Path h = home.toAbsolutePath().normalize();
        Path run = h.resolve(".run");
        try {
            Files.createDirectories(run);
        } catch (IOException e) {
            throw new IllegalStateException("로그 폴더를 만들 수 없습니다.\n" + run);
        }
        return new Layout(mode, h, h.resolve("data"), run);
    }

    static boolean isRepository(Path dir) {
        return Files.isRegularFile(dir.resolve("backend").resolve("pom.xml"))
                && Files.isRegularFile(dir.resolve("scripts").resolve("start-background.ps1"));
    }

    static java.util.Optional<Path> dataDirOverride(String env, String dotEnv) {
        String v = dotEnv != null && !dotEnv.isBlank() ? dotEnv : env;
        return v == null || v.isBlank() ? java.util.Optional.empty()
                : java.util.Optional.of(Path.of(v.trim()).toAbsolutePath().normalize());
    }

    static boolean insideImage(Path dir, Path image) {
        // WindowsPath compares case-insensitively
        return dir.toAbsolutePath().normalize().startsWith(image.toAbsolutePath().normalize());
    }

    /** Creates the directory if needed and proves it accepts a file. */
    static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".write-test-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** KEY=VALUE lines, '#' comments, UTF-8 with or without BOM; the same rules as start-background.ps1. */
    static Map<String, String> readDotEnv(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            for (String raw : text.split("\\R")) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 0) continue;
                String k = line.substring(0, eq).trim();
                if (!k.isEmpty()) out.put(k, line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            log("could not read .env: " + e.getClass().getSimpleName());
        }
        return out;
    }

    // ---------- single instance ----------

    /**
     * Serializes the start procedure: a second double-click waits here until the first instance is up,
     * then finds it healthy and only opens the browser. Held only while starting.
     */
    private static FileLock acquireStartLock(Path runDir) {
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        try {
            FileChannel ch = FileChannel.open(runDir.resolve("launcher.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (System.nanoTime() < deadline) {
                FileLock l = ch.tryLock();
                if (l != null) return l;
                Thread.sleep(500);
            }
            ch.close();
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            log("start lock unavailable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null; // proceed without it; the port check still prevents a second backend
    }

    private static void release(FileLock lock) {
        if (lock == null || !lock.isValid()) return;
        try {
            lock.release();
            lock.channel().close();
        } catch (IOException ignored) {
            // released when the process exits anyway
        }
    }

    /**
     * MENTALCARE when {@code /api/health} answers {@code {"status":"UP","app":"mentalcare"}}; OTHER when something
     * else holds the port (re-checked for a few seconds in case MentalCare itself is just binding); FREE otherwise.
     */
    static PortState probe(HttpClient http) {
        if (isMentalCare(health(http))) return PortState.MENTALCARE;
        if (!portOpen()) return PortState.FREE;
        for (int i = 0; i < 5; i++) {
            sleep(1000);
            if (isMentalCare(health(http))) return PortState.MENTALCARE;
        }
        return portOpen() ? PortState.OTHER : PortState.FREE;
    }

    static boolean isMentalCare(String healthBody) {
        return healthBody != null
                && Pattern.compile("\"status\"\\s*:\\s*\"UP\"").matcher(healthBody).find()
                && Pattern.compile("\"app\"\\s*:\\s*\"mentalcare\"").matcher(healthBody).find();
    }

    private static String health(HttpClient http) {
        return get(http, "/api/health");
    }

    private static String get(HttpClient http, String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL + path))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitForHealth(HttpClient http, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            if (isMentalCare(health(http))) return true;
            sleep(500);
        }
        return false;
    }

    /** The instance already running may use another data folder (e.g. the CMD start of a different copy). */
    private static void warnIfOtherDataDir(HttpClient http, Path expected) {
        String running = extractDataDir(get(http, "/api/system/status"));
        if (running == null) return;
        if (!Path.of(running).toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(expected.toAbsolutePath().normalize().toString())) {
            log("running instance uses another data dir");
            showMessage("이미 실행 중인 MentalCare가 다른 데이터 폴더를 사용하고 있습니다.\n실행 중: " + running
                    + "\n이 앱의 폴더: " + expected + "\n\n그 MentalCare를 엽니다. 이 폴더로 쓰려면 먼저 종료하세요.",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static String extractDataDir(String statusJson) {
        if (statusJson == null) return null;
        Matcher m = Pattern.compile("\"dataDir\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(statusJson);
        if (!m.find()) return null;
        return m.group(1).replace("\\\\", "\u0000").replace("\\/", "/").replace("\\\"", "\"").replace("\u0000", "\\");
    }

    // ---------- Spring Boot ----------

    /** Same code path as {@code java -jar mentalcare.jar}: Boot's JarLauncher is at the root of the fat jar. */
    private static void startSpringBoot(String[] args) throws Throwable {
        try {
            Class.forName("org.springframework.boot.loader.launch.JarLauncher")
                    .getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static void handleStartFailure(Throwable t, HttpClient http, Path runDir, boolean openBrowser) {
        log("start failed: " + t.getClass().getName());
        if (causedByPortInUse(t)) {
            // lost a race against another start (e.g. MentalCare 시작.cmd at the same moment)
            if (probe(http) == PortState.MENTALCARE) {
                if (openBrowser) browse();
                System.exit(0);
            }
            fail("포트 " + PORT + "을(를) 다른 프로그램이 사용 중입니다.\n그 프로그램을 종료한 뒤 다시 실행하세요.", 2);
            return;
        }
        fail("MentalCare를 시작하지 못했습니다.\n로그: " + runDir.resolve("mentalcare.log"), 5);
    }

    static boolean causedByPortInUse(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getName();
            if (n.endsWith("PortInUseException") || c instanceof java.net.BindException) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static Thread startWatchdog(Path runDir) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(START_WARNING.toMillis());
                log("not ready after " + START_WARNING.toSeconds() + "s");
                showMessage("MentalCare가 " + START_WARNING.toSeconds() + "초 안에 준비되지 않았습니다. 계속 기다리는 중입니다.\n"
                        + "로그: " + runDir.resolve("mentalcare.log"), JOptionPane.WARNING_MESSAGE);
            } catch (InterruptedException ignored) {
                // started (or failed) in time
            }
        }, "mentalcare-launcher-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- desktop ----------

    private static void browse() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(URL));
                return;
            }
        } catch (Exception e) {
            log("Desktop.browse failed: " + e.getClass().getSimpleName());
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", URL).start();
        } catch (IOException e) {
            showMessage("브라우저를 열지 못했습니다. 직접 여세요: " + URL, JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void fail(String message, int exitCode) {
        log("error exit=" + exitCode);
        showMessage(message, JOptionPane.ERROR_MESSAGE);
        System.exit(exitCode);
    }

    private static void showMessage(String message, int type) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        try {
            // always on top: a process started by a double-click may not get the foreground, and a dialog
            // hidden behind the browser looks like a silent failure
            javax.swing.JDialog dialog = new JOptionPane(message, type).createDialog(TITLE);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
            dialog.dispose();
        } catch (Throwable t) {
            System.err.println(TITLE + ": " + message);
        }
    }

    /** Launcher decisions only (mode, paths, exit codes); never request bodies, tokens or counseling text. */
    private static synchronized void log(String line) {
        if (launcherLog == null) return;
        try {
            if (Files.exists(launcherLog) && Files.size(launcherLog) > 256 * 1024) Files.delete(launcherLog);
            Files.writeString(launcherLog, LocalDateTime.now().withNano(0) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never stop the launch
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
