package io.github.jhonatasdefreitas.inventory.messaging;

import io.github.jhonatasdefreitas.inventory.domain.Reservation;
import io.github.jhonatasdefreitas.inventory.domain.ReservationCreated;
import io.github.jhonatasdefreitas.inventory.domain.ReservationRejected;
import io.github.jhonatasdefreitas.inventory.domain.ReservationReleased;
import io.github.jhonatasdefreitas.inventory.messaging.InventoryEvents.StockRejected;
import io.github.jhonatasdefreitas.inventory.messaging.InventoryEvents.StockReleased;
import io.github.jhonatasdefreitas.inventory.messaging.InventoryEvents.StockReserved;
import io.github.jhonatasdefreitas.inventory.persistence.OutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Plain listeners run in the caller's thread, so the event row is written by the same transaction that
// changed the stock. MANDATORY makes them fail if someone ever changes the stock outside of one.
@Component
public class InventoryEventsOutbox {

    private final OutboxRepository outbox;

    public InventoryEventsOutbox(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ReservationCreated event) {
        Reservation reservation = event.reservation();
        add("StockReserved", reservation.getOrderId(), reservation.getCreatedAt(), StockReserved.from(reservation));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ReservationRejected event) {
        add("StockRejected", event.orderId(), event.occurredAt(), StockRejected.from(event.rejection()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ReservationReleased event) {
        Reservation reservation = event.reservation();
        add("StockReleased", reservation.getOrderId(), reservation.getReleasedAt(), StockReleased.from(reservation));
    }

    private void add(String type, UUID orderId, Instant occurredAt, Object payload) {
        outbox.save(new OutboxEvent(Topics.INVENTORY, EventEnvelope.of(type, orderId, occurredAt, payload)));
    }
}
