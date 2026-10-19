package com.mentalcare.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Fallback for platforms without an OS credential API (Linux containers, macOS without Keychain
 * bindings): AES-256-GCM with a random key kept in an owner-only file next to the credentials.
 * This separates credentials from the counseling database and protects against casual reads and
 * backups of the DB, but NOT against an attacker who can read the key file. Documented in SECURITY.md.
 */
public class AesFileKeyCredentialProtector implements CredentialProtector {

    public static final String ID = "aes-gcm-file-key";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public AesFileKeyCredentialProtector(Path credentialsDir) {
        this.keyFile = credentialsDir.resolve(".master.key");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "AES-256-GCM (키 파일 " + keyFile.getFileName() + ", 소유자만 읽기)";
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException | IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Encryption failed", e);
        }
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) {
        try {
            if (ciphertext.length < IV_BYTES + 16) throw new AuthException(AuthException.Code.STORAGE, "Ciphertext too short");
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(ciphertext, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (GeneralSecurityException | IOException e) {
            throw new AuthException(AuthException.Code.STORAGE, "Decryption failed", e);
        }
    }

    private synchronized byte[] loadOrCreateKey() throws IOException {
        if (Files.exists(keyFile)) {
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length != 32) throw new IOException("master key has unexpected length");
            return key;
        }
        byte[] key = new byte[32];
        random.nextBytes(key);
        Files.createDirectories(keyFile.getParent());
        Path tmp = keyFile.resolveSibling(".master.key.tmp");
        Files.write(tmp, key);
        FilePermissions.ownerOnly(tmp);
        Files.move(tmp, keyFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        FilePermissions.ownerOnly(keyFile);
        return key;
    }

    /** POSIX permission helper shared with the credential store. No-op on Windows (ACLs inherit from the user dir). */
    static final class FilePermissions {
        private FilePermissions() {}

        static void ownerOnly(Path path) {
            try {
                if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                            Files.isDirectory(path) ? "rwx------" : "rw-------"));
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // best effort
            }
        }
    }
}
