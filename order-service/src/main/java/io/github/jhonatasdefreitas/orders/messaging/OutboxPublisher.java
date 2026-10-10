package io.github.jhonatasdefreitas.orders.messaging;

import io.github.jhonatasdefreitas.orders.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final int batchSize;

    OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                    PlatformTransactionManager transactionManager, MeterRegistry meters,
                    @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        Gauge.builder("outbox.pending.events", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Events written to the outbox and not yet published")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
    void publishPending() {
        try {
            int published;
            do {
                published = transaction.execute(status -> publishBatch());
            } while (published == batchSize);
        } catch (RuntimeException e) {
            log.warn("Outbox batch not published, it stays pending for the next poll: {}",
                    NestedExceptionUtils.getMostSpecificCause(e).toString());
        }
    }

    // The rows stay locked until the broker has acknowledged the whole batch. If a send fails nothing is
    // marked, the batch goes out again on the next poll and the consumers drop what they had already seen.
    private int publishBatch() {
        List<OutboxEvent> batch = outbox.lockPending(batchSize);
        CompletableFuture<?>[] sends = batch.stream()
                .map(event -> kafka.send(event.toRecord()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).join();
        for (OutboxEvent event : batch) {
            event.markPublished();
            try (MDC.MDCCloseable ignored = MDC.putCloseable("orderId", event.getMessageKey())) {
                log.info("Published {}", event.getEventType());
            }
        }
        return batch.size();
    }
}
