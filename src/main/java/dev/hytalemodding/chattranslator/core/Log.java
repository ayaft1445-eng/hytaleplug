package dev.hytalemodding.chattranslator.core;

/** Куда писать сообщения плагина: в консоль сервера, а в тестах — куда угодно. */
public interface Log {

    void info(String message);

    void warn(String message);

    /** Ничего не пишет. */
    Log SILENT = new Log() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };
}
