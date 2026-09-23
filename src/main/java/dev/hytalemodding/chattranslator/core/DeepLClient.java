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

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    static final String USER_AGENT = "ChatTranslator-Hytale/1.1";

    private final String authorization;
    private final URI translateUri;
    private final URI usageUri;
    private final HttpClient http;

    public DeepLClient(String apiKey, HttpClient http) {
        this(apiKey, baseUrlFor(apiKey), http);
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

    @Override
    public String name() {
        return "DeepL";
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

    private static String checked(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        throw error(status);
    }

    static ServiceException error(int status) {
        switch (status) {
            case 401:
            case 403:
                return new ServiceException(ServiceException.Kind.KEY_REJECTED, status,
                        "DeepL не принял ключ (код " + status + "): проверьте DeepLApiKey в config.json");
            case 451:
                return new ServiceException(ServiceException.Kind.REGION_BLOCKED, status,
                        "DeepL отказал с кодом 451: он не обслуживает страну, где стоит сервер");
            case 456:
                return new ServiceException(ServiceException.Kind.QUOTA, status,
                        "исчерпан месячный лимит символов DeepL (код 456); бесплатный тариф — 500 000 символов в месяц");
            case 429:
                return new ServiceException(ServiceException.Kind.TOO_MANY_REQUESTS, status,
                        "слишком много запросов подряд, DeepL просит подождать (код 429)");
            case 400:
                return new ServiceException(ServiceException.Kind.OTHER, status, "DeepL не понял запрос (код 400)");
            case 413:
                return new ServiceException(ServiceException.Kind.OTHER, status, "сообщение слишком длинное для DeepL (код 413)");
            default:
                return new ServiceException(ServiceException.Kind.OTHER, status, status >= 500
                        ? "DeepL временно недоступен (код " + status + ")"
                        : "DeepL ответил кодом " + status);
        }
    }

    private static Map<String, Object> parseBody(String body) {
        try {
            return Json.parseObject(body);
        } catch (Json.JsonException exception) {
            throw new ServiceException(ServiceException.Kind.OTHER, 200, "непонятный ответ DeepL: " + exception.getMessage());
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
        throw new ServiceException(ServiceException.Kind.OTHER, 200, "в ответе DeepL нет перевода");
    }
}
