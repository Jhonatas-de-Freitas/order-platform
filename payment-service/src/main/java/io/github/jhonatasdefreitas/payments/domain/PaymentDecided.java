package io.github.jhonatasdefreitas.payments.domain;

/** Published inside the transaction that stored the payment, approved or refused. */
public record PaymentDecided(Payment payment) {
}
