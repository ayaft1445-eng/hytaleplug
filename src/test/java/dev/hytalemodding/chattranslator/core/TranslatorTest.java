package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorTest {

    /** Подставной сервис: записывает запросы и отвечает тем, что ему велели. */
    private static final class FakeService implements TranslationService {
        final List<String> requests = new ArrayList<>();
        final List<CompletableFuture<String>> pending = new ArrayList<>();
        boolean manual;
        RuntimeException failure;

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
            return CompletableFuture.completedFuture("[" + to.code() + "] " + text);
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

    @Test
    void repeatedPhraseIsTakenFromMemory() {
        FakeService service = new FakeService();
        Translator translator = new Translator(service, new TranslationMemory(100), Log.SILENT);

        assertEquals("[en] привет всем", translator.translate("привет всем", Lang.RU, Lang.EN).join());
        assertEquals("[en] привет всем", translator.translate("Привет  всем", Lang.RU, Lang.EN).join());
        assertEquals("[en] привет всем", translator.translate("привет всем", Lang.RU, Lang.EN).join());

        assertEquals(List.of("ru>en:привет всем"), service.requests, "в DeepL ушёл только первый запрос");
        assertEquals(2, translator.fromMemory());
        assertEquals(1, translator.fromService());
        assertEquals("привет всем".length(), translator.serviceCharacters());
    }

    @Test
    void identicalMessagesAtTheSameTimeShareOneRequest() {
        FakeService service = new FakeService();
        service.manual = true;
        Translator translator = new Translator(service, new TranslationMemory(100), Log.SILENT);

        CompletableFuture<String> first = translator.translate("hello", Lang.EN, Lang.RU);
        CompletableFuture<String> second = translator.translate("Hello", Lang.EN, Lang.RU);
        assertEquals(1, service.requests.size());

        service.pending.get(0).complete("привет");
        assertEquals("привет", first.join());
        assertEquals("привет", second.join());
        assertEquals("привет", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertEquals(1, service.requests.size());
    }

    @Test
    void failuresAreNotRemembered() {
        FakeService service = new FakeService();
        service.failure = new IllegalStateException("сеть недоступна");
        Logs logs = new Logs();
        Translator translator = new Translator(service, new TranslationMemory(100), logs);

        CompletionException error = assertThrows(CompletionException.class,
                () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertInstanceOf(IllegalStateException.class, error.getCause());

        service.failure = null;
        assertEquals("[ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
        assertEquals(2, service.requests.size());
        assertEquals(1, logs.warnings.size());
    }

    @Test
    void rejectedKeyPausesRequests() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeService service = new FakeService();
        service.failure = new DeepLClient.DeepLException(403, DeepLClient.explain(403));
        Logs logs = new Logs();
        Translator translator = new Translator(service, new TranslationMemory(100), logs, now::get);

        assertThrows(CompletionException.class, () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertNotNull(translator.pauseReason());
        assertTrue(logs.warnings.get(0).contains("приостановлен"), logs.warnings.toString());

        CompletionException paused = assertThrows(CompletionException.class,
                () -> translator.translate("another", Lang.EN, Lang.RU).join());
        assertInstanceOf(Translator.PausedException.class, paused.getCause());
        assertEquals(1, service.requests.size(), "во время паузы DeepL не дёргаем");

        now.addAndGet(11 * 60 * 1000);
        service.failure = null;
        assertNull(translator.pauseReason());
        assertEquals("[ru] another", translator.translate("another", Lang.EN, Lang.RU).join());
    }

    @Test
    void memoryStillWorksWithoutKey() {
        TranslationMemory memory = new TranslationMemory(100);
        memory.remember("gg", Lang.EN, Lang.RU, "гг");
        Translator translator = new Translator(null, memory, Log.SILENT);

        assertEquals("гг", translator.translate("gg", Lang.EN, Lang.RU).join());
        CompletionException error = assertThrows(CompletionException.class,
                () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertInstanceOf(Translator.PausedException.class, error.getCause());
    }

    @Test
    void newServiceAfterReloadClearsPause() {
        AtomicLong now = new AtomicLong(0);
        FakeService broken = new FakeService();
        broken.failure = new DeepLClient.DeepLException(456, DeepLClient.explain(456));
        Translator translator = new Translator(broken, new TranslationMemory(100), Log.SILENT, now::get);
        assertThrows(CompletionException.class, () -> translator.translate("hello", Lang.EN, Lang.RU).join());
        assertNotNull(translator.pauseReason());

        translator.useService(new FakeService());
        assertNull(translator.pauseReason());
        assertEquals("[ru] hello", translator.translate("hello", Lang.EN, Lang.RU).join());
    }
}
