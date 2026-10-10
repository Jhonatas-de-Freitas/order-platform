package io.github.jhonatasdefreitas.orders.persistence;

import io.github.jhonatasdefreitas.orders.domain.Order;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // Stock and payment events of the same order come from different topics, so two consumers
    // can pick up the same order at once. The row lock makes them take turns.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(UUID id);

    @EntityGraph(attributePaths = {"items", "history"})
    Optional<Order> findWithItemsAndHistoryById(UUID id);
}
