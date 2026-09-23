package dev.hytalemodding.chattranslator;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.chattranslator.commands.TranslateCommand;
import dev.hytalemodding.chattranslator.commands.TranslatorCommand;
import dev.hytalemodding.chattranslator.core.Log;
import dev.hytalemodding.chattranslator.core.Styled;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Точка входа плагина.
 *
 * Переводит чат между русским и английским: каждый игрок видит сообщения на своём
 * языке. Язык игрока определяется по языку игры при первом входе, игрок может
 * сменить его в любой момент командой /tr. Частые фразы и отдельные слова переводятся
 * на сервере (разговорник и словарь), уже переведённые фразы берутся из памяти,
 * остальное переводит сервис.
 */
public class ChatTranslatorPlugin extends JavaPlugin {

    /** Через сколько секунд после запуска проверить, что имена команд не заняты. */
    private static final long COMMAND_CHECK_DELAY_SECONDS = 30;

    private TranslatorCore core;
    private Log log;
    private TranslateCommand translateCommand;

    public ChatTranslatorPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.log = new Log() {
            @Override
            public void info(String message) {
                getLogger().at(Level.INFO).log("[ChatTranslator] " + message);
            }

            @Override
            public void warn(String message) {
                getLogger().at(Level.WARNING).log("[ChatTranslator] " + message);
            }
        };

        this.core = new TranslatorCore(this.getDataDirectory(), this.log);

        // LAST: к этому моменту другие плагины уже решили, отменять ли сообщение (мут, антиспам)
        ChatListener chatListener = new ChatListener(this.core, this.log);
        this.getEventRegistry().registerAsyncGlobal(EventPriority.LAST, PlayerChatEvent.class, chatListener::onChat);

        JoinListener joinListener = new JoinListener(this.core);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, joinListener::onPlayerReady);

        this.translateCommand = new TranslateCommand(this.core);
        this.getCommandRegistry().registerCommand(this.translateCommand);
        this.getCommandRegistry().registerCommand(new TranslatorCommand(this.core, this::commandCheck));

        this.core.start();
        // Встроенные команды сервера регистрируются и после плагинов — поэтому проверка позже.
        this.core.later(this::logCommandCheck, COMMAND_CHECK_DELAY_SECONDS);
    }

    @Override
    protected void shutdown() {
        if (this.core != null) {
            this.core.stop();
        }
    }

    /**
     * Какие имена команды выбора языка действительно ведут в плагин. Сервер хранит
     * команды по имени, и зарегистрированная позже команда с тем же именем вытесняет
     * прежнюю — так встроенная {@code /lang} перекрыла команду плагина в версии 1.0.0.
     */
    private List<Styled> commandCheck() {
        List<String> working = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        try {
            CommandManager manager = CommandManager.get();
            for (String name : TranslateCommand.names()) {
                if (manager.resolveCommand(name) == this.translateCommand) {
                    working.add("/" + name);
                } else {
                    taken.add("/" + name);
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            return List.of(new Styled().bad("Проверить имена команды выбора языка не удалось: " + exception));
        }
        List<Styled> lines = new ArrayList<>();
        lines.add(working.isEmpty()
                ? new Styled().bad("Команда выбора языка недоступна игрокам: все её имена заняты другими командами.")
                : new Styled().text("Команда выбора языка для игроков: ").command(String.join(", ", working)));
        if (!taken.isEmpty()) {
            lines.add(new Styled().bad("Заняты другой командой сервера или плагина: ").text(String.join(", ", taken)));
        }
        return lines;
    }

    private void logCommandCheck() {
        List<Styled> lines = this.commandCheck();
        this.log.info(lines.get(0).plain());
        for (int i = 1; i < lines.size(); i++) {
            this.log.warn(lines.get(i).plain());
        }
    }
}
