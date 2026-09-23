#!/usr/bin/env python3
"""
Собирает словарь ChatTranslator из данных OpenRussian.

Источник: https://github.com/Badestrand/russian-dictionary (данные сайта
https://en.openrussian.org, лицензия CC BY-SA 4.0). Частоты слов для отбора —
библиотека wordfreq, английские формы слов (множественное число, сравнительная
степень, части речи) — библиотека lemminflect.

Результат:
  src/main/resources/dictionary/ru-en.tsv  — русская словоформа -> английский перевод
  src/main/resources/dictionary/en-ru.tsv  — английское слово -> русское слово

Словарь переводит только отдельные слова, поэтому в нём остаются лишь надёжные
пары. Всё сомнительное пропускается — такие слова переведёт сервис:
  * формы, которые совпадают у двух разных слов («стали» — «сталь» и «стать»,
    «тут» — «здесь» и «тутовое дерево»), если одно не встречается намного чаще;
  * формы глаголов, кроме неопределённой формы и повелительного наклонения
    («иду», «сделал» по одному слову переводятся плохо);
  * описания вместо переводов («genitive of I», «begin to do sth»);
  * английские служебные слова (the, is, can) и формы глаголов (found, got);
  * английские слова, которые чаще бывают глаголом, чем существительным
    (work, play, run): без контекста их не перевести одним словом.
Для игровых слов значение выбрано вручную (GLOSSARY_*): «лук» — bow, а не onion.

Запуск:
  pip install wordfreq lemminflect
  git clone --depth 1 https://github.com/Badestrand/russian-dictionary /tmp/openrussian
  python3 tools/build_dictionary.py /tmp/openrussian
"""
import csv
import os
import re
import sys
from collections import defaultdict

from lemminflect import getAllInflections, getAllLemmas
from wordfreq import zipf_frequency

# Слова реже одного раза на миллион в словарь не попадают: в чате они почти не встречаются.
MIN_ZIPF_RU = 3.0
MIN_ZIPF_EN = 2.5

# Если форма есть у двух слов, берётся более частое, только если оно встречается
# чаще на MARGIN_ZIPF единиц шкалы Zipf (1.0 — в 10 раз чаще). Иначе форма пропускается.
MARGIN_ZIPF = 1.0

# Английское слово, которое переводится русским глаголом не реже, чем
# существительным, в английско-русский словарь не попадает (work, play, run).
VERB_MARGIN = 0.5

# Штраф за второй синоним первого значения и за следующие значения.
SYNONYM_PENALTY = 1.2
# Штраф, если часть речи не та: city -> «городской» вместо «город».
POS_PENALTY = 1.5
# Штраф русскому слову, у которого есть омоним с другим значением («замок»).
HOMOGRAPH_PENALTY = 1.5

# Формы, которые переводятся по словарю. Остальные формы глаголов нужны только
# для того, чтобы найти совпадения с формами других слов.
TRANSLATED_FORMS = {
    "nouns": ["sg_nom", "sg_gen", "sg_dat", "sg_acc", "sg_inst", "sg_prep",
              "pl_nom", "pl_gen", "pl_dat", "pl_acc", "pl_inst", "pl_prep"],
    "verbs": ["imperative_sg", "imperative_pl"],
    "adjectives": ["short_m", "short_f", "short_n", "short_pl"]
                  + [f"decl_{g}_{c}" for g in ("m", "f", "n", "pl")
                     for c in ("nom", "gen", "dat", "acc", "inst", "prep")],
    "others": [],
}
SHADOW_FORMS = {
    "nouns": [],
    "verbs": ["past_m", "past_f", "past_n", "past_pl",
              "presfut_sg1", "presfut_sg2", "presfut_sg3",
              "presfut_pl1", "presfut_pl2", "presfut_pl3"],
    "adjectives": ["superlative"],
    "others": [],
}

# Формы, которых нет в данных OpenRussian: у «год» там только «лета, лет».
EXTRA_PLURALS = {
    "год": ["годы", "годов", "годам", "годами", "годах"],
}

