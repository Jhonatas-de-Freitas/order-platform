package io.github.jhonatasdefreitas.payments.api;

import io.github.jhonatasdefreitas.payments.domain.Payment;
import io.github.jhonatasdefreitas.payments.domain.PaymentStatus;
import io.github.jhonatasdefreitas.payments.domain.RefusalReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        PaymentStatus status,
        RefusalReason reason,
        Instant createdAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus(),
                payment.getReason(), payment.getCreatedAt());
    }
}
