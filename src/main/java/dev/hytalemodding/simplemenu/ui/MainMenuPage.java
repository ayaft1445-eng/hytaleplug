package dev.hytalemodding.simplemenu.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
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
 * Главное меню сервера.
 *
 * Устройство окна: сверху полоса из шести вкладок, слева четыре пустых слота
 * (запас на будущее), в середине — содержимое. При открытии показываются
 * новости; кнопки вкладок переключают содержимое, кнопка «Новости» внизу
 * возвращает на главную.
 *
 * Переключение сделано пересборкой: по нажатию игроку открывается эта же
 * страница с другим номером раздела, поэтому кнопка выбранной вкладки всегда
 * яркая, а содержимое всегда соответствует выбору.
 *
 * Разметка: src/main/resources/Common/UI/Custom/SimpleMenu/
 */
public class MainMenuPage extends InteractiveCustomUIPage<MainMenuPage.MenuEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Главная страница меню — новости. */
    public static final int SECTION_HOME = -1;

    /** Номер вкладки «Мини-игры» — у неё есть кнопки «Найти группу». */
    private static final int SECTION_MINIGAMES = 0;

    /** Сколько кнопок «Найти группу» во вкладке мини-игр. */
    private static final int FIND_GROUP_BUTTONS = 3;

    private static final String FRAME_LAYOUT = "SimpleMenu/Frame.ui";
    private static final String HOME_LAYOUT = "SimpleMenu/Content_Home.ui";

    /** Вкладки: порядок в этом списке — порядок кнопок слева направо. */
    private static final Tab[] TABS = {
            new Tab("МИНИ-ИГРЫ", "SimpleMenu/Tab_Minigames_On.ui", "SimpleMenu/Tab_Minigames_Off.ui", "SimpleMenu/Content_Minigames.ui"),
            new Tab("ТАБЛИЦА ЛИДЕРОВ", "SimpleMenu/Tab_Leaderboard_On.ui", "SimpleMenu/Tab_Leaderboard_Off.ui", "SimpleMenu/Content_Leaderboard.ui"),
            new Tab("КОСМЕТИКА", "SimpleMenu/Tab_Cosmetics_On.ui", "SimpleMenu/Tab_Cosmetics_Off.ui", "SimpleMenu/Content_Cosmetics.ui"),
            new Tab("МИРЫ", "SimpleMenu/Tab_Worlds_On.ui", "SimpleMenu/Tab_Worlds_Off.ui", "SimpleMenu/Content_Worlds.ui"),
            new Tab("ПРАВИЛА", "SimpleMenu/Tab_Rules_On.ui", "SimpleMenu/Tab_Rules_Off.ui", "SimpleMenu/Content_Rules.ui"),
            new Tab("ДИСКОРД", "SimpleMenu/Tab_Discord_On.ui", "SimpleMenu/Tab_Discord_Off.ui", "SimpleMenu/Content_Discord.ui")
    };

    private static final String ACTION_TAB_PREFIX = "tab";
    private static final String ACTION_FIND_PREFIX = "find";
    private static final String ACTION_NEWS = "news";
    private static final String ACTION_CLOSE = "close";

    private final PlayerRef playerRef;
    private final PageManager pageManager;
    private final int section;

    public MainMenuPage(@Nonnull PlayerRef playerRef, @Nonnull PageManager pageManager, int section) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEventData.CODEC);
        this.playerRef = playerRef;
        this.pageManager = pageManager;
        this.section = isTab(section) ? section : SECTION_HOME;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder evt,
            @Nonnull Store<EntityStore> store
    ) {
        cmd.append(FRAME_LAYOUT);

        // Полоса вкладок: выбранная кнопка яркая, остальные тёмные
        for (int i = 0; i < TABS.length; i++) {
            cmd.append("#TabBar", i == this.section ? TABS[i].activeLayout : TABS[i].idleLayout);
            evt.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TabBar[" + i + "]",
                    new EventData().append("Action", ACTION_TAB_PREFIX + i),
                    false
            );
        }

        cmd.append("#Content", this.section == SECTION_HOME ? HOME_LAYOUT : TABS[this.section].contentLayout);

        // Кнопки «Найти группу» есть только во вкладке мини-игр
        if (this.section == SECTION_MINIGAMES) {
            for (int i = 0; i < FIND_GROUP_BUTTONS; i++) {
                evt.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#FindGroup" + i,
                        new EventData().append("Action", ACTION_FIND_PREFIX + i),
                        false
                );
            }
        }

        evt.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#NewsButton",
                new EventData().append("Action", ACTION_NEWS),
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
        LOGGER.at(Level.INFO).log("[SimpleMenu] menu event: " + action);

        if (action == null) {
            return;
        }

        if (ACTION_CLOSE.equals(action)) {
            this.close();
            return;
        }

        if (ACTION_NEWS.equals(action)) {
            this.openSection(ref, store, SECTION_HOME);
            return;
        }

        if (action.startsWith(ACTION_TAB_PREFIX)) {
            int requested = parseIndex(action, ACTION_TAB_PREFIX);
            if (isTab(requested) && requested != this.section) {
                this.openSection(ref, store, requested);
            }
            return;
        }

        // Заглушка: поиск группы пока только отвечает игроку в чат
        if (action.startsWith(ACTION_FIND_PREFIX)) {
            this.playerRef.sendMessage(Message.raw("Поиск группы появится позже."));
        }
    }

    /** Открывает игроку это же меню с другим выбранным разделом. */
    private void openSection(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            int newSection
    ) {
        this.pageManager.openCustomPage(ref, store, new MainMenuPage(this.playerRef, this.pageManager, newSection));
    }

    private static boolean isTab(int value) {
        return value >= 0 && value < TABS.length;
    }

    private static int parseIndex(@Nonnull String action, @Nonnull String prefix) {
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return SECTION_HOME;
        }
    }

    /** Одна вкладка: название, две кнопки (выбранная и нет) и её содержимое. */
    private record Tab(String title, String activeLayout, String idleLayout, String contentLayout) {
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
