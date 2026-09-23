package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор сообщения чата: слова, числа, пробелы, знаки препинания, смайлы и ссылки,
 * а также деление на предложения. Из кусочков можно собрать исходный текст без
 * потерь — перевод меняет только слова, остальное остаётся как написал игрок.
 */
final class ChatText {

    private ChatText() {
    }

    enum Kind {
        /** Слово: буквы и цифры, внутри — дефис или апостроф («кто-то», «don't»). */
        WORD,
        /** Число: «5», «3.5», «10:30». */
        NUMBER,
        SPACE,
        /** Знаки препинания и прочие символы. */
        PUNCT,
        /** Смайл: «:)», «xD», «<3». */
        EMOTICON,
        /** Ссылка или упоминание через @: не переводится. */
        LINK
    }

    static final class Token {

        final Kind kind;
        final String text;

        Token(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        boolean isContent() {
            return this.kind == Kind.WORD || this.kind == Kind.NUMBER || this.kind == Kind.LINK;
        }

        /** Язык слова по буквам: кириллица — RU, латиница — EN, иначе {@code null}. */
        Lang script() {
            if (this.kind != Kind.WORD) {
                return null;
            }
            boolean latin = false;
            for (int i = 0; i < this.text.length(); i++) {
                char c = this.text.charAt(i);
                if (!Character.isLetter(c)) {
                    continue;
                }
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script == Character.UnicodeScript.CYRILLIC) {
                    return Lang.RU;
                }
                if (script == Character.UnicodeScript.LATIN) {
                    latin = true;
                }
            }
            return latin ? Lang.EN : null;
        }

        /** Разделяет ли знак части предложения: запятая, точка, тире, смайл. */
        boolean isSeparator() {
            if (this.kind == Kind.EMOTICON) {
                return true;
            }
            if (this.kind != Kind.PUNCT) {
                return false;
            }
            for (int i = 0; i < this.text.length(); i++) {
                if (",.!?…:;-—–)(".indexOf(this.text.charAt(i)) >= 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return this.kind + "(" + this.text + ")";
        }
    }

    // ---------------------------------------------------------------- разбор на кусочки

    /** Смайлы, которые не должны считаться словами или знаками внутри предложения. */
    private static final String[] EMOTICONS = {
            ":-)", ":-(", ":-D", ":-P", ":-p", ";-)", ":'(", ":)", ":(", ":D", ":P", ":p", ":O", ":o",
            ":3", ":*", ":/", ":|", ";)", ";D", "=)", "=(", "=D", "<3", "^_^", "^^", "-_-", "o_O", "O_o",
            "T_T", "xD", "XD", "xd", "Xd", "хД", "ХД", "хд", "Хд"
    };

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            int end;
            Kind kind;
            if (Character.isWhitespace(c)) {
                end = i + 1;
                while (end < length && Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                kind = Kind.SPACE;
            } else if ((end = linkEnd(text, i)) > i) {
                kind = Kind.LINK;
            } else if ((end = emoticonEnd(text, i)) > i) {
                kind = Kind.EMOTICON;
            } else if (Character.isLetterOrDigit(c)) {
                end = wordEnd(text, i);
                kind = Kind.NUMBER;
                for (int j = i; j < end; j++) {
                    if (Character.isLetter(text.charAt(j))) {
                        kind = Kind.WORD;
                        break;
                    }
                }
            } else {
                end = i + 1;
                while (end < length) {
                    char next = text.charAt(end);
                    if (Character.isWhitespace(next) || Character.isLetterOrDigit(next)
                            || emoticonEnd(text, end) > end || linkEnd(text, end) > end) {
                        break;
                    }
                    end++;
                }
                kind = Kind.PUNCT;
            }
            tokens.add(new Token(kind, text.substring(i, end)));
            i = end;
        }
        return tokens;
    }

    /** Конец слова или числа, начатого в {@code start}. */
    private static int wordEnd(String text, int start) {
        int length = text.length();
        int end = start;
        while (end < length) {
            char c = text.charAt(end);
            if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK) {
                end++;
                continue;
            }
            // Дефис и апостроф внутри слова: «кто-то», «don't»; точка и двоеточие внутри числа: «3.5», «10:30».
            boolean inner = end + 1 < length && Character.isLetterOrDigit(text.charAt(end + 1)) && end > start;
            if (inner && (c == '-' || c == '\'' || c == '’')) {
                end++;
                continue;
            }
            if (inner && (c == '.' || c == ',' || c == ':')
                    && Character.isDigit(text.charAt(end - 1)) && Character.isDigit(text.charAt(end + 1))) {
                end++;
                continue;
            }
            break;
        }
        return end;
    }

    private static boolean boundaryBefore(String text, int index) {
        return index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
    }

    private static boolean boundaryAfter(String text, int index) {
        return index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
    }

