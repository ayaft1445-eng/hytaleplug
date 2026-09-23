package dev.hytalemodding.chattranslator.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /translator status} — работает ли ключ DeepL, сколько символов осталось,
 * сколько фраз в памяти; {@code /translator reload} — перечитать config.json.
 *
 * Для администраторов: право на команду сервер создаёт сам, у операторов оно есть.
 * В консоли сервера команда пишется без косой черты: {@code translator status}.
 */
public class TranslatorCommand extends AbstractCommand {

    private final TranslatorCore core;
    private final RequiredArg<String> actionArg;

    public TranslatorCommand(TranslatorCore core) {
        super("translator", "ChatTranslator: status | reload");
        this.core = core;
        this.actionArg = this.withRequiredArg("action", "status | reload", ArgTypes.STRING);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String action = context.get(this.actionArg).trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "status":
                return this.core.status().thenAccept(lines -> send(context, lines));
            case "reload":
                send(context, this.core.reload());
                return CompletableFuture.completedFuture(null);
            default:
                context.sendMessage(Message.raw("Использование: /translator status или /translator reload"));
                return CompletableFuture.completedFuture(null);
        }
    }

    private static void send(CommandContext context, List<String> lines) {
        for (String line : lines) {
            context.sendMessage(Message.raw(line));
        }
    }
}
