package io.github.jhonatasdefreitas.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** What the inventory tells about a reserved product: the name and the price from its catalog. */
public record ReservedItem(UUID productId, String productName, BigDecimal unitPrice) {

    public ReservedItem {
        Objects.requireNonNull(productId, "productId is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
    }
}
