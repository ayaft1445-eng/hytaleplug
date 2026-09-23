package dev.hytalemodding.chattranslator.core;

/** Отказ сервиса перевода с причиной, понятной администратору. */
public final class ServiceException extends RuntimeException {

    /** Что именно пошло не так — от этого зависит, на сколько сервис откладывается. */
    public enum Kind {
        /** Ключ или почта не подошли. */
        KEY_REJECTED,
        /** Сервис не работает со страной, где стоит сервер (код 451). */
        REGION_BLOCKED,
        /** Закончился лимит символов. */
        QUOTA,
        /** Слишком много запросов подряд. */
        TOO_MANY_REQUESTS,
        /** Всё остальное: сервис недоступен, непонятный ответ, слишком длинное сообщение. */
        OTHER
    }

    private final Kind kind;
    private final int status;

    public ServiceException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return this.kind;
    }

    /** HTTP-код ответа сервиса; 0, если запрос не отправлялся. */
    public int status() {
        return this.status;
    }
}
