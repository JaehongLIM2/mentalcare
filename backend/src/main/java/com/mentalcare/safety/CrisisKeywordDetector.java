package com.mentalcare.safety;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Cheap secondary safety signal: a small list of Korean / Japanese / English phrases that are
 * strongly associated with self-harm or suicidal intent. Idiomatic "죽겠다" alone is deliberately
 * NOT matched. This is only a signal that triggers the structured assessment, never a verdict.
 */
@Component
public class CrisisKeywordDetector {

    private static final List<Pattern> PATTERNS = List.of(
            // Korean
            Pattern.compile("자살"),
            Pattern.compile("자해"),
            Pattern.compile("죽고\\s*싶"),
            Pattern.compile("죽어\\s*버리"),
            Pattern.compile("죽으면\\s*(편|좋)"),
            Pattern.compile("사라지고\\s*싶"),
            Pattern.compile("없어지고\\s*싶"),
            Pattern.compile("목을?\\s*매"),
            Pattern.compile("뛰어\\s*내리"),
            Pattern.compile("손목"),
            Pattern.compile("약을?\\s*(다|한꺼번에|몽땅|전부)\\s*(먹|삼)"),
            Pattern.compile("유서"),
            Pattern.compile("살기\\s*싫"),
            Pattern.compile("살고\\s*싶지\\s*않"),
            // Japanese
            Pattern.compile("自殺"),
            Pattern.compile("自傷"),
            Pattern.compile("死にたい"),
            Pattern.compile("消えたい"),
            Pattern.compile("リストカット"),
            Pattern.compile("遺書"),
            // English
            Pattern.compile("(?i)\\bsuicid"),
            Pattern.compile("(?i)\\bkill\\s+myself"),
            Pattern.compile("(?i)\\bend\\s+my\\s+life"),
            Pattern.compile("(?i)\\bself[- ]harm"),
            Pattern.compile("(?i)\\bwant\\s+to\\s+die")
    );

    public boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }
}
