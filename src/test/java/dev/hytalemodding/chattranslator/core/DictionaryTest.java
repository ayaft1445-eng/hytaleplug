package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверки настоящего словаря из jar: частые слова и игровые значения. */
class DictionaryTest {

    private static Dictionary dictionary;

    @BeforeAll
    static void load() throws Exception {
        dictionary = Dictionary.load();
    }

    @Test
    void isBigEnough() {
        assertTrue(dictionary.size(Lang.RU) > 50_000, "русских форм: " + dictionary.size(Lang.RU));
        assertTrue(dictionary.size(Lang.EN) > 10_000, "английских слов: " + dictionary.size(Lang.EN));
    }

    @Test
    void wordFormsAndPlurals() {
        assertEquals("sword", dictionary.lookup("меч", Lang.RU));
        assertEquals("sword", dictionary.lookup("Мечом", Lang.RU));
        assertEquals("swords", dictionary.lookup("мечи", Lang.RU));
        assertEquals("меч", dictionary.lookup("sword", Lang.EN));
        assertEquals("мечи", dictionary.lookup("Swords", Lang.EN));
        assertEquals("years", dictionary.lookup("годы", Lang.RU));
        assertEquals("годы", dictionary.lookup("years", Lang.EN));
    }

    @Test
    void gameMeaningsWin() {
        assertEquals("bow", dictionary.lookup("лук", Lang.RU));
        assertEquals("лук", dictionary.lookup("bow", Lang.EN));
        assertEquals("pickaxe", dictionary.lookup("кирку", Lang.RU));
        assertEquals("game", dictionary.lookup("игры", Lang.RU));
        assertEquals("furnace", dictionary.lookup("печь", Lang.RU));
    }

    @Test
    void ambiguousAndServiceWordsAreLeftToTheService() {
        assertNull(dictionary.lookup("стали", Lang.RU), "«сталь» или «стать»");
        assertNull(dictionary.lookup("были", Lang.RU), "форма глагола");
        assertNull(dictionary.lookup("все", Lang.RU), "«все» или «всё»");
        assertNull(dictionary.lookup("the", Lang.EN));
        assertNull(dictionary.lookup("can", Lang.EN), "не «банка»");
        assertNull(dictionary.lookup("does", Lang.EN), "не «самки»");
        assertNull(dictionary.lookup("work", Lang.EN), "чаще глагол, чем существительное");
    }

    @Test
    void yoDoesNotMatter() {
        assertEquals(dictionary.lookup("ещё", Lang.RU), dictionary.lookup("еще", Lang.RU));
    }
}
