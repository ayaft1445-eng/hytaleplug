package dev.hytalemodding.chattranslator.core;

import java.util.Locale;

/** Тексты, которые плагин показывает игрокам, — на языке самого игрока. */
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

    private static String prefix(Lang lang) {
        return lang == Lang.RU ? "[Переводчик] " : "[Translator] ";
    }

    /** Подсказка при первом входе на сервер. */
    public static String joinHint(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Сообщения на английском будут переводиться для вас на русский. "
                    + "/tr en — переводить на английский, /tr off — без перевода, /tr — все настройки.";
        }
        return prefix(lang) + "Russian chat messages will be translated into English for you. "
                + "/tr ru — translate into Russian, /tr off — no translation, /tr — all options.";
    }

    /** Игрок с английской игрой написал по-русски, и ему включён перевод на русский. */
    public static String learnedRussian() {
        return prefix(Lang.RU) + "Вы пишете по-русски, поэтому сообщения на английском теперь "
                + "переводятся для вас на русский. Вернуть английский: /tr en";
    }

    /** Ответ на {@code /tr} без слов: что включено сейчас и как поменять. */
    public static String info(Lang lang, boolean translate) {
        if (lang == Lang.RU) {
            String now = translate
                    ? "Ваш язык чата: русский — сообщения на английском переводятся для вас на русский."
                    : "Перевод для вас выключен — вы видите все сообщения как есть.";
            return prefix(lang) + now + " Сменить: /tr en — английский, /tr ru — русский, /tr off — без перевода, "
                    + "/tr auto — как в игре. Проверить перевод: /tr test привет. "
                    + "Свои сообщения вы всегда видите как написали — перевод видят другие.";
        }
        String now = translate
                ? "Your chat language: English — Russian messages are translated into English for you."
                : "Translation is off for you — you see every message as written.";
        return prefix(lang) + now + " Change: /tr ru — Russian, /tr en — English, /tr off — no translation, "
                + "/tr auto — game language. Try it: /tr test hello. "
                + "You always see your own messages as you wrote them — others see the translation.";
    }

    /** Ответ на {@code /tr ru} и {@code /tr en}. */
    public static String chosen(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Язык чата: русский. Сообщения на английском будут переводиться для вас на русский.";
        }
        return prefix(lang) + "Chat language: English. Russian messages will be translated into English for you.";
    }

    /** Ответ на {@code /tr auto}. */
    public static String reset(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Язык снова берётся из языка игры: русский.";
        }
        return prefix(lang) + "Your language is taken from the game language again: English.";
    }

    /** Ответ на {@code /tr off}. */
    public static String turnedOff(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Перевод выключен: вы видите все сообщения так, как их написали. "
                    + "Включить: /tr ru или /tr en";
        }
        return prefix(lang) + "Translation is off: you see every message as it was written. "
                + "Turn it back on: /tr en or /tr ru";
    }

    /** Непонятное слово после {@code /tr}. */
    public static String usage(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Напишите /tr ru, /tr en, /tr auto (как в игре), /tr off (без перевода) "
                    + "или /tr test текст (проверить перевод).";
        }
        return prefix(lang) + "Type /tr en, /tr ru, /tr auto (game language), /tr off (no translation) "
                + "or /tr test text (try a translation).";
    }

    /** {@code /tr test} без текста. */
    public static String testUsage(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Напишите текст после /tr test, например: /tr test привет всем";
        }
        return prefix(lang) + "Put some text after /tr test, for example: /tr test hello everyone";
    }

    /** Результат {@code /tr test}. */
    public static String testResult(Lang lang, String original, String translation) {
        return prefix(lang) + original.trim() + " -> " + translation;
    }

    /** {@code /tr test} не смог перевести. */
    public static String testFailed(Lang lang, String reason) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Перевести не удалось: " + reason;
        }
        return prefix(lang) + "Translation failed: " + reason;
    }

    /** {@code /tr test} с текстом без букв. */
    public static String testNothing(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Здесь нечего переводить: нужны русские или английские буквы.";
        }
        return prefix(lang) + "Nothing to translate: the text needs Russian or English letters.";
    }

    /** Добавка к ответу на {@code /tr}, пока ни один переводчик не настроен. */
    public static String notConfigured(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Выбор сохранён, но перевод на сервере ещё не настроен.";
        }
        return prefix(lang) + "Saved, but translation is not set up on this server yet.";
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
