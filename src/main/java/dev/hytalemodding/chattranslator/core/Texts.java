package dev.hytalemodding.chattranslator.core;

import java.util.Locale;

/** Тексты, которые плагин показывает игрокам, — на языке самого игрока. */
public final class Texts {

    private Texts() {
    }

    /** Что игрок написал после {@code /lang}. */
    public enum Choice {
        RU, EN, AUTO, OFF;

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
                    + "/lang en — переводить на английский, /lang off — без перевода.";
        }
        return prefix(lang) + "Russian chat messages will be translated into English for you. "
                + "/lang ru — translate into Russian, /lang off — no translation.";
    }

    /** Игрок с английской игрой написал по-русски, и ему включён перевод на русский. */
    public static String learnedRussian() {
        return prefix(Lang.RU) + "Вы пишете по-русски, поэтому сообщения на английском теперь "
                + "переводятся для вас на русский. Вернуть английский: /lang en";
    }

    /** Ответ на {@code /lang ru} и {@code /lang en}. */
    public static String chosen(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Язык чата: русский. Сообщения на английском будут переводиться для вас на русский.";
        }
        return prefix(lang) + "Chat language: English. Russian messages will be translated into English for you.";
    }

    /** Ответ на {@code /lang auto}. */
    public static String reset(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Язык снова берётся из языка игры: русский.";
        }
        return prefix(lang) + "Your language is taken from the game language again: English.";
    }

    /** Ответ на {@code /lang off}. */
    public static String turnedOff(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Перевод выключен: вы видите все сообщения так, как их написали. "
                    + "Включить: /lang ru или /lang en";
        }
        return prefix(lang) + "Translation is off: you see every message as it was written. "
                + "Turn it back on: /lang en or /lang ru";
    }

    /** Непонятный аргумент {@code /lang}. */
    public static String usage(Lang lang) {
        if (lang == Lang.RU) {
            return prefix(lang) + "Напишите /lang ru, /lang en, /lang auto (как в игре) или /lang off (без перевода).";
        }
        return prefix(lang) + "Type /lang en, /lang ru, /lang auto (game language) or /lang off (no translation).";
    }

    /** Добавка к ответу на {@code /lang}, пока администратор не вписал ключ DeepL. */
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
