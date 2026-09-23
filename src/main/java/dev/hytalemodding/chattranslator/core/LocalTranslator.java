package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Решает, какие части сообщения переводятся на месте, а какие уходят в сервис.
 *
 * Сообщение делится на предложения. Предложение переводится на месте, если оно
 * целиком есть в разговорнике («как дела?») или состоит из обращений через запятую
 * («да, конечно»), а также если в нём не больше {@code maxWords} слов и все они есть
 * в словаре («меч» -> «sword»). Обращение в начале или в конце предложения
 * («привет, ...», «..., спасибо») переводится само, а остальное отправляется в сервис.
 * Соседние предложения для сервиса склеиваются в один запрос, чтобы не терять смысл.
 */
final class LocalTranslator {

    /** Как переведена часть сообщения. */
    enum Via {
        /** Оставлена как есть: знаки, смайлы, числа, слова уже на нужном языке. */
        KEEP,
        PHRASEBOOK,
        DICTIONARY,
        /** Нужен сервис (или память переводов). */
        REMOTE
    }

    static final class Piece {

        final Via via;
        final String source;
        final String translation;

        Piece(Via via, String source, String translation) {
            this.via = via;
            this.source = source;
            this.translation = translation;
        }

        @Override
        public String toString() {
            return this.via + "[" + this.source + (this.via == Via.REMOTE || this.via == Via.KEEP ? "" : " -> " + this.translation) + "]";
        }
    }

    /**
     * После обращения в начале предложения идёт придаточное — переводить по частям
     * нельзя: «спасибо, что помог» — это не «спасибо» + «что помог».
     */
    private static final Set<String> RU_CLAUSE_WORDS = Set.of(
            "что", "чтобы", "чтоб", "как", "когда", "если", "потому", "где", "куда", "откуда", "зачем",
            "почему", "который", "которая", "которое", "которые", "которого", "которой", "которому",
            "которым", "которых", "котором", "которую", "ли", "будто", "хотя", "пока", "чем", "раз");
    private static final Set<String> EN_CLAUSE_WORDS = Set.of(
            "that", "if", "when", "because", "how", "what", "who", "which", "where", "since", "though",
            "although", "unless", "whether", "while", "why", "than", "as", "so");

    private final Phrasebook phrasebook;
    private final Dictionary dictionary;
    private final int maxWords;
    private final Predicate<String> remembered;

    /**
     * @param maxWords   сколько слов подряд можно перевести по словарю (0 — только разговорник)
     * @param remembered есть ли фраза в памяти переводов: перевод сервиса точнее перевода по словам
     */
    LocalTranslator(Phrasebook phrasebook, Dictionary dictionary, int maxWords, Predicate<String> remembered) {
        this.phrasebook = phrasebook;
        this.dictionary = dictionary;
        this.maxWords = maxWords;
        this.remembered = remembered;
    }

    /** Части сообщения по порядку: из них собирается перевод. */
    List<Piece> plan(String text, Lang from) {
        List<Piece> pieces = new ArrayList<>();
        for (List<ChatText.Token> sentence : ChatText.sentences(ChatText.tokenize(text))) {
            this.planSentence(sentence, from, pieces);
        }
        return merge(pieces);
    }

