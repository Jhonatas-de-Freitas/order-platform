package io.github.jhonatasdefreitas.inventory.domain;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("Product " + productId + " not found");
    }
}
