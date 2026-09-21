package dev.hytalemodding.simplemenu;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.simplemenu.commands.MenuCommand;
import dev.hytalemodding.simplemenu.ui.MenuHud;

import javax.annotation.Nonnull;

/**
 * Точка входа плагина.
 *
 * Что делает плагин:
 *  - регистрирует команду /menu, которая открывает главное меню с двумя вкладками;
 *  - каждому вошедшему игроку показывает постоянную плашку в правом нижнем углу экрана.
 */
public class SimpleMenuPlugin extends JavaPlugin {

    public SimpleMenuPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new MenuCommand());
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, SimpleMenuPlugin::onPlayerReady);
        this.getLogger().info("[SimpleMenu] Плагин загружен. Команда для открытия меню: /menu");
    }

    /** Вешает плашку в правом нижнем углу, как только игрок полностью зашёл на сервер. */
    private static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }

        player.getHudManager().addCustomHud(playerRef, new MenuHud(playerRef));
    }
}
