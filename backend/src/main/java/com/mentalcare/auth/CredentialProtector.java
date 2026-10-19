package com.mentalcare.auth;

/**
 * Encrypts credential bytes at rest. The provider id is written into the stored envelope so a file
 * produced by one provider is never silently decrypted (or destroyed) by another.
 */
public interface CredentialProtector {

    /** Stable identifier of the mechanism, e.g. {@code windows-dpapi} or {@code aes-gcm-file-key}. */
    String id();

    /** Human-readable description shown in the Settings screen. */
    String description();

    byte[] protect(byte[] plaintext);

    byte[] unprotect(byte[] ciphertext);
}
