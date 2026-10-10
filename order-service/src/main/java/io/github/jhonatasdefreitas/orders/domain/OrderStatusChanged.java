package io.github.jhonatasdefreitas.orders.domain;

/**
 * Published inside the transaction that changed the order. One transaction can publish two of these
 * for the same order, so listeners must read the status from {@code change}, not from the order.
 */
public record OrderStatusChanged(Order order, StatusChange change) {
}
