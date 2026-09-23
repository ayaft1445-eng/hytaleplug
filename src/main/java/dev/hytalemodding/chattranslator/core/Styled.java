package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сообщение плагина из кусочков с разным оформлением: команды, значения и пояснения
 * выделяются по-разному. Цвета задаёт тот, кто отправляет сообщение игроку.
 */
public final class Styled {

    /** Роль кусочка текста — от неё зависит цвет. */
    public enum Style {
        /** «[Переводчик]» в начале сообщения. */
        PREFIX,
        /** Обычный текст. */
        TEXT,
        /** Команда, которую можно ввести: «/tr en». */
        COMMAND,
        /** Выбранное значение: «русский», «выключен». */
        VALUE,
        /** Пояснение второго плана. */
        MUTED,
        /** Всё хорошо: «работает». */
        GOOD,
        /** Проблема: «не работает», «на паузе». */
        BAD
    }

    /** Кусочек текста с одной ролью. */
    public static final class Span {

        private final String text;
        private final Style style;

        Span(String text, Style style) {
            this.text = text;
            this.style = style;
        }

        public String text() {
            return this.text;
        }

        public Style style() {
            return this.style;
        }
    }

    private final List<Span> spans = new ArrayList<>();

    public static Styled of(String text) {
        return new Styled().text(text);
    }

    public Styled add(String text, Style style) {
        if (!text.isEmpty()) {
            this.spans.add(new Span(text, style));
        }
        return this;
    }

    public Styled prefix(String text) {
        return this.add(text, Style.PREFIX);
    }

    public Styled text(String text) {
        return this.add(text, Style.TEXT);
    }

    public Styled command(String text) {
        return this.add(text, Style.COMMAND);
    }

    public Styled value(String text) {
        return this.add(text, Style.VALUE);
    }

    public Styled muted(String text) {
        return this.add(text, Style.MUTED);
    }

    public Styled good(String text) {
        return this.add(text, Style.GOOD);
    }

    public Styled bad(String text) {
        return this.add(text, Style.BAD);
    }

    public Styled append(Styled other) {
        this.spans.addAll(other.spans);
        return this;
    }

    public List<Span> spans() {
        return Collections.unmodifiableList(this.spans);
    }

    /** Текст без оформления: для консоли и проверок. */
    public String plain() {
        StringBuilder text = new StringBuilder();
        for (Span span : this.spans) {
            text.append(span.text);
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return this.plain();
    }
}
