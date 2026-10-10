package io.github.jhonatasdefreitas.orders.domain;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Name and price are unknown until the inventory reserves the stock. */
@Embeddable
public record OrderItem(UUID productId, String productName, int quantity, BigDecimal unitPrice) {

    public static OrderItem requested(UUID productId, int quantity) {
        return new OrderItem(productId, null, quantity, null);
    }

    public OrderItem priced(String productName, BigDecimal unitPrice) {
        return new OrderItem(productId, productName, quantity, unitPrice.setScale(2, RoundingMode.HALF_UP));
    }
}
