package dev.hytalemodding.chattranslator.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Запросы к DeepL API: перевод и остаток символов на месяц.
 *
 * Ключ бесплатного тарифа заканчивается на {@code :fx} и работает только с адресом
 * {@code api-free.deepl.com}, платный — с {@code api.deepl.com}; адрес выбирается по ключу.
 */
public final class DeepLClient implements TranslationService {

    public static final String FREE_API = "https://api-free.deepl.com";
    public static final String PRO_API = "https://api.deepl.com";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final String USER_AGENT = "ChatTranslator-Hytale/1.0";

    private final String authorization;
    private final URI translateUri;
    private final URI usageUri;
    private final HttpClient http;

    public DeepLClient(String apiKey) {
        this(apiKey, baseUrlFor(apiKey), HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    DeepLClient(String apiKey, String baseUrl, HttpClient http) {
        this.authorization = "DeepL-Auth-Key " + apiKey.trim();
        this.translateUri = URI.create(baseUrl + "/v2/translate");
        this.usageUri = URI.create(baseUrl + "/v2/usage");
        this.http = http;
    }

    public static boolean isFreeKey(String apiKey) {
        return apiKey.trim().endsWith(":fx");
    }

    public static String baseUrlFor(String apiKey) {
        return isFreeKey(apiKey) ? FREE_API : PRO_API;
    }

    /** Ответ DeepL с кодом ошибки и объяснением по-русски. */
    public static final class DeepLException extends RuntimeException {

        private final int status;

        DeepLException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** HTTP-код ответа DeepL. */
        public int status() {
            return this.status;
        }
    }

    /** Остаток символов: сколько потрачено в этом месяце и сколько всего можно. */
    public static final class Usage {

        private final long used;
        private final long limit;

        Usage(long used, long limit) {
            this.used = used;
            this.limit = limit;
        }

        public long used() {
            return this.used;
        }

        public long limit() {
            return this.limit;
        }
    }

    @Override
    public CompletableFuture<String> translate(String text, Lang from, Lang to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", List.of(text));
        body.put("source_lang", from.deeplSource());
        body.put("target_lang", to.deeplTarget());
        // Не исправлять заглавные буквы и точки: в чате пишут как придётся.
        body.put("preserve_formatting", true);
        // Там, где язык различает «ты» и «вы», переводить на «ты», как принято в игровом чате.
        body.put("formality", "prefer_less");

        HttpRequest request = HttpRequest.newBuilder(this.translateUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", this.authorization)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parseTranslation(checked(response)));
    }

    public CompletableFuture<Usage> usage() {
        HttpRequest request = HttpRequest.newBuilder(this.usageUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", this.authorization)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    Map<String, Object> json = parseBody(checked(response));
                    return new Usage(Json.getLong(json, "character_count", 0), Json.getLong(json, "character_limit", 0));
                });
    }

    /** Перестаёт принимать новые запросы; начатые доходят до конца. */
    public void shutdown() {
        this.http.shutdown();
    }

    private static String checked(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        throw new DeepLException(status, explain(status));
    }

    static String explain(int status) {
        switch (status) {
            case 400:
                return "DeepL не понял запрос (код 400)";
            case 401:
            case 403:
                return "DeepL не принял ключ (код " + status + "): проверьте DeepLApiKey в config.json";
            case 413:
                return "сообщение слишком длинное для DeepL (код 413)";
            case 429:
                return "слишком много запросов подряд, DeepL просит подождать (код 429)";
            case 456:
                return "исчерпан месячный лимит символов DeepL (код 456); бесплатный тариф — 500 000 символов в месяц";
            default:
                if (status >= 500) {
                    return "DeepL временно недоступен (код " + status + ")";
                }
                return "DeepL ответил кодом " + status;
        }
    }

    private static Map<String, Object> parseBody(String body) {
        try {
            return Json.parseObject(body);
        } catch (Json.JsonException exception) {
            throw new DeepLException(200, "непонятный ответ DeepL: " + exception.getMessage());
        }
    }

    static String parseTranslation(String body) {
        Object translations = parseBody(body).get("translations");
        if (translations instanceof List && !((List<?>) translations).isEmpty()) {
            Object first = ((List<?>) translations).get(0);
            if (first instanceof Map) {
                Object text = ((Map<?, ?>) first).get("text");
                if (text instanceof String) {
                    return (String) text;
                }
            }
        }
        throw new DeepLException(200, "в ответе DeepL нет перевода");
    }
}