# Игровые значения слов. Русское слово (словарная форма) -> английский перевод;
# все формы слова переводятся так же, множественное число — во множественном.
GLOSSARY_RU = {
    "лук": "bow", "кирка": "pickaxe", "печь": "furnace", "броня": "armor",
    "сундук": "chest", "дерево": "wood", "доска": "plank", "предмет": "item",
    "урон": "damage", "арбалет": "crossbow", "курица": "chicken", "игра": "game",
    "крутой": "cool", "классный": "cool", "отличный": "excellent", "база": "base",
    "шахта": "mine", "ключ": "key", "команда": "team", "уровень": "level",
    "опыт": "experience", "сила": "strength", "здоровье": "health", "зелье": "potion",
    "стрела": "arrow", "факел": "torch", "железо": "iron", "золото": "gold",
    "земля": "ground", "мясо": "meat", "хлеб": "bread", "камень": "stone",
    "щит": "shield", "шлем": "helmet", "посох": "staff", "лава": "lava",
    "игрок": "player", "ресурс": "resource", "торговец": "trader", "житель": "villager",
}

# Английское слово -> русское (словарная форма; множественное число берётся из данных).
GLOSSARY_EN = {
    "bow": "лук", "pickaxe": "кирка", "furnace": "печь", "armor": "броня", "armour": "броня",
    "chest": "сундук", "wood": "дерево", "plank": "доска", "item": "предмет",
    "damage": "урон", "crossbow": "арбалет", "chicken": "курица", "head": "голова",
    "base": "база", "party": "группа", "area": "область", "guy": "парень",
    "villager": "житель", "trader": "торговец", "merchant": "торговец",
    "key": "ключ", "level": "уровень", "health": "здоровье", "potion": "зелье",
    "cave": "пещера", "village": "деревня", "ore": "руда", "coal": "уголь",
    "iron": "железо", "gold": "золото", "diamond": "алмаз", "stone": "камень",
    "torch": "факел", "arrow": "стрела", "sword": "меч", "axe": "топор",
    "shield": "щит", "helmet": "шлем", "boss": "босс", "enemy": "враг",
    "friend": "друг", "player": "игрок", "server": "сервер", "game": "игра",
    "world": "мир", "map": "карта", "house": "дом", "home": "дом",
    "north": "север", "south": "юг", "east": "восток", "west": "запад",
    "board": "доска", "land": "земля", "perhaps": "возможно", "super": "супер",
    "crazy": "сумасшедший", "forward": "вперёд", "race": "раса", "size": "размер",
    "staff": "посох", "hit": "удар", "stuff": "вещи", "round": "раунд",
    "source": "источник", "blue": "синий", "kind": "добрый", "general": "общий",
    "single": "одиночный", "others": "другие", "everyone": "все", "everybody": "все",
    "true": "правда", "current": "текущий", "figure": "фигура",
    "great": "отлично", "always": "всегда", "everything": "всё", "nothing": "ничего",
    "hard": "сложно", "sure": "конечно", "fine": "хорошо", "english": "английский",
    "test": "тест", "code": "код", "card": "карта", "report": "отчёт", "city": "город",
    "town": "город", "data": "данные",
}

# Английские служебные слова: по словарю они переводятся бессмысленно
# (the -> «тем», can -> «банка»). Частые из них есть в разговорнике.
EN_STOPLIST = set("""
a an the this that these those some any every each either neither both such
i me my mine myself you your yours yourself yourselves he him his himself she her hers herself
it its itself we us our ours ourselves they them their theirs themselves one ones
who whom whose which what whatever whoever where when why how whether
be am is are was were been being have has had having do does did done doing
will would shall should can could may might must ought need dare let
to of in on at by for with from into onto upon about above below over under between among
through during before after since until till against without within along across behind
beyond near off out up down around via per than as like
and or but nor so yet if unless because although though while whereas
not no yes nope yeah yep ok okay oh ah eh um uh hmm hey hi hello bye please thanks
there here then now just also too very only even still already again ever never
more most less least much many few little lot lots own same other another else
don't doesn't didn't can't cannot couldn't won't wouldn't shouldn't isn't aren't wasn't weren't
haven't hasn't hadn't i'm i'll i've i'd you're you'll you've you'd he's she's it's we're we'll
we've they're they'll they've that's there's what's who's let's
several due able worth miss matter fucking fuckin re mi fa la si ti sol st nd rd th vs ie eg
once coming bit towards toward stay wait come go run help stop follow look see take give get
make move jump fight attack kill die build craft dig trade buy sell find try use eat drink sleep
play work want know think say tell ask call leave start finish open close turn hold keep put set
bring show check watch listen hear feel meet needs wants
""".split())

