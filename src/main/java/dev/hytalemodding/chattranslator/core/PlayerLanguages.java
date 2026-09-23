package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * На каком языке игрок читает чат.
 *
 * При первом входе язык берётся из языка игры, который клиент присылает серверу
 * ({@code ru-RU} — русский, {@code en-US} — английский, остальное — язык из
 * настройки DefaultLanguage), и запоминается в {@code players.json}. Дальше
 * его меняет только сам игрок командой {@code /lang} или «обучение»: если у
 * игрока английская игра, а пишет он по-русски, ему начинают переводить на русский.
 */
public final class PlayerLanguages {

    /** Сколько кириллических букв нужно в сообщении, чтобы считать, что игрок пишет по-русски. */
    static final int LEARN_MIN_CYRILLIC = 3;

    private static final List<String> HELP = List.of(
            "Языки игроков ChatTranslator. Меняются командой /lang в игре.",
            "lang — на каком языке игрок читает чат (ru, en); translate: false — перевод выключен (/lang off).",
            "source — откуда взялся язык: game — язык игры при первом входе, chat — понят по сообщениям игрока, command — выбран командой /lang."
    );

    /** Откуда известен язык игрока. */
    public enum Source {
        /** Язык игры при первом входе. */
        GAME("game"),
        /** Понят по сообщениям игрока. */
        CHAT("chat"),
        /** Выбран игроком командой /lang. */
        COMMAND("command");

        private final String code;

        Source(String code) {
            this.code = code;
        }

        static Source fromCode(String code) {
            for (Source source : values()) {
                if (source.code.equals(code)) {
                    return source;
                }
            }
            return GAME;
        }
    }

    /** Что известно об игроке. */
    public static final class Entry {
        private String name;
        private Lang lang;
        private boolean translate;
        private Source source;

        Entry(String name, Lang lang, boolean translate, Source source) {
            this.name = name;
            this.lang = lang;
            this.translate = translate;
            this.source = source;
        }

        public String name() {
            return this.name;
        }

        /** Язык, на котором игрок читает чат; по нему же пишутся подсказки плагина. */
        public Lang lang() {
            return this.lang;
        }

        /** {@code false}, если игрок выключил перевод командой {@code /lang off}. */
        public boolean translate() {
            return this.translate;
        }

        public Source source() {
            return this.source;
        }
    }

    private volatile Lang defaultLanguage;
    private final Map<UUID, Entry> players = new LinkedHashMap<>();
    private boolean dirty;

