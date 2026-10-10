package io.github.jhonatasdefreitas.inventory.messaging;

import io.github.jhonatasdefreitas.inventory.domain.RequestedItem;
import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import io.github.jhonatasdefreitas.inventory.persistence.ProcessedEvents;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final ProcessedEvents processedEvents;
    private final ReservationService reservations;

    public EventHandler(ProcessedEvents processedEvents, ReservationService reservations) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
    }

    // One transaction for the "already seen" mark and for the effect on the stock:
    // if the effect fails, the mark is rolled back too and the retry starts clean.
    @Transactional
    public void handle(EventEnvelope event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.info("Skipping {} {}: already processed", event.type(), event.eventId());
            return;
        }
        UUID orderId = event.orderId();
        switch (event.type()) {
            case "OrderCreated" -> reservations.reserve(orderId, event.payloadAs(OrderCreated.class).requestedItems());
            // Whatever the reason of the cancellation, the stock goes back if the order still holds any.
            case "OrderCancelled" -> reservations.release(orderId);
            default -> log.debug("Ignoring {}: nothing to do for this type", event.type());
        }
    }

    // Only the fields this service uses. Anything else in the payload is ignored.

    record OrderCreated(List<Item> items) {

        OrderCreated {
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("items is required");
            }
        }

        record Item(UUID productId, int quantity) {

            Item {
                Objects.requireNonNull(productId, "productId is required");
                if (quantity < 1) {
                    throw new IllegalArgumentException("quantity must be at least 1");
                }
            }
        }

        List<RequestedItem> requestedItems() {
            return items.stream().map(item -> new RequestedItem(item.productId(), item.quantity())).toList();
        }
    }
}
