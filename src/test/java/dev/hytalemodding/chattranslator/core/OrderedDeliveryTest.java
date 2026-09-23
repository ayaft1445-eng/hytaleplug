package dev.hytalemodding.chattranslator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderedDeliveryTest {

    @Test
    void laterQuickTranslationWaitsForEarlierSlowOne() {
        OrderedDelivery delivery = new OrderedDelivery(Log.SILENT);
        List<String> shown = new CopyOnWriteArrayList<>();

        CompletableFuture<String> slow = new CompletableFuture<>();
        delivery.enqueue(slow, shown::add, error -> shown.add("ошибка"));
        delivery.enqueue(CompletableFuture.completedFuture("второе"), shown::add, error -> shown.add("ошибка"));
        assertEquals(List.of(), shown, "второе сообщение не обгоняет первое");

        slow.complete("первое");
        assertEquals(List.of("первое", "второе"), shown);
    }

    @Test
    void failedTranslationShowsOriginalAndQueueGoesOn() {
        OrderedDelivery delivery = new OrderedDelivery(Log.SILENT);
        List<String> shown = new CopyOnWriteArrayList<>();

        delivery.enqueue(CompletableFuture.<String>failedFuture(new IllegalStateException("нет сети")),
                shown::add, error -> shown.add("как есть: " + error.getMessage()));
        delivery.enqueue(CompletableFuture.completedFuture("дальше"), shown::add, error -> shown.add("ошибка"));

        assertEquals(List.of("как есть: нет сети", "дальше"), shown);
    }

    @Test
    void brokenRecipientDoesNotStopTheQueue() {
        OrderedDelivery delivery = new OrderedDelivery(Log.SILENT);
        List<String> shown = new CopyOnWriteArrayList<>();

        delivery.enqueue(CompletableFuture.completedFuture("x"), value -> {
            throw new IllegalStateException("игрок вышел");
        }, error -> shown.add("ошибка"));
        delivery.enqueue(CompletableFuture.completedFuture("после"), shown::add, error -> shown.add("ошибка"));

        assertEquals(List.of("после"), shown);
    }

    @Test
    void hangingTranslationTimesOut() throws Exception {
        OrderedDelivery delivery = new OrderedDelivery(Log.SILENT, 100);
        List<String> shown = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        delivery.enqueue(new CompletableFuture<String>(), shown::add,
                error -> shown.add(error instanceof TimeoutException ? "без перевода" : "ошибка"));
        delivery.enqueue(CompletableFuture.completedFuture("следующее"), value -> {
            shown.add(value);
            done.complete(null);
        }, error -> shown.add("ошибка"));

        done.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("без перевода", "следующее"), shown);
    }
}
