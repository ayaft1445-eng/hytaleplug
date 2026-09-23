package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки из {@code config.json} в папке плагина.
 *
 * Если файла нет, он создаётся с настройками по умолчанию и пустым ключом DeepL.
 * Поля, которых нет в файле (например, после обновления плагина), дописываются
 * со значениями по умолчанию; то, что уже вписано, не трогается.
 */
public final class TranslatorConfig {

    static final String KEY_API_KEY = "DeepLApiKey";
    static final String KEY_TRANSLATORS = "Translators";
    static final String KEY_MYMEMORY_EMAIL = "MyMemoryEmail";
    static final String KEY_DEFAULT_LANGUAGE = "DefaultLanguage";
    static final String KEY_MARK = "MarkTranslations";
    static final String KEY_SHOW_ORIGINAL = "ShowOriginal";
    static final String KEY_LEARN = "LearnLanguageFromChat";
    static final String KEY_JOIN_HINT = "JoinHint";
    static final String KEY_MEMORY_SIZE = "MemoryMaxPhrases";
    static final String KEY_HELP = "_help";

    static final int MIN_MEMORY = 100;
    static final int MAX_MEMORY = 1_000_000;

    /** Переводчики, которые знает плагин. */
    public static final String DEEPL = "deepl";
    public static final String MYMEMORY = "mymemory";
    static final List<String> DEFAULT_TRANSLATORS = List.of(DEEPL, MYMEMORY);

    private static final List<String> HELP = Arrays.asList(
            "Настройки ChatTranslator. После правки напишите /translator reload (или перезапустите сервер).",
            "DeepLApiKey — ключ DeepL API, вставляется между кавычками. Ключ бесплатного тарифа заканчивается на :fx.",
            "Translators — переводчики по порядку: если первый не может перевести (нет ключа, кончился лимит, не работает в стране сервера), переводит следующий. Есть \"deepl\" и \"mymemory\".",
            "MyMemoryEmail — почта для бесплатного переводчика MyMemory: с ней лимит 50 000 символов в день вместо 5 000. Можно оставить пустой.",
            "DefaultLanguage — язык для игроков, у которых игра не на русском и не на английском: \"ru\" или \"en\".",
            "MarkTranslations — дописывать к переведённому сообщению пометку (перевод) / (translated): true или false.",
            "ShowOriginal — вместо пометки показывать в скобках исходный текст сообщения: true или false.",
            "LearnLanguageFromChat — если игрок, у которого игра на английском, пишет по-русски, переводить ему на русский.",
            "JoinHint — при первом входе на сервер подсказать игроку команду /tr: true или false.",
            "MemoryMaxPhrases — сколько фраз хранить в памяти переводов (файл memory.json)."
    );

    private String deeplApiKey = "";
    private List<String> translators = DEFAULT_TRANSLATORS;
    private String myMemoryEmail = "";
    private Lang defaultLanguage = Lang.EN;
    private boolean markTranslations = true;
    private boolean showOriginal = false;
    private boolean learnLanguageFromChat = true;
    private boolean joinHint = true;
    private int memoryMaxPhrases = 20_000;

    /** Замечания к значениям в файле: неизвестный язык, слишком маленькая память и т. п. */
    private final List<String> warnings = new ArrayList<>();

    public static TranslatorConfig defaults() {
        return new TranslatorConfig();
    }

    /**
     * Читает файл или создаёт его. Если файл испорчен, бросает {@link Json.JsonException}
     * и ничего не перезаписывает — чтобы правку можно было исправить, а не потерять.
     */
    public static TranslatorConfig loadOrCreate(Path file) throws IOException, Json.JsonException {
        if (!Files.exists(file)) {
            TranslatorConfig config = defaults();
            DataFiles.writeAtomically(file, Json.writePretty(config.toJson()));
            return config;
        }
        Map<String, Object> json = Json.parseObject(DataFiles.read(file));
        TranslatorConfig config = fromJson(json);
        Map<String, Object> normalized = config.toJson();
        if (!normalized.keySet().equals(json.keySet()) || !HELP.equals(json.get(KEY_HELP))) {
            // Дописываем недостающие поля и свежую подсказку; значения игрока сохраняются.
            DataFiles.writeAtomically(file, Json.writePretty(normalized));
        }
        return config;
    }

