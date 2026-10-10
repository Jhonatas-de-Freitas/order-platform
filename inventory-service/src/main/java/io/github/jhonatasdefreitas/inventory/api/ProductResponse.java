package io.github.jhonatasdefreitas.inventory.api;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String name, String description, BigDecimal price, int availableQuantity) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getAvailableQuantity());
    }
}
