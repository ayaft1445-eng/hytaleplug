package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextLanguageTest {

    @Test
    void russianAndEnglishByLetters() {
        assertEquals(Lang.RU, TextLanguage.detect("привет, где спавн?"));
        assertEquals(Lang.EN, TextLanguage.detect("hi, where is the spawn?"));
    }

    @Test
    void nothingToTranslate() {
        assertNull(TextLanguage.detect(""));
        assertNull(TextLanguage.detect("   "));
        assertNull(TextLanguage.detect("123 456 !!!"));
        assertNull(TextLanguage.detect(":)"));
        assertNull(TextLanguage.detect("k"));
        assertNull(TextLanguage.detect(null));
    }

    @Test
    void linksAndMentionsDoNotTurnRussianIntoEnglish() {
        assertEquals(Lang.RU, TextLanguage.detect("смотри https://example.com/some/long/path"));
        assertEquals(Lang.RU, TextLanguage.detect("@SuperLongPlayerName иди сюда"));
        assertEquals(Lang.RU, TextLanguage.detect("заходи на www.example.org"));
    }

    @Test
    void mixedTextFollowsMajority() {
        assertEquals(Lang.RU, TextLanguage.detect("го на pvp арену"));
        assertEquals(Lang.EN, TextLanguage.detect("let's go to the арена"));
    }

    @Test
    void countsCyrillicLetters() {
        assertEquals(6, TextLanguage.cyrillicLetters("привет!"));
        assertEquals(0, TextLanguage.cyrillicLetters("hello"));
    }

    @Test
    void languageFromGameLocale() {
        assertEquals(Lang.RU, Lang.fromLocale("ru-RU"));
        assertEquals(Lang.RU, Lang.fromLocale("ru"));
        assertEquals(Lang.RU, Lang.fromLocale("RU_ru"));
        assertEquals(Lang.EN, Lang.fromLocale("en-US"));
        assertEquals(Lang.EN, Lang.fromLocale("en-GB"));
        assertNull(Lang.fromLocale("de-DE"));
        assertNull(Lang.fromLocale("rus"));
        assertNull(Lang.fromLocale(null));
    }
}
