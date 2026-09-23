package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorCoreTest {

    @TempDir
    Path dir;

    private final List<String> console = new ArrayList<>();

    private final Log log = new Log() {
        @Override
        public void info(String message) {
            TranslatorCoreTest.this.console.add("INFO " + message);
        }

        @Override
        public void warn(String message) {
            TranslatorCoreTest.this.console.add("WARN " + message);
        }
    };

    @Test
    void firstStartWithoutKeyExplainsWhereToPutIt() {
        TranslatorCore core = new TranslatorCore(this.dir, this.log);
        core.start();
        try {
            assertFalse(core.isActive());
            assertTrue(Files.exists(this.dir.resolve("config.json")));
            assertTrue(this.console.stream().anyMatch(line -> line.startsWith("WARN") && line.contains("DeepLApiKey")
                    && line.contains(this.dir.resolve("config.json").toAbsolutePath().toString())), this.console.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void reloadPicksUpTheKey() throws Exception {
        TranslatorCore core = new TranslatorCore(this.dir, this.log);
        try {
            Path config = this.dir.resolve("config.json");
            String text = Files.readString(config, StandardCharsets.UTF_8)
                    .replace("\"DeepLApiKey\": \"\"", "\"DeepLApiKey\": \"test-key:fx\"");
            Files.writeString(config, text, StandardCharsets.UTF_8);

            List<String> reply = core.reload();

            assertTrue(core.isActive());
            assertTrue(reply.stream().anyMatch(line -> line.contains("перевод включён")), reply.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void brokenConfigOnReloadKeepsWorkingSettings() throws Exception {
        Path config = this.dir.resolve("config.json");
        Files.writeString(config, "{\"DeepLApiKey\": \"test-key:fx\"}", StandardCharsets.UTF_8);
        TranslatorCore core = new TranslatorCore(this.dir, this.log);
        try {
            assertTrue(core.isActive());
            Files.writeString(config, "{\"DeepLApiKey\": test-key}", StandardCharsets.UTF_8);

            List<String> reply = core.reload();

            assertTrue(core.isActive(), "прежние настройки остались");
            assertTrue(reply.get(0).contains("не прочитан"), reply.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void dataSurvivesRestart() {
        UUID alex = UUID.randomUUID();
        TranslatorCore first = new TranslatorCore(this.dir, this.log);
        first.players().choose(alex, "Alex", "en-US", Lang.RU);
        first.memory().remember("where is spawn", Lang.EN, Lang.RU, "где спавн");
        first.stop();

        TranslatorCore second = new TranslatorCore(this.dir, this.log);
        try {
            assertEquals(Lang.RU, second.players().readingLanguage(alex, "Alex", "en-US"));
            assertEquals("где спавн", second.memory().lookup("Where is spawn", Lang.EN, Lang.RU));
        } finally {
            second.stop();
        }
    }

    @Test
    void brokenMemoryFileIsMovedAside() throws Exception {
        Files.writeString(this.dir.resolve("memory.json"), "{ broken", StandardCharsets.UTF_8);

        TranslatorCore core = new TranslatorCore(this.dir, this.log);
        core.stop();

        assertTrue(Files.exists(this.dir.resolve("memory.json.broken")));
        assertTrue(this.console.stream().anyMatch(line -> line.contains("memory.json.broken")), this.console.toString());
    }
}
