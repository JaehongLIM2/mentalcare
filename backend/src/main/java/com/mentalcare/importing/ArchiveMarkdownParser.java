package com.mentalcare.importing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a "ChatGPT 상담 이관 기록" Markdown archive (the export template the user runs in ChatGPT).
 * Pure text processing: tolerant of small header variations, never executes or renders anything.
 * The output is treated as untrusted user data downstream (it only ever enters {@code <<<DATA>>>} blocks).
 */
public final class ArchiveMarkdownParser {

    public record MemoryCandidate(String type, String evidence, String content, String whyItMatters,
                                  String timeContext, boolean mayBecomeOutdated) {}

    public record Parsed(
            String title,
            String counseledAtRaw,
            LocalDate counseledAt,
            String mainTopics,
            Map<String, List<String>> sections,   // normalized section key -> bullet items / paragraphs
            String coreContext,                    // "다음 상담 AI에게 전달할 핵심 맥락" as free text
            List<MemoryCandidate> memoryCandidates,
            List<String> warnings
    ) {}

    /** Section keys we understand, matched by keyword against the "## " heading text. */
    public static final String SITUATION = "situation", FACTS = "facts", RELATIONSHIPS = "relationships",
            EVENTS = "events", CONCERNS = "concerns", EMOTIONS = "emotions", THOUGHTS = "thoughts", VALUES = "values",
            CHANGED = "changed", DECISIONS = "decisions", UNRESOLVED = "unresolved", FOLLOW_UP = "followUp",
            OUTDATED = "outdated", AI_PATTERNS = "aiPatterns", MEMORY = "memory", CORE = "core", METADATA = "metadata";

