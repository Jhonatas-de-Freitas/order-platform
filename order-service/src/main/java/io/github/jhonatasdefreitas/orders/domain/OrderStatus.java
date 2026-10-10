package io.github.jhonatasdefreitas.orders.domain;

public enum OrderStatus {
    PENDING,
    STOCK_RESERVED,
    CONFIRMED,
    CANCELLED;

    public boolean isFinal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}
