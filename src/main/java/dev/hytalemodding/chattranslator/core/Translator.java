package dev.hytalemodding.chattranslator.core;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Переводит фразу: сначала ищет её в памяти переводов, и только если там нет —
 * спрашивает DeepL и запоминает ответ.
 *
 * Если DeepL не принял ключ или закончился месячный лимит, запросы на время
 * приостанавливаются, чтобы не засыпать консоль ошибками; сообщения в это время
 * уходят без перевода.
 */
public final class Translator {

    private static final long KEY_REJECTED_PAUSE = TimeUnit.MINUTES.toMillis(10);
    private static final long QUOTA_PAUSE = TimeUnit.HOURS.toMillis(1);
    private static final long TOO_MANY_REQUESTS_PAUSE = TimeUnit.SECONDS.toMillis(10);
    private static final long WARNING_INTERVAL = TimeUnit.MINUTES.toMillis(1);

    private volatile TranslationService service;
    private final TranslationMemory memory;
    private final Log log;
    private final LongSupplier clock;

    /** Одинаковые фразы, отправленные одновременно, ждут один и тот же ответ DeepL. */
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    private volatile long pausedUntil;
    private volatile String pauseReason = "";
    private long lastWarningAt = Long.MIN_VALUE / 2;

    private final AtomicLong fromMemory = new AtomicLong();
    private final AtomicLong fromService = new AtomicLong();
    private final AtomicLong serviceCharacters = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public Translator(TranslationService service, TranslationMemory memory, Log log) {
        this(service, memory, log, System::currentTimeMillis);
    }

    Translator(TranslationService service, TranslationMemory memory, Log log, LongSupplier clock) {
        this.service = service;
        this.memory = memory;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Меняет сервис после {@code /translator reload}; счётчики сохраняются, пауза снимается.
     * {@code null} — ключа нет, переводить нечем.
     */
    public void useService(TranslationService service) {
        this.service = service;
        this.pausedUntil = 0;
    }

    /** Переводы сейчас приостановлены (с объяснением причины). */
    public static final class PausedException extends RuntimeException {
        PausedException(String reason) {
            super(reason);
        }
    }

    public CompletableFuture<String> translate(String text, Lang from, Lang to) {
        String remembered = this.memory.lookup(text, from, to);
        if (remembered != null) {
            this.fromMemory.incrementAndGet();
            return CompletableFuture.completedFuture(remembered);
        }
        TranslationService current = this.service;
        if (current == null) {
            return CompletableFuture.failedFuture(new PausedException("ключ DeepL не задан"));
        }
        if (this.clock.getAsLong() < this.pausedUntil) {
            return CompletableFuture.failedFuture(new PausedException(this.pauseReason));
        }

        String key = from.code() + '>' + to.code() + '|' + TranslationMemory.normalize(text);
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> already = this.inFlight.putIfAbsent(key, result);
        if (already != null) {
            return already.copy();
        }

        this.fromService.incrementAndGet();
        this.serviceCharacters.addAndGet(text.length());
        CompletableFuture<String> request;
        try {
            request = current.translate(text, from, to);
        } catch (RuntimeException exception) {
            request = CompletableFuture.failedFuture(exception);
        }
        request.whenComplete((translation, error) -> {
            this.inFlight.remove(key, result);
            if (error == null && translation != null && !translation.isBlank()) {
                this.memory.remember(text, from, to, translation);
                result.complete(translation);
                return;
            }
            Throwable cause = error != null ? unwrap(error) : new IllegalStateException("DeepL вернул пустой перевод");
            onFailure(cause);
            result.completeExceptionally(cause);
        });
        return result.copy();
    }

    private void onFailure(Throwable cause) {
        this.failures.incrementAndGet();
        if (cause instanceof DeepLClient.DeepLException) {
            int status = ((DeepLClient.DeepLException) cause).status();
            if (status == 401 || status == 403) {
                pause(KEY_REJECTED_PAUSE, cause.getMessage());
                return;
            }
            if (status == 456) {
                pause(QUOTA_PAUSE, cause.getMessage());
                return;
            }
            if (status == 429) {
                pause(TOO_MANY_REQUESTS_PAUSE, cause.getMessage());
                return;
            }
        }
        warnOccasionally("перевод не удался, сообщение отправлено без перевода: " + describe(cause));
    }

    private void pause(long millis, String reason) {
        long now = this.clock.getAsLong();
        boolean alreadyPaused = now < this.pausedUntil;
        this.pauseReason = reason;
        this.pausedUntil = now + millis;
        if (!alreadyPaused) {
            this.log.warn(reason + ". Перевод приостановлен на " + formatDuration(millis)
                    + ", сообщения пока идут без перевода.");
        }
    }

    private synchronized void warnOccasionally(String message) {
        long now = this.clock.getAsLong();
        if (now - this.lastWarningAt >= WARNING_INTERVAL) {
            this.lastWarningAt = now;
            this.log.warn(message);
        }
    }

    static String describe(Throwable cause) {
        if (cause instanceof DeepLClient.DeepLException || cause instanceof PausedException) {
            return cause.getMessage();
        }
        if (cause instanceof HttpTimeoutException) {
            return "DeepL не ответил вовремя";
        }
        if (cause instanceof ConnectException) {
            return "не удалось подключиться к DeepL (нет интернета или адрес заблокирован)";
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String formatDuration(long millis) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        if (seconds < 60) {
            return seconds + " с";
        }
        return TimeUnit.SECONDS.toMinutes(seconds) + " мин";
    }

    // ---------------------------------------------------------------- статистика

    /** Сколько переводов взято из памяти с запуска сервера. */
    public long fromMemory() {
        return this.fromMemory.get();
    }

    /** Сколько запросов ушло в DeepL с запуска сервера. */
    public long fromService() {
        return this.fromService.get();
    }

    /** Сколько символов отправлено в DeepL с запуска сервера. */
    public long serviceCharacters() {
        return this.serviceCharacters.get();
    }

    public long failures() {
        return this.failures.get();
    }

    /** Причина паузы или {@code null}, если перевод работает. */
    public String pauseReason() {
        return this.clock.getAsLong() < this.pausedUntil ? this.pauseReason : null;
    }
}
