package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorCoreTest {

    @TempDir
    Path dir;

    private final List<String> console = new CopyOnWriteArrayList<>();

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

    /** Ядро, у которого любой сетевой запрос сразу упирается в закрытый порт — в интернет тесты не ходят. */
    private TranslatorCore core() {
        HttpClient offline = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)))
                .build();
        return new TranslatorCore(this.dir, this.log, offline);
    }

    private void writeConfig(String json) throws Exception {
        Files.writeString(this.dir.resolve("config.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void worksWithoutDeepLKeyThroughMyMemory() {
        TranslatorCore core = core();
        try {
            assertTrue(Files.exists(this.dir.resolve("config.json")));
            assertTrue(core.isActive(), "без ключа DeepL переводит MyMemory");
            assertEquals(1, core.translator().providers().size());
            assertEquals("MyMemory", core.translator().providers().get(0).name());
        } finally {
            core.stop();
        }
    }

    @Test
    void keyPutsDeepLFirst() throws Exception {
        writeConfig("{\"DeepLApiKey\": \"test-key:fx\"}");
        TranslatorCore core = core();
        try {
            List<Translator.Provider> providers = core.translator().providers();
            assertEquals(2, providers.size());
            assertEquals("DeepL", providers.get(0).name());
            assertEquals("MyMemory", providers.get(1).name());
        } finally {
            core.stop();
        }
    }

    @Test
    void noTranslatorsMeansInactiveWithHint() throws Exception {
        writeConfig("{\"Translators\": []}");
        TranslatorCore core = core();
        core.start();
        try {
            assertFalse(core.isActive());
            assertTrue(this.console.stream().anyMatch(line -> line.startsWith("WARN") && line.contains("Translators")),
                    this.console.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void reloadPicksUpTheKey() throws Exception {
        writeConfig("{\"Translators\": [\"deepl\"]}");
        TranslatorCore core = core();
        try {
            assertFalse(core.isActive(), "DeepL без ключа пропускается");
            writeConfig("{\"Translators\": [\"deepl\"], \"DeepLApiKey\": \"test-key:fx\"}");

            List<String> reply = core.reload();

            assertTrue(core.isActive());
            assertTrue(reply.stream().anyMatch(line -> line.contains("DeepL")), reply.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void brokenConfigOnReloadKeepsWorkingSettings() throws Exception {
        writeConfig("{\"DeepLApiKey\": \"test-key:fx\"}");
        TranslatorCore core = core();
        try {
            assertEquals(2, core.translator().providers().size());
            writeConfig("{\"DeepLApiKey\": test-key}");

            List<String> reply = core.reload();

            assertEquals(2, core.translator().providers().size(), "прежние настройки остались");
            assertTrue(reply.get(0).contains("не прочитан"), reply.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void unreachableServicesAreReportedAndPaused() throws Exception {
        writeConfig("{\"DeepLApiKey\": \"test-key:fx\"}");
        TranslatorCore core = core();
        try {
            List<String> lines = core.checkServices().join();

            assertEquals(2, lines.size());
            assertTrue(lines.get(0).startsWith("DeepL: не работает"), lines.toString());
            assertTrue(lines.get(1).startsWith("MyMemory: не работает"), lines.toString());
            assertNull(core.translator().activeProvider(), "оба недоступны — оба на паузе");

            List<String> status = core.status().join();
            assertTrue(status.stream().anyMatch(line -> line.contains("все переводчики на паузе")), status.toString());
        } finally {
            core.stop();
        }
    }

    @Test
    void dataSurvivesRestart() {
        UUID alex = UUID.randomUUID();
        TranslatorCore first = core();
        first.players().choose(alex, "Alex", "en-US", Lang.RU);
        first.memory().remember("where is spawn", Lang.EN, Lang.RU, "где спавн");
        first.stop();

        TranslatorCore second = core();
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

        TranslatorCore core = core();
        core.stop();

        assertTrue(Files.exists(this.dir.resolve("memory.json.broken")));
        assertTrue(this.console.stream().anyMatch(line -> line.contains("memory.json.broken")), this.console.toString());
    }
}
