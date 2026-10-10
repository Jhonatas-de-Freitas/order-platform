package io.github.jhonatasdefreitas.payments.domain;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// After commit, so a payment that was rolled back is not counted.
@Component
class PaymentMetrics {

    private final MeterRegistry meters;

    PaymentMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener
    void on(PaymentDecided event) {
        String outcome = event.payment().isApproved() ? "approved" : "refused";
        meters.counter("payments", "outcome", outcome).increment();
    }
}
