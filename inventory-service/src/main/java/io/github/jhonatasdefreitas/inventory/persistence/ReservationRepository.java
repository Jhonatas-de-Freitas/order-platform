package io.github.jhonatasdefreitas.inventory.persistence;

import io.github.jhonatasdefreitas.inventory.domain.Reservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    boolean existsByOrderId(UUID orderId);

    // Two cancellations of the same order must not both find the reservation active.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.orderId = :orderId")
    Optional<Reservation> findByOrderIdForUpdate(UUID orderId);

    @EntityGraph(attributePaths = "items")
    Optional<Reservation> findWithItemsByOrderId(UUID orderId);
}
