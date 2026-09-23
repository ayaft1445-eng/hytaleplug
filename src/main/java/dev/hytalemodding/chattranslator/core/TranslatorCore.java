package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * память переводов, языки игроков, разговорник, словарь, переводчики и очередь доставки.
 *
 * Файлы лежат в папке данных плагина:
 * {@code config.json} — настройки и ключ DeepL,
 * {@code phrases.txt} — свои переводы фраз и слов,
 * {@code memory.json} — память переводов,
 * {@code players.json} — языки игроков.
 */
public final class TranslatorCore {

    private static final String PHRASES_TEMPLATE = String.join("\n",
            "# Свои переводы фраз и слов для ChatTranslator. Они важнее встроенного разговорника",
            "# и словаря, а сервис перевода для них не нужен.",
            "#",
            "# Одна строка — одна фраза:",
            "#   русский = english    перевод в обе стороны",
            "#   русский > english    только с русского на английский",
            "#   русский < english    только с английского на русский",
            "# Регистр букв, «ё» и знаки препинания не важны: строка «как дела = how are you»",
            "# сработает и для «Как дела?», и для «КАК ДЕЛА».",
            "#",
            "# Фраза переводится так, когда сообщение (или предложение в нём) целиком из неё состоит.",
            "# Строки после заголовка [слова] — отдельные слова: они заменяют перевод из словаря",
            "# и внутри предложений, которые переводятся по словам (LocalMaxWords в config.json).",
            "# Строки с # в начале не действуют. Файл сохраняйте в кодировке UTF-8.",
            "# После правки напишите /translator reload (или перезапустите сервер).",
            "#",
            "# Примеры — уберите # в начале строки, чтобы включить:",
            "# го на арену = let's go to the arena",
            "# где магазин = where is the shop",
            "# [слова]",
            "# арена = arena",
            "# магазин = shop",
            "");

    private final Log log;
    private final Path configFile;
    private final Path phrasesFile;
    private final Path memoryFile;
    private final Path playersFile;
    private final HttpClient http;

    private final TranslationMemory memory;
    private final PlayerLanguages players;
    private final Translator translator;
    private final MessageTranslator messages;
    private final OrderedDelivery delivery;

    private volatile TranslatorConfig config;
    private volatile int serverPhrases;
    private volatile boolean dictionaryRequested;
    private ScheduledExecutorService saver;