    private void planSentence(List<ChatText.Token> s, Lang from, List<Piece> out) {
        int first = 0;
        while (first < s.size() && !s.get(first).isContent()) {
            first++;
        }
        if (first == s.size()) {
            out.add(keep(ChatText.join(s, 0, s.size())));
            return;
        }
        int last = s.size() - 1;
        while (!s.get(last).isContent()) {
            last--;
        }
        if (first > 0) {
            out.add(keep(ChatText.join(s, 0, first)));
        }
        String tail = ChatText.join(s, last + 1, s.size());

        // Предложение целиком из разговорника: «как дела?», «привет, как дела».
        String whole = this.phrase(s, first, last + 1, from);
        if (whole != null) {
            out.add(new Piece(Via.PHRASEBOOK, ChatText.join(s, first, last + 1), whole));
            out.add(keep(tail));
            return;
        }

        // Обращения в начале: «привет, ...», «ну, ...», «да, да, ...».
        int start = first;
        while (true) {
            int runEnd = runEnd(s, start, last);
            if (runEnd <= start) {
                break;
            }
            int separator = skipSpaces(s, runEnd, last);
            if (separator > last || !s.get(separator).isSeparator()) {
                break;
            }
            int next = separator;
            while (next <= last && !s.get(next).isContent()) {
                next++;
            }
            if (next > last || isClauseWord(s.get(next), from)) {
                break;
            }
            String value = this.phrasebook.interjection(Phrasebook.key(s, start, runEnd), from);
            if (value == null) {
                break;
            }
            String source = ChatText.join(s, start, runEnd);
            out.add(new Piece(Via.PHRASEBOOK, source, ChatText.applyCase(source, value)));
            out.add(keep(ChatText.join(s, runEnd, next)));
            start = next;
        }

        // Обращения в конце: «..., спасибо», «..., бро».
        int end = last + 1;
        List<Piece> trailing = new ArrayList<>();
        while (true) {
            int runStart = runStart(s, end, start);
            if (runStart < 0) {
                break;
            }
            int separator = runStart - 1;
            while (separator >= start && s.get(separator).kind == ChatText.Kind.SPACE) {
                separator--;
            }
            if (separator < start || !s.get(separator).isSeparator()) {
                break;
            }
            int previous = separator;
            while (previous >= start && !s.get(previous).isContent()) {
                previous--;
            }
            if (previous < start) {
                break;
            }
            String value = this.phrasebook.interjection(Phrasebook.key(s, runStart, end), from);
            if (value == null) {
                break;
            }
            String source = ChatText.join(s, runStart, end);
            trailing.add(0, new Piece(Via.PHRASEBOOK, source, ChatText.applyCase(source, value)));
            trailing.add(0, keep(ChatText.join(s, previous + 1, runStart)));
            end = previous + 1;
        }

        // Знаки в конце уходят в сервис вместе с предложением: без «?» вопрос
        // переведётся как утверждение. Пробелы после предложения остаются как есть.
        String sentenceEnd = trailing.isEmpty() ? tail.stripTrailing() : "";
        Piece core = this.core(s, start, end, from, start != first || end != last + 1, sentenceEnd);
        if (core.via == Via.REMOTE && trailing.isEmpty()) {
            out.add(new Piece(Via.REMOTE, core.source + sentenceEnd, null));
            out.add(keep(tail.substring(sentenceEnd.length())));
            return;
        }
        out.add(core);
        out.addAll(trailing);
        out.add(keep(tail));
    }

    /**
     * Середина предложения без обращений: разговорник, словарь или сервис.
     *
     * @param sentenceEnd знаки после неё, с которыми она ушла бы в сервис (и лежит в памяти)
     */
    private Piece core(List<ChatText.Token> s, int start, int end, Lang from, boolean trimmed, String sentenceEnd) {
        String source = ChatText.join(s, start, end);
        if (trimmed) {
            String phrase = this.phrase(s, start, end, from);
            if (phrase != null) {
                return new Piece(Via.PHRASEBOOK, source, phrase);
            }
        }
        int words = 0;
        for (int i = start; i < end; i++) {
            if (s.get(i).script() == from) {
                words++;
            }
        }
        if (words == 0) {
            // Переводить нечего: ник, «ok», число или слова уже на нужном языке.
            return keep(source);
        }
        boolean remembered = words > 1
                && (this.remembered.test(source) || this.remembered.test(source + sentenceEnd));
        if (words <= this.maxWords && !remembered) {
            StringBuilder translation = new StringBuilder();
            boolean complete = true;
            for (int i = start; i < end && complete; i++) {
                ChatText.Token token = s.get(i);
                if (token.script() != from) {
                    translation.append(token.text);
                    continue;
                }
                String word = this.word(token.text, from);
                if (word == null) {
                    complete = false;
                } else {
                    // «I» пишется с большой буквы всегда: «я» от этого заглавной не становится.
                    boolean keepCase = from == Lang.EN && token.text.equals("I");
                    translation.append(keepCase ? word : ChatText.applyCase(token.text, word));
                }
            }
            if (complete) {
                return new Piece(Via.DICTIONARY, source, translation.toString());
            }
        }
        return new Piece(Via.REMOTE, source, null);
    }

