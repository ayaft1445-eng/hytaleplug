package dev.hytalemodding.chattranslator.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Бесплатный переводчик MyMemory (api.mymemory.translated.net): ключ не нужен.
 *
 * Без почты можно перевести 5 000 символов в день, с почтой в настройке
 * MyMemoryEmail — 50 000. За один запрос — не больше 500 байт текста.
 */
public final class MyMemoryClient implements TranslationService {

    public static final String API = "https://api.mymemory.translated.net";

    /** Больше MyMemory за один запрос не принимает. */
    static final int MAX_QUERY_BYTES = 500;

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|quot|amp|lt|gt|apos);");

    private final String baseUrl;
    private final String email;
    private final HttpClient http;

    public MyMemoryClient(String email, HttpClient http) {
        this(email, API, http);
    }

    MyMemoryClient(String email, String baseUrl, HttpClient http) {
        this.email = email == null ? "" : email.trim();
        this.baseUrl = baseUrl;
        this.http = http;
    }

    @Override
    public String name() {
        return "MyMemory";
    }

    @Override
    public CompletableFuture<String> translate(String text, Lang from, Lang to) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            return CompletableFuture.failedFuture(new ServiceException(ServiceException.Kind.OTHER, 0,
                    "сообщение длиннее " + MAX_QUERY_BYTES + " байт, MyMemory такие не переводит"));
        }
        StringBuilder url = new StringBuilder(this.baseUrl)
                .append("/get?q=").append(encode(text))
                .append("&langpair=").append(encode(from.code() + "|" + to.code()));
        if (!this.email.isEmpty()) {
            url.append("&de=").append(encode(this.email));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(DeepLClient.REQUEST_TIMEOUT)
                .header("User-Agent", DeepLClient.USER_AGENT)
                .GET()
                .build();

        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parse(response.statusCode(), response.body()));
    }

    static String parse(int httpStatus, String body) {
        if (httpStatus == 451) {
            throw new ServiceException(ServiceException.Kind.REGION_BLOCKED, httpStatus,
                    "MyMemory отказал с кодом 451: он не обслуживает страну, где стоит сервер");
        }
        if (httpStatus == 429) {
            throw quota(httpStatus);
        }
        if (httpStatus != 200) {
            throw new ServiceException(ServiceException.Kind.OTHER, httpStatus, httpStatus >= 500
                    ? "MyMemory временно недоступен (код " + httpStatus + ")"
                    : "MyMemory ответил кодом " + httpStatus);
        }

        Map<String, Object> json;
        try {
            json = Json.parseObject(body);
        } catch (Json.JsonException exception) {
            throw new ServiceException(ServiceException.Kind.OTHER, httpStatus, "непонятный ответ MyMemory: " + exception.getMessage());
        }
        int status = responseStatus(json.get("responseStatus"));
        String details = Json.getString(json, "responseDetails", "").trim();
        String translation = null;
        Object data = json.get("responseData");
        if (data instanceof Map) {
            Object text = ((Map<?, ?>) data).get("translatedText");
            if (text instanceof String) {
                translation = (String) text;
            }
        }

        if (Boolean.TRUE.equals(json.get("quotaFinished")) || status == 429
                || (translation != null && translation.startsWith("MYMEMORY WARNING"))) {
            throw quota(status);
        }
        if (status == 403 && details.toUpperCase(Locale.ROOT).contains("EMAIL")) {
            throw new ServiceException(ServiceException.Kind.KEY_REJECTED, status,
                    "MyMemory не принял почту из MyMemoryEmail в config.json");
        }
        if (status != 200 || translation == null || translation.isBlank()) {
            throw new ServiceException(ServiceException.Kind.OTHER, status,
                    "MyMemory не перевёл сообщение" + (details.isEmpty() ? "" : ": " + details));
        }
        return unescape(translation);
    }

    private static ServiceException quota(int status) {
        return new ServiceException(ServiceException.Kind.QUOTA, status,
                "исчерпан дневной лимит MyMemory (без почты 5 000 символов в день, с почтой в MyMemoryEmail — 50 000)");
    }

    /** MyMemory присылает код ответа то числом, то строкой. */
    private static int responseStatus(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** MyMemory иногда присылает апострофы и кавычки как HTML-коды: {@code It&#39;s}. */
    static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            switch (entity) {
                case "quot":
                    replacement = "\"";
                    break;
                case "amp":
                    replacement = "&";
                    break;
                case "lt":
                    replacement = "<";
                    break;
                case "gt":
                    replacement = ">";
                    break;
                case "apos":
                    replacement = "'";
                    break;
                default:
                    try {
                        int codePoint = entity.startsWith("#x") || entity.startsWith("#X")
                                ? Integer.parseInt(entity.substring(2), 16)
                                : Integer.parseInt(entity.substring(1));
                        replacement = new String(Character.toChars(codePoint));
                    } catch (IllegalArgumentException invalid) {
                        replacement = matcher.group();
                    }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
