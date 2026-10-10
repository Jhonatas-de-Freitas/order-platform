package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.CancellationReason;
import io.github.jhonatasdefreitas.orders.domain.Order;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        String customerName,
        String customerEmail,
        OrderStatus status,
        BigDecimal totalAmount,
        CancellationReason cancellationReason,
        String cancellationDetail,
        List<Item> items,
        List<HistoryEntry> history,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(UUID productId, String productName, int quantity, BigDecimal unitPrice) {
    }

    public record HistoryEntry(OrderStatus status, Instant occurredAt, String detail) {
    }

    static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(item.productId(), item.productName(), item.quantity(), item.unitPrice()))
                .toList();
        List<HistoryEntry> history = order.getHistory().stream()
                .map(change -> new HistoryEntry(change.status(), change.occurredAt(), change.detail()))
                .toList();
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getCustomerName(),
                order.getCustomerEmail(), order.getStatus(), order.getTotalAmount(), order.getCancellationReason(),
                order.getCancellationDetail(), items, history, order.getCreatedAt(), order.getUpdatedAt());
    }
}
