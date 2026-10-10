package io.github.jhonatasdefreitas.inventory.messaging;

import io.github.jhonatasdefreitas.inventory.domain.Rejection;
import io.github.jhonatasdefreitas.inventory.domain.RejectionReason;
import io.github.jhonatasdefreitas.inventory.domain.Reservation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payloads of what this service publishes on inventory.events. */
final class InventoryEvents {

    private InventoryEvents() {
    }

    record StockReserved(List<Item> items, BigDecimal totalAmount) {

        record Item(UUID productId, String productName, int quantity, BigDecimal unitPrice) {
        }

        static StockReserved from(Reservation reservation) {
            List<Item> items = reservation.getItems().stream()
                    .map(item -> new Item(item.productId(), item.productName(), item.quantity(), item.unitPrice()))
                    .toList();
            return new StockReserved(items, reservation.totalAmount());
        }
    }

    record StockRejected(RejectionReason reason, List<Item> unavailableItems) {

        record Item(UUID productId, int requestedQuantity, int availableQuantity) {
        }

        static StockRejected from(Rejection rejection) {
            List<Item> items = rejection.unavailableItems().stream()
                    .map(item -> new Item(item.productId(), item.requestedQuantity(), item.availableQuantity()))
                    .toList();
            return new StockRejected(rejection.reason(), items);
        }
    }

    record StockReleased(List<Item> items) {

        record Item(UUID productId, int quantity) {
        }

        static StockReleased from(Reservation reservation) {
            List<Item> items = reservation.getItems().stream()
                    .map(item -> new Item(item.productId(), item.quantity()))
                    .toList();
            return new StockReleased(items);
        }
    }
}
