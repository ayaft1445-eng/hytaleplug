package dev.hytalemodding.chattranslator;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.chattranslator.commands.LangCommand;
import dev.hytalemodding.chattranslator.commands.TranslatorCommand;
import dev.hytalemodding.chattranslator.core.Log;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Точка входа плагина.
 *
 * Переводит чат между русским и английским: каждый игрок видит сообщения на своём
 * языке. Язык игрока определяется по языку игры при первом входе, игрок может
 * сменить его командой /lang. Уже переведённые фразы берутся из памяти, а не из DeepL.
 */
public class ChatTranslatorPlugin extends JavaPlugin {

    private TranslatorCore core;

    public ChatTranslatorPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Log log = new Log() {
            @Override
            public void info(String message) {
                getLogger().at(Level.INFO).log("[ChatTranslator] " + message);
            }

            @Override
            public void warn(String message) {
                getLogger().at(Level.WARNING).log("[ChatTranslator] " + message);
            }
        };

        this.core = new TranslatorCore(this.getDataDirectory(), log);

        // LAST: к этому моменту другие плагины уже решили, отменять ли сообщение (мут, антиспам)
        ChatListener chatListener = new ChatListener(this.core, log);
        this.getEventRegistry().registerAsyncGlobal(EventPriority.LAST, PlayerChatEvent.class, chatListener::onChat);

        JoinListener joinListener = new JoinListener(this.core);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, joinListener::onPlayerReady);

        this.getCommandRegistry().registerCommand(new LangCommand(this.core));
        this.getCommandRegistry().registerCommand(new TranslatorCommand(this.core));

        this.core.start();
    }

    @Override
    protected void shutdown() {
        if (this.core != null) {
            this.core.stop();
        }
    }
}
