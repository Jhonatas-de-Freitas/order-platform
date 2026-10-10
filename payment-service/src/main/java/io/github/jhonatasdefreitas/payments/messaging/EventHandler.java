package io.github.jhonatasdefreitas.payments.messaging;

import io.github.jhonatasdefreitas.payments.domain.PaymentService;
import io.github.jhonatasdefreitas.payments.persistence.ProcessedEvents;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final ProcessedEvents processedEvents;
    private final PaymentService payments;

    public EventHandler(ProcessedEvents processedEvents, PaymentService payments) {
        this.processedEvents = processedEvents;
        this.payments = payments;
    }

    // One transaction for the "already seen" mark and for the payment:
    // if the charge fails, the mark is rolled back too and the retry starts clean.
    @Transactional
    public void handle(EventEnvelope event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.info("Skipping {} {}: already processed", event.type(), event.eventId());
            return;
        }
        switch (event.type()) {
            case "StockReserved" -> payments.charge(event.orderId(), event.payloadAs(StockReserved.class).totalAmount());
            default -> log.debug("Ignoring {}: nothing to do for this type", event.type());
        }
    }

    // Only the field this service uses. Anything else in the payload is ignored.

    record StockReserved(BigDecimal totalAmount) {

        StockReserved {
            if (totalAmount == null || totalAmount.signum() <= 0) {
                throw new IllegalArgumentException("totalAmount must be greater than zero");
            }
        }
    }
}
