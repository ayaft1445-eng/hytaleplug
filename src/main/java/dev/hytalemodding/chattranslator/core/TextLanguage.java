package dev.hytalemodding.chattranslator.core;

import java.util.Locale;

/**
 * Определяет язык сообщения по буквам: кириллица — русский, латиница — английский.
 * Ссылки и упоминания через @ не учитываются, чтобы ссылка в русской фразе
 * не превращала её в «английскую».
 */
public final class TextLanguage {

    /** Меньше букв — переводить нечего («?», «k», смайлы, числа). */
    private static final int MIN_LETTERS = 2;

    private TextLanguage() {
    }

    /** Язык сообщения или {@code null}, если переводить нечего. */
    public static Lang detect(String text) {
        Counts counts = count(text);
        if (counts.cyrillic + counts.latin < MIN_LETTERS) {
            return null;
        }
        return counts.cyrillic >= counts.latin ? Lang.RU : Lang.EN;
    }

    /** Сколько кириллических букв в сообщении (без ссылок и упоминаний). */
    public static int cyrillicLetters(String text) {
        return count(text).cyrillic;
    }

    private static Counts count(String text) {
        Counts counts = new Counts();
        if (text == null) {
            return counts;
        }
        for (String word : text.split("\\s+")) {
            if (isSkipped(word)) {
                continue;
            }
            word.codePoints().forEach(codePoint -> {
                if (!Character.isLetter(codePoint)) {
                    return;
                }
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.CYRILLIC) {
                    counts.cyrillic++;
                } else if (script == Character.UnicodeScript.LATIN) {
                    counts.latin++;
                }
            });
        }
        return counts;
    }

    private static boolean isSkipped(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.contains("://") || lower.startsWith("www.") || lower.startsWith("@");
    }

    private static final class Counts {
        int cyrillic;
        int latin;
    }
}
