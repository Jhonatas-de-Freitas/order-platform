package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.OrderStatusChanged;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
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

    // A browser this far behind has stopped reading. It is dropped instead of piling up messages.
    static final int MAX_PENDING = 100;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ThreadFactory writers = Thread.ofPlatform().name("order-stream-", 1).daemon().factory();

    SseEmitter subscribe() {
        return subscribe(new SseEmitter(0L));
    }

    SseEmitter subscribe(SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(emitter);
        emitter.onCompletion(subscriber::stop);
        emitter.onError(error -> subscriber.stop());
        subscribers.add(subscriber);
        subscriber.writer.start();
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
        subscribers.forEach(subscriber -> subscriber.offer(message));
    }

    // On ContextClosedEvent and not on @PreDestroy: the web server waits for open requests before
    // the beans are destroyed, and these streams would hold the shutdown until it times out.
    @EventListener(ContextClosedEvent.class)
    void closeStreams() {
        subscribers.forEach(Subscriber::stop);
    }

    // Each browser has its own queue and its own thread writing to it. A connection that stops reading
    // blocks only its own writes: never the others, the Kafka consumer or the request that changed the order.
    private final class Subscriber {

        private final SseEmitter emitter;
        private final BlockingQueue<Supplier<SseEventBuilder>> pending = new ArrayBlockingQueue<>(MAX_PENDING);
        private final Thread writer;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
            this.writer = writers.newThread(this::write);
        }

        void offer(Supplier<SseEventBuilder> message) {
            if (!pending.offer(message)) {
                stop();
            }
        }

        void stop() {
            subscribers.remove(this);
            writer.interrupt();
        }

        private void write() {
            try {
                while (true) {
                    emitter.send(pending.take().get());
                }
            } catch (InterruptedException e) {
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                // The browser went away. Nothing to complete: the container reports the broken connection.
                subscribers.remove(this);
            }
        }
    }
}
