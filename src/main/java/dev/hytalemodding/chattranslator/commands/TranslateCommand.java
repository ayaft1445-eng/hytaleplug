package dev.hytalemodding.chattranslator.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.chattranslator.Render;
import dev.hytalemodding.chattranslator.core.CommandInput;
import dev.hytalemodding.chattranslator.core.Lang;
import dev.hytalemodding.chattranslator.core.PlayerLanguages;
import dev.hytalemodding.chattranslator.core.Styled;
import dev.hytalemodding.chattranslator.core.TextLanguage;
import dev.hytalemodding.chattranslator.core.Texts;
import dev.hytalemodding.chattranslator.core.Translator;
import dev.hytalemodding.chattranslator.core.TranslatorCore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /tr ru | en | auto | off | test текст} — на каком языке игрок читает чат.
 * Без слов показывает, что включено сейчас. Доступна всем игрокам в любой момент.
 *
 * Имя {@code /lang} занято встроенной командой сервера (языковые файлы Hytale),
 * поэтому у плагина свои: {@code /tr}, {@code /chatlang}, {@code /перевод}, {@code /язык}.
 */
public class TranslateCommand extends AbstractPlayerCommand {

    /** Основное имя — редкое, чтобы его не заняла другая команда. */
    public static final String NAME = "chatlang";

    /** Короткие имена: {@code /tr} и русские, чтобы не переключать раскладку. */
    public static final List<String> ALIASES = List.of("tr", "перевод", "язык");

    private final TranslatorCore core;

    public TranslateCommand(TranslatorCore core) {
        super(NAME, "Язык перевода чата / chat translation: ru, en, auto, off, test");
        this.core = core;
        this.addAliases(ALIASES.toArray(new String[0]));
        // Слова после команды разбираются здесь, чтобы работали и /tr, и /tr en, и /tr test текст.
        this.setAllowsExtraArguments(true);
        // Иначе сервер выдал бы команде отдельное право, и обычные игроки не смогли бы её вызвать.
        this.requireNoPermission();
    }

    /** Все имена команды: основное и короткие. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        names.add(NAME);
        names.addAll(ALIASES);
        return names;
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
        PlayerLanguages.Entry current = players.resolve(id, name, gameLanguage);

        List<String> arguments = CommandInput.arguments(context.getInputString(), names());
        if (arguments.isEmpty()) {
            for (Styled line : Texts.info(current.lang(), current.translate())) {
                reply(context, line);
            }
            if (!this.core.isActive()) {
                reply(context, Texts.notConfigured(current.lang()));
            }
            return;
        }

        Texts.Choice choice = Texts.Choice.parse(arguments.get(0));
        if (choice == null) {
            reply(context, Texts.usage(current.lang()));
            return;
        }
        if (choice == Texts.Choice.TEST) {
            this.test(context, current.lang(), String.join(" ", arguments.subList(1, arguments.size())));
            return;
        }

        PlayerLanguages.Entry entry;
        Styled answer;
        switch (choice) {
            case RU:
                entry = players.choose(id, name, gameLanguage, Lang.RU);
                answer = Texts.chosen(Lang.RU);
                break;
            case EN:
                entry = players.choose(id, name, gameLanguage, Lang.EN);
                answer = Texts.chosen(Lang.EN);
                break;
            case AUTO:
                entry = players.reset(id, name, gameLanguage);
                answer = Texts.reset(entry.lang());
                break;
            default:
                entry = players.turnOff(id, name, gameLanguage);
                answer = Texts.turnedOff(entry.lang());
                break;
        }
        reply(context, answer);
        if (!this.core.isActive()) {
            reply(context, Texts.notConfigured(entry.lang()));
        }
    }

    /**
     * {@code /tr test текст}: переводит текст на другой язык и показывает только самому
     * игроку — вместе с тем, откуда взят перевод (разговорник, словарь, память, сервис).
     */
    private void test(CommandContext context, Lang reader, String text) {
        if (text.isBlank()) {
            reply(context, Texts.testUsage(reader));
            return;
        }
        Lang written = TextLanguage.detect(text);
        if (written == null) {
            reply(context, Texts.testNothing(reader));
            return;
        }
        Lang target = written == Lang.RU ? Lang.EN : Lang.RU;
        this.core.messages().translate(text, written, target).whenComplete((translation, error) -> {
            if (error == null) {
                reply(context, Texts.testResult(reader, text, translation));
            } else {
                reply(context, Texts.testFailed(reader, Translator.describe(Translator.unwrap(error))));
            }
        });
    }

    private static void reply(CommandContext context, Styled text) {
        context.sendMessage(Render.message(text));
    }
}
