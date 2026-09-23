package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhrasebookTest {

    @Test
    void directionsAndSections() {
        Phrasebook phrasebook = new Phrasebook();
        List<String> problems = new ArrayList<>();
        int added = phrasebook.add(String.join("\n",
                "# комментарий",
                "как дела = how are you",
                "спс > thx",
                "пока < cya",
                "[слова]",
                "кирка = pickaxe",
                "[обращения]",
                "привет = hi",
                "[как есть]",
                "gg"), problems);

        assertEquals(List.of(), problems);
        assertEquals(6, added);
        assertEquals("how are you", phrasebook.exact("как дела", Lang.RU));
        assertEquals("как дела", phrasebook.exact("how are you", Lang.EN));
        assertEquals("thx", phrasebook.exact("спс", Lang.RU));
        assertNull(phrasebook.exact("thx", Lang.EN), "> — только с русского");
        assertEquals("пока", phrasebook.exact("cya", Lang.EN));
        assertNull(phrasebook.exact("пока", Lang.RU), "< — только с английского");

        assertEquals("pickaxe", phrasebook.word("кирка", Lang.RU));
        assertNull(phrasebook.word("как дела", Lang.RU), "фраза — не слово");
        assertEquals("hi", phrasebook.interjection("привет", Lang.RU));
        assertNull(phrasebook.interjection("как дела", Lang.RU), "обычная фраза в начале предложения не отделяется");
        assertEquals("gg", phrasebook.exact("gg", Lang.EN));
        assertEquals("gg", phrasebook.interjection("gg", Lang.EN));
    }

    @Test
    void keysIgnoreCasePunctuationAndYo() {
        assertEquals("все хорошо", Phrasebook.key("Всё хорошо!!!"));
        assertEquals("привет как дела", Phrasebook.key("Привет,  как дела? :)"));
        assertEquals("i'm new", Phrasebook.key("I’m new"));
        assertEquals("1v1 me", Phrasebook.key("1v1 me"));
    }

    @Test
    void firstLineWinsSoServerPhrasesComeFirst() {
        Phrasebook phrasebook = new Phrasebook();
        phrasebook.add("привет = hey there", null);
        phrasebook.add("[обращения]\nпривет = hi\nпривет < hello", null);

        assertEquals("hey there", phrasebook.exact("привет", Lang.RU));
        assertEquals("hey there", phrasebook.interjection("привет", Lang.RU), "обращение с переводом сервера");
        assertEquals("привет", phrasebook.exact("hello", Lang.EN));
    }

    @Test
    void badLinesAreReportedWithNumbers() {
        List<String> problems = new ArrayList<>();
        Phrasebook phrasebook = new Phrasebook();
        phrasebook.add("просто текст\n[разное]\n = hi\nнорм = fine", problems);

        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("строка 1:"), problems.toString());
        assertTrue(problems.get(1).contains("[разное]"), problems.toString());
        assertTrue(problems.get(2).startsWith("строка 3:"), problems.toString());
        assertEquals("fine", phrasebook.exact("норм", Lang.RU), "остальные строки работают");
    }

    @Test
    void heartIsNotASeparator() {
        Phrasebook phrasebook = new Phrasebook();
        phrasebook.add("люблю <3 = love <3", null);
        assertEquals("love <3", phrasebook.exact("люблю", Lang.RU), "смайл в ключ не входит");
    }

    @Test
    void builtInPhrasebookLoadsCleanly() throws Exception {
        Phrasebook phrasebook = new Phrasebook();
        List<String> problems = new ArrayList<>();
        try (var stream = Phrasebook.class.getResourceAsStream(Phrasebook.BUILT_IN)) {
            phrasebook.add(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), problems);
        }
        assertEquals(List.of(), problems);
        assertTrue(phrasebook.size() > 500, "в разговорнике " + phrasebook.size() + " фраз");
        assertEquals("hi", phrasebook.exact("привет", Lang.RU));
        assertEquals("привет", phrasebook.exact("hi", Lang.EN));
        assertEquals("как дела", phrasebook.exact("how are you", Lang.EN));
        assertEquals("подожди", phrasebook.exact("wait", Lang.EN));
        assertEquals("let's go", phrasebook.exact("го", Lang.RU));
        assertEquals("gg", phrasebook.exact("gg", Lang.EN), "понятное без перевода не переводится");
    }
}
