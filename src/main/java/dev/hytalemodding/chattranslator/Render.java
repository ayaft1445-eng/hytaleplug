package dev.hytalemodding.chattranslator;

import com.hypixel.hytale.server.core.Message;
import dev.hytalemodding.chattranslator.core.Styled;

/**
 * Цвета сообщений плагина: заголовок «[Переводчик]», команды и выбранные значения
 * выделены, пояснения приглушены, чтобы важное было видно с первого взгляда.
 */
public final class Render {

    /** Пометка «(перевод)» после переведённого сообщения. */
    static final String NOTE_COLOR = "#9a9a9a";

    private Render() {
    }

    public static Message message(Styled styled) {
        Message[] parts = new Message[styled.spans().size()];
        for (int i = 0; i < parts.length; i++) {
            Styled.Span span = styled.spans().get(i);
            Message part = Message.raw(span.text()).color(color(span.style()));
            if (isBold(span.style())) {
                part = part.bold(true);
            }
            parts[i] = part;
        }
        if (parts.length == 0) {
            return Message.raw("");
        }
        return parts.length == 1 ? parts[0] : Message.join(parts);
    }

    private static String color(Styled.Style style) {
        switch (style) {
            case PREFIX:
                return "#4fc3f7";
            case COMMAND:
                return "#ffd54f";
            case VALUE:
            case GOOD:
                return "#81c784";
            case MUTED:
                return "#a0a0a0";
            case BAD:
                return "#ff8a65";
            case TEXT:
            default:
                return "#e8e8e8";
        }
    }

    private static boolean isBold(Styled.Style style) {
        return style == Styled.Style.PREFIX || style == Styled.Style.COMMAND || style == Styled.Style.VALUE;
    }
}
