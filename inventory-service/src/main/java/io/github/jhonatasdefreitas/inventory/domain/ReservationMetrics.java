package io.github.jhonatasdefreitas.inventory.domain;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// After commit, so a reservation that was rolled back is not counted.
@Component
class ReservationMetrics {

    private final MeterRegistry meters;

    ReservationMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener
    void on(ReservationCreated event) {
        count("reserved");
    }

    @TransactionalEventListener
    void on(ReservationRejected event) {
        count("rejected");
    }

    @TransactionalEventListener
    void on(ReservationReleased event) {
        count("released");
    }

    private void count(String outcome) {
        meters.counter("stock.reservations", "outcome", outcome).increment();
    }
}