# Русские предлоги и частицы: отдельным сообщением они бессмысленны, а перевод
# в данных выбран для одного из многих значений («у» -> «by»).
RU_STOPLIST = set("""
у к ко о об обо по за из изо от ото до с со в во на над надо под подо при про для без безо
через между перед передо около ли же бы б ведь вот вон то де мол ж ль пусть равно
сих нем есть
""".split())

# Слова, у которых в данных вместо перевода перечень («сам» -> ourselves, yourself, ...).
RU_LEMMA_STOPLIST = {"сам", "себя", "свой", "самый"}

IRREGULAR_PLURALS = {
    "person": "people", "fish": "fish", "sheep": "sheep", "deer": "deer",
    "money": "money", "news": "news", "information": "information",
    "advice": "advice", "furniture": "furniture", "equipment": "equipment",
    "armor": "armor", "armour": "armour", "damage": "damage", "health": "health",
    "experience": "experience", "strength": "strength", "meat": "meat",
    "bread": "bread", "lava": "lava", "iron": "iron", "gold": "gold", "wood": "wood",
    "stone": "stones", "ground": "ground",
}

ENGLISH = re.compile(r"^[A-Za-z][A-Za-z' -]*[A-Za-z]$|^[A-Za-z]$")

# Описания вместо переводов: «genitive of I», «begin to do sth».
PLACEHOLDERS = re.compile(
    r"\b(sth|smth|smb|sb|e\.g|etc|genitive|genetive|dative|accusative|instrumental|"
    r"prepositional|nominative|plural of|form of|diminutive|past tense|abbr|adjective of|"
    r"adverb of|noun of|pejorative)\b",
    re.IGNORECASE)

TRAILING_PREPOSITION = re.compile(r" (of|to|with|for|at|on|in|from|about)$")


def norm_ru(text):
    return text.strip().replace("'", "").replace("ё", "е").replace("Ё", "Е").lower()


def plain_ru(text):
    """Словарная форма без знаков ударения, «ё» сохраняется."""
    return text.strip().replace("'", "")


def clean_synonym(text, category):
    if PLACEHOLDERS.search(text):
        return ""
    text = re.sub(r"\([^)]*\)", " ", text)
    text = text.split("/")[0]
    text = re.sub(r"\s+", " ", text).strip(" ,.;:!?-")
    prefixes = ["it is ", "it's "]
    if category == "verbs":
        prefixes.append("to ")
    if category == "nouns":
        prefixes += ["a ", "an ", "the "]
    if category in ("adjectives", "others"):
        prefixes += ["is ", "be "]
    for prefix in prefixes:
        if text.lower().startswith(prefix):
            text = text[len(prefix):]
    if category == "adjectives":
        text = TRAILING_PREPOSITION.sub("", text)
    return text.strip()


def senses(translations, category):
    """[[синоним, ...], ...] — значения через «;», синонимы через «,»."""
    result = []
    for sense in (translations or "").split(";"):
        synonyms = []
        for synonym in sense.split(","):
            cleaned = clean_synonym(synonym, category)
            if cleaned and len(cleaned) <= 30 and ENGLISH.match(cleaned):
                synonyms.append(cleaned)
        if synonyms:
            result.append(synonyms)
    return result