    static TranslatorConfig fromJson(Map<String, Object> json) {
        TranslatorConfig config = new TranslatorConfig();
        config.deeplApiKey = Json.getString(json, KEY_API_KEY, "").trim();
        config.translators = parseTranslators(json.get(KEY_TRANSLATORS), config.warnings);
        config.myMemoryEmail = Json.getString(json, KEY_MYMEMORY_EMAIL, "").trim();

        String language = Json.getString(json, KEY_DEFAULT_LANGUAGE, Lang.EN.code());
        Lang parsed = Lang.fromCode(language);
        if (parsed == null) {
            config.warnings.add(KEY_DEFAULT_LANGUAGE + ": неизвестный язык \"" + language
                    + "\", используется \"en\". Допустимо: \"ru\" или \"en\".");
            parsed = Lang.EN;
        }
        config.defaultLanguage = parsed;

        config.markTranslations = Json.getBoolean(json, KEY_MARK, true);
        config.showOriginal = Json.getBoolean(json, KEY_SHOW_ORIGINAL, false);
        config.learnLanguageFromChat = Json.getBoolean(json, KEY_LEARN, true);
        config.joinHint = Json.getBoolean(json, KEY_JOIN_HINT, true);

        long memory = Json.getLong(json, KEY_MEMORY_SIZE, 20_000);
        if (memory < MIN_MEMORY || memory > MAX_MEMORY) {
            long clamped = Math.max(MIN_MEMORY, Math.min(MAX_MEMORY, memory));
            config.warnings.add(KEY_MEMORY_SIZE + ": " + memory + " вне допустимых границ, используется " + clamped + ".");
            memory = clamped;
        }
        config.memoryMaxPhrases = (int) memory;
        return config;
    }

    /** Список переводчиков из файла: {@code ["deepl", "mymemory"]} или строка через запятую. */
    private static List<String> parseTranslators(Object value, List<String> warnings) {
        if (value == null) {
            return DEFAULT_TRANSLATORS;
        }
        List<Object> items = new ArrayList<>();
        if (value instanceof List) {
            items.addAll((List<?>) value);
        } else if (value instanceof String) {
            items.addAll(Arrays.asList(((String) value).split(",")));
        } else {
            warnings.add(KEY_TRANSLATORS + ": ожидался список вида [\"deepl\", \"mymemory\"], используется он.");
            return DEFAULT_TRANSLATORS;
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String name = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || result.contains(name)) {
                continue;
            }
            if (name.equals(DEEPL) || name.equals(MYMEMORY)) {
                result.add(name);
            } else {
                warnings.add(KEY_TRANSLATORS + ": неизвестный переводчик \"" + item + "\", допустимо \"deepl\" и \"mymemory\".");
            }
        }
        if (result.isEmpty()) {
            warnings.add(KEY_TRANSLATORS + ": список пуст — чат не переводится.");
        }
        return List.copyOf(result);
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(KEY_HELP, HELP);
        json.put(KEY_API_KEY, this.deeplApiKey);
        json.put(KEY_TRANSLATORS, this.translators);
        json.put(KEY_MYMEMORY_EMAIL, this.myMemoryEmail);
        json.put(KEY_DEFAULT_LANGUAGE, this.defaultLanguage.code());
        json.put(KEY_MARK, this.markTranslations);
        json.put(KEY_SHOW_ORIGINAL, this.showOriginal);
        json.put(KEY_LEARN, this.learnLanguageFromChat);
        json.put(KEY_JOIN_HINT, this.joinHint);
        json.put(KEY_MEMORY_SIZE, this.memoryMaxPhrases);
        return json;
    }

    public String deeplApiKey() {
        return this.deeplApiKey;
    }

    public boolean hasApiKey() {
        return !this.deeplApiKey.isEmpty();
    }

    /** Переводчики по порядку: {@code deepl}, {@code mymemory}. */
    public List<String> translators() {
        return this.translators;
    }

    public String myMemoryEmail() {
        return this.myMemoryEmail;
    }

    public Lang defaultLanguage() {
        return this.defaultLanguage;
    }

    public boolean markTranslations() {
        return this.markTranslations;
    }

    public boolean showOriginal() {
        return this.showOriginal;
    }

    public boolean learnLanguageFromChat() {
        return this.learnLanguageFromChat;
    }

    public boolean joinHint() {
        return this.joinHint;
    }

    public int memoryMaxPhrases() {
        return this.memoryMaxPhrases;
    }

    public List<String> warnings() {
        return this.warnings;
    }
}
