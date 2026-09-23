package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLanguagesTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOHN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID HANS = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TempDir
    Path dir;

    @Test
    void firstVisitTakesGameLanguage() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);

        assertTrue(players.register(ALEX, "Alex", "ru-RU"));
        assertFalse(players.register(ALEX, "Alex", "ru-RU"), "второй вход — уже знакомый игрок");
        assertTrue(players.register(JOHN, "John", "en-US"));
        assertTrue(players.register(HANS, "Hans", "de-DE"));

        assertEquals(Lang.RU, players.readingLanguage(ALEX, "Alex", "ru-RU"));
        assertEquals(Lang.EN, players.readingLanguage(JOHN, "John", "en-US"));
        assertEquals(Lang.EN, players.readingLanguage(HANS, "Hans", "de-DE"), "прочие языки — язык по умолчанию");
    }

    @Test
    void languageIsRememberedEvenIfGameLanguageChanges() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.register(ALEX, "Alex", "ru-RU");
        assertEquals(Lang.RU, players.readingLanguage(ALEX, "Alex", "en-US"));
    }

    @Test
    void learnsRussianFromChat() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.register(ALEX, "Alex", "en-US");

        assertFalse(players.learnFromMessage(ALEX, "Alex", "en-US", "hello there"));
        assertFalse(players.learnFromMessage(ALEX, "Alex", "en-US", "ok да"), "двух русских букв мало");
        assertTrue(players.learnFromMessage(ALEX, "Alex", "en-US", "привет, где спавн?"));
        assertEquals(Lang.RU, players.readingLanguage(ALEX, "Alex", "en-US"));
        assertFalse(players.learnFromMessage(ALEX, "Alex", "en-US", "ещё сообщение"), "уже русский");
    }

    @Test
    void russianPlayerWritingEnglishStaysRussian() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.register(ALEX, "Alex", "ru-RU");
        assertFalse(players.learnFromMessage(ALEX, "Alex", "ru-RU", "hello everyone, how are you?"));
        assertEquals(Lang.RU, players.readingLanguage(ALEX, "Alex", "ru-RU"));
    }

    @Test
    void chosenLanguageIsNotOverriddenByLearning() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.choose(JOHN, "John", "en-US", Lang.EN);
        assertFalse(players.learnFromMessage(JOHN, "John", "en-US", "я немного говорю по-русски"));
        assertEquals(Lang.EN, players.readingLanguage(JOHN, "John", "en-US"));
    }

    @Test
    void offAndAuto() {
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.register(ALEX, "Alex", "ru-RU");

        players.turnOff(ALEX, "Alex", "ru-RU");
        assertNull(players.readingLanguage(ALEX, "Alex", "ru-RU"));
        assertEquals(1, players.translationOff());

        PlayerLanguages.Entry entry = players.reset(ALEX, "Alex", "ru-RU");
        assertEquals(Lang.RU, entry.lang());
        assertTrue(entry.translate());
        assertEquals(PlayerLanguages.Source.GAME, entry.source());
    }

    @Test
    void savesAndLoads() throws Exception {
        Path file = this.dir.resolve("players.json");
        PlayerLanguages players = new PlayerLanguages(Lang.EN);
        players.register(ALEX, "Alex", "ru-RU");
        players.choose(JOHN, "John", "ru-RU", Lang.EN);
        players.turnOff(HANS, "Hans", "de-DE");
        players.saveIfDirty(file);

        PlayerLanguages loaded = new PlayerLanguages(Lang.RU);
        assertEquals(3, loaded.load(file));
        assertFalse(loaded.register(ALEX, "Alex", "en-US"), "после перезапуска игрок уже знаком");
        assertEquals(Lang.RU, loaded.readingLanguage(ALEX, "Alex", "en-US"));
        assertEquals(Lang.EN, loaded.readingLanguage(JOHN, "John", "ru-RU"));
        assertNull(loaded.readingLanguage(HANS, "Hans", "de-DE"));
        assertEquals(PlayerLanguages.Source.COMMAND, loaded.resolve(JOHN, "John", "ru-RU").source());
    }
}
