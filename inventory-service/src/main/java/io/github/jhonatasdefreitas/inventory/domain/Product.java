package io.github.jhonatasdefreitas.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
    private String description;
    private BigDecimal price;
    private int availableQuantity;

    protected Product() {
    }

    public Product(String name, String description, BigDecimal price, int availableQuantity) {
        this.name = name;
        this.description = description;
        this.price = price.setScale(2, RoundingMode.HALF_UP);
        setAvailableQuantity(availableQuantity);
    }

    public boolean has(int quantity) {
        return quantity <= availableQuantity;
    }

    public void take(int quantity) {
        if (!has(quantity)) {
            throw new IllegalStateException(
                    "Cannot take %d of product %s: only %d available".formatted(quantity, id, availableQuantity));
        }
        availableQuantity -= quantity;
    }

    public void giveBack(int quantity) {
        availableQuantity += quantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity cannot be negative");
        }
        this.availableQuantity = availableQuantity;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
