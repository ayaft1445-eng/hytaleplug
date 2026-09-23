package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
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
        assertEquals(Texts.Choice.TEST, Texts.Choice.parse("test"));
        assertEquals(Texts.Choice.TEST, Texts.Choice.parse("тест"));
        assertEquals(Texts.Choice.EN, Texts.Choice.parse("eng"));
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

    private static String plain(List<Styled> lines) {
        StringBuilder text = new StringBuilder();
        for (Styled line : lines) {
            text.append(line.plain()).append('\n');
        }
        return text.toString();
    }

    @Test
    void messagesAreInTheReadersLanguage() {
        assertTrue(Texts.joinHint(Lang.RU).plain().contains("/tr en"));
        assertTrue(Texts.joinHint(Lang.EN).plain().contains("/tr ru"));
        assertTrue(plain(Texts.info(Lang.RU, true)).contains("/tr off"));
        assertTrue(plain(Texts.info(Lang.EN, false)).contains("Translation is off for you"));
        assertFalse(Texts.usage(Lang.RU).plain().contains("/lang"), "встроенная /lang сервера — не наша команда");
        assertTrue(Texts.chosen(Lang.EN).plain().startsWith("[Translator]"));
        assertTrue(Texts.chosen(Lang.RU).plain().startsWith("[Переводчик]"));
    }

    @Test
    void commandsAndValuesAreHighlighted() {
        Styled hint = Texts.joinHint(Lang.RU);
        assertEquals(Styled.Style.PREFIX, hint.spans().get(0).style());
        assertTrue(hint.spans().stream().anyMatch(span -> span.style() == Styled.Style.COMMAND && span.text().equals("/tr en")));
        assertTrue(hint.spans().stream().anyMatch(span -> span.style() == Styled.Style.VALUE && span.text().equals("русский")));
        for (Styled line : Texts.info(Lang.EN, true)) {
            assertTrue(line.spans().stream().anyMatch(span -> span.style() != Styled.Style.TEXT), line.plain());
        }
    }

    @Test
    void testResultNamesWhereTheTranslationCameFrom() {
        Translation local = new Translation("hi, how are you", List.of(Translation.PHRASEBOOK));
        assertEquals("[Переводчик] привет, как дела → hi, how are you (разговорник)",
                Texts.testResult(Lang.RU, " привет, как дела ", local).plain());

        Translation mixed = new Translation("hi, I'm new here", List.of(Translation.PHRASEBOOK, "DeepL"));
        assertEquals("[Translator] привет, я тут новенький → hi, I'm new here (phrasebook + DeepL)",
                Texts.testResult(Lang.EN, "привет, я тут новенький", mixed).plain());
    }
}
