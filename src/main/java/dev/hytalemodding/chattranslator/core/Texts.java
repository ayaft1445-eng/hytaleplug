package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Тексты, которые плагин показывает игрокам, — на языке самого игрока.
 * Команды, выбранные значения и пояснения размечены, чтобы в чате они
 * выделялись цветом ({@link Styled}).
 */
public final class Texts {

    private Texts() {
    }

    /** Что игрок написал после {@code /tr}. */
    public enum Choice {
        RU, EN, AUTO, OFF, TEST;

        /** Понимает {@code ru}, {@code русский}, {@code eng}, {@code выкл} и т. п.; иначе {@code null}. */
        public static Choice parse(String value) {
            if (value == null) {
                return null;
            }
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "ru":
                case "rus":
                case "russian":
                case "ру":
                case "рус":
                case "русский":
                    return RU;
                case "en":
                case "eng":
                case "english":
                case "анг":
                case "англ":
                case "английский":
                    return EN;
                case "auto":
                case "авто":
                    return AUTO;
                case "off":
                case "none":
                case "выкл":
                case "нет":
                    return OFF;
                case "test":
                case "тест":
                case "проверка":
                    return TEST;
                default:
                    return null;
            }
        }
    }

    private static Styled prefix(Lang lang) {
        return new Styled().prefix(lang == Lang.RU ? "[Переводчик] " : "[Translator] ");
    }

    /** Подсказка при первом входе на сервер. */
    public static Styled joinHint(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Сообщения на английском будут переводиться для вас на ").value("русский")
                    .text(". ").command("/tr en").muted(" — на английский, ").command("/tr off")
                    .muted(" — без перевода, ").command("/tr").muted(" — все настройки.");
        }
        return prefix(lang).text("Russian chat messages will be translated into ").value("English")
                .text(" for you. ").command("/tr ru").muted(" — into Russian, ").command("/tr off")
                .muted(" — no translation, ").command("/tr").muted(" — all options.");
    }

    /** Игрок с английской игрой написал по-русски, и ему включён перевод на русский. */
    public static Styled learnedRussian() {
        return prefix(Lang.RU).text("Вы пишете по-русски, поэтому сообщения на английском теперь переводятся для вас на ")
                .value("русский").text(". Вернуть английский: ").command("/tr en");
    }

    /** Ответ на {@code /tr} без слов: что включено сейчас и как поменять — по строке на тему. */
    public static List<Styled> info(Lang lang, boolean translate) {
        List<Styled> lines = new ArrayList<>();
        if (lang == Lang.RU) {
            lines.add(translate
                    ? prefix(lang).text("Ваш язык чата: ").value("русский")
                            .text(" — сообщения на английском переводятся для вас на русский.")
                    : prefix(lang).text("Перевод для вас ").value("выключен").text(" — вы видите все сообщения как есть."));
            lines.add(new Styled().text("Сменить: ").command("/tr en").muted(" английский · ")
                    .command("/tr ru").muted(" русский · ").command("/tr off").muted(" без перевода · ")
                    .command("/tr auto").muted(" как в игре"));
            lines.add(new Styled().text("Проверить перевод: ").command("/tr test привет")
                    .muted(". Свои сообщения вы видите как написали — перевод видят другие."));
            return lines;
        }
        lines.add(translate
                ? prefix(lang).text("Your chat language: ").value("English")
                        .text(" — Russian messages are translated into English for you.")
                : prefix(lang).text("Translation is ").value("off").text(" for you — you see every message as written."));
        lines.add(new Styled().text("Change: ").command("/tr ru").muted(" Russian · ")
                .command("/tr en").muted(" English · ").command("/tr off").muted(" no translation · ")
                .command("/tr auto").muted(" game language"));
        lines.add(new Styled().text("Try it: ").command("/tr test hello")
                .muted(". You always see your own messages as you wrote them — others see the translation."));
        return lines;
    }

    /** Ответ на {@code /tr ru} и {@code /tr en}. */
    public static Styled chosen(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Язык чата: ").value("русский")
                    .text(". Сообщения на английском будут переводиться для вас на русский.");
        }
        return prefix(lang).text("Chat language: ").value("English")
                .text(". Russian messages will be translated into English for you.");
    }

    /** Ответ на {@code /tr auto}. */
    public static Styled reset(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Язык снова берётся из языка игры: ").value("русский").text(".");
        }
        return prefix(lang).text("Your language is taken from the game language again: ").value("English").text(".");
    }

    /** Ответ на {@code /tr off}. */
    public static Styled turnedOff(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Перевод ").value("выключен").text(": вы видите все сообщения так, как их написали. Включить: ")
                    .command("/tr ru").text(" или ").command("/tr en");
        }
        return prefix(lang).text("Translation is ").value("off").text(": you see every message as it was written. Turn it back on: ")
                .command("/tr en").text(" or ").command("/tr ru");
    }

    /** Непонятное слово после {@code /tr}. */
    public static Styled usage(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Напишите ").command("/tr ru").text(", ").command("/tr en").text(", ")
                    .command("/tr auto").muted(" (как в игре)").text(", ").command("/tr off").muted(" (без перевода)")
                    .text(" или ").command("/tr test текст").muted(" (проверить перевод).");
        }
        return prefix(lang).text("Type ").command("/tr en").text(", ").command("/tr ru").text(", ")
                .command("/tr auto").muted(" (game language)").text(", ").command("/tr off").muted(" (no translation)")
                .text(" or ").command("/tr test text").muted(" (try a translation).");
    }

    /** {@code /tr test} без текста. */
    public static Styled testUsage(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Напишите текст после ").command("/tr test").text(", например: ")
                    .command("/tr test привет всем");
        }
        return prefix(lang).text("Put some text after ").command("/tr test").text(", for example: ")
                .command("/tr test hello everyone");
    }

    /** Результат {@code /tr test}: перевод и откуда он взят. */
    public static Styled testResult(Lang lang, String original, Translation translation) {
        Styled line = prefix(lang).muted(original.trim()).text(" → ").value(translation.text());
        List<String> sources = new ArrayList<>();
        for (String source : translation.sources()) {
            sources.add(sourceName(source, lang));
        }
        if (!sources.isEmpty()) {
            line.muted(" (" + String.join(" + ", sources) + ")");
        }
        return line;
    }

    /** Откуда перевод: «разговорник», «словарь», «память» или имя сервиса. */
    public static String sourceName(String source, Lang lang) {
        switch (source) {
            case Translation.PHRASEBOOK:
                return lang == Lang.RU ? "разговорник" : "phrasebook";
            case Translation.DICTIONARY:
                return lang == Lang.RU ? "словарь" : "dictionary";
            case Translation.MEMORY:
                return lang == Lang.RU ? "память переводов" : "translation memory";
            default:
                return source;
        }
    }

    /** {@code /tr test} не смог перевести. */
    public static Styled testFailed(Lang lang, String reason) {
        if (lang == Lang.RU) {
            return prefix(lang).bad("Перевести не удалось: ").muted(reason);
        }
        return prefix(lang).bad("Translation failed: ").muted(reason);
    }

    /** {@code /tr test} с текстом без букв. */
    public static Styled testNothing(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).text("Здесь нечего переводить: нужны русские или английские буквы.");
        }
        return prefix(lang).text("Nothing to translate: the text needs Russian or English letters.");
    }

    /** Добавка к ответу на {@code /tr}, пока ни один переводчик не настроен. */
    public static Styled notConfigured(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang).bad("Выбор сохранён, но перевод на сервере ещё не настроен.");
        }
        return prefix(lang).bad("Saved, but translation is not set up on this server yet.");
    }

    /** Пометка у переведённого сообщения. */
    public static String translatedMark(Lang reader) {
        return reader == Lang.RU ? "(перевод)" : "(translated)";
    }

    /**
     * Что дописать после переведённого текста: пометку, исходный текст в скобках
     * или ничего ({@code null}).
     */
    public static String translationNote(TranslatorConfig config, Lang reader, String original) {
        if (config.showOriginal()) {
            return "(" + original.trim() + ")";
        }
        if (config.markTranslations()) {
            return translatedMark(reader);
        }
        return null;
    }

    /** Перевод ничем не отличается от исходника (ники, «gg», «ok»): показывать исходник без пометки. */
    public static boolean sameText(String original, String translation) {
        return TranslationMemory.normalize(original).equals(TranslationMemory.normalize(translation));
    }
}
