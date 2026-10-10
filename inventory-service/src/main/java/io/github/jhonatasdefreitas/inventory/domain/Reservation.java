package io.github.jhonatasdefreitas.inventory.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orderId;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private Instant createdAt;
    private Instant releasedAt;

    @ElementCollection
    @CollectionTable(name = "reservation_items", joinColumns = @JoinColumn(name = "reservation_id"))
    @OrderColumn(name = "seq")
    private List<ReservedItem> items = new ArrayList<>();

    protected Reservation() {
    }

    /** Takes every item out of the stock. The caller has already made sure that all of them are there. */
    public static Reservation of(UUID orderId, List<RequestedItem> requested, Map<UUID, Product> products) {
        Reservation reservation = new Reservation();
        reservation.orderId = orderId;
        reservation.status = ReservationStatus.RESERVED;
        reservation.createdAt = now();
        for (RequestedItem item : requested) {
            Product product = products.get(item.productId());
            product.take(item.quantity());
            reservation.items.add(ReservedItem.taken(product, item.quantity()));
        }
        return reservation;
    }

    /** Puts the items back in the stock. A reservation gives its stock back only once. */
    void release(Map<UUID, Product> products) {
        if (!isActive()) {
            return;
        }
        items.forEach(item -> products.get(item.productId()).giveBack(item.quantity()));
        status = ReservationStatus.RELEASED;
        releasedAt = now();
    }

    public boolean isActive() {
        return status == ReservationStatus.RESERVED;
    }

    public BigDecimal totalAmount() {
        return items.stream().map(ReservedItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Milliseconds are what the event contract shows and what survives a round trip to the database.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public List<ReservedItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
