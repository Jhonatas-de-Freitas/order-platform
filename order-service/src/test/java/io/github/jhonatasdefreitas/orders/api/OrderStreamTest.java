package io.github.jhonatasdefreitas.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class OrderStreamTest {

    private final OrderStream stream = new OrderStream();
    private final StuckBrowser stuck = new StuckBrowser();
    private final ReadingBrowser reading = new ReadingBrowser();

    @AfterEach
    void close() {
        stream.closeStreams();
    }

    @Test
    void aBrowserThatStopsReadingDoesNotHoldTheOthers() {
        stream.subscribe(stuck);
        stream.subscribe(reading);

        stream.keepAlive();
        eventually().untilTrue(stuck.writing);
        stream.keepAlive();
        stream.keepAlive();

        eventually().untilAsserted(() -> assertThat(reading.received).hasValue(3));
    }

    @Test
    void dropsABrowserThatFallsTooFarBehind() {
        stream.subscribe(stuck);
        stream.subscribe(reading);
        stream.keepAlive();
        eventually().untilTrue(stuck.writing);

        // One message is being written; these fill its queue, and the last one does not fit.
        for (int sent = 2; sent <= OrderStream.MAX_PENDING + 2; sent++) {
            stream.keepAlive();
            int expected = sent;
            eventually().untilAsserted(() -> assertThat(reading.received).hasValue(expected));
        }

        eventually().untilTrue(stuck.completed);
        assertThat(reading.completed).isFalse();
        stream.keepAlive();
        eventually().untilAsserted(() -> assertThat(reading.received).hasValue(OrderStream.MAX_PENDING + 3));
    }

    private static ConditionFactory eventually() {
        return await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10));
    }

    // A connection that stays open but takes nothing: the write blocks until the thread is interrupted.
    private static class StuckBrowser extends SseEmitter {

        final AtomicBoolean writing = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(SseEventBuilder message) {
            writing.set(true);
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void complete() {
            completed.set(true);
        }
    }

    private static class ReadingBrowser extends SseEmitter {

        final AtomicInteger received = new AtomicInteger();
        final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(SseEventBuilder message) {
            received.incrementAndGet();
        }

        @Override
        public void complete() {
            completed.set(true);
        }
    }
}