    public PlayerLanguages(Lang defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    /** Язык по умолчанию после {@code /translator reload}; касается только новых игроков. */
    public void setDefaultLanguage(Lang defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    /** Язык по языку игры клиента; неизвестный язык — язык по умолчанию. */
    public Lang fromGameLanguage(String gameLanguage) {
        Lang lang = Lang.fromLocale(gameLanguage);
        return lang != null ? lang : this.defaultLanguage;
    }

    /**
     * Запоминает игрока при входе на сервер.
     *
     * @return {@code true}, если игрок пришёл впервые и его язык только что определён
     */
    public synchronized boolean register(UUID id, String name, String gameLanguage) {
        boolean firstVisit = !this.players.containsKey(id);
        resolve(id, name, gameLanguage);
        return firstVisit;
    }

    /**
     * Данные игрока; при первой встрече язык определяется по языку игры и запоминается.
     *
     * @param gameLanguage язык игры, который прислал клиент, например {@code ru-RU}
     */
    public synchronized Entry resolve(UUID id, String name, String gameLanguage) {
        Entry entry = this.players.get(id);
        if (entry == null) {
            entry = new Entry(name, fromGameLanguage(gameLanguage), true, Source.GAME);
            this.players.put(id, entry);
            this.dirty = true;
        } else if (name != null && !name.equals(entry.name)) {
            entry.name = name;
            this.dirty = true;
        }
        return entry;
    }

    /** На какой язык переводить игроку сообщения; {@code null} — перевод выключен. */
    public synchronized Lang readingLanguage(UUID id, String name, String gameLanguage) {
        Entry entry = resolve(id, name, gameLanguage);
        return entry.translate ? entry.lang : null;
    }

    /**
     * Учится по сообщению игрока: если язык взят из английской игры, а игрок пишет
     * по-русски, переключает его на русский.
     *
     * @return {@code true}, если язык игрока только что поменялся на русский
     */
    public synchronized boolean learnFromMessage(UUID id, String name, String gameLanguage, String text) {
        Entry entry = resolve(id, name, gameLanguage);
        if (entry.source == Source.COMMAND || entry.lang == Lang.RU) {
            return false;
        }
        if (TextLanguage.detect(text) != Lang.RU || TextLanguage.cyrillicLetters(text) < LEARN_MIN_CYRILLIC) {
            return false;
        }
        entry.lang = Lang.RU;
        entry.source = Source.CHAT;
        this.dirty = true;
        return true;
    }

    /** {@code /lang ru}, {@code /lang en}: язык выбран игроком, обучение больше не трогает его. */
    public synchronized Entry choose(UUID id, String name, String gameLanguage, Lang lang) {
        Entry entry = resolve(id, name, gameLanguage);
        entry.lang = lang;
        entry.translate = true;
        entry.source = Source.COMMAND;
        this.dirty = true;
        return entry;
    }

    /** {@code /lang off}: игрок видит все сообщения как есть. */
    public synchronized Entry turnOff(UUID id, String name, String gameLanguage) {
        Entry entry = resolve(id, name, gameLanguage);
        entry.translate = false;
        entry.source = Source.COMMAND;
        this.dirty = true;
        return entry;
    }

    /** {@code /lang auto}: снова язык игры, обучение по сообщениям снова работает. */
    public synchronized Entry reset(UUID id, String name, String gameLanguage) {
        Entry entry = resolve(id, name, gameLanguage);
        entry.lang = fromGameLanguage(gameLanguage);
        entry.translate = true;
        entry.source = Source.GAME;
        this.dirty = true;
        return entry;
    }

    /** Сколько игроков с включённым переводом читает на каждом языке. */
    public synchronized Map<Lang, Integer> countByLanguage() {
        Map<Lang, Integer> counts = new EnumMap<>(Lang.class);
        for (Entry entry : this.players.values()) {
            if (entry.translate) {
                counts.merge(entry.lang, 1, Integer::sum);
            }
        }
        return counts;
    }

    public synchronized int size() {
        return this.players.size();
    }

    public synchronized int translationOff() {
        int off = 0;
        for (Entry entry : this.players.values()) {
            if (!entry.translate) {
                off++;
            }
        }
        return off;
    }

    // ---------------------------------------------------------------- файл

    public synchronized int load(Path file) throws IOException, Json.JsonException {
        this.players.clear();
        if (!Files.exists(file)) {
            return 0;
        }
        Map<String, Object> json = Json.parseObject(DataFiles.read(file));
        Object map = json.get("players");
        if (map instanceof Map) {
            for (Map.Entry<?, ?> item : ((Map<?, ?>) map).entrySet()) {
                if (!(item.getValue() instanceof Map)) {
                    continue;
                }
                UUID id;
                try {
                    id = UUID.fromString(String.valueOf(item.getKey()));
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) item.getValue();
                Lang lang = Lang.fromCode(Json.getString(object, "lang", null));
                if (lang == null) {
                    continue;
                }
                this.players.put(id, new Entry(
                        Json.getString(object, "name", ""),
                        lang,
                        Json.getBoolean(object, "translate", true),
                        Source.fromCode(Json.getString(object, "source", "game"))
                ));
            }
        }
        this.dirty = false;
        return this.players.size();
    }

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
        Map<String, Object> players = new LinkedHashMap<>();
        for (Map.Entry<UUID, Entry> item : this.players.entrySet()) {
            Entry entry = item.getValue();
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("name", entry.name == null ? "" : entry.name);
            object.put("lang", entry.lang.code());
            object.put("translate", entry.translate);
            object.put("source", entry.source.code);
            players.put(item.getKey().toString(), object);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("_help", HELP);
        json.put("players", players);
        return json;
    }
}
