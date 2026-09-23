package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Небольшой JSON без внешних библиотек: файлы плагина и запросы к DeepL.
 *
 * Объект читается в {@code LinkedHashMap<String, Object>}, массив — в {@code List<Object>},
 * целые числа — в {@code Long}, дробные — в {@code Double}. Разбор прощает то, что
 * часто получается при правке руками: метку BOM в начале файла и запятую после
 * последнего элемента.
 */
public final class Json {

    private Json() {
    }

    /** Ошибка разбора; в сообщении строка и позиция, где файл сломан. */
    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- чтение

    public static Object parse(String text) throws JsonException {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("лишний текст после конца JSON");
        }
        return value;
    }

    /** Разбирает текст, который обязан быть объектом {@code { ... }}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("ожидался объект в фигурных скобках { ... }");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
            if (text.startsWith("﻿")) {
                this.pos = 1;
            }
        }

        boolean atEnd() {
            return this.pos >= this.text.length();
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = this.text.charAt(this.pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    this.pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() throws JsonException {
            if (atEnd()) {
                throw error("файл обрывается, ожидалось значение");
            }
            char c = this.text.charAt(this.pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("неожиданный символ '" + c + "' (текст должен быть в кавычках)");
            }
        }

        private Map<String, Object> readObject() throws JsonException {
            Map<String, Object> result = new LinkedHashMap<>();
            this.pos++; // {
            skipWhitespace();
            if (peek('}')) {
                this.pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek('}')) { // запятая после последнего поля
                    this.pos++;
                    return result;
                }
                if (!peek('"')) {
                    throw error("ожидалось имя поля в кавычках");
                }
                String key = readString();
                skipWhitespace();
                if (!peek(':')) {
                    throw error("после имени поля \"" + key + "\" ожидалось двоеточие");
                }
                this.pos++;
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                if (peek(',')) {
                    this.pos++;
                } else if (peek('}')) {
                    this.pos++;
                    return result;
                } else {
                    throw error("ожидалась запятая или } после поля \"" + key + "\"");
                }
            }
        }

        private List<Object> readArray() throws JsonException {
            List<Object> result = new ArrayList<>();
            this.pos++; // [
            skipWhitespace();
            if (peek(']')) {
                this.pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek(']')) { // запятая после последнего элемента
                    this.pos++;
                    return result;
                }
                result.add(readValue());
                skipWhitespace();
                if (peek(',')) {
                    this.pos++;
                } else if (peek(']')) {
                    this.pos++;
                    return result;
                } else {
                    throw error("ожидалась запятая или ] в списке");
                }
            }
        }

        private String readString() throws JsonException {
            int start = this.pos;
            this.pos++; // "
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    this.pos = start;
                    throw error("не закрыта кавычка");
                }
                char c = this.text.charAt(this.pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\n') {
                    this.pos = start;
                    throw error("не закрыта кавычка до конца строки");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("строка обрывается после \\");
                }
                char escaped = this.text.charAt(this.pos++);
                switch (escaped) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (this.pos + 4 > this.text.length()) {
                            throw error("неполная последовательность \\u");
                        }
                        try {
                            out.append((char) Integer.parseInt(this.text.substring(this.pos, this.pos + 4), 16));
                        } catch (NumberFormatException exception) {
                            throw error("неверная последовательность \\u");
                        }
                        this.pos += 4;
                        break;
                    default:
                        this.pos--;
                        throw error("неизвестная последовательность \\" + escaped
                                + " (обратную косую черту в тексте пишите как \\\\)");
                }
            }
        }

        private Object readNumber() throws JsonException {
            int start = this.pos;
            if (peek('-')) {
                this.pos++;
            }
            boolean fraction = false;
            while (!atEnd()) {
                char c = this.text.charAt(this.pos);
                if (c >= '0' && c <= '9') {
                    this.pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = true;
                    this.pos++;
                } else {
                    break;
                }
            }
            String number = this.text.substring(start, this.pos);
            try {
                if (!fraction) {
                    return Long.parseLong(number);
                }
                return Double.parseDouble(number);
            } catch (NumberFormatException exception) {
                this.pos = start;
                throw error("неверное число: " + number);
            }
        }

        private void expectWord(String word) throws JsonException {
            if (!this.text.startsWith(word, this.pos)) {
                throw error("ожидалось " + word + " (текст должен быть в кавычках)");
            }
            this.pos += word.length();
        }

        private boolean peek(char expected) {
            return !atEnd() && this.text.charAt(this.pos) == expected;
        }

        JsonException error(String message) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < this.pos && i < this.text.length(); i++) {
                if (this.text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new JsonException("строка " + line + ", символ " + column + ": " + message);
        }
    }

    // ---------------------------------------------------------------- запись

    /** Весь JSON одной строкой — для запросов. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0, 0);
        return out.toString();
    }

    /**
     * JSON с отступами для файлов: верхний объект и его списки раскрыты по строкам,
     * а объекты внутри них пишутся одной строкой — так одна запись занимает одну строку.
     */
    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0, 2);
        out.append('\n');
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, int depth, int expandDepth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append(quote((String) value));
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && !Double.isInfinite(number) && Math.abs(number) < 1e15) {
                out.append((long) number);
            } else {
                out.append(number);
            }
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, depth, expandDepth);
        } else if (value instanceof Iterable) {
            writeArray(out, (Iterable<?>) value, depth, expandDepth);
        } else {
            out.append(quote(value.toString()));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth, int expandDepth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        boolean expand = depth < expandDepth;
        out.append('{');
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            if (expand) {
                newLine(out, depth + 1);
            }
            out.append(quote(String.valueOf(entry.getKey()))).append(": ");
            writeValue(out, entry.getValue(), depth + 1, expandDepth);
            if (iterator.hasNext()) {
                out.append(expand ? "," : ", ");
            }
        }
        if (expand) {
            newLine(out, depth);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> items, int depth, int expandDepth) {
        Iterator<?> iterator = items.iterator();
        if (!iterator.hasNext()) {
            out.append("[]");
            return;
        }
        boolean expand = depth < expandDepth;
        out.append('[');
        while (iterator.hasNext()) {
            if (expand) {
                newLine(out, depth + 1);
            }
            writeValue(out, iterator.next(), depth + 1, expandDepth);
            if (iterator.hasNext()) {
                out.append(expand ? "," : ", ");
            }
        }
        if (expand) {
            newLine(out, depth);
        }
        out.append(']');
    }

    private static void newLine(StringBuilder out, int depth) {
        out.append('\n');
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    /** Строка в кавычках с экранированием. Русские буквы пишутся как есть. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    // ---------------------------------------------------------------- удобные геттеры

    /** Строка из поля объекта или {@code fallback}, если поля нет или оно другого типа. */
    public static String getString(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static boolean getBoolean(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public static long getLong(Map<String, Object> object, String key, long fallback) {
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
