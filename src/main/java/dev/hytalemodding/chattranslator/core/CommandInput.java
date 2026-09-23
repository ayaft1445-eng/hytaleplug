package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Разбор того, что игрок написал после имени команды. */
public final class CommandInput {

    private CommandInput() {
    }

    /**
     * Слова после имени команды. Сервер отдаёт команде всю строку ввода
     * ({@code "tr en"} или {@code "/tr en"}), поэтому имя команды в начале отбрасывается.
     */
    public static List<String> arguments(String input, Collection<String> commandNames) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>(Arrays.asList(input.trim().split("\\s+")));
        String first = words.get(0).startsWith("/") ? words.get(0).substring(1) : words.get(0);
        for (String name : commandNames) {
            if (name.equalsIgnoreCase(first)) {
                words.remove(0);
                break;
            }
        }
        return words;
    }
}
