package dev.hytalemodding.simplemenu.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * Постоянная плашка в правом нижнем углу экрана.
 *
 * Разметка лежит в src/main/resources/Common/UI/Custom/SimpleMenu/MenuHud.ui
 */
public class MenuHud extends CustomUIHud {

    /** Уникальный ключ этого HUD-элемента (по нему его можно убрать). */
    public static final String KEY = "simplemenu_hud";

    /** Путь к .ui файлу относительно папки Common/UI/Custom/ */
    public static final String LAYOUT = "SimpleMenu/MenuHud.ui";

    public MenuHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(LAYOUT);
    }
}
