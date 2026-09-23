package dev.hytalemodding.chattranslator.core;

import java.util.Locale;

/** Языки, между которыми переводит плагин. */
public enum Lang {

    RU("ru", "RU", "RU"),
    EN("en", "EN", "EN-US");

    private final String code;
    private final String deeplSource;
    private final String deeplTarget;

    Lang(String code, String deeplSource, String deeplTarget) {
        this.code = code;
        this.deeplSource = deeplSource;
        this.deeplTarget = deeplTarget;
    }

    /** Короткий код для файлов и команд: {@code ru} или {@code en}. */
    public String code() {
        return this.code;
    }

    /** Код исходного языка в запросе к DeepL. */
    public String deeplSource() {
        return this.deeplSource;
    }

    /** Код языка перевода в запросе к DeepL (для английского DeepL требует вариант). */
    public String deeplTarget() {
        return this.deeplTarget;
    }

    /** {@code ru} / {@code en} в любом регистре; иначе {@code null}. */
    public static Lang fromCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Lang lang : values()) {
            if (lang.code.equals(normalized)) {
                return lang;
            }
        }
        return null;
    }

    /**
     * Язык игры, который клиент присылает при подключении: {@code ru-RU}, {@code en_US}, {@code ru}.
     * Для остальных языков возвращает {@code null}.
     */
    public static Lang fromLocale(String locale) {
        if (locale == null) {
            return null;
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        for (Lang lang : values()) {
            if (normalized.equals(lang.code)
                    || normalized.startsWith(lang.code + "-")
                    || normalized.startsWith(lang.code + "_")) {
                return lang;
            }
        }
        return null;
    }
}
