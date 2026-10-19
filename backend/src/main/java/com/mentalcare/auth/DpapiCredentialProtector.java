package com.mentalcare.auth;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.nio.charset.StandardCharsets;

/**
 * Windows Data Protection API (CryptProtectData, current-user scope). Only the same Windows user on
 * the same machine can decrypt; no key file is written. Loaded only on Windows.
 */
public class DpapiCredentialProtector implements CredentialProtector {

    public static final String ID = "windows-dpapi";
    private static final byte[] ENTROPY = "mentalcare.chatgpt.credentials.v1".getBytes(StandardCharsets.UTF_8);

    /** Probes DPAPI with a round trip so a broken native setup is detected before use. */
    public static boolean available() {
        try {
            byte[] probe = "probe".getBytes(StandardCharsets.UTF_8);
            byte[] enc = Crypt32Util.cryptProtectData(probe, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "mentalcare", null);
            byte[] dec = Crypt32Util.cryptUnprotectData(enc, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
            return java.util.Arrays.equals(probe, dec);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Windows DPAPI (현재 Windows 사용자 계정에 묶여 암호화)";
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        return Crypt32Util.cryptProtectData(plaintext, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "mentalcare", null);
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) {
        return Crypt32Util.cryptUnprotectData(ciphertext, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
    }
}
