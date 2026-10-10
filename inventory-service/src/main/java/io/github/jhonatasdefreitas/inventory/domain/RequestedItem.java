package io.github.jhonatasdefreitas.inventory.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RequestedItem(UUID productId, int quantity) {

    /**
     * One line per product, with the quantities added up. An order is not supposed to repeat a product,
     * but if one does, checking each line on its own would let through more than the stock has.
     */
    static List<RequestedItem> perProduct(List<RequestedItem> items) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.merge(item.productId(), item.quantity(), Integer::sum));
        return quantities.entrySet().stream()
                .map(entry -> new RequestedItem(entry.getKey(), entry.getValue()))
                .toList();
    }
}