    private String word(String word, Lang from) {
        String key = ChatText.normalizeWord(word);
        String translation = this.phrasebook.word(key, from);
        return translation != null ? translation : this.dictionary.lookup(key, from);
    }

    /** Перевод из разговорника для кусочков [start, end) целиком, с регистром как в исходнике. */
    private String phrase(List<ChatText.Token> s, int start, int end, Lang from) {
        for (int i = start; i < end; i++) {
            if (s.get(i).kind == ChatText.Kind.LINK) {
                return null;
            }
        }
        String value = this.phrasebook.exact(Phrasebook.key(s, start, end), from);
        return value == null ? null : ChatText.applyCase(ChatText.join(s, start, end), value);
    }

    private static boolean isClauseWord(ChatText.Token token, Lang from) {
        if (token.kind != ChatText.Kind.WORD) {
            return false;
        }
        String word = ChatText.normalizeWord(token.text);
        return from == Lang.RU ? RU_CLAUSE_WORDS.contains(word) : EN_CLAUSE_WORDS.contains(word);
    }

    /** Конец цепочки слов через одиночные пробелы, которая начинается в {@code start}. */
    private static int runEnd(List<ChatText.Token> s, int start, int last) {
        if (s.get(start).kind != ChatText.Kind.WORD) {
            return start;
        }
        int end = start + 1;
        while (end + 1 <= last && isPlainSpace(s.get(end)) && s.get(end + 1).kind == ChatText.Kind.WORD) {
            end += 2;
        }
        return end;
    }

    /** Начало цепочки слов, которая заканчивается перед {@code end}, или -1. */
    private static int runStart(List<ChatText.Token> s, int end, int first) {
        int start = end - 1;
        if (start < first || s.get(start).kind != ChatText.Kind.WORD) {
            return -1;
        }
        while (start - 2 >= first && isPlainSpace(s.get(start - 1)) && s.get(start - 2).kind == ChatText.Kind.WORD) {
            start -= 2;
        }
        return start;
    }

    private static boolean isPlainSpace(ChatText.Token token) {
        return token.kind == ChatText.Kind.SPACE && token.text.indexOf('\n') < 0;
    }

    private static int skipSpaces(List<ChatText.Token> s, int from, int last) {
        int index = from;
        while (index <= last && s.get(index).kind == ChatText.Kind.SPACE) {
            index++;
        }
        return index;
    }

    private static Piece keep(String text) {
        return new Piece(Via.KEEP, text, text);
    }

    /**
     * Соседние части для сервиса склеиваются в одну вместе со знаками и пробелами
     * между ними: «я пошёл. кто со мной?» — один запрос, а не два.
     */
    static List<Piece> merge(List<Piece> pieces) {
        List<Piece> result = new ArrayList<>();
        int i = 0;
        while (i < pieces.size()) {
            Piece piece = pieces.get(i);
            if (piece.source.isEmpty()) {
                i++;
                continue;
            }
            if (piece.via != Via.REMOTE) {
                result.add(piece);
                i++;
                continue;
            }
            StringBuilder source = new StringBuilder(piece.source);
            int next = i + 1;
            int lastRemote = i;
            StringBuilder pending = new StringBuilder();
            while (next < pieces.size() && (pieces.get(next).via == Via.KEEP || pieces.get(next).via == Via.REMOTE)) {
                Piece following = pieces.get(next);
                if (following.via == Via.REMOTE) {
                    source.append(pending).append(following.source);
                    pending.setLength(0);
                    lastRemote = next;
                } else {
                    pending.append(following.source);
                }
                next++;
            }
            result.add(new Piece(Via.REMOTE, source.toString(), null));
            i = lastRemote + 1;
        }
        return result;
    }
}
