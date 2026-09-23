package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTranslatorTest {

    /** Подставной сервис: записывает, что ему прислали, и отвечает «EN(текст)». */
    private static final class FakeService implements TranslationService {
        final List<String> requests = new ArrayList<>();
        boolean broken;

        @Override
        public String name() {
            return "DeepL";
        }

        @Override
        public CompletableFuture<String> translate(String text, Lang from, Lang to) {
            this.requests.add(text);
            if (this.broken) {
                return CompletableFuture.failedFuture(new ServiceException(ServiceException.Kind.OTHER, 500, "сломался"));
            }
            return CompletableFuture.completedFuture(to.code().toUpperCase() + "(" + text + ")");
        }
    }

    private final FakeService service = new FakeService();
    private final TranslationMemory memory = new TranslationMemory(100);
    private final MessageTranslator messages;

    MessageTranslatorTest() {
        Translator translator = new Translator(this.memory, Log.SILENT);
        translator.useServices(List.of(this.service));
        this.messages = new MessageTranslator(translator, this.memory);
        Phrasebook phrasebook = new Phrasebook();
        phrasebook.add("[обращения]\nпривет = hi\n[фразы]\nкак дела = how are you", null);
        this.messages.usePhrasebook(phrasebook);
        this.messages.useDictionary(Dictionary.of(Map.of("меч", "sword"), Map.of("sword", "меч")));
    }

    private Translation translate(String text) {
        return this.messages.translate(text, Lang.RU, Lang.EN).join();
    }

    @Test
    void localPhrasesNeedNoService() {
        Translation translation = translate("Привет! Как дела?");

        assertEquals("Hi! How are you?", translation.text());
        assertEquals(List.of(Translation.PHRASEBOOK), translation.sources());
        assertTrue(translation.isLocal());
        assertEquals(List.of(), this.service.requests);
        assertEquals(1, this.messages.localMessages());
        assertEquals(2, this.messages.phrasebookHits());
    }

    @Test
    void wordsComeFromTheDictionary() {
        Translation translation = translate("меч?");

        assertEquals("sword?", translation.text());
        assertEquals(List.of(Translation.DICTIONARY), translation.sources());
        assertEquals(List.of(), this.service.requests);
    }

    @Test
    void onlyTheHardPartGoesToTheService() {
        Translation translation = translate("привет, я новенький!");

        assertEquals("hi, EN(я новенький!)", translation.text());
        assertEquals(List.of("я новенький!"), this.service.requests);
        assertEquals(List.of(Translation.PHRASEBOOK, "DeepL"), translation.sources());
        assertFalse(translation.isLocal());
        assertEquals(1, this.messages.partlyLocalMessages());

        // Второй раз эта часть берётся из памяти.
        assertEquals("Hi, EN(я новенький!)", translate("Привет, я новенький!").text());
        assertEquals(1, this.service.requests.size());
    }

    @Test
    void wholeMessagesRememberedBeforeTheUpdateAreStillUsed() {
        this.memory.remember("привет, я новенький!", Lang.RU, Lang.EN, "hello, I'm new!");

        Translation translation = translate("привет, я новенький!");

        assertEquals("hello, I'm new!", translation.text());
        assertEquals(List.of(Translation.MEMORY), translation.sources());
        assertEquals(List.of(), this.service.requests);
    }

    @Test
    void failedServiceFailsTheWholeMessage() {
        this.service.broken = true;

        CompletableFuture<Translation> result = this.messages.translate("привет, я новенький", Lang.RU, Lang.EN);

        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void localTranslationCanBeSwitchedOff() {
        this.messages.configure(false, 1);

        Translation translation = translate("привет");

        assertEquals("EN(привет)", translation.text());
        assertEquals(List.of("привет"), this.service.requests);
    }
}
