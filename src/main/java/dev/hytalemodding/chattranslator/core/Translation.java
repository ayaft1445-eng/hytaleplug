package dev.hytalemodding.chattranslator.core;

import java.util.List;

/** Готовый перевод сообщения и откуда он взят. */
public final class Translation {

    /** Источник «разговорник». */
    public static final String PHRASEBOOK = "phrasebook";
    /** Источник «словарь». */
    public static final String DICTIONARY = "dictionary";
    /** Источник «память переводов». */
    public static final String MEMORY = Translator.MEMORY;

    private final String text;
    private final List<String> sources;

    Translation(String text, List<String> sources) {
        this.text = text;
        this.sources = List.copyOf(sources);
    }

    public String text() {
        return this.text;
    }

    /**
     * Откуда перевод, по порядку: {@link #PHRASEBOOK}, {@link #DICTIONARY}, {@link #MEMORY}
     * или имя переводчика («DeepL», «MyMemory»). Пусто, если переводить было нечего.
     */
    public List<String> sources() {
        return this.sources;
    }

    /** Переведено без обращения к сервису. */
    public boolean isLocal() {
        for (String source : this.sources) {
            if (!source.equals(PHRASEBOOK) && !source.equals(DICTIONARY) && !source.equals(MEMORY)) {
                return false;
            }
        }
        return true;
    }
}