    public TranslatorCore(Path dataDirectory, Log log) {
        this(dataDirectory, log, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    TranslatorCore(Path dataDirectory, Log log, HttpClient http) {
        this.log = log;
        this.http = http;
        this.configFile = dataDirectory.resolve("config.json").toAbsolutePath();
        this.phrasesFile = dataDirectory.resolve("phrases.txt").toAbsolutePath();
        this.memoryFile = dataDirectory.resolve("memory.json").toAbsolutePath();
        this.playersFile = dataDirectory.resolve("players.json").toAbsolutePath();

        TranslatorConfig loaded = readConfig();
        this.config = loaded != null ? loaded : TranslatorConfig.defaults();
        this.memory = new TranslationMemory(this.config.memoryMaxPhrases());
        this.players = new PlayerLanguages(this.config.defaultLanguage());
        this.translator = new Translator(this.memory, log);
        this.messages = new MessageTranslator(this.translator, this.memory);
        this.delivery = new OrderedDelivery(log);

        loadMemory();
        loadPlayers();
        loadPhrasebook(null);
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

    /** Перевод сообщений целиком: разговорник, словарь, память, сервис. */
    public MessageTranslator messages() {
        return this.messages;
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

    /** Запускает периодическое сохранение, загрузку словаря и проверку переводчиков. */
    public void start() {
        this.saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ChatTranslator-save");
            thread.setDaemon(true);
            return thread;
        });
        this.saver.scheduleWithFixedDelay(this::savePlayers, 1, 1, TimeUnit.MINUTES);
        this.saver.scheduleWithFixedDelay(this::saveMemory, 5, 5, TimeUnit.MINUTES);
        if (this.config.localDictionary()) {
            // Словарь грузится в фоне, чтобы не задерживать запуск сервера; до этого
            // работают разговорник, память и сервисы.
            this.dictionaryRequested = true;
            this.saver.execute(this::loadDictionary);
        }

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

    /** Загружает словарь из jar (в фоновом потоке при запуске). */
    void loadDictionary() {
        try {
            long started = System.nanoTime();
            Dictionary dictionary = Dictionary.load();
            this.messages.useDictionary(dictionary);
            this.log.info("Словарь загружен: " + number(dictionary.size(Lang.RU)) + " русских и "
                    + number(dictionary.size(Lang.EN)) + " английских слов за "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " мс.");
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            this.log.warn("Словарь не загружен (" + exception + "), слова переводит сервис.");
        }
    }

    // ---------------------------------------------------------------- настройки

    /**
     * Перечитывает {@code config.json} и {@code phrases.txt}. Если config.json испорчен,
     * остаются прежние настройки.
     *
     * @return строки для ответа администратору
     */
    public synchronized List<Styled> reload() {
        List<Styled> lines = new ArrayList<>();
        TranslatorConfig loaded = readConfig();
        if (loaded == null) {
            lines.add(new Styled().bad("config.json не прочитан").text(" (подробности в консоли сервера), оставлены прежние настройки."));
        } else {
            apply(loaded);
            lines.add(new Styled().good("Настройки перечитаны").muted(" из " + this.configFile));
            for (String warning : loaded.warnings()) {
                lines.add(new Styled().bad("Замечание: ").text(warning));
            }
        }
        List<String> problems = new ArrayList<>();
        loadPhrasebook(problems);
        lines.add(new Styled().text("Свои фразы из phrases.txt: ").value(number(this.serverPhrases)));
        for (String problem : problems) {
            lines.add(new Styled().bad("phrases.txt, " + problem));
        }
        TranslatorConfig current = this.config;
        if (current.localDictionary() && !this.dictionaryRequested && this.saver != null) {
            this.dictionaryRequested = true;
            this.saver.execute(this::loadDictionary);
        }
        lines.add(isActive()
                ? new Styled().text("Переводчики по порядку: ").value(chainDescription()).muted(". Проверить их: ").command("/translator status")
                : new Styled().bad("Не настроен ни один переводчик — чат не переводится."));
        if (current.translators().contains(TranslatorConfig.DEEPL) && !current.hasApiKey()) {
            lines.add(new Styled().muted("DeepL пропущен: в DeepLApiKey нет ключа."));
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
        this.messages.configure(next.localDictionary(), next.localMaxWords());
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

    /**
     * Собирает разговорник: сначала phrases.txt сервера (создаётся с примерами, если его нет),
     * потом встроенный. Ошибки в строках пишутся в консоль и в {@code problems}.
     */
    private void loadPhrasebook(List<String> problems) {
        Phrasebook phrasebook = new Phrasebook();
        List<String> found = new ArrayList<>();
        int own = 0;
        try {
            if (!Files.exists(this.phrasesFile)) {
                DataFiles.writeAtomically(this.phrasesFile, PHRASES_TEMPLATE);
            }
            own = phrasebook.add(readText(this.phrasesFile, found), found);
        } catch (IOException exception) {
            found.add("не прочитан: " + exception);
        }
        try {
            phrasebook.addBuiltIn();
        } catch (IOException exception) {
            this.log.warn("Встроенный разговорник не загружен: " + exception);
        }
        for (String problem : found) {
            this.log.warn("phrases.txt, " + problem);
        }
        if (problems != null) {
            problems.addAll(found);
        }
        this.serverPhrases = own;
        this.messages.usePhrasebook(phrasebook);
    }

    /** Текст файла в UTF-8; файл в кодировке Windows-1251 (старый «Блокнот») тоже читается. */
    private static String readText(Path file, List<String> problems) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            try {
                String text = new String(bytes, Charset.forName("windows-1251"));
                problems.add("файл не в UTF-8, прочитан как Windows-1251 — лучше пересохраните его в UTF-8");
                return text;
            } catch (IllegalArgumentException noSuchCharset) {
                problems.add("файл не в UTF-8 — пересохраните его в UTF-8");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    // ---------------------------------------------------------------- проверка переводчиков

    /** Результат проверки одного переводчика. */
    private static final class Check {

        final String name;
        final boolean works;
        final String details;

        Check(String name, boolean works, String details) {
            this.name = name;
            this.works = works;
            this.details = details;
        }

        String line() {
            return this.name + ": " + (this.works ? "работает" : "не работает — ") + this.details;
        }

        Styled styled() {
            Styled line = new Styled().text(this.name + ": ");
            return this.works ? line.good("работает").muted(this.details) : line.bad("не работает").muted(" — " + this.details);
        }
    }

    /**
     * Проверяет каждый переводчик настоящим запросом: у DeepL спрашивается остаток
     * символов (лимит не тратится), MyMemory переводит слово «hello».
     * Переводчик, который отказал надолго, сразу ставится на паузу.
     *
     * @return по строке на переводчик
     */
    public CompletableFuture<List<String>> checkServices() {
        return checks().thenApply(checks -> {
            List<String> lines = new ArrayList<>();
            for (Check check : checks) {
                lines.add(check.line());
            }
            return lines;
        });
    }

    private CompletableFuture<List<Check>> checks() {
        List<Translator.Provider> chain = this.translator.providers();
        List<CompletableFuture<Check>> checks = new ArrayList<>();
        for (Translator.Provider provider : chain) {
            checks.add(check(provider.service()));
        }
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0])).handle((ignored, error) -> {
            List<Check> results = new ArrayList<>();
            for (CompletableFuture<Check> check : checks) {
                results.add(check.join());
            }
            return results;
        });
    }

    private CompletableFuture<Check> check(TranslationService service) {
        CompletableFuture<String> probe;
        if (service instanceof DeepLClient) {
            probe = ((DeepLClient) service).usage().thenApply(usage -> ", в этом месяце израсходовано "
                    + number(usage.used()) + " из " + number(usage.limit()) + " символов");
        } else {
            probe = service.translate("hello", Lang.EN, Lang.RU).thenApply(result -> " (проверка: hello -> " + result + ")");
        }
        return probe.handle((details, error) -> {
            if (error == null) {
                return new Check(service.name(), true, details);
            }
            this.translator.reportFailure(service.name(), error);
            return new Check(service.name(), false, Translator.describe(Translator.unwrap(error)));
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
    public CompletableFuture<List<Styled>> status() {
        TranslatorConfig current = this.config;
        return checks().thenApply(checks -> {
            List<Styled> lines = new ArrayList<>();
            lines.add(new Styled().prefix("ChatTranslator").text(": перевод чата ru <-> en"));
            if (isActive()) {
                lines.add(new Styled().text("Переводчики по порядку: ").value(chainDescription()));
            } else {
                lines.add(new Styled().bad("Не настроен ни один переводчик").muted(" (поле Translators в " + this.configFile + ")"));
            }
            if (current.translators().contains(TranslatorConfig.DEEPL) && !current.hasApiKey()) {
                lines.add(new Styled().text("DeepL: ").muted("пропущен, в DeepLApiKey нет ключа"));
            }
            for (Check check : checks) {
                lines.add(check.styled());
            }

            Translator.Provider active = this.translator.activeProvider();
            if (isActive()) {
                lines.add(active != null
                        ? new Styled().text("Сейчас переводит: ").value(active.name())
                        : new Styled().bad("Сейчас все переводчики на паузе").text(" — сообщения идут без перевода"));
            }
            lines.add(localStatus(current));

            Styled counters = new Styled().text("С запуска сервера: из памяти ").value(number(this.translator.fromMemory()));
            for (Translator.Provider provider : this.translator.providers()) {
                counters.text(", " + provider.name() + " ").value(number(provider.requests()))
                        .muted(" (" + number(provider.characters()) + " симв.)");
            }
            counters.text(", без перевода ").value(number(this.translator.untranslated()));
            lines.add(counters);
            lines.add(new Styled().text("Без сервиса переведено сообщений: ").value(number(this.messages.localMessages()))
                    .text(" целиком и ").value(number(this.messages.partlyLocalMessages())).text(" частично")
                    .muted(" (фраз из разговорника " + number(this.messages.phrasebookHits())
                            + ", слов из словаря " + number(this.messages.dictionaryHits()) + ")"));
            lines.add(new Styled().text("Память переводов: ").value(number(this.memory.size()))
                    .muted(" фраз из " + number(current.memoryMaxPhrases())));
            Map<Lang, Integer> byLanguage = this.players.countByLanguage();
            lines.add(new Styled().text("Игроков в базе: ").value(number(this.players.size()))
                    .muted(" — читают по-русски " + number(byLanguage.getOrDefault(Lang.RU, 0))
                            + ", по-английски " + number(byLanguage.getOrDefault(Lang.EN, 0))
                            + ", без перевода " + number(this.players.translationOff())));
            return lines;
        });
    }

    private Styled localStatus(TranslatorConfig current) {
        if (!current.localDictionary()) {
            return new Styled().text("Перевод на сервере (разговорник и словарь): ").bad("выключен")
                    .muted(" — LocalDictionary в config.json");
        }
        Dictionary dictionary = this.messages.dictionary();
        String words = dictionary.isEmpty()
                ? "словарь загружается"
                : "словарь " + number(dictionary.size(Lang.RU) + dictionary.size(Lang.EN)) + " слов";
        String limit = current.localMaxWords() == 0
                ? "по словарю не переводится"
                : "по словарю до " + current.localMaxWords() + " "
                + (current.localMaxWords() == 1 ? "слова" : "слов") + " подряд";
        return new Styled().text("Перевод на сервере: ").good("включён")
                .muted(" — разговорник " + number(this.messages.phrasebook().size()) + " фраз (своих в phrases.txt: "
                        + number(this.serverPhrases) + "), " + words + ", " + limit);
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
