package dev.hytalemodding.chattranslator.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.chattranslator.core.Lang;
import dev.hytalemodding.chattranslator.core.PlayerLanguages;
import dev.hytalemodding.chattranslator.core.Texts;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * {@code /lang ru | en | auto | off} — на каком языке игрок читает чат.
 * Доступна всем игрокам, отдельное право не нужно.
 */
public class LangCommand extends AbstractPlayerCommand {

    private final TranslatorCore core;
    private final RequiredArg<String> languageArg;

    public LangCommand(TranslatorCore core) {
        super("lang", "Язык перевода чата / chat translation language: ru, en, auto, off");
        this.core = core;
        this.languageArg = this.withRequiredArg("language", "ru | en | auto | off", ArgTypes.STRING);
        // Без этого сервер выдал бы команде отдельное право, и обычные игроки не смогли бы её вызвать.
        this.requireNoPermission();
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        PlayerLanguages players = this.core.players();
        UUID id = playerRef.getUuid();
        String name = playerRef.getUsername();
        String gameLanguage = playerRef.getLanguage();

        Texts.Choice choice = Texts.Choice.parse(context.get(this.languageArg));
        if (choice == null) {
            PlayerLanguages.Entry entry = players.resolve(id, name, gameLanguage);
            context.sendMessage(Message.raw(Texts.usage(entry.lang())));
            return;
        }

        PlayerLanguages.Entry entry;
        String reply;
        switch (choice) {
            case RU:
                entry = players.choose(id, name, gameLanguage, Lang.RU);
                reply = Texts.chosen(Lang.RU);
                break;
            case EN:
                entry = players.choose(id, name, gameLanguage, Lang.EN);
                reply = Texts.chosen(Lang.EN);
                break;
            case AUTO:
                entry = players.reset(id, name, gameLanguage);
                reply = Texts.reset(entry.lang());
                break;
            default:
                entry = players.turnOff(id, name, gameLanguage);
                reply = Texts.turnedOff(entry.lang());
                break;
        }
        context.sendMessage(Message.raw(reply));
        if (!this.core.isActive()) {
            context.sendMessage(Message.raw(Texts.notConfigured(entry.lang())));
        }
    }
}
