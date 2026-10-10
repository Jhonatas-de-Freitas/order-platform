package io.github.jhonatasdefreitas.inventory.domain;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.UUID;

/** Name and price are copied from the catalog when the stock is taken: a later price change does not reach the order. */
@Embeddable
public record ReservedItem(UUID productId, String productName, int quantity, BigDecimal unitPrice) {

    static ReservedItem taken(Product product, int quantity) {
        return new ReservedItem(product.getId(), product.getName(), quantity, product.getPrice());
    }

    BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
