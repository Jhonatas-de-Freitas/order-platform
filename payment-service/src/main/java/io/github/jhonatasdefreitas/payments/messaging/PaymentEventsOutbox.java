package io.github.jhonatasdefreitas.payments.messaging;

import io.github.jhonatasdefreitas.payments.domain.Payment;
import io.github.jhonatasdefreitas.payments.domain.PaymentDecided;
import io.github.jhonatasdefreitas.payments.messaging.PaymentEvents.PaymentApproved;
import io.github.jhonatasdefreitas.payments.messaging.PaymentEvents.PaymentRefused;
import io.github.jhonatasdefreitas.payments.persistence.OutboxRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventsOutbox {

    private final OutboxRepository outbox;

    public PaymentEventsOutbox(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    // A plain listener runs in the caller's thread, so the event row is written by the same transaction
    // that stored the payment. MANDATORY makes it fail if someone ever stores a payment outside of one.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaymentDecided event) {
        Payment payment = event.payment();
        if (payment.isApproved()) {
            add("PaymentApproved", payment, PaymentApproved.from(payment));
        } else {
            add("PaymentRefused", payment, PaymentRefused.from(payment));
        }
    }

    private void add(String type, Payment payment, Object payload) {
        EventEnvelope envelope = EventEnvelope.of(type, payment.getOrderId(), payment.getCreatedAt(), payload);
        outbox.save(new OutboxEvent(Topics.PAYMENTS, envelope));
    }
}
