package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorConfigTest {

    @TempDir
    Path dir;

    @Test
    void createsFileWithEmptyKey() throws Exception {
        Path file = this.dir.resolve("sub").resolve("config.json");
        TranslatorConfig config = TranslatorConfig.loadOrCreate(file);

        assertFalse(config.hasApiKey());
        assertEquals(Lang.EN, config.defaultLanguage());
        assertTrue(config.markTranslations());
        assertFalse(config.showOriginal());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"DeepLApiKey\": \"\""), written);
        assertTrue(written.contains("\"_help\": ["), written);
    }

    @Test
    void readsKeyAndFillsMissingFields() throws Exception {
        Path file = this.dir.resolve("config.json");
        Files.writeString(file, "{ \"DeepLApiKey\": \"  abc:fx \", \"ShowOriginal\": true }", StandardCharsets.UTF_8);

        TranslatorConfig config = TranslatorConfig.loadOrCreate(file);

        assertEquals("abc:fx", config.deeplApiKey());
        assertTrue(config.showOriginal());
        Map<String, Object> rewritten = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("abc:fx", rewritten.get("DeepLApiKey"));
        assertEquals(Boolean.TRUE, rewritten.get("ShowOriginal"), "значение игрока не потерялось");
        assertEquals("en", rewritten.get("DefaultLanguage"), "недостающее поле дописано");
        assertEquals(20000L, rewritten.get("MemoryMaxPhrases"));
    }

    @Test
    void completeFileIsNotRewritten() throws Exception {
        Path file = this.dir.resolve("config.json");
        TranslatorConfig.loadOrCreate(file);
        String firstVersion = Files.readString(file, StandardCharsets.UTF_8);
        String edited = firstVersion.replace("\"DeepLApiKey\": \"\"", "\"DeepLApiKey\": \"key:fx\"");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        assertEquals("key:fx", TranslatorConfig.loadOrCreate(file).deeplApiKey());
        assertEquals(edited, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void brokenFileIsReportedAndKept() throws Exception {
        Path file = this.dir.resolve("config.json");
        String broken = "{\n  \"DeepLApiKey\": \"abc:fx\"\n  \"JoinHint\": false\n}";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        Json.JsonException error = assertThrows(Json.JsonException.class, () -> TranslatorConfig.loadOrCreate(file));
        assertTrue(error.getMessage().startsWith("строка 3") || error.getMessage().startsWith("строка 2"), error.getMessage());
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8), "испорченный файл не перезаписан");
    }

    @Test
    void translatorsDefaultToDeepLThenMyMemory() throws Exception {
        TranslatorConfig config = TranslatorConfig.loadOrCreate(this.dir.resolve("config.json"));
        assertEquals(List.of("deepl", "mymemory"), config.translators());
        assertEquals("", config.myMemoryEmail());
    }

    @Test
    void translatorsAreReadFromFile() throws Exception {
        Path file = this.dir.resolve("config.json");
        Files.writeString(file, "{\"Translators\": [\"MyMemory\", \"google\", \"mymemory\"], \"MyMemoryEmail\": \" me@example.com \"}",
                StandardCharsets.UTF_8);

        TranslatorConfig config = TranslatorConfig.loadOrCreate(file);

        assertEquals(List.of("mymemory"), config.translators());
        assertEquals("me@example.com", config.myMemoryEmail());
        assertEquals(1, config.warnings().size(), config.warnings().toString());
        assertTrue(config.warnings().get(0).contains("google"));
    }

    @Test
    void oldConfigGetsNewFields() throws Exception {
        Path file = this.dir.resolve("config.json");
        // config.json версии 1.0.0 — без Translators и MyMemoryEmail.
        Files.writeString(file, "{\"DeepLApiKey\": \"key:fx\", \"DefaultLanguage\": \"en\", \"MarkTranslations\": true,"
                + " \"ShowOriginal\": false, \"LearnLanguageFromChat\": true, \"JoinHint\": true, \"MemoryMaxPhrases\": 20000}",
                StandardCharsets.UTF_8);

        TranslatorConfig.loadOrCreate(file);

        Map<String, Object> rewritten = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("key:fx", rewritten.get("DeepLApiKey"));
        assertEquals(List.of("deepl", "mymemory"), rewritten.get("Translators"));
        assertEquals("", rewritten.get("MyMemoryEmail"));
        assertEquals(true, rewritten.get("LocalDictionary"), "перевод на сервере включается и у старых настроек");
        assertEquals(1L, rewritten.get("LocalMaxWords"));
    }

    @Test
    void localTranslationSettings() throws Exception {
        Path file = this.dir.resolve("config.json");
        TranslatorConfig defaults = TranslatorConfig.loadOrCreate(file);
        assertTrue(defaults.localDictionary());
        assertEquals(1, defaults.localMaxWords());

        Files.writeString(file, "{\"LocalDictionary\": false, \"LocalMaxWords\": 3}", StandardCharsets.UTF_8);
        TranslatorConfig custom = TranslatorConfig.loadOrCreate(file);
        assertFalse(custom.localDictionary());
        assertEquals(3, custom.localMaxWords());

        Files.writeString(file, "{\"LocalMaxWords\": 50}", StandardCharsets.UTF_8);
        TranslatorConfig clamped = TranslatorConfig.loadOrCreate(file);
        assertEquals(TranslatorConfig.MAX_LOCAL_WORDS, clamped.localMaxWords());
        assertEquals(1, clamped.warnings().size(), clamped.warnings().toString());
    }

    @Test
    void wrongValuesFallBackWithWarning() throws Exception {
        Path file = this.dir.resolve("config.json");
        Files.writeString(file, "{\"DefaultLanguage\": \"de\", \"MemoryMaxPhrases\": 5}", StandardCharsets.UTF_8);

        TranslatorConfig config = TranslatorConfig.loadOrCreate(file);

        assertEquals(Lang.EN, config.defaultLanguage());
        assertEquals(TranslatorConfig.MIN_MEMORY, config.memoryMaxPhrases());
        assertEquals(2, config.warnings().size());
    }
}
