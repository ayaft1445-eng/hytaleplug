package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalTranslatorTest {

    private static final Phrasebook PHRASEBOOK = new Phrasebook();

    static {
        PHRASEBOOK.add(String.join("\n",
                "[как есть]",
                "gg",
                "[обращения]",
                "привет = hi",
                "да = yes",
                "спасибо = thanks",
                "пожалуйста = please",
                "ребят > guys",
                "[фразы]",
                "как дела = how are you",
                "привет, как дела = hi, how are you",
                "конечно = of course",
                "помоги мне = help me",
                "не знаю = I don't know",
                "[слова]",
                "мне = me"), null);
    }

    private static final Dictionary DICTIONARY = Dictionary.of(
            Map.of("меч", "sword", "мечи", "swords", "дай", "give", "алмазов", "diamonds", "мой", "my"),
            Map.of("sword", "меч", "swords", "мечи", "house", "дом"));

    private static String plan(String text, Lang from, int maxWords, Set<String> remembered) {
        return new LocalTranslator(PHRASEBOOK, DICTIONARY, maxWords, remembered::contains).plan(text, from).toString();
    }

    private static String plan(String text) {
        return plan(text, Lang.RU, 1, Set.of());
    }

    @Test
    void wholePhrasesComeFromThePhrasebook() {
        assertEquals("[PHRASEBOOK[Как дела -> How are you], KEEP[?]]", plan("Как дела?"));
        assertEquals("[PHRASEBOOK[ПРИВЕТ -> HI], KEEP[!!!]]", plan("ПРИВЕТ!!!"));
        assertEquals("[PHRASEBOOK[привет, как дела -> hi, how are you], KEEP[?]]", plan("привет, как дела?"));
        assertEquals("[PHRASEBOOK[Привет -> Hi], KEEP[! ], PHRASEBOOK[Как дела -> How are you], KEEP[?)]]",
                plan("Привет! Как дела?)"));
    }

    @Test
    void greetingsAndThanksAreSplitOffAtCommas() {
        assertEquals("[PHRASEBOOK[привет -> hi], KEEP[, ], REMOTE[я новенький на сервере!]]",
                plan("привет, я новенький на сервере!"));
        assertEquals("[PHRASEBOOK[помоги мне -> help me], KEEP[, ], PHRASEBOOK[пожалуйста -> please]]",
                plan("помоги мне, пожалуйста"));
        assertEquals("[PHRASEBOOK[да -> yes], KEEP[, ], PHRASEBOOK[конечно -> of course]]", plan("да, конечно"));
        assertEquals("[PHRASEBOOK[ребят -> guys], KEEP[, ], REMOTE[кто в шахту], KEEP[, ], PHRASEBOOK[спасибо -> thanks]]",
                plan("ребят, кто в шахту, спасибо"));
    }

    @Test
    void clausesAreNotTornApart() {
        assertEquals("[REMOTE[спасибо, что помог!]]", plan("спасибо, что помог!"));
        assertEquals("[REMOTE[привет как жизнь]]", plan("привет как жизнь"), "без запятой обращение не отделяется");
    }

    @Test
    void singleWordsComeFromTheDictionary() {
        assertEquals("[DICTIONARY[меч -> sword]]", plan("меч"));
        assertEquals("[DICTIONARY[Мечи -> Swords], KEEP[!]]", plan("Мечи!"));
        assertEquals("[DICTIONARY[5 алмазов -> 5 diamonds]]", plan("5 алмазов"), "числа не считаются словами");
        assertEquals("[REMOTE[дай меч]]", plan("дай меч"), "два слова — уже сервис");
        assertEquals("[REMOTE[копьё]]", plan("копьё"), "слова нет в словаре");
        assertEquals("[DICTIONARY[house -> дом]]", plan("house", Lang.EN, 1, Set.of()));
    }

    @Test
    void moreWordsWhenAllowed() {
        assertEquals("[DICTIONARY[дай мне меч -> give me sword]]", plan("дай мне меч", Lang.RU, 3, Set.of()));
        assertEquals("[REMOTE[дай мне меч]]", plan("дай мне меч", Lang.RU, 3, Set.of("дай мне меч")),
                "сервис уже переводил эту фразу — его перевод точнее");
        assertEquals("[REMOTE[меч]]", plan("меч", Lang.RU, 0, Set.of()), "0 — словарь не используется");
        assertEquals("[REMOTE[дай мне меч!]]", plan("дай мне меч!", Lang.RU, 3, Set.of("дай мне меч!")),
                "в памяти фраза лежит вместе со знаком в конце");
    }

    @Test
    void textInTheReadersLanguageStaysAsItIs() {
        assertEquals("[PHRASEBOOK[привет -> hi], KEEP[, ], KEEP[Steve]]", plan("привет, Steve"));
        assertEquals("[KEEP[ok], KEEP[ :)]]", plan("ok :)"));
        assertEquals("[KEEP[https://site.com]]", plan("https://site.com"));
        assertEquals("[PHRASEBOOK[gg -> gg]]", plan("gg", Lang.EN, 1, Set.of()));
    }

    @Test
    void neighbouringSentencesGoToTheServiceTogether() {
        assertEquals("[REMOTE[я пошёл в шахту. Кто со мной?]]", plan("я пошёл в шахту. Кто со мной?"));
        assertEquals("[REMOTE[я пошёл в шахту.], KEEP[ ], PHRASEBOOK[Спасибо -> Thanks], KEEP[!]]",
                plan("я пошёл в шахту. Спасибо!"));
    }

    @Test
    void piecesRebuildTheWholeMessage() {
        String text = "  Привет, я тут новенький!! Как дела?)  ";
        StringBuilder source = new StringBuilder();
        for (LocalTranslator.Piece piece : new LocalTranslator(PHRASEBOOK, DICTIONARY, 1, s -> false).plan(text, Lang.RU)) {
            source.append(piece.source);
        }
        assertEquals(text, source.toString());
    }

    @Test
    void mergeJoinsRemotePiecesAcrossKeptOnes() {
        List<LocalTranslator.Piece> merged = LocalTranslator.merge(List.of(
                new LocalTranslator.Piece(LocalTranslator.Via.REMOTE, "раз.", null),
                new LocalTranslator.Piece(LocalTranslator.Via.KEEP, " ", " "),
                new LocalTranslator.Piece(LocalTranslator.Via.REMOTE, "два?", null),
                new LocalTranslator.Piece(LocalTranslator.Via.KEEP, " ", " "),
                new LocalTranslator.Piece(LocalTranslator.Via.PHRASEBOOK, "да", "yes")));
        assertEquals("[REMOTE[раз. два?], KEEP[ ], PHRASEBOOK[да -> yes]]", merged.toString());
    }
}
