package io.github.jhonatasdefreitas.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");
    private static final BigDecimal LIMIT = new BigDecimal("5000.00");

    @Test
    void approvesPaymentBelowLimit() {
        Payment payment = Payment.charge(ORDER_ID, new BigDecimal("699.80"), LIMIT);

        assertThat(payment.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("699.80"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.isApproved()).isTrue();
        assertThat(payment.getReason()).isNull();
        assertThat(payment.getCreatedAt()).isNotNull();
    }

    @Test
    void refusesPaymentAboveLimit() {
        Payment payment = Payment.charge(ORDER_ID, new BigDecimal("7499.00"), LIMIT);

        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("7499.00"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUSED);
        assertThat(payment.isApproved()).isFalse();
        assertThat(payment.getReason()).isEqualTo(RefusalReason.LIMIT_EXCEEDED);
    }

    @Test
    void approvesPaymentOfExactlyTheLimit() {
        assertThat(Payment.charge(ORDER_ID, new BigDecimal("5000.00"), LIMIT).isApproved()).isTrue();
        assertThat(Payment.charge(ORDER_ID, new BigDecimal("5000.01"), LIMIT).isApproved()).isFalse();
    }

    @Test
    void theLimitIsWhateverWasConfigured() {
        BigDecimal lowLimit = new BigDecimal("100");

        assertThat(Payment.charge(ORDER_ID, new BigDecimal("100.00"), lowLimit).isApproved()).isTrue();
        assertThat(Payment.charge(ORDER_ID, new BigDecimal("129.90"), lowLimit).isApproved()).isFalse();
    }

    @Test
    void keepsTheAmountWithTwoDecimalPlaces() {
        assertThat(Payment.charge(ORDER_ID, new BigDecimal("699.8"), LIMIT).getAmount())
                .isEqualTo(new BigDecimal("699.80"));
        assertThat(Payment.charge(ORDER_ID, new BigDecimal("2199"), LIMIT).getAmount())
                .isEqualTo(new BigDecimal("2199.00"));
    }
}
