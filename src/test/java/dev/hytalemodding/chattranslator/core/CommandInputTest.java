package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandInputTest {

    private static final List<String> NAMES = List.of("chatlang", "tr", "перевод", "язык");

    @Test
    void commandNameIsDropped() {
        assertEquals(List.of("en"), CommandInput.arguments("tr en", NAMES));
        assertEquals(List.of("en"), CommandInput.arguments("/tr en", NAMES));
        assertEquals(List.of("ru"), CommandInput.arguments("ChatLang   ru ", NAMES));
        assertEquals(List.of("англ"), CommandInput.arguments("язык англ", NAMES));
    }

    @Test
    void onlyArgumentsAreKeptAsIs() {
        assertEquals(List.of("en"), CommandInput.arguments("en", NAMES));
        assertEquals(List.of("test", "привет", "всем"), CommandInput.arguments("tr test привет всем", NAMES));
    }

    @Test
    void noArguments() {
        assertEquals(List.of(), CommandInput.arguments("tr", NAMES));
        assertEquals(List.of(), CommandInput.arguments("  ", NAMES));
        assertEquals(List.of(), CommandInput.arguments(null, NAMES));
    }
}
