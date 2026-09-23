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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Запросы к MyMemory на подставном сервере с ответами в формате настоящего. */
class MyMemoryClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Map<String, String>> lastQuery = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/get", exchange -> {
            this.requests.incrementAndGet();
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            this.lastQuery.set(query);
            byte[] body = this.responseBody.getBytes(StandardCharsets.UTF_8);
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

    private MyMemoryClient client(String email) {
        HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
        return new MyMemoryClient(email, this.baseUrl, http);
    }

    private static String answer(String translation, int status, boolean quotaFinished) {
        return "{\"responseData\":{\"translatedText\":" + Json.quote(translation) + ",\"match\":0.85},"
                + "\"quotaFinished\":" + quotaFinished + ",\"mtLangSupported\":null,\"responseDetails\":\"\","
                + "\"responseStatus\":" + status + ",\"responderId\":null,\"exception_code\":null,\"matches\":[]}";
    }

    private ServiceException failure(MyMemoryClient client) {
        CompletionException error = assertThrows(CompletionException.class,
                () -> client.translate("hello", Lang.EN, Lang.RU).join());
        return assertInstanceOf(ServiceException.class, error.getCause());
    }

    @Test
    void translatesWithoutKey() {
        this.responseBody = answer("Привет всем", 200, false);

        assertEquals("Привет всем", client("").translate("hi everyone", Lang.EN, Lang.RU).join());

        Map<String, String> query = this.lastQuery.get();
        assertEquals("hi everyone", query.get("q"));
        assertEquals("en|ru", query.get("langpair"));
        assertFalse(query.containsKey("de"), "без почты параметр de не отправляется");
    }

    @Test
    void emailRaisesTheLimit() {
        this.responseBody = answer("where is the spawn", 200, false);
        client("admin@example.com").translate("где спавн", Lang.RU, Lang.EN).join();
        assertEquals("ru|en", this.lastQuery.get().get("langpair"));
        assertEquals("admin@example.com", this.lastQuery.get().get("de"));
    }

    @Test
    void htmlCodesAreDecoded() {
        this.responseBody = answer("It&#39;s &quot;fine&quot; &amp; ok", 200, false);
        assertEquals("It's \"fine\" & ok", client("").translate("всё в порядке", Lang.RU, Lang.EN).join());
    }

    @Test
    void dailyLimitIsRecognised() {
        this.responseBody = answer("MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY.", 429, true);
        ServiceException quota = failure(client(""));
        assertEquals(ServiceException.Kind.QUOTA, quota.kind());
        assertTrue(quota.getMessage().contains("MyMemoryEmail"), quota.getMessage());
    }

    @Test
    void statusMayComeAsText() {
        this.responseBody = "{\"responseData\":{\"translatedText\":\"INVALID EMAIL PROVIDED\"},"
                + "\"responseDetails\":\"INVALID EMAIL PROVIDED\",\"responseStatus\":\"403\"}";
        assertEquals(ServiceException.Kind.KEY_REJECTED, failure(client("not-an-email")).kind());
    }

    @Test
    void regionBlockAndServerErrors() {
        this.status = 451;
        assertEquals(ServiceException.Kind.REGION_BLOCKED, failure(client("")).kind());
        this.status = 502;
        assertEquals(ServiceException.Kind.OTHER, failure(client("")).kind());
    }

    @Test
    void tooLongMessageIsNotSent() {
        String longText = "очень длинное сообщение ".repeat(30);
        CompletionException error = assertThrows(CompletionException.class,
                () -> client("").translate(longText, Lang.RU, Lang.EN).join());
        assertInstanceOf(ServiceException.class, error.getCause());
        assertEquals(0, this.requests.get());
    }
}
