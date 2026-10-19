package com.mentalcare.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialStoreTest {

    @TempDir Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ChatGptAccount account() {
        return new ChatGptAccount(1, "oaiapp_x", "sub", "me@example.com", "Me",
                List.of("openid", "chatgpt.tokens.use.direct"), ChatGptAccount.CONNECTED,
                "ACCESS-SECRET", Instant.now().plusSeconds(3600), "REFRESH-SECRET", "ID-TOKEN", Instant.now());
    }

    @Test
    void aesStoreRoundTripsAndNeverWritesPlaintext() throws Exception {
        Path dir = tmp.resolve("creds");
        CredentialStore store = new CredentialStore(dir, mapper, new AesFileKeyCredentialProtector(dir));

        store.save(account());

        byte[] raw = Files.readAllBytes(dir.resolve("chatgpt-auth.json"));
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("ACCESS-SECRET").doesNotContain("REFRESH-SECRET").doesNotContain("ID-TOKEN")
                .doesNotContain("me@example.com");
        assertThat(store.load()).isPresent().get().satisfies(a -> {
            assertThat(a.accessToken()).isEqualTo("ACCESS-SECRET");
            assertThat(a.planUsageGranted()).isTrue();
        });
        assertThat(dir.resolve(".master.key")).exists();
        assertThat(Files.list(dir).map(p -> p.getFileName().toString()).toList()).doesNotContain(".master.key.tmp");
    }

    @Test
    void fileFromAnotherProtectorIsPreservedNotOverwritten() throws Exception {
        Path dir = tmp.resolve("creds");
        new CredentialStore(dir, mapper, new AesFileKeyCredentialProtector(dir)).save(account());
        CredentialProtector other = new CredentialProtector() {
            @Override public String id() { return "other-provider"; }
            @Override public String description() { return "other"; }
            @Override public byte[] protect(byte[] p) { return p; }
            @Override public byte[] unprotect(byte[] c) { return c; }
        };
        CredentialStore second = new CredentialStore(dir, mapper, other);

        assertThatThrownBy(second::load).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.code()).isEqualTo(AuthException.Code.STORAGE));
        assertThat(Files.readString(dir.resolve("chatgpt-auth.json"))).contains("aes-gcm-file-key");
    }

    @Test
    void hostIdIsStableAndOpaque() {
        Path dir = tmp.resolve("creds");
        CredentialStore store = new CredentialStore(dir, mapper, new AesFileKeyCredentialProtector(dir));
        String id = store.hostId();
        assertThat(id).matches("urn:uuid:[0-9a-f-]{36}");
        assertThat(new CredentialStore(dir, mapper, new AesFileKeyCredentialProtector(dir)).hostId()).isEqualTo(id);
    }

    @Test
    void credentialsLiveOutsideTheCounselingDatabase() {
        Path dir = tmp.resolve("creds");
        CredentialStore store = new CredentialStore(dir, mapper, new AesFileKeyCredentialProtector(dir));
        store.save(account());
        assertThat(dir.resolve("chatgpt-auth.json")).exists();
        assertThat(tmp.resolve("mentalcare.db")).doesNotExist();
    }
}
