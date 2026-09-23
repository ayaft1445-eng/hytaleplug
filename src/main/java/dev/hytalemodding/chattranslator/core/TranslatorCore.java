package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Всё, что плагину нужно для перевода, без привязки к API сервера: настройки,
 * память переводов, языки игроков, переводчики и очередь доставки.
 *
 * Файлы лежат в папке данных плагина:
 * {@code config.json} — настройки и ключ DeepL,
 * {@code memory.json} — память переводов,
 * {@code players.json} — языки игроков.
 */
public final class TranslatorCore {

    private final Log log;
    private final Path configFile;
    private final Path memoryFile;
    private final Path playersFile;
    private final HttpClient http;

    private final TranslationMemory memory;
    private final PlayerLanguages players;
    private final Translator translator;
    private final OrderedDelivery delivery;

    private volatile TranslatorConfig config;
    private ScheduledExecutorService saver;

    public TranslatorCore(Path dataDirectory, Log log) {
        this(dataDirectory, log, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    TranslatorCore(Path dataDirectory, Log log, HttpClient http) {
        this.log = log;
        this.http = http;
        this.configFile = dataDirectory.resolve("config.json").toAbsolutePath();
        this.memoryFile = dataDirectory.resolve("memory.json").toAbsolutePath();
        this.playersFile = dataDirectory.resolve("players.json").toAbsolutePath();

        TranslatorConfig loaded = readConfig();
        this.config = loaded != null ? loaded : TranslatorConfig.defaults();
        this.memory = new TranslationMemory(this.config.memoryMaxPhrases());
        this.players = new PlayerLanguages(this.config.defaultLanguage());
        this.translator = new Translator(this.memory, log);
        this.delivery = new OrderedDelivery(log);

        loadMemory();
        loadPlayers();
        apply(this.config);
    }

    // ---------------------------------------------------------------- доступ

    /** Есть хотя бы один переводчик: чат переводится. */
    public boolean isActive() {
        return !this.translator.providers().isEmpty();
    }

    public TranslatorConfig config() {
        return this.config;
    }

    public PlayerLanguages players() {
        return this.players;
    }

    public Translator translator() {
        return this.translator;
    }

    public OrderedDelivery delivery() {
        return this.delivery;
    }

    public TranslationMemory memory() {
        return this.memory;
    }

    public Path configFile() {
        return this.configFile;
    }

    // ---------------------------------------------------------------- запуск и остановка

    /** Запускает периодическое сохранение, пишет в консоль, что с плагином, и проверяет переводчики. */
    public void start() {
        this.saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChatTranslator-save");
            thread.setDaemon(true);
            return thread;
        });
        this.saver.scheduleWithFixedDelay(this::savePlayers, 1, 1, TimeUnit.MINUTES);
        this.saver.scheduleWithFixedDelay(this::saveMemory, 5, 5, TimeUnit.MINUTES);

        if (isActive()) {
            this.log.info("Готов: перевод чата ru <-> en, переводчики по порядку: " + chainDescription()
                    + ". В памяти фраз: " + this.memory.size() + ", игроков в базе: " + this.players.size() + ".");
            checkServices().thenAccept(lines -> lines.forEach(this.log::info));
        } else {
            this.log.warn("Не настроен ни один переводчик, поэтому чат не переводится. Проверьте поле Translators в "
                    + this.configFile + " и напишите в консоли сервера: translator reload");
        }
    }

