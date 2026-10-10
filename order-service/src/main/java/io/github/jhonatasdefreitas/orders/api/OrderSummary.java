package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.Order;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import io.github.jhonatasdefreitas.orders.domain.StatusChange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderSummary(
        UUID id,
        String customerId,
        String customerName,
        OrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt) {

    static OrderSummary from(Order order) {
        return new OrderSummary(order.getId(), order.getCustomerId(), order.getCustomerName(), order.getStatus(),
                order.getTotalAmount(), order.getItemCount(), order.getCreatedAt(), order.getUpdatedAt());
    }

    /** The order as it was right after one specific change, even if it has moved on since. */
    static OrderSummary after(Order order, StatusChange change) {
        return new OrderSummary(order.getId(), order.getCustomerId(), order.getCustomerName(), change.status(),
                order.getTotalAmount(), order.getItemCount(), order.getCreatedAt(), change.occurredAt());
    }
}
