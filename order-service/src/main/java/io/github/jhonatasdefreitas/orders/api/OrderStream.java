package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.OrderStatusChanged;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

@Component
class OrderStream {

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    // One thread writes to every browser. It keeps the updates in order and, more important,
    // a slow connection never holds the Kafka consumer or the request that changed the order.
    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("order-stream").daemon().factory());

    SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        subscribers.add(emitter);
        return emitter;
    }

    // After commit: a change that was rolled back must never reach the screen.
    @TransactionalEventListener
    void on(OrderStatusChanged event) {
        OrderSummary summary = OrderSummary.after(event.order(), event.change());
        broadcast(() -> SseEmitter.event().name("order-updated").data(summary, MediaType.APPLICATION_JSON));
    }

    @Scheduled(fixedRateString = "${app.stream.keep-alive}")
    void keepAlive() {
        broadcast(() -> SseEmitter.event().comment("keep-alive"));
    }

    private void broadcast(Supplier<SseEventBuilder> message) {
        sender.execute(() -> {
            for (SseEmitter subscriber : subscribers) {
                try {
                    subscriber.send(message.get());
                } catch (IOException | IllegalStateException e) {
                    // The browser went away. Nothing to complete: the container reports the broken connection.
                    subscribers.remove(subscriber);
                }
            }
        });
    }

    // On ContextClosedEvent and not on @PreDestroy alone: the web server waits for open requests
    // before the beans are destroyed, and these streams would hold the shutdown until it times out.
    @EventListener(ContextClosedEvent.class)
    void closeStreams() {
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    @PreDestroy
    void stopSender() {
        sender.shutdownNow();
    }
}
