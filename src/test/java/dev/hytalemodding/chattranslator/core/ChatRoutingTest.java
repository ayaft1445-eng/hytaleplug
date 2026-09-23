package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRoutingTest {

    /** Игрок для теста: имя, UUID и язык чтения ({@code null} — перевод выключен). */
    private record Player(String name, UUID id, Lang reads) {
        static Player of(String name, Lang reads) {
            return new Player(name, UUID.nameUUIDFromBytes(name.getBytes()), reads);
        }
    }

    private static ChatRouting<Player> split(Player sender, List<Player> recipients, Lang written) {
        return ChatRouting.split(sender, recipients, written, Player::id, Player::reads);
    }

    @Test
    void englishMessageGoesTranslatedToRussianReaders() {
        Player john = Player.of("John", Lang.EN);
        Player mike = Player.of("Mike", Lang.EN);
        Player alex = Player.of("Alex", Lang.RU);
        Player ivan = Player.of("Ivan", Lang.RU);
        Player off = Player.of("Bilingual", null);

        ChatRouting<Player> routing = split(john, List.of(john, mike, alex, ivan, off), Lang.EN);

        assertEquals(List.of(john, mike, off), routing.original());
        assertEquals(Map.of(Lang.RU, List.of(alex, ivan)), routing.translated());
        assertTrue(routing.needsTranslation());
    }

    @Test
    void authorAlwaysSeesOwnMessageAsWritten() {
        Player alex = Player.of("Alex", Lang.RU);
        // Игрок читает по-русски, но написал по-английски — себе он видит оригинал.
        ChatRouting<Player> routing = split(alex, List.of(alex), Lang.EN);
        assertEquals(List.of(alex), routing.original());
        assertFalse(routing.needsTranslation());
    }

    @Test
    void nobodyNeedsTranslation() {
        Player alex = Player.of("Alex", Lang.RU);
        Player ivan = Player.of("Ivan", Lang.RU);
        ChatRouting<Player> routing = split(alex, List.of(alex, ivan), Lang.RU);
        assertEquals(List.of(alex, ivan), routing.original());
        assertFalse(routing.needsTranslation());
    }
}
