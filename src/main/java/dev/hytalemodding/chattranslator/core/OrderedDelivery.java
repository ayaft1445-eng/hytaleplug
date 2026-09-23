package dev.hytalemodding.chattranslator.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Раздаёт переведённые сообщения в том порядке, в котором их написали.
 *
 * Перевод из памяти готов сразу, а ответ DeepL приходит через доли секунды, поэтому
 * без очереди короткая фраза могла бы обогнать длинную, написанную раньше.
 * Каждое сообщение ждёт, пока будут показаны все предыдущие. Если перевод не пришёл
 * за {@link #TIMEOUT_SECONDS} секунд, сообщение уходит без перевода и очередь не стоит.
 */
public final class OrderedDelivery {

    static final long TIMEOUT_SECONDS = 10;

    private final Log log;
    private final long timeoutMillis;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    public OrderedDelivery(Log log) {
        this(log, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }

    OrderedDelivery(Log log, long timeoutMillis) {
        this.log = log;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * @param translation будущий перевод
     * @param onTranslated показать перевод получателям
     * @param onFailed перевода нет — показать получателям исходное сообщение
     */
    public synchronized <T> void enqueue(CompletableFuture<T> translation, Consumer<T> onTranslated, Consumer<Throwable> onFailed) {
        CompletableFuture<T> bounded = translation.copy().orTimeout(this.timeoutMillis, TimeUnit.MILLISECONDS);
        CompletableFuture<Void> next = this.tail.thenCompose(ignored -> bounded.handle((value, error) -> {
            try {
                if (error == null) {
                    onTranslated.accept(value);
                } else {
                    onFailed.accept(Translator.unwrap(error));
                }
            } catch (Throwable exception) {
                // Одно сломанное сообщение не должно остановить очередь.
                this.log.warn("не удалось доставить сообщение: " + exception);
            }
            return null;
        }));
        this.tail = next.exceptionally(error -> null);
    }
}
