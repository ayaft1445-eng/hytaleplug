package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationMemoryTest {

    @TempDir
    Path dir;

    @Test
    void samePhraseIgnoringCaseAndSpaces() {
        TranslationMemory memory = new TranslationMemory(100);
        memory.remember("Привет всем", Lang.RU, Lang.EN, "Hi everyone");

        assertEquals("Hi everyone", memory.lookup("привет  всем", Lang.RU, Lang.EN));
        assertEquals("Hi everyone", memory.lookup("  ПРИВЕТ ВСЕМ ", Lang.RU, Lang.EN));
        assertNull(memory.lookup("привет всем", Lang.EN, Lang.RU), "другое направление — другая фраза");
        assertNull(memory.lookup("привет", Lang.RU, Lang.EN));
    }

    @Test
    void repeatedPhrasesSurviveWhenMemoryIsFull() {
        TranslationMemory memory = new TranslationMemory(100);
        memory.remember("где спавн", Lang.RU, Lang.EN, "where is spawn");
        memory.lookup("где спавн", Lang.RU, Lang.EN); // фраза повторилась

        for (int i = 0; i < 150; i++) {
            memory.remember("разовая фраза " + i, Lang.RU, Lang.EN, "one-off phrase " + i);
        }

        assertTrue(memory.size() <= 100);
        assertEquals("where is spawn", memory.lookup("где спавн", Lang.RU, Lang.EN));
        assertNull(memory.lookup("разовая фраза 0", Lang.RU, Lang.EN), "старые разовые фразы забываются первыми");
        assertNotNull(memory.lookup("разовая фраза 149", Lang.RU, Lang.EN), "свежие остаются");
    }

    @Test
    void savesAndLoadsWithCounts() throws Exception {
        Path file = this.dir.resolve("memory.json");
        TranslationMemory memory = new TranslationMemory(100);
        memory.remember("hello", Lang.EN, Lang.RU, "привет");
        memory.lookup("hello", Lang.EN, Lang.RU);
        memory.remember("gg", Lang.EN, Lang.RU, "гг");
        memory.saveIfDirty(file);

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("{\"from\": \"en\", \"to\": \"ru\", \"count\": 2, \"text\": \"hello\", \"translation\": \"привет\"}"), saved);

        TranslationMemory loaded = new TranslationMemory(100);
        assertEquals(2, loaded.load(file));
        assertEquals("привет", loaded.lookup("Hello", Lang.EN, Lang.RU));
        assertEquals("гг", loaded.lookup("GG", Lang.EN, Lang.RU));
    }

    @Test
    void handEditedFileIsUsed() throws Exception {
        Path file = this.dir.resolve("memory.json");
        Files.writeString(file, "{\n  \"entries\": [\n"
                + "    {\"from\": \"ru\", \"to\": \"en\", \"count\": 5, \"text\": \"гг\", \"translation\": \"gg\"},\n"
                + "    {\"from\": \"ru\", \"to\": \"ru\", \"text\": \"ошибка\", \"translation\": \"пропускается\"},\n"
                + "    {\"from\": \"ru\", \"to\": \"en\", \"text\": \"без счётчика\", \"translation\": \"no count\"},\n"
                + "  ]\n}\n", StandardCharsets.UTF_8);

        TranslationMemory memory = new TranslationMemory(100);
        assertEquals(2, memory.load(file));
        assertEquals("gg", memory.lookup("ГГ", Lang.RU, Lang.EN));
        assertEquals("no count", memory.lookup("без счётчика", Lang.RU, Lang.EN));
    }

    @Test
    void savesOnlyWhenChanged() throws Exception {
        Path file = this.dir.resolve("memory.json");
        TranslationMemory memory = new TranslationMemory(100);
        memory.saveIfDirty(file);
        assertFalse(Files.exists(file), "пустую память без изменений не пишем");

        memory.remember("hi", Lang.EN, Lang.RU, "привет");
        memory.saveIfDirty(file);
        assertTrue(Files.exists(file));
    }
}
