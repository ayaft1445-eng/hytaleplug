package dev.hytalemodding.chattranslator.core;

import java.util.concurrent.CompletableFuture;

/** Сервис машинного перевода (DeepL, MyMemory; в тестах — подставной). */
public interface TranslationService {

    /** Имя для консоли и {@code /translator status}. */
    String name();

    /**
     * Перевод текста. При отказе сервиса будущее завершается {@link ServiceException},
     * при сетевой ошибке — исключением сети.
     */
    CompletableFuture<String> translate(String text, Lang from, Lang to);
}
