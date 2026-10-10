package io.github.jhonatasdefreitas.inventory.domain;

import io.github.jhonatasdefreitas.inventory.persistence.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<Product> list() {
        return products.findAll(Sort.by("name", "id"));
    }

    @Transactional(readOnly = true)
    public Product get(UUID productId) {
        return products.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

    public Product create(String name, String description, BigDecimal price, int availableQuantity) {
        Product product = products.save(new Product(name, description, price, availableQuantity));
        log.info("Product {} created with {} in stock", product.getId(), availableQuantity);
        return product;
    }

    // No row lock needed: this replaces the balance instead of adding to it, so whatever order it ends up
    // in relative to a reservation that is running at the same time, the result is a valid one.
    public Product setStock(UUID productId, int availableQuantity) {
        Product product = get(productId);
        product.setAvailableQuantity(availableQuantity);
        log.info("Stock of product {} set to {}", productId, availableQuantity);
        return product;
    }
}
