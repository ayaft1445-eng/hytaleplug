package dev.hytalemodding.chattranslator.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Переводит сообщение чата: что можно — на месте (разговорник, словарь), остальное —
 * через {@link Translator} (память переводов, затем DeepL или MyMemory).
 *
 * Порядок: фраза из разговорника (сначала phrases.txt сервера, потом встроенный),
 * слово из словаря, память переводов, сервис. Если сервис не смог перевести свою
 * часть, всё сообщение уходит без перевода — как и раньше.
 */
public final class MessageTranslator {

    private final Translator translator;
    private final TranslationMemory memory;

    private volatile Phrasebook phrasebook = new Phrasebook();
    private volatile Dictionary dictionary = Dictionary.empty();
    private volatile boolean local = true;
    private volatile int maxWords = 1;

    private final AtomicLong localMessages = new AtomicLong();
    private final AtomicLong partlyLocalMessages = new AtomicLong();
    private final AtomicLong phrasebookHits = new AtomicLong();
    private final AtomicLong dictionaryHits = new AtomicLong();

    public MessageTranslator(Translator translator, TranslationMemory memory) {
        this.translator = translator;
        this.memory = memory;
    }

    /**
     * @param local    переводить ли на месте (разговорник и словарь)
     * @param maxWords сколько слов подряд можно перевести по словарю
     */
    public void configure(boolean local, int maxWords) {
        this.local = local;
        this.maxWords = maxWords;
    }

    public void usePhrasebook(Phrasebook phrasebook) {
        this.phrasebook = phrasebook;
    }

    public void useDictionary(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public Phrasebook phrasebook() {
        return this.phrasebook;
    }

    public Dictionary dictionary() {
        return this.dictionary;
    }

    public boolean isLocal() {
        return this.local;
    }

    public CompletableFuture<Translation> translate(String text, Lang from, Lang to) {
        if (!this.local) {
            return this.remote(text, from, to);
        }
        LocalTranslator planner = new LocalTranslator(this.phrasebook, this.dictionary, this.maxWords,
                phrase -> this.memory.contains(phrase, from, to));
        List<LocalTranslator.Piece> pieces = planner.plan(text, from);

        int remote = 0;
        int phrasebookPieces = 0;
        int dictionaryPieces = 0;
        for (LocalTranslator.Piece piece : pieces) {
            if (piece.via == LocalTranslator.Via.REMOTE) {
                remote++;
            } else if (piece.via == LocalTranslator.Via.PHRASEBOOK) {
                phrasebookPieces++;
            } else if (piece.via == LocalTranslator.Via.DICTIONARY) {
                dictionaryPieces++;
            }
        }
        boolean usedLocal = phrasebookPieces + dictionaryPieces > 0;
        if (remote > 0 && (usedLocal || remote > 1) && this.memory.contains(text, from, to)) {
            // Сообщение целиком уже переводилось (так память заполнялась до версии 1.2).
            return this.remote(text, from, to);
        }
        this.phrasebookHits.addAndGet(phrasebookPieces);
        this.dictionaryHits.addAndGet(dictionaryPieces);
        if (usedLocal) {
            (remote == 0 ? this.localMessages : this.partlyLocalMessages).incrementAndGet();
        }

        Set<String> sources = new LinkedHashSet<>();
        if (phrasebookPieces > 0) {
            sources.add(Translation.PHRASEBOOK);
        }
        if (dictionaryPieces > 0) {
            sources.add(Translation.DICTIONARY);
        }
        List<CompletableFuture<Translator.Result>> requests = new ArrayList<>();
        for (LocalTranslator.Piece piece : pieces) {
            requests.add(piece.via == LocalTranslator.Via.REMOTE
                    ? this.translator.translateDetailed(piece.source, from, to)
                    : null);
        }
        CompletableFuture<?>[] pending = requests.stream().filter(request -> request != null).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending).thenApply(ignored -> {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < pieces.size(); i++) {
                LocalTranslator.Piece piece = pieces.get(i);
                if (piece.via == LocalTranslator.Via.REMOTE) {
                    Translator.Result translated = requests.get(i).join();
                    result.append(translated.text());
                    sources.add(translated.source());
                } else {
                    result.append(piece.translation);
                }
            }
            return new Translation(result.toString(), new ArrayList<>(sources));
        });
    }

    private CompletableFuture<Translation> remote(String text, Lang from, Lang to) {
        return this.translator.translateDetailed(text, from, to)
                .thenApply(result -> new Translation(result.text(), List.of(result.source())));
    }

    // ---------------------------------------------------------------- счётчики

    /** Сообщений, переведённых целиком без сервиса (разговорник и словарь), с запуска сервера. */
    public long localMessages() {
        return this.localMessages.get();
    }

    /** Сообщений, где часть переведена на месте, а часть — сервисом. */
    public long partlyLocalMessages() {
        return this.partlyLocalMessages.get();
    }

    /** Сколько фраз взято из разговорника. */
    public long phrasebookHits() {
        return this.phrasebookHits.get();
    }

    /** Сколько раз помог словарь. */
    public long dictionaryHits() {
        return this.dictionaryHits.get();
    }
}