def keep_case(source, result):
    """Russia -> Russians: заглавная буква сохраняется."""
    return result[:1].upper() + result[1:] if source[:1].isupper() else result


def plural_en(word):
    """Множественное число английского существительного или None."""
    if " " in word or not word.isalpha():
        return None
    lower = word.lower()
    if lower in IRREGULAR_PLURALS:
        return keep_case(word, IRREGULAR_PLURALS[lower])
    for form in getAllInflections(lower, upos="NOUN").get("NNS", ()):
        if form != lower:
            return keep_case(word, form)
    if re.search(r"(s|x|z|ch|sh)$", lower):
        return keep_case(word, lower + "es")
    if re.search(r"[^aeiou]y$", lower):
        return keep_case(word, lower[:-1] + "ies")
    return keep_case(word, lower + "s")


def comparative_en(word):
    """Сравнительная степень английского прилагательного: better, faster, more beautiful."""
    if " " in word or not word.isalpha():
        return None
    known = getAllInflections(word.lower(), upos="ADJ").get("JJR", ())
    return known[0] if known else "more " + word


# Заглавная буква в данных бывает случайной («наверное» -> Probably). Английские
# слова, которые lemminflect знает как обычные (не имена собственные), пишутся со строчной.
KEEP_CAPITAL = {"i", "god", "lord", "catholic", "christian",
                "january", "february", "march", "april", "may", "june", "july", "august",
                "september", "october", "november", "december",
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"}
FORCE_LOWER = {"oh"}


def fix_case(russian, english):
    if not english[:1].isupper() or russian[:1].isupper():
        return english
    first = english.split(" ")[0].lower()
    if first in KEEP_CAPITAL:
        return english
    if first in FORCE_LOWER or getAllLemmas(first):
        return english[:1].lower() + english[1:]
    return english


def english_pos(word):
    return set(getAllLemmas(word).keys())


def is_verb_form(word):
    """Форма другого английского глагола: found (find), got (get), making (make)."""
    return any(lemma != word for lemma in getAllLemmas(word).get("VERB", ()))


def forms_of(row, columns):
    """(форма, это множественное число?) для перечисленных колонок."""
    result = []
    for column in columns:
        for form in re.split(r"[,;]", row.get(column) or ""):
            form = norm_ru(form)
            if form:
                result.append((form, column.startswith("pl_") or column.endswith("_pl")))
    return result


class Lemma:
    def __init__(self, index, category, bare, zipf, meanings, row):
        self.index = index
        self.category = category
        self.bare = bare
        self.zipf = zipf
        self.meanings = meanings
        self.row = row
        self.glossary = False

    @property
    def primary(self):
        if not self.meanings:
            return None
        return fix_case(self.bare, self.meanings[0][0])

    def plural_ru(self):
        extra = EXTRA_PLURALS.get(self.bare)
        if extra:
            return extra[0]
        first = re.split(r"[,;]", self.row.get("pl_nom") or "")[0]
        return plain_ru(first) if first.strip() else None


def load(source):
    lemmas = []
    # форма -> {номер слова: [переводится?, множественное число?, сравнительная степень?]}
    forms = defaultdict(dict)
    for category in ("others", "verbs", "adjectives", "nouns"):
        with open(os.path.join(source, category + ".csv"), encoding="utf-8") as handle:
            for row in csv.DictReader(handle, delimiter="\t"):
                bare = plain_ru(row.get("bare") or "")
                if not bare or " " in bare:
                    continue
                meanings = senses(row.get("translations_en"), category)
                if bare in RU_LEMMA_STOPLIST:
                    meanings = []
                lemma = Lemma(len(lemmas), category, bare, 0.0, meanings, row)
                if category in ("nouns", "adjectives") and bare in GLOSSARY_RU:
                    lemma.meanings = [[GLOSSARY_RU[bare]]] + meanings
                    lemma.glossary = True
                lemmas.append(lemma)

                slot = forms[norm_ru(bare)].setdefault(lemma.index, [False, False, False])
                slot[0] = True
                translated = forms_of(row, TRANSLATED_FORMS[category])
                translated += [(form, True) for form in EXTRA_PLURALS.get(bare, [])]
                for form, plural in translated:
                    slot = forms[form].setdefault(lemma.index, [False, True, False])
                    slot[0] = True
                    # Одна форма бывает и единственным, и множественным числом («дела»):
                    # тогда переводится как единственное.
                    slot[1] = slot[1] and plural
                if category == "adjectives":
                    for form, _ in forms_of(row, ["comparative"]):
                        forms[form].setdefault(lemma.index, [True, False, True])
                for form, _ in forms_of(row, SHADOW_FORMS[category]):
                    forms[form].setdefault(lemma.index, [False, False, False])
    estimate_frequencies(lemmas, forms)
    return lemmas, forms


ZIPF_CACHE = {}


def zipf_ru(form):
    value = ZIPF_CACHE.get(form)
    if value is None:
        value = ZIPF_CACHE[form] = zipf_frequency(form, "ru")
    return value


def estimate_frequencies(lemmas, forms):
    """
    Частота слова — по самой частой из его форм, которые не совпадают с формами
    других слов. Частота одной словарной формы обманчива: у «смог» (дым) она
    складывается из «смог» (от «смочь»), а «стать» пишут реже, чем «стал».
    """
    own = defaultdict(list)
    for form, owners in forms.items():
        if len(owners) == 1:
            own[next(iter(owners))].append(form)
    for lemma in lemmas:
        unique = own.get(lemma.index)
        lemma.zipf = max(zipf_ru(form) for form in unique) if unique else zipf_ru(norm_ru(lemma.bare))


def english_for(lemma, flags):
    translated, plural, comparative = flags
    if not translated or not lemma.primary:
        return None
    english = lemma.primary
    if plural and lemma.category == "nouns":
        english = plural_en(english) or english
    if comparative:
        english = comparative_en(english)
    return english


def build_ru_en(lemmas, forms):
    ru_en = {}
    skipped = 0
    for form, owners in forms.items():
        if form in RU_STOPLIST:
            continue
        # Слово из игрового словаря важнее омонимов: «печь» — furnace, а не bake.
        ranked = sorted(owners.items(), key=lambda item: (not lemmas[item[0]].glossary, -lemmas[item[0]].zipf))
        best = lemmas[ranked[0][0]]
        # Наречие важнее совпадающей краткой формы прилагательного:
        # «долго» — a long time (а не «долгий»), «меньше» — less (а не smaller).
        adverbs = [index for index, flags in ranked
                   if lemmas[index].category == "others" and norm_ru(lemmas[index].bare) == form]
        adverb = adverbs[0] if len(adverbs) == 1 else None
        if (adverb is not None and not best.glossary and lemmas[adverb].primary
                and all(lemmas[index].category in ("others", "adjectives") for index, _ in ranked)):
            if lemmas[adverb].zipf >= MIN_ZIPF_RU:
                ru_en[form] = lemmas[adverb].primary
            continue
        english = english_for(best, ranked[0][1])
        if not english or best.zipf < MIN_ZIPF_RU:
            continue
        ambiguous = False
        if not best.glossary:
            for other_index, flags in ranked[1:]:
                other = lemmas[other_index]
                if other.zipf < best.zipf - MARGIN_ZIPF:
                    break
                other_english = english_for(other, flags)
                if not other_english or other_english.lower() != english.lower():
                    ambiguous = True
                    break
        if ambiguous:
            skipped += 1
            continue
        ru_en[form] = english
    return ru_en, skipped


def build_en_ru(lemmas):
    by_bare = defaultdict(list)
    for lemma in lemmas:
        by_bare[lemma.bare].append(lemma)

    verb_score = {}
    other_score = {}
    candidates = {}   # английское слово -> (оценка, русское слово, английская словарная форма)

    def offer(english, score, russian, base):
        previous = candidates.get(english)
        if previous is None or score > previous[0]:
            candidates[english] = (score, russian, base)

    for lemma in lemmas:
        if lemma.zipf < MIN_ZIPF_RU:
            continue
        homographs = [other for other in by_bare[lemma.bare]
                      if other is not lemma and other.primary and other.primary != lemma.primary]
        for sense_index, synonyms in enumerate(lemma.meanings):
            for synonym_index, synonym in enumerate(synonyms):
                english = synonym.lower()
                if " " in english:
                    continue
                penalty = 0 if (sense_index, synonym_index) == (0, 0) else (1 if sense_index == 0 else 2)
                score = lemma.zipf - SYNONYM_PENALTY * penalty
                if lemma.category == "verbs":
                    verb_score[english] = max(verb_score.get(english, -99), score)
                    continue
                other_score[english] = max(other_score.get(english, -99), score)
                if (len(english) < 2 or english in EN_STOPLIST
                        or zipf_frequency(english, "en") < MIN_ZIPF_EN):
                    continue
                pos = english_pos(english)
                if is_verb_form(english):
                    # «building», «saw», «rose» — и форма глагола, и своё существительное:
                    # остаются, только если это первый перевод русского существительного.
                    if penalty > 0 or lemma.category != "nouns" or english not in getAllLemmas(english).get("NOUN", ()):
                        continue
                if pos and lemma.category == "nouns" and "NOUN" not in pos:
                    score -= POS_PENALTY
                if pos and lemma.category == "adjectives" and "ADJ" not in pos:
                    score -= POS_PENALTY
                if homographs:
                    score -= HOMOGRAPH_PENALTY
                offer(english, score, lemma.bare, english)
                if lemma.category == "nouns":
                    plural_ru = lemma.plural_ru()
                    plural = plural_en(english)
                    if plural_ru and plural and plural != english and plural not in EN_STOPLIST:
                        offer(plural, score, plural_ru, english)

    en_ru = {}
    for english, (score, russian, base) in candidates.items():
        # wants, needs, works: множественное число, но чаще — форма глагола.
        if verb_score.get(base, -99) >= other_score.get(base, -99) - VERB_MARGIN:
            continue
        en_ru[english] = russian

    # Игровые значения — поверх найденных.
    nouns = {lemma.bare: lemma for lemma in lemmas if lemma.category == "nouns"}
    for english, russian in GLOSSARY_EN.items():
        en_ru[english] = russian
        lemma = nouns.get(russian)
        plural = plural_en(english)
        if lemma and plural and plural != english and lemma.plural_ru():
            en_ru[plural] = lemma.plural_ru()
    return en_ru


HEADER = [
    "# Словарь ChatTranslator. Данные: OpenRussian.org (https://en.openrussian.org),",
    "# https://github.com/Badestrand/russian-dictionary — лицензия CC BY-SA 4.0",
    "# (https://creativecommons.org/licenses/by-sa/4.0/). Изменения: отобраны частые",
    "# слова (по wordfreq), оставлен один перевод, убраны ударения и неоднозначные",
    "# формы, для игровых слов выбрано игровое значение. Этот файл распространяется",
    "# на тех же условиях CC BY-SA 4.0. Собран скриптом tools/build_dictionary.py.",
]


def write(path, mapping):
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(HEADER) + "\n")
        for key in sorted(mapping):
            out.write(f"{key}\t{mapping[key]}\n")


def main():
    source = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "src", "main", "resources", "dictionary")
    os.makedirs(target, exist_ok=True)
    csv.field_size_limit(10 ** 9)

    lemmas, forms = load(source)
    ru_en, skipped = build_ru_en(lemmas, forms)
    en_ru = build_en_ru(lemmas)
    write(os.path.join(target, "ru-en.tsv"), ru_en)
    write(os.path.join(target, "en-ru.tsv"), en_ru)
    print(f"слов в данных: {len(lemmas)}, ru-en: {len(ru_en)} (неоднозначных форм пропущено: {skipped}), "
          f"en-ru: {len(en_ru)}")


if __name__ == "__main__":
    main()
