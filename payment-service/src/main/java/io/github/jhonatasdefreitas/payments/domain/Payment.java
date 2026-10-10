package io.github.jhonatasdefreitas.payments.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orderId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    private RefusalReason reason;

    private Instant createdAt;

    protected Payment() {
    }

    /** The simulated charge: an amount above the limit is refused, anything up to it is approved. */
    public static Payment charge(UUID orderId, BigDecimal amount, BigDecimal approvalLimit) {
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (payment.amount.compareTo(approvalLimit) > 0) {
            payment.status = PaymentStatus.REFUSED;
            payment.reason = RefusalReason.LIMIT_EXCEEDED;
        } else {
            payment.status = PaymentStatus.APPROVED;
        }
        // Milliseconds are what the event contract shows and what survives a round trip to the database.
        payment.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return payment;
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public RefusalReason getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
