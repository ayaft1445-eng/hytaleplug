package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextsTest {

    @Test
    void langArgumentAcceptsCommonSpellings() {
        assertEquals(Texts.Choice.RU, Texts.Choice.parse("ru"));
        assertEquals(Texts.Choice.RU, Texts.Choice.parse("RU"));
        assertEquals(Texts.Choice.RU, Texts.Choice.parse("русский"));
        assertEquals(Texts.Choice.EN, Texts.Choice.parse("english"));
        assertEquals(Texts.Choice.EN, Texts.Choice.parse("англ"));
        assertEquals(Texts.Choice.AUTO, Texts.Choice.parse("auto"));
        assertEquals(Texts.Choice.OFF, Texts.Choice.parse("выкл"));
        assertEquals(Texts.Choice.OFF, Texts.Choice.parse(" off "));
        assertNull(Texts.Choice.parse("de"));
        assertNull(Texts.Choice.parse(null));
    }

    @Test
    void noteAfterTranslation() {
        TranslatorConfig defaults = TranslatorConfig.defaults();
        assertEquals("(перевод)", Texts.translationNote(defaults, Lang.RU, "hello"));
        assertEquals("(translated)", Texts.translationNote(defaults, Lang.EN, "привет"));

        TranslatorConfig showOriginal = TranslatorConfig.fromJson(Map.<String, Object>of("ShowOriginal", true));
        assertEquals("(hello there)", Texts.translationNote(showOriginal, Lang.RU, " hello there "));

        TranslatorConfig plain = TranslatorConfig.fromJson(Map.<String, Object>of("MarkTranslations", false));
        assertNull(Texts.translationNote(plain, Lang.RU, "hello"));
    }

    @Test
    void sameTextIgnoresCaseAndSpaces() {
        assertTrue(Texts.sameText("GG", "gg"));
        assertTrue(Texts.sameText("Steve  ok", "steve ok"));
        assertFalse(Texts.sameText("hello", "привет"));
    }

    @Test
    void messagesAreInTheReadersLanguage() {
        assertTrue(Texts.joinHint(Lang.RU).contains("/lang en"));
        assertTrue(Texts.joinHint(Lang.EN).contains("/lang ru"));
        assertTrue(Texts.chosen(Lang.EN).startsWith("[Translator]"));
        assertTrue(Texts.chosen(Lang.RU).startsWith("[Переводчик]"));
    }
}
