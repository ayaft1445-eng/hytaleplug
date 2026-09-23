package dev.hytalemodding.chattranslator.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Запросы к DeepL на подставном сервере, который отвечает так же, как настоящий. */
class DeepLClientTest {

    private static final String KEY = "00000000-0000-0000-0000-000000000000:fx";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/v2/", exchange -> {
            this.lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            this.lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            this.lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = this.responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(this.status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        this.server.start();
        this.baseUrl = "http://" + this.server.getAddress().getHostString() + ":" + this.server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    private DeepLClient client() {
        HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
        return new DeepLClient(KEY, this.baseUrl, http);
    }

    @Test
    void translatesWithKeyInHeader() throws Exception {
        this.responseBody = "{\"translations\":[{\"detected_source_language\":\"RU\",\"text\":\"Hi everyone\"}]}";

        String translation = client().translate("Привет всем", Lang.RU, Lang.EN).join();

        assertEquals("Hi everyone", translation);
        assertEquals("DeepL-Auth-Key " + KEY, this.lastAuthorization.get());
        assertTrue(this.lastContentType.get().startsWith("application/json"));
        Map<String, Object> request = Json.parseObject(this.lastBody.get());
        assertEquals(List.of("Привет всем"), request.get("text"));
        assertEquals("RU", request.get("source_lang"));
        assertEquals("EN-US", request.get("target_lang"));
        assertEquals(Boolean.TRUE, request.get("preserve_formatting"));
        assertEquals("prefer_less", request.get("formality"));
    }

    @Test
    void englishToRussianUsesPlainRuTarget() throws Exception {
        this.responseBody = "{\"translations\":[{\"text\":\"где спавн?\"}]}";
        assertEquals("где спавн?", client().translate("where is spawn?", Lang.EN, Lang.RU).join());
        Map<String, Object> request = Json.parseObject(this.lastBody.get());
        assertEquals("EN", request.get("source_lang"));
        assertEquals("RU", request.get("target_lang"));
    }

    private ServiceException failure() {
        CompletionException error = assertThrows(CompletionException.class,
                () -> client().translate("hello", Lang.EN, Lang.RU).join());
        return assertInstanceOf(ServiceException.class, error.getCause());
    }

    @Test
    void errorCodesBecomeReadableMessages() {
        this.status = 403;
        ServiceException rejected = failure();
        assertEquals(ServiceException.Kind.KEY_REJECTED, rejected.kind());
        assertEquals(403, rejected.status());
        assertTrue(rejected.getMessage().contains("ключ"), rejected.getMessage());

        this.status = 456;
        ServiceException quota = failure();
        assertEquals(ServiceException.Kind.QUOTA, quota.kind());
        assertTrue(quota.getMessage().contains("лимит"), quota.getMessage());

        this.status = 503;
        assertEquals(ServiceException.Kind.OTHER, failure().kind());
    }

    @Test
    void regionBlockIsRecognised() {
        // Такой ответ DeepL прислал живому серверу: сервис не работает в стране, где тот стоит.
        this.status = 451;
        ServiceException blocked = failure();
        assertEquals(ServiceException.Kind.REGION_BLOCKED, blocked.kind());
        assertTrue(blocked.getMessage().contains("451"), blocked.getMessage());

        CompletionException usage = assertThrows(CompletionException.class, () -> client().usage().join());
        assertEquals(ServiceException.Kind.REGION_BLOCKED, assertInstanceOf(ServiceException.class, usage.getCause()).kind());
    }

    @Test
    void brokenResponseIsAnError() {
        this.responseBody = "{\"message\":\"no translations here\"}";
        assertEquals(ServiceException.Kind.OTHER, failure().kind());
    }

    @Test
    void usage() {
        this.responseBody = "{\"character_count\":12345,\"character_limit\":500000}";
        DeepLClient.Usage usage = client().usage().join();
        assertEquals(12345, usage.used());
        assertEquals(500000, usage.limit());
        assertEquals("DeepL-Auth-Key " + KEY, this.lastAuthorization.get());
    }

    @Test
    void nameIsShownInConsole() {
        assertEquals("DeepL", client().name());
    }

    @Test
    void endpointDependsOnKey() {
        assertEquals(DeepLClient.FREE_API, DeepLClient.baseUrlFor("abc:fx"));
        assertEquals(DeepLClient.FREE_API, DeepLClient.baseUrlFor(" abc:fx \n"));
        assertEquals(DeepLClient.PRO_API, DeepLClient.baseUrlFor("abc"));
    }
}
