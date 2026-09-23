package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Делит получателей сообщения на тех, кто увидит его как есть, и тех,
 * кому нужен перевод (по языкам).
 *
 * Как есть сообщение получают: сам автор, те, кто читает на языке сообщения,
 * и те, кто выключил перевод командой {@code /lang off}.
 *
 * @param <P> игрок (в плагине — {@code PlayerRef})
 */
public final class ChatRouting<P> {

    private final List<P> original;
    private final Map<Lang, List<P>> translated;

    private ChatRouting(List<P> original, Map<Lang, List<P>> translated) {
        this.original = original;
        this.translated = translated;
    }

    /**
     * @param written язык, на котором написано сообщение
     * @param idOf UUID игрока
     * @param readingLanguageOf на какой язык переводить игроку; {@code null} — перевод выключен
     */
    public static <P> ChatRouting<P> split(
            P sender,
            List<P> recipients,
            Lang written,
            Function<P, UUID> idOf,
            Function<P, Lang> readingLanguageOf
    ) {
        UUID senderId = sender != null ? idOf.apply(sender) : null;
        List<P> original = new ArrayList<>(recipients.size());
        Map<Lang, List<P>> translated = new EnumMap<>(Lang.class);
        for (P recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            if (senderId != null && Objects.equals(senderId, idOf.apply(recipient))) {
                original.add(recipient);
                continue;
            }
            Lang reading = readingLanguageOf.apply(recipient);
            if (reading == null || reading == written) {
                original.add(recipient);
            } else {
                translated.computeIfAbsent(reading, lang -> new ArrayList<>()).add(recipient);
            }
        }
        return new ChatRouting<>(original, translated);
    }

    /** Кто получит сообщение как есть. */
    public List<P> original() {
        return this.original;
    }

    /** Кому какой перевод нужен; пусто, если переводить некому. */
    public Map<Lang, List<P>> translated() {
        return Collections.unmodifiableMap(this.translated);
    }

    public boolean needsTranslation() {
        return !this.translated.isEmpty();
    }
}
