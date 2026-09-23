package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Переводит фразу: сначала ищет её в памяти переводов, а если там нет — спрашивает
 * переводчики по очереди (по умолчанию DeepL, затем MyMemory) и запоминает ответ.
 *
 * Переводчик, который отказал надолго (ключ не подошёл, закончился лимит, сервис не
 * работает в стране сервера), на время пропускается, и работу берёт следующий.
 * Если не справился никто, сообщение уходит без перевода.
 */
public final class Translator {

    private static final long REGION_BLOCKED_PAUSE = TimeUnit.HOURS.toMillis(6);
    private static final long KEY_REJECTED_PAUSE = TimeUnit.MINUTES.toMillis(10);
    private static final long QUOTA_PAUSE = TimeUnit.HOURS.toMillis(1);
    private static final long TOO_MANY_REQUESTS_PAUSE = TimeUnit.SECONDS.toMillis(10);
    private static final long UNREACHABLE_PAUSE = TimeUnit.MINUTES.toMillis(1);
    private static final long WARNING_INTERVAL = TimeUnit.MINUTES.toMillis(1);

    /** Переводчик из очереди: счётчики и пауза. */
    public static final class Provider {

        private final TranslationService service;
        private final AtomicLong requests;
        private final AtomicLong characters;
        private final AtomicLong failures;
        private volatile long pausedUntil;
        private volatile String pauseReason = "";

        private Provider(TranslationService service, Provider previous) {
            this.service = service;
            // После /translator reload счётчики того же переводчика продолжаются.
            this.requests = previous != null ? previous.requests : new AtomicLong();
            this.characters = previous != null ? previous.characters : new AtomicLong();
            this.failures = previous != null ? previous.failures : new AtomicLong();
        }

        public String name() {
            return this.service.name();
        }

        public TranslationService service() {
            return this.service;
        }

        /** Сколько запросов ушло в этот переводчик с запуска сервера. */
        public long requests() {
            return this.requests.get();
        }

        /** Сколько символов отправлено в этот переводчик с запуска сервера. */
        public long characters() {
            return this.characters.get();
        }

        public long failures() {
            return this.failures.get();
        }

        boolean isPaused(long now) {
            return now < this.pausedUntil;
        }
    }

    private final TranslationMemory memory;
    private final Log log;
    private final LongSupplier clock;

    private volatile List<Provider> providers = List.of();

    /** Одинаковые фразы, отправленные одновременно, ждут один и тот же ответ. */
    private final Map<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    private long lastWarningAt = Long.MIN_VALUE / 2;

    private final AtomicLong fromMemory = new AtomicLong();
    private final AtomicLong untranslated = new AtomicLong();

    public Translator(TranslationMemory memory, Log log) {
        this(memory, log, System::currentTimeMillis);
    }