    private static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put(METADATA, new String[]{"metadata", "메타"});
        KEYWORDS.put(SITUATION, new String[]{"당시 상황"});
        KEYWORDS.put(FACTS, new String[]{"직접 말한 사실"});
        KEYWORDS.put(RELATIONSHIPS, new String[]{"인간관계"});
        KEYWORDS.put(EVENTS, new String[]{"주요 사건"});
        KEYWORDS.put(CONCERNS, new String[]{"주요 고민"});
        KEYWORDS.put(EMOTIONS, new String[]{"당시 감정"});
        KEYWORDS.put(THOUGHTS, new String[]{"생각과 판단"});
        KEYWORDS.put(VALUES, new String[]{"가치관"});
        KEYWORDS.put(CHANGED, new String[]{"생각이 바뀐"});
        KEYWORDS.put(DECISIONS, new String[]{"결정하거나", "하기로 한"});
        KEYWORDS.put(UNRESOLVED, new String[]{"해결되지 않"});
        KEYWORDS.put(FOLLOW_UP, new String[]{"확인할 필요"});
        KEYWORDS.put(OUTDATED, new String[]{"갱신이 필요"});
        KEYWORDS.put(AI_PATTERNS, new String[]{"AI가 관찰", "가능성 있는 패턴"});
        KEYWORDS.put(MEMORY, new String[]{"Long-term Memory", "Memory 후보", "기억 후보"});
        KEYWORDS.put(CORE, new String[]{"핵심 맥락", "전달할"});
    }

    private static final int MAX_ITEMS = 40;
    private static final int MAX_ITEM_CHARS = 1000;
    private static final int MAX_CORE_CHARS = 12000;

    private ArchiveMarkdownParser() {}

    public static Parsed parse(String markdown) {
        List<String> warnings = new ArrayList<>();
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            warnings.add("빈 파일입니다.");
        }

        String title = null;
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        List<String> unknownHeadings = new ArrayList<>();
        String current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("# ") && title == null) {
                title = line.substring(2).trim();
                continue;
            }
            if (line.startsWith("## ")) {
                String heading = line.substring(3).trim();
                current = classify(heading);
                if (current == null) {
                    unknownHeadings.add(heading);
                    current = "unknown:" + heading;
                }
                bodies.computeIfAbsent(current, k -> new StringBuilder());
                continue;
            }
            if (current != null) {
                bodies.get(current).append(line).append('\n');
            }
        }
        if (title == null || !title.contains("이관")) {
            warnings.add("제목이 '# ChatGPT 상담 이관 기록' 형식이 아닙니다. 그래도 섹션 기준으로 읽습니다.");
        }
        if (!unknownHeadings.isEmpty()) {
            warnings.add("알 수 없는 섹션은 건너뜁니다: " + String.join(", ", unknownHeadings.subList(0, Math.min(5, unknownHeadings.size()))));
        }

        // metadata
        String counseledAtRaw = null, mainTopics = null;
        StringBuilder meta = bodies.get(METADATA);
        if (meta != null) {
            for (String line : meta.toString().split("\n")) {
                String l = stripBullet(line);
                if (l.startsWith("상담 시점")) counseledAtRaw = afterColon(l);
                else if (l.startsWith("주요 주제")) mainTopics = afterColon(l);
            }
        } else {
            warnings.add("Metadata 섹션이 없습니다.");
        }
        LocalDate counseledAt = parseDate(counseledAtRaw);
        if (counseledAtRaw != null && !counseledAtRaw.isBlank() && counseledAt == null) {
            warnings.add("상담 시점 '" + counseledAtRaw + "'을 날짜로 해석하지 못해 가져온 날짜를 사용합니다.");
        }

        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String key : List.of(SITUATION, FACTS, RELATIONSHIPS, EVENTS, CONCERNS, EMOTIONS, THOUGHTS, VALUES,
                CHANGED, DECISIONS, UNRESOLVED, FOLLOW_UP, OUTDATED, AI_PATTERNS)) {
            StringBuilder b = bodies.get(key);
            sections.put(key, b == null ? List.of() : items(b.toString()));
        }

        String core = bodies.containsKey(CORE) ? bodies.get(CORE).toString().strip() : "";
        if (core.length() > MAX_CORE_CHARS) {
            core = core.substring(0, MAX_CORE_CHARS);
            warnings.add("핵심 맥락이 길어 " + MAX_CORE_CHARS + "자로 잘랐습니다.");
        }
        if (core.isBlank()) {
            warnings.add("'다음 상담 AI에게 전달할 핵심 맥락' 섹션이 비어 있습니다.");
        }

        List<MemoryCandidate> candidates = bodies.containsKey(MEMORY) ? candidates(bodies.get(MEMORY).toString(), warnings) : List.of();
        if (candidates.isEmpty()) {
            warnings.add("Memory Candidate가 없습니다. 기억 후보는 생성되지 않습니다.");
        }
        return new Parsed(title, counseledAtRaw, counseledAt, mainTopics, sections, core, candidates, warnings);
    }

    static String classify(String heading) {
        String h = heading.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (h.contains(kw.toLowerCase(Locale.ROOT))) return e.getKey();
            }
        }
        return null;
    }

    /** Bullet lines become items; consecutive non-bullet lines form paragraph items. Sub-headings are kept as text. */
    static List<String> items(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(para, out);
                continue;
            }
            if (isBullet(line)) {
                flush(para, out);
                out.add(limit(stripBullet(line)));
            } else {
                if (!para.isEmpty()) para.append(' ');
                para.append(line.startsWith("#") ? line.replaceFirst("^#+\\s*", "") : line);
            }
            if (out.size() >= MAX_ITEMS) break;
        }
        flush(para, out);
        return out.size() > MAX_ITEMS ? out.subList(0, MAX_ITEMS) : out;
    }

    private static void flush(StringBuilder para, List<String> out) {
        if (!para.isEmpty()) {
            String p = para.toString().strip();
            if (!p.isEmpty() && !isBoilerplate(p)) out.add(limit(p));
            para.setLength(0);
        }
    }

    /** Template filler such as "(없음)" or the "이 항목은 AI 해석이다" notice is not content. */
    private static boolean isBoilerplate(String p) {
        return p.equals("(없음)") || p.equals("없음") || p.equals("-")
                || (p.startsWith("이 항목은") && p.contains("해석"));
    }

    static List<MemoryCandidate> candidates(String body, List<String> warnings) {
        List<MemoryCandidate> out = new ArrayList<>();
        String[] blocks = body.split("(?m)^###\\s*Memory Candidate.*$");
        for (String block : blocks) {
            Map<String, String> fields = new LinkedHashMap<>();
            String lastKey = null;
            for (String raw : block.split("\n")) {
                String line = stripBullet(raw.strip());
                Matcher m = FIELD.matcher(line);
                if (m.matches()) {
                    lastKey = m.group(1).trim().toLowerCase(Locale.ROOT);
                    fields.put(lastKey, m.group(2).trim());
                } else if (lastKey != null && !line.isEmpty() && !line.startsWith("#")) {
                    fields.put(lastKey, fields.get(lastKey) + " " + line);
                }
            }
            String content = fields.get("content");
            if (content == null || content.isBlank()) continue;
            String evidence = normalizeEvidence(fields.get("evidence"));
            if (evidence == null) {
                warnings.add("Evidence가 없는 후보는 AI_OBSERVED로 취급합니다: " + limit(content).substring(0, Math.min(30, content.length())));
                evidence = "AI_OBSERVED";
            }
            String outdated = fields.getOrDefault("may become outdated", "").toUpperCase(Locale.ROOT);
            out.add(new MemoryCandidate(
                    fields.getOrDefault("type", "FACT").toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", ""),
                    evidence, limit(content),
                    limit(fields.getOrDefault("why it matters", "")),
                    limit(fields.getOrDefault("time context", "")),
                    outdated.startsWith("YES")));
            if (out.size() >= MAX_ITEMS) break;
        }
        return out;
    }

    private static final Pattern FIELD = Pattern.compile("^([A-Za-z][A-Za-z ]{1,30}?)\\s*:\\s*(.*)$");

    private static String normalizeEvidence(String v) {
        if (v == null) return null;
        String u = v.toUpperCase(Locale.ROOT);
        if (u.contains("USER")) return "USER_STATED";
        if (u.contains("AI")) return "AI_OBSERVED";
        return null;
    }

    static LocalDate parseDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m;
        if ((m = Pattern.compile("(\\d{4})[-./년]\\s*(\\d{1,2})[-./월]\\s*(\\d{1,2})").matcher(s)).find()) {
            try { return LocalDate.of(i(m, 1), i(m, 2), i(m, 3)); } catch (RuntimeException ignored) { }
        }
        if ((m = Pattern.compile("(\\d{4})[-./년]\\s*(\\d{1,2})").matcher(s)).find()) {
            try { return YearMonth.of(i(m, 1), i(m, 2)).atDay(1); } catch (RuntimeException ignored) { }
        }
        if ((m = Pattern.compile("(\\d{4})").matcher(s)).find()) {
            try { return LocalDate.of(i(m, 1), 1, 1); } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    private static boolean isBullet(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.matches("^\\d+[.)]\\s.*");
    }

    private static String stripBullet(String line) {
        return line.replaceFirst("^(-|\\*|\\d+[.)])\\s+", "").trim();
    }

    private static String afterColon(String line) {
        int idx = line.indexOf(':');
        return idx < 0 ? "" : line.substring(idx + 1).trim();
    }

    private static String limit(String s) {
        return s.length() > MAX_ITEM_CHARS ? s.substring(0, MAX_ITEM_CHARS) : s;
    }
}
