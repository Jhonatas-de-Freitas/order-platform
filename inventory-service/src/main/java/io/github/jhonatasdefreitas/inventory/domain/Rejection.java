package io.github.jhonatasdefreitas.inventory.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Why an order cannot be reserved, with every item that is in the way. */
public record Rejection(RejectionReason reason, List<UnavailableItem> unavailableItems) {

    /**
     * Looks at the whole order before anything is taken, which is what makes the reservation all or nothing.
     * A product that does not exist wins over one that is short: that order can never be reserved.
     *
     * @return empty when every item can be taken
     */
    static Optional<Rejection> of(List<RequestedItem> requested, Map<UUID, Product> products) {
        List<UnavailableItem> unknown = requested.stream()
                .filter(item -> !products.containsKey(item.productId()))
                .map(item -> new UnavailableItem(item.productId(), item.quantity(), 0))
                .toList();
        if (!unknown.isEmpty()) {
            return Optional.of(new Rejection(RejectionReason.PRODUCT_NOT_FOUND, unknown));
        }
        List<UnavailableItem> shortOnStock = requested.stream()
                .filter(item -> !products.get(item.productId()).has(item.quantity()))
                .map(item -> new UnavailableItem(item.productId(), item.quantity(),
                        products.get(item.productId()).getAvailableQuantity()))
                .toList();
        if (!shortOnStock.isEmpty()) {
            return Optional.of(new Rejection(RejectionReason.INSUFFICIENT_STOCK, shortOnStock));
        }
        return Optional.empty();
    }
}
