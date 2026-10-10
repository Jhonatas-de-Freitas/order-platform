package io.github.jhonatasdefreitas.payments.messaging;

final class Topics {

    static final String INVENTORY = "inventory.events";
    static final String PAYMENTS = "payments.events";
    static final String DEAD_LETTER = "payment-service.dlt";

    private Topics() {
    }
}
