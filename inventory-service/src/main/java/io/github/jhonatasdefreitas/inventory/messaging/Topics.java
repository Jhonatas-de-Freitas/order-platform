package io.github.jhonatasdefreitas.inventory.messaging;

final class Topics {

    static final String ORDERS = "orders.events";
    static final String INVENTORY = "inventory.events";
    static final String DEAD_LETTER = "inventory-service.dlt";

    private Topics() {
    }
}
