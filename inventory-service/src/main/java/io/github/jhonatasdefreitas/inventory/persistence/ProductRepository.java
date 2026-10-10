package io.github.jhonatasdefreitas.inventory.persistence;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
}
