package dev.hytalemodding.chattranslator.core;

import java.util.concurrent.CompletableFuture;

/** Сервис машинного перевода (DeepL; в тестах — подставной). */
public interface TranslationService {

    CompletableFuture<String> translate(String text, Lang from, Lang to);
}
