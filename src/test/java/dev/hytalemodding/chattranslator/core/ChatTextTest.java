package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatTextTest {

    private static List<String> kinds(String text) {
        List<String> result = new ArrayList<>();
        for (ChatText.Token token : ChatText.tokenize(text)) {
            if (token.kind != ChatText.Kind.SPACE) {
                result.add(token.kind + ":" + token.text);
            }
        }
        return result;
    }

    private static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (List<ChatText.Token> sentence : ChatText.sentences(ChatText.tokenize(text))) {
            result.add(ChatText.join(sentence, 0, sentence.size()));
        }
        return result;
    }

    @Test
    void wordsKeepHyphensAndApostrophes() {
        assertEquals(List.of("WORD:кто-то", "PUNCT:,", "WORD:don't", "WORD:I’m", "WORD:1v1"),
                kinds("кто-то, don't I’m 1v1"));
        assertEquals(List.of("NUMBER:5", "WORD:алмазов", "NUMBER:3.5", "NUMBER:10:30"), kinds("5 алмазов 3.5 10:30"));
        assertEquals(List.of("WORD:привет", "PUNCT:-", "WORD:пока"), kinds("привет - пока"));
    }

    @Test
    void linksMentionsAndSmileysAreSeparate() {
        assertEquals(List.of("WORD:зайди", "WORD:на", "LINK:https://site.com/a?b=1", "PUNCT:."),
                kinds("зайди на https://site.com/a?b=1."));
        assertEquals(List.of("WORD:привет", "PUNCT:,", "LINK:@Steve_1"), kinds("привет, @Steve_1"));
        assertEquals(List.of("WORD:ок", "EMOTICON::)))", "WORD:lol", "EMOTICON:xD", "EMOTICON:<3"), kinds("ок :))) lol xD <3"));
        assertEquals(List.of("WORD:xbox"), kinds("xbox"), "слово на x — не смайл");
    }

    @Test
    void textIsRebuiltWithoutLosses() {
        String text = "  Привет!!  как дела?)) я тут :) https://x.io  ";
        List<ChatText.Token> tokens = ChatText.tokenize(text);
        assertEquals(text, ChatText.join(tokens, 0, tokens.size()));
    }

    @Test
    void sentencesEndOnPunctuationSmileysAndNewLines() {
        assertEquals(List.of("Привет! ", "Как дела?"), sentences("Привет! Как дела?"));
        assertEquals(List.of("привет) ", "как дела"), sentences("привет) как дела"));
        assertEquals(List.of("ура))) ", "го"), sentences("ура))) го"));
        assertEquals(List.of("ок :) ", "иду"), sentences("ок :) иду"));
        assertEquals(List.of("(не знаю) ок"), sentences("(не знаю) ок"), "закрывающая скобка — не конец предложения");
        assertEquals(List.of("привет (Стив) как дела"), sentences("привет (Стив) как дела"), "скобка после «(» — не смайл");
        assertEquals(List.of("ну...ладно"), sentences("ну...ладно"));
        assertEquals(List.of("первая\n", "вторая"), sentences("первая\nвторая"));
        assertEquals(List.of("3.5 блока"), sentences("3.5 блока"));
    }

    @Test
    void caseFollowsTheOriginal() {
        assertEquals("hi", ChatText.applyCase("привет", "hi"));
        assertEquals("Hi", ChatText.applyCase("Привет", "hi"));
        assertEquals("HI", ChatText.applyCase("ПРИВЕТ", "hi"));
        assertEquals("PvP", ChatText.applyCase("пвп", "PvP"), "заглавные буквы перевода не пропадают");
        assertEquals("I'm new", ChatText.applyCase("я новенький", "I'm new"));
        assertEquals("Я", ChatText.applyCase("I", "я"), "одна заглавная буква — это не КАПС");
    }

    @Test
    void wordScript() {
        assertEquals(Lang.RU, ChatText.tokenize("привет").get(0).script());
        assertEquals(Lang.EN, ChatText.tokenize("hello").get(0).script());
        assertEquals(Lang.RU, ChatText.tokenize("пvп").get(0).script(), "смесь раскладок считается русской");
        assertNull(ChatText.tokenize("123").get(0).script());
    }

    @Test
    void normalizedWords() {
        assertEquals("еще", ChatText.normalizeWord("Ещё"));
        assertEquals("don't", ChatText.normalizeWord("Don’t"));
    }
}
