package io.github.jhonatasdefreitas.orders.messaging;

final class Topics {

    static final String ORDERS = "orders.events";
    static final String INVENTORY = "inventory.events";
    static final String PAYMENTS = "payments.events";
    static final String DEAD_LETTER = "order-service.dlt";

    private Topics() {
    }
}
