package dev.hytalemodding.simplemenu.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Главное меню сервера: четыре вкладки, которые переключаются кнопками сверху.
 *
 * Как устроено переключение: страница всегда знает, какая вкладка выбрана
 * (поле activeTab). При постройке она вставляет в полосу вкладок яркую кнопку
 * для выбранной вкладки и тёмные для остальных, а в область содержимого —
 * разметку только выбранной вкладки. По нажатию на другую вкладку игроку
 * открывается эта же страница с другим номером вкладки.
 *
 * Разметка: src/main/resources/Common/UI/Custom/SimpleMenu/
 */
public class MainMenuPage extends InteractiveCustomUIPage<MainMenuPage.MenuEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Путь к .ui файлу относительно папки Common/UI/Custom/ */
    public static final String LAYOUT = "SimpleMenu/MainMenu.ui";

    private static final String TAB_ACTIVE_LAYOUT = "SimpleMenu/TabActive.ui";
    private static final String TAB_IDLE_LAYOUT = "SimpleMenu/TabIdle.ui";

    /** Названия вкладок — в том же порядке, что и разметка содержимого ниже. */
    private static final String[] TAB_TITLES = {
            "МИНИ-ИГРЫ",
            "ТАБЛИЦА ЛИДЕРОВ",
            "КОСМЕТИКА",
            "МИРЫ"
    };

    /** Содержимое вкладок — по одному .ui файлу на вкладку. */
    private static final String[] TAB_PANELS = {
            "SimpleMenu/Panel_Minigames.ui",
            "SimpleMenu/Panel_Leaderboard.ui",
            "SimpleMenu/Panel_Cosmetics.ui",
            "SimpleMenu/Panel_Worlds.ui"
    };

    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_TAB_PREFIX = "tab";

    private final PlayerRef playerRef;
    private final PageManager pageManager;
    private final int activeTab;

    public MainMenuPage(@Nonnull PlayerRef playerRef, @Nonnull PageManager pageManager, int activeTab) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEventData.CODEC);
        this.playerRef = playerRef;
        this.pageManager = pageManager;
        this.activeTab = Math.max(0, Math.min(activeTab, TAB_TITLES.length - 1));
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder evt,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append(LAYOUT);

        for (int i = 0; i < TAB_TITLES.length; i++) {
            // Вставленная кнопка становится элементом #TabBar[i]
            cmd.append("#TabBar", i == this.activeTab ? TAB_ACTIVE_LAYOUT : TAB_IDLE_LAYOUT);

            String selector = "#TabBar[" + i + "]";
            cmd.set(selector + ".Text", TAB_TITLES[i]);
            evt.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector,
                    new EventData().append("Action", ACTION_TAB_PREFIX + i),
                    false
            );
        }

        cmd.append("#Content", TAB_PANELS[this.activeTab]);

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
        LOGGER.at(Level.INFO).log("[SimpleMenu] menu event: " + action);

        if (action == null) {
            return;
        }

        if (ACTION_CLOSE.equals(action)) {
            this.close();
            return;
        }

        if (action.startsWith(ACTION_TAB_PREFIX)) {
            int tab = parseTabIndex(action);
            if (tab < 0 || tab == this.activeTab) {
                return;
            }
            this.pageManager.openCustomPage(ref, store, new MainMenuPage(this.playerRef, this.pageManager, tab));
        }
    }

    private static int parseTabIndex(@Nonnull String action) {
        try {
            return Integer.parseInt(action.substring(ACTION_TAB_PREFIX.length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
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
