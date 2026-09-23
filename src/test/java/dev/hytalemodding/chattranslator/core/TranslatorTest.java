package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorTest {

    /** Подставной переводчик: записывает запросы и отвечает тем, что ему велели. */
    private static final class FakeService implements TranslationService {
        final String name;
        final List<String> requests = new ArrayList<>();
        final List<CompletableFuture<String>> pending = new ArrayList<>();
        boolean manual;
        Throwable failure;

        FakeService(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public CompletableFuture<String> translate(String text, Lang from, Lang to) {
            this.requests.add(from.code() + ">" + to.code() + ":" + text);
            if (this.failure != null) {
                return CompletableFuture.failedFuture(this.failure);
            }
            if (this.manual) {
                CompletableFuture<String> future = new CompletableFuture<>();
                this.pending.add(future);
                return future;
            }
            return CompletableFuture.completedFuture("[" + this.name + " " + to.code() + "] " + text);
        }
    }

    private static final class Logs implements Log {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            this.warnings.add(message);
        }
    }

    private static Translator translator(Log log, AtomicLong clock, TranslationService... services) {
        Translator translator = new Translator(new TranslationMemory(100), log, clock::get);
        translator.useServices(List.of(services));
        return translator;
    }

    @Test
    void repeatedPhraseIsTakenFromMemory() {
        FakeService deepl = new FakeService("DeepL");
        Translator translator = translator(Log.SILENT, new AtomicLong(), deepl);

        assertEquals("[DeepL en] привет всем", translator.translate("привет всем", Lang.RU, Lang.EN).join());
        assertEquals("[DeepL en] привет всем", translator.translate("Привет  всем", Lang.RU, Lang.EN).join());
        assertEquals("[DeepL en] привет всем", translator.translate("привет всем", Lang.RU, Lang.EN).join());

        assertEquals(List.of("ru>en:привет всем"), deepl.requests, "в сервис ушёл только первый запрос");
        assertEquals(2, translator.fromMemory());
        assertEquals(1, translator.providers().get(0).requests());
        assertEquals("привет всем".length(), translator.providers().get(0).characters());
    }

    @Test
    void identicalMessagesAtTheSameTimeShareOneRequest() {
        FakeService deepl = new FakeService("DeepL");
        deepl.manual = true;
        Translator translator = translator(Log.SILENT, new AtomicLong(), deepl);

        CompletableFuture<String> first = translator.translate("hello", Lang.EN, Lang.RU);
        CompletableFuture<String> second = translator.translate("Hello", Lang.EN, Lang.RU);
        assertEquals(1, deepl.requests.size());

        deepl.pending.get(0).complete("привет");
        assertEquals("привет", first.join());
        assertEquals("привет", second.join());
        assertEquals("привет", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertEquals(1, deepl.requests.size());
    }

    @Test
    void regionBlockedServiceIsSkippedForTheNextOne() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = DeepLClient.error(451);
        FakeService myMemory = new FakeService("MyMemory");
        Logs logs = new Logs();
        Translator translator = translator(logs, now, deepl, myMemory);

        assertEquals("[MyMemory ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertEquals(1, logs.warnings.size());
        assertTrue(logs.warnings.get(0).contains("451") && logs.warnings.get(0).contains("переводит MyMemory"),
                logs.warnings.toString());

        assertEquals("[MyMemory ru] where", translator.translate("where", Lang.EN, Lang.RU).join());
        assertEquals(1, deepl.requests.size(), "заблокированный DeepL больше не спрашиваем");
        assertSame(translator.providers().get(1), translator.activeProvider());
        assertNotNull(translator.pauseReason(translator.providers().get(0)));
    }

    @Test
    void temporaryErrorTriesNextServiceWithoutPause() {
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = DeepLClient.error(503);
        FakeService myMemory = new FakeService("MyMemory");
        Translator translator = translator(Log.SILENT, new AtomicLong(), deepl, myMemory);

        assertEquals("[MyMemory ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertNull(translator.pauseReason(translator.providers().get(0)), "сбой 503 — не повод отключать");
        deepl.failure = null;
        assertEquals("[DeepL ru] again", translator.translate("again", Lang.EN, Lang.RU).join());
    }

    @Test
    void unreachableServiceIsPausedForAMinute() {
        AtomicLong now = new AtomicLong(0);
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = new ConnectException("Connection refused");
        FakeService myMemory = new FakeService("MyMemory");
        Translator translator = translator(Log.SILENT, now, deepl, myMemory);

        translator.translate("one", Lang.EN, Lang.RU).join();
        translator.translate("two", Lang.EN, Lang.RU).join();
        assertEquals(1, deepl.requests.size());

        now.addAndGet(61_000);
        deepl.failure = null;
        assertEquals("[DeepL ru] three", translator.translate("three", Lang.EN, Lang.RU).join());
    }

    @Test
    void whenEveryServiceFailsTheMessageGoesUntranslated() {
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = DeepLClient.error(503);
        Logs logs = new Logs();
        Translator translator = translator(logs, new AtomicLong(), deepl);

        CompletionException error = assertThrows(CompletionException.class,
                () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertInstanceOf(ServiceException.class, error.getCause());
        assertEquals(1, translator.untranslated());

        deepl.failure = null;
        assertEquals("[DeepL ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join(), "ошибки не запоминаются");
    }

    @Test
    void pausedServicesMeanNoTranslationUntilPauseEnds() {
        AtomicLong now = new AtomicLong(0);
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = DeepLClient.error(403);
        Translator translator = translator(Log.SILENT, now, deepl);

        assertThrows(CompletionException.class, () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        CompletionException paused = assertThrows(CompletionException.class,
                () -> translator.translate("other", Lang.EN, Lang.RU).join());
        assertInstanceOf(Translator.PausedException.class, paused.getCause());
        assertNull(translator.activeProvider());

        now.addAndGet(11 * 60 * 1000);
        deepl.failure = null;
        assertEquals("[DeepL ru] other", translator.translate("other", Lang.EN, Lang.RU).join());
    }

    @Test
    void startupCheckFailurePausesServiceRightAway() {
        FakeService deepl = new FakeService("DeepL");
        FakeService myMemory = new FakeService("MyMemory");
        Translator translator = translator(Log.SILENT, new AtomicLong(), deepl, myMemory);

        translator.reportFailure("DeepL", new CompletionException(DeepLClient.error(451)));

        assertEquals("[MyMemory ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertTrue(deepl.requests.isEmpty());
    }

    @Test
    void reloadKeepsCountersAndClearsPauses() {
        FakeService deepl = new FakeService("DeepL");
        deepl.failure = DeepLClient.error(456);
        Translator translator = translator(Log.SILENT, new AtomicLong(), deepl);
        assertThrows(CompletionException.class, () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertNull(translator.activeProvider());

        FakeService fresh = new FakeService("DeepL");
        translator.useServices(List.of(fresh));

        assertEquals("[DeepL ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertEquals(2, translator.providers().get(0).requests(), "счётчик продолжился после reload");
    }

    @Test
    void memoryWorksWithoutServices() {
        TranslationMemory memory = new TranslationMemory(100);
        memory.remember("gg", Lang.EN, Lang.RU, "гг");
        Translator translator = new Translator(memory, Log.SILENT);

        assertEquals("гг", translator.translate("gg", Lang.EN, Lang.RU).join());
        CompletionException error = assertThrows(CompletionException.class,
                () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertInstanceOf(Translator.PausedException.class, error.getCause());
    }
}