    Translator(TranslationMemory memory, Log log, LongSupplier clock) {
        this.memory = memory;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Задаёт очередь переводчиков (при запуске и после {@code /translator reload}).
     * Паузы снимаются, счётчики переводчиков с тем же именем сохраняются.
     */
    public void useServices(List<? extends TranslationService> services) {
        List<Provider> previous = this.providers;
        List<Provider> next = new ArrayList<>(services.size());
        for (TranslationService service : services) {
            Provider old = null;
            for (Provider candidate : previous) {
                if (candidate.name().equals(service.name())) {
                    old = candidate;
                }
            }
            next.add(new Provider(service, old));
        }
        this.providers = List.copyOf(next);
    }

    /** Очередь переводчиков в порядке опроса. */
    public List<Provider> providers() {
        return this.providers;
    }

    /** Переводы сейчас невозможны: переводчиков нет или все на паузе. */
    public static final class PausedException extends RuntimeException {
        PausedException(String reason) {
            super(reason);
        }
    }

    /** Перевод и откуда он: имя переводчика или {@link #MEMORY}. */
    public static final class Result {

        private final String text;
        private final String source;

        Result(String text, String source) {
            this.text = text;
            this.source = source;
        }

        public String text() {
            return this.text;
        }

        /** Имя переводчика («DeepL», «MyMemory») или {@link #MEMORY}. */
        public String source() {
            return this.source;
        }
    }

    /** Источник перевода «память переводов». */
    public static final String MEMORY = "memory";

    public CompletableFuture<String> translate(String text, Lang from, Lang to) {
        return this.translateDetailed(text, from, to).thenApply(Result::text);
    }

    /** Как {@link #translate}, но ещё и сообщает, кто перевёл. */
    public CompletableFuture<Result> translateDetailed(String text, Lang from, Lang to) {
        String remembered = this.memory.lookup(text, from, to);
        if (remembered != null) {
            this.fromMemory.incrementAndGet();
            return CompletableFuture.completedFuture(new Result(remembered, MEMORY));
        }
        List<Provider> chain = this.providers;
        if (chain.isEmpty()) {
            this.untranslated.incrementAndGet();
            return CompletableFuture.failedFuture(new PausedException("не настроен ни один переводчик"));
        }

        String key = from.code() + '>' + to.code() + '|' + TranslationMemory.normalize(text);
        CompletableFuture<Result> result = new CompletableFuture<>();
        CompletableFuture<Result> already = this.inFlight.putIfAbsent(key, result);
        if (already != null) {
            return already.copy();
        }
        this.attempt(chain, 0, text, from, to, key, result, null);
        return result.copy();
    }

    /** Отдаёт фразу первому переводчику из очереди, начиная с {@code index}, который не на паузе. */
    private void attempt(List<Provider> chain, int index, String text, Lang from, Lang to,
                         String key, CompletableFuture<Result> result, Throwable lastError) {
        long now = this.clock.getAsLong();
        int position = index;
        while (position < chain.size() && chain.get(position).isPaused(now)) {
            position++;
        }
        if (position >= chain.size()) {
            this.inFlight.remove(key, result);
            this.untranslated.incrementAndGet();
            result.completeExceptionally(lastError != null ? lastError : new PausedException(pausedSummary(chain, now)));
            return;
        }

        Provider provider = chain.get(position);
        provider.requests.incrementAndGet();
        provider.characters.addAndGet(text.length());
        CompletableFuture<String> request;
        try {
            request = provider.service.translate(text, from, to);
        } catch (RuntimeException exception) {
            request = CompletableFuture.failedFuture(exception);
        }
        int next = position + 1;
        request.whenComplete((translation, error) -> {
            if (error == null && translation != null && !translation.isBlank()) {
                this.inFlight.remove(key, result);
                this.memory.remember(text, from, to, translation);
                result.complete(new Result(translation, provider.name()));
                return;
            }
            Throwable cause = error != null ? unwrap(error)
                    : new ServiceException(ServiceException.Kind.OTHER, 200, "пустой перевод");
            provider.failures.incrementAndGet();
            this.onFailure(provider, cause, chain, next);
            this.attempt(chain, next, text, from, to, key, result, cause);
        });
    }

    /**
     * Сообщает об отказе переводчика, замеченном вне перевода (например, при проверке
     * ключа на старте), чтобы сразу поставить его на паузу.
     */
    public void reportFailure(String providerName, Throwable error) {
        List<Provider> chain = this.providers;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).name().equals(providerName)) {
                this.onFailure(chain.get(i), unwrap(error), chain, i + 1);
                return;
            }
        }
    }

    private void onFailure(Provider provider, Throwable cause, List<Provider> chain, int nextIndex) {
        long pause = pauseFor(cause);
        String described = describe(cause);
        String reason = described.contains(provider.name()) ? described : provider.name() + ": " + described;
        if (pause <= 0) {
            this.warnOccasionally(reason);
            return;
        }
        long now = this.clock.getAsLong();
        boolean alreadyPaused = provider.isPaused(now);
        provider.pauseReason = described;
        provider.pausedUntil = now + pause;
        if (!alreadyPaused) {
            String replacement = null;
            for (int i = nextIndex; i < chain.size() && replacement == null; i++) {
                if (!chain.get(i).isPaused(now)) {
                    replacement = chain.get(i).name();
                }
            }
            this.log.warn(reason + ". " + provider.name() + " отключён на " + formatDuration(pause)
                    + (replacement != null ? ", переводит " + replacement + "." : ", сообщения пока идут без перевода."));
        }
    }

    static long pauseFor(Throwable cause) {
        if (cause instanceof ServiceException) {
            switch (((ServiceException) cause).kind()) {
                case REGION_BLOCKED:
                    return REGION_BLOCKED_PAUSE;
                case KEY_REJECTED:
                    return KEY_REJECTED_PAUSE;
                case QUOTA:
                    return QUOTA_PAUSE;
                case TOO_MANY_REQUESTS:
                    return TOO_MANY_REQUESTS_PAUSE;
                default:
                    return 0;
            }
        }
        if (cause instanceof IOException) {
            // Нет связи с сервисом: ждать тайм-аут на каждом сообщении незачем.
            return UNREACHABLE_PAUSE;
        }
        return 0;
    }

    private String pausedSummary(List<Provider> chain, long now) {
        StringBuilder summary = new StringBuilder("все переводчики на паузе");
        for (Provider provider : chain) {
            if (provider.isPaused(now)) {
                summary.append("; ").append(provider.name()).append(": ").append(provider.pauseReason);
            }
        }
        return summary.toString();
    }

    private synchronized void warnOccasionally(String message) {
        long now = this.clock.getAsLong();
        if (now - this.lastWarningAt >= WARNING_INTERVAL) {
            this.lastWarningAt = now;
            this.log.warn(message);
        }
    }

    public static String describe(Throwable cause) {
        if (cause instanceof ServiceException || cause instanceof PausedException) {
            return cause.getMessage();
        }
        if (cause instanceof HttpTimeoutException) {
            return "сервис не ответил вовремя";
        }
        if (cause instanceof ConnectException) {
            return "не удалось подключиться (нет интернета или адрес заблокирован)";
        }
        if (cause instanceof IOException) {
            return "ошибка сети" + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    public static Throwable unwrap(Throwable error) {
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
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        if (minutes < 60) {
            return minutes + " мин";
        }
        return TimeUnit.MINUTES.toHours(minutes) + " ч";
    }

    // ---------------------------------------------------------------- состояние

    /** Причина паузы переводчика или {@code null}, если он работает. */
    public String pauseReason(Provider provider) {
        return provider.isPaused(this.clock.getAsLong()) ? provider.pauseReason : null;
    }

    /** Кто переводит прямо сейчас: первый переводчик в очереди не на паузе, или {@code null}. */
    public Provider activeProvider() {
        long now = this.clock.getAsLong();
        for (Provider provider : this.providers) {
            if (!provider.isPaused(now)) {
                return provider;
            }
        }
        return null;
    }

    /** Сколько переводов взято из памяти с запуска сервера. */
    public long fromMemory() {
        return this.fromMemory.get();
    }

    /** Сколько сообщений не удалось перевести (ушли как есть) с запуска сервера. */
    public long untranslated() {
        return this.untranslated.get();
    }
}
