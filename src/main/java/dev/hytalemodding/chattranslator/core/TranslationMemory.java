package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Память переводов: фраза, которую уже переводили, второй раз в DeepL не отправляется.
 *
 * Ключ — направление перевода и текст без лишних пробелов в нижнем регистре,
 * поэтому «Привет всем», «привет  всем» и «ПРИВЕТ ВСЕМ» — одна и та же фраза.
 * У каждой фразы считается, сколько раз она встретилась. Когда память заполнена,
 * первыми забываются фразы, которые встретились меньше всего раз и давно
 * не повторялись, — повторяющиеся фразы остаются.
 *
 * Память хранится в {@code memory.json}: одна фраза — одна строка, её можно
 * поправить руками при выключенном сервере.
 */
public final class TranslationMemory {

    private static final List<String> HELP = List.of(
            "Память переводов ChatTranslator: фраза, которая уже переводилась, берётся отсюда, а не из DeepL.",
            "from/to — направление (ru, en), text — фраза в нижнем регистре, translation — перевод, count — сколько раз встречалась.",
            "Неудачный перевод можно исправить прямо здесь: остановите сервер, поправьте translation, сохраните файл в UTF-8, запустите сервер.",
            "Чтобы плагин забыл фразу, удалите её строку целиком (вместе с запятой в конце)."
    );

    private int maxPhrases;

    /** Порядок доступа: первыми идут фразы, которые дольше всего не встречались. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

    private boolean dirty;

    public TranslationMemory(int maxPhrases) {
        this.maxPhrases = maxPhrases;
    }

    /** Одна запомненная фраза. */
    private static final class Entry {
        final Lang from;
        final Lang to;
        final String text;
        String translation;
        long count;

        Entry(Lang from, Lang to, String text, String translation, long count) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.translation = translation;
            this.count = count;
        }
    }

    /** Новый лимит фраз после {@code /translator reload}. */
    public synchronized void setMaxPhrases(int maxPhrases) {
        this.maxPhrases = maxPhrases;
        int before = this.entries.size();
        evictIfFull();
        if (this.entries.size() != before) {
            this.dirty = true;
        }
    }

    /** Текст фразы в том виде, в котором она хранится в памяти. */
    public static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String key(Lang from, Lang to, String normalizedText) {
        return from.code() + '>' + to.code() + '|' + normalizedText;
    }

    /** Есть ли фраза в памяти. В отличие от {@link #lookup}, фраза не считается встреченной. */
    public synchronized boolean contains(String text, Lang from, Lang to) {
        return this.entries.containsKey(key(from, to, normalize(text)));
    }

    /** Перевод из памяти или {@code null}. Найденная фраза считается встреченной ещё раз. */
    public synchronized String lookup(String text, Lang from, Lang to) {
        Entry entry = this.entries.get(key(from, to, normalize(text)));
        if (entry == null) {
            return null;
        }
        entry.count++;
        this.dirty = true;
        return entry.translation;
    }

    /** Запоминает перевод. Если фраза уже есть, перевод обновляется, счётчик растёт. */
    public synchronized void remember(String text, Lang from, Lang to, String translation) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        String key = key(from, to, normalized);
        Entry entry = this.entries.get(key);
        if (entry == null) {
            this.entries.put(key, new Entry(from, to, normalized, translation, 1));
            evictIfFull();
        } else {
            entry.translation = translation;
            entry.count++;
        }
        this.dirty = true;
    }

    public synchronized int size() {
        return this.entries.size();
    }

    /**
     * Когда фраз больше лимита, освобождает десятую часть места: сначала фразы,
     * которые встретились один раз, от самых давних; если их не хватило —
     * встреченные дважды, и так далее.
     */
    private void evictIfFull() {
        if (this.entries.size() <= this.maxPhrases) {
            return;
        }
        int target = this.maxPhrases - Math.max(1, this.maxPhrases / 10);
        long threshold = 1;
        while (this.entries.size() > target) {
            long smallestAbove = Long.MAX_VALUE;
            Iterator<Entry> iterator = this.entries.values().iterator();
            while (iterator.hasNext() && this.entries.size() > target) {
                Entry entry = iterator.next();
                if (entry.count <= threshold) {
                    iterator.remove();
                } else {
                    smallestAbove = Math.min(smallestAbove, entry.count);
                }
            }
            if (smallestAbove == Long.MAX_VALUE) {
                break;
            }
            threshold = smallestAbove;
        }
    }

    // ---------------------------------------------------------------- файл

    /** Загружает память из файла. Возвращает число загруженных фраз. */
    public synchronized int load(Path file) throws IOException, Json.JsonException {
        this.entries.clear();
        if (!Files.exists(file)) {
            return 0;
        }
        Map<String, Object> json = Json.parseObject(DataFiles.read(file));
        Object list = json.get("entries");
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) item;
                Lang from = Lang.fromCode(Json.getString(object, "from", null));
                Lang to = Lang.fromCode(Json.getString(object, "to", null));
                String text = Json.getString(object, "text", null);
                String translation = Json.getString(object, "translation", null);
                if (from == null || to == null || from == to || text == null || translation == null) {
                    continue;
                }
                String normalized = normalize(text);
                if (normalized.isEmpty()) {
                    continue;
                }
                long count = Math.max(1, Json.getLong(object, "count", 1));
                this.entries.put(key(from, to, normalized), new Entry(from, to, normalized, translation, count));
            }
        }
        evictIfFull();
        this.dirty = false;
        return this.entries.size();
    }

    /** Сохраняет память, если в ней что-то поменялось. */
    public void saveIfDirty(Path file) throws IOException {
        String content;
        synchronized (this) {
            if (!this.dirty) {
                return;
            }
            content = Json.writePretty(toJson());
            this.dirty = false;
        }
        try {
            DataFiles.writeAtomically(file, content);
        } catch (IOException exception) {
            synchronized (this) {
                this.dirty = true;
            }
            throw exception;
        }
    }

    private Map<String, Object> toJson() {
        List<Object> list = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries.values()) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("from", entry.from.code());
            object.put("to", entry.to.code());
            object.put("count", entry.count);
            object.put("text", entry.text);
            object.put("translation", entry.translation);
            list.add(object);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("_help", HELP);
        json.put("entries", list);
        return json;
    }
}
