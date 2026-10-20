package com.mentalcare.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure decisions of the app-image launcher: where data goes, .env parsing, health and port-conflict recognition. */
class MentalCareLauncherTest {

    @TempDir Path tmp;

    private Path repo() throws IOException {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("backend"));
        Files.createDirectories(repo.resolve("scripts"));
        Files.writeString(repo.resolve("backend/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("scripts/start-background.ps1"), "#");
        return repo;
    }

    @Test
    void imageInsideCheckoutSharesTheRepositoryDataAndRunDirectories() throws IOException {
        Path repo = repo();
        Path image = repo.resolve("dist/MentalCare");
        Files.createDirectories(image);

        MentalCareLauncher.Layout l = MentalCareLauncher.resolveLayout(image, tmp.resolve("local").toString());

        assertThat(l.mode()).isEqualTo("repository");
        assertThat(l.dataDir()).isEqualTo(repo.resolve("data").toAbsolutePath().normalize());
        assertThat(l.runDir()).isEqualTo(repo.resolve(".run").toAbsolutePath().normalize());
        assertThat(MentalCareLauncher.insideImage(l.dataDir(), image)).isFalse();
    }

    @Test
    void portableCopyKeepsDataNextToTheImageNeverInsideIt() {
        Path image = tmp.resolve("usb/MentalCare");

        MentalCareLauncher.Layout l = MentalCareLauncher.resolveLayout(image, tmp.resolve("local").toString());

        assertThat(l.mode()).isEqualTo("portable");
        assertThat(l.dataDir()).isEqualTo(tmp.resolve("usb/MentalCare-data/data").toAbsolutePath().normalize());
        assertThat(MentalCareLauncher.insideImage(l.dataDir(), image)).isFalse();
        assertThat(MentalCareLauncher.insideImage(image.resolve("app/data"), image)).isTrue();
    }

    @Test
    void existingLocalAppDataIsReusedBeforeCreatingAPortableFolder() throws IOException {
        Path local = tmp.resolve("local");
        Files.createDirectories(local.resolve("MentalCare/data"));

        MentalCareLauncher.Layout l = MentalCareLauncher.resolveLayout(tmp.resolve("usb/MentalCare"), local.toString());

        assertThat(l.mode()).isEqualTo("localappdata");
        assertThat(l.dataDir()).isEqualTo(local.resolve("MentalCare/data").toAbsolutePath().normalize());
    }

    @Test
    void existingPortableFolderWinsOverLocalAppData() throws IOException {
        Files.createDirectories(tmp.resolve("usb/MentalCare-data/data"));
        Files.createDirectories(tmp.resolve("local/MentalCare/data"));

        MentalCareLauncher.Layout l = MentalCareLauncher.resolveLayout(tmp.resolve("usb/MentalCare"),
                tmp.resolve("local").toString());

        assertThat(l.mode()).isEqualTo("portable");
    }

    @Test
    void dotEnvValueWinsOverTheEnvironmentLikeTheStartScript() {
        assertThat(MentalCareLauncher.dataDirOverride("C:/env", "C:/dotenv")).contains(Path.of("C:/dotenv"));
        assertThat(MentalCareLauncher.dataDirOverride("C:/env", null)).contains(Path.of("C:/env"));
        assertThat(MentalCareLauncher.dataDirOverride(" ", "")).isEmpty();
    }

    @Test
    void dotEnvParsingMatchesStartBackgroundPs1() throws IOException {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "\uFEFF# comment\r\nOPENAI_AUTH_MODE = chatgpt\r\n\r\nnoequals\r\nURL=a=b\n=x\n",
                StandardCharsets.UTF_8);

        Map<String, String> m = MentalCareLauncher.readDotEnv(env);

        assertThat(m).containsExactly(Map.entry("OPENAI_AUTH_MODE", "chatgpt"), Map.entry("URL", "a=b"));
        assertThat(MentalCareLauncher.readDotEnv(tmp.resolve("missing"))).isEmpty();
    }

    @Test
    void onlyMentalCaresHealthAnswerCountsAsRunning() {
        assertThat(MentalCareLauncher.isMentalCare("{\"app\":\"mentalcare\",\"status\":\"UP\"}")).isTrue();
        assertThat(MentalCareLauncher.isMentalCare("{\"status\":\"UP\"}")).isFalse(); // e.g. another Spring app
        assertThat(MentalCareLauncher.isMentalCare("<html>hello</html>")).isFalse();
        assertThat(MentalCareLauncher.isMentalCare(null)).isFalse();
    }

    @Test
    void runningDataDirIsReadFromTheStatusJson() {
        String json = "{\"status\":\"UP\",\"dataDir\":\"C:\\\\Users\\\\me\\\\mentalcare\\\\data\",\"busySessions\":0}";
        assertThat(MentalCareLauncher.extractDataDir(json)).isEqualTo("C:\\Users\\me\\mentalcare\\data");
        assertThat(MentalCareLauncher.extractDataDir("{}")).isNull();
    }

    @Test
    void portInUseIsRecognisedThroughWrappedCauses() {
        assertThat(MentalCareLauncher.causedByPortInUse(new IllegalStateException("x", new BindException("in use")))).isTrue();
        assertThat(MentalCareLauncher.causedByPortInUse(new IllegalStateException("x"))).isFalse();
    }
}