    /** Конец смайла, который начинается в {@code start}, или {@code start}, если смайла нет. */
    private static int emoticonEnd(String text, int start) {
        if (!boundaryBefore(text, start)) {
            return start;
        }
        for (String emoticon : EMOTICONS) {
            if (text.startsWith(emoticon, start)) {
                int end = start + emoticon.length();
                // «:DDD», «:)))» — повтор последнего символа тоже часть смайла.
                char last = emoticon.charAt(emoticon.length() - 1);
                while (end < text.length() && text.charAt(end) == last && !Character.isLetter(last)) {
                    end++;
                }
                if (Character.isLetter(last)) {
                    while (end < text.length() && Character.toLowerCase(text.charAt(end)) == Character.toLowerCase(last)) {
                        end++;
                    }
                }
                if (boundaryAfter(text, end)) {
                    return end;
                }
            }
        }
        return start;
    }

    /** Конец ссылки или упоминания, которое начинается в {@code start}, или {@code start}. */
    private static int linkEnd(String text, int start) {
        if (!boundaryBefore(text, start)) {
            return start;
        }
        String rest = text.substring(start, Math.min(text.length(), start + 8)).toLowerCase(Locale.ROOT);
        boolean link = rest.startsWith("http://") || rest.startsWith("https://") || rest.startsWith("www.");
        boolean mention = text.charAt(start) == '@' && start + 1 < text.length()
                && Character.isLetterOrDigit(text.charAt(start + 1));
        if (!link && !mention) {
            return start;
        }
        int end = start + 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        // Точка или запятая в конце — уже не часть ссылки: «зайди на www.site.com.»
        while (end > start + 1 && ".,!?;:)".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return end;
    }

    // ---------------------------------------------------------------- предложения

    /**
     * Делит кусочки на предложения. Предложение заканчивается на «.», «!», «?», «…»,
     * на смайле или на скобке-смайле «)», если за ними пробел или конец сообщения,
     * а также на переводе строки. Пробелы после конца предложения входят в него.
     */
    static List<List<Token>> sentences(List<Token> tokens) {
        List<List<Token>> sentences = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        boolean openBracket = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            current.add(token);
            if (token.kind == Kind.PUNCT && token.text.indexOf('(') >= 0 && token.text.indexOf(')') < 0
                    && i + 1 < tokens.size() && tokens.get(i + 1).kind != Kind.SPACE) {
                openBracket = true;
            }
            boolean nextIsGap = i + 1 >= tokens.size() || tokens.get(i + 1).kind == Kind.SPACE;
            boolean ends;
            if (token.kind == Kind.SPACE) {
                ends = token.text.indexOf('\n') >= 0;
            } else if (token.kind == Kind.EMOTICON) {
                ends = nextIsGap;
            } else if (token.kind == Kind.PUNCT) {
                ends = nextIsGap && (endsSentence(token.text) || isBracketSmiley(token.text, openBracket));
                if (token.text.indexOf(')') >= 0) {
                    openBracket = false;
                }
            } else {
                ends = false;
            }
            if (ends) {
                // Пробелы после конца предложения — его часть.
                while (i + 1 < tokens.size() && tokens.get(i + 1).kind == Kind.SPACE && token.kind != Kind.SPACE) {
                    current.add(tokens.get(++i));
                }
                sentences.add(current);
                current = new ArrayList<>();
                openBracket = false;
            }
        }
        if (!current.isEmpty()) {
            sentences.add(current);
        }
        return sentences;
    }

    private static boolean endsSentence(String punct) {
        for (int i = 0; i < punct.length(); i++) {
            if (".!?…".indexOf(punct.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** «привет)» и «ура)))» — смайл, а не закрывающая скобка после «(». */
    private static boolean isBracketSmiley(String punct, boolean openBracket) {
        boolean onlyBrackets = !punct.isEmpty();
        for (int i = 0; i < punct.length(); i++) {
            if (punct.charAt(i) != ')' && punct.charAt(i) != '(') {
                onlyBrackets = false;
            }
        }
        if (!onlyBrackets) {
            return false;
        }
        return punct.length() >= 2 || !openBracket;
    }

    static String join(List<Token> tokens, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            text.append(tokens.get(i).text);
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- сравнение и регистр

    /** Слово для поиска в словаре и разговорнике: строчные буквы, «е» вместо «ё», прямой апостроф. */
    static String normalizeWord(String word) {
        return word.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('’', '\'')
                .replace('‘', '\'');
    }

    /**
     * Регистр перевода по образцу исходного текста: «Привет» -> «Hi», «ПРИВЕТ» -> «HI».
     * Строчными буквы перевода не становятся никогда: «pvp» -> «PvP» остаётся «PvP».
     */
    static String applyCase(String source, String translation) {
        int letters = 0;
        int upper = 0;
        char first = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetter(c)) {
                if (letters == 0) {
                    first = c;
                }
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (letters >= 2 && upper == letters) {
            return translation.toUpperCase(Locale.ROOT);
        }
        if (letters > 0 && Character.isUpperCase(first) && !translation.isEmpty()) {
            int firstLetter = 0;
            while (firstLetter < translation.length() && !Character.isLetter(translation.charAt(firstLetter))) {
                firstLetter++;
            }
            if (firstLetter < translation.length()) {
                return translation.substring(0, firstLetter)
                        + Character.toUpperCase(translation.charAt(firstLetter))
                        + translation.substring(firstLetter + 1);
            }
        }
        return translation;
    }
}
