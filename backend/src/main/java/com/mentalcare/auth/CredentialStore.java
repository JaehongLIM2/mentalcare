package com.mentalcare.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalcare.auth.AesFileKeyCredentialProtector.FilePermissions;
import com.mentalcare.config.AppProperties;
import com.sun.jna.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Files under {@code mentalcare.credentials-dir} (separate from the counseling database):
 * <ul>
 *   <li>{@code chatgpt-host.json}: non-secret stable {@code ext_agent_host_id} (urn:uuid)</li>
 *   <li>{@code chatgpt-auth.json}: {"version":1,"provider":"...","ciphertext":"base64"} – encrypted {@link ChatGptAccount}</li>
 * </ul>
 * Writes are atomic (temp file + move) and owner-only where the file system supports it.
 */
@Component
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);
    private static final String HOST_FILE = "chatgpt-host.json";
    private static final String AUTH_FILE = "chatgpt-auth.json";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private final Path dir;
    private final ObjectMapper mapper;
    private final CredentialProtector protector;

    @org.springframework.beans.factory.annotation.Autowired
    public CredentialStore(AppProperties app, ObjectMapper mapper) {
        this(Path.of(app.credentialsDirOrDefault()), mapper, selectProtector(Path.of(app.credentialsDirOrDefault())));
    }

    public CredentialStore(Path dir, ObjectMapper mapper, CredentialProtector protector) {
        this.dir = dir;
        this.mapper = mapper;
        this.protector = protector;
        try {
            Files.createDirectories(dir);
            FilePermissions.ownerOnly(dir);
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Cannot create credentials directory", e);
        }
        log.info("credential store dir={} protector={}", dir.toAbsolutePath().normalize(), protector.id());
    }

    static CredentialProtector selectProtector(Path dir) {
        if (Platform.isWindows() && DpapiCredentialProtector.available()) {
            return new DpapiCredentialProtector();
        }
        return new AesFileKeyCredentialProtector(dir);
    }

    public CredentialProtector protector() {
        return protector;
    }

    public Path directory() {
        return dir;
    }

    // ------------------------------------------------------------------ host id

    /** Returns the stable host id, creating {@code urn:uuid:<v4>} on first use. Not a secret. */
    public synchronized String hostId() {
        Path file = dir.resolve(HOST_FILE);
        try {
            if (Files.exists(file)) {
                JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                String id = node.path("id").asText("");
                if (id.startsWith("urn:uuid:")) return id;
            }
            String id = "urn:uuid:" + UUID.randomUUID();
            ObjectNode node = mapper.createObjectNode();
            node.put("version", 1);
            node.put("id", id);
            writeAtomic(file, mapper.writeValueAsBytes(node));
            return id;
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Cannot read/write host id", e);
        }
    }

    // ------------------------------------------------------------------ account

    public synchronized Optional<ChatGptAccount> load() {
        Path file = dir.resolve(AUTH_FILE);
        if (!Files.exists(file)) return Optional.empty();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new AuthException(AuthException.Code.STORAGE, "Credential file too large");
            }
            JsonNode envelope = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String provider = envelope.path("provider").asText("");
            if (!protector.id().equals(provider)) {
                // Do not destroy the file: it may belong to another protector (e.g. moved between OSes).
                throw new AuthException(AuthException.Code.STORAGE,
                        "Credential file was written by provider '" + provider + "', current is '" + protector.id() + "'");
            }
            byte[] ciphertext = Base64.getDecoder().decode(envelope.path("ciphertext").asText(""));
            byte[] plaintext = protector.unprotect(ciphertext);
            return Optional.of(mapper.readValue(plaintext, ChatGptAccount.class));
        } catch (AuthException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Credential file unreadable", e);
        }
    }

    /** Provider id recorded in the existing credential file, if one exists. */
    public synchronized Optional<String> existingProvider() {
        Path file = dir.resolve(AUTH_FILE);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.ofNullable(mapper.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("provider").asText(null));
        } catch (IOException e) {
            return Optional.of("unreadable");
        }
    }

    public synchronized void save(ChatGptAccount account) {
        existingProvider().filter(p -> !protector.id().equals(p)).ifPresent(p -> {
            throw new AuthException(AuthException.Code.STORAGE,
                    "Credential file was written by provider '" + p + "'; refusing to overwrite with '" + protector.id()
                            + "'. Delete the file or use the original environment.");
        });
        try {
            byte[] plaintext = mapper.writeValueAsBytes(account);
            byte[] ciphertext = protector.protect(plaintext);
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("version", 1);
            envelope.put("provider", protector.id());
            envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            writeAtomic(dir.resolve(AUTH_FILE), mapper.writeValueAsBytes(envelope));
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Cannot write credential file", e);
        }
    }

    /**
     * Writes credential files taken from a backup (names from the fixed allow-list only, shapes checked,
     * contents never logged). A file written by another protector stays unreadable until the user signs in
     * again; {@link #load()} reports that instead of guessing.
     */
    public synchronized void restoreFiles(java.util.Map<String, byte[]> files) {
        try {
            for (var e : files.entrySet()) {
                String name = e.getKey();
                byte[] bytes = e.getValue();
                if (bytes == null || bytes.length > MAX_FILE_BYTES) {
                    throw new AuthException(AuthException.Code.STORAGE, "Credential file in backup has an unexpected size");
                }
                switch (name) {
                    case AUTH_FILE -> {
                        JsonNode n = mapper.readTree(bytes);
                        if (!n.path("provider").isTextual() || !n.path("ciphertext").isTextual()) {
                            throw new AuthException(AuthException.Code.STORAGE, "Credential file in backup has an unexpected shape");
                        }
                    }
                    case HOST_FILE -> {
                        if (!mapper.readTree(bytes).path("id").asText("").startsWith("urn:uuid:")) {
                            throw new AuthException(AuthException.Code.STORAGE, "Host id file in backup has an unexpected shape");
                        }
                    }
                    case ".master.key" -> {
                        if (bytes.length > 4096) throw new AuthException(AuthException.Code.STORAGE, "Key file in backup has an unexpected size");
                    }
                    default -> throw new AuthException(AuthException.Code.STORAGE, "Unexpected credential file name in backup");
                }
            }
            for (var e : files.entrySet()) writeAtomic(dir.resolve(e.getKey()), e.getValue());
            log.info("credential files restored from backup count={}", files.size());
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Cannot restore credential files", e);
        }
    }

    public synchronized void delete() {
        try {
            Files.deleteIfExists(dir.resolve(AUTH_FILE));
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Cannot delete credential file", e);
        }
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, bytes);
            FilePermissions.ownerOnly(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            FilePermissions.ownerOnly(target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
