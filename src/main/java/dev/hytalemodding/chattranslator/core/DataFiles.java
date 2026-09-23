package dev.hytalemodding.chattranslator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Чтение и запись файлов плагина в UTF-8. */
final class DataFiles {

    private DataFiles() {
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Пишет файл через временный: если сервер выключится посреди записи,
     * старый файл останется целым.
     */
    static void writeAtomically(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Откладывает испорченный файл в сторону ({@code memory.json.broken}), чтобы
     * плагин записал новый, а старый можно было посмотреть и починить.
     */
    static Path moveAside(Path file) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + ".broken");
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
