package dev.hytalemodding.simplemenu.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Главное меню сервера: две вкладки («Мини-игры» и «Таблица лидеров»),
 * которые переключаются кнопками сверху.
 *
 * Разметка лежит в src/main/resources/Common/UI/Custom/SimpleMenu/MainMenu.ui
 */
public class MainMenuPage extends InteractiveCustomUIPage<MainMenuPage.MenuEventData> {

    /** Путь к .ui файлу относительно папки Common/UI/Custom/ */
    public static final String LAYOUT = "SimpleMenu/MainMenu.ui";

    private static final String TAB_MINIGAMES = "minigames";
    private static final String TAB_LEADERBOARD = "leaderboard";
    private static final String ACTION_CLOSE = "close";

    public MainMenuPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEventData.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder evt,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append(LAYOUT);

        // При открытии всегда показываем первую вкладку.
        cmd.set("#PanelMinigames.Visible", true);
        cmd.set("#PanelLeaderboard.Visible", false);

        evt.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TabMinigames",
                new EventData().append("Action", TAB_MINIGAMES),
                false
        );
        evt.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TabLeaderboard",
                new EventData().append("Action", TAB_LEADERBOARD),
                false
        );
        evt.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseButton",
                new EventData().append("Action", ACTION_CLOSE),
                false
        );
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull MenuEventData data
    ) {
        String action = data.getAction();
        if (action == null) {
            return;
        }

        switch (action) {
            case TAB_MINIGAMES -> selectTab(true);
            case TAB_LEADERBOARD -> selectTab(false);
            case ACTION_CLOSE -> this.close();
            default -> {
            }
        }
    }

    /** Показывает одну вкладку и прячет вторую. */
    private void selectTab(boolean minigames) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#PanelMinigames.Visible", minigames);
        cmd.set("#PanelLeaderboard.Visible", !minigames);
        this.sendUpdate(cmd, false);
    }

    /** Данные, которые клиент присылает при нажатии на кнопку. */
    public static class MenuEventData {

        public static final BuilderCodec<MenuEventData> CODEC = BuilderCodec
                .builder(MenuEventData.class, MenuEventData::new)
                .append(
                        new KeyedCodec<>("Action", Codec.STRING),
                        (eventData, value, extraInfo) -> eventData.action = value,
                        (eventData, extraInfo) -> eventData.action
                )
                .add()
                .build();

        private String action;

        public MenuEventData() {
        }

        public String getAction() {
            return this.action;
        }
    }
}
