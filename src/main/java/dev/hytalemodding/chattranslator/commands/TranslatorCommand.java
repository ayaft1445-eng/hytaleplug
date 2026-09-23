package dev.hytalemodding.chattranslator.commands;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import dev.hytalemodding.chattranslator.Render;
import dev.hytalemodding.chattranslator.core.Styled;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code /translator status} — какие переводчики работают, сколько символов осталось,
 * сколько сообщений переведено без сервиса, сколько фраз в памяти, какие имена команды
 * выбора языка доступны игрокам; {@code /translator reload} — перечитать config.json
 * и phrases.txt.
 *
 * Для администраторов: право на команду сервер создаёт сам, у операторов оно есть.
 * В консоли сервера команда пишется без косой черты: {@code translator status}.
 */
public class TranslatorCommand extends AbstractCommand {

    private final TranslatorCore core;
    private final Supplier<List<Styled>> commandCheck;
    private final RequiredArg<String> actionArg;

    public TranslatorCommand(TranslatorCore core, Supplier<List<Styled>> commandCheck) {
        super("translator", "ChatTranslator: status | reload");
        this.core = core;
        this.commandCheck = commandCheck;
        this.actionArg = this.withRequiredArg("action", "status | reload", ArgTypes.STRING);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String action = context.get(this.actionArg).trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "status":
                return this.core.status().thenAccept(lines -> {
                    send(context, lines);
                    send(context, this.commandCheck.get());
                });
            case "reload":
                send(context, this.core.reload());
                return CompletableFuture.completedFuture(null);
            default:
                context.sendMessage(Render.message(new Styled().text("Использование: ").command("/translator status")
                        .text(" или ").command("/translator reload")));
                return CompletableFuture.completedFuture(null);
        }
    }

    private static void send(CommandContext context, List<Styled> lines) {
        for (Styled line : lines) {
            context.sendMessage(Render.message(line));
        }
    }
}
