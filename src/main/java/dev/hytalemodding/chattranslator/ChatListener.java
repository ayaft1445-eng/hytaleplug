package dev.hytalemodding.chattranslator;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.chattranslator.core.ChatRouting;
import dev.hytalemodding.chattranslator.core.Lang;
import dev.hytalemodding.chattranslator.core.Log;
import dev.hytalemodding.chattranslator.core.TextLanguage;
import dev.hytalemodding.chattranslator.core.Texts;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Перевод сообщений чата.
 *
 * Сервер рассылает сообщение списку получателей. Из списка убираются те, кому
 * нужен перевод, — остальные (и сам автор) получают сообщение как обычно и сразу.
 * Убранным игрокам плагин сам отправляет переведённое сообщение в том же оформлении,
 * что и обычный чат, как только придёт перевод. Если перевести не вышло, они
 * получают сообщение как есть — пропасть оно не может.
 */
final class ChatListener {

    /** Цвет пометки «(перевод)» после переведённого текста. */
    private static final String NOTE_COLOR = "#9a9a9a";

    private final TranslatorCore core;
    private final Log log;

    ChatListener(TranslatorCore core, Log log) {
        this.core = core;
        this.log = log;
    }

    CompletableFuture<PlayerChatEvent> onChat(CompletableFuture<PlayerChatEvent> future) {
        return future.thenApply(event -> {
            try {
                this.handle(event);
            } catch (Throwable exception) {
                // Что бы ни случилось с переводом, чат должен работать как без плагина.
                this.log.warn("сообщение отправлено без перевода из-за ошибки: " + exception);
            }
            return event;
        });
    }

    private void handle(PlayerChatEvent event) {
        if (event.isCancelled() || !this.core.isActive()) {
            return;
        }
        String text = event.getContent();
        Lang written = TextLanguage.detect(text);
        if (written == null) {
            return;
        }
        PlayerRef sender = event.getSender();

        if (sender != null && this.core.config().learnLanguageFromChat()
                && this.core.players().learnFromMessage(sender.getUuid(), sender.getUsername(), sender.getLanguage(), text)) {
            sender.sendMessage(Message.raw(Texts.learnedRussian()).color(NOTE_COLOR));
        }

        ChatRouting<PlayerRef> routing = ChatRouting.split(
                sender,
                event.getTargets(),
                written,
                PlayerRef::getUuid,
                target -> this.core.players().readingLanguage(target.getUuid(), target.getUsername(), target.getLanguage())
        );
        if (!routing.needsTranslation()) {
            return;
        }

        PlayerChatEvent.Formatter formatter = event.getFormatter();
        for (Map.Entry<Lang, List<PlayerRef>> group : routing.translated().entrySet()) {
            Lang reader = group.getKey();
            List<PlayerRef> recipients = group.getValue();
            this.core.delivery().enqueue(
                    this.core.translator().translate(text, written, reader),
                    translation -> send(recipients, this.render(formatter, sender, text, translation, reader)),
                    error -> send(recipients, formatter.format(sender, text))
            );
        }
        // Получатели перевода убираются из рассылки сервера последним шагом:
        // если что-то выше сломается, они получат сообщение как обычно.
        event.setTargets(routing.original());
    }

    /** Сообщение в оформлении чата, с пометкой «(перевод)» или исходным текстом в скобках. */
    private Message render(PlayerChatEvent.Formatter formatter, PlayerRef sender, String original, String translation, Lang reader) {
        if (Texts.sameText(original, translation)) {
            return formatter.format(sender, original);
        }
        Message message = formatter.format(sender, translation);
        String note = Texts.translationNote(this.core.config(), reader, original);
        if (note == null) {
            return message;
        }
        return Message.join(message, Message.raw(" " + note).color(NOTE_COLOR));
    }

    private static void send(List<PlayerRef> recipients, Message message) {
        for (PlayerRef recipient : recipients) {
            try {
                recipient.sendMessage(message);
            } catch (RuntimeException ignored) {
                // Игрок успел выйти с сервера, пока сообщение переводилось.
            }
        }
    }
}
