package io.github.jhonatasdefreitas.inventory.persistence;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // The row locks make two orders for the same product take turns, so the second one sees what the
    // first one left. Always in id order: orders that share products lock them in the same sequence,
    // whatever the order of their items, and neither ends up holding a row the other is waiting for.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdForUpdate(Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(UUID id);
}
