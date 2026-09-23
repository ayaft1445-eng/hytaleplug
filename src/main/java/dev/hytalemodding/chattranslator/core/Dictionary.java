package dev.hytalemodding.chattranslator.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Словарь отдельных слов: русская словоформа -> английское слово и английское слово
 * -> русское. Файлы лежат в jar ({@code dictionary/ru-en.tsv}, {@code dictionary/en-ru.tsv})
 * и собраны из данных OpenRussian.org (лицензия CC BY-SA 4.0) скриптом
 * {@code tools/build_dictionary.py}. Сомнительные слова в словарь не попали —
 * их переводит сервис.
 */
public final class Dictionary {

    private final Map<Lang, Map<String, String>> words;

    private Dictionary(Map<Lang, Map<String, String>> words) {
        this.words = words;
    }

    /** Пустой словарь: пока настоящий загружается или если он выключен. */
    public static Dictionary empty() {
        return of(Map.of(), Map.of());
    }

    /** Словарь из готовых пар (для проверок). */
    static Dictionary of(Map<String, String> russianToEnglish, Map<String, String> englishToRussian) {
        Map<Lang, Map<String, String>> words = new EnumMap<>(Lang.class);
        words.put(Lang.RU, Map.copyOf(russianToEnglish));
        words.put(Lang.EN, Map.copyOf(englishToRussian));
        return new Dictionary(words);
    }

    /** Загружает словарь из jar: около 120 тысяч слов, 10–20 МБ памяти, доли секунды. */
    public static Dictionary load() throws IOException {
        // Переводы повторяются у разных форм слова («меч», «меча», «мечом» -> sword):
        // одинаковые строки хранятся один раз.
        Map<String, String> shared = new HashMap<>();
        Map<Lang, Map<String, String>> words = new EnumMap<>(Lang.class);
        words.put(Lang.RU, read("/dictionary/ru-en.tsv", 150_000, shared));
        words.put(Lang.EN, read("/dictionary/en-ru.tsv", 20_000, shared));
        return new Dictionary(words);
    }

    private static Map<String, String> read(String resource, int expected, Map<String, String> shared) throws IOException {
        Map<String, String> result = new HashMap<>(expected * 4 / 3 + 1);
        try (InputStream stream = Dictionary.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("в jar нет " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                String value = line.substring(tab + 1);
                String existing = shared.putIfAbsent(value, value);
                result.put(line.substring(0, tab), existing != null ? existing : value);
            }
        }
        return result;
    }

    /** Перевод слова или {@code null}. Слово сравнивается без учёта регистра и «ё». */
    public String lookup(String word, Lang from) {
        return this.words.get(from).get(ChatText.normalizeWord(word));
    }

    /** Сколько слов в словаре для перевода с языка {@code from}. */
    public int size(Lang from) {
        return this.words.get(from).size();
    }

    public boolean isEmpty() {
        return this.words.get(Lang.RU).isEmpty() && this.words.get(Lang.EN).isEmpty();
    }
}
