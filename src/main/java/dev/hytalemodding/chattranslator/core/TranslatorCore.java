package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.nio.file.Path;
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
 * память переводов, языки игроков, DeepL и очередь доставки.
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

    private final TranslationMemory memory;
    private final PlayerLanguages players;
    private final Translator translator;
    private final OrderedDelivery delivery;

    private volatile TranslatorConfig config;
    private volatile DeepLClient client;
    private ScheduledExecutorService saver;

    public TranslatorCore(Path dataDirectory, Log log) {
        this.log = log;
        this.configFile = dataDirectory.resolve("config.json").toAbsolutePath();
        this.memoryFile = dataDirectory.resolve("memory.json").toAbsolutePath();
        this.playersFile = dataDirectory.resolve("players.json").toAbsolutePath();

        TranslatorConfig loaded = readConfig();
        this.config = loaded != null ? loaded : TranslatorConfig.defaults();
        this.memory = new TranslationMemory(this.config.memoryMaxPhrases());
        this.players = new PlayerLanguages(this.config.defaultLanguage());
        this.translator = new Translator(null, this.memory, log);
        this.delivery = new OrderedDelivery(log);

        loadMemory();
        loadPlayers();
        apply(this.config);
    }

    // ---------------------------------------------------------------- доступ

    /** Ключ DeepL вписан: чат переводится. */
    public boolean isActive() {
        return this.config.hasApiKey();
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

    /** Запускает периодическое сохранение файлов и пишет в консоль, что с плагином. */
    public void start() {
        this.saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChatTranslator-save");
            thread.setDaemon(true);
            return thread;
        });
        this.saver.scheduleWithFixedDelay(this::savePlayers, 1, 1, TimeUnit.MINUTES);
        this.saver.scheduleWithFixedDelay(this::saveMemory, 5, 5, TimeUnit.MINUTES);

        if (isActive()) {
            this.log.info("Готов: перевод чата ru <-> en через DeepL ("
                    + (DeepLClient.isFreeKey(this.config.deeplApiKey()) ? "бесплатный тариф" : "платный тариф")
                    + "). В памяти фраз: " + this.memory.size() + ", игроков в базе: " + this.players.size() + ".");
        } else {
            this.log.warn("Ключ DeepL не задан, поэтому чат пока не переводится. Откройте файл "
                    + this.configFile + ", впишите ключ в поле DeepLApiKey и напишите в консоли сервера: translator reload");
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
        DeepLClient current = this.client;
        if (current != null) {
            current.shutdown();
        }
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
        lines.add(loaded.hasApiKey()
                ? "Ключ DeepL задан, перевод включён. Проверить ключ: /translator status"
                : "Ключ DeepL не задан — чат не переводится.");
        return lines;
    }

    private void apply(TranslatorConfig next) {
        DeepLClient previous = this.client;
        DeepLClient created = next.hasApiKey() ? new DeepLClient(next.deeplApiKey()) : null;
        this.client = created;
        this.translator.useService(created);
        this.memory.setMaxPhrases(next.memoryMaxPhrases());
        this.players.setDefaultLanguage(next.defaultLanguage());
        this.config = next;
        if (previous != null) {
            previous.shutdown();
        }
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

    /** Строки для {@code /translator status}; остаток символов DeepL спрашивается у сервиса. */
    public CompletableFuture<List<String>> status() {
        TranslatorConfig current = this.config;
        DeepLClient currentClient = this.client;
        List<String> lines = new ArrayList<>();
        lines.add("ChatTranslator: перевод чата ru <-> en");
        if (!current.hasApiKey()) {
            lines.add("Ключ DeepL: НЕ задан. Впишите его в " + this.configFile + " (поле DeepLApiKey) и напишите /translator reload");
        } else {
            lines.add("Ключ DeepL: задан, " + (DeepLClient.isFreeKey(current.deeplApiKey())
                    ? "бесплатный тариф (api-free.deepl.com)" : "платный тариф (api.deepl.com)"));
        }
        String pause = this.translator.pauseReason();
        if (pause != null) {
            lines.add("Сейчас перевод приостановлен: " + pause);
        }
        lines.add("С запуска сервера: из памяти " + number(this.translator.fromMemory())
                + ", через DeepL " + number(this.translator.fromService())
                + " (" + number(this.translator.serviceCharacters()) + " симв.), ошибок " + number(this.translator.failures()));
        lines.add("Память переводов: " + number(this.memory.size()) + " фраз из " + number(current.memoryMaxPhrases()));
        Map<Lang, Integer> byLanguage = this.players.countByLanguage();
        lines.add("Игроков в базе: " + number(this.players.size())
                + " — читают по-русски " + number(byLanguage.getOrDefault(Lang.RU, 0))
                + ", по-английски " + number(byLanguage.getOrDefault(Lang.EN, 0))
                + ", без перевода " + number(this.players.translationOff()));

        if (currentClient == null) {
            return CompletableFuture.completedFuture(lines);
        }
        return currentClient.usage().handle((usage, error) -> {
            if (error == null) {
                lines.add(2, "DeepL: ключ работает, в этом месяце израсходовано "
                        + number(usage.used()) + " из " + number(usage.limit()) + " символов");
            } else {
                lines.add(2, "DeepL: проверка ключа не удалась — "
                        + Translator.describe(Translator.unwrap(error)));
            }
            return lines;
        });
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
