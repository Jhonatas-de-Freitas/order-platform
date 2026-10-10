package io.github.jhonatasdefreitas.payments.domain;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID orderId) {
        super("Payment for order " + orderId + " not found");
    }
}
