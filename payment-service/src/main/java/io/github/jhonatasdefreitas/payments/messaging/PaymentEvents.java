package io.github.jhonatasdefreitas.payments.messaging;

import io.github.jhonatasdefreitas.payments.domain.Payment;
import io.github.jhonatasdefreitas.payments.domain.RefusalReason;
import java.math.BigDecimal;
import java.util.UUID;

/** Payloads of what this service publishes on payments.events. */
final class PaymentEvents {

    private PaymentEvents() {
    }

    record PaymentApproved(UUID paymentId, BigDecimal amount) {

        static PaymentApproved from(Payment payment) {
            return new PaymentApproved(payment.getId(), payment.getAmount());
        }
    }

    record PaymentRefused(UUID paymentId, BigDecimal amount, RefusalReason reason) {

        static PaymentRefused from(Payment payment) {
            return new PaymentRefused(payment.getId(), payment.getAmount(), payment.getReason());
        }
    }
}
