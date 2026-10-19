package com.mentalcare.counseling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validation and normalisation of text files attached to a message. Only plain text arrives here (the
 * browser decodes the file); the server never reads paths. Limits keep one turn's context reasonable.
 */
public final class Attachments {
    private Attachments() {}

    public static final int MAX_FILES = 3;
    /** All files of one message together (≈ 100 KB of Korean text). */
    public static final int MAX_TOTAL_CHARS = 60_000;
    public static final int MAX_NAME_CHARS = 120;

    public record Input(String name, String content) {}

    /** Returns cleaned attachments or throws IllegalArgumentException with a message for the user. */
    public static List<Input> validate(List<Input> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_FILES) throw new IllegalArgumentException("파일은 한 번에 " + MAX_FILES + "개까지 첨부할 수 있습니다.");
        List<Input> out = new ArrayList<>();
        int total = 0;
        for (Input in : raw) {
            if (in == null) continue;
            String name = cleanName(in.name());
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
                throw new IllegalArgumentException("텍스트(.txt) 또는 Markdown(.md) 파일만 첨부할 수 있습니다: " + name);
            }
            String content = in.content() == null ? "" : in.content();
            if (content.startsWith("﻿")) content = content.substring(1);
            content = content.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
            if (content.isBlank()) throw new IllegalArgumentException("내용이 비어 있는 파일입니다: " + name);
            total += content.length();
            if (total > MAX_TOTAL_CHARS) {
                throw new IllegalArgumentException("첨부 파일이 너무 깁니다. 한 메시지에 모두 합쳐 "
                        + String.format("%,d", MAX_TOTAL_CHARS) + "자까지 첨부할 수 있습니다.");
            }
            out.add(new Input(name, content));
        }
        return List.copyOf(out);
    }

    /** File name only (no folders), no control characters, bounded length. */
    static String cleanName(String name) {
        String n = name == null ? "" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("\\p{Cntrl}", "").trim();
        if (n.isEmpty()) n = "첨부.txt";
        if (n.length() > MAX_NAME_CHARS) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, MAX_NAME_CHARS - ext.length()) + ext;
        }
        return n;
    }
}
