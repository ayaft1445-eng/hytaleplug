package dev.hytalemodding.chattranslator.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Разговорник: готовые переводы частых фраз и слов чата — «привет», «как дела»,
 * «gg», игровые словечки. Фраза из разговорника переводится сразу, без сервиса.
 *
 * Строки разговорника: {@code русский = english} (в обе стороны),
 * {@code русский > english} (только с русского), {@code русский < english}
 * (только с английского). Разделы: {@code [фразы]} (по умолчанию), {@code [слова]},
 * {@code [обращения]}, {@code [как есть]}. Строки с {@code #} — комментарии.
 * Если одна фраза встречается несколько раз, в каждую сторону берётся первая строка,
 * поэтому файл сервера читается раньше встроенного разговорника.
 */
public final class Phrasebook {

    /** Встроенный разговорник внутри jar. */
    static final String BUILT_IN = "/dictionary/phrases.txt";

    private enum Section {
        PHRASES, WORDS, INTERJECTIONS, KEEP
    }

    private final Map<Lang, Map<String, String>> exact = new EnumMap<>(Lang.class);
    private final Map<Lang, Map<String, String>> words = new EnumMap<>(Lang.class);
    private final Map<Lang, Set<String>> interjections = new EnumMap<>(Lang.class);

    public Phrasebook() {
        for (Lang lang : Lang.values()) {
            this.exact.put(lang, new HashMap<>());
            this.words.put(lang, new HashMap<>());
            this.interjections.put(lang, new HashSet<>());
        }
    }

    /** Встроенный разговорник из jar. */
    public static Phrasebook builtIn() throws IOException {
        Phrasebook phrasebook = new Phrasebook();
        phrasebook.addBuiltIn();
        return phrasebook;
    }

    /** Дописывает встроенный разговорник (после файла сервера: его строки важнее). */
    public void addBuiltIn() throws IOException {
        try (InputStream stream = Phrasebook.class.getResourceAsStream(BUILT_IN)) {
            if (stream == null) {
                throw new IOException("в jar нет " + BUILT_IN);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                this.add(reader, null);
            }
        }
    }

    /**
     * Добавляет строки из текста. Строки, которые не удалось разобрать, пропускаются,
     * а в {@code problems} попадает номер строки и причина.
     *
     * @return сколько фраз добавлено
     */
    public int add(String content, List<String> problems) {
        try {
            return this.add(new BufferedReader(new StringReader(content)), problems);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private int add(BufferedReader reader, List<String> problems) throws IOException {
        Section section = Section.PHRASES;
        String line;
        int number = 0;
        int added = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            String text = line.strip();
            if (number == 1 && text.startsWith("﻿")) {
                text = text.substring(1).strip();
            }
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            if (text.startsWith("[") && text.endsWith("]")) {
                Section parsed = parseSection(text.substring(1, text.length() - 1));
                if (parsed == null) {
                    problem(problems, number, "неизвестный раздел " + text
                            + " (есть [фразы], [слова], [обращения], [как есть])");
                } else {
                    section = parsed;
                }
                continue;
            }
            if (section == Section.KEEP) {
                String key = key(text);
                if (key.isEmpty()) {
                    problem(problems, number, "нет слов");
                    continue;
                }
                for (Lang lang : Lang.values()) {
                    this.put(lang, section, key, text);
                }
                added++;
                continue;
            }
            int separator = findSeparator(text);
            if (separator < 0) {
                problem(problems, number, "нет знака =, > или < между русской и английской частью");
                continue;
            }
            char direction = text.charAt(separator);
            String russian = text.substring(0, separator).strip();
            String english = text.substring(separator + 1).strip();
            String russianKey = key(russian);
            String englishKey = key(english);
            if (russianKey.isEmpty() || englishKey.isEmpty()) {
                problem(problems, number, "одна из частей пустая");
                continue;
            }
            if (direction == '=' || direction == '>') {
                this.put(Lang.RU, section, russianKey, english);
            }
            if (direction == '=' || direction == '<') {
                this.put(Lang.EN, section, englishKey, russian);
            }
            added++;
        }
        return added;
    }

    private void put(Lang from, Section section, String key, String value) {
        this.exact.get(from).putIfAbsent(key, value);
        if (section == Section.WORDS && key.indexOf(' ') < 0) {
            this.words.get(from).putIfAbsent(key, value);
        }
        if (section == Section.INTERJECTIONS || section == Section.KEEP) {
            this.interjections.get(from).add(key);
        }
    }

    private static void problem(List<String> problems, int line, String message) {
        if (problems != null) {
            problems.add("строка " + line + ": " + message);
        }
    }

    private static Section parseSection(String name) {
        switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "фразы":
            case "phrases":
                return Section.PHRASES;
            case "слова":
            case "words":
                return Section.WORDS;
            case "обращения":
            case "interjections":
                return Section.INTERJECTIONS;
            case "как есть":
            case "keep":
                return Section.KEEP;
            default:
                return null;
        }
    }

    /** Первый из знаков {@code =}, {@code >}, {@code <}; «<3» разделителем не считается. */
    private static int findSeparator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '>' || (c == '<' && !(i + 1 < text.length() && text.charAt(i + 1) == '3'))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Фраза в том виде, в котором она ищется: слова и числа в нижнем регистре через
     * пробел, «е» вместо «ё», без знаков препинания и смайлов.
     */
    public static String key(String text) {
        return key(ChatText.tokenize(text), 0, -1);
    }

    static String key(List<ChatText.Token> tokens, int from, int to) {
        int end = to < 0 ? tokens.size() : to;
        StringBuilder key = new StringBuilder();
        for (int i = from; i < end; i++) {
            ChatText.Token token = tokens.get(i);
            if (token.kind == ChatText.Kind.WORD || token.kind == ChatText.Kind.NUMBER) {
                if (key.length() > 0) {
                    key.append(' ');
                }
                key.append(ChatText.normalizeWord(token.text));
            }
        }
        return key.toString();
    }

    // ---------------------------------------------------------------- поиск

    /** Перевод фразы целиком или {@code null}. */
    public String exact(String key, Lang from) {
        return this.exact.get(from).get(key);
    }

    /** Перевод обращения («привет», «да», «спасибо») или {@code null}, если это не обращение. */
    public String interjection(String key, Lang from) {
        return this.interjections.get(from).contains(key) ? this.exact(key, from) : null;
    }

    /** Перевод отдельного слова из раздела [слова] или {@code null}. */
    public String word(String key, Lang from) {
        return this.words.get(from).get(key);
    }

    /** Сколько фраз знает разговорник в обе стороны вместе. */
    public int size() {
        int size = 0;
        for (Map<String, String> map : this.exact.values()) {
            size += map.size();
        }
        return size;
    }
}
