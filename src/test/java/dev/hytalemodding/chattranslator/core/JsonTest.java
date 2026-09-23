package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTripKeepsValues() throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("text", "привет \"всем\"\n\\ \t");
        entry.put("count", 3L);
        entry.put("ok", true);
        entry.put("nothing", null);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", List.of(entry, entry));
        root.put("numbers", Arrays.asList(1L, -2L, 3.5));

        for (String written : List.of(Json.write(root), Json.writePretty(root))) {
            Map<String, Object> parsed = Json.parseObject(written);
            assertEquals(root, parsed, written);
        }
    }

    @Test
    void prettyOutputPutsOneEntryPerLine() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("from", "ru");
        entry.put("text", "привет");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", List.of(entry, entry));

        String expected = "{\n"
                + "  \"entries\": [\n"
                + "    {\"from\": \"ru\", \"text\": \"привет\"},\n"
                + "    {\"from\": \"ru\", \"text\": \"привет\"}\n"
                + "  ]\n"
                + "}\n";
        assertEquals(expected, Json.writePretty(root));
    }

    @Test
    void forgivesBomAndTrailingCommas() throws Exception {
        Map<String, Object> parsed = Json.parseObject("﻿{ \"a\": [1, 2, ], \"b\": \"x\", }");
        assertEquals(List.of(1L, 2L), parsed.get("a"));
        assertEquals("x", parsed.get("b"));
    }

    @Test
    void unicodeEscapes() throws Exception {
        assertEquals("Жук", Json.parse("\"\\u0416\\u0443\\u043a\""));
    }

    @Test
    void errorsPointToTheLine() {
        Json.JsonException missingQuote = assertThrows(Json.JsonException.class,
                () -> Json.parse("{\n  \"DeepLApiKey\": \"abc,\n  \"JoinHint\": true\n}"));
        assertTrue(missingQuote.getMessage().startsWith("строка 2"), missingQuote.getMessage());

        Json.JsonException missingComma = assertThrows(Json.JsonException.class,
                () -> Json.parse("{\n  \"a\": 1\n  \"b\": 2\n}"));
        assertTrue(missingComma.getMessage().contains("запятая"), missingComma.getMessage());

        Json.JsonException bareWord = assertThrows(Json.JsonException.class,
                () -> Json.parse("{\"DefaultLanguage\": ru}"));
        assertTrue(bareWord.getMessage().contains("кавычках"), bareWord.getMessage());

        assertThrows(Json.JsonException.class, () -> Json.parse("{} extra"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1, 2]"));
    }
}
