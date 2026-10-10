package io.github.jhonatasdefreitas.orders.domain;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class OrderMetrics {

    private final MeterRegistry meters;

    OrderMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    // After commit, so a rolled back change is not counted.
    @TransactionalEventListener
    void on(OrderStatusChanged event) {
        StatusChange change = event.change();
        String status = change.status().name();
        meters.counter("orders.status.changes", "status", status).increment();
        if (change.status().isFinal()) {
            Duration sinceCreated = Duration.between(event.order().getCreatedAt(), change.occurredAt());
            meters.timer("orders.time.to.final", "status", status).record(sinceCreated);
        }
    }
}
