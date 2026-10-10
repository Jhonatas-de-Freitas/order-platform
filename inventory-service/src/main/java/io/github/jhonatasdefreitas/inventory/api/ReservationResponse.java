package io.github.jhonatasdefreitas.inventory.api;

import io.github.jhonatasdefreitas.inventory.domain.Reservation;
import io.github.jhonatasdefreitas.inventory.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID orderId,
        ReservationStatus status,
        List<Item> items,
        Instant createdAt,
        Instant releasedAt) {

    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    static ReservationResponse from(Reservation reservation) {
        List<Item> items = reservation.getItems().stream()
                .map(item -> new Item(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
        return new ReservationResponse(reservation.getOrderId(), reservation.getStatus(), items,
                reservation.getCreatedAt(), reservation.getReleasedAt());
    }
}