    /** Выполняет задачу один раз через {@code delaySeconds} секунд в фоновом потоке плагина. */
    public void later(Runnable task, long delaySeconds) {
        if (this.saver != null) {
            this.saver.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError exception) {
                    this.log.warn("фоновая проверка не удалась: " + exception);
                }
            }, delaySeconds, TimeUnit.SECONDS);
        }
    }

    /** Сохраняет всё и останавливает фоновые задачи. */
    public void stop() {
        if (this.saver != null) {
            // Без прерывания: начатое сохранение должно дописать файл до конца.
            this.saver.shutdown();
            try {
                this.saver.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        savePlayers();
        saveMemory();
        this.http.shutdown();
    }

    // ---------------------------------------------------------------- настройки

    /**
     * Перечитывает {@code config.json}. Если файл испорчен, остаются прежние настройки.
     *
     * @return строки для ответа администратору
     */
    public synchronized List<String> reload() {
        List<String> lines = new ArrayList<>();
        TranslatorConfig loaded = readConfig();
        if (loaded == null) {
            lines.add("config.json не прочитан (подробности в консоли сервера), оставлены прежние настройки.");
            return lines;
        }
        apply(loaded);
        lines.add("Настройки перечитаны из " + this.configFile);
        for (String warning : loaded.warnings()) {
            lines.add("Замечание: " + warning);
        }
        lines.add(isActive()
                ? "Переводчики по порядку: " + chainDescription() + ". Проверить их: /translator status"
                : "Не настроен ни один переводчик — чат не переводится.");
        if (loaded.translators().contains(TranslatorConfig.DEEPL) && !loaded.hasApiKey()) {
            lines.add("DeepL пропущен: в DeepLApiKey нет ключа.");
        }
        if (isActive()) {
            checkServices().thenAccept(results -> results.forEach(this.log::info));
        }
        return lines;
    }

    private void apply(TranslatorConfig next) {
        List<TranslationService> services = new ArrayList<>();
        for (String name : next.translators()) {
            if (name.equals(TranslatorConfig.DEEPL) && next.hasApiKey()) {
                services.add(new DeepLClient(next.deeplApiKey(), this.http));
            } else if (name.equals(TranslatorConfig.MYMEMORY)) {
                services.add(new MyMemoryClient(next.myMemoryEmail(), this.http));
            }
        }
        this.translator.useServices(services);
        this.memory.setMaxPhrases(next.memoryMaxPhrases());
        this.players.setDefaultLanguage(next.defaultLanguage());
        this.config = next;
    }

    private String chainDescription() {
        List<String> names = new ArrayList<>();
        for (Translator.Provider provider : this.translator.providers()) {
            names.add(provider.name());
        }
        return names.isEmpty() ? "нет" : String.join(" -> ", names);
    }

    /** Читает настройки; {@code null}, если файл испорчен или не читается (причина уже в консоли). */
    private TranslatorConfig readConfig() {
        try {
            TranslatorConfig loaded = TranslatorConfig.loadOrCreate(this.configFile);
            for (String warning : loaded.warnings()) {
                this.log.warn("config.json: " + warning);
            }
            return loaded;
        } catch (Json.JsonException exception) {
            this.log.warn("В файле " + this.configFile + " ошибка: " + exception.getMessage()
                    + ". Файл не перезаписан — исправьте его и напишите translator reload.");
        } catch (IOException exception) {
            this.log.warn("Не удалось прочитать или создать " + this.configFile + ": " + exception);
        }
        return null;
    }

    // ---------------------------------------------------------------- проверка переводчиков

    /**
     * Проверяет каждый переводчик настоящим запросом: у DeepL спрашивается остаток
     * символов (лимит не тратится), MyMemory переводит слово «hello».
     * Переводчик, который отказал надолго, сразу ставится на паузу.
     *
     * @return по строке на переводчик
     */
    public CompletableFuture<List<String>> checkServices() {
        List<Translator.Provider> chain = this.translator.providers();
        List<CompletableFuture<String>> checks = new ArrayList<>();
        for (Translator.Provider provider : chain) {
            checks.add(check(provider.service()));
        }
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0])).handle((ignored, error) -> {
            List<String> lines = new ArrayList<>();
            for (CompletableFuture<String> check : checks) {
                lines.add(check.join());
            }
            return lines;
        });
    }

    private CompletableFuture<String> check(TranslationService service) {
        CompletableFuture<String> probe;
        if (service instanceof DeepLClient) {
            probe = ((DeepLClient) service).usage().thenApply(usage -> "работает, в этом месяце израсходовано "
                    + number(usage.used()) + " из " + number(usage.limit()) + " символов");
        } else {
            probe = service.translate("hello", Lang.EN, Lang.RU).thenApply(result -> "работает (проверка: hello -> " + result + ")");
        }
        return probe.handle((result, error) -> {
            if (error == null) {
                return service.name() + ": " + result;
            }
            this.translator.reportFailure(service.name(), error);
            return service.name() + ": не работает — " + Translator.describe(Translator.unwrap(error));
        });
    }

    // ---------------------------------------------------------------- файлы данных

    private void loadMemory() {
        try {
            int count = this.memory.load(this.memoryFile);
            if (count > 0) {
                this.log.info("Память переводов: загружено фраз — " + count + ".");
            }
        } catch (Json.JsonException exception) {
            moveAside(this.memoryFile, exception.getMessage());
        } catch (IOException exception) {
            this.log.warn("Не удалось прочитать " + this.memoryFile + ": " + exception);
        }
    }

    private void loadPlayers() {
        try {
            this.players.load(this.playersFile);
        } catch (Json.JsonException exception) {
            moveAside(this.playersFile, exception.getMessage());
        } catch (IOException exception) {
            this.log.warn("Не удалось прочитать " + this.playersFile + ": " + exception);
        }
    }

    private void moveAside(Path file, String problem) {
        try {
            Path aside = DataFiles.moveAside(file);
            this.log.warn("В файле " + file + " ошибка: " + problem + ". Он переименован в "
                    + aside.getFileName() + ", плагин начнёт новый.");
        } catch (IOException exception) {
            this.log.warn("В файле " + file + " ошибка: " + problem + ", и отложить его не удалось: " + exception);
        }
    }

    /** Сохраняет языки игроков, если они менялись. */
    public void savePlayers() {
        try {
            this.players.saveIfDirty(this.playersFile);
        } catch (IOException | RuntimeException exception) {
            this.log.warn("Не удалось сохранить " + this.playersFile + ": " + exception);
        }
    }

    /** Сохраняет память переводов, если она менялась. */
    public void saveMemory() {
        try {
            this.memory.saveIfDirty(this.memoryFile);
        } catch (IOException | RuntimeException exception) {
            this.log.warn("Не удалось сохранить " + this.memoryFile + ": " + exception);
        }
    }

    // ---------------------------------------------------------------- состояние

    /** Строки для {@code /translator status}; каждый переводчик проверяется настоящим запросом. */
    public CompletableFuture<List<String>> status() {
        TranslatorConfig current = this.config;
        return checkServices().thenApply(checks -> {
            List<String> lines = new ArrayList<>();
            lines.add("ChatTranslator: перевод чата ru <-> en");
            if (isActive()) {
                lines.add("Переводчики по порядку: " + chainDescription());
            } else {
                lines.add("Не настроен ни один переводчик (поле Translators в " + this.configFile + ")");
            }
            if (current.translators().contains(TranslatorConfig.DEEPL) && !current.hasApiKey()) {
                lines.add("DeepL: пропущен, в DeepLApiKey нет ключа");
            }
            lines.addAll(checks);

            Translator.Provider active = this.translator.activeProvider();
            if (isActive()) {
                lines.add(active != null
                        ? "Сейчас переводит: " + active.name()
                        : "Сейчас все переводчики на паузе — сообщения идут без перевода");
            }
            StringBuilder counters = new StringBuilder("С запуска сервера: из памяти ")
                    .append(number(this.translator.fromMemory()));
            for (Translator.Provider provider : this.translator.providers()) {
                counters.append(", ").append(provider.name()).append(' ').append(number(provider.requests()))
                        .append(" (").append(number(provider.characters())).append(" симв.)");
            }
            counters.append(", без перевода ").append(number(this.translator.untranslated()));
            lines.add(counters.toString());
            lines.add("Память переводов: " + number(this.memory.size()) + " фраз из " + number(current.memoryMaxPhrases()));
            Map<Lang, Integer> byLanguage = this.players.countByLanguage();
            lines.add("Игроков в базе: " + number(this.players.size())
                    + " — читают по-русски " + number(byLanguage.getOrDefault(Lang.RU, 0))
                    + ", по-английски " + number(byLanguage.getOrDefault(Lang.EN, 0))
                    + ", без перевода " + number(this.players.translationOff()));
            return lines;
        });
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
