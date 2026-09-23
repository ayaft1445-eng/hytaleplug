package dev.hytalemodding.chattranslator;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.chattranslator.core.PlayerLanguages;
import dev.hytalemodding.chattranslator.core.Texts;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

/**
 * Вход игрока на сервер: при первом входе его язык определяется по языку игры
 * и запоминается, а сам игрок получает подсказку про команду /tr.
 */
final class JoinListener {

    private final TranslatorCore core;

    JoinListener(TranslatorCore core) {
        this.core = core;
    }

    void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        boolean firstVisit = this.core.players().register(playerRef.getUuid(), playerRef.getUsername(), playerRef.getLanguage());
        if (firstVisit && this.core.isActive() && this.core.config().joinHint()) {
            PlayerLanguages.Entry entry = this.core.players().resolve(playerRef.getUuid(), playerRef.getUsername(), playerRef.getLanguage());
            playerRef.sendMessage(Render.message(Texts.joinHint(entry.lang())));
        }
    }
}
