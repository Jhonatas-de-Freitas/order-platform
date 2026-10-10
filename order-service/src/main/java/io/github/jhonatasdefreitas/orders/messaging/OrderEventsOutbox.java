package io.github.jhonatasdefreitas.orders.messaging;

import io.github.jhonatasdefreitas.orders.domain.Order;
import io.github.jhonatasdefreitas.orders.domain.OrderStatusChanged;
import io.github.jhonatasdefreitas.orders.messaging.OrderEvents.OrderCancelled;
import io.github.jhonatasdefreitas.orders.messaging.OrderEvents.OrderConfirmed;
import io.github.jhonatasdefreitas.orders.messaging.OrderEvents.OrderCreated;
import io.github.jhonatasdefreitas.orders.persistence.OutboxRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventsOutbox {

    private final OutboxRepository outbox;

    public OrderEventsOutbox(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    // A plain listener runs in the caller's thread, so the event row is written by the same transaction
    // that changed the order. MANDATORY makes it fail if someone ever changes an order outside of one.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(OrderStatusChanged event) {
        Order order = event.order();
        switch (event.change().status()) {
            case PENDING -> add("OrderCreated", event, OrderCreated.from(order));
            case CONFIRMED -> add("OrderConfirmed", event, OrderConfirmed.from(order));
            case CANCELLED -> add("OrderCancelled", event, OrderCancelled.from(order));
            case STOCK_RESERVED -> {
                // A step of this service only. The contract has no event for it.
            }
        }
    }

    private void add(String type, OrderStatusChanged event, Object payload) {
        EventEnvelope envelope = EventEnvelope.of(type, event.order().getId(), event.change().occurredAt(), payload);
        outbox.save(new OutboxEvent(Topics.ORDERS, envelope));
    }
}
